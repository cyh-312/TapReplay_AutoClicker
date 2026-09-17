package com.example.tapreplay;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight diagnostic logger.
 *
 * Storage layout (normally):
 * /storage/emulated/0/Android/data/com.cyh312.douyinfilter/files/logs/
 *
 * Total retained size is bounded to about 5 MiB:
 * douyin.log + douyin.log.1 ... douyin.log.4, each about 1 MiB.
 */
public final class TraceLogger {
    private TraceLogger() {}

    private static final String TAG = "DouyinFilterTrace";
    private static final String CURRENT_NAME = "douyin.log";
    static final long MAX_FILE_BYTES = 1024L * 1024L;
    static final int MAX_BACKUPS = 4;
    private static final long FLUSH_INTERVAL_MS = 1000L;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static File logDir;
    private static File currentFile;
    private static BufferedWriter writer;
    private static long approxBytes;
    private static long lastFlushMs;
    private static volatile boolean shareTracing;

    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (writer != null) return;
            try {
                File external = context.getExternalFilesDir("logs");
                logDir = external != null ? external : new File(context.getFilesDir(), "logs");
                if (!logDir.exists() && !logDir.mkdirs()) {
                    Log.w(TAG, "Could not create log dir: " + logDir);
                }
                currentFile = new File(logDir, CURRENT_NAME);
                openWriterLocked();
                writeLocked("LOGGER", "init path=" + logDir.getAbsolutePath() +
                        " limit≈5MiB", true);
            } catch (Throwable e) {
                Log.e(TAG, "Logger init failed", e);
                closeLocked();
            }
        }
    }

    public static String getLogDirectory() {
        synchronized (LOCK) {
            return logDir == null ? "" : logDir.getAbsolutePath();
        }
    }

    public static void setShareTracing(boolean enabled) {
        shareTracing = enabled;
        critical("TRACE", enabled ? "share tracing ON" : "share tracing OFF");
    }

    public static boolean isShareTracing() {
        return shareTracing;
    }

    public static void log(String category, String message) {
        write(category, message, false);
    }

    public static void critical(String category, String message) {
        write(category, message, true);
    }

    private static void write(String category, String message, boolean forceFlush) {
        String cat = category == null ? "LOG" : category;
        String msg = message == null ? "" : message;
        Log.i(TAG, "[" + cat + "] " + msg);

        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writeLocked(cat, msg, forceFlush);
            } catch (Throwable e) {
                Log.e(TAG, "File log write failed", e);
                closeLocked();
            }
        }
    }

    private static void writeLocked(String category, String message, boolean forceFlush) throws Exception {
        String line = TS.format(new Date()) + " [" + category + "] " + message + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);

        if (approxBytes > 0 && approxBytes + bytes.length > MAX_FILE_BYTES) {
            rotateLocked();
        }

        writer.write(line);
        approxBytes += bytes.length;

        long now = android.os.SystemClock.uptimeMillis();
        if (forceFlush || now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            writer.flush();
            lastFlushMs = now;
        }
    }

    private static void rotateLocked() throws Exception {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }

        File oldest = new File(logDir, CURRENT_NAME + "." + MAX_BACKUPS);
        if (oldest.exists() && !oldest.delete()) {
            Log.w(TAG, "Could not delete oldest log: " + oldest);
        }

        for (int i = MAX_BACKUPS - 1; i >= 1; i--) {
            File src = new File(logDir, CURRENT_NAME + "." + i);
            File dst = new File(logDir, CURRENT_NAME + "." + (i + 1));
            if (src.exists()) {
                if (dst.exists()) dst.delete();
                if (!src.renameTo(dst)) Log.w(TAG, "Could not rotate " + src + " -> " + dst);
            }
        }

        File first = new File(logDir, CURRENT_NAME + ".1");
        if (currentFile.exists()) {
            if (first.exists()) first.delete();
            if (!currentFile.renameTo(first)) Log.w(TAG, "Could not rotate current log");
        }
        openWriterLocked();
    }

    private static void openWriterLocked() throws Exception {
        if (currentFile == null) currentFile = new File(logDir, CURRENT_NAME);
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(currentFile, true), StandardCharsets.UTF_8), 8192);
        approxBytes = currentFile.length();
        lastFlushMs = android.os.SystemClock.uptimeMillis();
    }

    public static void close() {
        synchronized (LOCK) {
            closeLocked();
        }
    }

    private static void closeLocked() {
        if (writer != null) {
            try { writer.flush(); } catch (Throwable ignored) {}
            try { writer.close(); } catch (Throwable ignored) {}
        }
        writer = null;
    }
}
