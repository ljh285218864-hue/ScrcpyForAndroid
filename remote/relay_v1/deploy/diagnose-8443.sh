#!/usr/bin/env bash
# ScreenSync Beta4 HTTPS 8443 troubleshooting; READ ONLY.
# Does not install, change firewall rules, restart units, or output TLS private keys.
set -uo pipefail
HOST="relay.privyluxe.com"
PORT=8443
section() { printf '\n===== %s =====\n' "$1"; }
note() { printf 'NOTE: %s\n' "$1"; }
check() { if command -v "$1" >/dev/null 2>&1; then return 0; else note "$1 unavailable"; return 1; fi; }

section "identity and host"
date -u '+UTC %Y-%m-%dT%H:%M:%SZ'
hostname || true
if [[ -r /etc/os-release ]]; then . /etc/os-release; printf '%s\n' "${PRETTY_NAME:-Unknown OS}"; fi
if check getent; then getent ahostsv4 "$HOST" | awk '!seen[$1]++ {print "DNS A:",$1}' || true; fi

section "resources (no mutation)"
if check free; then free -m; fi
if check df; then df -h /; fi
if [[ -r /proc/meminfo ]]; then
  awk '$1=="MemAvailable:" {printf "MemAvailable: %.0f MiB\n", $2/1024}' /proc/meminfo
fi

section "TCP listeners: relay and existing Xray HTTPS"
if check ss; then
  ss -H -lntp 2>/dev/null | awk '$4 ~ /:(80|443|8443|8766|8767)$/ {print}' || true
  if ! ss -H -lnt 2>/dev/null | awk '$4 ~ /:8443$/ {hit=1} END {exit !hit}'; then
    note "NO TCP listener detected on 8443. Loading a config file alone will not open the port."
  fi
fi

section "service state: no restarts"
if check systemctl; then
  for name in nginx caddy screensync-signaling screensync-media x-ui 3x-ui xray wg-quick@wg0 wg-quick@wg-screensync; do
    printf '%-29s ' "$name"
    systemctl is-active "$name" 2>/dev/null || true
  done
  for name in screensync-signaling screensync-media; do
    if systemctl list-unit-files "$name.service" --no-legend 2>/dev/null | grep -q "^$name.service "; then
      printf '%s: configured unit is present\n' "$name"
    else
      note "$name unit file not installed"
    fi
  done
fi

section "nginx configuration validation (read only)"
if check nginx; then
  nginx -t 2>&1 || note "nginx -t failed; inspect certificate path, duplicate server block or directive context."
  for candidate in /etc/nginx/conf.d/screensync-relay.conf /etc/nginx/sites-enabled/screensync-relay /etc/nginx/sites-enabled/relay.privyluxe.com; do
    if [[ -e "$candidate" ]]; then printf 'Config exists: %s\n' "$candidate"; fi
  done
fi

section "certificate metadata (never print private key)"
CERT="/etc/letsencrypt/live/$HOST/fullchain.pem"
KEY="/etc/letsencrypt/live/$HOST/privkey.pem"
if [[ -r "$CERT" ]] && check openssl; then
  openssl x509 -in "$CERT" -noout -dates -checkhost "$HOST" 2>&1 || true
else
  note "Expected Lets Encrypt certificate not readable: $CERT"
fi
if [[ ! -r "$KEY" ]]; then note "Expected TLS private key not readable as current user: $KEY"; fi
note "Missing certificate or key can prevent nginx from loading the 8443 config."

section "loopback HTTP backend (no authentication)"
if check curl; then
  for url in "http://127.0.0.1:8766/health" "https://$HOST:8443/health"; do
    printf 'URL %s => ' "$url"
    if [[ "$url" == https:* ]]; then
      curl --noproxy '*' --resolve "$HOST:8443:127.0.0.1" --connect-timeout 3 --max-time 7 -sS -o /dev/null -w 'HTTP %{http_code} / TLS %{ssl_verify_result}\n' "$url" || note "Local 8443 HTTPS path failed."
    else
      curl --noproxy '*' --connect-timeout 3 --max-time 7 -sS -o /dev/null -w 'HTTP %{http_code}\n' "$url" || note "Local signaling API is not responding."
    fi
  done
fi

section "firewall state (no changes)"
if check ufw; then ufw status verbose 2>/dev/null | head -n 18 || true; fi
if check nft; then nft list tables 2>/dev/null || true; fi
note "Provider-side Evoxt firewall must be checked separately. A local 8443 listener can still be blocked at the provider."

section "next safe decision"
note "If nginx is missing the relay config, first verify a valid certificate and resource headroom, then stage the dedicated 8443 config and run nginx -t BEFORE reloading nginx."
note "If 8443 is listening locally but refused externally, inspect local/provider firewall, bind address, and public IPv4 mapping."
note "If HTTPS /health works but /media fails, inspect the separate loopback media service on 8767."
note "No server configuration or existing proxy/VPN service was changed."
