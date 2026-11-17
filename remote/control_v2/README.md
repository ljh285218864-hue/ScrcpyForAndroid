# ScreenSync Beta4 multi-pair prototype — NOT DEPLOYED

> **状态：代码实验分支；尚未在大阪 VPS 部署、未进行真实 WireGuard / nft 隔离验证，不能向客户发布。**

本目录是旧 remote/control (固定 2 台、100.80.0.2/3) 的安全替代原型，不应把旧控制器装到新接口。

## 独立网络与现有 Beta3

- 保留现有 wg-screensync / UDP 51820 / 100.80.0.0/24 和 3X-UI / VLESS Reality 原样。
- 新网络专用接口 `wg-ss-beta4`，预留 UDP 51821，服务端 `100.81.0.1/16`，客户动态分配 100.81.x.x/32。
- 服务器拒绝复用旧接口/端口；启动时校验已存在的 WireGuard peers 必须能被当前数据库认领。
- `nft` 表 `inet screensync_beta4` 只拦截 Beta4 接口进出同一接口的转发；默认 DROP，仅显式同一组 A↔B IP 放行。
- 每组独立设备密钥、令牌、邀请和配对数据；默认最多 2 组，用 SCREENSYNC_MAX_PAIRS 在审慎压测后调整，不能承诺当前 512MB VPS 的容量。
- 配对码一次性、5 分钟有效；每组主手机由管理员在可信服务器终端签发一次性激活 QR，普通副手机扫码该组主手机的邀请二维码。

## 开发测试（不用 root，不修改系统）

```bash
python3 -m compileall -q remote/control_v2
python3 -m unittest discover -s remote/control_v2 -p 'test_*.py' -v
bash -n remote/control_v2/install_ubuntu.sh
bash -n remote/control_v2/issue_owner_qr.sh
```

## 未来的 VPS 上线门槛

1. 由 Work 在可信终端确认 Ubuntu 版本、旧 WireGuard peers、3X-UI Reality、UDP 51821 是否空闲、DNS/HTTPS 端口冲突、磁盘和 RAM，并创建可回滚快照。
2. 审核 `install_ubuntu.sh` 对独立接口和 systemd 服务的全部影响。脚本要求显式 SCREENSYNC_WG_ENDPOINT，首次运行会新增配置、专用 nft 表/Hook 和独立 WireGuard 服务；绝不应在未审核前运行。
3. 在确认 UDP 51821 未占用且防火墙允许、独立接口不会影响原 VPN 后，才执行如下命令：

```bash
git clone --branch feature/screensync-beta4-auto-remote --single-branch \
  https://github.com/ljh285218864-hue/ScrcpyForAndroid.git
cd ScrcpyForAndroid
sudo env SCREENSYNC_WG_ENDPOINT=166.88.95.233:51821 \
  bash remote/control_v2/install_ubuntu.sh
sudo systemctl status screensync-control-v2 --no-pager
curl --fail http://127.0.0.1:8765/health
```

4. 与 x-ui 原有 Reality 端口不冲突的情况下，配置 `screensync.privyluxe.com` DNS 和有效证书，反向代理 HTTPS :8443 → `127.0.0.1:8765`；8765 等控制端口严禁直接暴露公网。域名和 HTTPS **尚未配置**。
5. 管理员通过 `sudo bash remote/control_v2/issue_owner_qr.sh` 给每组生成一次性主手机开通码（只在可信终端扫描，严禁外传）。
6. 双方扫码、批准，验证两组互相不可达、同组可达、解绑后不可达，测试重启/网络切换/异常断网。Android 必须由本人确认 VPN 与无线调试授权。

## 安全与“只读”

**当前 ADB + scrcpy 拥有比只读更多的底层权限**，界面“只读”不能限制 ADB 服务实际获得的权限。因此本服务端原型仅用于已知可信设备的私网连接验证，绝不代表已开发了安全的多客户只读观看服务。面向客户的严格只读视频通道需要重新评估安卓合规屏幕共享技术（包括必要的系统提示和用户撤销授权能力）。

不能把服务端 Python 单元测试视作真实内核规则、无线调试在 VPN 上可达、客户授权和屏幕观看的验收。

安装和发布前应审查 root systemd 权限、nft 规则重启持久性、异常终止时 fail-closed 行为、TLS 代理限流、代码和密钥回滚。不要删除 Beta3 peers 来绕过保护规则。
