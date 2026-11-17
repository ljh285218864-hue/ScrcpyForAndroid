# ScreenSync Beta4 No-VPN — 实验版

> 状态：**配对与 HTTPS 信令原型，不是可以看屏幕的正式版本。** 这条分支的 AndroidManifest 不注册 VpnService，不会申请系统 VPN 授权，不占用 VPN 槽位，也不能显示“远程视频可观看”的假状态。Android 屏幕采集的系统授权不能由信令层替代。

## 当前实际完成

- `remote/relay_v1/server.py`：独立 Python 标准库信令原型，默认只监听 `127.0.0.1:8766`；只能从有效 HTTPS 反向代理暴露给手机。
- 一次性管理员开通码、五分钟邀请、两端六位核对码、本人批准/拒绝、组内专属设备 ID/令牌、拒绝跨组信令、双方解绑令牌失效。
- `POST /v1/heartbeat` 更新在线时间；`POST /v1/signal/send` 和 `/v1/signal/poll` 传递短期信令。双向请求仅发送到已批准的对方 ID，不允许任意 IP 或 Android 远程控制命令。队列最多32条、90秒清理。
- `NoVpnApi.java` 以系统证书信任链调用 HTTPS，令牌经 AndroidKeyStore 加密保留，记录 `device_id`、`group_id`、已确认 `peer_device_id` 和消息序号，短暂断线/应用界面重开后可以补取有效期内消息。
- `RemoteSetupActivity.java`：扫码开通、生成二维码、批准/拒绝、显示在线状态及六位核对码、A→B 或 B→A 发送连接测试消息、撤销并使两端旧令牌失效。
- `MainActivity` 不再为 Beta4 无 VPN 用户自动启动旧 WireGuard/ADB 连接。**旧 LAN 模式独立保留以免影响原先局域网实验。**

## 本地验证（独立测试环境，不部署旧 VPS）

```sh
python3 -m unittest discover -s remote/relay_v1 -p 'test_*.py' -v
SS_NOVPN_DB=/tmp/screensync-test/relay.db python3 remote/relay_v1/server.py issue-owner
SS_NOVPN_DB=/tmp/screensync-test/relay.db python3 remote/relay_v1/server.py serve
```

注意：`serve` 仅绑定本机，HTTPS 代理还没配置，Android 默认 `SCREENSYNC_NOVPN_URL=https://relay.invalid` 会故意拒绝连接，不会联系原 WireGuard API。**不建议把 `/tmp`、测试数据库或 HTTP 地址用于真实用户。**

### 无通知模式与实时性的取舍

无 VPN 实验版明确不使用 Android 前台接收服务或持续通知，不能在后台无限制运行视频服务。与用户已启用的通知权限无关，ScreenSync 不会尝试掩盖 Android 的屏幕共享状态提示。恢复的是配对身份和后台心跳，**不是无授权自动录屏**。

## 上线前需要完成

1. **正式 HTTPS 与流控：** 独立子域名、有效 TLS、在反向代理上做基于真实来源 IP 的注册/扫码限流、控制服务只绑定 127.0.0.1，确保不干扰 x-ui/Reality/Beta3。
2. **后台与重启恢复（第一阶段已加入）**：Android 使用 `NoVpnBackgroundSync` + `NoVpnPresenceWorker`。配对页面提供用户主动开启/关闭的后台定期检查；已启用后由 WorkManager 持久保留带联网约束的15分钟周期 HTTPS 心跳，进程运行时监听网络变化并排队一次快速重试，正常手机重启后由 WorkManager 尝试恢复；解绑关闭。**不启动前台服务，不产生常驻通知，不在后台采集屏幕，不自动接受观看。** Android Doze、厂商省电和强制停止可能导致任务大幅延迟或暂停，不能保证实时在线。旧 `WakeLanService` 在无 VPN 实验版 manifest 中已禁用，以免旧局域网收件服务启动前台通知；旧 LAN 功能在此分支暂不可用，独立旧版分支不变。
3. **视频传输：** 考虑 WebRTC + 媒体服务器与明确授权的屏幕分享。当前只有 `hello` 连接测试，不传输视频或 ADB 数据，不能默认为长期录屏授权。
4. **可靠性：** 网络恢复、蜂窝/Wi-Fi 切换、飞行模式、系统杀后台、重启、换设备、重复 QR/非法 QR、超时、解绑定/跨组、压力测试；当前仅完成部分自动测试。
5. **隐私与协议：** 传输只能访问授权组，屏幕采集系统提示不可移除；客户要有可见接收状态及随时撤销按钮。上线前必须进行安全审查及稳定 APK 签名。

## 真实使用流程（信令测试）

1. 管理员离线在独立服务器生成一次性主手机开通二维码（未部署服务器前不可用）。
2. A 扫码开通→生成5分钟邀请→B 扫码提交→两端核对6位码→A批准。
3. A/B 打开“ScreenSync 无 VPN 配对”，检查对方在线状态，点击“测试双方信令连接”；对方在该页面能收到信令消息。
4. A/B 均可主动解除配对。撤销后双方现有令牌不能再使用，重新使用时必须双方重新同意。

### 不应混淆

- “设备已配对” ≠ “对方在线”；“信令在线” ≠ “视频可观看”。
- “未弹 VPN 授权框” ≠ “不需要 Android 屏幕分享授权”。
- “断网后恢复信令” ≠ “后台常驻/重启后能随时观看”。
