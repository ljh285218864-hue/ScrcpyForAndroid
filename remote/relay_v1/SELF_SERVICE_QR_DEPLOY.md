# ScreenSync A 生成二维码、B 扫码配对 — 一次性部署说明

## 已实现

- 首次使用 A 手机点「生成配对二维码」，客户端通过 HTTPS 向现有信令服务发起 `POST /v1/self-register`。
- 服务端原子创建一对一隔离组、A 的 48 位设备凭证，以及有效 5 分钟的一次性邀请；不需要登录 VPS 或管理员激活码。
- B 点「扫描另一台手机二维码」，通过原 `/v1/join` 流程加入；A/B 核对相同的六位数字后，A 才可批准配对。
- 每组最多一个 owner 和一个 guest。观看屏幕仍需要被观看方明确同意本次会话，通知栏可立即停止。
- 后续 A/B 直接点击观看；恢复配对关系不需要再次扫描。
- 单个安装的全局自助注册限制：12 组/小时、30 组/天、现有总组上限默认 100。匿名注册不是严格的访问控制，公网开放仍可能被恶意消耗配额；生产推广前应增加边缘 IP 速率限制/防机器人或受控邀请码。

## 重要的安全开关

`SS_ALLOW_SELF_SERVICE=1` 才会启用新接口；默认关闭。错误 `SELF_REGISTER_DISABLED` 表示服务器尚未执行一次性升级，安卓 APK 不能单独启用此功能。

## 大阪 VPS 更新范围

由已授权的操作者在确认实际服务路径后审阅并执行：
`remote/relay_v1/deploy/deploy-self-service-signaling.sh`。

脚本针对已实际报告的目录：
- 源码：`/opt/screensync-relay-src/remote/relay_v1/server.py`
- Python：`/opt/screensync-relay-venv/bin/python`
- systemd：`screensync-signaling.service`
- 新增一个且仅一个专属 override：`/etc/systemd/system/screensync-signaling.service.d/90-self-service-qr.conf`

备份后只替换 `server.py`，建立 override、重启 `screensync-signaling`，检查本地 8766 和 /health。失败后恢复原文件并尝试撤销 override。**不会**触碰 media relay、nginx、x-ui、Reality、WireGuard、防火墙和既有配对数据库。脚本仅对已核实的服务布局执行，检查失败即中止。

## 安卓使用

1. A、B 两台手机安装同一新 APK。若之前调试 APK 证书不同，Android 可能不能覆盖安装；卸载会丢失本机身份令牌，现有配对需要重建。长期使用应配置固定的私有签名证书。
2. A 点「生成配对二维码」，系统通过 HTTPS 自动完成身份创建并显示二维码。
3. B 点「扫描另一台手机二维码」，扫描 A 的二维码；两台手机核对显示的六位数字。
4. A 点「确认配对」，之后互相观看无需再次配对（观看请求仍需被观看手机确认）。
5. 右上角「×」只关闭全屏画面，不注销身份、不切断仍有效的会话；真正结束共享可在首页点「结束本次观看 / 共享」，被观看方也可用状态通知停止。

## 验证

CI 对服务端实际 HTTP 测试覆盖：关闭开关、注册凭证、数据库不保存明文邀请、单次扫码、待配对双端验证码、跨组隔离、批准后心跳、解绑清理、邀请到期、续邀、全局限速及非法名称。
Android CI 编译 ARM64 APK，验证配对/视频 JPEG 协议接口存在。
真机互看仍须在正式 VPS 升级后进行 A↔B 4G/5G 测试，不能把单元测试当成真实连接已成功。
