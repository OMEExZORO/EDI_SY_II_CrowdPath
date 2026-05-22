package com.crowdpath.emulator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Main emulator UI screen.
 *
 * Shows:
 *  - Connection status banner
 *  - Current vibration pattern (animated)
 *  - Obstacle distance slider + manual alert button
 *  - Scrolling command log
 *  - Clear log / simulate drain buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorScreen(
    bleServer: BLEServer,
    logger: CommandLogger,
    broadcaster: StatusBroadcaster
) {
    var isAdvertising by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var connectedDeviceName by remember { mutableStateOf<String?>(null) }
    var activeVibration by remember { mutableIntStateOf(0) }
    var obstacleDistance by remember { mutableFloatStateOf(100f) }
    var simulateDrain by remember { mutableStateOf(false) }
    var logEntries by remember { mutableStateOf(logger.entries) }

    // Register callbacks
    LaunchedEffect(Unit) {
        bleServer.onConnectionChanged = { connected, name ->
            isConnected = connected
            connectedDeviceName = name
            logger.logSystem(if (connected) "Connected: $name" else "Disconnected")
            logEntries = logger.entries
        }
        bleServer.onCommandReceived = { raw, parsed ->
            logger.logCommand(raw, parsed)

            // Extract vibration pattern if SET_VIBE
            val cmd = parsed["cmd"] as? String
            if (cmd == "SET_VIBE") {
                activeVibration = (parsed["pattern"] as? Number)?.toInt() ?: 0
                logger.logVibration(activeVibration)
            } else if (cmd == "NAV") {
                // Infer vibration from NAV type
                val type = parsed["type"] as? String
                val dir = parsed["dir"] as? String
                activeVibration = when (type) {
                    "TURN" -> if (dir == "LEFT") 1 else 2
                    "STAIRS" -> 4
                    "ARRIVED" -> 5
                    "STOP" -> 6
                    else -> 3
                }
                logger.logVibration(activeVibration)
            }
            logEntries = logger.entries
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Cane Emulator") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // ── Connection status ──────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isConnected -> MaterialTheme.colorScheme.primaryContainer
                        isAdvertising -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when {
                            isConnected -> Icons.Default.BluetoothConnected
                            isAdvertising -> Icons.Default.BluetoothSearching
                            else -> Icons.Default.BluetoothDisabled
                        },
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                isConnected -> "Connected to Phone"
                                isAdvertising -> "Advertising…"
                                else -> "Not Advertising"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isConnected && connectedDeviceName != null) {
                            Text(
                                "Device: $connectedDeviceName",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "Battery: %.1fV".format(broadcaster.batteryVoltage),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (!isAdvertising) {
                        FilledTonalButton(onClick = {
                            bleServer.startAdvertising()
                            broadcaster.start()
                            isAdvertising = true
                            logger.logSystem("Advertising started")
                            logEntries = logger.entries
                        }) {
                            Text("Start")
                        }
                    } else {
                        FilledTonalButton(onClick = {
                            bleServer.stop()
                            broadcaster.stop()
                            isAdvertising = false
                            isConnected = false
                            logger.logSystem("Advertising stopped")
                            logEntries = logger.entries
                        }) {
                            Text("Stop")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Vibration visualizer ───────────────────────────────
            VibrationVisualizer(activePattern = activeVibration)

            Spacer(Modifier.height(8.dp))

            // ── Obstacle distance slider ───────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (obstacleDistance < 30f)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Obstacle Distance", fontWeight = FontWeight.Medium)
                        Text(
                            "${obstacleDistance.toInt()} cm",
                            fontWeight = FontWeight.Bold,
                            color = if (obstacleDistance < 30f) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Slider(
                        value = obstacleDistance,
                        onValueChange = {
                            obstacleDistance = it
                            broadcaster.obstacleDistanceCm = it.toInt()
                        },
                        valueRange = 0f..200f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            broadcaster.triggerObstacleAlert()
                            logEntries = logger.entries
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Trigger Obstacle Alert")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Command log ────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Command Log", fontWeight = FontWeight.SemiBold)
                Row {
                    FilterChip(
                        selected = simulateDrain,
                        onClick = {
                            simulateDrain = !simulateDrain
                            broadcaster.simulateDrain = simulateDrain
                        },
                        label = { Text("Drain") },
                        leadingIcon = {
                            Icon(Icons.Default.BatteryAlert, null, Modifier.size(16.dp))
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        logger.clear()
                        logEntries = logger.entries
                    }) {
                        Text("Clear")
                    }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(logEntries) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: CommandLogger.LogEntry) {
    val iconColor = when (entry.type) {
        "CMD" -> Color(0xFF42A5F5)
        "VIBE" -> Color(0xFFFFA726)
        "STATUS" -> Color(0xFF66BB6A)
        "SYSTEM" -> Color(0xFF78909C)
        else -> Color.Gray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            entry.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        Text(
            "●",
            color = iconColor,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}
