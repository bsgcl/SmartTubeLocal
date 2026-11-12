package com.liskovsoft.smartyoutubetv2.common.local;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Android-free metadata parser and archive index rules, shared by production and JVM tests. */
public final class LocalArchiveIndexCore {
    public static final class InvalidArchiveMetadataException extends IllegalArgumentException {
        InvalidArchiveMetadataException(String message) {
            super(message);
        }
    }

    private LocalArchiveIndexCore() {
    }

    public static List<LocalArchiveItem> build(Map<String, String> sidecars,
                                                Map<String, Long> fileLengths) {
        if (sidecars == null || fileLengths == null) return Collections.emptyList();

        List<String> paths = new ArrayList<>(fileLengths.keySet());
        LinkedHashMap<String, LocalArchiveItem> byVideoId = new LinkedHashMap<>();
        for (Map.Entry<String, String> sidecar : sidecars.entrySet()) {
            LocalArchiveItem item = parse(sidecar.getKey(), sidecar.getValue(), paths, fileLengths);
            LocalArchiveItem previous = byVideoId.get(item.videoId);
            if (previous == null || (!previous.isAvailable() && item.isAvailable())) {
                byVideoId.put(item.videoId, item);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(byVideoId.values()));
    }

    private static LocalArchiveItem parse(String sidecarPath, String json,
                                          Collection<String> filePaths,
                                          Map<String, Long> fileLengths) {
        String safeSidecarPath = requireSafeRelativePath(sidecarPath);
        JsonObject metadata;
        try {
            JsonElement parsed = new JsonParser().parse(json);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new InvalidArchiveMetadataException("Sidecar must contain a JSON object");
            }
            metadata = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new InvalidArchiveMetadataException("Malformed sidecar JSON");
        }

        String videoId = required(metadata, "id");
        String channelId = required(metadata, "channel_id");
        String title = required(metadata, "title");
        String channelName = optional(metadata, "channel");
        if (channelName.isEmpty()) channelName = optional(metadata, "uploader");
        if (!videoId.matches("[A-Za-z0-9_-]{6,32}")) {
            throw new InvalidArchiveMetadataException("Invalid video id in sidecar");
        }
        if (channelName.trim().isEmpty()) channelName = "Unknown channel";

        String parent = parentOf(safeSidecarPath);
        String base = safeSidecarPath.substring(0, safeSidecarPath.length() - ".info.json".length());
        String mediaPath = null;
        String artworkPath = null;
        long mediaLength = -1;
        for (String candidatePath : filePaths) {
            String candidate = requireSafeRelativePath(candidatePath);
            if (!parentOf(candidate).equals(parent)) continue;
            String name = fileName(candidate).toLowerCase(Locale.ROOT);
            String id = videoId.toLowerCase(Locale.ROOT);
            if (name.contains(id) && name.endsWith(".mp4")) {
                if (mediaPath == null || candidate.equalsIgnoreCase(base + ".mp4")) {
                    mediaPath = candidate;
                    Long length = fileLengths.get(candidatePath);
                    mediaLength = length == null ? -1 : length;
                }
            } else if (name.contains(id) && isArtwork(name)) {
                if (artworkPath == null || candidate.equalsIgnoreCase(base + extension(candidate))) {
                    artworkPath = candidate;
                }
            }
        }

        return new LocalArchiveItem(videoId, channelId, title, channelName,
                optionalLong(metadata, "duration"), safeSidecarPath,
                optional(metadata, "upload_date"), mediaPath, artworkPath, mediaLength);
    }

    public static String requireSafeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.startsWith("\\") ||
                path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) {
            throw new InvalidArchiveMetadataException("Unsafe archive-relative path");
        }
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new InvalidArchiveMetadataException("Unsafe archive-relative path");
            }
        }
        return normalized;
    }

    private static String required(JsonObject metadata, String key) {
        String value = optional(metadata, key).trim();
        if (value.isEmpty()) throw new InvalidArchiveMetadataException("Missing " + key + " in sidecar");
        return value;
    }

    private static String optional(JsonObject metadata, String key) {
        JsonElement value = metadata.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long optionalLong(JsonObject metadata, String key) {
        JsonElement value = metadata.get(key);
        if (value == null || value.isJsonNull()) return 0;
        try {
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static boolean isArtwork(String name) {
        return name.endsWith(".webp") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot);
    }
}
