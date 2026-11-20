# ScreenSync FCM 推送唤醒：完成配置与验收

本分支已加入 Android 的 FirebaseMessagingService、后台联网开关下的 FCM token 注册，以及服务器的认证注册/唤醒接口。**但源码存在并不表示 VPS/手机已经能收到推送**：需要自己的 Firebase 项目、服务器私有凭证和重新构建/部署。

## 第一步：注册 Firebase Android 应用

1. 在 Firebase 控制台创建项目，并注册 Android 应用，其包名必须是 **`org.client.scrcpy`**。
2. 从项目设置找到以下 Android 客户端标识（这些不是服务器私钥）：Firebase app ID、项目 ID、项目编号/发件者 ID、Web API Key。
3. 在 GitHub 仓库 `Settings → Secrets and variables → Actions` 创建四个 Secrets：
   - `SCREENSYNC_FCM_APP_ID`
   - `SCREENSYNC_FCM_PROJECT_ID`
   - `SCREENSYNC_FCM_SENDER_ID`
   - `SCREENSYNC_FCM_API_KEY`
4. GitHub Actions 的 `accessibility-only.yml` 在构建时读取这些值并构建 FirebaseOptions。无需提交 `google-services.json`；这四项为空时，APK 仍能编译但 FCM 处于未配置状态。

**不能把 Firebase 服务账号私钥放入 APK 或 GitHub 项目源码。** 服务器使用专门的服务账号，通过 FCM HTTP v1 API 发送普通优先级数据消息。

## 第二步：配置私有服务器

1. 在 Firebase 项目/Google Cloud 打开 FCM HTTP v1 API，创建具有发送 FCM 权限的服务账号。
2. 只在 VPS 上放置私钥 JSON，例如 `/etc/screensync/fcm-service-account.json`，属主设为运行 ScreenSync signaling 的用户、权限 0600。
3. 创建服务器私有文件 `/etc/screensync/fcm.env`：

```ini
SS_FCM_PROJECT_ID=your-project-id
SS_FCM_SERVICE_ACCOUNT_JSON=/etc/screensync/fcm-service-account.json
```

4. 服务器用 `remote/relay_v1/requirements-media.txt` 更新虚拟环境依赖（新增 `google-auth[requests]`），部署新的 `server.py`，并在使用 Nginx 8443 的情况下，应用新模板中允许的 `/v1/push/token`、`/v1/push/clear`、`/v1/push/wake` 反向代理路由。
5. **只对 ScreenSync 服务的配置做受控更新**。不要修改其他 VPS 服务、3X-UI、Reality 或 WireGuard。实际部署必须先检查端口、systemd、证书与现有配置，执行 nginx -t 后才重载。

## 第三步：两端安装和测试

1. A 和 B 均安装使用同一个 Firebase 项目标识编译的 APK；每台手机需要可用的 Google Play 服务。
2. 保持配对关系，A 和 B 在软件内主动开启“后台在线维护”，等待诊断日志出现 `FCM_TOKEN registered`（服务器实际更新后）。
3. A 手机开启长期共享授权（或保留每次手动同意），允许真正共享时显示唯一的“ScreenSync 正在共享屏幕／停止”通知。
4. A 划走任务卡片；B 点击观看。如果服务器当前认为 A 离线，B 会请求一次 FCM 唤醒并在 45 秒内轮询重试。预期 A 日志有 `FCM_WAKE signaling_reconnect`，B 日志有 `FCM_WAKE_REQUEST state=sent`。
5. A 若系统关闭无障碍、用户强制停止、缺失 Google Play 服务或进入 OEM 深度休眠，即使 FCM 发送成功也无法保证即时接收或恢复截图。

## 正常通知规则

FCM 只发送 `data`，`NORMAL` 优先级，30 秒有效期，消息中只有固定事件、当前组 ID 与已配对来源设备 ID。**待机不发布任何 ScreenSync 通知**；没有权限绕过行为。真正进行截图时，才由原代码发布唯一的共享状态通知并保留停止按钮。高优先级数据推送如果不用于用户可见的即时通知，可能被 Google 降权；因此此处不依靠高优先级唤醒实现隐藏截图。

## 发布和安装限制

当前 GitHub Actions 产物是调试签名的 APK，**没有配置固定签名证书**；不同 CI 构建不一定能直接覆盖升级。卸载重装会清理本地配对与无障碍授权。正式分发前须配置固定签名，且不可提交 keystore/密码到仓库。

服务器代码/Android CI 自动测试通过 ≠ 已成功连接实际 Firebase 或两台实体手机。要证明真实唤醒，必须完成上述凭证和 VPS 配置后测试。
