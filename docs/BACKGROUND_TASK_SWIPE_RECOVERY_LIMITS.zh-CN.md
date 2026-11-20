# 划走最近任务卡片后的后台连接能力边界与恢复

现有代码将 WSS socket 保存在 `ScreenSyncMediaKeeper` 的进程内静态对象中；`RemoteSetupActivity.onDestroy` 会解绑 UI listener，但不会主动结束已授权且具有可见停止通知的流。进程被系统杀死时，静态 socket 也随之消失。

本次在 `NoVpnPresenceWorker.doWork` 成功确认配对及发送心跳后，调用 `ScreenSyncMediaKeeper.restoreOptedInSignaling(app)`，以便 Android 日后实际运行 WorkManager 时在有效、明确启用的后台维护设置下尝试恢复 WSS 信令。此恢复仅处理信令及网络会话，不自行打开无障碍权限、不会自动越过共享 STOP 提示，也不新增待机通知、前台服务或悬浮窗。

**已知限制：** WorkManager 周期任务最短 15 分钟，可能因 Doze/厂商策略延后；被强制停止的应用不会立即自动恢复。没有系统许可的及时唤醒机制或长期前台运行组件，就无法保证从“进程完全被杀掉”的状态随时响应 B 的观看请求。用户可以先测试：A 手机打开后启动后台在线/长期共享授权，保持系统共享通知可用，随后划走最近任务卡片；等待系统计划任务运行后检查 `BACKGROUND_WSS worker_recovery_attempted` 与 WSS 认证日志。任务是否执行、连接能否保持取决于设备及 OEM。

未修改服务器，不影响配对/签名，未改变消息通知约定：唯一共享通知仍仅在实际截图期间显示。
