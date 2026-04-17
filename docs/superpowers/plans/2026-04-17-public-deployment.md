# Public Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy the watch party server to Railway, publish a signed APK via GitHub Releases, and add a landing page — all automated so `git tag v1.x.x && git push --tags` produces a new release.

**Architecture:** A root-level `package.json` lets Railway (nixpacks) auto-detect and install deps, then starts `server/server.js`. GitHub Actions triggers on version tags, builds a signed APK using keystore secrets, and uploads it to a GitHub Release. The landing page is a static HTML file served by the existing Node server.

**Tech Stack:** Node.js 18+, Railway (nixpacks), GitHub Actions, Android Gradle Plugin 8.2.2, `softprops/action-gh-release@v2`, `keytool` (JDK bundled)

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `package.json` | **Create** | Root-level package.json so Railway nixpacks finds it |
| `railway.json` | **Create** | Railway start command config |
| `.gitignore` | **Modify** | Exclude keystore + Android build artifacts |
| `server/index.html` | **Create** | Landing page with APK download link |
| `server/server.js` | **Modify** | Add `/` route serving `index.html` |
| `android/app/build.gradle` | **Modify** | Add `buildConfig`, `SERVER_URL` field, signing config |
| `android/app/src/main/res/layout/dialog_join_party.xml` | **Modify** | Update default server URL to Railway URL |
| `.github/workflows/release.yml` | **Create** | CI: build + sign + release APK on tag push |

> **Note on services:** Hotstar, Prime Video, YouTube, and Netflix are already implemented in `android/app/src/main/kotlin/com/buddiestime/watchparty/Services.kt`. No code changes are needed — the APK already ships all four services.

---

## Task 1: GitHub Repository Setup

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Create the GitHub repo**

Go to https://github.com/new and create a **public** repo named `buddiestime-watch-party`. Do **not** initialize with README or .gitignore (we already have code).

- [ ] **Step 2: Update .gitignore**

Replace the contents of `.gitignore` with:

```
node_modules/
*.log
.DS_Store

# Android build outputs
android/.gradle/
android/app/build/
android/build/
android/local.properties

# Release keystore — never commit this
release.keystore
android/app/release.keystore
```

- [ ] **Step 3: Push existing code to GitHub**

```bash
cd "/c/Users/prane_ii3rizl/Downloads/Pranesh Projects/hotstar-watch-party"
git remote add origin https://github.com/praneshnangare/buddiestime-watch-party.git
git branch -M main
git add .gitignore
git commit -m "chore: update gitignore for public repo"
git push -u origin main
```

Expected: GitHub shows the repo with all project files.

---

## Task 2: Railway Configuration Files

**Files:**
- Create: `package.json` (root)
- Create: `railway.json`

- [ ] **Step 1: Create root package.json**

Create `package.json` at the repo root (not inside `server/`):

```json
{
  "name": "buddiestime-watch-party",
  "version": "1.0.0",
  "description": "Watch party sync server",
  "scripts": {
    "start": "node server/server.js"
  },
  "dependencies": {
    "ws": "^8.18.0"
  },
  "engines": {
    "node": ">=18"
  }
}
```

- [ ] **Step 2: Create railway.json**

Create `railway.json` at the repo root:

```json
{
  "$schema": "https://railway.app/railway.schema.json",
  "build": {
    "builder": "nixpacks"
  },
  "deploy": {
    "startCommand": "node server/server.js",
    "restartPolicyType": "ON_FAILURE",
    "restartPolicyMaxRetries": 3
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add package.json railway.json
git commit -m "chore: add Railway deployment config"
git push
```

---

## Task 3: Deploy to Railway (Manual Steps)

No files to edit — this is a dashboard task. Follow each step exactly.

- [ ] **Step 1: Create Railway account**

Go to https://railway.app and sign in with GitHub.

- [ ] **Step 2: Create a new project**

Click **New Project** → **Deploy from GitHub repo** → select `praneshnangare/buddiestime-watch-party`.

- [ ] **Step 3: Wait for deploy**

Railway auto-detects Node.js (via root `package.json`), runs `npm install`, then `node server/server.js`. Wait for the deploy to show **Active** (green).

- [ ] **Step 4: Get your Railway URL**

In the Railway dashboard, go to your service → **Settings** → **Networking** → click **Generate Domain**. Copy the URL — it looks like:

