package com.buddiestime.watchparty

import android.content.SharedPreferences

private const val KEY_DEVICE_ID = "device_id"
private const val KEY_ROOM_ID = "paired_room_id"

class DeviceIdentity(private val prefs: SharedPreferences) {
    fun localDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    fun hasDevice(): Boolean = localDeviceId() != null
    fun store(deviceId: String) { prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply() }

    fun localRoomId(): String? = prefs.getString(KEY_ROOM_ID, null)
    fun hasRoom(): Boolean = localRoomId() != null
    fun storeRoomId(roomId: String) { prefs.edit().putString(KEY_ROOM_ID, roomId).apply() }
}
