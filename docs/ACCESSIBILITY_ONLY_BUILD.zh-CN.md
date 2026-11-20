# ScreenSync — 无障碍截图专用版（不使用无线调试）

此分支从 `feature/screensync-dismissible-start-alert` 独立派生。
**只使用 Android AccessibilityService.takeScreenshot() + JPEG + WSS**，
不采用无线调试、ADB、本机 scrcpy-server、VPN 或 MediaProjection 作为截图入口。

## 当前实现
- 手机持有人在 Android 系统设置中主动开启无障碍截图权限。
- 双方扫码并确认配对码。每次观看请求仍需被观看方在软件内明确允许。
- A 端截取 JPEG 图片，经过 WSS 中转到 B 端；B 端显示在观看界面。
- 保留明确的共享状态、停止共享操作和普通共享通知。不能依靠远端指令自动启动截图。
- 当前截图版本在共享期间要求普通通知开启，以保留可见停止入口；
  不会私自绕过系统提醒。服务器在线心跳与截图采集是不同功能。
- 后台任务只做配对状态心跳，不会自动批准观看或重新启动截图。

## 移除的旧路线
- 从此分支的 Android 源码中移除旧 LAN/USB/无线调试界面、ADB 助手、
  `LocalScrcpyStreamer`、旧 scrcpy 服务以及相关 Java 测试、布局。
- Android 构建不再包含 `:server` 模块，不再构建或拷贝 `libadb.so`
  或 `scrcpy-server.jar`，无需 Android NDK。
- 历史 Git 分支保持不变，便于审计和回滚；大阪 VPS 旧 3X-UI、VLESS
  Reality、WireGuard 服务绝不在本次任务中改动。

## 限制与验收
- Android 11+ 才支持 `AccessibilityService.takeScreenshot()`；本包 minSdk 24
  但低版本设备应显示不支持截图，不能回退无线调试。
- Android 后台回收、强制停止、重启后不保证持续连接或继续采集。
- 部署前须核实大阪服务器已支持 JPEG 媒体帧；仅 APK 构建成功不代表
  VPS 中转已更新。
- 需在 vivo/OPPO/小米 Android 14+ 真机分别测试安装、无障碍授权、
  互相配对、截图观看、结束共享、断网及后台恢复。
