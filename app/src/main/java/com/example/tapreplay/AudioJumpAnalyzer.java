package com.example.tapreplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Lightweight fallback audio event analyzer.
 *
 * The main path now uses PreciseJumpAnalyzer. This class remains as a fallback and
 * also owns the shared Result data structure used by both analyzers.
 */
public final class AudioJumpAnalyzer {
    public static final int SAMPLE_RATE = 44100;

    private static final int FRAME_MS = 20;
    private static final int HOP_MS = 5;
    private static final int MIN_EVENT_GAP_MS = 220;
    private static final int START_ANCHOR_MIN_GAP_MS = 250;
    private static final int START_ANCHOR_MAX_GAP_MS = 900;
    private static final long START_SOUND_DELAY_MS = 64L;
    private static final double TEMPLATE_SIMILARITY = 0.82;
    private static final double KEEP_SIMILARITY = 0.76;
    private static final int MAX_EVENTS = 48;

    private AudioJumpAnalyzer() { }

    public static Result analyze(short[] pcm, int sampleRate) {
        Result result = new Result();
        if (pcm == null || pcm.length < sampleRate / 2) {
            result.debugText = "录音太短，至少录 1 秒以上";
            return result;
        }

        ArrayList<Candidate> candidates = findCandidates(pcm, sampleRate);
        result.candidateCount = candidates.size();
        if (candidates.isEmpty()) {
            result.debugText = "没有检测到明显点击/跳跃音效；可能游戏不允许内部音频捕获，或音量过低";
            return result;
        }

        for (Candidate candidate : candidates) candidate.feature = buildFeature(pcm, sampleRate, candidate.sampleIndex);

        ArrayList<Candidate> matched = clusterRepeatedSound(candidates);
        if (matched.isEmpty()) matched.addAll(candidates);
        Collections.sort(matched, Comparator.comparingLong(c -> c.timeMs));

        result.eventTimesMs.clear();
        for (Candidate c : matched) result.eventTimesMs.add(c.timeMs);

        buildRecommendedPlan(candidates, matched, result);
        double durationSec = pcm.length / (double) sampleRate;
        result.debugText = String.format(Locale.US,
                "录音%.1fs 候选%d 声纹%d 推荐%d %s",
                durationSec,
                result.candidateCount,
                matched.size(),
                result.recommendedTimesMs.size(),
                result.anchorFound ? "锚点OK" : "无锚点");
        return result;
    }

    private static ArrayList<Candidate> findCandidates(short[] pcm, int sampleRate) {
        int frame = Math.max(256, sampleRate * FRAME_MS / 1000);
        int hop = Math.max(64, sampleRate * HOP_MS / 1000);
        int frames = 1 + Math.max(0, (pcm.length - frame) / hop);
        double[] score = new double[frames];

        for (int i = 0; i < frames; i++) {
            int start = i * hop;
            double diffEnergy = 0.0;
            double rawEnergy = 0.0;
            short previous = pcm[start];
            for (int j = 1; j < frame && start + j < pcm.length; j++) {
                short current = pcm[start + j];
                double v = current / 32768.0;
                double d = (current - previous) / 32768.0;
                rawEnergy += v * v;
                diffEnergy += d * d;
                previous = current;
            }
            double rms = Math.sqrt(rawEnergy / Math.max(1, frame));
            double transientRms = Math.sqrt(diffEnergy / Math.max(1, frame));
            score[i] = Math.log1p(80.0 * transientRms + 20.0 * rms);
        }

        double median = median(score);
        double mad = medianAbsDeviation(score, median);
        double threshold = median + Math.max(0.16, 3.0 * mad);

        ArrayList<Candidate> localPeaks = new ArrayList<>();
        for (int i = 1; i < frames - 1; i++) {
            if (score[i] >= threshold && score[i] >= score[i - 1] && score[i] > score[i + 1]) {
                long timeMs = Math.round((i * hop + frame / 2.0) * 1000.0 / sampleRate);
                int sampleIndex = Math.min(pcm.length - 1, i * hop + frame / 2);
                localPeaks.add(new Candidate(timeMs, sampleIndex, score[i]));
            }
        }

        Collections.sort(localPeaks, (a, b) -> Double.compare(b.score, a.score));
        ArrayList<Candidate> selected = new ArrayList<>();
        for (Candidate peak : localPeaks) {
            boolean tooClose = false;
            for (Candidate existing : selected) {
                if (Math.abs(existing.timeMs - peak.timeMs) < MIN_EVENT_GAP_MS) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) selected.add(peak);
            if (selected.size() >= MAX_EVENTS) break;
        }
        Collections.sort(selected, Comparator.comparingLong(c -> c.timeMs));
        return selected;
    }

