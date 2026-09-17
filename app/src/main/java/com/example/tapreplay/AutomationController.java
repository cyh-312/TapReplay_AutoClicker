package com.example.tapreplay;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutomationController {
    private static volatile AutomationController INSTANCE;

    public static AutomationController get(Context context) {
        if (INSTANCE == null) {
            synchronized (AutomationController.class) {
                if (INSTANCE == null) INSTANCE = new AutomationController(context.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    private static final int TARGET_FRAMES = 18;
    private static final int MAX_FRAMES = 20;
    private static final long SAMPLE_INTERVAL_MS = 300;
    private static final long MAX_CAPTURE_MS = 8000;
    private static final long AFTER_SWIPE_MS = 950;
    private static final long BETWEEN_CYCLES_MS = 350;

    private AutomationController(Context context) {
        this.context = context;
        TraceLogger.init(context);
    }

    public boolean isRunning() { return running.get(); }

    public synchronized void start() {
        if (running.get()) return;

        TapAccessibilityService service = TapAccessibilityService.getInstance();
        if (service == null) {
            TapAccessibilityService.setOverlayStatus("先开启无障碍");
            return;
        }
        if (!ScreenCaptureService.isReady()) {
            TapAccessibilityService.setOverlayStatus("先在App里授权屏幕捕获");
            return;
        }

        String target = context.getSharedPreferences("douyin_filter", Context.MODE_PRIVATE)
                .getString("share_target", "").trim();
        if (target.isEmpty()) {
            TapAccessibilityService.setOverlayStatus("先在App里填写分享对象");
            return;
        }

        running.set(true);
        TraceLogger.critical("AUTO", "start");
        service.setOverlayRunning(true);
        TapAccessibilityService.setOverlayStatus("准备开始…");
        worker = new Thread(this::loop, "douyin-automation");
        worker.start();
    }

    public synchronized void stop() {
        boolean wasRunning = running.getAndSet(false);
        if (wasRunning) TraceLogger.critical("AUTO", "stop");
        TapAccessibilityService service = TapAccessibilityService.getInstance();
        if (service != null) {
            service.setOverlayRunning(false);
            TapAccessibilityService.setOverlayStatus("已停止");
        }
    }

    public void toggle() {
        if (running.get()) stop(); else start();
    }

    private void loop() {
        TapAccessibilityService service = TapAccessibilityService.getInstance();
        try {
            ModelEngine engine = ModelEngine.get(context);
            TapAccessibilityService.setOverlayStatus("正在加载识别模型…");
            long modelStart = System.currentTimeMillis();
            engine.ensureLoaded();
            long modelMs = System.currentTimeMillis() - modelStart;
            TapAccessibilityService.setOverlayStatus("模型好了｜" + engine.getBackendNote() + "｜" + formatMs(modelMs));

            int cycle = 0;
            while (running.get()) {
                cycle++;
                long cycleStart = System.currentTimeMillis();
                service = TapAccessibilityService.getInstance();
                if (service == null) throw new IllegalStateException("无障碍服务断开了");

                TapAccessibilityService.setOverlayStatus("第" + cycle + "条｜正在切到下一条…");
                if (!service.swipeNextVideo()) throw new RuntimeException("上滑失败");
                sleepInterruptible(AFTER_SWIPE_MS);
                if (!running.get()) break;

                TapAccessibilityService.setOverlayStatus("第" + cycle + "条｜正在取画面…");
                long captureStart = System.currentTimeMillis();
                List<ModelEngine.FrameData> frames = captureFrames(engine);
                long captureMs = System.currentTimeMillis() - captureStart;
                int sampled = frames.size();

                if (frames.size() < 2) {
                    recycle(frames);
                    TapAccessibilityService.setOverlayStatus(
                            "第" + cycle + "条｜画面没采够，先跳过｜" + formatMs(captureMs));
                    continue;
                }

                TapAccessibilityService.setOverlayStatus(
                        "第" + cycle + "条｜已取" + sampled + "帧，正在判断…");

                ModelEngine.Decision decision;
                long analyzeStart = System.currentTimeMillis();
                try {
                    decision = engine.analyze(frames);
                } finally {
                    recycle(frames);
                }
                long analyzeMs = System.currentTimeMillis() - analyzeStart;

                String friendly = friendlyResult(decision);
                String detail = sampled + "帧｜女生" + decision.femaleFrames + "/" + decision.checkedFrames +
                        "｜最高" + String.format(Locale.US, "%.2f", decision.maxSensual) +
                        "｜" + formatMs(captureMs + analyzeMs);
                TapAccessibilityService.setOverlayStatus(
                        "第" + cycle + "条｜" + friendly + "\n" + detail);

                if (decision.isPositive() && running.get()) {
                    String target = context.getSharedPreferences("douyin_filter", Context.MODE_PRIVATE)
                            .getString("share_target", "").trim();
                    if (target.isEmpty()) {
                        TapAccessibilityService.setOverlayStatus("分享对象没设置，已停下");
                        running.set(false);
                        break;
                    }

                    TapAccessibilityService.setOverlayStatus(
                            "第" + cycle + "条｜符合，准备打开分享…");
                    long shareStart = System.currentTimeMillis();
                    boolean ok = ShareFlowV6.shareToTarget(service, target, running);
                    long shareMs = System.currentTimeMillis() - shareStart;
                    if (!ok && running.get()) {
                        // ShareFlowV6 会保留明确的两行失败阶段日志；这里不能覆盖。
                        running.set(false);
                        break;
                    }
                    if (ok && running.get()) {
                        long totalMs = System.currentTimeMillis() - cycleStart;
                        TapAccessibilityService.setOverlayStatus(
                                "第" + cycle + "条｜已分享 ✓\n整轮" + formatMs(totalMs) + "｜分享" + formatMs(shareMs));
                    }
                }

                sleepInterruptible(BETWEEN_CYCLES_MS);
            }
        } catch (Throwable e) {
            TraceLogger.critical("AUTO", "exception=" + shortError(e));
            TapAccessibilityService.setOverlayStatus("停下了｜" + shortError(e));
        } finally {
            running.set(false);
            TapAccessibilityService s = TapAccessibilityService.getInstance();
            if (s != null) s.setOverlayRunning(false);
        }
    }

    private String friendlyResult(ModelEngine.Decision d) {
        if (d == null) return "没判断出来，先跳过";
        switch (d.state) {
            case "positive": return "符合，准备分享";
            case "border": return "有点像，先跳过";
            case "negative": return "不符合，跳过";
            case "insufficient": return "画面没采够，先跳过";
            default: return "没判断出来，先跳过";
        }
    }

    private List<ModelEngine.FrameData> captureFrames(ModelEngine engine) throws Exception {
        ArrayList<ModelEngine.FrameData> out = new ArrayList<>();
        long started = System.currentTimeMillis();
        long next = started;
        ScreenCaptureService cap = ScreenCaptureService.getInstance();
        if (cap == null) throw new IllegalStateException("屏幕捕获还没准备好");

        while (running.get() && out.size() < MAX_FRAMES) {
            long elapsed = System.currentTimeMillis() - started;
            if (elapsed >= MAX_CAPTURE_MS) break;

            long wait = next - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            if (!running.get()) break;

            Bitmap full = null;
            try {
                full = cap.captureLatest(1200);
                out.add(engine.prepareFrame(full));
            } catch (Throwable e) {
                // 单张画面偶尔失败不影响整轮，继续取下一张。
            } finally {
                if (full != null && !full.isRecycled()) full.recycle();
            }

            if (out.size() >= TARGET_FRAMES &&
                    System.currentTimeMillis() - started >= 4500) break;
            next += SAMPLE_INTERVAL_MS;
        }
        return out;
    }

    private void recycle(List<ModelEngine.FrameData> frames) {
        for (ModelEngine.FrameData f : frames) f.recycle();
        frames.clear();
    }

    private void sleepInterruptible(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (running.get() && System.currentTimeMillis() < end) {
            Thread.sleep(Math.min(80, end - System.currentTimeMillis()));
        }
    }

    private String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format(Locale.US, "%.1fs", ms / 1000.0);
    }

    private String shortError(Throwable e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        if (s.length() > 70) s = s.substring(0, 70);
        return s;
    }
}
