# Netflix Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Netflix alongside Hotstar using a modular service abstraction, allowing users to pick a streaming service at app startup.

**Architecture:** `StreamingService` interface encapsulates per-service config (URL, user agent, headers). `ServiceSelectorActivity` is the new launcher; `MainActivity` reads the selected service and applies it dynamically. Sync protocol unchanged.

**Tech Stack:** Kotlin, Android WebView, Material Design, adb/bash for testing

---

## Task 1: Create StreamingService Interface

**Files:**
- Create: `android/app/src/main/kotlin/com/buddiestime/watchparty/StreamingService.kt`

- [ ] **Step 1: Create StreamingService.kt with interface definition**

```kotlin
package com.buddiestime.watchparty

interface StreamingService {
    val name: String
    val displayName: String
    val url: String
    val userAgent: String?
    val headersOverride: Map<String, String>
}
```

- [ ] **Step 2: Commit**

```bash
cd "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party"
git add android/app/src/main/kotlin/com/buddiestime/watchparty/StreamingService.kt
git commit -m "feat: add StreamingService interface for multi-service abstraction"
```

---

## Task 2: Create Hotstar and Netflix Service Implementations

**Files:**
- Create: `android/app/src/main/kotlin/com/buddiestime/watchparty/Services.kt`

- [ ] **Step 1: Create Services.kt with HotstarService object**

```kotlin
package com.buddiestime.watchparty

object HotstarService : StreamingService {
    override val name = "hotstar"
    override val displayName = "Hotstar"
    override val url = "https://www.hotstar.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf("X-Requested-With" to "")
}
```

- [ ] **Step 2: Add NetflixService object to the same file**

```kotlin
object NetflixService : StreamingService {
    override val name = "netflix"
    override val displayName = "Netflix"
    override val url = "https://www.netflix.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf("X-Requested-With" to "")
}
```

- [ ] **Step 3: Add service registry function at the end of Services.kt**

```kotlin
fun getStreamingService(name: String): StreamingService = when (name.lowercase()) {
    "netflix" -> NetflixService
    "hotstar" -> HotstarService
    else -> HotstarService  // default fallback
}
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/Services.kt
git commit -m "feat: add Hotstar and Netflix service implementations with registry"
```

---

## Task 3: Create ServiceSelectorActivity

**Files:**
- Create: `android/app/src/main/kotlin/com/buddiestime/watchparty/ServiceSelectorActivity.kt`

- [ ] **Step 1: Create ServiceSelectorActivity.kt**

```kotlin
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
    }

    private fun launchParty(serviceName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", serviceName)
        })
        finish()  // Don't keep selector in back stack
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/ServiceSelectorActivity.kt
git commit -m "feat: add ServiceSelectorActivity for choosing streaming service"
```

---

## Task 4: Create Service Selector Layout

**Files:**
- Create: `android/app/src/main/res/layout/activity_service_selector.xml`

- [ ] **Step 1: Create activity_service_selector.xml layout file**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
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

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/res/layout/activity_service_selector.xml
git commit -m "feat: add service selector layout with Hotstar and Netflix tiles"
```

---

## Task 5: Create Menu Layout for Switch Service

**Files:**
- Create: `android/app/src/main/res/menu/menu_main.xml`

- [ ] **Step 1: Create menu_main.xml**

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

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/res/menu/menu_main.xml
git commit -m "feat: add menu with Switch Service option"
```

---

## Task 6: Update strings.xml with App Name and Service Names

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`

- [ ] **Step 1: Read current strings.xml**

Current content:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Hotstar Watch Party</string>
</resources>
```

- [ ] **Step 2: Replace app_name with Buddies Time**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Buddies Time</string>
</resources>
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/res/values/strings.xml
git commit -m "chore: rename app name to 'Buddies Time'"
```

---

## Task 7: Update AndroidManifest.xml — Rename Package and Set Launcher

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Change package from com.hotstar.watchparty to com.buddiestime.watchparty**

At the top of the manifest, change:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.buddiestime.watchparty">
```

- [ ] **Step 2: Update theme name from Theme.HotstarWatchParty to Theme.BuddiesTime**

