package com.liskovsoft.smartyoutubetv2.common.local;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bounded, identity-checked projection of a yt-dlp .info.json description. */
public final class LocalArchiveVideoMetadata {
    // Existing archive evidence includes 736,157-byte .info.json records.
    public static final int MAX_INFO_BYTES = 1024 * 1024;
    public static final int MAX_DESCRIPTION_CHARS = 100_000;

    private LocalArchiveVideoMetadata() {
    }

    /** Returns null for malformed, mismatched, missing, or over-limit metadata. */
    public static String parseDescription(String json, LocalArchiveItem expected) {
        if (json == null || expected == null || expected.videoId == null) return null;
        try {
            JsonElement parsed = new JsonParser().parse(json);
            if (parsed == null || !parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            if (!expected.videoId.equals(string(root.get("id")))) return null;
            String channelId = string(root.get("channel_id"));
            if (channelId == null || !expected.channelId.equals(channelId)) return null;

            JsonElement durationElement = root.get("duration");
            if (expected.durationSeconds > 0) {
                if (durationElement == null || durationElement.isJsonNull() ||
                        !durationElement.isJsonPrimitive() || !durationElement.getAsJsonPrimitive().isNumber()) return null;
                double duration = durationElement.getAsDouble();
                if (Double.isNaN(duration) || Double.isInfinite(duration) || duration <= 0 ||
                        Math.abs(Math.round(duration) - expected.durationSeconds) > 2) return null;
            }

            String description = string(root.get("description"));
            if (description == null || description.trim().isEmpty() ||
                    description.length() > MAX_DESCRIPTION_CHARS) return null;
            return description;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String string(JsonElement element) {
        return element != null && !element.isJsonNull() && element.isJsonPrimitive() &&
                element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
    }
}
