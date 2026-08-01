# Share invite link from Rooms Home

## Problem

Tapping "Invite them" on `RoomsHomeActivity` always calls `PairingApi.triggerNudge()`, which the
server correctly rejects with `409 {"ok":false,"reason":"no-partner"}` when nobody has redeemed
the room's invite yet (`room.partnerDeviceId == null`). The app surfaces this as a generic
`"Couldn't reach them right now — try again later"` toast, which reads like a bug rather than "no
partner has joined yet."

The actual invite-link flow already exists twice — `WelcomeSetupActivity.mintInviteAndShare()`
(first-run onboarding) and `SettingsActivity.repair()` (buried in Settings) — but there is no way
to re-share the invite link from the Rooms Home screen, which is where a user naturally looks for it.

## Design

### Behavior

`RoomsHomeActivity`'s single button (`btnInviteNow`) becomes context-aware, driven by the
`partnerPaired` boolean already computed in `refreshPairingStatus()`
([RoomsHomeActivity.kt:169](../../../android/app/src/main/kotlin/com/buddiestime/watchparty/RoomsHomeActivity.kt)):

- **No partner yet** (`currentRoom?.partnerDeviceId == null`, including the case where
  `currentRoom` hasn't loaded): mint a fresh invite token and open the Android share sheet.
  Button label: **"Share invite link"**.
- **Partner already paired**: send the nudge push (existing behavior, unchanged).
  Button label: **"Nudge them 💌"**.

No new server calls are needed to pick the mode — the existing `getRoom` poll in
`refreshPairingStatus()` already supplies `partnerDeviceId`.

### Shared helper

The "mint invite token → build `Intent.ACTION_SEND` chooser" logic currently exists twice
(`WelcomeSetupActivity.mintInviteAndShare`, `SettingsActivity.repair`) and would become a third
copy in `RoomsHomeActivity`. Extract it into one shared helper and use it in all three places.

New file: `android/app/src/main/kotlin/com/buddiestime/watchparty/InviteSharing.kt`

```kotlin
object InviteSharing {
    /** Mints a fresh invite token for [roomId] and opens the Android share sheet with the link.
     *  Invalidates any previously unredeemed invite for the room (server-side behavior). */
    fun mintAndShare(
        activity: Activity,
        api: PairingApi,
        roomId: String,
        deviceId: String,
        pin: String?,
        onError: (String?) -> Unit,
    ) {
        api.mintInvite(roomId, deviceId, pin) { token, error ->
            if (token == null) {
                onError(error)
                return@mintInvite
            }
            val link = "${Config.baseHttpUrl()}/pair/$roomId/$token"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Join our watch party 💗 $link")
            }
            activity.startActivity(Intent.createChooser(shareIntent, "Send the invite"))
        }
    }
}
```

Callers stay responsible for their own UI (busy state, toasts, `runOnUiThread`, post-share
navigation) — `InviteSharing` only does the mint + share-intent part, since that's the part that
was actually duplicated. `WelcomeSetupActivity.mintInviteAndShare` and `SettingsActivity.repair`
call this instead of duplicating the token-mint + intent-build.

### RoomsHomeActivity changes

- `onCreate`: click listener on `btnInviteNow` calls a new dispatcher, e.g. `onInviteButtonTapped()`.
- `onInviteButtonTapped()`: reads `currentRoom?.partnerDeviceId`; if `null`, calls
  `InviteSharing.mintAndShare(...)` with an `onError` that shows
  `"Couldn't create a new invite — try again"`; otherwise calls the existing
  `api.triggerNudge(...)` flow unchanged.
- `refreshPairingStatus()`: in addition to updating `tvPairingStatus`, also sets
  `btnInviteNow.text` to `"Share invite link"` or `"Nudge them 💌"` based on `partnerPaired`.
- Before `refreshPairingStatus()` resolves for the first time (`currentRoom == null`), the button
  keeps its default label/behavior from the layout, which should be **"Share invite link"** (the
  safe default — nudging with no known partner is guaranteed to fail, sharing is always valid).

### Error handling

- Mint failure (network / `store-unavailable`): toast `"Couldn't create a new invite — try again"`.
- Nudge failure: unchanged existing toast (`"Couldn't reach them right now — try again later"`).
  This path is now unreachable in the `no-partner` case specifically, since the button won't call
  nudge unless a partner is already known to be paired — but other nudge failure reasons
  (`no-push-token`, `quiet-hours`, `no-messages`, `push-failed`) remain possible and keep the
  existing toast.

### Testing

Manual, using the existing two-emulator setup (`tools/emu.js`):

1. Fresh room, no partner paired. Tap the button on host → confirm label reads "Share invite
   link" and the Android share chooser opens with a `/pair/{roomId}/{token}` link (verify via
   `emu logcat PAIR` showing `POST /api/rooms/.../invite → 200`).
2. Redeem that link on the guest emulator (`PairingRedeemActivity` deep link).
3. Return to host Rooms Home (`onResume` → `refreshPairingStatus`) → confirm label flips to
   "Nudge them 💌".
4. Tap again → confirm `POST /api/rooms/.../nudge` fires and no longer returns `no-partner`.
5. Spot-check `WelcomeSetupActivity` and `SettingsActivity` invite flows still work unchanged
   after the extraction (they're behaviorally identical, just calling the shared helper).

## Out of scope

- Changing the nudge failure copy/behavior for reasons other than `no-partner`.
- Any change to the server-side `pairing.js` — `mintInvite`/`triggerNudge` semantics are unchanged.
- Re-inviting a _new_ device when a partner is already paired (not a requested use case here).
