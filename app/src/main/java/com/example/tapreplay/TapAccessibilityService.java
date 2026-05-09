package com.example.tapreplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Dialog;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.util.DisplayMetrics;

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
    private Button inputButton;
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
    private AudioJumpAnalyzer.Result lastAnalysisResult;

    // 默认方案：人工点保存 -> 出现提示 -> 点悬浮开始。
    // #01 = 点掉提示并开始游戏；后续为跳跃。已包含 60ms 的全局后移补偿。
    private static final long[] DEFAULT_TIMES = new long[] {
            0L,
            469L,
            980L,
            1915L,
            3003L,
            5310L,
            7461L,
            8392L,
            9096L,
            10540L
    };

    // 输入档位码映射。0~4 代表相对默认时间的微调。
    // 0 = 提前80ms；1 = 提前40ms；2 = 默认；3 = 延后40ms；4 = 延后80ms。
    private static final long[] CODE_OFFSETS = new long[] { -80L, -40L, 0L, 40L, 80L };

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
        AudioCaptureForegroundService.setListener(new AudioCaptureForegroundService.Listener() {
            @Override
            public void onState(String text) {
                handler.post(() -> {
                    setStatus(text);
                    if (analyzeButton != null && AudioCaptureForegroundService.isRecording()) analyzeButton.setText("结束");
                });
            }

            @Override
            public void onResult(AudioJumpAnalyzer.Result result) {
                handler.post(() -> handleAudioAnalysisResult(result));
            }
        });

        floatPanel = new LinearLayout(this);
        floatPanel.setOrientation(LinearLayout.VERTICAL);
        floatPanel.setPadding(dp(4), dp(4), dp(4), dp(4));
        floatPanel.setBackgroundColor(Color.argb(210, 25, 25, 25));

        topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        startButton = new Button(this);
        startButton.setText("开始");
        startButton.setTextColor(Color.WHITE);
        startButton.setBackgroundColor(Color.argb(235, 211, 47, 47));
        startButton.setTextSize(12);
        topRow.addView(startButton, new LinearLayout.LayoutParams(dp(78), dp(42)));

        addButton = smallButton("+点");
        addButton.setOnClickListener(v -> {
            long next = tapTimesMs.isEmpty() ? 0L : tapTimesMs.get(tapTimesMs.size() - 1) + 500L;
            tapTimesMs.add(next);
            saveTimes();
            refreshList();
            setStatus("已新增点击：" + next + "ms");
        });
        topRow.addView(addButton, new LinearLayout.LayoutParams(dp(48), dp(38)));

        inputButton = smallButton("输入");
        inputButton.setOnClickListener(v -> showInputDialog());
        topRow.addView(inputButton, new LinearLayout.LayoutParams(dp(52), dp(38)));

        resetButton = smallButton("重置");
        resetButton.setOnClickListener(v -> {
            loadDefaultTimes();
            saveTimes();
            refreshList();
            recommendedTimesMs.clear();
            lastAnalysisResult = null;
            setApplyEnabled(false);
            setStatus("已重置为默认预设");
        });
        topRow.addView(resetButton, new LinearLayout.LayoutParams(dp(52), dp(38)));

        hideButton = smallButton("隐藏");
        hideButton.setOnClickListener(v -> {
            compactMode = true;
            savePanelMode();
            applyPanelMode();
        });
        topRow.addView(hideButton, new LinearLayout.LayoutParams(dp(52), dp(38)));
        floatPanel.addView(topRow);

        analysisRow = new LinearLayout(this);
        analysisRow.setOrientation(LinearLayout.HORIZONTAL);
        analysisRow.setGravity(Gravity.CENTER_VERTICAL);

        analyzeButton = smallButton("音频分析");
        analyzeButton.setOnClickListener(v -> toggleAudioAnalysis());
        analysisRow.addView(analyzeButton, new LinearLayout.LayoutParams(dp(78), dp(34)));

        applyButton = smallButton("应用推荐");
        applyButton.setEnabled(false);
        applyButton.setAlpha(0.55f);
        applyButton.setOnClickListener(v -> applyRecommendedTimes());
        analysisRow.addView(applyButton, new LinearLayout.LayoutParams(dp(86), dp(34)));

        Button allMinus = smallButton("全-10");
        allMinus.setOnClickListener(v -> adjustAllJumps(-10));
        analysisRow.addView(allMinus, new LinearLayout.LayoutParams(dp(62), dp(34)));

        Button allPlus = smallButton("全+10");
        allPlus.setOnClickListener(v -> adjustAllJumps(10));
        analysisRow.addView(allPlus, new LinearLayout.LayoutParams(dp(62), dp(34)));
        floatPanel.addView(analysisRow);

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(10);
        statusText.setText("保存后提示界面点开始；长按开始收起/展开");
        statusText.setPadding(0, dp(2), 0, dp(2));
        floatPanel.addView(statusText);

        scrollView = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listContainer);
        floatPanel.addView(scrollView, new LinearLayout.LayoutParams(dp(328), dp(245)));

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = 25;
        panelParams.y = 110;

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
                    if (windowManager != null && floatPanel != null) windowManager.updateViewLayout(floatPanel, panelParams);
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
                        } else if (dt < 250) toggleRun();
                    }
                    return true;
            }
            return true;
        });
    }

    private void applyPanelMode() {
        if (floatPanel == null || startButton == null) return;
        int v = compactMode ? View.GONE : View.VISIBLE;
        if (addButton != null) addButton.setVisibility(v);
        if (inputButton != null) inputButton.setVisibility(v);
        if (resetButton != null) resetButton.setVisibility(v);
        if (hideButton != null) hideButton.setVisibility(v);
        if (analysisRow != null) analysisRow.setVisibility(v);
        if (statusText != null) statusText.setVisibility(v);
        if (scrollView != null) scrollView.setVisibility(v);

        if (compactMode) {
            floatPanel.setPadding(dp(1), dp(1), dp(1), dp(1));
            startButton.setTextSize(11);
            startButton.setLayoutParams(new LinearLayout.LayoutParams(dp(66), dp(34)));
        } else {
            floatPanel.setPadding(dp(4), dp(4), dp(4), dp(4));
            startButton.setTextSize(12);
            startButton.setLayoutParams(new LinearLayout.LayoutParams(dp(78), dp(42)));
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
            row.setPadding(0, dp(1), 0, dp(1));

            TextView label = new TextView(this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(9);
            String name = index == 0 ? "确认" : "跳" + index;
            label.setText(String.format(Locale.US, "#%02d %s %8.3f", index + 1, name, (double) t));
            row.addView(label, new LinearLayout.LayoutParams(dp(116), dp(29)));

            Button minus10 = tinyButton("-10");
            minus10.setOnClickListener(v -> adjustTime(index, -10));
            row.addView(minus10, new LinearLayout.LayoutParams(dp(38), dp(28)));
            Button minus1 = tinyButton("-1");
            minus1.setOnClickListener(v -> adjustTime(index, -1));
            row.addView(minus1, new LinearLayout.LayoutParams(dp(34), dp(28)));
            Button plus1 = tinyButton("+1");
            plus1.setOnClickListener(v -> adjustTime(index, 1));
            row.addView(plus1, new LinearLayout.LayoutParams(dp(34), dp(28)));
            Button plus10 = tinyButton("+10");
            plus10.setOnClickListener(v -> adjustTime(index, 10));
            row.addView(plus10, new LinearLayout.LayoutParams(dp(38), dp(28)));
            Button delete = tinyButton("删");
            delete.setOnClickListener(v -> removeTime(index));
            row.addView(delete, new LinearLayout.LayoutParams(dp(34), dp(28)));

            listContainer.addView(row);
        }
    }

    private void showInputDialog() {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackgroundColor(Color.argb(238, 35, 35, 35));

        TextView title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(12);
        title.setText("输入：0,1,2,3,4 档位码；或 0,469,980 毫秒列表");
        box.addView(title);

        EditText input = new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("例如：2,2,2,3,2 或 0,469,980,1915");
        input.setText(formatTimes(tapTimesMs));
        box.addView(input, new LinearLayout.LayoutParams(dp(330), dp(82)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button apply = smallButton("应用");
        Button close = smallButton("关闭");
        buttons.addView(apply, new LinearLayout.LayoutParams(dp(84), dp(38)));
        buttons.addView(close, new LinearLayout.LayoutParams(dp(84), dp(38)));
        box.addView(buttons);

        apply.setOnClickListener(v -> {
            String text = input.getText() == null ? "" : input.getText().toString();
            if (applyInputText(text)) dialog.dismiss();
        });
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        dialog.setOnShowListener(d -> {
            try {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                }
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception ignored) { }
        });
        try {
            dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        } catch (Exception e) {
            setStatus("输入框打开失败：" + e.getClass().getSimpleName());
        }
    }

    private boolean applyInputText(String text) {
        ArrayList<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            setStatus("输入为空");
            return false;
        }

        boolean digitCode = true;
        for (String s : tokens) {
            if (s.length() != 1 || s.charAt(0) < '0' || s.charAt(0) > '4') {
                digitCode = false;
                break;
            }
        }

        if (digitCode) {
            applyDigitCode(tokens);
            return true;
        }

        ArrayList<Long> values = new ArrayList<>();
        for (String s : tokens) {
            try {
                long v = Math.round(Double.parseDouble(s));
                if (v >= 0L) values.add(v);
            } catch (Exception ignored) { }
        }
        if (values.isEmpty()) {
            setStatus("无法解析输入；请输入 0-4 档位或毫秒列表");
            return false;
        }
        tapTimesMs.clear();
        tapTimesMs.addAll(values);
        Collections.sort(tapTimesMs);
        saveTimes();
        refreshList();
        setStatus("已应用毫秒列表：" + formatTimes(tapTimesMs));
        return true;
    }

    private ArrayList<String> tokenize(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        String cleaned = text.trim();
        if (cleaned.matches("[0-4]+")) {
            for (int i = 0; i < cleaned.length(); i++) out.add(String.valueOf(cleaned.charAt(i)));
            return out;
        }
        String[] parts = cleaned.split("[^0-9.]+ ");
        if (parts.length <= 1) parts = cleaned.split("[^0-9.]+");
        for (String p : parts) if (p != null && !p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    private void applyDigitCode(ArrayList<String> codes) {
        ArrayList<Long> base = new ArrayList<>();
        for (long v : DEFAULT_TIMES) base.add(v);

        if (codes.size() == 1) {
            int code = codes.get(0).charAt(0) - '0';
            long delta = CODE_OFFSETS[code];
            for (int i = 1; i < base.size(); i++) base.set(i, Math.max(0L, base.get(i) + delta));
            setTimesAndSave(base, "已应用全局档位 " + code + "：" + delta + "ms");
            return;
        }

        for (int i = 1; i < base.size(); i++) {
            int codeIndex = Math.min(i - 1, codes.size() - 1);
            int code = codes.get(codeIndex).charAt(0) - '0';
            base.set(i, Math.max(0L, base.get(i) + CODE_OFFSETS[code]));
        }
        setTimesAndSave(base, "已应用分段档位：" + formatTimes(base));
    }

    private void setTimesAndSave(ArrayList<Long> values, String status) {
        tapTimesMs.clear();
        tapTimesMs.addAll(values);
        Collections.sort(tapTimesMs);
        saveTimes();
        refreshList();
        setStatus(status);
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

    private void adjustAllJumps(long deltaMs) {
        for (int i = 1; i < tapTimesMs.size(); i++) tapTimesMs.set(i, Math.max(0L, tapTimesMs.get(i) + deltaMs));
        Collections.sort(tapTimesMs);
        saveTimes();
        refreshList();
        setStatus("全部跳跃 " + (deltaMs > 0 ? "+" : "") + deltaMs + "ms");
    }

    private void removeTime(int index) {
        if (tapTimesMs.size() <= 1) return;
        if (index < 0 || index >= tapTimesMs.size()) return;
        tapTimesMs.remove(index);
        saveTimes();
        refreshList();
    }

    private void toggleAudioAnalysis() {
        if (AudioCaptureForegroundService.isRecording()) finishAudioAnalysis(); else startAudioAnalysis();
    }

    private void startAudioAnalysis() {
        if (running) stopRun();
        recommendedTimesMs.clear();
        lastAnalysisResult = null;
        setApplyEnabled(false);
        if (analyzeButton != null) analyzeButton.setText("结束");
        setStatus("分析开始：请从提示确认点击开始，把确认声和跳跃声都录进去");
        AudioCaptureForegroundService.start(this);
    }

    private void finishAudioAnalysis() {
        if (analyzeButton != null) analyzeButton.setText("分析中");
        setStatus("正在结束内部音频并分析全部咔哒/跳跃...");
        AudioCaptureForegroundService.stop(this);
    }

    private void handleAudioAnalysisResult(AudioJumpAnalyzer.Result result) {
        lastAnalysisResult = result;
        recommendedTimesMs.clear();
        recommendedTimesMs.addAll(result.recommendedTimesMs);
        if (analyzeButton != null) analyzeButton.setText("音频分析");
        setApplyEnabled(!recommendedTimesMs.isEmpty());
        if (recommendedTimesMs.isEmpty()) setStatus(result.debugText);
        else setStatus(result.debugText + " | 推荐：" + formatTimes(recommendedTimesMs));
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
        int limit = Math.min(values.size(), 20);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        if (values.size() > limit) sb.append("...");
        return sb.toString();
    }

    private void toggleRun() {
        if (AudioCaptureForegroundService.isRecording()) {
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
                        "#%02d 计划 %.3f | 记录 %.3f | 偏差 %.3f",
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
        b.setTextSize(9);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.argb(215, 80, 80, 80));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private Button tinyButton(String text) {
        Button b = smallButton(text);
        b.setTextSize(8);
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
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_TIMES, sb.toString()).apply();
    }

    private void loadPanelMode() {
        compactMode = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_COMPACT_MODE, false);
    }

    private void savePanelMode() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_COMPACT_MODE, compactMode).apply();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AudioCaptureForegroundService.setListener(null);
        AudioCaptureForegroundService.discard(this);
        if (windowManager != null && floatPanel != null) {
            windowManager.removeView(floatPanel);
            floatPanel = null;
        }
    }
}
