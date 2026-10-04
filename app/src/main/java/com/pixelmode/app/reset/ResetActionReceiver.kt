package com.pixelmode.app.reset

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pixelmode.app.controller.ModeController

/** Handles the notification's "EMERGENCY RESET" action. */
class ResetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ModeController.emergencyReset(context.applicationContext)
    }
}
