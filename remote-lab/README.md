# Android 15 双机浏览器实时控制台

本目录为 ScrcpyForAndroid 的 **独立自托管测试部署方案**。在带 KVM 加速的 Linux 服务器上启动两台 Android 15（API 35）虚拟机，用 noVNC 把 A、B 两个画面以只读方式显示在 iPhone Safari 中。HTTPS 和密码保护由 Caddy 提供。

**注意：仓库有部署配置 ≠ 已有在线网页。** 需要先准备有足够硬件资源的服务器并部署验证；不能在 GitHub Actions 中长期托管。

## 服务器要求

- Ubuntu 24.04 **x86_64** 云主机，且可以访问 `/dev/kvm`（云服务商需要支持嵌套虚拟化）
- 建议至少 **4 vCPU、12 GB RAM、40 GB 可用磁盘**（安装脚本最低硬件检查：4 vCPU、8 GB 内存、30 GB 可用磁盘）
- 已安装 Docker Engine 和 Docker Compose v2
- 一个专用二级域名，例如 `lab.example.com`，A 记录指向此服务器。允许入站 TCP 80、443

目前已有的 1 vCPU / 512 MB / 5 GB 小型 VPS 不能胜任此任务。不要在运行 WordPress、3X-UI 或 LocShare 的现有生产服务器上贸然执行，本方案不会触碰它们的配置。

## 启动

将整个仓库克隆到 **新服务器**：

```bash
git clone https://github.com/ljh285218864-hue/ScrcpyForAndroid.git
cd ScrcpyForAndroid/remote-lab
bash check.sh
bash setup.sh
```

`setup.sh` 会先检查机器资源、KVM、Docker、80/443 端口占用，拒绝覆盖已有 `.env`；接着让你输入专用域名及 14 字符以上密码，生成密码哈希，建立本地 `.env`，然后尝试构建 Android 15 Docker 镜像并启动容器。

从浏览器访问 `https://你的专用域名/`，用户为 `viewer`，密码就是启动时设置的私有密码。HTTPS 由 Caddy 在域名 DNS/端口条件满足后自动申请证书。

提供 **双机 / 只看 A / 只看 B** 三个选项，iPhone 仅能查看画面；noVNC 的 `-viewonly` 会拒绝触摸和键盘输入。首次启动可能需要先完成安卓开机。**noVNC 没有安卓系统音频，YouTube 视频可能不流畅；若要流畅播放视频和音频，后续应换 WebRTC 流媒体链路。**

## 维护

```bash
docker compose ps
docker compose logs --tail=150 phone_a
docker compose logs --tail=150 phone_b
docker compose logs --tail=150 web
docker compose stop
```

A、B 使用不同的数据卷，停止容器不会删除 Android AVD 数据。不要随意执行 `docker compose down -v`，它会删除持久数据。

## B 安装 ScrcpyForAndroid

启动浏览器双机控制台后，B 还是一台独立虚拟手机，不是自动显示 A 画面的 scrcpy。要在 B 手机内部运行 ScrcpyForAndroid，需要准备 **x86_64 架构**的 Debug APK，再安装到 B：

```bash
docker compose cp /path/to/scrcpy-x86_64-debug.apk phone_b:/tmp/client.apk
docker compose exec phone_b adb wait-for-device
docker compose exec phone_b adb install -r /tmp/client.apk
```

在 B 中尝试连接 `phone_a:5558`：内部桥接将其转发到 A 的虚拟机 ADB。**ADB 端口从未发布到公网，仍应在 A 完成授权。** 此连接方式需要在真正部署后再验证；不要认为创建网页已自动完成 B→A 同屏。

## 安全约束

- 公网仅开放 Caddy 的 HTTPS Web 端口 80/443；noVNC 与 ADB 不暴露。
- 所有网页与 WebSocket 通过同一套加密 HTTPS + Basic Auth 验证。
- 密码哈希保存在本地 `.env` 中，已配置 `.gitignore`；**不要公开或上传 .env**。
- 管理员仍应维护系统防火墙、SSH 密钥和服务器安全更新。
- 这是需要实际云端硬件验证的初版：Dockerfile、GUI、noVNC iPhone 触控兼容及性能还未经过真实目标 VPS 测试。
