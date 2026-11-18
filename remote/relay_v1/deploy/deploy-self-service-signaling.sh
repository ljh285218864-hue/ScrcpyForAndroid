#!/usr/bin/env bash
# ScreenSync A-create/B-scan QR registration, isolated to existing signaling service.
# No nginx, x-ui, Xray, Reality, WireGuard, media, firewall or database erasure.
# Deliberate one-time run on the user's authorized Osaka VPS. Does not run from CI.
set -Eeuo pipefail
export LC_ALL=C
ROOT="/opt/screensync-relay-src/remote/relay_v1"
PYTHON="/opt/screensync-relay-venv/bin/python"
TARGET="$ROOT/server.py"
SERVICE="screensync-signaling.service"
DROPIN_DIR="/etc/systemd/system/${SERVICE}.d"
DROPIN="$DROPIN_DIR/90-self-service-qr.conf"
PINNED_COMMIT="7342df33d410c2371bfc98bf54e13228643e08a9"
SOURCE_URL="https://raw.githubusercontent.com/ljh285218864-hue/ScrcpyForAndroid/${PINNED_COMMIT}/remote/relay_v1/server.py"
TEMP=""
BACKUP=""
WRITTEN_DROPIN=0
MOVED_TARGET=0
rollback() {
    set +e
    echo "ROLLBACK: reverting only ScreenSync signaling changes" >&2
    if [[ "$MOVED_TARGET" == 1 && -f "$BACKUP/server.py" ]]; then
        cp -p -- "$BACKUP/server.py" "$TARGET"
        # Keep the service recoverable even if the prior file was root:root 0600.
        chown screensync:screensync "$TARGET"
        chmod 0600 "$TARGET"
    fi
    if [[ "$WRITTEN_DROPIN" == 1 ]]; then
        rm -f -- "$DROPIN"
    fi
    systemctl daemon-reload
    systemctl restart "$SERVICE"
    systemctl is-active --quiet "$SERVICE" ||
        echo "URGENT: signaling service did not recover; inspect only $SERVICE" >&2
    exit 1
}
cleanup() {
    if [[ -n "$TEMP" && -f "$TEMP" ]]; then rm -f -- "$TEMP"; fi
}
trap cleanup EXIT
die() { printf 'ABORT: %s\n' "$*" >&2; exit 1; }
[[ "$EUID" -eq 0 ]] || die "requires authorized root on Osaka VPS"
for command in curl systemctl sha256sum; do
    command -v "$command" >/dev/null || die "missing command $command"
done
[[ -f "$TARGET" && -x "$PYTHON" ]] || die "unexpected existing server path or venv"
systemctl is-active --quiet "$SERVICE" || die "signaling is not active before upgrade"
[[ "$(systemctl show -p User --value "$SERVICE")" == screensync ]] ||
    die "signaling service must run as screensync"
[[ "$(systemctl show -p WorkingDirectory --value "$SERVICE")" == "$ROOT" ]] ||
    die "unexpected service WorkingDirectory, no changes made"
systemctl show -p ExecStart --value "$SERVICE" |
    grep -Fq "/opt/screensync-relay-venv/bin/python" ||
    die "unexpected service Python interpreter"
systemctl show -p ExecStart --value "$SERVICE" | grep -Fq "server.py serve" ||
    die "unexpected signaling command"
[[ ! -e "$DROPIN" ]] || die "self-service unit override already exists; inspect manually"
# Existing source may be root:root 0600 from an earlier deployment.
# New source is installed screensync:screensync 0600 so restart is possible.
if ! runuser -u screensync -- test -r "$TARGET"; then
    echo "NOTICE: old signaling source unreadable to screensync; new source will fix its ownership" >&2
fi
if command -v nginx >/dev/null; then
    nginx -t >/dev/null 2>&1 || die "nginx baseline invalid; no changes made"
fi
TEMP="$(mktemp "$ROOT/.server-selfservice.XXXXXXXX")"
curl --connect-timeout 8 --max-time 40 -fsSL "$SOURCE_URL" -o "$TEMP" ||
    die "could not retrieve pinned server source"
"$PYTHON" - "$TEMP" <<'PY' || die "server.py syntax or enrollment contract invalid"
import ast, sys
code = open(sys.argv[1], encoding="utf-8").read()
ast.parse(code)
for symbol in ('"/v1/self-register"', "SELF_SERVICE_ENABLED",
               "MAX_SELF_REGISTER_HOUR", "MAX_SELF_REGISTER_DAY",
               "def peer(", "def dispatch(", '"/v1/approve"', '"/v1/revoke"'):
    assert symbol in code, symbol
print("SELF_SERVICE_SOURCE_VALID")
PY
BACKUP="/root/screensync-signaling-selfservice-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 0700 "$BACKUP"
cp -p -- "$TARGET" "$BACKUP/server.py" || die "could not create backup"
systemctl cat "$SERVICE" > "$BACKUP/service-before.txt"
sha256sum "$TARGET" > "$BACKUP/server-before.sha256"
printf 'Backup %s\n' "$BACKUP"
# Atomic replacement with rights that actually allow the service to import.
chown screensync:screensync "$TEMP"
chmod 0600 "$TEMP"
mkdir -p "$DROPIN_DIR"
mv -- "$TEMP" "$TARGET"
TEMP=""
MOVED_TARGET=1
TEMP="$(mktemp "$DROPIN_DIR/.selfservice.XXXXXXXX")"
printf '[Service]\nEnvironment=SS_ALLOW_SELF_SERVICE=1\nEnvironment=SS_SELF_REGISTER_HOUR=12\nEnvironment=SS_SELF_REGISTER_DAY=30\n' > "$TEMP"
chmod 0644 "$TEMP"
mv -- "$TEMP" "$DROPIN"
TEMP=""
WRITTEN_DROPIN=1
systemctl daemon-reload || rollback
systemctl restart "$SERVICE" || rollback
sleep 2
systemctl is-active --quiet "$SERVICE" || rollback
if command -v ss >/dev/null; then
    ss -lnt | awk '$4 ~ /:8766$/ {found=1} END {exit !found}' || rollback
fi
curl --connect-timeout 4 --max-time 8 -fsS http://127.0.0.1:8766/health >/dev/null ||
    rollback
echo "SUCCESS: ScreenSync signaling only; registration API enabled with hourly/daily caps"
echo "Backup: $BACKUP"
echo "NOT CHANGED: media relay, nginx, :8443, x-ui, Xray/Reality, WireGuard"
echo "NEXT: install matching APK and test A-create/B-scan/6-digit approval"
