package com.crowdpath.emulator

import java.util.Timer
import java.util.TimerTask

/**
 * Periodically broadcasts cane status (battery voltage, ultrasonic distance)
 * to the connected phone via BLE notifications.
 *
 * Battery decays slowly to simulate real usage.
 */
class StatusBroadcaster(
    private val bleServer: BLEServer,
    private val logger: CommandLogger
) {

    var batteryVoltage: Float = 3.8f
    var obstacleDistanceCm: Int = 100
    var simulateDrain: Boolean = false

    private var timer: Timer? = null
    private var isRunning = false

    /**
     * Start broadcasting status every [intervalMs] milliseconds.
     */
    fun start(intervalMs: Long = 5000L) {
        if (isRunning) return
        isRunning = true

        timer = Timer("StatusBroadcaster", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (simulateDrain) {
                        batteryVoltage = (batteryVoltage - 0.01f).coerceAtLeast(2.8f)
                    }

                    bleServer.sendStatus(batteryVoltage, obstacleDistanceCm)
                    logger.logStatus(batteryVoltage, obstacleDistanceCm)
                }
            }, 0L, intervalMs)
        }
        logger.logSystem("Status broadcaster started (interval=${intervalMs}ms)")
    }

    /**
     * Stop broadcasting.
     */
    fun stop() {
        timer?.cancel()
        timer = null
        isRunning = false
    }

    /**
     * Trigger an immediate obstacle alert (ultra_cm < 30).
     */
    fun triggerObstacleAlert() {
        val savedDistance = obstacleDistanceCm
        obstacleDistanceCm = 15  // simulate close obstacle
        bleServer.sendStatus(batteryVoltage, obstacleDistanceCm)
        logger.logSystem("⚠️ Obstacle alert triggered! (15cm)")
        // Restore after a brief moment — the phone should handle the alert
        obstacleDistanceCm = savedDistance
    }
}
