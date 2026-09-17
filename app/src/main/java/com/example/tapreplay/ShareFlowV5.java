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
 * V0.7 稳定优先分享状态机。
 *
 * 设计原则：
 * 1. 只点右侧操作栏高可信“分享”按钮；
 * 2. 点击后必须精确看到“分享给”才继续；
 * 3. 只扫描当前可见联系人，绝不自动横滑联系人栏；
 * 4. 目标昵称必须精确匹配；目标是否已选只看目标自身状态，不拿“发送”节点代替；
 * 5. 未选中时目标最多点击一次，绝不补点，避免把选中状态再次取消；
 * 6. 发送按钮只用内存截图识别已经亮起的大面积抖音红色按钮；
 * 7. 发送只点一次；失败后保留分享面板，便于直接看现场和日志；
 * 8. 日志固定两行，适配悬浮条，不再出现第三行被截断。
 */
public final class ShareFlowV5 {
    private ShareFlowV5() {}

    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final int OPEN_ATTEMPTS = 3;
    private static final int TARGET_SCANS = 3;

    public static boolean shareToTarget(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running) throws Exception {

        if (service == null || running == null || !running.get()) return false;
        target = target == null ? "" : target.trim();
        if (target.isEmpty()) return false;

        final long started = SystemClock.uptimeMillis();

        if (!dismissKeyboardIfVisible(service, running)) {
            fail(started, "失败0｜键盘没收起", "还没开始点分享");
            return false;
        }

        log(started, "①找分享｜看右侧按钮", "只认高可信分享按钮");
        if (!openSharePanel(service, running, started)) {
            fail(started, "失败①｜分享栏没打开", "没继续乱点");
            return false;
        }

        PanelRead panel = waitPanel(service, running, 650);
        if (panel == null) {
            fail(started, "失败②｜分享栏读不到", "刚打开后界面发生变化");
            return false;
        }
        log(started, "②分享栏｜已确认“分享给”", "联系人区域已就绪");
        panel.recycle();

        ContactHit hit = null;
        for (int i = 1; i <= TARGET_SCANS && running.get(); i++) {
            hit = scanTarget(service, target);
            if (hit != null && (hit.found || hit.ambiguous)) break;
            int count = hit == null ? 0 : hit.visibleCount;
            log(started, "③找好友｜原地扫描" + i + "/" + TARGET_SCANS,
                    "看到" + count + "个名字｜不滑联系人栏");
            SystemClock.sleep(110);
        }

        if (!running.get()) return false;
        if (hit == null || !hit.found) {
            int count = hit == null ? 0 : hit.visibleCount;
            fail(started, "失败③｜当前页没目标", "看到" + count + "个名字｜联系人栏未移动");
            return false;
        }
        if (hit.ambiguous) {
            fail(started, "失败③｜有同名对象", "为避免误发，没有继续点");
            return false;
        }

        log(started, "③找好友｜已找到", "精确匹配“" + compactName(target) + "”");

        if (hit.selected) {
            log(started, "④点好友｜已经选中", "不重复点击，直接等发送");
        } else {
            log(started, "④点好友｜只点一次", "坐标来自目标联系人本身");
            if (!tap(service, hit.tapX, hit.tapY, 65)) {
                fail(started, "失败④｜好友没点上", "点击手势没有完成");
                return false;
            }
            SystemClock.sleep(130);

            if (isKeyboardVisible(service)) {
                dismissKeyboardIfVisible(service, running);
                fail(started, "失败④｜点后弹出键盘", "已收起键盘，不再继续");
                return false;
            }

            if (!isSharePanelOpenNow(service)) {
                fail(started, "失败④｜点后页面跑偏", "分享栏已经不在");
                return false;
            }
        }

        log(started, "⑤找发送｜等红色按钮亮", "只认底部大面积红色按钮");
        PointHit send = waitActiveSendButton(service, running, started, 1350);
        if (send == null) {
            fail(started, "失败⑤｜发送没亮", "不会再点好友，现场已保留");
            return false;
        }

        if (!running.get()) return false;
        log(started, "⑥发送｜红色按钮已找到", "只发送一次");
        if (!tap(service, send.x, send.y, 65)) {
            fail(started, "失败⑥｜发送没点上", "点击手势没有完成");
            return false;
        }

        if (waitPanelClosedStable(service, running, 1900)) {
            log(started, "分享完成✓", "分享栏已收起");
            SystemClock.sleep(280);
            return true;
        }

        // 不自动补点发送，避免页面响应慢时产生重复分享。
        fail(started, "失败⑥｜发送后栏还在", "不重复发送，请看当前页面");
        return false;
    }

