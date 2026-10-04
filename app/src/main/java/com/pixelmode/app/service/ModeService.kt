package com.pixelmode.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.pixelmode.app.PixelModeApp
import com.pixelmode.app.R
import com.pixelmode.app.controller.ModeController
import com.pixelmode.app.data.AppStatus
import com.pixelmode.app.data.ModePrefs
import com.pixelmode.app.model.ColorMode
import com.pixelmode.app.model.ModeState
import com.pixelmode.app.overlay.OverlayController
import com.pixelmode.app.reset.ResetActionReceiver
import com.pixelmode.app.root.ColorEngine
import com.pixelmode.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the selected mode applied:
 *  - writes the SurfaceFlinger color matrix / daltonizer keys (event-driven, no polling)
 *  - owns the checkerboard overlay window (redrawn only on real changes)
 *  - re-applies after SCREEN_ON, rotation is handled in onConfigurationChanged
 *  - always restores normal colors on stop, on reset and (via [PixelModeApp]'s safety net)
 *    after a crash, so the screen can never stay stuck.
 */
class ModeService : android.app.Service() {

    companion object {
        const val ACTION_APPLY = "com.pixelmode.app.action.APPLY"
        const val ACTION_STOP = "com.pixelmode.app.action.STOP"
        const val ACTION_RESET = "com.pixelmode.app.action.RESET"

        private const val NOTIF_ID = 4215
        private const val CHANNEL_ID = "pixelmode_active"
        private const val MAX_COLOR_RETRIES = 4
        private const val MAX_STOP_RETRIES = 3
        private const val RETRY_DELAY_MS = 10_000L

        @Volatile
        var running: Boolean = false
            private set
    }

    private lateinit var overlay: OverlayController
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenReceiver: BroadcastReceiver? = null
    private var colorRetries = 0
    private var stopRetries = 0

    override fun onCreate() {
        super.onCreate()
        running = true
        overlay = OverlayController(this)
        createChannel()
        startInForeground(buildNotification(ModePrefs.state.value.summary))
        registerScreenReceiver()
        ModeController.requestTileUpdate(this)
        AppStatus.log("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET -> emergencyReset()
            ACTION_STOP -> stopEverything()
            else -> {
                // Also the path taken after a sticky restart (intent == null).
                val state = ModePrefs.state.value
                if (state.active) applyState(state) else stopEverything()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / display size change → rebuild the overlay at the new native size.
        val state = ModePrefs.state.value
        if (state.active && state.checkerOn) {
            overlay.show(state)
            AppStatus.update { it.copy(overlayActive = overlay.isActive, overlayError = overlay.lastError) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        screenReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        screenReceiver = null
        overlay.hide()
        // If the service dies unexpectedly, re-check colors shortly after (crash safety net).
        (applicationContext as? PixelModeApp)?.scheduleSafetyCheck(2_000)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- apply / restore

    private fun applyState(state: ModeState) {
        applyOverlay(state)
        applyColor(state)
        notifyUpdate(state.summary)
        ModeController.requestTileUpdate(this)
    }

    private fun applyOverlay(state: ModeState) {
        overlay.show(state)
        AppStatus.update {
            it.copy(overlayActive = overlay.isActive, overlayError = overlay.lastError)
        }
        if (state.checkerOn) {
            AppStatus.log(
                "checkerboard ${if (overlay.isActive) "on" else "FAILED"} " +
                    "(${overlay.describeSize()})" +
                    if (state.checkerPhaseFlipped) ", phase flipped" else ""
            )
        }
    }

    private fun applyColor(state: ModeState) {
        serviceScope.launch {
            val outcome = ColorEngine.apply(this@ModeService, state)
            AppStatus.update { it.copy(colorMethod = outcome.method, warning = outcome.warning) }
            AppStatus.log(outcome.log)
            if (outcome.ok) {
                colorRetries = 0
            } else {
                scheduleColorRetry()
            }
            notifyUpdate(ModePrefs.state.value.summary)
            ModeController.requestTileUpdate(this@ModeService)
        }
    }

    /**
     * At boot Magisk may not have a root shell yet — retry a bounded number of times, but
     * only if root was granted before (a real denial is not retried forever).
     */
    private fun scheduleColorRetry() {
        if (colorRetries >= MAX_COLOR_RETRIES || !ModePrefs.state.value.rootEverGranted) return
        colorRetries++
        AppStatus.log("retry $colorRetries/$MAX_COLOR_RETRIES in ${RETRY_DELAY_MS / 1000}s")
        mainHandler.postDelayed({
            if (running && ModePrefs.state.value.active) applyColor(ModePrefs.state.value)
        }, RETRY_DELAY_MS)
    }

    private fun stopEverything() {
        val state = ModePrefs.state.value
        overlay.hide()
        serviceScope.launch {
            val restored = ColorEngine.restore(this@ModeService, state, full = false)
            if (!restored && state.rootEverGranted && stopRetries < MAX_STOP_RETRIES) {
                // At boot Magisk may not be ready yet — keep the service alive briefly and retry
                // so we never leave the daltonizer key / matrix behind.
                stopRetries++
                AppStatus.log("restore failed, retry $stopRetries/$MAX_STOP_RETRIES in ${RETRY_DELAY_MS / 1000}s")
                mainHandler.postDelayed({ if (running) stopEverything() }, RETRY_DELAY_MS)
                return@launch
            }
            AppStatus.update {
                it.copy(
                    colorMethod = "— (off)",
                    warning = null,
                    overlayActive = false,
                    overlayError = null,
                )
            }
            AppStatus.log(if (restored) "stopped, colors restored" else "stopped (nothing to restore)")
            ModeController.requestTileUpdate(this@ModeService)
            stopSelf()
        }
    }

    private fun emergencyReset() {
        val state = ModePrefs.state.value
        serviceScope.launch {
            ColorEngine.restore(this@ModeService, state.copy(fallbackActive = true), full = true)
            ModePrefs.update { it.copy(masterOn = false, fallbackActive = false, matrixDirty = false) }
            overlay.hide()
            AppStatus.update {
                it.copy(
                    colorMethod = "— (emergency reset)",
                    warning = null,
                    overlayActive = false,
                    overlayError = null,
                )
            }
            AppStatus.log("EMERGENCY RESET: identity matrix, daltonizer + inversion off")
            ModeController.requestTileUpdate(this@ModeService)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- screen-on re-apply

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = ModePrefs.state.value
                if (state.active && state.colorMode != ColorMode.OFF) {
                    // SurfaceFlinger can drop the client matrix on display power transitions.
                    applyColor(state)
                }
            }
        }
        screenReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        nm?.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reset = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, ResetActionReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pixelmode_tile)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.emergency_reset),
                    reset,
                ).build(),
            )
            .build()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notifyUpdate(content: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(content))
    }
}
