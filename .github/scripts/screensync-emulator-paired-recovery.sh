#!/usr/bin/env bash
# End-to-end pairing + lifecycle checks on a disposable Android 15 emulator
# and ephemeral isolated HTTPS/WSS relay with a synthetic 2nd peer.
# No traffic to Osaka VPS; no real pair created or modified.
set -Eeuo pipefail

PACKAGE="org.client.scrcpy"
ACTIVITY="org.client.scrcpy/.RemoteSetupActivity"
SERVICE="org.client.scrcpy/org.client.scrcpy.AccessibilityShotService"
REPORT="emulator-pair-recovery-results"
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
if online 30; then
  log "PASS: after pairing, emulator app WSS is authenticated and visible to paired peer"
else
  log "FAIL: app did not authenticate WSS after pairing"
  failures=$((failures+1))
fi
snapshot paired-online

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
  log "FAIL: unapproved screen capture unexpectedly began"
  failures=$((failures+1))
else
  log "PASS: no unapproved screen capture started during boot/reconnection"
fi
instrument revoke
log "PASS: test pairing revoked and isolated credentials cleaned up"
if [[ "$failures" != 0 ]]; then
  log "RESULT: some lifecycle assertions failed; see per-stage evidence"
  exit 1
fi
log "RESULT: all paired Android emulator lifecycle assertions passed"
