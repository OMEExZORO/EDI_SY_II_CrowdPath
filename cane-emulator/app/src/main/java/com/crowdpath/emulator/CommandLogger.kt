package com.crowdpath.emulator

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory command log for the emulator.
 *
 * Stores timestamped entries of all BLE commands received
 * and actions taken (vibration patterns, status broadcasts).
 */
class CommandLogger {

    data class LogEntry(
        val timestamp: String,
        val type: String,       // CMD, VIBE, STATUS, SYSTEM
        val message: String
    )

    private val _entries = mutableListOf<LogEntry>()
    val entries: List<LogEntry> get() = _entries.toList()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Log a received command.
     */
    fun logCommand(raw: String, parsed: Map<String, Any?>) {
        val cmd = parsed["cmd"] as? String ?: "UNKNOWN"
        val description = when (cmd) {
            "NAV" -> {
                val type = parsed["type"] as? String ?: "?"
                val dir = parsed["dir"] as? String ?: ""
                val dist = parsed["distance_m"]?.toString() ?: ""
                "NAV: $type $dir ${if (dist.isNotEmpty()) "${dist}m" else ""}"
            }
            "SET_VIBE" -> {
                val pattern = (parsed["pattern"] as? Number)?.toInt() ?: 0
                "VIBE: Pattern $pattern (${describePattern(pattern)})"
            }
            else -> "CMD: $raw"
        }
        addEntry("CMD", description)
    }

    /**
     * Log a vibration event.
     */
    fun logVibration(pattern: Int) {
        addEntry("VIBE", "Vibrating: Pattern $pattern (${describePattern(pattern)})")
    }

    /**
     * Log a status broadcast.
     */
    fun logStatus(batteryV: Float, ultraCm: Int) {
        addEntry("STATUS", "Sent: battery=${batteryV}V ultra=${ultraCm}cm")
    }

    /**
     * Log a system event.
     */
    fun logSystem(message: String) {
        addEntry("SYSTEM", message)
    }

    fun clear() {
        _entries.clear()
    }

    private fun addEntry(type: String, message: String) {
        _entries.add(0, LogEntry(timeFormat.format(Date()), type, message))
        // Keep only last 200 entries
        if (_entries.size > 200) {
            _entries.removeAt(_entries.lastIndex)
        }
    }

    private fun describePattern(pattern: Int): String = when (pattern) {
        1 -> "Turn Left"
        2 -> "Turn Right"
        3 -> "Near Turn"
        4 -> "Stairs Ahead"
        5 -> "Arrived"
        6 -> "STOP/Obstacle"
        else -> "Unknown"
    }
}
