package com.crowdpath.app.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crowdpath.app.data.database.AppDatabase
 import com.crowdpath.app.navigation.PDRTracker
import com.crowdpath.app.ui.theme.*
import kotlinx.coroutines.launch

private const val PREFS                = "crowdpath_settings"
private const val KEY_VOICE_SPEED      = "voice_speed"
private const val KEY_STEP_LENGTH      = "step_length"
private const val KEY_ACCESSIBLE_ONLY  = "accessible_only"
private const val KEY_VIBRATION        = "vibration_enabled"
private const val KEY_PHOTO_CONSENT    = "photo_consent"

@Composable
fun SettingsScreen() {
    val context        = LocalContext.current
    val prefs          = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val db             = remember { AppDatabase.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var voiceSpeed        by remember { mutableFloatStateOf(prefs.getFloat(KEY_VOICE_SPEED, 0.9f)) }
    var stepLength        by remember { mutableFloatStateOf(prefs.getFloat(KEY_STEP_LENGTH, 0.7f)) }
    var accessibleOnly    by remember { mutableStateOf(prefs.getBoolean(KEY_ACCESSIBLE_ONLY, false)) }
    var vibrationEnabled  by remember { mutableStateOf(prefs.getBoolean(KEY_VIBRATION, true)) }
    var photoConsent      by remember { mutableStateOf(prefs.getBoolean(KEY_PHOTO_CONSENT, false)) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ── Step calibration state ─────────────────────────────────────────────
    val calibPdr          = remember { PDRTracker(context) }
    var showCalibDialog   by remember { mutableStateOf(false) }
    var calibRunning      by remember { mutableStateOf(false) }
    var calibSteps        by remember { mutableIntStateOf(0) }
    var calibDistInput    by remember { mutableFloatStateOf(14f) }  // default 20×0.7
    var calibPhase        by remember { mutableIntStateOf(0) }      // 0=walk, 1=enter dist


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBg)
            .verticalScroll(rememberScrollState())
    ) {
        // ── Header ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(colors = listOf(Color(0xFF0D2552), NavyBg))
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text(
                    "Settings",
                    style      = MaterialTheme.typography.headlineSmall,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Personalise your navigation experience",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Voice speed section ────────────────────────────────────
        SettingsSection(
            icon    = Icons.Default.VolumeUp,
            title   = "Voice Speed",
            accent  = ElectricBlue
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "%.1f×".format(voiceSpeed),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 16.sp,
                    color      = ElectricBlue
                )
                Text("Slow ← → Fast", style = MaterialTheme.typography.labelSmall, color = SlateText)
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value         = voiceSpeed,
                onValueChange = {
                    voiceSpeed = it
                    prefs.edit().putFloat(KEY_VOICE_SPEED, it).apply()
                },
                valueRange    = 0.5f..2.0f,
                steps         = 5,
                modifier      = Modifier.fillMaxWidth(),
                colors        = SliderDefaults.colors(
                    thumbColor      = ElectricBlue,
                    activeTrackColor = ElectricBlue,
                    inactiveTrackColor = Color(0xFF334155)
                )
            )
        }

        // ── Step length section ────────────────────────────────────
        SettingsSection(
            icon   = Icons.Default.DirectionsWalk,
            title  = "Step Length",
            accent = CyanAccent
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "%.2f m".format(stepLength),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 16.sp,
                    color      = CyanAccent
                )
                Text("Short ← → Long", style = MaterialTheme.typography.labelSmall, color = SlateText)
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value         = stepLength,
                onValueChange = {
                    stepLength = it
                    prefs.edit().putFloat(KEY_STEP_LENGTH, it).apply()
                },
                valueRange    = 0.4f..1.2f,
                steps         = 7,
                modifier      = Modifier.fillMaxWidth(),
                colors        = SliderDefaults.colors(
                    thumbColor        = CyanAccent,
                    activeTrackColor  = CyanAccent,
                    inactiveTrackColor = Color(0xFF334155)
                )
            )
            Spacer(Modifier.height(8.dp))
            // Auto-calibrate button
            Button(
                onClick = {
                    calibSteps     = 0
                    calibPhase     = 0
                    calibDistInput = 14f
                    calibRunning   = false
                    showCalibDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = CyanAccent.copy(alpha = 0.15f))
            ) {
                Icon(
                    Icons.Default.DirectionsWalk,
                    contentDescription = null,
                    tint     = CyanAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Auto-Calibrate Step Length",
                    color      = CyanAccent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // ── Toggle section ─────────────────────────────────────────
        SettingsSection(
            icon   = Icons.Default.Tune,
            title  = "Preferences",
            accent = Color(0xFF7C3AED)
        ) {
            PremiumToggle(
                icon           = Icons.Default.Accessibility,
                title          = "Accessible Routes Only",
                subtitle       = "Avoid stairs, prefer elevators and ramps",
                checked        = accessibleOnly,
                onCheckedChange = {
                    accessibleOnly = it
                    prefs.edit().putBoolean(KEY_ACCESSIBLE_ONLY, it).apply()
                },
                accentColor    = Color(0xFF7C3AED)
            )
            Divider(color = Color(0xFF1E293B), modifier = Modifier.padding(vertical = 8.dp))
            PremiumToggle(
                icon           = Icons.Default.Vibration,
                title          = "Vibration Feedback",
                subtitle       = "Phone vibrates at every turn and arrival",
                checked        = vibrationEnabled,
                onCheckedChange = {
                    vibrationEnabled = it
                    prefs.edit().putBoolean(KEY_VIBRATION, it).apply()
                },
                accentColor    = SuccessGreen
            )
            Divider(color = Color(0xFF1E293B), modifier = Modifier.padding(vertical = 8.dp))
            PremiumToggle(
                icon           = Icons.Default.CameraAlt,
                title          = "Photo Consent",
                subtitle       = "Allow photo upload during mapping sessions",
                checked        = photoConsent,
                onCheckedChange = {
                    photoConsent = it
                    prefs.edit().putBoolean(KEY_PHOTO_CONSENT, it).apply()
                },
                accentColor    = CyanAccent
            )
        }

        // ── App info ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NavyCard)
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = SlateText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("CrowdPath v1.0", fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp)
                    Text(
                        "Positioning: PDR (Pedestrian Dead Reckoning) + ARCore on supported devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Danger zone ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, ErrorRed.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .background(ErrorRed.copy(alpha = 0.05f))
                .padding(16.dp)
        ) {
            Column {
                Text("Danger Zone", fontWeight = FontWeight.SemiBold, color = ErrorRed, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick  = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete All Cached Data", fontWeight = FontWeight.Medium)
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor   = NavyCard,
                title = { Text("Delete all maps?", color = Color.White, fontWeight = FontWeight.Bold) },
                text  = { Text("This will delete every map saved on this device. This cannot be undone.", color = SlateText) },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch { db.mapDao().deleteAllMaps() }
                            showDeleteConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                    ) { Text("Delete All", color = Color.White, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = SlateText)
                    }
                }
            )
        }

        // ── Step calibration dialog ────────────────────────────────
        if (showCalibDialog) {
            AlertDialog(
                onDismissRequest = {
                    calibPdr.stop(); calibRunning = false; showCalibDialog = false
                },
                containerColor = NavyCard,
                title = {
                    Text(
                        if (calibPhase == 0) "Walk 20 Steps" else "How Far Did You Walk?",
                        color = Color.White, fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        if (calibPhase == 0) {
                            Text(
                                "Walk 20 steps at your normal pace, then tap Done. Hold the phone naturally.",
                                style = MaterialTheme.typography.bodyMedium, color = SlateText
                            )
                            Spacer(Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CyanAccent.copy(alpha = 0.1f))
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$calibSteps", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = CyanAccent)
                                    Text("/ 20 steps", color = SlateText, fontSize = 14.sp)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (!calibRunning) {
                                Button(
                                    onClick = {
                                        calibSteps = 0; calibRunning = true
                                        calibPdr.onStep = { steps, _ ->
                                            calibSteps = steps
                                            if (steps >= 20) {
                                                calibPdr.stop(); calibRunning = false; calibPhase = 1
                                            }
                                        }
                                        calibPdr.resetSteps(); calibPdr.start()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors   = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                                ) { Text("Start Walking", color = Color.White, fontWeight = FontWeight.Bold) }
                            } else {
                                Text("Walking… $calibSteps / 20 steps counted",
                                    style = MaterialTheme.typography.bodySmall, color = CyanAccent)
                            }
                        } else {
                            Text(
                                "You walked $calibSteps steps. Slide to your best distance estimate.",
                                style = MaterialTheme.typography.bodyMedium, color = SlateText
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "%.1f m  →  step: %.2f m".format(
                                    calibDistInput, calibDistInput / calibSteps.coerceAtLeast(1)
                                ),
                                fontWeight = FontWeight.Bold, color = CyanAccent, fontSize = 15.sp
                            )
                            Slider(
                                value = calibDistInput, onValueChange = { calibDistInput = it },
                                valueRange = 5f..30f, modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = CyanAccent, activeTrackColor = CyanAccent,
                                    inactiveTrackColor = Color(0xFF334155)
                                )
                            )
                            Text("Tip: a room is ~3–5 m, a corridor is ~10–20 m.",
                                style = MaterialTheme.typography.bodySmall, color = SlateText)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (calibPhase == 0) {
                                calibPdr.stop(); calibRunning = false; calibPhase = 1
                            } else if (calibSteps > 0) {
                                val computed = calibDistInput / calibSteps
                                stepLength = computed
                                prefs.edit().putFloat(KEY_STEP_LENGTH, computed).apply()
                                showCalibDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                    ) {
                        Text(if (calibPhase == 0) "Done Walking" else "Save",
                            color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        calibPdr.stop(); calibRunning = false; showCalibDialog = false
                    }) { Text("Cancel", color = SlateText) }
                }
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NavyCard)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun PremiumToggle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint     = if (checked) accentColor else SlateText,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SlateText)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = accentColor,
                uncheckedTrackColor = Color(0xFF334155)
            )
        )
    }
}
