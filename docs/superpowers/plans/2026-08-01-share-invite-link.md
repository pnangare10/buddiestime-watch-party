# Share Invite Link from Rooms Home — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `RoomsHomeActivity`'s single invite button context-aware — share the invite link when no partner has joined yet, nudge the partner once they have — and stop the three copies of "mint invite → build share intent" from becoming a fourth.

**Architecture:** Extract the mint-invite-and-share logic (currently duplicated in `WelcomeSetupActivity` and `SettingsActivity`) into one shared `InviteSharing.mintAndShare()` helper. All three activities call it. `RoomsHomeActivity` additionally reads `currentRoom?.partnerDeviceId` (already populated by its existing `refreshPairingStatus()` poll) to decide which behavior + button label to show.

**Tech Stack:** Kotlin, Android (`androidx.appcompat`, Material Components), OkHttp-backed `PairingApi`. No unit test framework exists in this repo (per `CLAUDE.md`) — verification is manual, via the two-emulator setup already configured in `tools/emu.config.json` and driven with `tools/emu.js`.

## Global Constraints

- Do not change server-side `pairing.js` — `mintInvite`/`triggerNudge` semantics are unchanged (spec: Out of scope).
- Do not change nudge failure copy/behavior for reasons other than the `no-partner` path becoming unreachable (spec: Error handling).
- Gradle cannot run in this session (`Unable to establish loopback connection` — see project memory `gradle-cannot-run-in-this-env`). The final build/install step in Task 5 must be run by the user in their own terminal, not attempted here.
- Package is `com.fluffles.watchparty`; all activity/source files live under namespace `com.buddiestime.watchparty` (see `tools/emu.js` PKG/NS constants) — new files go in `android/app/src/main/kotlin/com/buddiestime/watchparty/`.

---

### Task 1: Create the shared `InviteSharing` helper

**Files:**

- Create: `android/app/src/main/kotlin/com/buddiestime/watchparty/InviteSharing.kt`

**Interfaces:**

- Consumes: `PairingApi.mintInvite(roomId: String, deviceId: String, pin: String?, cb: (token: String?, error: String?) -> Unit)` ([PairingApi.kt:118](../../../android/app/src/main/kotlin/com/buddiestime/watchparty/PairingApi.kt)); `Config.baseHttpUrl(): String` ([Config.kt:6](../../../android/app/src/main/kotlin/com/buddiestime/watchparty/Config.kt)).
- Produces: `InviteSharing.mintAndShare(activity: Activity, api: PairingApi, roomId: String, deviceId: String, pin: String?, onDone: (error: String?) -> Unit)` — Tasks 2, 3, and 4 all call this. `onDone` fires exactly once: `null` after the share sheet has been launched (success), or the mint failure reason (non-null) if minting failed. The caller owns busy-state and toast copy in both cases.

- [ ] **Step 1: Write the file**

```kotlin
package com.buddiestime.watchparty

import android.app.Activity
import android.content.Intent

/**
 * Mints a fresh invite token for [roomId] and opens the Android share sheet with the link.
 * Minting a new invite invalidates any previously unredeemed one for the room (server-side
 * behavior in pairing.js mintInvite). [onDone] is called exactly once: with `null` after the
 * share sheet has been launched, or with the failure reason if minting failed.
 */
object InviteSharing {
    fun mintAndShare(
        activity: Activity,
        api: PairingApi,
        roomId: String,
        deviceId: String,
        pin: String?,
        onDone: (error: String?) -> Unit,
    ) {
        api.mintInvite(roomId, deviceId, pin) { token, error ->
            if (token == null) {
                onDone(error)
                return@mintInvite
            }
            val link = "${Config.baseHttpUrl()}/pair/$roomId/$token"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Join our watch party 💗 $link")
            }
            activity.startActivity(Intent.createChooser(shareIntent, "Send the invite"))
            onDone(null)
        }
    }
}
```

- [ ] **Step 2: Self-check — confirm the new file matches call sites planned in Tasks 2-4**

Read the file back and confirm:

- Package is `com.buddiestime.watchparty` (matches every other file in the directory).
- The `mintAndShare` signature exactly matches what Tasks 2, 3, and 4 call (5 positional args + trailing lambda `onDone: (String?) -> Unit`).

