package com.buddiestime.watchparty

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Uses a trivial in-memory SharedPreferences fake rather than a full Robolectric context,
// consistent with this codebase's existing pattern of testing pure logic against fakes.
class FakePrefs : SharedPreferences {
    private val map = mutableMapOf<String, String?>()
    override fun getString(key: String?, def: String?) = map[key] ?: def
    override fun edit() = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { map[key] = value }
        override fun apply() {}
        override fun commit() = true
        // remaining Editor/SharedPreferences methods unused in this test — default no-ops/throws
        override fun putStringSet(k: String?, v: MutableSet<String>?) = this
        override fun putInt(k: String?, v: Int) = this
        override fun putLong(k: String?, v: Long) = this
        override fun putFloat(k: String?, v: Float) = this
        override fun putBoolean(k: String?, v: Boolean) = this
        override fun remove(k: String?) = this
        override fun clear() = this
    }
    override fun getAll() = mutableMapOf<String, Any?>()
    override fun getStringSet(k: String?, d: MutableSet<String>?) = d
    override fun getInt(k: String?, d: Int) = d
    override fun getLong(k: String?, d: Long) = d
    override fun getFloat(k: String?, d: Float) = d
    override fun getBoolean(k: String?, d: Boolean) = d
    override fun contains(k: String?) = map.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class DeviceIdentityTest {
    @Test fun no_device_initially() {
        val id = DeviceIdentity(FakePrefs())
        assertFalse(id.hasDevice())
        assertEquals(null, id.localDeviceId())
    }
    @Test fun store_then_read() {
        val id = DeviceIdentity(FakePrefs())
        id.store("abc123")
        assertTrue(id.hasDevice())
        assertEquals("abc123", id.localDeviceId())
    }
}
