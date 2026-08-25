package com.buddiestime.watchparty

import org.json.JSONObject

data class RoomStatus(
    val roomId: String,
    val active: Boolean,
    val count: Int,
    val platform: String?,
    val videoUrl: String?,
    val title: String?,
)

// Returns the string field or null when absent/JSON-null (no "null" sentinel strings).
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

fun parseRoomStatus(o: JSONObject): RoomStatus = RoomStatus(
    roomId = o.optString("roomId"),
    active = o.optBoolean("active", false),
    count = o.optInt("count", 0),
    platform = o.optStringOrNull("platform"),
    videoUrl = o.optStringOrNull("videoUrl"),
    title = o.optStringOrNull("title"),
)

/** Single-room shape returned by GET /api/room/<id>. */
fun parseRoomStatus(json: String): RoomStatus = parseRoomStatus(JSONObject(json))

fun parseRoomStatusList(json: String): List<RoomStatus> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("rooms") ?: return emptyList()
    return (0 until arr.length()).map { i -> parseRoomStatus(arr.getJSONObject(i)) }
}

/**
 * True when someone is actually watching something right now. `active` alone is not
 * enough: the server keeps roomState alive for ROOM_GRACE_MS after the last person
 * leaves, so an empty room still reports active=true for minutes afterwards.
 */
fun RoomStatus.isLiveParty(): Boolean =
    active && count > 0 && !videoUrl.isNullOrBlank()
