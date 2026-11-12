package com.liskovsoft.smartyoutubetv2.common.local;

import android.content.Context;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment;

/**
 * Fetches the Pi-produced archive catalogue over the existing read-only SMB
 * client and keeps the last valid library on device storage.
 *
 * Share layout (relative to the configured archive folder):
 *   _catalogue/pointer.json                    -> current revision
 *   _catalogue/v{revision}/library.json        -> channel summaries + video entries
 *   _catalogue/v{revision}/channels/{id}.json  -> one channel, loaded lazily
 *
 * Any failed refresh preserves the previous valid on-device cache.
 */
final class ArchiveCatalogueClient {
    private static final String CATALOGUE_DIR = "_catalogue";
    private static final int MAX_POINTER_BYTES = 4096;

    private final SmbjArchiveClient client;
    private final String archiveFolder;
    private final File cacheDir;

    ArchiveCatalogueClient(SmbjArchiveClient client, String archiveFolder, Context context) {
        this.client = client;
        this.archiveFolder = archiveFolder;
        this.cacheDir = new File(context.getFilesDir(), "local-archive-catalogue");
        recoverInterruptedUpdates(this.cacheDir);
    }

    /**
     * Crash recovery for every cache file: a process death between the
     * "old -> .bak" and "tmp -> target" rename steps leaves the last valid
     * cache only in the .bak file. On the next launch this restores it, so
     * an interrupted update can never lose the previous valid catalogue.
     * Stray .tmp files from interrupted writes are discarded.
     */
    static void recoverInterruptedUpdates(File cacheDir) {
        try {
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".bak")) {
                    String base = name.substring(0, name.length() - 4);
                    File target = new File(cacheDir, base);
                    if (!target.isFile()) {
                        if (!file.renameTo(target)) {
                            // Rename can fail transiently (e.g. file still
                            // held); fall back to copy so recovery succeeds.
                            copyFile(file, target);
                            file.delete();
                        }
                    } else {
                        file.delete();  // target already valid; drop the stale backup
                    }
                } else if (name.endsWith(".tmp")) {
                    file.delete();
                }
            }
        } catch (Exception ignored) {
            // Recovery is best-effort; the share remains the source of truth.
        }
    }

    /** Load pointer + library; fall back to the on-device cache when the refresh fails. */
    ArchiveCatalogue.CatalogueLibrary loadLibrary() throws IOException {
        String cachedText = readCache("library.json");
        String pointerText;
        try {
            pointerText = new String(client.readSmallFile(
                    join(archiveFolder, CATALOGUE_DIR, "pointer.json"), MAX_POINTER_BYTES),
                    Charset.forName("UTF-8"));
        } catch (Exception refreshError) {
            if (cachedText != null) return ArchiveCatalogue.parseLibrary(cachedText);
            throw new IOException("Catalogue refresh failed and no cached catalogue exists", refreshError);
        }
        long revision;
        try {
            revision = ArchiveCatalogue.parsePointer(pointerText);
        } catch (IllegalArgumentException pointerError) {
            if (cachedText != null) return ArchiveCatalogue.parseLibrary(cachedText);
            throw new IOException("Invalid catalogue pointer", pointerError);
        }
        String libraryText;
        try {
            libraryText = new String(client.readSmallFile(
                    join(archiveFolder, CATALOGUE_DIR, "v" + revision, "library.json"),
                    ArchiveCatalogue.MAX_LIBRARY_BYTES), Charset.forName("UTF-8"));
            ArchiveCatalogue.CatalogueLibrary library = ArchiveCatalogue.parseLibrary(libraryText);
            if (library.revision != revision) {
                throw new IOException("Library revision does not match pointer");
            }
            writeCache("library.json", libraryText);
            return library;
        } catch (Exception libraryError) {
            // Malformed library or failed fetch: preserve the previous valid catalogue.
            if (cachedText != null) return ArchiveCatalogue.parseLibrary(cachedText);
            throw new IOException("Invalid catalogue library and no cached catalogue exists", libraryError);
        }
    }

    /** Lazily load one channel file; falls back to the per-channel cache. */
    String loadChannel(long revision, String channelId) throws IOException {
        String cacheName = "channel-r" + revision + "-" + channelId + ".json";
        String cachedText = readCache(cacheName);
        try {
            String text = new String(client.readSmallFile(
                    join(archiveFolder, CATALOGUE_DIR, "v" + revision, "channels", channelId + ".json"),
                    ArchiveCatalogue.MAX_CHANNEL_BYTES), Charset.forName("UTF-8"));
            // Validate before accepting: parse throws on structural problems
            // (format version, identity, malformed entries).
            ArchiveCatalogue.parseChannel(channelId, "validation", text);
            writeCache(cacheName, text);
            return text;
        } catch (Exception refreshError) {
            if (cachedText != null) return cachedText;
            throw new IOException("Channel refresh failed and no cached channel exists", refreshError);
        }
    }

    /** Load the optional playlist index for this library revision. Older channel-only catalogues return an empty list. */
    java.util.List<ArchiveCatalogue.PlaylistSummary> loadPlaylistsIndex(long revision) throws IOException {
        String cacheName = "playlists-r" + revision + ".json";
        String cachedText = readCache(cacheName);
        try {
            String text = new String(client.readSmallFile(
                    join(archiveFolder, CATALOGUE_DIR, "v" + revision, "playlists.json"),
                    ArchiveCatalogue.MAX_PLAYLISTS_BYTES), Charset.forName("UTF-8"));
            java.util.List<ArchiveCatalogue.PlaylistSummary> playlists =
                    ArchiveCatalogue.parsePlaylistsIndex(text, revision);
            writeCache(cacheName, text);
            return playlists;
        } catch (Exception refreshError) {
            if (cachedText != null) {
                try {
                    return ArchiveCatalogue.parsePlaylistsIndex(cachedText, revision);
                } catch (IllegalArgumentException invalidCache) {
                    // Do not use a malformed cache; older archive revisions may have no playlist index.
                }
            }
            return java.util.Collections.emptyList();
        }
    }

    /** Load and validate one playlist detail, cached by revision to avoid stale membership. */
    String loadPlaylist(long revision, ArchiveCatalogue.PlaylistSummary summary) throws IOException {
        String cacheName = "playlist-r" + revision + "-" + summary.playlistId + ".json";
        String cachedText = readCache(cacheName);
        try {
            String text = new String(client.readSmallFile(
                    join(archiveFolder, CATALOGUE_DIR, "v" + revision, "playlists",
                            summary.playlistId + ".json"),
                    ArchiveCatalogue.MAX_PLAYLIST_BYTES), Charset.forName("UTF-8"));
            ArchiveCatalogue.parsePlaylist(text, revision, summary);
            writeCache(cacheName, text);
            return text;
        } catch (Exception refreshError) {
            if (cachedText != null) {
                try {
                    ArchiveCatalogue.parsePlaylist(cachedText, revision, summary);
                    return cachedText;
                } catch (IllegalArgumentException invalidCache) {
                    // Fall through to the read failure below.
                }
            }
            throw new IOException("Playlist refresh failed and no valid cached playlist exists",
                    refreshError);
        }
    }

    /** Read a validated, bounded SponsorBlock record from the current archive revision. */
    List<SponsorSegment> loadSponsorSegments(long revision, String videoId, long durationMs) throws IOException {
        if (TextUtils.isEmpty(videoId) || !videoId.matches("[A-Za-z0-9_-]{6,32}")) {
            throw new IOException("Invalid SponsorBlock video ID");
        }
        String cacheName = "sponsor-r" + revision + "-" + videoId + ".json";
        String cachedText = readCache(cacheName);
        try {
            String text = new String(client.readSmallFile(
                    join(archiveFolder, CATALOGUE_DIR, "v" + revision, "sponsorblock", videoId + ".json"),
                    ArchiveCatalogue.MAX_SPONSOR_BYTES), Charset.forName("UTF-8"));
            List<SponsorSegment> segments = ArchiveCatalogue.parseSponsorSegments(
                    text, videoId, durationMs, revision);
            writeCache(cacheName, text);
            return segments;
        } catch (Exception refreshError) {
            if (cachedText != null) {
                try {
                    return ArchiveCatalogue.parseSponsorSegments(cachedText, videoId, durationMs, revision);
                } catch (IllegalArgumentException invalidCache) {
                    // Invalid or mismatched cached timings are never applied.
                }
            }
            if (refreshError instanceof IOException) throw (IOException) refreshError;
            throw new IOException("SponsorBlock cache is invalid", refreshError);
        }
    }

    /** Read one bounded .info.json file on demand; no description enters library.json. */
    String loadVideoDescription(LocalArchiveItem item) throws IOException {
        if (item == null || item.metadataPath == null) return null;
        String infoPath;
        try {
            infoPath = LocalArchiveIndex.requireSafeRelativePath(item.metadataPath);
        } catch (org.json.JSONException unsafePath) {
            throw new IOException("Unsafe local video metadata path", unsafePath);
        }
        byte[] bytes = client.readSmallFile(join(archiveFolder, infoPath),
                LocalArchiveVideoMetadata.MAX_INFO_BYTES);
        return LocalArchiveVideoMetadata.parseDescription(
                new String(bytes, Charset.forName("UTF-8")), item);
    }

    private String readCache(String name) {
        try {
            File file = new File(cacheDir, name);
            if (!file.isFile()) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCache(String name, String text) {
        try {
            cacheDir.mkdirs();
            updateCache(cacheDir, name, text);
        } catch (Exception ignored) {
            // Cache is best-effort; the share remains the source of truth.
        }
    }

    /**
     * Atomically replaces a cache file while guaranteeing that the previous
     * valid file is never deleted before the replacement is fully installed
     * (swap-through-backup; works on Android API 21+ local storage).
     *
     * Sequence: write tmp -> move old target to .bak -> rename tmp onto
     * target -> delete .bak. If any rename fails, the previous file is
     * restored and the tmp is discarded, so a failed update always leaves
     * the last valid cache in place. If the process dies between the two
     * renames, the next launch restores the .bak via
     * {@link #recoverInterruptedUpdates()}.
     *
     * Callers must validate the text (parse) before calling; malformed JSON
     * never reaches this method.
     *
     * @return true when the replacement is installed, false when the
     *         previous valid cache was preserved unchanged.
     */
    static boolean updateCache(File cacheDir, String name, String text) {
        File target = new File(cacheDir, name);
        File tmp = new File(cacheDir, name + ".tmp");
        File backup = new File(cacheDir, name + ".bak");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(Charset.forName("UTF-8")));
                out.getFD().sync();
            }
            boolean hadPrevious = target.isFile();
            if (hadPrevious && !target.renameTo(backup)) {
                // Could not move the old file aside: keep it untouched.
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(target)) {
                // Restore the previous cache before giving up.
                if (hadPrevious) {
                    backup.renameTo(target);  // best effort restore
                }
                tmp.delete();
                return false;
            }
            if (backup.isFile()) {
                backup.delete();
            }
            return true;
        } catch (Exception error) {
            tmp.delete();
            return false;
        }
    }

    private static void copyFile(File source, File target) {
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (Exception ignored) {
            // Best-effort; the share remains the source of truth.
        }
    }

    private static String join(String... parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) continue;
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }
}
