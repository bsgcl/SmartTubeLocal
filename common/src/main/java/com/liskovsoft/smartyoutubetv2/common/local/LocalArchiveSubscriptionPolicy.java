package com.liskovsoft.smartyoutubetv2.common.local;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/** Shared classification for subscription actions on archive-backed cards. */
public final class LocalArchiveSubscriptionPolicy {
    private LocalArchiveSubscriptionPolicy() {
    }

    public static boolean isLocalArchiveItem(Video video) {
        return video != null && (video.isLocalArchive || video.isLocalArchiveOnly || video.hasLocalArchiveCopy ||
                LocalArchiveChannelIdentity.isLocalId(video.channelId));
    }

    public static String localChannelId(Video video) {
        if (video == null || video.channelId == null || video.channelId.trim().isEmpty()) return null;
        return LocalArchiveChannelIdentity.isLocalId(video.channelId) ? video.channelId :
                LocalArchiveChannelIdentity.fromCanonicalId(video.channelId);
    }

    public static String displayTitle(Video video) {
        if (video == null) return null;
        String author = video.author;
        if (author == null || author.trim().isEmpty()) {
            author = video.secondTitle != null ? video.secondTitle.toString() : null;
        }
        return LocalArchiveChannelIdentity.displayTitle(author);
    }
}
