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
  ACTUAL=$(adb shell settings get --user 0 secure enabled_accessibility_services | tr -d '\\r')
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
Y1=$((HEIGHT*52/100))
Y2=$((HEIGHT*13/100))
echo "Recents screenshot captured; swipe card from ($X,$Y1) to ($X,$Y2)"
adb shell input swipe "$X" "$Y1" "$X" "$Y2" 480
sleep 6
adb exec-out screencap -p > "$RESULTS/recents-after-swipe.png" || true
adb shell dumpsys activity recents > "$RESULTS/recents-after-swipe.txt" 2>&1 || true
check_enabled after-recents-swipe

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
echo "PASS: system accessibility toggle retained after Home, Back, recents UI swipe and am kill."
echo "LIMIT: the recents gesture may not remove the intended card; inspect before/after screenshots."
echo "LIMIT: Pixel Android emulator cannot reproduce vivo/OPPO/Xiaomi OEM permission revocations."
