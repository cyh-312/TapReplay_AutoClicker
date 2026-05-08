package com.example.tapreplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class AudioCaptureForegroundService extends Service {
    public static final String ACTION_START = "com.example.tapreplay.audio.START";
    public static final String ACTION_STOP = "com.example.tapreplay.audio.STOP";
    public static final String ACTION_DISCARD = "com.example.tapreplay.audio.DISCARD";

    private static final String CHANNEL_ID = "tapreplay_audio_capture";
    private static final int NOTIFICATION_ID = 9201;

    public interface Listener {
        void onState(String text);
        void onResult(AudioJumpAnalyzer.Result result);
    }

    private static volatile Listener listener;
    private static volatile boolean recording = false;

    private InternalAudioRecorder recorder;

    public static void setListener(Listener newListener) {
        listener = newListener;
    }

    public static boolean isRecording() {
        return recording;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, AudioCaptureForegroundService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AudioCaptureForegroundService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static void discard(Context context) {
        Intent intent = new Intent(context, AudioCaptureForegroundService.class);
        intent.setAction(ACTION_DISCARD);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            handleStart();
        } else if (ACTION_STOP.equals(action)) {
            handleStopAndAnalyze();
        } else if (ACTION_DISCARD.equals(action)) {
            handleDiscard();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void handleStart() {
        if (recording) {
            publishState("内部音频已在分析中");
            return;
        }

        Notification notification = buildNotification("TapReplay 正在捕获内部游戏音频");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            publishState("前台服务启动失败：" + e.getClass().getSimpleName());
            stopSelf();
            return;
        }

        recorder = new InternalAudioRecorder(this, this::publishState);
        if (recorder.start()) {
            recording = true;
            publishState("内部音频分析中：打一遍/播放本关，结束后生成推荐点");
        } else {
            String error = recorder.getLastError();
            if (error == null || error.isEmpty()) error = "内部音频启动失败";
            publishState(error);
            recording = false;
            cleanupForeground();
            stopSelf();
        }
    }

    private void handleStopAndAnalyze() {
        if (!recording || recorder == null) {
            publishState("当前没有正在进行的音频分析");
            cleanupForeground();
            stopSelf();
            return;
        }
        recording = false;
        publishState("正在结束内部音频并精确分析...");
        final short[] pcm = recorder.stopAndGetPcm();
        recorder = null;

        new Thread(() -> {
            AudioJumpAnalyzer.Result result = PreciseJumpAnalyzer.analyze(pcm, PreciseJumpAnalyzer.SAMPLE_RATE);
            publishResult(result);
            cleanupForeground();
            stopSelf();
        }, "TapReplayPreciseAudioAnalyze").start();
    }

    private void handleDiscard() {
        recording = false;
        if (recorder != null) {
            recorder.discard();
            recorder = null;
        }
        publishState("已取消内部音频分析");
        cleanupForeground();
        stopSelf();
    }

    private void cleanupForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) { }
    }

    private void publishState(String text) {
        Listener l = listener;
        if (l != null) l.onState(text);
    }

    private void publishResult(AudioJumpAnalyzer.Result result) {
        Listener l = listener;
        if (l != null) l.onResult(result);
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("TapReplay 内部音频分析")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true);
        return builder.build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "TapReplay 内部音频分析",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("用于在前台捕获游戏内部音频并生成点击时间表");
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (recorder != null) recorder.discard();
        recorder = null;
        recording = false;
    }
}
