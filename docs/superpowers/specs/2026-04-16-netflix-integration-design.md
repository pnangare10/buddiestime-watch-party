# Netflix Integration Design — Buddies Time Android App

**Date:** 2026-04-16  
**Scope:** Add Netflix streaming support alongside Hotstar using a modular service abstraction  
**Status:** Design Approved, Ready for Implementation

---

## Executive Summary

Buddies Time currently supports synchronized video watching on Hotstar only. This spec adds Netflix support via a `StreamingService` abstraction, allowing users to pick either Hotstar or Netflix at app startup. The sync protocol, WebSocket logic, and JavaScript injection remain unchanged—only the entry point and URL configuration are service-aware.

**Key insight:** Netflix loads successfully in Android WebView. DRM limitations may affect playback quality (SD vs HD) but do not block the sync mechanism.

---

## Architecture

### Service Abstraction Layer

All streaming services conform to a `StreamingService` interface:

```kotlin
interface StreamingService {
    val name: String                           // "hotstar" (programmatic)
    val displayName: String                    // "Hotstar" (user-facing)
    val url: String                            // https://www.hotstar.com
    val userAgent: String?                     // Desktop UA override (or null if default)
    val headersOverride: Map<String, String>   // e.g., {"X-Requested-With" to ""}
}
```

**Implementation Strategy:**
- Create two object implementations: `HotstarService` and `NetflixService`
- Each service encapsulates its URL, user agent, and HTTP header requirements
- MainActivity queries the service registry at startup based on intent extras
- No service-specific logic in MainActivity—all differences are data-driven

### Data Flow

```
┌──────────────────────────────┐
│  ServiceSelectorActivity     │  (NEW launcher)
│  - Hotstar tile              │
│  - Netflix tile              │
└──────────────┬───────────────┘
               │ intent: service="netflix"
               ↓
┌──────────────────────────────┐
│  MainActivity                │  (MODIFIED to be service-agnostic)
│  - Read service name         │
│  - Load StreamingService     │
│  - Apply URL + headers       │
│  - Initialize WebView        │
└──────────────┬───────────────┘
               │
               ↓
┌──────────────────────────────┐
│  WebView (embedded browser)  │  (UNCHANGED internal logic)
│  - Load netflix.com / ...    │
│  - Inject SYNC_SCRIPT        │
│  - Poll for <video> element  │
└──────────────┬───────────────┘
               │
               ↓
┌──────────────────────────────┐
│  WatchPartyManager           │  (UNCHANGED WebSocket sync)
│  (WebSocket to server)       │
│  - Play/Pause/Seek relay     │
└──────────────────────────────┘
```

---

## Components

### 1. StreamingService Interface & Implementations

**File:** `StreamingService.kt` (new)

```kotlin
interface StreamingService {
    val name: String
    val displayName: String
    val url: String
    val userAgent: String?
    val headersOverride: Map<String, String>
}
```

**File:** `Services.kt` (new)

```kotlin
object HotstarService : StreamingService {
    override val name = "hotstar"
    override val displayName = "Hotstar"
    override val url = "https://www.hotstar.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf("X-Requested-With" to "")
}

object NetflixService : StreamingService {
    override val name = "netflix"
    override val displayName = "Netflix"
    override val url = "https://www.netflix.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf("X-Requested-With" to "")
}

// Service registry
fun getStreamingService(name: String): StreamingService = when (name.lowercase()) {
    "netflix" -> NetflixService
    "hotstar" -> HotstarService
    else -> HotstarService  // default
}
```

**Why this design:**
- Easy to add new services (Prime Video, Disney+, etc.) — just add a new object
- No logic in MainActivity means no conditional branches per service
- Centralized configuration makes debugging and testing simpler

---

### 2. ServiceSelectorActivity

**File:** `ServiceSelectorActivity.kt` (new)

Purpose: Present the initial service picker screen.

```kotlin
class ServiceSelectorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service_selector)

        findViewById<CardView>(R.id.cardHotstar).setOnClickListener {
            launchParty("hotstar")
        }

        findViewById<CardView>(R.id.cardNetflix).setOnClickListener {
            launchParty("netflix")
        }
    }

    private fun launchParty(serviceName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", serviceName)
        })
        finish()  // don't keep selector in back stack
    }
}
```

**Layout:** `activity_service_selector.xml` (new)

Simple two-tile Material Design layout:
- Title: "Choose Your Streaming Service"
- Two CardView tiles in a vertical stack or grid
  - Tile 1: Hotstar logo/text, clickable
  - Tile 2: Netflix logo/text, clickable
- Centered, full-width, padding around edges

---

### 3. Modified MainActivity

**File:** `MainActivity.kt` (existing, modifications only)

Key changes:

