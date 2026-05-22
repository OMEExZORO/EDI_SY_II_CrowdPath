package com.crowdpath.app.mapping

import com.crowdpath.app.data.models.Edge
import com.crowdpath.app.data.models.EdgeAttributes
import com.crowdpath.app.data.models.Node
import com.crowdpath.app.data.models.BuildingMapData
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Accumulates [Node]s and auto-generates [Edge]s during a mapping session.
 *
 * Nodes are added sequentially as the volunteer walks through the building.
 * An edge is created between each consecutive pair of nodes, with distance
 * computed from ARCore poses and heading derived from the X/Y delta.
 */
class GraphBuilder {

    private val nodes = mutableListOf<Node>()
    private val edges = mutableListOf<Edge>()

    val nodeCount: Int get() = nodes.size
    val edgeCount: Int get() = edges.size

    /**
     * Add a new node and auto-create an edge from the previous node.
     *
     * @param node          The checkpoint just recorded.
     * @param explicitTurn  Volunteer's button selection for the direction they
     *                      just walked to reach [node]. When non-null, this
     *                      overrides compass-based inference in NavigationEngine.
     */
    fun addNode(node: Node, explicitTurn: com.crowdpath.app.data.models.TurnDirection? = null) {
        if (nodes.isNotEmpty()) {
            val prev = nodes.last()
            val dx = node.pose.x - prev.pose.x
            val dy = node.pose.y - prev.pose.y
            val dz = node.pose.z - prev.pose.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            val heading = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                .let { if (it < 0) it + 360f else it }

            val hasStairs = node.type == com.crowdpath.app.data.models.NodeType.STAIRS ||
                    prev.type == com.crowdpath.app.data.models.NodeType.STAIRS
            val goingUp = node.pose.z > prev.pose.z

            edges.add(
                Edge(
                    id = "e_${UUID.randomUUID().toString().take(8)}",
                    fromNodeId = prev.id,
                    toNodeId = node.id,
                    lengthMeters = distance,
                    heading = heading,
                    attributes = EdgeAttributes(
                        hasStairs = hasStairs,
                        stairCount = if (hasStairs) estimateStairs(dz) else null,
                        stairDirection = if (hasStairs) {
                            if (goingUp) com.crowdpath.app.data.models.StairDirection.UP
                            else com.crowdpath.app.data.models.StairDirection.DOWN
                        } else null,
                        isAccessible = !hasStairs
                    ),
                    explicitTurnDirection = explicitTurn
                )
            )
        }
        nodes.add(node)
    }

    /**
     * Build the final [BuildingMapData] from accumulated nodes and edges.
     */
    fun build(): BuildingMapData = BuildingMapData(
        nodes = nodes.toList(),
        edges = edges.toList()
    )

    /**
     * Total distance covered by all edges (m).
     */
    fun getTotalDistance(): Float = edges.sumOf { it.lengthMeters.toDouble() }.toFloat()

    /**
     * Reset the builder for a new mapping session.
     */
    fun clear() {
        nodes.clear()
        edges.clear()
    }

    /**
     * Rough estimate: ~0.18m per step (standard residential stair).
     */
    private fun estimateStairs(heightDiff: Float): Int {
        return (kotlin.math.abs(heightDiff) / 0.18f).toInt().coerceAtLeast(1)
    }
}
