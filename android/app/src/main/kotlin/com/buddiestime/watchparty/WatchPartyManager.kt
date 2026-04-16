package com.buddiestime.watchparty

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages the WebSocket connection to the watch party server.
 * Runs entirely on the OkHttp dispatcher thread; all callbacks are posted to the main thread.
 */
class WatchPartyManager(
    private val onRoleAssigned: (role: String) -> Unit,
    private val onSyncCommand: (time: Double, paused: Boolean) -> Unit,
    private val onSeekCommand: (time: Double) -> Unit,
    private val onSyncRequested: () -> Unit,
    private val onStatusChange: (status: String) -> Unit
) {
    var role: String? = null
        private set

    private var ws: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)  // keep-alive for long watch sessions
        .build()

    fun connect(serverUrl: String, roomId: String) {
        ws?.close(1000, "Reconnecting")

        val clientId = "android-" + (Math.random() * 999999).toInt()
        val request = Request.Builder().url(serverUrl).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().apply {
                    put("type", "join")
                    put("roomId", roomId)
                    put("clientId", clientId)
                }.toString())
                post { onStatusChange("Connecting…") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) { return }
                post { handleMessage(msg) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                role = null
                post { onStatusChange("Error: ${t.message ?: "Connection failed"}") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                role = null
                post { onStatusChange("Disconnected") }
            }
        })
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "joined" -> {
                role = msg.optString("role")
                onStatusChange(if (role == "host") "● Host" else "● Guest")
                onRoleAssigned(role!!)
            }
            "sync-request" -> {
                // Server is asking host to broadcast current state (a new guest joined)
                if (role == "host") onSyncRequested()
            }
            "sync-response" -> {
                if (role == "guest") {
                    onSyncCommand(msg.optDouble("time", 0.0), msg.optBoolean("paused", true))
                }
            }
            "play" -> if (role == "guest") onSyncCommand(msg.optDouble("time", 0.0), false)
            "pause" -> if (role == "guest") onSyncCommand(msg.optDouble("time", 0.0), true)
            "seek" -> if (role == "guest") onSeekCommand(msg.optDouble("time", 0.0))
        }
    }

    /** Called by JsBridge when the host's video fires play/pause/seeked. */
    fun sendVideoEvent(type: String, time: Double) {
        if (role != "host") return
        ws?.send(JSONObject().apply {
            put("type", type)
            put("time", time)
        }.toString())
    }

    /** Called by JsBridge after the host reports its current state in response to sync-request. */
    fun sendSyncResponse(time: Double, paused: Boolean) {
        ws?.send(JSONObject().apply {
            put("type", "sync-response")
            put("time", time)
            put("paused", paused)
        }.toString())
    }

    fun disconnect() {
        ws?.close(1000, "User left party")
        ws = null
        role = null
    }

    fun isConnected() = ws != null

    private fun post(action: () -> Unit) = mainHandler.post(action)
}
