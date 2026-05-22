package com.crowdpath.app.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs

/**
 * Pedestrian Dead Reckoning — step counting + heading tracking.
 *
 * Uses Android's [Sensor.TYPE_STEP_DETECTOR] for step events and
 * [Sensor.TYPE_ROTATION_VECTOR] for compass heading.
 *
 * Call [start] to begin tracking and [stop] when navigation ends.
 */
class PDRTracker(context: Context) {

    // ── Config ─────────────────────────────────────────────────────────
    var stepLengthM: Float = 0.7f  // calibratable
    private val turnThresholdDeg: Float = 15f

    // ── State ──────────────────────────────────────────────────────────
    var totalSteps: Int = 0; private set
    var totalDistance: Float = 0f; private set
    var currentHeading: Float = 0f; private set  // degrees 0-360

    var onStep: ((steps: Int, distance: Float) -> Unit)? = null
    var onTurnDetected: ((headingDeg: Float) -> Unit)? = null

    private var previousHeading: Float = 0f
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // ── Smoothing ──────────────────────────────────────────────────────
    private val headingBuffer = FloatArray(5)
    private var headingIndex = 0
    private val orientationAngles = FloatArray(3)
    private val rotationMatrix = FloatArray(9)

    // ── Sensor listeners ───────────────────────────────────────────────

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            totalSteps++
            totalDistance = totalSteps * stepLengthM
            onStep?.invoke(totalSteps, totalDistance)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                .let { if (it < 0) it + 360f else it }

            // Moving-average smoothing
            headingBuffer[headingIndex % headingBuffer.size] = azimuthDeg
            headingIndex++
            currentHeading = headingBuffer.average().toFloat()

            // Detect turn
            val delta = abs(currentHeading - previousHeading)
            if (delta > turnThresholdDeg && delta < 350f) {
                onTurnDetected?.invoke(currentHeading)
            }
            previousHeading = currentHeading
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun start() {
        totalSteps = 0
        totalDistance = 0f
        headingIndex = 0
        stepSensor?.let {
            sensorManager.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        } ?: Log.w(TAG, "Step detector sensor not available")
        rotationSensor?.let {
            sensorManager.registerListener(rotationListener, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(TAG, "Rotation vector sensor not available")
        Log.i(TAG, "PDR tracking started (step length = ${stepLengthM}m)")
    }

    fun resetSteps() {
        totalSteps = 0
        totalDistance = 0f
    }

    fun stop() {
        sensorManager.unregisterListener(stepListener)
        sensorManager.unregisterListener(rotationListener)
        Log.i(TAG, "PDR tracking stopped — $totalSteps steps / ${totalDistance}m")
    }

    companion object {
        private const val TAG = "PDRTracker"
    }
}
