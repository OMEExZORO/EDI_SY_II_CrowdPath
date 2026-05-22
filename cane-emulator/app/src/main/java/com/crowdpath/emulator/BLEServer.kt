package com.crowdpath.emulator

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.google.gson.Gson
import java.util.UUID

/**
 * BLE GATT Server that advertises as "SmartCane_Emulator".
 *
 * Provides a service with two characteristics:
 *  - **Command RX** (write): receives navigation/vibration commands from the phone.
 *  - **Status TX** (notify): broadcast cane status (battery, ultrasonic) to the phone.
 */
class BLEServer(private val context: Context) {

    companion object {
        private const val TAG = "BLEServer"
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val COMMAND_CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val STATUS_CHAR_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private var connectedDevice: BluetoothDevice? = null
    private val gson = Gson()

    var isAdvertising: Boolean = false; private set
    var isConnected: Boolean = false; private set

    var onCommandReceived: ((raw: String, parsed: Map<String, Any?>) -> Unit)? = null
    var onConnectionChanged: ((connected: Boolean, deviceName: String?) -> Unit)? = null

    // ── Advertise ──────────────────────────────────────────────────

    fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        try {
            // Set device name
            bluetoothAdapter?.name = "SmartCane_Emulator"

            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.i(TAG, "Advertising started as SmartCane_Emulator")
        } catch (e: SecurityException) {
            Log.e(TAG, "Advertise permission denied: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "Advertising started successfully")
            openGattServer()
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Advertising failed: errorCode=$errorCode")
        }
    }

    // ── GATT Server ────────────────────────────────────────────────

    private fun openGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

            val service = BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // Command characteristic — phone writes to this
            val commandChar = BluetoothGattCharacteristic(
                COMMAND_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            // Status characteristic — cane notifies phone
            statusCharacteristic = BluetoothGattCharacteristic(
                STATUS_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or
                                BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }

            service.addCharacteristic(commandChar)
            service.addCharacteristic(statusCharacteristic!!)

            gattServer?.addService(service)
            Log.i(TAG, "GATT server opened with service")
        } catch (e: SecurityException) {
            Log.e(TAG, "GATT server permission denied: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                isConnected = true
                val name = try {
                    device?.name
                } catch (_: SecurityException) {
                    "Unknown"
                }
                Log.i(TAG, "Device connected: $name")
                onConnectionChanged?.invoke(true, name)
            } else {
                connectedDevice = null
                isConnected = false
                Log.i(TAG, "Device disconnected")
                onConnectionChanged?.invoke(false, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic?.uuid == COMMAND_CHAR_UUID && value != null) {
                val raw = String(value, Charsets.UTF_8)
                Log.i(TAG, "Command received: $raw")

                // Parse JSON
                val parsed: Map<String, Any?> = try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(raw, Map::class.java) as Map<String, Any?>
                } catch (_: Exception) {
                    mapOf("raw" to raw)
                }

                onCommandReceived?.invoke(raw, parsed)
            }

            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                } catch (_: SecurityException) { }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                } catch (_: SecurityException) { }
            }
        }
    }

    // ── Send status to phone ───────────────────────────────────────

    fun sendStatus(batteryV: Float, ultraCm: Int) {
        val device = connectedDevice ?: return
        val char = statusCharacteristic ?: return

        val json = """{"cmd":"STATUS","battery_v":$batteryV,"ultra_cm":$ultraCm}"""
        char.value = json.toByteArray(Charsets.UTF_8)

        try {
            gattServer?.notifyCharacteristicChanged(device, char, false)
            Log.d(TAG, "Status sent: $json")
        } catch (e: SecurityException) {
            Log.e(TAG, "Notify permission denied: ${e.message}")
        }
    }

    // ── Shutdown ────────────────────────────────────────────────────

    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (_: SecurityException) { }
        isAdvertising = false
        isConnected = false
        connectedDevice = null
        Log.i(TAG, "BLE server stopped")
    }
}
