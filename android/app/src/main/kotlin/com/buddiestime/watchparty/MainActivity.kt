package com.buddiestime.watchparty

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.buddiestime.watchparty.ServiceSelectorActivity
import com.buddiestime.watchparty.StreamingService
import com.buddiestime.watchparty.getStreamingService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

private const val TAG = "HWP-MAIN"
private const val PREFS = "hwp_prefs"
private const val KEY_NAME = "displayName"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fabParty: FloatingActionButton
    private lateinit var fabChat: FloatingActionButton
    private lateinit var fabMic: FloatingActionButton
    private lateinit var tvStatus: TextView
    private lateinit var tvChatBadge: TextView
    private var voice: VoiceManager? = null
    private var voiceStatus: VoiceManager.VoiceStatus = VoiceManager.VoiceStatus.IDLE
    private var micLatched = false
    private var micWasHold = false
    private val micHoldHandler = Handler(Looper.getMainLooper())
    private var micHoldRunnable: Runnable? = null
    private var pendingVoiceToken: Triple<String, String, String>? = null   // token, url, lkRoom
    private val RC_MIC = 42
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var recentRooms: RecentRoomsStore

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var manager: WatchPartyManager? = null
    private var currentService: StreamingService? = null

    private var lastSyncTime: Double? = null
    private var lastSyncPaused: Boolean? = null

    @Volatile private var currentPageUrl: String = ""

    // Chat state
    private var participants: List<Participant> = emptyList()
    private var unreadChatCount = 0
    private lateinit var chatOverlay: ChatOverlayController

    // Floating heart reactions
    private var activeHearts = 0
    private val maxHearts = 12

    private val SYNC_SCRIPT = """
        (function() {
            if (window.__hwpNative) return;
            window.__hwpNative = true;

            var DRIFT = 2;
            var video = null;
            var trackedVideos = [];
            var isSyncing = false;
            var isHost = false;
            var pollTimer = null;
            var stateInterval = null;
            var pendingSync = null;

            window.HWP_setRole = function(r) {
                isHost = (r === 'host');
                if (isHost) {
                    if (stateInterval) clearInterval(stateInterval);
                    stateInterval = setInterval(function() {
                        if (!video) return;
                        console.log('[HWP] periodic state-update: t=' + video.currentTime.toFixed(2) + ' paused=' + video.paused + ' url=' + window.location.href);
                        HwpBridge.onStateUpdate(video.currentTime, video.paused, window.location.href);
                    }, 5000);
                }
            };

            window.HWP_syncTo = function(time, paused) {
                if (!video) {
                    console.log('[HWP] syncTo buffered: t=' + time + ' paused=' + paused);
                    pendingSync = { time: time, paused: paused };
                    return;
                }
                var drift = Math.abs(video.currentTime - time);
                var pauseMismatch = video.paused !== paused;
                console.log('[HWP] syncTo check: hostT=' + time.toFixed(2) + ' guestT=' + video.currentTime.toFixed(1) + ' drift=' + drift.toFixed(2) + ' pauseMismatch=' + pauseMismatch);
                if (drift > DRIFT) {
                    console.log('[HWP] drift exceeded — seeking + correcting pause');
                    isSyncing = true;
                    video.currentTime = time;
                    if (paused && !video.paused) video.pause();
                    else if (!paused && video.paused) video.play().catch(function() {});
                    setTimeout(function() { isSyncing = false; }, 500);
                } else if (pauseMismatch) {
                    console.log('[HWP] pause mismatch — correcting pause state only');
                    isSyncing = true;
                    if (paused && !video.paused) video.pause();
                    else if (!paused && video.paused) video.play().catch(function() {});
                    setTimeout(function() { isSyncing = false; }, 500);
                } else {
                    console.log('[HWP] in sync — no correction needed');
                }
            };

            window.HWP_stop = function() {
                if (pollTimer)     { clearTimeout(pollTimer);   pollTimer     = null; }
                if (stateInterval) { clearInterval(stateInterval); stateInterval = null; }
                trackedVideos = [];
                video = null;
                window.__hwpNative = false;
            };

            function attachListeners(v) {
                v.addEventListener('play', function() {
                    console.log('[HWP] play t=' + v.currentTime.toFixed(2) + ' host=' + isHost + ' syncing=' + isSyncing);
                    if (!isSyncing && isHost) HwpBridge.onStateUpdate(v.currentTime, false, window.location.href);
                });
                v.addEventListener('pause', function() {
                    console.log('[HWP] pause t=' + v.currentTime.toFixed(2) + ' host=' + isHost + ' syncing=' + isSyncing);
                    if (!isSyncing && isHost) HwpBridge.onStateUpdate(v.currentTime, true, window.location.href);
                });
                v.addEventListener('seeked', function() {
                    console.log('[HWP] seeked t=' + v.currentTime.toFixed(2) + ' host=' + isHost + ' syncing=' + isSyncing);
                    if (!isSyncing && isHost) HwpBridge.onStateUpdate(v.currentTime, v.paused, window.location.href);
                });
            }

            function getBestVideo() {
                var best = null;
                for (var i = 0; i < trackedVideos.length; i++) {
                    var v = trackedVideos[i];
                    if (!document.contains(v)) continue;
                    if (v.readyState <= 0 || v.duration <= 1) continue;
                    if (!best) { best = v; continue; }
                    if (!v.paused && best.paused) { best = v; continue; }
                    if (v.duration > (best.duration || 0)) { best = v; }
                }
                return best;
            }

            function poll() {
                var all = document.querySelectorAll('video');
                for (var i = 0; i < all.length; i++) {
                    var v = all[i];
                    var known = false;
                    for (var j = 0; j < trackedVideos.length; j++) {
                        if (trackedVideos[j] === v) { known = true; break; }
                    }
                    if (!known) {
                        trackedVideos.push(v);
                        console.log('[HWP] video #' + trackedVideos.length + ' found: readyState=' + v.readyState + ' dur=' + v.duration.toFixed(1));
                        attachListeners(v);
                    }
                }
                var best = getBestVideo();
                if (best) {
                    if (best !== video) {
                        video = best;
                        console.log('[HWP] active video selected: dur=' + best.duration.toFixed(1) + ' readyState=' + best.readyState + ' paused=' + best.paused);
                    }
                    if (pendingSync) {
                        var ps = pendingSync;
                        pendingSync = null;
                        console.log('[HWP] applying pendingSync: t=' + ps.time + ' paused=' + ps.paused);
                        HWP_syncTo(ps.time, ps.paused);
                    }
                }
                pollTimer = setTimeout(poll, 1000);
            }

            poll();
        })();
    """.trimIndent()

    inner class JsBridge {
        @JavascriptInterface
        fun onStateUpdate(time: Double, paused: Boolean, url: String) {
            Log.d(TAG, "JsBridge.onStateUpdate t=${"%.2f".format(time)} paused=$paused url=$url")
            manager?.sendStateUpdate(time, paused, url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlufflesTheme.apply(this)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        recentRooms = RecentRoomsStore(prefs)

        val serviceName = intent.getStringExtra("service") ?: "hotstar"
        currentService = getStreamingService(serviceName)
        Log.d(TAG, "onCreate — service=$serviceName storedName=\"${prefs.getString(KEY_NAME, "")}\"")

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        fabParty = findViewById(R.id.fabParty)
        fabChat = findViewById(R.id.fabChat)
        fabMic = findViewById(R.id.fabMic)
        tvStatus = findViewById(R.id.tvStatus)
        tvChatBadge = findViewById(R.id.tvChatBadge)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        chatOverlay = ChatOverlayController(
            overlayRoot = findViewById(R.id.chatOverlay),
            ambientView = findViewById(R.id.tvOverlayAmbient),
            peersView = findViewById(R.id.tvOverlayPeers),
            scrollView = findViewById(R.id.svOverlay),
            messagesContainer = findViewById(R.id.llOverlayMessages),
            inputRow = findViewById(R.id.rowOverlayInput),
            inputField = findViewById(R.id.etOverlayInput),
            ownDisplayName = { prefs.getString(KEY_NAME, "")?.trim().orEmpty() },
            onSend = { text ->
                val ok = manager?.isConnected() == true
                if (ok) manager?.sendChat(text)
                ok
            },
            onUnread = {
                unreadChatCount++
                tvChatBadge.text = unreadChatCount.toString()
                tvChatBadge.visibility = View.VISIBLE
            },
            onExpandedChanged = { expanded ->
                if (expanded) {
                    unreadChatCount = 0
                    tvChatBadge.visibility = View.GONE
                }
            }
        )

        setupWebView()

        fabParty.setOnClickListener {
            if (manager?.isConnected() == true) showLeaveDialog() else showJoinDialog()
        }
        fabChat.setOnClickListener {
            Log.d(TAG, "fabChat click → toggle overlay")
            chatOverlay.toggle()
        }
        findViewById<TextView>(R.id.btnSendHeart).setOnClickListener {
            Log.d(TAG, "btnSendHeart click → sending reaction")
            manager?.sendReaction("💗")
            showFloatingHearts("💗", 4)   // sender sees their own burst locally
        }
        setupMicButton()

        val startUrl = intent.getStringExtra("hwp_url") ?: currentService?.url ?: "https://www.hotstar.com"
        currentService?.let { service -> webView.loadUrl(startUrl, service.headersOverride) }
            ?: webView.loadUrl(startUrl, mapOf("X-Requested-With" to ""))

        val testServer = intent.getStringExtra("hwp_server")
        val testRoom   = intent.getStringExtra("hwp_room")
        val joinRoomId = intent.getStringExtra("roomId")
        if (testServer != null && testRoom != null) {
            Log.d(TAG, "debug auto-connect: $testServer / $testRoom")
            connectToParty(testServer, testRoom)
        } else if (intent.getBooleanExtra("join", false)) {
            if (joinRoomId.isNullOrBlank()) {
                Log.w(TAG, "home-join requested but roomId missing/blank — ignoring")
            } else {
                Log.d(TAG, "home-join auto-connect: room=$joinRoomId")
                connectToParty(Config.SERVER_URL, joinRoomId)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = currentService?.userAgent
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            useWideViewPort = false
            loadWithOverviewMode = false
            allowFileAccess = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(JsBridge(), "HwpBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && !request.isRedirect) {
                    view.loadUrl(request.url.toString(), currentService?.headersOverride ?: mapOf("X-Requested-With" to ""))
                    return true
                }
                return false
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                currentPageUrl = url
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentPageUrl = url
                Log.d(TAG, "onPageFinished url=$url")
                view.evaluateJavascript("""
                    (function() {
                        document.documentElement.lang = 'en-IN';
                        Object.defineProperty(navigator, 'language', { value: 'en-IN', writable: false, configurable: true });
                        console.log('[HWP] Language set to en-IN');
                    })();
                """.trimIndent(), null)

                view.evaluateJavascript(SYNC_SCRIPT, null)
                manager?.role?.let { view.evaluateJavascript("HWP_setRole('$it')", null) }
                val t = lastSyncTime
                val p = lastSyncPaused
                if (t != null && p != null && manager?.role == "guest") {
                    view.evaluateJavascript("HWP_syncTo($t, $p)", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.grant(request.resources) }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView = view
                fullscreenCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                hideSystemUi()
                chatOverlay.bringToFront()
                fabChat.bringToFront()
                tvChatBadge.bringToFront()
            }
            override fun onHideCustomView() {
                fullscreenView?.let { fullscreenContainer.removeView(it) }
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                showSystemUi()
                chatOverlay.bringToFront()
                fabChat.bringToFront()
                tvChatBadge.bringToFront()
            }
        }
    }

    // ── Party management ──────────────────────────────────────────────────────

    private fun showJoinDialog() {
        Log.d(TAG, "showJoinDialog")
        val dialogView = layoutInflater.inflate(R.layout.dialog_join_party, null)
        val etServer = dialogView.findViewById<TextInputEditText>(R.id.etServer)
        val etRoom = dialogView.findViewById<TextInputEditText>(R.id.etRoom)
        val tilServer = dialogView.findViewById<TextInputLayout>(R.id.tilServer)
        val tvAdvanced = dialogView.findViewById<TextView>(R.id.tvAdvancedToggle)
        etServer.setText(Config.SERVER_URL)

        tvAdvanced.setOnClickListener {
            val show = tilServer.visibility != View.VISIBLE
            Log.d(TAG, "advanced toggle → showServer=$show")
            tilServer.visibility = if (show) View.VISIBLE else View.GONE
            tvAdvanced.text = if (show) "advanced ▾" else "advanced ▸"
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Watch together 💗")
            .setView(dialogView)
            .setPositiveButton("Join") { _, _ ->
                val server = etServer.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: Config.SERVER_URL
                val room = etRoom.text?.toString()?.trim().orEmpty()
                Log.d(TAG, "Join click server=$server room=$room")
                if (room.isNotEmpty()) connectToParty(server, room)
                else Toast.makeText(this, "Type the room code first 💌", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Create new") { _, _ ->
                val server = etServer.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: Config.SERVER_URL
                val room = generateRoomId()
                Log.d(TAG, "Create click server=$server generatedRoom=$room")
                connectToParty(server, room)
                Toast.makeText(this, "Our room is ready: $room 💌", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        etRoom.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
            true
        }
        dialog.show()
    }

    private fun generateRoomId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun showLeaveDialog() {
        val roleLabel = manager?.role?.replaceFirstChar { it.uppercase() } ?: "Unknown"
        MaterialAlertDialogBuilder(this)
            .setTitle("Leaving already? 🥺")
            .setMessage("You're connected as $roleLabel. End the movie night?")
            .setPositiveButton("Leave") { _, _ -> leaveParty() }
            .setNegativeButton("Stay", null)
            .show()
    }

    private fun connectToParty(serverUrl: String, room: String) {
        val name = prefs.getString(KEY_NAME, "")?.trim().orEmpty()
        Log.d(TAG, "connectToParty server=$serverUrl room=$room storedName=\"$name\"")
        if (name.isEmpty()) {
            Log.w(TAG, "connectToParty: no stored name — aborting and sending user back to selector")
            Toast.makeText(this, "Enter your name first (App > restart and pick a name)", Toast.LENGTH_LONG).show()
            return
        }
        // currentPageUrl only updates once the WebView finishes navigating (async), so right after
        // onCreate triggers loadUrl(hwp_url) it's still blank — fall back to the intended URL so we
        // don't send/store an empty videoUrl and clobber the recent-room entry we just used to get here.
        val effectiveVideoUrl = currentPageUrl.ifBlank { intent.getStringExtra("hwp_url").orEmpty() }

        manager = WatchPartyManager(
            onRoleAssigned = { role ->
                Log.d(TAG, "onRoleAssigned role=$role")
                webView.evaluateJavascript("HWP_setRole('$role')", null)
                tvStatus.text = statusForRole(role)
                tvStatus.visibility = View.VISIBLE
                fabChat.visibility = View.VISIBLE
                chatOverlay.show()
                fabMic.visibility = View.VISIBLE
                updateMicAppearance()
            },
            onSyncCommand = { time, paused, videoUrl, platform ->
                lastSyncTime = time
                lastSyncPaused = paused
                if (platform.isNotEmpty() && platform != currentService?.name) {
                    val newService = getStreamingService(platform)
                    currentService = newService
                    webView.settings.userAgentString = newService.userAgent
                }
                if (videoUrl.isNotEmpty() && videoUrl != currentPageUrl) {
                    webView.loadUrl(videoUrl, currentService?.headersOverride ?: mapOf("X-Requested-With" to ""))
                } else {
                    webView.evaluateJavascript("HWP_syncTo($time, $paused)", null)
                }
            },
            onStatusChange = { status ->
                Log.d(TAG, "onStatusChange status=\"$status\"")
                tvStatus.text = status
                tvStatus.visibility = View.VISIBLE
            },
            onChatMessage = { m ->
                Log.d(TAG, "onChatMessage from ${m.name}(${m.from}): \"${m.text.take(80)}\"")
                chatOverlay.onIncoming(m)
            },
            onParticipantsChange = { list ->
                Log.d(TAG, "onParticipantsChange count=${list.size}")
                val prevCount = participants.size
                participants = list
                chatOverlay.setPeers(list)
                if (prevCount < 2 && list.size >= 2) {
                    Log.d(TAG, "together now ($prevCount→${list.size}) → heart burst + toast")
                    Toast.makeText(this, "you're watching together 💗", Toast.LENGTH_SHORT).show()
                    showFloatingHearts("💗", 5)
                }
            },
            onServerError = { reason, detail ->
                Log.w(TAG, "onServerError reason=$reason detail=$detail")
                Toast.makeText(this, "Server: $reason — $detail", Toast.LENGTH_LONG).show()
            },
            onVoiceToken = { token, url, lkRoom ->
                Log.d(TAG, "onVoiceToken lkRoom=$lkRoom → joining LK")
                ensureVoice().joinVoice(url, token)
            },
            onVoiceParticipants = { list ->
                Log.d(TAG, "onVoiceParticipants count=${list.size}")
            },
            onReconnecting = { attempt ->
                Log.d(TAG, "onReconnecting attempt=$attempt")
                tvStatus.text = "coming back to you… (#$attempt)"
                tvStatus.visibility = View.VISIBLE
            },
            onReaction = { emoji ->
                Log.d(TAG, "onReaction emoji=$emoji → floating hearts")
                showFloatingHearts(emoji)
            },
        )

        val platform = currentService?.name ?: "android"
        manager?.connect(serverUrl, room, platform, effectiveVideoUrl, name)
        tvStatus.text = "finding you… 💫"
        tvStatus.visibility = View.VISIBLE

        recentRooms.add(RecentRoom(
            roomId = room,
            platform = currentService?.name ?: "hotstar",
            videoUrl = effectiveVideoUrl,
            lastJoined = System.currentTimeMillis()
        ))
    }

    private fun leaveParty() {
        Log.d(TAG, "leaveParty()")
        manager?.disconnect()
        manager = null
        lastSyncTime = null
        lastSyncPaused = null
        tvStatus.visibility = View.GONE
        fabChat.visibility = View.GONE
        fabMic.visibility = View.GONE
        voice?.dispose()
        voice = null
        voiceStatus = VoiceManager.VoiceStatus.IDLE
        micLatched = false
        tvChatBadge.visibility = View.GONE
        participants = emptyList()
        unreadChatCount = 0
        chatOverlay.reset()
        webView.evaluateJavascript("if (window.HWP_stop) HWP_stop();", null)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    @Suppress("DEPRECATION")
    private fun showSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> {
                fullscreenView?.let { fullscreenContainer.removeView(it) }
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                showSystemUi()
            }
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    // onDestroy is defined below with voice-manager cleanup.

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_switch_service -> {
                leaveParty()
                startActivity(Intent(this, ServiceSelectorActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Voice (LiveKit) ───────────────────────────────────────────────────────

    private fun ensureVoice(): VoiceManager {
        voice?.let { return it }
        Log.d(TAG, "ensureVoice() — creating new VoiceManager")
        val vm = VoiceManager(
            context = this,
            onStatusChange = { s ->
                Log.d(TAG, "voice status → $s")
                voiceStatus = s
                runOnUiThread { updateMicAppearance() }
                if (s == VoiceManager.VoiceStatus.DISCONNECTED) {
                    micLatched = false
                }
            },
            onSpeakersChange = { ids -> Log.d(TAG, "voice speakers=$ids") },
            onError = { msg ->
                Log.w(TAG, "voice error: $msg")
                runOnUiThread { Toast.makeText(this, "Voice: $msg", Toast.LENGTH_LONG).show() }
            }
        )
        voice = vm
        return vm
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMicButton() {
        Log.d(TAG, "setupMicButton()")
        fabMic.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "mic ACTION_DOWN — voiceStatus=$voiceStatus")
                    if (voiceStatus == VoiceManager.VoiceStatus.IDLE || voiceStatus == VoiceManager.VoiceStatus.DISCONNECTED) {
                        Log.d(TAG, "  not in voice → request join flow")
                        micLatched = false
                        requestJoinVoice()
                        return@setOnTouchListener true
                    }
                    micWasHold = false
                    val r = Runnable {
                        Log.d(TAG, "  → 300ms threshold → HOLD mode")
                        micWasHold = true
                        voice?.setMicOn(true)
                        updateMicAppearance()
                    }
                    micHoldRunnable = r
                    micHoldHandler.postDelayed(r, 300)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Log.d(TAG, "mic ACTION_UP/CANCEL wasHold=$micWasHold")
                    micHoldRunnable?.let { micHoldHandler.removeCallbacks(it) }
                    micHoldRunnable = null
                    if (voiceStatus != VoiceManager.VoiceStatus.CONNECTED) {
                        Log.d(TAG, "  not connected — ignoring release")
                        return@setOnTouchListener true
                    }
                    if (micWasHold) {
                        Log.d(TAG, "  release from HOLD → mic off")
                        voice?.setMicOn(false)
                        micWasHold = false
                    } else {
                        micLatched = !micLatched
                        Log.d(TAG, "  click release → latched=$micLatched")
                        voice?.setMicOn(micLatched)
                    }
                    updateMicAppearance()
                    true
                }
                else -> false
            }
        }
    }

    private fun statusForRole(role: String?): String =
        if (role == "host") "💗 our room is live" else "💗 together now"

    /**
     * Floats a burst of emoji hearts up from the bottom-right, over whatever
     * is on screen (WebView or fullscreen video). Hard-capped so reaction
     * spam can't leak views or jank playback; every view removes itself.
     */
    private fun showFloatingHearts(emoji: String, burst: Int = 6) {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        Log.d(TAG, "showFloatingHearts emoji=$emoji burst=$burst active=$activeHearts")
        repeat(burst) { i ->
            if (activeHearts >= maxHearts) {
                Log.d(TAG, "  heart cap ($maxHearts) reached — dropping remainder")
                return
            }
            activeHearts++
            val tv = TextView(this).apply {
                text = emoji
                textSize = (26 + Math.random() * 10).toFloat()
                alpha = 0f
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = (w * (0.55 + Math.random() * 0.32)).toInt()
            lp.topMargin = h - (140 * density).toInt()
            root.addView(tv, lp)

            val rise = (h * (0.35 + Math.random() * 0.25)).toFloat()
            val drift = ((Math.random() - 0.5) * 140).toFloat()
            val duration = (1200 + Math.random() * 800).toLong()
            tv.animate()
                .alpha(1f)
                .setStartDelay(i * 90L)
                .setDuration(150)
                .withEndAction {
                    tv.animate()
                        .translationY(-rise)
                        .translationX(drift)
                        .alpha(0f)
                        .setDuration(duration)
                        .withEndAction {
                            root.removeView(tv)
                            activeHearts--
                            Log.d(TAG, "  heart removed, active=$activeHearts")
                        }
                        .start()
                }
                .start()
        }
    }

    private fun updateMicAppearance() {
        val tint = when {
            voiceStatus != VoiceManager.VoiceStatus.CONNECTED ->
                ContextCompat.getColor(this, R.color.plum_surface_hi)
            micWasHold -> ContextCompat.getColor(this, R.color.error_rose)
            micLatched -> ContextCompat.getColor(this, R.color.mint_ok)
            else -> ContextCompat.getColor(this, R.color.plum_surface_hi)
        }
        fabMic.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        Log.d(TAG, "updateMicAppearance: status=$voiceStatus latched=$micLatched hold=$micWasHold")
    }

    private fun requestJoinVoice() {
        Log.d(TAG, "requestJoinVoice()")
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "  RECORD_AUDIO granted=$hasPerm")
        if (!hasPerm) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC)
            return
        }
        val m = manager
        if (m == null) { Log.w(TAG, "  no manager"); return }
        voiceStatus = VoiceManager.VoiceStatus.CONNECTING
        updateMicAppearance()
        m.requestVoiceToken()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult rc=$requestCode granted=${grantResults.joinToString()}")
        if (requestCode == RC_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "  mic granted — proceeding with voice join")
                requestJoinVoice()
            } else {
                Toast.makeText(this, "Mic permission required for voice chat", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        voice?.dispose()
        voice = null
        manager?.disconnect()
        webView.destroy()
        super.onDestroy()
    }
}
