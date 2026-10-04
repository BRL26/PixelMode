package com.pixelmode.app.controller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.pixelmode.app.data.ModePrefs
import com.pixelmode.app.model.ColorMode
import com.pixelmode.app.root.ColorEngine
import com.pixelmode.app.service.ModeService
import com.pixelmode.app.tile.ModeTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.service.quicksettings.TileService

/**
 * Single entry point for changing the mode: persists the new state, then pushes it into the
 * foreground service — or restores normal colors directly when nothing is running.
 */
object ModeController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun setMaster(context: Context, on: Boolean) {
        ModePrefs.update {
            when {
                // Turning master on while everything else is off → give it a usable default.
                on && it.colorMode == ColorMode.OFF && !it.checkerOn ->
                    it.copy(masterOn = true, colorMode = ColorMode.GRAYSCALE)
                else -> it.copy(masterOn = on)
            }
        }
        push(context)
    }

    fun setColorMode(context: Context, mode: ColorMode) {
        ModePrefs.update {
            it.copy(
                colorMode = mode,
                // Keep the invariant: master is only "on" while something is actually applied.
                masterOn = it.masterOn && (mode != ColorMode.OFF || it.checkerOn),
            )
        }
        push(context)
    }

    fun setThreshold(context: Context, value: Float) {
        ModePrefs.update { it.copy(threshold = value.coerceIn(0.05f, 0.95f)) }
        push(context)
    }

    fun setCheckerEnabled(context: Context, on: Boolean) {
        ModePrefs.update {
            it.copy(
                checkerOn = on,
                masterOn = it.masterOn && (on || it.colorMode != ColorMode.OFF),
            )
        }
        push(context)
    }

    fun setCheckerPhase(context: Context, flipped: Boolean) {
        ModePrefs.update { it.copy(checkerPhaseFlipped = flipped) }
        push(context)
    }

    fun setCheckerOpacity(context: Context, percent: Int) {
        ModePrefs.update { it.copy(checkerOpacity = percent.coerceIn(0, 100)) }
        push(context)
    }

    /**
     * Push the persisted state to the service, or restore colors when the mode is (now) off.
     */
    fun push(context: Context) {
        requestTileUpdate(context)
        val state = ModePrefs.state.value
        val intent = Intent(context, ModeService::class.java)
        when {
            state.active ->
                context.startForegroundService(intent.setAction(ModeService.ACTION_APPLY))
            ModeService.running ->
                context.startForegroundService(intent.setAction(ModeService.ACTION_STOP))
            state.matrixDirty ->
                restoreOutsideService(context, full = false)
        }
    }

    /**
     * Emergency reset (notification action / in-app long-press): force master off, restore
     * normal colors everywhere and stop the service.
     */
    fun emergencyReset(context: Context) {
        ModePrefs.update { it.copy(masterOn = false) }
        requestTileUpdate(context)
        val intent = Intent(context, ModeService::class.java).setAction(ModeService.ACTION_RESET)
        try {
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            // Service could not start → restore directly without it.
            restoreOutsideService(context, full = true)
        }
    }

    private fun restoreOutsideService(context: Context, full: Boolean) {
        val app = context.applicationContext
        val state = ModePrefs.state.value
        scope.launch {
            ColorEngine.restore(app, state, full)
        }
    }

    fun requestTileUpdate(context: Context) {
        try {
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context, ModeTileService::class.java),
            )
        } catch (t: Throwable) {
            // SystemUI unreachable (boot, shutdown) — harmless.
        }
    }
}
