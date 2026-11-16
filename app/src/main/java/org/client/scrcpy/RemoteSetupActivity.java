package org.client.scrcpy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONObject;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * First-run remote setup; QR-based, mutually approved, no .conf imports.
 * The OS VPN permission and ADB/wireless debugging prompts remain visible.
 */
public final class RemoteSetupActivity extends Activity {
    private static final int SCAN_REQUEST = 9371;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button showInvite;
    private Button approve;
    private Button decline;
    private Button revoke;
    private String pendingId;
    private boolean busy;
    private boolean resumed;
    private boolean receiverRequested;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (AutoRemoteProvisioner.isRegistered(RemoteSetupActivity.this) && !busy) {
                perform("正在查询配对状态…",
                        () -> AutoRemoteProvisioner.status(RemoteSetupActivity.this),
                        RemoteSetupActivity.this::handleStatus, true);
            }
            ui.postDelayed(this, 7000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("ScreenSync 异地连接");
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(16);
        content.setPadding(margin, margin, margin, margin);
        content.setBackgroundColor(Color.rgb(248, 249, 250));

        TextView intro = new TextView(this);
        intro.setText("首次使用需双方同意配对。自动配置专属加密网络，无需导入文件或填写服务器地址。\n\n"
                + "首次 VPN 授权和被观看手机的无线调试/ADB 授权仍须本人完成。"
                + "对方只有得到明确授权后才能尝试观看，双方均可断开。");
        intro.setTextColor(Color.rgb(40, 44, 49));
        intro.setTextSize(15f);
        content.addView(intro);

        status = new TextView(this);
        status.setTextSize(16f);
        status.setTextColor(Color.rgb(17, 66, 108));
        status.setPadding(0, dp(20), 0, dp(18));
        status.setText("准备检查设备状态…");
        content.addView(status);

        Button scan = action(content, "扫码开通／加入异地配对", v ->
                startActivityForResult(new Intent(this, AppQrCaptureActivity.class),
                        SCAN_REQUEST));
        scan.setContentDescription("扫描管理员一次性开通码或另一部手机的配对邀请码");

        showInvite = action(content, "主手机：生成配对二维码（5分钟有效）",
                v -> perform("正在生成配对邀请…",
                        () -> AutoRemoteProvisioner.createInvite(this),
                        response -> {
                            try {
                                showQr(AutoRemoteProvisioner.inviteQr(
                                        response.getString("invite_code")));
                                status.setText("请让另一部手机扫码；扫码后还需要你确认。");
                            } catch (Exception error) {
                                message("配对码显示失败");
                            }
                        }, false));

        approve = action(content, "同意待配对手机",
                v -> {
                    String id = pendingId;
                    if (id == null) return;
                    new AlertDialog.Builder(this)
                            .setTitle("确认授权配对")
                            .setMessage("你确认这台手机是家人持有的设备吗？\n"
                                    + "确认后双方会获得私有网络通道，"
                                    + "但屏幕观看仍要求被观看方的系统授权。")
                            .setPositiveButton("确认配对", (d, w) ->
                                    perform("正在批准配对…",
                                            () -> AutoRemoteProvisioner.approve(this, id),
                                            value -> updateStatus(), false))
                            .setNegativeButton("取消", null).show();
                });

        decline = action(content, "拒绝待配对手机", v -> {
            String id = pendingId;
            if (id == null) return;
            new AlertDialog.Builder(this)
                    .setTitle("拒绝配对申请")
                    .setMessage("拒绝后这台手机不能加入加密网络，可重新生成新的邀请。")
                    .setPositiveButton("拒绝", (d, w) ->
                            perform("正在拒绝配对…",
                                    () -> AutoRemoteProvisioner.decline(this, id),
                                    value -> {
                                        pendingId = null;
                                        updateStatus();
                                    }, false))
                    .setNegativeButton("取消", null).show();
        });

        Button reload = action(content, "刷新连接状态", v -> updateStatus());
        revoke = action(content, "解绑并撤销异地访问", v -> {
            new AlertDialog.Builder(this)
                    .setTitle("断开并撤销配对")
                    .setMessage("服务器会立即删除当前远程配对权限，并清除本机的远程网络配置。确定继续吗？")
                    .setPositiveButton("撤销访问", (d, which) ->
                            perform("正在从服务器撤销授权…",
                                    () -> AutoRemoteProvisioner.revoke(this),
                                    result -> {
                                        // Stop active Android VPN before deleting its config.
                                        EmbeddedRemoteTunnel.stop(this, (active, info) -> {
                                            if (EmbeddedRemoteTunnel.isActive()) {
                                                status.setText("服务器已撤销，但系统 VPN 未停止；"
                                                        + "请在系统 VPN 设置中手动关闭。");
                                            } else {
                                                AutoRemoteProvisioner.removeLocalTunnelConfig(this);
                                                pendingId = null;
                                                showState("本机已解绑，服务器已撤销访问权限。");
                                            }
                                        });
                                    }, false))
                    .setNegativeButton("取消", null).show();
        });

        Button back = action(content, "完成，返回立即观看", v -> finish());
        back.setContentDescription("返回主页，使用已授权的立即观看按钮");

        ScrollView root = new ScrollView(this);
        root.addView(content);
        setContentView(root);
        refreshButtons();
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private Button action(LinearLayout layout, String text,
                          View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(7);
        layout.addView(button, params);
        button.setOnClickListener(listener);
        return button;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(refresh);
        refresh.run();
        refreshButtons();
    }

    @Override protected void onPause() {
        resumed = false;
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void refreshButtons() {
        boolean registered = AutoRemoteProvisioner.isRegistered(this);
        boolean owner = "owner".equals(AutoRemoteProvisioner.registrationRole(this));
        if (showInvite != null) showInvite.setEnabled(registered && owner && !busy);
        if (approve != null) approve.setEnabled(registered && owner &&
                pendingId != null && !busy);
        if (decline != null) decline.setEnabled(registered && owner &&
                pendingId != null && !busy);
        if (revoke != null) revoke.setEnabled(registered && !busy);
    }

    private void showState(String text) {
        status.setText(text);
        refreshButtons();
    }

    private void message(String text) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        showState(text);
    }

    private void perform(String progress, Callable<JSONObject> work,
                         Consumer<JSONObject> success, boolean quiet) {
        if (busy || isFinishing()) return;
        busy = true;
        showState(progress);
        new Thread(() -> {
            JSONObject response = null;
            String error = null;
            try {
                response = work.call();
            } catch (Exception failure) {
                error = failure instanceof AutoRemoteProvisioner.Failure
                        ? failure.getMessage()
                        : "网络或服务器不可用，请检查 HTTPS、证书及互联网连接";
            }
            final JSONObject result = response;
            final String problem = error;
            ui.post(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                refreshButtons();
                if (problem != null) {
                    if (!quiet) message(problem);
                    else showState(problem);
                    return;
                }
                if (success != null && result != null) success.accept(result);
            });
        }, "screensync-enrollment").start();
    }

