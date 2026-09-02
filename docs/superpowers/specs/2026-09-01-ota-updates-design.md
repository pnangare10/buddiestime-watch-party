# In-app OTA updates

## Problem

The app is distributed by sideloading over USB. That works for the developer's own phone and not
at all for the partner's, which is the phone that actually needs updating — it can't be plugged
into the build machine. Today a new build reaches it only by physically borrowing it.

Two existing properties make this worse than it looks:

- **`versionCode` is hardcoded to `1`** ([android/app/build.gradle](../../../android/app/build.gradle)) and has never been
  bumped. `adb install -r` tolerates equal version codes, so sideloading hides the problem; any
  real update channel rejects a non-increasing `versionCode`.
- **Builds are signed with a debug key** at `D:\devcache\Android\user-home\debug.keystore`
  (located there because `ANDROID_USER_HOME` points into `devcache`). It is the only copy, and it
  sits in a cache directory. If it is ever cleared, Gradle silently generates a new debug key,
  every subsequent build fails to install over the existing app with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only recovery is uninstalling — destroying the
  local data an update mechanism exists to protect. Debug signing also marks the app `DEBUGGABLE`,
  so anyone with physical access to either phone can attach a debugger and read its data.

Building an update channel on a key that can vanish would bake in the exact failure it is meant to
prevent, so the signing migration is part of this work rather than a follow-up.

## Constraints

**Silent self-update is impossible.** A sideloaded app that installs an APK needs
`REQUEST_INSTALL_PACKAGES`, a one-time "allow from this source" grant in system settings, and then
Android shows its own confirmation dialog for _every_ install. Only device-owner/MDM apps bypass
this. The achievable best case is two taps per update, with data preserved.

**Signature and `applicationId` are the app's identity.** Android replaces an app in place only
when `applicationId` matches, the signing key matches, and `versionCode` is higher. Android
verifies the signature at install time, so an APK signed with a different key cannot silently
replace the app — that check is the integrity guarantee, and it is why the keystore matters more
than any validation in application code.

`applicationId` stays `com.fluffles.watchparty`. The Fluffles rename already cost one re-pair;
changing it again would cost another and buy nothing.

## Decisions

| Question                 | Decision                                                                                            |
| ------------------------ | --------------------------------------------------------------------------------------------------- |
| APK hosting              | GitHub Releases (repo is public, so assets download unauthenticated — no token ships in the app)    |
| Update policy            | Optional by default; forced when the release declares a `min-supported` above the installed version |
| Manifest source of truth | Server reads the GitHub Releases API at request time and caches                                     |
| Signing                  | New release keystore, replacing the debug key (one-time reinstall)                                  |

Forced updates are gated per-release rather than always-on because this app's failure mode is
protocol drift: two clients speaking different versions of the WebSocket protocol. Routine releases
nudge; protocol-breaking releases force.

## Phasing

Design review found that the original single-shipment plan would make the first OTA release also
the first exercise of an install path that had never run — with no working channel left to fix it
if it failed. The work is therefore split, and **Phase 1 ships and is verified on hardware before
Phase 2 begins**.

### Phase 1 — stable identity (no OTA)

1. **Fix `redeemInvite` to support owner recovery.** As written
   ([pairing.js:122](../../../server/pairing.js)) it hardcodes `const replacedRole = "partner"` and always
   assigns `room.partnerDeviceId`, despite a comment claiming it replaces "whichever side isn't the
   requester". A reinstalled owner therefore cannot rejoin: its fresh `deviceId` can only land in
   the partner slot, and `createRoom` cannot rebuild the room either because `reserveRoomName`
   ([pairing.js:43](../../../server/pairing.js)) returns `room-name-taken` for the still-reserved name.
   Without this fix the migration below abandons the room rather than costing one re-pair.
2. **Register the release fingerprint before migrating.** `server.js:66` hardcodes the _debug_
   certificate; release fingerprints reach `assetlinks.json` only through the `ANDROID_CERT_SHA256`
   environment variable. Because `autoVerify="true"` fails silently, an unregistered release key
   sends every `/pair/` invite to the browser — and the migration's recovery step depends on that
   link working. Set the variable and confirm verification **before** uninstalling anything.
3. **Register the FCM token explicitly** whenever a `deviceId` exists, rather than relying on the
   one-shot `onNewToken` callback that currently drops it on first run. See Migration below — a
   reinstall re-rolls the timing gamble this depends on today.
4. **Migrate both phones** to the release keystore and verify pairing, invites, and nudges.

### Phase 2 — OTA on top of a stable identity

Everything below in this document, incorporating these review corrections:

