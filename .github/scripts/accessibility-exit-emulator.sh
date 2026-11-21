#!/usr/bin/env bash
set -Eeuo pipefail
RESULTS=accessibility-exit-results
PKG=org.client.scrcpy
SERVICE="$PKG/$PKG.AccessibilityShotService"
ACTIVITY="$PKG/.RemoteSetupActivity"
mkdir -p "$RESULTS"
exec > >(tee "$RESULTS/summary.txt") 2>&1
trap 'echo "=== End diagnostics ==="; adb shell dumpsys accessibility > "$RESULTS/accessibility-final.txt" 2>&1 || true; adb shell dumpsys activity recents > "$RESULTS/recents-final.txt" 2>&1 || true; adb logcat -d -v time > "$RESULTS/logcat.txt" 2>&1 || true; adb exec-out screencap -p > "$RESULTS/screen-final.png" 2>/dev/null || true' EXIT

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put system screen_off_timeout 600000 || true
echo "Emulator Android release=$(adb shell getprop ro.build.version.release | tr -d '\r') API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
APK=$(find app/build/outputs/apk/scrcpy/debug -maxdepth 1 -name '*.apk' -print -quit)
test -s "$APK"
adb install -r "$APK"
adb shell pm path "$PKG"
adb logcat -c
# Let Android PackageManager/AccessibilityManager register a freshly installed
# accessibility service before enabling it. On Android 15 an immediate put may
# be discarded during the package-scan reconciliation.
echo "--- Detect installed accessibility service ---"
sleep 10
adb shell dumpsys accessibility > "$RESULTS/accessibility-before-enable.txt"
adb shell dumpsys package "$PKG" > "$RESULTS/package-before-enable.txt"
grep -n "AccessibilityShotService" "$RESULTS/package-before-enable.txt" | head -8 || true

# ADB shell is a privileged TEST HARNESS on this emulator, not app code.
# Set the master switch before the per-service list, then verify persistence.
adb shell settings put secure accessibility_enabled 1
for attempt in 1 2 3 4; do
  adb shell settings put --user 0 secure enabled_accessibility_services "$SERVICE"
  sleep 3
  ACTUAL=$(adb shell settings get --user 0 secure enabled_accessibility_services | tr -d '\r')
  echo "Accessibility enable attempt $attempt: $ACTUAL"
  if [[ "$ACTUAL" == *"AccessibilityShotService"* ]]; then break; fi
  sleep 5
done

check_enabled() {
  local stage="$1" actual
  actual=$(adb shell settings get --user 0 secure enabled_accessibility_services | tr -d '\r')
  echo "$stage: secure.enabled_accessibility_services=$actual"
  if [[ "$actual" != *"$PKG"* || "$actual" != *"AccessibilityShotService"* ]]; then
    echo "FAIL [$stage]: system accessibility toggle removed the service"
    exit 1
  fi
  adb shell dumpsys accessibility > "$RESULTS/accessibility-$stage.txt"
  echo "PASS [$stage]: system accessibility enabled list retains ScreenSync"
}
check_enabled initially-enabled

echo "--- Launch ScreenSync UI ---"
adb shell am start -W -n "$ACTIVITY"
sleep 8
adb shell pidof "$PKG" || true
check_enabled after-launch
echo "--- Press Home ---"
adb shell input keyevent KEYCODE_HOME
sleep 5
check_enabled after-home
echo "--- Reopen ScreenSync and use Back ---"
adb shell am start -W -n "$ACTIVITY"
sleep 3
adb shell input keyevent KEYCODE_BACK
sleep 5
check_enabled after-back
echo "--- Reopen and swipe recent-task card ---"
adb shell am start -W -n "$ACTIVITY"
sleep 3
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 4
adb exec-out screencap -p > "$RESULTS/recents-before-swipe.png" || true
RESOLUTION=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
WIDTH=${RESOLUTION%x*}
HEIGHT=${RESOLUTION#*x}
if [[ -z "$WIDTH" || -z "$HEIGHT" ]]; then WIDTH=1080; HEIGHT=1920; fi
X=$((WIDTH/2))
# Validate that this is the ScreenSync task, not an arbitrary overview item.
screen_sync_in_recents() {
  adb shell dumpsys activity recents 2>/dev/null | grep -q 'baseIntent=.*org.client.scrcpy'
}
if ! screen_sync_in_recents; then
  echo "FAIL: ScreenSync was not in recent tasks before swipe"
  exit 1
fi
removed=0
for attempt in 1 2 3; do
  if ((attempt > 1)); then
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    adb shell input keyevent KEYCODE_APP_SWITCH
    sleep 3
  fi
  case "$attempt" in
    1) Y1=$((HEIGHT*38/100)); Y2=5; DURATION=280 ;;
    2) Y1=$((HEIGHT*52/100)); Y2=5; DURATION=160 ;;
    3) Y1=$((HEIGHT*26/100)); Y2=5; DURATION=400 ;;
  esac
  echo "Swipe attempt $attempt: ($X,$Y1) -> ($X,$Y2) ms=$DURATION"
  adb shell input touchscreen swipe "$X" "$Y1" "$X" "$Y2" "$DURATION"
  sleep 5
  adb exec-out screencap -p > "$RESULTS/recents-after-attempt-$attempt.png" || true
  if ! screen_sync_in_recents; then
    removed=1
    echo "PASS: ScreenSync recent-task card was actually removed on attempt $attempt"
    break
  fi
done
adb shell dumpsys activity recents > "$RESULTS/recents-after-swipe.txt" 2>&1
if [[ "$removed" != 1 ]]; then
  echo "INFO: emulator overview ignored automated vertical swipes; testing Clear all as an equivalent recent-task removal."
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  adb shell input keyevent KEYCODE_APP_SWITCH
  sleep 3
  # Pixel launcher horizontally scrolls to reveal "Clear all" to the left.
  adb shell input touchscreen swipe "$((WIDTH*18/100))" "$((HEIGHT*38/100))" "$((WIDTH*90/100))" "$((HEIGHT*38/100))" 380
  sleep 3
  adb exec-out screencap -p > "$RESULTS/recents-before-clear-all.png" || true
  adb shell input tap "$((WIDTH*16/100))" "$((HEIGHT*38/100))"
  sleep 6
  adb exec-out screencap -p > "$RESULTS/recents-after-clear-all.png" || true
  adb shell dumpsys activity recents > "$RESULTS/recents-after-clear-all.txt" 2>&1
  if screen_sync_in_recents; then
    echo "FAIL: recent-task card still exists after both swipe and Clear all"
    exit 1
  fi
  echo "PASS: ScreenSync recent-task card truly removed using the launcher Clear all control"
  check_enabled after-recents-clear-all
else
  check_enabled after-recents-swipe
fi

echo "--- Simulate ordinary OS reclaim of background process (not force-stop) ---"
adb shell input keyevent KEYCODE_HOME
adb shell am kill "$PKG" || true
sleep 6
check_enabled after-am-kill

echo "--- Reopen after recents / process reclaim ---"
adb shell am start -W -n "$ACTIVITY"
sleep 6
check_enabled after-reopen
if adb logcat -d -v time | grep -E 'FATAL EXCEPTION: main|Process: org.client.scrcpy' > "$RESULTS/crashes.txt"; then
  echo "FAIL: crash markers found in logcat"
  cat "$RESULTS/crashes.txt"
  exit 1
fi
echo "PASS: system accessibility toggle retained after Home, Back, verified recent-task removal and am kill."
echo "LIMIT: Pixel Android emulator cannot reproduce vivo/OPPO/Xiaomi OEM permission revocations."
