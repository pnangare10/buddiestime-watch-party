# Watch Party — Pairing, Onboarding & Personalization Redesign (Android)

**Date:** 2026-07-22
**Status:** Draft — requirements gathering, pre-planning
**Scope:** Android app + server. No changes intended for the Chrome extension, bookmarklet, or `room.html` web client unless called out.
**Implementation note (decided):** this is a **full replacement** of the existing hardcoded-couple / multi-room code — no backward-compat shims for `Personalization.kt`'s hardcoded names or the multi-room `RoomsHomeActivity` UI. All work happens on the current branch (`claude/run-application-kl2zdw`), which is **not** `main` — do not merge to `main`.

## 1. Context — what exists today

The Android app currently supports **arbitrary multi-room** watch parties:

- `RoomsHomeActivity` — home screen with a "Create Party" button (service picker), a free-text room-code search/join field, and a recents list of past rooms.
- Any two people can create/join any room by sharing a 6-character code typed in by hand.
- `Personalization.kt` hardcodes both names (`HER_NAME = "Komal"`, `HIS_NAME = "Sonu"`) at compile time — the app is literally built once per couple. There is no in-app setup/config screen.
- `ServiceSelectorActivity` asks only the **local device's own display name** on first use (`displayName` in `hwp_prefs`), used for chat attribution. It does not identify who's using the device relative to the other person.
- `FlufflesTheme.kt` auto-picks one of 3 accent colors **by calendar date** (`epochDay % 3`), independently computed on each device — no user choice, no explicit sync (it happens to match because both phones compute the same date math).
- `FlirtyLines.kt` shows one hardcoded flirty line on the **splash screen at cold app-launch only** (local, not a push notification, not configurable, no way to add new lines).
- There is no push notification capability (no FCM, no `POST_NOTIFICATIONS` handling, no background service) — the WebSocket connection only exists while `MainActivity` is open and a party is actively connected. Rooms auto-expire 5 minutes after both people disconnect (per `2026-07-03-persistent-rooms-reliable-connect-design.md`).
- There is no Settings screen anywhere in the app.
- The existing `server.js` room model (`rooms`, `roomState` Maps) is entirely **ephemeral** — it exists only to drift-correct video playback during an active watch session, and is discarded 5 minutes after both sides disconnect. It has no concept of a durable identity for a person or a couple.

## 2. Goals (from this request)

Reshape the app from "generic multi-room tool, one name hardcoded at build time" into a **personalized pairing app built around exactly two specific people**, configured at install time through a shareable link rather than a compiled build, with:

