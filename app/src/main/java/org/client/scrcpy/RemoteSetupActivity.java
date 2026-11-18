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
import android.view.View;
import android.widget.FrameLayout;
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
    private Button invite, approve, decline, revoke;
    private Button mediaRequestButton, mediaEndButton;
    private TextView mediaState;
    private NoVpnMediaSocket mediaSocket;
    private NoVpnMediaSocket.Listener mediaListener;
    private String shownIncomingSession = "";
    private AccessibilityJpegStreamer jpegStreamer;
    private JpegFrameRenderer jpegRenderer;
    private ImageView jpegImage;
    private FullscreenViewer viewerLayer;
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

    // Minimal everyday screen: no ADB, USB, Wi-Fi IP, VPN, ports or test controls.
    // Either phone can start an independent pair; the other scans its QR.
    // The server is explicit opt-in and does not give media consent.
    private Button scanButton, accessibilityButton, notificationsButton;
    private TextView hint;
    private LinearLayout permissionsPanel;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestAfterConnect = getIntent().getBooleanExtra(
                EXTRA_REQUEST_WATCH_ONCE, false);
        setTitle("屏幕同步");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 249, 252));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(26), dp(20), dp(28));
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("屏幕同步");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(24, 35, 53));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("两台手机 · 扫码配对 · 异地观看");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.rgb(105, 115, 131));
        subtitle.setPadding(0, dp(5), 0, dp(20));
        content.addView(subtitle);

        LinearLayout statusPanel = panel(content);
        TextView statusTitle = heading(statusPanel, "连接状态");
        status = note(statusPanel, "正在检查配对状态…");
        status.setTextColor(Color.rgb(48, 63, 83));
        status.setPadding(0, dp(5), 0, dp(3));

        hint = note(content, "第一台手机生成二维码，第二台扫码，核对六位数字后即可连接。");
        hint.setPadding(dp(4), dp(12), dp(4), dp(12));

        invite = action(content, "生成配对二维码", v -> {
            if (NoVpnApi.isRegistered(this)) {
                runNetwork(() -> NoVpnApi.invite(this),
                        result -> displayPairInvite(result), false);
            } else {
                runNetwork(() -> NoVpnApi.registerOwner(this),
                        result -> {
                            currentState = "ready";
                            displayPairInvite(result);
                            refreshButtons();
                        }, false);
            }
        });
        primary(invite);

        scanButton = action(content, "扫描另一台手机二维码", v -> startScanner());
        primary(scanButton);

        approve = action(content, "确认配对", v -> {
            String id = pendingId;
            String code = pendingCode;
            if (id.isEmpty() || code.length() != 6 || pendingExpired) return;
            new AlertDialog.Builder(this)
                    .setTitle("核对配对码")
                    .setMessage("请与另一台手机核对这六位数字是否一致：\n\n"
                            + code + "\n\n确认后两台手机建立长期配对关系。"
                            + "以后观看屏幕仍需被观看方同意本次共享。")
                    .setPositiveButton("数字一致，确认配对", (d, w) ->
                            runNetwork(() -> NoVpnApi.approve(this, id),
                                    done -> refreshStatus(), false))
                    .setNegativeButton("取消", null).show();
        });
        primary(approve);
        decline = action(content, "拒绝配对", v -> {
            String id = pendingId;
            if (id.isEmpty()) return;
            runNetwork(() -> NoVpnApi.decline(this, id),
                    done -> refreshStatus(), false);
        });

        // The home screen has no black video placeholder; the remote image
        // lives only in the fullscreen layer created below.
        mediaState = note(statusPanel, "手机连接就绪后，点击观看对方屏幕");
        mediaState.setPadding(0, dp(8), 0, dp(2));

        mediaRequestButton = action(content, "观看对方屏幕", v -> {
            ScreenSyncDiagnostics.record(this, "USER_ACTION", "watch_fullscreen");
            if (viewerLayer != null) {
                viewerLayer.openPicture(jpegRenderer != null
                        && jpegRenderer.hasDisplayedFrame());
            }
            if (mediaSocket != null && mediaSocket.hasActiveViewerSession()) {
                // Closing the viewer never cancels an already approved session.
                return;
            }
            if (mediaSocket != null && mediaSocket.hasViewerRequestOutstanding()) {
                if (viewerLayer != null)
                    viewerLayer.showWaiting("等待对方手机确认本次共享…");
                return;
            }
            requestAfterConnect = true;
            if (mediaSocket == null) {
                connectMediaTest();
            } else if (mediaSocket.requestWatch()) {
                requestAfterConnect = false;
                mediaState.setText("已发送观看请求，等待对方手机同意");
                if (viewerLayer != null)
                    viewerLayer.showWaiting("已发送请求，等待对方手机同意…");
            } else {
                mediaState.setText("连接服务器中…");
                if (viewerLayer != null)
                    viewerLayer.showWaiting("连接服务器中…");
            }
        });
        primary(mediaRequestButton);

        mediaEndButton = action(content, "结束本次观看 / 共享", v -> {
            ScreenSyncDiagnostics.record(this, "USER_ACTION", "stop_pressed");
            requestAfterConnect = false;
            stopLocalStream();
            if (jpegRenderer != null) jpegRenderer.stopSession();
            if (mediaSocket != null) mediaSocket.endSession();
            if (viewerLayer != null) viewerLayer.closePicture();
            mediaState.setText("本次共享已结束，配对仍然有效");
        });

        permissionsPanel = panel(content);
        heading(permissionsPanel, "首次使用设置");
        accessibilityButton = action(permissionsPanel, "开启无障碍截图权限",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        notificationsButton = action(permissionsPanel, "允许共享状态通知",
                v -> openShareNotificationSettings());
        TextView securityNote = note(permissionsPanel,
                "无障碍服务需由手机持有人开启。每次共享均需本机明确同意，"
                + "共享时会显示可停止的通知。");
        securityNote.setPadding(dp(4), dp(6), dp(4), 0);

        revoke = action(content, "解除配对", v ->
                new AlertDialog.Builder(this)
                    .setTitle("确定解除配对？")
                    .setMessage("双方的配对身份将被撤销。解除后要重新扫码才能连接。")
                    .setPositiveButton("解除配对", (d, w) ->
                        runNetwork(() -> NoVpnApi.revoke(this), done -> {
                            stopLocalStream();
                            if (jpegRenderer != null) jpegRenderer.stopSession();
                            if (mediaSocket != null) {
                                ScreenSyncMediaKeeper.stop();
                                mediaSocket = null;
                            }
                            NoVpnBackgroundSync.setEnabled(this, false);
                            pendingId = "";
                            currentState = "";
                            report("配对已解除");
                            refreshStatus();
                        }, false))
                    .setNegativeButton("取消", null).show());

        action(content, "诊断日志 · 排查黑屏", v -> showDiagnostics());
        FrameLayout root = new FrameLayout(this);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        viewerLayer = new FullscreenViewer(this);
        viewerLayer.setOnClosedListener(() -> {
            // Closing a picture does not close WSS, but a request that has
            // not yet been transmitted must not auto-start while hidden.
            requestAfterConnect = false;
        });
        root.addView(viewerLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        jpegImage = viewerLayer.image();
        jpegRenderer = new JpegFrameRenderer(jpegImage);
        jpegRenderer.setFirstFrameCallback(() -> {
            if (viewerLayer != null) viewerLayer.hideWaiting();
        });
        setContentView(root);
        ScreenSyncDiagnostics.environment(this);
        ScreenSyncDiagnostics.record(this, "APP", "simple_ui_started");
        refreshButtons();
        refreshPermissions();
    }

    private void showDiagnostics() {
        boolean notificationOk = AccessibilityJpegStreamer.notificationAllowed(this);
        ScreenSyncDiagnostics.record(this, "DIAGNOSTICS", "paired="
                + "paired".equals(currentState)
                + " accessibility=" + AccessibilityShotService.ready()
                + " notification=" + notificationOk
                + " media_socket=" + (mediaSocket != null)
                + " sharing=" + (jpegStreamer != null)
                + " received=" + receivedVideoFrames);
        TextView logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(14), dp(10), dp(14), dp(10));
        logText.setText(ScreenSyncDiagnostics.read(this));
        ScrollView view = new ScrollView(this);
        view.addView(logText);
        new AlertDialog.Builder(this)
                .setTitle("ScreenSync 诊断日志")
                .setMessage("在两台手机分别尝试观看后，打开这里复制日志。"
                        + "不会复制屏幕内容、密钥或完整设备ID。")
                .setView(view)
                .setPositiveButton("复制日志", (dialog, which) -> {
                    String copyText = ScreenSyncDiagnostics.read(this);
                    android.content.ClipboardManager manager =
                            (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                    manager.setPrimaryClip(android.content.ClipData.newPlainText(
                            "ScreenSync 诊断日志", copyText));
                    Toast.makeText(this, "日志已复制，可直接发给我排查",
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("清空日志", (dialog, which) -> {
                    ScreenSyncDiagnostics.clear(this);
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(232, 236, 243));
        card.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        parent.addView(card, p);
        return card;
    }

    private TextView heading(LinearLayout parent, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(15);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setTextColor(Color.rgb(30, 41, 59));
        parent.addView(label);
        return label;
    }

    private TextView note(LinearLayout parent, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(13);
        label.setTextColor(Color.rgb(111, 120, 136));
        label.setLineSpacing(dp(3), 1f);
        parent.addView(label);
        return label;
    }

    private void primary(Button button) {
        if (button == null) return;
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(37, 99, 235));
        bg.setCornerRadius(dp(12));
        button.setBackground(bg);
        button.setTextColor(Color.WHITE);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
    }

    private void openShareNotificationSettings() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.POST_NOTIFICATIONS}, 9434);
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (RuntimeException unavailable) {
            ScreenSyncDiagnostics.record(this, "PERMISSION_UI",
                    "notifications_settings_unavailable");
            report("请在系统设置 → 应用 → 屏幕同步 → 通知中手动开启");
        }
    }

    private void showSourcePrerequisiteDialog(String issue) {
        boolean needsAccessibility = "accessibility_not_enabled".equals(issue);
        String message = needsAccessibility
                ? "被观看手机没有开启无障碍截图服务。请先在本机设置开启，再让另一台手机重新申请观看。"
                : "被观看手机没有允许 ScreenSync 显示共享状态通知。"
                    + "请开启应用通知和 ScreenSync 共享状态通知频道，再重新申请观看。";
        new AlertDialog.Builder(this)
                .setTitle("当前无法共享屏幕")
                .setMessage(message + "\n\n本次请求已拒绝，不会在后台偷偷启动共享。")
                .setPositiveButton(needsAccessibility ? "去开启无障碍" : "去开启通知",
                        (dialog, which) -> {
                            if (needsAccessibility) {
                                startActivity(new Intent(
                                        Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            } else {
                                openShareNotificationSettings();
                            }
                        })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void refreshPermissions() {
        if (accessibilityButton != null) {
            accessibilityButton.setText(AccessibilityShotService.ready()
                    ? "✓ 无障碍截图已授权" : "1 · 开启无障碍截图权限");
            accessibilityButton.setEnabled(!AccessibilityShotService.ready());
        }
        if (notificationsButton != null) {
            boolean allowed = AccessibilityJpegStreamer.notificationAllowed(this);
            notificationsButton.setText(allowed
                    ? "✓ 共享状态通知已允许" : "2 · 允许共享状态通知");
            notificationsButton.setEnabled(!allowed);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ScreenSyncDiagnostics.record(this, "APP_RESUME", "registered="
                + NoVpnApi.isRegistered(this)
                + " accessibility=" + AccessibilityShotService.ready());
        active = true;
        ScreenSyncWatchNotice.clear(this);
        if (mediaSocket != null) mediaSocket.redispatchIncomingRequest();
        ui.removeCallbacks(refresh);
        if (!NoVpnApi.isRegistered(this)) refreshStatus();
        refresh.run();
        refreshButtons();
        refreshPermissions();
        ui.postDelayed(this::refreshPermissions, 600);
    }

    @Override public void onBackPressed() {
        if (viewerLayer != null && viewerLayer.isShowing()) {
            viewerLayer.closePicture();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        active = false;
        requestAfterConnect = false;
        ui.removeCallbacks(refresh);
        // Do not terminate a user-approved share or an authenticated WSS
        // connection merely because this Activity is covered or swiped away.
        // This is process-lifetime best effort, NOT a guarantee after
        // Android kills the process, force-stop, or device reboot.
        ScreenSyncDiagnostics.record(this, "APP_PAUSE",
                "socket_kept=" + (mediaSocket != null)
                + " sharing=" + AccessibilityJpegStreamer.sharingActive());
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (viewerLayer != null) viewerLayer.closePicture();
        if (mediaSocket != null && mediaListener != null)
            mediaSocket.clearListenerIf(mediaListener);
        // The transport is retained by ScreenSyncMediaKeeper in this process.
        // The screenshot capturer has its own stop notification while active.
        if (jpegRenderer != null) {
            jpegRenderer.close();
            jpegRenderer = null;
        }
        ScreenSyncDiagnostics.record(this, "APP_DESTROY",
                "socket_kept=" + (ScreenSyncMediaKeeper.current() != null)
                + " sharing=" + AccessibilityJpegStreamer.sharingActive());
        super.onDestroy();
    }

    private void stopLocalStream() {
        AccessibilityJpegStreamer shot = jpegStreamer;
        jpegStreamer = null;
        if (shot != null) shot.stop();
        else AccessibilityJpegStreamer.stopActiveWithoutSending();
    }

    private void startLocalStream() {
        // May arrive just after the device owner tapped "Allow" and left the
        // Activity; explicit consent has already been authenticated by WSS.
        if (mediaSocket == null || jpegStreamer != null
                || AccessibilityJpegStreamer.sharingActive()) return;
        if (!AccessibilityShotService.ready()) {
            ScreenSyncDiagnostics.record(this, "SOURCE_START", "accessibility_not_ready");
            mediaState.setText("需要在本机设置中开启无障碍截图权限");
            mediaSocket.endSourceSession("source_accessibility_missing");
            return;
        }
        final AccessibilityJpegStreamer[] ref = new AccessibilityJpegStreamer[1];
        AccessibilityJpegStreamer shot = new AccessibilityJpegStreamer(
                this, mediaSocket, reason -> {
                    if (jpegStreamer == ref[0]) {
                        jpegStreamer = null;
                        if (active) mediaState.setText(reason);
                    }
                });
        ref[0] = shot;
        if (shot.start()) {
            ScreenSyncDiagnostics.record(this, "SOURCE_START", "jpeg_stream_started");
            jpegStreamer = shot;
            mediaState.setText("屏幕共享中，可从通知栏立即停止");
        } else {
            String reason = AccessibilityJpegStreamer.readinessIssue(this);
            ScreenSyncDiagnostics.record(this, "SOURCE_START",
                    "jpeg_start_failed prereq=" + (reason.isEmpty()
                            ? "capture_or_transport" : reason));
            mediaState.setText(reason.isEmpty()
                    ? "本次共享启动失败，请查看诊断日志"
                    : "共享前请先开启无障碍及通知权限");
            mediaSocket.endSourceSession(reason.isEmpty()
                    ? "source_start_failed" : "source_permissions_missing");
        }
    }

    private String friendlyMediaStatus(String status) {
        switch (status) {
            case "connecting":
            case "authenticating":
                return "正在安全连接服务器…";
            case "authenticated":
                return "已连接，等待观看";
            case "peer_online":
                return "配对手机已连接";
            case "peer_offline":
                return "对方暂时未打开软件";
            case "awaiting_peer_approval":
                return "等待另一台手机确认共享";
            case "viewer_session_active":
                return "连接成功，等待对方画面";
            case "video_source_permitted":
                return "正在向另一台手机共享画面";
            case "source_session_ended":
            case "viewer_session_ended":
            case "session_stopped_by_user":
            case "source_session_stopped":
                return "共享已结束";
            case "disconnected":
            case "network_unavailable":
                return "网络暂时断开，请重试";
            default:
                if (status.startsWith("server_error:"))
                    return "服务器拒绝：" + status.substring("server_error:".length());
                if ("protocol_error".equals(status))
                    return "接收协议错误，请查看诊断日志并更新到修复版";
                return "连接状态：" + status;
        }
    }

    private void connectMediaTest() {
        if (!NoVpnApi.isRegistered(this) || !"paired".equals(currentState)) {
            report("请先完成两台手机的扫码配对");
            return;
        }
        if (mediaSocket != null) {
            mediaState.setText("正在连接对方，请稍候");
            return;
        }
        receivedVideoFrames = 0;
        NoVpnMediaSocket.Listener callback = new NoVpnMediaSocket.Listener() {
                    @Override public void onStatus(String value) {
                        if ("video_source_permitted".equals(value)) {
                            // Start the previously approved share even if the
                            // owner just pressed Home after approving.
                            startLocalStream();
                        }
                        if ("source_session_ended".equals(value)
                                || "source_session_stopped".equals(value)
                                || "session_stopped_by_user".equals(value)
                                || "disconnected".equals(value)
                                || "network_unavailable".equals(value)) {
                            // Must stop screenshots even after the Activity becomes hidden.
                            stopLocalStream();
                        }
                        if (!active || isFinishing()) return;
                        mediaState.setText("viewer_session_ended".equals(value)
                                && receivedVideoFrames == 0
                                ? "对方结束了共享且没有发送画面，请检查被观看手机权限"
                                : friendlyMediaStatus(value));
                        if (viewerLayer != null && viewerLayer.isShowing()) {
                            if ("awaiting_peer_approval".equals(value))
                                viewerLayer.showWaiting("正在等待对方确认共享…");
                            else if ("viewer_session_active".equals(value))
                                viewerLayer.showWaiting("对方已同意，等待画面…");
                            else if ("viewer_session_ended".equals(value)
                                    || "session_stopped_by_user".equals(value)) {
                                if ("viewer_session_ended".equals(value)
                                        && receivedVideoFrames == 0)
                                    viewerLayer.showWaiting(
                                            "没有收到画面。请在被观看手机开启无障碍截图和应用通知，"
                                            + "然后关闭画面并重新申请观看。");
                                else
                                    viewerLayer.showWaiting(
                                            "本次共享已结束，点击右上角关闭");
                            }
                            else if ("peer_offline".equals(value))
                                viewerLayer.showWaiting("配对手机暂未连接，请在对方手机打开软件");
                            else if ("disconnected".equals(value)
                                    || "network_unavailable".equals(value))
                                viewerLayer.showWaiting("网络连接中断，请关闭后重试");
                            else if ("protocol_error".equals(value)
                                    || value.startsWith("server_error:"))
                                viewerLayer.showWaiting(friendlyMediaStatus(value));
                        }
                        if ("authenticated".equals(value) && requestAfterConnect) {
                            if (mediaSocket != null && mediaSocket.requestWatch()) {
                                requestAfterConnect = false;
                                mediaState.setText("已请求观看，等待对方同意本次屏幕共享");
                            }
                        }
                        if ("source_session_ended".equals(value)
                                || "source_session_stopped".equals(value)) {
                            stopLocalStream();
                        } else if ("viewer_session_ended".equals(value)) {
                            if (jpegRenderer != null) jpegRenderer.stopSession();
                            receivedVideoFrames = 0;
                        } else if ("session_stopped_by_user".equals(value)
                                || "disconnected".equals(value)
                                || "network_unavailable".equals(value)) {
                            stopLocalStream();
                            if (jpegRenderer != null) jpegRenderer.stopSession();
                            receivedVideoFrames = 0;
                        }
                    }
                    @Override public void onRequest(String sid, String viewerId) {
                        if (!active || isFinishing()) {
                            ScreenSyncDiagnostics.record(RemoteSetupActivity.this,
                                    "WATCH_INBOUND", "background_notification");
                            ScreenSyncWatchNotice.show(RemoteSetupActivity.this);
                            return;
                        }
                        if (sid.equals(shownIncomingSession)) return;
                        shownIncomingSession = sid;
                        ScreenSyncDiagnostics.record(RemoteSetupActivity.this,
                                "WATCH_INBOUND", "request_received");
                        new AlertDialog.Builder(RemoteSetupActivity.this)
                                .setTitle("已配对设备请求观看")
                                .setMessage("对方设备 ID 尾号：" +
                                        viewerId.substring(Math.max(0, viewerId.length() - 6))
                                        + "\n是否允许对方观看你手机的当前屏幕？"
                                        + "\n将使用本机已授权的无障碍截图发送画面；通知栏显示正在共享。"
                                        + "\n可点击停止共享立即结束。")
                                .setPositiveButton("允许本次屏幕共享", (dialog, which) -> {
                                    ScreenSyncDiagnostics.record(RemoteSetupActivity.this,
                                            "USER_ACTION", "share_approved");
                                    String issue = AccessibilityJpegStreamer
                                            .readinessIssue(RemoteSetupActivity.this);
                                    if (!issue.isEmpty()) {
                                        ScreenSyncDiagnostics.record(RemoteSetupActivity.this,
                                                "SHARE_PREFLIGHT", "blocked=" + issue);
                                        if (mediaSocket != null)
                                            mediaSocket.declineRequest(sid);
                                        showSourcePrerequisiteDialog(issue);
                                        return;
                                    }
                                    if (mediaSocket != null && !mediaSocket.acceptRequest(sid)) {
                                        ScreenSyncDiagnostics.record(RemoteSetupActivity.this,
                                                "SHARE_PREFLIGHT", "accept_send_failed");
                                        mediaState.setText("共享请求失效，请让对方重新点击观看");
                                    }
                                })
                                .setNegativeButton("拒绝", (dialog, which) -> {
                                    ScreenSyncDiagnostics.record(RemoteSetupActivity.this,
                                            "USER_ACTION", "share_declined");
                                    if (mediaSocket != null)
                                        mediaSocket.declineRequest(sid);
                                }).show();
                    }
                    @Override public void onVideoSetup(String sid, int[] dimensions,
                                                        String codec) {
                        ScreenSyncDiagnostics.record(RemoteSetupActivity.this, "VIEW_SETUP",
                                "codec=" + codec + " active=" + active);
                        if (!active) return;
                        if ("jpeg".equals(codec) && jpegRenderer != null) {
                            jpegRenderer.startSession(sid);
                            runOnUiThread(() -> {
                                receivedVideoFrames = 0;
                                if (viewerLayer != null && viewerLayer.isShowing())
                                    viewerLayer.showWaiting("正在等待对方发送屏幕画面…");
                                mediaState.setText("已建立画面传输，等待首帧");
                            });
                        } else {
                            ScreenSyncDiagnostics.record(RemoteSetupActivity.this,
                                    "VIEW_SETUP", "unsupported_video_codec=" + codec);
                            runOnUiThread(() -> {
                                mediaState.setText("对方视频格式不兼容，请双方安装 JPEG 修复版");
                                if (viewerLayer != null)
                                    viewerLayer.showWaiting("对方视频格式不兼容");
                            });
                        }
                    }
                    @Override public void onVideoPacket(String sid, byte[] packet) {
                        if (!active) return;
                        if (packet != null && packet.length > 0 && packet[0] == 2) {
                            JpegFrameRenderer target = jpegRenderer;
                            if (target != null) {
                                target.onPacket(sid, packet);
                                if (receivedVideoFrames++ == 0)
                                    runOnUiThread(() -> {
                                        if (active) mediaState.setText("正在观看对方屏幕");
                                    });
                            }
                        }
                    }
                };
        mediaListener = callback;
        NoVpnMediaSocket retained = ScreenSyncMediaKeeper.current();
        if (retained != null) {
            retained.setListener(callback);
            mediaSocket = retained;
            if (retained.hasConfiguredVideo() && jpegRenderer != null)
                jpegRenderer.startSession(retained.activeViewerSessionId());
            mediaState.setText("已恢复进程内的连接");
            retained.redispatchIncomingRequest();
            return;
        }
        NoVpnMediaSocket socket = new NoVpnMediaSocket(this, callback);
        try {
            socket.connect();
            ScreenSyncMediaKeeper.retain(socket);
            mediaSocket = socket;
            mediaState.setText("正在认证 WSS 中转连接……");
        } catch (Exception error) {
            ScreenSyncDiagnostics.record(this, "WSS_CONNECT_FAIL",
                    "error=" + error.getClass().getSimpleName());
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
        item.setAllCaps(false);
        item.setText(text);
        item.setTextSize(15);
        item.setMinHeight(dp(49));
        item.setPadding(dp(12), dp(12), dp(12), dp(12));
        item.setTextColor(Color.rgb(53, 65, 84));
        android.graphics.drawable.GradientDrawable surface =
                new android.graphics.drawable.GradientDrawable();
        surface.setColor(Color.WHITE);
        surface.setCornerRadius(dp(12));
        surface.setStroke(dp(1), Color.rgb(219, 225, 236));
        item.setBackground(surface);
        item.setOnClickListener(callback);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        layout.addView(item, p);
        return item;
    }

    private void refreshButtons() {
        boolean registered = NoVpnApi.isRegistered(this);
        boolean paired = registered && "paired".equals(currentState);
        boolean ready = registered && "ready".equals(currentState);
        boolean pending = !pendingId.isEmpty() && !pendingExpired;
        if (scanButton != null) {
            scanButton.setVisibility(registered ? android.view.View.GONE
                    : android.view.View.VISIBLE);
            scanButton.setEnabled(!busy);
        }
        if (invite != null) {
            invite.setVisibility((!registered || ready) && pendingId.isEmpty()
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            invite.setEnabled(!busy);
        }
        if (approve != null) {
            approve.setVisibility(ready && pending
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            approve.setEnabled(!busy);
        }
        if (decline != null) {
            decline.setVisibility(ready && !pendingId.isEmpty()
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            decline.setEnabled(!busy);
        }
        if (mediaRequestButton != null) {
            mediaRequestButton.setVisibility(paired
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            mediaRequestButton.setEnabled(!busy);
        }
        if (mediaEndButton != null) {
            mediaEndButton.setVisibility(paired
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            mediaEndButton.setEnabled(!busy);
        }
        if (permissionsPanel != null)
            permissionsPanel.setVisibility(paired
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        if (revoke != null) {
            revoke.setVisibility(registered
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            revoke.setEnabled(!busy);
        }
        if (hint != null) {
            if (!registered) {
                hint.setText("A 手机直接生成二维码，B 手机扫码，双方确认六位数字。"
                        + "不需要管理员开通码或输入服务器地址。");
            } else if (ready) {
                hint.setText(pendingId.isEmpty()
                        ? "让另一台手机扫描本机生成的二维码。过期可直接重新生成。"
                        : "请核对两台手机显示的六位数字，再点击确认配对。");
            } else if ("pending".equals(currentState)) {
                hint.setText("请让另一台手机检查六位配对码并点击确认。");
            } else if (paired) {
                hint.setText("配对已完成。以后直接点击观看，"
                        + "被观看方需要确认本次共享；不需要重新扫码。");
            } else {
                hint.setText("正在连接服务器…");
            }
        }
        refreshPermissions();
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
            status.setText("尚未配对 · 生成二维码或扫描对方二维码");
            refreshButtons();
            return;
        }
        runNetwork(() -> NoVpnApi.status(this), this::showStatus, false);
    }

    private void showStatus(JSONObject state) {
        String previousState = currentState;
        currentState = state.optString("state", "");
        if (!currentState.equals(previousState))
            ScreenSyncDiagnostics.record(this, "PAIRING_STATE", currentState);
        pendingId = "";
        pendingCode = "";
        pendingExpired = false;
        if ("ready".equals(currentState)) {
            pendingId = state.optString("pending_id", "");
            pendingCode = state.optString("pending_verify_code", "");
            pendingExpired = state.optBoolean("pending_expired", false);
            status.setText(pendingId.isEmpty() ? "已创建配对组 · 等待另一台手机扫码"
                    : "收到配对申请：" + state.optString("pending_name", "新设备")
                    + "\n核对码  " + pendingCode
                    + (pendingExpired ? "\n申请已过期，请拒绝后重新扫码"
                            : "\n请核对另一台手机的六位数字"));
        } else if ("pending".equals(currentState)) {
            status.setText("申请已发送 · 等待另一台确认"
                    + "\n核对码  " + state.optString("pending_verify_code"));
        } else if ("paired".equals(currentState)) {
            String peerName = state.optString("peer_name", "配对手机");
            status.setText("✓ 已连接配对手机：" + peerName
                    + "\n" + (state.optBoolean("peer_online")
                            ? "对方最近在线" : "对方暂未在线，可能需要打开软件"));
            // Pairing remains valid after app restart. Only network identity
            // reconnects automatically; no automatic screen capture.
            if (active && mediaSocket == null && NoVpnMediaSocket.endpointConfigured()) {
                connectMediaTest();
            }
            if (state.has("_cursor")) {
                try {
                    NoVpnApi.acknowledge(this, state.getLong("_cursor"));
                } catch (Exception error) {
                    status.append("\n消息状态刷新失败，请重试");
                }
            }
        } else {
            status.setText(currentState.isEmpty() ? "尚未开通，请扫码"
                    : "正在准备：" + currentState);
        }
        refreshButtons();
    }

    private void displayPairInvite(JSONObject result) {
        try {
            String inviteCode = result.getString("invite_code");
            if (!inviteCode.matches("[a-f0-9]{48}"))
                throw new IllegalArgumentException("invalid_invite");
            showQr("screensync://remote?code=" + inviteCode);
            status.setText("二维码已生成，请让第二台手机扫码并核对六位码");
        } catch (Exception error) {
            ScreenSyncDiagnostics.record(this, "PAIR_QR", "create_or_display_error");
            report("二维码显示失败，可再次点击生成");
        }
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
        new AlertDialog.Builder(this).setTitle("配对二维码 · 5分钟有效")
                .setMessage("让另一台手机打开 ScreenSync 扫描本二维码。之后双方核对六位数字并确认。")
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
            report("不是有效的 ScreenSync 配对二维码");
            return;
        }
        if (NoVpnApi.isRegistered(this)) {
            report("本机已有配对身份，不能被新二维码覆盖。请先解绑。");
            return;
        }
        boolean owner = activation != null;
        new AlertDialog.Builder(this)
                .setTitle(owner ? "兼容旧版开通二维码" : "申请与另一台手机配对")
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
