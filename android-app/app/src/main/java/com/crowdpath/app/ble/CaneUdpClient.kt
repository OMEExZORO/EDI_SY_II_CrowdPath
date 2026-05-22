package com.crowdpath.app.ble

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.crowdpath.app.data.models.CaneNavCommand
import com.crowdpath.app.data.models.CaneVibeCommand
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * WiFi/UDP cane client for ESP8266.
 *
 * Mirrors the exact same public interface as [CaneClient] (BLE version).
 * NavigationEngine, NavigationScreen, and all other callers don't need any changes —
 * just swap which client is active via [CaneTransport].
 *
 * Protocol:
 *   Commands → 192.168.4.1:4210  (ESP8266 listens)
 *   Status   ← any port :4211    (app listens, ESP8266 replies to sender)
 */
class CaneUdpClient(private val context: Context) {

    // ── Constants ─────────────────────────────────────────────────────────────
    companion object {
        private const val TAG           = "CaneUdpClient"
        private const val ESP_IP        = "192.168.4.1"   // fixed AP gateway IP
        private const val ESP_PORT      = 4210
        private const val LISTEN_PORT   = 4211
        private const val ESP_SSID      = "SmartCane"
        private const val OBSTACLE_CM   = 60
        private const val TIMEOUT_MS    = 500
    }

    // ── State — same fields as CaneClient ────────────────────────────────────
    var isConnected: Boolean = false; private set
    var onStatusReceived:    ((batteryV: Float, ultraCm: Int) -> Unit)? = null
    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null
    var onObstacleDetected:  ((distanceCm: Int) -> Unit)?   = null
    var onObstacleCleared:   (() -> Unit)?                   = null

    private var lastUltraCm = 999
    private var sendSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val espAddress: InetAddress by lazy { InetAddress.getByName(ESP_IP) }

    // ── "Connect" — just checks WiFi SSID and opens sockets ──────────────────
    fun startScan() {
        scope.launch {
            // Poll until phone is on SmartCane WiFi or timeout (15s)
            val wifiMgr = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            var waited = 0
            while (waited < 15_000) {
                val ssid = wifiMgr.connectionInfo.ssid.trim('"')
                if (ssid == ESP_SSID) {
                    openSockets()
                    return@launch
                }
                delay(500)
                waited += 500
            }
            Log.w(TAG, "Not connected to $ESP_SSID WiFi after 15s")
            // Still open sockets — user might have connected manually
            openSockets()
        }
    }

    private fun openSockets() {
        try {
            sendSocket   = DatagramSocket()
            listenSocket = DatagramSocket(LISTEN_PORT)
            listenSocket!!.soTimeout = TIMEOUT_MS

            isConnected = true
            onConnectionChanged?.invoke(true)
            Log.i(TAG, "UDP sockets open — ready to send/receive")

            startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open sockets: ${e.message}")
            isConnected = false
            onConnectionChanged?.invoke(false)
        }
    }

    fun stopScan() { /* no-op for UDP — sockets open on demand */ }

    fun disconnect() {
        scope.launch {
            sendSocket?.close();   sendSocket   = null
            listenSocket?.close(); listenSocket = null
            isConnected = false
            onConnectionChanged?.invoke(false)
            Log.i(TAG, "UDP disconnected")
        }
    }

    // ── Status listener ───────────────────────────────────────────────────────
    private fun startListening() {
        scope.launch {
            val buf = ByteArray(256)
            while (isActive && isConnected) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    listenSocket?.receive(packet) ?: break
                    val json = String(packet.data, 0, packet.length)
                    parseStatus(json)
                } catch (_: Exception) {
                    // soTimeout fires every 500ms — normal, keep looping
                }
            }
        }
    }

    private fun parseStatus(json: String) {
        // e.g. {"cmd":"STATUS","battery_v":3.85,"ultra_cm":45}
        val battV = Regex("\"battery_v\":([\\.\\d]+)")
            .find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: return
        val ultra = Regex("\"ultra_cm\":(\\d+)")
            .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return

        onStatusReceived?.invoke(battV, ultra)

        if (ultra < OBSTACLE_CM) {
            onObstacleDetected?.invoke(ultra)
        } else if (lastUltraCm < OBSTACLE_CM && ultra >= OBSTACLE_CM) {
            onObstacleCleared?.invoke()
        }
        lastUltraCm = ultra
    }

    // ── Send commands (same API as CaneClient) ────────────────────────────────
    fun sendNavigationCommand(cmd: CaneNavCommand) {
        val json = BLEProtocol.encodeNavCommandAsString(cmd)
        send(json.toByteArray())
    }

    fun sendVibrationCommand(cmd: CaneVibeCommand) {
        val json = BLEProtocol.encodeVibeCommandAsString(cmd)
        send(json.toByteArray())
    }

    private fun send(data: ByteArray) {
        scope.launch {
            try {
                val packet = DatagramPacket(data, data.size, espAddress, ESP_PORT)
                sendSocket?.send(packet)
                Log.d(TAG, "Sent: ${String(data)}")
            } catch (e: Exception) {
                Log.e(TAG, "Send failed: ${e.message}")
                // Mark disconnected so UI shows reconnect prompt
                if (isConnected) {
                    isConnected = false
                    onConnectionChanged?.invoke(false)
                }
            }
        }
    }

    fun close() {
        scope.cancel()
        disconnect()
    }
}