There is no compiler available in this session to check this automatically (Gradle cannot run
here — see Global Constraints). Task 5's manual build is the real compile check for all four
source-file changes at once.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/InviteSharing.kt
git commit -m "feat(android): add shared InviteSharing.mintAndShare helper"
```

---

### Task 2: Refactor `WelcomeSetupActivity` to use `InviteSharing`

**Files:**

- Modify: `android/app/src/main/kotlin/com/buddiestime/watchparty/WelcomeSetupActivity.kt:144-165`

**Interfaces:**

- Consumes: `InviteSharing.mintAndShare(...)` from Task 1.

- [ ] **Step 1: Replace `mintInviteAndShare`**

Replace the existing method body (currently lines 144-165, the whole `private fun mintInviteAndShare(...)` function) with:

```kotlin
    private fun mintInviteAndShare(roomId: String, deviceId: String, pin: String?) {
        setBusy(true)
        InviteSharing.mintAndShare(this, api, roomId, deviceId, pin) { error ->
            runOnUiThread {
                setBusy(false)
                if (error != null) {
                    Log.w(TAG, "mintInvite failed: $error")
                    Toast.makeText(this, "Room created, but the invite link failed — you can re-invite from Settings", Toast.LENGTH_LONG).show()
                }
                goToHome()
            }
        }
    }
```

This preserves the exact original behavior: `goToHome()` runs in both the success and failure
case (it did in the original code too — the failure branch called it directly, the success
branch called it after starting the share intent). The only change is that the mint-and-share
mechanics now live in `InviteSharing`.

- [ ] **Step 2: Remove the now-unused `Intent` import if nothing else in the file uses it**

Check whether `Intent` is still used elsewhere in `WelcomeSetupActivity.kt` (it is — `goToHome()`
uses `Intent(this, RoomsHomeActivity::class.java)` and `requestNotificationPermissionIfNeeded`
does not, but `goToHome` does). Keep the `import android.content.Intent` line — do not remove it.

- [ ] **Step 3: Self-check**

Read the modified file and confirm:

- No reference to `Intent.ACTION_SEND`, `Intent.EXTRA_TEXT`, or `Intent.createChooser` remains in this file (that logic now lives only in `InviteSharing`).
- `Config` is no longer referenced in this function (it was only used to build the link, which `InviteSharing` now does) — check if `Config` is still used elsewhere in the file before removing any import; it is not imported by name in this file (`Config.baseHttpUrl()` was called via the object directly, no import needed), so no import changes are required here.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/WelcomeSetupActivity.kt
git commit -m "refactor(android): WelcomeSetupActivity uses shared InviteSharing helper"
```

---

### Task 3: Refactor `SettingsActivity.repair()` to use `InviteSharing`

**Files:**

- Modify: `android/app/src/main/kotlin/com/buddiestime/watchparty/SettingsActivity.kt:87-107`

**Interfaces:**

- Consumes: `InviteSharing.mintAndShare(...)` from Task 1.

- [ ] **Step 1: Replace `repair()`**

Replace the existing method body (currently lines 87-107, the whole `private fun repair()`
function) with:

```kotlin
    private fun repair() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "repair roomId=$roomId")
        InviteSharing.mintAndShare(this, api, roomId, deviceId, null) { error ->
            runOnUiThread {
                if (error != null) {
                    Log.w(TAG, "repair failed: $error")
                    Toast.makeText(this, "Couldn't create a new invite — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
```

- [ ] **Step 2: Self-check**

Read the modified file and confirm no reference to `Intent.ACTION_SEND`, `Intent.EXTRA_TEXT`, or
`Intent.createChooser` remains in `SettingsActivity.kt`. The `import android.content.Intent` line
is still needed elsewhere in the file (`wireNotifications`/permission requests do not use `Intent`,
but check before removing) — grep the file for `Intent(` after this edit; if zero matches remain,
remove the unused `import android.content.Intent` line, otherwise leave it.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/SettingsActivity.kt
git commit -m "refactor(android): SettingsActivity.repair uses shared InviteSharing helper"
```

---

### Task 4: Make `RoomsHomeActivity`'s invite button context-aware

**Files:**

- Modify: `android/app/src/main/kotlin/com/buddiestime/watchparty/RoomsHomeActivity.kt:66, 157-199`
- Modify: `android/app/src/main/res/layout/activity_rooms_home.xml:62-66`

**Interfaces:**

- Consumes: `InviteSharing.mintAndShare(...)` from Task 1; `RoomView.partnerDeviceId: String?` ([PairingModels.kt:11](../../../android/app/src/main/kotlin/com/buddiestime/watchparty/PairingModels.kt)); `PairingApi.triggerNudge(roomId: String, deviceId: String, cb: (Boolean) -> Unit)` ([PairingApi.kt:181](../../../android/app/src/main/kotlin/com/buddiestime/watchparty/PairingApi.kt)).
- Produces: nothing consumed by later tasks (this is the last code task).

- [ ] **Step 1: Update the layout's default button text**

In `activity_rooms_home.xml`, change the button's default text (currently line 66) from:

```xml
        android:text="💌 Invite them now" />
