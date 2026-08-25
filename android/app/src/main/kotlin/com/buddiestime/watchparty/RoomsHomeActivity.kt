package com.buddiestime.watchparty

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
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
// Shown only when the welcome-message pool is empty (fresh pairing, nobody's added one yet).
private val defaultWelcomeLines = listOf("Hey, welcome back 💗", "Missed you 🎬", "Ready for movie night?")
// How often the home screen re-checks whether a party is live, so the guest sees the
// host start watching without having to re-foreground the app.
private const val STATUS_POLL_INTERVAL_MS = 5000L

class RoomsHomeActivity : AppCompatActivity() {
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var profileStore: ProfileStore
    private lateinit var api: PairingApi
    private var currentRoom: RoomView? = null
    private var liveStatus: RoomStatus? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    // Last observed live-party state. Auto-join fires only on the not-live → live
    // *transition*, so returning from the party (which leaves this state true) doesn't
    // immediately bounce the user back in. Reset to false when the party ends.
    private var wasLiveParty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceIdentity = DeviceIdentity(prefs)
        profileStore = ProfileStore(prefs)
        api = PairingApi(Config.baseHttpUrl())

        FlufflesTheme.apply(this, profileStore.cachedTheme())
        setContentView(R.layout.activity_rooms_home)

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

        findViewById<View>(R.id.btnStartWatching).setOnClickListener { onStartWatchingTapped() }
        findViewById<MaterialButton>(R.id.btnInviteNow).setOnClickListener { onInviteButtonTapped() }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_rooms_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

    // Picks a line from the partner's cached welcome messages (shown on app-open
    // only, per the design decision); falls back to a small built-in default set
    // so a brand-new pairing with an empty pool isn't blank on day one.
    private fun pickWelcomeLine(): String =
        profileStore.cachedPartnerWelcomeMessages().takeIf { it.isNotEmpty() }?.random()
            ?: defaultWelcomeLines.random()

    // ── Love-note splash (cold start only) ──────────────────────────────────
    private fun showLoveNoteSplash() {
        val stub = findViewById<ViewStub>(R.id.stubSplash)
        if (stub == null) { Log.w(TAG, "splash: stub missing — skipping"); return }
        val overlay = stub.inflate()
        val line = pickWelcomeLine()
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
            startStatusPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        stopStatusPolling()
    }

    // Re-checks the live-party status on a timer so the guest picks up the host starting
    // a party without re-foregrounding the app. Only the status call is polled (not the
    // full pairing record); getRoom stays a one-shot in refreshPairingStatus().
    private fun startStatusPolling() {
        stopStatusPolling()
        val roomId = deviceIdentity.localRoomId() ?: return
        val runnable = object : Runnable {
            override fun run() {
                api.getRoomStatus(roomId) { status ->
                    runOnUiThread {
                        Log.d(TAG, "poll getRoomStatus active=${status?.active} count=${status?.count} url=${status?.videoUrl}")
                        liveStatus = status
                        renderHome()
                        maybeAutoJoin()
                    }
                }
                pollHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
            }
        }
        pollRunnable = runnable
        pollHandler.postDelayed(runnable, STATUS_POLL_INTERVAL_MS)
    }

    private fun stopStatusPolling() {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    // Enters the party automatically the moment it goes live. Edge-triggered on the
    // not-live → live transition so a user who backed out of the party isn't dragged
    // straight back in (that path leaves wasLiveParty=true, so no transition fires).
    private fun maybeAutoJoin() {
        val live = liveStatus?.isLiveParty() == true
        if (live && !wasLiveParty) {
            wasLiveParty = true
            Log.d(TAG, "auto-join: party went live → entering it")
            onStartWatchingTapped()
            return
        }
        wasLiveParty = live
    }

    // Two independent calls: the pairing record (who we are) and the live playback state
    // (what's on right now). They race, so neither callback touches the UI directly —
    // each stores its own result and re-runs renderHome(), which reads both. Whichever
    // lands second produces the final state, so arrival order can't matter.
    private fun refreshPairingStatus() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "refreshPairingStatus roomId=$roomId")

