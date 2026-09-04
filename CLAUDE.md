# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Running the server

```bash
cd server
node server.js          # production
node --watch server.js  # dev (auto-restart on file change)
```

Server runs on port 8080 (overridable via `PORT` env var).  
Static routes served:

- `http://localhost:8080/test-page.html` — two-browser WebSocket test page
- `http://localhost:8080/install.html` — phone bookmarklet setup page

## Testing

See **[TESTING.md](TESTING.md)** for the full end-to-end testing approach (emulator + browser + real video).

No test framework is set up. Two ways to verify the sync logic:

**Automated (Node.js WebSocket client):**

```bash
cd server
node -e "/* inline WebSocket test */"
```

See the existing inline test patterns in chat history — they open two WS clients, join as host/guest, and assert message relay.

**Browser (Playwright or manual):**
Open `http://localhost:8080/test-page.html` in two tabs. Tab 1 → Join as Host, Tab 2 → Join as Guest. Use the Play/Pause/Seek buttons on the host tab and watch the guest log.

## Architecture

The project has three independent clients that all speak the same WebSocket protocol to one central server:

```
server/server.js          ← Node.js WebSocket + HTTP server (no framework)
extension/content.js      ← Chrome extension content script (runs on hotstar.com)
extension/popup.js        ← Extension popup UI (collects server URL + room ID)
bookmarklet/bookmarklet.js ← Standalone version for mobile/non-Chrome users
bookmarklet/install.html  ← Phone setup page (served by the server at /install.html)
server/test-page.html     ← Local test page (no extension needed)
```

### WebSocket protocol

All messages are JSON. Room membership is tracked server-side in a `Map<roomId, Map<ws, {role, id}>>`.

| Message                                | Direction          | Meaning                                                               |
| -------------------------------------- | ------------------ | --------------------------------------------------------------------- |
| `{type:'join', roomId, clientId}`      | client→server      | Join or create a room. First client becomes `host`, rest are `guest`. |
| `{type:'joined', role, clientId}`      | server→client      | Confirms role assignment.                                             |
| `{type:'sync-request', from}`          | server→host        | Sent when a guest joins; host should reply with `sync-response`.      |
| `{type:'sync-response', time, paused}` | host→server→guest  | Host's current video state for initial sync.                          |
| `{type:'play'\|'pause'\|'seek', time}` | host→server→guests | Video event relay.                                                    |

The server does **not** enforce roles — it broadcasts every message from any client to all others in the room. Role enforcement (only host sends play/pause/seek) is done client-side.

### Content script lifecycle

1. Popup sends `start-party` chrome message → content script calls `connect(serverUrl, roomId, clientId)`
2. On `joined`: `waitForVideo()` polls for `document.querySelector('video')` every 500ms, then calls `attachVideoListeners()`
3. `attachVideoListeners()` uses **named handler functions** (`onVideoPlay`, `onVideoPause`, `onVideoSeek`) so they can be cleanly removed on disconnect
4. `disconnect()` cancels the poll timer, removes all video listeners, resets `ws/video/role/isSyncing` to null

### Drift correction

Guests only seek to match the host if `Math.abs(guest.currentTime - host.time) > 3` seconds (`DRIFT_THRESHOLD`). Sub-3s differences are ignored to avoid constant micro-seeks.

### Bookmarklet vs extension

The bookmarklet (`bookmarklet/bookmarklet.js`) is a self-contained IIFE with the same sync logic. `install.html` inlines a minified version of it into a `javascript:` href, with `server` and `room` values hardcoded by the host's share URL so the guest doesn't need to type anything.

## Agent team

For any non-trivial feature or fix, run it through this pipeline instead of a single freeform pass:

1. **Plan** (built-in `Plan` agent) — drafts the implementation approach.
2. **Critique** (built-in `plan-critique` agent) — stress-tests the plan before anything is built.
3. **Implement** (`watch-party-developer`, `.claude/agents/watch-party-developer.md`) — builds the approved plan, one bounded change at a time, staying consistent with the WS protocol across all clients.
4. **Review** (`watch-party-reviewer`, `.claude/agents/watch-party-reviewer.md`) — checks the diff for protocol-parity bugs, role-enforcement gaps, and drift-correction regressions specific to this repo.
5. **Test** (built-in `browser-test-runner` agent, or manual two-tab `test-page.html` flow per [TESTING.md](TESTING.md)) — verifies the change actually works end-to-end.

Dispatch each stage as an `Agent` call with the corresponding `subagent_type`; don't skip the critique or review steps just because the task looks small — that's exactly where protocol drift between clients slips in.

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:

- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)
