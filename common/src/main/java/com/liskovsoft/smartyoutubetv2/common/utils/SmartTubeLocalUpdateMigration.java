package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

public final class SmartTubeLocalUpdateMigration {
    private static final String PACKAGE_NAME = "org.smarttube.beta";
    private static final String PREFS_NAME = "com.liskovsoft.appupdatechecker2.preferences";
    private static final String MIGRATION_DONE = "smarttube_local_source_migration_completed";

    private SmartTubeLocalUpdateMigration() {
    }

    public static void migrate(Context context) {
        if (!PACKAGE_NAME.equals(context.getPackageName())) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(MIGRATION_DONE, false)) {
            return;
        }

        String apkPath = prefs.getString("apk_path", null);
        if (apkPath != null) {
            new File(apkPath).delete();
        }

        prefs.edit()
                .remove("apk_path")
                .remove("latest_version_name")
                .remove("latest_version_number")
                .remove("last_checked_ms")
                .remove("main_host")
                .putBoolean(MIGRATION_DONE, true)
                .apply();
    }
}
