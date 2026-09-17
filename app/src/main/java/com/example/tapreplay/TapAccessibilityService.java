package com.example.tapreplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务只负责：悬浮条、上滑、提供系统手势能力。
 * 分享流程全部交给 ShareFlowV5，避免旧分享逻辑和新状态机互相干扰。
 */
public class TapAccessibilityService extends AccessibilityService {
    private static volatile TapAccessibilityService instance;

    private WindowManager wm;
    private LinearLayout overlay;
    private TextView button;
    private TextView status;
    private WindowManager.LayoutParams params;

    public static TapAccessibilityService getInstance() {
        return instance;
    }

    public static void setOverlayStatus(String text) {
        TapAccessibilityService s = instance;
        if (s != null) {
            s.runOnUiThread(() -> {
                if (s.status != null) s.status.setText(text == null ? "" : text);
            });
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        // 再补一次关键 flags，确保能读取资源 ID、非重要节点和多窗口。
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        } catch (Throwable ignored) {}

        showOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 分享状态机按需主动读取最新界面，不依赖事件缓存。
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        AutomationController.get(this).stop();
        if (wm != null && overlay != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) {}
        }
        overlay = null;
        instance = null;
        super.onDestroy();
    }

    private void runOnUiThread(Runnable r) {
        new android.os.Handler(getMainLooper()).post(r);
    }

    private void showOverlay() {
        if (overlay != null) return;

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(4), dp(3), dp(5), dp(3));
        overlay.setBackgroundColor(Color.argb(188, 18, 18, 18));

        button = new TextView(this);
        button.setText("开始");
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(4), dp(3), dp(4), dp(3));
        overlay.addView(button, new LinearLayout.LayoutParams(dp(52), dp(34)));

        status = new TextView(this);
        status.setText("就绪");
        status.setTextColor(Color.WHITE);
        status.setTextSize(10);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(6), 0, dp(3), 0);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        overlay.addView(status, new LinearLayout.LayoutParams(dp(235), dp(44)));

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(10);
        params.y = dp(170);

        final float[] downRaw = new float[2];
        final int[] downXY = new int[2];
        final boolean[] moved = new boolean[1];

        overlay.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    downXY[0] = params.x;
                    downXY[1] = params.y;
                    moved[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRaw[0];
                    float dy = event.getRawY() - downRaw[1];
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved[0] = true;
                    params.x = downXY[0] + Math.round(dx);
                    params.y = downXY[1] + Math.round(dy);
                    try { wm.updateViewLayout(overlay, params); } catch (Throwable ignored) {}
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!moved[0]) AutomationController.get(this).toggle();
                    return true;
            }
            return false;
        });

        wm.addView(overlay, params);
    }

    public void setOverlayRunning(boolean running) {
        runOnUiThread(() -> {
            if (button != null) button.setText(running ? "停止" : "开始");
        });
    }

    /** 切到下一条视频。坐标全部按当前屏幕比例计算，不绑定固定分辨率。 */
    public boolean swipeNextVideo() {
        if (!dismissKeyboardBeforeSwipe()) return false;

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Random random = new Random();

        float x1r = 0.44f + random.nextFloat() * 0.12f;
        float x2r = clamp(x1r + (-0.025f + random.nextFloat() * 0.05f), 0.38f, 0.62f);
        float y1r = 0.78f + random.nextFloat() * 0.06f;
        float y2r = 0.20f + random.nextFloat() * 0.08f;
        int duration = 280 + random.nextInt(101);

        return gestureLine(w * x1r, h * y1r, w * x2r, h * y2r, duration);
    }

    private boolean dismissKeyboardBeforeSwipe() {
        for (int i = 0; i < 3; i++) {
            if (!isKeyboardVisible()) return true;
            setOverlayStatus("键盘还在｜正在收起…");
            performGlobalAction(GLOBAL_ACTION_BACK);
            SystemClock.sleep(300);
        }
        return !isKeyboardVisible();
    }

    private boolean isKeyboardVisible() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo w : windows) {
                if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean gestureLine(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(45, duration));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};

        boolean accepted = dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        ok[0] = true;
                        latch.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        latch.countDown();
                    }
                },
                null);

        if (!accepted) return false;
        try {
            latch.await(duration + 1200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok[0];
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }
}
