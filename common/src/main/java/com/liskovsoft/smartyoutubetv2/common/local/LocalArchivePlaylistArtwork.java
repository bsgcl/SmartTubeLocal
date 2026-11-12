package com.liskovsoft.smartyoutubetv2.common.local;

import java.util.List;
import java.util.Map;

/** Pure playlist-cover resolution and profile checks shared by the repository and tests. */
public final class LocalArchivePlaylistArtwork {
    private LocalArchivePlaylistArtwork() {
    }

    public static String coverVideoId(String playlistId,
                                      List<ArchiveCatalogue.PlaylistSummary> summaries,
                                      Map<String, LocalArchiveItem> availableItems) {
        if (isEmpty(playlistId) || summaries == null || availableItems == null) return null;
        for (ArchiveCatalogue.PlaylistSummary summary : summaries) {
            if (!playlistId.equals(summary.playlistId) || isEmpty(summary.thumbnailVideoId)) continue;
            LocalArchiveItem cover = availableItems.get(summary.thumbnailVideoId);
            return cover != null && summary.thumbnailVideoId.equals(cover.videoId) &&
                    !isEmpty(cover.artworkPath) ? cover.videoId : null;
        }
        return null;
    }

    public static boolean isProfileCurrent(String requestedProfileId, String activeProfileId) {
        return requestedProfileId != null && requestedProfileId.equals(activeProfileId);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
