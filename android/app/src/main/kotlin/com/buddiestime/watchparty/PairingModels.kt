package com.buddiestime.watchparty

import org.json.JSONArray
import org.json.JSONObject

data class Profile(val displayName: String, val petName: String?, val timezone: String?, val birthday: String?)
data class MessageEntry(val id: String, val text: String, val authorDeviceId: String, val createdAt: Long)
data class ThemeState(val mode: String, val value: String?, val setByDeviceId: String?, val setAt: Long?)
data class RoomView(
    val roomId: String, val roomName: String,
    val ownerDeviceId: String, val partnerDeviceId: String?,
    val theme: ThemeState, val nudgeMessages: List<MessageEntry>, val welcomeMessages: List<MessageEntry>,
)

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

fun parseProfile(o: JSONObject): Profile = Profile(
    displayName = o.optString("displayName"),
    petName = o.optStringOrNull("petName"),
    timezone = o.optStringOrNull("timezone"),
    birthday = o.optStringOrNull("birthday"),
)

private fun parseMessages(arr: JSONArray?): List<MessageEntry> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        MessageEntry(o.optString("id"), o.optString("text"), o.optString("authorDeviceId"), o.optLong("createdAt"))
    }
}

fun parseRoomView(json: String): RoomView {
    val o = JSONObject(json)
    val t = o.getJSONObject("theme")
    return RoomView(
        roomId = o.optString("roomId"),
        roomName = o.optString("roomName"),
        ownerDeviceId = o.optString("ownerDeviceId"),
        partnerDeviceId = o.optStringOrNull("partnerDeviceId"),
        theme = ThemeState(t.optString("mode", "auto"), t.optStringOrNull("value"), t.optStringOrNull("setByDeviceId"), if (t.isNull("setAt")) null else t.optLong("setAt")),
        nudgeMessages = parseMessages(o.optJSONArray("nudgeMessages")),
        welcomeMessages = parseMessages(o.optJSONArray("welcomeMessages")),
    )
}
