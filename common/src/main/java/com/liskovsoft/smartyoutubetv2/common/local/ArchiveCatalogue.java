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
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment;
import com.liskovsoft.youtubeapi.service.data.YouTubeSponsorSegment;

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
    public static final int MAX_TOTAL_VIDEOS = 20_000;
    public static final int MAX_CHANNELS = 500;
    public static final int MAX_PLAYLISTS = 500;
    public static final int MAX_PLAYLIST_ENTRIES = 5_000;
    public static final int MAX_TOTAL_SOURCE_PLAYLIST_ENTRIES = 50_000;
    private static final int MAX_VIDEO_TITLE_CHARS = 300;
    static final int MAX_SPONSOR_BYTES = 256 * 1024;
    private static final int MAX_SPONSOR_SEGMENTS = 256;
    static final int MAX_LIBRARY_BYTES = 32 * 1024 * 1024;
    static final int MAX_CHANNEL_BYTES = 32 * 1024 * 1024;
    static final int MAX_PLAYLISTS_BYTES = 2 * 1024 * 1024;
    static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;

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

    public static final class PlaylistSummary {
        public final String playlistId;
        public final String title;
        public final String channelId;
        public final String channelTitle;
        public final int videoCount;
        public final int sourceVideoCount;
        public final int missingVideoCount;
        public final String thumbnailVideoId;

        PlaylistSummary(String playlistId, String title, String channelId, String channelTitle,
                        int videoCount, int sourceVideoCount, int missingVideoCount,
                        String thumbnailVideoId) {
            this.playlistId = playlistId;
            this.title = title;
            this.channelId = channelId;
            this.channelTitle = channelTitle;
            this.videoCount = videoCount;
            this.sourceVideoCount = sourceVideoCount;
            this.missingVideoCount = missingVideoCount;
            this.thumbnailVideoId = thumbnailVideoId;
        }
    }

    public static final class PlaylistDocument {
        public final String playlistId;
        public final String title;
        public final String channelId;
        public final String channelTitle;
        public final int sourceVideoCount;
        public final int missingVideoCount;
        public final String thumbnailVideoId;
        /** Original source order, including intentional repeated IDs. */
        public final List<String> videoIds;

        PlaylistDocument(String playlistId, String title, String channelId, String channelTitle,
                         int sourceVideoCount, int missingVideoCount, String thumbnailVideoId,
                         List<String> videoIds) {
            this.playlistId = playlistId;
            this.title = title;
            this.channelId = channelId;
            this.channelTitle = channelTitle;
            this.sourceVideoCount = sourceVideoCount;
            this.missingVideoCount = missingVideoCount;
            this.thumbnailVideoId = thumbnailVideoId;
            this.videoIds = videoIds;
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
        /** Playable local items keyed by canonical YouTube video ID. */
        public final Map<String, LocalArchiveItem> availableItemsByVideo;

        CatalogueLibrary(long revision, String generatedAt,
                         List<ChannelSummary> channels, Map<String, String> videoIndex,
                         Map<String, List<LocalArchiveItem>> itemsByChannel,
                         Map<String, LocalArchiveItem> availableItemsByVideo) {
            this.revision = revision;
            this.generatedAt = generatedAt;
            this.channels = channels;
            this.videoIndex = videoIndex;
            this.itemsByChannel = itemsByChannel;
            this.availableItemsByVideo = availableItemsByVideo;
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

    /** Validate cached SponsorBlock timings against the exact local video and media duration. */
    public static List<SponsorSegment> parseSponsorSegments(String json, String videoId,
                                                             long durationMs, long revision) {
        JsonObject root = requireObject(json);
        requireVersion(root);
        if (optLong(root, "revision", -1) != revision ||
                !videoId.equals(optString(root, "videoId", ""))) {
            throw invalid("SponsorBlock cache identity mismatch");
        }
        long cachedDuration = optLong(root, "durationMs", -1);
        if (durationMs <= 0 || cachedDuration <= 0 || Math.abs(cachedDuration - durationMs) > 2_000) {
            throw invalid("SponsorBlock media duration mismatch");
        }
        if (!root.has("segments") || !root.get("segments").isJsonArray() ||
                root.getAsJsonArray("segments").size() > MAX_SPONSOR_SEGMENTS) {
            throw invalid("SponsorBlock segment list is invalid");
        }
        List<SponsorSegment> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("segments")) {
            JsonObject segment = element.getAsJsonObject();
            long start = optLong(segment, "startMs", -1);
            long end = optLong(segment, "endMs", -1);
            String category = optString(segment, "category", "");
            String action = optString(segment, "action", "");
            if (start < 0 || end <= start || end > durationMs ||
                    !isSponsorCategory(category) ||
                    !(SponsorSegment.ACTION_SKIP.equals(action) || SponsorSegment.ACTION_MUTE.equals(action))) {
                throw invalid("SponsorBlock segment is outside the media timeline or invalid");
            }
            result.add(YouTubeSponsorSegment.fromMillis(start, end, category, action));
        }
        return result;
    }

    private static boolean isSponsorCategory(String category) {
        return SponsorSegment.CATEGORY_SPONSOR.equals(category) || SponsorSegment.CATEGORY_INTRO.equals(category) ||
                SponsorSegment.CATEGORY_OUTRO.equals(category) || SponsorSegment.CATEGORY_INTERACTION.equals(category) ||
                SponsorSegment.CATEGORY_SELF_PROMO.equals(category) || SponsorSegment.CATEGORY_MUSIC_OFF_TOPIC.equals(category) ||
                SponsorSegment.CATEGORY_PREVIEW_RECAP.equals(category) || SponsorSegment.CATEGORY_POI_HIGHLIGHT.equals(category) ||
                SponsorSegment.CATEGORY_FILLER.equals(category);
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
        Map<String, LocalArchiveItem> availableItemsByVideo = new HashMap<>();
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
                    if (title.length() > MAX_VIDEO_TITLE_CHARS) throw invalid("Video title limit exceeded");
                    long duration = optLong(video, "duration", -1);
                    videos.add(new VideoSummary(videoId, title, duration));
                    // One malformed entry is dropped; the rest of the channel stays usable.
                    try {
                        String infoPath = optionalMetadataPath(video);
                        items.add(new LocalArchiveItem(videoId, channelId, title, channelTitle,
                                duration, infoPath, optString(video, "uploadDate", null),
                                optionalSafePath(video, "mediaPath"),
                                optionalSafePath(video, "thumbPath"),
                                optLong(video, "mediaLength", -1)));
                        LocalArchiveItem item = items.get(items.size() - 1);
                        if (item.isAvailable() && item.mediaLength > 0) {
                            availableItemsByVideo.put(videoId, item);
                        }
                    } catch (LocalArchiveIndexCore.InvalidArchiveMetadataException pathError) {
                        // Path unsafe for this entry: index the video without media access.
                    }
                }
            }
            channels.add(new ChannelSummary(channelId, channelTitle, videos));
            itemsByChannel.put(channelId, items);
        }
        return new CatalogueLibrary(revision, generatedAt, channels, videoIndex,
                itemsByChannel, availableItemsByVideo);
    }

    /** Parse the compact playlist index. Missing index files are handled by the caller for old channel-only catalogues. */
    public static List<PlaylistSummary> parsePlaylistsIndex(String json, long expectedRevision) {
        JsonObject root = requireObject(json);
        requireVersion(root);
        if (optLong(root, "revision", -1) != expectedRevision) {
            throw invalid("Playlist index revision does not match library");
        }
        if (!root.has("playlists") || !root.get("playlists").isJsonArray()) {
            throw invalid("Playlist index missing playlists");
        }
        JsonArray array = root.getAsJsonArray("playlists");
        if (array.size() > MAX_PLAYLISTS) throw invalid("Playlist limit exceeded");
        List<PlaylistSummary> result = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (JsonElement element : array) {
            JsonObject playlist = element.getAsJsonObject();
            String id = requirePlaylistId(playlist, "playlistId");
            if (seen.put(id, Boolean.TRUE) != null) throw invalid("Duplicate playlistId: " + id);
            String title = optString(playlist, "title", "").trim();
            String channelId = requireCanonicalId(playlist, "channelId");
            String channelTitle = optString(playlist, "channelTitle", "").trim();
            int count = boundedCount(playlist, "videoCount", MAX_PLAYLIST_ENTRIES);
            int sourceCount = boundedCount(playlist, "sourceVideoCount", MAX_TOTAL_SOURCE_PLAYLIST_ENTRIES);
            int missingCount = boundedCount(playlist, "missingVideoCount", MAX_TOTAL_SOURCE_PLAYLIST_ENTRIES);
            String thumbnailVideoId = optionalCanonicalId(playlist, "thumbnailVideoId");
            if (title.isEmpty() || title.length() > 300 || channelTitle.isEmpty()
                    || channelTitle.length() > 200 || count > sourceCount
                    || missingCount > sourceCount || count + missingCount != sourceCount
                    || (count == 0) != (thumbnailVideoId == null)) {
                throw invalid("Invalid playlist summary");
            }
            result.add(new PlaylistSummary(id, title, channelId, channelTitle, count,
                    sourceCount, missingCount, thumbnailVideoId));
        }
        return result;
    }

    /** Parse a versioned playlist detail and verify it against its summary. */
    public static PlaylistDocument parsePlaylist(String json, long expectedRevision,
                                                 PlaylistSummary expected) {
        JsonObject root = requireObject(json);
        requireVersion(root);
        if (optLong(root, "revision", -1) != expectedRevision) {
            throw invalid("Playlist detail revision does not match library");
        }
        String id = requirePlaylistId(root, "playlistId");
        String title = optString(root, "title", "").trim();
        String channelId = requireCanonicalId(root, "channelId");
        String channelTitle = optString(root, "channelTitle", "").trim();
        int sourceCount = boundedCount(root, "sourceVideoCount", MAX_TOTAL_SOURCE_PLAYLIST_ENTRIES);
        int missingCount = boundedCount(root, "missingVideoCount", MAX_TOTAL_SOURCE_PLAYLIST_ENTRIES);
        String thumbnailVideoId = optionalCanonicalId(root, "thumbnailVideoId");
        if (!id.equals(expected.playlistId) || !title.equals(expected.title)
                || !channelId.equals(expected.channelId)
                || !channelTitle.equals(expected.channelTitle)
                || sourceCount != expected.sourceVideoCount
                || missingCount != expected.missingVideoCount
                || !java.util.Objects.equals(thumbnailVideoId, expected.thumbnailVideoId)) {
            throw invalid("Playlist detail identity does not match index");
        }
        if (!root.has("videoIds") || !root.get("videoIds").isJsonArray()) {
            throw invalid("Playlist video IDs missing");
        }
        JsonArray ids = root.getAsJsonArray("videoIds");
        if (ids.size() > MAX_PLAYLIST_ENTRIES || ids.size() != expected.videoCount) {
            throw invalid("Playlist entry count is invalid");
        }
        List<String> videoIds = new ArrayList<>();
        for (JsonElement element : ids) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw invalid("Playlist video ID must be a string");
            }
            String videoId = element.getAsString();
            if (!videoId.matches("[A-Za-z0-9_-]{6,32}")) {
                throw invalid("Invalid playlist video ID");
            }
            videoIds.add(videoId);
        }
        if (sourceCount != missingCount + videoIds.size()) {
            throw invalid("Playlist source and available counts do not match");
        }
        return new PlaylistDocument(id, title, channelId, channelTitle, sourceCount,
                missingCount, thumbnailVideoId, videoIds);
    }

    private static int boundedCount(JsonObject object, String key, int maximum) {
        long value = optLong(object, key, -1);
        if (value < 0 || value > maximum) throw invalid("Invalid " + key);
        return (int) value;
    }

    private static String optionalCanonicalId(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        return requireCanonicalId(object, key);
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
                if (optString(video, "title", videoId).length() > MAX_VIDEO_TITLE_CHARS) {
                    throw invalid("Video title limit exceeded");
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
                        optLong(video, "duration", -1), optionalMetadataPath(video),
                        optString(video, "uploadDate", null), mediaPath, thumbPath,
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

    private static String requirePlaylistId(JsonObject object, String key) {
        String id = optString(object, key, "");
        if (id.isEmpty() || !id.matches("[A-Za-z0-9_-]{6,128}")) {
            throw invalid("Invalid playlist ID for " + key);
        }
        return id;
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

    /** Metadata is optional; an unsafe info path must not hide otherwise playable video. */
    private static String optionalMetadataPath(JsonObject object) {
        try {
            return optionalSafePath(object, "infoPath");
        } catch (LocalArchiveIndexCore.InvalidArchiveMetadataException ignored) {
            return null;
        }
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
