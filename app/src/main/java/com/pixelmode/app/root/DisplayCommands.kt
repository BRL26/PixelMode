package com.pixelmode.app.root

import com.pixelmode.app.model.ModeState
import java.util.Locale

/**
 * Builders for the shell commands PixelMode uses to change how the whole screen renders.
 * Every command here is documented — together with its adb revert command — in README.md.
 *
 * Reference (verified against AOSP source):
 *  - SurfaceFlinger::onTransact case 1015 reads `int32 apply`, then a **column-major mat4**
 *    (16 floats; last row must be (0,0,0,1)). `i32 0` resets the client matrix to identity.
 *    Present unchanged from Android 10 through Android 16.
 *  - Settings.Secure `accessibility_display_daltonizer` value 0 is
 *    AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY; ColorDisplayService turns that
 *    into a real grayscale matrix. This is the non-threshold fallback.
 */
object DisplayCommands {

    /** Rec.709 luma weights (same as AOSP ColorDisplayService MATRIX_GRAYSCALE). */
    private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    /** Gain of the 1-bit threshold; transition band in luma is 1/gain wide. */
    const val ONE_BIT_GAIN = 48f

    /** SurfaceFlinger 1015 with `i32 0` → client color matrix back to identity. */
    const val SF_RESET_MATRIX = "service call SurfaceFlinger 1015 i32 0"

    // Hidden Settings.Secure keys observed by AOSP's ColorDisplayService.
    const val KEY_DALTONIZER = "accessibility_display_daltonizer"
    const val KEY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    const val KEY_INVERSION_ENABLED = "accessibility_display_inversion_enabled"

    /** Turn the AOSP accessibility grayscale (daltonizer monochromacy) on. */
    const val SETTINGS_GRAYSCALE_ON =
        "settings put secure $KEY_DALTONIZER 0 && " +
            "settings put secure $KEY_DALTONIZER_ENABLED 1"

    /** Turn the accessibility grayscale off again. */
    const val SETTINGS_GRAYSCALE_OFF =
        "settings put secure $KEY_DALTONIZER_ENABLED 0"

    /** Turn accessibility color inversion off (restores normal colors). */
    const val SETTINGS_INVERSION_OFF =
        "settings put secure $KEY_INVERSION_ENABLED 0"

    /** Row-major Android color matrix (4 rows x 5: r g b a offset) → Rec.709 grayscale. */
    fun grayscaleMatrix(): FloatArray = floatArrayOf(
        LUMA[0], LUMA[1], LUMA[2], 0f, 0f,
        LUMA[0], LUMA[1], LUMA[2], 0f, 0f,
        LUMA[0], LUMA[1], LUMA[2], 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /**
     * High-contrast matrix approximating a true 1-bit threshold:
     * every channel gets `clamp(gain * luma + 0.5 - gain * t)`, so pixels settle at
     * fully black or fully white with only a 1/gain-wide luma band in between.
     * (A linear color matrix can never be a perfect step function — see README.)
     */
    fun oneBitMatrix(threshold: Float): FloatArray {
        val t = threshold.coerceIn(0.05f, 0.95f)
        val offset = 0.5f - ONE_BIT_GAIN * t
        fun row() = floatArrayOf(
            ONE_BIT_GAIN * LUMA[0], ONE_BIT_GAIN * LUMA[1], ONE_BIT_GAIN * LUMA[2], 0f, offset,
        )
        return floatArrayOf(
            *row(), *row(), *row(),
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** `service call SurfaceFlinger 1015 i32 1 f m0 … f m15` (16 floats, column-major). */
    fun sfMatrixCommand(matrix: FloatArray): String {
        val args = toColumnMajorMat4(matrix)
            .joinToString(separator = " ") { "f ${formatFloat(it)}" }
        return "service call SurfaceFlinger 1015 i32 1 $args"
    }

    /**
     * Row-major 4x5 Android matrix → the column-major mat4 that case 1015 reads:
     * ```
     * for (i in 0..3) for (j in 0..3) mClientColorMatrix[i][j] = data.readFloat()
     * ```
     * i = column, j = row. Column 3 is the constant column (it multiplies the implicit 1 of
     * (R,G,B,1)); the alpha coefficient of the Android matrix is not part of the transform.
     */
    private fun toColumnMajorMat4(m: FloatArray): FloatArray {
        val flat = FloatArray(16)
        for (row in 0..3) {
            val base = row * 5
            flat[0 * 4 + row] = m[base + 0] // R input column
            flat[1 * 4 + row] = m[base + 1] // G input column
            flat[2 * 4 + row] = m[base + 2] // B input column
            flat[3 * 4 + row] = m[base + 4] // offset column
        }
        flat[15] = 1f // last row must be (0,0,0,1) or SurfaceFlinger logs an error
        return flat
    }

    private fun formatFloat(v: Float): String = String.format(Locale.US, "%.6f", v)

    /**
     * Restore normal colors.
     * @param fallback true if our daltonizer fallback may be enabled (always revert it then)
     * @param full true for the emergency reset (also force inversion off)
     */
    fun resetCommands(fallback: Boolean, full: Boolean): List<String> = buildList {
        add(SF_RESET_MATRIX)
        if (fallback || full) add(SETTINGS_GRAYSCALE_OFF)
        if (full) add(SETTINGS_INVERSION_OFF)
    }

    /** Which color matrix the current state wants (null when no matrix should be applied). */
    fun matrixFor(state: ModeState): FloatArray? = when (state.colorMode) {
        com.pixelmode.app.model.ColorMode.OFF -> null
        com.pixelmode.app.model.ColorMode.GRAYSCALE -> grayscaleMatrix()
        com.pixelmode.app.model.ColorMode.ONE_BIT -> oneBitMatrix(state.threshold)
    }
}
