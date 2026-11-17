#!/usr/bin/env bash
# Read-only discovery; never installs, kills, opens or rewrites services.
set -eu
printf '\n--- Existing listener/port ownership (including old services) ---\n'
if command -v ss >/dev/null 2>&1; then
  ss -lntup 2>/dev/null | grep -E 'State|LISTEN|:443 |:80 |:8766 |:8767 ' || true
fi
printf '\n--- Free disk and available memory ---\n'
df -h /
free -m 2>/dev/null || true
printf '\n--- Existing service status (do not restart) ---\n'
for service in x-ui 3x-ui xray wg-quick@wg0 screensync-signaling screensync-media; do
  systemctl is-active "$service" 2>/dev/null || true
done
printf '\n--- No configuration has been changed ---\n'
