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

    private fun launchParty(serviceName: String) {
        Log.d(TAG, "launchParty(service=$serviceName)")
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", serviceName)
        })
        finish()
    }
}
