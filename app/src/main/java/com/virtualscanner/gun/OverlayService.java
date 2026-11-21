package com.virtualscanner.gun;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 悬浮窗 + 相机扫码 + Shizuku 注入。
 *
 * 窗口一律 FLAG_NOT_FOCUSABLE，保证焦点始终留在目标 App 上，
 * 否则按键会打到我们自己的窗口里。
 */
public class OverlayService extends Service implements LifecycleOwner {

    public static final String ACTION_STOP = "com.virtualscanner.gun.STOP";

    /** 长按悬浮球的判定阈值（毫秒）。手指抬起时若未移动且超过此时长即视为长按。 */
    private static final long LONG_PRESS_MS = 600;

    /** 整页扫描一次最多录入多少条，见 {@link BarcodePick#MAX_ALL}。 */
    private static final int BATCH_MAX = BarcodePick.MAX_ALL;
    /** 整页扫描时两条之间的间隔：目标 App 处理完一条再收下一条，避免粘成一串。 */
    private static final long BATCH_GAP_MS = 150L;
    /** 照片识别前把最长边压到这个尺寸，与图库识别保持一致。 */
    private static final int PHOTO_MAX_EDGE = 2000;
    /** 自动折叠前留出的时间：让状态行上的成功结果（以及提示音）先被看见 / 听见。 */
    private static final long AUTO_COLLAPSE_DELAY_MS = 500L;

    /** 服务是否在运行。主界面用它显示"启动/停止"，避免按钮状态与实际不一致。 */
    private static volatile boolean running = false;

    /** 运行状态变化的回调（主界面注册），用于服务被外部结束时同步按钮文案。 */
    public interface RunningListener {
        void onRunningChanged(boolean running);
    }

    private static volatile RunningListener runningListener;

    /** 当前实例：图库识别完成后由 {@link PickImageActivity} 回送结果用（同进程、无界面跳转）。 */
    private static volatile OverlayService instance;

    public static boolean isRunning() {
        return running;
    }

    /**
     * 图库识别出结果后调用（单条）：先等目标 App 重新拿到焦点，再走与扫码完全相同的注入链路。
     *
     * <p>不能立刻注入——此刻我们自己那个透明的 {@link PickImageActivity} 还在前台，
     * 无障碍会把文字写进它（等于丢弃），Shizuku 通道则可能打进正在退场的输入法。
     */
    public static void deliverFromImage(String value) {
        deliverFromImage(java.util.Collections.singletonList(value));
    }

    /**
     * 一批结果（整页扫描 / 图库整页识别）交回服务：等目标 App 拿到焦点后逐条注入。
     *
     * <p>批量路径同样要先等焦点——只是它等的是"选图时离开的那个 App"，不是我们自己。
     */
    public static void deliverFromImage(java.util.List<String> values) {
        OverlayService s = instance;
        if (s == null || values == null || values.isEmpty()) {
            return;
        }
        final java.util.List<String> batch = new java.util.ArrayList<>(values);
        s.main.post(() -> s.awaitTargetThenInject(batch));
    }

    public static void setRunningListener(RunningListener listener) {
        runningListener = listener;
    }

    /** 悬浮窗内切换镜头后通知主界面刷新下拉框（Activity 在前台时不会走 onResume）。 */
    private static volatile Runnable cameraListener;

    public static void setCameraListener(Runnable listener) {
        cameraListener = listener;
    }

    private static void publishRunning(boolean value) {
        running = value;
        RunningListener l = runningListener;
        if (l != null) {
            l.onRunningChanged(value);
        }
    }

    private static final String CHANNEL_ID = "vsg_overlay";
    private static final int NOTIF_ID = 1001;

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private View bubbleView;
    private View panelView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;

    private PreviewView previewView;
    private TextView tvStatus;

    private ProcessCameraProvider cameraProvider;
    private BarcodeScanner scanner;
    /** 整页扫描用：拍全分辨率照片，比 1280×720 的分析流清晰得多。绑定失败时为空。 */
    private ImageCapture imageCapture;
    private boolean capturing;
    private ExecutorService analysisExecutor;
    /** 注入串行执行：Shizuku 通道一次约 280 ms，而分析帧每 100 ms 就来一发，并发的
     *  {@code input text} 会互相穿插，把内容打成乱码。 */
    private ExecutorService injectExecutor;
    private boolean cameraBound;

    private final ScanGuard guard = new ScanGuard();

    /** 相机重新绑定成功后要显示一次的提示（切换镜头用）。 */
    private String bindNote;

