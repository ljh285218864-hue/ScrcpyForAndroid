#!/usr/bin/env bash
# ScreenSync only: dedicated 2-phone WireGuard gateway on an Ubuntu VPS.
# Run explicitly as root on a VPS that you control, never on shared web hosting.
set -euo pipefail
umask 077

usage() {
  printf 'Usage: sudo bash %s --endpoint VPS_PUBLIC_IP_OR_DNS\n' "$0"
  printf 'This provisions ONE isolated 100.80.0.0/24 WireGuard network with 2 phone keys.\n'
}

ENDPOINT=""
while (($#)); do
  case "$1" in
    --endpoint)
      (($# >= 2)) || { usage; exit 2; }
      ENDPOINT="$2"; shift 2 ;;
    *) usage; exit 2 ;;
  esac
done
[[ $EUID -eq 0 ]] || { echo "必须在自己的 Ubuntu VPS 上以 root 运行"; exit 1; }
[[ -n "$ENDPOINT" && "$ENDPOINT" =~ ^[A-Za-z0-9.-]+$ ]] || { usage; exit 2; }
[[ "$ENDPOINT" != *..* && "$ENDPOINT" != .* && "$ENDPOINT" != *. ]] ||
  { echo "服务器地址不正确"; exit 2; }

IFACE="wg-screensync"
BASE="/etc/wireguard"
SERVER="$BASE/$IFACE.conf"
CLIENTS="$BASE/screensync-clients"
if [[ -e "$SERVER" || -e "$CLIENTS/phone-a.conf" || -e "$CLIENTS/phone-b.conf" ]]; then
  echo "ScreenSync 服务器/手机配置已经存在，为保护已有密钥，本脚本不会覆盖。"
  exit 1
fi
command -v apt-get >/dev/null || { echo "仅适用于有 apt-get 的 Ubuntu/Debian VPS"; exit 1; }
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y wireguard wireguard-tools iptables
command -v wg >/dev/null
command -v wg-quick >/dev/null

mkdir -p "$CLIENTS"
chmod 700 "$CLIENTS"
SERVER_PRIVATE="$(wg genkey)"
SERVER_PUBLIC="$(printf '%s' "$SERVER_PRIVATE" | wg pubkey)"
A_PRIVATE="$(wg genkey)"
A_PUBLIC="$(printf '%s' "$A_PRIVATE" | wg pubkey)"
B_PRIVATE="$(wg genkey)"
B_PUBLIC="$(printf '%s' "$B_PRIVATE" | wg pubkey)"

cat > "$SERVER" <<EOF
[Interface]
Address = 100.80.0.1/24
ListenPort = 51820
PrivateKey = $SERVER_PRIVATE
PostUp = iptables -A FORWARD -i $IFACE -o $IFACE -j ACCEPT
PostDown = iptables -D FORWARD -i $IFACE -o $IFACE -j ACCEPT

[Peer]
PublicKey = $A_PUBLIC
AllowedIPs = 100.80.0.2/32

[Peer]
PublicKey = $B_PUBLIC
AllowedIPs = 100.80.0.3/32
EOF
cat > "$CLIENTS/phone-a.conf" <<EOF
[Interface]
Address = 100.80.0.2/32
PrivateKey = $A_PRIVATE

[Peer]
PublicKey = $SERVER_PUBLIC
AllowedIPs = 100.64.0.0/10
Endpoint = $ENDPOINT:51820
PersistentKeepalive = 25
EOF
cat > "$CLIENTS/phone-b.conf" <<EOF
[Interface]
Address = 100.80.0.3/32
PrivateKey = $B_PRIVATE

[Peer]
PublicKey = $SERVER_PUBLIC
AllowedIPs = 100.64.0.0/10
Endpoint = $ENDPOINT:51820
PersistentKeepalive = 25
EOF

chmod 600 "$SERVER" "$CLIENTS/phone-a.conf" "$CLIENTS/phone-b.conf"
sysctl -w net.ipv4.ip_forward=1
cat > /etc/sysctl.d/90-screensync-forward.conf <<EOF
net.ipv4.ip_forward=1
EOF
systemctl enable "wg-quick@$IFACE"
systemctl start "wg-quick@$IFACE"

if command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then
  ufw allow 51820/udp comment "ScreenSync WireGuard private network"
fi

echo "WireGuard 专用隧道已安装。"
echo "请同时确认 VPS 云防火墙放行 UDP 51820；不要对公网开放 ADB 端口。"
echo "手机 A 导入：$CLIENTS/phone-a.conf"
echo "手机 B 导入：$CLIENTS/phone-b.conf"
echo "请安全传送这两个不同的私钥配置，不要公开分享它们。"
echo "查看服务器握手：sudo wg show $IFACE"
