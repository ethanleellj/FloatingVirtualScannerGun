package com.virtualscanner.gun;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 100;
    private static final int REQ_NOTIF = 101;
    private static final int REQ_BT = 102;

    private TextView tvOverlay;
    private TextView tvCamera;
    private TextView tvA11y;
    private TextView tvA11yHint;
    private TextView tvShizuku;
    private TextView tvPreview;
    private TextView tvResult;
    private Button btnToggle;
    private Button btnLanguage;
    private Button btnProfiles;
    private EditText etManual;
    private EditText etTestText;
    private EditText etNetHost;
    private EditText etNetPort;
    private EditText etHidMac;
    private View llNetSection;
    private View llHidSection;
    private EditText etPrefix;
    private EditText etSuffix;
    private CheckBox cbEnter;
    private CheckBox cbBeep;
    private CheckBox cbAppend;
    private CheckBox cbIgnoreDup;
    private Spinner spCamera;
    private Spinner spChannel;
    private Spinner spModal;
    private Spinner spMultiCode;
    private Spinner spCodeType;

    private final List<Cameras.Choice> cameras = new ArrayList<>();
    /** 程序化回填选中项时抑制回调，避免把回填当成用户选择。 */
    private boolean suppressCameraCallback;
    private boolean suppressChannelCallback;
    private boolean suppressModalCallback;
    private boolean suppressMultiCodeCallback;
    private boolean suppressCodeTypeCallback;
    /** 回填配置档内容期间不触发保存，否则会把旧档的值写到新档上。 */
    private boolean loadingProfile;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> runOnUiThread(this::refreshStatus);
    private final Shizuku.OnBinderDeadListener binderDeadListener =
            () -> runOnUiThread(this::refreshStatus);
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> runOnUiThread(this::refreshStatus);

    // 服务可能被悬浮球/通知栏直接退出，这里跟随状态刷新"启动/停止"按钮
    private final OverlayService.RunningListener runningListener =
            isRunning -> runOnUiThread(this::refreshStatus);

    // 悬浮窗里换镜头时，同步主界面下拉框
    private final Runnable cameraChangedListener = () -> runOnUiThread(this::reloadCameras);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.edgeToEdge(this);
        Profiles.ensureInit(this);

        tvOverlay = findViewById(R.id.tvOverlay);
        tvCamera = findViewById(R.id.tvCamera);
        tvA11y = findViewById(R.id.tvA11y);
        tvA11yHint = findViewById(R.id.tvA11yHint);
        tvShizuku = findViewById(R.id.tvShizuku);
        tvPreview = findViewById(R.id.tvPreview);
        tvResult = findViewById(R.id.tvResult);
        btnToggle = findViewById(R.id.btnToggleService);
        btnLanguage = findViewById(R.id.btnLanguage);
        btnProfiles = findViewById(R.id.btnProfiles);
        etManual = findViewById(R.id.etManual);
        etTestText = findViewById(R.id.etTestText);
        etNetHost = findViewById(R.id.etNetHost);
        etNetPort = findViewById(R.id.etNetPort);
        etHidMac = findViewById(R.id.etHidMac);
        llNetSection = findViewById(R.id.llNetSection);
        llHidSection = findViewById(R.id.llHidSection);
        etPrefix = findViewById(R.id.etPrefix);
        etSuffix = findViewById(R.id.etSuffix);
        cbEnter = findViewById(R.id.cbEnter);
        cbBeep = findViewById(R.id.cbBeep);
        cbAppend = findViewById(R.id.cbAppend);
        cbIgnoreDup = findViewById(R.id.cbIgnoreDup);
        spCamera = findViewById(R.id.spCamera);
        spChannel = findViewById(R.id.spChannel);
        spModal = findViewById(R.id.spModal);
        spMultiCode = findViewById(R.id.spMultiCode);
        spCodeType = findViewById(R.id.spCodeType);

        findViewById(R.id.btnOverlayPerm).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        findViewById(R.id.btnCameraPerm).setOnClickListener(v -> ensureCameraPermission());
        findViewById(R.id.btnA11yPerm).setOnClickListener(v -> A11y.openSettings(this));

        findViewById(R.id.btnShizukuPerm).setOnClickListener(v -> {
            if (!Injector.isBinderAlive()) {
                toast(getString(R.string.toast_shizuku_not_running));
                return;
            }
            Injector.requestPermission();
            toast(getString(R.string.toast_auth_requested));
        });

        findViewById(R.id.btnSelfTest).setOnClickListener(v ->
                injectAsync(Prefs.testText(this), true));

        btnToggle.setOnClickListener(v -> toggleService());
        btnLanguage.setOnClickListener(v -> showLanguageDialog());

        btnProfiles.setOnClickListener(v ->
                startActivity(new Intent(this, ProfilesActivity.class)));
        findViewById(R.id.btnSnippets).setOnClickListener(v ->
                startActivity(new Intent(this, SnippetsActivity.class)));
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.btnHelp).setOnClickListener(v ->
                startActivity(new Intent(this, HelpActivity.class)));
        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));

        setupCameraSpinner();
        setupChannelSpinner();
        setupModalSpinner();
        setupMultiCodeSpinner();
        setupCodeTypeSpinner();
        setupNetFields();
        setupHidFields();

        findViewById(R.id.btnManualSend).setOnClickListener(v -> sendManual(false));
        findViewById(R.id.btnManualSendEnter).setOnClickListener(v -> sendManual(true));

        TextWatcher configWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                saveProfileFields();
                refreshPreview();
            }
        };
        etPrefix.addTextChangedListener(configWatcher);
        etSuffix.addTextChangedListener(configWatcher);

        // 「自检 / Test」按钮发什么：这里改，保存后主界面的自检和悬浮面板的 Test 都跟着变。
        // 允许清空——按钮读的时候会回落到出厂值，不会出现"点了没反应"
        etTestText.setText(Prefs.testText(this));
        etTestText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // 注入器写进来的内容不算用户改的：前台是本应用时（比如边调设置边扫码），
                // 扫到的码会落进这个框，存下来就会污染自检内容，下次再套一层前后缀。
                Prefs.setTestText(MainActivity.this, s.toString());
                refreshPreview();
            }
        });
        cbEnter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveProfileFields();
            refreshPreview();
        });
        cbAppend.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setAppendMode(this, isChecked));
        cbIgnoreDup.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setIgnoreDup(this, isChecked));
        // 提示音是全局设置，不跟配置档走
        cbBeep.setChecked(Prefs.soundEnabled(this));
        cbBeep.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setSoundEnabled(this, isChecked));

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        OverlayService.setRunningListener(runningListener);
        OverlayService.setCameraListener(cameraChangedListener);
        loadProfileIntoUi();
        refreshStatus();
        reloadCameras();
    }

    @Override
    protected void onPause() {
        super.onPause();
        OverlayService.setRunningListener(null);
        OverlayService.setCameraListener(null);
        saveProfileFields();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    // ---------------- 状态 ----------------

    private String grantState(boolean granted) {
        return getString(granted ? R.string.state_granted : R.string.state_denied);
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        tvOverlay.setText(getString(R.string.label_overlay_perm, grantState(overlay)));
        tvOverlay.setTextColor(overlay ? 0xFF1B7F3B : 0xFFB3261E);

        tvCamera.setText(getString(R.string.label_camera_perm, grantState(camera)));
        tvCamera.setTextColor(camera ? 0xFF1B7F3B : 0xFFB3261E);

        boolean a11yEnabled = A11y.isEnabled(this);
        boolean a11yReady = A11y.isConnected();
        String a11yState = a11yReady ? getString(R.string.a11y_state_connected)
                : a11yEnabled ? getString(R.string.a11y_state_enabled)
                : getString(R.string.state_denied);
        tvA11y.setText(getString(R.string.label_a11y, a11yState));
        tvA11y.setTextColor(a11yReady ? 0xFF1B7F3B : 0xFFB3261E);
        // 没开才提示"开哪一个"；开了就不占地方
        tvA11yHint.setVisibility(a11yEnabled ? View.GONE : View.VISIBLE);

        String shizuku = Injector.statusText(this);
        boolean ready = Injector.hasPermission();
        tvShizuku.setText(getString(R.string.label_shizuku, shizuku));
        tvShizuku.setTextColor(ready ? 0xFF1B7F3B : 0xFFB3261E);

        btnToggle.setText(OverlayService.isRunning()
                ? R.string.btn_stop_overlay : R.string.btn_start_overlay);

        btnProfiles.setText(getString(R.string.btn_profiles,
                Profiles.displayName(this, Profiles.current(this))));
        refreshLanguageButton();
    }

    // ---------------- 配置档 ----------------

    private void loadProfileIntoUi() {
        Profile p = Profiles.current(this);
        loadingProfile = true;
        etPrefix.setText(p.prefix);
        etSuffix.setText(p.suffix);
        cbEnter.setChecked(p.enter);
        cbAppend.setChecked(p.appendMode);
        cbIgnoreDup.setChecked(p.ignoreDup);

        suppressChannelCallback = true;
        spChannel.setSelection(channelIndex(p.channel));
        spChannel.post(() -> suppressChannelCallback = false);

        refreshChannelSettings();

        suppressModalCallback = true;
        spModal.setSelection(p.modalPolicy);
        spModal.post(() -> suppressModalCallback = false);

        suppressMultiCodeCallback = true;
        spMultiCode.setSelection(multiCodeIndex(Prefs.multiCodePolicy(this)));
        spMultiCode.post(() -> suppressMultiCodeCallback = false);

        suppressCodeTypeCallback = true;
        spCodeType.setSelection(codeTypeIndex(Prefs.codeType(this)));
        spCodeType.post(() -> suppressCodeTypeCallback = false);

        loadingProfile = false;
        btnProfiles.setText(getString(R.string.btn_profiles,
                Profiles.displayName(this, p)));
        refreshPreview();
    }

    private int channelIndex(int channel) {
        switch (channel) {
            case Profile.CHANNEL_A11Y:
                return 1;
            case Profile.CHANNEL_SHIZUKU:
                return 2;
            case Profile.CHANNEL_CLIPBOARD:
                return 3;
            case Profile.CHANNEL_NET:
                return 4;
            case Profile.CHANNEL_HID:
                return 5;
            default:
                return 0;
        }
    }

    private int channelValue(int index) {
        switch (index) {
            case 1:
                return Profile.CHANNEL_A11Y;
            case 2:
                return Profile.CHANNEL_SHIZUKU;
            case 3:
                return Profile.CHANNEL_CLIPBOARD;
            case 4:
                return Profile.CHANNEL_NET;
            case 5:
                return Profile.CHANNEL_HID;
            default:
                return Profile.CHANNEL_AUTO;
        }
    }

    /**
     * 底部两个全局设置区块（网络转发 / 蓝牙键盘）只在该通道被选中时显示，
     * 其余通道用不到，收起来避免主界面越拉越长。
     */
    private void refreshChannelSettings() {
        if (llNetSection == null || llHidSection == null) {
            return;
        }
        Profile p = Profiles.current(this);
        llNetSection.setVisibility(p.channel == Profile.CHANNEL_NET
                ? View.VISIBLE : View.GONE);
        llHidSection.setVisibility(p.channel == Profile.CHANNEL_HID
                ? View.VISIBLE : View.GONE);
    }

    /** 网络转发的目标地址：全局设置，跟着手机走而不是跟着配置档走。 */
    private void setupNetFields() {        etNetHost.setText(Prefs.netHost(this));
        etNetPort.setText(String.valueOf(Prefs.netPort(this)));
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int port;
                try {
                    port = Integer.parseInt(etNetPort.getText().toString().trim());
                } catch (NumberFormatException e) {
                    // 还没填完端口：先存 0，注入时会明确提示"没配好"，而不是乱连一个端口
                    port = 0;
                }
                Prefs.setNet(MainActivity.this, etNetHost.getText().toString(), port);
            }
        };
        etNetHost.addTextChangedListener(watcher);
        etNetPort.addTextChangedListener(watcher);
    }

    /** 蓝牙键盘的目标电脑：和网络转发一样是"跟着手机走"的全局设置。 */
    private void setupHidFields() {
        etHidMac.setText(Prefs.hidMac(this));
        etHidMac.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Prefs.setHidMac(MainActivity.this, etHidMac.getText().toString());
            }
        });
        findViewById(R.id.btnHidPick).setOnClickListener(v -> pickHidHost());
    }

    /**
     * 从已配对设备里挑一台。
     *
     * <p>只能挑"已配对"的：手机是 HID Device，配对是电脑发起的（电脑去发现手机的键盘服务），
     * App 自己没法替用户完成配对，硬做只会让电脑端反复弹配对请求。
     */
    private void pickHidHost() {
        if (!btPermissionGranted()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        showBondedDevices();
    }

    /** 权限没给就直接要，不弹任何前置对话框——系统弹窗本身就是最好的说明。 */
    private void ensureBtPermission() {
        if (!btPermissionGranted()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    private boolean btPermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showBondedDevices() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        Set<BluetoothDevice> bonded = null;
        if (adapter != null && adapter.isEnabled()) {
            try {
                bonded = adapter.getBondedDevices();
            } catch (SecurityException ignored) {
                // 权限刚被撤销：按"没有已配对设备"处理，提示去系统设置配对
            }
        }
        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.hid_pick_title)
                    .setMessage(R.string.hid_pick_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String name = d.getName();
            if (name == null || name.trim().isEmpty()) {
                name = getString(R.string.hid_pick_unnamed);
            }
            items[i] = name + "\n" + d.getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.hid_pick_title)
                .setItems(items, (dialog, which) -> {
                    String mac = devices.get(which).getAddress();
                    etHidMac.setText(mac);
                    Prefs.setHidMac(MainActivity.this, mac);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveProfileFields() {
        if (loadingProfile) {
            return;
        }
        Prefs.updateCurrent(this, etPrefix.getText().toString(),
                etSuffix.getText().toString(), cbEnter.isChecked(), 0);
    }

    private void refreshPreview() {
        if (tvPreview == null) {
            return;
        }
        Profile p = Profiles.current(this).copy();
        p.prefix = etPrefix.getText().toString();
        p.suffix = etSuffix.getText().toString();
        p.enter = cbEnter.isChecked();
        // 用"自检按钮要发的内容"做样例，预览行就等于自检真正发出去的报文
        tvPreview.setText(getString(R.string.payload_preview,
                Payload.of(p, Prefs.testText(this)).preview()));
    }

    // ---------------- 通道 / 弹窗策略 ----------------

    private void setupChannelSpinner() {
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.channel_auto));
        labels.add(getString(R.string.channel_a11y));
        labels.add(getString(R.string.channel_shizuku));
        labels.add(getString(R.string.channel_clipboard));
        labels.add(getString(R.string.channel_net));
        labels.add(getString(R.string.channel_hid));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spChannel.setAdapter(adapter);
        spChannel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressChannelCallback || loadingProfile) {
                    return;
                }
                int value = channelValue(position);
                Prefs.setChannel(MainActivity.this, value);
                refreshChannelSettings();
                if (value == Profile.CHANNEL_HID) {
                    // 选了蓝牙键盘就顺手要权限：等真扫到东西再弹权限框，人已经走开了
                    ensureBtPermission();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupModalSpinner() {
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.modal_block));
        labels.add(getString(R.string.modal_auto_close));
        labels.add(getString(R.string.modal_wait));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spModal.setAdapter(adapter);
        spModal.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressModalCallback || loadingProfile) {
                    return;
                }
                Prefs.setModalPolicy(MainActivity.this, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 码制下拉的展示顺序：默认"全部"排第一，与存储值 0/1/2 一一对应。 */
    private static final int[] CODE_TYPE_ORDER = {
            CodeTypes.ALL,
            CodeTypes.LINEAR,
            CodeTypes.MATRIX,
    };

    /**
     * 下拉框的展示顺序。策略值刻意不复用下标（序号/末位策略是后加的 4..7），
     * 这里显式列出，避免以后再加策略时把老设置解释错。
     */
    private static final int[] MULTI_CODE_ORDER = {
            BarcodePick.POLICY_FIRST,
            BarcodePick.POLICY_SECOND,
            BarcodePick.POLICY_THIRD,
            BarcodePick.POLICY_FOURTH,
            BarcodePick.POLICY_LAST,
            BarcodePick.POLICY_LARGEST,
            BarcodePick.POLICY_CENTER,
            BarcodePick.POLICY_SKIP,
            BarcodePick.POLICY_ALL,
    };

    private int multiCodeLabel(int policy) {
        switch (policy) {
            case BarcodePick.POLICY_SECOND:
                return R.string.multi_code_second;
            case BarcodePick.POLICY_THIRD:
                return R.string.multi_code_third;
            case BarcodePick.POLICY_FOURTH:
                return R.string.multi_code_fourth;
            case BarcodePick.POLICY_LAST:
                return R.string.multi_code_last;
            case BarcodePick.POLICY_LARGEST:
                return R.string.multi_code_largest;
            case BarcodePick.POLICY_CENTER:
                return R.string.multi_code_center;
            case BarcodePick.POLICY_SKIP:
                return R.string.multi_code_skip;
            case BarcodePick.POLICY_ALL:
                return R.string.multi_code_all;
            default:
                return R.string.multi_code_first;
        }
    }

    private int multiCodeIndex(int policy) {
        for (int i = 0; i < MULTI_CODE_ORDER.length; i++) {
            if (MULTI_CODE_ORDER[i] == policy) {
                return i;
            }
        }
        return 0;
    }

    /** 一帧出现多个条码时的采信策略；全局设置，不跟配置档走。 */
    private void setupMultiCodeSpinner() {
        List<String> labels = new ArrayList<>();
        for (int policy : MULTI_CODE_ORDER) {
            labels.add(getString(multiCodeLabel(policy)));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMultiCode.setAdapter(adapter);
        spMultiCode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressMultiCodeCallback) {
                    return;
                }
                int policy = MULTI_CODE_ORDER[position];
                Prefs.setMultiCodePolicy(MainActivity.this, policy);
                if (policy == BarcodePick.POLICY_ALL) {
                    // 这是个"模式"而不是"取哪一个"：实时帧会停止注入，得让用户知道去哪儿拍
                    Toast.makeText(MainActivity.this, R.string.multi_code_all_toast,
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 采信哪类码；全局设置，与配置档无关，改完立刻对实时扫码和图库识别生效。 */
    private void setupCodeTypeSpinner() {
        List<String> labels = new ArrayList<>();
        for (int type : CODE_TYPE_ORDER) {
            labels.add(getString(codeTypeLabel(type)));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCodeType.setAdapter(adapter);
        spCodeType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressCodeTypeCallback) {
                    return;
                }
                Prefs.setCodeType(MainActivity.this, CODE_TYPE_ORDER[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private int codeTypeIndex(int type) {
        for (int i = 0; i < CODE_TYPE_ORDER.length; i++) {
            if (CODE_TYPE_ORDER[i] == type) {
                return i;
            }
        }
        return 0;
    }

    private int codeTypeLabel(int type) {
        switch (CodeTypes.normalize(type)) {
            case CodeTypes.LINEAR:
                return R.string.code_type_linear;
            case CodeTypes.MATRIX:
                return R.string.code_type_matrix;
            default:
                return R.string.code_type_all;
        }
    }

    // ---------------- 摄像头选择 ----------------
    private void setupCameraSpinner() {
        spCamera.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressCameraCallback || position >= cameras.size()) {
                    return;
                }
                String chosen = cameras.get(position).id;
                if (chosen.equals(Prefs.cameraId(MainActivity.this))) {
                    return;
                }
                Prefs.setCameraId(MainActivity.this, chosen);
                restartServiceIfRunning();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        reloadCameras();
    }

    /** 重新枚举并同步选中项。悬浮窗里的「镜头」按钮也会改配置，回来时要对上。 */
    private void reloadCameras() {
        cameras.clear();
        cameras.addAll(Cameras.list(this));

        List<String> labels = new ArrayList<>();
        for (Cameras.Choice c : cameras) {
            labels.add(c.label);
        }
        if (labels.isEmpty()) {
            labels.add(getString(R.string.camera_none));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCamera.setAdapter(adapter);

        Cameras.Choice current = Cameras.findSelected(this, cameras);
        if (current != null) {
            suppressCameraCallback = true;
            spCamera.setSelection(cameras.indexOf(current));
            spCamera.post(() -> suppressCameraCallback = false);
        }
    }

    /** 悬浮窗正在运行时切换镜头，需要重启服务才会生效。 */
    private void restartServiceIfRunning() {
        if (!OverlayService.isRunning()) {
            return;
        }
        Intent stop = new Intent(this, OverlayService.class);
        stop.setAction(OverlayService.ACTION_STOP);
        startService(stop);

        btnToggle.postDelayed(() -> {
            ContextCompat.startForegroundService(this, new Intent(this, OverlayService.class));
            btnToggle.postDelayed(this::refreshStatus, 400);
            toast(getString(R.string.camera_restarted));
        }, 700);
    }

    // ---------------- 语言 ----------------

    private void refreshLanguageButton() {
        int index = currentLanguageIndex();
        String name;
        switch (index) {
            case 1:
                name = getString(R.string.lang_zh_hans);
                break;
            case 2:
                name = getString(R.string.lang_zh_hant);
                break;
            case 3:
                name = getString(R.string.lang_en);
                break;
            default:
                name = getString(R.string.lang_system);
                break;
        }
        btnLanguage.setText(getString(R.string.btn_language, name));
    }

    private int currentLanguageIndex() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) {
            return 0;
        }
        String tag = locales.get(0).toLanguageTag();
        if (tag.startsWith("zh")) {
            return tag.contains("Hant") || tag.contains("TW")
                    || tag.contains("HK") || tag.contains("MO") ? 2 : 1;
        }
        return 3;
    }

    private void showLanguageDialog() {
        final String[] items = {
                getString(R.string.lang_system),
                getString(R.string.lang_zh_hans),
                getString(R.string.lang_zh_hant),
                getString(R.string.lang_en),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.section_language)
                .setSingleChoiceItems(items, currentLanguageIndex(), (dialog, which) -> {
                    dialog.dismiss();
                    applyLanguage(which);
                })
                .show();
    }

    private void applyLanguage(int index) {
        LocaleListCompat locales;
        switch (index) {
            case 1:
                locales = LocaleListCompat.forLanguageTags("zh-Hans");
                break;
            case 2:
                locales = LocaleListCompat.forLanguageTags("zh-Hant");
                break;
            case 3:
                locales = LocaleListCompat.forLanguageTags("en");
                break;
            default:
                locales = LocaleListCompat.getEmptyLocaleList();
                break;
        }
        // AppCompat 会持久化并在切换后自动重建 Activity
        AppCompatDelegate.setApplicationLocales(locales);
    }

    // ---------------- 动作 ----------------

    private void ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.toast_camera_granted));
            return;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private void toggleService() {
        if (OverlayService.isRunning()) {
            Intent intent = new Intent(this, OverlayService.class);
            intent.setAction(OverlayService.ACTION_STOP);
            startService(intent);
            toast(getString(R.string.toast_stop_requested));
            // 服务的 onDestroy 是异步的，稍后再刷新按钮状态
            btnToggle.postDelayed(this::refreshStatus, 400);
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_need_overlay));
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.toast_need_camera));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        Intent intent = new Intent(this, OverlayService.class);
        ContextCompat.startForegroundService(this, intent);
        toast(getString(R.string.toast_overlay_started));
        btnToggle.postDelayed(this::refreshStatus, 400);
    }

    private void sendManual(boolean enter) {
        String text = etManual.getText().toString();
        if (text.isEmpty()) {
            toast(getString(R.string.toast_need_content));
            return;
        }
        injectAsync(text, enter);
    }

    private void injectAsync(String text, boolean enter) {
        tvResult.setText(getString(R.string.result_sending, text));
        final Profile profile = Profiles.current(this);
        new Thread(() -> {
            InjectRouter.Result result = InjectRouter.send(this, profile, text, enter);
            ScanHistory.get(this).add(text, "", result.ok, result.channel,
                    A11y.foregroundPackage());
            runOnUiThread(() -> tvResult.setText(getString(R.string.result_line,
                    result.ok ? "✅" : "❌", result.describe(this), text)));
        }, "vsg-manual-inject").start();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            // 刚拿到（或刚被拒绝）蓝牙权限：拿到了就直接把设备列表弹出来
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showBondedDevices();
            }
        }
        refreshStatus();
    }
}
