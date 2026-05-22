package com.crowdpath.app.data.models

import com.google.gson.annotations.SerializedName

// ── Enums ──────────────────────────────────────────────────────────────────

enum class NodeType {
    ROOM, INTERSECTION, STAIRS, ELEVATOR, DOOR, ENTRANCE
}

enum class StairDirection {
    UP, DOWN
}

enum class NavType {
    TURN, STAIRS, ARRIVED, STOP
}

/** Explicit turn direction set by the volunteer during mapping.
 *  Takes priority over compass-computed heading in NavigationEngine. */
enum class TurnDirection {
    SHARP_LEFT,   // ~150° turn left
    LEFT,         // ~90° turn left
    SLIGHT_LEFT,  // ~45° turn left
    STRAIGHT,     // continue ahead
    SLIGHT_RIGHT, // ~45° turn right
    RIGHT,        // ~90° turn right
    SHARP_RIGHT   // ~150° turn right
}

// ── Core Data Classes ──────────────────────────────────────────────────────

data class Pose3D(
    val x: Float,
    val y: Float,
    val z: Float
)

data class WiFiReading(
    val ssid: String = "",
    val bssid: String,       // MAC address — most important
    val rssi: Int            // Signal strength in dBm
)

data class BLEReading(
    @SerializedName("device_name") val deviceName: String? = null,
    @SerializedName("mac_address") val macAddress: String,
    val rssi: Int
)

data class Fingerprints(
    val wifi: List<WiFiReading> = emptyList(),
    val ble: List<BLEReading> = emptyList()
)

data class EdgeAttributes(
    @SerializedName("has_stairs") val hasStairs: Boolean = false,
    @SerializedName("stair_count") val stairCount: Int? = null,
    @SerializedName("stair_direction") val stairDirection: StairDirection? = null,
    @SerializedName("is_accessible") val isAccessible: Boolean = true
)

// ── Graph Data ─────────────────────────────────────────────────────────────

data class Node(
    val id: String,
    val label: String,
    val floor: Int = 0,
    val pose: Pose3D,
    val fingerprints: Fingerprints = Fingerprints(),
    val type: NodeType = NodeType.ROOM,
    val timestamp: Long = System.currentTimeMillis()
)

data class Edge(
    val id: String,
    @SerializedName("from_node_id") val fromNodeId: String,
    @SerializedName("to_node_id") val toNodeId: String,
    @SerializedName("length_meters") val lengthMeters: Float,
    val heading: Float,
    val attributes: EdgeAttributes = EdgeAttributes(),
    /** Volunteer-specified turn direction. When set, overrides compass inference. */
    @SerializedName("explicit_turn") val explicitTurnDirection: TurnDirection? = null
)

data class BuildingMapData(
    val nodes: List<Node>,
    val edges: List<Edge>
)

// ── API Request / Response ─────────────────────────────────────────────────

data class BuildingCreate(
    val id: String,
    val name: String,
    @SerializedName("uploaded_by") val uploadedBy: String = "android_user",
    @SerializedName("map_data") val mapData: BuildingMapData,
    @SerializedName("is_public") val isPublic: Boolean = true
)

data class BuildingResponse(
    val id: String,
    val name: String,
    @SerializedName("uploaded_by") val uploadedBy: String,
    @SerializedName("upload_date") val uploadDate: String,
    @SerializedName("map_data") val mapData: BuildingMapData,
    val version: Int,
    @SerializedName("is_public") val isPublic: Boolean
)

data class BuildingListItem(
    val id: String,
    val name: String,
    @SerializedName("uploaded_by") val uploadedBy: String,
    @SerializedName("upload_date") val uploadDate: String,
    val version: Int,
    @SerializedName("node_count") val nodeCount: Int = 0,
    @SerializedName("edge_count") val edgeCount: Int = 0
)

// ── BLE Protocol ───────────────────────────────────────────────────────────

data class CaneNavCommand(
    val cmd: String = "NAV",
    val type: String,        // TURN, STAIRS, ARRIVED, STOP
    val dir: String? = null, // LEFT, RIGHT, UP, DOWN
    @SerializedName("distance_m") val distanceM: Float? = null
)

data class CaneVibeCommand(
    val cmd: String = "SET_VIBE",
    val pattern: Int         // 1–6
)

data class CaneStatus(
    val cmd: String = "STATUS",
    @SerializedName("battery_v") val batteryV: Float,
    @SerializedName("ultra_cm") val ultraCm: Int
)

// ── Vibration Patterns ─────────────────────────────────────────────────────

object VibrationPatterns {
    const val TURN_LEFT = 1
    const val TURN_RIGHT = 2
    const val NEAR_TURN = 3
    const val STAIRS_AHEAD = 4
    const val ARRIVED = 5
    const val STOP_OBSTACLE = 6

    fun describe(pattern: Int): String = when (pattern) {
        TURN_LEFT -> "Turn Left"
        TURN_RIGHT -> "Turn Right"
        NEAR_TURN -> "Near Turn (pulse)"
        STAIRS_AHEAD -> "Stairs Ahead"
        ARRIVED -> "Arrived! 🎉"
        STOP_OBSTACLE -> "STOP — Obstacle"
        else -> "Unknown"
    }
}

// ── Obstacle Resilience ────────────────────────────────────────────────────

data class ObstacleEvent(
    val edgeId: String,
    val buildingId: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ObstacleReport(
    @SerializedName("edge_id") val edgeId: String,
    @SerializedName("building_id") val buildingId: String,
    @SerializedName("timestamp_ms") val timestampMs: Long
)
