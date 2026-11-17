#!/usr/bin/env bash
# ScreenSync Beta4 deployment preflight. READ ONLY.
# Does not install software, write files, change ports, or restart services.
set -euo pipefail
issues=0
warnings=0
ok() { printf '[OK] %s\n' "$*"; }
stop() { printf '[STOP] %s\n' "$*" >&2; issues=$((issues+1)); }
warn() { printf '[CHECK] %s\n' "$*"; warnings=$((warnings+1)); }

echo "ScreenSync Beta4 read-only VPS preflight"
date -u '+Date: %Y-%m-%dT%H:%M:%SZ'
if [[ -r /etc/os-release ]]; then
  (source /etc/os-release; echo "Operating system: $PRETTY_NAME")
fi

mem_total="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
mem_available="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
swap_total="$(awk '/^SwapTotal:/ {print $2}' /proc/meminfo)"
disk_free="$(df -Pk / | awk 'NR == 2 {print $4}')"
printf 'MemTotal: %s KiB; MemAvailable: %s KiB; Swap: %s KiB; free disk: %s KiB\n' \
  "$mem_total" "$mem_available" "$swap_total" "$disk_free"

if (( mem_available < 393216 )); then
  stop "可用内存不足 384MiB，当前禁止执行 Beta4 安装器"
else
  ok "可用内存满足实验部署最低检查值"
fi
if (( swap_total == 0 && mem_total < 1048576 )); then
  warn "总内存小于 1GiB 且没有 Swap，存在 OOM 风险，优先升级 VPS"
fi
if (( disk_free < 1048576 )); then
  stop "根盘剩余空间不足 1GiB"
else
  ok "根盘至少有 1GiB 可用空间"
fi

for bin in wg nft ip ss systemctl python3 curl; do
  if command -v "$bin" >/dev/null 2>&1; then
    ok "依赖已安装：$bin"
  else
    stop "缺少依赖：$bin"
  fi
done

if command -v wg >/dev/null 2>&1; then
  if wg show wg-screensync listen-port 2>/dev/null | grep -qx 51820; then
    ok "原有 Beta3 wg-screensync:51820 已存在"
  else
    warn "无法确认原有 wg-screensync:51820，非 root 用户可能缺少权限"
  fi
fi

if command -v ss >/dev/null 2>&1; then
  for spec in 'UDP:51821:u' 'TCP:8765:t' 'TCP:8443:t'; do
    IFS=: read -r label port family <<< "$spec"
    if [[ "$family" == u ]]; then
      found="$(ss -H -lun "sport = :$port" 2>/dev/null || true)"
    else
      found="$(ss -H -ltn "sport = :$port" 2>/dev/null || true)"
    fi
    if [[ -n "$found" ]]; then
      if [[ "$port" == 8443 ]]; then
        warn "$label 已占用，必须选择不冲突的 HTTPS 入口"
      else
        stop "$label 已被监听，禁止覆盖现有服务"
      fi
    else
      ok "$label 当前无本机监听（不代表云防火墙已开放）"
    fi
  done
  echo "Existing TCP 443 and WireGuard UDP 51820 listening addresses:"
  ss -H -ltn 'sport = :443' 2>/dev/null | awk '{print "TCP443 "$1" "$4}' || true
  ss -H -lun 'sport = :51820' 2>/dev/null | awk '{print "UDP51820 "$1" "$4}' || true
fi

if command -v systemctl >/dev/null 2>&1; then
  for unit in wg-quick@wg-screensync.service x-ui.service; do
    if systemctl is-active --quiet "$unit"; then
      ok "$unit active"
    else
      warn "$unit 未能确认 active，可能使用不同服务名"
    fi
  done
fi

if command -v ip >/dev/null 2>&1; then
  if ip link show wg-ss-beta4 >/dev/null 2>&1; then
    stop "wg-ss-beta4 接口已存在，必须确认是否是旧安装"
  else
    ok "独立 Beta4 接口名称未占用"
  fi
fi

for item in /etc/wireguard/wg-ss-beta4.conf \
            /etc/systemd/system/screensync-control-v2.service \
            /etc/screensync-v2/registration.env \
            /var/lib/screensync-v2/registration.db; do
  if [[ -e "$item" ]]; then
    stop "发现现有 Beta4 文件：$item，禁止重复部署"
  fi
done

echo "------------------"
echo "Blocking findings: $issues; other checks: $warnings"
if (( issues > 0 )); then
  echo "Result: BLOCKED. No configuration was changed."
  exit 1
fi
echo "Result: local preflight passed; still needs backup recovery,"
echo "real x-ui/Reality handshake, HTTPS/DNS, firewall and device tests."
