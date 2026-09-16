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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V0.5 分享状态机。
 *
 * 思路尽量复刻已经稳定的 PC 版：
 * 1. 每一步都重新读取抖音窗口，不复用旧节点；
 * 2. 只认右侧操作栏的真实“分享”按钮；
 * 3. 点完必须确认真正出现“分享给”面板；
 * 4. 只在分享面板的联系人横条中寻找目标；
 * 5. 目标点选后重新确认，再找底部发送按钮；
 * 6. 发送后必须确认分享面板稳定消失；
 * 7. 任一步误弹键盘都先收起并回到当前状态重新判断，绝不继续盲点。
 */
public final class ShareFlowV3 {
    private ShareFlowV3() {}

    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";

    private static final int OPEN_PANEL_ROUNDS = 5;
    private static final int CONTACT_PAGES = 8;
    private static final int SEND_FIND_ROUNDS = 7;

    public static boolean shareToTarget(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running) throws Exception {

        if (service == null || running == null || !running.get()) return false;
        target = target == null ? "" : target.trim();
        if (target.isEmpty()) return false;

        // 进入分享流程前先确保输入法没有盖住视频页。
        if (!dismissKeyboardIfVisible(service, running, false)) return false;

        TapAccessibilityService.setOverlayStatus("符合｜正在找右侧分享按钮…");
        if (!openSharePanel(service, running)) {
            failAndRecover(service, running, "分享栏没打开｜已停下");
            return false;
        }

        if (!running.get()) return false;
        TapAccessibilityService.setOverlayStatus("分享栏已打开｜正在找分享对象…");

        for (int page = 0; page < CONTACT_PAGES && running.get(); page++) {
            if (!dismissKeyboardIfVisible(service, running, true)) {
                failAndRecover(service, running, "键盘一直没收起｜已停下");
                return false;
            }

            AccessibilityNodeInfo root = getDouyinRoot(service);
            if (root == null) {
                SystemClock.sleep(160);
                continue;
            }

            PanelInfo panel = inspectPanel(service, root);
            if (!panel.open) {
                safeRecycle(root);
                failAndRecover(service, running, "分享栏不见了｜已停下");
                return false;
            }

            ContactHit hit = findTargetContact(service, root, panel, target);
            safeRecycle(root);

            if (hit.ambiguous) {
                failAndRecover(service, running, "发现多个同名联系人｜已停下");
                return false;
            }

            if (hit.found) {
                TapAccessibilityService.setOverlayStatus(
                        hit.selected ? "目标已经选中｜正在找发送按钮…" : "找到分享对象｜正在点选…");

                if (!hit.selected) {
                    if (!tap(service, hit.bounds.centerX(), hit.bounds.centerY(), 70)) {
                        failAndRecover(service, running, "没点上分享对象｜已停下");
                        return false;
                    }

                    // 等待联系人选中动画与发送按钮出现。
                    SystemClock.sleep(420);
                    if (isKeyboardVisible(service)) {
                        // 目标联系人本不应该弹键盘。若发生，说明界面发生了意外变化；
                        // 收起后重新确认分享面板和目标，不沿用旧坐标。
                        dismissKeyboardIfVisible(service, running, true);
                        if (!isSharePanelOpenNow(service)) {
                            failAndRecover(service, running, "点选后页面跑偏｜已停下");
                            return false;
                        }
                    }
                }

                if (!running.get()) return false;

                PointHit send = waitForSendButton(service, running);
                if (send == null) {
                    failAndRecover(service, running, "发送按钮没出来｜已停下");
                    return false;
                }

                // 发送最多尝试两次。每次都重新找按钮并验证分享面板是否真的消失。
                for (int sendTry = 1; sendTry <= 2 && running.get(); sendTry++) {
                    TapAccessibilityService.setOverlayStatus(
                            sendTry == 1 ? "正在发送…" : "发送没反应｜再点一次…");

                    if (!tap(service, send.x, send.y, 70)) {
                        SystemClock.sleep(180);
                    } else if (waitPanelClosedStable(service, running, 3600)) {
                        SystemClock.sleep(700);
                        return true;
                    }

                    if (isKeyboardVisible(service)) {
                        dismissKeyboardIfVisible(service, running, true);
                        failAndRecover(service, running, "发送时页面跑偏｜已停下");
                        return false;
                    }

                    if (!isSharePanelOpenNow(service)) {
                        // 面板已经消失，按发送成功处理。
                        SystemClock.sleep(700);
                        return true;
                    }

                    send = waitForSendButton(service, running);
                    if (send == null) break;
                }

                failAndRecover(service, running, "发送后分享栏没收起｜已停下");
                return false;
            }

            // 当前可见联系人中没有目标，只允许在已经确认的联系人横条内横向滑。
            Rect row = panel.contactRow;
            if (row == null || row.width() <= 0 || row.height() <= 0) {
                failAndRecover(service, running, "没认出联系人栏｜已停下");
                return false;
            }

            TapAccessibilityService.setOverlayStatus(
                    "没看到目标｜联系人栏继续找 " + (page + 1) + "/" + CONTACT_PAGES);

            float y = row.centerY();
            float x1 = Math.max(row.left + 24, row.right - row.width() * 0.12f);
            float x2 = Math.min(row.right - 24, row.left + row.width() * 0.20f);
            if (!gesture(service, x1, y, x2, y, 380)) {
                failAndRecover(service, running, "联系人栏没滑动｜已停下");
                return false;
            }
            SystemClock.sleep(500);

            if (!isSharePanelOpenNow(service)) {
                failAndRecover(service, running, "找联系人时分享栏关了｜已停下");
                return false;
            }
        }

        failAndRecover(service, running, "联系人栏里没找到目标｜已停下");
        return false;
    }

