package org.client.scrcpy;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Dedicated immersive viewing layer inside the same Activity.
 * Closing this layer exits only the active viewing session through its
 * registered owner callback. It never revokes the saved consent, disconnects
 * the authenticated WSS control channel, or unpairs devices.
 */
public final class FullscreenViewer extends FrameLayout {
    private final Activity activity;
    private final ImageView picture;
    private final TextView waitMessage;
    private final Button close;
    private boolean visible;
    private Runnable onClosed;
    private int previousUiFlags;

    public FullscreenViewer(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setVisibility(GONE);

        picture = new ImageView(activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setBackgroundColor(Color.BLACK);
        picture.setContentDescription("对方共享的全屏画面");
        addView(picture, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        waitMessage = new TextView(activity);
        waitMessage.setText("等待配对手机响应观看请求…");
        waitMessage.setTextColor(Color.WHITE);
        waitMessage.setTextSize(15);
        waitMessage.setGravity(Gravity.CENTER);
        waitMessage.setPadding(dp(18), dp(12), dp(18), dp(12));
        waitMessage.setBackgroundColor(Color.argb(165, 0, 0, 0));
        LayoutParams waitLayout = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        waitLayout.bottomMargin = dp(56);
        addView(waitMessage, waitLayout);

        close = new Button(activity);
        close.setText("×");
        close.setTextSize(27);
        close.setAllCaps(false);
        close.setTextColor(Color.WHITE);
        close.setContentDescription("退出本次观看，保留配对及长期授权");
        close.setMinWidth(dp(52));
        close.setMinHeight(dp(52));
        close.setPadding(0, 0, 0, 0);
        close.setBackground(pillBackground());
        LayoutParams closeParams = new LayoutParams(dp(52), dp(52),
                Gravity.TOP | Gravity.RIGHT);
        closeParams.topMargin = dp(22);
        closeParams.rightMargin = dp(18);
        addView(close, closeParams);
        close.setOnClickListener(v -> closePicture());

        Button sizing = new Button(activity);
        sizing.setText("完整 / 填充");
        sizing.setTextSize(12);
        sizing.setAllCaps(false);
        sizing.setTextColor(Color.WHITE);
        sizing.setBackground(pillBackground());
        LayoutParams sizingParams = new LayoutParams(dp(110), dp(44),
                Gravity.RIGHT | Gravity.BOTTOM);
        sizingParams.rightMargin = dp(16);
        sizingParams.bottomMargin = dp(10);
        addView(sizing, sizingParams);
        sizing.setOnClickListener(v -> picture.setScaleType(
                picture.getScaleType() == ImageView.ScaleType.CENTER_CROP
                        ? ImageView.ScaleType.FIT_CENTER
                        : ImageView.ScaleType.CENTER_CROP));
        close.bringToFront();
    }

    public void setOnClosedListener(Runnable callback) { onClosed = callback; }
    public ImageView image() { return picture; }
    public boolean isShowing() { return visible; }

    public void openPicture(boolean hasFrame) {
        if (visible) return;
        visible = true;
        previousUiFlags = activity.getWindow().getDecorView().getSystemUiVisibility();
        setVisibility(VISIBLE);
        bringToFront();
        if (hasFrame) hideWaiting();
        else showWaiting("正在连接配对手机，等待共享画面…");
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        ScreenSyncDiagnostics.record(activity, "VIEW_UI", "fullscreen_open");
    }

    public void showWaiting(String message) {
        if (!visible) return;
        waitMessage.setText(message);
        waitMessage.setVisibility(VISIBLE);
    }

    public void hideWaiting() {
        waitMessage.setVisibility(GONE);
    }

    public void closePicture() {
        if (!visible) return;
        visible = false;
        setVisibility(GONE);
        activity.getWindow().getDecorView().setSystemUiVisibility(previousUiFlags);
        if (onClosed != null) onClosed.run();
        ScreenSyncDiagnostics.record(activity, "VIEW_UI",
                "viewer_hidden_pair_and_socket_unchanged");
    }

    private GradientDrawable pillBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(175, 25, 32, 43));
        d.setCornerRadius(dp(30));
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
