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

    // 使用方式：人工点击右下角“保存”，进入保存后的确认/测试界面后，再点悬浮按钮“开始”。
    // 脚本从确认/测试界面开始计时：0ms 点确认/测试/开始；后续节点沿用原成功视频的跳跃节奏。
    private final long[] tapTimesMs = new long[] {
            0L,      // 确认/测试/开始
            862L,    // 第一跳
            1373L,   // 第二跳
            2308L,   // 第三跳
            3396L,   // 右侧关键全包跳/折返点
            5704L,   // 后续跳
            7855L,
            8785L,
            9489L,
            10933L
    };

    // 当前手机：1260 x 2720，横屏实际按 2720 x 1260 计算。全部使用比例坐标。
    // 注意：不再点击“保存”，保存由人工完成。
    private static final float START_X_RATIO = 0.398f;
    private static final float START_Y_RATIO = 0.946f;

    // 跳跃是单点，全屏任意空白区域理论等效；这里放在地图中下部，避开按钮、悬浮窗和系统导航区。
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
                    return false;
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
            return false;
        });

        windowManager.addView(floatButton, params);
    }

    private void toggleRun() {
        if (running) stopRun(); else startRun();
    }

    private void startRun() {
        running = true;
        if (floatButton != null) floatButton.setText("停止");

        // 用一个系统手势承载全部点击，减少 Handler 逐次调度导致的毫秒抖动。
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
            float xr = (i == 0) ? START_X_RATIO : JUMP_X_RATIO;
            float yr = (i == 0) ? START_Y_RATIO : JUMP_Y_RATIO;
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
