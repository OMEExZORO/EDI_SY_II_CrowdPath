package com.crowdpath.app.navigation

import android.content.Context
import android.util.Log
import com.crowdpath.app.ble.CaneClient
import com.crowdpath.app.data.api.RetrofitClient
import com.crowdpath.app.data.models.*
import com.crowdpath.app.mapping.WiFiScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.delay

private const val SETTINGS_PREFS      = "crowdpath_settings"
private const val KEY_STEP_LENGTH     = "step_length"
private const val KEY_ACCESSIBLE_ONLY = "accessible_only"

class NavigationEngine(
    private val context: Context,
    private val caneClient: CaneClient? = null,
    private val buildingId: String = "unknown"
) {

    private val pathPlanner = PathPlanner()
    private val pdrTracker = PDRTracker(context)
    private val ttsGuide = TTSGuide(context)
    private val wifiScanner = WiFiScanner(context)
    private val haptic = HapticFeedbackManager(context, caneClient)
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var route: List<Edge> = emptyList()
    private var currentEdgeIndex: Int = 0
    private var stepsOnEdge: Int = 0
    private var distanceOnEdge: Float = 0f
    // Calibrated flat-ground step length — stair edges use 50% of this
    private var calibratedStepLengthM: Float = 0.7f
    // Floor tracking: fire "You are now on Floor X" only when floor number changes
    private var lastAnnouncedFloor: Int = -999  // sentinel = unset

    var isNavigating: Boolean = false; private set

    var onInstructionUpdate: ((instruction: String) -> Unit)? = null
    var onArrival: (() -> Unit)? = null
    var onProgressUpdate: ((edgeIndex: Int, distOnEdge: Float, totalEdges: Int) -> Unit)? = null
    var onReroute: ((newRoute: List<Edge>?) -> Unit)? = null

    // Warning thresholds — computed per-edge so they work for
    // both 4m home corridors and 40m university hallways.
    // earlyWarning = 40% of edge length, min 2m, max 10m
    // nearWarning  = 15% of edge length, min 1m, max 3m
    private fun earlyWarningM(edgeLen: Float) = (edgeLen * 0.40f).coerceIn(2f, 10f)
    private fun nearWarningM(edgeLen: Float)  = (edgeLen * 0.15f).coerceIn(1f, 3f)

    // Guards
    private val minEdgeDurationMs = 2_000L  // edge cannot complete in under 2 seconds
    private var edgeStartTimeMs   = 0L      // System.currentTimeMillis() when edge began
    private var hasAnnouncedEarly = false   // early warning fires once per edge
    private var hasAnnouncedNear  = false   // immediate instruction fires once per edge

    // ── Obstacle resilience state ──────────────────────────────────────
    private val blockedEdgeIds = mutableSetOf<String>()
    private val sessionObstacleLog = mutableListOf<ObstacleEvent>()
    private var obstacleCheckJob: Job? = null
    private var isObstacleCheckPending = false

    // ── Public API ─────────────────────────────────────────────────────

    fun loadMap(map: BuildingMapData, accessibleOnly: Boolean = false) {
        pathPlanner.loadMap(map, accessibleOnly)
    }

    fun startNavigation(startNodeId: String, endNodeId: String): String? {
        route = pathPlanner.findPath(startNodeId, endNodeId, blockedEdgeIds)
        if (route.isEmpty()) {
            ttsGuide.announce("No route found.")
            return null
        }

        // Read user settings from SharedPreferences each time navigation starts
        val prefs        = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val stepLength   = prefs.getFloat(KEY_STEP_LENGTH, 0.7f)
        val accessOnly   = prefs.getBoolean(KEY_ACCESSIBLE_ONLY, false)
        calibratedStepLengthM   = stepLength
        pdrTracker.stepLengthM  = stepLength   // flat ground default
        pathPlanner.loadMap(pathPlanner.currentMap ?: return null, accessOnly)

        currentEdgeIndex = 0
        stepsOnEdge = 0
        distanceOnEdge = 0f
        hasAnnouncedEarly = false
        hasAnnouncedNear  = false
        edgeStartTimeMs   = System.currentTimeMillis()
        isNavigating = true
        // Initialise floor tracker from the start node
        lastAnnouncedFloor = pathPlanner.getNode(route.first().fromNodeId)?.floor ?: 0

        val summary = pathPlanner.routeSummary(route)
        ttsGuide.announce("Route planned: $summary. Starting navigation.")
        announceCurrentEdge()

        // Delay 400ms before registering step listener so buffered sensor
        // events (fired immediately on registration) are ignored.
        engineScope.launch {
            delay(400L)
            pdrTracker.onStep = { _, _ -> onStepDetected() }
            pdrTracker.start()
        }

        return summary
    }

    fun stopNavigation() {
        isNavigating = false
        obstacleCheckJob?.cancel()
        isObstacleCheckPending = false
        pdrTracker.stop()
        ttsGuide.announce("Navigation stopped.")
        sendCaneCommand(CaneNavCommand(type = "STOP"))
    }

    /**
     * Called by CaneClient when the ultrasonic sensor (handle-mounted at ~90–100cm)
     * reports a reading below threshold. Runs a 5-second persistence check before
     * treating it as a real barrier — filters out people walking past.
     */
    fun onObstacleDetected(distanceCm: Int) {
        if (distanceCm >= 40) return
        if (isObstacleCheckPending) return  // already checking, ignore duplicate signals

        isObstacleCheckPending = true
        ttsGuide.announce("Obstacle detected. Please wait.")
        sendCaneVibe(VibrationPatterns.STOP_OBSTACLE)

        obstacleCheckJob?.cancel()
        obstacleCheckJob = engineScope.launch {
            delay(5_000L)

            // After 5 seconds, obstacle is still there — treat as persistent barrier
            isObstacleCheckPending = false
            handlePersistentObstacle()
        }
    }

    /**
     * Called by CaneClient when obstacle clears (reading goes back above threshold).
     * Cancels the persistence check — treated as transient (person walked past).
     */
    fun onObstacleCleared() {
        if (!isObstacleCheckPending) return

        obstacleCheckJob?.cancel()
        isObstacleCheckPending = false
        ttsGuide.announce("Path is clear. Continue forward.")
        Log.i(TAG, "Obstacle cleared within window — treated as transient, no reroute.")
    }

    fun setStepLength(meters: Float) {
        pdrTracker.stepLengthM = meters
    }

    fun shutdown() {
        stopNavigation()
        engineScope.cancel()
        ttsGuide.shutdown()
    }

    // ── Obstacle resilience internals ──────────────────────────────────

    private fun handlePersistentObstacle() {
        if (!isNavigating || currentEdgeIndex >= route.size) return

        val blockedEdge = route[currentEdgeIndex]
        Log.w(TAG, "Persistent obstacle confirmed on edge ${blockedEdge.id}. Blocking and rerouting.")

        // 1. Block this edge for the rest of this session
        blockedEdgeIds.add(blockedEdge.id)

        // 2. Log event (for backend reporting and future map flagging)
        logObstacleEvent(blockedEdge.id)

        // 3. Attempt reroute from current node excluding blocked edges
        val currentNodeId = blockedEdge.fromNodeId
        val destinationNodeId = route.last().toNodeId

        val newRoute = pathPlanner.findPath(currentNodeId, destinationNodeId, blockedEdgeIds)

        if (newRoute.isEmpty()) {
            // Dead end — no alternate path exists
            ttsGuide.announce(
                "All routes to your destination appear blocked. " +
                "Please seek assistance or try again later."
            )
            sendCaneVibe(VibrationPatterns.STOP_OBSTACLE)
            onReroute?.invoke(null)
            Log.e(TAG, "No alternate route found. Navigation suspended.")
        } else {
            // Reroute found
            route = newRoute
            currentEdgeIndex = 0
            stepsOnEdge = 0
            distanceOnEdge = 0f

            val summary = pathPlanner.routeSummary(newRoute)
            ttsGuide.announce("Path is blocked. Alternate route found. $summary")
            announceCurrentEdge()
            onReroute?.invoke(newRoute)
            Log.i(TAG, "Rerouted successfully via ${newRoute.size} edges.")
        }
    }

    private fun logObstacleEvent(edgeId: String) {
        val event = ObstacleEvent(edgeId = edgeId, buildingId = buildingId)
        sessionObstacleLog.add(event)
        Log.i(TAG, "Obstacle event logged: edge=$edgeId, total session events=${sessionObstacleLog.size}")

        // Report to backend asynchronously — does not block navigation
        engineScope.launch(Dispatchers.IO) {
            try {
                val report = ObstacleReport(
                    edgeId = edgeId,
                    buildingId = buildingId,
                    timestampMs = event.timestampMs
                )
                RetrofitClient.api.reportObstacle(report)
                Log.i(TAG, "Obstacle report sent to backend for edge $edgeId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send obstacle report (offline?): ${e.message}")
                // Silent fail — navigation is not affected by backend connectivity
            }
        }
    }

    // ── Step tracking ──────────────────────────────────────────────────

    private fun onStepDetected() {
        if (!isNavigating || currentEdgeIndex >= route.size) return
        if (isObstacleCheckPending) return

        stepsOnEdge++
        distanceOnEdge = stepsOnEdge * pdrTracker.stepLengthM
        val currentEdge = route[currentEdgeIndex]
        val edgeLen = currentEdge.lengthMeters
        val early   = earlyWarningM(edgeLen)
        val near    = nearWarningM(edgeLen)

        onProgressUpdate?.invoke(currentEdgeIndex, distanceOnEdge, route.size)

        val remainingOnEdge = (edgeLen - distanceOnEdge).coerceAtLeast(0f)

        // Periodic distance countdown — every 10 steps, quietly tell the user
        // how far they still have to go. Only fires in the "free walking" zone
        // (before the early-warning zone kicks in) so it never overlaps a turn.
        // Minimum 3m remaining so we don't say "1 meter remaining" and then
        // immediately fire the proper near-turn announcement.
        if (stepsOnEdge % 10 == 0 && remainingOnEdge > early && remainingOnEdge > 3f) {
            ttsGuide.queue("${remainingOnEdge.toInt()} meters remaining")
        }

        val onStairs = currentEdge.attributes.hasStairs

        // Early warning: approaching the turn — skip on stair edges
        // (heading is unreliable on stairs; the stair type is already announced)
        if (!hasAnnouncedEarly && remainingOnEdge in near..early && !onStairs) {
            hasAnnouncedEarly = true
            prepareNextInstruction(remainingOnEdge)
        }

        // Immediate instruction: at the turn point — skip on stair edges
        if (!hasAnnouncedNear && remainingOnEdge in 0f..near && !onStairs) {
            hasAnnouncedNear = true
            fireImmediateInstruction()
        }

        // Advance: distance covered AND minimum time elapsed (prevents instant
        // completion on very short edges like doorway-to-junction < 1m)
        val timeOnEdge = System.currentTimeMillis() - edgeStartTimeMs
        if (distanceOnEdge >= edgeLen && timeOnEdge >= minEdgeDurationMs) {
            advanceToNextEdge()
        }
    }

    private fun prepareNextInstruction(remaining: Float) {
        val nextEdge = route.getOrNull(currentEdgeIndex + 1) ?: return
        val direction = inferDirection(route[currentEdgeIndex], nextEdge)
        val msg = "In ${remaining.toInt()} meters, $direction"
        ttsGuide.queue(msg)
        onInstructionUpdate?.invoke(msg)
        sendCaneVibe(VibrationPatterns.NEAR_TURN)
    }

    private fun fireImmediateInstruction() {
        val nextEdge = route.getOrNull(currentEdgeIndex + 1) ?: return
        val direction = inferDirection(route[currentEdgeIndex], nextEdge)
        val msg = "$direction NOW"
        ttsGuide.announce(msg)
        onInstructionUpdate?.invoke(msg)

        val vibePattern = when {
            nextEdge.attributes.hasStairs -> VibrationPatterns.STAIRS_AHEAD
            direction.contains("left", true) -> VibrationPatterns.TURN_LEFT
            direction.contains("right", true) -> VibrationPatterns.TURN_RIGHT
            else -> VibrationPatterns.NEAR_TURN
        }
        sendCaneVibe(vibePattern)

        val dir = when {
            nextEdge.attributes.hasStairs ->
                if (nextEdge.attributes.stairDirection?.name == "UP") "UP" else "DOWN"
            direction.contains("left", true) -> "LEFT"
            direction.contains("right", true) -> "RIGHT"
            else -> null
        }
        val type = if (nextEdge.attributes.hasStairs) "STAIRS" else "TURN"
        sendCaneCommand(CaneNavCommand(type = type, dir = dir, distanceM = nextEdge.lengthMeters))
    }

    private fun advanceToNextEdge() {
        val completedEdge = route.getOrNull(currentEdgeIndex)

        currentEdgeIndex++
        stepsOnEdge = 0
        distanceOnEdge = 0f
        hasAnnouncedEarly = false
        hasAnnouncedNear  = false
        edgeStartTimeMs   = System.currentTimeMillis()

        // Adjust step length for stair vs flat terrain
        val nextEdge = route.getOrNull(currentEdgeIndex)
        pdrTracker.stepLengthM = if (nextEdge?.attributes?.hasStairs == true) {
            calibratedStepLengthM * 0.5f
        } else {
            calibratedStepLengthM
        }

        // Floor-change announcement: fires when we complete a stair edge and
        // arrive at a node whose floor number differs from the last announced floor.
        // Works for any number of stair sets (2, 3, 4+) between floors:
        //   - Landing between stair sets = same floor as previous node → silent
        //   - Top/bottom of full floor transition → announces new floor
        if (completedEdge?.attributes?.hasStairs == true) {
            val arrivedNodeId = completedEdge.toNodeId
            val arrivedFloor  = pathPlanner.getNode(arrivedNodeId)?.floor
            if (arrivedFloor != null && arrivedFloor != lastAnnouncedFloor) {
                lastAnnouncedFloor = arrivedFloor
                val floorLabel = when {
                    arrivedFloor == 0 -> "ground floor"
                    arrivedFloor > 0  -> "floor $arrivedFloor"
                    else              -> "basement level ${-arrivedFloor}"
                }
                ttsGuide.announce("You are now on the $floorLabel.")
                sendCaneVibe(VibrationPatterns.ARRIVED)  // one long buzz = floor reached
                Log.i(TAG, "Floor change: now on floor $arrivedFloor")
            }
        }

        confirmWithWiFi()

        if (currentEdgeIndex >= route.size) {
            ttsGuide.announce("You have arrived at your destination.")
            sendCaneVibe(VibrationPatterns.ARRIVED)
            sendCaneCommand(CaneNavCommand(type = "ARRIVED"))
            isNavigating = false
            pdrTracker.stop()
            onArrival?.invoke()
        } else {
            announceCurrentEdge()
        }
    }

    private fun announceCurrentEdge() {
        val edge = route[currentEdgeIndex]
        val targetNode = pathPlanner.getNode(edge.toNodeId)
        val label = targetNode?.label ?: "next checkpoint"

        // Figure out the direction that got us onto this edge
        // (compare incoming direction from previous edge, or use edge heading vs north)
        val incomingDirection: String = if (currentEdgeIndex == 0) {
            // First edge: tell user to head in the direction of the first segment
            val headingDesc = when ((edge.heading.toInt() + 360) % 360) {
                in 315..360, in 0..44  -> "heading north"
                in 45..134             -> "heading east"
                in 135..224            -> "heading south"
                else                   -> "heading west"
            }
            headingDesc
        } else {
            val prevEdge = route[currentEdgeIndex - 1]
            inferDirection(prevEdge, edge)  // e.g. "turn right", "turn left", "continue straight"
        }

        val msg = if (edge.attributes.hasStairs) {
            val dir   = edge.attributes.stairDirection?.name?.lowercase() ?: ""
            val count = edge.attributes.stairCount ?: "some"
            "$incomingDirection — stairs ahead, $count steps going $dir"
        } else {
            "$incomingDirection — walk ${edge.lengthMeters.toInt()} meters toward $label"
        }
        ttsGuide.announce(msg)
        onInstructionUpdate?.invoke(msg)
    }

    private fun confirmWithWiFi() {
        if (currentEdgeIndex == 0 || currentEdgeIndex > route.size) return
        val prevEdge = route[currentEdgeIndex - 1]
        val node: Node = pathPlanner.getNode(prevEdge.toNodeId) ?: return

        val currentScan = wifiScanner.takeSnapshot()
        val confidence = FingerprintMatcher.matchFingerprint(currentScan, node.fingerprints.wifi)

        if (confidence >= FingerprintMatcher.CONFIRM_THRESHOLD) {
            Log.i(TAG, "WiFi confirmed at ${node.label} (conf=$confidence)")
            ttsGuide.queue("Confirmed at ${node.label}")
            pdrTracker.resetSteps()
        } else if (confidence < FingerprintMatcher.REJECT_THRESHOLD) {
            Log.w(TAG, "WiFi mismatch at ${node.label} (conf=$confidence)")
        }
    }

    /**
     * Determine the turn direction from [current] edge to [next] edge.
     *
     * Priority:
     *  1. [Edge.explicitTurnDirection] — set by volunteer during mapping. 100% reliable.
     *  2. Compass heading delta — fallback when no explicit direction was recorded.
     */
    private fun inferDirection(current: Edge, next: Edge): String {
        // ── Priority 1: volunteer-specified direction ─────────────────────
        next.explicitTurnDirection?.let { dir ->
            return when (dir) {
                com.crowdpath.app.data.models.TurnDirection.SHARP_LEFT  -> "sharp left"
                com.crowdpath.app.data.models.TurnDirection.LEFT        -> "turn left"
                com.crowdpath.app.data.models.TurnDirection.SLIGHT_LEFT -> "slight left"
                com.crowdpath.app.data.models.TurnDirection.STRAIGHT    -> "continue straight"
                com.crowdpath.app.data.models.TurnDirection.SLIGHT_RIGHT-> "slight right"
                com.crowdpath.app.data.models.TurnDirection.RIGHT       -> "turn right"
                com.crowdpath.app.data.models.TurnDirection.SHARP_RIGHT -> "sharp right"
            }
        }

        // ── Priority 2: compass heading delta (fallback) ──────────────────
        val delta = (next.heading - current.heading + 360f) % 360f
        return when {
            delta < 20f || delta > 340f -> "continue straight"
            delta in 20f..60f           -> "slight right"
            delta in 60f..120f          -> "turn right"
            delta in 120f..150f         -> "sharp right"
            delta in 210f..240f         -> "sharp left"
            delta in 240f..300f         -> "turn left"
            delta in 300f..340f         -> "slight left"
            else                        -> "turn around"
        }
    }

    private fun sendCaneCommand(cmd: CaneNavCommand) {
        haptic.sendNavCommand(cmd)
    }

    private fun sendCaneVibe(pattern: Int) {
        haptic.firePattern(pattern)
    }

    companion object {
        private const val TAG = "NavigationEngine"
    }
}
