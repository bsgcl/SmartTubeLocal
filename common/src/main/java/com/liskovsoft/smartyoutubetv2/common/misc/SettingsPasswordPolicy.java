package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/** Reuses the existing Settings password check for every Settings item click. */
public final class SettingsPasswordPolicy {
    private SettingsPasswordPolicy() {
    }

    public static boolean isAuthorized(String storedPassword, String suppliedPassword) {
        return storedPassword == null ||
                (suppliedPassword != null && Utils.passwordMatch(storedPassword, suppliedPassword));
    }
}
