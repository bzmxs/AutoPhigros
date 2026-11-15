package com.bzmxs.autophigros;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FloatingControlService extends Service {

    private static final String TAG = "AutoPhigros";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 1;

    // ==================== 录制参数 ====================

    /**
     * 录制分辨率相对屏幕的缩小倍数：1 = 原画质，2 = 宽高各减半（像素量 1/4）。
     * <p>
     * 识别只看判定线附近十几个像素，半分辨率完全够用；而 MediaProjection 每帧要拷贝的
     * 数据量直接少 3/4，扫描的像素也少 3/4，这是解决“玩一会儿就卡”最有效的一刀。
     * 下发点击坐标时内部会自动乘回 CAPTURE_SCALE，所以外面不用管。
     */
    private static final int CAPTURE_SCALE = 2;

    // ==================== 识别参数 ====================

    /** 自动校准失败时的兜底比例（实测 1920x1080 的 Phira：827/1080 = 0.766） */
    private static final float JUDGE_RATIO_FALLBACK = 0.766f;

    /** 校准判定线时，在画面高度的这个区间里找那条横贯屏幕的亮线 */
    private static final float JUDGE_SEARCH_TOP = 0.45f;
    private static final float JUDGE_SEARCH_BOTTOM = 0.98f;

    /**
     * 判定线不是钉死的：Phigros / Phira 打歌过程中判定线会上下移动。
     * 所以每帧都要在上一帧位置上下各 120 像素内重新找一次，跟着它走。
     */
    private static final int JUDGE_TRACK_RANGE = 120;

    /**
     * 触发线：判定线上方 28 像素的一条横线（录制像素）。
     * 音符底边从“触发线上方”跨到“触发线下方”的那一帧，就是这个音符唯一一次被登记的时刻，
     * 然后按 距离÷速度 算出还要多久到判定线，到点了再点。
     * <p>
     * 早先这里是 70 像素（提前量 ~130ms），速度估偏 30% 就会错三四十毫秒；
     * 试过 28 像素（~50ms）后发现整体变成 Late（结算 Late 20 / Early 3）——
     * 说明 45 像素（~85ms）附近才是这两种误差的平衡点。
     */
    private static final int TRIGGER_UP = 45;

    /** 触发线上下各扫多少像素（录制像素） */
    private static final int SCAN_UP = 42;
    private static final int SCAN_DOWN = 34;

    /**
     * 提前量的上限（毫秒）。宁可按着算出来的时间点，也不要提前太久。
     * 正常情况下 28px ÷ 500px/s ≈ 56ms，这里留到 150ms 给慢速谱面用。
     */
    private static final int LEAD_MAX_MS = 150;

    /** 算提前量时速度的下限：速度估成 150 会让提前量翻倍，直接钳住 */
    private static final float SPEED_FLOOR = 300f;

    /**
     * 时间补偿（ms）：整体往后挪一点再点。
     * <p>
     * 这个值现在可以在悬浮窗上用 ⏱ 按钮实时调（-30 ~ +30，每步 10ms，存在 SharedPreferences 里），
     * 结算界面看 Early / Late 哪个多就往反方向调：
     * Late 多 → 调小（甚至负数）；Early 多 → 调大。
     */
    private volatile int timingBias = 0;
    private static final String PREF_NAME = "autophigros";
    private static final String PREF_TIMING = "timing_bias";

    /**
     * 又宽又扁、又不是 Hold 色的段 → 是背景亮块（歌曲封面、特效带），不是音符。
     * 之前这种段会照样排队、发点击，落点全在画面中间，纯浪费手势通道。
     */
    private static final int BG_MAX_WIDTH = 200;
    private static final int BG_MAX_HEIGHT = 12;

    /** 横向采样步长（录制像素） */
    private static final int X_STEP = 4;

    /** 纵向采样步长（录制像素）。越小越准，但更吃 CPU */
    private static final int Y_STEP = 2;

    /** 相邻命中点间隔超过这个值（录制像素），就认为不是一个音符 */
    private static final int SEGMENT_GAP_PX = 16;

    /** 宽度超过这个值（录制像素），当成长按（Hold）而不是轻点 */
    private static final int HOLD_MIN_WIDTH = 40;

    /**
     * 竖直方向延伸超过这个高度（录制像素）就当 Hold。
     * <p>
     * 这是看实测抓帧定的：Hold 是**竖直**的浅蓝长条（往上延伸几百像素），
     * Tap 是菱形（二三十像素）、Drag 是横条（只有十几像素厚）。
     * 所以“够高”比“够宽”可靠得多 —— 短 Hold 的宽度可能只有 40 左右，
     * 而一条横着的 Drag 反而能到 100 多宽。
     */
    private static final int HOLD_MIN_HEIGHT = 45;

    /**
     * 宽到离谱的“音符段”一定是背景（整片亮色区域），不能当 Hold 按。
     * 实测 Hold 条宽约 50~110（录制像素），给到 220 已经足够宽松。
     */
    private static final int HOLD_MAX_WIDTH = 220;

    /**
     * 一次长按最多按多久（ms）。
     * <p>
     * 之前放到 8 秒，结果一条“假 Hold”（把亮色背景当成音符）就能把手势通道
     * 占满 8 秒，这期间所有轻点全被丢掉 —— 表现就是“每次点击都巨卡”。
     * 现在封顶 3 秒：真 Hold 超过 3 秒的极少，误判的代价也小得多。
     */
    private static final int HOLD_MAX_MS = 3000;
    private static final int HOLD_MIN_MS = 120;

    /** 两次下发手势之间至少隔这么久（ms），别把系统输入队列塞爆 */
    private static final int MIN_FIRE_GAP_MS = 16;

    /**
     * 提前排程窗口（ms）：一次性把未来 70ms 内该点的音符全部塞进同一个手势，
     * 用 StrokeDescription 的 startTime 让系统自己按时刻落下。
     * <p>
     * 这一步是精度的关键。以前是“到点一个发一个”，每个音符都要等上一个手势结束
     * （每次约 37ms），密集段就会一路往后拖 20~40ms —— 实测把补偿从 0 调到 -30
     * 只救回 4 个 Late（17 → 13），说明晚的量是逐音符累积的，不是固定的偏移。
     * 现在时间由系统按 startTime 落点，不再受我们下发速度影响。
     */
    private static final int BATCH_LOOKAHEAD_MS = 70;

    /** 长按末尾多按一点（ms），宁可多按几十毫秒，也不要半路松手 */
    private static final int HOLD_MARGIN_MS = 60;

    /** 速度估算会略微偏高，按这个系数打折后再算时长，条子就按得长一点 */
    private static final float HOLD_SPEED_BIAS = 0.85f;

    /** 还没测出速度时，先按这么久（ms） */
    private static final int HOLD_FALLBACK_MS = 900;

    /**
     * 下长按时，往后预排多远（ms）。
     * <p>
     * Android 一次只能跑一个手势，长按会把手势通道一直占着，这期间的 Tap 自己发不出去。
     * 但一个手势里可以放多个触点、每个触点有自己的 startTime，
     * 所以下长按时把这段时间内到点的音符一起塞进同一个手势就行。
     * 预排太远会让手势变长、抬手变晚，700ms 是折中。
     */
    private static final int PRESCHEDULE_MS = 700;

    /** 排程器多久看一次“有没有音符到点了”（ms） */
    private static final int SCHEDULER_TICK_MS = 8;

    /** 允许迟到多久还发出去（ms），超过就说明已经过期了，丢掉 */
    private static final int LATE_TOLERANCE_MS = 90;

    /** 一次手势最多带几个触点（系统上限一般是 10，这里留点余量给并排的和弦） */
    private static final int MAX_STROKES = 10;

    /** 点击按下时长（ms）。1~5ms 有些设备会丢触摸；太长又会卡住下一个音符 */
    private static final int TAP_MS = 25;

    /** 处理帧的最小间隔（ms），限制识别频率，防止空转吃满 CPU */
    private static final int FRAME_MIN_INTERVAL_MS = 8;

    /** 多久检查一次画面尺寸/方向的变化（ms） */
    private static final int DISPLAY_CHECK_MS = 1000;

    // ==================== 字段 ====================

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams windowParams;   // 拖动悬浮窗时要用它 updateViewLayout
    private TextView tvStatus;

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean projectionReady = false;

    /** 屏幕真实分辨率（下发点击坐标用这个） */
    private int displayWidth, displayHeight;
    /** 录制分辨率（识别用这个，= 屏幕 / CAPTURE_SCALE） */
    private int captureWidth, captureHeight, captureDpi;
    /** 屏幕像素 = 录制像素 × captureScale */
    private int captureScale = 1;

    /** 录制坐标系里的判定线 y；-1 表示还没有可用值 */
    private int judgeY = -1;
    /** 当前使用的判定线比例 */
    private float judgeRatio = JUDGE_RATIO_FALLBACK;    /** 是否已经自动校准成功 */
    private boolean judgeCalibrated = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 所有图像处理都在这个后台线程里，绝不占用主线程 */
    private HandlerThread workerThread;
    private Handler workerHandler;

    private volatile boolean isRunning = false;
    private volatile boolean dumpRequested = false;

    private long lastFrameAt = 0;

    /** 手势通道被占用到什么时候（一次只能下发一个手势，自己排好队） */
    private long gestureEndsAt = 0;
    /** 长按占着手势通道到什么时候（按住期间别的 Tap 发出去也会失败） */
    private long holdBusyUntil = 0;

    /** 音符下落速度（录制像素/秒）。0 = 还没测出来 */
    private float noteSpeed = 0f;
    /** 最近 5 个速度样本，取中位数用（单帧估速噪声太大） */
    private final float[] speedSamples = new float[5];
    private int speedCount = 0;
    private int speedIdx = 0;

    /** 最近一次排程的 Hold 条宽度（录制像素），0 = 还没遇到过 Hold。状态栏显示用 */
    private int lastHoldWidth = 0;
    /** 最近一次排程的 Hold 条“剩余长度”（录制像素，也就是竖着量的高度） */
    private int lastHoldLen = 0;
    /** 最近一帧扫到的最宽音符段宽度（录制像素），判断“Hold 到底有没有被当成 Hold” */
    private int lastWidestRun = 0;
    /** 上一帧扫到的音符段 (中心x, 底边y)，用来算速度 + 判断有没有跨过触发线 */
    private final int[] prevRunX = new int[24];
    private final int[] prevRunY = new int[24];
    private int prevRunCount = 0;
    private long prevRunAt = 0;

    /** 已经排好程、等着到点下发的音符 */
    private final java.util.ArrayList<PendingNote> pending = new java.util.ArrayList<>();

    /** 一个待下发的音符：什么时刻、点哪里、什么类型、按多久 */
    private static final class PendingNote {
        long arrival;
        int x;
        int type;
        int durMs;
        int bucket;
    }

    // 统计（给悬浮窗状态栏用）
    private long statFrames = 0, statHits = 0, statClicks = 0;
    private long statBlue = 0, statRed = 0, statTan = 0;   // 每类颜色各扫到多少列，调参用
    /** 因为长时间按占着手势通道而没发出去的 Tap 数量 */
    private long statDropped = 0;
    private long lastStatAt = 0;
    private long lastLogAt = 0;
    private long totalClicks = 0;

    /** 上一次真正下发手势的时刻（ms），用来限制下发频率 */
    private long lastFireAt = 0;
    /** 每帧识别耗时统计（ms），用来判断“卡”是识别算不过来还是手势通道被占 */
    private long statFrameCostSum = 0;
    private long statFrameCostMax = 0;
    private long statHoldFires = 0;
    /** 被“又宽又扁 = 背景亮块”规则过滤掉的段数，用来确认这条规则到底有没有生效 */
    private long statBg = 0;
    /** 每帧耗时指数平均：超过 10ms 隔一帧识别，超过 16ms 隔两帧 */
    private float costEma = 0f;
    private int skipEvery = 0;
    private int skipCounter = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        timingBias = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(PREF_TIMING, 0);
        workerThread = new HandlerThread("AutoPhigrosWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        showFloatingWindow();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 可能被调用两次：
        // 1) MainActivity 启动服务（没有 resultCode）→ 只显示悬浮窗
        // 2) 录屏授权回调后，带着 resultCode + data 再来一次 → 初始化录屏
        if (intent != null && intent.hasExtra("resultCode")) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = (Intent) intent.getParcelableExtra("data");
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                initScreenCapture(resultCode, data);
            } else {
                Toast.makeText(this, "录屏授权无效，请重试", Toast.LENGTH_SHORT).show();
            }
        }

        // 悬浮窗被 ✖ 关掉后，回 App 再点一次“启动悬浮窗”，服务会走到这里把面板重新挂上去
        if (floatingView == null) {
            showFloatingWindow();
        }

        return START_STICKY;
    }

    // ==================== 悬浮窗 ====================

    private void showFloatingWindow() {
        try {
            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_control, null);

            int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 20;
            params.y = 60;
            windowParams = params;
            windowManager.addView(floatingView, params);
            setupDrag(floatingView, params);   // 整个面板都能拖，按钮区域除外

            Button btnStart = floatingView.findViewById(R.id.btn_start);
            Button btnStop = floatingView.findViewById(R.id.btn_stop);
            Button btnExit = floatingView.findViewById(R.id.btn_exit);
            Button btnTune = floatingView.findViewById(R.id.btn_tune);
            tvStatus = floatingView.findViewById(R.id.tv_status);
            btnTune.setText(tuneLabel());

            // ⏱ 时序微调：-30 → -20 → -10 → 0 → +10 → +20 → +30 循环
            // 结算里 Late 多就往负方向调（前移），Early 多就往正方向调（后移）
            btnTune.setOnClickListener(v -> {
                int[] steps = {-30, -20, -10, 0, 10, 20, 30};
                int cur = 0;
                for (int i = 0; i < steps.length; i++) {
                    if (steps[i] == timingBias) { cur = i; break; }
                }
                timingBias = steps[(cur + 1) % steps.length];
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .putInt(PREF_TIMING, timingBias).apply();
                btnTune.setText(tuneLabel());
                Toast.makeText(this, String.format(Locale.US,
                        "时序补偿 %+dms（Late 多就调负，Early 多就调正）", timingBias),
                        Toast.LENGTH_SHORT).show();
            });

            btnStart.setOnClickListener(v -> {
                if (isRunning) {
                    Toast.makeText(this, "已在运行中", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!projectionReady) {
                    Toast.makeText(this, "请先在 App 里授权录屏", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!AutoClickService.isReady()) {
                    Toast.makeText(this, "请先在系统设置里开启无障碍服务", Toast.LENGTH_LONG).show();
                    return;
                }
                startAutoPlay();
            });

            btnStop.setOnClickListener(v -> {
                if (isRunning) {
                    stopAutoPlay();
                } else {
                    Toast.makeText(this, "未在运行", Toast.LENGTH_SHORT).show();
                }
            });

            // 长按 ▶ = 偷偷抓一帧存 PNG（界面上不再放截图按钮，这个功能留着调参用）
            btnStart.setOnLongClickListener(v -> {
                if (!projectionReady) {
                    Toast.makeText(this, "请先授权录屏", Toast.LENGTH_SHORT).show();
                    return true;
                }
                dumpRequested = true;
                Toast.makeText(this, "下一帧保存截图...", Toast.LENGTH_SHORT).show();
                return true;
            });

            // ✖ 只关掉悬浮窗：录屏和识别状态都保留，回 App 点“启动悬浮窗”能再打开
            btnExit.setOnClickListener(v -> {
                stopAutoPlay();
                hideFloatingWindow();
                Toast.makeText(this, "悬浮窗已关闭（录屏还在），回 App 点“启动悬浮窗”可再打开",
                        Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "悬浮窗创建失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 让整个悬浮面板可以拖动。
     * <p>
     * 原理：给根 View 加 OnTouchListener，把手指的位移 rawX/rawY 累加到
     * WindowManager.LayoutParams 的 x/y 上，再 updateViewLayout 让窗口跟着走。
     * 按钮是子 View，会先消费自己的触摸，所以点按钮不会误拖。
     */
    private void setupDrag(View view, WindowManager.LayoutParams params) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (!moved && Math.hypot(dx, dy) < slop) {
                            return true;   // 抖动，不算拖动
                        }
                        moved = true;
                        params.x = startX + dx;
                        params.y = startY + dy;
                        if (params.x < 0) params.x = 0;
                        if (params.y < 0) params.y = 0;
                        try {
                            windowManager.updateViewLayout(floatingView, params);
                        } catch (Exception ignored) {
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * 只把悬浮窗从屏幕上摘掉：录屏、识别、后台线程全都保留。
     * <p>
     * 注意：不能在这里 stopSelf()/stopForeground()，否则 MediaProjection 会被系统回收，
     * 下次又得重新弹一次“允许录屏”。所以这里只 removeView。
     */
    private void hideFloatingWindow() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {
            }
        }
        floatingView = null;
        tvStatus = null;
        windowParams = null;
    }

    // ==================== 录屏初始化 ====================

    private void initScreenCapture(int resultCode, Intent data) {
        try {
            // 如果之前已经有一份录屏（比如关了悬浮窗又重新授权），先彻底释放旧的，
            // 不然会同时存在两个 VirtualDisplay：一份泄漏，还会双份识别、双份点击
            if (projectionReady || mediaProjection != null) {
                projectionReady = false;
                workerHandler.removeCallbacks(displayWatchdog);
                releaseVirtualDisplay();
                if (mediaProjection != null) {
                    if (projectionCallback != null) {
                        try {
                            mediaProjection.unregisterCallback(projectionCallback);
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        mediaProjection.stop();
                    } catch (Exception ignored) {
                    }
                }
                mediaProjection = null;
                projectionCallback = null;
            }

            // ① 先进入前台服务，并明确声明 mediaProjection 类型（Android 14 要求）
            startForegroundAsMediaProjection();

            // ② 获取 MediaProjection（每个授权 token 只能用一次）
            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Toast.makeText(this, "获取录屏失败，请重试", Toast.LENGTH_LONG).show();
                stopForeground(true);
                return;
            }

            // ③ Android 14 起，createVirtualDisplay() 之前必须注册回调
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(FloatingControlService.this::handleProjectionStopped);
                }
            };
            mediaProjection.registerCallback(projectionCallback, mainHandler);

            // ④ 真实分辨率 → 换算录制分辨率
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            applyDisplaySize(dm.widthPixels, dm.heightPixels, dm.densityDpi);

            // ⑤ ImageReader + 后台线程监听
            imageReader = ImageReader.newInstance(captureWidth, captureHeight,
                    PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, workerHandler);

            // ⑥ 创建虚拟屏（按缩小后的尺寸录制，坐标下发时会乘回去）
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "AutoPhigrosCapture",
                    captureWidth, captureHeight, captureDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, workerHandler);

            projectionReady = true;
            Toast.makeText(this, "录屏已就绪 " + displayWidth + "x" + displayHeight
                            + "（识别用 " + captureWidth + "x" + captureHeight + "）",
                    Toast.LENGTH_SHORT).show();
            Log.i(TAG, "capture ready display=" + displayWidth + "x" + displayHeight
                    + " capture=" + captureWidth + "x" + captureHeight);

            // ⑦ 盯住横竖屏切换：游戏从竖屏切到横屏时，必须重建录制，否则画面是错位的
            workerHandler.removeCallbacks(displayWatchdog);
            workerHandler.postDelayed(displayWatchdog, DISPLAY_CHECK_MS);

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "录屏启动失败：" + e.getClass().getSimpleName()
                    + " - " + e.getMessage(), Toast.LENGTH_LONG).show();
            releaseVirtualDisplay();
            stopForeground(true);
        }
    }

    /**
     * 屏幕尺寸变了（启动、横竖屏切换）：重新算录制尺寸、判定线、冷却桶。
     * 录制尺寸要取偶数，VirtualDisplay 用奇数宽高在某些机器上会花屏。
     */
    private void applyDisplaySize(int w, int h, int dpi) {
        displayWidth = w;
        displayHeight = h;
        captureScale = Math.max(1, CAPTURE_SCALE);
        captureWidth = Math.max(2, (w / captureScale) & ~1);
        captureHeight = Math.max(2, (h / captureScale) & ~1);
        captureDpi = dpi;   // 密度保持真实值，否则某些 App 的排布会变

        gestureEndsAt = 0;
        holdBusyUntil = 0;
        pending.clear();

        judgeY = -1;
        judgeCalibrated = false;
        judgeRatio = JUDGE_RATIO_FALLBACK;
        noteSpeed = 0f;
        speedCount = 0;
        speedIdx = 0;
        prevRunCount = 0;
    }

    private void startForegroundAsMediaProjection() {
        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "录屏服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("自动打歌工具")
                .setContentText("正在运行录屏服务...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // ==================== 帧处理（全部在 worker 线程） ====================

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            handleFrame(image);
        } catch (Exception e) {
            Log.w(TAG, "frame error", e);
        } finally {
            if (image != null) image.close();
        }
    }

    private void handleFrame(Image image) {
        if (image.getPlanes().length == 0) return;
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int w = image.getWidth();
        int h = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        if (dumpRequested) {
            dumpRequested = false;
            saveFrame(buffer, w, h, rowStride, pixelStride);
        }

        if (!isRunning) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastFrameAt < FRAME_MIN_INTERVAL_MS) return;  // 限速，别把 CPU 吃满
        lastFrameAt = now;

        // 自适应降频：上一轮平均每帧耗时太高就隔帧识别。
        // 宁可少看几帧，也不要让识别线程把 CPU 吃满、把游戏拖到掉帧。
        if (skipEvery > 0) {
            if (++skipCounter <= skipEvery) return;
            skipCounter = 0;
        }

        statFrames++;

        long t0 = System.nanoTime();

        // 判定线：每帧都重新找一次（它会动），找到了就平滑地跟过去
        int near = trackJudgeLine(buffer, w, h, rowStride, pixelStride, judgeY);
        if (near > 0) {
            if (judgeY < 0) {
                judgeY = near;
                Log.i(TAG, "judge line locked: y=" + near
                        + " ratio=" + String.format(Locale.US, "%.4f", near / (float) h));
            } else {
                // 平滑跟随，但不拖泥带水：一次最多挪过去 70%
                judgeY = (int) (judgeY + (near - judgeY) * 0.7f);
            }
            judgeCalibrated = true;
        } else if (!judgeCalibrated) {
            judgeY = (int) (h * JUDGE_RATIO_FALLBACK);   // 实在找不到就按兜底比例先跑
        }
        if (judgeY < 0) judgeY = (int) (h * JUDGE_RATIO_FALLBACK);
        if (judgeY >= h) judgeY = h - 1;
        judgeRatio = judgeY / (float) h;

        scanAndSchedule(buffer, w, h, rowStride, pixelStride, now);
        // 记一下这一帧（找判定线 + 扫音符）花了多久：
        // cost 接近/超过 16ms 说明卡顿来自“算不过来”，那就自动隔帧识别
        long costMs = (System.nanoTime() - t0) / 1_000_000L;
        statFrameCostSum += costMs;
        if (costMs > statFrameCostMax) statFrameCostMax = costMs;
        costEma = costEma <= 0f ? costMs : costEma * 0.85f + costMs * 0.15f;
        if (costEma > 16f) skipEvery = 2;
        else if (costEma > 10f) skipEvery = 1;
        else skipEvery = 0;
    }

    /**
     * 直接按字节读像素做识别 —— 完全不创建 Bitmap。
     * <p>
     * 之前每帧都 createBitmap + copyPixelsFromBuffer（1080x2400 约 10MB），
     * 60fps 下每秒要分配 1.2GB，GC 根本追不上，这就是“跑一会巨卡”的直接原因。
     * 识别只需要判定线附近十几行像素，按 rowStride 算偏移直接读就行。
     */
    /**
     * 扫描“触发线”附近，把刚刚跨过触发线的音符排进队列。
     * <p>
     * 为什么不再“看到音符就点”：音符穿过判定线附近十几像素的窄带只要十几毫秒，
     * 60fps 下常常一帧就过去了 → 这就是“有时点得中、有时完全点不中”的根因。
     * 现在改成：音符底边跨过触发线（判定线上方 70 像素）的那一帧登记一次，
     * 用 距离 ÷ 下落速度 算出“还有多久到判定线”，到点了再精确下发。
     */
    private void scanAndSchedule(ByteBuffer buffer, int w, int h, int rowStride, int pixelStride, long now) {
        final int judgeYCap = Math.min(h - 1, Math.max(0, judgeY));
        final int triggerY = Math.max(0, judgeYCap - TRIGGER_UP);
        final int y0 = Math.max(0, triggerY - SCAN_UP);
        final int y1 = Math.min(h - 1, triggerY + SCAN_DOWN);
        if (y1 - y0 < 4) return;

        final int maxRuns = 24;
        int[] runFirst = new int[maxRuns];
        int[] runLast = new int[maxRuns];
        int[] runBottom = new int[maxRuns];
        int[] runClass = new int[maxRuns];
        int[] runHeight = new int[maxRuns];
        int runCount = 0;

        int curFirst = -1, curLast = -1, curClass = COLOR_NONE, curBottom = -1;

        // 逐列从下往上找第一个“音符色”的像素：最靠下的那个就是这一列里离判定线最近的音符
        for (int x = 0; x < w; x += X_STEP) {
            int colBase = x * pixelStride;
            int hitY = -1;
            int cls = COLOR_NONE;
            for (int y = y1; y >= y0; y -= Y_STEP) {
                int off = y * rowStride + colBase;
                int r = buffer.get(off) & 0xFF;
                int g = buffer.get(off + 1) & 0xFF;
                int b = buffer.get(off + 2) & 0xFF;
                cls = noteClass(r, g, b);
                if (cls != COLOR_NONE) {
                    hitY = y;
                    break;
                }
            }
            if (hitY >= 0) {
                if (cls == COLOR_BLUE) statBlue++;
                else if (cls == COLOR_RED) statRed++;
                else statTan++;
                if (curFirst < 0) {
                    curFirst = x;
                    curClass = cls;
                    curBottom = hitY;
                }
                curLast = x;
                if (hitY > curBottom) curBottom = hitY;
            } else if (curFirst >= 0 && x - curLast > SEGMENT_GAP_PX) {
                if (runCount < maxRuns) {
                    runFirst[runCount] = curFirst;
                    runLast[runCount] = curLast;
                    runBottom[runCount] = curBottom;
                    runClass[runCount] = curClass;
                    runCount++;
                }
                curFirst = -1;
                curBottom = -1;
            }
        }
        if (curFirst >= 0 && runCount < maxRuns) {
            runFirst[runCount] = curFirst;
            runLast[runCount] = curLast;
            runBottom[runCount] = curBottom;
            runClass[runCount] = curClass;
            runCount++;
        }

        // ---------- 量每段竖着有多高：Hold 是竖条，Tap/Drag 是矮的 ----------
        for (int i = 0; i < runCount; i++) {
            int cx = (runFirst[i] + runLast[i]) / 2;
            int wcap = runLast[i] - runFirst[i] + X_STEP;
            // 太宽的段是整片背景，根本不用量高度（量了也是白花 CPU）
            if (cx >= 0 && cx < w && wcap <= HOLD_MAX_WIDTH) {
                runHeight[i] = verticalExtent(buffer, cx, runBottom[i], rowStride, pixelStride);
                if (runHeight[i] > 300) runHeight[i] = 300;
            }
        }

        // ---------- 用相邻两帧的位移估下落速度（录制像素/秒） ----------
        int widest = 0;
        for (int i = 0; i < runCount; i++) {
            int wcap = runLast[i] - runFirst[i] + X_STEP;
            if (wcap > widest) widest = wcap;
        }
        if (widest > 0) lastWidestRun = widest;

        long dt = now - prevRunAt;
        if (dt >= 8 && dt <= 120 && prevRunCount > 0 && runCount > 0) {
            float sum = 0f;
            int matched = 0;
            for (int i = 0; i < runCount; i++) {
                int centerX = (runFirst[i] + runLast[i]) / 2;
                int bestJ = -1, bestDx = Integer.MAX_VALUE;
                for (int j = 0; j < prevRunCount; j++) {
                    int dx = Math.abs(prevRunX[j] - centerX);
                    if (dx < bestDx) {
                        bestDx = dx;
                        bestJ = j;
                    }
                }
                if (bestJ >= 0 && bestDx <= 24) {
                    int dy = runBottom[i] - prevRunY[bestJ];
                    if (dy >= 2 && dy <= 60) {   // 确实往下走了一段
                        sum += dy;
                        matched++;
                    }
                }
            }
            if (matched > 0) {
                float v = sum / matched * 1000f / dt;
                // 单帧估出来的速度噪声很大（同一段被拆开、配对错位都会让它翻几倍），
                // 直接 EMA 会让 lead 和 Hold 时长一起抖，所以取最近 5 个样本的中位数
                if (v >= 150f && v <= 2000f) {
                    speedSamples[speedIdx] = v;
                    speedIdx = (speedIdx + 1) % speedSamples.length;
                    if (speedCount < speedSamples.length) speedCount++;
                    noteSpeed = medianSpeed();
                }
            }
        }

        // ---------- 只有“跨过触发线”的音符才登记，保证每个音符只登记一次 ----------
        for (int i = 0; i < runCount; i++) {
            int centerX = (runFirst[i] + runLast[i]) / 2;
            int widthCap = runLast[i] - runFirst[i] + X_STEP;
            int bottom = runBottom[i];

            // 又宽又扁 = 背景亮块（封面、特效带），不是音符：
            // 排进去只会往画面中间乱点，还占手势通道。Hold 是竖条，不受这条影响。
            if (widthCap > BG_MAX_WIDTH && runHeight[i] < BG_MAX_HEIGHT && runClass[i] != COLOR_HOLD) {
                statBg++;
                continue;
            }

            int prevBottom = -1, bestDx = Integer.MAX_VALUE;
            for (int j = 0; j < prevRunCount; j++) {
                int dx = Math.abs(prevRunX[j] - centerX);
                if (dx < bestDx) {
                    bestDx = dx;
                    prevBottom = prevRunY[j];
                }
            }
            boolean matched = prevBottom >= 0 && bestDx <= 24;
            if (matched) {
                // 上一帧也在，且这一帧真的跨过了触发线 → 正常登记
                if (!(prevBottom < triggerY && bottom >= triggerY)) continue;
            } else {
                // 这一帧才第一次看到它（或上一帧位置跳得太远）：
                // 只要它已经在触发线下方，就补登一次，否则这个音符会被彻底漏掉。
                // 第一帧不补登（那时 prevRunCount 还是 0），避免开局把满屏音符一起点。
                if (prevRunCount <= 0 || bottom < triggerY) continue;
            }

            scheduleNote(centerX, widthCap, runHeight[i], runClass[i], bottom, judgeYCap, now);
        }

        int copy = Math.min(runCount, prevRunX.length);
        for (int i = 0; i < copy; i++) {
            prevRunX[i] = (runFirst[i] + runLast[i]) / 2;
            prevRunY[i] = runBottom[i];
        }
        prevRunCount = copy;
        prevRunAt = now;

        if (runCount > 0) statHits++;
        updateStatusIfDue(now);
    }

    /**
     * 把一个刚跨过触发线的音符换算成“什么时候点、点什么、按多久”，放进待发队列。
     */
    private void scheduleNote(int centerX, int widthCap, int heightCap, int cls, int bottomY, int judgeYCap, long now) {
        // 日志里大量 `w=4 h=52 cls=3` 这种“4px 宽、几十像素高”的细条，
        // 其实是长条音符左右两条描边被单独当成了一段（颜色偏米黄）。
        // 它们的中心和真正的音符差不了几像素，点了也只会多一次空点，
        // 但会占掉手势里宝贵的触点名额，所以直接当背景丢掉。
        if (widthCap <= 6 && heightCap <= 8) {
            statBg++;
            return;
        }

        // Hold 的“剩余长度”= 竖着量到的高度 + 触发线到判定线那一小段
        final int holdLengthCap = heightCap + Math.max(0, judgeYCap - bottomY);

        int type;
        int durMs;
        // 宽到离谱的段（整片亮色背景）一定不是 Hold：按下去就是一条假长按，
        // 会把手势通道占住好几秒，看起来就是“巨卡”。所以这里先卡宽度上限。
        boolean holdLike = widthCap >= 12 && widthCap <= HOLD_MAX_WIDTH
                && (cls == COLOR_HOLD
                    ? heightCap >= 30
                    : (widthCap >= HOLD_MIN_WIDTH && heightCap >= HOLD_MIN_HEIGHT));
        if (holdLike) {
            type = AutoClickService.TYPE_HOLD;
            durMs = estimateHoldMs(holdLengthCap);
            lastHoldWidth = widthCap;
            lastHoldLen = holdLengthCap;
        } else if (cls == COLOR_RED) {
            type = AutoClickService.TYPE_FLICK;
            durMs = TAP_MS;
        } else {
            type = AutoClickService.TYPE_TAP;
            durMs = TAP_MS;
        }

        long lead;
        if (noteSpeed > 40f) {
            // 速度估小会让提前量成倍放大（150px/s 时 28px 要 187ms），
            // 那样点击会落在音符到达前一大截 → 游戏里就是 Miss。所以钳一个下限。
            float v = Math.max(noteSpeed, SPEED_FLOOR);
            lead = (long) ((judgeYCap - bottomY) * 1000f / v);
            if (lead < 0) lead = 0;
            if (lead > LEAD_MAX_MS) lead = LEAD_MAX_MS;
        } else {
            lead = 0;   // 速度还没测出来，先“看到就点”，至少不会完全不点
        }
        long arrival = now + lead + timingBias;
        if (arrival < now) arrival = now;   // ⏱ 调成负数时也别排到过去
        int x = centerX * captureScale;
        int bucket = x / 40;

        // 去重：同一位置、到达时间几乎一样 → 是同一个音符被重复登记了
        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingNote p = pending.get(i);
            if (p.bucket == bucket && Math.abs(p.arrival - arrival) < 45) return;
        }

        PendingNote p = new PendingNote();
        p.arrival = arrival;
        p.x = x;
        p.type = type;
        p.durMs = durMs;
        p.bucket = bucket;
        pending.add(p);

        Log.d(TAG, String.format(Locale.US,
                "queue type=%d x=%d lead=%dms dur=%dms speed=%d w=%d h=%d cls=%d",
                type, x, lead, durMs, (int) noteSpeed, widthCap, heightCap, cls));
    }

    /**
     * Hold 要按多久 = 条的剩余长度 ÷ 下落速度。
     * 长度长的条代表按得久，速度越快同样的长度按得越短。
     */
    private int estimateHoldMs(int lengthCap) {
        if (noteSpeed < 40f) return HOLD_FALLBACK_MS;   // 速度还没测出来：先按久一点
        // 速度普遍会被估高一点，所以先打个折 => 算出来的时长偏长 => 按到底不会提前松手
        // 但反过来速度偶尔被估成 150~220，时长会翻两倍多，直接按到 3 秒上限把后面的音符全堵住，
        // 所以速度钳一个下限：最慢按 300px/s 算。
        float v = Math.max(noteSpeed, SPEED_FLOOR) * HOLD_SPEED_BIAS;
        int ms = (int) (lengthCap * 1000f / v) + HOLD_MARGIN_MS;
        if (ms < HOLD_MIN_MS) ms = HOLD_MIN_MS;
        if (ms > HOLD_MAX_MS) ms = HOLD_MAX_MS;
        Log.d(TAG, String.format(Locale.US, "hold len=%d speed=%d -> %dms",
                lengthCap, (int) noteSpeed, ms));
        return ms;
    }

    /**
     * 排程器：每 8ms 看一眼队列里有没有到点的音符，到点就一次性下发。
     * <p>
     * 关键点：一次手势通道只能跑一个手势，所以这里自己排队——
     * 上一发还没结束就等（now &lt; gestureEndsAt）。
     * 长按会占住通道很久，所以下长按的同时把长按期间到点的轻点预排进同一个手势
     * （见 {@link AutoClickService#performBatchAt}），只有超出预排窗口的才会丢。
     */
    private final Runnable schedulerTick = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            long now = SystemClock.uptimeMillis();

            if (now < gestureEndsAt) {
                workerHandler.postDelayed(this, SCHEDULER_TICK_MS);
                return;
            }
            if (now < holdBusyUntil) {
                for (int i = pending.size() - 1; i >= 0; i--) {
                    if (pending.get(i).arrival <= now + 8) {
                        pending.remove(i);
                        statDropped++;
                    }
                }
                workerHandler.postDelayed(this, SCHEDULER_TICK_MS);
                return;
            }

            // 下发太密也会卡：系统输入队列塞满之后游戏会开始丢帧。
            // 45ms 一次足够覆盖 60fps 的谱面密度。
            if (now - lastFireAt < MIN_FIRE_GAP_MS) {
                workerHandler.postDelayed(this, SCHEDULER_TICK_MS);
                return;
            }

            int[] xs = new int[MAX_STROKES];
            int[] types = new int[MAX_STROKES];
            int[] durs = new int[MAX_STROKES];
            int[] starts = new int[MAX_STROKES];
            PendingNote[] notes = new PendingNote[MAX_STROKES];
            int n = 0;
            long maxEnd = 0;
            long holdDur = 0;

            // 第一步：收“现在到未来 70ms 之间”该点的音符（到点的和快到的都在这一发里）
            for (int i = pending.size() - 1; i >= 0 && n < MAX_STROKES; i--) {
                PendingNote p = pending.get(i);
                if (p.arrival > now + BATCH_LOOKAHEAD_MS) continue;
                if (now - p.arrival > LATE_TOLERANCE_MS) {
                    pending.remove(i);
                    statDropped++;
                    continue;
                }
                notes[n] = p;
                xs[n] = p.x;
                types[n] = p.type;
                durs[n] = p.durMs;
                starts[n] = (int) Math.max(0, p.arrival - now);
                if (p.type == AutoClickService.TYPE_HOLD && p.durMs > holdDur) holdDur = p.durMs;
                maxEnd = Math.max(maxEnd, starts[n] +
                        (p.type == AutoClickService.TYPE_HOLD ? p.durMs + 12L : TAP_MS + 12L));
                n++;
            }

            // 第二步：这一发里有长按，就把长按期间（最多 PRESCHEDULE_MS）要点的音符
            // 一起预排进同一个手势——它们的 startTime 是相对现在算的，
            // 会在长按还按着的时候依次落下，不用再被系统“一次只能一个手势”卡住。
            if (holdDur > 0) {
                long horizon = now + Math.min(holdDur, PRESCHEDULE_MS);
                for (int i = pending.size() - 1; i >= 0 && n < MAX_STROKES; i--) {
                    PendingNote p = pending.get(i);
                    if (p.arrival <= now + BATCH_LOOKAHEAD_MS) continue;   // 第一步已经收过
                    if (p.arrival > horizon) continue;
                    if (p.type == AutoClickService.TYPE_HOLD) continue;   // 一条手势里不放第二个长按
                    notes[n] = p;
                    xs[n] = p.x;
                    types[n] = p.type;
                    durs[n] = p.durMs;
                    starts[n] = (int) Math.max(1, p.arrival - now);
                    maxEnd = Math.max(maxEnd, starts[n] + TAP_MS + 12L);
                    n++;
                }
            }

            if (n == 0) {
                workerHandler.postDelayed(this, SCHEDULER_TICK_MS);
                return;
            }

            int y = judgeY * captureScale;
            boolean ok = AutoClickService.performBatchAt(xs, types, durs, starts, y, n, null);
            if (ok) {
                // 按对象删，不用下标——万一队列被别处改过也不会 IndexOutOfBounds
                for (int k = 0; k < n; k++) pending.remove(notes[k]);
                gestureEndsAt = now + maxEnd;
                boolean hasHold = holdDur > 0;
                if (hasHold) {
                    // 预排窗口之外的音符还是发不出去，用这个标志把它们直接丢掉，别白试
                    holdBusyUntil = now + Math.min(holdDur, PRESCHEDULE_MS);
                    statHoldFires++;
                }
                lastFireAt = now;
                statClicks++;
                totalClicks++;
                Log.d(TAG, "fire " + n + " note(s) at y=" + y + " hold=" + hasHold
                        + " dur=" + holdDur + " span=" + maxEnd);
            } else {
                Log.w(TAG, "dispatch failed, retry next tick");
            }

            workerHandler.postDelayed(this, SCHEDULER_TICK_MS);
        }
    };

    /**
     * 每帧找一次判定线（它会上下移动）。
     * <p>
     * Phigros 系那条判定线横贯屏幕，特点是“白、够长、是这一带最亮的一行”。
     * 先在上次的 y 上下 ±100 像素内找，跟着它走；还没锁定时全画面找一次。
     *
     * @return 判定线在录制坐标系里的 y；找不到返回 -1
     */
    private int trackJudgeLine(ByteBuffer buffer, int w, int h, int rowStride, int pixelStride, int prev) {
        int top, bottom;
        int rowStep = 3;
        int xStep = 12;
        if (prev < 0) {
            top = (int) (h * JUDGE_SEARCH_TOP);
            bottom = (int) (h * JUDGE_SEARCH_BOTTOM);
            rowStep = 2;
            xStep = 8;
        } else {
            top = Math.max(0, prev - JUDGE_TRACK_RANGE);
            bottom = Math.min(h - 1, prev + JUDGE_TRACK_RANGE);
        }
        if (bottom - top < 8) return -1;

        int samples = 0;
        for (int x = 0; x < w; x += xStep) samples++;

        int bestY = -1, bestScore = 0;
        int need = (int) (samples * 0.45f);

        for (int y = top; y <= bottom; y += rowStep) {
            int rowBase = y * rowStride;
            int bright = 0;
            for (int x = 0; x < w; x += xStep) {
                int off = rowBase + x * pixelStride;
                int r = buffer.get(off) & 0xFF;
                int g = buffer.get(off + 1) & 0xFF;
                int b = buffer.get(off + 2) & 0xFF;
                int mx = Math.max(r, Math.max(g, b));
                int mn = Math.min(r, Math.min(g, b));
                if (mn >= 165 && mx - mn <= 50) bright++;   // 白，且不太偏色
            }
            if (bright >= need && bright > bestScore) {
                bestScore = bright;
                bestY = y;
            }
        }
        return bestY;
    }

    // 音符颜色分类
    private static final int COLOR_NONE = 0;
    private static final int COLOR_BLUE = 1;   // 蓝色 Tap
    private static final int COLOR_RED = 2;    // 红色 Flick
    private static final int COLOR_TAN = 3;    // 米黄方块 Drag（会被扫到就点，宽判不亏）
    private static final int COLOR_HOLD = 4;   // 浅蓝竖直长条 Hold

    /**
     * 颜色判定。阈值不是拍脑袋写的，是拿你那张 1920x1080 抓帧统计出来的：
     * 蓝 (76,160,229)、红 (215,42,38)、米黄 (179,158,74)、金色描边 (251,153,19)、
     * **Hold 浅蓝 (176,234,251)/(162,234,254)**。
     * 金色描边故意排除（|r-g| 限制），否则每条音符的镶边都会被当成一个音符。
     */
    private static int noteClass(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 120) return COLOR_NONE;        // 太暗，不是音符
        if (max - min < 60) return COLOR_NONE;   // 太灰（白字/灰底），不是音符

        // Hold：很亮的浅蓝（g 也高）。Drag 的蓝 g 只有 160 左右，用它区分开。
        if (b >= 220 && g >= 190 && b - r >= 30 && g - r >= 30) return COLOR_HOLD;
        if (b >= 150 && b - r >= 70 && b - g >= 35 && g >= 90) return COLOR_BLUE;
        if (r >= 150 && r - g >= 90 && r - b >= 85) return COLOR_RED;
        if (r >= 150 && g >= 120 && b <= 140 && r - b >= 70 && g - b >= 40
                && Math.abs(r - g) <= 70) return COLOR_TAN;
        return COLOR_NONE;
    }

    /** 最近几个速度样本的中位数（样本少时就是它们的平均/单值） */
    private float medianSpeed() {
        int n = speedCount;
        if (n <= 0) return 0f;
        float[] tmp = new float[n];
        System.arraycopy(speedSamples, 0, tmp, 0, n);
        java.util.Arrays.sort(tmp);
        return tmp[n / 2];
    }

    /**
     * 从 (x, yBottom) 往上数，同色音符像素连续延伸多高（录制像素）。
     * Hold 是竖直长条，往上能延伸几百像素；Tap 是菱形，二三十；Drag 是横条，十几。
     */
    private int verticalExtent(ByteBuffer buffer, int x, int yBottom, int rowStride, int pixelStride) {
        if (x < 0 || yBottom < 0) return 0;
        int colBase = x * pixelStride;
        int limit = Math.min(yBottom, 320);
        int gap = 0;
        int lastHit = -1;
        boolean seenNote = false;
        // 隔行采样（y -= 2）：Hold 有几十上百像素高，隔行完全够用，
        // 但每帧要量 20 多段，隔行能把这里的开销直接砍一半
        for (int y = yBottom; y >= yBottom - limit; y -= 2) {
            int off = y * rowStride + colBase;
            int r = buffer.get(off) & 0xFF;
            int g = buffer.get(off + 1) & 0xFF;
            int b = buffer.get(off + 2) & 0xFF;
            boolean note = noteClass(r, g, b) != COLOR_NONE;
            int mx = Math.max(r, Math.max(g, b));
            int mn = Math.min(r, Math.min(g, b));
            // Hold 条的两端有白色高光、中间还可能夹一条白线。
            // 这些白色像素必须算“条子的一部分”，否则一条 300px 的 Hold 会被量成 4px，
            // 时长就按几毫秒算，长按一下就松手 —— 之前“长 Hold 没反应”就是这个原因。
            boolean partOfBar = note || (seenNote && mx >= 150 && mx - mn <= 70);
            if (partOfBar) {
                if (note) seenNote = true;
                lastHit = y;
                gap = 0;
            } else if (++gap > 2) {
                break;
            }
        }
        return lastHit < 0 ? 0 : yBottom - lastHit;
    }

    // ==================== 抓帧 / 旋转处理 / 状态栏 ====================

    /** 手动抓一帧存 PNG，用来量判定线位置和看音符颜色 */
    private void saveFrame(ByteBuffer buffer, int w, int h, int rowStride, int pixelStride) {
        try {
            int rowPadding = Math.max(rowStride - pixelStride * w, 0);
            int paddedWidth = w + rowPadding / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);

            Bitmap shot = padded;
            if (paddedWidth != w) {
                shot = Bitmap.createBitmap(padded, 0, 0, w, h);
                padded.recycle();
            }

            File dir = getExternalFilesDir(null);
            if (dir != null && !dir.exists()) dir.mkdirs();
            String name = "capture_" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date())
                    + "_" + w + "x" + h + ".png";
            File file = new File(dir, name);

            FileOutputStream out = new FileOutputStream(file);
            shot.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            shot.recycle();

            final String path = file.getAbsolutePath();
            Log.i(TAG, "frame saved " + path);
            mainHandler.post(() -> Toast.makeText(this,
                    "截图已保存：\n" + path, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.w(TAG, "save frame failed", e);
            mainHandler.post(() -> Toast.makeText(this,
                    "截图保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    /**
     * 每秒检查一次画面尺寸：手机从竖屏切到横屏（进游戏）时，
     * 虚拟屏还停在竖屏尺寸，画面会被缩放/旋转，坐标全错 —— 必须重建。
     */
    private final Runnable displayWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!projectionReady) return;
            try {
                DisplayMetrics dm = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(dm);
                if (dm.widthPixels != displayWidth || dm.heightPixels != displayHeight) {
                    resizeCapture(dm.widthPixels, dm.heightPixels, dm.densityDpi);
                }
            } catch (Exception e) {
                Log.w(TAG, "display check failed", e);
            }
            workerHandler.postDelayed(this, DISPLAY_CHECK_MS);
        }
    };

    private void resizeCapture(int w, int h, int dpi) {
        try {
            Log.i(TAG, "resize capture " + displayWidth + "x" + displayHeight + " -> " + w + "x" + h);
            applyDisplaySize(w, h, dpi);

            ImageReader newReader = ImageReader.newInstance(captureWidth, captureHeight,
                    PixelFormat.RGBA_8888, 2);
            newReader.setOnImageAvailableListener(this::onImageAvailable, workerHandler);

            if (virtualDisplay != null) {
                virtualDisplay.resize(captureWidth, captureHeight, captureDpi);
                virtualDisplay.setSurface(newReader.getSurface());
            }

            ImageReader old = imageReader;
            imageReader = newReader;
            if (old != null) old.close();

            final String msg = "画面已重建：" + w + "x" + h;
            mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.w(TAG, "resize failed", e);
        }
    }

    /** ⏱ 按钮上显示当前补偿值，比如 “+10” / “-20” / “0” */
    private String tuneLabel() {
        return (timingBias > 0 ? "+" : "") + timingBias;
    }

    private void updateStatusIfDue(long now) {
        if (now - lastStatAt < 500) return;
        long elapsed = lastStatAt == 0 ? 500 : now - lastStatAt;
        final int fps = (int) (statFrames * 1000 / Math.max(elapsed, 1));
        final long clicksPerSec = statClicks * 1000 / Math.max(elapsed, 1);
        final long droppedPerSec = statDropped * 1000 / Math.max(elapsed, 1);
        final int queue = pending.size();
        final long costAvg = statFrameCostSum / Math.max(statFrames, 1);
        final String text = String.format(Locale.US,
                "%dx%d %dfps c:%d/%d 队:%d 点:%d/s 丢:%d/s 背:%d 总:%d 条:%d 宽:%d 长:%d v:%d",
                displayWidth, displayHeight, fps, costAvg, statFrameCostMax,
                queue, clicksPerSec, droppedPerSec, statBg,
                totalClicks, lastWidestRun, lastHoldWidth, lastHoldLen, (int) noteSpeed);

        // 每 2 秒往 Logcat 打一行：识别帧率 + 队列/下发/丢弃 + 判定线与速度（调参就看这个）
        // cost：这一帧的识别耗时（均值/最大）。它要是接近 16ms，就说明卡在“算不过来”。
        if (now - lastLogAt >= 2000 && statFrames > 0) {
            lastLogAt = now;
            Log.d(TAG, String.format(Locale.US,
                    "fps=%d cost=%d/%dms 队列=%d 点击=%d 丢弃=%d 背景=%d hold=%d 蓝=%d 红=%d 米黄=%d judgeY=%d speed=%d 条=%d 宽=%d 长=%d bias=%d",
                    fps, costAvg, statFrameCostMax, queue, statClicks, statDropped, statBg, statHoldFires,
                    statBlue, statRed, statTan,
                    judgeY, (int) noteSpeed, lastWidestRun, lastHoldWidth, lastHoldLen, timingBias));
        }

        statFrames = 0;
        statHits = 0;
        statClicks = 0;
        statDropped = 0;
        statBg = 0;
        statBlue = 0;
        statRed = 0;
        statTan = 0;
        statFrameCostSum = 0;
        statFrameCostMax = 0;
        statHoldFires = 0;
        lastStatAt = now;
        mainHandler.post(() -> {
            if (tvStatus != null) tvStatus.setText(text);
        });
    }

    // ==================== 开始 / 停止 ====================

    private void startAutoPlay() {
        if (mediaProjection == null) {
            Toast.makeText(this, "请先授权录屏！", Toast.LENGTH_SHORT).show();
            return;
        }
        isRunning = true;
        // 这些状态都是 worker 线程在读写，这里必须丢回 worker 去改，
        // 否则按钮点得快的时候会和识别线程抢 pending，直接抛 IndexOutOfBounds 把 App 干掉。
        workerHandler.post(() -> {
            gestureEndsAt = 0;
            holdBusyUntil = 0;
            pending.clear();
            lastStatAt = 0;
            judgeCalibrated = false;   // 每局开始重新找一次判定线，换个游戏/换首歌也能自适应
            judgeY = -1;
            noteSpeed = 0f;
            speedCount = 0;
            speedIdx = 0;
            prevRunCount = 0;
            lastHoldWidth = 0;
            lastHoldLen = 0;
            lastFireAt = 0;
            statFrameCostSum = 0;
            statFrameCostMax = 0;
            statHoldFires = 0;
            statBg = 0;
            costEma = 0f;
            skipEvery = 0;
            skipCounter = 0;
        });
        workerHandler.removeCallbacks(schedulerTick);
        workerHandler.post(schedulerTick);
        Toast.makeText(this, "开始自动识别并打歌！", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "autoplay started");
    }

    private void stopAutoPlay() {
        isRunning = false;
        workerHandler.removeCallbacks(schedulerTick);
        workerHandler.post(pending::clear);
        Toast.makeText(this, "已停止识别", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "autoplay stopped, total clicks=" + totalClicks);
    }

    // ==================== 释放 ====================

    private void handleProjectionStopped() {
        if (!projectionReady) return;
        projectionReady = false;
        isRunning = false;
        workerHandler.removeCallbacks(displayWatchdog);
        releaseVirtualDisplay();
        mediaProjection = null;
        projectionCallback = null;
        stopForeground(true);
        Toast.makeText(this, "录屏已被系统停止", Toast.LENGTH_SHORT).show();
    }

    private void releaseVirtualDisplay() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        projectionReady = false;
        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        releaseVirtualDisplay();

        if (mediaProjection != null) {
            if (projectionCallback != null) {
                try {
                    mediaProjection.unregisterCallback(projectionCallback);
                } catch (Exception ignored) {
                }
            }
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
            projectionCallback = null;
        }

        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {
            }
            floatingView = null;
        }

        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }

        stopForeground(true);
        stopSelf();
    }
}