    private static ArrayList<Candidate> clusterRepeatedSound(ArrayList<Candidate> candidates) {
        if (candidates.size() <= 2) return new ArrayList<>(candidates);

        Candidate bestSeed = null;
        int bestCount = 0;
        double bestScoreSum = -1.0;

        for (Candidate seed : candidates) {
            int count = 0;
            double scoreSum = 0.0;
            for (Candidate other : candidates) {
                double similarity = cosine(seed.feature, other.feature);
                if (similarity >= TEMPLATE_SIMILARITY) {
                    count++;
                    scoreSum += other.score;
                }
            }
            if (count > bestCount || (count == bestCount && scoreSum > bestScoreSum)) {
                bestSeed = seed;
                bestCount = count;
                bestScoreSum = scoreSum;
            }
        }

        ArrayList<Candidate> matched = new ArrayList<>();
        if (bestSeed == null) return matched;
        for (Candidate candidate : candidates) {
            if (cosine(bestSeed.feature, candidate.feature) >= KEEP_SIMILARITY) matched.add(candidate);
        }
        Collections.sort(matched, Comparator.comparingLong(c -> c.timeMs));
        return matched;
    }

    private static void buildRecommendedPlan(ArrayList<Candidate> allCandidates,
                                             ArrayList<Candidate> matched,
                                             Result result) {
        result.recommendedTimesMs.clear();
        if (matched.isEmpty()) return;

        long firstMatchedMs = matched.get(0).timeMs;
        Candidate anchor = null;
        for (Candidate candidate : allCandidates) {
            long gap = firstMatchedMs - candidate.timeMs;
            if (gap >= START_ANCHOR_MIN_GAP_MS && gap <= START_ANCHOR_MAX_GAP_MS) {
                if (anchor == null || candidate.timeMs > anchor.timeMs) anchor = candidate;
            }
        }

        long originMs;
        result.recommendedTimesMs.add(0L);
        if (anchor != null) {
            result.anchorFound = true;
            result.anchorSoundMs = anchor.timeMs;
            originMs = Math.max(0L, anchor.timeMs - START_SOUND_DELAY_MS);
            for (Candidate candidate : matched) addIfUseful(result.recommendedTimesMs, candidate.timeMs - originMs);
        } else {
            result.anchorFound = false;
            result.anchorSoundMs = -1L;
            originMs = firstMatchedMs;
            for (int i = 1; i < matched.size(); i++) addIfUseful(result.recommendedTimesMs, matched.get(i).timeMs - originMs);
        }
    }

    private static void addIfUseful(ArrayList<Long> values, long valueMs) {
        if (valueMs < 80L) return;
        if (!values.isEmpty() && Math.abs(valueMs - values.get(values.size() - 1)) < MIN_EVENT_GAP_MS) return;
        values.add(valueMs);
    }

    private static double[] buildFeature(short[] pcm, int sampleRate, int centerSample) {
        double[] feature = new double[20];
        int index = 0;

        double[] freqs = new double[] { 650, 900, 1300, 1800, 2500, 3500, 5000, 7000 };
        for (double freq : freqs) feature[index++] = Math.log1p(goertzelPower(pcm, sampleRate, centerSample, 70, freq));

        for (int slot = 0; slot < 12; slot++) {
            int startMs = -30 + slot * 10;
            int endMs = startMs + 10;
            feature[index++] = Math.log1p(1000.0 * transientEnergy(pcm, sampleRate, centerSample, startMs, endMs));
        }

        normalize(feature);
        return feature;
    }

    private static double transientEnergy(short[] pcm, int sampleRate, int centerSample, int startMs, int endMs) {
        int start = clamp(centerSample + sampleRate * startMs / 1000, 1, pcm.length - 1);
        int end = clamp(centerSample + sampleRate * endMs / 1000, start + 1, pcm.length);
        double sum = 0.0;
        for (int i = start; i < end; i++) {
            double d = (pcm[i] - pcm[i - 1]) / 32768.0;
            sum += d * d;
        }
        return Math.sqrt(sum / Math.max(1, end - start));
    }

    private static double goertzelPower(short[] pcm, int sampleRate, int centerSample, int windowMs, double freq) {
        int half = sampleRate * windowMs / 2000;
        int start = clamp(centerSample - half, 0, pcm.length - 1);
        int end = clamp(centerSample + half, start + 1, pcm.length);
        int n = end - start;
        double omega = 2.0 * Math.PI * freq / sampleRate;
        double coeff = 2.0 * Math.cos(omega);
        double s0;
        double s1 = 0.0;
        double s2 = 0.0;
        for (int i = 0; i < n; i++) {
            double w = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / Math.max(1, n - 1));
            double x = (pcm[start + i] / 32768.0) * w;
            s0 = x + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return Math.max(0.0, power / Math.max(1, n));
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

    private static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    private static double median(double[] values) {
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int mid = copy.length / 2;
        if (copy.length % 2 == 0) return (copy[mid - 1] + copy[mid]) / 2.0;
        return copy[mid];
    }

    private static double medianAbsDeviation(double[] values, double median) {
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) dev[i] = Math.abs(values[i] - median);
        return median(dev);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Candidate {
        final long timeMs;
        final int sampleIndex;
        final double score;
        double[] feature;

        Candidate(long timeMs, int sampleIndex, double score) {
            this.timeMs = timeMs;
            this.sampleIndex = sampleIndex;
            this.score = score;
        }
    }

    public static final class Result {
        public final ArrayList<Long> eventTimesMs = new ArrayList<>();
        public final ArrayList<Long> recommendedTimesMs = new ArrayList<>();
        public int candidateCount = 0;
        public int correlationPeakCount = 0;
        public double templateScore = 0.0;
        public boolean anchorFound = false;
        public long anchorSoundMs = -1L;
        public String debugText = "";
    }
}