1. A single default room — no room-creation/browsing UI.
2. A link-based pairing/configuration flow (BF configures → shares link → GF's app self-configures).
3. Push notifications with configurable flirty/suggestive invite text ("dirty text") to nudge the partner to join.
4. A shared "today's theme" that either person can set from Settings, applied to both devices.
5. Configurable welcome messages, editable from Settings, shown to the other person.

## 3. Functional requirements

### 3.1 Single default room (replaces multi-room home screen)

- The app is scoped to **exactly one persistent room/pairing** per installed pair — not a browsable list of rooms.
- Remove (or fully repurpose) the multi-room browsing UI: "Create Party" service picker, free-text room-code join field, and the recents list in `RoomsHomeActivity`.
- The room identifier is established once, during pairing (§3.2), and is not something either user manually types again — the one exception is the room's vanity **name**, which the *owner* (first device) chooses once at creation time (§3.2); the partner never types anything.
- **Streaming service selection stays free per session** — decided. The room/pairing is singular, but either person can still pick Hotstar/Netflix/Prime/YouTube each time they start watching, same as today's per-session picker. The room is not pinned to one service at pairing time.
- Settings exposes the current room/pairing identity and lets the user re-pair or reset it (see §3.2, §8 for what "configurable in settings" should mean exactly).

### 3.2 Device identity, room ownership & link-based pairing

This is the architectural core of the redesign, replacing the current flow (hardcoded names at build time + separately typed local display name) with a **server-issued device identity + a persistent Room record**, distinct from today's ephemeral video-sync session.

**Device identity — no login, ever.**
1. On first launch (either phone, either role), the app has no stored `deviceId`. It calls the server once to mint one: `POST /api/devices` → server generates a unique, unguessable `deviceId` (e.g. a random UUID/token — **not** a sequential counter, since possession of this ID is the only credential the device has) → the app persists it locally and sends it on every subsequent request. No password, no phone-number verification, no account.
2. Each device is linked to **at most one room** at a time, tracked both server-side (`Device.roomId`) and client-side as a local guard.

**Room creation (boyfriend's device, first run, no room yet).**
3. If the device isn't linked to a room, the app asks him to name their room — a vanity identifier (e.g. a couple nickname), not something the partner will ever type. `POST /api/rooms { roomName, deviceId }` → server checks `roomName` uniqueness; on success creates the Room with `ownerDeviceId = <his deviceId>`, returns `roomId`.
4. The app then collects his own profile (name, pet name, timezone, birthday, quiet hours) and a first draft of hers (name, pet name he calls her) — see the setup field list in the previous draft, retained below for reference:
   - **Her display name** (required) and **pet name/nickname** (optional).
   - **His display name** (required) and pet name (optional).
   - **Timezone** — defaults to India (IST), selectable.
   - **Anniversary / "together since" date** (optional, couple-level).
   - **Her birthday** (optional). His birthday comes from his own profile, entered once for himself.
   - **Preferred starting theme** (optional) — seeds `Room.theme` instead of starting from date-based auto-rotation.
   - **Notification quiet hours** (optional, per-device — each person sets their own).
   - **Optional pairing PIN** (short numeric code) — if set, redeeming the link additionally requires entering this PIN (typed manually by her, never embedded in the link), as defense if the link itself is intercepted or forwarded to the wrong person.
5. This is persisted to the Room record server-side (`ownerProfile`, `partnerProfileDraft`) — see §6 for the exact schema.

**Link generation & sharing.**
6. The app requests a **one-time invite token** scoped to the room: `POST /api/rooms/:roomId/invite` → `{ token }`. The shareable link is a **pointer, not a payload** — e.g. `https://<server>/pair/<roomId>/<token>` — it carries no profile data itself, only a route back to this specific room plus a single-use credential.
7. Shared via the Android share sheet (WhatsApp, SMS, etc.), however he chooses.

**Redemption (girlfriend's device).**
8. Opening the link deep-links into the app (already installed). If her device has no `deviceId` yet, it mints one first (step 1, same as any first launch).
9. The app extracts `roomId` + `token` from the link and calls `POST /api/rooms/:roomId/join { token, deviceId, pin? }`. Server validates: token exists and is unused, room doesn't already have a `partnerDeviceId`, PIN matches if one was set. On success: `Room.partnerDeviceId = herDeviceId`, token is consumed (can never be reused), her `Device.roomId` is set.
10. The server responds with **her profile draft** (what he entered about her — prefilled into her own editable profile, not read-only) and **his profile** (read-only from her side, since it's his self-authored data). Her app self-configures immediately — no prompts.
11. Both devices are now paired: the room has exactly 2 device IDs, and each device belongs to exactly one room. A third redemption attempt on a consumed/full room is rejected with a clear error.

**Why a pointer instead of a self-contained payload:** keeps the link short, lets pairing data change server-side without needing a new link, and means the one-time token is the only thing that expires — not the profile data itself.

### 3.3 "Nudge" push notifications (configurable flirty invite text)

- **Primary trigger, per this request: automatic.** When one partner starts playing a video (the existing host-becomes-active / `state-update` moment), the server fires a push notification to the other device using a message drawn at random from the room's **nudge-message pool** (§3.5 — kept as a pool distinct from welcome messages, per your confirmation).
- *Assumption, flag if wrong:* a manual "invite now" button (nudge before actually pressing play, e.g. "wanna watch?") is also worth keeping as a secondary trigger. Only the auto-on-play trigger was explicitly described — confirm whether you want the manual button too or not.
- Tapping the notification opens the app directly into the room, ready to sync.
- Requires push infrastructure that doesn't exist today: FCM registration per device (`Device.fcmToken`, collected at first launch alongside `POST_NOTIFICATIONS` runtime permission on Android 13+), and a server-side send-to-partner-token step triggered off the video-start event.
- Respects the device's own quiet-hours window (§3.2) — a nudge due to fire inside quiet hours is either suppressed or deferred (needs a pick in planning).

### 3.4 Shared daily theme, settable by either person

- Settings gets a theme picker (replacing/extending the current silent date-based auto-rotation in `FlufflesTheme`).
- Theme lives on the **Room** record server-side (`Room.theme`), not per-device — so it is inherently shared rather than something that needs live-syncing between two independent local states. Either device's Settings screen writing `Room.theme` is instantly the answer for both, the next time either app reads it (see §6 for why this doesn't need the ephemeral WS session to be open).
- Default behavior (auto date-based rotation) still applies when nobody has made an explicit choice; an explicit choice overrides it. Whether an explicit choice persists forever or lapses back to auto-rotation the next day is still open (§7).

### 3.5 Configurable welcome messages (separate pool from nudge messages — confirmed)

- Settings gets an editable list of "welcome messages," stored on the **Room** record as `Room.welcomeMessages`, each entry tagged with `authorDeviceId` so the UI can show "written by you" vs. "written by {partner}."
- This is a **distinct pool** from §3.3's nudge/invite messages (`Room.nudgeMessages`) — confirmed. Both pools live in the same place (room config) for the same reason: they need to be readable/editable by either device independent of an active session.
- Trigger for display still needs a decision: shown when the partner opens the app? Joins the (single) room? Both? (§7)

## 4. Settings screen (implied by all of the above)

None of this works without a Settings screen, which doesn't exist today. It needs, at minimum:
- Pairing status (room name, who's paired), and a re-pair/reset control (§3.2, §7).
- Theme picker (§3.4).
- Welcome-message list editor: add / edit / delete (§3.5).
- Nudge-message pool editor, kept separate from welcome messages (§3.3, §3.5).
- Notification permission status/toggle, and quiet-hours editor.
- Own profile editor (name, pet name, timezone, birthday) — since profile data no longer comes from a compiled constant, it needs somewhere to live/edit after initial setup too.

## 5. Non-functional considerations

- **Device ID is a bearer credential.** Whoever holds it can act as that device against the server (no separate login). Send it like a secret (e.g. an `X-Device-Id` header over HTTPS), and don't log it in plaintext anywhere it'd be easy to scrape. This is intentionally lightweight for a 2-person personal app, not enterprise-grade auth.
- **Device ID durability.** Uninstalling the app (or clearing app data) loses the local `deviceId`, orphaning that side of the Room — the server still shows a `partnerDeviceId` that no longer has a live device behind it. Needs a recovery path in planning (§7).
- **Privacy/security of the pairing link:** shared over ordinary messaging apps; treat as semi-public. It's now a pointer + one-time token rather than a payload, and the optional PIN adds a second factor if you want it — no server secrets or long-lived credentials are ever embedded in the link itself.
- **This is a two-person app by design** — no accounts, no multi-couple support, no discovery.
- **Notification tone:** "dirty text" messages are intentionally suggestive/flirty by request — no moderation concerns since author and recipient are the same two consenting users, but the pool should stay easy to edit from Settings.

## 6. Proposed server data model

Two server-side entities, both persistent (unlike today's ephemeral `rooms`/`roomState` Maps in `server.js`, which stay as-is for live video-sync during an active watch session — see the note at the end of this section on how the two relate).

```
Device
  deviceId        — server-generated, unique, unguessable (bearer credential)
  roomId          — nullable; the one room this device belongs to
  role            — "owner" | "partner"
  profile: { displayName, petName, timezone, birthday? }
  fcmToken        — nullable; for push notifications
  quietHours      — { startHour, endHour } | null
  createdAt

Room
  roomId          — server-generated at creation
  roomName        — owner-chosen vanity name, unique, cosmetic only (partner never types it)
  ownerDeviceId
  partnerDeviceId — nullable until redeemed
  anniversaryDate — nullable, couple-level (not tied to one device)
  theme: { mode: "auto" | "manual", value, setByDeviceId, setAt }
  nudgeMessages:   [ { id, text, authorDeviceId, createdAt } ]   // §3.3 pool
  welcomeMessages: [ { id, text, authorDeviceId, createdAt } ]   // §3.5 pool, separate
  pendingInvite:   { token, createdAt } | null    // cleared the moment it's redeemed
  createdAt, updatedAt
```

**Where do the message pools and theme live? Recommendation (you asked for this one directly): on the `Room` record, server-side, fetched via plain REST (`GET /api/rooms/:roomId`) whenever the app opens or Settings is viewed** — not inside the ephemeral WebSocket room state. That's what makes them work independent of whether a watch party is currently connected: the persistent `Room` is always queryable; the existing `server.js` WS room is still exactly what it is today (drift-corrected playback sync during an active session), just now keyed off the same durable `roomId` when a session happens to be live. Theme/message changes don't need the WS connection open at all — they're a REST read/write against `Room`, with the nudge notification (§3.3) as the one thing that genuinely needs push delivery because it must reach a closed app.

## 7. Open questions to resolve before/in planning

Resolved since earlier drafts: pairing setup fields, streaming service stays free-per-session, pairing link is one-time-use, nudge/welcome pools are separate, message pools + theme live on the Room record. Remaining:

1. **Device ID recovery after uninstall/reinstall.** If either phone loses its `deviceId` (uninstall, cleared data, factory reset), that side of the Room is orphaned. Does the *other* device get a "re-pair" action in Settings that mints a fresh invite token for the same room (keeping history/messages), or does losing a device mean starting a brand-new Room?
2. **Room-name collision UX.** If the owner's chosen vanity name is taken, do we just reprompt, or auto-suggest a variant?
3. **Manual "invite now" button** in addition to the automatic on-video-start trigger — wanted or not?
4. **Quiet-hours behavior for the auto-nudge.** Suppress the notification entirely if it lands inside quiet hours, or queue/defer it to the end of the window?
5. **Welcome-message display trigger.** On app open, on entering the room, or both?
6. **Theme persistence semantics.** Does an explicit pick stick forever until changed again, or does auto-rotation resume the next day absent a new explicit choice?
7. **Pairing link delivery mechanics.** Android App Link (https, needs domain + asset-links verification) vs. custom URI scheme (simpler, weaker if the app isn't installed yet). Do we need a "not installed → Play Store" fallback, or is "install first, then tap the link" acceptable for two people?

## 8. Out of scope (unless told otherwise)

- Multi-couple / multi-room support (explicitly being removed, not generalized).
- Chrome extension, bookmarklet, and `room.html` web client changes.
- Voice chat (LiveKit) changes — unaffected by this redesign.
- Any web-based (non-Android) pairing flow.

## 9. Success criteria (draft — refine in planning)

- A fresh install on the boyfriend's phone requires no room code entry; he names the room once, enters both profiles, and gets a shareable one-time link.
- Opening that link on the girlfriend's phone (app already installed) fully configures her app — her profile, his profile, the room — with zero manual steps beyond tapping the link (and entering the PIN, if one was set).
- Opening the same link a second time (or on a different device) fails with a clear "already used" message instead of silently re-pairing or overwriting.
- A room never has more than 2 linked devices; a device is never linked to more than 1 room.
- Neither user ever sees a "create room" / "join room by code" / browse-rooms screen again.
- Either user can still freely pick a streaming service each time they start watching, independent of the pairing.
- Starting a video automatically nudges the partner via push notification with a random pick from the nudge pool, deliverable even if their app is closed.
- Either user picking a theme in Settings changes both devices' UI accent, without either device needing an active watch session open.
- Either user can add a new welcome message from Settings, stored on the shared room config, and it's visible to the other person per whatever trigger we land on in §7.