    /** 点「图片」时记下当时的前台包名，识别完要等它重新拿到焦点再注入。 */
    private String galleryReturnPkg;
    /** 码制过滤日志的节流时间戳：镜头一直对着 QR 时不能每帧刷一条。 */
    private long lastTypeLogAt;
    /** 整页扫描模式下状态行的刷新节流（画面里没码时不必每帧重画）。 */
    private long lastAllModeAt;
    /** 状态行"保持到"的时刻：整页模式每 500 ms 会刷新计数，不能把刚弹出的结果盖掉。 */
    private long statusHoldUntil;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        publishRunning(true);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        analysisExecutor = Executors.newSingleThreadExecutor();
        injectExecutor = Executors.newSingleThreadExecutor();
        scanner = BarcodeScanning.getClient();

        startAsForeground();
        guard.setListener((event, detail) -> Log.i("VSG-Guard", event + " " + detail));
        showBubble();
    }

    /**
     * 让悬浮窗的文案跟随应用语言，而不是系统语言。
     *
     * <p>应用级 locale 由 {@code AppCompatDelegate.setApplicationLocales} 设置，
     * 对 Activity 自动生效，但 Service 拿到的 base context 仍是系统 locale
     * （本机系统是 en-US，所以面板显示英文，主界面却是中文）。这里用应用级
     * locale 包一个 context，inflate 和 getString 都从它取。
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(localized(base));
    }

    private static Context localized(Context base) {
        try {
            LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
            if (locales.isEmpty()) {
                return base;
            }
            Configuration config = new Configuration(base.getResources().getConfiguration());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(new android.os.LocaleList(locales.get(0)));
            } else {
                config.locale = locales.get(0);
            }
            return base.createConfigurationContext(config);
        } catch (Throwable t) {
            return base;
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    // ---------------- 前台通知 ----------------

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }

        Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .addAction(0, getString(R.string.notification_stop), stopPi)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    // ---------------- 悬浮球 ----------------

    private void showBubble() {
        if (bubbleView != null) {
            return;
        }
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
        bubbleParams = baseParams();
        // WindowManager 用 WRAP_CONTENT 时不会理会布局里写死的 56dp，
        // 会把气泡量成"文字大小"。这里必须显式给出窗口尺寸。
        bubbleParams.width = dp(56);
        bubbleParams.height = dp(56);
        // 沿用上次退出时的位置；越界时夹回屏幕内并写回，避免下次启动仍然越界
        bubbleParams.x = Position.clamp(Prefs.floatX(this), screenWidthPx(), bubbleParams.width);
        bubbleParams.y = Position.clamp(Prefs.floatY(this), screenHeightPx(), bubbleParams.height);
        saveCurrentPosition();

        bubbleView.setOnTouchListener(new DragTouchListener(bubbleView, bubbleParams) {
            @Override
            void onClick() {
                expand();
            }

            @Override
            void onLongClick() {
                // 收起状态下唯一能触达的操作入口：长按悬浮球 = 退出
                confirmExit();
            }

            @Override
            void onPositionChanged(int x, int y) {
                saveCurrentPosition();
            }
        });

        try {
            wm.addView(bubbleView, bubbleParams);
        } catch (Throwable t) {
            bubbleView = null;
        }
    }

    private void hideBubble() {
        if (bubbleView == null) {
            return;
        }
        try {
            wm.removeView(bubbleView);
        } catch (Throwable ignored) {
        }
        bubbleView = null;
    }

    // ---------------- 面板 ----------------

    private void expand() {
        if (panelView != null) {
            return;
        }
        hideBubble();

        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        previewView = panelView.findViewById(R.id.preview);
        tvStatus = panelView.findViewById(R.id.tvStatus);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        panelParams = baseParams();
        // 面板比悬浮球大，具体边界在 fitPreviewToScreen() 里按实测尺寸收敛
        panelParams.x = Prefs.floatX(this);
        panelParams.y = Prefs.floatY(this);

        View dragBar = panelView.findViewById(R.id.dragBar);
        dragBar.setOnTouchListener(new DragTouchListener(panelView, panelParams) {
            @Override
            void onClick() {
            }

            @Override
            void onPositionChanged(int x, int y) {
                saveCurrentPosition();
            }
        });

        panelView.findViewById(R.id.btnCollapse).setOnClickListener(v -> collapse());

        // 自检：悬浮窗保持 FLAG_NOT_FOCUSABLE，焦点仍在目标 App 上，
        // 所以这里的注入路径与真实扫码完全一致，可用于现场排障。
        // 发什么内容由主界面「自检内容」决定（默认 TEST123），前后缀照常套用。
        // 自检是一次诊断操作，不是识别结果：即使开着自动折叠，也不能把面板收走
        panelView.findViewById(R.id.btnTest)
                .setOnClickListener(v -> handleCode(Prefs.testText(this), false));

        // 退出：彻底结束服务，连悬浮球和常驻通知一起清掉
        panelView.findViewById(R.id.btnExit).setOnClickListener(v -> confirmExit());

        // 快速切换镜头：后置多摄时在取景过程中也能换，不用回主界面
        panelView.findViewById(R.id.btnSwitchCam).setOnClickListener(v -> cycleCamera());

        // 常用指令：面板上直接发，焦点始终在目标 App 上（在主界面里发会打进自己）
        panelView.findViewById(R.id.btnSnippets).setOnClickListener(v -> showSnippets());

        // 从图库选一张图识别：整条链路与扫码一致，只是图像来自文件而不是镜头
        panelView.findViewById(R.id.btnGallery).setOnClickListener(v -> pickFromGallery());

        // 整页扫描：拍一张全分辨率照片，把整页的条码按顺序一次性录入
        panelView.findViewById(R.id.btnBatch).setOnClickListener(v -> captureBatch());

        // 横屏 / 分屏时先把预览压到能放得下，否则底部四个按钮会顶出屏幕
        fitPreviewToScreen();

        try {
            wm.addView(panelView, panelParams);
        } catch (Throwable t) {
            panelView = null;
            showBubble();
            return;
        }

        bindCamera();
    }

    /**
     * 按"除预览以外的实际高度"反推预览能有多高。
     *
     * <p>预览在布局里写死 300dp，加上标题、状态行和两行按钮，竖屏刚好放得下；
     * 但横屏时可用高度只有约 380dp，面板会顶出屏幕下沿——底部 [自检][指令][镜头][退出]
     * 点不到，等于整套功能在横屏下不可用。
     *
     * <p>做法：先把预览高度置 0 量一次，得到其余部分的真实高度，再用屏幕高度减掉它。
     * 不去猜"大约 150dp"，因为字号/语言变了那个数就不准。
     */
    private void fitPreviewToScreen() {
        if (panelView == null || previewView == null) {
            return;
        }
        final int desired = dp(300);
        final int floor = dp(140);
        ViewGroup.LayoutParams lp = previewView.getLayoutParams();
        lp.height = 0;
        previewView.setLayoutParams(lp);
        panelView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int overhead = panelView.getMeasuredHeight();
        // 48dp 余量：状态栏 / 手势条 / 拖动时不至于贴边
        int available = screenHeightPx() - overhead - dp(48);
        lp.height = Math.max(floor, Math.min(desired, available));
        previewView.setLayoutParams(lp);

        // 只压预览高度还不够：面板沿用上次保存的位置，横屏 / 分屏 / 换机后可能整个跑到屏幕外。
        // 按实测尺寸把 x / y 都收进来，四边都要看得见、摸得着。
        if (panelParams != null) {
            int total = overhead + lp.height;
            int usableH = Math.max(0, screenHeightPx() - dp(16));
            int newX = Position.clamp(panelParams.x, screenWidthPx(), panelView.getMeasuredWidth());
            int newY = Position.clamp(panelParams.y, usableH, total);
            boolean moved = newX != panelParams.x || newY != panelParams.y;
            panelParams.x = newX;
            panelParams.y = newY;
            if (moved) {
                // 越界修正后的位置也要记住，否则下次展开还要重新夹一遍
                saveCurrentPosition();
                if (panelView.isAttachedToWindow()) {
                    try {
                        wm.updateViewLayout(panelView, panelParams);
                    } catch (Throwable ignored) {
                        // 窗口刚被移除时会抛，忽略即可
                    }
                }
            }
        }
        Log.i("VSG-Panel", "fitPreview: overhead=" + overhead + " available=" + available
                + " preview=" + lp.height
                + " x=" + (panelParams == null ? -1 : panelParams.x)
                + " y=" + (panelParams == null ? -1 : panelParams.y));
    }

    private int screenWidthPx() {
        return windowBounds().width();
    }

    private int screenHeightPx() {
        return windowBounds().height();
    }

    /** 当前窗口的可用区域；分屏时 getCurrentWindowMetrics 给的就是本窗口边界。 */
    private Rect windowBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.getCurrentWindowMetrics().getBounds();
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    /** 保存当前可见窗口的位置。悬浮球与面板共用一份，收起 / 展开时不会跳回出厂点。 */
    private void saveCurrentPosition() {
        if (panelView != null && panelParams != null) {
            Prefs.setFloatPosition(this, panelParams.x, panelParams.y);
        } else if (bubbleParams != null) {
            Prefs.setFloatPosition(this, bubbleParams.x, bubbleParams.y);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转横竖屏时面板还开着：重新算一次，不用让用户手动收起再展开
        if (panelView != null) {
            fitPreviewToScreen();
        }
    }

    private void collapse() {
        unbindCamera();
        if (panelView != null) {
            try {
                wm.removeView(panelView);
            } catch (Throwable ignored) {
            }
            panelView = null;
        }
        previewView = null;
        tvStatus = null;
        showBubble();
    }

    // ---------------- 退出 ----------------

    /**
     * 弹确认框再退出。悬浮球长按与面板"退出"按钮共用此入口，
     * 防止误触把正在扫码的服务关掉。
     */
    private void confirmExit() {
        try {
            AlertDialog dialog = new AlertDialog.Builder(this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.exit_title)
                    .setMessage(R.string.exit_message)
                    .setPositiveButton(R.string.btn_exit, (d, w) -> exitNow())
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            // 服务持有一个悬浮窗，弹窗同样要以悬浮层类型显示，否则会被系统拒绝
            dialog.getWindow().setType(overlayType());
            dialog.show();
        } catch (Throwable t) {
            // 弹窗失败不阻断退出，直接关闭
            exitNow();
        }
    }

    /** 真正的退出：先撤掉所有窗口，再停前台通知并结束服务。 */
    private void exitNow() {
        // 退出即落盘：下次启动从用户最后看到的位置开始
        saveCurrentPosition();
        Toast.makeText(getApplicationContext(), R.string.toast_exited, Toast.LENGTH_SHORT).show();
        unbindCamera();
        hideBubble();
        if (panelView != null) {
            try {
                wm.removeView(panelView);
            } catch (Throwable ignored) {
            }
            panelView = null;
            previewView = null;
            tvStatus = null;
        }
        publishRunning(false);
        stopForeground(true);
        stopSelf();
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private WindowManager.LayoutParams baseParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    // ---------------- 相机与识别 ----------------

    private void bindCamera() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);

        guard.setSameCodeWindowMs(Prefs.dedupMs(this));
        guard.setIgnoreDup(Prefs.ignoreDup(this));
        final String cameraId = Prefs.cameraId(this);

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = newPreview();
                ImageAnalysis analysis = newAnalysis();
                ImageCapture capture = newCapture();

                cameraProvider.unbindAll();

                CameraSelector selector = Cameras.selector(cameraId);
                try {
                    // 三路绑定：预览 + 分析流（实时扫码）+ 拍照（整页扫描）
                    cameraProvider.bindToLifecycle(this, selector, preview, analysis, capture);
                    imageCapture = capture;
                } catch (Throwable t) {
                    // 个别 ROM 上三路同时绑定会失败：放弃拍照，保住实时扫码
                    Log.w("VSG-Camera", "bind with ImageCapture failed, fallback: " + t);
                    imageCapture = null;
                    try {
                        cameraProvider.bindToLifecycle(this, selector, preview, analysis);
                    } catch (Throwable t2) {
                        // 选定镜头不可用（被占用 / ID 失效）时退回系统默认后置，保证还能扫码
                        selector = CameraSelector.DEFAULT_BACK_CAMERA;
                        cameraProvider.bindToLifecycle(this, selector, preview, analysis);
                    }
                }

                cameraBound = true;
                if (bindNote != null) {
                    setStatus(bindNote);
                    bindNote = null;
                } else if (SystemClock.elapsedRealtime() >= statusHoldUntil) {
                    // 拍完整页刚写了结果，别被"对准条码"这句立刻盖掉
                    setStatus(getString(R.string.status_idle));
                }
            } catch (Throwable t) {
                setStatus(getString(R.string.status_camera_failed,
                        String.valueOf(t.getMessage())));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private Preview newPreview() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        return preview;
    }

    private ImageAnalysis newAnalysis() {
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyze);
        return analysis;
    }

    /**
     * 拍照用例：明确要 2000px 级分辨率。
     *
     * <p>不指定时 CameraX 按"预览窗口多大"来挑（面板预览是 220×300dp），只拍出 768×1024，
     * 一页纸上密集的码就糊了。目标值按预览的 3:4 竖幅给，否则会因宽高比不匹配被降级。
     */
    private ImageCapture newCapture() {
        return new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(
                                new android.util.Size(2160, 2880),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .build();
    }

    /** 在已枚举到的摄像头之间循环切换，并立即重新绑定。 */
    private void cycleCamera() {
        List<Cameras.Choice> all = Cameras.list(this);
        if (all.size() < 2) {
            setStatus(getString(R.string.camera_single));
            return;
        }
        Cameras.Choice current = Cameras.findSelected(this, all);
        int index = all.indexOf(current);
        Cameras.Choice next = all.get((index + 1) % all.size());

        Prefs.setCameraId(this, next.id);
        bindNote = getString(R.string.camera_switched, next.label);
        unbindCamera();
        bindCamera();

        Runnable listener = cameraListener;
        if (listener != null) {
            listener.run();
        }
    }

    private void unbindCamera() {
        if (cameraProvider != null && cameraBound) {
            try {
                cameraProvider.unbindAll();
            } catch (Throwable ignored) {
            }
        }
        cameraBound = false;
        imageCapture = null;
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
    }

    private void analyze(@NonNull ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        // 包围盒坐标是"转正之后"的图像坐标，中心点判定要用转正后的宽高
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        final int width = rotation % 180 == 0 ? imageProxy.getWidth() : imageProxy.getHeight();
        final int height = rotation % 180 == 0 ? imageProxy.getHeight() : imageProxy.getWidth();

        scanner.process(image)
                .addOnSuccessListener(barcodes -> onBarcodes(barcodes, width, height))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onBarcodes(List<Barcode> barcodes, int frameW, int frameH) {
        final int codeType = Prefs.codeType(this);
        List<BarcodePick.Candidate> candidates = new ArrayList<>();
        int skipped = 0;
        if (barcodes != null) {
            for (Barcode barcode : barcodes) {
                if (!CodeTypes.accepts(barcode.getFormat(), codeType)) {
                    skipped++;
                    continue;
                }
                android.graphics.Rect box = barcode.getBoundingBox();
                candidates.add(box == null
                        ? new BarcodePick.Candidate(barcode.getRawValue())
                        : new BarcodePick.Candidate(barcode.getRawValue(),
                        box.left, box.top, box.right, box.bottom));
            }
        }
        if (skipped > 0 && SystemClock.elapsedRealtime() - lastTypeLogAt > 2000L) {
            lastTypeLogAt = SystemClock.elapsedRealtime();
            Log.i("VSG-Scan", "type filter skipped " + skipped + " code(s), setting=" + codeType);
        }
        final int policy = Prefs.multiCodePolicy(this);
        if (policy == BarcodePick.POLICY_ALL) {
            // 整页扫描模式：实时帧只当取景窗用。一页纸几十个码，逐帧注入会乱序、录重、漏号，
            // 而且分析流只有 1280×720，糊掉的码会认错——这里一律不采信
            long now = SystemClock.elapsedRealtime();
            if (now - lastAllModeAt > 500L && now >= statusHoldUntil) {
                lastAllModeAt = now;
                setStatus(getString(R.string.status_all_mode,
                        BarcodePick.distinctValues(candidates)));
            }
            return;
        }
        if (capturing) {
            // 正在拍整页：别在这半秒里把眼前的码也录进去，否则整页里会混进一条重复内容
            return;
        }
        // 一帧里出现多个码时按配置策略取一个，默认只取第一个
        String value = BarcodePick.pick(candidates, frameW, frameH, policy);
        int distinct = BarcodePick.distinctValues(candidates);
        if (distinct > 1) {
            Log.i("VSG-Scan", "multi-code frame: " + distinct + " values, policy=" + policy
                    + ", picked '" + value + "'");
        }
        // 单帧结果不可信：交给 ScanGuard 做多帧投票 + 视野锁定
        String confirmed = guard.onFrame(value, SystemClock.elapsedRealtime());
        if (confirmed != null) {
            handleCode(confirmed, true);
        }
    }

    /**
     * @param fromRecognition 是否来自镜头 / 图片的识别结果。面板「自检」是手动诊断，传 false，
     *                        否则一次自检就把面板折起来，没法连续排障。
     */
    private void handleCode(String code, boolean fromRecognition) {
        final Profile profile = Profiles.current(this);
        final Payload payload = Payload.of(profile, code);
        setStatus(getString(R.string.status_detected, brief(code)));
        Log.i("VSG-Scan", "emit '" + code + "' len=" + code.length()
                + " rejectedSoFar=" + guard.rejected());

        injectExecutor.execute(() -> {
            InjectRouter.Result result = InjectRouter.inject(this, profile, code);
            ScanHistory.get(this).add(code, "", result.ok, result.channel,
                    A11y.foregroundPackage());
            String describe = result.describe(this);
            String content = brief(payload.visibleText());
            main.post(() -> {
                setStatus(result.ok
                        ? getString(R.string.status_sent, describe, content)
                        : getString(R.string.status_send_failed, describe, content));
                // 只有真正送出去了才响：失败时静音，免得把"没扫到"误听成"扫到了"
                if (result.ok && Prefs.soundEnabled(this)) {
                    Beep.success();
                }
                // 识别结果成功送出后才折叠：失败时留着面板，状态行上的原因才看得见。
                // 记下当前面板实例——等待期间用户若手动收起又重新展开，别把新面板也收走。
                final View panel = panelView;
                if (fromRecognition && result.ok && profile.autoCollapse && panel != null) {
                    main.postDelayed(() -> {
                        if (panelView == panel) {
                            collapse();
                        }
                    }, AUTO_COLLAPSE_DELAY_MS);
                }
            });
        });
    }

    private String brief(String s) {
        if (s.length() <= 42) {
            return s;
        }
        return s.substring(0, 20) + getString(R.string.brief_truncated, s.length());
    }

    // ---------------- 图库识别 ----------------

    private void pickFromGallery() {
        // 先记下"回来时该等谁"：选择器关掉后要等它重新拿到焦点再注入
        galleryReturnPkg = returnTarget();
        Log.i("VSG-Gallery", "open picker, return target='" + galleryReturnPkg + "'");
        // 面板占了小半个屏幕，不收起会把系统选择器的缩略图盖住，点不到想选的那张
        collapse();
        Intent intent = new Intent(this, PickImageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Log.w("VSG-Gallery", "cannot start picker: " + t);
            expand();
            setStatus(getString(R.string.image_pick_unavailable));
        }
    }

    /**
     * 判断"选完图回来时，该等哪个 App 重新拿到焦点"。
     *
     * <p>首选当前可编辑目标所属的包名，而不是前台包名：软键盘升起时窗口事件会把前台记成
     * 输入法（实测搜狗/微信输入法都这样），等输入法"重新拿到焦点"永远等不到，只能干等 3 秒
     * 再硬注入。真正要等的永远是那个正在被输入的 App。
     */
    private String returnTarget() {
        A11y.Target target = A11y.findTarget();
        if (target != null && target.pkg != null && !target.pkg.isEmpty()
                && !target.pkg.equals(getPackageName())) {
            return target.pkg;
        }
        String pkg = A11y.activePackage();
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) {
            pkg = A11y.foregroundPackage();
        }
        return pkg;
    }

    /** 图库流程结束（选中或取消）后把面板放回来，省得用户再点一次悬浮球。 */
    static void onPickClosed() {
        OverlayService s = instance;
        if (s == null) {
            return;
        }
        s.main.postDelayed(() -> {
            if (s.panelView == null && s.bubbleView != null) {
                s.expand();
            }
        }, 350L);
    }

    private void awaitTargetThenInject(java.util.List<String> values) {
        final String want = galleryReturnPkg;
        if (values.size() == 1) {
            setStatus(getString(R.string.status_image_found, brief(values.get(0))));
        } else {
            setStatus(getString(R.string.status_image_batch, values.size()));
        }
        boolean usable = want != null && !want.isEmpty() && !want.equals(getPackageName())
                && A11y.isConnected();
        if (!usable) {
            // 没有无障碍或者拿不到前台包名：只能按固定延时赌一把，好过不注入
            main.postDelayed(() -> injectValues(values), 600L);
            return;
        }
        pollFocus(values, want, SystemClock.elapsedRealtime() + 3000L);
    }

    /** 每 100 ms 看一次焦点是否回到目标 App，最多等 3 s；超时也照常注入。 */
    private void pollFocus(java.util.List<String> values, String want, long deadline) {
        if (focusReady(want)) {
            injectValues(values);
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            Log.w("VSG-Gallery", "focus did not return to " + want + ", injecting anyway");
            injectValues(values);
            return;
        }
        main.postDelayed(() -> pollFocus(values, want, deadline), 100L);
    }

    /**
     * 焦点是否已经回到目标 App。
     *
     * <p>三个判据依次放宽：前台包名、活跃窗口包名、当前输入框所属包名。最后一条是给
     * "输入法占着前台"的情况兜底的——那种时候前两个判据都会一直指向输入法。
     */
    private boolean focusReady(String want) {
        if (want == null || want.isEmpty()) {
            return true;
        }
        if (want.equals(A11y.foregroundPackage())) {
            return true;
        }
        A11y.Target target = A11y.findTarget();
        return target != null && want.equals(target.pkg);
    }

    /** 一条走原来的单发链路，多条按顺序逐条录入。 */
    private void injectValues(java.util.List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        if (values.size() == 1) {
            // 图库单码识别也算识别结果：自动折叠开着时走同一套收起逻辑
            handleCode(values.get(0), true);
        } else {
            // 整页 / 图库批量要逐条看进度，不能中途把面板收走
            injectBatch(values);
        }
    }

    // ---------------- 整页扫描 ----------------

    /**
     * 拍一张全分辨率照片，把这一页的条码一次性识别出来。
     *
     * <p>为什么不在实时帧上做"全部扫描"：分析流只有 1280×720，手一直抖，一页纸上几十个
     * 码里总有几个糊到解不开；而且扫描是逐帧的，同一页会被拆成很多次、顺序还会随识别器
     * 抖动而乱。照片走 ImageCapture 的全分辨率通道，静止快门比连续帧清楚一个量级。
     *
     * <p>拍照不会挪走焦点（悬浮窗是 FLAG_NOT_FOCUSABLE），所以拿到结果可以直接注入，
     * 不像图库那条路要等目标 App 把焦点拿回去。
     */
    private void captureBatch() {
        if (capturing) {
            return;
        }
        final ImageCapture ic = imageCapture;
        if (ic == null || !cameraBound) {
            setStatusHold(getString(R.string.status_batch_no_camera));
            return;
        }
        capturing = true;
        setStatusHold(getString(R.string.status_batch_capturing));
        try {
            ic.takePicture(ContextCompat.getMainExecutor(this),
                    new ImageCapture.OnImageCapturedCallback() {
                        @Override
                        public void onCaptureSuccess(@NonNull ImageProxy image) {
                            byte[] jpeg = null;
                            int rotation = 0;
                            try {
                                rotation = image.getImageInfo().getRotationDegrees();
                                java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                jpeg = new byte[buffer.remaining()];
                                buffer.get(jpeg);
                            } catch (Throwable t) {
                                Log.w("VSG-Batch", "read photo failed: " + t);
                            } finally {
                                image.close();
                            }
                            capturing = false;
                            if (jpeg == null || jpeg.length == 0) {
                                setStatusHold(getString(R.string.status_batch_capture_failed,
                                        getString(R.string.status_batch_empty_photo)));
                                return;
                            }
                            recognizePhoto(jpeg, rotation);
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException e) {
                            capturing = false;
                            Log.w("VSG-Batch", "takePicture failed: " + e);
                            setStatusHold(getString(R.string.status_batch_capture_failed,
                                    String.valueOf(e.getMessage())));
                        }
                    });
        } catch (Throwable t) {
            capturing = false;
            setStatusHold(getString(R.string.status_batch_capture_failed,
                    String.valueOf(t.getMessage())));
        }
    }

    /** 解码 + 识别都放到分析线程上做：一张 2000px 的 JPEG 解码要几百毫秒，不能卡主线程。 */
    private void recognizePhoto(byte[] jpeg, int rotation) {
        analysisExecutor.execute(() -> {
            final Bitmap bitmap = decodePhoto(jpeg);
            if (bitmap == null) {
                main.post(() -> setStatusHold(getString(R.string.status_batch_capture_failed,
                        getString(R.string.status_batch_empty_photo))));
                return;
            }
            // 包围盒是"转正之后"的坐标，宽高也得按转正后的方向取
            final int width = rotation % 180 == 0 ? bitmap.getWidth() : bitmap.getHeight();
            final int height = rotation % 180 == 0 ? bitmap.getHeight() : bitmap.getWidth();
            scanner.process(InputImage.fromBitmap(bitmap, rotation))
                    .addOnSuccessListener(list -> onBatchCodes(list, width, height))
                    .addOnFailureListener(e -> {
                        Log.w("VSG-Batch", "photo process failed: " + e);
                        setStatusHold(getString(R.string.status_batch_capture_failed,
                                String.valueOf(e.getMessage())));
                    })
                    .addOnCompleteListener(task -> bitmap.recycle());
        });
    }

    private Bitmap decodePhoto(byte[] jpeg) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            // 只按 2 的幂降采样，且"降完仍不小于上限"才降：4096 的照片降到 1024
            // 会把一页纸上密集的条码直接抹掉（实测 6 个码只剩 2 个能解）
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / (sample * 2) >= PHOTO_MAX_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        } catch (Throwable t) {
            Log.w("VSG-Batch", "decode photo failed: " + t);
            return null;
        }
    }

    private void onBatchCodes(List<Barcode> barcodes, int width, int height) {
        final int codeType = Prefs.codeType(this);
        List<BarcodePick.Candidate> candidates = new ArrayList<>();
        int total = barcodes == null ? 0 : barcodes.size();
        if (barcodes != null) {
            for (Barcode barcode : barcodes) {
                if (!CodeTypes.accepts(barcode.getFormat(), codeType)) {
                    continue;
                }
                android.graphics.Rect box = barcode.getBoundingBox();
                candidates.add(box == null
                        ? new BarcodePick.Candidate(barcode.getRawValue())
                        : new BarcodePick.Candidate(barcode.getRawValue(),
                        box.left, box.top, box.right, box.bottom));
            }
        }
        List<String> values = BarcodePick.pickAll(candidates, BATCH_MAX);
        int distinct = BarcodePick.distinctValues(candidates);
        Log.i("VSG-Batch", "photo " + width + "x" + height + ": " + total + " code(s), type="
                + codeType + ", accepted=" + candidates.size() + ", distinct=" + distinct
                + ", picked=" + values.size() + " " + values);
        if (values.isEmpty()) {
            setStatusHold(getString(total > 0 && candidates.isEmpty()
                    ? R.string.status_batch_none_of_type : R.string.status_batch_none));
            return;
        }
        if (distinct > values.size()) {
            // 超上限：先说清楚为什么只录了一部分，再开始，免得用户以为漏扫
            setStatusHold(getString(R.string.status_batch_over_limit, distinct, values.size()));
            main.postDelayed(() -> injectBatch(values), 1200L);
            return;
        }
        injectBatch(values);
    }

    /**
     * 逐条录入，每条之间留 {@link #BATCH_GAP_MS}。
     *
     * <p>注入本身走 {@link #injectExecutor}（单线程），所以这里的顺序就是录进去的顺序；
     * 中间那段间隔是留给目标 App 的——思迅收银每条都要等上一件商品结算完才收下一条，
     * 粘着发会把两条合成一条、或者丢掉后一条。
     */
    private void injectBatch(List<String> values) {
        final Profile profile = Profiles.current(this);
        final int total = values.size();
        setStatusHold(getString(R.string.status_batch_found, total));
        injectExecutor.execute(() -> {
            int ok = 0;
            for (int i = 0; i < total; i++) {
                final String value = values.get(i);
                final int index = i + 1;
                InjectRouter.Result result = InjectRouter.injectAppending(this, profile, value);
                ScanHistory.get(this).add(value, "", result.ok, result.channel,
                        A11y.foregroundPackage());
                if (result.ok) {
                    ok++;
                }
                final int okSoFar = ok;
                main.post(() -> setStatusHold(getString(R.string.status_batch_progress,
                        index, total, brief(value))));
                Log.i("VSG-Batch", "batch " + index + "/" + total + " '" + value
                        + "' ok=" + result.ok + " so far=" + okSoFar);
                if (index < total) {
                    sleepQuiet(BATCH_GAP_MS);
                }
            }
            final int okFinal = ok;
            main.post(() -> {
                setStatusHold(getString(R.string.status_batch_done, okFinal, total));
                if (okFinal > 0 && Prefs.soundEnabled(this)) {
                    Beep.success();
                }
            });
        });
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- 常用指令 ----------------

    /**
     * 在悬浮层里弹出指令列表。
     *
     * 必须用悬浮窗类型显示：普通 Activity 弹窗会把自己顶到前台，
     * 焦点离开目标 App 后就注入不进去了。
     */
    private void showSnippets() {
        final List<Snippets.Snippet> snippets = Snippets.all(this);
        if (snippets.isEmpty()) {
            setStatus(getString(R.string.snippet_empty));
            return;
        }
        String[] names = new String[snippets.size()];
        for (int i = 0; i < snippets.size(); i++) {
            names[i] = snippets.get(i).name;
        }
        try {
            AlertDialog dialog = new AlertDialog.Builder(this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.btn_snippets)
                    .setItems(names, (d, which) -> sendSnippet(snippets.get(which)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.getWindow().setType(overlayType());
            dialog.show();
        } catch (Throwable t) {
            setStatus(getString(R.string.snippet_send_failed, String.valueOf(t.getMessage())));
        }
    }

    private void sendSnippet(Snippets.Snippet snippet) {
        setStatus(getString(R.string.status_detected, brief(snippet.name)));
        // 弹窗关闭后焦点要稍等一会才回到目标 App，立即发可能打空
        main.postDelayed(() -> injectExecutor.execute(() -> {
            Profile profile = Profiles.current(this);
            InjectRouter.Result result = InjectRouter.send(this, profile, snippet.text,
                    profile.enter);
            ScanHistory.get(this).add(snippet.text, "", result.ok, result.channel,
                    A11y.foregroundPackage());
            String describe = result.describe(this);
            String content = brief(Payload.visible(snippet.text));
            main.post(() -> setStatus(result.ok
                    ? getString(R.string.status_sent, describe, content)
                    : getString(R.string.status_send_failed, describe, content)));
        }), 250);
    }

    private void setStatus(String text) {
        if (tvStatus != null) {
            tvStatus.setText(text);
        }
    }

    /**
     * 写状态行并"按住"4 秒。
     *
     * <p>整页模式下 {@link #onBarcodes} 每 500 ms 会把画面里的码数刷到状态行上，
     * 拍照结果、失败原因、录入进度这些一次性消息刚写上去就被盖掉，等于没提示。
     */
    private void setStatusHold(String text) {
        setStatus(text);
        statusHoldUntil = SystemClock.elapsedRealtime() + 4000L;
    }

    // ---------------- 拖动 ----------------

    private abstract class DragTouchListener implements View.OnTouchListener {
        private final View target;
        private final WindowManager.LayoutParams params;
        private float downX, downY;
        private int startX, startY;
        private long downAt;
        private boolean moved;

        DragTouchListener(View target, WindowManager.LayoutParams params) {
            this.target = target;
            this.params = params;
        }

        abstract void onClick();

        /** 松手时把最终位置落盘（点按也会写一次，开销可忽略）。 */
        abstract void onPositionChanged(int x, int y);

        /** 长按回调，默认不处理。 */
        void onLongClick() {
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    downAt = SystemClock.uptimeMillis();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        moved = true;
                    }
                    params.x = startX + dx;
                    params.y = startY + dy;
                    try {
                        wm.updateViewLayout(target, params);
                    } catch (Throwable ignored) {
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    // 先落盘再处理点击：展开面板时读到的就是刚刚拖到的位置
                    onPositionChanged(params.x, params.y);
                    if (!moved) {
                        if (SystemClock.uptimeMillis() - downAt >= LONG_PRESS_MS) {
                            onLongClick();
                        } else {
                            onClick();
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void onDestroy() {
        instance = null;
        // 被系统回收时也保住最后一次拖动结果
        saveCurrentPosition();
        publishRunning(false);
        unbindCamera();
        hideBubble();
        if (panelView != null) {
            try {
                wm.removeView(panelView);
            } catch (Throwable ignored) {
            }
            panelView = null;
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
        if (injectExecutor != null) {
            injectExecutor.shutdown();
        }
        if (scanner != null) {
            scanner.close();
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
