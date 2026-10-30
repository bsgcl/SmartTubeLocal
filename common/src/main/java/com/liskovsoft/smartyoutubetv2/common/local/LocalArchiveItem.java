package com.liskovsoft.smartyoutubetv2.common.local;

/** One metadata-backed entry discovered in the configured SMB archive. */
public final class LocalArchiveItem {
    public final String videoId;
    public final String channelId;
    public final String title;
    public final String channelName;
    public final long durationSeconds;
    public final String metadataPath;
    public final String mediaPath;
    public final String artworkPath;
    public final long mediaLength;

    LocalArchiveItem(String videoId, String channelId, String title, String channelName,
                     long durationSeconds, String metadataPath, String mediaPath,
                     String artworkPath, long mediaLength) {
        this.videoId = videoId;
        this.channelId = channelId;
        this.title = title;
        this.channelName = channelName;
        this.durationSeconds = durationSeconds;
        this.metadataPath = metadataPath;
        this.mediaPath = mediaPath;
        this.artworkPath = artworkPath;
        this.mediaLength = mediaLength;
    }

    public boolean isAvailable() {
        return mediaPath != null && mediaLength >= 0;
    }
}
