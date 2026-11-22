#!/usr/bin/env bash
# End-to-end pairing + lifecycle checks on a disposable Android 15 emulator
# and ephemeral isolated HTTPS/WSS relay with a synthetic 2nd peer.
# No traffic to Osaka VPS; only isolated synthetic pair + real JPEG data is tested.
set -Eeuo pipefail

PACKAGE="org.client.scrcpy"
ACTIVITY="org.client.scrcpy/.RemoteSetupActivity"
SERVICE="org.client.scrcpy/org.client.scrcpy.AccessibilityShotService"
REPORT="emulator-watch-after-reboot-results"
PYTHON_RELAY=".github/scripts/screensync-ci-pair-relay.py"
mkdir -p "$REPORT"
failures=0
relay_pid=""

log() { echo "$*" | tee -a "$REPORT/results.txt"; }
diagnostics() {
  adb shell run-as "$PACKAGE" cat files/screensync-diagnostics.log 2>/dev/null | tr -d '\r' || true
}
snapshot() {
  local label="$1"
  adb shell dumpsys accessibility > "$REPORT/$label-accessibility.txt" 2>&1 || true
  adb shell dumpsys activity recents > "$REPORT/$label-recents.txt" 2>&1 || true
  adb shell dumpsys power > "$REPORT/$label-power.txt" 2>&1 || true
  adb shell settings get secure enabled_accessibility_services > "$REPORT/$label-enabled.txt" 2>&1 || true
  adb shell pidof "$PACKAGE" > "$REPORT/$label-pid.txt" 2>&1 || true
  diagnostics > "$REPORT/$label-diagnostics.txt" || true
  adb shell screencap -p "/sdcard/ss-ci-$label.png" >/dev/null 2>&1 &&
    adb pull "/sdcard/ss-ci-$label.png" "$REPORT/$label.png" >/dev/null 2>&1 || true
}
cleanup() {
  local exit_code=$?
  set +e
  snapshot final
  adb logcat -d -v threadtime > "$REPORT/emulator-logcat.txt" 2>&1
  if [[ -n "$relay_pid" ]]; then kill "$relay_pid" 2>/dev/null || true; fi
  log "ASSERTION_FAILURES=$failures SHELL_EXIT_CODE=$exit_code"
}
trap cleanup EXIT

instrument() {
  local phase="$1"
  shift
  local output
  output="$(adb shell am instrument -w -r \
    -e class org.client.scrcpy.ScreenSyncPairedLifecycleTest#pairedLifecyclePhase \
    -e phase "$phase" "$@" \
    org.client.scrcpy.test/androidx.test.runner.AndroidJUnitRunner 2>&1)" || {
    echo "$output" | tail -30
    return 1
  }
  if ! grep -Eq 'OK \(1 test\)|OK \(1 tests\)' <<<"$output"; then
    echo "$output" | tail -30
    return 1
  fi
  return 0
}

online() {
  python3 "$PYTHON_RELAY" online --seconds "$1"
}
watch_frames() {
  python3 "$PYTHON_RELAY" watch --seconds "$1"
}
# Tap the real, on-device UI button/dialog. No instrumentation helper grants
# long-term watching; the app's actual onClick consent path is exercised.
tap_ui_text() {
  local needle="$1" xy attempt
  for attempt in 1 2 3 4; do
    # UIAutomator can hang indefinitely on some headless emulator builds.
    timeout 15s adb shell uiautomator dump /sdcard/screensync-ci-ui.xml >/dev/null 2>&1 || true
    timeout 8s adb exec-out cat /sdcard/screensync-ci-ui.xml > /tmp/screensync-ci-ui.xml 2>/dev/null || true
    xy="$(python3 - "$needle" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse("/tmp/screensync-ci-ui.xml")
    for node in root.iter("node"):
        if sys.argv[1] in node.attrib.get("text", ""):
            b = re.findall(r"\d+", node.attrib["bounds"])
            if len(b) == 4:
                print((int(b[0])+int(b[2]))//2, (int(b[1])+int(b[3]))//2)
                break
except Exception:
    pass
PY
)"
    if [[ -n "$xy" ]]; then
      adb shell input tap $xy
      return 0
    fi
    sleep 2
  done
  return 1
}

# The relay uses only throwaway local state and ephemeral TLS.
python3 "$PYTHON_RELAY" serve > "$REPORT/isolated-server.log" 2>&1 &
relay_pid=$!
for t in $(seq 1 30); do
  if curl -fsS --max-time 2 --cacert /tmp/screensync-ci-pair/ci-ca.pem \
    https://127.0.0.1:18443/health >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS --max-time 5 --cacert /tmp/screensync-ci-pair/ci-ca.pem \
  https://127.0.0.1:18443/health >/dev/null
