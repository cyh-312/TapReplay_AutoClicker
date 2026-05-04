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

    // 正确流程：
    // 1. 用户停在编辑界面，右下角“保存”可见。
    // 2. 点悬浮“开始”。悬浮按钮先隐藏，避免挡住保存按钮。
    // 3. 0ms：脚本点击“保存”。
    // 4. 453ms：出现“连续两次抵达宝箱的位置……”提示后，脚本点屏幕空白处，游戏立即开始。
    // 5. 后续跳跃时间以第 4 步这次“提示确认/开始”点击为基准。
    private final long[] tapTimesMs = new long[] {
            0L,      // 保存
            453L,    // 点提示/确认，立即开始游戏；不是点测试
            1315L,   // 开始后约 862ms：第一跳
            1826L,   // 第二跳
            2761L,   // 第三跳
            3849L,   // 右侧关键全包跳/折返点
            6157L,   // 后续跳
            8308L,
            9238L,
            9942L,
            11386L
    };

    // 当前手机：1260 x 2720，横屏实际按 2720 x 1260 计算。全部使用比例坐标。
    private static final float SAVE_X_RATIO = 0.903f;
    private static final float SAVE_Y_RATIO = 0.946f;

    // 保存后提示界面：点屏幕中部/中下部空白处即可确认并开始。
    private static final float PROMPT_X_RATIO = 0.500f;
    private static final float PROMPT_Y_RATIO = 0.650f;

    // 跳跃是单点，全屏空白区域理论等效；这里继续点中下部，避开按钮、悬浮窗、系统导航区。
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

        // 运行期间先移除悬浮按钮，避免它挡住“保存”按钮或吃掉脚本点击。
        hideFloatingButton();

        // 等一帧让悬浮窗真正消失，再注入完整点击序列。
        handler.postDelayed(() -> {
            if (!running) return;
            dispatchFullTapSequence();
            handler.postDelayed(this::stopRun, tapTimesMs[tapTimesMs.length - 1] + 500L);
        }, 45L);
    }

    private void stopRun() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        showFloatingButton();
        if (floatButton != null) floatButton.setText("开始");
    }

    private void hideFloatingButton() {
        if (windowManager != null && floatButton != null) {
            windowManager.removeView(floatButton);
            floatButton = null;
        }
    }

    private void dispatchFullTapSequence() {
        int[] size = getScreenSize();
        GestureDescription.Builder builder = new GestureDescription.Builder();

        for (int i = 0; i < tapTimesMs.length; i++) {
            float xr;
            float yr;
            if (i == 0) {
                xr = SAVE_X_RATIO;
                yr = SAVE_Y_RATIO;
            } else if (i == 1) {
                xr = PROMPT_X_RATIO;
                yr = PROMPT_Y_RATIO;
            } else {
                xr = JUMP_X_RATIO;
                yr = JUMP_Y_RATIO;
            }
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
        hideFloatingButton();
    }
}