    private static boolean openSharePanel(
            TapAccessibilityService service,
            AtomicBoolean running,
            long started) {

        if (isSharePanelOpenNow(service)) return true;

        for (int attempt = 1; attempt <= OPEN_ATTEMPTS && running.get(); attempt++) {
            if (isKeyboardVisible(service) && !dismissKeyboardIfVisible(service, running)) return false;

            AccessibilityNodeInfo root = getVideoRoot(service);
            if (root == null) {
                log(started, "①找分享｜第" + attempt + "/" + OPEN_ATTEMPTS + "次", "视频页还没读到");
                SystemClock.sleep(120);
                continue;
            }

            List<ShareCandidate> candidates = findShareCandidates(service, root);
            safeRecycle(root);

            if (candidates.isEmpty()) {
                log(started, "①找分享｜第" + attempt + "/" + OPEN_ATTEMPTS + "次", "右侧候选0个");
                SystemClock.sleep(130);
                continue;
            }

            ShareCandidate best = candidates.get(0);
            log(started, "①点分享｜第" + attempt + "/" + OPEN_ATTEMPTS + "次",
                    "候选" + candidates.size() + "个｜只点最可信1个");
            boolean tapped = tap(service, best.bounds.centerX(), best.bounds.centerY(), 65);
            recycleCandidates(candidates);
            if (!tapped) {
                SystemClock.sleep(100);
                continue;
            }

            long end = SystemClock.uptimeMillis() + 850;
            while (SystemClock.uptimeMillis() < end && running.get()) {
                if (isSharePanelOpenNow(service)) return true;
                if (isKeyboardVisible(service)) {
                    log(started, "①点错了｜键盘弹出", "已收起，重新找右侧分享");
                    dismissKeyboardIfVisible(service, running);
                    break;
                }
                SystemClock.sleep(65);
            }
        }
        return false;
    }

    /**
     * 只认两类右侧候选：
     * 1. content-desc 类似“分享11.1万，按钮”；
     * 2. 右侧小区域里文字/描述恰好等于“分享”。
     * 不再单凭 resource-id 含 share 就点击。
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
            String cls = normalize(text(n.getClassName()));

            boolean forbidden = isForbiddenShareText(tx) || isForbiddenShareText(ds) ||
                    cls.contains("edittext") || cls.contains("textfield");
            boolean rightRail = b.centerX() >= w * 0.80f &&
                    b.centerY() >= h * 0.18f && b.centerY() <= h * 0.90f;
            boolean compact = b.width() > 0 && b.height() > 0 &&
                    b.width() <= w * 0.24f && b.height() <= h * 0.16f;
            boolean strongDesc = ds.startsWith("分享") && ds.contains("按钮") && !isForbiddenShareText(ds);
            boolean exact = "分享".equals(tx) || "分享".equals(ds);

            if (!forbidden && n.isVisibleToUser() && n.isEnabled() && rightRail && compact &&
                    (strongDesc || exact)) {
                int score = 0;
                if (strongDesc) score += 700;
                if (exact) score += 220;
                if (n.isClickable()) score += 90;
                if (b.centerX() >= w * 0.86f) score += 90;
                addShareCandidate(out, n, b, score);

                // 真正可点击区域可能是文字节点的父容器，只向上看两层。
                AccessibilityNodeInfo p = n.getParent();
                for (int depth = 0; p != null && depth < 2; depth++) {
                    Rect pb = new Rect();
                    p.getBoundsInScreen(pb);
                    boolean parentOk = p.isVisibleToUser() && p.isEnabled() &&
                            pb.centerX() >= w * 0.80f &&
                            pb.centerY() >= h * 0.18f && pb.centerY() <= h * 0.90f &&
                            pb.width() > 0 && pb.height() > 0 &&
                            pb.width() <= w * 0.27f && pb.height() <= h * 0.17f;
                    if (parentOk) {
                        addShareCandidate(out, p, pb,
                                score + (p.isClickable() ? 140 : 15) - depth * 10);
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

    /** 只扫描当前可见联系人，不产生任何横向手势。 */
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

