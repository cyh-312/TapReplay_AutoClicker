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
 * V0.7.5 share guard around the proven V7 parameter-driven flow.
 *
 * Goals:
 * 1) Never reuse a right-rail share coordinate across videos.
 * 2) Reconcile the known false-negative case where Send succeeds and the video page is visibly
 *    restored, but the MainActivity WINDOW_STATE_CHANGED event is missed.
 * 3) Treat a real per-video share failure as recoverable: dismiss any remaining share UI and let
 *    AutomationController continue to the next video. This class never swipes videos itself.
 *
 * V7 still owns the exact-target, one-target-tap, pixel-confirmed-send and one-send-tap rules.
 */
public final class ShareFlowV8 {
    private ShareFlowV8() {}

    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final long RECONCILE_WAIT_MS = 360L;
    private static final long RECOVER_WAIT_MS = 750L;

    public static boolean shareToTarget(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running) throws Exception {

        if (service == null || running == null || !running.get()) return false;

        // Cross-video coordinate reuse caused occasional clicks on 收藏 when a video's right rail
        // shifted vertically. V7 may still cache internally, but we invalidate it before every
        // distinct video share attempt so each video is re-located by Accessibility parameters.
        if (!disableV7CoordinateCache()) {
            TraceLogger.critical("SHARE_GUARD", "cannot disable V7 coordinate cache; skip share safely");
            TapAccessibilityService.setOverlayStatus("分享跳过｜无法关闭坐标缓存");
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

        // V7 intentionally trusts MainActivity WINDOW_STATE_CHANGED after Send. On the captured
        // Huawei/Douyin runs that event is occasionally absent even though the sheet has closed and
        // the original video page is already back. Reconcile that exact case with independent,
        // parameter-based evidence before declaring a real failure.
        if (reconcilePostSendSuccess(service, running, snapshot)) {
            TraceLogger.critical("POST_SEND_RECONCILE",
                    "success without MainActivity event; sourceWindowId=" + snapshot.sourceWindowId);
            TapAccessibilityService.setOverlayStatus(
                    "分享完成✓\n主界面已恢复（事件补偿）");
            SystemClock.sleep(260L);
            return true;
        }

        boolean recovered = recoverToVideo(service, running, snapshot.sourceWindowId);
        TraceLogger.critical("RECOVER",
                "shareFailed sourceWindowId=" + snapshot.sourceWindowId + " recovered=" + recovered);
        if (running.get()) {
            TapAccessibilityService.setOverlayStatus(
                    recovered
                            ? "分享失败｜已退出分享框\n继续下一条"
                            : "分享失败｜恢复未完全确认\n仍尝试下一条");
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
     * Secondary success path used only after V7 already returned false.
     *
     * It deliberately requires several independent facts so an early-stage failure cannot be
     * misclassified as a successful send:
     * - this attempt opened a new SharePanelDialog;
     * - this attempt produced a target ImageView click inside that share-dialog window;
     * - the active Douyin window is back to the exact pre-share video window id;
     * - no visible "分享给" panel remains in any Douyin window;
     * - the video's right-rail "分享" parameter is visible again.
     */
    private static boolean reconcilePostSendSuccess(
            TapAccessibilityService service,
            AtomicBoolean running,
            AttemptSnapshot snapshot) {

        if (snapshot.sourceWindowId < 0 || !running.get()) return false;

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
                " dialogWindowId=" + shareDialogWindowId +
                " lastClickWindowId=" + lastClickWindowId +
                " lastClickClass=" + lastClickClass);

        if (!dialogOpenedThisAttempt || !targetClickThisAttempt) return false;

        long end = SystemClock.uptimeMillis() + RECONCILE_WAIT_MS;
        int stable = 0;
        while (running.get() && SystemClock.uptimeMillis() < end) {
            boolean sourceActive = isSourceWindowActive(service, snapshot.sourceWindowId);
            if (!sourceActive) {
                stable = 0;
                SystemClock.sleep(45L);
                continue;
            }

            boolean panelGone = !isSharePanelVisibleAnyWindow(service);
            boolean shareVisible = panelGone &&
                    hasRightRailShareParameter(service, snapshot.sourceWindowId);

            TraceLogger.log("POST_SEND_RECONCILE",
                    "sourceActive=true panelGone=" + panelGone +
                    " rightRailShare=" + shareVisible);

            if (panelGone && shareVisible) {
                stable++;
                if (stable >= 2) return true;
            } else {
                stable = 0;
            }
            SystemClock.sleep(45L);
        }
        return false;
    }

    /**
     * Recover a real failed share without ever swiping. The next-video swipe belongs only to
     * AutomationController at the beginning of the next cycle, preventing double skips.
     */
    private static boolean recoverToVideo(
            TapAccessibilityService service,
            AtomicBoolean running,
            int sourceWindowId) {

        if (!running.get()) return false;
        TraceLogger.critical("RECOVER", "begin sourceWindowId=" + sourceWindowId);

        // If the original video window is already active, do not press Back. This is the most
        // important guard against accidentally navigating away from Douyin after a late UI settle.
        if (sourceWindowId >= 0 && isSourceWindowActive(service, sourceWindowId)) {
            TraceLogger.log("RECOVER", "source video already active");
            SystemClock.sleep(160L);
            return true;
        }

        if (isKeyboardVisible(service) && running.get()) {
            TraceLogger.log("RECOVER", "keyboard visible -> BACK");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(220L);
            if (sourceWindowId >= 0 && isSourceWindowActive(service, sourceWindowId)) return true;
        }

        boolean panelVisible = isSharePanelVisibleAnyWindow(service);
        int activeId = getActiveDouyinWindowId(service);

        // If the top Douyin window is not the original video window, it is still a modal/share
        // layer from this attempt. One bounded Back is safer than blindly tapping or swiping.
        if (running.get() &&
                (panelVisible || (sourceWindowId >= 0 && activeId >= 0 && activeId != sourceWindowId))) {
            TraceLogger.log("RECOVER",
                    "modal/share layer -> BACK panelVisible=" + panelVisible + " activeId=" + activeId);
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            if (waitForSourceVideo(service, running, sourceWindowId, RECOVER_WAIT_MS)) {
                SystemClock.sleep(180L);
                return true;
            }
        }

        if (!running.get()) return false;

        // A bottom sheet may ignore Back during its closing animation. Only use an outside tap if
        // the share panel is still positively visible in a Douyin window.
        if (isSharePanelVisibleAnyWindow(service)) {
            int w = service.getResources().getDisplayMetrics().widthPixels;
            int h = service.getResources().getDisplayMetrics().heightPixels;
            float x = w * 0.50f;
            float y = h * 0.36f;
            TraceLogger.log("RECOVER", "panel still visible -> safe outside tap x=" +
                    Math.round(x) + " y=" + Math.round(y));
            tap(service, x, y, 55L, "recover-outside");
            if (waitForSourceVideo(service, running, sourceWindowId, RECOVER_WAIT_MS)) {
                SystemClock.sleep(180L);
                return true;
            }
        }

        if (!running.get()) return false;

        // Final bounded fallback only when a non-source Douyin window is still on top. Never Back
        // from the already-restored source video window.
        activeId = getActiveDouyinWindowId(service);
        if (sourceWindowId >= 0 && activeId >= 0 && activeId != sourceWindowId) {
            TraceLogger.log("RECOVER", "non-source window remains -> final BACK activeId=" + activeId);
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            if (waitForSourceVideo(service, running, sourceWindowId, RECOVER_WAIT_MS)) {
                SystemClock.sleep(180L);
                return true;
            }
        }

        if (sourceWindowId >= 0) return isSourceWindowActive(service, sourceWindowId);
        return !isSharePanelVisibleAnyWindow(service) && isDouyinActive(service);
    }

    private static boolean waitForSourceVideo(
            TapAccessibilityService service,
            AtomicBoolean running,
            int sourceWindowId,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (running.get() && SystemClock.uptimeMillis() < end) {
            if (sourceWindowId >= 0) {
                if (isSourceWindowActive(service, sourceWindowId)) return true;
            } else if (!isSharePanelVisibleAnyWindow(service) && isDouyinActive(service)) {
                return true;
            }
            SystemClock.sleep(45L);
        }
        if (sourceWindowId >= 0) return isSourceWindowActive(service, sourceWindowId);
        return !isSharePanelVisibleAnyWindow(service) && isDouyinActive(service);
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

    private static boolean isSourceWindowActive(TapAccessibilityService service, int sourceWindowId) {
        if (sourceWindowId < 0) return false;
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return false;
        try {
            return root.getWindowId() == sourceWindowId;
        } finally {
            safeRecycle(root);
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

    /** Query all Douyin windows because the visible SharePanelDialog may not be the active root. */
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

    /** Cheap parameter query; no full video-tree traversal. */
    private static boolean hasRightRailShareParameter(
            TapAccessibilityService service,
            int expectedWindowId) {
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return false;
        List<AccessibilityNodeInfo> hits = null;
        try {
            if (expectedWindowId >= 0 && root.getWindowId() != expectedWindowId) return false;
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
            TraceLogger.log("POST_SEND_RECONCILE", "share parameter check error=" + shortThrowable(e));
            return false;
        } finally {
            if (hits != null) for (AccessibilityNodeInfo n : hits) safeRecycle(n);
            safeRecycle(root);
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

    private static boolean isDouyinActive(TapAccessibilityService service) {
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return false;
        safeRecycle(root);
        return true;
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
                if (window != null && window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true;
                }
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