In `<application>` tag:
```xml
android:theme="@style/Theme.BuddiesTime"
```

- [ ] **Step 3: Add ServiceSelectorActivity as the launcher activity**

Add this **before** the existing MainActivity entry:
```xml
<activity
    android:name=".ServiceSelectorActivity"
    android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

- [ ] **Step 4: Update MainActivity to NOT be exported, NOT be launcher**

Update the existing MainActivity entry:
```xml
<activity
    android:name=".MainActivity"
    android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"
    android:exported="false"
    android:windowSoftInputMode="adjustResize" />
```

Remove the `<intent-filter>` from MainActivity (it should not have MAIN/LAUNCHER anymore).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml
git commit -m "chore: rename package to com.buddiestime.watchparty, set ServiceSelectorActivity as launcher"
```

---

## Task 8: Modify MainActivity — Read Service and Apply Dynamically

**Files:**
- Modify: `android/app/src/main/kotlin/com/hotstar/watchparty/MainActivity.kt`

Note: This file is still in `com.hotstar.watchparty` package. We'll rename it in Task 9.

- [ ] **Step 1: Add currentService property at the top of MainActivity class**

After the line `private var manager: WatchPartyManager? = null`, add:

```kotlin
private var currentService: StreamingService? = null
```

- [ ] **Step 2: Update onCreate to read service from intent and initialize currentService**

In the `onCreate` method, add these lines **before** `setContentView(R.layout.activity_main)`:

```kotlin
// Read selected service from intent
val serviceName = intent.getStringExtra("service") ?: "hotstar"
currentService = getStreamingService(serviceName)
```

- [ ] **Step 3: Update the webView.loadUrl call to use service's URL and headers**

Replace this line (around line 191):
```kotlin
webView.loadUrl(startUrl, mapOf("X-Requested-With" to ""))
```

With:
```kotlin
// Load the selected service's URL with its headers
currentService?.let { service ->
    webView.loadUrl(service.url, service.headersOverride)
} ?: run {
    // Fallback if no service (shouldn't happen, but safe)
    webView.loadUrl("https://www.hotstar.com", mapOf("X-Requested-With" to ""))
}
```

- [ ] **Step 4: Update setupWebView to use service's user agent**

In `setupWebView()`, find the userAgentString line (around line 212) and replace:
```kotlin
userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"
```

With:
```kotlin
userAgentString = currentService?.userAgent 
    ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
```

- [ ] **Step 5: Add onCreateOptionsMenu method to inflate menu_main.xml**

Add this method to MainActivity class (at the end, before closing brace):

```kotlin
override fun onCreateOptionsMenu(menu: Menu?): Boolean {
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
}
```

- [ ] **Step 6: Add onOptionsItemSelected to handle Switch Service menu click**

Add this method to MainActivity class (after onCreateOptionsMenu):

```kotlin
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
```

- [ ] **Step 7: Add necessary imports at the top of MainActivity.kt**

