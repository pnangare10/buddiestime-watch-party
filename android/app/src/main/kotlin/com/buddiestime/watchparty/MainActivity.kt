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
    // catches up even if the sync-response arrived before the page was ready.
    private var lastSyncTime: Double? = null
    private var lastSyncPaused: Boolean? = null

    // ── Injected JS that runs inside the Hotstar page ──────────────────────────
    // Polls for the <video> element, attaches listeners (host only sends events),
    // and exposes HWP_* functions that the native side can call via evaluateJavascript.
    private val SYNC_SCRIPT = """
        (function() {
            if (window.__hwpNative) return;
            window.__hwpNative = true;

            var DRIFT = 3;
            var video = null;           // active video we sync to/from
            var trackedVideos = [];     // all video elements we have listeners on
            var isSyncing = false;
            var isHost = false;
            var pollTimer = null;
            var pendingSync = null;

            /** Called by native after the room role is confirmed. */
            window.HWP_setRole = function(r) {
                isHost = (r === 'host');
            };

            /** Guest: apply a play or pause command from the host. */
            window.HWP_syncTo = function(time, paused) {
                if (!video) {
                    console.log('[HWP] syncTo buffered: t=' + time + ' paused=' + paused);
                    pendingSync = { time: time, paused: paused };
                    return;
                }
                console.log('[HWP] syncTo: t=' + time + ' paused=' + paused + ' cur=' + video.currentTime.toFixed(1));
                isSyncing = true;
                if (Math.abs(video.currentTime - time) > DRIFT) video.currentTime = time;
                if (paused && !video.paused) video.pause();
                else if (!paused && video.paused) video.play().catch(function() {});
                setTimeout(function() { isSyncing = false; }, 300);
            };

            /** Guest: apply a seek command — preserves current play/pause state. */
            window.HWP_seekTo = function(time) {
                if (!video) {
                    console.log('[HWP] seekTo buffered: t=' + time);
                    pendingSync = { time: time, paused: pendingSync ? pendingSync.paused : null };
                    return;
                }
                console.log('[HWP] seekTo: t=' + time + ' cur=' + video.currentTime.toFixed(1));
                isSyncing = true;
                if (Math.abs(video.currentTime - time) > DRIFT) video.currentTime = time;
                setTimeout(function() { isSyncing = false; }, 300);
            };

            /** Host: reports current state when a guest joins. */
            window.HWP_reportState = function() {
                var t = video ? video.currentTime : 0;
                var p = video ? video.paused : true;
                HwpBridge.onVideoState(t, p);
            };

            /** Native calls this on disconnect to stop the poll loop. */
            window.HWP_stop = function() {
                if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
                trackedVideos = [];
                video = null;
                window.__hwpNative = false;
            };

            function attachListeners(v) {
                v.addEventListener('play', function() {
                    console.log('[HWP] play t=' + v.currentTime.toFixed(2) + ' host=' + isHost + ' syncing=' + isSyncing + ' dur=' + v.duration.toFixed(1));
                    if (!isSyncing && isHost) HwpBridge.onVideoPlay(v.currentTime);
                });
                v.addEventListener('pause', function() {
                    console.log('[HWP] pause t=' + v.currentTime.toFixed(2) + ' host=' + isHost + ' syncing=' + isSyncing + ' dur=' + v.duration.toFixed(1));
                    if (!isSyncing && isHost) HwpBridge.onVideoPause(v.currentTime);
                });
                v.addEventListener('seeked', function() {
                    console.log('[HWP] seeked t=' + v.currentTime.toFixed(2) + ' host=' + isHost + ' syncing=' + isSyncing + ' dur=' + v.duration.toFixed(1));
                    if (!isSyncing && isHost) HwpBridge.onVideoSeek(v.currentTime);
                });
            }

            // Prefer the playing video; among paused ones prefer the longest duration
            // (main content vs. ad/trailer). Skip elements removed from the DOM.
            function getBestVideo() {
                var best = null;
                for (var i = 0; i < trackedVideos.length; i++) {
                    var v = trackedVideos[i];
                    if (!document.contains(v)) continue;
                    if (!best) { best = v; continue; }
                    if (!v.paused && best.paused) { best = v; continue; }
                    if (v.duration > (best.duration || 0)) { best = v; }
                }
                return best;
            }

            // Poll for all video elements — services like Prime Video have multiple.
            // Attach listeners to any new ones and keep the active reference current.
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
                        console.log('[HWP] video #' + trackedVideos.length + ' attached, dur=' + v.duration.toFixed(1));
                        attachListeners(v);
                    }
                }
                var best = getBestVideo();
                if (best) {
                    if (best !== video) {
                        video = best;
                        console.log('[HWP] active video: dur=' + best.duration.toFixed(1) + ' paused=' + best.paused);
                    }
                    if (pendingSync) {
                        var ps = pendingSync;
                        pendingSync = null;
                        console.log('[HWP] applying pendingSync: t=' + ps.time + ' paused=' + ps.paused);
                        if (ps.paused !== null) HWP_syncTo(ps.time, ps.paused);
                        else HWP_seekTo(ps.time);
                    }
                }
                pollTimer = setTimeout(poll, 1000);
            }

            poll();
        })();
    """.trimIndent()

    // ── JS Bridge (called from inside the WebView page) ───────────────────────

    inner class JsBridge {
        @JavascriptInterface
        fun onVideoPlay(time: Double) {
            manager?.sendVideoEvent("play", time)
        }

        @JavascriptInterface
        fun onVideoPause(time: Double) {
            manager?.sendVideoEvent("pause", time)
        }

        @JavascriptInterface
        fun onVideoSeek(time: Double) {
            manager?.sendVideoEvent("seek", time)
        }

        /**
         * Host reports its current video state back to native so we can forward
         * a sync-response to the server (which delivers it to the new guest).
         */
        @JavascriptInterface
        fun onVideoState(time: Double, paused: Boolean) {
            manager?.sendSyncResponse(time, paused)
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
            // Enable desktop-style viewport so the player renders at full width
            useWideViewPort = true
            loadWithOverviewMode = true
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

            override fun onPageFinished(view: WebView, url: String) {
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
            .setTitle("Join Watch Party")
            .setView(dialogView)
            .setPositiveButton("Join") { _, _ ->
                val server = etServer.text?.toString()?.trim().orEmpty()
                val room = etRoom.text?.toString()?.trim().orEmpty()
                if (server.isNotEmpty() && room.isNotEmpty()) {
                    connectToParty(server, room)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Allow pressing "Done" on keyboard to submit the dialog
        etRoom.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
            true
        }

        dialog.show()
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
            onSyncCommand = { time, paused ->
                lastSyncTime = time
                lastSyncPaused = paused
                webView.evaluateJavascript("HWP_syncTo($time, $paused)", null)
            },
            onSeekCommand = { time ->
                webView.evaluateJavascript("HWP_seekTo($time)", null)
            },
            onSyncRequested = {
                // A guest joined — ask JS for the current video state, which calls back
                // JsBridge.onVideoState() → WatchPartyManager.sendSyncResponse()
                webView.evaluateJavascript("HWP_reportState()", null)
            },
            onStatusChange = { status ->
                tvStatus.text = status
                tvStatus.visibility = View.VISIBLE
            }
        )
        manager?.connect(serverUrl, room)
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