```

to:

```xml
        android:text="📤 Share invite link" />
```

This is the label shown before `refreshPairingStatus()` resolves for the first time — per the
spec, "sharing is always valid" is the safe default, so the button should not claim to nudge
before pairing state is known.

- [ ] **Step 2: Change the click listener wiring in `onCreate`**

In `RoomsHomeActivity.kt`, replace line 66:

```kotlin
        findViewById<MaterialButton>(R.id.btnInviteNow).setOnClickListener { inviteNow() }
```

with:

```kotlin
        findViewById<MaterialButton>(R.id.btnInviteNow).setOnClickListener { onInviteButtonTapped() }
```

- [ ] **Step 3: Update `refreshPairingStatus()` to also set the button label**

In `RoomsHomeActivity.kt`, the current `refreshPairingStatus()` body (lines 157-183) sets
`tvPairingStatus` based on `partnerPaired`. Add one line setting the button text right after it.
The full updated method:

```kotlin
    private fun refreshPairingStatus() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "refreshPairingStatus roomId=$roomId")
        api.getRoom(roomId, deviceId) { room, error ->
            runOnUiThread {
                if (room == null) {
                    Log.w(TAG, "refreshPairingStatus failed: $error")
                    findViewById<TextView>(R.id.tvPairingStatus).text = "Couldn't reach the server — pull to refresh"
                    return@runOnUiThread
                }
                currentRoom = room
                val partnerPaired = room.partnerDeviceId != null
                Log.d(TAG, "refreshPairingStatus room=${room.roomName} partnerPaired=$partnerPaired")
                findViewById<TextView>(R.id.tvPairingStatus).text = if (partnerPaired) {
                    "💗 ${room.roomName} — you're both paired up"
                } else {
                    "💗 ${room.roomName} — waiting for your partner to join"
                }
                findViewById<MaterialButton>(R.id.btnInviteNow).text = if (partnerPaired) {
                    "💌 Nudge them"
                } else {
                    "📤 Share invite link"
                }

                profileStore.cacheWelcomeMessages(
                    room.welcomeMessages.filter { it.authorDeviceId != deviceId }.map { it.text }
                )
                profileStore.cacheTheme(room.theme)
            }
        }
    }
