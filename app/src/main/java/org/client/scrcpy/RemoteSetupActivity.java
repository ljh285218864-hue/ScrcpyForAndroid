package org.client.scrcpy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * No-VPN first-run enrollment and A/B bidirectional signaling smoke tests.
 * Explicit owner approval is required. This UI does not start screen capture.
 * No Android system VPN service, activation UI or import is available.
 */
public final class RemoteSetupActivity extends Activity {
    public static final String EXTRA_REQUEST_WATCH_ONCE =
            "org.client.scrcpy.EXTRA_REQUEST_WATCH_ONCE";
    private static final int SCAN = 9371;
    private static final int CAMERA = 9372;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button invite, approve, decline, test, revoke, backgroundToggle;
    private Button mediaConnectButton, mediaRequestButton, mediaEndButton;
    private Button sharingNotificationToggle;
    private TextView backgroundState;
    private TextView mediaState;
    private NoVpnMediaSocket mediaSocket;
    private LocalScrcpyStreamer localStreamer;
    private RelayVideoRenderer renderer;
    private SurfaceView remoteVideoSurface;
    private int receivedVideoFrames;
    private boolean requestAfterConnect;
    private boolean active;
    private boolean busy;
    private String currentState = "";
    private String pendingId = "";
    private String pendingCode = "";
    private boolean pendingExpired;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (!busy && NoVpnApi.isRegistered(RemoteSetupActivity.this)) {
                runNetwork(() -> {
                    JSONObject state = NoVpnApi.status(RemoteSetupActivity.this);
                    if ("paired".equals(state.optString("state"))) {
                        NoVpnApi.heartbeat(RemoteSetupActivity.this);
                        // Each polling cycle also retrieves messages sent
                        // during brief disconnections within 90 seconds.
                        JSONObject relay = NoVpnApi.poll(RemoteSetupActivity.this);
                        state.put("_events", relay.getJSONArray("messages"));
                        state.put("_cursor", relay.getLong("cursor"));
                    }
                    return state;
                }, RemoteSetupActivity.this::showStatus, true);
            }
            ui.postDelayed(this, 7000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestAfterConnect = getIntent().getBooleanExtra(
                EXTRA_REQUEST_WATCH_ONCE, false);
        setTitle("ScreenSync 无 VPN 配对");
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(20), dp(16), dp(22));

        TextView intro = new TextView(this);
        intro.setText("无 VPN 异地连接实验版：两台手机扫码、核对6位码并同意配对。"
                + "通过 HTTPS/WSS 信令和中转连接，不使用系统 VPN。"
                + "\n\n当前已接入本机 scrcpy 采集及视频解码代码，须部署中转服务并真机测试。"
                + "视频仅在被观看手机持有人明确同意本次会话后传输；可随时停止。"
                + "\n首次无线调试授权仍须由手机持有人按照系统要求完成。");
        intro.setTextSize(15);
        content.addView(intro);

        status = new TextView(this);
        status.setTextColor(Color.rgb(23, 71, 110));
        status.setTextSize(16);
        status.setPadding(0, dp(18), 0, dp(15));
        status.setText("正在准备…");
        content.addView(status);