- **`BuildConfig.VERSION_CODE` does not exist.** AGP 8 does not generate `BuildConfig` unless
  `buildFeatures { buildConfig true }` is set, and nothing sets it. Read the installed version from
  `PackageManager.getPackageInfo(...).longVersionCode` instead, which avoids the build-config
  dependency entirely.
- **Use the `PackageInstaller` session API**, not `ACTION_VIEW`/`ACTION_INSTALL_PACKAGE`. The
  intent-based path reports no result, so the client cannot distinguish "user cancelled" from
  "signature mismatch" from "downgrade blocked" — which is exactly the information forced-update
  recovery needs.
- **`REQUEST_INSTALL_PACKAGES` is not a one-time grant.** Android 11+ auto-revokes it under app
  hibernation. Re-check `canRequestPackageInstalls()` before _every_ install attempt.
- **FileProvider authority must be `${applicationId}.fileprovider`.** This repo's `namespace`
  (`com.buddiestime.watchparty`) differs from its `applicationId` (`com.fluffles.watchparty`), and
  the manifest still carries a legacy `package` attribute, so deriving the authority from the
  visible package name yields a runtime failure.
- **Download from `browser_download_url`.** A release asset's `url` field returns JSON metadata
  unless an octet-stream `Accept` header is sent.
- **The server must refuse to emit a forcing flag** unless the release has a downloadable `.apk`
  asset and `minSupported <= versionCode`, and an `OTA_DISABLED` kill switch must exist. A forced
  update pointing at a missing or unusable APK is a two-device outage recoverable only from a
  machine with `gh` access.
- **The forced-update gate cannot live in one Activity.** `RoomsHomeActivity.onCreate` finishes and
  jumps to `WelcomeSetupActivity` when unpaired, `maybeAutoJoin()` can launch `MainActivity` from a
  poll, and `FcmService` and `PairingRedeemActivity` are independent entry points. The check belongs
  in `Application.onCreate` with a shared result consulted by every launcher, and auto-join must be
  suppressed while a forced update is pending.

Two Phase 2 questions remain open and are deliberately not settled here: whether to replace the
GitHub API proxy with a committed `app-version.json` (simpler, and `git revert` becomes the
rollback), and whether forced updates should ship in the first OTA release at all rather than being
added over the existing WebSocket once the plain update path is observed working.

## Design

### Signing and version identity

The release keystore is generated by the developer, who keeps the passwords — they are never
handled by tooling in this repo. It is stored outside the repository and outside `devcache`,
gitignored, and backed up somewhere permanent.

No build restructuring is required: the release `signingConfig` already reads `KEYSTORE_PATH`,
`STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` from the environment and hard-fails release
builds when they are absent.

`versionCode` moves out of `build.gradle` into `android/version.properties`, read at configure
time:

```properties
versionCode=2
versionName=1.1
```

The release script is the only thing that writes this file. Nothing else bumps the version, which
removes the "forgot to bump" failure permanently.

### Release publishing

`android/release.sh` performs the whole release: bump `version.properties`, `assembleRelease`,
then `gh release create` with the APK attached. The release body carries the update policy:

```
version-code: 7
min-supported: 5
```

Publishing a release becomes the only release step. The binary and the policy governing it travel
as a single artifact and cannot disagree.

### Server: `GET /api/app-version`

New module `server/appversion.js`, following the shape of `push.js` and `store.js` — configured
from the environment, exposing a `READY` flag, failing soft when unconfigured.

Configuration is a single variable, `GITHUB_RELEASES_REPO` (e.g. `pncodes10/buddiestime-watch-party`),
defaulting to that value so the endpoint works on a fresh deploy with nothing set. No token is
required or accepted: the repository is public, and unauthenticated GitHub API access allows 60
requests per hour — far above what a 15-minute cache and two phones consume. Keeping the endpoint
tokenless also means no credential is present to leak through it.

It fetches the repository's `releases/latest`, then derives:

- `versionCode` and `minSupported` from the `version-code` / `min-supported` lines in the body
- `versionName` from the tag
- `url` from the release's `.apk` asset
- `notes` from the body with the policy lines stripped

Results are cached in memory for 15 minutes. On a GitHub error the last good answer is served; on
a cold cache the endpoint returns `{"ok": false, "reason": "unavailable"}`.

The endpoint never throws and never blocks. A failing update check must not be able to break the
app — an outage at GitHub should read to the client as "no update available," not as an error.

Malformed or missing `version-code` makes a release invisible to clients rather than crashing the
parser; the release is treated as if it does not exist.

The route lives in `server.js` beside the existing pairing routes.

### App: check, download, install

New `UpdateChecker.kt`, invoked from `RoomsHomeActivity` on start, off the main thread:

