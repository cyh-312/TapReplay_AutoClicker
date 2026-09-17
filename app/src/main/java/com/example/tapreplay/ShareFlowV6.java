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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V0.7.1 share flow.
 *
 * Changes are deliberately limited to UI control / diagnostics:
 * - prefer the current active Douyin window instead of scanning every Douyin window;
 * - stop share-button traversal as soon as a strong right-rail candidate is found;
 * - only treat a visible "分享给" node in the active window as an open share panel;
 * - after Send, require the active window to return to a video page with a strong
 *   right-rail share button before declaring success;
 * - keep the V0.7 rules: exact target, no horizontal contact scrolling, one target tap,
 *   pixel-confirmed Send button, and one Send tap only.
 */
public final class ShareFlowV6 {
    private ShareFlowV6() {}

    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final int OPEN_ATTEMPTS = 2;
    private static final int TARGET_SCANS = 3;

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
        TraceLogger.critical("SHARE", "BEGIN target=" + compactName(target) +
                " eventSeq=" + service.getUiEventSequence());

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
            fail(started, "失败0｜键盘没收起", "还没开始点分享");
            return false;
        }

        if (isSharePanelOpenNow(service)) {
            log(started, "准备分享｜清理旧面板", "当前活动窗口仍有“分享给”");
            TraceLogger.critical("SHARE", "old panel visible -> GLOBAL_ACTION_BACK");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            if (!waitPanelGone(service, running, 650)) {
                fail(started, "失败0｜旧分享栏关不掉", "没有继续操作");
                return false;
            }
        }

        log(started, "①找分享｜当前视频窗口", "只认右侧高可信分享按钮");
        if (!openSharePanel(service, running, started)) {
            fail(started, "失败①｜分享栏没打开", "没继续乱点");
            return false;
        }

        PanelRead panel = waitPanel(service, running, 850);
        if (panel == null) {
            fail(started, "失败②｜分享栏读不到", "当前活动窗口没有可见“分享给”");
            return false;
        }
        TraceLogger.critical("PANEL", "confirmed windowId=" + panel.root.getWindowId() +
                " zone=" + panel.info.contactZone);
        log(started, "②分享栏｜已确认“分享给”", "联系人区域已就绪");
        panel.recycle();

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
            log(started, "③找好友｜原地扫描" + i + "/" + TARGET_SCANS,
                    "看到" + count + "个名字｜不滑联系人栏");
            SystemClock.sleep(90);
        }

        if (!running.get()) return false;
        if (hit != null && hit.ambiguous) {
            fail(started, "失败③｜有同名对象", "为避免误发，没有继续点");
            return false;
        }
        if (hit == null || !hit.found) {
            int count = hit == null ? 0 : hit.visibleCount;
            fail(started, "失败③｜当前页没目标", "看到" + count + "个名字｜联系人栏未移动");
            return false;
        }

        log(started, "③找好友｜已找到", "精确匹配“" + compactName(target) + "”");

        if (hit.selected) {
            log(started, "④点好友｜已经选中", "不重复点击，直接等发送");
        } else {
            log(started, "④点好友｜只点一次", "坐标来自目标联系人本身");
            if (!tap(service, hit.tapX, hit.tapY, 65, "target")) {
                fail(started, "失败④｜好友没点上", "点击手势没有完成");
                return false;
            }
            SystemClock.sleep(110);

            if (isKeyboardVisible(service)) {
                dismissKeyboardIfVisible(service, running);
                fail(started, "失败④｜点后弹出键盘", "已收起键盘，不再继续");
                return false;
            }

            if (!isSharePanelOpenNow(service)) {
                fail(started, "失败④｜点后页面跑偏", "活动窗口已不是分享栏");
                return false;
            }
        }

        log(started, "⑤找发送｜等红色按钮亮", "只认底部大面积红色按钮");
        PointHit send = waitActiveSendButton(running, started, 1500);
        if (send == null) {
            fail(started, "失败⑤｜发送没亮", "不会再点好友，现场已保留");
            return false;
        }

        if (!running.get()) return false;
        TraceLogger.critical("SEND", "pixel button x=" + send.x + " y=" + send.y);
        log(started, "⑥发送｜红色按钮已找到", "只发送一次");
        long eventSeqBeforeSend = service.getUiEventSequence();
        if (!tap(service, send.x, send.y, 65, "send")) {
            fail(started, "失败⑥｜发送没点上", "点击手势没有完成");
            return false;
        }

        if (waitReturnToVideo(service, running, started, eventSeqBeforeSend, 2600)) {
            log(started, "分享完成✓", "已确认回到视频页");
            SystemClock.sleep(220);
            return true;
        }

        // Never retry Send automatically. A slow UI must not cause a duplicate share.
        fail(started, "失败⑥｜发送后未确认回视频页", "不重复发送，请看当前页面和日志");
        return false;
    }

    private static boolean openSharePanel(
            TapAccessibilityService service,
            AtomicBoolean running,
            long started) {

        if (isSharePanelOpenNow(service)) return true;

        for (int attempt = 1; attempt <= OPEN_ATTEMPTS && running.get(); attempt++) {
            if (isKeyboardVisible(service) && !dismissKeyboardIfVisible(service, running)) return false;

            AccessibilityNodeInfo root = getActiveDouyinRoot(service);
            if (root == null) {
                TraceLogger.log("SHARE_FIND", "attempt=" + attempt + " activeRoot=null");
                log(started, "①找分享｜第" + attempt + "/" + OPEN_ATTEMPTS + "次", "当前视频窗口没读到");
                SystemClock.sleep(90);
                continue;
            }

            long t0 = SystemClock.uptimeMillis();
            ShareCandidate best = findShareCandidateFast(service, root, true);
            int windowId = root.getWindowId();
            safeRecycle(root);
            long scanMs = SystemClock.uptimeMillis() - t0;

            if (best == null) {
                TraceLogger.log("SHARE_FIND", "attempt=" + attempt + " windowId=" + windowId +
                        " candidate=none elapsedMs=" + scanMs);
                log(started, "①找分享｜第" + attempt + "/" + OPEN_ATTEMPTS + "次", "右侧候选0个");
                SystemClock.sleep(100);
                continue;
            }

            TraceLogger.critical("SHARE_FIND", "attempt=" + attempt + " windowId=" + windowId +
                    " score=" + best.score + " bounds=" + best.bounds + " scanMs=" + scanMs);
            log(started, "①点分享｜第" + attempt + "/" + OPEN_ATTEMPTS + "次",
                    "已锁定右侧高可信按钮");
            if (!tap(service, best.bounds.centerX(), best.bounds.centerY(), 65, "share")) {
                SystemClock.sleep(80);
                continue;
            }

            long end = SystemClock.uptimeMillis() + 1050;
            while (SystemClock.uptimeMillis() < end && running.get()) {
                if (isSharePanelOpenNow(service)) {
                    TraceLogger.critical("PANEL", "visible after share tap elapsedMs=" +
                            (SystemClock.uptimeMillis() - t0));
                    return true;
                }
                if (isKeyboardVisible(service)) {
                    TraceLogger.critical("SHARE", "keyboard appeared after share tap");
                    dismissKeyboardIfVisible(service, running);
                    break;
                }
                SystemClock.sleep(60);
            }
        }
        return false;
    }

    /**
     * Scan only the current active Douyin root. A strong content description such as
     * "分享3092，按钮" ends the traversal immediately after resolving its compact parent.
     */
    private static ShareCandidate findShareCandidateFast(
            TapAccessibilityService service,
            AccessibilityNodeInfo root,
            boolean allowExactFallback) {

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        ShareCandidate best = null;
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

            boolean forbidden = isForbiddenShareText(tx) || isForbiddenShareText(ds) ||
                    cls.contains("edittext") || cls.contains("textfield");
            boolean rightRail = b.centerX() >= w * 0.82f &&
                    b.centerY() >= h * 0.20f && b.centerY() <= h * 0.90f;
            boolean compact = b.width() > 0 && b.height() > 0 &&
                    b.width() <= w * 0.20f && b.height() <= h * 0.11f;
            boolean strongDesc = ds.startsWith("分享") && ds.contains("按钮") && !isForbiddenShareText(ds);
            boolean exact = "分享".equals(tx) || "分享".equals(ds);

            if (!forbidden && n.isVisibleToUser() && n.isEnabled() && rightRail && compact &&
                    (strongDesc || (allowExactFallback && exact))) {
                int score = 0;
                if (strongDesc) score += 700;
                if (exact) score += 220;
                if (n.isClickable()) score += 90;
                if (b.centerX() >= w * 0.88f) score += 90;

                ShareCandidate c = resolveShareTapCandidate(n, b, score, w, h);
                if (best == null || c.score > best.score) best = c;

                if (strongDesc && c.score >= 790) {
                    safeRecycle(n);
                    recycleQueue(q);
                    TraceLogger.log("SHARE_SCAN", "early strong hit nodes=" + scanned +
                            " elapsedMs=" + (SystemClock.uptimeMillis() - began));
                    return c;
                }
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.add(child);
            }
            safeRecycle(n);
        }

        TraceLogger.log("SHARE_SCAN", "finished nodes=" + scanned + " elapsedMs=" +
                (SystemClock.uptimeMillis() - began) + " found=" + (best != null));
        return best;
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

    /** Current visible contacts only. No horizontal gesture exists in this class. */
    private static ContactHit scanTarget(TapAccessibilityService service, String target) {
        PanelRead panel = readPanel(service);
        if (panel == null) return ContactHit.notFound(0);
        try {
            if (panel.info.contactZone == null) return ContactHit.notFound(0);
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

    private static boolean containsSelectedWord(String s) {
        if (s == null) return false;
        return s.contains("已选择") || s.contains("已选中") || s.contains("选中");
    }

    private static boolean isUiWord(String s) {
        return s.equals("分享给") || s.equals("发送") || s.equals("关闭") || s.equals("取消") ||
                s.contains("分享此刻想法") || s.contains("分享此刻的想法") ||
                s.contains("分享你的想法") || s.equals("复制链接") || s.equals("保存本地");
    }

    /** Read only the active/focused Douyin window. */
    private static PanelRead readPanel(TapAccessibilityService service) {
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return null;
        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        PanelInfo info = inspectPanel(root, w, h);
        if (!info.open) {
            safeRecycle(root);
            return null;
        }
        return new PanelRead(root, info);
    }

    private static PanelRead waitPanel(
            TapAccessibilityService service,
            AtomicBoolean running,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            PanelRead p = readPanel(service);
            if (p != null) return p;
            SystemClock.sleep(60);
        }
        return null;
    }

    private static boolean waitPanelGone(
            TapAccessibilityService service,
            AtomicBoolean running,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        int gone = 0;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            if (isSharePanelOpenNow(service)) {
                gone = 0;
            } else if (++gone >= 2) {
                return true;
            }
            SystemClock.sleep(70);
        }
        return false;
    }

    private static boolean isSharePanelOpenNow(TapAccessibilityService service) {
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return false;
        try {
            int h = service.getResources().getDisplayMetrics().heightPixels;
            return hasVisibleShareTitle(root, h);
        } finally {
            safeRecycle(root);
        }
    }

    /**
     * The panel title must be visible in the current active window. Stale hidden nodes and
     * inactive historical Douyin windows are intentionally ignored.
     */
    private static boolean hasVisibleShareTitle(AccessibilityNodeInfo root, int h) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            if (n.isVisibleToUser() && ("分享给".equals(tx) || "分享给".equals(ds)) &&
                    b.centerY() >= h * 0.45f) {
                safeRecycle(n);
                recycleQueue(q);
                return true;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }
        return false;
    }

    private static PanelInfo inspectPanel(AccessibilityNodeInfo root, int w, int h) {
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

        TraceLogger.log("PANEL_SCAN", "windowId=" + root.getWindowId() + " nodes=" + scanned +
                " elapsedMs=" + (SystemClock.uptimeMillis() - began) + " title=" + (title != null));
        if (title == null) return PanelInfo.closed();

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

        return new PanelInfo(true, new Rect(0, top, w, Math.max(top + 1, bottom)));
    }

    /** Prefer getRootInActiveWindow; only fall back to an active/focused Douyin window. */
    private static AccessibilityNodeInfo getActiveDouyinRoot(TapAccessibilityService service) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                if (DOUYIN_PACKAGE.equals(text(root.getPackageName()))) return root;
                safeRecycle(root);
            }
        } catch (Throwable ignored) {}

        AccessibilityNodeInfo focusedFallback = null;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || (!window.isActive() && !window.isFocused())) continue;
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        if (root == null || !DOUYIN_PACKAGE.equals(text(root.getPackageName()))) {
                            safeRecycle(root);
                            continue;
                        }
                        if (window.isActive()) {
                            safeRecycle(focusedFallback);
                            return root;
                        }
                        if (focusedFallback == null) {
                            focusedFallback = root;
                            root = null;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        safeRecycle(root);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return focusedFallback;
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
            if (poll == 4 || poll == 8) {
                log(started, "⑤找发送｜还没亮", "继续等红色大按钮");
            }
            SystemClock.sleep(90);
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
            TraceLogger.log("SEND_PIXEL", "capture/detect error=" + shortThrowable(e));
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

    /**
     * Success is no longer "some old share window disappeared". We require the active
     * Douyin window to have no visible panel title and to expose the strong right-rail
     * video share button twice consecutively.
     */
    private static boolean waitReturnToVideo(
            TapAccessibilityService service,
            AtomicBoolean running,
            long started,
            long eventSeqBeforeSend,
            long timeoutMs) {

        long end = SystemClock.uptimeMillis() + timeoutMs;
        int videoReady = 0;
        int poll = 0;

        while (SystemClock.uptimeMillis() < end && running.get()) {
            poll++;
            AccessibilityNodeInfo root = getActiveDouyinRoot(service);
            if (root == null) {
                TraceLogger.log("POST_SEND", "poll=" + poll + " root=null eventSeq=" + service.getUiEventSequence());
                videoReady = 0;
                SystemClock.sleep(85);
                continue;
            }

            int w = service.getResources().getDisplayMetrics().widthPixels;
            int h = service.getResources().getDisplayMetrics().heightPixels;
            int windowId = root.getWindowId();
            boolean panelVisible = hasVisibleShareTitle(root, h);
            boolean strongVideoShare = false;
            if (!panelVisible) {
                ShareCandidate c = findShareCandidateFast(service, root, false);
                strongVideoShare = c != null && c.score >= 790;
            }
            safeRecycle(root);

            long seq = service.getUiEventSequence();
            TraceLogger.log("POST_SEND", "poll=" + poll + " windowId=" + windowId +
                    " panel=" + panelVisible + " videoShare=" + strongVideoShare +
                    " eventSeq=" + seq + " delta=" + (seq - eventSeqBeforeSend));

            if (!panelVisible && strongVideoShare) {
                videoReady++;
                if (videoReady >= 2) {
                    TraceLogger.critical("POST_SEND", "confirmed video page polls=" + poll +
                            " elapsedMs=" + (timeoutMs - Math.max(0, end - SystemClock.uptimeMillis())));
                    return true;
                }
            } else {
                videoReady = 0;
            }

            if (poll == 6) {
                log(started, "⑥发送｜等待回视频页", "不会补点发送");
            }
            SystemClock.sleep(85);
        }
        return false;
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
            if (running != null && !running.get()) return false;
            TraceLogger.critical("KEYBOARD", "visible -> GLOBAL_ACTION_BACK attempt=" + (i + 1));
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(220);
        }
        return !isKeyboardVisible(service);
    }

    private static void log(long started, String stage, String detail) {
        double sec = (SystemClock.uptimeMillis() - started) / 1000.0;
        String rendered = stage + "\n" + detail + "｜" + String.format(Locale.US, "%.1fs", sec);
        TapAccessibilityService.setOverlayStatus(rendered);
        TraceLogger.log("STAGE", stage + " | " + detail + " | " +
                String.format(Locale.US, "%.1fs", sec));
    }

    private static void fail(long started, String stage, String detail) {
        log(started, stage, detail);
        TraceLogger.critical("FAIL", stage + " | " + detail);
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
            TraceLogger.critical("GESTURE", label + " accepted=false x=" + Math.round(x) + " y=" + Math.round(y));
            return false;
        }
        try {
            latch.await(duration + 700, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TraceLogger.critical("GESTURE", label + " interrupted");
            return false;
        }
        TraceLogger.critical("GESTURE", label + " completed=" + ok[0] +
                " x=" + Math.round(x) + " y=" + Math.round(y) +
                " elapsedMs=" + (SystemClock.uptimeMillis() - began));
        return ok[0];
    }

    private static String compactName(String name) {
        if (name == null) return "";
        String s = name.trim();
        return s.length() <= 10 ? s : s.substring(0, 10) + "…";
    }

    private static String shortThrowable(Throwable e) {
        if (e == null) return "unknown";
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() <= 80 ? s : s.substring(0, 80);
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private static void recycleQueue(ArrayDeque<AccessibilityNodeInfo> q) {
        while (!q.isEmpty()) safeRecycle(q.removeFirst());
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
        final boolean open;
        final Rect contactZone;
        PanelInfo(boolean open, Rect contactZone) {
            this.open = open;
            this.contactZone = contactZone;
        }
        static PanelInfo closed() { return new PanelInfo(false, null); }
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
