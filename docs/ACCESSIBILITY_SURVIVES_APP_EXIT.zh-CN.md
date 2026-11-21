# ScreenSync：退出软件不等于撤销系统无障碍权限

**产品约束：** 返回桌面、切换到其他应用、划掉最近任务卡片，以及 `RemoteSetupActivity.onPause/onDestroy` 仅改变界面和必要的 UI 监听状态，不能调用 `AccessibilityService.disableSelf()` 或写入 `Settings.Secure`。用户在 Android 系统设置中授权的无障碍开关只能由 Android 系统/设备持有人管理。

本次修正：

- 在 `AccessibilityShotService.onUnbind/onDestroy` 中增加 `system_switch=true/false` 只读诊断，以区别系统开关被关闭和服务连接丢失。
- 当 **无障碍服务本身** 被 Android 解绑或销毁时，仍须停止依赖它的截图会话；但不再误称“无障碍权限已关闭”，而改为“无障碍服务连接已中断，当前共享结束；系统权限可能仍开启”。
- 在活动 `APP_PAUSE`、`APP_DESTROY` 日志加入 `system_accessibility`（`connected`、`enabled_not_connected` 或 `system_off`），用于核对划卡片前后状态。
- 应用内“无障碍状态”帮助对话框说明退出软件不等于系统权限撤销。
- GitHub CI 添加防回归检查，不允许活动退出流程或无障碍服务调用 `disableSelf`、写入系统安全设置；当前唯一的正在共享通知和停止共享操作保持不变。

**无法保证：** 如果 OEM 安全策略、系统自动撤销权限、用户强行停止或系统更新导致 Android 真的把无障碍开关设为关闭，普通 App 无权将其自动打开。手机进程被终止后，已在运行的截图会话无法在没有系统许可的情况下自动继续；之后可恢复信令，但重新采集必须继续满足共享授权与可见停止入口要求。

排查步骤：不要卸载/换装 APK，先用同一版本开启无障碍，返回桌面、划卡片，再通过系统设置确认开关是否仍开启；回到 ScreenSync 点击“诊断日志”，对照 `APP_PAUSE`、`APP_DESTROY`、`ACCESSIBILITY service_unbound/service_destroyed system_switch=`。如果退出前开关为 true、退出后开关真的变 false，属于 Android/OEM 权限管理问题，不是 Activity 将权限关闭。
