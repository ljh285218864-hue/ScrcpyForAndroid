package org.client.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.client.scrcpy.usb.AdbConnection;
import org.client.scrcpy.usb.UsbAdb;
import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.PreUtils;
import org.client.scrcpy.utils.Progress;
import org.client.scrcpy.utils.ResolutionHelper;
import org.client.scrcpy.utils.SessionLog;
import org.client.scrcpy.utils.ThreadUtils;
import org.client.scrcpy.utils.Util;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class MainActivity extends Activity implements Scrcpy.ServiceCallbacks, SensorEventListener {

    // 是否直接连接远程
    public final static String START_REMOTE = "start_remote_headless";

    private boolean headlessMode = false;  // 是否为无头模式，不显示操作选项等
    private int screenWidth;
    private int screenHeight;
    private boolean landscape = false;
    private boolean first_time = true;
    private boolean result_of_Rotation = false;
    private boolean serviceBound = false;
    // 如果 pause 切换到后台，断开后，自动重连
    // 该状态禁止保存恢复
    private boolean resumeScrcpy = false;
    SensorManager sensorManager;
    private SendCommands sendCommands;
    private int videoBitrate;
    private int delayControl;
    private String videoCodec = Options.CODEC_H264;
    private String videoEncoder = "";
    private int videoFrameRate = 60;
    private Context context;
    private String serverAdr = null;
    private SurfaceView surfaceView;
    private Surface surface;
    private SurfaceHolder.Callback surfaceCallback;
    private Scrcpy scrcpy;
    private long timestamp = 0;
    private OnBackInvokedCallback backInvokedCallback;
    private LanDeviceDiscovery lanDiscovery;
    private OneTapConnection oneTapConnection;
    private SystemAdbQrPairingSession systemQrSession;
    private boolean oneTapConnecting;
    private boolean skipWakeRequestOnce;
    private List<LanDeviceDiscovery.Device> lanDevices = new ArrayList<>();
    private final Handler lanSearchHandler = new Handler(Looper.getMainLooper());
    private final Handler pairingHandler = new Handler(Looper.getMainLooper());
    private String lastPromptedPairId = "";
    private final Runnable pairingApprovalPoll = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (first_time) {
                WakeLanService.PendingPair pending = WakeLanService.getPendingPair();
                if (pending != null && !pending.id.equals(lastPromptedPairId)) {
                    lastPromptedPairId = pending.id;
                    showIncomingPairConsent(pending);
                }
                if (findViewById(R.id.paired_device_status) != null) updatePairedDeviceUi();
            }
            pairingHandler.postDelayed(this, 1200);
        }
    };

    private LinearLayout linearLayout;

