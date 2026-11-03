package com.liskovsoft.smartyoutubetv2.common.local;

/** Stable, non-YouTube identity for a subscription backed by local archive metadata. */
public final class LocalArchiveChannelIdentity {
    private static final String PREFIX = "local-archive-channel:";

    private LocalArchiveChannelIdentity() { }

    public static String fromCanonicalId(String channelId) {
        return channelId == null || channelId.trim().isEmpty() ? null : PREFIX + channelId;
    }

    public static boolean isLocalId(String channelId) {
        return channelId != null && channelId.startsWith(PREFIX) && channelId.length() > PREFIX.length();
    }

    public static String canonicalId(String localId) {
        return isLocalId(localId) ? localId.substring(PREFIX.length()) : null;
    }

    public static String displayTitle(String channelName) {
        return channelName == null || channelName.trim().isEmpty() ? null : "[LOCAL] " + channelName.trim();
    }
}
