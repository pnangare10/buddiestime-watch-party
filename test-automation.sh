#!/bin/bash
set -e

SERVICE=${1:-netflix}  # Default to netflix
LOG_FILE="test-results-${SERVICE}-$(date +%s).log"
ADB="/c/Users/prane_ii3rizl/AppData/Local/Android/Sdk/platform-tools/adb.exe"

echo "[TEST] Buddies Time - $SERVICE Integration Test" | tee "$LOG_FILE"
echo "========================================" >> "$LOG_FILE"
echo "Test Date: $(date)" >> "$LOG_FILE"
echo "Service: $SERVICE" >> "$LOG_FILE"
echo "========================================" >> "$LOG_FILE"

# Kill any running instance
echo "[INFO] Stopping any running instance..." | tee -a "$LOG_FILE"
"$ADB" shell am force-stop com.buddiestime.watchparty || true
sleep 2

# Launch ServiceSelectorActivity
echo "[INFO] Launching ServiceSelectorActivity..." | tee -a "$LOG_FILE"
"$ADB" shell am start -n com.buddiestime.watchparty/.ServiceSelectorActivity
sleep 4

# Determine which tile to tap based on service
if [ "$SERVICE" = "netflix" ]; then
    echo "[ACTION] Tapping Netflix tile..." | tee -a "$LOG_FILE"
    # Approximate coordinates for Netflix card on Pixel 7 Pro
    "$ADB" shell input tap 720 1200
else
    echo "[ACTION] Tapping Hotstar tile..." | tee -a "$LOG_FILE"
    # Approximate coordinates for Hotstar card on Pixel 7 Pro
    "$ADB" shell input tap 720 600
fi

echo "[INFO] Waiting for MainActivity to load..." | tee -a "$LOG_FILE"
sleep 6

# Clear and start capturing logcat
"$ADB" logcat -c
sleep 1

echo "[INFO] Checking for video element detection..." | tee -a "$LOG_FILE"
sleep 5

# Capture HWP sync messages
echo "" >> "$LOG_FILE"
echo "[LOGCAT] Sync Messages:" >> "$LOG_FILE"
"$ADB" logcat -d 2>&1 | grep -E "\[HWP\]|error|failed|not supported" >> "$LOG_FILE" 2>&1 || true

echo "[INFO] Test sequence complete." | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "Results saved to: $LOG_FILE" | tee -a "$LOG_FILE"