// USB ADB support
    private static final String ACTION_USB_PERMISSION = "org.client.scrcpy.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbAdb usbAdb;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                startUsbConnect(device);
            } else {
                Progress.closeDialog();
                Toast.makeText(MainActivity.this, R.string.usb_permission_denied,
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    private boolean autoResolutionEnabled = false;
    private ResolutionHelper.LimitSource resolutionLimitSource = null;

    private volatile boolean statusMonitorActive = false;
    private volatile int statusCheckGeneration = 0;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(MainActivity.this);
            serviceBound = true;
            if (first_time) {
                if (!Progress.isShowing()) {
                    Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
                }
                scrcpy.start(surface, Scrcpy.LOCAL_IP + ":" + Scrcpy.LOCAL_FORWART_PORT,
                        screenHeight, screenWidth, delayControl,
                        PreUtils.get(MainActivity.this, Constant.AUDIO_FORWARD, true),
                        videoCodec);
                ThreadUtils.workPost(() -> {
                    boolean success = AdbHelper.executeWithTimeout(() -> {
                        while (!scrcpy.check_socket_connection()) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }, SendCommands.WAIT_TIME, TimeUnit.MILLISECONDS);
                    ThreadUtils.post(() -> {
                        Progress.closeDialog();
                        if (!success) {
                            if (serviceBound) {
                                showMainView();
                            }
                            Toast.makeText(context, "Connection Timed out 2", Toast.LENGTH_SHORT).show();
                        } else {
                            first_time = false;
                            applyClientOrientationFromRemote();
                            refreshMirrorLayout();
                            connectSuccessExt();
                        }
                    });
                });
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                refreshMirrorLayout();
                connectSuccessExt();
            }
            // set_display_nd_touch();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
        }
    };

    private void showMainView() {
        showMainView(false);
    }

    // userDisconnect ：是否为用户手动断开连接
    private void showMainView(boolean userDisconnect) {
        // 先停止 Scrcpy 服务（停止视频流），再关闭 usbAdb。
        // 若先关 USB 连接，readerThread 可能正阻塞在 bulkTransfer 上，
        // 强制中断传输会导致设备被重新枚举（deviceId 变化），授权丢失，
        // 下次启动连接时就会重新弹 USB 授权请求。
        if (scrcpy != null) {
            scrcpy.StopService();
        }
        if (usbAdb != null) {
            usbAdb.close();
            usbAdb = null;
        }
        try {
            // 可能会导致重复解绑，所以捕获异常
            unbindService(serviceConnection);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (surfaceView != null && surfaceCallback != null) {
            surfaceView.getHolder().removeCallback(surfaceCallback);
            surfaceView = null;
        }
        surfaceCallback = null;
        if (surface != null) {
            surface = null;
        }
        serviceBound = false;
        scrcpy_main();

        if (scrcpy != null) {
            scrcpy = null;
        }
        // 退出连接，需要处理额外的事件
        connectExitExt(userDisconnect);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.context = this;
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setTitle(getString(R.string.app_name));
        if (savedInstanceState != null) {
            first_time = savedInstanceState.getBoolean("first_time");
            landscape = savedInstanceState.getBoolean("landscape");
            headlessMode = savedInstanceState.getBoolean("headlessMode");
            resumeScrcpy = savedInstanceState.getBoolean("resumeScrcpy");
            screenHeight = savedInstanceState.getInt("screenHeight");
            screenWidth = savedInstanceState.getInt("screenWidth");
        }
        if (first_time) {
            scrcpy_main();
        } else {
            landscape = getResources().getConfiguration().orientation
                    != Configuration.ORIENTATION_PORTRAIT;
            Log.e("Scrcpy: ", "from onCreate");
            start_screen_copy_magic();
        }
        sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        Sensor proximity;
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);

        if (savedInstanceState != null) {
            Log.i("Scrcpy", "outState: " + savedInstanceState.getBoolean("from_save_instance"));
        }
        // Never start a hidden/headless viewing session from an intent.
        // The viewer is visible and must be started by the user.
        headlessMode = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = this::handleBackPressed;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.i("Scrcpy", "enter onSaveInstanceState");
        outState.putBoolean("from_save_instance", true);
        outState.putBoolean("first_time", first_time);
        outState.putBoolean("landscape", landscape);
        outState.putBoolean("headlessMode", headlessMode);  // 第二次进入时，intent会被重置，需要保存状态
        // 小窗模式、半屏模式切换避免恢复横竖屏，会导致黑屏（因为scrcpy恢复的resume只允许一次连接）
        // outState.putBoolean("resumeScrcpy", resumeScrcpy);
        outState.putInt("screenHeight", screenHeight);
        outState.putInt("screenWidth", screenWidth);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    public void scrcpy_main() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.getWindow().setStatusBarColor(getColor(R.color.status_bar));
        } else {
            this.getWindow().setStatusBarColor(getResources().getColor(R.color.status_bar));
        }
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        landscape = false;  // 将模式重新置为 竖屏，模式不正确将导致连接黑屏
        setContentView(R.layout.activity_main);
        Util.applyStatusBarInset(findViewById(R.id.app_header), 80,
                findViewById(R.id.main_scroll_view));


        // find view by id
        ScrollView scrollView = findViewById(R.id.main_scroll_view);
        Button startButton = findViewById(R.id.button_start);
        TextView btnMoreSettings = findViewById(R.id.btn_more_settings);
        LinearLayout layoutMoreSettings = findViewById(R.id.layout_more_settings);

        sendCommands = new SendCommands();

        startButton.setOnClickListener(v -> {
            final EditText wakeHostField = findViewById(R.id.editText_wake_host);
            final EditText wakeKeyField = findViewById(R.id.editText_wake_key);
            final String wakeHostText = wakeHostField == null ? "" : wakeHostField.getText().toString().trim();
            final String wakeKeyText = wakeKeyField == null ? "" : wakeKeyField.getText().toString().trim();
            if (TextUtils.isEmpty(wakeHostText) != TextUtils.isEmpty(wakeKeyText)) {
                Toast.makeText(this, R.string.wake_lan_incomplete, Toast.LENGTH_LONG).show();
                return;
            }
            getSharedPreferences(WakeLanService.PREF, MODE_PRIVATE).edit()
                    .putString("viewer_host", wakeHostText)
                    .putString("viewer_code", wakeKeyText).apply();
            stopStatusMonitor();
            // 启动连接前先释放上一次的 USB/scrcpy 会话（停止服务、关闭 usbAdb、
            // 释放 7008 与 USB 设备），避免横屏/反复连接后授权丢失或 openDevice 失败
            cleanupUsbSessionForShell();
            getAttributes();
            SessionLog.i("Start clicked host=" + serverAdr
                    + " max=" + Math.max(screenHeight, screenWidth)
                    + " bitrate=" + videoBitrate
                    + " codec=" + videoCodec
                    + " encoder=" + videoEncoder
                    + " fps=" + videoFrameRate);
            if (UsbShellCompat.isUsbSelection(serverAdr)) {
                connectViaUsb();
            } else {
                connectScrcpyServer(serverAdr);
            }
        });
        // No reboot/power-off controls in this read-only application.
        Button shareLogButton = findViewById(R.id.button_share_log);
        if (shareLogButton != null) {
            shareLogButton.setOnClickListener(v -> shareSessionLog());
        }
        // No ADB terminal or shell is reachable from the viewer UI.
        // Tests the internal wake receiver without starting ADB or sharing a screen.
        Button wakeTestButton = findViewById(R.id.button_test_wake);
        if (wakeTestButton != null) {
            wakeTestButton.setOnClickListener(v -> testWakeReceiverLocally());
        }
        btnMoreSettings.setOnClickListener(v -> {
            AutoTransition autoTransition = new AutoTransition();
            TransitionManager.beginDelayedTransition(scrollView, autoTransition);

            if (layoutMoreSettings.getVisibility() == View.GONE) {
                layoutMoreSettings.setVisibility(View.VISIBLE);
                btnMoreSettings.setText(R.string.collapse_settings);
            } else {
                layoutMoreSettings.setVisibility(View.GONE);
                btnMoreSettings.setText(R.string.more_settings);
            }
        });

        get_saved_preferences();
        PairedDeviceStore.Peer savedPeer = PairedDeviceStore.load(this);
        if (savedPeer != null) syncPeerToViewFields(savedPeer);
        initLanWakeControls();
        initLanAutoDiscovery();
        initPairingHome();
        initQuickHome();
        Button openDebugSettings = findViewById(R.id.button_open_wireless_debugging);
        final boolean isXiaomiFamily = XiaomiSettingsSupport.isXiaomiFamily(
                Build.MANUFACTURER, Build.BRAND);
        final boolean isVivoFamily = VivoSettingsSupport.isVivoFamily(
                Build.MANUFACTURER, Build.BRAND);
        if (openDebugSettings != null) {
            if (isXiaomiFamily) {
                openDebugSettings.setText(R.string.action_open_xiaomi_developer_options);
            } else if (isVivoFamily) {
                openDebugSettings.setText(R.string.action_open_vivo_developer_options);
            }
            openDebugSettings.setOnClickListener(v -> openWirelessDebugSettings());
        }
        Button xiaomiGuide = findViewById(R.id.button_xiaomi_debug_help);
        if (xiaomiGuide != null) {
            xiaomiGuide.setVisibility(isXiaomiFamily ? View.VISIBLE : View.GONE);
            xiaomiGuide.setOnClickListener(v -> showXiaomiDeveloperGuide());
        }
        Button vivoGuide = findViewById(R.id.button_vivo_debug_help);
        if (vivoGuide != null) {
            vivoGuide.setVisibility(isVivoFamily ? View.VISIBLE : View.GONE);
            vivoGuide.setOnClickListener(v -> showVivoDeveloperGuide());
        }
        Button vivoBackground = findViewById(R.id.button_vivo_background_settings);
        if (vivoBackground != null) {
            vivoBackground.setVisibility(isVivoFamily ? View.VISIBLE : View.GONE);
            vivoBackground.setOnClickListener(v -> showVivoBackgroundGuide());
        }

        EditText editText = findViewById(R.id.editText_server_host);

        findViewById(R.id.history_list).setOnClickListener(v -> {
            Log.i("Scrcpy", "focus true");
            editText.clearFocus();
            showListPopulWindow(editText);
        });

        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                updateResolutionPreview();
            }
        });
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (statusMonitorActive) {
                    scheduleStatusCheck(800);
                }
            }
        });

        startStatusMonitor();

        // 无头模式，实际上要隐藏掉所有控件，否则会被显示出 ip 地址
        if (headlessMode) {
            if (scrollView != null) {
                scrollView.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void showListPopulWindow(EditText mEditText) {
        String[] history = getHistoryList();//要填充的数据
        if (history.length == 0) {  // 如果list为空，则使用本机填充一个
            history = new String[]{"127.0.0.1"};
        }
        // 第一项固定为 USB 连接入口，其余为历史 IP 记录
        // 若检测到 USB 设备，显示其在 adb devices 中的名称
        String usbLabel = getUsbAdbDeviceLabel();
        final List<String> items = new ArrayList<>();
        items.add(usbLabel != null ? usbLabel : getString(R.string.action_start_usb));
        items.addAll(Arrays.asList(history));

        final ListPopupWindow listPopupWindow;
        listPopupWindow = new ListPopupWindow(this);
        // 自定义列表项：设备名 + 右侧删除按钮
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_history_device, R.id.text_device, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                View deleteButton = view.findViewById(R.id.button_delete);
                if (position == 0) {
                    // USB 连接入口不允许删除
                    deleteButton.setVisibility(View.GONE);
                } else {
                    deleteButton.setVisibility(View.VISIBLE);
                    final String device = getItem(position);
                    deleteButton.setOnClickListener(v -> {
                        removeHistory(device);
                        remove(device);
                        notifyDataSetChanged();
                    });
                }
                return view;
            }
        };
        listPopupWindow.setAdapter(adapter);
        listPopupWindow.setAnchorView(mEditText);//以哪个控件为基准，在该处以mEditText为基准
        listPopupWindow.setModal(true);
        listPopupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        listPopupWindow.setOnItemClickListener(new AdapterView.OnItemClickListener() {//设置项点击监听
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                listPopupWindow.dismiss();
                mEditText.setText(items.get(i));
                if (i == 0) {  // 选择了 USB 连接入口，仅填入设备，由用户点击启动/ADB命令按钮
                    // 触发一次状态刷新，检测 USB 设备在线状态
                    if (statusMonitorActive) {
                        scheduleStatusCheck(0);
                    } else {
                        startStatusMonitor();
                    }
                }
            }
        });
        listPopupWindow.show();
    }

    /**
     * 从历史连接记录中删除指定设备
     */
    private void removeHistory(String device) {
        String[] historyList = getHistoryList();
        JSONArray historyJson = new JSONArray();
        for (String item : historyList) {
            if (!item.equals(device)) {
                historyJson.put(item);
            }
        }
        PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
    }

    private void shareSessionLog() {
        java.io.File logFile = SessionLog.getFile();
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            Toast.makeText(this, R.string.share_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String subject = getString(R.string.share_log_subject, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        Uri uri = Uri.parse("content://" + getPackageName() + ".log/session.log");
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        // WhatsApp: solo riassunto ~400 caratteri. Il log intero è l'allegato.
        send.putExtra(Intent.EXTRA_TEXT, SessionLog.readPreview());
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("scrcpy-session.log", uri));
        try {
            startActivity(Intent.createChooser(send, getString(R.string.action_share_log)));
        } catch (Exception e) {
            SessionLog.e("Share log failed", e);
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }


//    private void showDisplayWindow() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            if (!Settings.canDrawOverlays(this)) {
//                //启动Activity让用户授权
//                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
//                startActivity(intent);
//                return;
//            }
//        }
//        Intent it = new Intent(this, FloatService.class);
//        it.putExtra("ip", serverAdr);
//        it.putExtra("w", screenWidth);
//        it.putExtra("h", screenHeight);
//        it.putExtra("b", videoBitrate);
//        startService(it);
//        finish();
//    }


    public void get_saved_preferences() {
        EditText editTextServerHost = findViewById(R.id.editText_server_host);
        Switch aSwitch0 = findViewById(R.id.switch0);
        Switch aSwitch1 = findViewById(R.id.switch1);
        Switch audioForwardSwitch = findViewById(R.id.switch_audio_enable);
        String historySpServerAdr = PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, "");
        if (TextUtils.isEmpty(historySpServerAdr)) {
            String[] historyList = getHistoryList();
            if (historyList.length > 0) {
                editTextServerHost.setText(historyList[0]);
            }
        } else {
            editTextServerHost.setText(historySpServerAdr);
        }
        // 若检测到 USB 设备，则默认预选 USB 连接（显示 adb devices 中的名称）
        String usbLabel = getUsbAdbDeviceLabel();
        if (usbLabel != null) {
            editTextServerHost.setText(usbLabel);
        }
        // Read-only fork: enforce a locked no-control preference.
        PreUtils.put(context, Constant.CONTROL_NO, true);
        PreUtils.put(context, Constant.CONTROL_NAV, false);
        aSwitch0.setChecked(true);
        aSwitch0.setEnabled(false);
        aSwitch1.setChecked(false);
        aSwitch1.setEnabled(false);
        audioForwardSwitch.setChecked(PreUtils.get(context, Constant.AUDIO_FORWARD, true));

        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, Constant.PREFERENCE_SPINNER_BITRATE);
        setSpinner(R.array.options_codec_keys, R.id.spinner_video_codec, Constant.PREFERENCE_SPINNER_CODEC);
        setSpinner(R.array.options_fps_keys, R.id.spinner_video_fps, Constant.PREFERENCE_SPINNER_FPS);
        setSpinner(R.array.options_delay_keys, R.id.delay_control_spinner, Constant.PREFERENCE_SPINNER_DELAY);
        setupResolutionSpinner();

        EditText encoderEdit = findViewById(R.id.editText_video_encoder);
        encoderEdit.setText(PreUtils.get(context, Constant.PREFERENCE_VIDEO_ENCODER, ""));

        if (aSwitch0.isChecked()) {
            aSwitch1.setClickable(false);
            aSwitch1.setTextColor(Color.GRAY);
            // aSwitch1.setVisibility(View.GONE);
        }

        aSwitch0.setOnClickListener(v -> {
            if (aSwitch0.isChecked()) {
                aSwitch1.setClickable(false);
                aSwitch1.setTextColor(Color.GRAY);
                // aSwitch1.setVisibility(View.GONE);
            } else {
                aSwitch1.setClickable(true);
                aSwitch1.setTextColor(Color.WHITE);
                // aSwitch1.setVisibility(View.VISIBLE);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    public void set_display_nd_touch() {
        if (linearLayout == null || surfaceView == null || scrcpy == null) {
            return;
        }
        // Mirroring only: consume taps locally, never forward any input.
        surfaceView.setOnTouchListener((view, event) -> true);
        float width = linearLayout.getWidth();
        float height = linearLayout.getHeight();
        int[] remote = scrcpy.getOrientedRemoteSize(landscape);
        int remoteW = remote[0];
        int remoteH = remote[1];
        if (remoteW <= 0 || remoteH <= 0 || width <= 0 || height <= 0) {
            return;
        }
        float scale = Math.min(width / remoteW, height / remoteH);
        int padX = Math.max(0, ((int) width - Math.round(remoteW * scale)) / 2);
        int padY = Math.max(0, ((int) height - Math.round(remoteH * scale)) / 2);
        linearLayout.setPadding(padX, padY, padX, padY);
    }

    private void setSpinner(final int textArrayOptionResId, final int textViewResId, final String preferenceId) {

        final Spinner spinner = findViewById(textViewResId);
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(this, textArrayOptionResId, android.R.layout.simple_spinner_item);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                PreUtils.put(context, preferenceId, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                PreUtils.put(context, preferenceId, 0);
            }
        });
        int selection = PreUtils.get(context, preferenceId, 0);
        if (selection < arrayAdapter.getCount()) {
            spinner.setSelection(selection);
        } else {
            spinner.setSelection(0);
        }
    }

    private void setupResolutionSpinner() {
        final Spinner spinner = findViewById(R.id.spinner_video_resolution);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                PreUtils.put(context, Constant.PREFERENCE_SPINNER_RESOLUTION, position);
                updateResolutionPreview();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                PreUtils.put(context, Constant.PREFERENCE_SPINNER_RESOLUTION, 0);
            }
        });
        int selection = PreUtils.get(context, Constant.PREFERENCE_SPINNER_RESOLUTION, 0);
        if (selection < spinner.getCount()) {
            spinner.setSelection(selection);
        } else {
            spinner.setSelection(0);
        }
        updateResolutionPreview();
    }

    private void updateResolutionPreview() {
        TextView info = findViewById(R.id.text_resolution_info);
        Spinner spinner = findViewById(R.id.spinner_video_resolution);
        if (info == null || spinner == null) {
            return;
        }
        if (spinner.getSelectedItemPosition() != Constant.RESOLUTION_AUTO_INDEX) {
            info.setVisibility(View.GONE);
            return;
        }
        EditText editText = findViewById(R.id.editText_server_host);
        String remoteAdr = editText != null ? editText.getText().toString().trim() : "";
        if (TextUtils.isEmpty(remoteAdr)) {
            info.setText(R.string.resolution_auto_need_ip);
            info.setVisibility(View.VISIBLE);
            return;
        }
        info.setText(R.string.resolution_auto_detecting);
        info.setVisibility(View.VISIBLE);
        ThreadUtils.workPost(() -> {
            try {
                ResolutionHelper.AutoResolution autoResolution =
                        ResolutionHelper.computeAuto(context, remoteAdr);
                final String message = formatResolutionMessage(autoResolution, false);
                ThreadUtils.post(() -> {
                    if (!isFinishing()) {
                        updateResolutionInfoText(message);
                    }
                });
            } catch (Exception e) {
                Log.e("Scrcpy", "Resolution preview failed: " + e.getMessage());
                ThreadUtils.post(() -> {
                    if (!isFinishing()) {
                        info.setText(R.string.resolution_auto_failed);
                        info.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void updateResolutionInfoText(String message) {
        TextView info = findViewById(R.id.text_resolution_info);
        if (info == null) {
            return;
        }
        if (TextUtils.isEmpty(message)) {
            info.setVisibility(View.GONE);
            return;
        }
        info.setText(message);
        info.setVisibility(View.VISIBLE);
    }

    private String formatResolutionMessage(ResolutionHelper.AutoResolution autoResolution, boolean activeStream) {
        if (autoResolution.limitSource == ResolutionHelper.LimitSource.LOCAL) {
            return getString(activeStream ? R.string.resolution_auto_stream_local : R.string.resolution_auto_local,
                    autoResolution.displayWidth, autoResolution.displayHeight);
        }
        return getString(activeStream ? R.string.resolution_auto_stream_remote : R.string.resolution_auto_remote,
                autoResolution.displayWidth, autoResolution.displayHeight);
    }

    private String formatActiveStreamResolutionMessage() {
        return formatResolutionMessage(
                new ResolutionHelper.AutoResolution(
                        Math.max(screenWidth, screenHeight),
                        screenWidth,
                        screenHeight,
                        ResolutionHelper.getLocalMaxDimension(context),
                        screenWidth,
                        screenHeight,
                        resolutionLimitSource),
                true);
    }

    private void getAttributes() {

        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        serverAdr = editTextServerHost.getText().toString();
        if (!TextUtils.isEmpty(serverAdr)) {
            serverAdr = serverAdr.trim();
        }
        // 不要把 USB 选择项当作 IP 地址持久化
        if (!TextUtils.isEmpty(serverAdr) && !UsbShellCompat.isUsbSelection(serverAdr)) {
            PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, serverAdr);
        }
        final Spinner videoResolutionSpinner = findViewById(R.id.spinner_video_resolution);
        final Spinner videoBitrateSpinner = findViewById(R.id.spinner_video_bitrate);
        final Spinner videoCodecSpinner = findViewById(R.id.spinner_video_codec);
        final Spinner videoFpsSpinner = findViewById(R.id.spinner_video_fps);
        final Spinner delayControlSpinner = findViewById(R.id.delay_control_spinner);
        final EditText encoderEdit = findViewById(R.id.editText_video_encoder);

        Switch a_Switch0 = findViewById(R.id.switch0);
        Switch a_Switch1 = findViewById(R.id.switch1);
        Switch audioEnableSwitch = findViewById(R.id.switch_audio_enable);
        PreUtils.put(context, Constant.CONTROL_NO, true);
        PreUtils.put(context, Constant.CONTROL_NAV, false);
        PreUtils.put(context, Constant.AUDIO_FORWARD, audioEnableSwitch.isChecked());

        String resolutionValue = getResources().getStringArray(R.array.options_resolution_values)[videoResolutionSpinner.getSelectedItemPosition()];
        autoResolutionEnabled = videoResolutionSpinner.getSelectedItemPosition() == Constant.RESOLUTION_AUTO_INDEX
                || "0".equals(resolutionValue) || "auto".equalsIgnoreCase(resolutionValue);
        resolutionLimitSource = null;
        if (autoResolutionEnabled) {
            screenHeight = 0;
            screenWidth = 0;
        } else if (resolutionValue.contains("x")) {
            final String[] videoResolutions = resolutionValue.split("x");
            screenHeight = Integer.parseInt(videoResolutions[0]);
            screenWidth = Integer.parseInt(videoResolutions[1]);
        } else {
            int maxSize = Integer.parseInt(resolutionValue);
            screenHeight = maxSize;
            screenWidth = maxSize;
        }
        videoBitrate = getResources().getIntArray(R.array.options_bitrate_values)[videoBitrateSpinner.getSelectedItemPosition()];
        videoCodec = getResources().getStringArray(R.array.options_codec_values)[videoCodecSpinner.getSelectedItemPosition()];
        videoFrameRate = getResources().getIntArray(R.array.options_fps_values)[videoFpsSpinner.getSelectedItemPosition()];
        delayControl = getResources().getIntArray(R.array.options_delay_values)[delayControlSpinner.getSelectedItemPosition()];
        videoEncoder = encoderEdit.getText() == null ? "" : encoderEdit.getText().toString().trim();
        PreUtils.put(context, Constant.PREFERENCE_VIDEO_ENCODER, videoEncoder);
    }

    private String[] getHistoryList() {
        String historyList = PreUtils.get(context, Constant.HISTORY_LIST_KEY, "");
        if (TextUtils.isEmpty(historyList)) {
            return new String[]{};
        }
        try {
            JSONArray historyJson = new JSONArray(historyList);
            String[] retList = new String[historyJson.length()];
            for (int i = 0; i < historyJson.length(); i++) {
                retList[i] = historyJson.get(i).toString();
            }
            return retList;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new String[]{};
    }

    /**
     * 保存设备历史连接记录
     */
    private boolean saveHistory(String device) {
        if (headlessMode) {
            // 无头模式不保存记录
            return false;
        }
        JSONArray historyJson = new JSONArray();
        String[] historyList = getHistoryList();
        if (historyList.length == 0) {
            historyJson.put(device);
        } else {
            try {
                historyJson.put(0, device);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            // 最多记录 30 个
            int count = Math.min(historyList.length, 30);
            for (int i = 0; i < count; i++) {
                if (!historyList[i].equals(device)) {
                    historyJson.put(historyList[i]);
                }
            }
        }
        try {
            return PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private void swapDimensions() {
        int temp = screenHeight;
        screenHeight = screenWidth;
        screenWidth = temp;
    }

    private void syncLandscapeFromConfiguration() {
        landscape = getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_PORTRAIT;
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void applyClientOrientationFromRemote() {
        if (scrcpy == null) {
            return;
        }
        int[] remote = scrcpy.getOrientedRemoteSize(landscape);
        if (remote[0] <= 0 || remote[1] <= 0) {
            return;
        }
        boolean remoteLandscape = remote[0] > remote[1];
        landscape = remoteLandscape;
        result_of_Rotation = true;
        Log.i("Scrcpy", "Client orientation from remote " + remote[0] + "x" + remote[1]
                + " landscape=" + remoteLandscape);
        if (remoteLandscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    private void applyMirrorImmersiveMode() {
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void refreshMirrorLayout() {
        if (linearLayout == null || scrcpy == null) {
            return;
        }
        linearLayout.setPadding(0, 0, 0, 0);
        linearLayout.post(() -> {
            if (serviceBound && scrcpy != null) {
                set_display_nd_touch();
                if (surfaceView != null) {
                    Surface currentSurface = surfaceView.getHolder().getSurface();
                    if (isSurfaceReady(currentSurface)) {
                        surface = currentSurface;
                        scrcpy.setParms(surface, screenWidth, screenHeight);
                    }
                }
            }
        });
    }

    private void setMirrorContentView() {
        Configuration overrideConfig = new Configuration(getResources().getConfiguration());
        overrideConfig.orientation = landscape
                ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;
        View root = LayoutInflater.from(createConfigurationContext(overrideConfig))
                .inflate(R.layout.surface, null);
        setContentView(root);
    }

    private void setupNavBar() {
        final LinearLayout navBar = findViewById(R.id.nav_button_bar);
        if (navBar != null) {
            navBar.setVisibility(View.GONE);
        }
    }

    private void bindMirrorSurfaceHolder() {
        surfaceView = findViewById(R.id.decoder_surface);
        if (surfaceView == null) {
            return;
        }
        if (surfaceCallback != null) {
            surfaceView.getHolder().removeCallback(surfaceCallback);
        }
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                // 仅在用户主动发起连接（first_time）且服务未绑定时启动服务。
                // 横屏旋转/退出后 surface 重建时（first_time=false）不重启服务，
                // 否则会误启动 scrcpy 会话，导致与下次连接冲突。
                if (!serviceBound && first_time) {
                    start_Scrcpy_service();
                } else if (scrcpy != null && isSurfaceReady(surface)) {
                    scrcpy.setParms(surface, screenWidth, screenHeight);
                    refreshMirrorLayout();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surface = holder.getSurface();
                if (serviceBound && scrcpy != null && isSurfaceReady(surface)) {
                    scrcpy.setParms(surface, screenWidth, screenHeight);
                    refreshMirrorLayout();
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
            }
        };
        surfaceView.getHolder().addCallback(surfaceCallback);
    }

    private void setupMirrorContentView() {
        syncLandscapeFromConfiguration();
        setMirrorContentView();
        applyMirrorImmersiveMode();
        bindMirrorSurfaceHolder();
        setupNavBar();
        linearLayout = findViewById(R.id.container1);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!first_time && serviceBound) {
            result_of_Rotation = true;
            setupMirrorContentView();
        }
    }

    private boolean isSurfaceReady(Surface displaySurface) {
        if (displaySurface == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return displaySurface.isValid();
        }
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void start_screen_copy_magic() {
        stopStatusMonitor();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        setupMirrorContentView();
    }

    private void start_Scrcpy_service() {
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void loadNewRotation() {
        ThreadUtils.post(() -> {
            result_of_Rotation = true;
            applyClientOrientationFromRemote();
            refreshMirrorLayout();
        });
    }

    @Override
    public void errorDisconnect() {
        // 必须退出
        // 退出重连
        Dialog.displayDialog(this, getString(R.string.disconnect),
                getString(R.string.disconnect_ask), () -> {
                    if (serviceBound) {
                        showMainView();
                        first_time = true;
                    } else {
                        MainActivity.this.finish();
                    }
                }, false);
    }

    @Override
    protected void onDestroy() {
        if (oneTapConnection != null) oneTapConnection.cancel();
        if (systemQrSession != null) systemQrSession.stop();
        pairingHandler.removeCallbacks(pairingApprovalPoll);
        stopLanAutoDiscovery();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            backInvokedCallback = null;
        }
        super.onDestroy();
        if (usbAdb != null) {
            usbAdb.close();
            usbAdb = null;
        }
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (Exception e) {
            // Receiver may not be registered.
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (resumeScrcpy && !isChangingConfigurations()) {
            // 返回到主页面，属于用户主动断开场景（旋转屏幕时不断开）
            showMainView(true);
            first_time = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d("Scrcpy", "onStart: " + serviceBound);
        if (resumeScrcpy) {
            if (!serviceBound) {
                resumeScrcpy = false;
                connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pairingHandler.removeCallbacks(pairingApprovalPoll);
        Log.d("Scrcpy", "onPause: " + serviceBound);
        try {
            // 页面不在前台时注销 USB 授权广播，避免 AdbShellActivity 的授权
            // 请求结果被本页面也接收，导致 scrcpy 与 adb 命令行同时启动
            unregisterReceiver(usbPermissionReceiver);
        } catch (Exception ignored) {
        }
        if (first_time) {
            stopStatusMonitor();
        }
        if (serviceBound && scrcpy != null) {
            scrcpy.pause();
            resumeScrcpy = true;
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        pairingHandler.removeCallbacks(pairingApprovalPoll);
        pairingHandler.post(pairingApprovalPoll);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION),
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
            }
        } catch (Exception ignored) {
        }
        if (first_time) {
            startStatusMonitor();
        }
        if (!first_time && !result_of_Rotation) {
            final View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (serviceBound) {
                if (surfaceView != null) {
                    Surface currentSurface = surfaceView.getHolder().getSurface();
                    if (isSurfaceReady(currentSurface)) {
                        surface = currentSurface;
                        scrcpy.setParms(surface, screenWidth, screenHeight);
                    }
                }
                scrcpy.resume();
            }
        }
        if (resumeScrcpy && !result_of_Rotation && scrcpy != null) {
            scrcpy.resume();
        }
        resumeScrcpy = false;  // 两处都要resumeScrcpy设置为false
        result_of_Rotation = false;
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBackPressed();
    }

    private void handleBackPressed() {
        if (timestamp == 0) {
            if (serviceBound) {
                timestamp = SystemClock.uptimeMillis();
                Toast.makeText(context, "Press again to exit", Toast.LENGTH_SHORT).show();
            } else {
                finish();
            }
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < timestamp + 1000) {
                timestamp = 0;
                if (serviceBound) {
                    showMainView(true);
                    first_time = true;
                } else {
                    finish();
                }
            }
            timestamp = 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (sensorEvent.values[0] == 0) {
                if (serviceBound) {
                    // 该事件会使远程手机 按下电源键，触发方式：按住距离传感器，然后点击屏幕即可锁屏
                    // 发送横竖屏会导致抬起事件无效
                    // scrcpy.sendKeyevent(28);
                }
            } else {
                if (serviceBound) {
                    // 发送横竖屏会导致抬起事件无效
                    // scrcpy.sendKeyevent(29);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private void confirmRebootRemoteDevice() {
        getAttributes();
        if (TextUtils.isEmpty(serverAdr)) {
            Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] serverInfo = Util.getServerHostAndPort(serverAdr);
        String device = serverInfo[0] + ":" + serverInfo[1];
        Dialog.displayDialog(this,
                getString(R.string.reboot_title),
                getString(R.string.reboot_ask, device),
                () -> rebootRemoteDevice(device),
                () -> {
                });
    }

    private void rebootRemoteDevice(String device) {
        Progress.showDialog(MainActivity.this, getString(R.string.reboot_wait));
        ThreadUtils.workPost(() -> {
            AdbHelper.adbCmd(App.mContext, "connect", device);
            String result = AdbHelper.adbCmd(App.mContext, "-s", device, "reboot");
            SessionLog.i("ADB reboot device=" + device + " result=" + result);
            ThreadUtils.post(() -> {
                Progress.closeDialog();
                if (MainActivity.this.isFinishing()) {
                    return;
                }
                Toast.makeText(context, getString(R.string.reboot_sent, device), Toast.LENGTH_SHORT).show();
                startStatusMonitor();
            });
        });
    }

    private void confirmPowerOffRemoteDevice() {
        getAttributes();
        if (TextUtils.isEmpty(serverAdr)) {
            Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] serverInfo = Util.getServerHostAndPort(serverAdr);
        String device = serverInfo[0] + ":" + serverInfo[1];
        Dialog.displayDialog(this,
                getString(R.string.power_off_title),
                getString(R.string.power_off_ask, device),
                () -> powerOffRemoteDevice(device),
                () -> {
                });
    }

    private void powerOffRemoteDevice(String device) {
        Progress.showDialog(MainActivity.this, getString(R.string.power_off_wait));
        ThreadUtils.workPost(() -> {
            AdbHelper.adbCmd(App.mContext, "connect", device);
            String result = AdbHelper.adbCmd(App.mContext, "-s", device, "reboot", "-p");
            SessionLog.i("ADB power off device=" + device + " result=" + result);
            ThreadUtils.post(() -> {
                Progress.closeDialog();
                if (MainActivity.this.isFinishing()) {
                    return;
                }
                Toast.makeText(context, getString(R.string.power_off_sent, device), Toast.LENGTH_SHORT).show();
                startStatusMonitor();
            });
        });
    }

    private void connectScrcpyServer(String serverAdr) {
        final boolean skipWakeForAuthorizedOneTap = skipWakeRequestOnce;
        skipWakeRequestOnce = false;
        if (!TextUtils.isEmpty(serverAdr)) {
            saveHistory(serverAdr);  // 保存到历史记录
            String[] serverInfo = Util.getServerHostAndPort(serverAdr);
            String serverHost = serverInfo[0];
            int serverPort = Integer.parseInt(serverInfo[1]);
            int localForwardPort = Scrcpy.LOCAL_FORWART_PORT;

            // Read-only viewer: first restore the previously authorized ADB
            // connection. This does NOT wake/unlock the remote screen or launch
            // an app on the other phone. If debugging was disabled by Android,
            // the owner must enable and authorize it again.
            Progress.showDialog(MainActivity.this, getString(R.string.reconnecting_authorized_device));
            ThreadUtils.workPost(() -> {
                android.content.SharedPreferences wakeSettings = getSharedPreferences(
                        WakeLanService.PREF, MODE_PRIVATE);
                String wakeHost = wakeSettings.getString("viewer_host", "");
                String wakeCode = wakeSettings.getString("viewer_code", "");
                if (!skipWakeForAuthorizedOneTap
                        && !TextUtils.isEmpty(wakeHost) && !TextUtils.isEmpty(wakeCode)) {
                    ThreadUtils.post(() -> Progress.showDialog(MainActivity.this,
                            getString(R.string.wake_lan_requesting)));
                    String failure = WakeLanClient.request(wakeHost, wakeCode);
                    if (failure != null) {
                        SessionLog.i("Authenticated LAN wake request failed: " + failure);
                        ThreadUtils.post(() -> {
                            Progress.closeDialog();
                            if (!isFinishing()) {
                                Toast.makeText(context, failure, Toast.LENGTH_LONG).show();
                                startStatusMonitor();
                            }
                        });
                        return;
                    }
                    SessionLog.i("Authenticated LAN wake request acknowledged; connecting ADB");
                    ThreadUtils.post(() -> Progress.showDialog(MainActivity.this,
                            getString(R.string.wake_lan_accepted)));
                }
                boolean adbReady = AdbHelper.isDeviceOnline(context, serverHost, serverPort);
                if (!adbReady) {
                    SessionLog.i("ADB preflight: reconnect unavailable, restarting local ADB daemon");
                    AdbHelper.restartAdb();
                    try {
                        AdbHelper.waitForRunning(5);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    adbReady = AdbHelper.isDeviceOnline(context, serverHost, serverPort);
                }
                if (!adbReady) {
                    SessionLog.i("ADB preflight: target still unavailable or not authorized");
                    ThreadUtils.post(() -> {
                        Progress.closeDialog();
                        if (!MainActivity.this.isFinishing()) {
                            Toast.makeText(context, R.string.adb_reconnect_failed, Toast.LENGTH_LONG).show();
                            startStatusMonitor();
                        }
                    });
                    return;
                }
                SessionLog.i("ADB preflight: authorized device is online; launching read-only stream");
                AdbHelper.writeAssetsJarServer(App.mContext);
                if (autoResolutionEnabled) {
                    ResolutionHelper.AutoResolution autoResolution =
                            ResolutionHelper.computeAuto(context, serverAdr);
                    screenWidth = autoResolution.displayWidth;
                    screenHeight = autoResolution.displayHeight;
                    resolutionLimitSource = autoResolution.limitSource;
                    final String resolutionMessage = formatResolutionMessage(autoResolution, false);
                    ThreadUtils.post(() -> updateResolutionInfoText(resolutionMessage));
                }
                Options options = new Options();
                options.setIp(Scrcpy.LOCAL_IP);
                options.setMaxSize(Math.max(screenHeight, screenWidth));
                options.setBitRate(videoBitrate);
                options.setTunnelForward(true);
                options.setEnableAudioForward(PreUtils.get(context, Constant.AUDIO_FORWARD, true));
                options.setVideoCodec(videoCodec);
                options.setVideoEncoder(videoEncoder);
                options.setFrameRate(videoFrameRate);
                SendCommands.CmdStatus sendStatus = sendCommands.SendAdbCommands(context, serverHost,
                        serverPort,
                        localForwardPort,
                        options);
                if (sendStatus == SendCommands.CmdStatus.SUCCESS) {
                    ThreadUtils.post(() -> {
                        if (!MainActivity.this.isFinishing()) {
                            // 进入主线程
                            Log.e("Scrcpy: ", "from startButton");
                            start_screen_copy_magic();
                        }
                    });
                } else {
                    ThreadUtils.post(() -> {
                        Progress.closeDialog();
                        Toast.makeText(context, "Network OR ADB connection failed", Toast.LENGTH_SHORT).show();
                        startStatusMonitor();
                        connectExitExt();
                    });
                }
            });
        } else {
            Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show();
            startStatusMonitor();
            connectExitExt();
        }
    }

    /**
     * Opens the Android system page, not a debugging toggle.
     * Some Android vendors expose a direct wireless debugging action, but
     * it is not a stable public Android API. Always fall back to the official
     * developer-options action and finally the general system settings.
     * The user must enable wireless debugging and approve ADB themselves.
     */
    private void openWirelessDebugSettings() {
        if (XiaomiSettingsSupport.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND)) {
            // Xiaomi / Redmi / POCO: the public developer-options entry is more
            // reliable than an undocumented wireless-debugging deep-link.
            // Never launch hidden com.android.settings internal activities.
            if (launchDebugSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) {
                Toast.makeText(this, R.string.xiaomi_debug_developer_opened,
                        Toast.LENGTH_LONG).show();
                return;
            }
            showXiaomiDeveloperGuide();
            return;
        }
        if (VivoSettingsSupport.isVivoFamily(Build.MANUFACTURER, Build.BRAND)) {
            // OriginOS / iQOO: use the public Developer Options entry instead of
            // undocumented vendor activities which differ by model and update.
            if (launchDebugSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) {
                Toast.makeText(this, R.string.vivo_debug_developer_opened,
                        Toast.LENGTH_LONG).show();
                return;
            }
            showVivoDeveloperGuide();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && launchDebugSettings("android.settings.WIRELESS_DEBUGGING_SETTINGS")) {
            return;
        }
        if (launchDebugSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) {
            Toast.makeText(this, R.string.wireless_debugging_find_in_developer_options,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (launchDebugSettings(Settings.ACTION_SETTINGS)) {
            Toast.makeText(this, R.string.wireless_debugging_find_in_system_settings,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.wireless_debugging_settings_unavailable,
                Toast.LENGTH_LONG).show();
    }

    /**
     * Xiaomi / Redmi / POCO setup guide. MIUI and HyperOS rename the "About
     * phone" and version submenus, so offer documented public Settings intents
     * rather than guessing hidden vendor activity class names.
     */
    private void showXiaomiDeveloperGuide() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.xiaomi_debug_help_title)
                .setMessage(R.string.xiaomi_debug_help_message)
                .setPositiveButton(R.string.xiaomi_debug_open_developer, (dialog, which) -> {
                    if (!launchDebugSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            && !launchDebugSettings(Settings.ACTION_SETTINGS)) {
                        Toast.makeText(this, R.string.wireless_debugging_settings_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(R.string.xiaomi_debug_open_about_phone, (dialog, which) -> {
                    if (!launchDebugSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
                            && !launchDebugSettings(Settings.ACTION_SETTINGS)) {
                        Toast.makeText(this, R.string.wireless_debugging_settings_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Instructions for vivo / iQOO users; all switches remain user controlled. */
    private void showVivoDeveloperGuide() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.vivo_debug_help_title)
                .setMessage(R.string.vivo_debug_help_message)
                .setPositiveButton(R.string.vivo_debug_open_developer, (dialog, which) -> {
                    if (!launchDebugSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            && !launchDebugSettings(Settings.ACTION_SETTINGS)) {
                        Toast.makeText(this, R.string.wireless_debugging_settings_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(R.string.vivo_debug_open_about_phone, (dialog, which) -> {
                    if (!launchDebugSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
                            && !launchDebugSettings(Settings.ACTION_SETTINGS)) {
                        Toast.makeText(this, R.string.wireless_debugging_settings_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Opens only this application's system details, never changes power settings. */
    private void showVivoBackgroundGuide() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.vivo_background_title)
                .setMessage(R.string.vivo_background_message)
                .setPositiveButton(R.string.vivo_background_open_details, (dialog, which) -> {
                    Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    details.setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(details);
                    } catch (ActivityNotFoundException | SecurityException error) {
                        Log.d("Scrcpy", "Application details unavailable", error);
                        if (!launchDebugSettings(Settings.ACTION_SETTINGS)) {
                            Toast.makeText(this, R.string.vivo_background_settings_unavailable,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean launchDebugSettings(String action) {
        try {
            startActivity(new Intent(action));
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.d("Scrcpy", "Settings action unavailable: " + action, error);
            return false;
        }
    }

    private void initPairingHome() {
        Button receive = findViewById(R.id.button_begin_pairing);
        Button watch = findViewById(R.id.button_watch_paired);
        Button systemAdb = findViewById(R.id.button_authorize_adb);
        Button remove = findViewById(R.id.button_remove_pair);
        if (receive != null) receive.setOnClickListener(v -> showLocalPairCode());
        if (watch != null) watch.setOnClickListener(v -> watchPairedDevice());
        if (systemAdb != null) systemAdb.setOnClickListener(v -> authorizeSystemAdb());
        if (remove != null) remove.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.pair_remove_title)
                    .setMessage(R.string.pair_remove_detail)
                    .setPositiveButton(R.string.pair_remove_confirm, (d, which) -> {
                        PairedDeviceStore.clear(this);
                        try { WakeLanService.stopByOwner(this); }
                        catch (RuntimeException error) {
                            Log.w("Scrcpy", "Wake listener could not be stopped", error);
                        }
                        getSharedPreferences(WakeLanService.PREF, MODE_PRIVATE).edit()
                                .remove("viewer_host").remove("viewer_code").apply();
                        EditText wakeField = findViewById(R.id.editText_wake_host);
                        EditText secretField = findViewById(R.id.editText_wake_key);
                        if (wakeField != null) wakeField.setText("");
                        if (secretField != null) secretField.setText("");
                        updatePairedDeviceUi();
                        Toast.makeText(this, R.string.pair_remove_adb_reminder,
                                Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
        updatePairedDeviceUi();
    }

    private void showLocalPairCode() {
        String code = WakeLanService.openPairingWindow(this);
        try {
            WakeLanService.startByOwner(this);
        } catch (RuntimeException error) {
            Log.e("Scrcpy", "Pairing receiver unavailable", error);
            Toast.makeText(this, R.string.wake_lan_start_failed, Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, 6, pad, 6);
        TextView hint = new TextView(this);
        hint.setText(R.string.qr_app_show_hint);
        hint.setTextSize(14);
        form.addView(hint);
        String localIp = PairQrCode.localWifiIp(this);
        if (localIp != null) {
            try {
                String payload = PairQrCode.createAppPayload(localIp, code);
                ImageView image = makeQrImage(payload);
                form.addView(image);
            } catch (Exception error) {
                Log.w("Scrcpy", "App QR render failed", error);
            }
        } else {
            Toast.makeText(this, R.string.qr_wifi_required, Toast.LENGTH_LONG).show();
        }
        TextView codeText = new TextView(this);
        codeText.setText(code);
        codeText.setTextSize(27);
        codeText.setTextIsSelectable(true);
        codeText.setGravity(android.view.Gravity.CENTER);
        codeText.setPadding(0, pad / 2, 0, pad / 2);
        form.addView(codeText);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pair_code_title)
                .setView(form)
                .setPositiveButton(R.string.pair_code_ack, null).show();
    }

    private ImageView makeQrImage(String payload) throws Exception {
        android.graphics.Bitmap bitmap = new BarcodeEncoder()
                .encodeBitmap(payload, BarcodeFormat.QR_CODE, 620, 620);
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setMaxWidth(620);
        image.setMaxHeight(620);
        image.setContentDescription("扫描二维码进行配对");
        return image;
    }

    private void scanAppPairQr() {
        if (PairedDeviceStore.load(this) != null) {
            Toast.makeText(this, R.string.qr_device_already_paired, Toast.LENGTH_LONG).show();
            return;
        }
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE_TYPES)
                .setPrompt(getString(R.string.qr_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult scanned = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanned != null) {
            if (scanned.getContents() != null) {
                PairQrCode.Target target = PairQrCode.parseAppPayload(scanned.getContents());
                if (target == null) {
                    Toast.makeText(this, R.string.qr_invalid, Toast.LENGTH_LONG).show();
                } else {
                    requestAppPairFromQr(target);
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void requestAppPairFromQr(PairQrCode.Target target) {
        if (PairedDeviceStore.load(this) != null) {
            Toast.makeText(this, R.string.qr_device_already_paired, Toast.LENGTH_LONG).show();
            return;
        }
        Progress.showDialog(this, getString(R.string.pair_waiting_owner));
        ThreadUtils.workPost(() -> {
            PairLanClient.Result result = PairLanClient.pair(target.host, target.port, target.code);
            ThreadUtils.post(() -> {
                Progress.closeDialog();
                if (isFinishing() || isDestroyed()) return;
                if (!result.ok()) {
                    Toast.makeText(this, getString(R.string.qr_app_pair_failed, result.error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (PairedDeviceStore.load(this) != null) {
                    Toast.makeText(this, R.string.qr_device_already_paired,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                PairedDeviceStore.save(this, "已扫码手机", target.host, result.secret, 0);
                try {
                    WakeLanService.startByOwner(this);
                } catch (RuntimeException error) {
                    Log.w("Scrcpy", "Receiver could not start after QR pairing", error);
                }
                syncPeerToViewFields(PairedDeviceStore.load(this));
                updatePairedDeviceUi();
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.pair_success_title)
                        .setMessage(R.string.qr_app_pair_success)
                        .setPositiveButton(R.string.qr_system_option,
                                (d, which) -> showSystemAdbPairQr())
                        .setNeutralButton(R.string.pair_authorize_button,
                                (d, which) -> authorizeSystemAdb())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void showIncomingPairConsent(WakeLanService.PendingPair pending) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pair_owner_approve_title)
                .setMessage(getString(R.string.pair_owner_approve_description, pending.remoteAddress))
                .setPositiveButton(R.string.pair_owner_approve, (d, which) -> {
                    WakeLanService.decidePairing(pending.id, true);
                    pairingHandler.postDelayed(this::updatePairedDeviceUi, 1100);
                })
                .setNegativeButton(R.string.pair_owner_deny, (d, which) ->
                        WakeLanService.decidePairing(pending.id, false))
                .setOnCancelListener(d -> WakeLanService.decidePairing(pending.id, false))
                .show();
    }

    private void updatePairedDeviceUi() {
        TextView label = findViewById(R.id.paired_device_status);
        if (label == null) return;
        PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
        label.setText(peer == null ? getString(R.string.pair_no_device)
                : getString(R.string.pair_connected_device, peer.name));
        Button watch = findViewById(R.id.button_watch_paired);
        Button auth = findViewById(R.id.button_authorize_adb);
        Button remove = findViewById(R.id.button_remove_pair);
        if (watch != null) watch.setEnabled(peer != null);
        if (auth != null) auth.setEnabled(peer != null);
        if (remove != null) remove.setEnabled(peer != null);
        TextView quickName = findViewById(R.id.quick_device_name);
        if (quickName != null) quickName.setText(peer == null
                ? getString(R.string.quick_device_none) : peer.name);
        LinearLayout pairActions = findViewById(R.id.quick_pair_actions);
        if (pairActions != null) pairActions.setVisibility(peer == null ? View.VISIBLE : View.GONE);
        Switch receiver = findViewById(R.id.quick_receive_switch);
        if (receiver != null) receiver.setVisibility(peer == null ? View.GONE : View.VISIBLE);
        Button quickWatch = findViewById(R.id.quick_watch_button);
        if (quickWatch != null && !oneTapConnecting) {
            quickWatch.setText(peer == null ? R.string.quick_watch_add : R.string.quick_watch_start);
            quickWatch.setEnabled(true);
        }
        TextView quickStatus = findViewById(R.id.quick_connection_status);
        if (quickStatus != null && !oneTapConnecting) {
            quickStatus.setText(peer == null
                    ? R.string.quick_status_unpaired : R.string.quick_status_paired);
        }
    }

    /**
     * Everyday controls sit above the fully preserved original troubleshooting UI.
     * They never grant system ADB permission or start video capture on the peer.
     */
    private void initQuickHome() {
        Button primary = findViewById(R.id.quick_watch_button);
        Button showCode = findViewById(R.id.quick_show_pair_code);
        Button search = findViewById(R.id.quick_find_peer);
        Button scanQr = findViewById(R.id.quick_scan_pair_qr);
        Button advanced = findViewById(R.id.quick_advanced_button);
        Switch receiver = findViewById(R.id.quick_receive_switch);
        if (primary != null) primary.setOnClickListener(v -> startOneTapWatch());
        if (showCode != null) showCode.setOnClickListener(v -> showLocalPairCode());
        if (search != null) search.setOnClickListener(v -> startLanAutoDiscovery());
        if (scanQr != null) scanQr.setOnClickListener(v -> scanAppPairQr());
        if (advanced != null) advanced.setOnClickListener(v -> toggleQuickAdvanced());
        if (receiver != null) {
            receiver.setChecked(!TextUtils.isEmpty(WakeLanService.currentCode(this)));
            receiver.setOnCheckedChangeListener((view, enabled) -> {
                if (enabled) {
                    try {
                        WakeLanService.startByOwner(this);
                    } catch (RuntimeException error) {
                        Log.w("Scrcpy", "Receiver could not start", error);
                        Toast.makeText(this, R.string.quick_receive_failed, Toast.LENGTH_LONG).show();
                        receiver.setOnCheckedChangeListener(null);
                        receiver.setChecked(false);
                        initQuickReceiverListener(receiver);
                    }
                } else {
                    try {
                        WakeLanService.stopByOwner(this);
                    } catch (RuntimeException error) {
                        Log.w("Scrcpy", "Receiver could not stop", error);
                    }
                }
            });
        }
        updatePairedDeviceUi();
    }

    private void initQuickReceiverListener(Switch receiver) {
        receiver.setOnCheckedChangeListener((view, enabled) -> {
            if (enabled) {
                try {
                    WakeLanService.startByOwner(this);
                } catch (RuntimeException error) {
                    Log.w("Scrcpy", "Receiver could not start", error);
                    Toast.makeText(this, R.string.quick_receive_failed, Toast.LENGTH_LONG).show();
                    receiver.setOnCheckedChangeListener(null);
                    receiver.setChecked(false);
                    initQuickReceiverListener(receiver);
                }
            } else {
                try {
                    WakeLanService.stopByOwner(this);
                } catch (RuntimeException error) {
                    Log.w("Scrcpy", "Receiver could not stop", error);
                }
            }
        });
    }

    private void toggleQuickAdvanced() {
        LinearLayout details = findViewById(R.id.layout_advanced_controls);
        Button button = findViewById(R.id.quick_advanced_button);
        if (details == null || button == null) return;
        boolean willShow = details.getVisibility() != View.VISIBLE;
        details.setVisibility(willShow ? View.VISIBLE : View.GONE);
        button.setText(willShow ? R.string.quick_close_advanced : R.string.quick_open_advanced);
    }

    private void showFirstUseQuickPair() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.quick_pair_title)
                .setMessage(R.string.quick_pair_help)
                .setPositiveButton(R.string.quick_pair_show, (d, which) -> showLocalPairCode())
                .setNeutralButton(R.string.quick_pair_scan, (d, which) -> scanAppPairQr())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void setQuickStage(int message) {
        TextView status = findViewById(R.id.quick_connection_status);
        if (status != null) status.setText(message);
    }

    private void endOneTapState() {
        oneTapConnecting = false;
        Button button = findViewById(R.id.quick_watch_button);
        if (button != null) {
            button.setEnabled(true);
            button.setText(R.string.quick_watch_start);
        }
    }

    private void startOneTapWatch() {
        if (oneTapConnecting || isFinishing() || isDestroyed()) return;
        PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
        if (peer == null) {
            showFirstUseQuickPair();
            return;
        }
        if (oneTapConnection == null) oneTapConnection = new OneTapConnection(this);
        oneTapConnecting = true;
        stopStatusMonitor();
        Button button = findViewById(R.id.quick_watch_button);
        if (button != null) {
            button.setEnabled(false);
            button.setText(R.string.quick_watch_busy);
        }
        oneTapConnection.start(peer, new OneTapConnection.Callback() {
            @Override public void onStage(int stringRes) {
                if (!oneTapConnecting || isFinishing() || isDestroyed()) return;
                setQuickStage(stringRes);
            }

            @Override public void onConnected(String host, int port) {
                if (!oneTapConnecting || isFinishing() || isDestroyed()) return;
                PairedDeviceStore.updateAddress(MainActivity.this, host);
                PairedDeviceStore.updatePort(MainActivity.this, port);
                PairedDeviceStore.Peer current = PairedDeviceStore.load(MainActivity.this);
                if (current == null) {
                    endOneTapState();
                    startStatusMonitor();
                    return;
                }
                syncPeerToViewFields(current);
                endOneTapState();
                setQuickStage(R.string.quick_status_online);
                // Preflight already verified ADB; do not fail merely because the
                // optional wake listener stopped while the peer remained online.
                skipWakeRequestOnce = true;
                Button existingStart = findViewById(R.id.button_start);
                if (existingStart != null) existingStart.performClick();
                else startStatusMonitor();
            }

            @Override public void onFailed(OneTapConnection.Failure reason) {
                if (!oneTapConnecting || isFinishing() || isDestroyed()) return;
                endOneTapState();
                startStatusMonitor();
                int detail = reason == OneTapConnection.Failure.DEVICE_NOT_FOUND
                        ? R.string.quick_fail_offline : R.string.quick_fail_adb;
                setQuickStage(detail);
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.quick_fail_title)
                        .setMessage(detail)
                        .setPositiveButton(R.string.quick_retry,
                                (d, which) -> startOneTapWatch())
                        .setNeutralButton(R.string.quick_repair,
                                (d, which) -> showQuickConnectionRepair())
                        .setNegativeButton(android.R.string.cancel, null).show();
            }
        });
    }

    private void showQuickConnectionRepair() {
        final CharSequence[] options = {
                getString(R.string.pair_authorize_button),
                getString(R.string.pair_adb_connect_manual_button),
                getString(R.string.quick_open_advanced)
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.quick_repair)
                .setItems(options, (d, which) -> {
                    if (which == 0) authorizeSystemAdb();
                    else if (which == 1) {
                        PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
                        if (peer != null) promptManualAdbConnectPort(peer);
                    } else {
                        LinearLayout advanced = findViewById(R.id.layout_advanced_controls);
                        if (advanced != null && advanced.getVisibility() != View.VISIBLE) {
                            toggleQuickAdvanced();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void syncPeerToViewFields(PairedDeviceStore.Peer peer) {
        EditText wakeHost = findViewById(R.id.editText_wake_host);
        EditText wakeKey = findViewById(R.id.editText_wake_key);
        if (wakeHost != null) wakeHost.setText(peer.host + ":" + WakeLanService.PORT);
        if (wakeKey != null) wakeKey.setText(peer.secret);
        getSharedPreferences(WakeLanService.PREF, MODE_PRIVATE).edit()
                .putString("viewer_host", peer.host + ":" + WakeLanService.PORT)
                .putString("viewer_code", peer.secret).apply();
        if (peer.adbPort > 0) {
            String serial = peer.host + ":" + peer.adbPort;
            EditText adbHost = findViewById(R.id.editText_server_host);
            if (adbHost != null) adbHost.setText(serial);
            PreUtils.put(this, Constant.CONTROL_REMOTE_ADDR, serial);
        }
    }

    private void watchPairedDevice() {
        PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
        if (peer == null) {
            Toast.makeText(this, R.string.pair_no_device, Toast.LENGTH_SHORT).show();
            return;
        }
        syncPeerToViewFields(peer);
        if (peer.adbPort <= 0) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.pair_adb_required_title)
                    .setMessage(R.string.pair_adb_required_description)
                    .setPositiveButton(R.string.pair_adb_connect_manual_button,
                            (d, which) -> promptManualAdbConnectPort(peer))
                    .setNeutralButton(R.string.pair_authorize_button,
                            (d, which) -> authorizeSystemAdb())
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        Button start = findViewById(R.id.button_start);
        if (start != null) start.performClick();
    }

    private void pairWithChosenDevice(LanDeviceDiscovery.Device device) {
        PairedDeviceStore.Peer existing = PairedDeviceStore.load(this);
        if (existing != null && device.address.equals(existing.host)) {
            if (device.adbPort > 0) PairedDeviceStore.updatePort(this, device.adbPort);
            syncPeerToViewFields(PairedDeviceStore.load(this));
            updatePairedDeviceUi();
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.pair_existing_title)
                    .setMessage(R.string.pair_existing_message)
                    .setPositiveButton(R.string.pair_watch_button,
                            (d, which) -> watchPairedDevice())
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        if (existing != null) {
            // Local addresses can change after reconnecting Wi-Fi. Authenticate
            // the saved secret against the newly discovered endpoint before
            // updating the stored IP; NEVER trust an NSD name alone.
            Progress.showDialog(this, getString(R.string.pair_verifying_existing));
            ThreadUtils.workPost(() -> {
                String failure = WakeLanClient.request(device.address + ":"
                        + device.wakePort, existing.secret);
                ThreadUtils.post(() -> {
                    Progress.closeDialog();
                    if (isFinishing() || isDestroyed()) return;
                    if (failure != null) {
                        Toast.makeText(this, R.string.pair_existing_verification_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    PairedDeviceStore.updateAddress(this, device.address);
                    if (device.adbPort > 0) PairedDeviceStore.updatePort(this, device.adbPort);
                    syncPeerToViewFields(PairedDeviceStore.load(this));
                    updatePairedDeviceUi();
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.pair_existing_title)
                            .setMessage(R.string.pair_existing_message)
                            .setPositiveButton(R.string.pair_watch_button,
                                    (d, which) -> watchPairedDevice())
                            .setNegativeButton(android.R.string.cancel, null).show();
                });
            });
            return;
        }
        EditText codeInput = new EditText(this);
        codeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeInput.setSingleLine(true);
        codeInput.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(6)});
        codeInput.setHint(R.string.pair_six_digits_hint);
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.pair_enter_code_title, device.name))
                .setMessage(R.string.pair_enter_code_instructions)
                .setView(codeInput)
                .setPositiveButton(R.string.pair_submit_button, (d, which) -> {
                    String code = codeInput.getText().toString().trim();
                    if (!code.matches("[0-9]{6}")) {
                        Toast.makeText(this, R.string.pair_bad_code, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Progress.showDialog(this, getString(R.string.pair_waiting_owner));
                    ThreadUtils.workPost(() -> {
                        PairLanClient.Result result = PairLanClient.pair(
                                device.address, device.wakePort, code);
                        ThreadUtils.post(() -> {
                            Progress.closeDialog();
                            if (isFinishing() || isDestroyed()) return;
                            if (!result.ok()) {
                                Toast.makeText(this, result.error, Toast.LENGTH_LONG).show();
                                return;
                            }
                            PairedDeviceStore.save(this, device.name,
                                    device.address, result.secret, device.adbPort);
                            // Both phones explicitly opted into mutual pairing.
                            // Start a visibly notified listener on B as well,
                            // so A can subsequently initiate its own requests.
                            try {
                                WakeLanService.startByOwner(this);
                            } catch (RuntimeException error) {
                                Log.w("Scrcpy", "Peer paired but B receiver unavailable", error);
                            }
                            syncPeerToViewFields(PairedDeviceStore.load(this));
                            updatePairedDeviceUi();
                            new android.app.AlertDialog.Builder(this)
                                    .setTitle(R.string.pair_success_title)
                                    .setMessage(R.string.pair_success_message)
                                    .setPositiveButton(R.string.pair_watch_button,
                                            (dialog, selected) -> watchPairedDevice())
                                    .setNegativeButton(R.string.pair_authorize_button,
                                            (dialog, selected) -> authorizeSystemAdb())
                                    .show();
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void authorizeSystemAdb() {
        PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
        if (peer == null) return;
        CharSequence[] methods = {
                getString(R.string.qr_system_option),
                getString(R.string.pair_adb_find_port),
                getString(R.string.pair_adb_manual_button)
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pair_adb_guide_title)
                .setMessage(R.string.pair_adb_guide_message)
                .setItems(methods, (d, selection) -> {
                    if (selection == 0) showSystemAdbPairQr();
                    else if (selection == 1) discoverAdbPairingService(peer);
                    else promptManualSystemAdbPairing(peer);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showSystemAdbPairQr() {
        PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
        if (peer == null) return;
        stopLanAutoDiscovery();
        if (systemQrSession != null) systemQrSession.stop();
        String serviceName = PairQrCode.newSystemQrService();
        String password = PairQrCode.newSystemQrPassword();
        String payload = PairQrCode.createSystemQrPayload(serviceName, password);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        TextView guide = new TextView(this);
        guide.setText(R.string.qr_system_hint);
        guide.setTextSize(14);
        content.addView(guide);
        try {
            content.addView(makeQrImage(payload));
        } catch (Exception error) {
            Log.w("Scrcpy", "System QR generation failed", error);
            Toast.makeText(this, R.string.pair_adb_failed, Toast.LENGTH_LONG).show();
            return;
        }
        TextView state = new TextView(this);
        state.setText(R.string.qr_system_waiting);
        state.setTextSize(14);
        state.setPadding(0, pad / 2, 0, 0);
        content.addView(state);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.qr_system_option)
                .setView(content)
                .setNegativeButton(R.string.qr_system_code_fallback,
                        (d, which) -> authorizeSystemAdbCodeOnly())
                .setPositiveButton(android.R.string.cancel, null)
                .create();
        systemQrSession = new SystemAdbQrPairingSession(this);
        final SystemAdbQrPairingSession current = systemQrSession;
        dialog.setOnDismissListener(d -> current.stop());
        dialog.show();
        current.start(serviceName, password, peer, new SystemAdbQrPairingSession.Listener() {
            @Override public void onWaiting() {
                if (!isFinishing()) state.setText(R.string.qr_system_waiting);
            }

            @Override public void onPairing() {
                if (!isFinishing()) state.setText(R.string.qr_system_pairing);
            }

            @Override public void onSuccess(String verifiedHost, int connectPort) {
                if (isFinishing() || isDestroyed()) return;
                PairedDeviceStore.updateAddress(MainActivity.this, verifiedHost);
                if (connectPort > 0) PairedDeviceStore.updatePort(MainActivity.this, connectPort);
                PairedDeviceStore.Peer updated = PairedDeviceStore.load(MainActivity.this);
                if (updated != null) syncPeerToViewFields(updated);
                updatePairedDeviceUi();
                dialog.dismiss();
                Toast.makeText(MainActivity.this, R.string.qr_system_success,
                        Toast.LENGTH_LONG).show();
                startOneTapWatch();
            }

            @Override public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                state.setText(message);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void authorizeSystemAdbCodeOnly() {
        PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
        if (peer == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pair_adb_guide_title)
                .setMessage(R.string.pair_adb_guide_message)
                .setPositiveButton(R.string.pair_adb_find_port,
                        (d, which) -> discoverAdbPairingService(peer))
                .setNeutralButton(R.string.pair_adb_manual_button,
                        (d, which) -> promptManualSystemAdbPairing(peer))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void discoverAdbPairingService(PairedDeviceStore.Peer peer) {
        stopLanAutoDiscovery();
        Progress.showDialog(this, getString(R.string.pair_adb_searching));
        final LanDeviceDiscovery[] scan = new LanDeviceDiscovery[1];
        final int[] pairPort = {0};
        final int[] connectPort = {peer.adbPort};
        scan[0] = new LanDeviceDiscovery(this, new LanDeviceDiscovery.Listener() {
            @Override public void onChanged(List<LanDeviceDiscovery.Device> devices) {
                for (LanDeviceDiscovery.Device device : devices) {
                    if (!peer.host.equals(device.address)) continue;
                    if (device.pairingPort > 0) pairPort[0] = device.pairingPort;
                    if (device.adbPort > 0) connectPort[0] = device.adbPort;
                }
            }
            @Override public void onError(String message) {
                Log.d("Scrcpy", "ADB pairing port scan: " + message);
            }
        });
        scan[0].start();
        pairingHandler.postDelayed(() -> {
            // Read the unfiltered NSD snapshot: the system's pairing service may
            // not appear in the list of app wake services.
            for (LanDeviceDiscovery.Device device : scan[0].allDiscovered()) {
                if (!peer.host.equals(device.address)) continue;
                if (device.pairingPort > 0) pairPort[0] = device.pairingPort;
                if (device.adbPort > 0) connectPort[0] = device.adbPort;
            }
            scan[0].stop();
            Progress.closeDialog();
            if (isFinishing() || isDestroyed()) return;
            if (connectPort[0] > 0) {
                PairedDeviceStore.updatePort(this, connectPort[0]);
                syncPeerToViewFields(PairedDeviceStore.load(this));
            }
            if (pairPort[0] <= 0) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.pair_adb_required_title)
                        .setMessage(R.string.pair_adb_port_missing)
                        .setPositiveButton(R.string.pair_adb_manual_button,
                                (d, which) -> promptManualSystemAdbPairing(peer))
                        .setNeutralButton(R.string.pair_adb_find_port,
                                (d, which) -> discoverAdbPairingService(peer))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
            promptSystemAdbCode(peer.host, pairPort[0]);
        }, 9000);
    }

    private void promptSystemAdbCode(String address, int port) {
        EditText systemCode = new EditText(this);
        systemCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        systemCode.setSingleLine(true);
        systemCode.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(6)});
        systemCode.setHint(R.string.pair_six_digits_hint);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pair_adb_code_title)
                .setMessage(R.string.pair_adb_code_message)
                .setView(systemCode)
                .setPositiveButton(R.string.pair_submit_button, (d, selected) ->
                        pairSystemAdb(address, port, systemCode.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Manual fallback for ROMs/routers that block Android ADB mDNS.
     * The IP is editable: DHCP can change it after the app was paired.
     */
    private void promptManualSystemAdbPairing(PairedDeviceStore.Peer peer) {
        if (peer == null) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, 0);
        TextView instructions = new TextView(this);
        instructions.setText(getString(R.string.pair_adb_manual_detail, peer.host));
        form.addView(instructions);

        EditText ipField = new EditText(this);
        ipField.setSingleLine(true);
        ipField.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        ipField.setHint(R.string.pair_adb_manual_ip_hint);
        ipField.setText(peer.host);
        form.addView(ipField);

        EditText portField = new EditText(this);
        portField.setSingleLine(true);
        portField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portField.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(5)});
        portField.setHint(R.string.pair_adb_manual_pair_port_hint);
        form.addView(portField);

        EditText codeField = new EditText(this);
        codeField.setSingleLine(true);
        codeField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeField.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(6)});
        codeField.setHint(R.string.pair_six_digits_hint);
        form.addView(codeField);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pair_adb_code_title)
                .setView(form)
                .setPositiveButton(R.string.pair_submit_button, (d, which) -> {
                    String host = ipField.getText().toString().trim();
                    if (!isPrivateIpv4(host)) {
                        Toast.makeText(this, R.string.pair_adb_bad_ip,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    int port = parseWirelessAdbPort(portField.getText().toString());
                    if (port <= 0) {
                        Toast.makeText(this, R.string.pair_adb_manual_port_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    pairSystemAdb(host, port, codeField.getText().toString().trim());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static boolean isPrivateIpv4(String host) {
        if (TextUtils.isEmpty(host)) return false;
        String[] octets = host.split("[.]");
        if (octets.length != 4) return false;
        int[] parts = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (octets[i].isEmpty() || octets[i].length() > 3) return false;
                for (int j = 0; j < octets[i].length(); j++) {
                    if (!Character.isDigit(octets[i].charAt(j))) return false;
                }
                parts[i] = Integer.parseInt(octets[i]);
                if (parts[i] > 255) return false;
            }
        } catch (NumberFormatException error) {
            return false;
        }
        return parts[0] == 10
                || parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31
                || parts[0] == 192 && parts[1] == 168;
    }

    private static int parseWirelessAdbPort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private String systemAdbPairFailure(
            org.client.scrcpy.utils.ExecUtil.AdbCommandResult result, String code) {
        if (result.timedOut) return getString(R.string.pair_adb_failure_timeout);
        String output = result.combinedOutput().replace(code, "******").trim();
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unknown command") || lower.contains("unrecognized command")
                || lower.contains("unknown option") || lower.contains("invalid command")) {
            return getString(R.string.pair_adb_failure_unsupported);
        }
        if (lower.contains("authentication failed") || lower.contains("wrong password")
                || lower.contains("pairing code") || lower.contains("incorrect password")
                || lower.contains("failed to pair") || lower.contains("invalid password")) {
            return getString(R.string.pair_adb_failure_code);
        }
        if (lower.contains("unable to connect") || lower.contains("connection refused")
                || lower.contains("no route") || lower.contains("network is unreachable")
                || lower.contains("connection timed out") || lower.contains("cannot connect")) {
            return getString(R.string.pair_adb_failure_network);
        }
        if (output.isEmpty()) output = getString(R.string.pair_adb_failure_empty_output);
        if (output.length() > 240) output = output.substring(0, 240) + "…";
        return getString(R.string.pair_adb_failure_unknown, result.exitCode, output);
    }

    private void pairSystemAdb(String address, int port, String code) {
        if (!code.matches("[0-9]{6}")) {
            Toast.makeText(this, R.string.pair_bad_code, Toast.LENGTH_LONG).show();
            return;
        }
        if (!isPrivateIpv4(address)) {
            Toast.makeText(this, R.string.pair_adb_bad_ip, Toast.LENGTH_LONG).show();
            return;
        }
        if (port < 1 || port > 65535) {
            Toast.makeText(this, R.string.pair_adb_manual_port_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        Progress.showDialog(this, getString(R.string.pair_adb_running));
        ThreadUtils.workPost(() -> {
            org.client.scrcpy.utils.ExecUtil.AdbCommandResult result =
                    AdbHelper.adbPair(this, address, port, code);
            String combined = result.combinedOutput().toLowerCase(java.util.Locale.ROOT);
            boolean paired = result.exitCode == 0 && combined.contains("successfully paired");
            String failure = paired ? "" : systemAdbPairFailure(result, code);
            // A different IP must be authenticated with the existing app pairing
            // secret before it can replace the saved target identity.
            boolean hostVerified = true;
            if (paired) {
                PairedDeviceStore.Peer existing = PairedDeviceStore.load(this);
                if (existing != null && !address.equals(existing.host)) {
                    String wakeFailure = WakeLanClient.request(
                            address + ":" + WakeLanService.PORT, existing.secret);
                    hostVerified = wakeFailure == null;
                }
            }
            final boolean trustedAddress = hostVerified;
            ThreadUtils.post(() -> {
                Progress.closeDialog();
                if (isFinishing() || isDestroyed()) return;
                if (!paired) {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.pair_adb_failure_title)
                            .setMessage(failure)
                            .setPositiveButton(R.string.pair_adb_manual_button,
                                    (d, which) -> {
                                        PairedDeviceStore.Peer peer =
                                                PairedDeviceStore.load(this);
                                        if (peer != null) promptManualSystemAdbPairing(peer);
                                    })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return;
                }
                if (!trustedAddress) {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.pair_adb_success)
                            .setMessage(R.string.pair_adb_success_host_verify_fail)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
                if (peer != null && !address.equals(peer.host)) {
                    PairedDeviceStore.updateAddress(this, address);
                }
                Toast.makeText(this, R.string.pair_adb_success, Toast.LENGTH_LONG).show();
                peer = PairedDeviceStore.load(this);
                if (peer != null && peer.adbPort <= 0) {
                    final PairedDeviceStore.Peer targetPeer = peer;
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.pair_adb_required_title)
                            .setMessage(R.string.pair_adb_pair_next_connect)
                            .setPositiveButton(R.string.pair_adb_connect_manual_button,
                                    (d, which) -> promptManualAdbConnectPort(targetPeer))
                            .setNeutralButton(R.string.pair_adb_find_port,
                                    (d, which) -> startLanAutoDiscovery())
                            .setNegativeButton(android.R.string.cancel, null).show();
                } else {
                    startLanAutoDiscovery();
                }
            });
        });
    }

    /** Connection port is displayed on the wireless debugging main page,
     * and differs from the temporary system pairing port. */
    private void promptManualAdbConnectPort(PairedDeviceStore.Peer peer) {
        if (peer == null) return;
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(5)});
        field.setHint(R.string.pair_adb_connect_manual_hint);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pair_adb_connect_manual_button)
                .setMessage(getString(R.string.pair_adb_connect_manual_detail, peer.host))
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    int port = parseWirelessAdbPort(field.getText().toString());
                    if (port <= 0) {
                        Toast.makeText(this, R.string.pair_adb_manual_port_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    PairedDeviceStore.updatePort(this, port);
                    PairedDeviceStore.Peer updated = PairedDeviceStore.load(this);
                    if (updated == null) return;
                    syncPeerToViewFields(updated);
                    updatePairedDeviceUi();
                    ThreadUtils.workPost(() -> {
                        boolean online = AdbHelper.isDeviceOnline(this, updated.host, port);
                        ThreadUtils.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            applyStatusLed(online);
                            Toast.makeText(this, online
                                            ? R.string.pair_adb_connect_success
                                            : R.string.pair_adb_connect_failed,
                                    Toast.LENGTH_LONG).show();
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void initLanAutoDiscovery() {
        Button search = findViewById(R.id.button_auto_find);
        if (search == null) return;
        search.setOnClickListener(v -> {
            if (!lanDevices.isEmpty() && lanDiscovery != null) {
                showLanDiscoveredDevices();
            } else {
                startLanAutoDiscovery();
            }
        });
    }

    private void startLanAutoDiscovery() {
        stopLanAutoDiscovery();
        lanDevices = new ArrayList<>();
        TextView status = findViewById(R.id.text_auto_find_status);
        if (status != null) status.setText(R.string.auto_find_searching);
        lanDiscovery = new LanDeviceDiscovery(this, new LanDeviceDiscovery.Listener() {
            @Override public void onChanged(List<LanDeviceDiscovery.Device> devices) {
                if (isFinishing() || isDestroyed()) return;
                lanDevices = new ArrayList<>(devices);
                TextView label = findViewById(R.id.text_auto_find_status);
                if (label != null) label.setText(
                        getString(R.string.auto_find_count, lanDevices.size()));
            }
            @Override public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                Log.w("LanDiscovery", message);
            }
        });
        lanDiscovery.start();
        lanSearchHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || lanDiscovery == null) return;
            showLanDiscoveredDevices();
        }, 8500);
    }

    private void showLanDiscoveredDevices() {
        List<LanDeviceDiscovery.Device> matches = new ArrayList<>(lanDevices);
        stopLanAutoDiscovery();
        if (isFinishing() || isDestroyed()) return;
        if (matches.isEmpty()) {
            TextView status = findViewById(R.id.text_auto_find_status);
            if (status != null) status.setText(R.string.auto_find_none);
            Toast.makeText(this, R.string.auto_find_none, Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] labels = new CharSequence[matches.size()];
        for (int i = 0; i < matches.size(); i++) labels[i] = matches.get(i).label();
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.auto_find_choose)
                .setItems(labels, (dialog, selection) -> pairWithChosenDevice(matches.get(selection)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void selectLanDiscoveredDevice(LanDeviceDiscovery.Device device) {
        // A broadcast name/IP is never sufficient to authorize viewing:
        // a matching secret and previously approved ADB key are still required.
        String wakeHost = device.address + ":" + device.wakePort;
        EditText wake = findViewById(R.id.editText_wake_host);
        if (wake != null) wake.setText(wakeHost);
        getSharedPreferences(WakeLanService.PREF, MODE_PRIVATE).edit()
                .putString("viewer_host", wakeHost).apply();
        TextView label = findViewById(R.id.text_auto_find_status);
        if (device.adbPort > 0) {
            String adbAddress = device.address + ":" + device.adbPort;
            EditText adb = findViewById(R.id.editText_server_host);
            if (adb != null) adb.setText(adbAddress);
            PreUtils.put(this, Constant.CONTROL_REMOTE_ADDR, adbAddress);
            if (label != null) label.setText(R.string.auto_find_ready);
        } else {
            if (label != null) label.setText(R.string.auto_find_missing_adb);
            Toast.makeText(this, R.string.auto_find_missing_adb, Toast.LENGTH_LONG).show();
        }
    }

    private void stopLanAutoDiscovery() {
        lanSearchHandler.removeCallbacksAndMessages(null);
        if (lanDiscovery != null) {
            lanDiscovery.stop();
            lanDiscovery = null;
        }
    }

    private void initLanWakeControls() {
        final Button on = findViewById(R.id.button_wake_listen_on);
        final Button off = findViewById(R.id.button_wake_listen_off);
        final EditText hostField = findViewById(R.id.editText_wake_host);
        final EditText keyField = findViewById(R.id.editText_wake_key);
        android.content.SharedPreferences prefs = getSharedPreferences(
                WakeLanService.PREF, MODE_PRIVATE);
        if (hostField != null) hostField.setText(prefs.getString("viewer_host", ""));
        if (keyField != null) keyField.setText(prefs.getString("viewer_code", ""));
        updateLanReceiverCode();
        if (on != null) {
            on.setOnClickListener(v -> {
                try {
                    WakeLanService.startByOwner(this);
                    new Handler(Looper.getMainLooper()).postDelayed(
                            this::updateLanReceiverCode, 900);
                } catch (RuntimeException error) {
                    Log.w("WakeLan", "Could not start owner-enabled receiver", error);
                    Toast.makeText(this, R.string.wake_lan_start_failed,
                            Toast.LENGTH_LONG).show();
                }
            });
        }
        if (off != null) {
            off.setOnClickListener(v -> {
                WakeLanService.stopByOwner(this);
                new Handler(Looper.getMainLooper()).postDelayed(
                        this::updateLanReceiverCode, 400);
            });
        }
    }

    private void updateLanReceiverCode() {
        TextView label = findViewById(R.id.wake_lan_code_label);
        if (label == null) return;
        String code = WakeLanService.currentCode(this);
        if (PairedDeviceStore.load(this) != null) {
            label.setText(R.string.wake_lan_paired);
        } else {
            label.setText(TextUtils.isEmpty(code) ? getString(R.string.wake_lan_off)
                    : getString(R.string.wake_lan_code_prefix, code));
        }
    }

    private static final int WAKE_NOTIFICATION_PERMISSION_REQUEST = 7231;

    private void testWakeReceiverLocally() {
        // User explicitly presses the test button. No background service is
        // launched without an authorized, foreground user action here.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                     != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    WAKE_NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        runLocalWakeTest();
    }

    private void runLocalWakeTest() {
        final long before = WakeRequestService.lastTestReceivedAt(this);
        if (!WakeRequestService.requestLocalTest(this)) {
            Toast.makeText(this, R.string.wake_local_test_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            final boolean received = WakeRequestService.lastTestReceivedAt(this) > before;
            final boolean canNotify = Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED;
            int message = received ? (canNotify ? R.string.wake_local_test_received
                    : R.string.wake_local_test_disabled_notification) : R.string.wake_local_test_failed;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }, 800);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WAKE_NOTIFICATION_PERMISSION_REQUEST) {
            // Receipt can still be verified in the app even if notification
            // permission is declined.
            runLocalWakeTest();
        }
    }

    private void startStatusMonitor() {
        if (headlessMode || isFinishing()) {
            return;
        }
        View led = findViewById(R.id.status_led);
        if (led == null) {
            return;
        }
        statusMonitorActive = true;
        scheduleStatusCheck(0);
    }

    private void stopStatusMonitor() {
        statusMonitorActive = false;
        statusCheckGeneration++;
    }

    private void scheduleStatusCheck(long delayMs) {
        final int gen = ++statusCheckGeneration;
        ThreadUtils.postDelayed(() -> {
            if (!statusMonitorActive || gen != statusCheckGeneration) {
                return;
            }
            EditText hostEdit = findViewById(R.id.editText_server_host);
            final String hostText = hostEdit != null ? hostEdit.getText().toString().trim() : "";
            ThreadUtils.execute(() -> {
                boolean online = false;
                if (!TextUtils.isEmpty(hostText)) {
                    if (UsbShellCompat.isUsbSelection(hostText)) {
                        online = isUsbDeviceOnline();
                    } else {
                        String[] serverInfo = Util.getServerHostAndPort(hostText);
                        try {
                            int port = Integer.parseInt(serverInfo[1]);
                            online = AdbHelper.isDeviceOnline(App.mContext, serverInfo[0], port);
                        } catch (NumberFormatException ignored) {
                            online = false;
                        }
                    }
                }
                final boolean onlineNow = online;
                ThreadUtils.post(() -> {
                    if (!statusMonitorActive || gen != statusCheckGeneration) {
                        return;
                    }
                    applyStatusLed(onlineNow);
                    scheduleStatusCheck(4000);
                });
            });
        }, delayMs);
    }

    /**
     * A USB device is considered online when it is attached, exposes an ADB
     * interface and has been granted USB access permission.
     */
    private boolean isUsbDeviceOnline() {
        if (usbManager == null) {
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        }
        UsbDevice device = UsbAdb.findAdbDevice(usbManager);
        if (device == null || AdbConnection.findAdbInterface(device) == null) {
            return false;
        }
        return usbManager.hasPermission(device);
    }

    /**
     * 进入 ADB 命令行前，彻底停止 scrcpy/USB 会话。
     * <p>
     * 1. 先停止 scrcpy 服务（停止视频流），再关闭 usbAdb：释放 USB 设备
     *    连接与 7008 端口（bridge）。若先关 USB 连接，readerThread 阻塞在
     *    bulkTransfer 时强制中断会导致设备重新枚举，授权丢失；
     * 2. 解绑服务并清除 resumeScrcpy，防止从命令行页面返回后
     *    onResume/onStart 自动恢复远程画面。
     */
    private void cleanupUsbSessionForShell() {
        if (scrcpy != null) {
            try {
                scrcpy.StopService();
            } catch (Exception e) {
                Log.e("Scrcpy", "stop scrcpy failed", e);
            }
            scrcpy = null;
        }
        if (usbAdb != null) {
            try {
                usbAdb.close();
            } catch (Exception e) {
                Log.e("Scrcpy", "close usbAdb failed", e);
            }
            usbAdb = null;
        }
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            // 可能未绑定，忽略
        }
        serviceBound = false;
        resumeScrcpy = false;
    }

    private void applyStatusLed(boolean online) {
        View led = findViewById(R.id.status_led);
        TextView label = findViewById(R.id.status_led_label);
        if (led == null) {
            return;
        }
        int color = getResources().getColor(online ? R.color.status_online : R.color.status_offline);
        Drawable background = led.getBackground();
        if (background instanceof GradientDrawable) {
            ((GradientDrawable) background.mutate()).setColor(color);
        } else if (background != null) {
            background.mutate().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP);
        } else {
            led.setBackgroundColor(color);
        }
        if (label != null) {
            label.setText(online ? R.string.status_online : R.string.status_offline);
            label.setTextColor(color);
        }
        TextView quick = findViewById(R.id.quick_connection_status);
        if (quick != null && !oneTapConnecting) {
            PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
            quick.setText(peer == null ? R.string.quick_status_unpaired
                    : online ? R.string.quick_status_online : R.string.quick_status_paired);
        }
    }

    private String getUsbAdbDeviceLabel() {
        if (usbManager == null) {
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        }
        UsbDevice device = UsbAdb.findAdbDevice(usbManager);
        if (device == null || AdbConnection.findAdbInterface(device) == null) {
            return null;
        }
        return UsbShellCompat.USB_PREFIX + usbDeviceName(device);
    }

    private String usbDeviceName(UsbDevice device) {
        // Prefer the serial number so it matches `adb devices`; fall back to the
        // product name (serial needs USB permission on newer Android versions).
        try {
            if (usbManager.hasPermission(device)) {
                String serial = device.getSerialNumber();
                if (!TextUtils.isEmpty(serial)) {
                    return serial;
                }
            }
        } catch (Exception ignore) {
            // Serial not accessible without permission.
        }
        String product = device.getProductName();
        if (!TextUtils.isEmpty(product)) {
            return product;
        }
        String name = device.getDeviceName();
        return TextUtils.isEmpty(name) ? "device" : name;
    }

    private void connectViaUsb() {
        if (usbManager == null) {
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        }
        UsbDevice device = UsbAdb.findAdbDevice(usbManager);
        if (device == null) {
            Toast.makeText(context, R.string.usb_no_device, Toast.LENGTH_LONG).show();
            return;
        }
        if (AdbConnection.findAdbInterface(device) == null) {
            Toast.makeText(context, R.string.usb_no_adb_interface, Toast.LENGTH_LONG).show();
            return;
        }
        if (usbManager.hasPermission(device)) {
            startUsbConnect(device);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    private void startUsbConnect(UsbDevice device) {
        if (!Progress.isShowing()) {
            Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
        }
        ThreadUtils.workPost(() -> {
            UsbAdb adb = null;
            try {
                // 先释放上一次的 USB 会话（释放 7008 监听端口和 USB 设备），再建立新连接
                if (usbAdb != null) {
                    usbAdb.close();
                    usbAdb = null;
                }
                // 移除 adb server 中的端口转发（tcp:7008 → tcp:7007）。
                // WiFi（IP）连接时会在 adb server 内创建该 forward 并持续监听 7008，
                // 若不移除，USB 连接的 AdbForwardBridge 绑定 7008 会报
                // "bind failed: address already in use"。
                try {
                    AdbHelper.adbCmd(App.mContext, "forward", "--remove-all");
                } catch (Exception ignored) {
                }
                AdbHelper.writeAssetsJarServer(App.mContext);
                File jar = AdbHelper.getServerJarFile(context);
                adb = UsbAdb.connect(context, usbManager, device);
                adb.startServerAndForward(jar, Scrcpy.LOCAL_FORWART_PORT, Scrcpy.LOCAL_IP,
                        videoBitrate, Math.max(screenHeight, screenWidth),
                        PreUtils.get(context, Constant.AUDIO_FORWARD, true));
                usbAdb = adb;
                adb = null;  // 所有权已转移给 usbAdb，防止 catch 误关
                ThreadUtils.post(() -> {
                    if (!MainActivity.this.isFinishing()) {
                        Log.e("Scrcpy: ", "from USB connect");
                        start_screen_copy_magic();
                    } else {
                        // activity 已销毁，立即释放连接，避免监听端口泄漏
                        if (usbAdb != null) {
                            usbAdb.close();
                            usbAdb = null;
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("Scrcpy", "USB connect failed", e);
                final String message = e.getMessage();
                // 失败时释放已建立的 bridge/connection，防止 7008 端口被占用
                if (adb != null) {
                    adb.close();
                }
                ThreadUtils.post(() -> {
                    Progress.closeDialog();
                    Toast.makeText(context,
                            getString(R.string.usb_connect_failed) + " " + message,
                            Toast.LENGTH_LONG).show();
                    connectExitExt();
                });
            }
        });
    }

    /**
     * 连接成功了，而且成功的显示了画面出来
     */
    protected void connectSuccessExt() {
        Dialog.closeDialogs();
        if (autoResolutionEnabled && resolutionLimitSource != null) {
            Toast.makeText(context, formatActiveStreamResolutionMessage(), Toast.LENGTH_LONG).show();
        }
    }

    protected void connectExitExt() {
        this.connectExitExt(false);
    }

    /**
     * 连接失败的额外处理
     */
    protected void connectExitExt(boolean userDisconnect) {
        if (!userDisconnect) {  // userDisconnect : 用户主动断开连接
            // 如果自动断开了端口连接，在系统恢复时，重启adb，避免
            // 警告！！！ 重启将会导致 adb 配对过程失效，从而无法连接新设备，需要更智能的重启机制
            // AdbHelper.restartAdb();
        }
        // 移除 adb server 中的端口转发（WiFi 连接创建的 tcp:7008 → tcp:7007），
        // 否则该 forward 会持续占用 7008 端口，之后用 USB 连接会报
        // "bind failed: address already in use"
        try {
            AdbHelper.adbCmd(App.mContext, "forward", "--remove-all");
        } catch (Exception ignored) {
        }
        if (headlessMode && !resumeScrcpy && !result_of_Rotation) {
            if (!userDisconnect) {
                Dialog.displayDialog(this, getString(R.string.connect_faild),
                        getString(R.string.connect_faild_ask), () -> {
                            // 重试连接
                            connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
                        }, () -> {
                            // 取消重试
                            finishAndRemoveTask();
                        });
            } else {
                finishAndRemoveTask();
            }
        }
//        Log.i("Scrcpy", "headlessMode： " + headlessMode +
//                " ,resumeScrcpy: " + resumeScrcpy + " ,result_of_Rotation: " + result_of_Rotation);
    }

}
