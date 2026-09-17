package com.example.tapreplay;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight runtime diagnostics for the internal stability build.
 * No user content, screenshots or model inputs are recorded here.
 */
final class StabilityDiagnostics {
    private static final AtomicBoolean EXIT_INFO_LOGGED = new AtomicBoolean(false);

    private StabilityDiagnostics() {}

    static void logStartupExitInfo(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        if (!EXIT_INFO_LOGGED.compareAndSet(false, true)) return;

        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return;

            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 5);
            if (list == null || list.isEmpty()) {
                TraceLogger.critical("EXIT_INFO", "no historical process exit info");
                return;
            }

            int count = Math.min(3, list.size());
            for (int i = 0; i < count; i++) {
                ApplicationExitInfo info = list.get(i);
                TraceLogger.critical("EXIT_INFO",
                        "idx=" + i +
                        " reason=" + reasonName(info.getReason()) + "(" + info.getReason() + ")" +
                        " status=" + info.getStatus() +
                        " importance=" + info.getImportance() +
                        " pssMiB=" + formatMiB(info.getPss() * 1024L) +
                        " rssMiB=" + formatMiB(info.getRss() * 1024L) +
                        " ts=" + info.getTimestamp() +
                        " desc=" + safe(info.getDescription()));
            }
        } catch (Throwable e) {
            TraceLogger.critical("EXIT_INFO", "read failed=" + shortError(e));
        }
    }

    static void logSnapshot(Context context, int cycle, String stage, boolean includePss) {
        try {
            Runtime rt = Runtime.getRuntime();
            long javaUsed = rt.totalMemory() - rt.freeMemory();
            long javaTotal = rt.totalMemory();
            long javaMax = rt.maxMemory();
            long nativeHeap = Debug.getNativeHeapAllocatedSize();

            StringBuilder sb = new StringBuilder(192)
                    .append("cycle=").append(cycle)
                    .append(" stage=").append(stage)
                    .append(" javaUsedMiB=").append(formatMiB(javaUsed))
                    .append(" javaTotalMiB=").append(formatMiB(javaTotal))
                    .append(" javaMaxMiB=").append(formatMiB(javaMax))
                    .append(" nativeMiB=").append(formatMiB(nativeHeap));

            if (includePss) {
                Debug.MemoryInfo mi = new Debug.MemoryInfo();
                Debug.getMemoryInfo(mi);
                sb.append(" pssMiB=").append(formatMiB(mi.getTotalPss() * 1024L));
            }

            if (Build.VERSION.SDK_INT >= 29) {
                PowerManager pm = context.getSystemService(PowerManager.class);
                if (pm != null) sb.append(" thermal=").append(pm.getCurrentThermalStatus());
            }

            TraceLogger.critical("MEM", sb.toString());
        } catch (Throwable e) {
            TraceLogger.log("MEM", "snapshot failed=" + shortError(e));
        }
    }

    private static String reasonName(int reason) {
        if (Build.VERSION.SDK_INT < 30) return "n/a";
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1048576.0);
    }

    private static String safe(String s) {
        if (s == null || s.trim().isEmpty()) return "-";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static String shortError(Throwable e) {
        if (e == null) return "unknown";
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
