#!/usr/bin/env bash
# ScreenSync Osaka: change ONLY the existing screensync-media Python relay.
# Keeps 3X-UI, Xray/Reality, legacy WireGuard, nginx :8443, signaling DB untouched.
# Requires deliberate root invocation on the existing VPS.
set -Eeuo pipefail
export LC_ALL=C
TARGET="/opt/screensync-relay/remote/relay_v1/media_relay.py"
PYTHON="/opt/screensync-relay/.venv/bin/python"
SERVICE="screensync-media.service"
EXPECTED_DIRECTORY="/opt/screensync-relay/remote/relay_v1"
SOURCE_COMMIT="e060a83b07bb29af46cc5002e6b8c8f0dd4371c4"
SOURCE_URL="https://raw.githubusercontent.com/ljh285218864-hue/ScrcpyForAndroid/${SOURCE_COMMIT}/remote/relay_v1/media_relay.py"
BACKUP=""
TEMP=""
cleanup() { if [[ -n "$TEMP" && -e "$TEMP" ]]; then rm -f -- "$TEMP"; fi; }
trap cleanup EXIT

die() { printf 'ABORT: %s\n' "$*" >&2; exit 1; }
log() { printf '[screensync-jpeg] %s\n' "$*"; }

[[ "${EUID}" -eq 0 ]] || die "Run with sudo/root on the authorized ScreenSync VPS"
command -v curl >/dev/null || die "curl missing"
command -v systemctl >/dev/null || die "systemctl missing"
[[ -f "$TARGET" ]] || die "Existing ScreenSync media relay not found at expected path"
[[ -x "$PYTHON" ]] || die "Existing ScreenSync virtualenv not found"
systemctl is-active --quiet "$SERVICE" || die "Media service is not running; investigate before any changes"
UNIT_DIR="$(systemctl show --property=WorkingDirectory --value "$SERVICE")"
[[ "$UNIT_DIR" == "$EXPECTED_DIRECTORY" ]] || die "Unexpected media service working directory: $UNIT_DIR"
systemctl cat "$SERVICE" | grep -Fq "/opt/screensync-relay/.venv/bin/python media_relay.py" ||
    die "Existing service ExecStart differs from reviewed template"
if command -v nginx >/dev/null; then
    nginx -t >/dev/null 2>&1 || die "Existing nginx configuration is not valid"
fi

log "Before: $(sha256sum "$TARGET" | cut -d' ' -f1)"
log "Media unit: $(systemctl is-active "$SERVICE")"
log "Safe scope: media_relay.py only; no firewall, nginx, x-ui or WireGuard changes"

TEMP="$(mktemp "${EXPECTED_DIRECTORY}/.media-relay-jpeg.XXXXXXXX")"
curl --connect-timeout 8 --max-time 35 -fsSL "$SOURCE_URL" -o "$TEMP" ||
    die "Unable to download pinned GitHub media implementation"
[[ -s "$TEMP" ]] || die "Downloaded Python module was empty"
"$PYTHON" - "$TEMP" <<'PY' || die "Invalid JPEG media Python source"
import ast, sys
source = open(sys.argv[1], "r", encoding="utf-8").read()
ast.parse(source)
for symbol in ("def legal_video_packet", "def legal_jpeg_packet",
               "class MediaRelay", "async def receive_video"):
    assert symbol in source, f"missing {symbol}"
PY
if cmp -s "$TEMP" "$TARGET"; then
    log "Already up-to-date; no restart needed"
    exit 0
fi

BACKUP="/root/screensync-media-only-backup-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 700 -- "$BACKUP" || die "Cannot create backup directory"
cp -p -- "$TARGET" "$BACKUP/media_relay.py" ||
    die "Cannot back up old media relay"
sha256sum "$BACKUP/media_relay.py" > "$BACKUP/media_relay.py.sha256"
log "Backup: $BACKUP/media_relay.py"

chown --reference="$TARGET" "$TEMP"
chmod --reference="$TARGET" "$TEMP"
mv -f -- "$TEMP" "$TARGET"
TEMP=""

# A failed media restart or validation restores the previous media relay.
rollback() {
    log "JPEG media validation failed; restoring previous media module"
    cp -p -- "$BACKUP/media_relay.py" "$TARGET"
    if systemctl restart "$SERVICE" && systemctl is-active --quiet "$SERVICE"; then
        log "Rollback restored original media service"
    else
        log "URGENT: rollback restart failed, inspect systemctl status $SERVICE"
    fi
    exit 1
}
if ! systemctl restart "$SERVICE"; then rollback; fi
sleep 2
if ! systemctl is-active --quiet "$SERVICE"; then rollback; fi
if ! (cd "$EXPECTED_DIRECTORY" && "$PYTHON" - <<'PY'
import media_relay as media
jpeg = bytes([2, 0]) + bytes(8) + bytes.fromhex("ffd8") + bytes(68) + bytes.fromhex("ffd9")
assert media.legal_jpeg_packet(jpeg)
assert not media.legal_video_packet(jpeg)
assert media.legal_video_packet(bytes([1, 1]) + bytes(8) + b"h264")
print("PASS: JPEG mode and existing H264 mode remain available")
PY
); then rollback; fi
if command -v ss >/dev/null; then
    if ! ss -lnt | awk '$4 ~ /:8767$/ {found=1} END {exit !found}'; then rollback; fi
fi
log "SUCCESS: only $SERVICE updated; media 127.0.0.1:8767 is listening"
log "After: $(sha256sum "$TARGET" | cut -d' ' -f1)"
log "Existing HTTPS/WSS :8443 and signaling service were not modified"
log "Backups retained at: $BACKUP"
log "Next: two-phone owner-approved JPEG WSS session test required"
