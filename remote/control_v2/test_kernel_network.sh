#!/usr/bin/env bash
# Real Linux WireGuard + nft integration test on a disposable CI runner only.
# No production servers, no real client secrets, no existing network changes.
set -euo pipefail
umask 077
[[ $EUID -eq 0 ]] || { echo "Root needed in disposable test VM"; exit 1; }
for bin in ip wg nft ping python3 sysctl; do command -v "$bin" >/dev/null || exit 1; done
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
P="ss4$$"
S="$P""s"; A="$P""a"; B="$P""b"; C="$P""c"; D="$P""d"
UNIT="ss4-test-$P.service"

cleanup() {
  if [[ -f "/run/systemd/system/$UNIT" ]]; then
    systemctl stop "$UNIT" >/dev/null 2>&1 || true
    rm -f "/run/systemd/system/$UNIT"
    systemctl daemon-reload >/dev/null 2>&1 || true
  fi
  for ns in "$S" "$A" "$B" "$C" "$D"; do ip netns del "$ns" >/dev/null 2>&1 || true; done
  rm -rf "$TMP"
}
trap cleanup EXIT

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

for ns in "$S" "$A" "$B" "$C" "$D"; do
  ip netns add "$ns"
  ip -n "$ns" link set lo up
done
for i in 1 2 3 4; do
  case "$i" in
    1) client="$A";; 2) client="$B";; 3) client="$C";; 4) client="$D";;
  esac
  ip link add "$P""u$i" type veth peer name "$P""d$i"
  ip link set "$P""u$i" netns "$S"
  ip link set "$P""d$i" netns "$client"
  ip -n "$S" link set "$P""u$i" name "uplink$i"
  ip -n "$client" link set "$P""d$i" name eth0
  ip -n "$S" addr add "172.27.$i.1/30" dev "uplink$i"
  ip -n "$client" addr add "172.27.$i.2/30" dev eth0
  ip -n "$S" link set "uplink$i" up
  ip -n "$client" link set eth0 up
done
ip netns exec "$S" sysctl -qw net.ipv4.ip_forward=1

# Simulate an intact Beta3 interface on the same isolated server namespace.
ip -n "$S" link add wg-screensync type wireguard
ip -n "$S" addr add 100.80.0.1/24 dev wg-screensync
ip -n "$S" link set wg-screensync up
wg genkey > "$TMP/old.key"
ip netns exec "$S" wg set wg-screensync private-key "$TMP/old.key" listen-port 51820
legacy_before="$(ip netns exec "$S" wg show wg-screensync dump | sha256sum | cut -d' ' -f1)"

# Separate Beta4 interface on 51821; independently keyed client tunnels.
ip -n "$S" link add wg-ss-beta4 type wireguard
ip -n "$S" addr add 100.81.0.1/16 dev wg-ss-beta4
ip -n "$S" link set wg-ss-beta4 up
wg genkey > "$TMP/server.key"
ip netns exec "$S" wg set wg-ss-beta4 private-key "$TMP/server.key" listen-port 51821
server_pub="$(wg pubkey < "$TMP/server.key")"
for i in 1 2 3 4; do
  case "$i" in
    1) client="$A";; 2) client="$B";; 3) client="$C";; 4) client="$D";;
  esac
  wg genkey > "$TMP/client$i.key"
  wg pubkey < "$TMP/client$i.key" > "$TMP/client$i.pub"
  ip -n "$client" link add wg4 type wireguard
  ip -n "$client" addr add "100.81.0.$((i + 1))/32" dev wg4
  ip netns exec "$client" wg set wg4 private-key "$TMP/client$i.key" \
    peer "$server_pub" allowed-ips 100.81.0.0/16 \
    endpoint "172.27.$i.1:51821" persistent-keepalive 5
  ip -n "$client" link set wg4 up
  ip -n "$client" route add 100.81.0.0/16 dev wg4
done

driver() {
  ip netns exec "$S" env \
    PYTHONPATH="$ROOT/remote/control_v2" \
    SCREENSYNC_DB="$TMP/test.db" \
    SCREENSYNC_WG_INTERFACE=wg-ss-beta4 \
    SCREENSYNC_WG_ENDPOINT=172.27.1.1:51821 \
    SCREENSYNC_ADMIN_SECRET=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
    SCREENSYNC_MAX_PAIRS=2 \
    SS4_PUB_1="$(cat "$TMP/client1.pub")" \
    SS4_PUB_2="$(cat "$TMP/client2.pub")" \
    SS4_PUB_3="$(cat "$TMP/client3.pub")" \
    SS4_PUB_4="$(cat "$TMP/client4.pub")" \
    python3 "$ROOT/remote/control_v2/netns_worker.py" "$1"
}

can_ping() {
  local try
  for try in 1 2 3; do
    if ip netns exec "$1" ping -n -c 1 -W 2 "$2" >/dev/null 2>&1; then return 0; fi
  done
  return 1
}
must_pass() {
  if ! can_ping "$1" "$2"; then
    echo "DIAGNOSTIC: failed authorized path; nft and peer IP routes follow"
    ip netns exec "$S" nft list table inet screensync_beta4 || true
    ip netns exec "$S" wg show wg-ss-beta4 allowed-ips || true
    ip -n "$1" route get "$2" || true
    ip netns exec "$1" ping -n -c 1 -W 2 "$2" || true
    fail "Expected authorized packet $1 -> $2"
  fi
  pass "Allowed $1 -> $2"
}
must_drop() {
  if ip netns exec "$1" ping -n -c 1 -W 1 "$2" >/dev/null 2>&1; then
    fail "Unauthorized packet forwarded $1 -> $2"
  fi
  pass "Blocked $1 -> $2"
}