Add these imports if not already present:
```kotlin
import android.view.Menu
import android.view.MenuItem
```

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/com/hotstar/watchparty/MainActivity.kt
git commit -m "feat: modify MainActivity to load streaming service dynamically with menu switch"
```

---

## Task 9: Rename Package from com.hotstar.watchparty to com.buddiestime.watchparty

**Files:**
- Move: `android/app/src/main/kotlin/com/hotstar/watchparty/*` → `android/app/src/main/kotlin/com/buddiestime/watchparty/*`
- Modify: Package declarations in all .kt files
- Modify: `build.gradle`

- [ ] **Step 1: Create new package directory structure**

```bash
mkdir -p "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/android/app/src/main/kotlin/com/buddiestime/watchparty"
```

- [ ] **Step 2: Move all .kt files from old package to new package**

```bash
cp "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/android/app/src/main/kotlin/com/hotstar/watchparty"/*.kt \
   "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/android/app/src/main/kotlin/com/buddiestime/watchparty/"
```

- [ ] **Step 3: Update package declaration in MainActivity.kt**

At the top of the file (line 1), change:
```kotlin
package com.hotstar.watchparty
```

To:
```kotlin
package com.buddiestime.watchparty
```

- [ ] **Step 4: Update package declaration in WatchPartyManager.kt**

At the top of the file (line 1), change:
```kotlin
package com.hotstar.watchparty
```

To:
```kotlin
package com.buddiestime.watchparty
```

- [ ] **Step 5: Update package declarations in all newly created files (if they still reference old package)**

For StreamingService.kt, Services.kt, ServiceSelectorActivity.kt — verify they all have:
```kotlin
package com.buddiestime.watchparty
```

At the top.

- [ ] **Step 6: Update build.gradle applicationId**

In `android/build.gradle`, find the `android { ... }` block and locate or add:

```gradle
android {
    namespace "com.buddiestime.watchparty"
    compileSdk 34

    defaultConfig {
        applicationId "com.buddiestime.watchparty"
        minSdk 29
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    
    // ... rest of gradle config unchanged
}
```

Make sure both `namespace` and `applicationId` are `com.buddiestime.watchparty`.

- [ ] **Step 7: Remove old package directory**

```bash
rm -rf "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/android/app/src/main/kotlin/com/hotstar"
```

- [ ] **Step 8: Rebuild to verify no compilation errors**

```bash
cd "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/android"
./gradlew clean assembleDebug 2>&1 | tail -20
```

Expected: Build succeeds with "BUILD SUCCESSFUL"

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "chore: rename package from com.hotstar.watchparty to com.buddiestime.watchparty"
```

---

## Task 10: Create Automated Test Script

**Files:**
- Create: `test-automation.sh`

- [ ] **Step 1: Create test-automation.sh**

```bash
#!/bin/bash
set -e

SERVICE=${1:-netflix}  # Default to netflix
LOG_FILE="test-results-${SERVICE}-$(date +%s).log"
ADB="/c/Users/prane_ii3rizl/AppData/Local/Android/Sdk/platform-tools/adb.exe"

echo "[TEST] Buddies Time - $SERVICE Integration Test" | tee "$LOG_FILE"
echo "========================================" >> "$LOG_FILE"
echo "Test Date: $(date)" >> "$LOG_FILE"
echo "Service: $SERVICE" >> "$LOG_FILE"
echo "========================================" >> "$LOG_FILE"

# Kill any running instance
echo "[INFO] Stopping any running instance..." | tee -a "$LOG_FILE"
"$ADB" shell am force-stop com.buddiestime.watchparty || true
sleep 2

# Launch ServiceSelectorActivity
echo "[INFO] Launching ServiceSelectorActivity..." | tee -a "$LOG_FILE"
"$ADB" shell am start -n com.buddiestime.watchparty/.ServiceSelectorActivity
sleep 4

# Determine which tile to tap based on service
if [ "$SERVICE" = "netflix" ]; then
    echo "[ACTION] Tapping Netflix tile..." | tee -a "$LOG_FILE"
    # Approximate coordinates for Netflix card on Pixel 7 Pro
    "$ADB" input tap 720 1200
else
    echo "[ACTION] Tapping Hotstar tile..." | tee -a "$LOG_FILE"
    # Approximate coordinates for Hotstar card on Pixel 7 Pro
    "$ADB" input tap 720 600
fi

echo "[INFO] Waiting for MainActivity to load..." | tee -a "$LOG_FILE"
sleep 6

# Clear and start capturing logcat
"$ADB" logcat -c
sleep 1

echo "[INFO] Checking for video element detection..." | tee -a "$LOG_FILE"
sleep 5

# Capture HWP sync messages
echo "" >> "$LOG_FILE"
echo "[LOGCAT] Sync Messages:" >> "$LOG_FILE"
"$ADB" logcat -d 2>&1 | grep -E "\[HWP\]|error|failed|not supported" >> "$LOG_FILE" 2>&1 || true

echo "[INFO] Test sequence complete." | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "Results saved to: $LOG_FILE" | tee -a "$LOG_FILE"
```

- [ ] **Step 2: Make test script executable**

```bash
chmod +x "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/test-automation.sh"
```

- [ ] **Step 3: Commit**

```bash
cd "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party"
git add test-automation.sh
git commit -m "test: add automated test script for service integration validation"
```

---

## Task 11: Build and Test APK

**Files:**
- None (compilation/testing phase)

- [ ] **Step 1: Clean and build debug APK**

```bash
cd "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/android"
./gradlew clean assembleDebug 2>&1 | tail -30
```

Expected output:
```
BUILD SUCCESSFUL in Xs
```

- [ ] **Step 2: Verify APK was created**

```bash
ls -lh "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/android/app/build/outputs/apk/debug/app-debug.apk"
```

Expected: File exists with size > 5MB

- [ ] **Step 3: Uninstall old app and install new APK**

```bash
ADB="/c/Users/prane_ii3rizl/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" uninstall com.hotstar.watchparty || true
"$ADB" install -r "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party/android/app/build/outputs/apk/debug/app-debug.apk"
```

Expected: "Success"

- [ ] **Step 4: Launch app manually to verify ServiceSelectorActivity appears**

```bash
ADB="/c/Users/prane_ii3rizl/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" shell am start -n com.buddiestime.watchparty/.ServiceSelectorActivity
sleep 5
"$ADB" exec-out screencap -p > "test-selector-screenshot.png"
echo "Screenshot saved to test-selector-screenshot.png"
```

Expected: Screenshot shows two tiles (Hotstar and Netflix)

- [ ] **Step 5: Run automated test for Netflix**

```bash
cd "C:/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party"
bash test-automation.sh netflix
```

Expected: `test-results-netflix-*.log` created with sync messages

- [ ] **Step 6: Run automated test for Hotstar (baseline)**

```bash
bash test-automation.sh hotstar
```

Expected: `test-results-hotstar-*.log` created with sync messages

- [ ] **Step 7: Review test logs for HWP sync messages**

```bash
cat test-results-netflix-*.log | grep "\[HWP\]"
cat test-results-hotstar-*.log | grep "\[HWP\]"
```

Expected output (if video found):
```
[HWP] video found, readyState=4 src=...
```

If Netflix DRM blocks: No `[HWP]` messages, look for DRM errors instead.

- [ ] **Step 8: Final commit with test results**

```bash
git add -A
git commit -m "test: add test results and screenshots for Netflix/Hotstar integration"
```

---

## Self-Review Against Spec

**Spec Coverage:**

1. ✅ **Architecture** — Task 1-2: StreamingService interface + implementations
2. ✅ **ServiceSelectorActivity** — Task 3-4: Activity + layout
3. ✅ **MainActivity modifications** — Task 8: Service loading, menu handling
4. ✅ **Manifest & branding** — Task 7, 6, 9: Package rename, app name, launcher
5. ✅ **Test automation** — Task 10-11: Test script and execution
6. ✅ **Menu for service switching** — Task 5, 8: menu_main.xml and handler

**Placeholder Scan:**
- ✅ All code blocks are complete, no "TBD" or "TODO"
- ✅ All commands include expected output
- ✅ All file paths are exact
- ✅ No "similar to Task X" references

**Type Consistency:**
- ✅ `StreamingService` interface used consistently across Tasks 1-2
- ✅ `getStreamingService()` function called in Task 8 matches definition in Task 2
- ✅ Menu ID `R.id.action_switch_service` matches in Tasks 5 and 8
- ✅ Service names ("hotstar", "netflix") consistent throughout

**Completeness:**
- ✅ All 4 modified files covered (MainActivity, Manifest, strings.xml, build.gradle)
- ✅ All 6 new files covered (StreamingService.kt, Services.kt, ServiceSelectorActivity.kt, 2 XML layouts, test script)
- ✅ Build and test validation included (Task 11)
- ✅ No spec requirement left out

---

## Plan saved to: `docs/superpowers/plans/2026-04-16-netflix-integration-plan.md`

Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh Haiku subagent per task, review results, fast iteration with parallel work

**2. Inline Execution** — Execute tasks here in this session using superpowers:executing-plans, batched with checkpoints

Which approach would you prefer?
