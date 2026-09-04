package com.buddiestime.watchparty

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

private const val TAG = "HWP-SEL"
private const val PREFS = "hwp_prefs"
private const val KEY_NAME = "displayName"
/** Last page visited in Browse, so re-entering Browse resumes instead of restarting. */
const val KEY_LAST_BROWSE_URL = "lastBrowseUrl"

class ServiceSelectorActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlufflesTheme.apply(this)
        setContentView(R.layout.activity_service_selector)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Log.d(TAG, "onCreate — stored displayName=\"${prefs.getString(KEY_NAME, "")}\"")

        findViewById<MaterialCardView>(R.id.cardHotstar).setOnClickListener { ensureNameThenLaunch("hotstar") }
        findViewById<MaterialCardView>(R.id.cardNetflix).setOnClickListener { ensureNameThenLaunch("netflix") }
        findViewById<MaterialCardView>(R.id.cardPrimeVideo).setOnClickListener { ensureNameThenLaunch("primevideo") }
        findViewById<MaterialCardView>(R.id.cardYouTube).setOnClickListener { ensureNameThenLaunch("youtube") }
        findViewById<MaterialCardView>(R.id.cardBrowse).setOnClickListener { ensureNameThenLaunch("browse") }
    }

    // Display name now comes from the paired Profile (set up during pairing) — never
    // asked ad hoc here, since reaching this screen already requires being paired.
    private fun ensureNameThenLaunch(serviceName: String) {
        val existing = prefs.getString(KEY_NAME, "")?.trim().orEmpty()
        Log.d(TAG, "ensureNameThenLaunch(service=$serviceName) existing=\"$existing\"")
        if (existing.isEmpty()) {
            val name = ProfileStore(prefs).selfProfile()?.displayName?.trim().orEmpty()
            if (name.isNotEmpty()) {
                Log.d(TAG, "  → backfilling displayName from paired profile: \"$name\"")
                prefs.edit().putString(KEY_NAME, name).apply()
            }
        }
        launchParty(serviceName)
    }

    // The pairing redesign fixed us to a single constant room, so the room id is never
    // typed — it comes from the device's pairing record and rides along as an extra so
    // MainActivity auto-connects instead of prompting.
    private fun launchParty(serviceName: String) {
        val roomId = DeviceIdentity(prefs).localRoomId()
        Log.d(TAG, "launchParty(service=$serviceName) pairedRoom=$roomId")
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", serviceName)
            // Browse has no fixed home, so it carries one: the page we were last on,
            // or the search page the first time. MainActivity already understands
            // hwp_url as "start here instead of the service's own url".
            if (serviceName == BrowseService.name) {
                putExtra("hwp_url", resumeBrowseUrl())
            }
            if (!roomId.isNullOrBlank()) {
                putExtra("roomId", roomId)
                putExtra("join", true)
            }
        })
        finish()
    }

    /**
     * Where Browse should open. The stored value is re-validated through
     * [BrowseUrl.resolve] rather than trusted: it was written by an earlier run of the
     * app, and anything that reaches `loadUrl` goes through the same guard no matter
     * how old it is.
     */
    private fun resumeBrowseUrl(): String {
        val stored = prefs.getString(KEY_LAST_BROWSE_URL, "")?.trim().orEmpty()
        val resolved = if (stored.isEmpty()) null else BrowseUrl.resolve(stored)
        Log.d(TAG, "resumeBrowseUrl stored=\"$stored\" → ${resolved ?: BrowseUrl.HOME}")
        return resolved ?: BrowseUrl.HOME
    }
}
