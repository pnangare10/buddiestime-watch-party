package com.buddiestime.watchparty

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

private const val KEY_SELF_PROFILE = "profile_self"
private const val KEY_PARTNER_PROFILE = "profile_partner"
private const val KEY_WELCOME_MESSAGES = "cached_welcome_messages"
private const val KEY_THEME_MODE = "cached_theme_mode"
private const val KEY_THEME_VALUE = "cached_theme_value"

private fun Profile.toJson(): JSONObject = JSONObject()
    .put("displayName", displayName)
    .put("petName", petName)
    .put("timezone", timezone)
    .put("birthday", birthday)

// Local cache of pairing data fetched via PairingApi.getRoom, so cold-start UI
// (greeting, splash, theme) has something to show before the network call returns.
class ProfileStore(private val prefs: SharedPreferences) {
    fun selfProfile(): Profile? = prefs.getString(KEY_SELF_PROFILE, null)?.let { parseProfile(JSONObject(it)) }
    fun partnerProfile(): Profile? = prefs.getString(KEY_PARTNER_PROFILE, null)?.let { parseProfile(JSONObject(it)) }
    fun storeSelf(profile: Profile) { prefs.edit().putString(KEY_SELF_PROFILE, profile.toJson().toString()).apply() }
    fun storePartner(profile: Profile) { prefs.edit().putString(KEY_PARTNER_PROFILE, profile.toJson().toString()).apply() }

    fun cachedPartnerWelcomeMessages(): List<String> {
        val raw = prefs.getString(KEY_WELCOME_MESSAGES, null) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).map { arr.getString(it) }
    }
    fun cacheWelcomeMessages(messages: List<String>) {
        val arr = JSONArray()
        messages.forEach { arr.put(it) }
        prefs.edit().putString(KEY_WELCOME_MESSAGES, arr.toString()).apply()
    }

    fun cachedTheme(): ThemeState? {
        val mode = prefs.getString(KEY_THEME_MODE, null) ?: return null
        return ThemeState(mode, prefs.getString(KEY_THEME_VALUE, null), null, null)
    }
    fun cacheTheme(theme: ThemeState) {
        prefs.edit().putString(KEY_THEME_MODE, theme.mode).putString(KEY_THEME_VALUE, theme.value).apply()
    }
}
