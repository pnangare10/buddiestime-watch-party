# Transparent Floating Chat Overlay (Android)

**Date:** 2026-06-29
**Branch:** `feature/transparent-chat-overlay`
**Surface:** Android app (`android/`, Kotlin) — `MainActivity` over the streaming `WebView`
**Status:** Design approved; ready for implementation plan.

## Goal

Replace the modal chat dialog (`dialog_chat.xml` / `openChatDialog()`) with a transparent,
in-Activity overlay drawn on top of the streaming `WebView`. The video stays fully visible;
the only thing that ever obscures it is the system soft keyboard while composing.

Backend messaging is unchanged: `WatchPartyManager.sendChat(text)` and the
`onChatMessage(ChatMessage(from, name, text, ts))` callback already exist. This is a UI/UX
layer only — **`WatchPartyManager.kt` is not modified.**

## Non-goals

- No system overlay (`SYSTEM_ALERT_WINDOW`) — stays in-Activity.
- No push/notification when the app is backgrounded.
- No avatars, message grouping, or rate-limiting (possible future work).
- No changes to the WebSocket protocol or the server.

## States

### Ambient (default)

- Only the **single latest** message floats at **bottom-left**: `FirstName: text`.
- White text with a dark text-shadow for legibility over arbitrary video frames; fully
  transparent background.
- **Auto-fades after `AMBIENT_FADE_MS = 5000`.** A new message re-shows and resets the timer.
- Fade is driven by a single `Handler` + a single `Runnable` token; `removeCallbacks(token)`
  is called before every reschedule so a stale fade can never erase a newer message.

### Expanded (tap `fabChat`)

- A transparent, scrollable panel anchored bottom-left showing the **last 5 messages**
  (`MAX_VISIBLE_MESSAGES = 5`) by default.
- **Scrollable upward** to reveal older history (full in-memory history, see Memory below).
  Panel auto-scrolls to the newest message on open and on new message.
- A small transparent **"N in party"** peers count in the panel header, preserving the
  participant awareness the old dialog provided.
- A **transparent input row** (`EditText`, no box/underline) anchored above the soft keyboard.

`fabChat` **toggles** between ambient and expanded via an `isExpanded` flag (the old code
only ever opened the dialog).

## Compose / keyboard flow

1. Tapping the input focuses the `EditText` and shows the soft keyboard.
2. Keyboard positioning uses the IME `WindowInsets` listener (Android 11+,
   `WindowInsetsCompat.Type.ime()`) to translate the input + message panel above the keyboard,
   with `windowSoftInputMode=adjustResize` as the fallback path. **Must be verified on-device
   while video is fullscreen** (see Risks).
3. The `EditText` is transparent — typed text appears directly over the keyboard.
4. `maxLength = 500` enforced (parity with old dialog).
5. Send triggers: `OnEditorActionListener` for `IME_ACTION_SEND` **and** an `OnKeyListener`
   for hardware/Bluetooth Enter (`KEYCODE_ENTER`, action-down, no Shift).
6. On send → **optimistic render** (see below) → **send-and-collapse**: clear input, hide
   keyboard, collapse to ambient. The just-sent message is the new ambient bubble.

## Optimistic rendering (own messages)

To avoid lag and message-loss on the server echo:

- On send, the message is rendered **locally and immediately** (added to history + shown as the
  ambient bubble) — the UI does not wait for the server echo.
- The subsequent server echo of the user's own message is **de-duplicated** (matched by sender
  identity + text + a short time window) so it is not shown twice.
- If the socket is **not open**, the draft is **kept** (input not cleared, overlay not
  collapsed) and a brief inline hint is shown ("Not connected"). Nothing is silently lost.

## Fullscreen / z-order (critical)

Streaming sites call `onShowCustomView`, which sets `webView.visibility = GONE` and adds a
fullscreen view to `fullscreenContainer`. The overlay must:

- Be added to the **root content `FrameLayout` as the last child** (top of z-order), so it
  renders above both the WebView and `fullscreenContainer`.
- Call `bringToFront()` (and re-assert visibility) on entering/exiting fullscreen
  (`onShowCustomView` / `onHideCustomView`).
- `fabChat` and the badge must likewise remain on top in fullscreen.

This must be validated on-device with a real fullscreen video before the feature is considered done.

## First-name rendering

```kotlin
val firstName = name.trim().split(Regex("\\s+")).firstOrNull()
    ?.takeIf { it.isNotBlank() } ?: "Guest"
```

Applies to all senders including self. Name collisions (two "Aman"s) are accepted for now.

## Badge lifecycle

- Increments on incoming messages while **collapsed**.
- **Cleared on expand.**
- If a message arrives while already expanded, no badge (it's visible).

## Memory

- Full `chatHistory` is retained in memory and reachable via scroll in the expanded panel.
- A **rolling cap** (e.g. 200 messages) bounds memory for long parties; oldest are dropped.

## Components

- **Overlay** — implemented as a dedicated block inflated into `activity_main.xml` (testable,
  inspectable) and owned by `MainActivity`. Holds: ambient bubble `TextView`, expanded
  scrollable message container (`NestedScrollView` + `LinearLayout`), peers count, input row.
- **`MainActivity`** — keeps `chatHistory`, `fabChat`, badge, `onChatMessage`; routes rendering
  to the overlay instead of inflating a dialog; owns `isExpanded` + fade `Handler`.
- **`WatchPartyManager`** — unchanged.

### Removed

- `openChatDialog()`, `dialog_chat.xml`, and the dialog-bound `appendChatRow` rendering path.

### Kept

- `fabChat` (now toggles), unread badge, `chatHistory`, all `WatchPartyManager` plumbing,
  participant count display (relocated into the expanded panel).

## Constants (tunable)

| Constant               | Value                                 |
| ---------------------- | ------------------------------------- |
| `AMBIENT_FADE_MS`      | 5000                                  |
| `MAX_VISIBLE_MESSAGES` | 5 (default visible; scroll for older) |
| `MAX_HISTORY`          | 200 (rolling in-memory cap)           |
| `INPUT_MAX_LENGTH`     | 500                                   |

## Testing (manual, on-device)

1. Legibility of white+shadow text over bright and dark video frames (real Hotstar/Netflix content).
2. **Overlay visible while video is fullscreen** (critical).
3. Keyboard raises the input above itself, including in fullscreen, across Android 10/12/14.
4. Enter (soft IME action + hardware Enter) sends and collapses.
5. Optimistic render: own message appears instantly; no duplicate when echo returns;
   disconnected send keeps the draft.
6. Ambient fade timing; rapid back-to-back messages don't erase the newest.
7. First-name extraction with single-word, multi-word, blank, and emoji names.
8. Scroll up in expanded panel reveals older history; auto-scrolls to newest on new message.
9. Badge increments while collapsed, clears on expand.

## Open risks to validate during implementation

- **IME positioning in fullscreen/immersive mode** — behavior of `adjustResize` vs IME insets
  is device/version dependent; needs a real-device pass with a fallback ready.
- **Fullscreen z-order** — confirm the overlay is not occluded by `fullscreenContainer`.
- **Contrast worst-case** — white-on-bright-frame; if shadow proves insufficient on real content,
  consider a very subtle semi-opaque scrim behind text (revisit only if testing shows a problem).