        api.getRoom(roomId, deviceId) { room, error ->
            runOnUiThread {
                if (room == null) {
                    Log.w(TAG, "getRoom failed: $error")
                    findViewById<TextView>(R.id.tvPairingStatus).text = "Couldn't reach the server — pull to refresh"
                    return@runOnUiThread
                }
                currentRoom = room
                Log.d(TAG, "getRoom room=${room.roomName} partnerPaired=${room.partnerDeviceId != null}")
                profileStore.cacheWelcomeMessages(
                    room.welcomeMessages.filter { it.authorDeviceId != deviceId }.map { it.text }
                )
                profileStore.cacheTheme(room.theme)
                renderHome()
            }
        }

        api.getRoomStatus(roomId) { status ->
            runOnUiThread {
                Log.d(TAG, "getRoomStatus active=${status?.active} count=${status?.count} url=${status?.videoUrl}")
                liveStatus = status
                renderHome()
                maybeAutoJoin()
            }
        }
    }

    private fun renderHome() {
        val room = currentRoom
        if (room != null) {
            val partnerPaired = room.partnerDeviceId != null
            findViewById<TextView>(R.id.tvPairingStatus).text = if (partnerPaired) {
                "💗 ${room.roomName} — you're both paired up"
            } else {
                "💗 ${room.roomName} — waiting for your partner to join"
            }
            findViewById<MaterialButton>(R.id.btnInviteNow).text = if (partnerPaired) {
                "💌 Nudge them"
            } else {
                "📤 Share invite link"
            }
        }

        val live = liveStatus?.takeIf { it.isLiveParty() }
        findViewById<TextView>(R.id.tvHeroTitle).text = if (live != null) {
            "▶️  Jump back into ${serviceLabel(live.platform)}"
        } else {
            "🍿  Start our movie night"
        }
        findViewById<TextView>(R.id.tvHeroSub).text = if (live != null) {
            "already playing — join them"
        } else {
            "our own little cinema"
        }
    }

    private fun serviceLabel(platform: String?): String = when (platform?.lowercase()) {
        "netflix" -> "Netflix"
        "primevideo" -> "Prime Video"
        "youtube" -> "YouTube"
        "hotstar" -> "Hotstar"
        else -> "our room"
    }

    // Live party → straight into it on the same video. Otherwise pick a platform first.
    // liveStatus is null until the first response lands, so the pre-load default is the
    // safe one (the selector), never a jump into a video that may not exist.
    private fun onStartWatchingTapped() {
        val live = liveStatus?.takeIf { it.isLiveParty() }
        val roomId = deviceIdentity.localRoomId()
        if (live == null || roomId.isNullOrBlank()) {
            Log.d(TAG, "hero tapped, no live party → ServiceSelector")
            startActivity(Intent(this, ServiceSelectorActivity::class.java))
            return
        }
        Log.d(TAG, "hero tapped, live party → MainActivity platform=${live.platform} url=${live.videoUrl}")
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", live.platform ?: "hotstar")
            putExtra("hwp_url", live.videoUrl)
            putExtra("roomId", roomId)
            putExtra("join", true)
        })
    }

    private fun onInviteButtonTapped() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        if (currentRoom?.partnerDeviceId == null) {
            shareInviteLink(roomId, deviceId)
        } else {
            nudgePartner(roomId, deviceId)
        }
    }

    private fun shareInviteLink(roomId: String, deviceId: String) {
        Log.d(TAG, "shareInviteLink roomId=$roomId")
        InviteSharing.mintAndShare(this, api, roomId, deviceId, null) { error ->
            runOnUiThread {
                if (error != null) {
                    Log.w(TAG, "shareInviteLink failed: $error")
                    Toast.makeText(this, "Couldn't create a new invite — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun nudgePartner(roomId: String, deviceId: String) {
        Log.d(TAG, "nudgePartner roomId=$roomId")
        api.triggerNudge(roomId, deviceId) { ok ->
            runOnUiThread {
                Log.d(TAG, "nudgePartner result ok=$ok")
                Toast.makeText(
                    this,
                    if (ok) "Nudge sent 💌" else "Couldn't reach them right now — try again later",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