        action(content, "扫码开通 / 扫码加入", v -> startScanner());
        invite = action(content, "主手机：生成5分钟配对二维码", v ->
                runNetwork(() -> NoVpnApi.invite(this), r -> {
                    try {
                        showQr("screensync://remote?code=" + r.getString("invite_code"));
                        status.setText("扫码后还需主手机确认，两台设备核对码必须一致。");
                    } catch (Exception error) {
                        report("二维码显示失败");
                    }
                }, false));
        approve = action(content, "核对无误：同意另一台手机", v -> {
            final String id = pendingId, code = pendingCode;
            if (id.isEmpty() || code.length() != 6 || pendingExpired) return;
            new AlertDialog.Builder(this).setTitle("确认对方身份")
                    .setMessage("双方都应显示同一个六位配对码：\n" + code
                            + "\n请与对方本人核对。通过后只开放信令；"
                            + "不会立即启动录屏或远程控制。")
                    .setPositiveButton("确认配对", (d, w) ->
                            runNetwork(() -> NoVpnApi.approve(this, id),
                                    result -> refreshStatus(), false))
                    .setNegativeButton("取消", null).show();
        });
        decline = action(content, "拒绝配对申请", v -> {
            final String id = pendingId;
            if (id.isEmpty()) return;
            runNetwork(() -> NoVpnApi.decline(this, id), result -> refreshStatus(), false);
        });
        test = action(content, "测试双方信令连接（不录屏）", v ->
                runNetwork(() -> {
                    NoVpnApi.status(this);
                    NoVpnApi.heartbeat(this);
                    return NoVpnApi.send(this, "hello",
                            new JSONObject().put("request_id", UUID.randomUUID().toString()));
                }, result -> report("连接测试消息已发送。对方打开本页面会看到消息。"),
                        false));
        action(content, "首次授权：打开本机无线调试设置", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception unsupported) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        action(content, "本机 ADB 配对码授权（只需系统要求时执行）",
                v -> showLocalAdbPairDialog());
        action(content, "检查本机无线调试是否已授权", v -> checkLocalAdbStatus());

