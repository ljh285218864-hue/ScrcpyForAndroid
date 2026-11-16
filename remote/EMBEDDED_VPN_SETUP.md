# ScreenSync 内置异地网络（无需另一款手机软件）

## 本版本的边界

- ScreenSync Android 内置官方 WireGuard userspace tunnel 库。两个手机不需要单独安装 Tailscale / WireGuard。
- 手机持有人首次点击“启用异地网络”时，需要批准 Android 标准 VPN 授权。系统 VPN 指示不会隐藏。
- ScreenSync 仍复用原有经过授权的 ADB + scrcpy；**此版本没有使用 MediaProjection，也不会新增它的录屏同意对话框。**
- **目前不能认为异地同屏已成功**：没有部署服务器或没有经过不同网络真机验证；某些 Android ROM 的无线调试只接受 Wi-Fi 接口连接。
- VPN 只能解决设备网络互通，不能强制开启 Android 无线调试，也不能代替系统配对、权限及被观看手机的知情同意。

## 前提：需要自己的 VPS（不是 WordPress 虚拟主机）

需要公网 IPv4 / UDP 51820 的 Linux VPS 一台。建议 Ubuntu 24.04 LTS。你可以使用自己可控制的 VPS，但必须管理防火墙、账号和 WireGuard 密钥。

WireGuard 官方工具：https://www.wireguard.com/install/

## 在 VPS 上准备同一个私有网络

1. 以服务器管理员身份安装 `wireguard` 与 `wireguard-tools`。
2. 为服务器、手机 A、手机 B **分别独立生成** WireGuard 密钥对；不能共用私钥。
3. 服务器内部地址示例：`100.80.0.1/24`；手机 A：`100.80.0.2/32`；手机 B：`100.80.0.3/32`。
4. 服务器的 `wg0.conf` 注册两个客户端公钥，AllowedIPs 分别写 `100.80.0.2/32` 和 `100.80.0.3/32`。
5. 开通 UDP 51820；**仅允许两个已配置的 peer**。如果要让两台手机互通，需打开 WireGuard 内部的 IP 转发规则；不要把 ADB TCP 端口直接暴露到公网。
6. 为每部手机单独创建 .conf。两部手机的 private key 永远不同，`AllowedIPs = 100.64.0.0/10`，`PersistentKeepalive = 25`，`Endpoint = <你的 VPS 公网 IP 或可信域名>:51820`。
7. 安全地把对应 .conf 交给相应手机，打开 **ScreenSync → 导入设备配置**。完成后删除手机“下载”中的明文配置文件。不要把 .conf 发到公共聊天或公开仓库。
8. 手机上点击 **启用异地网络**，首次同意系统 VPN 授权。屏幕共享时用户仍能看到安全提示并可随时断开。
9. 保持以前的 ScreenSync 双方配对。第一次建议同一 Wi-Fi 下双方打开 ScreenSync，自动同步各自新的 VPN 地址。之后再分别切换到不同 Wi-Fi/移动网络尝试同一个“立即观看”。

### 单台设备配置结构示例（占位符，不是真实密钥）

```ini
[Interface]
Address = 100.80.0.2/32
PrivateKey = <仅属于手机 A 的合法 WireGuard 私钥>

[Peer]
PublicKey = <VPS 服务器公钥>
AllowedIPs = 100.64.0.0/10
Endpoint = <VPS 公网 IP>:51820
PersistentKeepalive = 25
```

手机 B 使用 `100.80.0.3/32` 和**自己**的私钥。

### 检查步骤

- 先用服务器端 `sudo wg show` 验证两部手机各自已经有最近握手（并检查 VPS 内部互通的防火墙）。
- Android 端查看 ScreenSync 是否显示「内置异地网络已启动」。这只表示隧道进程启动，**不代表已经穿透 NAT 或能观看屏幕**。
- 如果双方 VPN 已握手，但仍无法观看：首先确认被观看手机已启用无线调试并保留此前 ADB 授权；检查 ROM 是否允许来自 VPN 的 ADB TLS 连接。
- 如 Android 系统提示 VPN 正在运行，这是正常且应保留的用户可见指示。
- 用户应可随时按「关闭异地网络」和「断开观看」停止传输。

### 安全说明

不要公开分享 VPN 配置、二维码密钥或 ADB 密钥。仅连接家人明确同意的设备；不要使用端口映射、UPnP 或开放的 5555 端口将 ADB 直接暴露到互联网。
