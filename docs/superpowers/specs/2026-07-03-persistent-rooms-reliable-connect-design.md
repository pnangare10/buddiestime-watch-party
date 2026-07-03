# Watch Party — Persistent Rooms + Reliable Connection (Android)

**Date:** 2026-07-03
**Status:** Approved design, pre-implementation
**Scope:** Android app + Node WebSocket server only. No changes to Chrome extension, bookmarklet, or web room.html this round. No database, no accounts, no cross-device recents.

## Problem

1. Users get disconnected often and see an error right after connecting; idle sessions drop.
2. The server (Render free tier) cold-starts (~50s) after inactivity, but the client's connect timeout is ~10s, so the first connect shows a timeout error.
3. Sync silently breaks, so guests end up watching different videos than the host. Root causes: no client reconnect after a drop, rooms destroyed the instant they go empty, and fragile host role (host leaves → nobody drives sync).

## Desired model

- One user creates a party (room). Others join that room.
- A room owns a **current playing video**; everyone connected syncs to it.
- Anyone joining a room with a `currentVideoUrl` automatically opens/plays that video.
- Host pushes the current timestamp on a regular interval (**5 s**); guests auto-sync.
- A room **stays active ~5 minutes even with zero participants**, then expires.
- Android home screen with **one "Create Party" button** and **one search/join field**, plus a **recent-rooms list** showing active/inactive status.

## Decisions (confirmed with user)

- **Target:** Android app only.
- **Persistence:** In-memory server rooms with a 5-min empty-grace TTL + a live status HTTP API. Recent rooms stored client-side (SharedPreferences); active/inactive fetched live. Rooms are lost on server redeploy (acceptable).
- **Host handoff:** Auto-promote the oldest remaining member to host when the host leaves. Everyone leaving → state freezes for the grace window; first rejoiner becomes host and inherits `videoUrl`/`time`.

---

## A. Connection reliability

1. **Wake-then-connect.** Before opening the WebSocket, poll `GET /health` with a generous budget (~60 s). UI shows "Waking up the server… this can take up to a minute" instead of an error. Open the WS only after health responds.
2. **Longer WS timeouts.** OkHttp `connectTimeout` = 60 s, `readTimeout` = 0 (WS needs none), keep `pingInterval(20 s)`.
3. **Auto-reconnect with backoff.** On any unexpected close/failure (not a user "Leave"), reconnect with exponential backoff (1 s → 2 s → 4 s → … capped ~15 s, indefinitely), reusing the same `roomId`/`clientId`/`name`. Status shows "Reconnecting…". Server's existing stale-socket replacement (server.js:201) makes rejoin seamless — user returns to the same room at the synced position.
4. **Server keep-alive sweep.** Server sends `ws.ping()` every 30 s and terminates sockets that don't pong, cleaning up dead/half-open connections.

## B. Room model (server.js)

1. **5-minute empty grace.** Replace immediate destroy-on-empty (server.js:331) with a 5-min timer. Rejoin cancels it; expiry deletes room + state. Room stays rejoinable at its last video/timestamp during the window.
2. **`roomState` is the video source of truth:** `{ platform, videoUrl, time, paused, updatedAt, createdAt, emptySince, title? }`. Already updated on `state-update`; stop discarding it on empty.
3. **Auto host-promotion.** Host disconnects with guests remaining → promote oldest remaining member to host, broadcast role change; new host resumes driving sync.
4. **Periodic sync cadence → 5 s** (host-driven `state-update`), keeping immediate push on play/pause/seek.
5. **Status endpoints:** `GET /api/rooms/status?ids=a,b,c` → `[{ roomId, active, count, platform, videoUrl, title }]`; `GET /api/room/:id` for single lookup.

## C. Android home screen + recent rooms + search

1. **New launcher `RoomsHomeActivity`:** app home screen with:
   - One primary button: **Create Party**.
   - One search/join field: type a room code to join; also filters recents as you type.
   - **Recent Rooms** list: each row shows room code, last video/platform, live active/inactive dot + participant count.
2. **Recents in SharedPreferences:** `{ roomId, platform, videoTitle/url, lastJoined }`. On open, one `/api/rooms/status` call annotates rows live.
3. **Create flow:** Create Party → pick service → `MainActivity` as host → navigating to a video sets the room's `currentVideoUrl`.
4. **Join flow:** tap a recent room or enter a code → `MainActivity` as guest → platform from room status, WebView loads room's `currentVideoUrl` (already implemented at MainActivity.kt:435).
5. **Server URL baked in** via `BuildConfig` with a debug override — users never type a server URL.

## D. Unchanged

Guest-follows-host-video (MainActivity.kt:427-439), drift correction, chat, voice — untouched except 2 s → 5 s cadence and the reconnect wrapper.

## Out of scope (YAGNI)

Database/persistence across redeploys, accounts, cross-device recents sync, changes to extension/bookmarklet/web clients.

## Success criteria

- Cold-start connect shows a "waking up" state and succeeds without a timeout error.
- A dropped connection auto-reconnects and returns the user to the same room/position.
- A room survives ~5 min with nobody in it and is rejoinable at its last video/timestamp.
- Host leaving does not stall sync (new host auto-promoted).
- Home screen: Create button + code search + recent-rooms list with live active/inactive status.
- Guests always land on the host's current video.