```
https://buddiestime-watch-party-production.up.railway.app
```

- [ ] **Step 5: Verify the server is live**

Open the URL in a browser. You should see:

```
Hotstar Watch Party server running
```

Also test WebSocket connectivity:
```bash
# In a browser console at any page:
const ws = new WebSocket('wss://YOUR-RAILWAY-URL.up.railway.app');
ws.onopen = () => console.log('connected!');
```

**Save your Railway URL — you need it in Tasks 4 and 5.**

---

## Task 4: Landing Page

**Files:**
- Create: `server/index.html`
- Modify: `server/server.js` (lines 11-29)

> Replace `YOUR_RAILWAY_URL` below with your actual Railway URL (e.g. `buddiestime-watch-party-production.up.railway.app`).
> Replace `praneshnangare` with your GitHub username if different.

- [ ] **Step 1: Create server/index.html**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>BuddiesTime Watch Party</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      background: #0f0f1a;
      color: #f0f0f0;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 48px 24px;
    }
    .hero { text-align: center; max-width: 560px; }
    h1 { font-size: 2rem; font-weight: 700; color: #fff; margin-bottom: 12px; }
    .tagline { font-size: 1.1rem; color: #a0a0c0; margin-bottom: 36px; }
    .download-btn {
      display: inline-block;
      background: #6c63ff;
      color: #fff;
      font-size: 1.1rem;
      font-weight: 600;
      padding: 16px 40px;
      border-radius: 12px;
      text-decoration: none;
      margin-bottom: 48px;
      transition: background 0.2s;
    }
    .download-btn:hover { background: #574fd6; }
    .services { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; margin-bottom: 48px; }
    .service-badge {
      background: #1e1e32;
      border: 1px solid #333355;
      border-radius: 8px;
      padding: 8px 16px;
      font-size: 0.85rem;
      color: #c0c0e0;
    }
    .steps { max-width: 480px; width: 100%; margin-bottom: 48px; }
    .steps h2 { font-size: 1.2rem; margin-bottom: 20px; color: #fff; }
    .step { display: flex; gap: 16px; margin-bottom: 20px; align-items: flex-start; }
    .step-num {
      background: #6c63ff;
      color: #fff;
      width: 32px; height: 32px;
      border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      font-weight: 700; flex-shrink: 0; font-size: 0.9rem;
    }
    .step-text { font-size: 0.95rem; color: #c0c0e0; padding-top: 6px; }
    .step-text strong { color: #f0f0f0; }
    .how-it-works { max-width: 480px; width: 100%; margin-bottom: 48px; }
    .how-it-works h2 { font-size: 1.2rem; margin-bottom: 12px; color: #fff; }
    .how-it-works p { font-size: 0.95rem; color: #a0a0c0; line-height: 1.6; }
    footer { font-size: 0.85rem; color: #606080; }
    footer a { color: #8080b0; text-decoration: none; }
    footer a:hover { color: #a0a0d0; }
  </style>
</head>
<body>
  <div class="hero">
    <h1>BuddiesTime Watch Party</h1>
    <p class="tagline">Watch Hotstar, Prime Video, YouTube &amp; Netflix in sync with friends</p>

    <a
      class="download-btn"
      href="https://github.com/praneshnangare/buddiestime-watch-party/releases/latest/download/buddiestime-watchparty-latest.apk"
      download
    >
      ⬇ Download APK (Android)
    </a>

    <div class="services">
      <span class="service-badge">Hotstar</span>
      <span class="service-badge">Prime Video</span>
      <span class="service-badge">YouTube</span>
      <span class="service-badge">Netflix</span>
    </div>
  </div>

  <div class="steps">
    <h2>How to get started</h2>
    <div class="step">
      <div class="step-num">1</div>
      <div class="step-text">
        Download and install the APK. On Android, go to <strong>Settings → Install unknown apps</strong> and allow your browser or file manager.
      </div>
    </div>
    <div class="step">
      <div class="step-num">2</div>
      <div class="step-text">
        Open the app, pick a streaming service, then tap the <strong>party icon</strong> and enter a room name.
      </div>
    </div>
    <div class="step">
      <div class="step-num">3</div>
      <div class="step-text">
        Share the room name with friends. They open the app, enter the same room name, and everyone stays in sync automatically.
      </div>
    </div>
  </div>

  <div class="how-it-works">
    <h2>How it works</h2>
    <p>
      The first person to join a room becomes the <strong>host</strong> — their play, pause, and seek actions sync to all guests in real time. Guests stay within 3 seconds of the host automatically. No account needed.
    </p>
  </div>

  <footer>
    <a href="https://github.com/praneshnangare/buddiestime-watch-party">View on GitHub</a>
  </footer>
</body>
</html>
```

- [ ] **Step 2: Add the `/` route to server/server.js**

In `server/server.js`, find the `STATIC_ROUTES` object (line 11) and add the `/` route:

```js
const STATIC_ROUTES = {
  '/':             path.join(__dirname, 'index.html'),
  '/install.html':    path.join(__dirname, '..', 'bookmarklet', 'install.html'),
  '/test-page.html':  path.join(__dirname, 'test-page.html'),
  '/guest-test.html': path.join(__dirname, 'guest-test.html'),
};
```

Also update the fallback response at the bottom of the HTTP handler (line 28) so it doesn't override the `/` route. The existing `if (filePath)` block already handles this correctly — no additional change needed.

- [ ] **Step 3: Test the landing page locally**

```bash
cd server
node server.js
```

Open http://localhost:8080 in a browser. You should see the landing page with the download button and 3-step instructions.

- [ ] **Step 4: Commit**

```bash
git add server/index.html server/server.js
git commit -m "feat: add landing page with APK download link"
git push
```

Expected: Railway auto-deploys within ~60 seconds. Visit your Railway URL — landing page should appear.

---

## Task 5: Update Android Default Server URL

**Files:**
- Modify: `android/app/src/main/res/layout/dialog_join_party.xml` (line 20)
- Modify: `android/app/build.gradle`

> Replace `YOUR_RAILWAY_URL` with your actual Railway domain (without `https://`), e.g. `buddiestime-watch-party-production.up.railway.app`

- [ ] **Step 1: Update the dialog default URL**

In `android/app/src/main/res/layout/dialog_join_party.xml`, change line 20 from:

```xml
android:text="wss://hotstar-watch-party.onrender.com" />
```

To (replace `YOUR_RAILWAY_URL`):

```xml
android:text="wss://YOUR_RAILWAY_URL" />
```

- [ ] **Step 2: Enable BuildConfig and add SERVER_URL field in build.gradle**

In `android/app/build.gradle`, update the `android { }` block to add `buildFeatures` and a `buildConfigField` inside `defaultConfig`:

```groovy
android {
    namespace 'com.buddiestime.watchparty'
    compileSdk 34

    buildFeatures {
        buildConfig true
    }

    defaultConfig {
        applicationId "com.buddiestime.watchparty"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"

        buildConfigField "String", "SERVER_URL", '"wss://YOUR_RAILWAY_URL"'
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = '1.8'
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/res/layout/dialog_join_party.xml android/app/build.gradle
git commit -m "feat: set Railway server URL as default in Android app"
git push
```

---

## Task 6: Add Signing Config to build.gradle

**Files:**
- Modify: `android/app/build.gradle`

This adds the release signing config that reads credentials from environment variables (set by GitHub Actions in CI, and locally for manual builds).

- [ ] **Step 1: Add signingConfigs block and wire it to release build type**

Replace the full `android { }` block in `android/app/build.gradle` with:

```groovy
android {
    namespace 'com.buddiestime.watchparty'
    compileSdk 34

    buildFeatures {
        buildConfig true
    }

    defaultConfig {
        applicationId "com.buddiestime.watchparty"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"

        buildConfigField "String", "SERVER_URL", '"wss://YOUR_RAILWAY_URL"'
    }

    signingConfigs {
        release {
            storeFile     file(System.getenv("KEYSTORE_PATH")   ?: "release.keystore")
            storePassword (System.getenv("STORE_PASSWORD")      ?: "")
            keyAlias      (System.getenv("KEY_ALIAS")           ?: "")
            keyPassword   (System.getenv("KEY_PASSWORD")        ?: "")
        }
    }

    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = '1.8'
    }
}
```

(Keep `YOUR_RAILWAY_URL` as set in Task 5.)

- [ ] **Step 2: Commit**

```bash
git add android/app/build.gradle
git commit -m "feat: add release signing config for CI"
git push
```

---

## Task 7: GitHub Actions Release Workflow

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create the workflows directory**

```bash
mkdir -p ".github/workflows"
```

- [ ] **Step 2: Create .github/workflows/release.yml**

```yaml
name: Release APK

on:
  push:
    tags:
      - 'v*.*.*'

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: write

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Decode keystore
        run: |
          echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 --decode > android/app/release.keystore

      - name: Make gradlew executable
        run: chmod +x android/gradlew

      - name: Build release APK
        working-directory: android
        env:
          KEYSTORE_PATH: release.keystore
          STORE_PASSWORD: ${{ secrets.STORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew assembleRelease

      - name: Rename APKs
        run: |
          VERSION="${GITHUB_REF_NAME}"
          cp android/app/build/outputs/apk/release/app-release.apk \
             "buddiestime-watchparty-${VERSION}.apk"
          cp android/app/build/outputs/apk/release/app-release.apk \
             "buddiestime-watchparty-latest.apk"

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: |
            buddiestime-watchparty-v*.apk
            buddiestime-watchparty-latest.apk
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: add GitHub Actions release workflow for APK"
git push
```

---

## Task 8: Generate Keystore and Add GitHub Secrets

This task has no code changes — it's a one-time setup of the signing key and CI secrets.

- [ ] **Step 1: Generate the release keystore**

Run this command in your terminal (not in the repo directory — or anywhere convenient). When prompted, fill in the values and **save the passwords somewhere safe**.

```bash
keytool -genkeypair \
  -v \
  -keystore release.keystore \
  -alias buddiestime \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Prompts:
- **Keystore password:** choose a strong password (e.g. `MyStr0ngPass!`)
- **Key password:** can be the same as keystore password
- **First and last name:** BuddiesTime
- **Organization unit:** (press Enter to skip)
- **Organization:** BuddiesTime
- **City, State, Country:** your details

- [ ] **Step 2: Base64-encode the keystore**

```bash
base64 -w 0 release.keystore
```

Copy the entire output (one long line) — this is your `KEYSTORE_BASE64` secret.

*(On macOS: `base64 -i release.keystore` — no `-w` flag needed)*

- [ ] **Step 3: Add secrets to GitHub**

Go to: `https://github.com/praneshnangare/buddiestime-watch-party/settings/secrets/actions`

Add these four secrets (click **New repository secret** for each):

| Secret name | Value |
|-------------|-------|
| `KEYSTORE_BASE64` | the base64 output from Step 2 |
| `STORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `buddiestime` |
| `KEY_PASSWORD` | the key password you chose |

- [ ] **Step 4: Delete the local keystore file**

```bash
rm release.keystore
```

The keystore now lives only in GitHub Secrets. Never commit it.

---

## Task 9: First Release

- [ ] **Step 1: Confirm everything is pushed**

```bash
git status
```

Expected: `nothing to commit, working tree clean`

- [ ] **Step 2: Tag v1.0.0 and push**

```bash
git tag v1.0.0
git push --tags
```

- [ ] **Step 3: Watch the build**

Go to: `https://github.com/praneshnangare/buddiestime-watch-party/actions`

Click the running workflow. The build takes ~8 minutes. All steps should show green checkmarks.

- [ ] **Step 4: Verify the GitHub Release**

Go to: `https://github.com/praneshnangare/buddiestime-watch-party/releases`

You should see **v1.0.0** with two APK assets:
- `buddiestime-watchparty-v1.0.0.apk`
- `buddiestime-watchparty-latest.apk`

- [ ] **Step 5: Verify the landing page download link**

Visit your Railway URL. Click **Download APK**. The file `buddiestime-watchparty-latest.apk` should download.

- [ ] **Step 6: Install and smoke-test the APK**

Transfer `buddiestime-watchparty-v1.0.0.apk` to an Android device (or emulator). Install it, open the app, tap the party icon. The server URL field should default to your Railway `wss://` URL. Enter a room name and verify it connects (status shows "● Host").

---

## Future Releases

```bash
# Bump versionCode and versionName in android/app/build.gradle, then:
git add android/app/build.gradle
git commit -m "chore: bump version to 1.1.0"
git push
git tag v1.1.0
git push --tags
# GitHub Actions builds and publishes the APK automatically
```
