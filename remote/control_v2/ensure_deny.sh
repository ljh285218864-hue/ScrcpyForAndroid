#!/usr/bin/env bash
# Applied by the dedicated Beta4 wg-quick PreUp hook BEFORE the interface
# receives its WireGuard cryptographic keys or opens UDP 51821.
# Ensures traffic is denied if the API/control service is not available.
set -euo pipefail

for bin in nft; do command -v "$bin" >/dev/null || exit 1; done
TABLE="screensync_beta4"
INTERFACE="wg-ss-beta4"

RULES="$(
  if nft list table inet "$TABLE" >/dev/null 2>&1; then
    printf 'delete table inet %s\n' "$TABLE"
  fi
  cat <<EOF
add table inet $TABLE
add chain inet $TABLE forward { type filter hook forward priority -150; policy accept; }
add chain inet $TABLE input { type filter hook input priority -150; policy accept; }
add rule inet $TABLE forward iifname "$INTERFACE" drop
add rule inet $TABLE forward oifname "$INTERFACE" drop
add rule inet $TABLE input iifname "$INTERFACE" drop
EOF
)"
# Single nft transaction. No ACCEPTs are installed until control server
# verifies persisted approvals and replaces the table with its own ACL.
printf '%s\n' "$RULES" | nft -f -
