package com.example.tapreplay;

import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

/**
 * Process-local holder for the MediaProjection permission grant.
 *
 * The accessibility service and the activity run in the same app process in this simple APK,
 * so keeping the grant here is enough for the first test version.
 */
public final class AudioCaptureHolder {
    private static int resultCode = 0;
    private static Intent resultData = null;

    private AudioCaptureHolder() { }

    public static synchronized void setProjectionGrant(int code, Intent data) {
        resultCode = code;
        resultData = data;
    }

    public static synchronized boolean hasProjectionGrant() {
        return resultCode != 0 && resultData != null;
    }

    public static synchronized MediaProjection createProjection(MediaProjectionManager manager) {
        if (manager == null || resultCode == 0 || resultData == null) return null;
        return manager.getMediaProjection(resultCode, resultData);
    }

    public static synchronized void clear() {
        resultCode = 0;
        resultData = null;
    }
}
