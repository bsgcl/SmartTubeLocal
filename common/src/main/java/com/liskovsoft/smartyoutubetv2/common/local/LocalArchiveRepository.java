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
import com.liskovsoft.youtubeapi.service.internal.LocalProfileManager;
import com.liskovsoft.googlecommon.common.helpers.ServiceHelper;
import android.text.format.DateUtils;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded scanner and playback factory for the configured router archive. */
public final class LocalArchiveRepository implements AppPrefs.ProfileChangeListener {
    private static final String TAG = LocalArchiveRepository.class.getSimpleName();
    private static final int MAX_ARTWORK_BYTES = 512 * 1024;
    private static final int MAX_CACHED_DESCRIPTIONS = 8;
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
    /** Constant-time canonical ID lookup; rebuilt atomically with the catalogue. */
    private volatile Map<String, LocalArchiveItem> itemsByVideoId = Collections.emptyMap();
    private final Map<String, List<Callback<String>>> artworkRequests = new HashMap<>();
    private final Map<String, Long> artworkRetryAfter = new HashMap<>();
    private final LinkedHashMap<String, String> descriptionCache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHED_DESCRIPTIONS;
        }
    };
    private volatile boolean scanned;
    private volatile ArchiveCatalogue.CatalogueLibrary catalogueLibrary;
    private volatile List<ArchiveCatalogue.PlaylistSummary> playlistSummaries = Collections.emptyList();
    /** Prepared Video templates stay repository-owned; callers receive mutable per-use copies. */
    private volatile PreparedLibraryVideos preparedLibraryVideos;

    private static final class PreparedLibraryVideos {
        final long revision;
        final String profileId;
        final List<LocalArchiveItem> sourceItems;
        final List<Video> templates;
        final Map<String, Video> byVideoId;
        final Map<String, List<Video>> byChannelId;

        PreparedLibraryVideos(long revision, String profileId, List<LocalArchiveItem> sourceItems,
                              List<Video> templates) {
            this.revision = revision;
            this.profileId = profileId;
            this.sourceItems = sourceItems;
            this.templates = Collections.unmodifiableList(new ArrayList<>(templates));
            Map<String, Video> index = new HashMap<>(Math.max(16, templates.size() * 4 / 3));
            Map<String, List<Video>> channelIndex = new HashMap<>();
            for (Video video : templates) {
                index.put(video.videoId, video);
                if (!TextUtils.isEmpty(video.channelId)) {
                    channelIndex.computeIfAbsent(video.channelId, ignored -> new ArrayList<>()).add(video);
                }
            }
            for (Map.Entry<String, List<Video>> entry : channelIndex.entrySet()) {
                entry.setValue(Collections.unmodifiableList(entry.getValue()));
            }
            byVideoId = index;
            byChannelId = channelIndex;
        }

        boolean matches(long expectedRevision, String expectedProfileId, List<LocalArchiveItem> expectedItems) {
            return revision == expectedRevision && profileId.equals(expectedProfileId) && sourceItems == expectedItems;
        }
    }

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
        itemsByVideoId = Collections.emptyMap();
        catalogueLibrary = null;
        preparedLibraryVideos = null;
        playlistSummaries = Collections.emptyList();
        synchronized (descriptionCache) {
            descriptionCache.clear();
        }
    }

    @Override
    public void onProfileChanged() {
        preparedLibraryVideos = null;
        synchronized (descriptionCache) {
            descriptionCache.clear();
        }
        main.postDelayed(() -> {
            if (!scanned || catalogueLibrary == null) {
                BrowsePresenter.instance(context).updateSections();
                return;
            }
            String requestedProfileId = activeProfileId();
            long revision = catalogueLibrary.revision;
            List<LocalArchiveItem> sourceItems = items;
            executeIo("video_presentation_prepare", "revision=" + revision + " profileId=" + requestedProfileId, () -> {
                if (settings.isLibraryEnabledForActiveProfile() && requestedProfileId.equals(activeProfileId())) {
                    prepareLibraryVideos(revision, requestedProfileId, sourceItems);
                }
                main.post(() -> BrowsePresenter.instance(context).updateSections());
            });
        }, 50);
    }

    /** Optional catalogue date for a video, gated by the active profile's existing archive permission. */
    public String getUploadDate(String videoId) {
        LocalArchiveItem item = findAccessibleItem(videoId);
        return item == null ? null : item.uploadDate;
    }

    /** Load only one validated local description on the archive I/O executor. */
    public void loadDescription(String videoId, Callback<String> callback) {
        if (callback == null) return;
        String profileId = activeProfileId();
        long requestId = DiagnosticTiming.nextTaskId();
        DiagnosticTiming.mark(requestId, "description.request_received", "videoId=" + videoId + " profileId=" + profileId);
        if (TextUtils.isEmpty(videoId) || !videoId.matches("[A-Za-z0-9_-]{6,32}") ||
                !settings.isLibraryEnabledForActiveProfile()) {
            main.post(() -> {
                DiagnosticTiming.mark(requestId, "description.callback_error", "reason=access_denied");
                callback.onError("This profile does not have permission for the local archive");
            });
            return;
        }

        ArchiveCatalogue.CatalogueLibrary loaded = catalogueLibrary;
        long cachedRevision = loaded == null ? -1 : loaded.revision;
        String cacheKey = descriptionCacheKey(profileId, cachedRevision, videoId);
        String cached;
        synchronized (descriptionCache) {
            cached = descriptionCache.get(cacheKey);
        }
        if (cached != null && cachedRevision >= 0) {
            String result = cached;
            DiagnosticTiming.mark(requestId, "description.cache_hit", "revision=" + cachedRevision);
            main.post(() -> {
                if (canReadForProfile(profileId)) {
                    DiagnosticTiming.mark(requestId, "description.callback_success", "source=cache");
                    callback.onSuccess(result);
                } else {
                    DiagnosticTiming.mark(requestId, "description.callback_error", "reason=profile_changed");
                    callback.onError("The active profile changed before metadata was displayed");
                }
            });
            return;
        }

        executeIo("description", "requestId=" + requestId + " videoId=" + videoId, () -> {
            try {
                if (!canReadForProfile(profileId)) throw new IOException("Local archive access is disabled");
                if (!scanned || catalogueLibrary == null) scanNow();
                ArchiveCatalogue.CatalogueLibrary library = catalogueLibrary;
                LocalArchiveItem item = findAccessibleItem(videoId);
                if (library == null || item == null || !item.isAvailable()) {
                    throw new IOException("Local video is not available to this profile");
                }
                String description = null;
                if (item.metadataPath != null) {
                    LocalArchiveSettings.Config config = settings.getConfig();
                    String archiveFolder = LocalArchiveIndex.requireSafeRelativePath(config.archiveFolder);
                    SmbjArchiveClient client = newClient(config);
                    try {
                        client.connect();
                        description = new ArchiveCatalogueClient(client, archiveFolder, context)
                                .loadVideoDescription(item);
                    } finally {
                        client.close();
                    }
                }
                if (!canReadForProfile(profileId)) throw new IOException("The active profile changed during metadata access");
                if (description != null) {
                    synchronized (descriptionCache) {
                        descriptionCache.put(descriptionCacheKey(profileId, library.revision, videoId), description);
                    }
                }
                String result = description;
                main.post(() -> {
                    if (canReadForProfile(profileId)) {
                        DiagnosticTiming.mark(requestId, "description.callback_success", "source=archive");
                        callback.onSuccess(result);
                    } else {
                        DiagnosticTiming.mark(requestId, "description.callback_error", "reason=profile_changed");
                        callback.onError("The active profile changed before metadata was displayed");
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    DiagnosticTiming.mark(requestId, "description.callback_error", "reason=" + error.getClass().getSimpleName());
                    callback.onError(userSafeError(error));
                });
            }
        });
    }

    private String descriptionCacheKey(String profileId, long revision, String videoId) {
        return profileId + ':' + revision + ':' + videoId;
    }

    private String activeProfileId() {
        String profileId = LocalProfileManager.instance().getActiveId();
        return profileId == null || profileId.trim().isEmpty() ? "default" : profileId;
    }

    private boolean canReadForProfile(String expectedProfileId) {
        return expectedProfileId != null && expectedProfileId.equals(activeProfileId()) &&
                settings.isLibraryEnabledForActiveProfile();
    }

    public List<Video> getLibraryVideos() {
        if (!settings.isLibraryEnabledForActiveProfile()) return Collections.emptyList();
        String profileId = activeProfileId();
        ArchiveCatalogue.CatalogueLibrary library = catalogueLibrary;
        List<LocalArchiveItem> sourceItems = items;
        PreparedLibraryVideos prepared = preparedLibraryVideos;
        long revision = library == null ? -1 : library.revision;
        if (prepared != null && prepared.matches(revision, profileId, sourceItems)) {
            long startedAt = DiagnosticTiming.nowNanos();
            List<Video> result = new ArrayList<>(prepared.templates.size());
            synchronized (prepared) {
                for (Video template : prepared.templates) result.add(copyPreparedVideo(template));
            }
            DiagnosticTiming.event(null, "section.video_presentation_cache.hit", "revision=" + revision +
                    " profileId=" + profileId + " videos=" + result.size() + " durationNs=" +
                    (DiagnosticTiming.nowNanos() - startedAt));
            return result;
        }

        long startedAt = DiagnosticTiming.nowNanos();
        List<Video> result = new ArrayList<>();
        for (LocalArchiveItem item : sourceItems) result.add(toVideo(item));
        DiagnosticTiming.event(null, "section.video_presentation_cache.miss", "revision=" + revision +
                " profileId=" + profileId + " videos=" + result.size() + " durationNs=" +
                (DiagnosticTiming.nowNanos() - startedAt));
        return result;
    }

    private void prepareLibraryVideos(long revision, String profileId, List<LocalArchiveItem> sourceItems) {
        if (sourceItems == null || !settings.isLibraryEnabledForActiveProfile() ||
                !profileId.equals(activeProfileId())) return;
        long startedAt = DiagnosticTiming.nowNanos();
        List<Video> templates = new ArrayList<>(sourceItems.size());
        for (LocalArchiveItem item : sourceItems) templates.add(toVideo(item));
        if (settings.isLibraryEnabledForActiveProfile() && profileId.equals(activeProfileId()) &&
                catalogueLibrary != null && catalogueLibrary.revision == revision && items == sourceItems) {
            preparedLibraryVideos = new PreparedLibraryVideos(revision, profileId, sourceItems, templates);
            DiagnosticTiming.event(null, "section.video_presentation_cache.prepared", "revision=" + revision +
                    " profileId=" + profileId + " videos=" + templates.size() + " durationNs=" +
                    (DiagnosticTiming.nowNanos() - startedAt));
        }
    }

    /** Copy repository presentation fields without sharing mutable Video/group state with adapters. */
    private static Video copyPreparedVideo(Video template) {
        Video copy = template.copy();
        copy.id = template.id;
        copy.author = template.author;
        copy.itemType = template.itemType;
        copy.isLocalArchive = template.isLocalArchive;
        copy.isLocalArchiveOnly = template.isLocalArchiveOnly;
        copy.hasLocalArchiveCopy = template.hasLocalArchiveCopy;
        copy.localArchivePath = template.localArchivePath;
        copy.localArchiveLength = template.localArchiveLength;
        copy.badge = template.badge;
        copy.startTimeSeconds = template.startTimeSeconds;
        copy.pendingPosMs = template.pendingPosMs;
        copy.volume = template.volume;
        return copy;
    }

    private void updatePreparedArtwork(String videoId, String url) {
        PreparedLibraryVideos prepared = preparedLibraryVideos;
        if (prepared == null) return;
        synchronized (prepared) {
            Video template = prepared.byVideoId.get(videoId);
            if (template != null) template.cardImageUrl = url;
        }
    }

    public List<Video> getRecommendedVideos() {
        if (!settings.isLibraryEnabledForActiveProfile() || !scanned) return Collections.emptyList();
        String localDate = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        List<LocalArchiveItem> selection = LocalArchiveRecommendations.select(items, localDate,
                com.liskovsoft.youtubeapi.service.internal.LocalProfileManager.instance().getActiveId(),
                catalogueLibrary != null ? catalogueLibrary.revision : 0, 20);
        List<Video> result = new ArrayList<>(selection.size());
        for (LocalArchiveItem item : selection) result.add(toVideo(item));
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
            String coverVideoId = getPlaylistArtworkVideoId(summary.playlistId);
            if (coverVideoId != null) video.cardImageUrl = getCachedArtworkUrl(coverVideoId);
            result.add(video);
        }
        return result;
    }

    /** Resolve a local playlist's designated cover without changing its playlist identity. */
    public String getPlaylistArtworkVideoId(String playlistId) {
        if (TextUtils.isEmpty(playlistId) || !settings.isLibraryEnabledForActiveProfile() || !scanned) {
            return null;
        }
        ArchiveCatalogue.CatalogueLibrary library = catalogueLibrary;
        if (library == null) return null;
        return LocalArchivePlaylistArtwork.coverVideoId(playlistId, playlistSummaries,
                library.availableItemsByVideo);
    }

    /** Fetch playlist cover art through the same validated, deduplicated cache path as video art. */
    public void loadPlaylistArtwork(String playlistId, Callback<String> callback) {
        if (callback == null) return;
        String requestedProfileId = activeProfileId();
        String coverVideoId = getPlaylistArtworkVideoId(playlistId);
        if (coverVideoId == null) {
            main.post(() -> callback.onError("Local playlist cover is unavailable"));
            return;
        }
        DiagnosticTiming.event(null, "artwork.playlist_request", "playlistId=" + playlistId +
                " coverVideoId=" + coverVideoId);
        loadArtwork(coverVideoId, new Callback<String>() {
            @Override public void onSuccess(String url) {
                if (!LocalArchivePlaylistArtwork.isProfileCurrent(requestedProfileId, activeProfileId()) ||
                        !settings.isLibraryEnabledForActiveProfile()) {
                    DiagnosticTiming.event(null, "artwork.playlist_callback_rejected",
                            "playlistId=" + playlistId + " reason=profile_changed");
                    callback.onError("Local archive access changed");
                    return;
                }
                callback.onSuccess(url);
            }

            @Override public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    /** Search only the validated in-memory catalogue and playlist summaries. */
    public void searchAsync(String query, Callback<List<Video>> callback) {
        if (callback == null) return;
        if (!settings.isLibraryEnabledForActiveProfile()) {
            main.post(() -> callback.onSuccess(Collections.emptyList()));
            return;
        }
        executeIo("search", "queryLength=" + (query == null ? 0 : query.length()), () -> {
            try {
                ensureCatalogueLoaded();
                if (!settings.isLibraryEnabledForActiveProfile()) {
                    main.post(() -> callback.onSuccess(Collections.emptyList()));
                    return;
                }
                String needle = query == null ? "" : query.trim();
                List<Video> result = new ArrayList<>();
                if (!needle.isEmpty()) {
                    Map<String, Video> matchedVideos = new LinkedHashMap<>();
                    for (LocalArchiveItem item : items) {
                        if (!item.isAvailable()) continue;
                        if (containsIgnoreCase(item.title, needle) || containsIgnoreCase(item.channelName, needle)) {
                            matchedVideos.put(item.videoId, toVideo(item));
                        }
                    }
                    List<Video> orderedVideos = new ArrayList<>(matchedVideos.values());
                    orderedVideos.sort(Comparator.comparing((Video video) -> video.title == null ? "" : video.title.toLowerCase(Locale.ROOT))
                            .thenComparing(video -> video.videoId));
                    result.addAll(orderedVideos);
                    for (Video playlist : getLibraryPlaylistVideos()) {
                        if (LocalArchiveSearchText.matches(playlist.title, needle)) result.add(playlist);
                    }
                }
                List<Video> found = result;
                main.post(() -> callback.onSuccess(found));
            } catch (Exception error) {
                main.post(() -> callback.onError(userSafeError(error)));
            }
        });
    }

    /** Local-only SponsorBlock lookup; missing or invalid data simply yields no skips. */
    public void loadSponsorSegments(String videoId, long durationMs, Callback<List<SponsorSegment>> callback) {
        if (callback == null) return;
        if (!settings.isLibraryEnabledForActiveProfile()) {
            main.post(() -> callback.onSuccess(Collections.emptyList()));
            return;
        }
        executeIo("sponsorblock", "videoId=" + videoId, () -> {
            List<SponsorSegment> result = Collections.emptyList();
            try {
                ensureCatalogueLoaded();
                if (settings.isLibraryEnabledForActiveProfile() && catalogueLibrary != null) {
                    LocalArchiveSettings.Config config = settings.getConfig();
                    String archiveFolder = LocalArchiveIndex.requireSafeRelativePath(config.archiveFolder);
                    SmbjArchiveClient client = newClient(config);
                    try {
                        client.connect();
                        result = new ArchiveCatalogueClient(client, archiveFolder, context)
                                .loadSponsorSegments(catalogueLibrary.revision, videoId, durationMs);
                    } finally {
                        client.close();
                    }
                }
            } catch (Exception error) {
                Log.d(TAG, "Local SponsorBlock data unavailable for %s: %s", videoId, error.toString());
            }
            List<SponsorSegment> segments = result;
            main.post(() -> callback.onSuccess(segments));
        });
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return LocalArchiveSearchText.matches(value, needle);
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
        return !settings.isLibraryEnabledForActiveProfile() || TextUtils.isEmpty(videoId) ?
                null : itemsByVideoId.get(videoId);
    }

    public void scanAsync(Callback<List<Video>> callback) {
        executeIo("catalogue_scan", "", () -> {
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
        boolean usable = LocalArchiveArtworkCache.isUsable(file);
        DiagnosticTiming.event(null, usable ? "artwork.cache_hit" : "artwork.cache_miss",
                "videoId=" + videoId + " pathPresent=true fileExists=" + file.isFile());
        return usable ? Uri.fromFile(file).toString() : null;
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

        executeIo("artwork_read", "videoId=" + videoId, () -> {
            String result = null;
            SmbjArchiveClient client = null;
            try {
                if (!settings.isLibraryEnabledForActiveProfile() || findAccessibleItem(videoId) != item) {
                    throw new IOException("Archive access changed");
                }
                LocalArchiveSettings.Config config = settings.getConfig();
                client = newClient(config);
                String artworkPath = sharePath(config.archiveFolder, item.artworkPath);
                long readStartedAt = DiagnosticTiming.nowNanos();
                byte[] bytes = client.readSmallFile(artworkPath, MAX_ARTWORK_BYTES);
                File output = artworkFile(item);
                LocalArchiveArtworkCache.save(output, bytes, MAX_ARTWORK_BYTES);
                result = Uri.fromFile(output).toString();
                updatePreparedArtwork(videoId, result);
                DiagnosticTiming.event(null, "artwork.read_success", "videoId=" + videoId +
                        " bytes=" + bytes.length + " durationNs=" +
                        (DiagnosticTiming.nowNanos() - readStartedAt) + " cacheFile=" + output.isFile());
            } catch (Exception error) {
                DiagnosticTiming.event(null, "artwork.read_failure", "videoId=" + videoId +
                        " reason=" + error.getClass().getSimpleName());
                Log.e(TAG, "Local thumbnail SMB fetch failed for %s: %s", videoId, error.toString());
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
                        DiagnosticTiming.event(null, "artwork.callback_rejected", "videoId=" + videoId +
                                " reason=profile_access_disabled");
                        listener.onError("Local archive access denied");
                    } else if (url != null) {
                        DiagnosticTiming.event(null, "artwork.callback_success", "videoId=" + videoId);
                        listener.onSuccess(url);
                    } else {
                        DiagnosticTiming.event(null, "artwork.callback_error", "videoId=" + videoId);
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
        String requestedProfileId = activeProfileId();
        executeIo("channel_videos", "channelId=" + channelId, () -> {
            try {
                ensureCatalogueLoaded();
                ArchiveCatalogue.CatalogueLibrary library = catalogueLibrary;
                PreparedLibraryVideos prepared = preparedLibraryVideos;
                if (library == null || prepared == null ||
                        !prepared.matches(library.revision, requestedProfileId, items)) {
                    throw new IllegalStateException("The local catalogue changed during channel selection");
                }
                List<Video> templates = prepared.byChannelId.get(channelId);
                List<Video> matches = new ArrayList<>(templates == null ? 0 : templates.size());
                if (templates != null) {
                    synchronized (prepared) {
                        for (Video template : templates) matches.add(copyPreparedVideo(template));
                    }
                }
                DiagnosticTiming.event(null, "channel.presentation_cache.hit", "revision=" + library.revision +
                        " profileId=" + requestedProfileId + " channelId=" + channelId +
                        " videos=" + matches.size());
                main.post(() -> {
                    if (canReadForProfile(requestedProfileId)) callback.onSuccess(matches);
                    else callback.onError("The active profile changed during channel selection");
                });
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
        executeIo("playlist_videos", "playlistId=" + playlistId, () -> {
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

    public void startPlayback(String videoId, Callback<PlaybackSource> callback) {
        executeIo("playback_start", "videoId=" + videoId, () -> {
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

    /** Submit to the existing serial executor and report queue wait/run duration without changing ordering. */
    private void executeIo(String operation, String detail, Runnable task) {
        long taskId = DiagnosticTiming.nextTaskId();
        long queuedAt = DiagnosticTiming.nowNanos();
        DiagnosticTiming.mark(taskId, "archive.request_enqueued", "operation=" + operation + " detail=" + detail +
                " queuedAtNs=" + queuedAt);
        io.execute(() -> {
            long startedAt = DiagnosticTiming.nowNanos();
            DiagnosticTiming.mark(taskId, "archive.task_started", "operation=" + operation + " detail=" + detail +
                    " queueWaitNs=" + (startedAt - queuedAt));
            try {
                task.run();
            } finally {
                long finishedAt = DiagnosticTiming.nowNanos();
                DiagnosticTiming.mark(taskId, "archive.task_finished", "operation=" + operation + " detail=" + detail +
                        " runNs=" + (finishedAt - startedAt));
            }
        });
    }

    private synchronized void scanNow() throws Exception {
        LocalArchiveSettings.Config config = settings.getConfig();
        if (!config.isReady()) throw new IllegalStateException("Configure the SMB archive in Settings first");
        String archiveFolder = LocalArchiveIndex.requireSafeRelativePath(config.archiveFolder);
        SmbjArchiveClient client = newClient(config);
        try {
            long catalogueStartedAt = DiagnosticTiming.nowNanos();
            DiagnosticTiming.event(null, "archive.catalogue_load.begin", "");
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
            itemsByVideoId = indexByVideoId(items);
            catalogueLibrary = library;
            playlistSummaries = playlists;
            preparedLibraryVideos = null;
            DiagnosticTiming.event(null, "archive.catalogue_load.end", "durationNs=" +
                    (DiagnosticTiming.nowNanos() - catalogueStartedAt) + " revision=" + library.revision +
                    " items=" + items.size() + " playlists=" + playlists.size());
            prepareLibraryVideos(library.revision, activeProfileId(), items);
            scanned = true;
        } finally {
            client.close();
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
        video.uploadDate = item.uploadDate;
        if (item.uploadDate != null) {
            long uploadedAt = LocalArchiveUploadDate.toLocalMidnightMillis(item.uploadDate);
            if (uploadedAt > 0) {
                CharSequence relative = DateUtils.getRelativeTimeSpanString(uploadedAt,
                        System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS);
                video.secondTitle = ServiceHelper.createInfo(item.channelName,
                        context.getString(com.liskovsoft.smartyoutubetv2.common.R.string.local_uploaded_relative,
                                relative));
            }
        }
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

    private static Map<String, LocalArchiveItem> indexByVideoId(List<LocalArchiveItem> values) {
        Map<String, LocalArchiveItem> result = new HashMap<>(Math.max(16, values.size() * 4 / 3));
        for (LocalArchiveItem item : values) result.put(item.videoId, item);
        return Collections.unmodifiableMap(result);
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
