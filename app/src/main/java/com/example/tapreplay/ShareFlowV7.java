package com.example.tapreplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V0.7.3 parameter-driven fast share flow with a small state-synchronization gate.
 *
 * Normal path:
 *   strong right-rail share parameter -> tap once
 *   -> SharePanelDialog WINDOW_STATE_CHANGED
 *   -> wait only until the panel reaches a minimum stable age
 *   -> exact visible target -> tap once
 *   -> require real Douyin VIEW_CLICKED or selected-state window transition
 *   -> active red Send button -> tap once
 *   -> MainActivity WINDOW_STATE_CHANGED
 *   -> short animation settle -> success.
 *
 * Safety rules retained:
 * - exact target name only;
 * - no horizontal contact scrolling;
 * - target is tapped at most once;
 * - Send is pixel-confirmed as the active large Douyin red/pink button;
 * - Send is tapped once only;
 * - keyboard appearance aborts instead of guessing.
 */
public final class ShareFlowV7 {
    private ShareFlowV7() {}

    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String SHARE_DIALOG_MARK = "sharepanel";
    private static final String MAIN_ACTIVITY_MARK = "main.MainActivity";
    private static final int TARGET_SCANS = 2;
    private static final long PANEL_MIN_SETTLE_MS = 250L;
    private static final long TARGET_ACK_TIMEOUT_MS = 500L;

    private static final AtomicLong WINDOW_STATE_SEQ = new AtomicLong(0L);
    private static volatile long lastWindowStateUptimeMs = 0L;
    private static volatile int lastWindowId = -1;
    private static volatile String lastWindowPackage = "";
    private static volatile String lastWindowClass = "";
    private static volatile long lastShareDialogUptimeMs = 0L;
    private static volatile int lastShareDialogWindowId = -1;

    private static final AtomicLong VIEW_CLICK_SEQ = new AtomicLong(0L);
    private static volatile long lastViewClickUptimeMs = 0L;
    private static volatile int lastViewClickWindowId = -1;
    private static volatile String lastViewClickPackage = "";
    private static volatile String lastViewClickClass = "";

    private static volatile boolean cachedShareValid = false;
    private static volatile float cachedShareX = 0f;
    private static volatile float cachedShareY = 0f;

