package com.example.tapreplay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_RECORD_AUDIO = 1001;
    private static final int REQ_MEDIA_PROJECTION = 1002;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("TapReplay 自动点击器");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);

        TextView info = new TextView(this);
        info.setText("1. 打开悬浮窗权限\n2. 授权录音权限\n3. 授权内部音频捕获\n4. 打开无障碍服务\n5. 回到游戏，用悬浮窗分析音频或执行点击序列\n\n说明：这里的录音权限用于 Android 内部音频捕获，不走麦克风外放方案。");
        info.setTextSize(16);
        info.setTextColor(Color.DKGRAY);
        info.setPadding(0, 30, 0, 30);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setPadding(0, 0, 0, 20);
        updateStatus();

        Button overlayBtn = new Button(this);
        overlayBtn.setText("打开悬浮窗权限");
        overlayBtn.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                setStatus("悬浮窗权限已开启。", false);
            }
        });

        Button micPermBtn = new Button(this);
        micPermBtn.setText("授权录音权限");
        micPermBtn.setOnClickListener(v -> requestRecordAudioPermissionOnly());

        Button audioBtn = new Button(this);
        audioBtn.setText("授权内部音频捕获");
        audioBtn.setOnClickListener(v -> requestInternalAudioCapture());

        Button accBtn = new Button(this);
        accBtn.setText("打开无障碍设置");
        accBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        root.addView(title);
        root.addView(info);
        root.addView(statusText);
        root.addView(overlayBtn);
        root.addView(micPermBtn);
        root.addView(audioBtn);
        root.addView(accBtn);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private boolean hasRecordAudioPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecordAudioPermissionOnly() {
        if (hasRecordAudioPermission()) {
            setStatus("录音权限已授权。下一步点“授权内部音频捕获”。", false);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQ_RECORD_AUDIO);
        }
    }

    private void requestInternalAudioCapture() {
        if (Build.VERSION.SDK_INT < 29) {
            setStatus("内部音频捕获需要 Android 10+。你的系统版本不支持。", true);
            return;
        }
        if (!hasRecordAudioPermission()) {
            setStatus("还缺录音权限。请先点“授权录音权限”，允许后再点“授权内部音频捕获”。", true);
            requestRecordAudioPermissionOnly();
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            setStatus("系统不支持 MediaProjection。", true);
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setStatus("录音权限已授权。现在点“授权内部音频捕获”，允许系统弹窗。", false);
            } else {
                setStatus("未授予录音权限，无法捕获内部游戏音频。请到系统应用权限里允许 TapReplay 录音。", true);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                AudioCaptureHolder.setProjectionGrant(resultCode, data);
                setStatus("内部音频捕获已授权。现在可以回到游戏使用悬浮窗分析。", false);
            } else {
                AudioCaptureHolder.clear();
                setStatus("内部音频捕获授权已取消。", true);
            }
        }
    }

    private void updateStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean recordAudio = hasRecordAudioPermission();
        boolean projection = AudioCaptureHolder.hasProjectionGrant();
        setStatus("悬浮窗：" + (overlay ? "已开" : "未开")
                + " | 录音权限：" + (recordAudio ? "已授权" : "未授权")
                + " | 内部音频：" + (projection ? "已授权" : "未授权"), false);
    }

    private void setStatus(String text, boolean warning) {
        if (statusText != null) {
            statusText.setText(text);
            statusText.setTextColor(warning ? Color.rgb(180, 40, 40) : Color.DKGRAY);
        }
    }
}