```

(Only the `findViewById<MaterialButton>(R.id.btnInviteNow).text = ...` block and this comment are
new; everything else in the method is unchanged from the current file — reproduced here in full
since this is a whole-method replacement.)

- [ ] **Step 4: Replace `inviteNow()` with the context-aware dispatcher + two named actions**

Replace the current `private fun inviteNow() { ... }` (lines 185-199) with:

```kotlin
    private fun onInviteButtonTapped() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        if (currentRoom?.partnerDeviceId == null) {
            shareInviteLink(roomId, deviceId)
        } else {
            nudgePartner(roomId, deviceId)
        }
    }

    private fun shareInviteLink(roomId: String, deviceId: String) {
        Log.d(TAG, "shareInviteLink roomId=$roomId")
        InviteSharing.mintAndShare(this, api, roomId, deviceId, null) { error ->
            runOnUiThread {
                if (error != null) {
                    Log.w(TAG, "shareInviteLink failed: $error")
                    Toast.makeText(this, "Couldn't create a new invite — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun nudgePartner(roomId: String, deviceId: String) {
        Log.d(TAG, "nudgePartner roomId=$roomId")
        api.triggerNudge(roomId, deviceId) { ok ->
            runOnUiThread {
                Log.d(TAG, "nudgePartner result ok=$ok")
                Toast.makeText(
                    this,
                    if (ok) "Nudge sent 💌" else "Couldn't reach them right now — try again later",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
```

- [ ] **Step 5: Self-check**

Read the full modified `RoomsHomeActivity.kt` and confirm:

- No reference to `inviteNow()` remains anywhere in the file (the old name).
- `currentRoom` is read (not reassigned) in `onInviteButtonTapped()` — it's only ever assigned in `refreshPairingStatus()`.
- The click listener in `onCreate` calls `onInviteButtonTapped()`, matching the new function name exactly.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/RoomsHomeActivity.kt android/app/src/main/res/layout/activity_rooms_home.xml
git commit -m "feat(android): context-aware invite button — share link vs nudge partner"
```

---

### Task 5: Build, install, and verify on the two-emulator setup

**Files:** none (verification only)

**Interfaces:** none — this task only exercises the code from Tasks 1-4 end to end.

Gradle cannot run in this session (see Global Constraints), so this task's build step must be
run by the user, not attempted here.

- [ ] **Step 1: Ask the user to build the APK**

Ask the user to run this in their own terminal (not this session):

```bash
cd android && ./gradlew assembleDebug
```

Wait for them to confirm it succeeded before continuing.

- [ ] **Step 2: Install on both emulators**

```bash
cd "C:/Users/prane_ii3rizl/Pranesh Projects/hotstar-watch-party" && node tools/emu.js devices
```

Expected: both `emulator-5554 [host]` and `emulator-5556 [guest]` listed online (matching
`tools/emu.config.json`). Then install the freshly built APK on both:

```bash
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r android/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Verify the "no partner yet" state shows the share button**

On the host emulator, launch the app fresh into a new room (or reuse an existing unpaired room),
then check the button text and tap it:

```bash
node tools/emu.js ui
```

Expected: a node reading `... "📤 Share invite link" <tap>` (the `MaterialButton` — via `ui`,
which works fine on this screen per `tools/emu.js`'s L033 note, since Rooms Home has no
sub-second timer).

```bash
node tools/emu.js tap '~share invite'
node tools/emu.js logcat PAIR -n10
```

Expected: a fresh line `POST /api/rooms/<roomId>/invite → 200 {"ok":true,"token":"..."}` and the
Android share chooser visible (confirm with `node tools/emu.js shot` since the chooser is a
system UI overlay `ui` may not label usefully).

- [ ] **Step 4: Redeem the invite on the guest emulator**

Extract the `roomId` and `token` from the logcat line above and build the deep link
`http://<server>/pair/<roomId>/<token>` (or use whatever share target is easiest in the emulator,
e.g. copy the link via `adb shell am start -a android.intent.action.VIEW -d "<link>"` on the guest
device):

```bash
adb -s emulator-5556 shell am start -a android.intent.action.VIEW -d "<the link from step 3>"
```

Expected: `PairingRedeemActivity` opens on the guest and completes redemption. Confirm via:

```bash
node tools/emu.js logcat "redeemInvite" -n10
```

Expected: `[PAIRING] redeemInvite roomId=... newPartner=...` on the server side (visible in the
host device's logcat since `PairingApi` calls go through the same server, but the confirming line
to check is the guest's own `HWP-PAIR-API` log showing a `200` on the redeem call).

- [ ] **Step 5: Verify the button flips to "Nudge them" on the host**

Background the host app and bring it back to foreground (or re-launch `RoomsHomeActivity`) to
trigger `onResume` → `refreshPairingStatus()`:

```bash
node tools/emu.js key app_switch
node tools/emu.js key back
node tools/emu.js ui
```

Expected: the button node now reads `... "💌 Nudge them" <tap>` and `tvPairingStatus` reads
`...you're both paired up`.

- [ ] **Step 6: Verify the nudge path actually fires**

```bash
node tools/emu.js tap '~nudge'
node tools/emu.js logcat "nudge" -n10
```

Expected: `POST /api/rooms/<roomId>/nudge → 200 {"ok":true}` (or a specific non-`no-partner`
reason like `no-push-token`/`quiet-hours` if the guest hasn't registered for push in this test
run — either is fine, since the point of this task is confirming `no-partner` no longer fires now
that a partner is paired).

- [ ] **Step 7: Spot-check the two other invite flows still work**

Uninstall and reinstall fresh (or use a brand-new room) to exercise
`WelcomeSetupActivity.mintInviteAndShare` end to end, and separately open `SettingsActivity` on an
already-paired room and tap "Repair" to exercise `SettingsActivity.repair()`. Both should still
mint a token, log a `200` on `/invite`, and open the share chooser — identical to their
pre-refactor behavior, just routed through `InviteSharing` now.

- [ ] **Step 8: Final commit (if any fixups were needed during verification)**

If Steps 1-7 required no code changes, there is nothing to commit here — Tasks 1-4 already
committed everything. If verification surfaced a bug, fix it, re-run the relevant step above, then
commit the fix with a message describing what verification caught.
