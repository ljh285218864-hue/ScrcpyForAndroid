# ScreenSync — 通知功能独立开发分支

- 分支：`feature/screensync-notifications-isolated`
- 起点：`31a5157979a0fbef850d9a30c085726d77ccea34`
- 上游：`feature/screensync-accessibility-only`
- 目的：将通知相关的代码改动放在独立 Git 分支维护，不影响原开发分支。
- 注意：Git 分支保留整个 Android 工程，不是单独模块；本提交只整理文件索引，**不改变原有通知、截图、配对或后台逻辑**。

## 1. 与通知直接有关的代码

| 文件 | 原始版本行号 | 说明 |
| --- | --- | --- |
| `app/src/main/java/org/client/scrcpy/ShareNotificationPreference.java` | 14–37 | App 内开关、本地保存、`mayPost()` 联合系统权限 |
| `app/src/main/java/org/client/scrcpy/AccessibilityJpegStreamer.java` | 30–45 | 通知 ID `8413`、渠道 `screensync_active_share`、`noticePosted` |
| 同上 | 65–81 | `notificationAllowed()`：系统应用权限/渠道检查 |
| 同上 | 84–106 | 页面可见性回调、`hasVisibleStopControl()`、`stopIfNoVisibleStopControl()`、`ensureVisibleStopControl()` |
| 同上 | 125–145 | 启动 JPEG 前创建可见的共享停止通知，不可用时终止本次截图启动 |
| 同上 | 155–166 | 截图过程中检查共享状态及停止入口 |
| 同上 | 283–288 | 结束截图时清理共享通知 |
| 同上 | 299–319 | 已有明确授权的后台会话启动后仍必须满足共享状态要求 |
| 同上 | 324–333 | `resendCurrentSetup()`：网络恢复时检查状态并重发视频参数 |
| 同上 | 347–372 | 用户主动停止或停止入口不可用时终止共享 |
| 同上 | 375–411 | `showNoticeIfAllowed()`：创建通知渠道、正在共享通知、停止按钮 |
| `app/src/main/java/org/client/scrcpy/AccessibilityShareStopReceiver.java` | 9–13 | 点击通知里的“停止共享”后调用截图停止方法 |
| `app/src/main/java/org/client/scrcpy/ScreenSyncWatchNotice.java` | 1–27 | 旧观看申请通知的静默清理；`show()` 不发通知 |
| `app/src/main/AndroidManifest.xml` | 7 | 声明 `POST_NOTIFICATIONS` 权限 |
| `app/src/main/java/org/client/scrcpy/RemoteSetupActivity.java` | 230–253 | 软件内共享通知开关、查看系统设置及说明文案 |
| 同上 | 471–482 | 用户点击后进入 Android 系统通知设置 |
| 同上 | 508–523 | 软件和系统通知权限的状态显示 |
| 同上 | 536–555 | Activity 进入/离开时触发停止入口检查 |
| 同上 | 623–659 | 本机共享启动、失败反馈和状态文案 |
| 同上 | 790–808 | 用户确认观看请求时的共享提示说明 |

## 2. 间接依赖通知状态的网络代码

| 文件 | 原始版本行号 | 说明 |
| --- | --- | --- |
| `app/src/main/java/org/client/scrcpy/NoVpnMediaSocket.java` | 127–133 | `prepareRejoin()` 判断会话是否能恢复 |
| 同上 | 334–340 | 新 WSS 认证后检查原本的共享状态 |
| 同上 | 361–367 | 共享中断期间确认停止入口是否仍然存在 |
| 同上 | 383–399 | 会话续接后调用 `resendCurrentSetup()` |
| `app/src/main/java/org/client/scrcpy/ScreenSyncMediaKeeper.java` | 1–11 | 持有连接的说明，注释提及共享停止通知 |
| `app/src/main/java/org/client/scrcpy/LongTermSharePolicy.java` | 1–48 | 长期授权绑定已配对设备；与通知开关独立 |

## 3. 其他与后台在线有关、但不是通知代码的文件

- `NoVpnBackgroundSync.java`、`NoVpnPresenceLoop.java`、`NoVpnPresenceWorker.java`：后台在线、心跳和调度，不依赖通知开关作为启动前提。
- `AccessibilityShotService.java`：Android 无障碍截图服务，系统安全提示不受普通通知开关控制。

## 4. 分支工作规则

1. 所有通知 UI、渠道和权限判断的开发先在本分支提交；不要直接修改原开发分支。
2. 修改通知相关行为时必须审查 `AccessibilityJpegStreamer` 与 `NoVpnMediaSocket` 中的会话边界、重连和停止逻辑。
3. 软件内开关不高于 Android 系统通知权限；系统拒绝通知时不创建替代悬浮提示，也不自动请求/修改系统权限。
4. 共享期间保留设备持有人可见的状态与停止入口；不引入不可察觉的后台屏幕采集。
5. 修改功能后使用 GitHub Actions 构建并验证 Android APK/relay；文档索引提交本身无需重新构建。
