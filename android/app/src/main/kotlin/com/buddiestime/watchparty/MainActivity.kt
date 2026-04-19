package com.buddiestime.watchparty

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.buddiestime.watchparty.ServiceSelectorActivity
import com.buddiestime.watchparty.StreamingService
import com.buddiestime.watchparty.getStreamingService
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fabParty: FloatingActionButton
    private lateinit var tvStatus: TextView
    private lateinit var fullscreenContainer: FrameLayout

    // Holds the View that Hotstar pushes into fullscreen mode
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var manager: WatchPartyManager? = null
    private var currentService: StreamingService? = null

    // Last sync command received — re-applied after each page load so the guest
    // catches up even if the state-update arrived before the page was ready.
    private var lastSyncTime: Double? = null
    private var lastSyncPaused: Boolean? = null

    // Current WebView URL — updated on every page finish, read by JsBridge off main thread.
    @Volatile private var currentPageUrl: String = ""

    // ── Injected JS that runs inside the streaming page ───────────────────────
    // Polls for <video> elements, attaches listeners (host sends state-update on
    // every event + every 2s), and exposes HWP_* functions called by native.
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

            /** Called by native after the room role is confirmed. */
            window.HWP_setRole = function(r) {
                isHost = (r === 'host');
                if (isHost) {
                    if (stateInterval) clearInterval(stateInterval);
                    stateInterval = setInterval(function() {
                        if (!video) return;
                        console.log('[HWP] periodic state-update: t=' + video.currentTime.toFixed(2) + ' paused=' + video.paused + ' url=' + window.location.href);
                        HwpBridge.onStateUpdate(video.currentTime, video.paused, window.location.href);
                    }, 2000);
                }
            };

            /** Guest: apply a state-update from the host — mirrors extension applySync logic. */
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

            /** Native calls this on disconnect to stop all timers. */
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

            // Only consider real content: readyState > 0 and duration > 1s (skips ads/previews).
            // Among candidates prefer playing video; among paused prefer longest duration.
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

            // Poll for all video elements — Prime Video has multiple.
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

    // ── JS Bridge (called from inside the WebView page) ───────────────────────

    inner class JsBridge {
        /** Called on every host video event (play/pause/seeked) and every 2s by the JS timer. */
        @JavascriptInterface
        fun onStateUpdate(time: Double, paused: Boolean, url: String) {
            manager?.sendStateUpdate(time, paused, url)
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read selected service from intent
        val serviceName = intent.getStringExtra("service") ?: "hotstar"
        currentService = getStreamingService(serviceName)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        fabParty = findViewById(R.id.fabParty)
        tvStatus = findViewById(R.id.tvStatus)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        setupWebView()

        fabParty.setOnClickListener {
            if (manager?.isConnected() == true) showLeaveDialog() else showJoinDialog()
        }

        // Debug: --es hwp_url http://... overrides the selected service URL.
        val startUrl = intent.getStringExtra("hwp_url") ?: currentService?.url ?: "https://www.hotstar.com"
        // Load the selected service's URL with its headers
        currentService?.let { service ->
            webView.loadUrl(startUrl, service.headersOverride)
        } ?: run {
            // Fallback if no service (shouldn't happen, but safe)
            webView.loadUrl(startUrl, mapOf("X-Requested-With" to ""))
        }

        // Debug shortcut: launch with --es hwp_server ws://... --es hwp_room roomId
        // to skip the dialog and auto-connect (useful for adb testing).
        val testServer = intent.getStringExtra("hwp_server")
        val testRoom   = intent.getStringExtra("hwp_room")
        if (testServer != null && testRoom != null) {
            connectToParty(testServer, testRoom)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Required so video can autoplay when we call .play() from JS
            mediaPlaybackRequiresUserGesture = false
            // Spoof a Desktop Chrome UA — streaming services show "download app" to mobile UAs;
            // desktop UA forces the full web player to load.
            userAgentString = currentService?.userAgent
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            // Desktop UA ensures the full video player loads; viewport renders at device width
            // so the page fits the mobile screen naturally rather than zooming out to desktop overview
            useWideViewPort = false
            loadWithOverviewMode = false
            // Allow cookies from Hotstar CDN subdomains (needed for login session)
            allowFileAccess = false
        }

        // Persist login cookies across app restarts
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(JsBridge(), "HwpBridge")

        webView.webViewClient = object : WebViewClient() {
            // Suppress X-Requested-With on every navigation — WebView adds the app package name
            // automatically which Hotstar uses to detect WebView and show "Download the App".
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && !request.isRedirect) {
                    view.loadUrl(request.url.toString(), currentService?.headersOverride ?: mapOf("X-Requested-With" to ""))
                    return true
                }
                return false
            }

            // Catches SPA navigations (YouTube, Netflix pushState) that skip onPageFinished.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                currentPageUrl = url
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentPageUrl = url
                // Set language/region for Netflix regional content availability
                // Netflix uses accept-language and document language for region detection
                view.evaluateJavascript("""
                    (function() {
                        // Set document language to en-IN for Indian region
                        document.documentElement.lang = 'en-IN';
                        // Override navigator language (some sites check this)
                        Object.defineProperty(navigator, 'language', {
                            value: 'en-IN',
                            writable: false,
                            configurable: true
                        });
                        console.log('[HWP] Language set to en-IN for regional availability');
                    })();
                """.trimIndent(), null)

                // Re-inject on every page load; the __hwpNative guard prevents double init
                // on SPA navigations that don't trigger a full reload.
                view.evaluateJavascript(SYNC_SCRIPT, null)
                // Re-apply role if we're already in a party (page reload mid-session)
                manager?.role?.let { view.evaluateJavascript("HWP_setRole('$it')", null) }
                // Re-apply last sync command so guest catches up even if sync-response
                // arrived before the page was ready to receive it.
                val t = lastSyncTime
                val p = lastSyncPaused
                if (t != null && p != null && manager?.role == "guest") {
                    view.evaluateJavascript("HWP_syncTo($t, $p)", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Grant Widevine DRM (RESOURCE_PROTECTED_MEDIA_ID) — needed for Hotstar streams
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            // Enter native fullscreen when Hotstar expands the player
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView = view
                fullscreenCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                hideSystemUi()
            }

            override fun onHideCustomView() {
                fullscreenView?.let { fullscreenContainer.removeView(it) }
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                showSystemUi()
            }
        }
    }

    // ── Party management ──────────────────────────────────────────────────────

    private fun showJoinDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_join_party, null)
        val etServer = dialogView.findViewById<TextInputEditText>(R.id.etServer)
        val etRoom = dialogView.findViewById<TextInputEditText>(R.id.etRoom)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Watch Party")
            .setView(dialogView)
            // Join: requires a room ID entered by the user
            .setPositiveButton("Join") { _, _ ->
                val server = etServer.text?.toString()?.trim().orEmpty()
                val room = etRoom.text?.toString()?.trim().orEmpty()
                if (server.isNotEmpty() && room.isNotEmpty()) {
                    connectToParty(server, room)
                } else {
                    Toast.makeText(this, "Enter a room ID to join", Toast.LENGTH_SHORT).show()
                }
            }
            // Create: auto-generate a room ID and become host
            .setNeutralButton("Create") { _, _ ->
                val server = etServer.text?.toString()?.trim().orEmpty()
                if (server.isNotEmpty()) {
                    val room = generateRoomId()
                    connectToParty(server, room)
                    Toast.makeText(this, "Room created: $room", Toast.LENGTH_LONG).show()
                }
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
        AlertDialog.Builder(this)
            .setTitle("Watch Party Active")
            .setMessage("You are connected as $roleLabel. Leave the party?")
            .setPositiveButton("Leave") { _, _ -> leaveParty() }
            .setNegativeButton("Stay", null)
            .show()
    }

    private fun connectToParty(serverUrl: String, room: String) {
        manager = WatchPartyManager(
            onRoleAssigned = { role ->
                webView.evaluateJavascript("HWP_setRole('$role')", null)
                tvStatus.text = if (role == "host") "● Host" else "● Guest"
                tvStatus.visibility = View.VISIBLE
            },
            onSyncCommand = { time, paused, videoUrl, platform ->
                lastSyncTime = time
                lastSyncPaused = paused
                // Switch platform if host is on a different service
                if (platform.isNotEmpty() && platform != currentService?.name) {
                    val newService = getStreamingService(platform)
                    currentService = newService
                    webView.settings.userAgentString = newService.userAgent
                }
                // Navigate to host's URL if we're not already there
                if (videoUrl.isNotEmpty() && videoUrl != currentPageUrl) {
                    webView.loadUrl(videoUrl, currentService?.headersOverride ?: mapOf("X-Requested-With" to ""))
                    // onPageFinished will re-inject SYNC_SCRIPT and re-apply lastSyncTime/lastSyncPaused
                } else {
                    webView.evaluateJavascript("HWP_syncTo($time, $paused)", null)
                }
            },
            onStatusChange = { status ->
                tvStatus.text = status
                tvStatus.visibility = View.VISIBLE
            }
        )
        val platform = currentService?.name ?: "android"
        manager?.connect(serverUrl, room, platform, currentPageUrl)
        tvStatus.text = "Connecting…"
        tvStatus.visibility = View.VISIBLE
    }

    private fun leaveParty() {
        manager?.disconnect()
        manager = null
        lastSyncTime = null
        lastSyncPaused = null
        tvStatus.visibility = View.GONE
        webView.evaluateJavascript("if (window.HWP_stop) HWP_stop();", null)
    }

    // ── System UI helpers ─────────────────────────────────────────────────────

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

    // ── Back button ───────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> {
                // Exit fullscreen player first
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

    override fun onDestroy() {
        super.onDestroy()
        manager?.disconnect()
        webView.destroy()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_switch_service -> {
                leaveParty()  // Disconnect from current party
                startActivity(Intent(this, ServiceSelectorActivity::class.java))
                finish()  // Close MainActivity, return to selector
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
