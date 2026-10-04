package com.pixelmode.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.pixelmode.app.root.Root

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var secureWritable by mutableStateOf(false)

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        refreshPermissions()
        Root.refresh() // first root check happens here (root manager may prompt once)

        setContent {
            PixelModeTheme {
                PixelModeScreen(
                    overlayGranted = overlayGranted,
                    notificationsGranted = notificationsGranted,
                    secureWritable = secureWritable,
                    onRequestOverlay = { requestOverlayPermission() },
                    onRequestNotifications = { requestNotificationPermission() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        Root.refresh()
    }

    private fun refreshPermissions() {
        overlayGranted = Settings.canDrawOverlays(this)
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        secureWritable =
            checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestOverlayPermission() {
        overlayLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
