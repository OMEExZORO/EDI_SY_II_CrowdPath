package com.crowdpath.app.navigation

import android.util.Log
import com.crowdpath.app.data.models.*
import java.util.PriorityQueue
import kotlin.math.min

/**
 * D* Lite pathfinder for dynamic indoor navigation.
 *
 * Key advantage over A*: when an edge becomes blocked (admin block or
 * real-time obstacle), updateEdgeCost() repairs only the affected cells
 * in the priority queue. The user gets a new route in microseconds rather
 * than a full re-search from scratch.
 *
 * Reference: Koenig & Likhachev, "D* Lite", AAAI 2002.
 *
 * Terminology:
 *   s_start  = current user position node
 *   s_goal   = destination node
 *   g(s)     = cost of shortest known path from s to goal (reverse search)
 *   rhs(s)   = one-step lookahead cost
 *   U        = priority queue of inconsistent nodes
 */
class PathPlanner {

    // Graph storage
    private val nodes = mutableMapOf<String, Node>()
    private val forwardEdges = mutableMapOf<String, MutableList<Edge>>()   // fromNodeId → edges
    private val reverseEdges = mutableMapOf<String, MutableList<Edge>>()   // toNodeId → edges

    /** The most recently loaded map — held so callers can re-apply settings without re-passing data. */
    var currentMap: BuildingMapData? = null
        private set

    // D* Lite state
    private val g = mutableMapOf<String, Double>()
    private val rhs = mutableMapOf<String, Double>()
    private val U = PriorityQueue<Pair<String, Pair<Double, Double>>>(
        compareBy({ it.second.first }, { it.second.second })
    )
    private var goalNodeId: String = ""
    private var startNodeId: String = ""
    private var km: Double = 0.0
    private val blockedEdges = mutableSetOf<String>()
    private var accessibleOnly = false

    companion object {
        private const val TAG = "PathPlanner"
        private const val INF = Double.MAX_VALUE / 2
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun loadMap(map: BuildingMapData, accessibleOnly: Boolean = false) {
        this.accessibleOnly = accessibleOnly
        this.currentMap = map
        nodes.clear()
        forwardEdges.clear()
        reverseEdges.clear()

        map.nodes.forEach { nodes[it.id] = it }
        map.edges.forEach { edge ->
            if (!accessibleOnly || edge.attributes.isAccessible) {
                forwardEdges.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge)
                reverseEdges.getOrPut(edge.toNodeId) { mutableListOf() }.add(edge)
                // Undirected graph: add reverse direction with inverted turn direction
                // so "turn right" on A→B becomes "turn left" on B→A.
                val reverseEdge = edge.copy(
                    fromNodeId = edge.toNodeId,
                    toNodeId = edge.fromNodeId,
                    heading = (edge.heading + 180f) % 360f,
                    isReversed = true,
                    explicitTurnDirection = edge.explicitTurnDirection?.inverted()
                )
                forwardEdges.getOrPut(edge.toNodeId) { mutableListOf() }.add(reverseEdge)
                reverseEdges.getOrPut(edge.fromNodeId) { mutableListOf() }.add(reverseEdge)
            }
        }
        Log.i(TAG, "Map loaded: ${nodes.size} nodes, ${map.edges.size} edges")
    }

    /**
     * Plan a route from start to goal, excluding any blocked edges.
     */
    fun findPath(
        startNodeId: String,
        endNodeId: String,
        blockedEdgeIds: Set<String> = emptySet()
    ): List<Edge> {
        if (!nodes.containsKey(startNodeId) || !nodes.containsKey(endNodeId)) {
            Log.e(TAG, "Start or end node not found in map.")
            return emptyList()
        }

        blockedEdges.clear()
        blockedEdges.addAll(blockedEdgeIds)

        this.startNodeId = startNodeId
        this.goalNodeId = endNodeId
        km = 0.0

        // Initialise D* Lite
        g.clear()
        rhs.clear()
        U.clear()
        nodes.keys.forEach { id ->
            g[id] = INF
            rhs[id] = INF
        }
        rhs[goalNodeId] = 0.0
        U.add(Pair(goalNodeId, calculateKey(goalNodeId)))

        computeShortestPath()

        return if (g[startNodeId] == INF) {
            Log.w(TAG, "No path from $startNodeId to $endNodeId")
            emptyList()
        } else {
            extractPath(startNodeId, endNodeId)
        }
    }

    /**
     * Block or unblock an edge dynamically without full replanning.
     * After calling, call findPath() again with the updated blockedEdgeIds.
     */
    fun updateEdgeCost(edgeId: String, blocked: Boolean) {
        if (blocked) {
            blockedEdges.add(edgeId)
        } else {
            blockedEdges.remove(edgeId)
        }

        // Find all edges affected by this change and update their nodes
        nodes.values.forEach { node ->
            (forwardEdges[node.id] ?: emptyList())
                .filter { it.id == edgeId }
                .forEach { edge ->
                    updateNode(edge.fromNodeId)
                }
        }
        computeShortestPath()
        Log.i(TAG, "Edge $edgeId ${if (blocked) "blocked" else "unblocked"}, graph repaired.")
    }

