#!/usr/bin/env bash
# Android 15 AOSP/Google API emulator lifecycle validation.
# ADB enables accessibility only inside a disposable test emulator.
set -Eeuo pipefail

PACKAGE="org.client.scrcpy"
SERVICE="org.client.scrcpy/org.client.scrcpy.AccessibilityShotService"
ACTIVITY="org.client.scrcpy/.RemoteSetupActivity"
REPORT_DIR="emulator-test-results"
mkdir -p "$REPORT_DIR"

report() { printf '%s\n' "$*" | tee -a "$REPORT_DIR/results.txt"; }
app_log() { adb shell run-as "$PACKAGE" cat files/screensync-diagnostics.log 2>/dev/null | tr -d '\r' || true; }
snapshot() {
  local label="$1"
  adb shell dumpsys accessibility > "$REPORT_DIR/accessibility-$label.txt" 2>&1 || true
  adb shell dumpsys activity recents > "$REPORT_DIR/recents-$label.txt" 2>&1 || true
  adb shell settings get secure enabled_accessibility_services > "$REPORT_DIR/enabled-$label.txt" 2>&1 || true
  adb shell pidof "$PACKAGE" > "$REPORT_DIR/pid-$label.txt" 2>&1 || true
  app_log > "$REPORT_DIR/diagnostics-$label.txt" || true
  adb shell screencap -p "/sdcard/screensync-$label.png" 2>/dev/null &&
    adb pull "/sdcard/screensync-$label.png" "$REPORT_DIR/$label.png" >/dev/null 2>&1 || true
}
collect() {
  local exit_code=$?
  set +e
  snapshot final
  adb logcat -d -v threadtime > "$REPORT_DIR/android-logcat.txt" 2>&1
  report "FINAL_EXIT_CODE=$exit_code"
}
trap collect EXIT

APK="$(find app/build/outputs/apk/scrcpy/debug -maxdepth 1 -name '*.apk' -print -quit)"
[[ -n "$APK" && -f "$APK" ]] || { report "FAIL: debug APK missing"; exit 1; }
report "APK=$APK"
report "EMULATOR=$(adb shell getprop ro.build.version.release | tr -d '\r'), api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == "35" ]] ||
  { report "FAIL: expected Android API 35"; exit 1; }

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$ACTIVITY"
sleep 4
adb shell pm path "$PACKAGE" | grep -q "package:" ||
  { report "FAIL: installed application not found"; exit 1; }
report "PASS: installed real debug APK and launched main activity"
snapshot first-launch

# Only inside this isolated throwaway emulator: emulate local owner grant.
# Production ScreenSync never writes Settings.Secure or grants itself access.
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
started=0
for attempt in $(seq 1 35); do
  if app_log | grep -q 'ACCESSIBILITY  service_connected'; then
    started=1
    break
  fi
  sleep 1
done
snapshot accessibility-enabled
[[ "$started" == 1 ]] ||
  { report "FAIL: no AccessibilityService.onServiceConnected callback"; exit 1; }
app_log | grep -q 'state=RUNNING trigger=service_connected' ||
  { report "FAIL: service never reached RUNNING"; exit 1; }
report "PASS: accessibility bound and reached RUNNING"

adb shell input keyevent KEYCODE_HOME
sleep 5
snapshot after-home
enabled="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
[[ "$enabled" == *"$SERVICE"* ]] ||
  { report "FAIL: accessibility grant lost after Home"; exit 1; }
report "PASS: Home did not disable accessibility grant"

# Real Pixel-emulator recents gesture; screenshots capture its effect.
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 3
snapshot recents-before-swipe
adb shell input swipe 540 1630 540 300 450
sleep 8
snapshot recents-after-swipe
enabled="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
[[ "$enabled" == *"$SERVICE"* ]] ||
  { report "FAIL: accessibility grant lost after swipe"; exit 1; }
report "PASS: swipe did not disable accessibility grant"
report "NOTE: recents screenshots/dumps available to verify actual card removal"

adb shell am start -W -n "$ACTIVITY"
sleep 10
snapshot reopened
app_log | grep -q 'APP_RESUME' ||
  { report "FAIL: reopening activity missing"; exit 1; }
app_log | grep -q 'state=RUNNING' ||
  { report "FAIL: service never reported RUNNING in diagnostic log"; exit 1; }
report "PASS: app reopened and accessibility RUNNING was recorded"

# Force-stop is NOT equivalent to a recents swipe and may suspend recovery.
adb shell am force-stop "$PACKAGE"
sleep 3
snapshot after-force-stop
report "OBSERVED: force-stop performed, no realtime recovery guarantee"
adb shell am start -W -n "$ACTIVITY"
sleep 10
snapshot after-force-stop-reopen
report "COMPLETE: Android emulator lifecycle test; vivo/Funtouch OS still needs a physical device"
