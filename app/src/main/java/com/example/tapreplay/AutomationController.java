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
    }

    public boolean isRunning() { return running.get(); }

    public synchronized void start() {
        if (running.get()) return;

        TapAccessibilityService service = TapAccessibilityService.getInstance();
        if (service == null) {
            TapAccessibilityService.setOverlayStatus("请先开启无障碍");
            return;
        }
        if (!ScreenCaptureService.isReady()) {
            TapAccessibilityService.setOverlayStatus("请先在App里授权屏幕捕获");
            return;
        }

        running.set(true);
        service.setOverlayRunning(true);
        worker = new Thread(this::loop, "douyin-automation");
        worker.start();
    }

    public synchronized void stop() {
        running.set(false);
        TapAccessibilityService service = TapAccessibilityService.getInstance();
        if (service != null) service.setOverlayRunning(false);
    }

    public void toggle() {
        if (running.get()) stop(); else start();
    }

    private void loop() {
        TapAccessibilityService service = TapAccessibilityService.getInstance();
        try {
            ModelEngine engine = ModelEngine.get(context);
            TapAccessibilityService.setOverlayStatus("加载模型…");
            engine.ensureLoaded();

            int cycle = 0;
            while (running.get()) {
                cycle++;
                service = TapAccessibilityService.getInstance();
                if (service == null) throw new IllegalStateException("无障碍服务已断开");

                TapAccessibilityService.setOverlayStatus("第" + cycle + "条：上滑");
                if (!service.swipeNextVideo()) throw new RuntimeException("上滑失败");
                sleepInterruptible(AFTER_SWIPE_MS);
                if (!running.get()) break;

                List<ModelEngine.FrameData> frames = captureFrames(engine);
                if (frames.size() < 2) {
                    recycle(frames);
                    TapAccessibilityService.setOverlayStatus("采样不足，继续");
                    continue;
                }

                TapAccessibilityService.setOverlayStatus("分析 " + frames.size() + " 帧…");
                ModelEngine.Decision decision;
                try {
                    decision = engine.analyze(frames);
                } finally {
                    recycle(frames);
                }

                TapAccessibilityService.setOverlayStatus(
                        decision.state + " " + decision.path +
                        String.format(Locale.US, "\nTopK %.3f / Max %.3f", decision.topkMean, decision.maxSensual));

                if (decision.isPositive() && running.get()) {
                    String target = context.getSharedPreferences("douyin_filter", Context.MODE_PRIVATE)
                            .getString("share_target", "老张分享");
                    boolean ok = service.shareToTarget(target, running);
                    if (!ok && running.get()) {
                        TapAccessibilityService.setOverlayStatus("分享失败，已安全停止");
                        running.set(false);
                        break;
                    }
                }

                sleepInterruptible(BETWEEN_CYCLES_MS);
            }
        } catch (Throwable e) {
            TapAccessibilityService.setOverlayStatus("停止：" + shortError(e));
        } finally {
            running.set(false);
            TapAccessibilityService s = TapAccessibilityService.getInstance();
            if (s != null) s.setOverlayRunning(false);
        }
    }

    private List<ModelEngine.FrameData> captureFrames(ModelEngine engine) throws Exception {
        ArrayList<ModelEngine.FrameData> out = new ArrayList<>();
        long started = System.currentTimeMillis();
        long next = started;
        ScreenCaptureService cap = ScreenCaptureService.getInstance();
        if (cap == null) throw new IllegalStateException("屏幕捕获服务未就绪");

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
                // 单帧失败不终止整轮
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

    private String shortError(Throwable e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }
}
