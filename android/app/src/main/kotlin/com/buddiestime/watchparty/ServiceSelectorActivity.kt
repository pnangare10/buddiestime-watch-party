package com.buddiestime.watchparty

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class ServiceSelectorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service_selector)

        findViewById<MaterialCardView>(R.id.cardHotstar).setOnClickListener {
            launchParty("hotstar")
        }
        findViewById<MaterialCardView>(R.id.cardNetflix).setOnClickListener {
            launchParty("netflix")
        }
        findViewById<MaterialCardView>(R.id.cardPrimeVideo).setOnClickListener {
            launchParty("primevideo")
        }
        findViewById<MaterialCardView>(R.id.cardYouTube).setOnClickListener {
            launchParty("youtube")
        }
    }

    private fun launchParty(serviceName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", serviceName)
        })
        finish()  // Don't keep selector in back stack
    }
}
