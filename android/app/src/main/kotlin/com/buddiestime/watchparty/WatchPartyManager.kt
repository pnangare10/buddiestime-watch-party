package com.buddiestime.watchparty

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val DRIFT_THRESHOLD = 2.0 // seconds — correct guest if drift exceeds this
private const val TAG = "HWP-WPM"

data class Participant(val id: String, val role: String, val name: String, val voice: Boolean = false)
data class ChatMessage(val from: String, val name: String, val text: String, val ts: Long)
data class VoiceParticipant(val id: String, val name: String)

class WatchPartyManager(
    private val onRoleAssigned: (role: String) -> Unit,
    private val onSyncCommand: (time: Double, paused: Boolean, videoUrl: String, platform: String) -> Unit,
    private val onStatusChange: (status: String) -> Unit,
    private val onChatMessage: (msg: ChatMessage) -> Unit = {},
    private val onParticipantsChange: (list: List<Participant>) -> Unit = {},
    private val onServerError: (reason: String, detail: String) -> Unit = { _, _ -> },
    private val onVoiceToken: (token: String, url: String, lkRoom: String) -> Unit = { _, _, _ -> },
    private val onVoiceParticipants: (list: List<VoiceParticipant>) -> Unit = {},
    private val onReconnecting: (attempt: Int) -> Unit = {},
) {
    @Volatile var role: String? = null
        private set

    @Volatile private var ws: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val backoff = BackoffPolicy()
    private var reconnectAttempt = 0
    @Volatile private var intentionalClose = false
    @Volatile private var connectionGen = 0   // bumped per connect()/disconnect(); stale wake/reconnect loops bail
    private var lastParams: JoinParams? = null
    private val healthClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    data class JoinParams(val serverUrl: String, val roomId: String, val platform: String, val videoUrl: String, val displayName: String, val clientId: String)

    fun connect(serverUrl: String, roomId: String, platform: String, videoUrl: String, displayName: String) {
        Log.d(TAG, "connect() serverUrl=$serverUrl roomId=$roomId platform=$platform videoUrl=$videoUrl displayName=\"$displayName\"")
        if (displayName.isBlank()) { Log.w(TAG, "connect aborted — displayName blank"); post { onStatusChange("Name required") }; return }

        val clientId = lastParams?.clientId ?: ("android-" + (Math.random() * 999999).toInt())
        lastParams = JoinParams(serverUrl, roomId, platform, videoUrl, displayName, clientId)
        intentionalClose = false
        val gen = ++connectionGen   // supersede any in-flight wake/reconnect loop
        openWithWake(lastParams!!, gen)
    }

    // gen: the connection generation that owns this loop. If connect()/disconnect()
    // is called again, connectionGen changes and this (now-stale) loop bails out —
    // preventing stacked reconnects and posts to a torn-down UI.
    private fun openWithWake(p: JoinParams, gen: Int) {
        post { onStatusChange("Waking up the server…") }
        Thread {
            val healthy = pollHealth(Config.healthUrl(p.serverUrl), 60_000, gen)
            Log.d(TAG, "health poll result=$healthy gen=$gen (current=$connectionGen)")
            if (intentionalClose || gen != connectionGen) { Log.d(TAG, "openWithWake aborted — stale/intentional"); return@Thread }
            if (!healthy) { post { if (gen == connectionGen) { onStatusChange("Server unreachable — retrying…"); scheduleReconnect(gen) } }; return@Thread }
            post { if (gen == connectionGen && !intentionalClose) openSocket(p, gen) }
        }.start()
    }

    private fun pollHealth(healthUrl: String, budgetMs: Long, gen: Int): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !intentionalClose && gen == connectionGen) {
            try {
                healthClient.newCall(Request.Builder().url(healthUrl).build()).execute().use { r ->
                    if (r.isSuccessful) return true
                }
            } catch (e: Exception) { Log.d(TAG, "health ping failed: ${e.message}") }
            try { Thread.sleep(2000) } catch (e: InterruptedException) { return false }
        }
        return false
    }

    private fun openSocket(p: JoinParams, gen: Int) {
        ws?.let { Log.d(TAG, "closing existing WS before reconnect"); it.close(1000, "Reconnecting") }
        val request = Request.Builder().url(p.serverUrl).build()
        Log.d(TAG, "opening WS to ${p.serverUrl} with clientId=${p.clientId} gen=$gen")
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                val joinPayload = JSONObject().apply {
                    put("type", "join"); put("roomId", p.roomId); put("clientId", p.clientId)
                    put("platform", p.platform); put("videoUrl", p.videoUrl); put("displayName", p.displayName)
                }
                Log.d(TAG, "WS onOpen — sending join: $joinPayload")
                webSocket.send(joinPayload.toString())
                post { onStatusChange("Connecting…") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS onMessage raw (len=${text.length}): ${text.take(240)}")
                val msg = try { JSONObject(text) } catch (e: Exception) { Log.w(TAG, "parse failed: ${e.message}"); return }
                post { handleMessage(msg) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS onFailure: ${t.message}", t)
                if (intentionalClose || gen != connectionGen) { post { ws = null; role = null; onStatusChange("Disconnected") } } else { post { ws = null; role = null; scheduleReconnect(gen) } }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS onClosed code=$code reason=$reason")
                if (intentionalClose || gen != connectionGen) { post { ws = null; role = null; onStatusChange("Disconnected") } } else { post { ws = null; role = null; scheduleReconnect(gen) } }
            }
        })
    }

    private fun scheduleReconnect(gen: Int) {
        if (intentionalClose || gen != connectionGen) return
        reconnectAttempt++
        val delay = backoff.delayFor(reconnectAttempt)
        Log.d(TAG, "scheduleReconnect attempt=$reconnectAttempt delay=${delay}ms gen=$gen")
        onReconnecting(reconnectAttempt)
        onStatusChange("Reconnecting…")
        val p = lastParams ?: return
        mainHandler.postDelayed({ if (!intentionalClose && gen == connectionGen) openWithWake(p, gen) }, delay)
    }

    private fun handleMessage(msg: JSONObject) {
        val type = msg.optString("type")
        Log.d(TAG, "handleMessage type=$type")
        when (type) {
            "error" -> {
                val reason = msg.optString("reason")
                val detail = msg.optString("detail")
                Log.w(TAG, "server error reason=$reason detail=$detail")
                onServerError(reason, detail)
            }
            "joined" -> {
                role = msg.optString("role")
                val name = msg.optString("name")
                val existed = msg.optBoolean("existed", false)
                Log.d(TAG, "joined as role=$role name=$name videoUrl=${msg.optString("videoUrl")} existed=$existed")
                onStatusChange(if (role == "host") "● Host" else "● Guest")
                onRoleAssigned(role!!)
                // Guests always adopt the room's authoritative state. A rejoining host only
                // adopts it when the room already existed server-side (a real resume) — not
                // when this join just created the room (that state is merely an echo of what
                // we ourselves submitted, and applying it would pause/reset our own fresh video).
                if (msg.has("time") && (role == "guest" || existed)) {
                    Log.d(TAG, "joined — applying initial sync (role=$role existed=$existed)")
                    onSyncCommand(
                        msg.optDouble("time", 0.0),
                        msg.optBoolean("paused", true),
                        msg.optString("videoUrl", ""),
                        msg.optString("platform", "")
                    )
                }
            }
            "role" -> {
                role = msg.optString("role")
                Log.d(TAG, "role change → $role")
                onRoleAssigned(role!!)
                onStatusChange(if (role == "host") "● Host" else "● Guest")
            }
            "state-update" -> {
                if (role != "guest") {
                    Log.d(TAG, "state-update ignored — role=$role (not guest)")
                    return
                }
                Log.d(TAG, "state-update: t=${msg.optDouble("time")} paused=${msg.optBoolean("paused")} url=${msg.optString("videoUrl")}")
                onSyncCommand(
                    msg.optDouble("time", 0.0),
                    msg.optBoolean("paused", true),
                    msg.optString("videoUrl", ""),
                    msg.optString("platform", "")
                )
            }
            "chat" -> {
                val m = ChatMessage(
                    from = msg.optString("from"),
                    name = msg.optString("name"),
                    text = msg.optString("text"),
                    ts   = msg.optLong("ts", System.currentTimeMillis())
                )
                Log.d(TAG, "chat from ${m.name}(${m.from}): \"${m.text.take(80)}\"")
                onChatMessage(m)
            }
            "participants" -> {
                val arr: JSONArray = msg.optJSONArray("list") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Participant(
                        id = o.optString("id"),
                        role = o.optString("role"),
                        name = o.optString("name"),
                        voice = o.optBoolean("voice", false)
                    )
                }
                Log.d(TAG, "participants update: ${list.size} members → ${list.joinToString { "${it.role}:${it.name}${if (it.voice) "🎤" else ""}" }}")
                onParticipantsChange(list)
            }
            "voice-token" -> {
                val token  = msg.optString("token")
                val lkUrl  = msg.optString("lkUrl")
                val lkRoom = msg.optString("lkRoom")
                Log.d(TAG, "voice-token received — lkRoom=$lkRoom tokenLen=${token.length}")
                onVoiceToken(token, lkUrl, lkRoom)
            }
            "voice-participants" -> {
                val arr: JSONArray = msg.optJSONArray("list") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    VoiceParticipant(id = o.optString("id"), name = o.optString("name"))
                }
                Log.d(TAG, "voice-participants update: ${list.size}")
                onVoiceParticipants(list)
            }
            else -> Log.w(TAG, "unknown message type: $type (raw: ${msg.toString().take(160)})")
        }
    }

    /** Called by JsBridge when the host video fires play/pause/seeked, or every 2s from the JS timer. */
    fun sendStateUpdate(time: Double, paused: Boolean, videoUrl: String) {
        if (role != "host") {
            Log.d(TAG, "sendStateUpdate skipped — role=$role (not host)")
            return
        }
        val payload = JSONObject().apply {
            put("type", "state-update")
            put("time", time)
            put("paused", paused)
            put("videoUrl", videoUrl)
        }
        Log.d(TAG, "sendStateUpdate: t=${"%.2f".format(time)} paused=$paused url=$videoUrl")
        val sent = ws?.send(payload.toString()) ?: false
        if (!sent) Log.w(TAG, "sendStateUpdate: ws.send returned false (queue full or closed)")
    }

    fun sendChat(text: String) {
        val trimmed = text.trim()
        Log.d(TAG, "sendChat: len=${trimmed.length}")
        if (trimmed.isEmpty()) { Log.d(TAG, "sendChat: empty → skip"); return }
        val w = ws
        if (w == null) { Log.w(TAG, "sendChat: ws is null"); return }
        val payload = JSONObject().apply {
            put("type", "chat")
            put("text", trimmed)
        }
        val sent = w.send(payload.toString())
        Log.d(TAG, "sendChat: ws.send returned $sent")
    }

    fun requestVoiceToken() {
        Log.d(TAG, "requestVoiceToken()")
        val w = ws
        if (w == null) { Log.w(TAG, "  ws is null"); return }
        val payload = JSONObject().apply { put("type", "voice-token-request") }
        val sent = w.send(payload.toString())
        Log.d(TAG, "  ws.send returned $sent")
    }

    fun sendVoiceLeave() {
        Log.d(TAG, "sendVoiceLeave()")
        val payload = JSONObject().apply { put("type", "voice-leave") }
        val sent = ws?.send(payload.toString()) ?: false
        Log.d(TAG, "  ws.send returned $sent")
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        intentionalClose = true
        connectionGen++          // invalidate any in-flight wake/reconnect loop (also covers activity teardown)
        lastParams = null
        reconnectAttempt = 0
        ws?.close(1000, "User left party")
        ws = null
        role = null
    }

    fun isConnected() = ws != null

    private fun post(action: () -> Unit) = mainHandler.post(action)
}
