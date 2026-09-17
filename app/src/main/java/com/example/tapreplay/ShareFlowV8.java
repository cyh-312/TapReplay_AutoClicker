package com.example.tapreplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V0.7.5 robust share guard around the proven V7 parameter-driven flow.
 *
 * V7 owns exact target matching, one target tap, pixel-confirmed Send and one Send tap.
 * This guard adds cross-video coordinate invalidation, missed-event success reconciliation,
 * and bounded per-video failure recovery without ever swiping videos itself.
 */
public final class ShareFlowV8 {
    private ShareFlowV8() {}

    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final long RECONCILE_WAIT_MS = 750L;
    private static final long PASSIVE_RECOVER_WAIT_MS = 450L;
    private static final long RECOVER_WAIT_MS = 800L;
    private static final int VIDEO_READY_STABLE_COUNT = 2;

    public static boolean shareToTarget(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running) throws Exception {

        if (service == null || running == null || !running.get()) return false;

        // Never reuse a right-rail coordinate across videos. Different Douyin videos can shift
        // 收藏/分享 vertically; stale coordinates were the cause of occasional 收藏 clicks.
        if (!disableV7CoordinateCache()) {
            TraceLogger.critical("SHARE_GUARD", "cannot disable V7 coordinate cache; skip share safely");
            TapAccessibilityService.setOverlayStatus("分享跳过｜坐标保护未就绪");
            return false;
        }

        AttemptSnapshot snapshot = captureAttemptSnapshot(service);
        TraceLogger.critical("SHARE_GUARD",
                "BEGIN v0.7.5 sourceWindowId=" + snapshot.sourceWindowId +
                " dialogBefore=" + snapshot.shareDialogUptimeBefore +
                " clickSeqBefore=" + snapshot.viewClickSeqBefore);

        boolean ok;
        try {
            ok = ShareFlowV7.shareToTarget(service, target, running);
        } catch (Exception e) {
            TraceLogger.critical("SHARE_GUARD", "V7 exception=" + shortThrowable(e));
            ok = false;
        }

        if (ok || !running.get()) return ok;

        // V7 may have shown a failure line because MainActivity's event was missed. Replace that
        // transient line immediately while we perform an independent UI-state reconciliation.
        TapAccessibilityService.setOverlayStatus("⑥发送｜补充确认主界面\n不会重复发送");

        if (reconcilePostSendSuccess(service, running, snapshot)) {
            TraceLogger.critical("POST_SEND_RECONCILE",
                    "success by stable video-page parameters sourceWindowId=" + snapshot.sourceWindowId);
            TapAccessibilityService.setOverlayStatus("分享完成✓\n主界面已恢复（状态补偿）");
            SystemClock.sleep(260L);
            return true;
        }

        boolean recovered = recoverToVideo(service, running);
        TraceLogger.critical("RECOVER", "shareFailed recovered=" + recovered);
        if (running.get()) {
            TapAccessibilityService.setOverlayStatus(
                    recovered
                            ? "分享失败｜已恢复视频页\n继续下一条"
                            : "分享失败｜已执行恢复\n继续下一条");
        }
        return false;
    }

    private static AttemptSnapshot captureAttemptSnapshot(TapAccessibilityService service) {
        return new AttemptSnapshot(
                getActiveDouyinWindowId(service),
                readV7Long("lastShareDialogUptimeMs", -1L),
                readV7AtomicLong("VIEW_CLICK_SEQ", -1L));
    }

    /**
     * Used only after V7 returned false. To avoid treating an early-stage failure as a successful
     * Send, first require evidence that this attempt opened SharePanelDialog and really clicked the
     * target ImageView. Then accept a restored video page independently of windowId:
     *   no visible 分享给 panel + active Douyin root + real right-rail 分享 parameter.
     * Two consecutive observations are required for stability.
     */
    private static boolean reconcilePostSendSuccess(
            TapAccessibilityService service,
            AtomicBoolean running,
            AttemptSnapshot snapshot) {

        if (!running.get()) return false;

        long dialogAfter = readV7Long("lastShareDialogUptimeMs", -1L);
        long clickAfter = readV7AtomicLong("VIEW_CLICK_SEQ", -1L);
        int shareDialogWindowId = readV7Int("lastShareDialogWindowId", -1);
        int lastClickWindowId = readV7Int("lastViewClickWindowId", -1);
        String lastClickClass = normalize(readV7String("lastViewClickClass", ""));

        boolean dialogOpenedThisAttempt = dialogAfter > snapshot.shareDialogUptimeBefore;
        boolean targetClickThisAttempt = clickAfter > snapshot.viewClickSeqBefore &&
                shareDialogWindowId >= 0 &&
                lastClickWindowId == shareDialogWindowId &&
                lastClickClass.contains("imageview");

        TraceLogger.log("POST_SEND_RECONCILE",
                "evidence dialogOpened=" + dialogOpenedThisAttempt +
                " targetClick=" + targetClickThisAttempt +
                " sourceWindowId=" + snapshot.sourceWindowId +
                " dialogWindowId=" + shareDialogWindowId +
                " lastClickWindowId=" + lastClickWindowId);

        if (!dialogOpenedThisAttempt || !targetClickThisAttempt) return false;
        return waitForStableVideoPage(service, running, RECONCILE_WAIT_MS, "POST_SEND_RECONCILE");
    }

