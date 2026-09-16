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
                stopProjection();
            }
        }, null);

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
        if (!isReady()) throw new IllegalStateException("屏幕捕获尚未就绪");
        long deadline = System.currentTimeMillis() + timeoutMs;

        Image image = imageReader.acquireLatestImage();
        while (image == null && System.currentTimeMillis() < deadline) {
            synchronized (imageLock) {
                imageLock.wait(Math.min(80, Math.max(1, deadline - System.currentTimeMillis())));
            }
            image = imageReader.acquireLatestImage();
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
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {}
            projection = null;
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
}
