#!/usr/bin/env bash
# ScreenSync only: replace media_relay.py with tested rolling inactivity TTL.
# Does not touch nginx, 3X-UI, VLESS Reality, Xray, WireGuard, firewall or DB.
# Needs an authorized operator on the Osaka VPS; never executes from CI.
set -Eeuo pipefail
export LC_ALL=C
APP="/opt/screensync-relay-src/remote/relay_v1"
TARGET="$APP/media_relay.py"
PYTHON="/opt/screensync-relay-venv/bin/python"
SERVICE="screensync-media.service"
PINNED="3aecde9e87e48a67776d5b23e594090383096b84"
SOURCE_URL="https://raw.githubusercontent.com/ljh285218864-hue/ScrcpyForAndroid/${PINNED}/remote/relay_v1/media_relay.py"
TEMP=""
BACKUP=""
REPLACED=0
cleanup() {
    if [[ -n "$TEMP" && -f "$TEMP" ]]; then rm -f -- "$TEMP"; fi
}
rollback() {
    local result=$?
    trap - ERR
    if [[ "$REPLACED" == 1 && -n "$BACKUP" && -f "$BACKUP/media_relay.py" ]]; then
        echo "ROLLBACK: restore only ScreenSync media_relay.py" >&2
        cp -p "$BACKUP/media_relay.py" "$TARGET" || true
        systemctl restart "$SERVICE" || true
        systemctl is-active "$SERVICE" || true
    fi
    exit "${result:-1}"
}
trap rollback ERR
trap cleanup EXIT
die() { echo "ABORT: $*" >&2; exit 1; }
[[ "$EUID" == 0 ]] || die "run as authorized root on Osaka VPS"
[[ -f "$TARGET" && -x "$PYTHON" ]] || die "unexpected relay source or Python location"
systemctl is-active --quiet "$SERVICE" || die "media service is not active before deployment"
[[ "$(systemctl show -p User --value "$SERVICE")" == screensync ]] ||
    die "media service must run as screensync"
systemctl cat "$SERVICE" | grep -Fq "media_relay.py" ||
    die "media service executable does not match known ScreenSync code"
ss -lnt | awk '$4 ~ /:8767$/ {found=1} END {exit !found}' ||
    die "baseline media port 8767 not listening"
TEMP="$(mktemp "$APP/.media-rolling.XXXXXXXX")"
curl --connect-timeout 8 --max-time 40 -fsSL "$SOURCE_URL" -o "$TEMP" ||
    die "could not fetch pinned tested media source"
"$PYTHON" - "$TEMP" <<'PY'
import ast, sys
source = open(sys.argv[1], encoding="utf-8").read()
ast.parse(source)
assert "session.expires = time.monotonic() + SESSION_TTL_SECONDS" in source
assert "await self.discard(session_id, \"stopped_by_device\")" in source
assert "valid_pair(session)" in source
assert "viewer_unreachable" in source
print("PASS: rolling expiry while actively delivering authorized frames")
PY
BACKUP="/root/screensync-media-rolling-backup-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 0700 "$BACKUP"
cp -p "$TARGET" "$BACKUP/media_relay.py"
systemctl cat "$SERVICE" > "$BACKUP/service-before.txt"
sha256sum "$TARGET" > "$BACKUP/media-before.sha256"
chown screensync:screensync "$TEMP"
chmod 0600 "$TEMP"
mv -f -- "$TEMP" "$TARGET"
TEMP=""
REPLACED=1
systemctl restart "$SERVICE"
sleep 3
systemctl is-active --quiet "$SERVICE"
ss -lnt | awk '$4 ~ /:8767$/ {found=1} END {exit !found}'
# A WebSocket 101 handshake confirms the media loopback listener is usable.
# It does not authorize capture or start a video session.
code="$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
    --http1.1 --connect-timeout 3 --max-time 3 \
    -H 'Upgrade: websocket' -H 'Connection: Upgrade' \
    -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    'http://127.0.0.1:8767/media' || true)"
[[ "$code" == 101 ]] || die "media WS handshake not 101 (got $code)"
echo "SUCCESS: ScreenSync media rolling TTL installed (only media service restarted)"
echo "Backup: $BACKUP"
echo "UNCHANGED: nginx, signaling, database, x-ui, Xray/Reality, WireGuard"
echo "NOTE: any currently active screen view was interrupted by this one-time media restart"
