#!/bin/bash

# Netflix Playback Test
# Opens the app, selects Netflix, attempts playback, and verifies via logcat.
# Usage: bash test-netflix-playback.sh
#
# App navigation goes through tools/emu.js, so taps resolve against resource-ids
# rather than hardcoded coordinates. Taps *inside* the Netflix player still use
# coordinates — that DOM is login-gated and its controls are not knowable up front.

EMU="node $(dirname "$0")/tools/emu.js"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="test-netflix-playback-${TIMESTAMP}.log"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║         Netflix Playback Test Suite                        ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

TESTS=0
PASSED=0

test_check() {
    local test_name="$1"
    local condition="$2"

    TESTS=$((TESTS + 1))
    if eval "$condition"; then
        echo -e "${GREEN}✓ PASS${NC} [$TESTS] $test_name"
        PASSED=$((PASSED + 1))
        return 0
    else
        echo -e "${RED}✗ FAIL${NC} [$TESTS] $test_name"
        return 1
    fi
}

# Screen centre, derived rather than hardcoded — the old 720,1200 / 360,600 values
# were device-specific and disagreed with each other across scripts.
read -r SCREEN_W SCREEN_H < <($EMU size)
CENTER_X=$((SCREEN_W / 2))
CENTER_Y=$((SCREEN_H / 2))

echo "────────────────────────────────────────────────────────────"
echo "[Step 1] App Management"
echo "────────────────────────────────────────────────────────────"

# ServiceSelectorActivity is exported="false" — it cannot be started by component.
# Enter through the launcher instead.
echo "Starting app via launcher intent..."
$EMU launch --restart
sleep 4

test_check "App started" "$EMU ui > /dev/null 2>&1"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 2] Netflix Selection"
echo "────────────────────────────────────────────────────────────"

echo "Tapping Netflix card (#cardNetflix)..."
if test_check "Netflix card tapped" "$EMU tap '#cardNetflix' > /dev/null 2>&1"; then
    sleep 8
else
    echo -e "${YELLOW}  ↳ #cardNetflix not on screen. Current screen:${NC}"
    $EMU ui | sed 's/^/    /'
    echo -e "${RED}Cannot continue without reaching the service selector.${NC}"
    exit 1
fi

test_check "Netflix UI loaded" "$EMU logcat -n400 | grep -q 'Meta Pixel'"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 3] Video Load Verification"
echo "────────────────────────────────────────────────────────────"

test_check "Video element found" "$EMU logcat -n400 | grep -q '\[HWP\] video found'"
test_check "Video ready (readyState=4)" "$EMU logcat -n400 | grep -q 'readyState=4'"
test_check "Language set to en-IN" "$EMU logcat -n400 | grep -q 'Language set to en-IN'"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 4] Playback Controls"
echo "────────────────────────────────────────────────────────────"

test_check "Play/Pause events detected" "$EMU logcat -n400 | grep -q '\[HWP\].*event'"
test_check "Video source loaded (blob)" "$EMU logcat -n400 | grep -q 'blob:https://www.netflix.com'"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 5] Playback Simulation"
echo "────────────────────────────────────────────────────────────"

echo "Attempting to click play..."
if $EMU tap '~play' > /dev/null 2>&1; then
    echo "  ↳ tapped a labelled play control from the accessibility tree"
else
    echo -e "${YELLOW}  ↳ no labelled play control exposed; falling back to centre tap"
    echo -e "     (${CENTER_X},${CENTER_Y}) — fragile by nature, Netflix's player DOM is login-gated${NC}"
    $EMU tap "${CENTER_X},${CENTER_Y}" > /dev/null 2>&1
fi
sleep 3

test_check "Video responds to input" "$EMU logcat -n400 | grep -q 'pause event\|play event'"

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 6] Detailed Log Output"
echo "────────────────────────────────────────────────────────────"
echo ""

echo -e "${YELLOW}Last 10 HWP Events:${NC}"
$EMU logcat -n400 | grep "\[HWP\]" | tail -10 | sed 's/^/  /'

echo ""
echo -e "${YELLOW}Video Metadata:${NC}"
$EMU logcat -n400 | grep "video found" | tail -3 | sed 's/^/  /'

echo ""
echo "────────────────────────────────────────────────────────────"
echo "[Step 7] Final Results"
echo "────────────────────────────────────────────────────────────"
echo ""

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
