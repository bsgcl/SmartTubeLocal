package com.liskovsoft.smartyoutubetv2.common.local;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded scanner and playback factory for the configured router archive. */
public final class LocalArchiveRepository implements AppPrefs.ProfileChangeListener {
    private static final String TAG = LocalArchiveRepository.class.getSimpleName();
    private static final int MAX_ARTWORK_ITEMS = 64;
    private static final int MAX_ARTWORK_BYTES = 512 * 1024;
    private static final long MAX_SCAN_MILLIS = 60_000;
    private static LocalArchiveRepository sInstance;

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    public static final class PlaybackSource {
        public final Video video;
        public final LoopbackHttpStream stream;

        PlaybackSource(Video video, LoopbackHttpStream stream) {
            this.video = video;
            this.stream = stream;
        }
    }

    private final Context context;
    private final LocalArchiveSettings settings;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "local-archive-io");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<LocalArchiveItem> items = Collections.emptyList();
    private final Map<String, List<Callback<String>>> artworkRequests = new HashMap<>();
    private final Map<String, Long> artworkRetryAfter = new HashMap<>();
    private volatile boolean scanned;
    private volatile ArchiveCatalogue.CatalogueLibrary catalogueLibrary;
    private volatile List<ArchiveCatalogue.PlaylistSummary> playlistSummaries = Collections.emptyList();

    private LocalArchiveRepository(Context context) {
        this.context = context.getApplicationContext();
        settings = new LocalArchiveSettings(context);
        AppPrefs.instance(context).addListener(this);
    }

    public static synchronized LocalArchiveRepository instance(Context context) {
        if (sInstance == null) sInstance = new LocalArchiveRepository(context);
        return sInstance;
    }

    public boolean isScanned() {
        return scanned;
    }

    public synchronized void invalidate() {
        scanned = false;
        items = Collections.emptyList();
        catalogueLibrary = null;
        playlistSummaries = Collections.emptyList();
    }

    @Override
    public void onProfileChanged() {
        main.postDelayed(() -> BrowsePresenter.instance(context).updateSections(), 50);
    }

    public List<Video> getLibraryVideos() {
        if (!settings.isLibraryEnabledForActiveProfile()) return Collections.emptyList();
        List<Video> result = new ArrayList<>();
        for (LocalArchiveItem item : items) result.add(toVideo(item));
        return result;
    }

    /** Playlist cards are part of Local Library and never use an online playlist request. */
    public List<Video> getLibraryPlaylistVideos() {
        if (!settings.isLibraryEnabledForActiveProfile() || !scanned) return Collections.emptyList();
        ArchiveCatalogue.CatalogueLibrary library = catalogueLibrary;
        if (library == null) return Collections.emptyList();
        List<Video> result = new ArrayList<>();
        for (ArchiveCatalogue.PlaylistSummary summary : playlistSummaries) {
            Video video = new Video();
            video.title = summary.title;
            video.author = summary.channelTitle;
            video.secondTitle = summary.videoCount + " videos";
            video.badge = summary.videoCount + " videos";
            video.playlistId = summary.playlistId;
            video.channelId = LocalArchivePlaylistIdentity.fromPlaylistId(summary.playlistId);
            video.itemType = MediaItem.TYPE_PLAYLIST;
            LocalArchiveItem first = library.availableItemsByVideo.get(summary.thumbnailVideoId);
            if (first != null) video.cardImageUrl = getCachedArtworkUrl(first.videoId);
            result.add(video);
        }
        return result;
    }

    /** Attach a LOCAL badge only for an exact canonical video ID match. */
    public void attachLocalCopy(Video video) {
        if (video == null || video.isLocalArchive || TextUtils.isEmpty(video.videoId) || !scanned) return;
        LocalArchiveItem item = findAccessibleItem(video.videoId);
        if (item == null || !LocalArchiveAccessPolicy.shouldAttachLocalCopy(
                video.videoId, item.videoId, settings.isLibraryEnabledForActiveProfile())) return;
        video.hasLocalArchiveCopy = true;
        video.localArchivePath = item.mediaPath;
        video.localArchiveLength = item.mediaLength;
        video.badge = "LOCAL";
    }

    public LocalArchiveItem findAccessibleItem(String videoId) {
        if (!settings.isLibraryEnabledForActiveProfile()) return null;
        for (LocalArchiveItem item : items) {
            if (item.videoId.equals(videoId)) return item;
        }
        return null;
    }

    public void scanAsync(Callback<List<Video>> callback) {
        io.execute(() -> {
            try {
                scanNow();
                List<Video> visible = getLibraryVideos();
                main.post(() -> callback.onSuccess(visible));
            } catch (Exception e) {
                main.post(() -> callback.onError(userSafeError(e)));
            }
        });
    }

    /** Return a cached local image URL, or null if it is missing or access is denied. */
    public String getCachedArtworkUrl(String videoId) {
        if (TextUtils.isEmpty(videoId)) return null;
        LocalArchiveItem item = findAccessibleItem(videoId);
        if (item == null || item.artworkPath == null) return null;
        File file = artworkFile(item);
        return LocalArchiveArtworkCache.isUsable(file) ? Uri.fromFile(file).toString() : null;
    }

    /** Fetch a missing thumbnail from SMB without repeating the archive scan. */
    public void loadArtwork(String videoId, Callback<String> callback) {
        if (callback == null) return;
        if (TextUtils.isEmpty(videoId) || !settings.isLibraryEnabledForActiveProfile()) {
            main.post(() -> callback.onError("Local archive access denied"));
            return;
        }
        LocalArchiveItem item = findAccessibleItem(videoId);
        if (item == null || item.artworkPath == null) {
            main.post(() -> callback.onError("Local thumbnail is unavailable"));
            return;
        }
        String cached = getCachedArtworkUrl(videoId);
        if (cached != null) {
            main.post(() -> callback.onSuccess(cached));
            return;
        }

        synchronized (artworkRequests) {
            Long retryAfter = artworkRetryAfter.get(videoId);
            if (retryAfter != null && System.currentTimeMillis() < retryAfter) {
                main.post(() -> callback.onError("Local thumbnail retry pending"));
                return;
            }
            List<Callback<String>> listeners = artworkRequests.get(videoId);
            if (listeners != null) {
                listeners.add(callback);
                return;
            }
            listeners = new ArrayList<>();
            listeners.add(callback);
            artworkRequests.put(videoId, listeners);
        }

        io.execute(() -> {
            String result = null;
            SmbjArchiveClient client = null;
            try {
                if (!settings.isLibraryEnabledForActiveProfile() || findAccessibleItem(videoId) != item) {
                    throw new IOException("Archive access changed");
                }
                LocalArchiveSettings.Config config = settings.getConfig();
                client = newClient(config);
                byte[] bytes = client.readSmallFile(
                        sharePath(config.archiveFolder, item.artworkPath), MAX_ARTWORK_BYTES);
                File output = artworkFile(item);
                LocalArchiveArtworkCache.save(output, bytes, MAX_ARTWORK_BYTES);
                result = Uri.fromFile(output).toString();
            } catch (Exception error) {
                Log.e(TAG, "Local thumbnail SMB fetch failed: %s", error.toString());
            } finally {
                if (client != null) client.close();
            }

            String url = result;
            List<Callback<String>> listeners;
            synchronized (artworkRequests) {
                listeners = artworkRequests.remove(videoId);
                if (url == null) artworkRetryAfter.put(videoId, System.currentTimeMillis() + 15_000);
                else artworkRetryAfter.remove(videoId);
            }
            main.post(() -> {
                for (Callback<String> listener : listeners) {
                    if (!settings.isLibraryEnabledForActiveProfile()) {
                        listener.onError("Local archive access denied");
                    } else if (url != null) {
                        listener.onSuccess(url);
                    } else {
                        listener.onError("Local thumbnail is unavailable");
                    }
                }
            });
        });
    }

    private File artworkFile(LocalArchiveItem item) {
        return new File(new File(context.getCacheDir(), "local-archive-thumbnails"),
                digest(item.videoId) + extension(item.artworkPath));
    }

    /** Return only indexed archive entries whose canonical channel ID matches exactly.
     *  Stage 3: the per-channel catalogue file is fetched lazily on channel access,
     *  falling back to the cached catalogue entries when the refresh fails. */
    public void loadChannelVideos(String channelId, Callback<List<Video>> callback) {
        if (TextUtils.isEmpty(channelId) || !settings.isLibraryEnabledForActiveProfile()) {
            main.post(() -> callback.onError("This profile does not have permission for the local archive"));
            return;
        }
        io.execute(() -> {
            try {
                ensureCatalogueLoaded();
                refreshChannelFromShare(channelId);
                List<Video> matches = new ArrayList<>();
                for (LocalArchiveItem item : items) {
                    if (channelId.equals(item.channelId)) matches.add(toVideo(item));
                }
                main.post(() -> callback.onSuccess(matches));
            } catch (Exception e) {
                main.post(() -> callback.onError(userSafeError(e)));
            }
        });
    }

    /** Load only indexed, playable videos for a source playlist, preserving source order. */
    public void loadPlaylistVideos(String playlistId, Callback<List<Video>> callback) {
        if (TextUtils.isEmpty(playlistId) || !settings.isLibraryEnabledForActiveProfile()) {
            main.post(() -> callback.onError("This profile does not have permission for the local archive"));
            return;
        }
        io.execute(() -> {
            try {
                ensureCatalogueLoaded();
                ArchiveCatalogue.CatalogueLibrary library = catalogueLibrary;
                ArchiveCatalogue.PlaylistSummary summary = findPlaylistSummary(playlistId);
                if (library == null || summary == null) {
                    throw new IllegalStateException("This playlist is not in the configured archive catalogue");
                }
                LocalArchiveSettings.Config config = settings.getConfig();
                String archiveFolder = LocalArchiveIndex.requireSafeRelativePath(config.archiveFolder);
                SmbjArchiveClient client = newClient(config);
                List<Video> videos;
                try {
                    client.connect();
                    ArchiveCatalogueClient catalogue = new ArchiveCatalogueClient(client, archiveFolder, context);
                    String json = catalogue.loadPlaylist(library.revision, summary);
                    ArchiveCatalogue.PlaylistDocument detail =
                            ArchiveCatalogue.parsePlaylist(json, library.revision, summary);
                    videos = new ArrayList<>();
                    int index = 0;
                    for (String videoId : detail.videoIds) {
                        LocalArchiveItem item = library.availableItemsByVideo.get(videoId);
                        if (item == null || !item.isAvailable()) continue;
                        Video video = toVideo(item);
                        video.playlistId = playlistId;
                        video.playlistIndex = index++;
                        videos.add(video);
                    }
                } finally {
                    client.close();
                }
                List<Video> result = videos;
                main.post(() -> callback.onSuccess(result));
            } catch (Exception error) {
                main.post(() -> callback.onError(userSafeError(error)));
            }
        });
    }

    private ArchiveCatalogue.PlaylistSummary findPlaylistSummary(String playlistId) {
        for (ArchiveCatalogue.PlaylistSummary summary : playlistSummaries) {
            if (playlistId.equals(summary.playlistId)) return summary;
        }
        return null;
    }

    /** Ensure the library catalogue is loaded without re-publishing scan side effects. */
    private void ensureCatalogueLoaded() throws Exception {
        if (scanned && catalogueLibrary != null) return;
        scanNow();
    }

    /** Lazily fetch the channel's catalogue file and merge it into the item index. */
    private void refreshChannelFromShare(String channelId) {
        ArchiveCatalogue.CatalogueLibrary library = catalogueLibrary;
        LocalArchiveSettings.Config config = settings.getConfig();
        if (library == null || !config.isReady()) return;
        try {
            String archiveFolder = LocalArchiveIndex.requireSafeRelativePath(config.archiveFolder);
            SmbjArchiveClient client = newClient(config);
            try {
                client.connect();
                ArchiveCatalogueClient catalogue = new ArchiveCatalogueClient(client, archiveFolder, context);
                String channelTitle = channelTitle(library, channelId);
                String json = catalogue.loadChannel(library.revision, channelId);
                List<LocalArchiveItem> fresh = ArchiveCatalogue.parseChannel(channelId, channelTitle, json);
                mergeChannelItems(channelId, fresh);
            } finally {
                client.close();
            }
        } catch (Exception error) {
            // Lazy refresh failed: the cached catalogue entries remain authoritative.
            Log.e(TAG, "Lazy channel refresh failed; using cached catalogue: %s", error.toString());
        }
    }

    private static String channelTitle(ArchiveCatalogue.CatalogueLibrary library, String channelId) {
        for (ArchiveCatalogue.ChannelSummary channel : library.channels) {
            if (channel.channelId.equals(channelId)) return channel.title;
        }
        return channelId;
    }

    private synchronized void mergeChannelItems(String channelId, List<LocalArchiveItem> fresh) {
        ArchiveCatalogue.CatalogueLibrary library = catalogueLibrary;
        if (library == null) return;
        // Replace this channel's entries in the merged index; other channels untouched.
        List<LocalArchiveItem> merged = new ArrayList<>();
        for (LocalArchiveItem item : items) {
            if (!channelId.equals(item.channelId)) merged.add(item);
        }
        merged.addAll(fresh);
        items = deduplicate(merged);
    }

    public void startPlayback(String videoId, Callback<PlaybackSource> callback) {
        io.execute(() -> {
            LoopbackHttpStream stream = null;
            try {
                boolean permitted = settings.isLibraryEnabledForActiveProfile();
                if (!permitted) {
                    throw new IllegalStateException("This profile does not have permission for that local video");
                }
                if (!scanned) scanNow();
                LocalArchiveItem item = findAccessibleItem(videoId);
                if (item == null) throw new IllegalStateException("This video is not in the configured archive");
                if (!LocalArchiveAccessPolicy.shouldPreferLocalPlayback(item.isAvailable(), permitted)) {
                    throw new IllegalStateException("The archived media file is unavailable");
                }
                LocalArchiveSettings.Config config = settings.getConfig();
                SmbjArchiveClient client = newClient(config);
                client.connect();
                stream = new LoopbackHttpStream(client, sharePath(config.archiveFolder, item.mediaPath), item.mediaLength);
                String streamUrl = stream.start();
                Video video = toVideo(item);
                video.isLocalArchive = true;
                video.isLocalArchiveOnly = true;
                video.localStreamUrl = streamUrl;
                LoopbackHttpStream finalStream = stream;
                main.post(() -> callback.onSuccess(new PlaybackSource(video, finalStream)));
            } catch (Exception e) {
                if (stream != null) stream.close();
                main.post(() -> callback.onError(userSafeError(e)));
            }
        });
    }

    private synchronized void scanNow() throws Exception {
        LocalArchiveSettings.Config config = settings.getConfig();
        if (!config.isReady()) throw new IllegalStateException("Configure the SMB archive in Settings first");
        String archiveFolder = LocalArchiveIndex.requireSafeRelativePath(config.archiveFolder);
        SmbjArchiveClient client = newClient(config);
        long deadline = System.currentTimeMillis() + MAX_SCAN_MILLIS;
        try {
            client.connect();
            // Stage 3: load the Pi-produced catalogue (pointer + library) instead of
            // scanning every yt-dlp .info.json sidecar over SMB.
            ArchiveCatalogueClient catalogue = new ArchiveCatalogueClient(client, archiveFolder, context);
            ArchiveCatalogue.CatalogueLibrary library = catalogue.loadLibrary();
            List<ArchiveCatalogue.PlaylistSummary> playlists =
                    catalogue.loadPlaylistsIndex(library.revision);
            List<LocalArchiveItem> indexed = new ArrayList<>();
            for (List<LocalArchiveItem> channelItems : library.itemsByChannel.values()) {
                indexed.addAll(channelItems);
            }
            items = deduplicate(indexed);
            catalogueLibrary = library;
            playlistSummaries = playlists;
            scanned = true;
            List<LocalArchiveItem> prioritized = new ArrayList<>();
            for (ArchiveCatalogue.PlaylistSummary playlist : playlists) {
                LocalArchiveItem cover = library.availableItemsByVideo.get(playlist.thumbnailVideoId);
                if (cover != null && !prioritized.contains(cover)) prioritized.add(cover);
            }
            for (LocalArchiveItem item : items) {
                if (!prioritized.contains(item)) prioritized.add(item);
            }
            cacheArtwork(client, archiveFolder, prioritized, deadline);
        } finally {
            client.close();
        }
    }

    private void cacheArtwork(SmbjArchiveClient client, String archiveFolder,
                              List<LocalArchiveItem> indexed, long deadline) {
        File cacheDirectory = new File(context.getCacheDir(), "local-archive-thumbnails");
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) return;
        int artworkCount = 0;
        for (LocalArchiveItem item : indexed) {
            if (artworkCount >= MAX_ARTWORK_ITEMS || System.currentTimeMillis() >= deadline) break;
            if (item.artworkPath == null) continue;
            File output = new File(cacheDirectory, digest(item.videoId) + extension(item.artworkPath));
            if (LocalArchiveArtworkCache.isUsable(output)) continue;
            try {
                artworkCount++;
                byte[] bytes = client.readSmallFile(sharePath(archiveFolder, item.artworkPath), MAX_ARTWORK_BYTES);
                LocalArchiveArtworkCache.save(output, bytes, MAX_ARTWORK_BYTES);
            } catch (Exception error) {
                Log.e(TAG, "Local thumbnail scan copy failed: %s", error.toString());
            }
        }
    }

    private Video toVideo(LocalArchiveItem item) {
        Video video = new Video();
        video.videoId = item.videoId;
        video.channelId = item.channelId;
        video.title = item.title;
        video.author = item.channelName;
        video.secondTitle = item.channelName;
        video.isLocalArchive = true;
        video.isLocalArchiveOnly = true;
        video.hasLocalArchiveCopy = item.isAvailable();
        video.localArchivePath = item.mediaPath;
        video.localArchiveLength = item.mediaLength;
        video.badge = item.isAvailable() ? "LOCAL" : "UNAVAILABLE";
        if (item.artworkPath != null) {
            File artwork = new File(new File(context.getCacheDir(), "local-archive-thumbnails"),
                    digest(item.videoId) + extension(item.artworkPath));
            if (LocalArchiveArtworkCache.isUsable(artwork)) video.cardImageUrl = Uri.fromFile(artwork).toString();
        }
        return video;
    }

    private static List<LocalArchiveItem> deduplicate(List<LocalArchiveItem> values) {
        LinkedHashMap<String, LocalArchiveItem> unique = new LinkedHashMap<>();
        for (LocalArchiveItem item : values) {
            LocalArchiveItem previous = unique.get(item.videoId);
            if (previous == null || (!previous.isAvailable() && item.isAvailable())) unique.put(item.videoId, item);
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static SmbjArchiveClient newClient(LocalArchiveSettings.Config config) {
        return new SmbjArchiveClient(config.host, 445, config.share, config.username, config.password, "");
    }

    private static String sharePath(String root, String relative) throws JSONException {
        return LocalArchiveIndex.requireSafeRelativePath(root + "/" + relative);
    }

    private static String join(String parent, String child) throws JSONException {
        return LocalArchiveIndex.requireSafeRelativePath(parent + "/" + child);
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? ".img" : path.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(Charset.forName("UTF-8")));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String userSafeError(Exception error) {
        if (error instanceof IllegalStateException) return error.getMessage();
        if (error instanceof JSONException) return "Archive path or metadata is invalid";
        return "Unable to read the configured SMB archive. Check the server, share, and credentials.";
    }
}
