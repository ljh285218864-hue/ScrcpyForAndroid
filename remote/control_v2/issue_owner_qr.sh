#!/usr/bin/env bash
# Generate one short-lived, one-use owner activation QR on the trusted VPS.
# Never paste the printed code into tickets, GitHub or public chat.
set -euo pipefail
umask 077
[[ $EUID -eq 0 ]] || { echo "必须使用 root/sudo" >&2; exit 1; }
[[ -f /etc/screensync-v2/registration.env ]] || {
  echo "独立服务尚未部署" >&2; exit 1;
}
for bin in curl qrencode python3; do
  command -v "$bin" >/dev/null || { echo "缺少 $bin" >&2; exit 1; }
done
API_URL="$(printenv SCREENSYNC_API_URL || echo https://screensync.privyluxe.com:8443)"
[[ "$API_URL" == https://* ]] || {
  echo "拒绝非 HTTPS 端点" >&2; exit 1;
}
curl --fail --silent --show-error --max-time 8 "$API_URL/health" >/dev/null ||
  { echo "对外 HTTPS 尚未验证成功，禁止生成生产激活码" >&2; exit 1; }
set -a
source /etc/screensync-v2/registration.env
set +a
# Only the owner gets this code; guest scans the owner's time-limited invite.
CODE="$(python3 /usr/local/libexec/screensync-v2/server.py issue 600)"
echo "首次开通码（10 分钟有效，一次使用）仅供该组主手机现场扫描："
printf '%s' "$CODE" | qrencode -t ANSIUTF8
unset CODE