prime_client() {
  # The server cannot forward a WireGuard packet to a new mobile peer until
  # that peer has *initiated* its own encrypted handshake. Force that first
  # handshake even though nft drops ICMP to the VPS's inner IP.
  local client="$1" try
  for try in 1 2 3 4; do
    ip netns exec "$client" ping -n -c 1 -W 1 100.81.0.1 >/dev/null 2>&1 || true
    if ip netns exec "$client" wg show wg4 latest-handshakes |
      awk 'NR==1 && $2>0 {ok=1} END{exit !ok}'; then
      pass "Encrypted WireGuard handshake established for $client"
      return 0
    fi
    sleep 1
  done
  fail "Newly provisioned peer did not establish a WireGuard handshake: $client"
}

driver prepare
must_drop "$A" 100.81.0.3
must_drop "$C" 100.81.0.5
must_drop "$A" 100.81.0.1
pass "Unpaired devices denied"

driver approve1
prime_client "$A"
prime_client "$B"
must_pass "$A" 100.81.0.3
must_pass "$B" 100.81.0.2
must_drop "$C" 100.81.0.5
must_drop "$A" 100.81.0.4

driver approve2
prime_client "$C"
prime_client "$D"
must_pass "$C" 100.81.0.5
must_pass "$D" 100.81.0.4
must_drop "$A" 100.81.0.4
must_drop "$B" 100.81.0.5
must_drop "$C" 100.81.0.2
must_drop "$C" 100.81.0.1
pass "Two customer groups isolated from each other and VPS services"

driver revoke1
must_drop "$A" 100.81.0.3
must_pass "$C" 100.81.0.5
pass "Unpair removes group1 access without harming group2"

# Reconstruct runtime from the persisted DB after a simulated program restart.
driver restart
must_drop "$A" 100.81.0.3
must_pass "$C" 100.81.0.5
must_drop "$A" 100.81.0.4
pass "State reload only restores still-approved groups"

# Exercise an ACTUAL systemd ExecStopPost on disposable GitHub Actions VM.
# Systemd runs the real Python control service inside the isolated server
# namespace, kills it, denies via nft, then restarts to reconcile authorized
# state. This still does not validate the Evoxt-specific production unit.
ip_bin="$(command -v ip)"
nft_bin="$(command -v nft)"
python_bin="$(command -v python3)"
cat > "/run/systemd/system/$UNIT" <<EOF
[Unit]
Description=Temporary ScreenSync Beta4 disposable namespace crash test
[Service]
Type=simple
Environment=SCREENSYNC_DB=$TMP/test.db
Environment=SCREENSYNC_WG_INTERFACE=wg-ss-beta4
Environment=SCREENSYNC_WG_ENDPOINT=172.27.1.1:51821
Environment=SCREENSYNC_ADMIN_SECRET=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
Environment=SCREENSYNC_MAX_PAIRS=2
ExecStart=$ip_bin netns exec $S $python_bin $ROOT/remote/control_v2/server.py
ExecStopPost=-$ip_bin netns exec $S $nft_bin insert rule inet screensync_beta4 forward iifname wg-ss-beta4 oifname wg-ss-beta4 drop
Restart=no
EOF
systemctl daemon-reload
systemctl start "$UNIT"
systemctl is-active --quiet "$UNIT" || fail "Temporary systemd service did not start"
# Type=simple returns before Python binds 127.0.0.1; wait for the actual
# application health endpoint instead of assuming the unit is immediately ready.
api_up=0
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if ip netns exec "$S" "$python_bin" -c 'from urllib.request import urlopen; assert b"ok" in urlopen("http://127.0.0.1:8765/health", timeout=2).read()' >/dev/null 2>&1; then
    api_up=1
    break
  fi
  sleep 0.2
done
if [[ "$api_up" != 1 ]]; then
  systemctl status "$UNIT" --no-pager || true
  journalctl -u "$UNIT" -n 20 --no-pager || true
  fail "Systemd unit started but control API did not become healthy"
fi
pass "Real systemd service and loopback-only HTTP health endpoint active"

systemctl kill --kill-whom=main --signal=SIGKILL "$UNIT"
hook_found=0
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if ip netns exec "$S" nft list chain inet screensync_beta4 forward |
    grep -q 'iifname "wg-ss-beta4" oifname "wg-ss-beta4" drop'; then
    hook_found=1
    break
  fi
  sleep 0.2
done
[[ "$hook_found" == 1 ]] || fail "Actual systemd abnormal exit did not install fail-closed nft rule"
must_drop "$C" 100.81.0.5
pass "Actual SIGKILL of control service triggered fail-closed nft isolation"

systemctl restart "$UNIT"
systemctl is-active --quiet "$UNIT" || fail "Control service failed to restart"
must_drop "$A" 100.81.0.3
must_pass "$C" 100.81.0.5
must_drop "$A" 100.81.0.4
pass "Actual systemd restart restored only persisted authorized pair"


legacy_after="$(ip netns exec "$S" wg show wg-screensync dump | sha256sum | cut -d' ' -f1)"
[[ "$legacy_before" == "$legacy_after" ]] || fail "Old Beta3 WireGuard modified"
pass "Original Beta3 51820 interface unchanged"
ip netns exec "$S" wg show wg-ss-beta4 latest-handshakes |
  awk '{if ($2 > 0) success++} END {exit !(success >= 2)}' ||
  fail "No real WireGuard kernel handshakes"
pass "Real WireGuard encrypted handshakes observed"
echo "PASS: all disposable kernel-network tests complete"
