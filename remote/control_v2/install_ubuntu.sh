#!/usr/bin/env bash
# Provision an independent WireGuard service for the experimental multi-pair backend.
# Never run as the old Beta3 install script. Changes only Beta4-specific paths.
set -euo pipefail
umask 077

die() { echo "ScreenSync Beta4 安全停止：$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "必须使用 sudo/root"
[[ -f remote/control_v2/server.py ]] || die "请在仓库根目录执行"
IFACE="wg-ss-beta4"
POOL="100.81.0.1/16"
PORT="51821"
ENDPOINT="$(printenv SCREENSYNC_WG_ENDPOINT || echo '')"
[[ "$ENDPOINT" =~ ^[A-Za-z0-9.-]+:51821$ ]] ||
  die "须显式指定 SCREENSYNC_WG_ENDPOINT=公网IP:51821，不能使用 Beta3 端口"
[[ "$ENDPOINT" != *..* ]] || die "地址格式不合法"

for program in python3 wg wg-quick nft ip ss systemctl sysctl install; do
  command -v "$program" >/dev/null || die "缺少必需命令 $program，未安装任何服务"
done
# Refuse deployment on a memory-starved, disk-starved production VPS.
# In particular the previously observed 512MiB / 0-swap Evoxt VM is
# NOT approved merely because the experimental CI tests passed.
MEM_AVAILABLE_KB="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
DISK_AVAILABLE_KB="$(df -Pk / | awk 'NR==2 {print $4}')"
[[ "${MEM_AVAILABLE_KB:-0}" -ge 393216 ]] ||
  die "可用内存不足 384MiB；禁止在现有 VPS 上安装，请先升级或迁移"
[[ "${DISK_AVAILABLE_KB:-0}" -ge 1048576 ]] ||
  die "根盘可用空间不足 1GiB；禁止安装"
[[ "$(sysctl -n net.ipv4.ip_forward)" == "1" ]] ||
  die "尚未启用 IP 转发；不自动修改全局转发设置"
[[ ! -e "/etc/wireguard/$IFACE.conf" ]] ||
  die "独立 WireGuard 配置已存在，拒绝覆盖"
if ip link show "$IFACE" >/dev/null 2>&1; then
  die "独立接口 $IFACE 已存在，拒绝覆盖"
fi
if ss -H -lun '( sport = :51821 )' | grep -q .; then
  die "UDP 51821 已占用"
fi
if ss -H -lnt '( sport = :8765 )' | grep -q .; then
  die "TCP 8765 已占用"
fi
[[ ! -f /etc/systemd/system/screensync-control-v2.service ]] ||
  die "服务已安装，拒绝二次覆盖"
[[ ! -d /var/lib/screensync-v2 ]] ||
  die "存在历史数据库，请检查是否需要恢复，不能重置配对权限"
[[ ! -f /etc/screensync-v2/registration.env ]] ||
  die "存在历史管理员密钥，拒绝覆盖"
# Require the real old interface and its configuration to remain untouched.
if ! wg show wg-screensync >/dev/null 2>&1; then
  die "找不到原有 Beta3 接口，先核对服务器，不执行新网络初始化"
fi

BACKUP="/root/screensync-v2-before-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 700 -p "$BACKUP"
wg show > "$BACKUP/wg-before.txt"
nft list ruleset > "$BACKUP/nft-before.txt"
ip -j address > "$BACKUP/ip-before.json"
systemctl list-units '*x-ui*' --no-pager > "$BACKUP/x-ui-units.txt" || true
chmod -R go-rwx "$BACKUP"

mkdir -m 700 -p /etc/screensync-v2 /var/lib/screensync-v2
install -d -m 755 /usr/local/libexec/screensync-v2
install -m 755 remote/control_v2/server.py /usr/local/libexec/screensync-v2/server.py
install -m 755 remote/control_v2/ensure_deny.sh /usr/local/libexec/screensync-v2/ensure_deny.sh

WGKEY="$(wg genkey)"
cat >"/etc/wireguard/$IFACE.conf" <<EOF
[Interface]
Address = $POOL
ListenPort = $PORT
PrivateKey = $WGKEY
# Install fail-closed firewall BEFORE WireGuard keys and UDP listener activate.
PreUp = /usr/local/libexec/screensync-v2/ensure_deny.sh
EOF
chmod 600 "/etc/wireguard/$IFACE.conf"
unset WGKEY

ADMIN="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
cat >/etc/screensync-v2/registration.env <<EOF
SCREENSYNC_DB=/var/lib/screensync-v2/registration.db
SCREENSYNC_WG_INTERFACE=$IFACE
SCREENSYNC_WG_ENDPOINT=$ENDPOINT
SCREENSYNC_ADMIN_SECRET=$ADMIN
SCREENSYNC_LISTEN_PORT=8765
SCREENSYNC_MAX_PAIRS=2
EOF
chmod 600 /etc/screensync-v2/registration.env
unset ADMIN

cat >/etc/systemd/system/screensync-control-v2.service <<'EOF'
[Unit]
Description=ScreenSync Beta4 isolated multi-pair control plane (experimental)
Requires=wg-quick@wg-ss-beta4.service
After=wg-quick@wg-ss-beta4.service

[Service]
Type=simple
User=root
Group=root
EnvironmentFile=/etc/screensync-v2/registration.env
ExecStart=/usr/bin/python3 /usr/local/libexec/screensync-v2/server.py
# If API stops, prepend a deny rule on the independent interface.
ExecStopPost=-/usr/sbin/nft insert rule inet screensync_beta4 forward iifname "wg-ss-beta4" oifname "wg-ss-beta4" drop
Restart=on-failure
RestartSec=3
NoNewPrivileges=yes
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_RAW
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW
ProtectSystem=strict
ReadWritePaths=/var/lib/screensync-v2
ProtectHome=true
PrivateTmp=true
ProtectControlGroups=true
ProtectKernelTunables=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
UMask=0077

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now "wg-quick@$IFACE"
systemctl enable --now screensync-control-v2
sleep 1
if ! systemctl is-active --quiet screensync-control-v2; then
  echo "注册服务未正常启动。没有触碰旧 Beta3 或 x-ui。备份：$BACKUP"
  echo "查看：sudo journalctl -u screensync-control-v2 -n 40 --no-pager"
  exit 1
fi

echo "独立接口 $IFACE 已部署，监听 UDP 51821"
echo "注册服务仅监听 127.0.0.1:8765；旧 Beta3 UDP 51820 不变"
echo "备份目录：$BACKUP"
echo "现在必须另行检查并配置 HTTPS、DNS 和云防火墙；不要公开 8765"
echo "管理员可在可信服务器终端发放开通码："
echo "  sudo --preserve-env=SCREENSYNC_WG_ENDPOINT bash remote/control_v2/issue_owner_qr.sh"
