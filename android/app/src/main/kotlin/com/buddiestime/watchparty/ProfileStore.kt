package com.buddiestime.watchparty

import android.content.SharedPreferences
import org.json.JSONObject

private const val KEY_SELF_PROFILE = "profile_self"
private const val KEY_PARTNER_PROFILE = "profile_partner"

private fun Profile.toJson(): JSONObject = JSONObject()
    .put("displayName", displayName)
    .put("petName", petName)
    .put("timezone", timezone)
    .put("birthday", birthday)

/**
 * Local cache of the paired self/partner Profile, so screens have names/pet-names
 * available without a network round trip. Refreshed opportunistically from the
 * join response at pairing time and whenever PairingApi.getRoom is called.
 */
class ProfileStore(private val prefs: SharedPreferences) {
    fun selfProfile(): Profile? = prefs.getString(KEY_SELF_PROFILE, null)?.let { parseProfile(JSONObject(it)) }
    fun partnerProfile(): Profile? = prefs.getString(KEY_PARTNER_PROFILE, null)?.let { parseProfile(JSONObject(it)) }
    fun storeSelf(profile: Profile) { prefs.edit().putString(KEY_SELF_PROFILE, profile.toJson().toString()).apply() }
    fun storePartner(profile: Profile) { prefs.edit().putString(KEY_PARTNER_PROFILE, profile.toJson().toString()).apply() }
}
