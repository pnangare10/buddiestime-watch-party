# Watch Party — Pairing, Onboarding & Personalization Redesign (Android)

**Date:** 2026-07-22
**Status:** Draft — requirements gathering, pre-planning
**Scope:** Android app + server. No changes intended for the Chrome extension, bookmarklet, or `room.html` web client unless called out.

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
- The room identifier is established once, during pairing (§3.2), and is not something either user manually types again.
- **Streaming service selection**: today's flow lets you pick Hotstar/Netflix/Prime/YouTube per session. Decide whether the "default room" is pinned to one service chosen at pairing time, or whether service choice remains free per watch session while the *room/pairing* itself stays singular. *(Open question — see §6.)*
- Settings exposes the current room/pairing identity and lets the user re-pair or reset it (see §3.2, §6 for what "configurable in settings" should mean exactly).

### 3.2 Link-based pairing & auto-configuration

Replaces the current flow (hardcoded names at build time + separately typed local display name):

1. **Setup (boyfriend's device, first run):** a setup screen collects his girlfriend's details (display name at minimum) and any other profile details we decide to support (see open question below) — plus implicitly his own name/config already on that device.
2. The app generates a **pairing link** encoding the config needed for the second device to self-configure: at minimum the room/pairing ID, the girlfriend's name, and the server to connect to.
3. That link is shared out (Android share sheet — WhatsApp, SMS, etc.) by the boyfriend, however he chooses.
4. **Girlfriend opens the link** on her device. It deep-links into the app (Android App Link / custom scheme). The app installs the config from the link automatically — **no manual entry, no room code typing, no name prompt**.
5. After this one-time exchange, both devices are permanently paired to the same room/pairing ID and know each other's names.

**Requirements for the link mechanism:**
- Must work whether or not the app is already installed on the receiving device (Play Store fallback vs. deep link) — *scope/priority TBD, see open questions.*
- Must be safe to send over a plain messaging app (i.e., not itself a secret/credential whose leakage matters much — but see §5 on privacy).
- Should degrade gracefully if opened twice, opened on a third device, or opened after the pair is already configured (re-pairing / overwrite behavior needs a decision).

### 3.3 "Nudge" push notifications (configurable flirty invite text)

- Either user can trigger a notification on the partner's device inviting them to join a watch party right now (e.g., a button in the app — "Invite her/him to watch").
- The notification body is **not a fixed string** — it's drawn from a **user-configurable pool of messages** (distinct from, or an evolution of, the existing `FlirtyLines` splash pool), editable in Settings (see §3.5 — this may be the same underlying "messages" feature or a separate pool; needs a decision).
- Tapping the notification opens the app directly into the (single) room, ready to sync.
- Requires **push infrastructure that does not exist today**: since the current WebSocket connection only lives while the app is foregrounded in an active party, delivering a notification to a partner who doesn't have the app open requires a push channel (e.g., FCM) plus a server-side way to route "invite partner X" to the correct device token. This is new backend surface, not just an Android change.
- Android 13+ requires runtime `POST_NOTIFICATIONS` permission — needs a request flow.

### 3.4 Shared daily theme, settable by either person

- Settings gets a theme picker (replacing/extending the current silent date-based auto-rotation in `FlufflesTheme`).
- When **either** user picks a theme, it applies to **both** devices' app UI — not just their own.
- This requires a sync mechanism that works independently of whether a watch-party session is currently connected (theme changes should propagate even if the partner isn't actively watching something right now) — see §6 for the architecture question this raises.
- Decide whether "today's theme" still auto-resets daily to something (e.g., last explicit choice persists until changed, vs. reverting to auto-rotation the next day if nobody chose).

### 3.5 Configurable welcome messages

- Settings gets an editable list of "welcome messages" — the user can add new ones (and presumably edit/remove), stored per-user.
- When the partner opens the app (or joins/returns to the room — needs a trigger definition), one of the messages the *other* person authored is shown to them.
- Relationship to §3.3's notification text pool needs clarifying: is this the same pool reused as notification bodies, a superset, or a fully separate feature (e.g., in-app-only "welcome back" banner vs. push notification body)?

## 4. New Settings screen (implied by all four features above)

None of this works without a Settings screen, which doesn't exist today. It needs, at minimum:
- Pairing status / re-pair or reset controls (§3.1, §3.2).
- Theme picker (§3.4).
- Welcome-message list editor: add / edit / delete (§3.5).
- Notification message pool editor, if separate from welcome messages (§3.3).
- Notification permission status/toggle.

## 5. Non-functional considerations

- **Privacy/security of the pairing link:** it's shared over ordinary messaging apps; treat it as semi-public. Avoid encoding anything sensitive beyond what's needed to pair (no server secrets, no auth tokens with broad scope).
- **This is a two-person app by design** — no need for accounts, multi-couple support, or discovery. Keep the pairing model as simple as the single-room requirement implies.
- **Backward compatibility:** existing installs currently rely on hardcoded `Personalization` names and the multi-room home screen. Decide whether this is a breaking redesign (fine for a personal app with 2 known installs) or needs a migration path.
- **Notification tone:** "dirty text" messages are intentionally suggestive/flirty by the user's own request — no content moderation concerns since both the author and recipient are the same two consenting users, but the message pool should still be easy to edit/tone down from Settings.

## 6. Open questions to resolve before/in planning

1. **"Other details" in the pairing setup** — beyond the girlfriend's display name, what else does the boyfriend enter during setup? (e.g., her photo/avatar, a nickname vs. legal name, preferred streaming service, anniversary date for personalization, etc.) Need an explicit list.
2. **Architecture for "always-on" sync (theme + welcome messages + notifications):** today, the WebSocket connection and room state only exist while a watch party is actively connected, and rooms expire 5 minutes after going empty. Theme changes and partner notifications need to reach a device that may not have an active party session at all. Options to weigh in planning:
   - A persistent/always-reconnecting background WS connection tied to the permanent pairing (not the ephemeral watch-room).
   - REST-based settings sync (poll or fetch-on-open) + push (FCM) for anything that needs to arrive instantly (the nudge notification).
   - Treat the "default room" as effectively permanent/non-expiring (remove the grace-expiry for this app's single room) and keep using the existing room-broadcast mechanism for theme/messages too.
3. **Pairing link delivery mechanics:** Android App Link (https, requires domain + asset-links verification) vs. custom URI scheme (simpler, less robust if app isn't installed yet). Do we need a "not installed yet → Play Store" fallback, or is manual "install the app first, then tap the link" acceptable given it's just two people?
4. **Re-pairing / overwrite behavior:** what happens if the link is opened a second time, on a third device, or after the receiving device already has a different pairing stored? Silent overwrite, confirmation prompt, or hard block?
5. **Service selection under a single default room:** does pairing also fix the streaming service (Hotstar/Netflix/etc.), or does the room stay service-agnostic and either person can still switch services per session as today?
6. **Notification-pool vs. welcome-message-pool:** same list or two independent lists? Who can see/edit whose messages — can each person only add to "their own" pool that's shown to the *other* person, or is it a single shared pool either can edit?
7. **Theme persistence semantics:** does an explicit choice stick forever until someone changes it again, or does the daily auto-rotation resume the next day absent a new explicit choice?
8. **Fate of existing personalization:** `Personalization.kt`'s hardcoded names, `FlirtyLines.kt`'s static splash pool, and the multi-room `RoomsHomeActivity` UI — replaced outright, or kept as fallback/defaults when no pairing exists yet?

## 7. Out of scope (unless told otherwise)

- Multi-couple / multi-room support (explicitly being removed, not generalized).
- Chrome extension, bookmarklet, and `room.html` web client changes.
- Voice chat (LiveKit) changes — unaffected by this redesign.
- Any web-based (non-Android) pairing flow.

## 8. Success criteria (draft — refine in planning)

- A fresh install on the boyfriend's phone requires no room code entry; he only enters his partner's details and gets a shareable link.
- Opening that link on the girlfriend's phone fully configures her app (name, pairing/room, no prompts) with zero manual steps beyond tapping the link and having the app installed.
- Neither user ever sees a "create room" / "join room by code" / browse-rooms screen again.
- Either user can send a flirty push notification inviting the other to watch, using text pulled from an editable pool, and it's deliverable even if the recipient's app is closed.
- Either user picking a theme in Settings changes both devices' UI accent.
- Either user can add a new welcome message from Settings, and the other person sees it (trigger condition TBD) after that.
