package com.buddiestime.watchparty

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class RecentRoom(
    val roomId: String,
    val platform: String,
    val videoUrl: String,
    val lastJoined: Long,
)

object RecentRoomsCodec {
    fun encode(list: List<RecentRoom>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject()
                .put("roomId", it.roomId)
                .put("platform", it.platform)
                .put("videoUrl", it.videoUrl)
                .put("lastJoined", it.lastJoined))
        }
        return arr.toString()
    }

    fun decode(json: String): List<RecentRoom> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentRoom(o.optString("roomId"), o.optString("platform"), o.optString("videoUrl"), o.optLong("lastJoined"))
        }
    } catch (e: Exception) { emptyList() }
}

fun mergeRecent(existing: List<RecentRoom>, room: RecentRoom, max: Int = 20): List<RecentRoom> =
    (listOf(room) + existing)
        .distinctBy { it.roomId }
        .sortedByDescending { it.lastJoined }
        .take(max)

private const val KEY_RECENTS = "recent_rooms"

class RecentRoomsStore(private val prefs: SharedPreferences) {
    fun all(): List<RecentRoom> = RecentRoomsCodec.decode(prefs.getString(KEY_RECENTS, "") ?: "")
    fun add(room: RecentRoom) {
        val merged = mergeRecent(all(), room)
        prefs.edit().putString(KEY_RECENTS, RecentRoomsCodec.encode(merged)).apply()
    }
}
