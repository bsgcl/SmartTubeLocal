package com.liskovsoft.smartyoutubetv2.common.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogue model and strict validation for the Stage 3 archive catalogue.
 * Gson-based so the parser runs on Android and on the JVM unit tests alike.
 *
 * Layout on the SMB share (under the configured archive folder):
 *   _catalogue/pointer.json                  {"formatVersion":1,"revision":N}
 *   _catalogue/v{N}/library.json             channel summaries + compact video entries
 *   _catalogue/v{N}/channels/{channelId}.json full entries for one channel
 *
 * Any validation failure rejects the whole file (library) or the whole channel
 * (channel file) so the previous valid catalogue is preserved.
 */
public final class ArchiveCatalogue {
    public static final int SUPPORTED_FORMAT_VERSION = 1;
    public static final int MAX_TOTAL_VIDEOS = 5_000;
    public static final int MAX_CHANNELS = 500;
    static final int MAX_LIBRARY_BYTES = 8 * 1024 * 1024;
    static final int MAX_CHANNEL_BYTES = 4 * 1024 * 1024;

    private ArchiveCatalogue() {
    }

    public static final class VideoSummary {
        public final String videoId;
        public final String title;
        public final long durationSeconds;

        VideoSummary(String videoId, String title, long durationSeconds) {
            this.videoId = videoId;
            this.title = title;
            this.durationSeconds = durationSeconds;
        }
    }

    public static final class ChannelSummary {
        public final String channelId;
        public final String title;
        public final List<VideoSummary> videos;

        ChannelSummary(String channelId, String title, List<VideoSummary> videos) {
            this.channelId = channelId;
            this.title = title;
            this.videos = videos;
        }
    }

    public static final class CatalogueLibrary {
        public final long revision;
        public final String generatedAt;
        public final List<ChannelSummary> channels;
        /** videoId -> channelId, built during validation. */
        public final Map<String, String> videoIndex;
        /** channelId -> fully usable items (media/thumb paths resolved). */
        public final Map<String, List<LocalArchiveItem>> itemsByChannel;

        CatalogueLibrary(long revision, String generatedAt,
                         List<ChannelSummary> channels, Map<String, String> videoIndex,
                         Map<String, List<LocalArchiveItem>> itemsByChannel) {
            this.revision = revision;
            this.generatedAt = generatedAt;
            this.channels = channels;
            this.videoIndex = videoIndex;
            this.itemsByChannel = itemsByChannel;
        }

        public String channelOf(String videoId) {
            return videoIndex.get(videoId);
        }
    }

    /** Parse and validate pointer.json. Throws IllegalArgumentException on any problem. */
    public static long parsePointer(String json) {
        JsonObject root = requireObject(json);
        requireVersion(root);
        long revision = optLong(root, "revision", -1);
        if (revision < 0) throw invalid("Pointer revision missing");
        return revision;
    }

    /** Parse and validate library.json. Rejects the whole file on structural errors. */
    public static CatalogueLibrary parseLibrary(String json) {
        JsonObject root = requireObject(json);
        requireVersion(root);
        long revision = optLong(root, "revision", -1);
        if (revision < 0) throw invalid("Library revision missing");
        String generatedAt = optString(root, "generatedAt", "");
        if (!root.has("channels") || !root.get("channels").isJsonArray()) {
            throw invalid("Library channels missing");
        }
        JsonArray channelArray = root.getAsJsonArray("channels");
        if (channelArray.size() > MAX_CHANNELS) throw invalid("Channel limit exceeded");

        List<ChannelSummary> channels = new ArrayList<>();
        Map<String, String> videoIndex = new HashMap<>();
        Map<String, List<LocalArchiveItem>> itemsByChannel = new HashMap<>();
        int totalVideos = 0;
        for (JsonElement element : channelArray) {
            JsonObject channel = element.getAsJsonObject();
            String channelId = requireCanonicalId(channel, "channelId");
            String channelTitle = optString(channel, "title", channelId);
            List<VideoSummary> videos = new ArrayList<>();
            List<LocalArchiveItem> items = new ArrayList<>();
            if (channel.has("videos") && channel.get("videos").isJsonArray()) {
                for (JsonElement videoElement : channel.getAsJsonArray("videos")) {
                    if (++totalVideos > MAX_TOTAL_VIDEOS) {
                        throw invalid("Total video limit exceeded");
                    }
                    JsonObject video = videoElement.getAsJsonObject();
                    String videoId = requireCanonicalId(video, "videoId");
                    if (videoIndex.put(videoId, channelId) != null) {
                        throw invalid("Duplicate videoId in library: " + videoId);
                    }
                    String title = optString(video, "title", videoId);
                    long duration = optLong(video, "duration", -1);
                    videos.add(new VideoSummary(videoId, title, duration));
                    // One malformed entry is dropped; the rest of the channel stays usable.
                    try {
                        items.add(new LocalArchiveItem(videoId, channelId, title, channelTitle,
                                duration, null, optionalSafePath(video, "mediaPath"),
                                optionalSafePath(video, "thumbPath"),
                                optLong(video, "mediaLength", -1)));
                    } catch (LocalArchiveIndexCore.InvalidArchiveMetadataException pathError) {
                        // Path unsafe for this entry: index the video without media access.
                    }
                }
            }
            channels.add(new ChannelSummary(channelId, channelTitle, videos));
            itemsByChannel.put(channelId, items);
        }
        return new CatalogueLibrary(revision, generatedAt, channels, videoIndex, itemsByChannel);
    }

