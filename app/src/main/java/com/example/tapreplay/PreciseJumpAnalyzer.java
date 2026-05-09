package com.example.tapreplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * Precision analyzer for TapReplay.
 *
 * Goal: record every clear click-like transient in order, including the prompt-confirm click
 * and all jump clicks. It no longer depends only on repeated jump-template matching because
 * the confirm click sound may be different but still must be kept as time zero.
 */
public final class PreciseJumpAnalyzer {
    public static final int SAMPLE_RATE = 44100;

    private static final int FRAME_MS = 8;
    private static final int HOP_MS = 1;
    private static final int MIN_EVENT_GAP_MS = 115;
    private static final int MAX_EVENTS = 40;
    private static final double MIN_ABSOLUTE_SCORE = 0.018;

    private PreciseJumpAnalyzer() { }

    public static AudioJumpAnalyzer.Result analyze(short[] pcm, int sampleRate) {
        AudioJumpAnalyzer.Result result = new AudioJumpAnalyzer.Result();
        if (pcm == null || pcm.length < sampleRate / 2) {
            result.debugText = "录音太短，至少录 1 秒以上";
            return result;
        }

        double[] signal = preprocess(pcm);
        ArrayList<Peak> transientEvents = detectOrderedTransients(signal, sampleRate);
        result.candidateCount = transientEvents.size();

        if (transientEvents.isEmpty()) {
            result.debugText = "未检测到清晰咔哒/跳跃瞬态；请确认内部音频授权、游戏音量、录制时包含确认点击和跳跃";
            return result;
        }

        // Refine each event to the true rising edge, not the energy maximum.
        ArrayList<Peak> refined = new ArrayList<>();
        for (Peak p : transientEvents) refined.add(refineOnset(signal, sampleRate, p));
        refined = selectSeparated(refined, MIN_EVENT_GAP_MS);
        Collections.sort(refined, (a, b) -> Long.compare(a.timeMs, b.timeMs));

        // Keep the strongest ordered events, but preserve chronology.
        if (refined.size() > MAX_EVENTS) refined = keepStrongestChronological(refined, MAX_EVENTS);

        for (Peak p : refined) result.eventTimesMs.add(p.timeMs);

        // Time zero is the first detected click after the user starts analysis.
        // This should be the prompt-confirm click when the user follows the workflow.
        long origin = refined.get(0).timeMs;
        result.recommendedTimesMs.add(0L);
        for (int i = 1; i < refined.size(); i++) {
            long t = refined.get(i).timeMs - origin;
            if (t >= 60L) result.recommendedTimesMs.add(t);
        }

        // If too many non-game transients got in, try a secondary template pass over events after origin.
        // This keeps confirm + repeated jump sounds and removes isolated UI noise.
        if (result.recommendedTimesMs.size() > 14) {
            ArrayList<Peak> filtered = filterByRepeatedShape(signal, sampleRate, refined);
            if (filtered.size() >= 4) {
                result.eventTimesMs.clear();
                result.recommendedTimesMs.clear();
                for (Peak p : filtered) result.eventTimesMs.add(p.timeMs);
                origin = filtered.get(0).timeMs;
                result.recommendedTimesMs.add(0L);
                for (int i = 1; i < filtered.size(); i++) {
                    long t = filtered.get(i).timeMs - origin;
                    if (t >= 60L) result.recommendedTimesMs.add(t);
                }
                result.correlationPeakCount = filtered.size();
            } else {
                result.correlationPeakCount = refined.size();
            }
        } else {
            result.correlationPeakCount = refined.size();
        }

        double durationSec = pcm.length / (double) sampleRate;
        result.debugText = String.format(Locale.US,
                "精确分析%.1fs 候选%d 保留%d 推荐%d 事件:%s 推荐:%s",
                durationSec,
                result.candidateCount,
                result.correlationPeakCount,
                result.recommendedTimesMs.size(),
                preview(result.eventTimesMs),
                preview(result.recommendedTimesMs));
        return result;
    }

    private static double[] preprocess(short[] pcm) {
        double[] hp = new double[pcm.length];
        double prevIn = 0.0;
        double prevOut = 0.0;
        double maxAbs = 1e-9;
        for (int i = 0; i < pcm.length; i++) {
            double x = pcm[i] / 32768.0;
            double y = x - prevIn + 0.985 * prevOut;
            prevIn = x;
            prevOut = y;
            hp[i] = y;
        }
        double[] out = new double[pcm.length];
        for (int i = 1; i < hp.length; i++) {
            double d = hp[i] - hp[i - 1];
            out[i] = d;
            if (Math.abs(d) > maxAbs) maxAbs = Math.abs(d);
        }
        for (int i = 0; i < out.length; i++) out[i] /= maxAbs;
        return out;
    }

    private static ArrayList<Peak> detectOrderedTransients(double[] signal, int sampleRate) {
        int frame = Math.max(96, sampleRate * FRAME_MS / 1000);
        int hop = Math.max(1, sampleRate * HOP_MS / 1000);
        int frames = 1 + Math.max(0, (signal.length - frame) / hop);
        double[] energy = new double[frames];

        for (int i = 0; i < frames; i++) {
            int start = i * hop;
            double sum = 0.0;
            for (int j = 0; j < frame && start + j < signal.length; j++) {
                double v = signal[start + j];
                sum += Math.abs(v);
            }
            energy[i] = sum / frame;
        }

        double med = median(energy);
        double mad = medianAbsDeviation(energy, med);
        double threshold = med + Math.max(MIN_ABSOLUTE_SCORE, 5.2 * mad);

        ArrayList<Peak> raw = new ArrayList<>();
        for (int i = 2; i < frames - 2; i++) {
            boolean localMax = energy[i] >= energy[i - 1] && energy[i] >= energy[i - 2]
                    && energy[i] > energy[i + 1] && energy[i] > energy[i + 2];
            if (energy[i] >= threshold && localMax) {
                long timeMs = Math.round((i * hop + frame / 2.0) * 1000.0 / sampleRate);
                raw.add(new Peak(timeMs, i * hop + frame / 2, energy[i]));
            }
        }
        return selectSeparated(raw, MIN_EVENT_GAP_MS);
    }

