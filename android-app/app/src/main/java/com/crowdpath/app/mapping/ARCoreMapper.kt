package com.crowdpath.app.mapping

import android.content.Context
import android.util.Log
import com.crowdpath.app.data.models.Pose3D
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.Config

/**
 * Manages an ARCore session and records trajectory poses.
 *
 * Call [start] to begin tracking and [stop] when mapping is complete.
 * Poses are sampled via [getCurrentPose] whenever the volunteer marks a node.
 */
class ARCoreMapper(private val context: Context) {

    private var session: Session? = null
    private val trajectoryPoints = mutableListOf<Pose3D>()

    var isTracking: Boolean = false
        private set

    /**
     * Create and configure an ARCore session.
     * Must be called from an Activity with a valid GL context.
     */
    fun start() {
        try {
            session = Session(context).apply {
                val config = Config(this)
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                configure(config)
                resume()
            }
            isTracking = true
            trajectoryPoints.clear()
            Log.i(TAG, "ARCore session started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ARCore: ${e.message}")
            isTracking = false
        }
    }

    /**
     * Sample the current ARCore pose and return as [Pose3D].
     * Returns `null` if tracking is lost.
     */
    fun getCurrentPose(): Pose3D? {
        val sess = session ?: return null
        return try {
            val frame: Frame = sess.update()
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return null

            val translation = camera.pose.translation
            val pose = Pose3D(
                x = translation[0],
                y = translation[1],
                z = translation[2]
            )
            trajectoryPoints.add(pose)
            pose
        } catch (e: Exception) {
            Log.e(TAG, "Pose update error: ${e.message}")
            null
        }
    }

    /**
     * Distance (m) between two 3-D poses.
     */
    fun distanceBetween(a: Pose3D, b: Pose3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Return all trajectory points recorded so far.
     */
    fun getTrajectory(): List<Pose3D> = trajectoryPoints.toList()

    /**
     * Total trajectory distance (m).
     */
    fun getTotalDistance(): Float {
        if (trajectoryPoints.size < 2) return 0f
        var total = 0f
        for (i in 1 until trajectoryPoints.size) {
            total += distanceBetween(trajectoryPoints[i - 1], trajectoryPoints[i])
        }
        return total
    }

    /**
     * Pause and destroy the ARCore session.
     */
    fun stop() {
        session?.pause()
        session?.close()
        session = null
        isTracking = false
        Log.i(TAG, "ARCore session stopped | ${trajectoryPoints.size} trajectory points recorded")
    }

    companion object {
        private const val TAG = "ARCoreMapper"
    }
}
