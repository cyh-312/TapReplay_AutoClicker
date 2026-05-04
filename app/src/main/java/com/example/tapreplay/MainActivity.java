package com.example.tapreplay;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
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
        info.setText("1. 打开悬浮窗权限\n2. 打开无障碍服务：TapReplay 自动点击服务\n3. 回到游戏，点悬浮按钮开始执行预设点击序列");
        info.setTextSize(16);
        info.setTextColor(Color.DKGRAY);
        info.setPadding(0, 30, 0, 30);

        Button overlayBtn = new Button(this);
        overlayBtn.setText("打开悬浮窗权限");
        overlayBtn.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });

        Button accBtn = new Button(this);
        accBtn.setText("打开无障碍设置");
        accBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        root.addView(title);
        root.addView(info);
        root.addView(overlayBtn);
        root.addView(accBtn);
        setContentView(root);
    }
}