    /** 打开真正的“分享给”面板。 */
    private static boolean openSharePanel(
            TapAccessibilityService service,
            AtomicBoolean running) {

        if (isSharePanelOpenNow(service)) return true;

        for (int round = 1; round <= OPEN_PANEL_ROUNDS && running.get(); round++) {
            dismissKeyboardIfVisible(service, running, false);

            AccessibilityNodeInfo root = getDouyinRoot(service);
            if (root == null) {
                TapAccessibilityService.setOverlayStatus("页面还没准备好｜再看一次 " + round + "/" + OPEN_PANEL_ROUNDS);
                SystemClock.sleep(180);
                continue;
            }

            List<ShareCandidate> candidates = findShareCandidates(service, root);
            safeRecycle(root);

            if (candidates.isEmpty()) {
                TapAccessibilityService.setOverlayStatus(
                        "没看到右侧分享按钮｜再找一次 " + round + "/" + OPEN_PANEL_ROUNDS);
                SystemClock.sleep(260);
                continue;
            }

            // 只试最可信的两个候选。点完后必须看到“分享给”面板才算成功。
            int tries = Math.min(2, candidates.size());
            for (int i = 0; i < tries && running.get(); i++) {
                ShareCandidate c = candidates.get(i);
                TapAccessibilityService.setOverlayStatus(
                        "正在点右侧分享按钮｜" + round + "/" + OPEN_PANEL_ROUNDS);

                tap(service, c.bounds.centerX(), c.bounds.centerY(), 70);

                long end = SystemClock.uptimeMillis() + 1650;
                boolean wrongComposer = false;
                while (SystemClock.uptimeMillis() < end && running.get()) {
                    if (isSharePanelOpenNow(service)) {
                        recycleCandidates(candidates);
                        return true;
                    }
                    if (isKeyboardVisible(service)) {
                        wrongComposer = true;
                        break;
                    }
                    SystemClock.sleep(90);
                }

                if (wrongComposer) {
                    TapAccessibilityService.setOverlayStatus("点到了输入区｜正在恢复…");
                    dismissKeyboardIfVisible(service, running, false);
                    SystemClock.sleep(260);
                }
            }

            recycleCandidates(candidates);
            SystemClock.sleep(220);
        }
        return false;
    }

