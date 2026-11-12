package com.liskovsoft.smartyoutubetv2.common.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Groups local archive videos by canonical channel ID for the Local Library row view. */
public final class LocalArchiveChannelRows {
    public interface Metadata<T> {
        String channelId(T video);
        String channelName(T video);
    }

    public static final class Row<T> {
        public final String channelId;
        public final String title;
        public final int id;
        public final List<T> videos;

        private Row(String channelId, String title, int id, List<T> videos) {
            this.channelId = channelId;
            this.title = title;
            this.id = id;
            this.videos = Collections.unmodifiableList(videos);
        }
    }

    private static final class MutableRow<T> {
        final String channelId;
        final String title;
        final List<T> videos = new ArrayList<>();

        MutableRow(String channelId, String title) {
            this.channelId = channelId;
            this.title = title;
        }
    }

    private LocalArchiveChannelRows() {
    }

    public static <T> List<Row<T>> group(List<T> videos, Metadata<T> metadata) {
        if (videos == null || videos.isEmpty()) return Collections.emptyList();

        Map<String, MutableRow<T>> grouped = new LinkedHashMap<>();
        int invalidIdIndex = 0;
        for (T video : videos) {
            if (video == null) continue;
            String channelId = metadata.channelId(video);
            boolean validId = channelId != null && !channelId.trim().isEmpty();
            String key = validId ? channelId : "invalid-channel:" + invalidIdIndex++;
            MutableRow<T> row = grouped.get(key);
            if (row == null) {
                String channelName = metadata.channelName(video);
                if (channelName == null || channelName.trim().isEmpty()) channelName = "Unknown channel";
                row = new MutableRow<>(validId ? channelId : null, "[LOCAL] " + channelName);
                grouped.put(key, row);
            }
            row.videos.add(video);
        }

        List<Row<T>> result = new ArrayList<>(grouped.size());
        Set<Integer> usedIds = new HashSet<>();
        for (Map.Entry<String, MutableRow<T>> entry : grouped.entrySet()) {
            MutableRow<T> row = entry.getValue();
            int id = ("local-channel:" + entry.getKey()).hashCode();
            while (!usedIds.add(id)) id++;
            result.add(new Row<>(row.channelId, row.title, id, row.videos));
        }
        return Collections.unmodifiableList(result);
    }
}