    fun getNode(nodeId: String): Node? = nodes[nodeId]

    fun routeSummary(route: List<Edge>): String {
        if (route.isEmpty()) return "No route found"
        val totalDist = route.sumOf { it.lengthMeters.toDouble() }
        val stairs = route.count { it.attributes.hasStairs }
        val turns = route.size - 1
        return "${totalDist.toInt()} meters, $turns turns, $stairs staircase(s)"
    }

    // ── D* Lite Core ────────────────────────────────────────────────────

    private fun calculateKey(nodeId: String): Pair<Double, Double> {
        val gVal = g[nodeId] ?: INF
        val rhsVal = rhs[nodeId] ?: INF
        val h = heuristic(nodeId, startNodeId)
        return Pair(
            min(gVal, rhsVal) + h + km,
            min(gVal, rhsVal)
        )
    }

    private fun computeShortestPath() {
        var iterations = 0
        val maxIterations = nodes.size * 3 // safety bound

        while (U.isNotEmpty() && iterations < maxIterations) {
            val (u, keyU) = U.peek() ?: break
            val keyStart = calculateKey(startNodeId)

            val gStart = g[startNodeId] ?: INF
            val rhsStart = rhs[startNodeId] ?: INF

            if (compareKeys(keyU, keyStart) >= 0 && rhsStart == gStart) break

            U.poll()
            iterations++
            val gU = g[u] ?: INF
            val rhsU = rhs[u] ?: INF

            val currentKey = calculateKey(u)
            if (compareKeys(keyU, currentKey) < 0) {
                // Key is outdated — reinsert with new key
                U.add(Pair(u, currentKey))
            } else if (gU > rhsU) {
                g[u] = rhsU
                getPredecessors(u).forEach { pred -> updateNode(pred) }
            } else {
                g[u] = INF
                updateNode(u)
                getPredecessors(u).forEach { pred -> updateNode(pred) }
            }
        }
    }

    private fun updateNode(nodeId: String) {
        if (nodeId != goalNodeId) {
            rhs[nodeId] = getSuccessors(nodeId).minOfOrNull { (successorId, cost) ->
                val gSucc = g[successorId] ?: INF
                if (gSucc >= INF) INF else gSucc + cost
            } ?: INF
        }

        U.removeIf { it.first == nodeId }
        val gVal = g[nodeId] ?: INF
        val rhsVal = rhs[nodeId] ?: INF
        if (gVal != rhsVal) {
            U.add(Pair(nodeId, calculateKey(nodeId)))
        }
    }

    /**
     * Returns (successorNodeId, edgeCost) pairs from nodeId.
     * Blocked edges have infinite cost.
     */
    private fun getSuccessors(nodeId: String): List<Pair<String, Double>> {
        return (forwardEdges[nodeId] ?: emptyList()).map { edge ->
            val cost = if (blockedEdges.contains(edge.id)) INF
                       else edge.lengthMeters.toDouble()
            Pair(edge.toNodeId, cost)
        }
    }

    /**
     * Returns predecessor node IDs (nodes with edges leading TO nodeId).
     */
    private fun getPredecessors(nodeId: String): List<String> {
        return (reverseEdges[nodeId] ?: emptyList())
            .filter { !blockedEdges.contains(it.id) }
            .map { it.fromNodeId }
    }

    private fun heuristic(fromId: String, toId: String): Double {
        val a = nodes[fromId]?.pose ?: return 0.0
        val b = nodes[toId]?.pose ?: return 0.0
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        return Math.sqrt(dx * dx + dy * dy)
    }

    private fun compareKeys(a: Pair<Double, Double>, b: Pair<Double, Double>): Int {
        return when {
            a.first < b.first -> -1
            a.first > b.first -> 1
            a.second < b.second -> -1
            a.second > b.second -> 1
            else -> 0
        }
    }

    /**
     * Follow the lowest-cost successor chain from start to goal.
     */
    private fun extractPath(startId: String, goalId: String): List<Edge> {
        val path = mutableListOf<Edge>()
        var current = startId
        val visited = mutableSetOf<String>()

        while (current != goalId) {
            if (visited.contains(current)) {
                Log.e(TAG, "Cycle detected during path extraction at $current")
                return emptyList()
            }
            visited.add(current)

            val bestEdge = (forwardEdges[current] ?: emptyList())
                .filter { !blockedEdges.contains(it.id) }
                .minByOrNull { edge ->
                    val gSucc = g[edge.toNodeId] ?: INF
                    if (gSucc >= INF) INF else gSucc + edge.lengthMeters
                }

            if (bestEdge == null || (g[bestEdge.toNodeId] ?: INF) >= INF) {
                Log.e(TAG, "No forward edge from $current — path broken")
                return emptyList()
            }

            path.add(bestEdge)
            current = bestEdge.toNodeId
        }

        return path
    }
}
