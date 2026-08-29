#!/bin/bash
# Chat overlay "last 5 visible, rest scrollable" regression test.
#
# Reproduces: open a live party on an emulator, send 8+ chat messages, confirm
# the input row never gets pushed off-screen and only the newest 5 bubbles show.
#
# Prerequisites:
#   - ANDROID_AVD_HOME / ANDROID_SDK_ROOT pointing at the SDK (see android/build_apk.bat)
#   - An emulator AVD already paired to a room in the app (RoomsHomeActivity shows
#     "Start our movie night" instead of the setup flow) — this script does not
#     handle first-time pairing.
#   - The Fluffles debug APK installed on the target emulator.
#
# Usage:
#   ./chat-overlay-last5-test.sh [device-serial]
#
# Output: screenshots under tests/screenshots-chat-overlay/

set -e

ADB="${ANDROID_SDK_ROOT:-/d/devcache/Android/Sdk}/platform-tools/adb.exe"
DEVICE="${1:-emulator-5554}"
PKG="com.fluffles.watchparty"
ACTIVITY="com.buddiestime.watchparty.RoomsHomeActivity"
OUT_DIR="$(dirname "$0")/screenshots-chat-overlay"
mkdir -p "$OUT_DIR"

adb() { "$ADB" -s "$DEVICE" "$@"; }

echo "[1/5] Launching app on $DEVICE"
adb shell am start -n "$PKG/$ACTIVITY"
sleep 5
adb exec-out screencap -p > "$OUT_DIR/01-launch.png"

echo "[2/5] Starting the movie night (tap 'Start our movie night')"
adb shell input tap 460 830
sleep 6

echo "[3/5] Picking a streaming service (YouTube, top-right tile)"
adb shell input tap 668 897
sleep 8
adb exec-out screencap -p > "$OUT_DIR/02-connected.png"

echo "[4/5] Sending 8 chat messages (each open->type->send cycle; the panel"
echo "      auto-collapses after every send by design)"
send_msg() {
  adb shell input tap 1286 2630   # chat FAB
  sleep 1
  adb shell input tap 524 1521    # message input field
  sleep 1
  adb shell input text "Msg_$1"
  sleep 1
  adb shell input keyevent 66     # Enter -> send
  sleep 1
}
for i in 1 2 3 4 5 6 7 8; do
  send_msg "$i"
done

echo "[5/5] Reopening chat to verify only the last 5 messages are visible"
echo "      and the input row is still reachable"
adb shell input tap 1286 2630
sleep 2
adb exec-out screencap -p > "$OUT_DIR/03-after-8-messages.png"

echo ""
echo "Done. Inspect $OUT_DIR/03-after-8-messages.png:"
echo "  - PASS: bubbles show only Msg_4..Msg_8, 'say something sweet...' input visible"
echo "  - FAIL: all 8 bubbles stacked, input pushed off-screen"
