package com.example.tapreplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.view.accessibility.AccessibilityEvent;

public class TapAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private Button floatButton;
    private boolean running = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 使用方式：
    // 1. 你先人工点右下角“保存”。
    // 2. 出现“连续两次抵达宝箱的位置，保存地宫设置。”提示。
    // 3. 这时点悬浮按钮“开始”。悬浮按钮不隐藏。
    // 4. 脚本 0ms 点提示界面空白处，让游戏真正开始。
    // 5. 后续跳跃时间以这一次“提示确认/开始”点击为 0 点。
    //
    // 根据教学视频重新校准：
    // 保存点击约 402.8ms；提示确认/开始约 808.1ms；
    // 跳跃点约 1216.9、1728.2、2662.8、3750.8、6058.5、8209.3、9140.0、9843.9、11288.1ms。
    // 因此相对“提示确认/开始”的跳跃时间为：409、920、1855、2943、5250、7401、8332、9036、10480ms。
    private final long[] tapTimesMs = new long[] {
            0L,      // 点提示界面空白处：确认并开始游戏
            409L,    // 第一跳
            920L,    // 第二跳
            1855L,   // 第三跳
            2943L,   // 右侧关键全包跳/折返点
            5250L,   // 后续跳
            7401L,
            8332L,
            9036L,
            10480L
    };

    // 当前手机：1260 x 2720，横屏实际按 2720 x 1260 计算。全部使用比例坐标。
    // 提示界面确认：点屏幕中部/中下部空白处，不点“测试”。
    private static final float PROMPT_X_RATIO = 0.500f;
    private static final float PROMPT_Y_RATIO = 0.650f;

    // 跳跃单点区域。放在地图中下部，避开悬浮按钮、底部按钮和系统导航区。
    private static final float JUMP_X_RATIO = 0.500f;
    private static final float JUMP_Y_RATIO = 0.720f;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        showFloatingButton();
    }

    private void showFloatingButton() {
        if (!Settings.canDrawOverlays(this) || floatButton != null) return;

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        floatButton = new Button(this);
        floatButton.setText("开始");
        floatButton.setTextColor(Color.WHITE);
        floatButton.setBackgroundColor(Color.argb(210, 211, 47, 47));
        floatButton.setTextSize(14);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 180;

        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final int[] startParamX = new int[1];
        final int[] startParamY = new int[1];
        final long[] downTime = new long[1];

        floatButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = (int) event.getRawX();
                    startY[0] = (int) event.getRawY();
                    startParamX[0] = params.x;
                    startParamY[0] = params.y;
                    downTime[0] = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startParamX[0] + ((int) event.getRawX() - startX[0]);
                    params.y = startParamY[0] + ((int) event.getRawY() - startY[0]);
                    windowManager.updateViewLayout(floatButton, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    long dt = System.currentTimeMillis() - downTime[0];
                    int dx = Math.abs((int) event.getRawX() - startX[0]);
                    int dy = Math.abs((int) event.getRawY() - startY[0]);
                    if (dt < 250 && dx < 12 && dy < 12) toggleRun();
                    return true;
            }
            return true;
        });

        windowManager.addView(floatButton, params);
    }

    private void toggleRun() {
        if (running) stopRun(); else startRun();
    }

    private void startRun() {
        running = true;
        if (floatButton != null) floatButton.setText("停止");

        // 不再隐藏悬浮按钮，避免额外 45ms 左右的延迟；完整点击序列立即下发。
        dispatchFullTapSequence();
        handler.postDelayed(this::stopRun, tapTimesMs[tapTimesMs.length - 1] + 500L);
    }

    private void stopRun() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (floatButton != null) floatButton.setText("开始");
    }

    private void dispatchFullTapSequence() {
        int[] size = getScreenSize();
        GestureDescription.Builder builder = new GestureDescription.Builder();

        for (int i = 0; i < tapTimesMs.length; i++) {
            float xr = (i == 0) ? PROMPT_X_RATIO : JUMP_X_RATIO;
            float yr = (i == 0) ? PROMPT_Y_RATIO : JUMP_Y_RATIO;
            int x = Math.round(size[0] * xr);
            int y = Math.round(size[1] * yr);
            addTapStroke(builder, x, y, tapTimesMs[i], 28L);
        }

        dispatchGesture(builder.build(), null, null);
    }

    private void addTapStroke(GestureDescription.Builder builder, int x, int y, long startMs, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        builder.addStroke(new GestureDescription.StrokeDescription(path, startMs, durationMs));
    }

    private int[] getScreenSize() {
        int width;
        int height;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }

        if (height > width) {
            int t = width;
            width = height;
            height = t;
        }
        return new int[] { width, height };
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && floatButton != null) {
            windowManager.removeView(floatButton);
            floatButton = null;
        }
    }
}
