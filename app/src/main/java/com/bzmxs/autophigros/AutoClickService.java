package com.bzmxs.autophigros;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickService extends AccessibilityService {

    /** 手势类型：轻点（Tap / Drag 都用它） */
    public static final int TYPE_TAP = 0;
    /** 手势类型：上滑（Flick 需要真的“划”一下才算） */
    public static final int TYPE_FLICK = 1;
    /** 手势类型：长按（Hold，原地按住 durationMs 再抬起） */
    public static final int TYPE_HOLD = 2;

    /** Flick 上滑的距离（px）：一次划 55px，大约 45ms */
    private static final int FLICK_DY = 55;
    private static final int FLICK_MS = 45;

    private static AutoClickService instance;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
    }

    /** 单点点击 */
    public static boolean performClick(int x, int y) {
        return performMultiTap(new int[]{x}, 1, y, 1, null);
    }

    /**
     * 一次手势里同时按下多个点，且每个点可以是“轻点”或“上滑”。
     * <p>
     * 为什么合并：多次调用 dispatchGesture 会排队，越排越慢；合并成一个手势就没有排队问题。
     *
     * @param xs         要点击的 x 坐标数组（屏幕像素）
     * @param types      每个点的类型，TYPE_TAP / TYPE_FLICK
     * @param count      xs 中有效元素的个数
     * @param y          y 坐标（判定线）
     * @param durationMs 轻点的按下时长，太小（1~5ms）有些设备/游戏会丢触摸
     * @param onFinished 手势结束（完成或被取消）时回调
     */
    public static boolean performNotes(int[] xs, int[] types, int count, int y,
                                       int durationMs, Runnable onFinished) {
        if (instance == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (xs == null || count <= 0) return false;

        int n = Math.min(count, maxStrokes());
        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (int i = 0; i < n; i++) {
            Path path = new Path();
            if (types[i] == TYPE_FLICK) {
                // 从线下往上划：起点在判定线下方一点，终点在上方，走 45ms
                path.moveTo(xs[i], y + FLICK_DY);
                path.lineTo(xs[i], y - FLICK_DY);
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, FLICK_MS));
            } else {
                path.moveTo(xs[i], y);
                builder.addStroke(new GestureDescription.StrokeDescription(
                        path, 0, Math.max(durationMs, 1)));
            }
        }
        return dispatch(builder, onFinished);
    }

    /**
     * 长按（Hold）。
     * <p>
     * 长按是“一个点在原地按住 durationMs 再抬起”，不能并进上面的合并手势里：
     * 一个 600ms 的长按会把那一帧的手势整个拖住，后面的 Tap 就排队了。
     * 所以长按单独发，而且不占用调用方的“忙”标志。
     *
     * @return 是否成功下发
     */
    public static boolean performHold(int x, int y, int durationMs) {
        if (instance == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(
                path, 0, Math.max(durationMs, 50)));
        return dispatch(builder, null);
    }

    /**
     * 一次手势里同时按下多个点（全部当轻点处理）。
     *
     * @param xs         要点击的 x 坐标数组（屏幕像素）
     * @param count      xs 中有效元素的个数
     * @param y          y 坐标（判定线）
     * @param durationMs 按下持续时长
     * @param onFinished 手势结束（完成或被取消）时回调
     */
    public static boolean performMultiTap(int[] xs, int count, int y, int durationMs, Runnable onFinished) {
        if (xs == null || count <= 0) return false;
        int n = Math.min(count, maxStrokes());
        int[] types = new int[n];
        return performNotes(xs, types, n, y, durationMs, onFinished);
    }

    /**
     * 一次手势里同时下发多种音符：轻点 / 上滑 / 长按，各自的按下时长可以不一样。
     * <p>
     * 这是给“到点才发”的排程器用的：同一时刻有好几个音符时，合并成一个手势，
     * 既不会互相排队，也不会出现“发第二个时系统说手势通道忙”。
     *
     * @param xs      x 坐标（屏幕像素）
     * @param types   TYPE_TAP / TYPE_FLICK / TYPE_HOLD
     * @param dursMs  每个触点的按下时长；Flick 会用固定值忽略它
     * @param y       y 坐标（判定线，屏幕像素）
     * @param count   有效个数
     */
    public static boolean performBatch(int[] xs, int[] types, int[] dursMs, int y, int count,
                                       Runnable onFinished) {
        return performBatchAt(xs, types, dursMs, null, y, count, onFinished);
    }

    /**
     * 和 {@link #performBatch} 一样，但允许调用方传一个 startsMs 数组。
     * <p>
     * ⚠️ 血的教训：**多指手势里所有触点必须同一瞬间按下**。
     * 如果同一个手势里 0 号触点 startTime=0、1 号 startTime=20，
     * Android 的 InputDispatcher 在拆分(MotionEvent)时会自己断言：
     * <pre>
     * Abort message: 'Split motion event has mismatching downTime and eventTime
     * for ACTION_DOWN, motionEntry=MotionEvent(... action=POINTER_DOWN(0),
     * pointers=[0: (824.0, 862.0), 1: (1116.0, 862.0)]), splitDownTime=...'
     * </pre>
     * 然后 <b>system_server 直接 SIGABRT 自杀</b>，表现就是整台设备/模拟器黑屏重载。
     * 所以这里不管调用方传什么，都把整批统一成同一个起始时刻（取最小值），
     * 让所有手指真的同时落下。要“错开时间”就只能分几次手势下发。
     *
     * @param startsMs 各触点的延迟（毫秒），null 表示全部立即；实际会被统一成最小值
     */
    public static boolean performBatchAt(int[] xs, int[] types, int[] dursMs, int[] startsMs,
                                         int y, int count, Runnable onFinished) {
        if (instance == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (xs == null || count <= 0) return false;

        int n = Math.min(count, maxStrokes());

        // 统一起始时刻：多指手势的 downTime 必须完全一致，否则 system_server 会崩
        long commonStart = 0;
        if (startsMs != null) {
            commonStart = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) commonStart = Math.min(commonStart, Math.max(0, startsMs[i]));
            if (commonStart == Long.MAX_VALUE) commonStart = 0;
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (int i = 0; i < n; i++) {
            long start = commonStart;
            Path path = new Path();
            switch (types[i]) {
                case TYPE_FLICK: {
                    path.moveTo(xs[i], y + FLICK_DY);
                    path.lineTo(xs[i], y - FLICK_DY);
                    builder.addStroke(new GestureDescription.StrokeDescription(path, start, FLICK_MS));
                    break;
                }
                case TYPE_HOLD: {
                    path.moveTo(xs[i], y);
                    builder.addStroke(new GestureDescription.StrokeDescription(
                            path, start, Math.max(dursMs[i], 50)));
                    break;
                }
                default: {
                    path.moveTo(xs[i], y);
                    builder.addStroke(new GestureDescription.StrokeDescription(
                            path, start, Math.max(dursMs[i], 1)));
                }
            }
        }
        return dispatch(builder, onFinished);
    }

    private static boolean dispatch(GestureDescription.Builder builder, Runnable onFinished) {
        if (instance == null) return false;
        GestureDescription description = builder.build();
        GestureResultCallback callback = null;
        if (onFinished != null) {
            callback = new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    onFinished.run();
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    onFinished.run();
                }
            };
        }
        return instance.dispatchGesture(description, callback, null);
    }

    /** 一次手势里最多能放几个触点（系统限制，一般是 10） */
    public static int maxStrokes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Math.max(1, GestureDescription.getMaxStrokeCount());
        }
        return 1;
    }
}
