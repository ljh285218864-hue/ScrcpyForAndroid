# ScreenSync Beta4 · 自动接入部署说明（实验版）

本目录包含可以运行的 Python 两设备注册/配对 API、Ubuntu 安装器、管理员开通二维码脚本、单元测试。
当前仅已提交 GitHub；没有自动部署到用户的 Evoxt 日本服务器。不得将 APK 构建成功等同于异地同屏成功。

## 核心结构
- 服务器继续使用现有 WireGuard UDP 51820；现有 3X-UI/VLESS Reality 不应被改动。
- 控制服务仅绑定 127.0.0.1:8765；公网必须有有效 HTTPS 反向代理，推荐独立域名 screensync.privyluxe.com 和 TCP 8443，配置前先检查 80/443/8443 端口。
- Android 编译默认 API URL 为 https://screensync.privyluxe.com:8443。可通过 GitHub Actions 仓库变量 SCREENSYNC_API_URL 改变；域名、DNS 和证书尚未核实。
- 主手机需首次扫描只有 VPS 管理员能显示的激活二维码；第二手机以后只扫描主手机的 5 分钟配对码，两方明确同意才拿到 VPN 配置。
- 客户端私钥在手机内生成，不上传服务器；VPN 和 ADB 系统授权仍然由本人确认，不会被隐藏或绕过。
- 只有两台手机、一个可信配对，默认不允许第三台加入。解绑在服务器优先取消互通，再取消本机配置。

## Ubuntu VPS 安装（先检查，不要盲目运行）
1. 进入自己的 Evoxt VPS，以 sudo wg show 核对接口名，确认 UDP 51820、现有 3X-UI 状态和端口占用；做好原始服务器快照。
2. 将 ScrcpyForAndroid 的 feature/screensync-beta4-auto-remote 分支检出到 VPS 的全新目录，避免覆盖原有目录或 x-ui 文件。
3. 在仓库根目录执行 sudo bash remote/control/install_ubuntu.sh。脚本检查 WireGuard 接口默认 wg-screensync，不匹配则退出；可提前以 SCREENSYNC_WG_INTERFACE 环境变量指明真实接口。
4. 安装器备份 /etc/wireguard、wg show 与 iptables-save 到 root 私有目录，只启动 ScreenSync 控制服务，不开放公网 TCP 8765。
5. 用 sudo systemctl status screensync-control --no-pager 和 curl --fail http://127.0.0.1:8765/health 确认服务只在回环地址运行。
6. 在 DNS 管理处创建 screensync.privyluxe.com 的 A 记录并配置有效 HTTPS 证书及单独的反向代理，代理到 127.0.0.1:8765。不要直接占用已有 3X-UI Reality 的 TCP 443；也不要自行关闭防火墙。缺少合适端口或证书时先停止发布。
7. 手机网络访问 https://screensync.privyluxe.com:8443/health，应返回 ok=true 且无证书警告，才开始手机激活。
8. 在已安装 qrencode 的 VPS 上执行 sudo bash remote/control/show_activation_qr.sh，在可信终端显示主手机一次性激活码。该码不可发到 GitHub/公共群聊。
9. 主手机扫码激活，生成配对二维码；另一手机扫码申请，主手机确认后两台手机分别获取自己的 VPN 配置，无需导入文件。
10. 双方按照系统提示启用 VPN，保持无线调试和 ADB 授权，然后用不同的 Wi-Fi/移动网络测试。服务器用 sudo wg show 查看握手时间，用 iptables -S SCREENSYNC_PAIR 查看两台互通的授权 ACL。

## 安全和迁移约束
- 现有 Beta3 静态设备可能已经占用 100.80.0.2/32 和 100.80.0.3/32。迁移时必须备份并核对旧 peer，必要时安全地撤销旧静态密钥，不能让旧密钥永久可达。
- 不得把管理员激活码、数据库、设备 token、WireGuard 私钥存到 APK、公开仓库或日志。
- 不得向公网暴露 ADB TCP 5555、10055 或 ScreenSync 注册服务的 8765 端口。
- Android 某些定制 ROM 不支持经 VPN 接入无线调试，即使 VPN 握手正常，ADB/scrcpy 仍有可能失败。必须真机验证。
- 系统重启时应确认控制服务和 WireGuard 启动顺序，检查 iptables ACL 仍优先于原有宽松转发规则。
- 部署前检查防火墙、DNS、证书，安装器不能代替这些现场检查。

## 自动测试
运行: python3 -m unittest discover -s remote/control -p 'test_*.py' -v
GitHub Actions: .github/workflows/beta4-auto-provision.yml
测试涵盖管理员激活、待配对状态、双方确认、非法 token、错误邀请、解绑撤销。APK 构建成功仅证明可编译，不证明 VPS/真机成功。
