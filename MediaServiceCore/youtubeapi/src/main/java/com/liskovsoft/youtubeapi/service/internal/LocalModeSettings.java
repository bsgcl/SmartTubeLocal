package com.liskovsoft.youtubeapi.service.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.liskovsoft.youtubeapi.app.AppService;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/** Profile-scoped online/local mode shared by the UI and YouTube request boundary. */
public final class LocalModeSettings {
    public static final String AUTO = "AUTO";
    public static final String LOCAL_ONLY = "LOCAL ONLY";
    private static final String PREFS = "local_mode_settings";
    private static final String KEY_PREFIX = "mode_";

    private LocalModeSettings() {
    }

    public static String getMode() {
        String profileId = LocalProfileManager.instance().getActiveId();
        return getMode(AppService.instance().getContext(), profileId);
    }

    public static String getMode(Context context, String profileId) {
        String value = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(storageKey(profileId), AUTO);
        return normalize(value);
    }

    public static void setMode(Context context, String profileId, String mode) {
        String normalized = normalize(mode);
        if (!isSupported(mode)) {
            throw new IllegalArgumentException("Mode must be AUTO or LOCAL ONLY");
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(storageKey(profileId), normalized).apply();
    }

    public static boolean isLocalOnly() {
        return LOCAL_ONLY.equals(getMode());
    }

    static String normalize(String value) {
        return isSupported(value) ? value : AUTO;
    }

    static boolean isSupported(String value) {
        return AUTO.equals(value) || LOCAL_ONLY.equals(value);
    }

    static String storageKey(String profileId) {
        String activeId = profileId == null || profileId.trim().isEmpty() ? "default" : profileId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(activeId.getBytes(Charset.forName("UTF-8")));
            return KEY_PREFIX + Base64.encodeToString(digest, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception e) {
            return KEY_PREFIX + Integer.toHexString(activeId.hashCode());
        }
    }
}
