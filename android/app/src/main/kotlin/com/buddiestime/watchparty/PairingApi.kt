package com.buddiestime.watchparty

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "HWP-PAIR-API"
private val JSON = "application/json".toMediaType()

/**
 * Thin OkHttp/JSON client for the pairing REST surface (server/server.js's
 * /api/devices and /api/rooms routes) — one call per server route, same
 * enqueue+Callback shape as RoomsHomeActivity.refreshStatuses().
 */
class PairingApi(private val baseHttpUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = baseHttpUrl.trimEnd('/') + path

    private fun post(path: String, body: JSONObject, onResult: (JSONObject?, String?) -> Unit) {
        val req = Request.Builder().url(url(path)).post(body.toString().toRequestBody(JSON)).build()
        Log.d(TAG, "POST $path body=$body")
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "POST $path failed: ${e.message}")
                onResult(null, e.message ?: "network-error")
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string()
                Log.d(TAG, "POST $path → ${response.code} $raw")
                val json = try { raw?.let { JSONObject(it) } } catch (e: Exception) { null }
                if (!response.isSuccessful) {
                    onResult(json, json?.optString("reason") ?: "http-${response.code}")
                    return
                }
                onResult(json, null)
            }
        })
    }

    private fun patch(path: String, body: JSONObject, onResult: (Boolean) -> Unit) {
        val req = Request.Builder().url(url(path)).patch(body.toString().toRequestBody(JSON)).build()
        Log.d(TAG, "PATCH $path body=$body")
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "PATCH $path failed: ${e.message}")
                onResult(false)
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "PATCH $path → ${response.code}")
                onResult(response.isSuccessful)
            }
        })
    }

    private fun delete(path: String, body: JSONObject, onResult: (Boolean) -> Unit) {
        val req = Request.Builder().url(url(path)).delete(body.toString().toRequestBody(JSON)).build()
        Log.d(TAG, "DELETE $path body=$body")
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "DELETE $path failed: ${e.message}")
                onResult(false)
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "DELETE $path → ${response.code}")
                onResult(response.isSuccessful)
            }
        })
    }

    fun createDevice(cb: (String?) -> Unit) {
        val req = Request.Builder().url(url("/api/devices")).post("".toRequestBody(JSON)).build()
        Log.d(TAG, "POST /api/devices")
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "createDevice failed: ${e.message}")
                cb(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string()
                Log.d(TAG, "createDevice → ${response.code} $raw")
                val json = try { raw?.let { JSONObject(it) } } catch (e: Exception) { null }
                if (!response.isSuccessful) {
                    cb(null)
                    return
                }
                val deviceId = json?.optString("deviceId")
                cb(deviceId.takeUnless { it.isNullOrEmpty() })
            }
        })
    }

    fun createRoom(
        deviceId: String, roomName: String, ownerProfile: JSONObject, partnerDraft: JSONObject,
        cb: (roomId: String?, error: String?) -> Unit,
    ) {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("roomName", roomName)
            .put("ownerProfile", ownerProfile)
            .put("partnerProfileDraft", partnerDraft)
        // optString("roomId") would return "" rather than null when absent (e.g. on an
        // error body like {"ok":false,"reason":"room-name-taken"}) — only extract on success.
        post("/api/rooms", body) { json, error -> cb(if (error == null) json?.optString("roomId") else null, error) }
    }

    fun mintInvite(roomId: String, deviceId: String, pin: String?, cb: (token: String?, error: String?) -> Unit) {
        val body = JSONObject().put("deviceId", deviceId)
        if (pin != null) body.put("pin", pin)
        post("/api/rooms/$roomId/invite", body) { json, error -> cb(if (error == null) json?.optString("token") else null, error) }
    }

    fun joinRoom(roomId: String, deviceId: String, token: String, pin: String?, cb: (herProfile: Profile?, hisProfile: Profile?, error: String?) -> Unit) {
        val body = JSONObject().put("deviceId", deviceId).put("token", token)
        if (pin != null) body.put("pin", pin)
        post("/api/rooms/$roomId/join", body) { json, error ->
            if (json == null) { cb(null, null, error); return@post }
            val her = json.optJSONObject("herProfile")?.let { parseProfile(it) }
            val his = json.optJSONObject("hisProfile")?.let { parseProfile(it) }
            cb(her, his, error)
        }
    }

    fun getRoom(roomId: String, deviceId: String, cb: (RoomView?, error: String?) -> Unit) {
        val req = Request.Builder().url(url("/api/rooms/$roomId")).header("X-Device-Id", deviceId).build()
        Log.d(TAG, "GET /api/rooms/$roomId deviceId=$deviceId")
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "getRoom failed: ${e.message}")
                cb(null, e.message ?: "network-error")
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string()
                Log.d(TAG, "getRoom → ${response.code} $raw")
                if (!response.isSuccessful || raw == null) {
                    val reason = try { raw?.let { JSONObject(it).optString("reason") } } catch (e: Exception) { null }
                    cb(null, reason ?: "http-${response.code}")
                    return
                }
                val room = try { parseRoomEnvelope(raw) } catch (e: Exception) {
                    Log.w(TAG, "getRoom parse failed: ${e.message}")
                    cb(null, "parse-error")
                    return
                }
                cb(room, null)
            }
        })
    }

    /**
     * Live playback state for a room (GET /api/room/<id>) — the WebSocket-era endpoint,
     * separate from the pairing record served by /api/rooms/<id>. A 404 means the server
     * holds no state for this room (nobody has watched anything recently), which is a
     * normal answer, not an error — hence null rather than a reason string.
     */
    fun getRoomStatus(roomId: String, cb: (RoomStatus?) -> Unit) {
        val req = Request.Builder().url(url("/api/room/$roomId")).build()
        Log.d(TAG, "GET /api/room/$roomId")
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "getRoomStatus failed: ${e.message}")
                cb(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string()
                Log.d(TAG, "getRoomStatus → ${response.code} $raw")
                if (!response.isSuccessful || raw == null) { cb(null); return }
                val status = try { parseRoomStatus(raw) } catch (e: Exception) {
                    Log.w(TAG, "getRoomStatus parse failed: ${e.message}")
                    null
                }
                cb(status)
            }
        })
    }

    fun setTheme(roomId: String, deviceId: String, mode: String, value: String?, cb: (Boolean) -> Unit) {
        val body = JSONObject().put("deviceId", deviceId).put("mode", mode)
        if (value != null) body.put("value", value)
        patch("/api/rooms/$roomId/theme", body, cb)
    }

    fun addMessage(roomId: String, deviceId: String, pool: String, text: String, cb: (Boolean) -> Unit) {
        val body = JSONObject().put("deviceId", deviceId).put("text", text)
        post("/api/rooms/$roomId/messages/$pool", body) { json, error -> cb(error == null && json?.optBoolean("ok") == true) }
    }

    fun removeMessage(roomId: String, deviceId: String, pool: String, id: String, cb: (Boolean) -> Unit) {
        val body = JSONObject().put("deviceId", deviceId)
        delete("/api/rooms/$roomId/messages/$pool/$id", body, cb)
    }

    fun updateProfile(deviceId: String, body: JSONObject, cb: (Boolean) -> Unit) {
        patch("/api/devices/$deviceId", body, cb)
    }

    fun triggerNudge(roomId: String, deviceId: String, cb: (Boolean) -> Unit) {
        val body = JSONObject().put("deviceId", deviceId)
        post("/api/rooms/$roomId/nudge", body) { _, error -> cb(error == null) }
    }
}
