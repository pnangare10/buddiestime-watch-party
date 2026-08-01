#!/bin/bash
set -euo pipefail

# Buddies Time — service integration smoke test.
#
# Drives the app through tools/emu.js so taps resolve against resource-ids from the
# accessibility tree instead of hardcoded pixel coordinates.
#
# Usage: bash test-automation.sh [netflix|hotstar|primevideo|youtube]

SERVICE="${1:-netflix}"
LOG_FILE="test-results-${SERVICE}-$(date +%s).log"
EMU="node $(dirname "$0")/tools/emu.js"

case "$SERVICE" in
  netflix)    CARD='#cardNetflix'    ;;
  hotstar)    CARD='#cardHotstar'    ;;
  primevideo) CARD='#cardPrimeVideo' ;;
  youtube)    CARD='#cardYouTube'    ;;
  *) echo "Unknown service: $SERVICE (expected netflix|hotstar|primevideo|youtube)" >&2; exit 2 ;;
esac

{
  echo "[TEST] Buddies Time - $SERVICE Integration Test"
  echo "========================================"
  echo "Test Date: $(date)"
  echo "Service: $SERVICE"
  echo "========================================"
} | tee "$LOG_FILE"

# ServiceSelectorActivity is exported="false", so it cannot be started by component.
# Enter through the launcher and navigate.
echo "[INFO] Launching app via launcher intent..." | tee -a "$LOG_FILE"
$EMU launch --restart | tee -a "$LOG_FILE"
sleep 4

echo "[INFO] Current screen:" | tee -a "$LOG_FILE"
$EMU ui | tee -a "$LOG_FILE"

echo "[ACTION] Tapping $SERVICE card ($CARD)..." | tee -a "$LOG_FILE"
if ! $EMU tap "$CARD" >>"$LOG_FILE" 2>&1; then
  echo "[FAIL] $CARD not found on screen — is the app past first-run setup?" | tee -a "$LOG_FILE"
  echo "       Run '$EMU ui' to see the current screen." | tee -a "$LOG_FILE"
  exit 1
fi

echo "[INFO] Waiting for MainActivity to load..." | tee -a "$LOG_FILE"
sleep 6

$EMU logcat -c >/dev/null
sleep 5

echo "" >> "$LOG_FILE"
echo "[LOGCAT] Sync messages:" >> "$LOG_FILE"
$EMU logcat -n200 >> "$LOG_FILE" 2>&1 || true

echo "[INFO] Test sequence complete." | tee -a "$LOG_FILE"
echo "Results saved to: $LOG_FILE" | tee -a "$LOG_FILE"