    /**
     * Parse one channel file into fully usable archive items.
     * A structural error throws; a single malformed entry is dropped.
     */
    public static List<LocalArchiveItem> parseChannel(String channelId, String channelTitle,
                                                      String json) {
        JsonObject root = requireObject(json);
        requireVersion(root);
        String parsedChannelId = requireCanonicalId(root, "channelId");
        if (!parsedChannelId.equals(channelId)) {
            throw invalid("Channel file identity mismatch");
        }
        if (!root.has("videos") || !root.get("videos").isJsonArray()) {
            throw invalid("Channel videos missing");
        }
        List<LocalArchiveItem> items = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (JsonElement videoElement : root.getAsJsonArray("videos")) {
            JsonObject video = videoElement.getAsJsonObject();
            try {
                String videoId = requireCanonicalId(video, "videoId");
                if (seen.put(videoId, Boolean.TRUE) != null) {
                    throw invalid("Duplicate videoId in channel: " + videoId);
                }
                // Unsafe paths degrade to null (no media access) instead of
                // dropping the entry, so one bad path cannot hide a video.
                String mediaPath;
                String thumbPath;
                try {
                    mediaPath = optionalSafePath(video, "mediaPath");
                } catch (LocalArchiveIndexCore.InvalidArchiveMetadataException pathError) {
                    mediaPath = null;
                }
                try {
                    thumbPath = optionalSafePath(video, "thumbPath");
                } catch (LocalArchiveIndexCore.InvalidArchiveMetadataException pathError) {
                    thumbPath = null;
                }
                items.add(new LocalArchiveItem(videoId, channelId,
                        optString(video, "title", videoId), channelTitle,
                        optLong(video, "duration", -1), null, mediaPath, thumbPath,
                        optLong(video, "mediaLength", -1)));
            } catch (IllegalArgumentException entryError) {
                // Malformed single entry: drop it, keep the rest of the channel.
            }
        }
        return items;
    }

    private static JsonObject requireObject(String json) {
        try {
            return new JsonParser().parse(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw invalid("Catalogue is not a JSON object");
        }
    }

    private static void requireVersion(JsonObject root) {
        if (!root.has("formatVersion")
                || root.get("formatVersion").getAsInt() != SUPPORTED_FORMAT_VERSION) {
            throw invalid("Unsupported catalogue formatVersion");
        }
    }

    private static String requireCanonicalId(JsonObject object, String key) {
        String id = optString(object, key, "");
        if (id.isEmpty() || !id.matches("[A-Za-z0-9_-]{6,32}")) {
            throw invalid("Invalid canonical ID for " + key);
        }
        return id;
    }

    private static String optionalSafePath(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        String path = optString(object, key, "");
        if (path.isEmpty()) return null;
        return LocalArchiveIndexCore.requireSafeRelativePath(path);
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsLong() : fallback;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static String optString(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : fallback;
        } catch (UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
