#!/usr/bin/env bash
set -euo pipefail

mkdir -p emulator-test-results
adb wait-for-device
echo "Emulator details:" | tee emulator-test-results/summary.txt
adb shell getprop ro.build.version.release | tee -a emulator-test-results/summary.txt
adb shell getprop ro.build.version.sdk | tee -a emulator-test-results/summary.txt
adb shell getprop ro.product.cpu.abi | tee -a emulator-test-results/summary.txt

adb shell input keyevent 82 || true
adb shell settings put system screen_off_timeout 600000 || true
APK=$(find app/build/outputs/apk/scrcpy/debug -maxdepth 1 -name '*.apk' -print -quit)
if [[ -z "$APK" ]]; then
  echo "APK not found" >&2
  exit 1
fi

echo "Installing $APK"
adb install -r "$APK" | tee -a emulator-test-results/summary.txt
adb shell pm path org.client.scrcpy | tee -a emulator-test-results/summary.txt

adb logcat -c
echo "Launching org.client.scrcpy/.MainActivity"
adb shell am start -W -n org.client.scrcpy/.MainActivity | tee -a emulator-test-results/summary.txt
sleep 20

adb shell dumpsys activity activities > emulator-test-results/activity.txt || true
adb shell dumpsys window > emulator-test-results/window.txt || true
adb logcat -d -v threadtime > emulator-test-results/logcat.txt || true
adb exec-out screencap -p > emulator-test-results/screen.png || true
adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
adb pull /sdcard/window.xml emulator-test-results/window.xml >/dev/null 2>&1 || true

if ! adb shell pidof org.client.scrcpy | grep -Eq '[0-9]'; then
  echo "FAIL: App process is not running after launch" | tee -a emulator-test-results/summary.txt
  grep -iE "FATAL EXCEPTION|AndroidRuntime|Process: org.client.scrcpy" emulator-test-results/logcat.txt | tail -n 60 || true
  exit 1
fi

echo "PASS: App installed, launched, and process remains alive after 20 seconds" | tee -a emulator-test-results/summary.txt
echo "Note: This is a one-emulator launch smoke test, not a full two-phone scrcpy connection test" | tee -a emulator-test-results/summary.txt
