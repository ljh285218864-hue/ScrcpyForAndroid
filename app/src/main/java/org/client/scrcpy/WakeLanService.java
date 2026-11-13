package org.client.scrcpy;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * User-enabled LAN wake receiver for read-only mirroring TESTS.
 * No ADB, screen capture, remote input, pairing bypass, or public Intent API.
 * A must start this explicitly and may stop it at any time.
 * A private temporary 128-bit code authenticates B via challenge-response.
 */
public final class WakeLanService extends Service {
    private static final String TAG = "WakeLan";
    public static final int PORT = 10055;
    public static final String PREF = "wake_lan_test";
    public static final String KEY_CODE = "temporary_code";
    public static final String KEY_LAST = "last_accepted";
    public static final String ACTION_START = "org.client.scrcpy.lan.START";
    public static final String ACTION_STOP = "org.client.scrcpy.lan.STOP";
    private static final int FGS_ID = 8451;
    private static final int REQUEST_ID = 8452;
    private static final String CHANNEL = "screen_mirror_active_listener";
    private static final String REQ_CHANNEL = "screen_mirror_incoming_request";
    private volatile boolean running;
    private ServerSocket server;
    private Thread worker;

    public static void startByOwner(Context context) {
        Intent intent = new Intent(context, WakeLanService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopByOwner(Context context) {
        Intent intent = new Intent(context, WakeLanService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static String currentCode(Context context) {
        return context.getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_CODE, "");
    }

    public static long lastAcceptedAt(Context context) {
        return context.getSharedPreferences(PREF, MODE_PRIVATE).getLong(KEY_LAST, 0L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            closeListener();
            getSharedPreferences(PREF, MODE_PRIVATE).edit().remove(KEY_CODE).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || ACTION_START.equals(intent.getAction())) {
            prepareChannels();
            Notification notification = notification(
                    "屏幕同步接收已开启", "仅接受持有临时密钥的局域网测试请求", CHANNEL, true);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FGS_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(FGS_ID, notification);
            }
            if (!running) {
                String code = currentCode(this);
                if (!WakeAuth.isHex(code, 32)) {
                    code = WakeAuth.randomHex(16);
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putString(KEY_CODE, code).apply();
                }
                startListener(code);
            }
        }
        return START_STICKY;
    }

    private void startListener(String code) {
        running = true;
        worker = new Thread(() -> {
            try (ServerSocket socket = new ServerSocket(PORT)) {
                server = socket;
                socket.setSoTimeout(1200);
                Log.i(TAG, "Wake LAN receiver listening on TCP " + PORT);
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        Socket client = socket.accept();
                        handleClient(client, code);
                    } catch (SocketTimeoutException ignored) {
                        // Periodically observe the owner stop flag.
                    } catch (Exception error) {
                        if (running) Log.w(TAG, "Wake handshake failed");
                    }
                }
            } catch (Exception error) {
                Log.e(TAG, "Receiver could not bind TCP port " + PORT, error);
                running = false;
                getSharedPreferences(PREF, MODE_PRIVATE).edit().remove(KEY_CODE).apply();
                stopSelf();
            }
        }, "wake-lan-receiver");
        worker.start();
    }

    private void handleClient(Socket client, String code) {
        try (Socket socket = client) {
            socket.setSoTimeout(2400);
            // Only directly connected private LAN or loopback peers, never an
            // unauthenticated public-Internet endpoint.
            if (!(socket.getInetAddress().isSiteLocalAddress()
                    || socket.getInetAddress().isLoopbackAddress())) return;
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.US_ASCII));
            String hello = in.readLine();
            if (hello == null || hello.length() != 22
                    || !hello.startsWith("HELLO ")
                    || !WakeAuth.isHex(hello.substring(6), 16)) {
                out.write("DENIED\n");
                out.flush();
                return;
            }
            String challenge = WakeAuth.randomHex(16);
            out.write("CHALLENGE " + challenge + "\n");
            out.flush();
            String response = in.readLine();
            String nonce = hello.substring(6);
            String expected = WakeAuth.mac(code, nonce, challenge);
            if (response == null || response.length() != 69
                    || !response.startsWith("AUTH ")
                    || !WakeAuth.verify(response.substring(5), expected)) {
                out.write("DENIED\n");
                out.flush();
                return;
            }
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putLong(KEY_LAST, System.currentTimeMillis()).apply();
            out.write("OK " + nonce + "\n");
            out.flush();
            Log.i(TAG, "Authorized LAN wake request accepted; screen sharing not triggered");
            postIncomingNotification();
        } catch (Exception error) {
            Log.w(TAG, "Wake request rejected or interrupted");
        }
    }

    private void prepareChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "屏幕同步接收状态", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(
                REQ_CHANNEL, "屏幕观看请求", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private Notification notification(String title, String message, String channel, boolean ongoing) {
        Intent show = new Intent(this, MainActivity.class);
        show.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 40, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title).setContentText(message)
                .setContentIntent(tap).setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_STATUS).build();
    }

    private void postIncomingNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(REQUEST_ID, notification(
                "收到屏幕观看请求", "一台持有测试密钥的设备请求观看；点击查看共享状态",
                REQ_CHANNEL, false));
    }

    private void closeListener() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
            server = null;
        }
        if (worker != null) worker.interrupt();
    }

    @Override public void onDestroy() {
        closeListener();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
