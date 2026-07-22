package com.buddiestime.watchparty

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

private const val TAG = "HWP-HOME"
private const val PREFS = "hwp_prefs"
private const val SPLASH_DURATION_MS = 3800L  // love-note auto-dismiss; tap skips early
private val tempWelcomeLines = listOf("Hey, welcome back 💗", "Missed you 🎬", "Ready for movie night?")

class RoomsHomeActivity : AppCompatActivity() {
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var profileStore: ProfileStore
    private lateinit var api: PairingApi
    private var currentRoom: RoomView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlufflesTheme.apply(this)
        setContentView(R.layout.activity_rooms_home)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceIdentity = DeviceIdentity(prefs)
        profileStore = ProfileStore(prefs)
        api = PairingApi(Config.baseHttpUrl())

        Log.d(TAG, "onCreate — hasDevice=${deviceIdentity.hasDevice()} hasRoom=${deviceIdentity.hasRoom()}")
        if (!deviceIdentity.hasDevice() || !deviceIdentity.hasRoom()) {
            Log.d(TAG, "onCreate: not fully paired yet → WelcomeSetupActivity")
            startActivity(Intent(this, WelcomeSetupActivity::class.java))
            finish()
            return
        }

        if (savedInstanceState == null) {
            Log.d(TAG, "cold start → showing love-note splash")
            showLoveNoteSplash()
        } else {
            Log.d(TAG, "recreation (savedInstanceState present) → skipping splash")
        }

        val selfProfile = profileStore.selfProfile()
        findViewById<TextView>(R.id.tvGreeting).text =
            "Hey ${selfProfile?.petName ?: selfProfile?.displayName ?: "there"} 💗"
        findViewById<TextView>(R.id.tvGreetingSub).text = greetingSubline()

        findViewById<View>(R.id.btnStartWatching).setOnClickListener {
            Log.d(TAG, "hero card tapped → ServiceSelector")
            startActivity(Intent(this, ServiceSelectorActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnInviteNow).setOnClickListener { inviteNow() }
    }

    private fun greetingSubline(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val line = when (hour) {
            in 5..11 -> "a whole day of us ahead ☀️"
            in 12..16 -> "sneaky afternoon episode? 👀"
            in 17..21 -> "perfect time for our movie night ✨"
            else -> "one more episode won't hurt 🌙"
        }
        Log.d(TAG, "greetingSubline: hour=$hour → \"$line\"")
        return line
    }

    // ── Love-note splash (cold start only) ──────────────────────────────────
    private fun showLoveNoteSplash() {
        val stub = findViewById<ViewStub>(R.id.stubSplash)
        if (stub == null) { Log.w(TAG, "splash: stub missing — skipping"); return }
        val overlay = stub.inflate()
        // Temporary fixed pool — replaced with Room.welcomeMessages in the next task.
        val line = tempWelcomeLines.random()
        val partnerProfile = profileStore.partnerProfile()
        overlay.findViewById<TextView>(R.id.tvFlirtyLine).text = line
        overlay.findViewById<TextView>(R.id.tvSplashSignature).text =
            "— ${partnerProfile?.petName ?: partnerProfile?.displayName ?: "your partner"} 💌"

        // Heart pulse — skipped when the user has animations turned off
        val animScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var pulse: ObjectAnimator? = null
        if (animScale > 0f) {
            val heart = overlay.findViewById<TextView>(R.id.tvSplashHeart)
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                heart,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
            ).apply {
                duration = 550
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            Log.d(TAG, "splash: animations disabled (scale=$animScale) — static heart")
        }

        var dismissed = false
        fun dismiss(reason: String) {
            if (dismissed) { Log.d(TAG, "splash: dismiss($reason) ignored — already dismissed"); return }
            dismissed = true
            Log.d(TAG, "splash: dismissing ($reason)")
            pulse?.cancel()
            overlay.animate().alpha(0f).setDuration(350).withEndAction {
                overlay.visibility = View.GONE
            }.start()
        }

        overlay.setOnClickListener { dismiss("tap-skip") }
        overlay.postDelayed({ dismiss("auto") }, SPLASH_DURATION_MS)
        Log.d(TAG, "splash: shown line=\"$line\" (auto-dismiss in ${SPLASH_DURATION_MS}ms)")
    }

    override fun onResume() {
        super.onResume()
        if (deviceIdentity.hasDevice() && deviceIdentity.hasRoom()) {
            refreshPairingStatus()
        }
    }

    private fun refreshPairingStatus() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "refreshPairingStatus roomId=$roomId")
        api.getRoom(roomId, deviceId) { room, error ->
            runOnUiThread {
                if (room == null) {
                    Log.w(TAG, "refreshPairingStatus failed: $error")
                    findViewById<TextView>(R.id.tvPairingStatus).text = "Couldn't reach the server — pull to refresh"
                    return@runOnUiThread
                }
                currentRoom = room
                val partnerPaired = room.partnerDeviceId != null
                Log.d(TAG, "refreshPairingStatus room=${room.roomName} partnerPaired=$partnerPaired")
                findViewById<TextView>(R.id.tvPairingStatus).text = if (partnerPaired) {
                    "💗 ${room.roomName} — you're both paired up"
                } else {
                    "💗 ${room.roomName} — waiting for your partner to join"
                }
            }
        }
    }

    private fun inviteNow() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "inviteNow roomId=$roomId")
        api.triggerNudge(roomId, deviceId) { ok ->
            runOnUiThread {
                Log.d(TAG, "inviteNow result ok=$ok")
                Toast.makeText(
                    this,
                    if (ok) "Nudge sent 💌" else "Couldn't reach them right now — try again later",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
