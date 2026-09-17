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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V0.6 快速分享状态机。
 *
 * 重点：
 * 1. 视频页和分享弹层分开找窗口，不再只拿“面积最大的抖音窗口”；
 * 2. 分享按钮只认右侧栏，正常路径只点一次并快速确认；
 * 3. 联系人先原地连续扫描，再先向右复位联系人栏，最后才向左翻页；
 * 4. 联系人匹配区域从“分享给”标题一直到“分享此刻想法”输入框上沿，昵称不会因落在头像栏下方而漏掉；
 * 5. 发送按钮已亮时不再重复点联系人，避免把已选中的目标取消；
 * 6. 日志固定为 分享①~⑥，每一步带次数/数量/耗时，失败时保留最后一步，不被外层覆盖。
 */
public final class ShareFlowV4 {
    private ShareFlowV4() {}

    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";

    private static final int OPEN_ATTEMPTS = 3;
    private static final int STILL_SCANS = 3;
    private static final int RESET_SWIPES = 2;
    private static final int FORWARD_PAGES = 4;

    public static boolean shareToTarget(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running) throws Exception {

        if (service == null || running == null || !running.get()) return false;
        target = target == null ? "" : target.trim();
        if (target.isEmpty()) return false;

        final long started = SystemClock.uptimeMillis();

        if (!dismissKeyboardIfVisible(service, running)) {
            fail(started, "分享失败｜键盘没收起来", "还没开始点分享");
            return false;
        }

        log(started, "分享① 找右侧按钮", "正在看当前视频页");
        if (!openSharePanelFast(service, running, started)) {
            recover(service, running);
            fail(started, "分享失败｜卡在①", "右侧分享按钮没能打开分享栏");
            return false;
        }

        PanelRead panel = waitPanel(service, running, 700);
        if (panel == null) {
            recover(service, running);
            fail(started, "分享失败｜卡在②", "分享栏出现后又没读到");
            return false;
        }
        log(started, "分享② 分享栏已打开", panel.info.contactZone != null ? "标题✓｜联系人区域✓" : "标题✓｜联系人区域没认出");
        panel.recycle();

        ContactHit targetHit = findTargetWithRecovery(service, target, running, started);
        if (targetHit == null || !targetHit.found) {
            recover(service, running);
            fail(started, "分享失败｜卡在③", "联系人栏里没找到目标");
            return false;
        }
        if (targetHit.ambiguous) {
            recover(service, running);
            fail(started, "分享失败｜卡在③", "出现多个同名目标，没继续点");
            return false;
        }

        if (!running.get()) return false;

        // 如果底部“发送”已经是可用的红色按钮，说明当前已有收件人处于选中状态。
        // 此时目标昵称也已经精确命中，优先按“目标已选”处理，避免再点一下把它取消。
        PointHit send = findSendButton(service, true);
        if (targetHit.selected || send != null) {
            log(started, "分享④ 目标已选", send != null ? "发送按钮已经亮了，不再重复点好友" : "界面显示目标已选中");
        } else {
            log(started, "分享④ 点分享对象", "已精确找到目标｜正在点一次");
            if (!tap(service, targetHit.tapX, targetHit.tapY, 65)) {
                recover(service, running);
                fail(started, "分享失败｜卡在④", "目标找到了，但点击手势没完成");
                return false;
            }

            // 正常情况下 300~900ms 内发送按钮就会出现。
            send = waitSendButton(service, running, started, 1000);
            if (send == null) {
                // 点击有时会被动画吃掉。重新读取目标，只允许再补点一次。
                ContactHit retry = scanTarget(service, target);
                if (retry != null && retry.found && !retry.ambiguous && running.get()) {
                    log(started, "分享④ 再点一次目标", "第一次点后发送按钮没亮");
                    tap(service, retry.tapX, retry.tapY, 65);
                    send = waitSendButton(service, running, started, 850);
                }
            }

            if (send == null) {
                recover(service, running);
                fail(started, "分享失败｜卡在④", "点了目标，但发送按钮一直没亮");
                return false;
            }
        }

        if (send == null) {
            log(started, "分享⑤ 找发送按钮", "目标已选｜正在找底部红色发送");
            send = waitSendButton(service, running, started, 850);
        }
        if (send == null) {
            recover(service, running);
            fail(started, "分享失败｜卡在⑤", "底部发送按钮没找到");
            return false;
        }

        if (!running.get()) return false;

        log(started, "分享⑥ 正在发送", "找到红色发送按钮｜正在点");
        if (!tap(service, send.x, send.y, 65)) {
            recover(service, running);
            fail(started, "分享失败｜卡在⑥", "发送按钮找到了，但点击手势没完成");
            return false;
        }

        if (waitPanelClosedStable(service, running, 1800)) {
            log(started, "分享完成 ✓", "分享栏已经收起");
            SystemClock.sleep(320);
            return true;
        }

        // 偶发第一次发送点击被动画吃掉，只补点一次，不长时间死等。
        if (running.get() && isSharePanelOpenNow(service)) {
            PointHit retrySend = findSendButton(service, true);
            if (retrySend != null) {
                log(started, "分享⑥ 再点一次发送", "第一次点后分享栏还在");
                tap(service, retrySend.x, retrySend.y, 65);
                if (waitPanelClosedStable(service, running, 1200)) {
                    log(started, "分享完成 ✓", "第二次点击后分享栏已收起");
                    SystemClock.sleep(320);
                    return true;
                }
            }
        }

        recover(service, running);
        fail(started, "分享失败｜卡在⑥", "点发送后分享栏没有收起");
        return false;
    }

