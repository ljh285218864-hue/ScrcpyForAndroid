package org.client.scrcpy;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Local-only bounded diagnostic log. No tokens, QR links, peer IDs, image bytes,
 * screen content, passwords, or location data. It is never uploaded automatically.
 */
public final class ScreenSyncDiagnostics {
    private static final String FILE_NAME = "screensync-diagnostics.log";
    private static final int MAX_BYTES = 32 * 1024;
    private static final int KEEP_BYTES = 20 * 1024;
    private static final Object LOCK = new Object();

    private ScreenSyncDiagnostics() { }

    public static void record(Context context, String stage, String safeDetail) {
        if (context == null) return;
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date()) + "  " + clean(stage) + "  " + clean(safeDetail) + "\n";
        Log.i("ScreenSyncTrace", line.trim());
        synchronized (LOCK) {
            File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
            try {
                byte[] old = file.exists() ? Files.readAllBytes(file.toPath()) : new byte[0];
                byte[] entry = line.getBytes(StandardCharsets.UTF_8);
                if (old.length + entry.length > MAX_BYTES) {
                    String history = new String(old, StandardCharsets.UTF_8);
                    int start = Math.max(0, history.length() - KEEP_BYTES);
                    int boundary = history.indexOf('\n', start);
                    String tail = boundary < 0 ? "" : history.substring(boundary + 1);
                    old = tail.getBytes(StandardCharsets.UTF_8);
                    try (FileOutputStream output = new FileOutputStream(file, false)) {
                        output.write(old);
                    }
                }
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write(entry);
                }
            } catch (Exception ignored) {
                // Diagnostics must never interrupt a live consented share.
            }
        }
    }

    public static String read(Context context) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
                if (!file.isFile()) return "暂无诊断记录。\n请在两台手机打开软件并尝试观看，再查看日志。";
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "读取诊断日志失败。";
            }
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
            if (file.exists()) file.delete();
        }
    }

    public static void environment(Context context) {
        record(context, "APP", "version=" + BuildConfig.VERSION_NAME
                + " android=" + Build.VERSION.SDK_INT
                + " brand=" + clean(Build.MANUFACTURER)
                + " model=" + clean(Build.MODEL));
    }

    private static String clean(String value) {
        if (value == null) return "";
        // Only a strict printable subset; no URL, bearer token, raw exception,
        // arbitrary message body, QR or full session/device identifiers.
        String cleaned = value.replaceAll("[^a-zA-Z0-9 _=.:,\u4e00-\u9fa5-]", "?");
        return cleaned.length() > 150 ? cleaned.substring(0, 150) : cleaned;
    }
}