            if (inZone && !tx.isEmpty() && tx.length() <= 24 && !isUiWord(tx)) {
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
            // 昵称通常在头像下方；没有可点击父节点时，仅把 Y 抬到联系人头像区域。
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

    private static PanelRead readPanel(TapAccessibilityService service) {
        List<AccessibilityNodeInfo> roots = getDouyinRoots(service);
        if (roots.isEmpty()) return null;

        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        PanelRead best = null;

        for (AccessibilityNodeInfo root : roots) {
            PanelInfo info = inspectPanel(root, w, h);
            if (info.open && (best == null || info.score > best.info.score)) {
                if (best != null) best.recycle();
                best = new PanelRead(AccessibilityNodeInfo.obtain(root), info);
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
            SystemClock.sleep(65);
        }
        return null;
    }

    private static boolean isSharePanelOpenNow(TapAccessibilityService service) {
        PanelRead p = readPanel(service);
        if (p == null) return false;
        p.recycle();
        return true;
    }

    /**
     * 稳定版只把“精确出现 分享给”作为分享面板成立条件。
     * 不再通过“关闭 + 输入框”等弱特征猜测，避免把别的底部弹层当成分享栏。
     */
    private static PanelInfo inspectPanel(AccessibilityNodeInfo root, int w, int h) {
        Rect title = null;
        int composerTop = Integer.MAX_VALUE;

        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(root));

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String tx = normalize(text(n.getText()));
            String ds = normalize(text(n.getContentDescription()));
            String cls = normalize(text(n.getClassName()));

            if (("分享给".equals(tx) || "分享给".equals(ds)) && b.centerY() >= h * 0.45f) {
                if (title == null || b.top < title.top) title = new Rect(b);
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

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            safeRecycle(n);
        }

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

        Rect zone = new Rect(0, top, w, Math.max(top + 1, bottom));
        int score = 1000 + (composerTop != Integer.MAX_VALUE ? 200 : 0);
        return new PanelInfo(true, zone, score);
    }

    private static AccessibilityNodeInfo getVideoRoot(TapAccessibilityService service) {
        List<AccessibilityNodeInfo> roots = getDouyinRoots(service);
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MIN_VALUE;

        for (AccessibilityNodeInfo root : roots) {
            Rect b = new Rect();
            root.getBoundsInScreen(b);
            long area = (long) Math.max(0, b.width()) * Math.max(0, b.height());
            if (area > bestArea) {
                safeRecycle(best);
                best = AccessibilityNodeInfo.obtain(root);
                bestArea = area;
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

    private static PointHit waitActiveSendButton(
            TapAccessibilityService service,
            AtomicBoolean running,
            long started,
            long timeoutMs) {

        long end = SystemClock.uptimeMillis() + timeoutMs;
        int poll = 0;
        while (SystemClock.uptimeMillis() < end && running.get()) {
            if (!isSharePanelOpenNow(service)) return null;
            poll++;
            PointHit hit = detectActiveSendButtonByPixels();
            if (hit != null) return hit;
            if (poll == 4 || poll == 8) {
                log(started, "⑤找发送｜还没亮", "继续等红色大按钮");
            }
            SystemClock.sleep(105);
        }
        return null;
    }

    /**
     * 只识别底部已经变成抖音红/粉色的大面积发送按钮。
     * 不使用 Accessibility 的“发送”文本来判断是否可发送，避免灰色/不可用发送节点误判。
     */
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
            int currentStart = -1;
            int bestSegStart = -1;
            int bestSegEnd = -1;
            int bestSegLen = 0;

            // 取“连续”的最长粉红横段，防止头像红勾等零散红色干扰。
            for (int x = x0; x < x1; x += 2) {
                if (isDouyinPink(img.getPixel(x, midY))) {
                    if (currentStart < 0) currentStart = x;
                } else if (currentStart >= 0) {
                    int len = x - currentStart;
                    if (len > bestSegLen) {
                        bestSegLen = len;
                        bestSegStart = currentStart;
                        bestSegEnd = x - 2;
                    }
                    currentStart = -1;
                }
            }
            if (currentStart >= 0) {
                int len = x1 - currentStart;
                if (len > bestSegLen) {
                    bestSegLen = len;
                    bestSegStart = currentStart;
                    bestSegEnd = x1 - 2;
                }
            }

            if (bestSegStart >= 0) {
                minX = bestSegStart;
                maxX = bestSegEnd;
            }
            if (maxX <= minX || maxX - minX < w * 0.50f) return null;

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
            SystemClock.sleep(95);
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
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(220);
        }
        return !isKeyboardVisible(service);
    }

    private static void log(long started, String stage, String detail) {
        double sec = (SystemClock.uptimeMillis() - started) / 1000.0;
        TapAccessibilityService.setOverlayStatus(
                stage + "\n" + detail + "｜" + String.format(Locale.US, "%.1fs", sec));
    }

    private static void fail(long started, String stage, String detail) {
        log(started, stage, detail);
    }

    private static String compactName(String name) {
        if (name == null) return "";
        String s = name.trim();
        return s.length() <= 10 ? s : s.substring(0, 10) + "…";
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
        Path path = new Path();
        path.moveTo(x, y);
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
            latch.await(duration + 750, TimeUnit.MILLISECONDS);
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

    private static void safeRecycle(AccessibilityNodeInfo node) {
        if (node != null) {
            try { node.recycle(); } catch (Throwable ignored) {}
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
        final Rect contactZone;
        final int score;
        PanelInfo(boolean open, Rect contactZone, int score) {
            this.open = open;
            this.contactZone = contactZone;
            this.score = score;
        }
        static PanelInfo closed() { return new PanelInfo(false, null, 0); }
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