    /**
     * A real share failure is per-video, not fatal to the automation. Recovery never sends again
     * and never swipes. It first waits passively because Douyin may still be finishing a close
     * animation, then uses bounded Back/outside-tap fallbacks only while share UI remains.
     */
    private static boolean recoverToVideo(
            TapAccessibilityService service,
            AtomicBoolean running) {

        if (!running.get()) return false;
        TraceLogger.critical("RECOVER", "begin");

        // Late MainActivity/UI close: do nothing while Douyin finishes by itself.
        if (waitForStableVideoPage(service, running, PASSIVE_RECOVER_WAIT_MS, "RECOVER_PASSIVE")) {
            TraceLogger.log("RECOVER", "video page restored passively");
            return true;
        }

        if (isKeyboardVisible(service) && running.get()) {
            TraceLogger.log("RECOVER", "keyboard visible -> BACK");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            if (waitForStableVideoPage(service, running, RECOVER_WAIT_MS, "RECOVER_KEYBOARD")) {
                SystemClock.sleep(160L);
                return true;
            }
        }

        boolean panelVisible = isSharePanelVisibleAnyWindow(service);
        if (panelVisible && running.get()) {
            TraceLogger.log("RECOVER", "share panel visible -> BACK");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            if (waitForStableVideoPage(service, running, RECOVER_WAIT_MS, "RECOVER_BACK1")) {
                SystemClock.sleep(160L);
                return true;
            }
        }

        if (!running.get()) return false;

        // Some bottom sheets ignore Back while animating. Only tap outside when 分享给 is still
        // positively visible; never blind-tap an already restored video page.
        if (isSharePanelVisibleAnyWindow(service)) {
            int w = service.getResources().getDisplayMetrics().widthPixels;
            int h = service.getResources().getDisplayMetrics().heightPixels;
            float x = w * 0.50f;
            float y = h * 0.34f;
            TraceLogger.log("RECOVER", "panel still visible -> outside tap x=" +
                    Math.round(x) + " y=" + Math.round(y));
            tap(service, x, y, 55L, "recover-outside");
            if (waitForStableVideoPage(service, running, RECOVER_WAIT_MS, "RECOVER_OUTSIDE")) {
                SystemClock.sleep(160L);
                return true;
            }
        }

        if (!running.get()) return false;

        // One final bounded Back only if the share panel is still demonstrably present.
        if (isSharePanelVisibleAnyWindow(service)) {
            TraceLogger.log("RECOVER", "panel remains -> final BACK");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            if (waitForStableVideoPage(service, running, RECOVER_WAIT_MS, "RECOVER_BACK2")) {
                SystemClock.sleep(160L);
                return true;
            }
        }

        return isVideoPageReady(service);
    }

