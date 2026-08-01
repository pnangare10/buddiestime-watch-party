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

| Log line                                                 | Meaning                                                         |
| -------------------------------------------------------- | --------------------------------------------------------------- |
| `[HWP] role=guest heartbeat=off selfCheck=250ms`         | Guest correction loop armed                                     |
| `[HWP] role=host heartbeat=1000ms selfCheck=off`         | Host is pushing state every second                              |
| `[HWP] host state: t=X paused=false`                     | A host sample arrived and was stamped                           |
| `[HWP] correcting (tick): err=-4.20s target=X lead=0.85` | Guest was 4.2s behind and is seeking past the target            |
| `[HWP] seek settled in 2.10s — seekCost now 1.24s`       | The device measured its own seek cost and adapted the lead      |
| `[HWP] buffering at t=X`                                 | Rebuffer started — a `playing` event should follow and re-check |

### Verifying sync convergence (the real proof)

Automated tests cover the pure logic and a simulated player:

```bash
node --test tests/sync-convergence.test.js
```

```bash
cd server && node --test test/*.test.js
```

Neither can exercise a real DRM player, so convergence itself must be confirmed on two
devices. Run both, join the same room, then watch the guest's error:

```bash
adb logcat -s chromium | grep --line-buffered "\[HWP\] correcting\|\[HWP\] seek settled"
```

Pass criteria:

- After the guest joins, `err` shrinks across at most 2–3 corrections and then corrections
  **stop**. Continuous `correcting` lines mean the seek lead is hunting.
- Pause on the host: the guest pauses **at the same timestamp**, not 5–10s behind.
- Throttle the guest's network to force a rebuffer. It should recover within a few seconds
  and go quiet again.
- Leave it running 10 minutes. No slow accumulating drift.

### Not covered by this sync model

- **Ad breaks** — during a Hotstar ad, `currentTime` refers to the ad, not the feature, so
  two devices in different ad states have incomparable timelines.
- **Live streams** — `currentTime` is not monotonic and backward seeks may be rejected.
- **A buffering host** — the host publishes its own stalled position as the truth.

### Known issues / future hardening

- **`HWP_setRole` timing race** — `onPageFinished` calls `evaluateJavascript(SYNC_SCRIPT)` then immediately calls `HWP_setRole`. Since `evaluateJavascript` is async, `HWP_setRole` can fire before SYNC_SCRIPT runs. Fix: call `HWP_setRole` inside the SYNC_SCRIPT injection callback, not after it.
- **Profile selection on emulator** — fresh launch lands on "Who's watching?" page. Use `hwp_url` pointing directly to the video URL to skip navigating from home page.
- **ANR dialog on cold emulator start** — emulator may show "System UI isn't responding" on first boot under load; tap "Wait" via `adb shell input tap` with corrected device coordinates (device is 1440×3120 at 560 DPI).
