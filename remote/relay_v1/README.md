# ScreenSync Beta4 No-VPN — 实验版

> 状态：**配对与 HTTPS 信令原型，不是可以看屏幕的正式版本。** 这条分支的 AndroidManifest 不注册 VpnService，不会申请系统 VPN 授权，不占用 VPN 槽位，也不能显示“远程视频可观看”的假状态。Android 屏幕采集的系统授权不能由信令层替代。

## 当前实际完成

- `remote/relay_v1/server.py`：独立 Python 标准库信令原型，默认只监听 `127.0.0.1:8766`；只能从有效 HTTPS 反向代理暴露给手机。
- 一次性管理员开通码、五分钟邀请、两端六位核对码、本人批准/拒绝、组内专属设备 ID/令牌、拒绝跨组信令、双方解绑令牌失效。
- `POST /v1/heartbeat` 更新在线时间；`POST /v1/signal/send` 和 `/v1/signal/poll` 传递短期信令。双向请求仅发送到已批准的对方 ID，不允许任意 IP 或 Android 远程控制命令。队列最多32条、90秒清理。
- `NoVpnApi.java` 以系统证书信任链调用 HTTPS，令牌经 AndroidKeyStore 加密保留，记录 `device_id`、`group_id`、已确认 `peer_device_id` 和消息序号，短暂断线/应用界面重开后可以补取有效期内消息。
- `RemoteSetupActivity.java`：扫码开通、生成二维码、批准/拒绝、显示在线状态及六位核对码、A→B 或 B→A 发送连接测试消息、撤销并使两端旧令牌失效。
- `MainActivity` 不再调用旧 WireGuard/ADB 远程连接；弃用的 WireGuard 依赖、代码、服务器安装脚本已从此实验分支删除。旧 LAN 的独立开发分支保持不变。

## 本地验证（独立测试环境，不部署旧 VPS）

```sh
python3 -m unittest discover -s remote/relay_v1 -p 'test_*.py' -v
SS_NOVPN_DB=/tmp/screensync-test/relay.db python3 remote/relay_v1/server.py issue-owner
SS_NOVPN_DB=/tmp/screensync-test/relay.db python3 remote/relay_v1/server.py serve
```

注意：`serve` 仅绑定本机，HTTPS 代理还没配置，Android 默认 `SCREENSYNC_NOVPN_URL=https://relay.invalid` 会故意拒绝连接，不会联系原 WireGuard API。**不建议把 `/tmp`、测试数据库或 HTTP 地址用于真实用户。**

### 无通知模式与实时性的取舍

无 VPN 实验版明确不使用 Android 前台接收服务或持续通知，不能在后台无限制运行视频服务。与用户已启用的通知权限无关，ScreenSync 不会尝试规避 Android 的权限、安全及隐私提示。恢复的是配对身份和后台心跳，**不是无授权自动采集视频**。

## 上线前需要完成

1. **正式 HTTPS 与流控：** 独立子域名、有效 TLS、在反向代理上做基于真实来源 IP 的注册/扫码限流、控制服务只绑定 127.0.0.1，确保不干扰 x-ui/Reality/Beta3。
2. **后台与重启恢复（第一阶段已加入）**：Android 使用 `NoVpnBackgroundSync` + `NoVpnPresenceWorker`。配对页面提供用户主动开启/关闭的后台定期检查；已启用后由 WorkManager 持久保留带联网约束的15分钟周期 HTTPS 心跳，进程运行时监听网络变化并排队一次快速重试，正常手机重启后由 WorkManager 尝试恢复；解绑关闭。**不启动前台服务，不产生常驻通知，不在后台采集屏幕，不自动接受观看。** Android Doze、厂商省电和强制停止可能导致任务大幅延迟或暂停，不能保证实时在线。旧 `WakeLanService` 在无 VPN 实验版 manifest 中已禁用，以免旧局域网收件服务启动前台通知；旧 LAN 功能在此分支暂不可用，独立旧版分支不变。
3. **视频传输（服务端原型已加入）：** 继续使用已有 ADB + scrcpy 编码与解码。新增 `media_relay.py` 实际 WebSocket 连接端，检查设备令牌、配对组、每次会话的接收方确认、只读 VIDEO 包格式、大小与速率，并在解绑后停止转发；`test_media_relay.py` 使用模拟视频帧测试。**Android 本机 scrcpy 视频采集桥、观看端接入以及 HTTPS/WSS 公网部署均未完成**。禁止透传完整 ADB、音频、控制或命令帧。详情见 `SCRCPY_MEDIA_RELAY_DESIGN.md`。
4. **可靠性：** 网络恢复、蜂窝/Wi-Fi 切换、飞行模式、系统杀后台、重启、换设备、重复 QR/非法 QR、超时、解绑定/跨组、压力测试；当前仅完成部分自动测试。
5. **隐私与协议：** 传输只能访问授权组，屏幕采集系统提示不可移除；客户要有可见接收状态及随时撤销按钮。上线前必须进行安全审查及稳定 APK 签名。

## 真实使用流程（信令测试）

1. 管理员离线在独立服务器生成一次性主手机开通二维码（未部署服务器前不可用）。
2. A 扫码开通→生成5分钟邀请→B 扫码提交→两端核对6位码→A批准。
3. A/B 打开“ScreenSync 无 VPN 配对”，检查对方在线状态，点击“测试双方信令连接”；对方在该页面能收到信令消息。
4. A/B 均可主动解除配对。撤销后双方现有令牌不能再使用，重新使用时必须双方重新同意。

### 不应混淆

- “设备已配对” ≠ “对方在线”；“信令在线” ≠ “视频可观看”。
- “未弹 VPN 授权框” ≠ “无线调试授权永久有效”；ADB 必须由本机用户明确授予，系统重启或关闭调试后可能需要恢复授权。
- “断网后恢复信令” ≠ “后台常驻/重启后能随时观看”。


## 独立视频中转原型（仅本机/CI）

`media_relay.py` 使用 `websockets==15.0.1`，与 HTTPS 配对服务共享同一 SQLite 身份数据库，默认仅监听 `127.0.0.1:8767`。两端都需主动建立 WSS 连接，先发送注册令牌 `{"type":"auth","token":"..."}`；观看方发送 `watch_request`，被观看方本次会话明确发送 `watch_accept` 后才能开始发送视频。任意一方发送 `watch_end` 即关闭会话。信令支持 `video_setup`（四个分辨率值及 h264/h265），媒体采用二进制数据（16字节会话ID + 4字节媒体长度 + VIDEO包），服务器拒绝非视频包并限制积压、时长和速率。

只供开发环境验证：

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r remote/relay_v1/requirements-media.txt
SS_NOVPN_DB=/tmp/screensync-test/relay.db python3 remote/relay_v1/server.py serve
# 在另一终端使用相同 SS_NOVPN_DB 运行：
SS_NOVPN_DB=/tmp/screensync-test/relay.db python3 remote/relay_v1/media_relay.py
python3 -m unittest discover -s remote/relay_v1 -p 'test_*.py' -v
```

**绝不能把 8766/8767 原始 HTTP/WS 端口暴露公网。** 正式使用须通过受保护的 HTTPS/WSS 反向代理、有效证书、限速和访问控制。现有日本 VPS 的内存与流量限制尚未评估，不部署、不改旧服务。双向配对和模拟视频中转通过，不等于两台手机已经能真正异地同屏。
