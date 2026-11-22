#!/usr/bin/env bash
# Safely upgrade ONLY the existing ScreenSync media relay on the Osaka VPS.
# Never touches 3X-UI, Xray/Reality, WireGuard, nginx, firewall, DB, or HTTPS signaling.
set -Eeuo pipefail
export LC_ALL=C
SERVICE="screensync-media.service"
PINNED_COMMIT="1350fcc210a9e05c83135f6f748b64d6f7c708e5"
RAW="https://raw.githubusercontent.com/ljh285218864-hue/ScrcpyForAndroid/${PINNED_COMMIT}/remote/relay_v1/media_relay.py"
DIR=""
PYTHON=""
TMP=""
BACKUP=""
CHANGED=0

abort() { printf 'ABORT (no change unless rollback noted): %s\n' "$*" >&2; exit 1; }
cleanup() { if [[ -n "$TMP" && -f "$TMP" ]]; then rm -f -- "$TMP"; fi; }
rollback() {
    trap - ERR
    set +e
    if [[ "$CHANGED" == "1" && -f "$BACKUP/media_relay.py" ]]; then
        echo "ROLLBACK: restoring only the previous ScreenSync media_relay.py" >&2
        cp -p -- "$BACKUP/media_relay.py" "$DIR/media_relay.py"
        systemctl restart "$SERVICE"
        systemctl is-active --quiet "$SERVICE" \
            && echo "ROLLBACK: original media relay is running" \
            || echo "URGENT: original media service did not restart; inspect journalctl" >&2
    fi
    exit 1
}
trap cleanup EXIT
trap rollback ERR

[[ "$EUID" -eq 0 ]] || abort "Run via sudo bash, on the authorized VPS"
for tool in systemctl curl sha256sum cmp mktemp; do
    command -v "$tool" >/dev/null || abort "Missing command: $tool"
done
systemctl is-active --quiet "$SERVICE" || abort "ScreenSync media service is not active; inspect it first"
UNIT_USER="$(systemctl show -p User --value "$SERVICE")"
[[ "$UNIT_USER" == "screensync" ]] || abort "Unexpected media service user: $UNIT_USER"

DIR="$(systemctl show -p WorkingDirectory --value "$SERVICE")"
case "$DIR" in
  /opt/screensync-relay/remote/relay_v1)
    PYTHON="/opt/screensync-relay/.venv/bin/python" ;;
  /opt/screensync-relay-src/remote/relay_v1)
    PYTHON="/opt/screensync-relay-venv/bin/python" ;;
  *) abort "Unknown ScreenSync WorkingDirectory: $DIR (no changes)" ;;
esac
[[ -f "$DIR/media_relay.py" && ! -L "$DIR/media_relay.py" ]] ||
    abort "media_relay.py missing or is a symlink"
[[ -f "$DIR/server.py" && -x "$PYTHON" ]] ||
    abort "Expected ScreenSync server.py or Python environment absent"
CMD="$(systemctl show -p ExecStart --value "$SERVICE")"
[[ "$CMD" == *"$PYTHON"* && "$CMD" == *"media_relay.py"* ]] ||
    abort "Media ExecStart does not match expected Python and file"
if command -v nginx >/dev/null; then
    nginx -t >/dev/null 2>&1 || abort "Existing nginx config is invalid; aborting before change"
fi
"$PYTHON" - <<'PY'
import websockets
print("Verified existing ScreenSync Python websocket dependency:", websockets.__version__)
PY
echo "PRECHECK OK: target $DIR/media_relay.py"
echo "Before SHA256: $(sha256sum "$DIR/media_relay.py" | cut -d' ' -f1)"
echo "SERVICE: $SERVICE (only this service can be restarted)"
echo "Pinned reviewed commit: $PINNED_COMMIT"

TMP="$(mktemp "$DIR/.screensync-transport.XXXXXXXX")"
curl -fLsS --connect-timeout 8 --max-time 60 "$RAW" -o "$TMP" ||
    abort "Cannot fetch pinned GitHub source (private repo/connection problem?)"
[[ -s "$TMP" ]] || abort "Downloaded file is empty"
"$PYTHON" - "$TMP" <<'PY'
import ast, pathlib, sys
source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
ast.parse(source)
for expected in (
    "JPEG_MAX_FPS = 8",
    '"jpeg_max_fps": JPEG_MAX_FPS',
    '"stale_session_replaced"',
    '"viewer_send_timeout"',
    "session.viewer_send_timeouts",
    "self.valid_pair(session)",
    '"pairing_revoked"',
):
    if expected not in source:
        raise SystemExit("Incorrect ScreenSync source: missing " + expected)
print("SOURCE CHECK PASSED: negotiated JPEG cap, stale-session cleanup, permissions")
PY
if cmp -s -- "$TMP" "$DIR/media_relay.py"; then
    echo "ALREADY CURRENT: media relay matches pinned source, no restart"
    exit 0
fi

BACKUP="$(mktemp -d /root/screensync-transport-backup-XXXXXXXX)"
chmod 0700 "$BACKUP"
cp -p -- "$DIR/media_relay.py" "$BACKUP/media_relay.py"
systemctl cat "$SERVICE" > "$BACKUP/media-service-before.txt"
sha256sum "$BACKUP/media_relay.py" > "$BACKUP/media-before.sha256"
echo "BACKUP: $BACKUP"

# Preserve private access, and give only the dedicated service user permission.
chown screensync:screensync "$TMP"
chmod 0600 "$TMP"
mv -f -- "$TMP" "$DIR/media_relay.py"
TMP=""
CHANGED=1
systemctl restart "$SERVICE"
sleep 2
systemctl is-active --quiet "$SERVICE"
"$PYTHON" - <<'PY'
import asyncio
from websockets.asyncio.client import connect
async def check():
    async with connect("ws://127.0.0.1:8767/media",
                       open_timeout=5, close_timeout=1) as sock:
        print("PASS: authenticated media server port accepts local WSS-proxy handshake")
asyncio.run(check())
PY
echo "SUCCESS: ScreenSync media-only transport upgrade installed"
echo "After SHA256: $(sha256sum "$DIR/media_relay.py" | cut -d' ' -f1)"
echo "Rollback backup: $BACKUP/media_relay.py"
echo "UNCHANGED: screensync-signaling, DB, nginx, 3X-UI, Reality, Xray, WireGuard"
echo "NOTE: existing live ScreenSync watch sessions were interrupted by this one-time media restart."