    /**
     * 只寻找右侧操作栏的真实分享按钮。
     * 高优先级：content-desc 类似“分享11.1万，按钮”；其次：资源 id 含 share；
     * 最后才接受右侧小区域内文本/描述恰好等于“分享”的节点。
     */
    private static List<ShareCandidate> findShareCandidates(
            TapAccessibilityService service,
            AccessibilityNodeInfo root) {

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        ArrayList<ShareCandidate> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);

            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            String id = normalize(text(n.getViewIdResourceName()));
            String cls = normalize(text(n.getClassName()));

            boolean forbiddenText = isForbiddenShareText(tx) || isForbiddenShareText(ds);
            boolean editLike = cls.contains("edittext") || cls.contains("textfield");

            boolean rightRail =
                    b.centerX() >= w * 0.74f &&
                    b.centerY() >= h * 0.16f &&
                    b.centerY() <= h * 0.90f;

            boolean compact =
                    b.width() > 0 && b.height() > 0 &&
                    b.width() <= w * 0.26f &&
                    b.height() <= h * 0.17f;

            boolean strongDesc = isStrongShareDescription(ds);
            boolean shareId = id.contains("share") || id.contains("fenxiang");
            boolean exactShare = "分享".equals(tx) || "分享".equals(ds);

            if (!forbiddenText && !editLike && n.isVisibleToUser() && n.isEnabled() &&
                    rightRail && compact && (strongDesc || shareId || exactShare)) {

                int baseScore = 0;
                if (strongDesc) baseScore += 500;
                if (shareId) baseScore += 260;
                if (exactShare) baseScore += 180;
                if (n.isClickable()) baseScore += 70;
                if (b.centerX() >= w * 0.82f) baseScore += 70;
                if (b.centerY() >= h * 0.55f) baseScore += 25;

                addCandidate(out, n, b, baseScore);

                // 文字节点有时只是子节点；向上找最多三层小型右侧容器，优先可点击父容器。
                AccessibilityNodeInfo p = n.getParent();
                for (int depth = 0; p != null && depth < 3; depth++) {
                    Rect pb = new Rect();
                    p.getBoundsInScreen(pb);
                    boolean parentRight =
                            pb.centerX() >= w * 0.74f &&
                            pb.centerY() >= h * 0.16f && pb.centerY() <= h * 0.90f;
                    boolean parentCompact =
                            pb.width() > 0 && pb.height() > 0 &&
                            pb.width() <= w * 0.30f && pb.height() <= h * 0.18f;

                    if (p.isVisibleToUser() && p.isEnabled() && parentRight && parentCompact) {
                        int score = baseScore + (p.isClickable() ? 120 : 20) - depth * 12;
                        addCandidate(out, p, pb, score);
                    }

                    AccessibilityNodeInfo next = p.getParent();
                    safeRecycle(p);
                    p = next;
                }
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }

