package com.buddiestime.watchparty

import android.content.SharedPreferences

private const val KEY_DEVICE_ID = "device_id"

class DeviceIdentity(private val prefs: SharedPreferences) {
    fun localDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    fun hasDevice(): Boolean = localDeviceId() != null
    fun store(deviceId: String) { prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply() }
}