        mediaState = new TextView(this);
        mediaState.setText("媒体连接：未连接；只在本页面可见时建立视频会话。");
        mediaState.setPadding(0, dp(12), 0, dp(8));
        content.addView(mediaState);
        remoteVideoSurface = new SurfaceView(this);
        content.addView(remoteVideoSurface, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));
        renderer = new RelayVideoRenderer(remoteVideoSurface,
                message -> runOnUiThread(() -> {
                    if (active && !isFinishing()) mediaState.setText(message);
                }));
        mediaConnectButton = action(content, "连接 WSS 视频中转",
                v -> connectMediaTest());
        mediaRequestButton = action(content, "一键请求观看配对手机", v -> {
            requestAfterConnect = true;
            if (mediaSocket == null) connectMediaTest();
            else if (mediaSocket.requestWatch()) {
                requestAfterConnect = false;
                mediaState.setText("已发送观看请求；等待对方在自己手机上确认");
            } else {
                mediaState.setText("等待 WSS 身份认证后自动发送本次观看请求");
            }
        });
        mediaEndButton = action(content, "停止观看 / 停止共享", v -> {
            requestAfterConnect = false;
            stopLocalStream();
            if (renderer != null) renderer.stopSession();
            if (mediaSocket != null) mediaSocket.endSession();
            mediaState.setText("已停止本次视频会话");
        });
        // Notification is optional. Never make the media session depend on it.
        sharingNotificationToggle = action(content, "", v -> {
            boolean enabled = !SharingStatusNotifier.isEnabled(this);
            SharingStatusNotifier.setEnabled(this, enabled);
            if (enabled && localStreamer != null) {
                SharingStatusNotifier.showIfEnabled(this);
            }
            refreshSharingNotificationButton();
        });
        refreshSharingNotificationButton();

        backgroundToggle = action(content, "后台待机：开启 / 关闭（仅维持配对状态）", v -> {
            if (!NoVpnApi.isRegistered(this) || !"paired".equals(currentState)) {
                report("两台手机完成双方确认后，才能启用后台恢复检查");
                return;
            }
            boolean enabled = !NoVpnBackgroundSync.isEnabled(this);
            try {
                NoVpnBackgroundSync.setEnabled(this, enabled);
                report(enabled
                        ? "后台待机已启用：联网、重启、更新后尽力恢复配对心跳；"
                        + "不传输屏幕。至少15分钟的周期可能被系统延迟。"
                        : "后台待机已关闭，双方配对关系仍保留。");
                refreshBackgroundState();
            } catch (RuntimeException error) {
                report("后台检查设置失败，请检查系统限制");
            }
        });
        backgroundState = new TextView(this);
        backgroundState.setTextSize(13);
        backgroundState.setPadding(0, dp(4), 0, dp(8));
        content.addView(backgroundState);
        refreshBackgroundState();
        action(content, "刷新状态", v -> {
            refreshBackgroundState();
            refreshStatus();
        });
        revoke = action(content, "双方解绑并注销所有旧凭证", v ->
                new AlertDialog.Builder(this).setTitle("双方撤销")
                        .setMessage("撤销将使两台设备的旧配对令牌立即失效，"
                                + "之后必须重新扫描并经双方批准。")
                        .setPositiveButton("确定撤销", (d, w) ->
                                runNetwork(() -> NoVpnApi.revoke(this), result -> {
                                    stopLocalStream();
                                    if (renderer != null) renderer.stopSession();
                                    if (mediaSocket != null) {
                                        mediaSocket.close();
                                        mediaSocket = null;
                                    }
                                    NoVpnBackgroundSync.setEnabled(this, false);
                                    pendingId = "";
                                    currentState = "";
                                    report("配对已撤销。两端旧凭证全部失效。");
                                    refreshButtons();
                                }, false))
                        .setNegativeButton("取消", null).show());
        action(content, "返回", v -> finish());
        ScrollView root = new ScrollView(this);
        root.addView(content);
        setContentView(root);
        refreshButtons();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        ui.removeCallbacks(refresh);
        if (!NoVpnApi.isRegistered(this)) refreshStatus();
        refresh.run();
        refreshButtons();
        // User returning to the app may request a short presence refresh.
        // This does NOT restart capture or an incoming watch session.
        NoVpnBackgroundSync.requestOnce(this, "owner_opened");
        refreshBackgroundState();
    }

    @Override protected void onPause() {
        active = false;
        requestAfterConnect = false;
        ui.removeCallbacks(refresh);
        // Leaving this visible screen stops local capture and remote video.
        stopLocalStream();
        if (renderer != null) renderer.stopSession();
        if (mediaSocket != null) {
            mediaSocket.close();
            mediaSocket = null;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopLocalStream();
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        super.onDestroy();
    }

    private void stopLocalStream() {
        LocalScrcpyStreamer current = localStreamer;
        localStreamer = null;
        // The notification is never the owner of the session lifecycle.
        SharingStatusNotifier.cancel(this);
        if (current != null) current.stop();
    }

    private void startLocalStream() {
        if (!active || mediaSocket == null || localStreamer != null) return;
        final LocalScrcpyStreamer[] ref = new LocalScrcpyStreamer[1];
        LocalScrcpyStreamer streamer = new LocalScrcpyStreamer(this, mediaSocket,
                new LocalScrcpyStreamer.Listener() {
                    @Override public void onState(String message) {
                        if (active && localStreamer == ref[0])
                            mediaState.setText(message);
                    }
                    @Override public void onStopped(String reason) {
                        if (localStreamer != ref[0]) return;
                        localStreamer = null;
                        SharingStatusNotifier.cancel(RemoteSetupActivity.this);
                        if (active && mediaSocket != null) {
                            mediaState.setText("本机视频结束：" + reason);
                            mediaSocket.endSourceSession();
                        }
                    }
                });
        ref[0] = streamer;
        localStreamer = streamer;
        if (!streamer.startAfterLocalApproval()) {
            localStreamer = null;
        } else {
            // Best-effort display only. A blocked notification must not
            // prevent or terminate the locally approved media session.
            SharingStatusNotifier.showIfEnabled(this);
        }
    }

    private void refreshSharingNotificationButton() {
        if (sharingNotificationToggle == null) return;
        sharingNotificationToggle.setText(
                SharingStatusNotifier.isEnabled(this)
                ? "共享状态通知：已开启（可关闭，不影响已授权共享）"
                : "共享状态通知：已关闭（不影响已授权共享）");
    }

    private void refreshBackgroundState() {
        if (backgroundState == null) return;
        if (!NoVpnBackgroundSync.isEnabled(this)) {
            backgroundState.setText("后台配对待机：关闭；无需通知权限。");
            return;
        }
        long at = NoVpnBackgroundSync.lastSuccess(this);
        String seen = at <= 0 ? "尚无成功心跳"
                : android.text.format.DateFormat.format("MM-dd HH:mm", at).toString();
        backgroundState.setText("后台配对待机：已启用；最近成功：" + seen
                + "；状态：" + NoVpnBackgroundSync.state(this)
                + "。后台待机不代表正在共享屏幕。");
    }

    private void showLocalAdbPairDialog() {
        String ownIp = PairQrCode.localWifiIp(this);
        if (ownIp == null || !PairQrCode.isPrivateIpv4(ownIp)) {
            report("请先连接 Wi-Fi，在本机设置打开无线调试");
            return;
        }
        LinearLayout input = new LinearLayout(this);
        input.setOrientation(LinearLayout.VERTICAL);
        input.setPadding(dp(18), dp(8), dp(18), 0);
        EditText port = new EditText(this);
        port.setHint("系统显示的配对端口（不是连接端口）");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.addView(port);
        EditText code = new EditText(this);
        code.setHint("系统显示的六位配对码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.addView(code);
        new AlertDialog.Builder(this)
                .setTitle("首次配对本机无线调试")
                .setMessage("先进入手机设置 → 开发者选项 → 无线调试"
                        + " → 使用配对码配对设备。"
                        + "\n请只输入本机系统显示的端口和六位码，"
                        + "不会上传到 ScreenSync 服务器。"
                        + "\n本机 Wi-Fi 地址：" + ownIp)
                .setView(input)
                .setPositiveButton("开始本机授权", (dialog, which) -> {
                    String p = port.getText().toString().trim();
                    String pin = code.getText().toString().trim();
                    code.setText(""); // Never save or log the 6-digit code.
                    try {
                        final int pairPort = Integer.parseInt(p);
                        if (!TrustedPairEndpoint.validAdbPort(pairPort)
                                || !pin.matches("[0-9]{6}")) {
                            report("端口或配对码格式不正确");
                            return;
                        }
                        runNetwork(() -> {
                            org.client.scrcpy.utils.ExecUtil.AdbCommandResult result =
                                    org.client.scrcpy.utils.AdbHelper.adbPair(
                                            this, ownIp, pairPort, pin);
                            if (result.exitCode != 0 || result.timedOut) {
                                throw new IllegalStateException(
                                        "本机无线调试配对失败或超时，请重新打开配对码");
                            }
                            return new JSONObject().put("result", "paired_locally");
                        }, result -> report("本机 ADB 配对已完成，请点击检查无线调试"),
                                false);
                    } catch (NumberFormatException invalid) {
                        report("无线调试配对端口不是有效数字");
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void checkLocalAdbStatus() {
        runNetwork(() -> {
            String ip = PairQrCode.localWifiIp(this);
            if (ip == null || !PairQrCode.isPrivateIpv4(ip))
                throw new IllegalStateException("本机未连接 Wi-Fi");
            org.client.scrcpy.utils.ExecUtil.AdbCommandResult mdns =
                    org.client.scrcpy.utils.AdbHelper.adbMdnsServices(this);
            int port = SystemAdbQrPairingSession.findAdbConnectPort(
                    mdns.combinedOutput(), ip);
            if (!TrustedPairEndpoint.validAdbPort(port))
                throw new IllegalStateException("未发现本机无线调试连接端口");
            if (!org.client.scrcpy.utils.AdbHelper.isDeviceOnline(this, ip, port))
                throw new IllegalStateException("本机尚未授权 ADB，或无线调试已关闭");
            return new JSONObject().put("state", "authorized");
        }, result -> report("已确认本机 ADB 连接授权有效"), false);
    }

    private void connectMediaTest() {
        if (!NoVpnApi.isRegistered(this) || !"paired".equals(currentState)) {
            report("先完成两台手机的配对确认，再测试 WSS 中转");
            return;
        }
        if (mediaSocket != null) {
            mediaState.setText("已有中转连接，请先结束后再重新连接");
            return;
        }
        receivedVideoFrames = 0;
        NoVpnMediaSocket socket = new NoVpnMediaSocket(this,
                new NoVpnMediaSocket.Listener() {
                    @Override public void onStatus(String value) {
                        if (!active || isFinishing()) return;
                        mediaState.setText("媒体中转：" + value);
                        if ("authenticated".equals(value) && requestAfterConnect) {
                            if (mediaSocket != null && mediaSocket.requestWatch()) {
                                requestAfterConnect = false;
                                mediaState.setText("已请求观看，等待对方同意本次屏幕共享");
                            }
                        }
                        if ("video_source_permitted".equals(value)) {
                            startLocalStream(); // after local consent + server confirmation
                        } else if ("source_session_ended".equals(value)
                                || "source_session_stopped".equals(value)) {
                            stopLocalStream();
                        } else if ("viewer_session_ended".equals(value)) {
                            if (renderer != null) renderer.stopSession();
                        } else if ("session_stopped_by_user".equals(value)
                                || "disconnected".equals(value)
                                || "network_unavailable".equals(value)) {
                            stopLocalStream();
                            if (renderer != null) renderer.stopSession();
                        }
                    }
                    @Override public void onRequest(String sid, String viewerId) {
                        if (!active || isFinishing()) return;
                        new AlertDialog.Builder(RemoteSetupActivity.this)
                                .setTitle("已配对设备请求观看")
                                .setMessage("对方设备 ID 尾号：" +
                                        viewerId.substring(Math.max(0, viewerId.length() - 6))
                                        + "\n是否允许对方观看你手机的当前屏幕？"
                                        + "\n允许后将通过本机已授权的 ADB + scrcpy 发送实时画面。"
                                        + "\n可点击停止共享立即结束。")
                                .setPositiveButton("允许本次屏幕共享", (dialog, which) -> {
                                    if (mediaSocket != null)
                                        mediaSocket.acceptRequest(sid);
                                })
                                .setNegativeButton("拒绝", (dialog, which) -> {
                                    if (mediaSocket != null)
                                        mediaSocket.declineRequest(sid);
                                }).show();
                    }
                    @Override public void onVideoSetup(String sid, int[] dimensions,
                                                        String codec) {
                        if (active && renderer != null) {
                            renderer.startSession(sid, dimensions, codec);
                            runOnUiThread(() -> {
                                if (active && !isFinishing())
                                    mediaState.setText("视频配置 " + codec + "；等待远端关键帧");
                            });
                        }
                    }
                    @Override public void onVideoPacket(String sid, byte[] packet) {
                        // Off UI thread: renderer uses bounded decode queue.
                        if (!active) return;
                        RelayVideoRenderer target = renderer;
                        if (target != null) target.onPacket(sid, packet);
                    }
                });
        try {
            socket.connect();
            mediaSocket = socket;
            mediaState.setText("正在认证 WSS 中转连接……");
        } catch (Exception error) {
            mediaState.setText(error.getMessage() == null
                    ? "WSS 中转连接配置无效"
                    : error.getMessage());
            socket.close();
        }
    }

    private int dp(int points) {
        return Math.round(points * getResources().getDisplayMetrics().density);
    }

    private Button action(LinearLayout layout, String text,
                          android.view.View.OnClickListener callback) {
        Button item = new Button(this);
        item.setText(text);
        item.setOnClickListener(callback);
        layout.addView(item);
        return item;
    }

    private void refreshButtons() {
        boolean registered = NoVpnApi.isRegistered(this);
        if (invite != null)
            invite.setEnabled(registered && "ready".equals(currentState) && !busy);
        if (approve != null)
            approve.setEnabled(registered && "ready".equals(currentState)
                    && !pendingId.isEmpty() && !pendingExpired && !busy);
        if (decline != null)
            decline.setEnabled(registered && !pendingId.isEmpty() && !busy);
        if (test != null)
            test.setEnabled(registered && "paired".equals(currentState) && !busy);
        if (revoke != null)
            revoke.setEnabled(registered && !busy);
        boolean paired = registered && "paired".equals(currentState);
        if (mediaConnectButton != null) mediaConnectButton.setEnabled(paired && !busy);
        if (mediaRequestButton != null) mediaRequestButton.setEnabled(paired && !busy);
        if (mediaEndButton != null) mediaEndButton.setEnabled(paired && !busy);
        if (backgroundToggle != null) {
            backgroundToggle.setEnabled(registered
                    && "paired".equals(currentState) && !busy);
            backgroundToggle.setText(NoVpnBackgroundSync.isEnabled(this)
                    ? "关闭后台定期检查（保留配对）"
                    : "启用后台定期检查（不显示常驻通知）");
        }
    }

    private void report(String value) {
        if (isFinishing() || isDestroyed()) return;
        status.setText(value);
        refreshButtons();
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private void runNetwork(Callable<JSONObject> work,
                            Consumer<JSONObject> onSuccess, boolean quiet) {
        if (busy || isFinishing()) return;
        busy = true;
        refreshButtons();
        new Thread(() -> {
            JSONObject response = null;
            String failure = null;
            try {
                response = work.call();
            } catch (Exception error) {
                failure = error.getMessage();
            }
            final JSONObject result = response;
            final String message = failure;
            ui.post(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                refreshButtons();
                if (message != null) {
                    if (!quiet) report(message);
                    else status.setText("连接服务器失败：" + message);
                } else if (onSuccess != null && result != null) {
                    onSuccess.accept(result);
                }
            });
        }, "screensync-novpn-http").start();
    }

    private void refreshStatus() {
        if (!NoVpnApi.isRegistered(this)) {
            currentState = "";
            status.setText("尚未开通。由管理员提供一次性开通二维码，"
                    + "再生成邀请供另一台手机扫码。"
                    + "\n不启用 VPN；目前不提供视频。");
            refreshButtons();
            return;
        }
        runNetwork(() -> NoVpnApi.status(this), this::showStatus, false);
    }

    private void showStatus(JSONObject state) {
        currentState = state.optString("state", "");
        pendingId = "";
        pendingCode = "";
        pendingExpired = false;
        if ("ready".equals(currentState)) {
            pendingId = state.optString("pending_id", "");
            pendingCode = state.optString("pending_verify_code", "");
            pendingExpired = state.optBoolean("pending_expired", false);
            status.setText(pendingId.isEmpty()
                    ? "主手机已开通，尚未配对。生成二维码让另一台手机扫描。"
                    : "等待主手机确认：" + state.optString("pending_name")
                        + "\n六位核对码：" + pendingCode
                        + (pendingExpired ? "\n邀请已过期，请拒绝后重新邀请。"
                                : "\n与另一台手机核对一致后再批准。"));
        } else if ("pending".equals(currentState)) {
            status.setText("申请已经提交，等待主手机同意。\n双方核对码："
                    + state.optString("pending_verify_code"));
        } else if ("paired".equals(currentState)) {
            String peerId = state.optString("peer_device_id", "");
            JSONArray events = state.optJSONArray("_events");
            StringBuilder description = new StringBuilder();
            description.append("已配对：").append(state.optString("peer_name", "另一台手机"))
                    .append("\n对方设备 ID 尾号：")
                    .append(peerId.length() > 6 ? peerId.substring(peerId.length() - 6) : peerId)
                    .append("\n双方核对码：").append(state.optString("pair_verify_code"))
                    .append("\n对方状态：")
                    .append(state.optBoolean("peer_online")
                            ? "最近45秒有心跳" : "最近45秒没有心跳（后台可能延迟）")
                    .append("\n对方最近心跳时间：")
                    .append(state.optLong("peer_last_seen", 0) > 0
                            ? new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                                java.util.Locale.getDefault()).format(
                                    new java.util.Date(
                                        state.optLong("peer_last_seen") * 1000L))
                            : "尚未收到")
                    .append("\n本机后台检查：")
                    .append(NoVpnBackgroundSync.isEnabled(this)
                            ? "已启用，系统择机执行，不保证实时" : "未启用")
                    .append("\n本机最后成功检查：")
                    .append(NoVpnBackgroundSync.lastSuccess(this) > 0
                            ? new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                                java.util.Locale.getDefault()).format(
                                    new java.util.Date(NoVpnBackgroundSync.lastSuccess(this)))
                            : "尚无")
                    .append("\n已启用：HTTPS 信令（无 VPN）")
                    .append("\n尚未启用：屏幕视频");
            if (events != null && events.length() > 0) {
                description.append("\n刚收到 ").append(events.length()).append(" 条消息");
                for (int i = 0; i < events.length(); i++) {
                    JSONObject event = events.optJSONObject(i);
                    if (event != null) description.append("\n")
                            .append(event.optString("type", "message"));
                }
            }
            status.setText(description);
            // When the user opens this page, maintain a connection only
            // while the Activity is visible. No foreground service is used.
            if (active && mediaSocket == null && NoVpnMediaSocket.endpointConfigured()) {
                connectMediaTest();
            }
            // Only acknowledge after the verified messages are rendered.
            // A process death before this call permits safe replay.
            if (state.has("_cursor")) {
                try {
                    NoVpnApi.acknowledge(this, state.getLong("_cursor"));
                } catch (Exception error) {
                    // The next poll will resend the message rather than lose
                    // a pending connection request.
                    status.append("\n信令已收到，但确认保存失败，可能再次显示。");
                }
            }
        } else {
            status.setText("服务器状态：" + currentState);
        }
        refreshButtons();
    }

    private void showQr(String payload) throws Exception {
        BitMatrix image = new MultiFormatWriter().encode(
                payload, BarcodeFormat.QR_CODE, 460, 460);
        Bitmap bitmap = Bitmap.createBitmap(460, 460, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 460; y++)
            for (int x = 0; x < 460; x++)
                bitmap.setPixel(x, y, image.get(x, y) ? Color.BLACK : Color.WHITE);
        ImageView view = new ImageView(this);
        view.setImageBitmap(bitmap);
        new AlertDialog.Builder(this).setTitle("5分钟有效的配对邀请")
                .setMessage("请让另一台手机扫码。双方还需要核对6位码。")
                .setView(view)
                .setPositiveButton("完成", (d, w) -> refreshStatus()).show();
    }

    private void startScanner() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA);
            return;
        }
        startActivityForResult(new Intent(this, AppQrCaptureActivity.class), SCAN);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions,
                                                      int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != CAMERA) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            startScanner();
        else report("需要相机权限才能扫码");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SCAN || result != RESULT_OK || data == null) return;
        String payload = data.getStringExtra(AppQrCaptureActivity.EXTRA_RESULT);
        String activation = NoVpnApi.parseQr(payload, "activate");
        String inviteCode = NoVpnApi.parseQr(payload, "remote");
        if (activation == null && inviteCode == null) {
            report("不是有效的 ScreenSync 开通/配对二维码");
            return;
        }
        if (NoVpnApi.isRegistered(this)) {
            report("本机已有配对身份，不能被新二维码覆盖。请先解绑。");
            return;
        }
        boolean owner = activation != null;
        new AlertDialog.Builder(this)
                .setTitle(owner ? "开通主手机" : "申请与主手机配对")
                .setMessage("只有在双方明确同意后才保存配对身份。"
                        + "\n此操作不采集或传输任何屏幕画面。")
                .setPositiveButton("同意", (d, w) ->
                        runNetwork(() -> owner
                                ? NoVpnApi.activate(this, activation)
                                : NoVpnApi.join(this, inviteCode),
                                response -> refreshStatus(), false))
                .setNegativeButton("取消", null).show();
    }
}
