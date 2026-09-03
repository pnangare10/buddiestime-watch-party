#!/usr/bin/env bash
# Publish a release: bump the version, build a signed APK, verify it, then create the
# GitHub Release that phones actually update from.
#
# Ordering is deliberate. The bump is committed and pushed BEFORE the build, and the built
# APK's real versionCode is verified against version.properties BEFORE the release is
# created. Doing it the other way round lets a release be published whose `version-code`
# line disagrees with the binary it ships — a newer release advertising an older code,
# which no client can recover from on its own.
set -euo pipefail

cd "$(dirname "$0")"

MIN_SUPPORTED="${MIN_SUPPORTED:-0}"

# ── preflight ───────────────────────────────────────────────────────────────
# google-services.json is untracked. A release built without it silently drops the
# google-services plugin and ships a build where FCM nudges are dead — and it installs
# cleanly over a working one, so nothing would reveal the mistake until a nudge failed.
[ -f app/google-services.json ] || { echo "FATAL: app/google-services.json missing — FCM would be dead in this build"; exit 1; }

for v in KEYSTORE_PATH STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  [ -n "${!v:-}" ] || { echo "FATAL: $v is not set — release signing would fail or produce a debug-signed APK"; exit 1; }
done

[ -z "$(git status --porcelain -- . ../server)" ] || { echo "FATAL: working tree is dirty — commit first so the release matches a known commit"; exit 1; }

command -v gh >/dev/null || { echo "FATAL: gh CLI not found"; exit 1; }

# ── bump ────────────────────────────────────────────────────────────────────
CURRENT=$(grep '^versionCode=' version.properties | cut -d= -f2)
NEXT=$((CURRENT + 1))
VERSION_NAME="${VERSION_NAME:-1.$NEXT}"

printf 'versionCode=%s\nversionName=%s\n' "$NEXT" "$VERSION_NAME" > version.properties
echo "→ versionCode $CURRENT → $NEXT (versionName $VERSION_NAME)"

git add version.properties
git commit -m "chore(android): release v$VERSION_NAME (versionCode $NEXT)"
git push origin HEAD

# ── build ───────────────────────────────────────────────────────────────────
# NOTE: Gradle cannot be launched from an agent session on this machine (AF_UNIX loopback
# failure); run this script from your own terminal, or via a Windows Scheduled Task.
./gradlew clean assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "FATAL: $APK was not produced"; exit 1; }

# ── verify the binary matches the metadata ──────────────────────────────────
AAPT=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/ | sort | tail -1)aapt2
BUILT=$("$AAPT" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
[ "$BUILT" = "$NEXT" ] || { echo "FATAL: APK reports versionCode $BUILT but version.properties says $NEXT"; exit 1; }
echo "→ verified APK versionCode=$BUILT"

# ── publish ─────────────────────────────────────────────────────────────────
# The body is the update policy: the server parses these two lines. min-supported must
# never exceed version-code — the server drops the forcing flag if it does, but failing
# here is clearer than silently shipping a release whose policy was ignored.
[ "$MIN_SUPPORTED" -le "$NEXT" ] || { echo "FATAL: MIN_SUPPORTED ($MIN_SUPPORTED) exceeds this release ($NEXT)"; exit 1; }

NOTES="${NOTES:-Bug fixes and improvements.}"
gh release create "v$VERSION_NAME" "$APK#fluffles-v$VERSION_NAME.apk" \
  --title "v$VERSION_NAME" \
  --notes "$(printf 'version-code: %s\nmin-supported: %s\n\n%s\n' "$NEXT" "$MIN_SUPPORTED" "$NOTES")"

echo "✓ published v$VERSION_NAME — phones will see it within the server's 15-minute cache window"
