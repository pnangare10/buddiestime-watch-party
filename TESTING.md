# End-to-End Testing Approach

## How It Works

The test simulates a real watch party between a Chrome browser (host) and Android emulator (guest), using the actual Hotstar website with a real video playing.

### Three-layer architecture under test

```
Browser (host) ──WS──► Server (port 8080) ──WS──► Android WebView (guest)
  content.js logic          server.js              SYNC_SCRIPT + WatchPartyManager
```

### Test steps

1. **Start server** — `node server/server.js` (or verify already running on 8080)
2. **Start emulator** — `emulator -avd Pixel_7_Pro_API_34 -no-snapshot-save`
3. **Install APK** — `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. **Open browser on Hotstar video** — Playwright navigates to a real watch URL
5. **Inject host logic** — `browser_evaluate` injects the same WebSocket + video listener code that `extension/content.js` uses; browser joins the room first and becomes host
6. **Launch Android as guest** — ADB intent with `hwp_server`, `hwp_room`, `hwp_url` pointing directly to the same video; app auto-connects as guest
7. **Perform seek** — `video.currentTime += 30` in the browser; the `seeked` event fires, the host sends `{type:'seek', time}` over WebSocket
8. **Verify on Android** — `adb logcat -s chromium` captures `[HWP]` console logs from SYNC_SCRIPT inside the WebView; confirms `seekTo: t=X cur=Y` and `seeked event t=X` within ~0.02s of target

### Why inject rather than use the real extension

Playwright can't load unpacked Chrome extensions without special launch flags. Injecting the equivalent JS from `content.js` into the page via `browser_evaluate` is functionally identical — same WebSocket protocol, same video event listeners, same server.

### Key ADB commands

```bash
# Start emulator
~/AppData/Local/Android/Sdk/emulator/emulator.exe -avd Pixel_7_Pro_API_34 -no-snapshot-save

# Wait for boot
adb shell getprop sys.boot_completed   # wait until "1"

# Install
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Launch directly into a video as guest (skips dialog)
adb shell am start \
  -n com.hotstar.watchparty/.MainActivity \
  --es hwp_server "ws://10.0.2.2:8080" \
  --es hwp_room "ROOM_ID" \
  --es hwp_url "https://www.hotstar.com/in/movies/TITLE/ID/watch"

# Watch HWP sync logs in real time
adb logcat -s chromium | grep "\[HWP\]"

# Take emulator screenshot
adb exec-out screencap -p > screenshot.png
```

### What to look for in logcat (success criteria)

| Log line | Meaning |
|----------|---------|
| `[HWP] syncTo: t=X paused=false cur=0.0` | Guest received initial sync-response |
| `[HWP] syncTo: t=X paused=false cur=X` | Seek applied (cur ≈ t) |
| `[HWP] seekTo: t=X cur=Y` | Seek command received (X−Y > 3 triggers actual seek) |
| `[HWP] seeked event t=X host=false` | Android video element confirmed the seek |

### Known issues / future hardening

- **`HWP_setRole` timing race** — `onPageFinished` calls `evaluateJavascript(SYNC_SCRIPT)` then immediately calls `HWP_setRole`. Since `evaluateJavascript` is async, `HWP_setRole` can fire before SYNC_SCRIPT runs. Fix: call `HWP_setRole` inside the SYNC_SCRIPT injection callback, not after it.
- **Profile selection on emulator** — fresh launch lands on "Who's watching?" page. Use `hwp_url` pointing directly to the video URL to skip navigating from home page.
- **ANR dialog on cold emulator start** — emulator may show "System UI isn't responding" on first boot under load; tap "Wait" via `adb shell input tap` with corrected device coordinates (device is 1440×3120 at 560 DPI).