    public static void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            long seq = WINDOW_STATE_SEQ.incrementAndGet();
            lastWindowStateUptimeMs = SystemClock.uptimeMillis();
            lastWindowId = event.getWindowId();
            lastWindowPackage = text(event.getPackageName());
            lastWindowClass = text(event.getClassName());
            if (DOUYIN_PACKAGE.equals(lastWindowPackage) &&
                    normalize(lastWindowClass).contains(SHARE_DIALOG_MARK)) {
                lastShareDialogUptimeMs = lastWindowStateUptimeMs;
                lastShareDialogWindowId = lastWindowId;
            }
            if (TraceLogger.isShareTracing()) {
                TraceLogger.log("STATE",
                        "seq=" + seq + " windowId=" + lastWindowId +
                        " pkg=" + lastWindowPackage + " class=" + lastWindowClass);
            }
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            long seq = VIEW_CLICK_SEQ.incrementAndGet();
            lastViewClickUptimeMs = SystemClock.uptimeMillis();
            lastViewClickWindowId = event.getWindowId();
            lastViewClickPackage = text(event.getPackageName());
            lastViewClickClass = text(event.getClassName());
            if (TraceLogger.isShareTracing()) {
                TraceLogger.log("CLICK_STATE",
                        "seq=" + seq + " windowId=" + lastViewClickWindowId +
                        " pkg=" + lastViewClickPackage + " class=" + lastViewClickClass);
            }
        }
    }

    public static boolean shareToTarget(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running) throws Exception {

        if (service == null || running == null || !running.get()) return false;
        target = target == null ? "" : target.trim();
        if (target.isEmpty()) return false;

        TraceLogger.init(service);
        TraceLogger.setShareTracing(true);
        final long started = SystemClock.uptimeMillis();
        TraceLogger.critical("SHARE", "BEGIN v0.7.3 target=" + compactName(target));

        try {
            return shareInternal(service, target, running, started);
        } finally {
            TraceLogger.critical("SHARE", "END elapsedMs=" + (SystemClock.uptimeMillis() - started));
            TraceLogger.setShareTracing(false);
        }
    }

    private static boolean shareInternal(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running,
            long started) throws Exception {

        if (!dismissKeyboardIfVisible(service, running)) {
            fail(started, "失败0｜键盘没收起", "没有开始分享");
            return false;
        }

        log(started, "①找分享｜参数定位", cachedShareValid ? "优先复用已确认坐标" : "查询右侧分享参数");
        if (!openSharePanelFast(service, running, started)) {
            fail(started, "失败①｜分享栏没打开", "没有继续乱点");
            return false;
        }

        PanelRead panel = waitPanelSmall(service, running, 650);
        if (panel == null) {
            fail(started, "失败②｜分享栏读不到", "已收到分享弹层事件但联系人区域未就绪");
            return false;
        }
        TraceLogger.critical("PANEL", "ready windowId=" + panel.root.getWindowId() +
                " zone=" + panel.info.contactZone);
        log(started, "②分享栏｜弹层已就绪", "SharePanelDialog 已确认");
        panel.recycle();

        if (!waitForPanelSettle(running, PANEL_MIN_SETTLE_MS)) return false;

        ContactHit hit = null;
        for (int i = 1; i <= TARGET_SCANS && running.get(); i++) {
            long t0 = SystemClock.uptimeMillis();
            hit = scanTarget(service, target);
            long ms = SystemClock.uptimeMillis() - t0;
            if (hit != null && (hit.found || hit.ambiguous)) {
                TraceLogger.critical("TARGET", "scan=" + i + " found=" + hit.found +
                        " ambiguous=" + hit.ambiguous + " selected=" + hit.selected +
                        " visibleNames=" + hit.visibleCount + " elapsedMs=" + ms);
                break;
            }
            int count = hit == null ? 0 : hit.visibleCount;
            TraceLogger.log("TARGET", "scan=" + i + " miss visibleNames=" + count + " elapsedMs=" + ms);
            if (i < TARGET_SCANS) SystemClock.sleep(70);
        }

        if (!running.get()) return false;
        if (hit != null && hit.ambiguous) {
            fail(started, "失败③｜有同名对象", "没有继续点");
            return false;
        }
        if (hit == null || !hit.found) {
            int count = hit == null ? 0 : hit.visibleCount;
            fail(started, "失败③｜当前页没目标", "看到" + count + "个名字｜联系人栏未移动");
            return false;
        }

        log(started, "③找好友｜已找到", "精确匹配“" + compactName(target) + "”");

        if (hit.selected) {
            log(started, "④点好友｜已经选中", "直接找发送按钮");
        } else {
            log(started, "④点好友｜只点一次", "精确目标坐标");
            long clickBefore = VIEW_CLICK_SEQ.get();
            long stateBefore = WINDOW_STATE_SEQ.get();
            if (!tap(service, hit.tapX, hit.tapY, 60, "target")) {
                fail(started, "失败④｜好友没点上", "点击手势没有完成");
                return false;
            }
            if (!waitForTargetAccepted(running, clickBefore, stateBefore, TARGET_ACK_TIMEOUT_MS)) {
                if (isKeyboardVisible(service)) {
                    dismissKeyboardIfVisible(service, running);
                    fail(started, "失败④｜点后弹出键盘", "已收起键盘，不再继续");
                } else {
                    fail(started, "失败④｜抖音没接住好友点击", "未收到点击/选中状态，不重复点好友");
                }
                return false;
            }
            if (isKeyboardVisible(service)) {
                dismissKeyboardIfVisible(service, running);
                fail(started, "失败④｜点后弹出键盘", "已收起键盘，不再继续");
                return false;
            }
        }

        log(started, "⑤找发送｜等红色按钮", "只认激活的大红色发送按钮");
        PointHit send = waitActiveSendButton(running, started, 1100);
        if (send == null) {
            fail(started, "失败⑤｜发送没亮", "不会重复点好友");
            return false;
        }

        if (!running.get()) return false;
        TraceLogger.critical("SEND", "pixel button x=" + send.x + " y=" + send.y);
        log(started, "⑥发送｜只点一次", "发送后等 MainActivity 返回");

        long stateBeforeSend = WINDOW_STATE_SEQ.get();
        long sendTapStart = SystemClock.uptimeMillis();
        if (!tap(service, send.x, send.y, 60, "send")) {
            fail(started, "失败⑥｜发送没点上", "点击手势没有完成");
            return false;
        }

        if (waitForMainActivity(running, stateBeforeSend, 1200)) {
            long returnMs = SystemClock.uptimeMillis() - sendTapStart;
            TraceLogger.critical("POST_SEND", "MainActivity returned elapsedMs=" + returnMs +
                    " windowId=" + lastWindowId);
            log(started, "分享完成✓", "主界面已返回｜" + returnMs + "ms");
            SystemClock.sleep(300);
            return true;
        }

        fail(started, "失败⑥｜发送后没回主界面", "未收到 MainActivity｜不重复发送");
        return false;
    }

    private static boolean openSharePanelFast(
            TapAccessibilityService service,
            AtomicBoolean running,
            long started) {

        if (cachedShareValid && running.get()) {
            long before = WINDOW_STATE_SEQ.get();
            TraceLogger.log("SHARE_FIND", "cached x=" + Math.round(cachedShareX) +
                    " y=" + Math.round(cachedShareY));
            if (tap(service, cachedShareX, cachedShareY, 60, "share-cache") &&
                    waitForShareDialog(running, before, 650)) {
                TraceLogger.critical("PANEL", "SharePanelDialog from cached share coordinate");
                return true;
            }
            cachedShareValid = false;
            TraceLogger.log("SHARE_FIND", "cached coordinate did not open SharePanelDialog -> drop cache");
            if (isKeyboardVisible(service)) {
                dismissKeyboardIfVisible(service, running);
                return false;
            }
        }

        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) {
            TraceLogger.log("SHARE_FIND", "activeRoot=null");
            return false;
        }

        long t0 = SystemClock.uptimeMillis();
        ShareCandidate best;
        try {
            best = findShareCandidateByQuery(service, root);
        } finally {
            safeRecycle(root);
        }
        long queryMs = SystemClock.uptimeMillis() - t0;

        if (best == null) {
            TraceLogger.log("SHARE_FIND", "parameter query miss elapsedMs=" + queryMs);
            return false;
        }

        TraceLogger.critical("SHARE_FIND", "parameter hit score=" + best.score +
                " bounds=" + best.bounds + " queryMs=" + queryMs);
        log(started, "①点分享｜已定位", "参数查询" + queryMs + "ms");

        long before = WINDOW_STATE_SEQ.get();
        if (!tap(service, best.bounds.centerX(), best.bounds.centerY(), 60, "share")) return false;
        if (!waitForShareDialog(running, before, 750)) {
            TraceLogger.log("PANEL", "SharePanelDialog event timeout");
            return false;
        }

        cachedShareX = best.bounds.centerX();
        cachedShareY = best.bounds.centerY();
        cachedShareValid = true;
        TraceLogger.critical("PANEL", "SharePanelDialog event confirmed; share coordinate cached");
        return true;
    }

    private static ShareCandidate findShareCandidateByQuery(
            TapAccessibilityService service,
            AccessibilityNodeInfo root) {

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        List<AccessibilityNodeInfo> hits = null;
        ShareCandidate best = null;
        long began = SystemClock.uptimeMillis();

        try {
            hits = root.findAccessibilityNodeInfosByText("分享");
            if (hits == null) return null;

            for (AccessibilityNodeInfo n : hits) {
                if (n == null) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                String tx = normalize(text(n.getText()));
                String ds = normalize(text(n.getContentDescription()));
                String cls = normalize(text(n.getClassName()));

                boolean forbidden = isForbiddenShareText(tx) || isForbiddenShareText(ds) ||
                        cls.contains("edittext") || cls.contains("textfield");
                boolean rightRail = b.width() > 0 && b.height() > 0 &&
                        b.centerX() >= w * 0.82f &&
                        b.centerY() >= h * 0.20f && b.centerY() <= h * 0.90f;
                boolean compact = b.width() <= w * 0.20f && b.height() <= h * 0.11f;
                boolean strongDesc = ds.startsWith("分享") && ds.contains("按钮") && !isForbiddenShareText(ds);
                boolean exact = "分享".equals(tx) || "分享".equals(ds);

                if (!forbidden && n.isVisibleToUser() && n.isEnabled() && rightRail && compact &&
                        (strongDesc || exact)) {
                    int score = 0;
                    if (strongDesc) score += 700;
                    if (exact) score += 220;
                    if (n.isClickable()) score += 90;
                    if (b.centerX() >= w * 0.88f) score += 90;
                    ShareCandidate c = resolveShareTapCandidate(n, b, score, w, h);
                    if (best == null || c.score > best.score) best = c;
                }
            }
            return best;
        } catch (Throwable e) {
            TraceLogger.log("SHARE_FIND", "query error=" + shortThrowable(e));
            return null;
        } finally {
            int count = hits == null ? 0 : hits.size();
            if (hits != null) {
                for (AccessibilityNodeInfo n : hits) safeRecycle(n);
            }
            TraceLogger.log("SHARE_QUERY", "hits=" + count + " elapsedMs=" +
                    (SystemClock.uptimeMillis() - began) + " found=" + (best != null));
        }
    }

    private static ShareCandidate resolveShareTapCandidate(
            AccessibilityNodeInfo node,
            Rect own,
            int baseScore,
            int w,
            int h) {

        ShareCandidate best = new ShareCandidate(new Rect(own), baseScore);
        AccessibilityNodeInfo p = node.getParent();
        for (int depth = 0; p != null && depth < 2; depth++) {
            Rect pb = new Rect();
            p.getBoundsInScreen(pb);
            boolean parentOk = p.isVisibleToUser() && p.isEnabled() &&
                    pb.centerX() >= w * 0.82f &&
                    pb.centerY() >= h * 0.20f && pb.centerY() <= h * 0.90f &&
                    pb.width() > 0 && pb.height() > 0 &&
                    pb.width() <= w * 0.22f && pb.height() <= h * 0.12f;
            if (parentOk) {
                int score = baseScore + (p.isClickable() ? 140 : 15) - depth * 10;
                if (score > best.score) best = new ShareCandidate(new Rect(pb), score);
            }
            AccessibilityNodeInfo next = p.getParent();
            safeRecycle(p);
            p = next;
        }
        return best;
    }

    private static boolean waitForShareDialog(AtomicBoolean running, long afterSeq, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            long seq = WINDOW_STATE_SEQ.get();
            String pkg = lastWindowPackage;
            String cls = lastWindowClass;
            if (seq > afterSeq && DOUYIN_PACKAGE.equals(pkg) &&
                    normalize(cls).contains(SHARE_DIALOG_MARK)) {
                return true;
            }
            SystemClock.sleep(25);
        }
        return false;
    }

    private static boolean waitForPanelSettle(AtomicBoolean running, long minAgeMs) {
        long opened = lastShareDialogUptimeMs;
        if (opened <= 0L) return running.get();
        long age = SystemClock.uptimeMillis() - opened;
        long remain = minAgeMs - age;
        if (remain <= 0L) return running.get();
        TraceLogger.log("PANEL", "settle waitMs=" + remain + " currentAgeMs=" + age);
        long end = SystemClock.uptimeMillis() + remain;
        while (running.get() && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(Math.min(20L, Math.max(1L, end - SystemClock.uptimeMillis())));
        }
        return running.get();
    }

    private static boolean waitForTargetAccepted(
            AtomicBoolean running,
            long afterClickSeq,
            long afterStateSeq,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (running.get() && SystemClock.uptimeMillis() < end) {
            long clickSeq = VIEW_CLICK_SEQ.get();
            if (clickSeq > afterClickSeq && DOUYIN_PACKAGE.equals(lastViewClickPackage)) {
                boolean samePanel = lastShareDialogWindowId < 0 || lastViewClickWindowId == lastShareDialogWindowId;
                boolean imageClick = normalize(lastViewClickClass).contains("imageview");
                if (samePanel || imageClick) {
                    TraceLogger.critical("TARGET_ACK",
                            "VIEW_CLICKED seq=" + clickSeq +
                            " windowId=" + lastViewClickWindowId +
                            " class=" + lastViewClickClass +
                            " delayMs=" + Math.max(0L, SystemClock.uptimeMillis() - lastViewClickUptimeMs));
                    return true;
                }
            }

            long stateSeq = WINDOW_STATE_SEQ.get();
            String cls = normalize(lastWindowClass);
            if (stateSeq > afterStateSeq && DOUYIN_PACKAGE.equals(lastWindowPackage) &&
                    lastWindowId != lastShareDialogWindowId && cls.contains("android.widget.framelayout")) {
                TraceLogger.critical("TARGET_ACK",
                        "WINDOW_STATE_CHANGED seq=" + stateSeq +
                        " windowId=" + lastWindowId + " class=" + lastWindowClass);
                return true;
            }
            SystemClock.sleep(20);
        }
        return false;
    }

    private static boolean waitForMainActivity(AtomicBoolean running, long afterSeq, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            long seq = WINDOW_STATE_SEQ.get();
            String pkg = lastWindowPackage;
            String cls = lastWindowClass;
            if (seq > afterSeq && DOUYIN_PACKAGE.equals(pkg) &&
                    cls.contains(MAIN_ACTIVITY_MARK)) {
                return true;
            }
            SystemClock.sleep(25);
        }
        return false;
    }

    private static ContactHit scanTarget(TapAccessibilityService service, String target) {
        PanelRead panel = readPanelSmall(service);
        if (panel == null) return ContactHit.notFound(0);
        try {
            return findTargetContact(service, panel.root, panel.info, target);
        } finally {
            panel.recycle();
        }
    }

    private static ContactHit findTargetContact(
            TapAccessibilityService service,
            AccessibilityNodeInfo root,
            PanelInfo panel,
            String target) {

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        String wanted = normalize(target);
        Rect zone = panel.contactZone;

        ArrayList<ContactHit> hits = new ArrayList<>();
        Set<String> visibleLabels = new HashSet<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            String cls = normalize(text(n.getClassName()));

            boolean inZone = b.width() > 0 && b.height() > 0 &&
                    b.centerY() >= zone.top && b.centerY() <= zone.bottom &&
                    b.centerX() >= -w * 0.03f && b.centerX() <= w * 1.03f;

            if (inZone && n.isVisibleToUser() && !tx.isEmpty() && tx.length() <= 24 && !isUiWord(tx)) {
                visibleLabels.add(tx);
            }

            boolean exact = wanted.equals(tx) || wanted.equals(ds);
            if (exact && inZone && !cls.contains("edittext") && n.isVisibleToUser() && n.isEnabled()) {
                ContactTap tap = bestContactTap(n, zone, w, h);
                boolean selected = tap.selected || n.isSelected() || n.isChecked() ||
                        containsSelectedWord(tx) || containsSelectedWord(ds);
                addContactHit(hits, new ContactHit(true, false, selected, tap.x, tap.y, visibleLabels.size()));
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }

        if (hits.isEmpty()) return ContactHit.notFound(visibleLabels.size());
        if (hits.size() > 1) return new ContactHit(false, true, false, 0, 0, visibleLabels.size());
        ContactHit one = hits.get(0);
        one.visibleCount = visibleLabels.size();
        return one;
    }

    private static ContactTap bestContactTap(AccessibilityNodeInfo start, Rect zone, int w, int h) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(start);
        ContactTap fallback = null;

        for (int depth = 0; cur != null && depth < 5; depth++) {
            Rect b = new Rect();
            cur.getBoundsInScreen(b);
            boolean plausible = cur.isVisibleToUser() && cur.isEnabled() &&
                    b.width() > 0 && b.height() > 0 &&
                    b.width() <= w * 0.34f &&
                    b.height() <= Math.max((int) (zone.height() * 1.18f), (int) (h * 0.09f)) &&
                    b.centerY() >= zone.top - h * 0.02f && b.centerY() <= zone.bottom + h * 0.02f;

            if (plausible) {
                boolean selected = cur.isSelected() || cur.isChecked() ||
                        containsSelectedWord(normalize(text(cur.getText()))) ||
                        containsSelectedWord(normalize(text(cur.getContentDescription())));
                ContactTap candidate = new ContactTap(b.centerX(), b.centerY(), selected);
                if (cur.isClickable()) {
                    safeRecycle(cur);
                    return candidate;
                }
                if (fallback == null) fallback = candidate;
            }

            AccessibilityNodeInfo next = cur.getParent();
            safeRecycle(cur);
            cur = next;
        }

        if (fallback != null) {
            return new ContactTap(fallback.x, zone.top + zone.height() * 0.38f, fallback.selected);
        }
        return new ContactTap(w * 0.5f, zone.top + zone.height() * 0.38f, false);
    }

    private static void addContactHit(List<ContactHit> out, ContactHit hit) {
        for (ContactHit e : out) {
            if (Math.abs(e.tapX - hit.tapX) <= 75 && Math.abs(e.tapY - hit.tapY) <= 100) {
                if (hit.selected) e.selected = true;
                return;
            }
        }
        out.add(hit);
    }

    private static PanelRead waitPanelSmall(
            TapAccessibilityService service,
            AtomicBoolean running,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            PanelRead p = readPanelSmall(service);
            if (p != null) return p;
            SystemClock.sleep(35);
        }
        return null;
    }

    private static PanelRead readPanelSmall(TapAccessibilityService service) {
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return null;

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        Rect title = null;
        int composerTop = Integer.MAX_VALUE;
        int scanned = 0;
        long began = SystemClock.uptimeMillis();

        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            scanned++;
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            String cls = normalize(text(n.getClassName()));
            boolean visible = n.isVisibleToUser();

            if (visible && ("分享给".equals(tx) || "分享给".equals(ds)) && b.centerY() >= h * 0.45f) {
                if (title == null || b.top < title.top) title = new Rect(b);
            }

            boolean composerText = tx.contains("分享此刻想法") || tx.contains("分享此刻的想法") ||
                    tx.contains("分享你的想法") || tx.contains("说点什么") ||
                    ds.contains("分享此刻想法") || ds.contains("分享此刻的想法") ||
                    ds.contains("分享你的想法") || ds.contains("说点什么");
            boolean composerEdit = (cls.contains("edittext") || cls.contains("textfield")) &&
                    b.centerY() >= h * 0.70f;
            if (visible && (composerText || composerEdit) && b.top > h * 0.55f) {
                composerTop = Math.min(composerTop, b.top);
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }

        TraceLogger.log("PANEL_SCAN", "nodes=" + scanned + " elapsedMs=" +
                (SystemClock.uptimeMillis() - began) + " title=" + (title != null));
        if (title == null) {
            safeRecycle(root);
            return null;
        }

        int top = Math.max(title.bottom, (int) (h * 0.57f));
        int bottom;
        if (composerTop != Integer.MAX_VALUE && composerTop > top + h * 0.04f) {
            bottom = composerTop - Math.max(2, (int) (h * 0.004f));
        } else {
            bottom = Math.min((int) (h * 0.86f), top + (int) (h * 0.23f));
        }
        if (bottom <= top + h * 0.05f) {
            bottom = Math.min((int) (h * 0.86f), top + (int) (h * 0.17f));
        }
        return new PanelRead(root, new PanelInfo(new Rect(0, top, w, Math.max(top + 1, bottom))));
    }

    private static AccessibilityNodeInfo getActiveDouyinRoot(TapAccessibilityService service) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                if (DOUYIN_PACKAGE.equals(text(root.getPackageName()))) return root;
                safeRecycle(root);
            }
        } catch (Throwable ignored) {}

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || !window.isActive()) continue;
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        if (root != null && DOUYIN_PACKAGE.equals(text(root.getPackageName()))) {
                            return root;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (root != null && !DOUYIN_PACKAGE.equals(text(root.getPackageName()))) safeRecycle(root);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static PointHit waitActiveSendButton(
            AtomicBoolean running,
            long started,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        int poll = 0;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            poll++;
            long t0 = SystemClock.uptimeMillis();
            PointHit hit = detectActiveSendButtonByPixels();
            long ms = SystemClock.uptimeMillis() - t0;
            TraceLogger.log("SEND_PIXEL", "poll=" + poll + " hit=" + (hit != null) + " elapsedMs=" + ms);
            if (hit != null) return hit;
            SystemClock.sleep(70);
        }
        return null;
    }

    private static PointHit detectActiveSendButtonByPixels() {
        ScreenCaptureService cap = ScreenCaptureService.getInstance();
        if (cap == null) return null;
        Bitmap img = null;
        try {
            img = cap.captureLatest(300);
            int w = img.getWidth();
            int h = img.getHeight();
            int x0 = (int) (w * 0.03f);
            int x1 = (int) (w * 0.97f);
            int y0 = (int) (h * 0.84f);
            int y1 = (int) (h * 0.99f);
            int sx = 4;
            int sy = 3;
            int samples = Math.max(1, (x1 - x0) / sx);
            int minPinkPerRow = (int) (samples * 0.46f);

            int bestStart = -1;
            int bestEnd = -1;
            int bestLen = 0;
            int runStart = -1;
            for (int y = y0; y < y1; y += sy) {
                int hits = 0;
                for (int x = x0; x < x1; x += sx) {
                    if (isDouyinPink(img.getPixel(x, y))) hits++;
                }
                if (hits >= minPinkPerRow) {
                    if (runStart < 0) runStart = y;
                } else if (runStart >= 0) {
                    int len = y - runStart;
                    if (len > bestLen) {
                        bestLen = len;
                        bestStart = runStart;
                        bestEnd = y - sy;
                    }
                    runStart = -1;
                }
            }
            if (runStart >= 0 && y1 - runStart > bestLen) {
                bestLen = y1 - runStart;
                bestStart = runStart;
                bestEnd = y1 - sy;
            }
            if (bestStart < 0 || bestLen < h * 0.018f) return null;

            int midY = (bestStart + bestEnd) / 2;
            int minX = w;
            int maxX = -1;
            int midHits = 0;
            for (int x = x0; x < x1; x += 2) {
                if (isDouyinPink(img.getPixel(x, midY))) {
                    midHits++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
            int midSamples = Math.max(1, (x1 - x0) / 2);
            if (midHits < midSamples * 0.40f) return null;
            if (maxX <= minX || maxX - minX < w * 0.52f) return null;
            return new PointHit((minX + maxX) / 2, midY);
        } catch (Throwable e) {
            TraceLogger.log("SEND_PIXEL", "error=" + shortThrowable(e));
            return null;
        } finally {
            if (img != null && !img.isRecycled()) img.recycle();
        }
    }

    private static boolean isDouyinPink(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return r >= 175 && g <= 160 && b <= 190 && r - g >= 42 && r - b >= 18;
    }

    private static boolean isKeyboardVisible(TapAccessibilityService service) {
        if (Build.VERSION.SDK_INT < 21) return false;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean dismissKeyboardIfVisible(
            TapAccessibilityService service,
            AtomicBoolean running) {
        for (int i = 0; i < 2; i++) {
            if (!isKeyboardVisible(service)) return true;
            if (!running.get()) return false;
            TraceLogger.critical("KEYBOARD", "visible -> GLOBAL_ACTION_BACK attempt=" + (i + 1));
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(180);
        }
        return !isKeyboardVisible(service);
    }

    private static boolean tap(
            TapAccessibilityService service,
            float x,
            float y,
            long duration,
            String label) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(45, duration));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        long began = SystemClock.uptimeMillis();
        boolean accepted = service.dispatchGesture(
                gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        ok[0] = true;
                        latch.countDown();
                    }
                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        latch.countDown();
                    }
                },
                null);
        if (!accepted) {
            TraceLogger.critical("GESTURE", label + " accepted=false");
            return false;
        }
        try {
            latch.await(duration + 550, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        TraceLogger.critical("GESTURE", label + " completed=" + ok[0] +
                " x=" + Math.round(x) + " y=" + Math.round(y) +
                " elapsedMs=" + (SystemClock.uptimeMillis() - began));
        return ok[0];
    }

    private static void log(long started, String stage, String detail) {
        double sec = (SystemClock.uptimeMillis() - started) / 1000.0;
        TapAccessibilityService.setOverlayStatus(
                stage + "\n" + detail + "｜" + String.format(Locale.US, "%.1fs", sec));
        TraceLogger.log("STAGE", stage + " | " + detail + " | " +
                String.format(Locale.US, "%.1fs", sec));
    }

    private static void fail(long started, String stage, String detail) {
        log(started, stage, detail);
        TraceLogger.critical("FAIL", stage + " | " + detail);
    }

    private static boolean isForbiddenShareText(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.contains("分享给你") ||
                s.contains("分享此刻想法") ||
                s.contains("分享此刻的想法") ||
                s.contains("分享你的想法") ||
                s.contains("分享想法") ||
                s.contains("说点什么") ||
                s.contains("写评论") ||
                s.contains("发表评论") ||
                s.startsWith("私信");
    }

    private static boolean containsSelectedWord(String s) {
        return s != null && (s.contains("已选择") || s.contains("已选中") || s.contains("选中"));
    }

    private static boolean isUiWord(String s) {
        return s.equals("分享给") || s.equals("发送") || s.equals("关闭") || s.equals("取消") ||
                s.contains("分享此刻想法") || s.contains("分享此刻的想法") ||
                s.contains("分享你的想法") || s.equals("复制链接") || s.equals("保存本地");
    }

    private static String compactName(String name) {
        if (name == null) return "";
        String s = name.trim();
        return s.length() <= 10 ? s : s.substring(0, 10) + "…";
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String shortThrowable(Throwable e) {
        if (e == null) return "unknown";
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() <= 90 ? s : s.substring(0, 90);
    }

    private static void safeRecycle(AccessibilityNodeInfo node) {
        if (node != null) {
            try { node.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static final class ShareCandidate {
        final Rect bounds;
        final int score;
        ShareCandidate(Rect bounds, int score) {
            this.bounds = bounds;
            this.score = score;
        }
    }

    private static final class PanelRead {
        final AccessibilityNodeInfo root;
        final PanelInfo info;
        PanelRead(AccessibilityNodeInfo root, PanelInfo info) {
            this.root = root;
            this.info = info;
        }
        void recycle() { safeRecycle(root); }
    }

    private static final class PanelInfo {
        final Rect contactZone;
        PanelInfo(Rect contactZone) { this.contactZone = contactZone; }
    }

    private static final class ContactTap {
        final float x;
        final float y;
        final boolean selected;
        ContactTap(float x, float y, boolean selected) {
            this.x = x;
            this.y = y;
            this.selected = selected;
        }
    }

    private static final class ContactHit {
        final boolean found;
        final boolean ambiguous;
        boolean selected;
        final float tapX;
        final float tapY;
        int visibleCount;

        ContactHit(boolean found, boolean ambiguous, boolean selected,
                   float tapX, float tapY, int visibleCount) {
            this.found = found;
            this.ambiguous = ambiguous;
            this.selected = selected;
            this.tapX = tapX;
            this.tapY = tapY;
            this.visibleCount = visibleCount;
        }

        static ContactHit notFound(int visibleCount) {
            return new ContactHit(false, false, false, 0, 0, visibleCount);
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