    private static Peak refineOnset(double[] signal, int sampleRate, Peak peak) {
        int back = sampleRate * 45 / 1000;
        int forward = sampleRate * 18 / 1000;
        int start = clamp(peak.sampleIndex - back, 1, signal.length - 1);
        int end = clamp(peak.sampleIndex + forward, start + 1, signal.length);

        double[] abs = new double[end - start];
        for (int i = start; i < end; i++) abs[i - start] = Math.abs(signal[i]);
        double med = median(abs);
        double mad = medianAbsDeviation(abs, med);
        double onsetThreshold = med + Math.max(0.012, 3.0 * mad);

        int onset = peak.sampleIndex;
        for (int i = start; i < end; i++) {
            if (Math.abs(signal[i]) >= onsetThreshold) {
                onset = i;
                break;
            }
        }
        long t = Math.round(onset * 1000.0 / sampleRate);
        return new Peak(t, onset, peak.score);
    }

    private static ArrayList<Peak> filterByRepeatedShape(double[] signal, int sampleRate, ArrayList<Peak> events) {
        if (events.size() <= 4) return new ArrayList<>(events);
        ArrayList<Peak> body = new ArrayList<>();
        for (int i = 1; i < events.size(); i++) body.add(events.get(i));
        if (body.size() < 3) return new ArrayList<>(events);

        double[][] feats = new double[body.size()][];
        for (int i = 0; i < body.size(); i++) feats[i] = extractFeature(signal, sampleRate, body.get(i).sampleIndex);

        int bestIndex = -1;
        int bestCount = 0;
        for (int i = 0; i < feats.length; i++) {
            if (feats[i] == null) continue;
            int count = 0;
            for (int j = 0; j < feats.length; j++) {
                if (feats[j] != null && dot(feats[i], feats[j]) >= 0.54) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                bestIndex = i;
            }
        }
        if (bestIndex < 0 || bestCount < 3) return new ArrayList<>(events);

        ArrayList<Peak> out = new ArrayList<>();
        out.add(events.get(0)); // confirm click must stay as origin
        for (int i = 0; i < body.size(); i++) {
            if (feats[i] != null && dot(feats[bestIndex], feats[i]) >= 0.48) out.add(body.get(i));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    private static double[] extractFeature(double[] signal, int sampleRate, int center) {
        int pre = sampleRate * 10 / 1000;
        int len = sampleRate * 90 / 1000;
        int start = center - pre;
        if (start < 0 || start + len > signal.length) return null;
        double[] out = new double[24];
        for (int k = 0; k < out.length; k++) {
            int s = start + k * len / out.length;
            int e = start + (k + 1) * len / out.length;
            double sum = 0.0;
            for (int i = s; i < e; i++) sum += Math.abs(signal[i]);
            out[k] = sum / Math.max(1, e - s);
        }
        normalize(out);
        return out;
    }

    private static ArrayList<Peak> keepStrongestChronological(ArrayList<Peak> raw, int maxEvents) {
        ArrayList<Peak> byScore = new ArrayList<>(raw);
        Collections.sort(byScore, (a, b) -> Double.compare(b.score, a.score));
        ArrayList<Peak> keep = new ArrayList<>();
        for (Peak p : byScore) {
            keep.add(p);
            if (keep.size() >= maxEvents) break;
        }
        Collections.sort(keep, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return keep;
    }

    private static ArrayList<Peak> selectSeparated(ArrayList<Peak> raw, int minGapMs) {
        Collections.sort(raw, (a, b) -> Double.compare(b.score, a.score));
        ArrayList<Peak> selected = new ArrayList<>();
        for (Peak p : raw) {
            boolean close = false;
            for (Peak q : selected) {
                if (Math.abs(q.timeMs - p.timeMs) < minGapMs) {
                    close = true;
                    break;
                }
            }
            if (!close) selected.add(p);
            if (selected.size() >= MAX_EVENTS) break;
        }
        Collections.sort(selected, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return selected;
    }

    private static void normalize(double[] values) {
        double mean = 0.0;
        for (double v : values) mean += v;
        mean /= Math.max(1, values.length);
        double norm = 0.0;
        for (int i = 0; i < values.length; i++) {
            values[i] -= mean;
            norm += values[i] * values[i];
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-9) return;
        for (int i = 0; i < values.length; i++) values[i] /= norm;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double median(double[] v) {
        double[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        int m = c.length / 2;
        return c.length % 2 == 0 ? (c[m - 1] + c[m]) / 2.0 : c[m];
    }

    private static double medianAbsDeviation(double[] v, double med) {
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) d[i] = Math.abs(v[i] - med);
        return median(d);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String preview(ArrayList<Long> v) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(v.size(), 14);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(v.get(i));
        }
        if (v.size() > n) sb.append("...");
        return sb.toString();
    }

    private static final class Peak {
        final long timeMs;
        final int sampleIndex;
        final double score;
        Peak(long t, int s, double sc) { timeMs = t; sampleIndex = s; score = sc; }
    }
}
