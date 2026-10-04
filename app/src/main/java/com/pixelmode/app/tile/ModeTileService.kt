package com.pixelmode.app.tile

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.provider.Settings
import com.pixelmode.app.R
import com.pixelmode.app.controller.ModeController
import com.pixelmode.app.data.ModePrefs

/**
 * Quick-settings tile: tap toggles the master switch.
 *
 * Android has no long-press callback for tiles — the system itself opens the tile's app on
 * long-press, which lands the user on PixelMode's screen with the big EMERGENCY RESET button.
 * The notification action covers the "reset without opening the app" case.
 */
class ModeTileService : TileService() {

    override fun onClick() {
        val state = ModePrefs.state.value
        val turningOn = !state.masterOn

        if (turningOn && state.checkerOn && !Settings.canDrawOverlays(this)) {
            // The checkerboard needs the overlay permission — send the user to the app.
            launchApp()
            return
        }
        ModeController.setMaster(this, turningOn)
        render()
    }

    override fun onStartListening() {
        render()
    }

    private fun render() {
        val state = ModePrefs.state.value
        qsTile?.apply {
            this.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            // minSdk 29 == API where Tile.setSubtitle was introduced.
            subtitle = if (state.active) state.summary else "Off"
            updateTile()
        }
    }

    // The Intent overload is the only one that exists below Android 14; the PendingIntent
    // overload above handles Android 14+, so the deprecated branch is never hit on targetSdk 34+.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchApp() {
        val launch: Intent? = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = android.app.PendingIntent.getActivity(
                this,
                0,
                launch,
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}
