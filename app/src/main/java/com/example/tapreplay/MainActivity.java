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
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
        title.setText("抖音筛选分享 · Android V0.4");
        title.setTextSize(22);
        box.addView(title);

        TextView desc = new TextView(this);
        desc.setText(
                "\n本地识别视频画面，符合条件时再分享给你指定的人。\n" +
                "画面只在内存里处理，不保存截图、检测记录或分享记录。\n\n" +
                "第一次使用：先填写分享对象，再依次开启无障碍、屏幕捕获和模型。\n");
        desc.setTextSize(15);
        box.addView(desc);

        targetEdit = new EditText(this);
        targetEdit.setHint("分享对象昵称（必须精确填写）");
        targetEdit.setSingleLine(true);
        targetEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        targetEdit.setText(prefs.getString("share_target", ""));
        targetEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveTargetAndHideKeyboard();
                return true;
            }
            return false;
        });
        box.addView(targetEdit, match());

        Button save = new Button(this);
        save.setText("保存分享对象");
        save.setOnClickListener(v -> saveTargetAndHideKeyboard());
        box.addView(save, match());

        nnapiCheck = new CheckBox(this);
        nnapiCheck.setText("NNAPI 硬件加速（试验功能，默认关闭）");
        nnapiCheck.setChecked(prefs.getBoolean("prefer_nnapi", false));
        nnapiCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("prefer_nnapi", isChecked).apply();
            ModelEngine.get(this).reset();
            Toast.makeText(this,
                    isChecked ? "已打开试验加速，下次加载模型时生效" : "已切回 CPU 模式",
                    Toast.LENGTH_SHORT).show();
        });
        box.addView(nnapiCheck, match());

        Button accessibility = new Button(this);
        accessibility.setText("1. 开启无障碍服务");
        accessibility.setOnClickListener(v -> {
            hideKeyboard();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        box.addView(accessibility, match());

        Button capture = new Button(this);
        capture.setText("2. 授权屏幕捕获");
        capture.setOnClickListener(v -> {
            hideKeyboard();
            requestCapture();
        });
        box.addView(capture, match());

        Button load = new Button(this);
        load.setText("3. 加载识别模型");
        load.setOnClickListener(v -> {
            hideKeyboard();
            preloadModels();
        });
        box.addView(load, match());

        Button openDouyin = new Button(this);
        openDouyin.setText("4. 打开抖音");
        openDouyin.setOnClickListener(v -> {
            hideKeyboard();
            targetEdit.clearFocus();
            Intent i = getPackageManager().getLaunchIntentForPackage("com.ss.android.ugc.aweme");
            if (i == null) {
                Toast.makeText(this, "没有找到抖音应用", Toast.LENGTH_LONG).show();
            } else {
                startActivity(i);
            }
        });
        box.addView(openDouyin, match());

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(18), 0, dp(12));
        box.addView(statusView, match());

        TextView note = new TextView(this);
        note.setText(
                "说明：\n" +
                "• 悬浮条左边是开始/停止，右边会显示当前进度和判断结果。\n" +
                "• 只有判断为“符合”的视频才会尝试分享。\n" +
                "• 只认右侧操作栏里的真正分享按钮，不会把“分享此刻想法”当成分享。\n" +
                "• 点击后必须确认分享栏真的打开，失败会重试，不会直接继续乱点。\n" +
                "• 如果键盘意外弹出，会先自动收起，再继续。\n" +
                "• 分享对象必须精确匹配；遇到同名或页面不确定时不会乱点。\n" +
                "• 横向找联系人只会在真正的分享联系人列表里进行。\n" +
                "• 屏幕尺寸按当前手机自动读取，不固定某一种分辨率。\n" +
                "• 运行中点“停止”，在发送前会取消后续操作。\n");
        note.setTextSize(13);
        box.addView(note, match());

        setContentView(scroll);
        requestNotificationPermission();
        preloadModels();
    }

    private void saveTargetAndHideKeyboard() {
        String t = targetEdit.getText().toString().trim();
        prefs.edit().putString("share_target", t).apply();
        targetEdit.clearFocus();
        hideKeyboard();
        if (t.isEmpty()) {
            Toast.makeText(this, "已清空。开始前需要先填写分享对象。", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已保存：" + t, Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            View v = getCurrentFocus();
            if (v == null) v = targetEdit;
            if (imm != null && v != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
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
                Toast.makeText(this, "没有取得屏幕捕获权限", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.setAction(ScreenCaptureService.ACTION_START);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "屏幕捕获已启动，只在内存中使用画面", Toast.LENGTH_SHORT).show();
        }
    }

    private void preloadModels() {
        if (statusView != null) statusView.setText("状态：正在加载识别模型……");
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
        String target = prefs.getString("share_target", "").trim();
        statusView.setText(
                "当前状态：\n" +
                "分享对象：" + (target.isEmpty() ? "未设置" : target) + "\n" +
                "无障碍：" + (acc ? "已开启" : "未开启") + "\n" +
                "屏幕捕获：" + (cap ? "已就绪" : "未就绪") + "\n" +
                "模型：" + (model ? "已加载" : "未加载") + "\n" +
                "运行方式：" + ModelEngine.get(this).getBackendNote());
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