    private void updateStatus() {
        if (!AutoRemoteProvisioner.isRegistered(this)) {
            showState("尚未开通：主手机扫描管理员开通二维码；"
                    + "另一台手机扫描主手机生成的配对二维码。");
            return;
        }
        perform("正在检查服务器…", () -> AutoRemoteProvisioner.status(this),
                this::handleStatus, false);
    }

    private void handleStatus(JSONObject result) {
        String state = result.optString("state");
        if ("ready".equals(state)) {
            JSONObject pending = result;
            pendingId = pending.optString("pending_id", null);
            if (pendingId != null) {
                showState("等待主手机确认："
                        + pending.optString("pending_name", "另一台手机")
                        + "\n请核实对方身份，再点击「同意待配对手机」。");
            } else {
                showState("主手机已开通。点击生成配对二维码，让另一部手机扫码。");
            }
        } else if ("pending".equals(state)) {
            showState("已提交配对请求，等待另一部手机的持有人明确同意。");
        } else if ("expired".equals(state)) {
            showState("配对码已过期，尚未获得远程访问权限。"
                    + "点击「解绑并撤销异地访问」清除旧申请，再扫码新的二维码。");
        } else if ("paired".equals(state)) {
            try {
                AutoRemoteProvisioner.applyPairedConfig(this, result);
                pendingId = null;
                showState("双方已同意，专属网络配置已自动保存。\n"
                        + "返回主页点击「立即观看」时将申请 Android VPN 授权。\n"
                        + "请确保被观看手机已开启并授权无线调试及 ScreenSync 接收服务。\n"
                        + "注意：此状态不代表 VPN 已握手，也不代表能观看。");
                // User explicitly consented by activating or joining the
                // pairing. Receiver will show Android's required FGS status.
                if (!receiverRequested) {
                    receiverRequested = true;
                    try {
                        WakeLanService.startByOwner(this);
                    } catch (RuntimeException failure) {
                        message("已配对；启动接收服务失败，请在主页手动开启");
                    }
                }
            } catch (Exception error) {
                showState("已配对，但无法保存私有网络配置；请关闭 VPN 后重试。");
            }
        } else {
            showState("服务器状态未知：" + state);
        }
        refreshButtons();
    }

    private void showQr(String payload) throws Exception {
        BitMatrix bits = new MultiFormatWriter().encode(
                payload, BarcodeFormat.QR_CODE, 480, 480);
        Bitmap bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 480; y++) {
            for (int x = 0; x < 480; x++) {
                bitmap.setPixel(x, y, bits.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("另一台手机扫码配对")
                .setMessage("5分钟有效。扫码后仍需要主手机确认，"
                        + "请不要将二维码发给不认识的人。")
                .setView(image)
                .setPositiveButton("完成", (d, w) -> updateStatus())
                .show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
                                               Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SCAN_REQUEST || resultCode != RESULT_OK || data == null)
            return;
        String payload = data.getStringExtra(AppQrCaptureActivity.EXTRA_RESULT);
        String activation = AutoRemoteProvisioner.parseQr(payload, "activate");
        String invite = AutoRemoteProvisioner.parseQr(payload, "remote");
        if (activation == null && invite == null) {
            message("不是 ScreenSync 的有效异地开通或配对二维码");
            return;
        }
        if (AutoRemoteProvisioner.isRegistered(this)) {
            message("本机已开通。请先解绑，不能覆盖现有授权。");
            return;
        }
        if (activation != null) {
            new AlertDialog.Builder(this).setTitle("开通主手机")
                    .setMessage("确认使用管理员提供的一次性授权码开通此设备？"
                            + "开通不会自动共享屏幕，仍需双方确认。")
                    .setPositiveButton("确认开通", (d, w) ->
                            perform("正在安全注册…",
                                    () -> AutoRemoteProvisioner.activate(this, activation),
                                    result -> updateStatus(), false))
                    .setNegativeButton("取消", null).show();
        } else {
            new AlertDialog.Builder(this).setTitle("申请异地配对")
                    .setMessage("你是否同意与生成此二维码的设备建立加密网络配对？"
                            + "对方还需要单独确认，并且不能绕过系统 ADB 授权。")
                    .setPositiveButton("同意申请", (d, w) ->
                            perform("正在提交配对申请…",
                                    () -> AutoRemoteProvisioner.join(this, invite),
                                    result -> updateStatus(), false))
                    .setNegativeButton("取消", null).show();
        }
    }
}