    private static boolean openSharePanelFast(
            TapAccessibilityService service,
            AtomicBoolean running,
            long started) {

        if (isSharePanelOpenNow(service)) return true;

        for (int attempt = 1; attempt <= OPEN_ATTEMPTS && running.get(); attempt++) {
            if (isKeyboardVisible(service)) dismissKeyboardIfVisible(service, running);

            AccessibilityNodeInfo videoRoot = getVideoRoot(service);
            if (videoRoot == null) {
                log(started, "分享① 找右侧按钮", "视频页还没读到｜第" + attempt + "/" + OPEN_ATTEMPTS + "次");
                SystemClock.sleep(120);
                continue;
            }

            List<ShareCandidate> candidates = findShareCandidates(service, videoRoot);
            safeRecycle(videoRoot);

            if (candidates.isEmpty()) {
                log(started, "分享① 找右侧按钮", "候选0个｜第" + attempt + "/" + OPEN_ATTEMPTS + "次");
                SystemClock.sleep(140);
                continue;
            }

            ShareCandidate best = candidates.get(0);
            log(started, "分享① 点右侧按钮", "候选" + candidates.size() + "个｜第" + attempt + "/" + OPEN_ATTEMPTS + "次");
            tap(service, best.bounds.centerX(), best.bounds.centerY(), 65);
            recycleCandidates(candidates);

            long end = SystemClock.uptimeMillis() + 950;
            while (SystemClock.uptimeMillis() < end && running.get()) {
                if (isSharePanelOpenNow(service)) return true;
                if (isKeyboardVisible(service)) {
                    log(started, "分享① 点错输入区", "键盘弹了｜正在恢复");
                    dismissKeyboardIfVisible(service, running);
                    break;
                }
                SystemClock.sleep(70);
            }
        }
        return false;
    }

    /** 右侧分享按钮只在视频主窗口里找。 */
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

            boolean forbidden = isForbiddenShareText(tx) || isForbiddenShareText(ds) ||
                    cls.contains("edittext") || cls.contains("textfield");
            boolean rightRail = b.centerX() >= w * 0.78f &&
                    b.centerY() >= h * 0.18f && b.centerY() <= h * 0.89f;
            boolean compact = b.width() > 0 && b.height() > 0 &&
                    b.width() <= w * 0.24f && b.height() <= h * 0.16f;

            boolean strongDesc = ds.startsWith("分享") && ds.contains("按钮") && !isForbiddenShareText(ds);
            boolean exact = "分享".equals(tx) || "分享".equals(ds);
            boolean shareId = id.contains("share") || id.contains("fenxiang");

