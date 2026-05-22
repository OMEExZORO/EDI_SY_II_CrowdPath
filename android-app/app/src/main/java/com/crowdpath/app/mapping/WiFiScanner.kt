package com.crowdpath.app.mapping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log
import com.crowdpath.app.data.models.WiFiReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Scans WiFi networks periodically and emits [WiFiReading] lists.
 *
 * Uses [WifiManager] to trigger scans every ~1 second during mapping.
 */
class WiFiScanner(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Emit WiFi scan results as a cold [Flow]. Scans every [intervalMs].
     */
    fun scanFlow(intervalMs: Long = 1000L): Flow<List<WiFiReading>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val results = wifiManager.scanResults.map { sr ->
                    WiFiReading(
                        ssid = sr.SSID ?: "",
                        bssid = sr.BSSID ?: "",
                        rssi = sr.level
                    )
                }
                trySend(results)
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        // Trigger periodic scans
        launch {
            while (isActive) {
                @Suppress("DEPRECATION")
                wifiManager.startScan()
                delay(intervalMs)
            }
        }

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver already unregistered: ${e.message}")
            }
        }
    }

    /**
     * Take a WiFi snapshot using the most recent completed scan results,
     * then trigger a new scan so the *next* call gets fresher data.
     *
     * Note: [WifiManager.startScan] is async — results are delivered via
     * broadcast, not synchronously. Reading [scanResults] immediately after
     * startScan() always returns the PREVIOUS scan's data. So we flip the
     * order: return the current cached results first, then kick off a new scan
     * for next time. This is correct and avoids returning empty lists.
     */
    fun takeSnapshot(): List<WiFiReading> {
        val results = wifiManager.scanResults.map { sr ->
            WiFiReading(
                ssid  = sr.SSID ?: "",
                bssid = sr.BSSID ?: "",
                rssi  = sr.level
            )
        }
        // Trigger next scan asynchronously (result arrives ~1-2s later)
        @Suppress("DEPRECATION")
        wifiManager.startScan()
        return results
    }

    companion object {
        private const val TAG = "WiFiScanner"
    }
}
