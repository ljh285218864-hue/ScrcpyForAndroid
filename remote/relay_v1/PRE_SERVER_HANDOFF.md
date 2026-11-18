# ScreenSync Beta 4 — 接入 VPS 之前的开发交接清单

**分支**：`feature/screensync-beta4-novpn`。实验版，不是可交付客户的正式版。多客户组，每组严格两台 A ↔ B，绝非一对多。

## 已提交源码（不需要 VPS 就能编译和自动测试）

- `LocalScrcpyStreamer`：手机持有人同意本次会话后，从**本机**已授权的无线 ADB 启动原有 scrcpy 编码器，过滤并仅经 WSS 发送 VIDEO 包，不暴露原始 ADB。
- `ScrcpyLocalFrameReader`、`RelayVideoFormat`、`ScrcpyRelayWire`：读取本地分辨率握手、限制视频包大小、拒绝音频/命令/控制包，安全解析 H.264 参数；配套 JUnit 假数据测试。
- `NoVpnMediaSocket`：HTTPS/WSS 身份绑定、逐次授权会话、双方停止、网络断开仅恢复**信令连接**，不默许重启视频采集。
- `RelayVideoRenderer`：复用已有 VideoDecoder，使用有界队列将收到的编码视频输出到 SurfaceView，离开画面释放资源。
- `RemoteSetupActivity`：扫码配对、身份核对、跳转本机无线调试、仅在本机输入临时配对端口/六码、WSS 连接、观看/同意/拒绝/停止、视频画面。
- `server.py`、`media_relay.py`：多组 1:1 租户隔离、每次观看会话的确认与撤销、WS 视频中转、限流；测试使用**模拟视频包**。
- `deploy/`：独立 systemd 和 HTTPS/WSS 反代模板、只读端口预检查；从未部署。CI 构建 APK 并检查无 VPN 组件。

## 本地自动验证（无需正式 VPS）

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r remote/relay_v1/requirements-media.txt
python3 -m unittest discover -s remote/relay_v1 -p 'test_*.py' -v
```

GitHub Actions 打包实验 arm64 APK。Android 默认 URL 为 `https://relay.invalid`、`wss://relay.invalid/media`，**故意不可连接**；必须在服务器准备好并经授权后，以有效 HTTPS/WSS 域名重新构建。

## 本轮客户端加固与使用入口

- 首页“一键观看”直接携带一次性意图进入配对观看页，HTTPS/WSS 身份验证通过后自动向**本组唯一对端**发送一次观看请求；不替被观看方批准。
- 纠正首个 CONFIG 抵达时旧解码队列错误清除“视频初始化任务”的黑屏风险；保留设置/停止任务、丢弃旧 P 帧和过期会话。MediaCodec 的输入等待改为有界，避免影响主动停止。
- WSS 重新连接时忽略过时 socket 的回调；本机采集采用独立 `127.0.0.1:7018` ADB-forward 端口，不占用旧局域网的 7008。
- 本地 scrcpy 启动遵循用户当前的本次许可状态，用户取消后尽量中止尚未完成的 ADB 启动。旧 scrcpy 的本机监听端口已限于回环地址并设置启动等待超时。
- 本机屏幕横竖旋转时，在后续 H264 CONFIG 前同步发新版分辨率握手，观看端重新配置解码器。没有收到真正的 Android 视频编码前，不能当作旋转实测通过。
- Android/Python 使用同一固定 VIDEO 二进制 golden vector 进行独立单元测试。CI 加入防回退检查，避免旧视频队列清空错误、安全校验和端口冲突重新出现。
- **安装更新注意**：GitHub Actions 的实验 debug APK 可能使用不同临时调试签名，不能保证旧 APK 原地升级。正式向多组客户分发前需要保管一把稳定的正式签名密钥，使用受保护 CI 密钥签名。不要把 keystore、口令写进仓库或聊天。
- **运行限制**：被观看方目前必须在可接收请求的应用界面里确认本次会话，切走页面会结束采集；没有前台常驻接收服务和 Android 允许的后台执行豁免，无法承诺随时后台接通。无线调试通常依赖本机 Wi-Fi，蜂窝网络不能替代首次本机调试授权。

## 只有 VPS / 实际手机到位以后才能完成

1. 先检查 3X-UI、Reality、旧 WireGuard、80/443/8766/8767 监听、CPU/内存/磁盘与月度流量；不得直接改动旧服务。
2. 独立 TLS 入口、反向代理、DNS 和证书；两端 APP 更新合法的 HTTPS/WSS 地址。
3. 两台本人同意的 Android 14+ 手机分别授予本机无线调试权限；异地 A 请求 B、B 在手机上确认，验收真实 H.264 画面，再反向测试 B→A。
4. 真机测试 Wi-Fi/4G 切换、横竖屏、后台划走、锁屏、手机重启、系统关闭 ADB，以及 vivo/OPPO/小米 ROM 兼容性。
5. 对多组真实手机并发压测 CPU/RAM/带宽。100 组**登记上限**不代表 100 组能同时看视频。
6. 正式安全审计、设备掉线/解绑、数据备份、回滚与证书更新。

## 无法保证的系统限制

- APK 编译通过、合成视频经过服务器，不代表真机 ADB 采集、硬件解码或异地流畅性已经通过。
- 无前台常驻服务时，Android 可延迟后台任务、停止网络进程；不能保证强杀/重启后秒级随时接通。WorkManager 的15分钟周期不是实时连接。
- Android 无线调试有系统授权和安全提示；如果系统重启后关闭无线调试，普通 App 不能保证静默重新启用。
- 只读视频会话由持有人同意、可停止；不添加 MediaProjection 替代方案，也不开放公网 ADB。

**生产 VPS 未变更；主分支未合并。**