        Collections.sort(out, Comparator.comparingInt((ShareCandidate x) -> x.score).reversed());
        return out;
    }

    private static boolean isStrongShareDescription(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!s.startsWith("分享")) return false;
        if (isForbiddenShareText(s)) return false;
        // 已实测的抖音右侧分享节点会出现“分享11.1万，按钮”一类描述。
        return s.contains("按钮") || "分享".equals(s);
    }

    private static boolean isForbiddenShareText(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.contains("分享给你") ||
                s.contains("分享此刻想法") ||
                s.contains("分享你的想法") ||
                s.contains("分享想法") ||
                s.contains("说点什么") ||
                s.contains("写评论") ||
                s.contains("发表评论") ||
                s.startsWith("私信");
    }

    /** 读取当前抖音应用窗口，而不是简单相信 active window。 */
    private static AccessibilityNodeInfo getDouyinRoot(TapAccessibilityService service) {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            AccessibilityNodeInfo best = null;
            int bestArea = -1;

            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo r = null;
                    try {
                        r = window.getRoot();
                        if (r == null) continue;
                        String pkg = text(r.getPackageName());
                        if (!DOUYIN_PACKAGE.equals(pkg)) {
                            safeRecycle(r);
                            continue;
                        }

                        Rect b = new Rect();
                        r.getBoundsInScreen(b);
                        int area = Math.max(0, b.width()) * Math.max(0, b.height());
                        if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) area += 1_000_000;

                        if (area > bestArea) {
                            safeRecycle(best);
                            best = AccessibilityNodeInfo.obtain(r);
                            bestArea = area;
                        }
                        safeRecycle(r);
                    } catch (Throwable ignored) {
                        safeRecycle(r);
                    }
                }
            }
            if (best != null) return best;
        } catch (Throwable ignored) {}

        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return null;
            String pkg = text(root.getPackageName());
            if (DOUYIN_PACKAGE.equals(pkg)) return root;
            safeRecycle(root);
        } catch (Throwable ignored) {}
        return null;
    }

    /** 获取分享面板状态以及联系人横条区域。 */
    private static PanelInfo inspectPanel(
            TapAccessibilityService service,
            AccessibilityNodeInfo root) {

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;

        Rect titleBounds = null;
        Rect closeBounds = null;
        ArrayList<Rect> recyclerCandidates = new ArrayList<>();
        boolean hasShareAction = false;

        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            String id = normalize(text(n.getViewIdResourceName()));

            if (("分享给".equals(tx) || "分享给".equals(ds)) && b.centerY() >= h * 0.50f) {
                titleBounds = new Rect(b);
            }

            if (("关闭".equals(tx) || "关闭".equals(ds) || "取消".equals(tx) || "取消".equals(ds)) &&
                    b.centerY() >= h * 0.50f) {
                closeBounds = new Rect(b);
            }

            if (b.centerY() >= h * 0.55f && (
                    "复制链接".equals(tx) || "复制链接".equals(ds) ||
                    "保存本地".equals(tx) || "保存本地".equals(ds) ||
                    "微信".equals(tx) || "微信".equals(ds) ||
                    "朋友圈".equals(tx) || "朋友圈".equals(ds))) {
                hasShareAction = true;
            }

            boolean recyclerLike = id.contains("recycler") || n.isScrollable();
            boolean plausibleRow =
                    b.top >= h * 0.52f && b.top <= h * 0.90f &&
                    b.width() >= w * 0.65f &&
                    b.height() >= h * 0.045f && b.height() <= h * 0.24f;
            if (recyclerLike && plausibleRow) recyclerCandidates.add(new Rect(b));

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }

        boolean open = titleBounds != null;
        if (!open && closeBounds != null && hasShareAction) open = true;

        Rect contactRow = null;
        if (open) {
            float titleBottom = titleBounds != null ? titleBounds.bottom : h * 0.62f;
            int bestScore = Integer.MIN_VALUE;

            for (Rect r : recyclerCandidates) {
                // 联系人横条通常紧跟在“分享给”标题下方，不能落到“分享此刻想法”输入区。
                boolean underTitle = r.top >= titleBottom - h * 0.02f;
                boolean notTooLow = r.centerY() <= h * 0.86f;
                if (!underTitle || !notTooLow) continue;

                int score = 0;
                if (r.width() >= w * 0.85f) score += 80;
                if (r.height() >= h * 0.06f && r.height() <= h * 0.18f) score += 80;
                score -= (int) Math.abs(r.top - titleBottom) / 4;
                if (score > bestScore) {
                    contactRow = new Rect(r);
                    bestScore = score;
                }
            }

            // 某些版本联系人条不暴露 RecyclerView。用标题下方的安全区域做兜底，
            // 仍然明确避开底部“分享此刻想法”输入区。
            if (contactRow == null && titleBounds != null) {
                int top = Math.max(titleBounds.bottom, (int) (h * 0.62f));
                int bottom = Math.min((int) (top + h * 0.20f), (int) (h * 0.84f));
                if (bottom > top) contactRow = new Rect(0, top, w, bottom);
            }
        }

        return new PanelInfo(open, titleBounds, contactRow);
    }

    private static boolean isSharePanelOpenNow(TapAccessibilityService service) {
        AccessibilityNodeInfo root = getDouyinRoot(service);
        if (root == null) return false;
        try {
            return inspectPanel(service, root).open;
        } finally {
            safeRecycle(root);
        }
    }

    /** 在联系人横条中精确匹配昵称。 */
    private static ContactHit findTargetContact(
            TapAccessibilityService service,
            AccessibilityNodeInfo root,
            PanelInfo panel,
            String target) {

        if (!panel.open || panel.contactRow == null) return ContactHit.notFound();

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        String wanted = normalize(target);

        ArrayList<ContactHit> hits = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            String cls = normalize(text(n.getClassName()));

            boolean exact = wanted.equals(tx) || wanted.equals(ds);
            boolean inRow = intersectsMostly(b, panel.contactRow) &&
                    b.centerY() >= panel.contactRow.top && b.centerY() <= panel.contactRow.bottom;

            if (exact && inRow && !cls.contains("edittext") && n.isVisibleToUser()) {
                NodeHit click = findBestContactContainer(n, panel.contactRow, w, h);
                Rect hitBounds = click != null ? click.bounds : new Rect(b);
                boolean selected = n.isSelected() || n.isChecked() || (click != null && click.selected);

                if (hitBounds.width() > 0 && hitBounds.height() > 0 &&
                        hitBounds.width() <= w * 0.38f && hitBounds.height() <= h * 0.20f &&
                        intersectsMostly(hitBounds, panel.contactRow)) {
                    addContactHit(hits, new ContactHit(true, false, selected, hitBounds));
                }
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }

        if (hits.isEmpty()) return ContactHit.notFound();
        if (hits.size() > 1) return ContactHit.ambiguous();
        return hits.get(0);
    }

    private static NodeHit findBestContactContainer(
            AccessibilityNodeInfo start,
            Rect row,
            int w,
            int h) {

        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(start);
        NodeHit best = null;

        for (int depth = 0; cur != null && depth < 5; depth++) {
            Rect b = new Rect();
            cur.getBoundsInScreen(b);

            boolean plausible =
                    cur.isVisibleToUser() && cur.isEnabled() &&
                    b.width() > 0 && b.height() > 0 &&
                    b.width() <= w * 0.38f && b.height() <= h * 0.20f &&
                    intersectsMostly(b, row);

            if (plausible) {
                boolean selected = cur.isSelected() || cur.isChecked();
                if (cur.isClickable()) {
                    best = new NodeHit(new Rect(b), selected);
                    safeRecycle(cur);
                    return best;
                }
                if (best == null) best = new NodeHit(new Rect(b), selected);
            }

            AccessibilityNodeInfo next = cur.getParent();
            safeRecycle(cur);
            cur = next;
        }
        return best;
    }

    private static void addContactHit(List<ContactHit> out, ContactHit hit) {
        for (ContactHit e : out) {
            if (Math.abs(e.bounds.centerX() - hit.bounds.centerX()) <= 80 &&
                    Math.abs(e.bounds.centerY() - hit.bounds.centerY()) <= 90) {
                // 同一个头像/昵称区域出现多个节点时合并，不当成重名联系人。
                if (hit.selected) e.selected = true;
                return;
            }
        }
        out.add(hit);
    }

    private static boolean intersectsMostly(Rect a, Rect b) {
        if (a == null || b == null || a.width() <= 0 || a.height() <= 0) return false;
        Rect inter = new Rect();
        if (!inter.setIntersect(a, b)) return false;
        long ia = (long) inter.width() * inter.height();
        long aa = (long) a.width() * a.height();
        return aa > 0 && ia >= aa * 0.55;
    }

    /** 等待底部“发送”按钮。先走无障碍节点，找不到再用内存截图识别大面积粉红按钮。 */
    private static PointHit waitForSendButton(
            TapAccessibilityService service,
            AtomicBoolean running) {

        for (int i = 1; i <= SEND_FIND_ROUNDS && running.get(); i++) {
            if (!isSharePanelOpenNow(service)) return null;

            PointHit hit = findSendButtonByAccessibility(service);
            if (hit == null) hit = detectSendButtonByPixels();
            if (hit != null) return hit;

            TapAccessibilityService.setOverlayStatus(
                    "目标已选｜正在等发送按钮 " + i + "/" + SEND_FIND_ROUNDS);
            SystemClock.sleep(240);
        }
        return null;
    }

    private static PointHit findSendButtonByAccessibility(TapAccessibilityService service) {
        AccessibilityNodeInfo root = getDouyinRoot(service);
        if (root == null) return null;
        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;

        PointHit best = null;
        int bestScore = Integer.MIN_VALUE;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        safeRecycle(root);

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));

            if (("发送".equals(tx) || "发送".equals(ds)) &&
                    b.centerY() >= h * 0.82f && n.isVisibleToUser()) {

                Rect use = new Rect(b);
                AccessibilityNodeInfo p = n.getParent();
                for (int depth = 0; p != null && depth < 3; depth++) {
                    Rect pb = new Rect();
                    p.getBoundsInScreen(pb);
                    if (p.isVisibleToUser() && p.isEnabled() &&
                            pb.centerY() >= h * 0.82f &&
                            pb.width() >= w * 0.45f && pb.width() <= w * 1.02f &&
                            pb.height() >= h * 0.025f && pb.height() <= h * 0.13f) {
                        use = new Rect(pb);
                        if (p.isClickable()) {
                            safeRecycle(p);
                            p = null;
                            break;
                        }
                    }
                    AccessibilityNodeInfo next = p.getParent();
                    safeRecycle(p);
                    p = next;
                }

                int score = 0;
                if (use.width() >= w * 0.65f) score += 120;
                if (use.centerY() >= h * 0.90f) score += 80;
                if (n.isClickable()) score += 30;
                if (score > bestScore) {
                    best = new PointHit(use.centerX(), use.centerY());
                    bestScore = score;
                }
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }
        return best;
    }

    /**
     * 像素兜底：不再把所有粉红像素简单做一个大包围盒，
     * 而是寻找底部连续多行、横向覆盖超过一半屏幕的粉红色长按钮。
     * 这样联系人头像上的红色勾不会被误认为发送按钮。
     */
    private static PointHit detectSendButtonByPixels() {
        ScreenCaptureService cap = ScreenCaptureService.getInstance();
        if (cap == null) return null;

        Bitmap img = null;
        try {
            img = cap.captureLatest(1200);
            int w = img.getWidth();
            int h = img.getHeight();

            int x0 = (int) (w * 0.035f);
            int x1 = (int) (w * 0.965f);
            int y0 = (int) (h * 0.80f);
            int y1 = (int) (h * 0.99f);
            int sx = 3;
            int sy = 3;

            int samplesPerRow = Math.max(1, (x1 - x0) / sx);
            int neededHits = (int) (samplesPerRow * 0.50f);

            int bestStart = -1, bestEnd = -1, bestLen = 0;
            int runStart = -1;

            for (int y = y0; y < y1; y += sy) {
                int hits = 0;
                for (int x = x0; x < x1; x += sx) {
                    if (isDouyinPink(img.getPixel(x, y))) hits++;
                }

                if (hits >= neededHits) {
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

            if (runStart >= 0) {
                int len = y1 - runStart;
                if (len > bestLen) {
                    bestLen = len;
                    bestStart = runStart;
                    bestEnd = y1 - sy;
                }
            }

            if (bestStart < 0 || bestLen < h * 0.018f) return null;

            int midY = (bestStart + bestEnd) / 2;
            int minX = w, maxX = -1;
            for (int x = x0; x < x1; x += 2) {
                if (isDouyinPink(img.getPixel(x, midY))) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }

            if (maxX <= minX || maxX - minX < w * 0.52f) return null;
            return new PointHit((minX + maxX) / 2, midY);

        } catch (Throwable ignored) {
            return null;
        } finally {
            if (img != null && !img.isRecycled()) img.recycle();
        }
    }

    private static boolean isDouyinPink(int c) {
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);
        return r >= 175 && g <= 155 && b <= 185 &&
                r - g >= 45 && r - b >= 20;
    }

    private static boolean waitPanelClosedStable(
            TapAccessibilityService service,
            AtomicBoolean running,
            long timeoutMs) {

        long end = SystemClock.uptimeMillis() + timeoutMs;
        int closedCount = 0;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            if (isSharePanelOpenNow(service)) {
                closedCount = 0;
            } else {
                closedCount++;
                if (closedCount >= 3) return true;
            }
            SystemClock.sleep(120);
        }
        return false;
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

    /**
     * panelMayBeOpen=true 时只收键盘，不额外按第二次返回；避免把已经打开的分享面板顺手关掉。
     */
    private static boolean dismissKeyboardIfVisible(
            TapAccessibilityService service,
            AtomicBoolean running,
            boolean panelMayBeOpen) {

        for (int i = 0; i < 3; i++) {
            if (!isKeyboardVisible(service)) return true;
            if (running != null && !running.get()) return false;
            TapAccessibilityService.setOverlayStatus("键盘弹出来了｜正在收起…");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(300);
        }
        return !isKeyboardVisible(service);
    }

    private static void failAndRecover(
            TapAccessibilityService service,
            AtomicBoolean running,
            String message) {
        TapAccessibilityService.setOverlayStatus(message);
        try {
            dismissKeyboardIfVisible(service, running, true);
            if (isSharePanelOpenNow(service)) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                SystemClock.sleep(260);
            }
        } catch (Throwable ignored) {}
    }

    private static void addCandidate(
            List<ShareCandidate> out,
            AccessibilityNodeInfo node,
            Rect bounds,
            int score) {

        for (ShareCandidate e : out) {
            if (Math.abs(e.bounds.centerX() - bounds.centerX()) <= 10 &&
                    Math.abs(e.bounds.centerY() - bounds.centerY()) <= 10 &&
                    Math.abs(e.bounds.width() - bounds.width()) <= 16 &&
                    Math.abs(e.bounds.height() - bounds.height()) <= 16) {
                if (score > e.score) e.score = score;
                return;
            }
        }
        out.add(new ShareCandidate(AccessibilityNodeInfo.obtain(node), new Rect(bounds), score));
    }

    private static void recycleCandidates(List<ShareCandidate> list) {
        for (ShareCandidate c : list) safeRecycle(c.node);
    }

    private static boolean tap(
            TapAccessibilityService service,
            float x,
            float y,
            long duration) {
        return gesture(service, x, y, x, y, duration);
    }

    private static boolean gesture(
            TapAccessibilityService service,
            float x1,
            float y1,
            float x2,
            float y2,
            long duration) {

        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(45, duration));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};

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

    private static void safeRecycle(AccessibilityNodeInfo n) {
        if (n != null) {
            try { n.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static final class ShareCandidate {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        int score;

        ShareCandidate(AccessibilityNodeInfo node, Rect bounds, int score) {
            this.node = node;
            this.bounds = bounds;
            this.score = score;
        }
    }

    private static final class PanelInfo {
        final boolean open;
        final Rect title;
        final Rect contactRow;

        PanelInfo(boolean open, Rect title, Rect contactRow) {
            this.open = open;
            this.title = title;
            this.contactRow = contactRow;
        }
    }

    private static final class NodeHit {
        final Rect bounds;
        final boolean selected;

        NodeHit(Rect bounds, boolean selected) {
            this.bounds = bounds;
            this.selected = selected;
        }
    }

    private static final class ContactHit {
        final boolean found;
        final boolean ambiguous;
        boolean selected;
        final Rect bounds;

        ContactHit(boolean found, boolean ambiguous, boolean selected, Rect bounds) {
            this.found = found;
            this.ambiguous = ambiguous;
            this.selected = selected;
            this.bounds = bounds;
        }

        static ContactHit notFound() {
            return new ContactHit(false, false, false, new Rect());
        }

        static ContactHit ambiguous() {
            return new ContactHit(false, true, false, new Rect());
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
