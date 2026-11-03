package com.liskovsoft.smartyoutubetv2.common.local;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded scanner and playback factory for the configured router archive. */
public final class LocalArchiveRepository implements AppPrefs.ProfileChangeListener {
    private static final int MAX_DEPTH = 4;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_SIDECARS = 128;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
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

    private static final class Folder {
        final String path;
        final int depth;
        Folder(String path, int depth) { this.path = path; this.depth = depth; }
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
    private volatile boolean scanned;

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

    /** Return only indexed archive entries whose canonical channel ID matches exactly. */
    public void loadChannelVideos(String channelId, Callback<List<Video>> callback) {
        if (TextUtils.isEmpty(channelId) || !settings.isLibraryEnabledForActiveProfile()) {
            main.post(() -> callback.onError("This profile does not have permission for the local archive"));
            return;
        }
        scanAsync(new Callback<List<Video>>() {
            @Override public void onSuccess(List<Video> videos) {
                List<Video> matches = new ArrayList<>();
                for (Video video : videos) {
                    if (channelId.equals(video.channelId)) matches.add(video);
                }
                callback.onSuccess(matches);
            }
            @Override public void onError(String message) { callback.onError(message); }
        });
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
        LinkedHashMap<String, Long> fileLengths = new LinkedHashMap<>();
        LinkedHashMap<String, String> sidecars = new LinkedHashMap<>();
        ArrayDeque<Folder> pending = new ArrayDeque<>();
        long deadline = System.currentTimeMillis() + MAX_SCAN_MILLIS;
        int entryCount = 0;
        try {
            client.connect();
            pending.add(new Folder(archiveFolder, 0));
            while (!pending.isEmpty()) {
                requireBeforeDeadline(deadline);
                Folder folder = pending.removeFirst();
                List<SmbjArchiveClient.Entry> entries = client.list(folder.path);
                for (SmbjArchiveClient.Entry entry : entries) {
                    if (++entryCount > MAX_ENTRIES) throw new IllegalStateException("Archive scan entry limit reached");
                    String fullPath = join(folder.path, entry.name);
                    String relative = fullPath.substring(archiveFolder.length()).replaceFirst("^/", "");
                    if (relative.isEmpty()) continue;
                    relative = LocalArchiveIndex.requireSafeRelativePath(relative);
                    if (entry.directory) {
                        if (folder.depth < MAX_DEPTH) pending.addLast(new Folder(fullPath, folder.depth + 1));
                    } else {
                        fileLengths.put(relative, entry.length);
                        if (relative.toLowerCase(java.util.Locale.ROOT).endsWith(".info.json")) {
                            if (sidecars.size() >= MAX_SIDECARS || System.currentTimeMillis() >= deadline) continue;
                            try {
                                byte[] bytes = client.readSmallFile(fullPath, MAX_METADATA_BYTES);
                                sidecars.put(relative, new String(bytes, Charset.forName("UTF-8")));
                            } catch (Exception ignored) {
                                // A malformed or unreadable sidecar is omitted; other entries remain usable.
                            }
                        }
                    }
                }
            }
            List<LocalArchiveItem> indexed = new ArrayList<>();
            for (Map.Entry<String, String> sidecar : sidecars.entrySet()) {
                try {
                    // Parse one sidecar at a time so one malformed item cannot hide the rest.
                    Map<String, String> oneSidecar = Collections.singletonMap(sidecar.getKey(), sidecar.getValue());
                    indexed.addAll(LocalArchiveIndex.build(oneSidecar, fileLengths));
                } catch (JSONException ignored) {
                    // The scan intentionally does not log sidecar contents or paths.
                }
            }
            items = deduplicate(indexed);
            cacheArtwork(client, archiveFolder, items, deadline);
            scanned = true;
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
            if (output.isFile() && output.length() > 0) continue;
            try {
                artworkCount++;
                byte[] bytes = client.readSmallFile(sharePath(archiveFolder, item.artworkPath), MAX_ARTWORK_BYTES);
                try (FileOutputStream file = new FileOutputStream(output)) {
                    file.write(bytes);
                }
            } catch (Exception ignored) {
                // Artwork is optional; never substitute a remote thumbnail.
            }
        }
    }

    private void requireBeforeDeadline(long deadline) {
        if (System.currentTimeMillis() >= deadline) {
            throw new IllegalStateException("Archive scan time limit reached");
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
            if (artwork.isFile()) video.cardImageUrl = Uri.fromFile(artwork).toString();
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
