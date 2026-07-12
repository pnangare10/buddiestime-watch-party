package com.buddiestime.watchparty

import android.app.AlertDialog
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText

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

    private fun ensureNameThenLaunch(serviceName: String) {
        val existing = prefs.getString(KEY_NAME, "")?.trim().orEmpty()
        Log.d(TAG, "ensureNameThenLaunch(service=$serviceName) existing=\"$existing\"")
        if (existing.isNotEmpty()) {
            Log.d(TAG, "  → already have name, launching")
            launchParty(serviceName)
            return
        }
        promptForName { name ->
            Log.d(TAG, "promptForName callback: saving \"$name\"")
            prefs.edit().putString(KEY_NAME, name).apply()
            launchParty(serviceName)
        }
    }

    private fun promptForName(onOk: (String) -> Unit) {
        Log.d(TAG, "promptForName dialog open")
        val view = layoutInflater.inflate(R.layout.dialog_name_prompt, null)
        val et = view.findViewById<TextInputEditText>(R.id.etDisplayName)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("What should I call you? 💕")
            .setMessage("This is the name that shows up in our chat.")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("OK", null) // override below so we can validate
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = et.text?.toString()?.trim().orEmpty()
                Log.d(TAG, "name dialog OK clicked — name=\"$name\"")
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (name.length > 32) {
                    Toast.makeText(this, "Max 32 chars", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onOk(name)
            }
        }
        dialog.show()
    }

    private fun launchParty(serviceName: String) {
        Log.d(TAG, "launchParty(service=$serviceName)")
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", serviceName)
        })
        finish()
    }
}
