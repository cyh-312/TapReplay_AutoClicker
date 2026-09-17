package com.example.tapreplay;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.*;
import java.util.concurrent.*;
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
    private final ExecutorService siglipPipelineExecutor;
    private Thread worker;

    private static final int TARGET_FRAMES = 18;
    private static final int MAX_FRAMES = 20;
    private static final long SAMPLE_INTERVAL_MS = 300;
    private static final long MAX_CAPTURE_MS = 8000;
    private static final long AFTER_SWIPE_MS = 950;
    private static final long BETWEEN_CYCLES_MS = 350;

    // Two 9-frame batches preserve all 18 sampled frames while overlapping the first half of
    // SigLIP2 inference with capture of the second half. No duplicate model/session is created.
    private static final int SIGLIP_PIPELINE_BATCH = 9;

    // Internal stability instrumentation / self-healing only. Recognition rules stay frozen.
    private static final int CAPTURE_RECOVER_AFTER_MISSES = 3;
    private static final int FULL_MEM_LOG_EVERY_CYCLES = 5;

    private AutomationController(Context context) {
        this.context = context;
        TraceLogger.init(context);
        StabilityDiagnostics.logStartupExitInfo(context);
        siglipPipelineExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "siglip-pipeline");
            t.setDaemon(true);
            return t;
        });
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
        TraceLogger.critical("AUTO", "start internal-v0.7.6-stability");
        StabilityDiagnostics.logSnapshot(context, 0, "start", true);
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
        int cycle = 0;
        try {
            ModelEngine engine = ModelEngine.get(context);
            TapAccessibilityService.setOverlayStatus("正在加载识别模型…");
            long modelStart = System.currentTimeMillis();
            engine.ensureLoaded();
            long modelMs = System.currentTimeMillis() - modelStart;
            TapAccessibilityService.setOverlayStatus("模型好了｜" + engine.getBackendNote() + "｜" + formatMs(modelMs));
            StabilityDiagnostics.logSnapshot(context, 0, "model_loaded", true);

            while (running.get()) {
                cycle++;
                long cycleStart = System.currentTimeMillis();
                service = TapAccessibilityService.getInstance();
                if (service == null) throw new IllegalStateException("无障碍服务断开了");

                TapAccessibilityService.setOverlayStatus("第" + cycle + "条｜正在切到下一条…");
                if (!service.swipeNextVideo()) throw new RuntimeException("上滑失败");
                sleepInterruptible(AFTER_SWIPE_MS);
                if (!running.get()) break;

                TapAccessibilityService.setOverlayStatus("第" + cycle + "条｜正在取画面+并行初判…");
                PipelineCapture capture = captureFramesPipelined(engine, cycle);
                List<ModelEngine.FrameData> frames = capture.frames;
                int sampled = frames.size();

                if (!running.get()) {
                    drainPipeline(capture);
                    recycle(frames);
                    break;
                }

                if (frames.size() < 2) {
                    drainPipeline(capture);
                    recycle(frames);
                    StabilityDiagnostics.logSnapshot(context, cycle, "capture_insufficient", false);
                    TapAccessibilityService.setOverlayStatus(
                            "第" + cycle + "条｜画面没采够，先跳过｜" + formatMs(capture.captureMs));
                    continue;
                }

                TapAccessibilityService.setOverlayStatus(
                        "第" + cycle + "条｜已取" + sampled + "帧，正在收尾判断…");

                ModelEngine.Decision decision;
                long postCaptureStart = System.currentTimeMillis();
                SiglipCollected siglip = collectSiglip(capture);
                long clipStart = System.currentTimeMillis();
                boolean fallback = false;

                boolean fullMem = cycle == 1 || cycle % FULL_MEM_LOG_EVERY_CYCLES == 0;
                StabilityDiagnostics.logSnapshot(context, cycle, "before_clip", fullMem);

                try {
                    if (siglip.ok) {
                        decision = engine.analyzeWithSensual(frames, siglip.scores);
                    } else {
                        fallback = true;
                        TraceLogger.critical("PERF", "pipeline fallback to full analyze: " + siglip.error);
                        decision = engine.analyze(frames);
                    }
                } finally {
                    // All pipeline futures have been drained before reaching here, so recycling
                    // cannot race an ONNX inference reading these bitmaps.
                }
                long clipDecisionMs = System.currentTimeMillis() - clipStart;
                long postCaptureMs = System.currentTimeMillis() - postCaptureStart;

                TraceLogger.critical("PERF",
                        "cycle=" + cycle +
                        " frames=" + sampled +
                        " captureMs=" + capture.captureMs +
                        " siglipComputeMs=" + siglip.computeMs +
                        " siglipTailWaitMs=" + siglip.tailWaitMs +
                        " clipDecisionMs=" + clipDecisionMs +
                        " postCaptureMs=" + postCaptureMs +
                        " fallback=" + fallback +
                        " backend=" + engine.getBackendNote());

                recycle(frames);
                if (fullMem) {
                    StabilityDiagnostics.logSnapshot(context, cycle, "after_recycle", true);
                }

                String friendly = friendlyResult(decision);
                String detail = sampled + "帧｜女生" + decision.femaleFrames + "/" + decision.checkedFrames +
                        "｜最高" + String.format(Locale.US, "%.2f", decision.maxSensual) +
                        "｜" + formatMs(capture.captureMs + postCaptureMs);
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
                    boolean ok = ShareFlowV8.shareToTarget(service, target, running);
                    long shareMs = System.currentTimeMillis() - shareStart;
                    if (!ok && running.get()) {
                        // A single share failure is a per-video failure, not an automation failure.
                        // ShareFlowV8 has already tried to dismiss the share UI. Do not swipe here:
                        // the next loop iteration owns the one and only next-video swipe.
                        TraceLogger.critical("SHARE_SKIP",
                                "cycle=" + cycle + " shareMs=" + shareMs + " -> continue next video");
                        TapAccessibilityService.setOverlayStatus(
                                "第" + cycle + "条｜分享失败，已跳过\n继续下一条");
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
            StabilityDiagnostics.logSnapshot(context, cycle, "caught_exception", true);
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

    private PipelineCapture captureFramesPipelined(ModelEngine engine, int cycle) throws Exception {
        ArrayList<ModelEngine.FrameData> out = new ArrayList<>();
        ArrayList<Future<SiglipBatchResult>> futures = new ArrayList<>();
        long started = System.currentTimeMillis();
        long next = started;
        int submittedUntil = 0;
        int consecutiveMisses = 0;
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
                consecutiveMisses = 0;
            } catch (Throwable e) {
                consecutiveMisses++;
                TraceLogger.log("CAPTURE",
                        "cycle=" + cycle + " miss=" + consecutiveMisses +
                                " err=" + shortError(e));

                if (consecutiveMisses >= CAPTURE_RECOVER_AFTER_MISSES && running.get()) {
                    StabilityDiagnostics.logSnapshot(context, cycle, "capture_stalled", true);
                    boolean recovered = cap.recoverCapturePipeline();
                    TraceLogger.critical("CAPTURE_RECOVER",
                            "cycle=" + cycle + " afterMisses=" + consecutiveMisses +
                                    " recovered=" + recovered);
                    consecutiveMisses = 0;
                    if (!recovered && !ScreenCaptureService.isReady()) {
                        throw new IllegalStateException("屏幕捕获已失效，需要重新授权");
                    }
                }
            } finally {
                if (full != null && !full.isRecycled()) full.recycle();
            }

            if (out.size() - submittedUntil >= SIGLIP_PIPELINE_BATCH) {
                int start = submittedUntil;
                int end = start + SIGLIP_PIPELINE_BATCH;
                futures.add(submitSiglipBatch(engine, out, start, end));
                submittedUntil = end;
            }

            if (out.size() >= TARGET_FRAMES &&
                    System.currentTimeMillis() - started >= 4500) break;
            next += SAMPLE_INTERVAL_MS;
        }

        if (out.size() >= 2 && submittedUntil < out.size()) {
            futures.add(submitSiglipBatch(engine, out, submittedUntil, out.size()));
        }

        return new PipelineCapture(out, futures, System.currentTimeMillis() - started);
    }

    private Future<SiglipBatchResult> submitSiglipBatch(
            ModelEngine engine,
            List<ModelEngine.FrameData> frames,
            int start,
            int end) {
        ArrayList<ModelEngine.FrameData> batch = new ArrayList<>(frames.subList(start, end));
        TraceLogger.log("PIPE", "submit SigLIP frames=" + start + ".." + (end - 1));
        return siglipPipelineExecutor.submit(() -> {
            long t0 = System.currentTimeMillis();
            float[] scores = engine.scoreSiglip(batch);
            long ms = System.currentTimeMillis() - t0;
            TraceLogger.log("PIPE", "done SigLIP frames=" + start + ".." + (end - 1) + " ms=" + ms);
            return new SiglipBatchResult(start, scores, ms);
        });
    }

    private SiglipCollected collectSiglip(PipelineCapture capture) throws InterruptedException {
        long tailStart = System.currentTimeMillis();
        float[] scores = new float[capture.frames.size()];
        boolean[] covered = new boolean[capture.frames.size()];
        long computeMs = 0L;
        String error = "";

        for (Future<SiglipBatchResult> future : capture.futures) {
            try {
                SiglipBatchResult r = future.get();
                computeMs += r.computeMs;
                if (r.start < 0 || r.start + r.scores.length > scores.length) {
                    error = "pipeline result range invalid";
                    continue;
                }
                System.arraycopy(r.scores, 0, scores, r.start, r.scores.length);
                for (int i = 0; i < r.scores.length; i++) covered[r.start + i] = true;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                error = shortError(cause);
            }
        }

        long tailWaitMs = System.currentTimeMillis() - tailStart;
        if (error.isEmpty()) {
            for (boolean ok : covered) {
                if (!ok) {
                    error = "pipeline scores incomplete";
                    break;
                }
            }
        }
        return new SiglipCollected(error.isEmpty(), scores, computeMs, tailWaitMs, error);
    }

    private void drainPipeline(PipelineCapture capture) {
        for (Future<SiglipBatchResult> future : capture.futures) {
            try {
                future.get();
            } catch (Throwable ignored) {}
        }
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
        String s = e == null ? null : e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e == null ? "unknown" : e.getClass().getSimpleName();
        if (s.length() > 70) s = s.substring(0, 70);
        return s;
    }

    private static final class PipelineCapture {
        final ArrayList<ModelEngine.FrameData> frames;
        final ArrayList<Future<SiglipBatchResult>> futures;
        final long captureMs;
        PipelineCapture(ArrayList<ModelEngine.FrameData> frames,
                        ArrayList<Future<SiglipBatchResult>> futures,
                        long captureMs) {
            this.frames = frames;
            this.futures = futures;
            this.captureMs = captureMs;
        }
    }

    private static final class SiglipBatchResult {
        final int start;
        final float[] scores;
        final long computeMs;
        SiglipBatchResult(int start, float[] scores, long computeMs) {
            this.start = start;
            this.scores = scores;
            this.computeMs = computeMs;
        }
    }

    private static final class SiglipCollected {
        final boolean ok;
        final float[] scores;
        final long computeMs;
        final long tailWaitMs;
        final String error;
        SiglipCollected(boolean ok, float[] scores, long computeMs, long tailWaitMs, String error) {
            this.ok = ok;
            this.scores = scores;
            this.computeMs = computeMs;
            this.tailWaitMs = tailWaitMs;
            this.error = error == null ? "" : error;
        }
    }
}
