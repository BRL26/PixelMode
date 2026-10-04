package com.pixelmode.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pixelmode.app.data.ModePrefs
import com.pixelmode.app.service.ModeService

/**
 * Re-applies the mode after reboot (and after app updates).
 * SurfaceFlinger resets itself at boot, but the Settings.Secure daltonizer key survives a
 * reboot — so even when the mode is off we ask the service to restore normal colors.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            -> {
                val app = context.applicationContext
                val pending = goAsync()
                Thread {
                    try {
                        dispatch(app)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun dispatch(app: Context) {
        val state = ModePrefs.state.value
        val intent = Intent(app, ModeService::class.java).setAction(
            when {
                state.active -> ModeService.ACTION_APPLY
                // Colors may still be altered (daltonizer key persists across reboots).
                state.matrixDirty || state.fallbackActive -> ModeService.ACTION_STOP
                else -> return
            }
        )
        try {
            app.startForegroundService(intent)
        } catch (t: Throwable) {
            // Device still booting (background-start restrictions) — the app's safety net
            // will fix colors on first launch, and README documents the manual adb revert.
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
