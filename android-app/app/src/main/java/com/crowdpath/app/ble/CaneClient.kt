package com.crowdpath.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.crowdpath.app.data.models.CaneNavCommand
import com.crowdpath.app.data.models.CaneVibeCommand
import java.util.UUID

/**
 * BLE client that connects to the SmartCane Emulator app.
 *
 * Scans for a device advertising [BLEProtocol.DEVICE_NAME_PREFIX],
 * connects, discovers services, then allows sending navigation /
 * vibration commands and receiving status notifications.
 */
class CaneClient(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null

    var isConnected: Boolean = false; private set
    var onStatusReceived: ((batteryV: Float, ultraCm: Int) -> Unit)? = null
    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null
    var onObstacleDetected: ((distanceCm: Int) -> Unit)? = null
    var onObstacleCleared: (() -> Unit)? = null

    private var lastUltraCm: Int = 999

    private val handler = Handler(Looper.getMainLooper())

    // ── Scanning ───────────────────────────────────────────────────────

    fun startScan() {
        val filter = ScanFilter.Builder()
            .setDeviceName(null) // accept all, filter manually
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scan started")
            // Auto-stop after 15s
            handler.postDelayed({ scanner?.stopScan(scanCallback) }, 15_000)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission denied: ${e.message}")
        }
    }

    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) { }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try { result.device.name } catch (_: SecurityException) { null }
            if (name != null && name.startsWith(BLEProtocol.DEVICE_NAME_PREFIX)) {
                Log.i(TAG, "Found cane emulator: $name")
                stopScan()
                connect(result.device)
            }
        }
    }

    // ── Connection ─────────────────────────────────────────────────────

    private fun connect(device: BluetoothDevice) {
        try {
            gatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Connect permission denied: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) { }
        gatt = null
        isConnected = false
        onConnectionChanged?.invoke(false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "Connected to cane emulator")
                try { g.discoverServices() } catch (_: SecurityException) { }
            } else {
                isConnected = false
                handler.post { onConnectionChanged?.invoke(false) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(UUID.fromString(BLEProtocol.SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Service not found")
                return
            }
            commandChar = service.getCharacteristic(UUID.fromString(BLEProtocol.COMMAND_CHAR_UUID))
            val statusChar = service.getCharacteristic(UUID.fromString(BLEProtocol.STATUS_CHAR_UUID))

            // Enable notifications for status
            if (statusChar != null) {
                try {
                    g.setCharacteristicNotification(statusChar, true)
                    val descriptor = statusChar.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(descriptor)
                } catch (_: SecurityException) { }
            }
            isConnected = true
            handler.post { onConnectionChanged?.invoke(true) }
            Log.i(TAG, "Services discovered, ready to send commands")
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UUID.fromString(BLEProtocol.STATUS_CHAR_UUID)) {
                val data = BLEProtocol.decodeStatus(characteristic.value ?: return)
                if (data != null) {
                    val battery = (data["battery_v"] as? Number)?.toFloat() ?: 0f
                    val ultra = (data["ultra_cm"] as? Number)?.toInt() ?: 100
                    handler.post {
                        onStatusReceived?.invoke(battery, ultra)

                        // Obstacle hysteresis: detect transitions across 40cm threshold
                        if (ultra < 40) {
                            onObstacleDetected?.invoke(ultra)
                        } else if (lastUltraCm < 40 && ultra >= 40) {
                            onObstacleCleared?.invoke()
                        }
                        lastUltraCm = ultra
                    }
                }
            }
        }
    }

    // ── Write commands ─────────────────────────────────────────────────

    fun sendNavigationCommand(cmd: CaneNavCommand) {
        write(BLEProtocol.encodeNavCommand(cmd))
    }

    fun sendVibrationCommand(cmd: CaneVibeCommand) {
        write(BLEProtocol.encodeVibeCommand(cmd))
    }

    private fun write(data: ByteArray) {
        val char = commandChar
        val g = gatt
        if (char == null || g == null || !isConnected) {
            Log.w(TAG, "Cannot write — not connected")
            return
        }
        try {
            char.value = data
            g.writeCharacteristic(char)
            Log.d(TAG, "Sent ${String(data)}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Write permission denied: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CaneClient"
    }
}
