package com.example.tapreplay;

import ai.onnxruntime.*;
import ai.onnxruntime.providers.NNAPIFlags;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.*;
import java.io.FileInputStream;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ModelEngine {
    public static final class FrameData {
        public final Bitmap siglip;
        public final Bitmap[] clipViews;
        public FrameData(Bitmap siglip, Bitmap[] clipViews) {
            this.siglip = siglip;
            this.clipViews = clipViews;
        }
        public void recycle() {
            if (siglip != null && !siglip.isRecycled()) siglip.recycle();
            if (clipViews != null) {
                for (Bitmap b : clipViews) if (b != null && !b.isRecycled()) b.recycle();
            }
        }
    }

    public static final class Decision {
        public final String state;
        public final String path;
        public final String reason;
        public final float topkMean;
        public final float maxSensual;
        public final int femaleFrames;
        public final int checkedFrames;

        Decision(String state, String path, String reason,
                 float topkMean, float maxSensual, int femaleFrames, int checkedFrames) {
            this.state = state;
            this.path = path;
            this.reason = reason;
            this.topkMean = topkMean;
            this.maxSensual = maxSensual;
            this.femaleFrames = femaleFrames;
            this.checkedFrames = checkedFrames;
        }

        public boolean isPositive() { return "positive".equals(state); }
    }

    private static volatile ModelEngine INSTANCE;
    public static ModelEngine get(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelEngine.class) {
                if (INSTANCE == null) INSTANCE = new ModelEngine(context.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private final Context context;
    private final ReentrantLock lock = new ReentrantLock();
    private OrtEnvironment env;
    private OrtSession siglipSession;
    private OrtSession clipSession;
    private volatile boolean loaded = false;
    private volatile String backendNote = "未加载";

    private static final int SIG_SIZE = 256;
    private static final int CLIP_SIZE = 224;
    private static final int SENSUAL_INDEX = 4;

    // Internal stability build: keep recognition semantics, but avoid repeated native allocations.
    private static final int MAX_SIGLIP_INPUT_FRAMES = 20;
    private static final int CLIP_INFER_BATCH = 12;
    private static final int ORT_INTRA_OP_THREADS_MAX = 4;

    private ShortBuffer siglipInputBuffer;
    private ShortBuffer clipInputBuffer;
    private final int[] siglipPixels = new int[SIG_SIZE * SIG_SIZE];
    private final int[] clipPixels = new int[CLIP_SIZE * CLIP_SIZE];

    private static final float FEMALE_MIN_PROB = 0.40f;
    private static final float FEMALE_MIN_MARGIN = 0.04f;
    private static final float FEMALE_RATIO_PERSON = 0.60f;
    private static final float FEMALE_RATIO_ALL = 0.50f;
    private static final int MIN_FEMALE_FRAMES = 3;

    private static final float WEAK = 0.50f;
    private static final float STRONG = 0.65f;
    private static final float POS_A_TOPK = 0.68f;
    private static final float POS_A_MAX = 0.75f;
    private static final float POS_B_TOPK = 0.60f;
    private static final int POS_B_MIN_STRONG = 2;
    private static final float POS_C_TOPK = 0.54f;
    private static final float POS_C_MIN_WEAK_RATIO = 0.30f;
    private static final int POS_C_MIN_WEAK_COUNT = 3;
    private static final int POS_C_MIN_WEAK_RUN = 2;
    private static final int POS_D_MAX_FRAMES = 7;
    private static final float POS_D_TOPK = 0.55f;
    private static final float POS_D_MAX = 0.65f;
    private static final int POS_D_MIN_WEAK_COUNT = 2;

    private static final float BORDER_TOPK = 0.46f;
    private static final float BORDER_MAX = 0.55f;
    private static final int BORDER_MIN_WEAK_COUNT = 2;
    private static final float BORDER_STRONG_SINGLE = 0.65f;

    private static final float[] CLIP_MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] CLIP_STD = {0.26862954f, 0.26130258f, 0.27577711f};

    private ModelEngine(Context context) {
        this.context = context;
    }

    public boolean isLoaded() { return loaded; }
    public String getBackendNote() { return backendNote; }

    public synchronized void reset() {
        loaded = false;
        lock.lock();
        try {
            closeSession(siglipSession);
            closeSession(clipSession);
            siglipSession = null;
            clipSession = null;
            siglipInputBuffer = null;
            clipInputBuffer = null;
            backendNote = "等待重新加载";
        } finally {
            lock.unlock();
        }
    }

    public void ensureLoaded() throws Exception {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            env = OrtEnvironment.getEnvironment();
            boolean preferNnapi = context.getSharedPreferences("douyin_filter", Context.MODE_PRIVATE)
                    .getBoolean("prefer_nnapi", false);

            String mode = preferNnapi ? "NNAPI→CPU回退" : "CPU";
            try {
                siglipSession = createSession("models/siglip2_raw_logits_fp16.onnx", preferNnapi);
                clipSession = createSession("models/clip_female_vision_fp16.onnx", preferNnapi);
            } catch (Throwable first) {
                closeSession(siglipSession);
                closeSession(clipSession);
                siglipSession = createSession("models/siglip2_raw_logits_fp16.onnx", false);
                clipSession = createSession("models/clip_female_vision_fp16.onnx", false);
                mode = "CPU（NNAPI不可用时回退）";
            }
            backendNote = mode;
            loaded = true;
        }
    }

    private OrtSession createSession(String asset, boolean nnapi) throws Exception {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        int cores = Runtime.getRuntime().availableProcessors();
        int intraThreads = Math.max(2, Math.min(ORT_INTRA_OP_THREADS_MAX, cores - 2));
        opts.setIntraOpNumThreads(intraThreads);
        opts.setInterOpNumThreads(1);
        if (nnapi) {
            opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16));
        }
        TraceLogger.critical("MODEL",
                "create session=" + asset + " nnapi=" + nnapi + " intraThreads=" + intraThreads);

        try (AssetFileDescriptor afd = context.getAssets().openFd(asset);
             FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            FileChannel ch = fis.getChannel();
            MappedByteBuffer mapped = ch.map(
                    FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
            return env.createSession(mapped, opts);
        }
    }

    private void closeSession(OrtSession s) {
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
    }

    public FrameData prepareFrame(Bitmap full) {
        Bitmap sig = Bitmap.createScaledBitmap(full, SIG_SIZE, SIG_SIZE, true);
        Bitmap[] views = makeClipViews(full);
        return new FrameData(sig, views);
    }

    private Bitmap[] makeClipViews(Bitmap full) {
        int w = full.getWidth(), h = full.getHeight();
        Bitmap[] out = new Bitmap[4];

        out[0] = Bitmap.createBitmap(CLIP_SIZE, CLIP_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out[0]);
        canvas.drawColor(Color.rgb(127, 127, 127));
        float scale = Math.min((float)CLIP_SIZE / w, (float)CLIP_SIZE / h);
        float dw = w * scale, dh = h * scale;
        RectF dst = new RectF((CLIP_SIZE - dw) / 2f, (CLIP_SIZE - dh) / 2f,
                (CLIP_SIZE + dw) / 2f, (CLIP_SIZE + dh) / 2f);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(full, null, dst, paint);

        if (h >= w) {
            int side = w;
            int maxTop = Math.max(0, h - side);
            int[] tops = {0, maxTop / 2, maxTop};
            for (int i = 0; i < 3; i++) {
                Bitmap crop = Bitmap.createBitmap(full, 0, tops[i], side, side);
                out[i + 1] = Bitmap.createScaledBitmap(crop, CLIP_SIZE, CLIP_SIZE, true);
                if (crop != out[i + 1]) crop.recycle();
            }
        } else {
            int side = h;
            int maxLeft = Math.max(0, w - side);
            int[] lefts = {0, maxLeft / 2, maxLeft};
            for (int i = 0; i < 3; i++) {
                Bitmap crop = Bitmap.createBitmap(full, lefts[i], 0, side, side);
                out[i + 1] = Bitmap.createScaledBitmap(crop, CLIP_SIZE, CLIP_SIZE, true);
                if (crop != out[i + 1]) crop.recycle();
            }
        }
        return out;
    }

    /**
     * Full legacy-quality analysis. Kept as a safe fallback and as a reference path.
     */
    public Decision analyze(List<FrameData> frames) throws Exception {
        ensureLoaded();
        if (frames.size() < 2) {
            return new Decision("insufficient", "INSUFFICIENT",
                    "有效采样帧不足", 0, 0, 0, 0);
        }
        float[] sensual = scoreSiglip(frames);
        return analyzeWithSensual(frames, sensual);
    }

    /**
     * Run only the unchanged SigLIP2 sensual branch. This makes it possible to overlap
     * SigLIP inference with frame capture without changing the model, preprocessing or scores.
     */
    public float[] scoreSiglip(List<FrameData> frames) throws Exception {
        ensureLoaded();
        if (frames == null || frames.isEmpty()) return new float[0];
        return runSiglip(frames);
    }

    /**
     * Finish the exact same CLIP female gate and A/B/C/D decision rules using already-computed
     * SigLIP scores. The score array must correspond one-to-one with frames in capture order.
     */
    public Decision analyzeWithSensual(List<FrameData> frames, float[] sensual) throws Exception {
        ensureLoaded();
        if (frames.size() < 2) {
            return new Decision("insufficient", "INSUFFICIENT",
                    "有效采样帧不足", 0, 0, 0, 0);
        }
        if (sensual == null || sensual.length != frames.size()) {
            throw new IllegalArgumentException("SigLIP分数数量与采样帧不一致");
        }

        List<Integer> checkIndices = selectFemaleCheckIndices(sensual);

        List<Bitmap> flatViews = new ArrayList<>();
        for (int idx : checkIndices) Collections.addAll(flatViews, frames.get(idx).clipViews);
        float[][] clip = runClip(flatViews);

        int personFrames = 0;
        int femaleFrames = 0;

        for (int j = 0; j < checkIndices.size(); j++) {
            float[][] vp = new float[4][3];
            for (int v = 0; v < 4; v++) vp[v] = clip[j * 4 + v];

            int bestView = 0;
            float bestEvidence = -Float.MAX_VALUE;
            int womanVotes = 0;
            for (int v = 0; v < 4; v++) {
                float ev = Math.max(vp[v][0], vp[v][1]) - vp[v][2];
                if (ev > bestEvidence) {
                    bestEvidence = ev;
                    bestView = v;
                }
                if (vp[v][0] > vp[v][1] && vp[v][0] > vp[v][2]) womanVotes++;
            }

            float[] best = Arrays.copyOf(vp[bestView], 3);
            if (womanVotes >= 2) {
                best[0] *= 1.06f;
                float sum = Math.max(1e-8f, best[0] + best[1] + best[2]);
                best[0] /= sum; best[1] /= sum; best[2] /= sum;
            }

            int label = argmax(best);
            if (label != 2) personFrames++;
            boolean female = label == 0 &&
                    best[0] >= FEMALE_MIN_PROB &&
                    (best[0] - best[1]) >= FEMALE_MIN_MARGIN &&
                    best[0] > best[2];
            if (female) femaleFrames++;
        }

        int checked = checkIndices.size();
        float ratioPerson = femaleFrames / (float)Math.max(1, personFrames);
        float ratioAll = femaleFrames / (float)Math.max(1, checked);
        int femaleMinRequired;
        if (checked <= 2) femaleMinRequired = checked;
        else femaleMinRequired = Math.min(
                MIN_FEMALE_FRAMES,
                Math.max(2, (int)Math.ceil(checked * FEMALE_RATIO_ALL)));

        boolean femaleGate = femaleFrames >= femaleMinRequired &&
                ratioPerson >= FEMALE_RATIO_PERSON &&
                ratioAll >= FEMALE_RATIO_ALL;

        int k = dynamicTopK(sensual.length);
        float[] sorted = Arrays.copyOf(sensual, sensual.length);
        Arrays.sort(sorted);
        float sum = 0;
        for (int i = 0; i < k; i++) sum += sorted[sorted.length - 1 - i];
        float topkMean = k > 0 ? sum / k : 0;
        float max = sorted.length > 0 ? sorted[sorted.length - 1] : 0;

        int count050 = countAtLeast(sensual, WEAK);
        int count065 = countAtLeast(sensual, STRONG);
        int run050 = longestRun(sensual, WEAK);
        int weakNeed = Math.max(POS_C_MIN_WEAK_COUNT,
                (int)Math.ceil(sensual.length * POS_C_MIN_WEAK_RATIO));
        boolean enough = sensual.length >= 3;

        boolean ruleA = enough && topkMean >= POS_A_TOPK && max >= POS_A_MAX;
        boolean ruleB = enough && topkMean >= POS_B_TOPK && count065 >= POS_B_MIN_STRONG;
        boolean ruleC = enough && topkMean >= POS_C_TOPK &&
                count050 >= weakNeed && run050 >= POS_C_MIN_WEAK_RUN;
        boolean ruleD = enough && sensual.length <= POS_D_MAX_FRAMES &&
                topkMean >= POS_D_TOPK && max >= POS_D_MAX &&
                count050 >= POS_D_MIN_WEAK_COUNT;

        if (femaleGate && ruleA)
            return new Decision("positive", "A:strong-peak", "强峰值证据", topkMean, max, femaleFrames, checked);
        if (femaleGate && ruleB)
            return new Decision("positive", "B:multi-strong", "多个较强帧", topkMean, max, femaleFrames, checked);
        if (femaleGate && ruleC)
            return new Decision("positive", "C:sustained-moderate", "中等证据持续出现", topkMean, max, femaleFrames, checked);
        if (femaleGate && ruleD)
            return new Decision("positive", "D:short-window", "短样本中高分证据", topkMean, max, femaleFrames, checked);

        boolean borderEvidence =
                (topkMean >= BORDER_TOPK && max >= BORDER_MAX) ||
                count050 >= BORDER_MIN_WEAK_COUNT ||
                max >= BORDER_STRONG_SINGLE;

        if (!femaleGate) {
            if (borderEvidence)
                return new Decision("border", "FEMALE_GATE_BORDER", "Sensual有证据，但女性主体门槛未通过", topkMean, max, femaleFrames, checked);
            return new Decision("negative", "FEMALE_GATE_FAIL", "女性主体证据不足", topkMean, max, femaleFrames, checked);
        }
        if (borderEvidence)
            return new Decision("border", "BORDER", "女性通过，但Sensual仍在边界区", topkMean, max, femaleFrames, checked);
        return new Decision("negative", "NEGATIVE", "多帧Sensual证据不足", topkMean, max, femaleFrames, checked);
    }

    private float[] runSiglip(List<FrameData> frames) throws Exception {
        int n = frames.size();
        if (n > MAX_SIGLIP_INPUT_FRAMES) {
            throw new IllegalArgumentException("SigLIP batch too large: " + n);
        }

        lock.lock();
        try {
            int elements = n * 3 * SIG_SIZE * SIG_SIZE;
            ShortBuffer sb = ensureSiglipInputBuffer(elements);
            for (int i = 0; i < n; i++) {
                Bitmap b = frames.get(i).siglip;
                b.getPixels(siglipPixels, 0, SIG_SIZE, 0, 0, SIG_SIZE, SIG_SIZE);
                for (int c = 0; c < 3; c++) {
                    for (int p : siglipPixels) {
                        int v = c == 0 ? Color.red(p) : c == 1 ? Color.green(p) : Color.blue(p);
                        float f = (v / 255f - 0.5f) / 0.5f;
                        sb.put(floatToHalf(f));
                    }
                }
            }
            sb.flip();

            long[] shape = {n, 3, SIG_SIZE, SIG_SIZE};
            try (OnnxTensor input = OnnxTensor.createTensor(env, sb, shape, OnnxJavaType.FLOAT16);
                 OrtSession.Result result = siglipSession.run(Collections.singletonMap(
                         siglipSession.getInputNames().iterator().next(), input))) {
                OnnxTensor out = (OnnxTensor) result.get(0);
                FloatBuffer fb = out.getFloatBuffer();
                long[] outShape = ((TensorInfo)out.getInfo()).getShape();
                int labels = (int)outShape[outShape.length - 1];
                float[] scores = new float[n];
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < labels; j++) {
                        float logit = fb.get();
                        if (j == SENSUAL_INDEX) scores[i] = sigmoid(logit);
                    }
                }
                return scores;
            }
        } finally {
            lock.unlock();
        }
    }

    private float[][] runClip(List<Bitmap> images) throws Exception {
        int n = images.size();
        float[][] probs = new float[n][3];
        if (n == 0) return probs;

        lock.lock();
        try {
            for (int offset = 0; offset < n; offset += CLIP_INFER_BATCH) {
                int batch = Math.min(CLIP_INFER_BATCH, n - offset);
                int elements = batch * 3 * CLIP_SIZE * CLIP_SIZE;
                ShortBuffer sb = ensureClipInputBuffer(elements);

                for (int i = 0; i < batch; i++) {
                    Bitmap b = images.get(offset + i);
                    b.getPixels(clipPixels, 0, CLIP_SIZE, 0, 0, CLIP_SIZE, CLIP_SIZE);
                    for (int c = 0; c < 3; c++) {
                        for (int p : clipPixels) {
                            int v = c == 0 ? Color.red(p) : c == 1 ? Color.green(p) : Color.blue(p);
                            float f = (v / 255f - CLIP_MEAN[c]) / CLIP_STD[c];
                            sb.put(floatToHalf(f));
                        }
                    }
                }
                sb.flip();

                long[] shape = {batch, 3, CLIP_SIZE, CLIP_SIZE};
                try (OnnxTensor input = OnnxTensor.createTensor(env, sb, shape, OnnxJavaType.FLOAT16);
                     OrtSession.Result result = clipSession.run(Collections.singletonMap(
                             clipSession.getInputNames().iterator().next(), input))) {
                    OnnxTensor out = (OnnxTensor) result.get(0);
                    FloatBuffer fb = out.getFloatBuffer();
                    for (int i = 0; i < batch; i++) {
                        for (int j = 0; j < 3; j++) probs[offset + i][j] = fb.get();
                    }
                }
            }
            return probs;
        } finally {
            lock.unlock();
        }
    }

    private ShortBuffer ensureSiglipInputBuffer(int requiredElements) {
        int capacity = MAX_SIGLIP_INPUT_FRAMES * 3 * SIG_SIZE * SIG_SIZE;
        if (requiredElements > capacity) {
            throw new IllegalArgumentException("SigLIP input exceeds reusable buffer");
        }
        if (siglipInputBuffer == null) {
            siglipInputBuffer = allocateHalfBuffer(capacity);
            TraceLogger.critical("MEMBUF",
                    "allocated reusable SigLIP FP16 direct buffer MiB=" +
                            String.format(Locale.US, "%.1f", capacity * 2 / 1048576.0));
        }
        siglipInputBuffer.clear();
        siglipInputBuffer.limit(requiredElements);
        return siglipInputBuffer;
    }

    private ShortBuffer ensureClipInputBuffer(int requiredElements) {
        int capacity = CLIP_INFER_BATCH * 3 * CLIP_SIZE * CLIP_SIZE;
        if (requiredElements > capacity) {
            throw new IllegalArgumentException("CLIP input exceeds reusable buffer");
        }
        if (clipInputBuffer == null) {
            clipInputBuffer = allocateHalfBuffer(capacity);
            TraceLogger.critical("MEMBUF",
                    "allocated reusable CLIP FP16 direct buffer MiB=" +
                            String.format(Locale.US, "%.1f", capacity * 2 / 1048576.0) +
                            " batch=" + CLIP_INFER_BATCH);
        }
        clipInputBuffer.clear();
        clipInputBuffer.limit(requiredElements);
        return clipInputBuffer;
    }

    private ShortBuffer allocateHalfBuffer(int elements) {
        return ByteBuffer.allocateDirect(elements * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
    }

    private static float sigmoid(float x) {
        if (x >= 0) return (float)(1.0 / (1.0 + Math.exp(-x)));
        double ex = Math.exp(x);
        return (float)(ex / (1.0 + ex));
    }

    private static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int s = (bits >>> 16) & 0x8000;
        int e = ((bits >>> 23) & 0xff) - 127 + 15;
        int m = bits & 0x7fffff;

        if (e <= 0) {
            if (e < -10) return (short)s;
            m = (m | 0x800000) >> (1 - e);
            if ((m & 0x1000) != 0) m += 0x2000;
            return (short)(s | (m >> 13));
        }
        if (e >= 31) return (short)(s | 0x7c00);
        if ((m & 0x1000) != 0) {
            m += 0x2000;
            if ((m & 0x800000) != 0) {
                m = 0;
                e += 1;
                if (e >= 31) return (short)(s | 0x7c00);
            }
        }
        return (short)(s | (e << 10) | (m >> 13));
    }

    private static int argmax(float[] a) {
        int idx = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[idx]) idx = i;
        return idx;
    }

    private static int dynamicTopK(int n) {
        if (n <= 0) return 0;
        if (n <= 4) return Math.min(2, n);
        if (n <= 8) return Math.min(3, n);
        if (n <= 12) return Math.min(4, n);
        return Math.min(5, n);
    }

    private static int countAtLeast(float[] scores, float t) {
        int c = 0;
        for (float s : scores) if (s >= t) c++;
        return c;
    }

    private static int longestRun(float[] scores, float t) {
        int best = 0, cur = 0;
        for (float s : scores) {
            if (s >= t) { cur++; best = Math.max(best, cur); }
            else cur = 0;
        }
        return best;
    }

    private static List<Integer> selectFemaleCheckIndices(float[] scores) {
        int n = scores.length;
        List<Integer> all = new ArrayList<>();
        if (n <= 9) {
            for (int i = 0; i < n; i++) all.add(i);
            return all;
        }

        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) set.add(Math.round(i * (n - 1) / 4f));

        Integer[] ids = new Integer[n];
        for (int i = 0; i < n; i++) ids[i] = i;
        Arrays.sort(ids, (a, b) -> Float.compare(scores[b], scores[a]));
        for (int i = 0; i < Math.min(4, n); i++) set.add(ids[i]);

        all.addAll(set);
        Collections.sort(all);
        return all;
    }
}