log "PASS: isolated HTTPS signaling / real WSS relay online (not Osaka production)"

APK="$(find app/build/outputs/apk/scrcpy/debug -maxdepth 1 -name '*.apk' -print -quit)"
TEST_APK="$(find app/build/outputs/apk/androidTest/scrcpy/debug -maxdepth 1 -name '*.apk' -print -quit)"
[[ -f "$APK" && -f "$TEST_APK" ]] || {
  log "FAIL: main APK or AndroidTest APK missing"; exit 1;
}
adb install -r "$APK" >/dev/null
adb install -r "$TEST_APK" >/dev/null
adb logcat -c
adb shell am start -W -n "$ACTIVITY" >/dev/null
log "PASS: installed actual APK plus instrumentation helper on Android API 35"

# Emulate a source owner's initial accessibility grant in throwaway emulator.
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
sleep 3
snapshot accessibility-granted
if ! diagnostics | grep -q 'state=RUNNING'; then
  log "FAIL: accessibility not RUNNING before paired test"
  failures=$((failures+1))
else
  log "PASS: accessibility screenshot service is actually bound and running"
fi

# Real Android HTTPS code creates A's identity; real server creates QR invite.
instrument register
invite="$(adb shell run-as "$PACKAGE" cat files/ci_pair_invite | tr -d '\r\n')"
[[ "$invite" =~ ^[a-f0-9]{48}$ ]] || {
  log "FAIL: owner app did not create a valid single-use QR invitation"; exit 1;
}
# An isolated synthetic phone B joins; compare the SAME 6-digit verify code
# before approving, exactly as the real UI expects. Never log test tokens.
joined="$(printf '%s\n' "$invite" | python3 "$PYTHON_RELAY" join)"
guest_id="$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["guest_id"])' "$joined")"
verify_code="$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["verification_code"])' "$joined")"
instrument approve -e guestId "$guest_id" -e verifyCode "$verify_code"
python3 "$PYTHON_RELAY" status
log "PASS: app-A owner/peer-B QR join, six-digit verification, explicit pair approval and persisted identity"

# Instrumentation processes can exit when tests complete. Explicitly launch
# the app ONCE to establish its normal owner-enabled background WebSocket.
adb shell am start -W -n "$ACTIVITY" >/dev/null
# The Android instrumentation runner force-stops the target after each test
# phase. This marks the enabled accessibility service as crashed, even if the
# production APK itself is healthy. Repair that TEST-ONLY fixture state before
# evaluating Home, screen-off or recent-tasks behavior. Never do this on a real
# device and never treat it as an allowed production auto-grant.
adb shell settings put secure accessibility_enabled 0
adb shell settings delete secure enabled_accessibility_services >/dev/null
sleep 2
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
baseline_bound=0
for i in $(seq 1 25); do
  if adb shell dumpsys accessibility | grep -q 'Bound services:.*ScreenSync'; then
    baseline_bound=1
    break
  fi
  sleep 1
done
snapshot paired-bound-baseline
if [[ "$baseline_bound" == 1 ]]; then
  log "PASS: accessibility service re-bound after test-runner's forced shutdown"
else
  log "FAIL: clean bound-service baseline was not established; later lifecycle observations cannot be attributed"
  exit 1
fi
if online 30; then
  log "PASS: after pairing, emulator app WSS is authenticated and visible to paired peer"
else
  log "FAIL: app did not authenticate WSS after pairing"
  failures=$((failures+1))
fi
snapshot paired-online

# Real explicit source-owner UI permission, including system notification grant
# needed to post a currently visible "正在共享 / 停止" control on each watch.
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
sleep 10
if tap_ui_text "同意长期观看"; then
  log "ACTION: local source user tapped first-pair long-term viewing consent"
elif tap_ui_text "首次同意长期观看授权"; then
  sleep 2
  tap_ui_text "我同意长期共享"
  log "ACTION: local source user tapped the long-term viewing toggle/dialog"
else
  log "FAIL: couldn't locate local long-term viewing consent UI"
  failures=$((failures+1))
fi
sleep 6
snapshot owner-longterm-consent
if diagnostics | grep -q 'owner_enabled_for_current_pair'; then
  log "PASS: long-term viewing approved through actual local UI"
else
  log "FAIL: no source-owner long-term approval was recorded"
  failures=$((failures+1))
fi

# A synthetic paired watcher makes a fresh WATCH REQUEST and validates two
# real JPEG frames (Pillow verifies image integrity) from the Android device.
if watch_frames 55; then
  log "PASS: FIRST VIEW succeeded: guest received and decoded >=2 real Android screenshots"
else
  log "FAIL: FIRST VIEW didn't receive 2 decodable JPEG screenshots"
  failures=$((failures+1))
fi
snapshot first-view-ended

