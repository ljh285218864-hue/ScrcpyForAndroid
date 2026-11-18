#!/usr/bin/env bash
# ScreenSync Beta4 No-VPN VPS preflight. READ ONLY: no install/restart/firewall changes.
# Run on the target VPS as a trusted administrator after taking a provider snapshot.
set -uo pipefail

MIN_AVAILABLE_MIB="${MIN_AVAILABLE_MIB:-384}"
MIN_DISK_MIB="${MIN_DISK_MIB:-1024}"
blocked=0

section() { printf '\n--- %s ---\n' "$1"; }
warn() { printf 'WARNING: %s\n' "$1"; }
block() { printf 'BLOCKER: %s\n' "$1"; blocked=1; }

section "Host and OS"
uname -sr
if [[ -r /etc/os-release ]]; then
  ( . /etc/os-release; printf 'OS: %s\n' "${PRETTY_NAME:-unknown}" )
fi

section "RAM: available, not total"
if [[ -r /proc/meminfo ]]; then
  available_kib="$(awk '$1=="MemAvailable:" {print $2; exit}' /proc/meminfo)"
  if [[ "${available_kib:-}" =~ ^[0-9]+$ ]]; then
    available_mib=$((available_kib / 1024))
    printf 'MemAvailable: %s MiB (minimum pre-deployment guardrail: %s MiB)\n' "$available_mib" "$MIN_AVAILABLE_MIB"
    if (( available_mib < MIN_AVAILABLE_MIB )); then
      block "Low available RAM. Do not deploy relay services to this VPS before reassessing resources."
    fi
  else
    block "Cannot read MemAvailable from /proc/meminfo."
  fi
else
  block "Cannot access /proc/meminfo."
fi
if command -v free >/dev/null 2>&1; then free -m || true; fi
if command -v swapon >/dev/null 2>&1; then
  printf 'Swap summary (read only):\n'
  swapon --show 2>/dev/null || true
fi

section "Disk free on root filesystem"
if command -v df >/dev/null 2>&1; then
  df -h /
  disk_kib="$(df -Pk / | awk 'NR==2 {print $4}')"
  if [[ "${disk_kib:-}" =~ ^[0-9]+$ ]]; then
    disk_mib=$((disk_kib / 1024))
    printf 'Available disk: %s MiB (minimum pre-deployment guardrail: %s MiB)\n' "$disk_mib" "$MIN_DISK_MIB"
    if (( disk_mib < MIN_DISK_MIB )); then
      block "Low disk space. Do not install or build on this VPS."
    fi
  else
    block "Cannot determine free disk space."
  fi
else
  block "df is unavailable."
fi

section "TCP and UDP listening ports; NO changes"
printf 'Check existing 3X-UI/Reality (80/443), old WG (51820), new HTTPS/WSS (8443/8766/8767), and Beta4 WG leftovers (51821/8765).\n'
if command -v ss >/dev/null 2>&1; then
  # Include UDP UNCONN sockets; ss -lntp alone would silently miss WireGuard.
  ss -lntup 2>/dev/null | awk 'NR == 1 || $5 ~ /:(80|443|8443|8765|8766|8767|51820|51821)$/'
  printf 'If a needed port is already owned, STOP and review; never stop or overwrite its owner automatically.\n'
else
  block "ss is unavailable; cannot inspect TCP/UDP listener ownership."
fi

section "Existing systemd service activity (observation only)"
if command -v systemctl >/dev/null 2>&1; then
  for unit in x-ui 3x-ui xray wg-quick@wg0 wg-quick@wg-screensync screensync-signaling screensync-media; do
    printf '%-35s ' "$unit"
    systemctl is-active "$unit" 2>/dev/null || true
  done
  printf 'Service names can vary: verify actual 3X-UI/Reality and legacy WG units manually.\n'
else
  warn "systemctl unavailable; check existing services manually."
fi

section "WireGuard interface names and listener ports (no peer keys)"
if command -v wg >/dev/null 2>&1; then
  wg show interfaces 2>/dev/null || warn "Cannot list interfaces without sufficient read permission."
  for name in wg0 wg-screensync wg-ss-beta4; do
    port="$(wg show "$name" listen-port 2>/dev/null || true)"
    if [[ -n "$port" ]]; then printf '%s UDP listen-port: %s\n' "$name" "$port"; fi
  done
else
  warn "wg command not found. Confirm whether legacy WireGuard runs elsewhere."
fi

section "Firewall tables (names only; NO mutation)"
if command -v nft >/dev/null 2>&1; then
  nft list tables 2>/dev/null || warn "nft table listing unavailable without sufficient privileges."
else
  warn "nft not installed; inspect active firewall before deploying."
fi

section "Optional intended HTTPS host DNS"
if [[ -n "${SCREENSYNC_HOSTNAME:-}" ]]; then
  if command -v getent >/dev/null 2>&1; then
    getent ahostsv4 "$SCREENSYNC_HOSTNAME" || warn "Hostname did not resolve via system resolver."
  else
    warn "getent unavailable; check hostname DNS manually."
  fi
else
  printf 'Set SCREENSYNC_HOSTNAME in the shell to check a chosen hostname; no DNS change is made.\n'
fi

section "Result and hard deployment gates"
if (( blocked != 0 )); then
  printf 'PREFLIGHT BLOCKED: insufficient resources or missing essential inspection data. No changes made.\n'
  exit 2
fi
printf 'READ-ONLY PREFLIGHT COMPLETED. No changes made. This is NOT deployment approval.\n'
printf 'Still required: provider snapshot/rollback rehearsal, port-owner review, external x-ui/Reality and legacy WG handshake tests, valid TLS, and two-phone consented video test.\n'
