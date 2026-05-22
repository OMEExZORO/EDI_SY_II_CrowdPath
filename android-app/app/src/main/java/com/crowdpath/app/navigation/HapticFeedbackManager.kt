package com.crowdpath.app.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.crowdpath.app.ble.CaneClient
import com.crowdpath.app.data.models.CaneNavCommand
import com.crowdpath.app.data.models.CaneVibeCommand
import com.crowdpath.app.data.models.VibrationPatterns

/**
 * HapticFeedbackManager
 * =====================
 * Single interface for all haptic/vibration output.
 *
 * Behaviour:
 *  - If a [CaneClient] is provided AND connected → sends BLE command to cane (primary).
 *  - Always ALSO fires phone haptic feedback as local confirmation — useful for
 *    demo/testing without the cane, and provides instant tactile confirmation
 *    even when the cane is connected.
 *
 * Phone vibration patterns are designed to match the cane patterns by feel:
 *  1 = Turn Left   → 2 long pulses (500ms on / 200ms off)
 *  2 = Turn Right  → 3 short pulses (150ms on / 100ms off)
 *  3 = Near Turn   → 1 medium pulse (300ms)
 *  4 = Stairs Ahead→ 4 slow pulses  (150ms on / 350ms off)
 *  5 = Arrived     → 1 long sustained buzz (800ms)
 *  6 = STOP/Obstacle → rapid urgent bursts (60ms on / 60ms off × 6)
 */
class HapticFeedbackManager(
    private val context: Context,
    private val caneClient: CaneClient? = null
) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // ── Pattern waveforms: [delay, on, off, on, off, ...] in ms ──────────────
    // VibrationEffect.createWaveform(timings, amplitudes, repeat=-1)
    // Amplitudes 0=off, 255=full, or -1 for DEFAULT_AMPLITUDE

    private val patterns: Map<Int, Pair<LongArray, IntArray>> = mapOf(
        // Pattern 1: Turn Left — 2 long deliberate pulses
        VibrationPatterns.TURN_LEFT to Pair(
            longArrayOf(0, 500, 200, 500),
            intArrayOf(0, 255, 0, 255)
        ),
        // Pattern 2: Turn Right — 3 short rapid pulses
        VibrationPatterns.TURN_RIGHT to Pair(
            longArrayOf(0, 150, 100, 150, 100, 150),
            intArrayOf(0, 255, 0, 255, 0, 255)
        ),
        // Pattern 3: Near Turn — 1 medium warning pulse
        VibrationPatterns.NEAR_TURN to Pair(
            longArrayOf(0, 300),
            intArrayOf(0, 200)
        ),
        // Pattern 4: Stairs Ahead — 4 slow rhythmic pulses
        VibrationPatterns.STAIRS_AHEAD to Pair(
            longArrayOf(0, 150, 350, 150, 350, 150, 350, 150),
            intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
        ),
        // Pattern 5: Arrived — 1 long celebratory buzz
        VibrationPatterns.ARRIVED to Pair(
            longArrayOf(0, 800),
            intArrayOf(0, 255)
        ),
        // Pattern 6: STOP / Obstacle — rapid urgent SOS bursts
        VibrationPatterns.STOP_OBSTACLE to Pair(
            longArrayOf(0, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60),
            intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255)
        )
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fire a vibration pattern.
     * Routes to cane via BLE if connected; always fires phone haptic.
     */
    fun firePattern(pattern: Int) {
        Log.d(TAG, "firePattern($pattern) — caneConnected=${caneClient?.isConnected}")
        vibratePhone(pattern)
        if (caneClient?.isConnected == true) {
            caneClient.sendVibrationCommand(CaneVibeCommand(pattern = pattern))
        }
    }

    /**
     * Send a structured navigation command to the cane (BLE only).
     * Phone has no equivalent — this is cane-specific metadata.
     * Silently skipped if cane is not connected.
     */
    fun sendNavCommand(cmd: CaneNavCommand) {
        if (caneClient?.isConnected == true) {
            caneClient.sendNavigationCommand(cmd)
        }
    }

    /**
     * Cancel any ongoing phone vibration (e.g., on navigation stop).
     */
    fun cancel() {
        vibrator.cancel()
    }

    // ── Phone vibration ───────────────────────────────────────────────────────

    private fun vibratePhone(pattern: Int) {
        if (!vibrator.hasVibrator()) {
            Log.w(TAG, "Device has no vibrator")
            return
        }

        val (timings, amplitudes) = patterns[pattern] ?: run {
            Log.w(TAG, "Unknown pattern $pattern")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator.hasAmplitudeControl()) {
                    // Full amplitude control — best experience
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(timings, amplitudes, -1)
                    )
                } else {
                    // No amplitude control — binary on/off, still correct timing
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(timings, -1)
                    )
                }
            } else {
                // API < 26 fallback
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
            Log.d(TAG, "Phone vibration fired: pattern=$pattern")
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HapticFeedbackManager"
    }
}
