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

/**
 * V0.7.4 share guard around the proven V7 flow.
 *
 * Changes are deliberately narrow:
 * 1) Cross-video share-coordinate reuse is disabled before every share attempt. V7 therefore
 *    re-queries the visible right-rail "分享" accessibility parameter for every video instead of
 *    blindly reusing a coordinate that may point at 收藏 on a differently laid-out video.
 * 2) A normal share failure is recoverable. We never retry target/send. We only dismiss any
 *    remaining share UI and return control to AutomationController so the next loop performs the
 *    one normal swipe to the next video.
 */
public final class ShareFlowV8 {
    private ShareFlowV8() {}

    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final long RECOVER_WAIT_MS = 500L;

    public static boolean shareToTarget(
            TapAccessibilityService service,
            String target,
            AtomicBoolean running) throws Exception {

        if (service == null || running == null || !running.get()) return false;

        // V7 keeps a coordinate cache for speed. It was proven unsafe across heterogeneous
        // Douyin video layouts, so V0.7.4 forcibly invalidates it before every new video share.
        if (!disableV7CoordinateCache()) {
            TraceLogger.critical("SHARE_GUARD", "cannot disable V7 coordinate cache; skip share safely");
            TapAccessibilityService.setOverlayStatus("分享跳过｜无法关闭坐标缓存");
            return false;
        }

        boolean ok;
        try {
            ok = ShareFlowV7.shareToTarget(service, target, running);
        } catch (Exception e) {
            TraceLogger.critical("SHARE_GUARD", "V7 exception=" + shortThrowable(e));
            ok = false;
        }

        if (ok || !running.get()) return ok;

        boolean recovered = recoverToVideo(service, running);
        TraceLogger.critical("RECOVER", "shareFailed recovered=" + recovered);
        if (running.get()) {
            TapAccessibilityService.setOverlayStatus(
                    recovered
                            ? "分享失败｜已退出分享框\n继续下一条"
                            : "分享失败｜恢复未完全确认\n仍尝试下一条");
        }
        return false;
    }

    /**
     * Do not swipe here. AutomationController owns the only next-video swipe at the beginning of
     * the next cycle, which prevents accidental double skipping.
     */
    private static boolean recoverToVideo(
            TapAccessibilityService service,
            AtomicBoolean running) {
        if (!running.get()) return false;

        TraceLogger.critical("RECOVER", "begin");

        // If a keyboard somehow survived the share failure, Back first dismisses only the IME.
        if (isKeyboardVisible(service) && running.get()) {
            TraceLogger.log("RECOVER", "keyboard visible -> BACK");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(220);
        }

        // Only press Back when we can positively see the share panel. This avoids navigating away
        // from the feed if failure happened before the panel ever opened.
        if (isSharePanelVisible(service) && running.get()) {
            TraceLogger.log("RECOVER", "share panel visible -> BACK");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            if (waitSharePanelGone(service, running, RECOVER_WAIT_MS)) {
                SystemClock.sleep(220);
                return true;
            }
        } else if (isDouyinActive(service)) {
            // Nothing modal remains; we are already safe for the next cycle.
            TraceLogger.log("RECOVER", "share panel not visible; Douyin active");
            SystemClock.sleep(160);
            return true;
        }

        if (!running.get()) return false;

        // Some bottom sheets occasionally ignore Back during an animation. Tap well above the
        // sheet in the video area; this is only used when the share panel is still positively seen.
        if (isSharePanelVisible(service)) {
            int w = service.getResources().getDisplayMetrics().widthPixels;
            int h = service.getResources().getDisplayMetrics().heightPixels;
            float x = w * 0.50f;
            float y = h * 0.36f;
            TraceLogger.log("RECOVER", "panel still visible -> safe outside tap x=" +
                    Math.round(x) + " y=" + Math.round(y));
            tap(service, x, y, 55, "recover-outside");
            if (waitSharePanelGone(service, running, RECOVER_WAIT_MS)) {
                SystemClock.sleep(220);
                return true;
            }
        }

        if (!running.get()) return false;

        // Final bounded fallback: one more Back, but only if the panel is still visible.
        if (isSharePanelVisible(service)) {
            TraceLogger.log("RECOVER", "panel still visible -> final BACK");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            if (waitSharePanelGone(service, running, RECOVER_WAIT_MS)) {
                SystemClock.sleep(220);
                return true;
            }
        }

        return !isSharePanelVisible(service) && isDouyinActive(service);
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

    private static boolean waitSharePanelGone(
            TapAccessibilityService service,
            AtomicBoolean running,
            long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (running.get() && SystemClock.uptimeMillis() < end) {
            if (!isSharePanelVisible(service)) return true;
            SystemClock.sleep(40);
        }
        return !isSharePanelVisible(service);
    }

    private static boolean isSharePanelVisible(TapAccessibilityService service) {
        AccessibilityNodeInfo root = getActiveDouyinRoot(service);
        if (root == null) return false;
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
            return false;
        } catch (Throwable e) {
            TraceLogger.log("RECOVER", "panel check error=" + shortThrowable(e));
            return false;
        } finally {
            if (hits != null) {
                for (AccessibilityNodeInfo n : hits) safeRecycle(n);
            }
            safeRecycle(root);
        }
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
}