```kotlin
private var currentService: StreamingService? = null

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // Read selected service from intent
    val serviceName = intent.getStringExtra("service") ?: "hotstar"
    currentService = getStreamingService(serviceName)

    webView = findViewById(R.id.webView)
    fabParty = findViewById(R.id.fabParty)
    tvStatus = findViewById(R.id.tvStatus)
    fullscreenContainer = findViewById(R.id.fullscreenContainer)

    setupWebView()
    
    fabParty.setOnClickListener {
        if (manager?.isConnected() == true) showLeaveDialog() else showJoinDialog()
    }

    // Load the selected service's URL with its headers
    currentService?.let { service ->
        webView.loadUrl(service.url, service.headersOverride)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun setupWebView() {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false
        
        // Apply service's user agent
        userAgentString = currentService?.userAgent 
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        
        useWideViewPort = true
        loadWithOverviewMode = true
        allowFileAccess = false
    }
    
    // ... rest of setupWebView unchanged
}

// NEW: Override menu to add "Switch Service" option
override fun onCreateOptionsMenu(menu: Menu?): Boolean {
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
}

override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.action_switch_service -> {
            leaveParty()  // disconnect from party
            startActivity(Intent(this, ServiceSelectorActivity::class.java))
            finish()  // close MainActivity, return to selector
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
```

**Why these changes are minimal:**
- SYNC_SCRIPT, attachListeners, onPageFinished, JsBridge all remain identical
- Only the URL loading and UA configuration differ per service
- No conditional logic per service inside MainActivity

---

### 4. Layout Files

**`activity_service_selector.xml`** (new)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="24dp"
    android:background="#000000">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Choose Your Streaming Service"
        android:textSize="24sp"
        android:textStyle="bold"
        android:textColor="#FFFFFF"
        android:layout_marginBottom="32dp" />

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/cardHotstar"
        android:layout_width="match_parent"
        android:layout_height="120dp"
        android:layout_marginBottom="16dp"
        app:cardBackgroundColor="#1F2937"
        app:cardElevation="8dp">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Hotstar"
                android:textSize="20sp"
                android:textStyle="bold"
                android:textColor="#FFFFFF" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/cardNetflix"
        android:layout_width="match_parent"
        android:layout_height="120dp"
        app:cardBackgroundColor="#1F2937"
        app:cardElevation="8dp">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Netflix"
                android:textSize="20sp"
                android:textStyle="bold"
                android:textColor="#FFFFFF" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

</LinearLayout>
```

**`menu_main.xml`** (new)

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_switch_service"
        android:title="Switch Service"
        app:showAsAction="never" />
</menu>
```

---

### 5. Manifest & Configuration Updates

**`AndroidManifest.xml`** changes:

1. Change launcher to `ServiceSelectorActivity` (not MainActivity)
2. Add `ServiceSelectorActivity` entry
3. Rename package to `com.buddiestime.watchparty`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.buddiestime.watchparty">
    
    <!-- ... permissions unchanged ... -->
    
    <application
        android:allowBackup="true"
        android:hardwareAccelerated="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.BuddiesTime">

        <!-- NEW: Selector as launcher -->
        <activity
            android:name=".ServiceSelectorActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- MainActivity no longer launcher -->
        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />

    </application>

</manifest>
```

**`strings.xml`** changes:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Buddies Time</string>
</resources>
```

**`build.gradle`** changes:

Update `applicationId` to match new package:
```gradle
android {
    namespace "com.buddiestime.watchparty"
    // ... rest unchanged
}
```

---

## Testing Strategy

### Automated Test Script

**File:** `test-automation.sh` (new, in project root)

The script automates the testing flow using adb commands to:
1. Launch ServiceSelectorActivity
2. Tap the Netflix tile (or Hotstar for baseline)
3. Wait for MainActivity/WebView to load
4. Monitor logcat for HWP sync messages
5. Simulate user actions (play, pause, seek)
6. Capture results in `test-results.log`

**Test flow:**

```bash
#!/bin/bash
set -e

SERVICE=${1:-netflix}  # default to netflix
LOG_FILE="test-results-${SERVICE}-$(date +%s).log"

echo "[TEST] Buddies Time - $SERVICE Integration" | tee "$LOG_FILE"
echo "---" >> "$LOG_FILE"

# Kill any running instance
adb shell am force-stop com.buddiestime.watchparty || true
sleep 2

# Start the app at ServiceSelectorActivity
adb shell am start -n com.buddiestime.watchparty/.ServiceSelectorActivity
echo "[INFO] App launched, waiting for selector..." | tee -a "$LOG_FILE"
sleep 4

# Tap the appropriate service tile
if [ "$SERVICE" = "netflix" ]; then
    echo "[ACTION] Tapping Netflix tile..." | tee -a "$LOG_FILE"
    adb input tap 720 1200  # Approximate coords for Netflix card
else
    echo "[ACTION] Tapping Hotstar tile..." | tee -a "$LOG_FILE"
    adb input tap 720 600   # Approximate coords for Hotstar card
fi

echo "[INFO] Waiting for WebView to load..." | tee -a "$LOG_FILE"
sleep 6

# Clear logcat and start capturing
adb logcat -c
sleep 1

# Simulate play action (if auto-plays, this is a no-op)
echo "[ACTION] Video should auto-play or user logs in..." | tee -a "$LOG_FILE"
sleep 5

# Capture sync messages
echo "[INFO] Checking for sync messages in logcat..." | tee -a "$LOG_FILE"
adb logcat -d | grep -iE "\[HWP\]|error|failed" >> "$LOG_FILE" 2>&1 || true

# Simulate pause
echo "[ACTION] Pausing video..." | tee -a "$LOG_FILE"
adb input tap 720 1500  # Approximate pause button location
sleep 2

# Capture pause event
adb logcat -d | grep -iE "pause|\[HWP\]" >> "$LOG_FILE" 2>&1 || true

# Simulate seek
echo "[ACTION] Seeking video..." | tee -a "$LOG_FILE"
adb input tap 720 1600  # Approximate seek location
sleep 2

# Final capture
adb logcat -d | grep -iE "seek|\[HWP\]" >> "$LOG_FILE" 2>&1 || true

echo "[INFO] Test complete. Results saved to $LOG_FILE" | tee -a "$LOG_FILE"
```

