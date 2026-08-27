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
import android.os.SystemClock
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
    private lateinit var fabPower: FloatingActionButton
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

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var manager: WatchPartyManager? = null
    private var currentService: StreamingService? = null

    private var lastSyncTime: Double? = null
    private var lastSyncPaused: Boolean? = null

    /** elapsedRealtime when lastSyncTime arrived, so we can age it before replaying it. */
    private var lastSyncReceivedAt: Long = 0L

    /** elapsedRealtime of the last navigation we forced, to keep reloads from looping. */
    private var lastReloadAt: Long = 0L

    @Volatile private var currentPageUrl: String = ""

    /** URL reported by JS. Leads [currentPageUrl] during SPA navigation. */
    @Volatile private var jsPageUrl: String = ""

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

            // Correct beyond this much error. Mirrors SyncPolicy.DRIFT_SEC.
            var DRIFT = 1.0;
            // Mirrors SyncPolicy.MAX_PROJECTION_SEC — see SyncPolicy.kt for the tested reference.
            var MAX_PROJECTION = 30;
            var HEARTBEAT_MS = 1000;   // host → server push
            var CHECK_MS = 250;        // guest re-checks itself between heartbeats
            var CORRECTION_GAP_MS = 2000;  // never stack corrective seeks faster than this
            var SEEK_SETTLE_MS = 10000;    // give up waiting for a seek to settle after this

            var video = null;
            var trackedVideos = [];
            var isSyncing = false;
            var isHost = false;
            var pollTimer = null;
            var stateInterval = null;
            var checkTimer = null;
            var hostState = null;      // { time, paused, recvAt } — recvAt is OUR clock
            var lastUrlSent = '';
            var seekCost = 0.8;        // EMA, seconds: how long a seek takes to resume here
            var pendingSeekAt = 0;
            var lastCorrectionAt = 0;

            // Where the host's video is *now*. Its sample is always stale by the time it
            // reaches us, and seeking to a stale timestamp is what left guests permanently
            // behind: the seek itself costs seconds, so they land where the host used to be.
            function projectedTarget() {
                if (!hostState) return null;
                if (hostState.paused) return hostState.time;
                var age = Math.max(0, (Date.now() - hostState.recvAt) / 1000);
                return hostState.time + Math.min(age, MAX_PROJECTION);
            }

            function noteSeekSettled() {
                if (!pendingSeekAt) return;
                var cost = (Date.now() - pendingSeekAt) / 1000;
                pendingSeekAt = 0;
                if (cost > 0 && cost < 20) {
                    seekCost = 0.7 * seekCost + 0.3 * cost;
                    if (seekCost < 0.2) seekCost = 0.2;
                    if (seekCost > 5.0) seekCost = 5.0;
                }
                console.log('[HWP] seek settled in ' + cost.toFixed(2) + 's — seekCost now ' + seekCost.toFixed(2) + 's');
            }

            function evaluate(reason) {
                if (isHost || !video || !hostState) return;
                if (pendingSeekAt) {
                    // A correction is still buffering. Issuing another seek now would restart
                    // that buffer and we would never converge.
                    if (Date.now() - pendingSeekAt < SEEK_SETTLE_MS) return;
                    pendingSeekAt = 0;
                }
                var target = projectedTarget();
                var err = video.currentTime - target;   // negative → we are behind the host
                var pauseMismatch = video.paused !== hostState.paused;

                if (Math.abs(err) > DRIFT) {
                    if (Date.now() - lastCorrectionAt < CORRECTION_GAP_MS) return;
                    lastCorrectionAt = Date.now();
                    // Aim past the target by what a seek actually costs on THIS device, so
                    // playback resumes level with the host rather than seekCost seconds behind.
                    var lead = hostState.paused ? 0 : seekCost;
                    console.log('[HWP] correcting (' + reason + '): err=' + err.toFixed(2) + 's target=' + target.toFixed(2) + ' lead=' + lead.toFixed(2));
                    isSyncing = true;
                    pendingSeekAt = Date.now();
                    video.currentTime = target + lead;
                    if (hostState.paused && !video.paused) video.pause();
                    else if (!hostState.paused && video.paused) video.play().catch(function() {});
                    setTimeout(function() { isSyncing = false; }, 1000);
                } else if (pauseMismatch) {
                    console.log('[HWP] pause mismatch (' + reason + '): err=' + err.toFixed(2) + 's → ' + (hostState.paused ? 'pause' : 'play'));
                    isSyncing = true;
                    if (hostState.paused) video.pause();
                    else video.play().catch(function() {});
                    setTimeout(function() { isSyncing = false; }, 1000);
                }
            }

            // Native hands us the host's latest sample; we stamp arrival on our own clock so
            // we can age it ourselves. No cross-device clock agreement is needed.
            window.HWP_applyHostState = function(time, paused) {
                hostState = { time: time, paused: paused, recvAt: Date.now() };
                console.log('[HWP] host state: t=' + time.toFixed(2) + ' paused=' + paused + (video ? '' : ' (no video yet — will apply when found)'));
                evaluate('host-update');
            };

            // Older callers (and onPageFinished replay) still use this name.
            window.HWP_syncTo = function(time, paused) { window.HWP_applyHostState(time, paused); };

            window.HWP_setRole = function(r) {
                isHost = (r === 'host');
                if (stateInterval) { clearInterval(stateInterval); stateInterval = null; }
                if (checkTimer)    { clearInterval(checkTimer);    checkTimer    = null; }
                if (isHost) {
                    stateInterval = setInterval(function() {
                        if (!video) return;
                        HwpBridge.onStateUpdate(video.currentTime, video.paused, window.location.href);
                    }, HEARTBEAT_MS);
                } else {
                    // Self-check between heartbeats: the target is extrapolated locally, so
                    // this costs no network traffic and catches drift within 250ms.
                    checkTimer = setInterval(function() { evaluate('tick'); }, CHECK_MS);
                }
                console.log('[HWP] role=' + r + ' heartbeat=' + (isHost ? HEARTBEAT_MS + 'ms' : 'off') + ' selfCheck=' + (isHost ? 'off' : CHECK_MS + 'ms'));
            };

            window.HWP_stop = function() {
                if (pollTimer)     { clearTimeout(pollTimer);      pollTimer     = null; }
                if (stateInterval) { clearInterval(stateInterval); stateInterval = null; }
                if (checkTimer)    { clearInterval(checkTimer);    checkTimer    = null; }
                trackedVideos = [];
                video = null;
                hostState = null;
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
                    // A seek while the host is paused never fires 'playing', so settle here.
                    if (!isHost && hostState && hostState.paused) noteSeekSettled();
                });
                // Rebuffering is the main way a guest falls behind, and nothing used to
                // observe it. The moment playback resumes, re-measure and re-check.
                v.addEventListener('playing', function() {
                    if (isHost) return;
                    noteSeekSettled();
                    evaluate('playing');
                });
                v.addEventListener('waiting', function() {
                    console.log('[HWP] buffering at t=' + v.currentTime.toFixed(2));
                });
                v.addEventListener('stalled', function() {
                    console.log('[HWP] stalled at t=' + v.currentTime.toFixed(2));
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
                        // A host sample that arrived before the video existed is still valid —
                        // projectedTarget() ages it, so it does not need re-buffering here.
                        evaluate('video-ready');
                    }
                }

                // Report our URL from JS. The native onPageFinished/doUpdateVisitedHistory
                // callbacks lag behind SPA pushState (see Learnings/spa-url-tracking-android-webview.md),
                // and comparing against that stale value is what made the guest reload the
                // page instead of syncing.
                var href = window.location.href;
                if (href !== lastUrlSent) {
                    lastUrlSent = href;
                    if (HwpBridge.onUrlChange) HwpBridge.onUrlChange(href);
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

        /** Page URL as JS sees it — authoritative during SPA navigation, unlike currentPageUrl. */
        @JavascriptInterface
        fun onUrlChange(url: String) {
            Log.d(TAG, "JsBridge.onUrlChange url=$url")
            jsPageUrl = url
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlufflesTheme.apply(this)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val serviceName = intent.getStringExtra("service") ?: "hotstar"
        currentService = getStreamingService(serviceName)
        Log.d(TAG, "onCreate — service=$serviceName storedName=\"${prefs.getString(KEY_NAME, "")}\"")

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        fabParty = findViewById(R.id.fabParty)
        fabChat = findViewById(R.id.fabChat)
        fabMic = findViewById(R.id.fabMic)
        fabPower = findViewById(R.id.fabPower)
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
            ownDisplayName = { effectiveDisplayName() },
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
            if (manager?.isConnected() == true) showLeaveDialog() else joinPairedRoom()
        }
        fabChat.setOnClickListener {
            Log.d(TAG, "fabChat click → toggle overlay")
            chatOverlay.toggle()
        }
        fabPower.setOnClickListener { confirmCloseTheirApp() }
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
                    // A page load takes seconds; replaying the raw timestamp we received
                    // before it started is what left guests behind after every navigation.
                    val age = SystemClock.elapsedRealtime() - lastSyncReceivedAt
                    val projected = SyncPolicy.projectTarget(t, p, age)
                    Log.d(TAG, "onPageFinished replay: t=$t aged ${age}ms → $projected paused=$p")
                    view.evaluateJavascript("HWP_applyHostState($projected, $p)", null)
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

    // There is exactly one room — the paired one — so there is nothing to ask for. The
    // old join-by-code dialog was a leftover of the multi-room era; the pairing redesign
    // removed the rest of it but this entry point survived and kept prompting.
    private fun joinPairedRoom() {
        val roomId = DeviceIdentity(prefs).localRoomId()
        Log.d(TAG, "joinPairedRoom pairedRoom=$roomId")
        if (roomId.isNullOrBlank()) {
            Log.w(TAG, "joinPairedRoom: no paired room — routing to setup")
            Toast.makeText(this, "Let's finish setting us up first 💌", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, WelcomeSetupActivity::class.java))
            return
        }
        connectToParty(Config.SERVER_URL, roomId)
    }

    private fun showLeaveDialog() {
        val roleLabel = manager?.role?.replaceFirstChar { it.uppercase() } ?: "Unknown"
        MaterialAlertDialogBuilder(this)
            .setTitle("Leaving already? 🥺")
            .setMessage("You're connected as $roleLabel. End the movie night?")
            .setPositiveButton("Leave") { _, _ -> endParty() }
            .setNegativeButton("Stay", null)
            .show()
    }

    // The display name lives in one of two places depending on how this device was set
    // up: the legacy manual-name flow wrote the top-level KEY_NAME string, but the pairing
    // flow only stores it inside the profile_self JSON (ProfileStore). A paired guest has
    // the latter and not the former, so reading KEY_NAME alone returned "" and aborted the
    // join before it opened a socket — the guest never appeared in the room. Fall back to
    // the profile name so either setup path yields a usable name.
    private fun effectiveDisplayName(): String {
        val legacy = prefs.getString(KEY_NAME, "")?.trim().orEmpty()
        if (legacy.isNotEmpty()) return legacy
        return ProfileStore(prefs).selfProfile()?.displayName?.trim().orEmpty()
    }

    private fun connectToParty(serverUrl: String, room: String) {
        val name = effectiveDisplayName()
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
                fabPower.visibility = View.VISIBLE
                updateMicAppearance()
            },
            onSyncCommand = { time, paused, videoUrl, platform ->
                lastSyncTime = time
                lastSyncPaused = paused
                lastSyncReceivedAt = SystemClock.elapsedRealtime()
                if (platform.isNotEmpty() && platform != currentService?.name) {
                    val newService = getStreamingService(platform)
                    currentService = newService
                    webView.settings.userAgentString = newService.userAgent
                }
                // Prefer the JS-reported URL: currentPageUrl lags behind SPA navigation, and
                // treating that lag as "different video" used to replace every sync with a
                // page reload — which then re-applied a position stale by the whole load.
                val guestUrl = jsPageUrl.ifBlank { currentPageUrl }
                val now = SystemClock.elapsedRealtime()
                if (SyncPolicy.shouldReload(videoUrl, guestUrl, lastReloadAt, now)) {
                    Log.d(TAG, "sync: host on different content → navigating (host=$videoUrl guest=$guestUrl)")
                    lastReloadAt = now
                    webView.loadUrl(videoUrl, currentService?.headersOverride ?: mapOf("X-Requested-With" to ""))
                } else {
                    webView.evaluateJavascript("HWP_applyHostState($time, $paused)", null)
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
                // A membership refusal is the one error a user can actually act on, and
                // the one they will hit if this build predates the deviceId join frame.
                // "Server: not-a-room-member — …" tells them nothing; say what to do.
                val friendly = when (reason) {
                    "not-a-room-member" -> "This phone isn't paired to your room yet 💔 Open your invite link again to reconnect it."
                    "store-unavailable" -> "Couldn't check your room just now — try again in a moment 💫"
                    else -> "Server: $reason — $detail"
                }
                Toast.makeText(this, friendly, Toast.LENGTH_LONG).show()
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
            onCloseApp = { who ->
                Log.d(TAG, "onCloseApp from=$who")
                closeAppOnRequest(who)
            },
            onPartyLeft = { who ->
                Log.d(TAG, "onPartyLeft from=$who")
                partyLeftByPeer(who)
            },
        )

        val platform = currentService?.name ?: "android"
        // deviceId is what proves to the server that this device belongs to the room —
        // the WebSocket used to accept anyone who knew the roomId, and roomId is in
        // every invite link.
        val deviceId = DeviceIdentity(prefs).localDeviceId()
        if (deviceId.isNullOrBlank()) Log.w(TAG, "connectToParty: no deviceId stored — this device is not registered")
        manager?.connect(serverUrl, room, platform, effectiveVideoUrl, name, deviceId)
        tvStatus.text = "finding you… 💫"
        tvStatus.visibility = View.VISIBLE
    }

    /**
     * Ask the other phone to shut down. Guarded by a confirm dialog: it closes an app on
     * someone else's device, so it must never fire on a stray tap.
     */
    private fun confirmCloseTheirApp() {
        Log.d(TAG, "confirmCloseTheirApp()")
        if (manager?.isConnected() != true) {
            Toast.makeText(this, "You're not connected right now", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Close their app? 🌙")
            .setMessage("This shuts the app on their phone and clears it from Recents. Yours stays open.")
            .setPositiveButton("Close it") { _, _ ->
                val sent = manager?.sendCloseApp() ?: false
                Log.d(TAG, "close-app sent=$sent")
                Toast.makeText(
                    this,
                    if (sent) "Goodnight sent 🌙" else "Couldn't reach them right now",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * A peer asked us to shut down. finishAndRemoveTask (not finish) so the app also
     * leaves Recents — otherwise it looks closed but is one swipe from being back.
     */
    private fun closeAppOnRequest(who: String) {
        Log.d(TAG, "closeAppOnRequest from=$who → finishAndRemoveTask")
        Toast.makeText(this, "$who says goodnight 🌙", Toast.LENGTH_SHORT).show()
        manager?.disconnect()
        finishAndRemoveTask()
    }

    private fun leaveParty() {
        Log.d(TAG, "leaveParty()")
        manager?.disconnect()
        manager = null
        lastSyncTime = null
        lastSyncPaused = null
        lastSyncReceivedAt = 0L
        lastReloadAt = 0L
        tvStatus.visibility = View.GONE
        fabChat.visibility = View.GONE
        fabMic.visibility = View.GONE
        fabPower.visibility = View.GONE
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

    /**
     * End the movie night for everyone: clears the room's stored video on the server
     * (so the next join doesn't sync back to it), tears down this device's own
     * connection, then heads back to the platform picker to start a new one.
     */
    private fun endParty() {
        Log.d(TAG, "endParty()")
        manager?.sendLeaveParty()
        leaveParty()
        goHomeToStartNewParty()
    }

    /** A peer sent leave-party — our room state (and video) are gone too, follow them home. */
    private fun partyLeftByPeer(who: String) {
        Log.d(TAG, "partyLeftByPeer from=$who")
        Toast.makeText(this, "$who left the party 💔", Toast.LENGTH_SHORT).show()
        leaveParty()
        goHomeToStartNewParty()
    }

    private fun goHomeToStartNewParty() {
        startActivity(Intent(this, ServiceSelectorActivity::class.java))
        finish()
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
