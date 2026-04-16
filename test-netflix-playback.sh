#!/bin/bash

# Netflix Playback Test
# Opens app, clicks Netflix, plays video, and verifies playback
# Usage: bash test-netflix-playback.sh

ADB="${HOME}/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APP_PACKAGE="com.buddiestime.watchparty"
APP_ACTIVITY=".ServiceSelectorActivity"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="test-netflix-playback-${TIMESTAMP}.log"

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║         Netflix Playback Test Suite                        ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Initialize counters
TESTS=0
PASSED=0

# Helper function
test_check() {
    local test_name="$1"
    local condition="$2"

    ((TESTS++))
    if eval "$condition"; then
        echo -e "${GREEN}✓ PASS${NC} [$TESTS] $test_name"
        ((PASSED++))
        return 0
    else
        echo -e "${RED}✗ FAIL${NC} [$TESTS] $test_name"
        return 1
    fi
}

echo "────────────────────────────────────────────────────────────"
echo "[Step 1] App Management"
echo "────────────────────────────────────────────────────────────"

# Stop if running, then start
$ADB shell am force-stop "$APP_PACKAGE" 2>/dev/null || true
sleep 1

echo "Starting app..."
$ADB shell am start -n "$APP_PACKAGE/$APP_ACTIVITY" > /dev/null 2>&1
sleep 4

test_check "App started" "$ADB shell pidof $APP_PACKAGE > /dev/null"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 2] Netflix Selection"
echo "────────────────────────────────────────────────────────────"

echo "Clicking Netflix tile (coordinates: 720, 1200)..."
$ADB shell input tap 720 1200
sleep 8

test_check "Netflix UI loaded" "$ADB logcat -d -s chromium:I | grep -q 'Meta Pixel'"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 3] Video Load Verification"
echo "────────────────────────────────────────────────────────────"

echo "Checking for video element..."
test_check "Video element found" "$ADB logcat -d -s chromium:I | grep -q '\[HWP\] video found'"

echo "Checking video readiness..."
test_check "Video ready (readyState=4)" "$ADB logcat -d -s chromium:I | grep -q 'readyState=4'"

echo "Checking region setting..."
test_check "Language set to en-IN" "$ADB logcat -d -s chromium:I | grep -q 'Language set to en-IN'"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 4] Playback Controls"
echo "────────────────────────────────────────────────────────────"

echo "Looking for play/pause controls..."
test_check "Play/Pause events detected" "$ADB logcat -d -s chromium:I | grep -q '\[HWP\].*event'"

echo "Checking for video sources..."
test_check "Video source loaded (blob)" "$ADB logcat -d -s chromium:I | grep -q 'blob:https://www.netflix.com'"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 5] Playback Simulation"
echo "────────────────────────────────────────────────────────────"

echo "Attempting to click play button..."
$ADB shell input tap 360 600
sleep 3

echo "Checking if content is interactive..."
test_check "Video responds to input" "$ADB logcat -d -s chromium:I | grep -q 'pause event\|play event'"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 6] Detailed Log Output"
echo "────────────────────────────────────────────────────────────"
echo ""

echo -e "${YELLOW}Last 10 HWP Events:${NC}"
$ADB logcat -d -s "chromium:I" | grep "\[HWP\]" | tail -10 | sed 's/^/  /'

echo ""
echo -e "${YELLOW}Video Metadata:${NC}"
$ADB logcat -d -s "chromium:I" | grep "video found" | tail -3 | sed 's/^/  /'

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 7] Final Results"
echo "────────────────────────────────────────────────────────────"
echo ""

# Print summary
FAILED=$((TESTS - PASSED))
echo -e "Total Tests: ${BLUE}${TESTS}${NC}"
echo -e "Passed:      ${GREEN}${PASSED}${NC}"
echo -e "Failed:      ${RED}${FAILED}${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  ✓ ALL TESTS PASSED - Netflix playback is working!         ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
    exit 0
else
    echo -e "${RED}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║  ✗ SOME TESTS FAILED - Check output above                 ║${NC}"
    echo -e "${RED}╚════════════════════════════════════════════════════════════╝${NC}"
    exit 1
fi
