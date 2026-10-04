package com.pixelmode.app

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.pixelmode.app.data.ModePrefs
import com.pixelmode.app.root.ColorEngine
import com.pixelmode.app.service.ModeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PixelModeApp : Application() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val safetyCheck = Runnable { runSafetyCheck() }

    override fun onCreate() {
        super.onCreate()
        ModePrefs.init(this)
        scheduleSafetyCheck()
    }

    /**
     * Crash-safety net. The SurfaceFlinger color matrix (and the daltonizer Settings key)
     * outlive our process, so whenever this process comes up without a running service we
     * either restore normal colors or re-apply the requested mode — the screen can never be
     * left stuck in an unusable state after a crash.
     */
    fun scheduleSafetyCheck(delayMs: Long = SAFETY_DELAY_MS) {
        handler.removeCallbacks(safetyCheck)
        handler.postDelayed(safetyCheck, delayMs)
    }

    private fun runSafetyCheck() {
        if (ModeService.running) return
        val state = ModePrefs.state.value
        when {
            state.active -> {
                try {
                    startForegroundService(
                        Intent(this, ModeService::class.java).setAction(ModeService.ACTION_APPLY)
                    )
                } catch (t: Throwable) {
                    // Background start not allowed right now; the sticky restart or the next
                    // user interaction will bring the service up instead.
                }
            }
            state.matrixDirty || state.fallbackActive -> {
                scope.launch { ColorEngine.restore(this@PixelModeApp, state, full = false) }
            }
        }
    }

    companion object {
        private const val SAFETY_DELAY_MS = 1_500L
    }
}