- `versionCode > BuildConfig.VERSION_CODE` — dismissible "Update available" card showing the
  release notes
- `minSupported > BuildConfig.VERSION_CODE` — blocking "Update required" screen

Download uses `DownloadManager`; installation uses a `FileProvider` content URI and an install
intent. When `canRequestPackageInstalls()` returns false, the user is routed to the system
"allow from this source" screen first — the one-time grant.

An APK already downloaded for the current target `versionCode` is not fetched again.

Manifest additions: the `REQUEST_INSTALL_PACKAGES` permission, a `FileProvider`, and
`xml/file_paths.xml`.

### Error handling

Every failure in this subsystem degrades to "no update available":

| Failure                             | Behavior                                      |
| ----------------------------------- | --------------------------------------------- |
| GitHub unreachable, cache warm      | Serve last good manifest                      |
| GitHub unreachable, cache cold      | `{"ok": false}`; client shows nothing         |
| Release body missing `version-code` | Release ignored                               |
| Server unreachable from app         | Check skipped silently; app starts normally   |
| Download fails                      | Card remains; retry on next launch            |
| Install rejected by user            | No state change; card remains                 |
| APK signed with wrong key           | Android refuses the install; app is untouched |

## Testing

**Server** — `server/test/appversion.test.js`, using an injected fake fetch, matching the
`node --test` pattern already used across `server/test/`: body parsing, missing and malformed
fields, GitHub 500 falling back to a warm cache, and cold-cache failure.

**Android** — a pure version-comparison unit test in `app/src/test/`, alongside the existing
`PairingModelsTest.kt`.

**End to end, on real hardware** — install vN, publish vN+1, confirm the card appears, the install
succeeds, and `deviceId` and `roomId` survive. This is the behavior the feature exists to deliver
and is not considered verified until observed on a device.

## Migration

Because the signing key changes, both phones need one reinstall. The ordering below is
load-bearing: steps 1–3 must all be verified before step 4, because each one is a prerequisite of
the recovery path that step 5 depends on.

1. **Ship the `redeemInvite` owner-recovery fix** and confirm it against the live server. Without
   it, step 5 cannot restore the owner.
2. **Generate the release keystore**, back it up, and set the signing environment variables. The
   developer holds the passwords; no tooling in this repo stores them.
3. **Set `ANDROID_CERT_SHA256`** on Render to the release fingerprint and confirm App Link
   verification passes (`pm get-app-links com.fluffles.watchparty` reports the domain as verified).
4. **Migrate one phone at a time.** The partner's phone stays paired and functional while the
   owner's is reinstalled, so it can mint the recovery invite. Uninstall, install the release
   build, then redeem the invite minted from the still-paired phone.
5. **Verify** pairing, invites, and a real nudge before migrating the second phone.

What is actually lost: `hwp_prefs` holds `device_id` and `paired_room_id` alongside `profile_self`,
`profile_partner`, `cached_welcome_messages`, `cached_theme_mode`, and `cached_theme_value`, plus
the selected service name. The cached entries are re-fetched from the room on next load, and
profiles are restored from the room's stored copies, so recovery is genuinely complete — but the
claim rests on the owner-recovery fix existing, not on the caches being unimportant.

**The FCM token is a genuine risk in this migration, not a formality.** `FcmService.onNewToken`
is the _only_ code path that ever sends a token to the server, and it drops the token outright when
no local `deviceId` exists yet:

```kotlin
val deviceId = DeviceIdentity(prefs).localDeviceId() ?: run {
    Log.w(TAG, "onNewToken: no local deviceId yet — dropping token, next getRoom/pairing flow will register it")
    return
}
```

No such "next getRoom/pairing flow" exists — nothing else calls `updateProfile` with an `fcmToken`.
On a fresh install, whether the token survives depends purely on whether Firebase happens to mint
it before or after the user finishes pairing. It survived on the current install by luck; a
reinstall re-rolls that dice, and losing it breaks nudges **silently** — the sender still sees a
success, exactly the failure already diagnosed and fixed once in this repo.

Phase 1 therefore also registers the token explicitly: fetch the current token and PATCH it
whenever a `deviceId` exists — on app start and immediately after pairing completes — keeping
`onNewToken` as the refresh path. This is a small change that removes a silent failure the
migration would otherwise gamble on, and a real nudge test remains part of step 5 to confirm it.

This is the last reinstall required — every subsequent release updates in place.

## Out of scope

- Publishing to the Play Store
- Automatic or background installation (impossible without device-owner privileges)
- Delta updates
- Rollback to a previous version
- Centralizing the welcome-message defaults that currently live in `RoomsHomeActivity`
