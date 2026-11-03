package com.liskovsoft.smartyoutubetv2.common.local;

/** Carries local-only intent through the existing playlist item's badge field. */
public final class LocalArchivePlaylistMarker {
    private static final String PREFIX = "\u001fST_LOCAL_ARCHIVE_ONLY:";

    private LocalArchivePlaylistMarker() { }

    public static String encode(String badge) {
        return PREFIX + (badge == null ? "" : badge);
    }

    public static boolean isMarked(String badge) {
        return badge != null && badge.startsWith(PREFIX);
    }

    public static String visibleBadge(String badge) {
        return isMarked(badge) ? badge.substring(PREFIX.length()) : badge;
    }
}