# Actual power-button screen-off; do not unlock while checking connectivity.
adb shell input keyevent KEYCODE_POWER
sleep 8
snapshot screen-off
if adb shell dumpsys power | grep -Eq 'mWakefulness=(Asleep|Dozing)'; then
  log "PASS: emulator display is asleep or dozing"
else
  log "WARN: emulator power state not conclusively asleep"
fi
if online 12; then
  log "PASS: paired WSS still online while screen is off"
else
  log "FAIL: paired WSS lost during screen off"
  failures=$((failures+1))
fi

adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_MENU
# Ensure a real unlocked foreground before manipulating recent-task cards.
adb shell input swipe 540 1850 540 400 350
adb shell input keyevent KEYCODE_HOME
adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 5
snapshot after-unlock-app-foreground
if online 18; then
  log "PASS: WSS reconnect after unlock and reopening owner app"
else
  log "FAIL: WSS still offline after unlock and reopening app"
  failures=$((failures+1))
fi

# Home, then remove the actual recent-tasks card; no force-stop.
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 3
snapshot recents-before-swipe
removed=0
attempt=0
# A short high-velocity fling is required by Pixel Quickstep recents.
# Slow drag gestures were visibly springing the card back into place.
for entry in "540 1300 540 80 90" "540 1550 540 40 110" "540 1050 540 50 150"; do
  attempt=$((attempt+1))
  adb shell input swipe $entry
  sleep 4
  snapshot "recents-attempt-$attempt"
  if ! adb shell dumpsys activity recents | grep -E 'Recent #[0-9]+: Task.*org[.]client[.]scrcpy' >/dev/null; then
    removed=1
    break
  fi
done
snapshot card-swiped
if [[ "$removed" == 1 ]]; then
  log "PASS: actually removed app task from Android recents"
else
  log "FAIL: app task remained in recents; this is not a valid swipe test"
  failures=$((failures+1))
fi
if online 15; then
  log "PASS: paired WSS still online after Home and real recents-card removal"
else
  log "FAIL: paired WSS offline after recents-card removal"
  failures=$((failures+1))
fi
if [[ "$removed" == 1 ]]; then
  if watch_frames 35; then
    log "PASS: after recents removal, new remote watch received real JPEG frames"
  else
    log "FAIL: after recents removal, new watch did not receive real JPEG frames"
    failures=$((failures+1))
  fi
fi

# Reboot the ENTIRE Android emulator. Do not manually launch ScreenSync until
# checking whether Android boot broadcast restored the pairing/WSS connection.
log "ACTION: rebooting Android 15 emulator; no UI launch until recovery check"
adb reboot
adb wait-for-device
booted=0
for i in $(seq 1 90); do
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; then
    booted=1
    break
  fi
  sleep 2
done
[[ "$booted" == 1 ]] || { log "FAIL: emulator OS did not finish reboot"; exit 1; }
sleep 10
snapshot boot-before-manual-launch
python3 "$PYTHON_RELAY" status
if online 50; then
  log "PASS: actual paired WSS reconnect after Android cold boot WITHOUT manual app launch"
else
  log "FAIL: no authenticated paired WSS reconnect within 50s of boot (before manual launch)"
  failures=$((failures+1))
fi
snapshot boot-recovery-checked

# CRITICAL user acceptance criterion: after a genuine Android cold reboot
# do NOT launch the ScreenSync Activity or issue any manual permission grants.
# The synthetic paired watcher requests a FRESH session and must receive two
# NEW, decodable JPEG frames from the dormant source device.
if watch_frames 60; then
  log "PASS: REBOOT VIEW - after cold boot, without opening source App, fresh request returned real screenshots"
else
  log "FAIL: REBOOT VIEW - paired WSS came back but no remote screenshot stream without opening App"
  failures=$((failures+1))
fi
snapshot boot-watch-attempted

# Now inspect persisted owner identity on Android via the real HTTPS client.
if instrument verify; then
  log "PASS: Android Keystore identity and paired peer survived OS reboot; HTTPS heartbeat works"
else
  log "FAIL: device-side pairing/heartbeat did not survive OS reboot"
  failures=$((failures+1))
fi
snapshot verified-after-reboot

# No persistent screen-capture consent was granted by this test, so pairing
# and reconnection MUST NOT be mistaken for auto screenshot authorization.
if diagnostics | grep -q 'LONG_TERM_WATCH_START  STARTED'; then
  log "PASS: approved long-term source explicitly starts only on paired viewer request"
else
  log "OBSERVED: no long-term auto-start callback recorded"
fi
instrument revoke
log "PASS: test pairing revoked and isolated credentials cleaned up"
if [[ "$failures" != 0 ]]; then
  log "RESULT: some lifecycle assertions failed; see per-stage evidence"
  exit 1
fi
log "RESULT: all paired Android emulator lifecycle assertions passed"
