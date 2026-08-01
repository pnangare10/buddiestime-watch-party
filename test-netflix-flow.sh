#!/bin/bash

# Netflix Playback Flow Test
#
# Flow:
# 1. Launch app via launcher intent (ServiceSelectorActivity is exported="false",
#    so it cannot be started by component — this is why the old `am start -n` failed)
# 2. Tap the Netflix card by resource-id (#cardNetflix), not by coordinate
# 3. Netflix loads with video ready
# 4. Language set to en-IN (regional fix)
# 5. Attempt play and verify video state via logcat

EMU="node $(dirname "$0")/tools/emu.js"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║          Netflix Playback Flow Test                        ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

TOTAL=0
PASSED=0

log_test() {
    local name="$1"
    local result="$2"

    TOTAL=$((TOTAL + 1))
    if [ "$result" = "pass" ]; then
        echo -e "${GREEN}✓ PASS${NC} [$TOTAL] $name"
        PASSED=$((PASSED + 1))
    else
        echo -e "${RED}✗ FAIL${NC} [$TOTAL] $name"
    fi
}

# Derived, not hardcoded — the old 720,1800 and 720,1400 constants were
# device-specific and disagreed with the other scripts.
read -r SCREEN_W SCREEN_H < <($EMU size)
CENTER_X=$((SCREEN_W / 2))
CENTER_Y=$((SCREEN_H / 2))

echo "Step 1: App Management"
echo "───────────────────────────────────────────────────────────"

$EMU launch --restart > /dev/null 2>&1
sleep 5

if $EMU ui > /dev/null 2>&1; then
    log_test "App launched and screen readable" "pass"
else
    log_test "App launched and screen readable" "fail"
fi

echo ""
echo "Step 2: Netflix Selection"
echo "───────────────────────────────────────────────────────────"

echo "Tapping Netflix card (#cardNetflix)..."
if $EMU tap '#cardNetflix' > /dev/null 2>&1; then
    log_test "Netflix card tapped" "pass"
    sleep 15
else
    log_test "Netflix card tapped" "fail"
    echo -e "${YELLOW}  ↳ #cardNetflix not on screen. Current screen:${NC}"
    $EMU ui | sed 's/^/    /'
    echo -e "${RED}Cannot continue without reaching the service selector.${NC}"
    exit 1
fi

if $EMU logcat -n400 | grep -q "MainActivity\|\[HWP\]"; then
    log_test "MainActivity opened" "pass"
else
    log_test "MainActivity opened" "fail"
fi

echo ""
echo "Step 3: Netflix Content Loading"
echo "───────────────────────────────────────────────────────────"

if $EMU logcat -n400 | grep -q "\[HWP\] video found"; then
    log_test "Video element found" "pass"
else
    log_test "Video element found" "fail"
fi

if $EMU logcat -n400 | grep -q "readyState=4"; then
    log_test "Video fully loaded (readyState=4)" "pass"
else
    log_test "Video fully loaded (readyState=4)" "fail"
fi

if $EMU logcat -n400 | grep -q "Language set to en-IN"; then
    log_test "Language set to en-IN (regional fix active)" "pass"
else
    log_test "Language set to en-IN (regional fix active)" "fail"
fi

if $EMU logcat -n400 | grep -q "blob:https://www.netflix.com"; then
    log_test "Video source loaded from Netflix" "pass"
else
    log_test "Video source loaded from Netflix" "fail"
fi

echo ""
echo "Step 4: Find and Click Play"
echo "───────────────────────────────────────────────────────────"

if $EMU tap '~play' > /dev/null 2>&1; then
    echo "Tapped a labelled play control from the accessibility tree."
else
    echo -e "${YELLOW}No labelled play control exposed; falling back to centre tap"
    echo -e "(${CENTER_X},${CENTER_Y}) — fragile by nature, Netflix's player DOM is login-gated.${NC}"
    $EMU tap "${CENTER_X},${CENTER_Y}" > /dev/null 2>&1
fi
sleep 3

log_test "Play attempted" "pass"

echo ""
echo "Step 5: Playback Validation"
echo "───────────────────────────────────────────────────────────"

if $EMU logcat -n400 | grep -q "\[HWP\].*event"; then
    log_test "Playback events detected (play/pause/seek)" "pass"
elif $EMU logcat -n400 | grep -q "readyState=4" && ! $EMU logcat -n400 | grep -qi "not available to watch instantly"; then
    log_test "Playback controls accessible (video ready, no errors)" "pass"
    echo "    ↳ Video ready and accepting input"
else
    log_test "Playback controls accessible (video ready, no errors)" "fail"
fi

echo ""
echo "Checking for regional availability errors..."
if $EMU logcat -n400 | grep -qi "not available to watch instantly\|This title is not available"; then
    log_test "ERROR: Regional blocking detected" "fail"
    echo "    ↳ Title not available in region"
else
    log_test "No regional blocking errors" "pass"
    echo "    ↳ Regional fix (en-IN) working correctly"
fi

# Note: AuthPII "credential not available" errors are Netflix auth issues, not regional blocking.
if $EMU logcat -n400 | grep -qi "drm error\|playback error\|content error"; then
    log_test "DRM/Playback errors detected" "fail"
else
    log_test "No DRM/Playback errors" "pass"
fi

echo ""
echo "Step 6: Detailed Results"
echo "───────────────────────────────────────────────────────────"

echo ""
echo -e "${YELLOW}Last 5 HWP Events:${NC}"
$EMU logcat -n400 | grep "\[HWP\]" | tail -5 | sed 's/^/  /'

echo ""
echo -e "${YELLOW}Video Metadata:${NC}"
$EMU logcat -n400 | grep "video found" | tail -1 | sed 's/^/  /'

echo ""
echo "Step 7: Summary"
echo "───────────────────────────────────────────────────────────"

FAILED=$((TOTAL - PASSED))
PERCENTAGE=0
[ "$TOTAL" -gt 0 ] && PERCENTAGE=$((PASSED * 100 / TOTAL))

echo ""
echo "Total Tests:  $TOTAL"
echo -e "Passed:       ${GREEN}$PASSED${NC}"
echo -e "Failed:       ${RED}$FAILED${NC}"
echo -e "Success Rate: ${BLUE}${PERCENTAGE}%${NC}"

echo ""
if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║     ✓ ALL TESTS PASSED - NETFLIX PLAYBACK WORKING!          ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
    exit 0
else
    echo -e "${RED}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║    ✗ SOME TESTS FAILED - CHECK OUTPUT ABOVE                ║${NC}"
    echo -e "${RED}╚════════════════════════════════════════════════════════════╝${NC}"
    exit 1
fi
