# FCM 唤醒服务端接入（可选，不上传密钥）

服务器增加三个需当前配对设备 Bearer 身份认证的 HTTPS 接口：

- `POST /v1/push/token`：当前设备上报自己的 FCM registration token。后台在线开关开启时才由客户端使用。
- `POST /v1/push/clear`：当前设备请求移除自己的 FCM token。
- `POST /v1/push/wake`：B 为**自己当前唯一配对的 A**请求一次普通优先级、仅含 `screensync_wake` 的 FCM 数据消息。服务端固定选择当前配对对象的 token，不接受客户端指定收件人，15 秒冷却，单对象每小时最多 60 次。

SQLite 存储令牌，不会在 API 响应或日志中输出。解绑后按外键关系删除当前分组令牌。不需要给观看方发送收件人的 FCM token。

发送端需在 VPS 私有环境设置：

```
SS_FCM_PROJECT_ID=你的_Firebase_project_id
SS_FCM_SERVICE_ACCOUNT_JSON=/etc/screensync/fcm-service-account.json
```

私钥 JSON 仅存服务器，权限设为 0600，不要放进 GitHub、客户端 APK 或诊断日志。部署前安装更新的 `requirements-media.txt` 依赖。服务端使用 Google OAuth2 服务账号请求 FCM HTTP v1 API，data-only、android `NORMAL` priority、30 秒 TTL，不发送 FCM notification payload。未配密钥时返回 `not_configured`，不影响原来的配对/HTTPS/WSS。

此设计只用推送唤醒 ScreenSync **信令**，不从推送直接启动屏幕采集，无障碍权限不会被自动开启；真正共享仍必须满足本机长期授权/本次确认和可见的“正在共享屏幕/停止”入口。

没有 Google Play 服务的设备无法使用 Firebase Messaging；即使具备 Play 服务，普通优先级 data message 也不能保证后台或进程被杀后的即时接收。Android 系统“强行停止”后不可依赖 FCM 唤醒。服务器源代码提交不等于已部署到大阪 VPS。
