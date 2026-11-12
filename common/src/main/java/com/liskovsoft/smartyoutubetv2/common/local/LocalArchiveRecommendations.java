package com.liskovsoft.smartyoutubetv2.common.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable, stateless local daily selection with round-robin channel variety. */
public final class LocalArchiveRecommendations {
    private LocalArchiveRecommendations() { }

    public static List<LocalArchiveItem> select(List<LocalArchiveItem> items, String localDate,
                                                 String profileId, long revision, int limit) {
        if (items == null || limit <= 0) return Collections.emptyList();
        Map<String, List<LocalArchiveItem>> byChannel = new LinkedHashMap<>();
        for (LocalArchiveItem item : items) {
            if (item == null || !item.isAvailable()) continue;
            byChannel.computeIfAbsent(item.channelId, key -> new ArrayList<>()).add(item);
        }
        Comparator<LocalArchiveItem> rank = Comparator
                .comparingInt((LocalArchiveItem item) -> rank(localDate, profileId, revision, item.videoId))
                .thenComparing(item -> item.videoId);
        List<String> channels = new ArrayList<>(byChannel.keySet());
        channels.sort(Comparator.comparingInt((String channel) ->
                rank(localDate, profileId, revision, "channel:" + channel)).thenComparing(channel -> channel));
        for (List<LocalArchiveItem> channelItems : byChannel.values()) channelItems.sort(rank);

        List<LocalArchiveItem> result = new ArrayList<>(Math.min(limit, items.size()));
        int depth = 0;
        while (result.size() < limit) {
            boolean added = false;
            for (String channel : channels) {
                List<LocalArchiveItem> channelItems = byChannel.get(channel);
                if (depth < channelItems.size()) {
                    result.add(channelItems.get(depth));
                    added = true;
                    if (result.size() == limit) break;
                }
            }
            if (!added) break;
            depth++;
        }
        return result;
    }

    private static int rank(String date, String profile, long revision, String value) {
        String seed = String.valueOf(date) + '\n' + String.valueOf(profile) + '\n' + revision + '\n' + value;
        return seed.hashCode();
    }
}
