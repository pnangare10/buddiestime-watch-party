# BuddiesTime Watch Party — Public Deployment Design

**Date:** 2026-04-17  
**Status:** Approved

## Goal

Make the app usable by real users: a hosted WebSocket server, a public APK download, and a landing page — all automated via GitHub + Railway.

---

## Architecture

```
GitHub repo (public)
  ├── server/           ← Node.js WebSocket server
  ├── android/          ← Android app (APK built by CI)
  ├── .github/workflows/release.yml  ← builds + signs + releases APK on git tag
  └── docs/

Railway (free tier)
  └── auto-deploys server/ on push to main
  └── serves wss:// endpoint + landing page at /

GitHub Releases
  └── signed APK uploaded automatically on git tag push
```

---

## Components

### 1. GitHub Repository

- Create a new public GitHub repo (e.g. `buddiestime/watch-party`)
- Push all existing code as the initial commit
- `main` branch is the deployment branch

### 2. Railway Server Deployment

- Add `railway.json` at repo root:
  ```json
  { "build": { "builder": "nixpacks" }, "deploy": { "startCommand": "node server/server.js" } }
  ```
- Connect Railway to the GitHub repo — auto-deploys on every push to `main`
- Railway provides a stable public URL: `https://watch-party-production.up.railway.app`
- WebSocket endpoint: `wss://watch-party-production.up.railway.app`

### 3. Landing Page

- New file: `server/index.html`
- Served at `/` by adding a static route in `server/server.js`
- Mobile-friendly, single page, no framework
- Content:
  - App name: **BuddiesTime Watch Party**
  - Tagline: "Watch Hotstar, Prime Video, YouTube & Netflix in sync with friends"
  - "Download APK (Android)" button → links to latest GitHub Release
  - 3-step setup:
    1. Install APK (enable "Install from unknown sources")
    2. Open app → pick a streaming service
    3. Type a room name → share with friends → they type the same name
  - Brief "How it works" explanation
  - Link to GitHub repo

### 4. Android App — Server URL

- `WatchPartyManager.kt`: replace hardcoded `localhost:8080` with the Railway URL via a `BuildConfig` field
- `android/app/build.gradle`: add `buildConfigField "String", "SERVER_URL", '"wss://watch-party-production.up.railway.app"'`
- App reads `BuildConfig.SERVER_URL` at runtime — no other changes to sync logic

### 5. Release Keystore

- Generated once locally: `keytool -genkeypair -v -keystore release.keystore ...`
- **Never committed to the repo**
- Stored as GitHub Actions secrets:
  - `KEYSTORE_BASE64` — base64-encoded keystore file
  - `KEY_ALIAS`
  - `KEY_PASSWORD`
  - `STORE_PASSWORD`
- `android/app/build.gradle` signing config reads these from environment variables during CI builds

### 6. GitHub Actions Workflow

**File:** `.github/workflows/release.yml`  
**Trigger:** push of a tag matching `v*.*.*` (e.g. `v1.0.0`)

**Steps:**
1. Checkout code
2. Set up JDK 17 + Android SDK
3. Decode keystore from `KEYSTORE_BASE64` secret
4. Run `./gradlew assembleRelease` with signing env vars
5. Create GitHub Release via `softprops/action-gh-release`
6. Upload `app-release.apk` as release asset, renamed to `buddiestime-watchparty-v<tag>.apk`

**Release workflow for developer:**
```bash
git tag v1.0.0
git push --tags
# GitHub Actions builds, signs, and publishes the APK automatically (~8 min)
```

---

## Supported Services

APK ships with support for:
- Hotstar
- Prime Video
- YouTube
- Netflix

---

## Out of Scope

- User accounts or authentication
- Room persistence across server restarts (rooms are in-memory)
- Push notifications
- iOS support
- Custom domain (Railway subdomain is sufficient for now)

---

## Success Criteria

1. Server deployed to Railway and reachable at a stable `wss://` URL
2. Android app connects to Railway URL (not localhost) and syncs video
3. GitHub Release exists with a signed, installable APK
4. Landing page at Railway URL has working download link and clear instructions
5. Pushing `git tag v1.0.1 && git push --tags` produces a new release with no manual build steps
