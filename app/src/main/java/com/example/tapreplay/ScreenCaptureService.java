package com.example.tapreplay;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    public static final String ACTION_START = "com.example.tapreplay.START_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "capture";
    private static volatile ScreenCaptureService instance;

    private final Object imageLock = new Object();
    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private int width;
    private int height;
    private int density;
    private int recoverCount;

    public static boolean isReady() {
        ScreenCaptureService s = instance;
        return s != null && s.projection != null && s.imageReader != null;
    }

    public static ScreenCaptureService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(100, buildNotification("正在提供内存屏幕采集"));
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                //noinspection deprecation
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultCode == Activity.RESULT_OK && resultData != null) {
                startProjection(resultCode, resultData);
            }
        }
        return START_STICKY;
    }

    private synchronized void startProjection(int resultCode, Intent data) {
        stopProjection();
        MediaProjectionManager m =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (m == null) return;

        updateDisplaySize();

        projection = m.getMediaProjection(resultCode, data);
        if (projection == null) return;

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                synchronized (ScreenCaptureService.this) {
                    TraceLogger.critical("CAPTURE", "MediaProjection onStop from system");
                    releaseCapturePipelineLocked();
                    projection = null;
                }
            }
        }, null);

        try {
            createCapturePipelineLocked();
            TraceLogger.critical("CAPTURE",
                    "projection started " + width + "x" + height + " density=" + density);
        } catch (Throwable e) {
            TraceLogger.critical("CAPTURE", "projection pipeline create failed=" + shortError(e));
            stopProjection();
        }
    }

    private void createCapturePipelineLocked() {
        if (projection == null) throw new IllegalStateException("MediaProjection unavailable");

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(reader -> {
            synchronized (imageLock) {
                imageLock.notifyAll();
            }
        }, null);

        virtualDisplay = projection.createVirtualDisplay(
                "DouyinFilterCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, null);

        if (virtualDisplay == null) {
            imageReader.close();
            imageReader = null;
            throw new IllegalStateException("VirtualDisplay create returned null");
        }
    }

    private void releaseCapturePipelineLocked() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
        synchronized (imageLock) {
            imageLock.notifyAll();
        }
    }

    /**
     * Rebuild only ImageReader + VirtualDisplay while keeping the existing MediaProjection token.
     * This is used after several consecutive capture timeouts and does not require a new consent
     * dialog as long as the system has not stopped the projection itself.
     */
    public synchronized boolean recoverCapturePipeline() {
        if (projection == null) {
            TraceLogger.critical("CAPTURE_RECOVER", "skip: projection already stopped");
            return false;
        }

        try {
            releaseCapturePipelineLocked();
            createCapturePipelineLocked();
            recoverCount++;
            TraceLogger.critical("CAPTURE_RECOVER",
                    "success count=" + recoverCount + " size=" + width + "x" + height);
            return true;
        } catch (Throwable e) {
            TraceLogger.critical("CAPTURE_RECOVER", "failed=" + shortError(e));
            releaseCapturePipelineLocked();
            return false;
        }
    }

    private void updateDisplaySize() {
        density = getResources().getDisplayMetrics().densityDpi;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            width = b.width();
            height = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null) {
                //noinspection deprecation
                wm.getDefaultDisplay().getRealMetrics(dm);
                width = dm.widthPixels;
                height = dm.heightPixels;
                density = dm.densityDpi;
            }
        }
        if (width <= 0 || height <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            width = dm.widthPixels;
            height = dm.heightPixels;
            density = dm.densityDpi;
        }
    }

    public Bitmap captureLatest(long timeoutMs) throws Exception {
        ImageReader reader;
        synchronized (this) {
            if (projection == null || imageReader == null) {
                throw new IllegalStateException("屏幕捕获尚未就绪");
            }
            reader = imageReader;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException e) {
            throw new RuntimeException("ImageReader不可用", e);
        }

        while (image == null && System.currentTimeMillis() < deadline) {
            synchronized (imageLock) {
                imageLock.wait(Math.min(80, Math.max(1, deadline - System.currentTimeMillis())));
            }
            try {
                image = reader.acquireLatestImage();
            } catch (IllegalStateException e) {
                throw new RuntimeException("ImageReader已关闭", e);
            }
        }
        if (image == null) throw new RuntimeException("等待屏幕帧超时");

        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            int paddedWidth = width + rowPadding / pixelStride;

            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (cropped != padded) padded.recycle();
            return cropped;
        } finally {
            image.close();
        }
    }

    private synchronized void stopProjection() {
        MediaProjection oldProjection = projection;
        projection = null;
        releaseCapturePipelineLocked();
        if (oldProjection != null) {
            try {
                oldProjection.stop();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        stopProjection();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "屏幕采集", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("抖音筛选分享")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private String shortError(Throwable e) {
        if (e == null) return "unknown";
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
