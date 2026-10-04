package com.pixelmode.app.ui

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelmode.app.controller.ModeController
import com.pixelmode.app.data.AppStatus
import com.pixelmode.app.data.ModePrefs
import com.pixelmode.app.model.ColorMode
import com.pixelmode.app.root.DisplayCommands
import com.pixelmode.app.root.Root

private const val ADB_GRANT_COMMAND =
    "adb shell pm grant com.pixelmode.app android.permission.WRITE_SECURE_SETTINGS"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PixelModeScreen(
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    secureWritable: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val state by ModePrefs.state.collectAsState()
    val status by AppStatus.state.collectAsState()
    val root by Root.status.collectAsState()

    var pendingOverlayAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    // Resume an action that was waiting for the overlay permission once it is granted.
    LaunchedEffect(overlayGranted) {
        if (overlayGranted) {
            pendingOverlayAction?.invoke()
            pendingOverlayAction = null
        }
    }

    /** Run [action], or divert to the overlay-permission screen first when required. */
    fun gated(needsOverlay: Boolean, action: () -> Unit) {
        if (needsOverlay && !overlayGranted) {
            pendingOverlayAction = action
            onRequestOverlay()
        } else {
            action()
        }
    }

    Scaffold(containerColor = Color.Black) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "PixelMode",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Root-driven whole-screen render modes for AMOLED",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------------------------------------------------------- master
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { },
                        onLongClick = { showResetDialog = true },
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Master", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.active) state.summary else "Off",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.active) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Switch(
                    checked = state.masterOn,
                    onCheckedChange = { on ->
                        gated(needsOverlay = on && state.checkerOn) {
                            ModeController.setMaster(context, on)
                        }
                    },
                )
            }
            Text(
                "Long-press the toggle for an emergency reset",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------------------------------------------------------- root
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Root",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        text = when (root) {
                            Root.Status.GRANTED -> "GRANTED"
                            Root.Status.DENIED -> "DENIED"
                            Root.Status.UNKNOWN -> "CHECKING"
                        },
                        good = root == Root.Status.GRANTED,
                    )
                }
                when (root) {
                    Root.Status.GRANTED -> Text(
                        "Full control: the SurfaceFlinger color matrix drives grayscale and the " +
                            "adjustable 1-bit threshold.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Root.Status.DENIED -> Text(
                        "Root DENIED — PixelMode cannot run \"service call SurfaceFlinger 1015\". " +
                            "Grant root to PixelMode in your root manager (Magisk / KernelSU / " +
                            "APatch) and tap Re-check. Until then only the Settings.Secure " +
                            "daltonizer fallback can change colors (needs the adb grant below); " +
                            "the checkerboard overlay works either way.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB3AD),
                    )
                    Root.Status.UNKNOWN -> Text(
                        "Checking root access…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { Root.refresh() }) { Text("Re-check root") }
            }
        }

        // ---------------------------------------------------------------- permissions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Permissions", style = MaterialTheme.typography.titleLarge)
                PermissionRow(
                    title = "Display over other apps",
                    detail = "Needed by the checkerboard overlay window.",
                    ok = overlayGranted,
                    actionLabel = "Grant",
                    onAction = onRequestOverlay,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        title = "Notifications",
                        detail = "Keeps the foreground-service notice visible — it carries the " +
                            "emergency reset action.",
                        ok = notificationsGranted,
                        actionLabel = "Allow",
                        onAction = onRequestNotifications,
                    )
                }
                PermissionRow(
                    title = "WRITE_SECURE_SETTINGS (optional)",
                    detail = "Rootless fallback for the grayscale keys. Grant it once via adb:\n" +
                        ADB_GRANT_COMMAND,
                    ok = secureWritable,
                    actionLabel = "Copy command",
                    onAction = {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("PixelMode", ADB_GRANT_COMMAND))
                    },
                )
            }
        }

        // ---------------------------------------------------------------- color mode
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Color mode", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(
                        text = "Off",
                        selected = state.colorMode == ColorMode.OFF,
                        modifier = Modifier.weight(1f),
                    ) { ModeController.setColorMode(context, ColorMode.OFF) }
                    ModeButton(
                        text = "Grayscale",
                        selected = state.colorMode == ColorMode.GRAYSCALE,
                        modifier = Modifier.weight(1f),
                    ) { ModeController.setColorMode(context, ColorMode.GRAYSCALE) }
                    ModeButton(
                        text = "1-bit B/W",
                        selected = state.colorMode == ColorMode.ONE_BIT,
                        modifier = Modifier.weight(1f),
                    ) { ModeController.setColorMode(context, ColorMode.ONE_BIT) }
                }
                if (state.colorMode == ColorMode.ONE_BIT) {
                    var threshold by remember(state.threshold) {
                        mutableFloatStateOf(state.threshold)
                    }
                    Text(
                        "Threshold ${"%.2f".format(threshold)} · gain " +
                            "${DisplayCommands.ONE_BIT_GAIN.toInt()}×",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        onValueChangeFinished = { ModeController.setThreshold(context, threshold) },
                        valueRange = 0.05f..0.95f,
                    )
                }
                Text(
                    "Both color modes combine with the checkerboard below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------------------------------------------------------- checkerboard
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Checkerboard", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "1px on/off grid — half the pixels black",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.checkerOn,
                        onCheckedChange = { on ->
                            gated(needsOverlay = on && state.masterOn) {
                                ModeController.setCheckerEnabled(context, on)
                            }
                        },
                    )
                }
                if (state.checkerOn) {
                    var opacity by remember(state.checkerOpacity) {
                        mutableIntStateOf(state.checkerOpacity)
                    }
                    Text(
                        "Opacity $opacity%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = opacity.toFloat(),
                        onValueChange = { opacity = it.toInt() },
                        onValueChangeFinished = {
                            ModeController.setCheckerOpacity(context, opacity)
                        },
                        valueRange = 0f..100f,
                    )
                    OutlinedButton(
                        onClick = {
                            ModeController.setCheckerPhase(context, !state.checkerPhaseFlipped)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.checkerPhaseFlipped) {
                                "Phase: flipped (even pixels black)"
                            } else {
                                "Phase: normal (odd pixels black)"
                            }
                        )
                    }
                    Text(
                        "The bitmap is generated at the panel's native pixel resolution at " +
                            "runtime — no density scaling, nearest-neighbour only — and drawn " +
                            "once. The window is TYPE_APPLICATION_OVERLAY with FLAG_NOT_TOUCHABLE " +
                            "+ FLAG_NOT_FOCUSABLE, covers the status and navigation bars, and is " +
                            "rebuilt on rotation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Status", style = MaterialTheme.typography.titleLarge)
                StatusRow("Color method", status.colorMethod)
                StatusRow(
                    "Overlay",
                    when {
                        status.overlayActive -> "active (native bitmap, drawn once)"
                        state.checkerOn -> "requested but inactive"
                        else -> "not requested"
                    },
                )
                status.overlayError?.let { Warning(it) }
                status.warning?.let { Warning(it) }
                if (status.log.isNotEmpty()) {
                    HorizontalDivider()
                    status.log.take(8).forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- emergency reset
        Button(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF3B30),
                contentColor = Color.White,
            ),
        ) {
            Text("EMERGENCY RESET", fontWeight = FontWeight.Bold)
        }
        Text(
            "Also available as a notification action while the mode runs. Android opens this " +
                "app when you long-press the Quick Settings tile — the reset button is right here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Emergency reset?") },
            text = {
                Text(
                    "Restores the identity color matrix, switches off the accessibility " +
                        "daltonizer and color inversion, removes the overlay and turns PixelMode off."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        ModeController.emergencyReset(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF3B30),
                        contentColor = Color.White,
                    ),
                ) { Text("RESET") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ------------------------------------------------------------------------------ helpers

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            StatusPill(text = if (ok) "OK" else "MISSING", good = ok)
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!ok) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusPill(text: String, good: Boolean) {
    val bg = if (good) Color(0xFF1E3A24) else Color(0xFF3A1414)
    val fg = if (good) Color(0xFF7BE495) else Color(0xFFFF8A80)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Warning(message: String) {
    Surface(
        color = Color(0x22FF5449),
        contentColor = Color(0xFFFFB3AD),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0x66FF5449)),
    ) {
        Text(
            message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
