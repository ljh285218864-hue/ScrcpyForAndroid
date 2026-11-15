package org.client.scrcpy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.Collections;
import java.util.List;

/**
 * Self-contained, explicit in-app QR camera, with no external scanner intent,
 * web URLs, Baidu/browser chooser, or OS camera-app integrations.
 *
 * This is for the application trust pairing QR only. System ADB pairing QR
 * MUST be read in Android Settings > Wireless debugging.
 */
public final class AppQrCaptureActivity extends Activity {
    public static final String EXTRA_RESULT = "org.client.scrcpy.qr_result";
    private BarcodeView camera;
    private boolean finished;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.rgb(29, 34, 40));

            TextView guide = new TextView(this);
            guide.setText("扫描另一台手机 ScreenSync 的软件配对二维码");
            guide.setTextSize(17);
            guide.setGravity(Gravity.CENTER);
            guide.setTextColor(Color.WHITE);
            guide.setPadding(20, 38, 20, 30);
            root.addView(guide, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            camera = new BarcodeView(this);
            camera.setDecoderFactory(new DefaultDecoderFactory(
                    Collections.singletonList(BarcodeFormat.QR_CODE)));
            root.addView(camera, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

            Button cancel = new Button(this);
            cancel.setText("取消扫码");
            cancel.setOnClickListener(v -> finish());
            root.addView(cancel, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            setContentView(root);

            camera.decodeSingle(new BarcodeCallback() {
                @Override public void barcodeResult(BarcodeResult result) {
                    if (finished || result == null || result.getText() == null) return;
                    finished = true;
                    Intent output = new Intent();
                    output.putExtra(EXTRA_RESULT, result.getText());
                    setResult(RESULT_OK, output);
                    finish();
                }

                @Override public void possibleResultPoints(List<ResultPoint> resultPoints) { }
            });
        } catch (RuntimeException error) {
            Log.e("Scrcpy", "Could not create local QR camera", error);
            Toast.makeText(this, "扫码相机无法启动，请改用搜索附近设备配对",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (camera != null) {
            try {
                camera.resume();
            } catch (RuntimeException error) {
                Log.e("Scrcpy", "Local QR camera resume failed", error);
                Toast.makeText(this, "扫码相机打开失败，请检查相机权限",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override protected void onPause() {
        if (camera != null) camera.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (camera != null) camera.pause();
        super.onDestroy();
    }
}
