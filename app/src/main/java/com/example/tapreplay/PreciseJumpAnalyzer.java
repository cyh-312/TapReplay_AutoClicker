package com.example.tapreplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

public final class PreciseJumpAnalyzer {
    public static final int SAMPLE_RATE = 44100;
    private static final int TEMPLATE_MS = 120;
    private static final int TEMPLATE_PRE_MS = 15;
    private static final int SEARCH_HOP_MS = 2;
    private static final int MIN_EVENT_GAP_MS = 115;
    private static final int MAX_EVENTS = 60;
    private static final double ABSOLUTE_MIN_CORR = 0.47;
    private static final double RELATIVE_CORR_RATIO = 0.72;

    private PreciseJumpAnalyzer() { }

    public static AudioJumpAnalyzer.Result analyze(short[] pcm, int sampleRate) {
        AudioJumpAnalyzer.Result result = new AudioJumpAnalyzer.Result();
        if (pcm == null || pcm.length < sampleRate / 2) {
            result.debugText = "录音太短，至少录 1 秒以上";
            return result;
        }
        double[] signal = preprocess(pcm);
        ArrayList<Peak> seeds = findEnergyPeaks(signal, sampleRate);
        result.candidateCount = seeds.size();
        if (seeds.isEmpty()) {
            result.debugText = "没有检测到明显跳跃音效；请确认游戏有声音且内部音频能捕获";
            return result;
        }
        TemplateChoice choice = chooseTemplate(signal, sampleRate, seeds);
        if (choice == null) {
            result.debugText = "未能建立跳跃音效模板";
            return result;
        }
        ArrayList<Peak> events = correlateTemplate(signal, sampleRate, choice.template);
        result.templateScore = choice.score;
        result.correlationPeakCount = events.size();
        if (events.isEmpty()) {
            result.debugText = "模板匹配未找到稳定跳跃音效";
            return result;
        }
        Collections.sort(events, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        for (Peak p : events) result.eventTimesMs.add(p.timeMs);
        long first = events.get(0).timeMs;
        result.recommendedTimesMs.add(0L);
        for (int i = 1; i < events.size(); i++) {
            long t = events.get(i).timeMs - first;
            if (t >= 60L) result.recommendedTimesMs.add(t);
        }
        double durationSec = pcm.length / (double) sampleRate;
        result.debugText = String.format(Locale.US,
                "精确分析%.1fs 候选%d 模板%.2f 匹配%d 推荐%d 事件:%s",
                durationSec, result.candidateCount, result.templateScore,
                result.correlationPeakCount, result.recommendedTimesMs.size(),
                preview(result.eventTimesMs));
        return result;
    }

    private static double[] preprocess(short[] pcm) {
        double[] out = new double[pcm.length];
        double prevIn = 0.0;
        double hp = 0.0;
        double maxAbs = 1e-9;
        for (int i = 0; i < pcm.length; i++) {
            double x = pcm[i] / 32768.0;
            double y = x - prevIn + 0.985 * hp;
            prevIn = x;
            hp = y;
            double d = i == 0 ? 0.0 : y - out[i - 1];
            out[i] = d;
            if (Math.abs(d) > maxAbs) maxAbs = Math.abs(d);
        }
        for (int i = 0; i < out.length; i++) out[i] /= maxAbs;
        return out;
    }

    private static ArrayList<Peak> findEnergyPeaks(double[] signal, int sampleRate) {
        int frame = Math.max(128, sampleRate * 12 / 1000);
        int hop = Math.max(32, sampleRate * 2 / 1000);
        int frames = 1 + Math.max(0, (signal.length - frame) / hop);
        double[] energy = new double[frames];
        for (int i = 0; i < frames; i++) {
            int start = i * hop;
            double sum = 0.0;
            for (int j = 0; j < frame && start + j < signal.length; j++) sum += Math.abs(signal[start + j]);
            energy[i] = sum / frame;
        }
        double med = median(energy);
        double mad = medianAbsDeviation(energy, med);
        double threshold = med + Math.max(0.018, 4.0 * mad);
        ArrayList<Peak> raw = new ArrayList<>();
        for (int i = 1; i < frames - 1; i++) {
            if (energy[i] >= threshold && energy[i] >= energy[i - 1] && energy[i] > energy[i + 1]) {
                raw.add(new Peak(Math.round((i * hop + frame / 2.0) * 1000.0 / sampleRate), i * hop + frame / 2, energy[i]));
            }
        }
        return selectSeparated(raw, MIN_EVENT_GAP_MS);
    }

    private static TemplateChoice chooseTemplate(double[] signal, int sampleRate, ArrayList<Peak> seeds) {
        int len = sampleRate * TEMPLATE_MS / 1000;
        int pre = sampleRate * TEMPLATE_PRE_MS / 1000;
        TemplateChoice best = null;
        int limit = Math.min(seeds.size(), 12);
        for (int i = 0; i < limit; i++) {
            Peak seed = seeds.get(i);
            double[] template = extractNormalized(signal, seed.sampleIndex - pre, len);
            if (template == null) continue;
            int count = 0;
            double sum = 0.0;
            for (Peak other : seeds) {
                double[] seg = extractNormalized(signal, other.sampleIndex - pre, len);
                if (seg == null) continue;
                double c = dot(template, seg);
                if (c > 0.40) { count++; sum += c; }
            }
            double avg = count == 0 ? 0.0 : sum / count;
            double score = count * 0.55 + avg;
            if (best == null || score > best.score) best = new TemplateChoice(template, score);
        }
        return best;
    }

    private static ArrayList<Peak> correlateTemplate(double[] signal, int sampleRate, double[] template) {
        int hop = Math.max(1, sampleRate * SEARCH_HOP_MS / 1000);
        int len = template.length;
        int steps = 1 + Math.max(0, (signal.length - len) / hop);
        double[] corr = new double[steps];
        double best = 0.0;
        for (int i = 0; i < steps; i++) {
            int start = i * hop;
            double norm = 0.0;
            double d = 0.0;
            for (int j = 0; j < len; j++) { double v = signal[start + j]; d += v * template[j]; norm += v * v; }
            double c = norm <= 1e-12 ? 0.0 : d / Math.sqrt(norm);
            corr[i] = c;
            if (c > best) best = c;
        }
        double threshold = Math.max(ABSOLUTE_MIN_CORR, best * RELATIVE_CORR_RATIO);
        ArrayList<Peak> raw = new ArrayList<>();
        for (int i = 1; i < steps - 1; i++) {
            if (corr[i] >= threshold && corr[i] >= corr[i - 1] && corr[i] > corr[i + 1]) {
                long t = Math.round((i * hop + TEMPLATE_PRE_MS * sampleRate / 1000.0) * 1000.0 / sampleRate);
                raw.add(new Peak(t, i * hop, corr[i]));
            }
        }
        return selectSeparated(raw, MIN_EVENT_GAP_MS);
    }

    private static ArrayList<Peak> selectSeparated(ArrayList<Peak> raw, int minGapMs) {
        Collections.sort(raw, (a, b) -> Double.compare(b.score, a.score));
        ArrayList<Peak> selected = new ArrayList<>();
        for (Peak p : raw) {
            boolean close = false;
            for (Peak q : selected) { if (Math.abs(q.timeMs - p.timeMs) < minGapMs) { close = true; break; } }
            if (!close) selected.add(p);
            if (selected.size() >= MAX_EVENTS) break;
        }
        Collections.sort(selected, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return selected;
    }

    private static double[] extractNormalized(double[] signal, int start, int len) {
        if (start < 0 || start + len > signal.length) return null;
        double[] out = new double[len];
        double mean = 0.0;
        for (int i = 0; i < len; i++) { out[i] = signal[start + i]; mean += out[i]; }
        mean /= len;
        double norm = 0.0;
        for (int i = 0; i < len; i++) { out[i] -= mean; norm += out[i] * out[i]; }
        norm = Math.sqrt(norm);
        if (norm < 1e-9) return null;
        for (int i = 0; i < len; i++) out[i] /= norm;
        return out;
    }

    private static double dot(double[] a, double[] b) { double s = 0.0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }
    private static double median(double[] v) { double[] c = Arrays.copyOf(v, v.length); Arrays.sort(c); int m = c.length / 2; return c.length % 2 == 0 ? (c[m - 1] + c[m]) / 2.0 : c[m]; }
    private static double medianAbsDeviation(double[] v, double med) { double[] d = new double[v.length]; for (int i = 0; i < v.length; i++) d[i] = Math.abs(v[i] - med); return median(d); }
    private static String preview(ArrayList<Long> v) { StringBuilder sb = new StringBuilder(); int n = Math.min(v.size(), 12); for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); sb.append(v.get(i)); } if (v.size() > n) sb.append("..."); return sb.toString(); }

    private static final class Peak { final long timeMs; final int sampleIndex; final double score; Peak(long t, int s, double sc) { timeMs = t; sampleIndex = s; score = sc; } }
    private static final class TemplateChoice { final double[] template; final double score; TemplateChoice(double[] t, double s) { template = t; score = s; } }
}
