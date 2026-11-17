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

cleanup() {
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
must_pass() { can_ping "$1" "$2" || fail "Expected authorized packet $1 -> $2"; pass "Allowed $1 -> $2"; }
must_drop() {
  if ip netns exec "$1" ping -n -c 1 -W 1 "$2" >/dev/null 2>&1; then
    fail "Unauthorized packet forwarded $1 -> $2"
  fi
  pass "Blocked $1 -> $2"
}

driver prepare
must_drop "$A" 100.81.0.3
must_drop "$C" 100.81.0.5
must_drop "$A" 100.81.0.1
pass "Unpaired devices denied"

driver approve1
must_pass "$A" 100.81.0.3
must_pass "$B" 100.81.0.2
must_drop "$C" 100.81.0.5
must_drop "$A" 100.81.0.4

driver approve2
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

# Simulate the installer's ExecStopPost kernel rule. Actual systemd crash
# supervision still needs independent live service testing.
ip netns exec "$S" nft insert rule inet screensync_beta4 forward \
  iifname "wg-ss-beta4" oifname "wg-ss-beta4" drop
must_drop "$C" 100.81.0.5
pass "Fail-closed kernel rule blocks active group on simulated shutdown"

driver restart
must_drop "$A" 100.81.0.3
must_pass "$C" 100.81.0.5
must_drop "$A" 100.81.0.4
pass "State reload only restores still-approved groups"

legacy_after="$(ip netns exec "$S" wg show wg-screensync dump | sha256sum | cut -d' ' -f1)"
[[ "$legacy_before" == "$legacy_after" ]] || fail "Old Beta3 WireGuard modified"
pass "Original Beta3 51820 interface unchanged"
ip netns exec "$S" wg show wg-ss-beta4 latest-handshakes |
  awk '{if ($2 > 0) success++} END {exit !(success >= 2)}' ||
  fail "No real WireGuard kernel handshakes"
pass "Real WireGuard encrypted handshakes observed"
echo "PASS: all disposable kernel-network tests complete"
