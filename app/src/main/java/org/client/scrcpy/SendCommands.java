package org.client.scrcpy;


import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class SendCommands {

    public final static int WAIT_TIME = 15000;

    public enum CmdStatus {
        SUCCESS,
        RUNNING,
        ERROR
    }

    public SendCommands() {

    }

    public CmdStatus SendAdbCommands(Context context, final String ip, int port, int forwardport, String localip, int bitrate, int size, boolean enableAudio) {
        Options options = new Options();
        options.setIp(localip);
        options.setMaxSize(size);
        options.setBitRate(bitrate);
        options.setTunnelForward(true);
        options.setEnableAudioForward(enableAudio);
        return SendAdbCommands(context, ip, port, forwardport, options);
    }

    public CmdStatus SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip, int bitrate, int size, boolean enableAudio) {
        return SendAdbCommands(context, ip, port, forwardport, localip, bitrate, size, enableAudio);
    }

    public CmdStatus SendAdbCommands(Context context, final String ip,
                                     int port, int forwardport, Options options) {
        return SendAdbCommands(context, ip, port, forwardport, options, () -> true);
    }

    /** Used by the approved local video sender; stops setup if user revokes. */
    public CmdStatus SendAdbCommands(Context context, final String ip,
                                     int port, int forwardport, Options options,
                                     BooleanSupplier allowed) {
        AtomicReference<CmdStatus> status = new AtomicReference<>(CmdStatus.RUNNING);

        List<String> cmdList = new ArrayList<>();
        Collections.addAll(cmdList,
                "-s", ip + ":" + port,
                "shell",
                " CLASSPATH=/data/local/tmp/scrcpy-server.jar",
                "app_process",
                "/",
                "org.server.scrcpy.Server"
        );
        Collections.addAll(cmdList, options.optionsToArgs());
        cmdList.add(";");
        String[] commands = cmdList.toArray(new String[0]);

        ThreadUtils.execute(() -> {
            try {
                if (!allowed.getAsBoolean()) {
                    status.set(CmdStatus.ERROR);
                    return;
                }
                boolean serverIsRunning = AdbHelper.checkAdbServer();
                Log.i("Scrcpy", "serverIsRunning: " + serverIsRunning);
                if (!serverIsRunning || !AdbHelper.isRunning()) {
                    AdbHelper.restartAdb();
                    AdbHelper.waitForRunning(5);
                }
                CmdStatus curStatus = startPortForward(
                        context, ip, port, forwardport, allowed);
                if (curStatus == CmdStatus.SUCCESS && allowed.getAsBoolean()) {
                    status.set(curStatus);
                    newAdbServerStart(commands);
                } else {
                    status.set(CmdStatus.ERROR);
                }
            } catch (Exception e) {
                status.set(CmdStatus.ERROR);
                e.printStackTrace();
            }
        });
        int count = 0;
        while (status.get() == CmdStatus.RUNNING && count < (WAIT_TIME / 100)
                && allowed.getAsBoolean()) {
            Log.e("ADB", "Connecting...");
            try {
                Thread.sleep(100);
                count++;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (count >= (WAIT_TIME / 100) || !allowed.getAsBoolean()) {
            status.set(CmdStatus.ERROR);
            return status.get();
        }
        if (status.get() == CmdStatus.SUCCESS) {
            count = 0;
            //  检测程序是否已经启动，如果启动了，该文件会被删除
            while (status.get() == CmdStatus.SUCCESS && count < 40
                    && allowed.getAsBoolean()) {
                String adbTextCmd = AdbHelper.adbCmd(App.mContext,
                        "-s", ip + ":" + port, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
                if (TextUtils.isEmpty(adbTextCmd)) {
                    break;
                } else {
                    try {
                        Thread.sleep(100);
                        count++;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return allowed.getAsBoolean() ? status.get() : CmdStatus.ERROR;
    }

    private CmdStatus startPortForward(Context context, String ip, int port,
                                       int serverport, BooleanSupplier allowed) {
        if (!allowed.getAsBoolean()) return CmdStatus.ERROR;
        Log.i("Scrcpy", "try connect to ip: " + ip);
        AdbHelper.adbCmd(App.mContext, "connect", ip + ":" + port);
        // 复制server端到可执行目录（内部存储，避免外部存储不可用时连接失败）
        String pushRet = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "push",
                AdbHelper.getServerJarFile(context).getAbsolutePath(), "/data/local/tmp/scrcpy-server.jar");

        Log.i("Scrcpy", "pushRet: " + pushRet);

        String adbTextCmd = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
        if (TextUtils.isEmpty(adbTextCmd)) {
            return CmdStatus.ERROR;
        }
        if (!allowed.getAsBoolean()) return CmdStatus.ERROR;
        // The requested local forward is for a specifically approved
        // session. The target 7007 is loopback-only inside this phone.
        Log.i("Scrcpy", "开启本地端口转发");
        AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port,
                "forward", "tcp:" + serverport, "tcp:7007");
        return allowed.getAsBoolean() ? CmdStatus.SUCCESS : CmdStatus.ERROR;
    }

    private void newAdbServerStart(String[] command) {
        // 执行启动命令
        AdbHelper.adbCmd(App.mContext, command);
    }

}
