package com.example.tapreplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.LinearLayout;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TapAccessibilityService extends AccessibilityService {
    private static volatile TapAccessibilityService instance;

    private WindowManager wm;
    private LinearLayout overlay;
    private TextView button;
    private TextView status;
    private WindowManager.LayoutParams params;

    public static TapAccessibilityService getInstance() { return instance; }

    public static void setOverlayStatus(String text) {
        TapAccessibilityService s = instance;
        if (s != null) s.runOnUiThread(() -> {
            if (s.status != null) s.status.setText(text);
        });
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        showOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        AutomationController.get(this).stop();
        if (wm != null && overlay != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) {}
        }
        overlay = null;
        instance = null;
        super.onDestroy();
    }

    private void runOnUiThread(Runnable r) {
        new android.os.Handler(getMainLooper()).post(r);
    }

    private void showOverlay() {
        if (overlay != null) return;
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(8), dp(6), dp(8), dp(6));
        overlay.setBackgroundColor(Color.argb(190, 20, 20, 20));

        button = new TextView(this);
        button.setText("开始");
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        overlay.addView(button, new LinearLayout.LayoutParams(dp(86), dp(44)));

        status = new TextView(this);
        status.setText("就绪");
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        status.setMaxWidth(dp(180));
        overlay.addView(status);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(180);

        final float[] downRaw = new float[2];
        final int[] downXY = new int[2];
        final boolean[] moved = new boolean[1];

        overlay.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    downXY[0] = params.x;
                    downXY[1] = params.y;
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRaw[0];
                    float dy = event.getRawY() - downRaw[1];
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved[0] = true;
                    params.x = downXY[0] + Math.round(dx);
                    params.y = downXY[1] + Math.round(dy);
                    try { wm.updateViewLayout(overlay, params); } catch (Throwable ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) AutomationController.get(this).toggle();
                    return true;
            }
            return false;
        });

        wm.addView(overlay, params);
    }

    public void setOverlayRunning(boolean running) {
        runOnUiThread(() -> {
            if (button != null) button.setText(running ? "停止" : "开始");
        });
    }

    public boolean swipeNextVideo() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Random r = new Random();
        float x1r = 0.44f + r.nextFloat() * 0.12f;
        float x2r = clamp(x1r + (-0.025f + r.nextFloat() * 0.05f), 0.38f, 0.62f);
        float y1r = 0.78f + r.nextFloat() * 0.06f;
        float y2r = 0.20f + r.nextFloat() * 0.08f;
        int duration = 280 + r.nextInt(101);
        return gestureLine(w * x1r, h * y1r, w * x2r, h * y2r, duration);
    }

    public boolean shareToTarget(String target, AtomicBoolean running) throws Exception {
        if (!running.get()) return false;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        if (!isSharePanelOpen(root)) {
            AccessibilityNodeInfo share = findShareButton(root);
            if (share == null) return false;
            clickNode(share);
            share.recycle();
            if (!waitSharePanel(true, 3000, running)) return false;
        }
        if (!running.get()) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            return false;
        }

        for (int page = 0; page < 6 && running.get(); page++) {
            root = getRootInActiveWindow();
            if (root == null || !isSharePanelOpen(root)) return false;

            List<AccessibilityNodeInfo> matches = findTargetContacts(root, target);
            if (matches.size() > 1) {
                recycleNodes(matches);
                return false;
            }
            if (matches.size() == 1) {
                AccessibilityNodeInfo node = matches.get(0);
                SystemClock.sleep(250);
                if (!running.get()) {
                    node.recycle();
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return false;
                }

                root = getRootInActiveWindow();
                List<AccessibilityNodeInfo> verify = root == null
                        ? Collections.emptyList() : findTargetContacts(root, target);
                if (verify.size() != 1) {
                    node.recycle();
                    recycleNodes(verify);
                    return false;
                }
                AccessibilityNodeInfo confirmed = verify.get(0);
                clickNode(confirmed);
                node.recycle();
                confirmed.recycle();
                SystemClock.sleep(700);

                if (!running.get()) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return false;
                }

                PointHit send = detectSendButton();
                if (send == null) return false;
                if (!running.get()) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return false;
                }
                if (!tap(send.x, send.y)) return false;

                setOverlayStatus("已点发送，等待面板关闭…");
                long end = SystemClock.uptimeMillis() + 5500;
                while (SystemClock.uptimeMillis() < end) {
                    if (waitSharePanel(false, 450, null)) {
                        SystemClock.sleep(800);
                        return true;
                    }
                }
                return false;
            }

            AccessibilityNodeInfo recycler = findShareRecycler(root);
            if (recycler == null) return false;
            Rect b = new Rect();
            recycler.getBoundsInScreen(b);
            recycler.recycle();
            int y = b.centerY();
            boolean swiped = gestureLine(
                    Math.max(b.left + 20, b.right - (b.width() * 0.16f)),
                    y,
                    Math.min(b.right - 20, b.left + (b.width() * 0.22f)),
                    y,
                    360);
            if (!swiped) return false;
            SystemClock.sleep(450);
        }
        return false;
    }

    private AccessibilityNodeInfo findShareButton(AccessibilityNodeInfo root) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String text = text(n.getText());
            String desc = text(n.getContentDescription());
            String label = text + " " + desc;
            if (label.contains("分享") &&
                    !normalize(text).contains("分享给你") &&
                    !normalize(desc).contains("分享给你") &&
                    !normalize(text).startsWith("私信") &&
                    !normalize(desc).startsWith("私信")) {
                Rect b = new Rect(); n.getBoundsInScreen(b);
                int score = 0;
                String nt = normalize(text), nd = normalize(desc);
                if ("分享".equals(nt) || "分享".equals(nd)) score += 100;
                if (nd.startsWith("分享") && nd.contains("按钮")) score += 80;
                if (b.centerX() >= w * 0.70) score += 30;
                if (b.centerY() <= h * 0.90) score += 10;
                if (n.isClickable()) score += 5;
                if (score > bestScore) {
                    if (best != null) best.recycle();
                    best = AccessibilityNodeInfo.obtain(n);
                    bestScore = score;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            n.recycle();
        }
        return best;
    }

    private boolean isSharePanelOpen(AccessibilityNodeInfo root) {
        int h = getResources().getDisplayMetrics().heightPixels;
        boolean exactTitle = false, cancel = false;
        Set<String> actionSet = new HashSet<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String[] vals = {normalize(text(n.getText())), normalize(text(n.getContentDescription()))};
            Rect b = new Rect(); n.getBoundsInScreen(b);
            for (String v : vals) {
                if ("分享给".equals(v) && b.centerY() >= h * 0.55) exactTitle = true;
                if ("取消".equals(v) && b.centerY() >= h * 0.55) cancel = true;
                if (b.centerY() >= h * 0.55 &&
                        Arrays.asList("复制链接","保存本地","微信","微信好友","朋友圈","qq","qq空间").contains(v)) {
                    actionSet.add(v);
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            n.recycle();
        }
        AccessibilityNodeInfo recycler = findShareRecycler(root);
        boolean hasRecycler = recycler != null;
        if (recycler != null) recycler.recycle();
        return exactTitle || (cancel && hasRecycler) || actionSet.size() >= 2;
    }

    private AccessibilityNodeInfo findShareRecycler(AccessibilityNodeInfo root) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if ("com.ss.android.ugc.aweme:id/recycler_view".equals(n.getViewIdResourceName())) {
                Rect b = new Rect(); n.getBoundsInScreen(b);
                if (b.top >= h * 0.55 && b.top <= h * 0.92 &&
                        b.width() >= w * 0.75 &&
                        b.height() >= h * 0.05 && b.height() <= h * 0.30) {
                    if (best == null) best = AccessibilityNodeInfo.obtain(n);
                    else {
                        Rect old = new Rect(); best.getBoundsInScreen(old);
                        if (b.top > old.top || (b.top == old.top && b.width() > old.width())) {
                            best.recycle();
                            best = AccessibilityNodeInfo.obtain(n);
                        }
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            n.recycle();
        }
        return best;
    }

    private List<AccessibilityNodeInfo> findTargetContacts(AccessibilityNodeInfo root, String target) {
        int h = getResources().getDisplayMetrics().heightPixels;
        String t = normalize(target);
        ArrayList<AccessibilityNodeInfo> raw = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n.isEnabled() && n.isClickable()) {
                Rect b = new Rect(); n.getBoundsInScreen(b);
                if (b.top >= h * 0.60 && b.top <= h * 0.92) {
                    String tx = normalize(text(n.getText()));
                    String ds = normalize(text(n.getContentDescription()));
                    if (t.equals(tx) || t.equals(ds)) raw.add(AccessibilityNodeInfo.obtain(n));
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            n.recycle();
        }

        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : raw) {
            Rect nb = new Rect(); n.getBoundsInScreen(nb);
            boolean duplicate = false;
            for (AccessibilityNodeInfo e : out) {
                Rect eb = new Rect(); e.getBoundsInScreen(eb);
                if (Math.abs(nb.centerX() - eb.centerX()) <= 100 &&
                        Math.abs(nb.centerY() - eb.centerY()) <= 140) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) n.recycle();
            else out.add(n);
        }
        return out;
    }

    private PointHit detectSendButton() {
        ScreenCaptureService cap = ScreenCaptureService.getInstance();
        if (cap == null) return null;
        Bitmap img = null;
        try {
            img = cap.captureLatest(1200);
            int w = img.getWidth(), h = img.getHeight();
            int y0 = (int)(h * 0.82), y1 = (int)(h * 0.985);
            int x0 = (int)(w * 0.02), x1 = (int)(w * 0.98);
            int step = 3;

            int minX = w, maxX = -1, minY = h, maxY = -1, hits = 0;
            for (int y = y0; y < y1; y += step) {
                for (int x = x0; x < x1; x += step) {
                    int c = img.getPixel(x, y);
                    int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                    if (r >= 185 && g <= 125 && b <= 160 &&
                            r - g >= 65 && r - b >= 35) {
                        hits++;
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                }
            }
            if (hits < 50 || maxX < minX || maxY < minY) return null;
            if (maxX - minX < w * 0.55 || maxY - minY < h * 0.025) return null;
            return new PointHit((minX + maxX) / 2, (minY + maxY) / 2);
        } catch (Throwable e) {
            return null;
        } finally {
            if (img != null && !img.isRecycled()) img.recycle();
        }
    }

    private boolean waitSharePanel(boolean wanted, long timeout, AtomicBoolean running) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            if (running != null && !running.get()) return false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                boolean open = isSharePanelOpen(root);
                if (open == wanted) return true;
            }
            SystemClock.sleep(120);
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect b = new Rect(); n.getBoundsInScreen(b);
        return tap(b.centerX(), b.centerY());
    }

    private boolean tap(float x, float y) {
        return gestureLine(x, y, x, y, 60);
    }

    private boolean gestureLine(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(40, duration));
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean accepted = dispatchGesture(gd, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                ok[0] = true; latch.countDown();
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, null);
        if (!accepted) return false;
        try { latch.await(duration + 1200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }

    private static String text(CharSequence s) { return s == null ? "" : s.toString(); }
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static void recycleNodes(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) if (n != null) n.recycle();
    }
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private static final class PointHit {
        final int x, y;
        PointHit(int x, int y) { this.x = x; this.y = y; }
    }
}
