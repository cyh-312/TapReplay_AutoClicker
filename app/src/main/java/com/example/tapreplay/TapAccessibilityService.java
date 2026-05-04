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

    // 以“点击保存”作为 0ms，包含保存点击、第二次非跳跃点击、后续所有跳跃点击。
    private final long[] tapTimesMs = new long[] {
            0L,
            453L,
            862L,
            1373L,
            2308L,
            3396L,
            5704L,
            7855L,
            8785L,
            9489L,
            10933L
    };

    // 当前手机：1260 x 2720，横屏实际按 2720 x 1260 计算。
    // 坐标改为比例坐标，避免不同分辨率下第一下保存点偏。
    // 0：右下角“保存”；1：底部“测试/开始”；2+：游戏内跳跃点击区域。
    private static final float SAVE_X_RATIO = 0.903f;
    private static final float SAVE_Y_RATIO = 0.946f;
    private static final float TEST_X_RATIO = 0.398f;
    private static final float TEST_Y_RATIO = 0.946f;
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
        if (running) {
            stopRun();
        } else {
            startRun();
        }
    }

    private void startRun() {
        running = true;
        if (floatButton != null) floatButton.setText("停止");

        for (int i = 0; i < tapTimesMs.length; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                if (!running) return;

                if (index == 0) {
                    performTapByRatio(SAVE_X_RATIO, SAVE_Y_RATIO);
                } else if (index == 1) {
                    performTapByRatio(TEST_X_RATIO, TEST_Y_RATIO);
                } else {
                    performTapByRatio(JUMP_X_RATIO, JUMP_Y_RATIO);
                }

                if (index == tapTimesMs.length - 1) stopRun();
            }, tapTimesMs[i]);
        }
    }

    private void stopRun() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (floatButton != null) floatButton.setText("开始");
    }

    private void performTapByRatio(float xr, float yr) {
        int[] size = getScreenSize();
        int x = Math.round(size[0] * xr);
        int y = Math.round(size[1] * yr);
        performTap(x, y);
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

        // 游戏强制横屏，确保坐标按横屏宽高计算。
        if (height > width) {
            int t = width;
            width = height;
            height = t;
        }
        return new int[] { width, height };
    }

    private void performTap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 35);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
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
