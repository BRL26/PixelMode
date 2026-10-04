package com.pixelmode.app.root

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.pixelmode.app.data.AppStatus
import com.pixelmode.app.data.ModePrefs
import com.pixelmode.app.model.ColorMode
import com.pixelmode.app.model.ModeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Applies / restores the display-wide color mode, following the fallback order required by
 * the spec:
 *
 *  (a) root → SurfaceFlinger color matrix (`service call SurfaceFlinger 1015 …`)
 *  (b) root (or an app-held WRITE_SECURE_SETTINGS grant) → Settings.Secure daltonizer /
 *      color-inversion keys — AOSP's own grayscale path, but **not** a hard threshold
 *  (c) neither works → report it honestly in the UI and keep the closest result on screen.
 */
object ColorEngine {

    const val METHOD_SF = "SurfaceFlinger color matrix (service call SurfaceFlinger 1015)"
    const val METHOD_SETTINGS = "Settings.Secure daltonizer grayscale (accessibility key)"

    data class Outcome(
        val ok: Boolean,
        val method: String,
        val warning: String?,
        val log: String,
    )

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Apply the color part of [state]. Safe to call repeatedly; blocking — run off the main thread. */
    suspend fun apply(context: Context, state: ModeState): Outcome {
        if (state.colorMode == ColorMode.OFF) {
            restore(context, state, full = false)
            return Outcome(true, "— (normal colors)", null, "color mode off → identity matrix")
        }

        val root = Root.evaluate()
        val canWrite = canWriteSecureSettings(context)

        // (a) Preferred: root + SurfaceFlinger color matrix -------------------------------
        if (root == Root.Status.GRANTED) {
            val matrix = DisplayCommands.matrixFor(state)
            if (matrix != null) {
                val cmds = buildList {
                    // Drop our daltonizer fallback first so the two paths never stack.
                    if (state.fallbackActive) add(DisplayCommands.SETTINGS_GRAYSCALE_OFF)
                    add(DisplayCommands.sfMatrixCommand(matrix))
                }
                val r = withContext(Dispatchers.IO) { Root.execBlocking(cmds) }
                if (r != null && r.isSuccess) {
                    ModePrefs.update { it.copy(fallbackActive = false, matrixDirty = true) }
                    return Outcome(
                        ok = true,
                        method = METHOD_SF,
                        warning = warningFor(state, METHOD_SF),
                        log = "SF 1015 matrix applied (root), exit=${r.code}",
                    )
                }
                AppStatus.log(
                    "SF 1015 rejected: " + ((r?.err ?: emptyList()) + (r?.out ?: emptyList()))
                        .joinToString(" ").ifEmpty { "no output, exit=${r?.code}" },
                )
            }
        }

        // (b) Fallback: Settings.Secure daltonizer (real grayscale, no hard threshold) ----
        if (root == Root.Status.GRANTED || canWrite) {
            val ok = if (root == Root.Status.GRANTED) {
                val r = withContext(Dispatchers.IO) {
                    Root.execBlocking(listOf(DisplayCommands.SETTINGS_GRAYSCALE_ON))
                }
                r != null && r.isSuccess
            } else {
                writeSettingsDirect(context, grayscaleOn = true, inversionOff = false)
            }
            if (ok) {
                ModePrefs.update { it.copy(fallbackActive = true, matrixDirty = true) }
                return Outcome(
                    ok = true,
                    method = METHOD_SETTINGS,
                    warning = warningFor(state, METHOD_SETTINGS),
                    log = "daltonizer grayscale fallback applied (root=$root, canWrite=$canWrite)",
                )
            }
        }

        // (c) Nothing worked — say so and keep whatever is on screen right now. -----------
        return Outcome(
            ok = false,
            method = "none — colors unchanged",
            warning = when (state.colorMode) {
                ColorMode.ONE_BIT -> "CLEAN THRESHOLD NOT AVAILABLE. Root was denied and " +
                    "WRITE_SECURE_SETTINGS is not granted, so PixelMode cannot touch the display " +
                    "colors at all. Closest result: whatever is currently on screen. Grant root " +
                    "(or run the adb grant command) and tap Re-check."
                else -> "Root denied and WRITE_SECURE_SETTINGS not granted — colors were left " +
                    "untouched. Grayscale needs one of the two."
            },
            log = "no apply method available (root=$root, canWrite=$canWrite)",
        )
    }

    /**
     * Restore normal colors. Never triggers a root prompt by itself (it only uses an already
     * granted/cached root shell, or the WRITE_SECURE_SETTINGS permission held by the app).
     */
    suspend fun restore(context: Context, state: ModeState, full: Boolean): Boolean {
        val mustReset = full || state.matrixDirty || state.fallbackActive
        if (!mustReset) return true

        var restored = false
        if (Root.isGranted || state.rootEverGranted) {
            val cmds = DisplayCommands.resetCommands(fallback = state.fallbackActive, full = full)
            val r = withContext(Dispatchers.IO) { Root.execBlocking(cmds) }
            restored = r != null && r.isSuccess
        }
        if (!restored && (state.fallbackActive || full)) {
            if (canWriteSecureSettings(context)) {
                restored = writeSettingsDirect(context, grayscaleOn = false, inversionOff = full)
            }
        }
        if (restored) {
            ModePrefs.update { it.copy(fallbackActive = false, matrixDirty = false) }
        }
        return restored
    }

    /** Honest, method-aware explanation (requirement (c)). */
    private fun warningFor(state: ModeState, method: String): String? {
        if (state.colorMode != ColorMode.ONE_BIT) return null
        return if (method == METHOD_SF) {
            val band = String.format(Locale.US, "%.3f", 1f / DisplayCommands.ONE_BIT_GAIN)
            "1-bit runs as a high-gain threshold (gain ${DisplayCommands.ONE_BIT_GAIN}×): pixels " +
                "settle on pure black/white except inside a ±$band luma band around the slider " +
                "point. A linear color matrix cannot be a perfect step function, so antialiased " +
                "edges can keep a thin gray fringe — adjust the threshold slider to taste."
        } else {
            "CLEAN THRESHOLD NOT AVAILABLE. The SurfaceFlinger matrix path (root) did not run, " +
                "and the Settings.Secure daltonizer fallback can only produce grayscale — no " +
                "linear key can hard-cut luma. Closest result kept: system grayscale (all tones " +
                "gray, no black/white separation). Grant root for the real 1-bit mode."
        }
    }

    /**
     * Rootless WRITE_SECURE_SETTINGS path — no shell, no root prompt.
     * Keys are the hidden Settings.Secure names AOSP's ColorDisplayService observes.
     */
    private fun writeSettingsDirect(
        context: Context,
        grayscaleOn: Boolean,
        inversionOff: Boolean,
    ): Boolean = try {
        val cr = context.contentResolver
        if (grayscaleOn) {
            // 0 == AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY → real grayscale matrix
            Settings.Secure.putInt(cr, DisplayCommands.KEY_DALTONIZER, 0)
            Settings.Secure.putInt(cr, DisplayCommands.KEY_DALTONIZER_ENABLED, 1)
        } else {
            Settings.Secure.putInt(cr, DisplayCommands.KEY_DALTONIZER_ENABLED, 0)
        }
        if (inversionOff) {
            Settings.Secure.putInt(cr, DisplayCommands.KEY_INVERSION_ENABLED, 0)
        }
        true
    } catch (t: Throwable) {
        false
    }
}
