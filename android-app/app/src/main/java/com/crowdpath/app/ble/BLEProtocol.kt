package com.crowdpath.app.ble

import com.crowdpath.app.data.models.CaneNavCommand
import com.crowdpath.app.data.models.CaneVibeCommand
import com.google.gson.Gson

/**
 * JSON serialisation helpers for the BLE protocol between
 * the phone and the cane emulator.
 */
object BLEProtocol {

    private val gson = Gson()

    // ── UUIDs (must match cane emulator) ───────────────────────────────
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val COMMAND_CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb" // phone writes
    const val STATUS_CHAR_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"  // cane notifies

    const val DEVICE_NAME_PREFIX = "SmartCane"

    /**
     * Serialise a navigation command to JSON bytes (BLE transport).
     */
    fun encodeNavCommand(cmd: CaneNavCommand): ByteArray =
        gson.toJson(cmd).toByteArray(Charsets.UTF_8)

    /**
     * Serialise a vibration command to JSON bytes (BLE transport).
     */
    fun encodeVibeCommand(cmd: CaneVibeCommand): ByteArray =
        gson.toJson(cmd).toByteArray(Charsets.UTF_8)

    /** Serialise a navigation command to a JSON string (UDP transport). */
    fun encodeNavCommandAsString(cmd: CaneNavCommand): String = gson.toJson(cmd)

    /** Serialise a vibration command to a JSON string (UDP transport). */
    fun encodeVibeCommandAsString(cmd: CaneVibeCommand): String = gson.toJson(cmd)

    /**
     * Decode a status JSON from the cane.
     */
    fun decodeStatus(bytes: ByteArray): Map<String, Any>? = try {
        gson.fromJson(String(bytes, Charsets.UTF_8), Map::class.java) as? Map<String, Any>
    } catch (_: Exception) {
        null
    }
}
