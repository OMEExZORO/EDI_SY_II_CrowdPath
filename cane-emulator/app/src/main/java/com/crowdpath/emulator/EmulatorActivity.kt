package com.crowdpath.emulator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

/**
 * Entry-point Activity for the SmartCane Emulator.
 *
 * Creates [BLEServer], [CommandLogger], and [StatusBroadcaster],
 * then renders the [EmulatorScreen].
 */
class EmulatorActivity : ComponentActivity() {

    private lateinit var bleServer: BLEServer
    private lateinit var logger: CommandLogger
    private lateinit var broadcaster: StatusBroadcaster

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            android.util.Log.w("EmulatorActivity", "Some BLE permissions were denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBlePermissions()

        bleServer = BLEServer(this)
        logger = CommandLogger()
        broadcaster = StatusBroadcaster(bleServer, logger)

        setContent {
            EmulatorTheme {
                EmulatorScreen(
                    bleServer = bleServer,
                    logger = logger,
                    broadcaster = broadcaster
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcaster.stop()
        bleServer.stop()
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun EmulatorTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
