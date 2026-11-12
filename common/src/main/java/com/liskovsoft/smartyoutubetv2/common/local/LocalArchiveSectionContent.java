package com.liskovsoft.smartyoutubetv2.common.local;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds browse content from the active profile's existing local channel subscriptions. */
public final class LocalArchiveSectionContent {
    private LocalArchiveSectionContent() { }

    public interface SubscriptionLookup {
        boolean isSubscribed(String channelId);
    }

    /** Returns one local ID for each archived channel subscribed either locally or online. */
    public static Set<String> localSubscribedChannelIds(List<Video> archiveVideos, SubscriptionLookup lookup) {
        if (archiveVideos == null || archiveVideos.isEmpty() || lookup == null) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (LocalArchiveChannelRows.Row<Video> row : LocalArchiveChannelRows.group(archiveVideos,
                new LocalArchiveChannelRows.Metadata<Video>() {
                    @Override public String channelId(Video video) { return video.channelId; }
                    @Override public String channelName(Video video) { return video.author; }
                })) {
            String localId = LocalArchiveChannelIdentity.fromCanonicalId(row.channelId);
            if (localId != null && (lookup.isSubscribed(localId) || lookup.isSubscribed(row.channelId))) {
                result.add(localId);
            }
        }
        return result;
    }

    public static List<Video> subscribedVideos(List<Video> archiveVideos, Set<String> localChannelIds) {
        if (archiveVideos == null || archiveVideos.isEmpty() || localChannelIds == null || localChannelIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Video> unique = new LinkedHashMap<>();
        for (Video video : archiveVideos) {
            if (video == null || video.videoId == null || video.channelId == null || !video.hasLocalArchiveCopy) continue;
            String localId = LocalArchiveChannelIdentity.fromCanonicalId(video.channelId);
            if (localChannelIds.contains(localId)) unique.put(video.videoId, video);
        }
        return new ArrayList<>(unique.values());
    }

    public static List<Video> subscribedChannels(List<Video> archiveVideos, Set<String> localChannelIds) {
        if (archiveVideos == null || archiveVideos.isEmpty() || localChannelIds == null || localChannelIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Video> channels = new LinkedHashMap<>();
        for (Video video : archiveVideos) {
            if (video == null || video.channelId == null || !video.hasLocalArchiveCopy) continue;
            String localId = LocalArchiveChannelIdentity.fromCanonicalId(video.channelId);
            if (!localChannelIds.contains(localId)) continue;

            Video channel = channels.get(localId);
            if (channel == null) {
                channel = new Video();
                channel.channelId = localId;
                channel.title = LocalArchiveChannelIdentity.displayTitle(video.author);
                channel.author = video.author;
                channel.cardImageUrl = video.cardImageUrl;
                channel.itemType = MediaItem.TYPE_CHANNEL;
                channel.isLocalArchiveOnly = true;
                channel.isSubscribed = true;
                channels.put(localId, channel);
            } else if (channel.cardImageUrl == null) {
                channel.cardImageUrl = video.cardImageUrl;
            }
        }
        return new ArrayList<>(channels.values());
    }

    public static Set<String> immutableIds(Set<String> ids) {
        return ids == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(new HashSet<>(ids));
    }
}
