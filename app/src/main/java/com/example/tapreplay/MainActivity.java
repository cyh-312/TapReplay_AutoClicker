package com.example.tapreplay;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFY = 1002;

    private TextView statusView;
    private EditText targetEdit;
    private CheckBox nnapiCheck;
    private SharedPreferences prefs;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("douyin_filter", MODE_PRIVATE);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        box.setPadding(p, p, p, p);
        scroll.addView(box);

        TextView title = new TextView(this);
        title.setText("抖音自动分析分享 · Android V0.1");
        title.setTextSize(22);
        box.addView(title);

        TextView desc = new TextView(this);
        desc.setText(
                "\n模型：SigLIP2 FP16 + CLIP FP16\n" +
                "运行：MediaProjection 内存采帧 + AccessibilityService\n" +
                "不保存截图、CSV、检测历史或分享历史。\n\n" +
                "首次使用按下面顺序授权，然后打开抖音，使用悬浮按钮“开始”。");
        desc.setTextSize(15);
        box.addView(desc);

        targetEdit = new EditText(this);
        targetEdit.setHint("分享目标");
        targetEdit.setText(prefs.getString("share_target", "老张分享"));
        box.addView(targetEdit, match());

        Button save = new Button(this);
        save.setText("保存分享目标");
        save.setOnClickListener(v -> {
            String t = targetEdit.getText().toString().trim();
            if (t.isEmpty()) t = "老张分享";
            prefs.edit().putString("share_target", t).apply();
            Toast.makeText(this, "已保存：" + t, Toast.LENGTH_SHORT).show();
        });
        box.addView(save, match());

        nnapiCheck = new CheckBox(this);
        nnapiCheck.setText("优先使用 NNAPI（实验；默认关闭，先以准确率为准）");
        nnapiCheck.setChecked(prefs.getBoolean("prefer_nnapi", false));
        nnapiCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("prefer_nnapi", isChecked).apply();
            ModelEngine.get(this).reset();
        });
        box.addView(nnapiCheck, match());

        Button accessibility = new Button(this);
        accessibility.setText("1. 开启无障碍服务");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        box.addView(accessibility, match());

        Button capture = new Button(this);
        capture.setText("2. 授权屏幕捕获");
        capture.setOnClickListener(v -> requestCapture());
        box.addView(capture, match());

        Button load = new Button(this);
        load.setText("3. 预加载模型");
        load.setOnClickListener(v -> preloadModels());
        box.addView(load, match());

        Button openDouyin = new Button(this);
        openDouyin.setText("4. 打开抖音");
        openDouyin.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.ss.android.ugc.aweme");
            if (i == null) {
                Toast.makeText(this, "未找到抖音包 com.ss.android.ugc.aweme", Toast.LENGTH_LONG).show();
            } else {
                startActivity(i);
            }
        });
        box.addView(openDouyin, match());

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(18), 0, dp(18));
        box.addView(statusView, match());

        TextView note = new TextView(this);
        note.setText(
                "说明：\n" +
                "• 悬浮按钮由无障碍服务创建，不需要单独的“悬浮窗权限”。\n" +
                "• 只有 positive 才会尝试分享。\n" +
                "• “老张分享 分享给你”等主视频页提示不会被当成分享面板。\n" +
                "• 横向寻找联系人只允许在真实分享联系人列表内部发生。\n" +
                "• 运行中点“停止”，发送前会取消后续操作。");
        box.addView(note, match());

        setContentView(scroll);
        requestNotificationPermission();
        preloadModels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private void requestCapture() {
        if (projectionManager == null) return;
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "屏幕捕获未授权", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.setAction(ScreenCaptureService.ACTION_START);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "正在启动内存屏幕捕获", Toast.LENGTH_SHORT).show();
        }
    }

    private void preloadModels() {
        if (statusView != null) statusView.setText("状态：正在加载两个 FP16 模型……");
        new Thread(() -> {
            try {
                ModelEngine.get(this).ensureLoaded();
                runOnUiThread(() -> {
                    Toast.makeText(this, "模型已就绪", Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    if (statusView != null) statusView.setText("模型加载失败：\n" + e);
                });
            }
        }, "model-preload").start();
    }

    private void refreshStatus() {
        if (statusView == null) return;
        boolean acc = TapAccessibilityService.getInstance() != null;
        boolean cap = ScreenCaptureService.isReady();
        boolean model = ModelEngine.get(this).isLoaded();
        statusView.setText(
                "当前状态：\n" +
                "无障碍：" + (acc ? "已连接" : "未连接") + "\n" +
                "屏幕捕获：" + (cap ? "已就绪" : "未授权/未就绪") + "\n" +
                "模型：" + (model ? "已加载" : "未加载") + "\n" +
                "推理后端：" + ModelEngine.get(this).getBackendNote());
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }
}