            if (!forbidden && n.isVisibleToUser() && n.isEnabled() && rightRail && compact &&
                    (strongDesc || exact || shareId)) {
                int score = 0;
                if (strongDesc) score += 600;
                if (exact) score += 220;
                if (shareId) score += 120;
                if (n.isClickable()) score += 90;
                if (b.centerX() >= w * 0.84f) score += 80;
                addShareCandidate(out, n, b, score);

                // 只向上找两层小型右侧容器，避免把整块页面当成候选。
                AccessibilityNodeInfo p = n.getParent();
                for (int depth = 0; p != null && depth < 2; depth++) {
                    Rect pb = new Rect();
                    p.getBoundsInScreen(pb);
                    boolean ok = p.isVisibleToUser() && p.isEnabled() &&
                            pb.centerX() >= w * 0.78f &&
                            pb.centerY() >= h * 0.18f && pb.centerY() <= h * 0.89f &&
                            pb.width() > 0 && pb.height() > 0 &&
                            pb.width() <= w * 0.26f && pb.height() <= h * 0.17f;
                    if (ok) {
                        addShareCandidate(out, p, pb,
                                score + (p.isClickable() ? 130 : 20) - depth * 10);
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

    /**
     * 先在当前联系人栏原地扫三次；仍未看到目标时，先向右把横条拉回开头；
     * 最后才向左逐页找。这样即使上一次失败把联系人滑走，也会先恢复，而不是越滑越远。
     */
    private static ContactHit findTargetWithRecovery(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running,
            long started) {

        ContactHit last = null;
        for (int i = 1; i <= STILL_SCANS && running.get(); i++) {
            last = scanTarget(service, target);
            if (last != null && (last.found || last.ambiguous)) return last;
            int count = last == null ? 0 : last.visibleCount;
            log(started, "分享③ 找分享对象", "原地第" + i + "/" + STILL_SCANS + "次｜看到" + count + "个名字｜目标没出现");
            SystemClock.sleep(120);
        }

        String previousFingerprint = last == null ? "" : last.fingerprint;

        // 先向右复位，解决“目标本来在前面，却被上一轮横滑滑没了”。
        for (int i = 1; i <= RESET_SWIPES && running.get(); i++) {
            PanelRead panel = readPanel(service);
            if (panel == null || panel.info.scrollRow == null) {
                if (panel != null) panel.recycle();
                break;
            }
            Rect row = panel.info.scrollRow;
            panel.recycle();

            log(started, "分享③ 恢复联系人栏", "先往回拉｜第" + i + "/" + RESET_SWIPES + "次");
            if (!swipeContactRow(service, row, true)) break;
            SystemClock.sleep(260);

            last = scanTarget(service, target);
            if (last != null && (last.found || last.ambiguous)) return last;
            String fp = last == null ? "" : last.fingerprint;
            if (!fp.isEmpty() && fp.equals(previousFingerprint)) break;
            previousFingerprint = fp;
        }

        int samePageCount = 0;
        for (int page = 1; page <= FORWARD_PAGES && running.get(); page++) {
            PanelRead panel = readPanel(service);
            if (panel == null || panel.info.scrollRow == null) {
                if (panel != null) panel.recycle();
                break;
            }
            Rect row = panel.info.scrollRow;
            panel.recycle();

            log(started, "分享③ 往后找对象", "联系人栏第" + page + "/" + FORWARD_PAGES + "页");
            if (!swipeContactRow(service, row, false)) break;
            SystemClock.sleep(280);

            last = scanTarget(service, target);
            if (last != null && (last.found || last.ambiguous)) return last;

            String fp = last == null ? "" : last.fingerprint;
            if (!fp.isEmpty() && fp.equals(previousFingerprint)) {
                samePageCount++;
                if (samePageCount >= 1) break;
            } else {
                samePageCount = 0;
            }
            previousFingerprint = fp;
        }
        return last == null ? ContactHit.notFound() : last;
    }

    private static boolean swipeContactRow(TapAccessibilityService service, Rect row, boolean toBeginning) {
        if (row == null || row.width() <= 0 || row.height() <= 0) return false;
        float y = row.top + row.height() * 0.42f;
        float left = row.left + row.width() * 0.18f;
        float right = row.right - row.width() * 0.12f;
        if (toBeginning) {
            return gesture(service, left, y, right, y, 280);
        } else {
            return gesture(service, right, y, left, y, 280);
        }
    }

    private static ContactHit scanTarget(TapAccessibilityService service, String target) {
        PanelRead panel = readPanel(service);
        if (panel == null) return ContactHit.notFound();
        try {
            if (panel.info.contactZone == null) return ContactHit.notFound();
            return findTargetContact(service, panel.root, panel.info, target);
        } finally {
            panel.recycle();
        }
    }

    /**
     * 精确匹配目标昵称，但匹配范围使用完整联系人区域：标题下方 -> 输入框上沿。
     * 这样昵称文字即使在 RecyclerView 头像区域的下沿之外，也不会被漏掉。
     */
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
                    b.centerX() >= -w * 0.05f && b.centerX() <= w * 1.05f;

            if (inZone && !tx.isEmpty() && tx.length() <= 24 && !isUiWord(tx)) {
                visibleLabels.add(tx);
            }

            boolean exact = wanted.equals(tx) || wanted.equals(ds);
            if (exact && inZone && !cls.contains("edittext") && n.isVisibleToUser()) {
                ContactTap tap = bestContactTap(n, zone, w, h);
                boolean selected = tap.selected || n.isSelected() || n.isChecked() ||
                        containsSelectedWord(tx) || containsSelectedWord(ds);
                addContactHit(hits, new ContactHit(
                        true, false, selected, tap.x, tap.y, 0, ""));
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }

        ArrayList<String> sorted = new ArrayList<>(visibleLabels);
        Collections.sort(sorted);
        StringBuilder fp = new StringBuilder();
        for (String s : sorted) fp.append(s).append('|');

        if (hits.isEmpty()) {
            return new ContactHit(false, false, false, 0, 0, visibleLabels.size(), fp.toString());
        }
        if (hits.size() > 1) {
            return new ContactHit(false, true, false, 0, 0, visibleLabels.size(), fp.toString());
        }

        ContactHit one = hits.get(0);
        one.visibleCount = visibleLabels.size();
        one.fingerprint = fp.toString();
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
                    b.width() <= w * 0.32f && b.height() <= Math.max(zone.height() * 1.15f, h * 0.08f) &&
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
            // 昵称本身通常在头像下方。没有可点击父节点时，把点击点抬到头像中心附近。
            float y = zone.top + zone.height() * 0.38f;
            return new ContactTap(fallback.x, y, fallback.selected);
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

    /**
     * 分享弹层有时是单独的 AccessibilityWindow。
     * 这里扫描所有抖音窗口，优先选择真正含“分享给”的那个，而不是永远选最大窗口。
     */
    private static PanelRead readPanel(TapAccessibilityService service) {
        List<AccessibilityNodeInfo> roots = getDouyinRoots(service);
        if (roots.isEmpty()) return null;

        PanelRead best = null;
        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;

        for (AccessibilityNodeInfo root : roots) {
            PanelInfo info = inspectPanel(root, w, h);
            if (info.open) {
                if (best == null || info.score > best.info.score) {
                    if (best != null) best.recycle();
                    best = new PanelRead(AccessibilityNodeInfo.obtain(root), info);
                }
            }
            safeRecycle(root);
        }
        return best;
    }

    private static PanelRead waitPanel(
            TapAccessibilityService service,
            AtomicBoolean running,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            PanelRead p = readPanel(service);
            if (p != null) return p;
            SystemClock.sleep(70);
        }
        return null;
    }

    private static boolean isSharePanelOpenNow(TapAccessibilityService service) {
        PanelRead p = readPanel(service);
        if (p == null) return false;
        p.recycle();
        return true;
    }

    private static PanelInfo inspectPanel(AccessibilityNodeInfo root, int w, int h) {
        Rect title = null;
        Rect close = null;
        int composerTop = Integer.MAX_VALUE;
        boolean sendSeen = false;
        ArrayList<Rect> recyclerRows = new ArrayList<>();

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

            if (("分享给".equals(tx) || "分享给".equals(ds)) && b.centerY() >= h * 0.45f) {
                if (title == null || b.top < title.top) title = new Rect(b);
            }

            if (("关闭".equals(tx) || "关闭".equals(ds) || "取消".equals(tx) || "取消".equals(ds)) &&
                    b.centerY() >= h * 0.45f) {
                close = new Rect(b);
            }

            if (("发送".equals(tx) || "发送".equals(ds)) && b.centerY() >= h * 0.82f) {
                sendSeen = true;
            }

            boolean composerText = tx.contains("分享此刻想法") || tx.contains("分享此刻的想法") ||
                    tx.contains("分享你的想法") || tx.contains("说点什么") ||
                    ds.contains("分享此刻想法") || ds.contains("分享此刻的想法") ||
                    ds.contains("分享你的想法") || ds.contains("说点什么");
            boolean composerEdit = (cls.contains("edittext") || cls.contains("textfield")) &&
                    b.centerY() >= h * 0.70f;
            if ((composerText || composerEdit) && b.top > h * 0.55f) {
                composerTop = Math.min(composerTop, b.top);
            }

            boolean rowLike = (id.contains("recycler") || n.isScrollable()) &&
                    b.width() >= w * 0.62f &&
                    b.height() >= h * 0.045f && b.height() <= h * 0.24f &&
                    b.top >= h * 0.50f && b.bottom <= h * 0.92f;
            if (rowLike) recyclerRows.add(new Rect(b));

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }

        boolean open = title != null || (close != null && composerTop != Integer.MAX_VALUE);
        if (!open) return PanelInfo.closed();

        int top = title != null ? Math.max(title.bottom, (int) (h * 0.58f)) : (int) (h * 0.62f);
        int bottom;
        if (composerTop != Integer.MAX_VALUE && composerTop > top) {
            bottom = composerTop - Math.max(2, (int) (h * 0.004f));
        } else {
            bottom = Math.min((int) (h * 0.87f), top + (int) (h * 0.22f));
        }
        if (bottom <= top + h * 0.06f) {
            bottom = Math.min((int) (h * 0.87f), top + (int) (h * 0.16f));
        }

        Rect zone = new Rect(0, top, w, Math.max(top + 1, bottom));
        Rect scrollRow = null;
        int bestScore = Integer.MIN_VALUE;
        for (Rect r : recyclerRows) {
            Rect inter = new Rect();
            if (!inter.setIntersect(r, zone)) continue;
            if (inter.height() < h * 0.035f) continue;
            int score = 0;
            if (r.width() >= w * 0.85f) score += 80;
            if (r.top >= top - h * 0.02f && r.bottom <= bottom + h * 0.03f) score += 100;
            score -= (int) Math.abs(r.top - top) / 5;
            if (score > bestScore) {
                scrollRow = new Rect(r);
                bestScore = score;
            }
        }
        if (scrollRow == null) scrollRow = new Rect(zone);

        int score = (title != null ? 1000 : 0) +
                (composerTop != Integer.MAX_VALUE ? 300 : 0) +
                (sendSeen ? 150 : 0) + (close != null ? 80 : 0);
        return new PanelInfo(true, title, zone, scrollRow, score);
    }

    private static AccessibilityNodeInfo getVideoRoot(TapAccessibilityService service) {
        List<AccessibilityNodeInfo> roots = getDouyinRoots(service);
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MIN_VALUE;
        for (AccessibilityNodeInfo root : roots) {
            Rect b = new Rect();
            root.getBoundsInScreen(b);
            long area = (long) Math.max(0, b.width()) * Math.max(0, b.height());
            // 面积大的主窗口优先；分享面板小窗口不会抢过视频页。
            long score = area;
            if (score > bestScore) {
                safeRecycle(best);
                best = AccessibilityNodeInfo.obtain(root);
                bestScore = score;
            }
            safeRecycle(root);
        }
        return best;
    }

    private static List<AccessibilityNodeInfo> getDouyinRoots(TapAccessibilityService service) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        if (root != null && DOUYIN_PACKAGE.equals(text(root.getPackageName()))) {
                            out.add(root);
                            root = null;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        safeRecycle(root);
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (out.isEmpty()) {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null && DOUYIN_PACKAGE.equals(text(root.getPackageName()))) {
                    out.add(root);
                } else {
                    safeRecycle(root);
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static PointHit waitSendButton(
            TapAccessibilityService service,
            AtomicBoolean running,
            long started,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        int poll = 0;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            poll++;
            PointHit hit = findSendButton(service, poll % 2 == 0);
            if (hit != null) return hit;
            if (poll == 1 || poll == 4) {
                log(started, "分享⑤ 找发送按钮", "目标已选｜还没看到红色发送");
            }
            SystemClock.sleep(110);
        }
        return null;
    }

    private static PointHit findSendButton(TapAccessibilityService service, boolean allowPixels) {
        PointHit node = findSendButtonByAccessibility(service);
        if (node != null) return node;
        return allowPixels ? detectSendButtonByPixels() : null;
    }

    private static PointHit findSendButtonByAccessibility(TapAccessibilityService service) {
        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        PointHit best = null;
        int bestScore = Integer.MIN_VALUE;

        List<AccessibilityNodeInfo> roots = getDouyinRoots(service);
        for (AccessibilityNodeInfo root : roots) {
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
                        n.isVisibleToUser() && n.isEnabled() && b.centerY() >= h * 0.84f) {
                    Rect use = new Rect(b);
                    AccessibilityNodeInfo p = n.getParent();
                    for (int depth = 0; p != null && depth < 3; depth++) {
                        Rect pb = new Rect();
                        p.getBoundsInScreen(pb);
                        if (p.isVisibleToUser() && p.isEnabled() &&
                                pb.centerY() >= h * 0.84f &&
                                pb.width() >= w * 0.50f && pb.width() <= w * 1.02f &&
                                pb.height() >= h * 0.02f && pb.height() <= h * 0.14f) {
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
        }
        return best;
    }

    private static PointHit detectSendButtonByPixels() {
        ScreenCaptureService cap = ScreenCaptureService.getInstance();
        if (cap == null) return null;
        Bitmap img = null;
        try {
            img = cap.captureLatest(320);
            int w = img.getWidth();
            int h = img.getHeight();
            int x0 = (int) (w * 0.03f);
            int x1 = (int) (w * 0.97f);
            int y0 = (int) (h * 0.86f);
            int y1 = (int) (h * 0.99f);
            int sx = 4;
            int sy = 3;

            int samples = Math.max(1, (x1 - x0) / sx);
            int needed = (int) (samples * 0.48f);
            int bestStart = -1, bestEnd = -1, bestLen = 0, runStart = -1;

            for (int y = y0; y < y1; y += sy) {
                int hits = 0;
                for (int x = x0; x < x1; x += sx) {
                    if (isDouyinPink(img.getPixel(x, y))) hits++;
                }
                if (hits >= needed) {
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

    private static boolean isDouyinPink(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return r >= 175 && g <= 160 && b <= 190 && r - g >= 42 && r - b >= 18;
    }

    private static boolean waitPanelClosedStable(
            TapAccessibilityService service,
            AtomicBoolean running,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        int closed = 0;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            if (isSharePanelOpenNow(service)) {
                closed = 0;
            } else {
                closed++;
                if (closed >= 2) return true;
            }
            SystemClock.sleep(100);
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

    private static boolean dismissKeyboardIfVisible(
            TapAccessibilityService service,
            AtomicBoolean running) {
        for (int i = 0; i < 2; i++) {
            if (!isKeyboardVisible(service)) return true;
            if (running != null && !running.get()) return false;
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(220);
        }
        return !isKeyboardVisible(service);
    }

    private static void recover(TapAccessibilityService service, AtomicBoolean running) {
        try {
            dismissKeyboardIfVisible(service, running);
            if (isSharePanelOpenNow(service)) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                SystemClock.sleep(180);
            }
        } catch (Throwable ignored) {}
    }

    private static void log(long started, String stage, String detail) {
        double sec = (SystemClock.uptimeMillis() - started) / 1000.0;
        TapAccessibilityService.setOverlayStatus(
                stage + "\n" + detail + "\n分享用时 " + String.format(Locale.US, "%.1fs", sec));
    }

    private static void fail(long started, String stage, String detail) {
        log(started, stage, detail);
    }

    private static void addShareCandidate(
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
            latch.await(duration + 900, TimeUnit.MILLISECONDS);
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
        final Rect title;
        final Rect contactZone;
        final Rect scrollRow;
        final int score;
        PanelInfo(boolean open, Rect title, Rect contactZone, Rect scrollRow, int score) {
            this.open = open;
            this.title = title;
            this.contactZone = contactZone;
            this.scrollRow = scrollRow;
            this.score = score;
        }
        static PanelInfo closed() { return new PanelInfo(false, null, null, null, 0); }
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
        String fingerprint;

        ContactHit(boolean found, boolean ambiguous, boolean selected,
                   float tapX, float tapY, int visibleCount, String fingerprint) {
            this.found = found;
            this.ambiguous = ambiguous;
            this.selected = selected;
            this.tapX = tapX;
            this.tapY = tapY;
            this.visibleCount = visibleCount;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }

        static ContactHit notFound() {
            return new ContactHit(false, false, false, 0, 0, 0, "");
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