package com.liskovsoft.smartyoutubetv2.common.local;

/** Non-YouTube identity for a playlist backed by the SMB archive catalogue. */
public final class LocalArchivePlaylistIdentity {
    private static final String PREFIX = "local-archive-playlist:";

    private LocalArchivePlaylistIdentity() { }

    public static String fromPlaylistId(String playlistId) {
        return playlistId == null || playlistId.trim().isEmpty()
                ? null : PREFIX + playlistId;
    }

    public static boolean isLocalId(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() > PREFIX.length();
    }

    public static String playlistId(String value) {
        return isLocalId(value) ? value.substring(PREFIX.length()) : null;
    }
}