    private static boolean waitForStableVideoPage(
            TapAccessibilityService service,
            AtomicBoolean running,
            long timeoutMs,
            String logTag) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        int stable = 0;
        while (running.get() && SystemClock.uptimeMillis() < end) {
            boolean ready = isVideoPageReady(service);
            TraceLogger.log(logTag, "videoReady=" + ready + " stable=" + stable);
            if (ready) {
                stable++;
                if (stable >= VIDEO_READY_STABLE_COUNT) return true;
            } else {
                stable = 0;
            }
            SystemClock.sleep(55L);
        }
        return stable >= VIDEO_READY_STABLE_COUNT || isVideoPageReady(service);
    }

    /**
     * Window ids are intentionally not part of this definition. Douyin can recreate the window
     * while returning to the same feed video. These three facts represent what we actually need:
     * Douyin is active, the share sheet is gone, and the feed right-rail 分享 control is visible.
     */
    private static boolean isVideoPageReady(TapAccessibilityService service) {
        if (isSharePanelVisibleAnyWindow(service)) return false;
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return false;
        try {
            return hasRightRailShareParameterInRoot(service, root);
        } finally {
            safeRecycle(root);
        }
    }

    private static boolean disableV7CoordinateCache() {
        try {
            Field valid = ShareFlowV7.class.getDeclaredField("cachedShareValid");
            Field x = ShareFlowV7.class.getDeclaredField("cachedShareX");
            Field y = ShareFlowV7.class.getDeclaredField("cachedShareY");
            valid.setAccessible(true);
            x.setAccessible(true);
            y.setAccessible(true);
            valid.setBoolean(null, false);
            x.setFloat(null, 0f);
            y.setFloat(null, 0f);
            return !valid.getBoolean(null);
        } catch (Throwable e) {
            TraceLogger.critical("SHARE_GUARD", "disable cache error=" + shortThrowable(e));
            return false;
        }
    }

    private static int getActiveDouyinWindowId(TapAccessibilityService service) {
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return -1;
        try {
            return root.getWindowId();
        } finally {
            safeRecycle(root);
        }
    }

    /** Check all Douyin windows because SharePanelDialog can sit above the active feed root. */
    private static boolean isSharePanelVisibleAnyWindow(TapAccessibilityService service) {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        if (root == null || !DOUYIN_PACKAGE.equals(text(root.getPackageName()))) continue;
                        if (rootHasVisibleShareTitle(service, root)) return true;
                    } catch (Throwable ignored) {
                    } finally {
                        safeRecycle(root);
                    }
                }
            }
        } catch (Throwable e) {
            TraceLogger.log("RECOVER", "window panel check error=" + shortThrowable(e));
        }

        AccessibilityNodeInfo active = getActiveDouyinRoot(service);
        if (active == null) return false;
        try {
            return rootHasVisibleShareTitle(service, active);
        } finally {
            safeRecycle(active);
        }
    }

    private static boolean rootHasVisibleShareTitle(
            TapAccessibilityService service,
            AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> hits = null;
        try {
            int h = service.getResources().getDisplayMetrics().heightPixels;
            hits = root.findAccessibilityNodeInfosByText("分享给");
            if (hits == null) return false;
            for (AccessibilityNodeInfo n : hits) {
                if (n == null) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                String tx = normalize(text(n.getText()));
                String ds = normalize(text(n.getContentDescription()));
                if (n.isVisibleToUser() && b.height() > 0 && b.centerY() >= h * 0.45f &&
                        ("分享给".equals(tx) || "分享给".equals(ds))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (hits != null) for (AccessibilityNodeInfo n : hits) safeRecycle(n);
        }
        return false;
    }

    private static boolean hasRightRailShareParameterInRoot(
            TapAccessibilityService service,
            AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> hits = null;
        try {
            int w = service.getResources().getDisplayMetrics().widthPixels;
            int h = service.getResources().getDisplayMetrics().heightPixels;
            hits = root.findAccessibilityNodeInfosByText("分享");
            if (hits == null) return false;
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
                boolean compact = b.width() <= w * 0.22f && b.height() <= h * 0.12f;
                boolean strongDesc = ds.startsWith("分享") && ds.contains("按钮");
                boolean exact = "分享".equals(tx) || "分享".equals(ds);
                if (!forbidden && n.isVisibleToUser() && n.isEnabled() &&
                        rightRail && compact && (strongDesc || exact)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            TraceLogger.log("VIDEO_READY", "share parameter check error=" + shortThrowable(e));
            return false;
        } finally {
            if (hits != null) for (AccessibilityNodeInfo n : hits) safeRecycle(n);
        }
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
                        if (root != null && DOUYIN_PACKAGE.equals(text(root.getPackageName()))) return root;
                    } catch (Throwable ignored) {
                    } finally {
                        if (root != null && !DOUYIN_PACKAGE.equals(text(root.getPackageName()))) {
                            safeRecycle(root);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
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

    private static boolean tap(
            TapAccessibilityService service,
            float x,
            float y,
            long duration,
            String label) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(45L, duration));
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
            TraceLogger.critical("RECOVER", label + " accepted=false");
            return false;
        }
        try {
            latch.await(duration + 450L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        TraceLogger.log("RECOVER", label + " completed=" + ok[0] +
                " elapsedMs=" + (SystemClock.uptimeMillis() - began));
        return ok[0];
    }

    private static long readV7AtomicLong(String fieldName, long fallback) {
        try {
            Field f = ShareFlowV7.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(null);
            return value instanceof AtomicLong ? ((AtomicLong) value).get() : fallback;
        } catch (Throwable e) {
            TraceLogger.log("SHARE_GUARD", "read " + fieldName + " failed=" + shortThrowable(e));
            return fallback;
        }
    }

    private static long readV7Long(String fieldName, long fallback) {
        try {
            Field f = ShareFlowV7.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getLong(null);
        } catch (Throwable e) {
            TraceLogger.log("SHARE_GUARD", "read " + fieldName + " failed=" + shortThrowable(e));
            return fallback;
        }
    }

    private static int readV7Int(String fieldName, int fallback) {
        try {
            Field f = ShareFlowV7.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable e) {
            TraceLogger.log("SHARE_GUARD", "read " + fieldName + " failed=" + shortThrowable(e));
            return fallback;
        }
    }

    private static String readV7String(String fieldName, String fallback) {
        try {
            Field f = ShareFlowV7.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(null);
            return value == null ? fallback : value.toString();
        } catch (Throwable e) {
            TraceLogger.log("SHARE_GUARD", "read " + fieldName + " failed=" + shortThrowable(e));
            return fallback;
        }
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

    private static final class AttemptSnapshot {
        final int sourceWindowId;
        final long shareDialogUptimeBefore;
        final long viewClickSeqBefore;

        AttemptSnapshot(int sourceWindowId, long shareDialogUptimeBefore, long viewClickSeqBefore) {
            this.sourceWindowId = sourceWindowId;
            this.shareDialogUptimeBefore = shareDialogUptimeBefore;
            this.viewClickSeqBefore = viewClickSeqBefore;
        }
    }
}
