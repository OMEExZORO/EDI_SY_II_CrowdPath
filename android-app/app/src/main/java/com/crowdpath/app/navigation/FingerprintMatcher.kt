package com.crowdpath.app.navigation

import com.crowdpath.app.data.models.WiFiReading
import kotlin.math.abs
import kotlin.math.max

/**
 * Compares a live WiFi scan against a stored fingerprint to determine
 * if the user is near a specific node.
 */
object FingerprintMatcher {

    /**
     * Calculate a confidence score (0.0 – 1.0) that the [currentScan]
     * matches the [storedFingerprint].
     *
     * Algorithm:
     *  1. Find common access points (by BSSID).
     *  2. Compute average RSSI difference.
     *  3. Confidence = max(0, 1 − avgDiff / 50).
     */
    fun matchFingerprint(
        currentScan: List<WiFiReading>,
        storedFingerprint: List<WiFiReading>
    ): Float {
        val storedByBssid = storedFingerprint.associateBy { it.bssid }

        val commonDiffs = mutableListOf<Int>()
        for (reading in currentScan) {
            val stored = storedByBssid[reading.bssid]
            if (stored != null) {
                commonDiffs.add(abs(reading.rssi - stored.rssi))
            }
        }

        if (commonDiffs.isEmpty()) return 0f

        val avgDiff = commonDiffs.average()
        return max(0f, 1f - (avgDiff / 50.0).toFloat())
    }

    /**
     * Higher-confidence threshold for resetting PDR error.
     */
    const val CONFIRM_THRESHOLD = 0.7f

    /**
     * Lower threshold — stay with PDR only.
     */
    const val REJECT_THRESHOLD = 0.3f
}