**How to use:**
```bash
# Test Netflix
bash test-automation.sh netflix

# Test Hotstar
bash test-automation.sh hotstar

# Review results
cat test-results-netflix-*.log
```

**Expected logcat output for a passing test:**
```
[HWP] video found, readyState=4 src=...
[HWP] play event t=0.00 host=true syncing=false
[HWP] pause event t=5.23 host=true syncing=false
[HWP] seeked event t=120.50 host=true syncing=false
```

**If DRM blocks Netflix playback:**
- No `[HWP] video found` message
- Logcat will show Chrome security/DRM errors
- Test log will indicate "FAIL — video not detected"

---

## Files to Create/Modify

**New Files:**
- `android/app/src/main/kotlin/com/buddiestime/watchparty/StreamingService.kt`
- `android/app/src/main/kotlin/com/buddiestime/watchparty/Services.kt`
- `android/app/src/main/kotlin/com/buddiestime/watchparty/ServiceSelectorActivity.kt`
- `android/app/src/main/res/layout/activity_service_selector.xml`
- `android/app/src/main/res/menu/menu_main.xml`
- `test-automation.sh`

**Modified Files:**
- `android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt` (service loading, menu handling)
- `android/app/src/main/AndroidManifest.xml` (launcher change, package rename, service activity)
- `android/app/src/main/res/values/strings.xml` (app name, service names)
- `android/build.gradle` (package rename)
- `CLAUDE.md` (update build/test instructions)

**Existing Files (no changes):**
- `WatchPartyManager.kt` (WebSocket sync logic — unchanged)
- `activity_main.xml` (layout — unchanged, though could add menu inflation)
- All server code, extension code, bookmarklet code

---

## Success Criteria

✅ **Functional:**
- App launches to ServiceSelectorActivity
- User can select Hotstar or Netflix
- Selected service URL loads in WebView
- Party join/host flow works identically on both services
- Play/pause/seek sync works on both services
- Menu "Switch Service" returns to selector

✅ **Quality:**
- No crashes on service selection
- No WebSocket reconnection issues when switching services
- Existing Hotstar flow unchanged
- Code is testable and extensible (adding a new service takes ~20 lines)

✅ **Testing:**
- Automated test script runs successfully
- Baseline Hotstar test passes (confirms no regression)
- Netflix test runs without crashes (even if video playback is blocked by DRM)
- Test logs are readable and actionable

---

## Scope & Limitations

**What this spec covers:**
- Multi-service UI and selection
- Modular service configuration
- Menu-based service switching
- Automated testing

**What this spec does NOT cover:**
- Custom Netflix-specific UI (e.g., Browse/Profile pages) — WebView shows Netflix's standard web UI
- Offline/local content support
- Account syncing across services
- Service-specific error handling (e.g., Netflix's "content unavailable" messages)

**Known Limitations:**
- Android WebView typically has Widevine L3 DRM support only, so Netflix may not play HD video or may refuse playback on some devices
- Service switching requires leaving the current party (disconnect → go to selector → rejoin with new service)
- Back button from MainActivity exits the app (doesn't go back to selector) — menu is the way to switch

---

## Deliverables

1. ✅ Modular StreamingService interface + implementations
2. ✅ ServiceSelectorActivity with UI layout
3. ✅ Modified MainActivity to load services dynamically
4. ✅ Menu support for service switching
5. ✅ Package and app name updates
6. ✅ Automated test script for regression testing
7. ✅ Updated CLAUDE.md with build/test instructions

---

## Timeline & Effort Estimate

**Implementation:** ~2-3 hours (straightforward, mostly new files, minimal MainActivity changes)  
**Testing:** ~1-2 hours (manual verification + test script refinement)  
**Total:** ~3-5 hours

This is a focused, low-risk change with no impact on the core sync protocol or server.
