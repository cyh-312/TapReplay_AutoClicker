package com.example.tapreplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分享流程单独放在这里，避免模型判断和抖音界面操作互相影响。
 * 设计原则：每一步都重新读取当前界面；点击后必须验证结果；不相信“点击返回 true”就等于页面真的打开。
 */
public final class ShareFlow {
    private ShareFlow() {}

    public static boolean shareToTarget(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running) throws Exception {

        if (service == null || running == null || !running.get()) return false;
        target = target == null ? "" : target.trim();
        if (target.isEmpty()) return false;

        dismissKeyboardIfVisible(service, running);

        TapAccessibilityService.setOverlayStatus("符合｜正在打开分享…");
        if (!openSharePanel(service, running)) {
            TapAccessibilityService.setOverlayStatus("分享栏没打开｜已停下");
            return false;
        }

        dismissKeyboardIfVisible(service, running);
        if (!running.get()) return false;

        for (int page = 0; page < 7 && running.get(); page++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                SystemClock.sleep(180);
                continue;
            }

            boolean panelOpen = isSharePanelOpen(service, root);
            if (!panelOpen) {
                root.recycle();
                TapAccessibilityService.setOverlayStatus("分享栏突然关了｜已停下");
                return false;
            }

            List<AccessibilityNodeInfo> targets = findTargetContacts(service, root, target);
            root.recycle();

            if (targets.size() > 1) {
                recycleNodes(targets);
                TapAccessibilityService.setOverlayStatus("有多个同名联系人｜已停下");
                return false;
            }

            if (targets.size() == 1) {
                AccessibilityNodeInfo node = targets.get(0);
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                node.recycle();

                TapAccessibilityService.setOverlayStatus("找到分享对象｜正在点选…");
                if (!tap(service, b.centerX(), b.centerY(), 70)) return false;
                SystemClock.sleep(520);
                dismissKeyboardIfVisible(service, running);
                if (!running.get()) return false;

                PointHit send = null;
                for (int i = 1; i <= 5 && running.get(); i++) {
                    send = detectSendButton();
                    if (send != null) break;
                    TapAccessibilityService.setOverlayStatus("正在等发送按钮… " + i + "/5");
                    dismissKeyboardIfVisible(service, running);
                    SystemClock.sleep(260);
                }

                if (send == null) {
                    TapAccessibilityService.setOverlayStatus("没找到发送按钮｜已停下");
                    return false;
                }

                TapAccessibilityService.setOverlayStatus("正在发送…");
                if (!tap(service, send.x, send.y, 70)) return false;

                long end = SystemClock.uptimeMillis() + 6000;
                while (SystemClock.uptimeMillis() < end && running.get()) {
                    if (!isSharePanelOpenNow(service)) {
                        SystemClock.sleep(650);
                        return true;
                    }
                    SystemClock.sleep(140);
                }

                TapAccessibilityService.setOverlayStatus("发送后页面没收起｜已停下");
                return false;
            }

            AccessibilityNodeInfo recycler = findShareRecycler(service);
            if (recycler == null) {
                TapAccessibilityService.setOverlayStatus("没找到联系人栏｜已停下");
                return false;
            }

            Rect rb = new Rect();
            recycler.getBoundsInScreen(rb);
            recycler.recycle();

            TapAccessibilityService.setOverlayStatus("没看到目标｜联系人栏继续找 " + (page + 1) + "/7");
            float y = rb.centerY();
            float x1 = Math.max(rb.left + 24, rb.right - rb.width() * 0.14f);
            float x2 = Math.min(rb.right - 24, rb.left + rb.width() * 0.20f);
            if (!gesture(service, x1, y, x2, y, 380)) return false;
            SystemClock.sleep(520);
            dismissKeyboardIfVisible(service, running);
        }

