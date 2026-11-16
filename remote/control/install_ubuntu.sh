#!/usr/bin/env bash
# Guarded installer for an EXISTING Ubuntu WireGuard VPS.
# Does not change the 3X-UI / VLESS / Reality service or open public ports.
set -euo pipefail
umask 077

if [[ $EUID -ne 0 ]]; then echo "请使用 sudo/root"; exit 1; fi
# This first Beta4 prototype supports only one group. It must not be deployed
# from a repository that includes the isolated multi-group implementation.
if [[ -f remote/control_v2/server.py ]]; then
  echo "安全停止：旧安装器只支持一对手机。请审查 remote/control_v2/README.md 后选择新版独立接口部署。"
  exit 1
fi
if [[ ! -f remote/control/server.py ]]; then
  echo "请在 ScrcpyForAndroid 仓库根目录运行"; exit 1
fi
IFACE="$(printenv SCREENSYNC_WG_INTERFACE || echo wg-ss-beta4)"
ENDPOINT="$(printenv SCREENSYNC_WG_ENDPOINT || echo "")"
[[ "$IFACE" =~ ^[a-zA-Z0-9_.-]{1,15}$ ]] || { echo "WireGuard 接口名称无效"; exit 1; }
[[ "$ENDPOINT" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]] || { echo "WireGuard Endpoint 无效"; exit 1; }

[[ "$IFACE" != "wg-screensync" && "${ENDPOINT##*:}" != "51820" ]] || { echo "Beta3 接口和端口已保留"; exit 1; }

for binary in wg iptables iptables-save python3 systemctl ss install; do
  command -v "$binary" >/dev/null || { echo "缺少 $binary，停止部署"; exit 1; }
done
if ! wg show "$IFACE" >/dev/null 2>&1; then
  echo "未发现接口 $IFACE。请先 sudo wg show 核对名称。"; exit 1
fi
PORT="$(wg show "$IFACE" listen-port)"
[[ "$PORT" == "${ENDPOINT##*:}" ]] || {
  echo "现有 WireGuard 使用端口 $PORT，与配置的 Endpoint 不一致。停止部署。"; exit 1;
}
# Installation is only safe on a dedicated, empty WireGuard interface.
# The legacy Beta3 interface contains pre-registered Xiaomi/vivo peers.
# Never overwrite their AllowedIPs or insert a broad FORWARD chain ahead of them.
if [[ -n "$(wg show "$IFACE" peers)" ]]; then
  echo "安全停止：接口 $IFACE 已登记 WireGuard peers（可能是 Beta3）。"
  echo "Beta4 需要独立接口与未占用的 UDP 端口，禁止复用当前接口。"
  echo "尚未创建文件、备份或改动防火墙。请先完成隔离网络设计。"
  exit 1
fi
if ss -H -lnt '( sport = :8765 )' | grep -q .; then
  echo "回环端口 8765 已占用，停止部署。"; exit 1
fi
if [[ -f /etc/screensync/registration.env || -f /etc/systemd/system/screensync-control.service ]]; then
  echo "发现已有注册服务，不覆盖现有配置。"; exit 1
fi

BACKUP_DIR="/root/screensync-beta4-backup-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$BACKUP_DIR" /etc/screensync /var/lib/screensync /usr/local/libexec/screensync
chmod 700 "$BACKUP_DIR" /etc/screensync /var/lib/screensync
[[ ! -d /etc/wireguard ]] || cp -a /etc/wireguard "$BACKUP_DIR/wireguard"
wg show "$IFACE" > "$BACKUP_DIR/wg-before.txt"
iptables-save > "$BACKUP_DIR/iptables-before.txt"
chmod -R go-rwx "$BACKUP_DIR"

install -m 755 remote/control/server.py /usr/local/libexec/screensync/server.py
ACTIVATION_CODE="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
cat >/etc/screensync/registration.env <<EOF
SCREENSYNC_DB=/var/lib/screensync/registration.db
SCREENSYNC_BOOTSTRAP_CODE=$ACTIVATION_CODE
SCREENSYNC_WG_INTERFACE=$IFACE
SCREENSYNC_WG_ENDPOINT=$ENDPOINT
SCREENSYNC_LISTEN_PORT=8765
EOF
chmod 600 /etc/screensync/registration.env

cat >/etc/systemd/system/screensync-control.service <<'EOF'
[Unit]
Description=ScreenSync pair-protected private WireGuard registration
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=root
Group=root
EnvironmentFile=/etc/screensync/registration.env
ExecStart=/usr/bin/python3 /usr/local/libexec/screensync/server.py
Restart=on-failure
RestartSec=5
NoNewPrivileges=yes
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_RAW
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW
ProtectSystem=strict
ReadWritePaths=/var/lib/screensync
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
systemctl enable --now screensync-control
sleep 1
if ! systemctl is-active --quiet screensync-control; then
  echo "注册服务未启动，检查 sudo journalctl -u screensync-control -n 30"
  echo "备份目录：$BACKUP_DIR"
  exit 1
fi
echo "内部 API 已启动 127.0.0.1:8765；备份在 $BACKUP_DIR"
echo "尚未配置公开 HTTPS，请勿直接向公网开放 8765。"
echo "需配置 screensync.privyluxe.com 的有效 DNS/HTTPS/反向代理（建议 :8443）。"
echo "TLS 和端口检查完成后，使用 show_activation_qr.sh 显示管理员一次性二维码。"
