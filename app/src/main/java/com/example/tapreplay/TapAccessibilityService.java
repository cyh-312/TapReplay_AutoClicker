package com.example.tapreplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public class TapAccessibilityService extends AccessibilityService {
    private static final String PREF_NAME = "tap_replay_prefs";
    private static final String KEY_TIMES = "tap_times_ms";
    private static final String KEY_COMPACT_MODE = "compact_mode";

    private WindowManager windowManager;
    private LinearLayout floatPanel;
    private LinearLayout topRow;
    private LinearLayout analysisRow;
    private Button startButton;
    private Button addButton;
    private Button resetButton;
    private Button hideButton;
    private Button analyzeButton;
    private Button applyButton;
    private LinearLayout listContainer;
    private ScrollView scrollView;
    private TextView statusText;
    private WindowManager.LayoutParams panelParams;

    private boolean running = false;
    private boolean compactMode = false;
    private long runStartNs = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Long> tapTimesMs = new ArrayList<>();
    private final ArrayList<Long> recommendedTimesMs = new ArrayList<>();
    private InternalAudioRecorder audioRecorder;
    private AudioJumpAnalyzer.Result lastAnalysisResult;

    // 这版默认时间：你人工点保存 -> 出现提示 -> 点悬浮“开始”。
    // 0ms 点掉提示并启动游戏；跳跃点在教学视频基础上整体后移 60ms，方便补偿提示消失到游戏真正可控的帧延迟。
    private static final long[] DEFAULT_TIMES = new long[] {
            0L,      // 提示确认/游戏开始
            469L,    // 第一跳：409 + 60
            980L,    // 第二跳：920 + 60
            1915L,   // 第三跳：1855 + 60
            3003L,   // 右侧关键全包跳：2943 + 60
            5310L,   // 后续跳：5250 + 60
            7461L,
            8392L,
            9096L,
            10540L
    };

    // 当前手机 1260 x 2720；横屏按 2720 x 1260 计算。全部用比例坐标。
    private static final float PROMPT_X_RATIO = 0.500f;
    private static final float PROMPT_Y_RATIO = 0.650f;
    private static final float JUMP_X_RATIO = 0.500f;
    private static final float JUMP_Y_RATIO = 0.720f;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        loadTimes();
        loadPanelMode();
        showFloatingPanel();
    }

    private void showFloatingPanel() {
        if (!Settings.canDrawOverlays(this) || floatPanel != null) return;

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audioRecorder = new InternalAudioRecorder(this, text -> handler.post(() -> setStatus(text)));

        floatPanel = new LinearLayout(this);
        floatPanel.setOrientation(LinearLayout.VERTICAL);
        floatPanel.setBackgroundColor(Color.argb(205, 30, 30, 30));

        topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        startButton = new Button(this);
        startButton.setText("开始");
        startButton.setTextColor(Color.WHITE);
        startButton.setBackgroundColor(Color.argb(230, 211, 47, 47));
        startButton.setTextSize(13);
        topRow.addView(startButton, new LinearLayout.LayoutParams(dp(92), dp(46)));

        addButton = smallButton("+点");
        addButton.setOnClickListener(v -> {
            long next = tapTimesMs.isEmpty() ? 0L : tapTimesMs.get(tapTimesMs.size() - 1) + 500L;
            tapTimesMs.add(next);
            saveTimes();
            refreshList();
        });
        topRow.addView(addButton, new LinearLayout.LayoutParams(dp(56), dp(42)));

        resetButton = smallButton("重置");
        resetButton.setOnClickListener(v -> {
            loadDefaultTimes();
            saveTimes();
            refreshList();
            recommendedTimesMs.clear();
            lastAnalysisResult = null;
            setStatus("已重置为默认预设");
        });
        topRow.addView(resetButton, new LinearLayout.LayoutParams(dp(64), dp(42)));

        hideButton = smallButton("隐藏");
        hideButton.setOnClickListener(v -> {
            compactMode = true;
            savePanelMode();
            applyPanelMode();
        });
        topRow.addView(hideButton, new LinearLayout.LayoutParams(dp(64), dp(42)));

        floatPanel.addView(topRow);

        analysisRow = new LinearLayout(this);
        analysisRow.setOrientation(LinearLayout.HORIZONTAL);
        analysisRow.setGravity(Gravity.CENTER_VERTICAL);

        analyzeButton = smallButton("音频分析");
        analyzeButton.setOnClickListener(v -> toggleAudioAnalysis());
        analysisRow.addView(analyzeButton, new LinearLayout.LayoutParams(dp(92), dp(38)));

        applyButton = smallButton("应用推荐");
        applyButton.setEnabled(false);
        applyButton.setAlpha(0.55f);
        applyButton.setOnClickListener(v -> applyRecommendedTimes());
        analysisRow.addView(applyButton, new LinearLayout.LayoutParams(dp(92), dp(38)));

        floatPanel.addView(analysisRow);

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(11);
        statusText.setText("基准：保存后提示界面，点开始执行；长按开始可展开/收起设置");
        statusText.setPadding(0, dp(3), 0, dp(3));
        floatPanel.addView(statusText);

        scrollView = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listContainer);
        floatPanel.addView(scrollView, new LinearLayout.LayoutParams(dp(390), dp(320)));

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = 35;
        panelParams.y = 130;

        attachDragAndStartBehavior();
        refreshList();
        applyPanelMode();
        windowManager.addView(floatPanel, panelParams);
    }

    private void attachDragAndStartBehavior() {
        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final int[] startParamX = new int[1];
        final int[] startParamY = new int[1];
        final long[] downTime = new long[1];

        startButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = (int) event.getRawX();
                    startY[0] = (int) event.getRawY();
                    startParamX[0] = panelParams.x;
                    startParamY[0] = panelParams.y;
                    downTime[0] = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    panelParams.x = startParamX[0] + ((int) event.getRawX() - startX[0]);
                    panelParams.y = startParamY[0] + ((int) event.getRawY() - startY[0]);
                    if (windowManager != null && floatPanel != null) {
                        windowManager.updateViewLayout(floatPanel, panelParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    long dt = System.currentTimeMillis() - downTime[0];
                    int dx = Math.abs((int) event.getRawX() - startX[0]);
                    int dy = Math.abs((int) event.getRawY() - startY[0]);
                    if (dx < 12 && dy < 12) {
                        if (dt >= 600) {
                            compactMode = !compactMode;
                            savePanelMode();
                            applyPanelMode();
                        } else if (dt < 250) {
                            toggleRun();
                        }
                    }
                    return true;
            }
            return true;
        });
    }

    private void applyPanelMode() {
        if (floatPanel == null || startButton == null) return;

        int otherVisibility = compactMode ? View.GONE : View.VISIBLE;
        if (addButton != null) addButton.setVisibility(otherVisibility);
        if (resetButton != null) resetButton.setVisibility(otherVisibility);
        if (hideButton != null) hideButton.setVisibility(otherVisibility);
        if (analysisRow != null) analysisRow.setVisibility(otherVisibility);
        if (statusText != null) statusText.setVisibility(otherVisibility);
        if (scrollView != null) scrollView.setVisibility(otherVisibility);

        if (compactMode) {
            floatPanel.setPadding(dp(2), dp(2), dp(2), dp(2));
            startButton.setTextSize(12);
            startButton.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(40)));
        } else {
            floatPanel.setPadding(dp(6), dp(6), dp(6), dp(6));
            startButton.setTextSize(13);
            startButton.setLayoutParams(new LinearLayout.LayoutParams(dp(92), dp(46)));
        }

        if (windowManager != null && floatPanel != null && panelParams != null && floatPanel.isAttachedToWindow()) {
            windowManager.updateViewLayout(floatPanel, panelParams);
        }
    }

    private void refreshList() {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        for (int i = 0; i < tapTimesMs.size(); i++) {
            final int index = i;
            long t = tapTimesMs.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(2), 0, dp(2));

            TextView label = new TextView(this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(10);
            String name = index == 0 ? "确认" : "跳" + index;
            label.setText(String.format(Locale.US, "#%02d %s %8.3fms", index + 1, name, (double) t));
            row.addView(label, new LinearLayout.LayoutParams(dp(138), dp(34)));

            Button minus10 = tinyButton("-10");
            minus10.setOnClickListener(v -> adjustTime(index, -10));
            row.addView(minus10, new LinearLayout.LayoutParams(dp(42), dp(32)));

            Button minus1 = tinyButton("-1");
            minus1.setOnClickListener(v -> adjustTime(index, -1));
            row.addView(minus1, new LinearLayout.LayoutParams(dp(38), dp(32)));

            Button plus1 = tinyButton("+1");
            plus1.setOnClickListener(v -> adjustTime(index, 1));
            row.addView(plus1, new LinearLayout.LayoutParams(dp(38), dp(32)));

            Button plus10 = tinyButton("+10");
            plus10.setOnClickListener(v -> adjustTime(index, 10));
            row.addView(plus10, new LinearLayout.LayoutParams(dp(42), dp(32)));

            Button delete = tinyButton("删");
            delete.setOnClickListener(v -> removeTime(index));
            row.addView(delete, new LinearLayout.LayoutParams(dp(38), dp(32)));

            listContainer.addView(row);
        }
    }

    private void adjustTime(int index, long deltaMs) {
        if (index < 0 || index >= tapTimesMs.size()) return;
        long next = tapTimesMs.get(index) + deltaMs;
        if (next < 0L) next = 0L;
        tapTimesMs.set(index, next);
        Collections.sort(tapTimesMs);
        saveTimes();
        refreshList();
    }

    private void removeTime(int index) {
        if (tapTimesMs.size() <= 1) return;
        if (index < 0 || index >= tapTimesMs.size()) return;
        tapTimesMs.remove(index);
        saveTimes();
        refreshList();
    }

    private void toggleAudioAnalysis() {
        if (audioRecorder == null) {
            audioRecorder = new InternalAudioRecorder(this, text -> handler.post(() -> setStatus(text)));
        }
        if (audioRecorder.isRecording()) finishAudioAnalysis(); else startAudioAnalysis();
    }

    private void startAudioAnalysis() {
        if (running) stopRun();
        recommendedTimesMs.clear();
        lastAnalysisResult = null;
        setApplyEnabled(false);
        if (audioRecorder.start()) {
            if (analyzeButton != null) analyzeButton.setText("结束分析");
            setStatus("内部音频分析中：打一遍/播放本关，结束后生成推荐点");
        } else {
            setStatus(audioRecorder.getLastError());
        }
    }

    private void finishAudioAnalysis() {
        if (analyzeButton != null) analyzeButton.setText("分析中...");
        setStatus("正在分析内部音频...");
        final short[] pcm = audioRecorder.stopAndGetPcm();
        new Thread(() -> {
            final AudioJumpAnalyzer.Result result = AudioJumpAnalyzer.analyze(pcm, AudioJumpAnalyzer.SAMPLE_RATE);
            handler.post(() -> {
                lastAnalysisResult = result;
                recommendedTimesMs.clear();
                recommendedTimesMs.addAll(result.recommendedTimesMs);
                if (analyzeButton != null) analyzeButton.setText("音频分析");
                setApplyEnabled(!recommendedTimesMs.isEmpty());
                if (recommendedTimesMs.isEmpty()) {
                    setStatus(result.debugText);
                } else {
                    setStatus(result.debugText + " | 推荐：" + formatTimes(recommendedTimesMs));
                }
            });
        }, "TapReplayAudioAnalyze").start();
    }

    private void applyRecommendedTimes() {
        if (recommendedTimesMs.isEmpty()) {
            setStatus("没有可应用的推荐结果");
            return;
        }
        tapTimesMs.clear();
        tapTimesMs.addAll(recommendedTimesMs);
        Collections.sort(tapTimesMs);
        saveTimes();
        refreshList();
        setStatus("已应用音频推荐：" + formatTimes(tapTimesMs));
    }

    private void setApplyEnabled(boolean enabled) {
        if (applyButton == null) return;
        applyButton.setEnabled(enabled);
        applyButton.setAlpha(enabled ? 1.0f : 0.55f);
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private String formatTimes(ArrayList<Long> values) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(values.size(), 12);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        if (values.size() > limit) sb.append("...");
        return sb.toString();
    }

    private void toggleRun() {
        if (audioRecorder != null && audioRecorder.isRecording()) {
            setStatus("请先结束音频分析，再开始执行");
            return;
        }
        if (running) stopRun(); else startRun();
    }

    private void startRun() {
        if (tapTimesMs.isEmpty()) return;
        running = true;
        runStartNs = System.nanoTime();
        if (startButton != null) startButton.setText("停止");
        if (statusText != null) statusText.setText("运行中：" + tapTimesMs.size() + " 次点击");

        dispatchTapSequenceInChunks();
        scheduleRunLogs();
        long last = tapTimesMs.get(tapTimesMs.size() - 1);
        handler.postDelayed(this::stopRun, last + 700L);
    }

    private void stopRun() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (startButton != null) startButton.setText("开始");
        if (statusText != null) statusText.setText("已停止，可继续微调");
    }

    private void dispatchTapSequenceInChunks() {
        final ArrayList<Long> times = new ArrayList<>(tapTimesMs);
        Collections.sort(times);
        final int maxStrokesPerGesture = 10;
        int start = 0;
        while (start < times.size()) {
            final int from = start;
            final int to = Math.min(start + maxStrokesPerGesture, times.size());
            final long baseTime = times.get(from);
            handler.postDelayed(() -> {
                if (!running) return;
                dispatchGestureChunk(times, from, to, baseTime);
            }, baseTime);
            start = to;
        }
    }

    private void dispatchGestureChunk(ArrayList<Long> times, int from, int to, long baseTime) {
        int[] size = getScreenSize();
        GestureDescription.Builder builder = new GestureDescription.Builder();

        for (int i = from; i < to; i++) {
            long relativeStart = Math.max(0L, times.get(i) - baseTime);
            float xr = (i == 0) ? PROMPT_X_RATIO : JUMP_X_RATIO;
            float yr = (i == 0) ? PROMPT_Y_RATIO : JUMP_Y_RATIO;
            int x = Math.round(size[0] * xr);
            int y = Math.round(size[1] * yr);
            addTapStroke(builder, x, y, relativeStart, 28L);
        }

        dispatchGesture(builder.build(), null, null);
    }

    private void scheduleRunLogs() {
        final ArrayList<Long> times = new ArrayList<>(tapTimesMs);
        Collections.sort(times);
        for (int i = 0; i < times.size(); i++) {
            final int index = i;
            final long planned = times.get(i);
            handler.postDelayed(() -> {
                if (!running || statusText == null) return;
                double actualMs = (System.nanoTime() - runStartNs) / 1_000_000.0;
                statusText.setText(String.format(Locale.US,
                        "#%02d 计划 %.3fms | 记录 %.3fms | 偏差 %.3fms",
                        index + 1, (double) planned, actualMs, actualMs - planned));
            }, planned);
        }
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

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.argb(215, 80, 80, 80));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private Button tinyButton(String text) {
        Button b = smallButton(text);
        b.setTextSize(9);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadTimes() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String csv = sp.getString(KEY_TIMES, null);
        tapTimesMs.clear();
        if (csv == null || csv.trim().isEmpty()) {
            loadDefaultTimes();
            saveTimes();
            return;
        }
        String[] parts = csv.split(",");
        for (String p : parts) {
            try {
                long v = Long.parseLong(p.trim());
                if (v >= 0L) tapTimesMs.add(v);
            } catch (Exception ignored) { }
        }
        if (tapTimesMs.isEmpty()) loadDefaultTimes();
        Collections.sort(tapTimesMs);
    }

    private void loadDefaultTimes() {
        tapTimesMs.clear();
        for (long v : DEFAULT_TIMES) tapTimesMs.add(v);
        Collections.sort(tapTimesMs);
    }

    private void saveTimes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tapTimesMs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(tapTimesMs.get(i));
        }
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_TIMES, sb.toString())
                .apply();
    }

    private void loadPanelMode() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        compactMode = sp.getBoolean(KEY_COMPACT_MODE, false);
    }

    private void savePanelMode() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPACT_MODE, compactMode)
                .apply();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (audioRecorder != null) audioRecorder.discard();
        if (windowManager != null && floatPanel != null) {
            windowManager.removeView(floatPanel);
            floatPanel = null;
        }
    }
}