        TapAccessibilityService.setOverlayStatus("联系人栏里没找到目标｜已停下");
        return false;
    }

    private static boolean openSharePanel(
            TapAccessibilityService service,
            AtomicBoolean running) throws Exception {

        if (isSharePanelOpenNow(service)) return true;

        for (int round = 1; round <= 4 && running.get(); round++) {
            dismissKeyboardIfVisible(service, running);

            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                SystemClock.sleep(180);
                continue;
            }

            List<NodeScore> candidates = findShareCandidates(service, root);
            root.recycle();

            if (candidates.isEmpty()) {
                TapAccessibilityService.setOverlayStatus("没看到分享按钮｜再找一次 " + round + "/4");
                SystemClock.sleep(260);
                continue;
            }

            // 最多试两个最可信候选。每次点击之后都验证分享栏是否真的出现。
            int n = Math.min(2, candidates.size());
            for (int i = 0; i < n && running.get(); i++) {
                NodeScore c = candidates.get(i);
                Rect b = c.bounds;
                TapAccessibilityService.setOverlayStatus(
                        "正在点分享按钮｜" + round + "/4");

                // 关键修正：直接按屏幕坐标点候选中心，不再把 ACTION_CLICK=true 当成“已经打开”。
                tap(service, b.centerX(), b.centerY(), 70);
                if (waitSharePanel(service, true, 1450, running)) {
                    recycleCandidates(candidates);
                    return true;
                }

                dismissKeyboardIfVisible(service, running);
                SystemClock.sleep(160);
            }

            recycleCandidates(candidates);
            SystemClock.sleep(220);
        }
        return false;
    }

    private static List<NodeScore> findShareCandidates(
            TapAccessibilityService service,
            AccessibilityNodeInfo root) {

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        ArrayList<NodeScore> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            String cls = text(n.getClassName()).toLowerCase(Locale.ROOT);
            Rect b = new Rect();
            n.getBoundsInScreen(b);

            boolean shareLabel =
                    "分享".equals(tx) ||
                    "分享".equals(ds) ||
                    (ds.startsWith("分享") && ds.contains("按钮")) ||
                    (tx.startsWith("分享") && tx.length() <= 8);

            boolean forbidden =
                    tx.contains("分享给你") || ds.contains("分享给你") ||
                    tx.startsWith("私信") || ds.startsWith("私信") ||
                    cls.contains("edittext") ||
                    !n.isVisibleToUser() || !n.isEnabled() ||
                    b.width() <= 0 || b.height() <= 0;

            boolean right = b.centerX() >= w * 0.60f &&
                    b.centerY() >= h * 0.10f &&
                    b.centerY() <= h * 0.93f;

            if (shareLabel && !forbidden && right) {
                addCandidate(out, n, b, scoreShareNode(n, tx, ds, b, w));

                // 抖音有时把“分享xx，按钮”的文字节点包在真正可点击容器里。
                // 把上面几层父节点也作为候选，避免点到文字节点却没有弹出分享栏。
                AccessibilityNodeInfo p = n.getParent();
                for (int depth = 0; p != null && depth < 3; depth++) {
                    Rect pb = new Rect();
                    p.getBoundsInScreen(pb);
                    if (p.isVisibleToUser() && p.isEnabled() &&
                            pb.width() > 0 && pb.height() > 0 &&
                            pb.centerX() >= w * 0.60f &&
                            pb.centerY() >= h * 0.10f && pb.centerY() <= h * 0.93f &&
                            pb.width() <= w * 0.45f && pb.height() <= h * 0.18f) {
                        int s = scoreShareNode(p, tx, ds, pb, w) +
                                (p.isClickable() ? 80 : 20) - depth * 8;
                        addCandidate(out, p, pb, s);
                    }
                    AccessibilityNodeInfo next = p.getParent();
                    p.recycle();
                    p = next;
                }
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            n.recycle();
        }

        Collections.sort(out, Comparator.comparingInt((NodeScore x) -> x.score).reversed());
        return out;
    }

    private static int scoreShareNode(
            AccessibilityNodeInfo n, String tx, String ds, Rect b, int w) {
        int s = 0;
        if (ds.startsWith("分享") && ds.contains("按钮")) s += 220;
        if ("分享".equals(tx) || "分享".equals(ds)) s += 130;
        if (b.centerX() >= w * 0.78f) s += 55;
        if (n.isClickable()) s += 35;
        if (b.width() >= 36 && b.height() >= 36) s += 20;
        return s;
    }

    private static void addCandidate(
            List<NodeScore> out,
            AccessibilityNodeInfo n,
            Rect b,
            int score) {
        for (NodeScore e : out) {
            if (Math.abs(e.bounds.centerX() - b.centerX()) <= 8 &&
                    Math.abs(e.bounds.centerY() - b.centerY()) <= 8 &&
                    Math.abs(e.bounds.width() - b.width()) <= 12 &&
                    Math.abs(e.bounds.height() - b.height()) <= 12) {
                if (score > e.score) e.score = score;
                return;
            }
        }
        out.add(new NodeScore(AccessibilityNodeInfo.obtain(n), new Rect(b), score));
    }

    private static boolean isSharePanelOpenNow(TapAccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            return isSharePanelOpen(service, root);
        } finally {
            root.recycle();
        }
    }

    private static boolean isSharePanelOpen(
            TapAccessibilityService service,
            AccessibilityNodeInfo root) {
        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        boolean exactTitle = false;
        boolean cancel = false;
        boolean lowerWideScrollable = false;
        Set<String> actions = new HashSet<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String[] vals = {
                    normalize(text(n.getText())),
                    normalize(text(n.getContentDescription()))
            };

            for (String v : vals) {
                if ("分享给".equals(v) && b.centerY() >= h * 0.50f) exactTitle = true;
                if ("取消".equals(v) && b.centerY() >= h * 0.55f) cancel = true;
                if (b.centerY() >= h * 0.55f && Arrays.asList(
                        "复制链接", "保存本地", "微信", "微信好友", "朋友圈",
                        "qq", "qq空间", "举报", "不感兴趣").contains(v)) {
                    actions.add(v);
                }
            }

            if (n.isScrollable() && b.centerY() >= h * 0.55f &&
                    b.width() >= w * 0.65f && b.height() <= h * 0.35f) {
                lowerWideScrollable = true;
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            n.recycle();
        }

        return exactTitle ||
                (cancel && lowerWideScrollable) ||
                actions.size() >= 2;
    }

    private static AccessibilityNodeInfo findShareRecycler(TapAccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;
        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        root.recycle();

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String id = text(n.getViewIdResourceName());

            boolean idMatch = id.endsWith(":id/recycler_view") || id.contains("recycler");
            boolean plausible = b.top >= h * 0.50f && b.top <= h * 0.93f &&
                    b.width() >= w * 0.65f &&
                    b.height() >= h * 0.04f && b.height() <= h * 0.34f;

            if (plausible && (idMatch || n.isScrollable())) {
                int score = (idMatch ? 100 : 0) + (n.isScrollable() ? 40 : 0) +
                        (b.width() >= w * 0.85f ? 20 : 0);
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

    private static List<AccessibilityNodeInfo> findTargetContacts(
            TapAccessibilityService service,
            AccessibilityNodeInfo root,
            String target) {

        int h = service.getResources().getDisplayMetrics().heightPixels;
        String wanted = normalize(target);
        ArrayList<AccessibilityNodeInfo> found = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            String cls = text(n.getClassName()).toLowerCase(Locale.ROOT);

            boolean exact = wanted.equals(tx) || wanted.equals(ds);
            boolean plausibleY = b.centerY() >= h * 0.52f && b.centerY() <= h * 0.94f;

            if (exact && plausibleY && !cls.contains("edittext") && n.isVisibleToUser()) {
                AccessibilityNodeInfo clickable = nearestClickable(n, h);
                if (clickable != null) addUniqueNode(found, clickable);
                else addUniqueNode(found, AccessibilityNodeInfo.obtain(n));
                if (clickable != null) clickable.recycle();
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            n.recycle();
        }
        return found;
    }

    private static AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo start, int h) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(start);
        for (int depth = 0; cur != null && depth < 5; depth++) {
            Rect b = new Rect();
            cur.getBoundsInScreen(b);
            if (cur.isEnabled() && cur.isVisibleToUser() && cur.isClickable() &&
                    b.centerY() >= h * 0.50f && b.centerY() <= h * 0.95f &&
                    b.width() > 0 && b.height() > 0) {
                AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(cur);
                cur.recycle();
                return result;
            }
            AccessibilityNodeInfo next = cur.getParent();
            cur.recycle();
            cur = next;
        }
        return null;
    }

    private static void addUniqueNode(List<AccessibilityNodeInfo> out, AccessibilityNodeInfo n) {
        Rect nb = new Rect();
        n.getBoundsInScreen(nb);
        for (AccessibilityNodeInfo e : out) {
            Rect eb = new Rect();
            e.getBoundsInScreen(eb);
            if (Math.abs(nb.centerX() - eb.centerX()) <= 70 &&
                    Math.abs(nb.centerY() - eb.centerY()) <= 100) {
                n.recycle();
                return;
            }
        }
        out.add(AccessibilityNodeInfo.obtain(n));
        n.recycle();
    }

    private static boolean isKeyboardVisible(TapAccessibilityService service) {
        if (Build.VERSION.SDK_INT < 21) return false;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo w : windows) {
                if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean dismissKeyboardIfVisible(
            TapAccessibilityService service,
            AtomicBoolean running) {
        for (int i = 0; i < 3; i++) {
            if (!isKeyboardVisible(service)) return true;
            if (running != null && !running.get()) return false;
            TapAccessibilityService.setOverlayStatus("键盘弹出来了｜正在收起…");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(280);
        }
        return !isKeyboardVisible(service);
    }

    private static PointHit detectSendButton() {
        ScreenCaptureService cap = ScreenCaptureService.getInstance();
        if (cap == null) return null;
        Bitmap img = null;
        try {
            img = cap.captureLatest(1200);
            int w = img.getWidth(), h = img.getHeight();
            int y0 = (int) (h * 0.80f), y1 = (int) (h * 0.99f);
            int x0 = (int) (w * 0.02f), x1 = (int) (w * 0.98f);
            int step = 3;

            int minX = w, maxX = -1, minY = h, maxY = -1, hits = 0;
            for (int y = y0; y < y1; y += step) {
                for (int x = x0; x < x1; x += step) {
                    int c = img.getPixel(x, y);
                    int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                    if (r >= 180 && g <= 135 && b <= 170 &&
                            r - g >= 55 && r - b >= 28) {
                        hits++;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }

            if (hits < 45 || maxX < minX || maxY < minY) return null;
            if (maxX - minX < w * 0.50f || maxY - minY < h * 0.022f) return null;
            return new PointHit((minX + maxX) / 2, (minY + maxY) / 2);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (img != null && !img.isRecycled()) img.recycle();
        }
    }

    private static boolean waitSharePanel(
            TapAccessibilityService service,
            boolean wanted,
            long timeoutMs,
            AtomicBoolean running) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            if (running != null && !running.get()) return false;
            if (isSharePanelOpenNow(service) == wanted) return true;
            SystemClock.sleep(110);
        }
        return false;
    }

    private static boolean tap(TapAccessibilityService service, float x, float y, long duration) {
        return gesture(service, x, y, x, y, duration);
    }

    private static boolean gesture(
            TapAccessibilityService service,
            float x1, float y1, float x2, float y2,
            long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(45, duration));
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean accepted = service.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, null);
        if (!accepted) return false;
        try {
            latch.await(duration + 1200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok[0];
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private static void recycleNodes(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null) try { n.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static void recycleCandidates(List<NodeScore> nodes) {
        for (NodeScore n : nodes) {
            if (n.node != null) try { n.node.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static final class NodeScore {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        int score;
        NodeScore(AccessibilityNodeInfo node, Rect bounds, int score) {
            this.node = node;
            this.bounds = bounds;
            this.score = score;
        }
    }

    private static final class PointHit {
        final int x;
        final int y;
        PointHit(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
