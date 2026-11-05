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
        String cacheName = "channel-" + channelId + ".json";
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
            File target = new File(cacheDir, name);
            File tmp = new File(cacheDir, name + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(Charset.forName("UTF-8")));
                out.getFD().sync();
            }
            if (!tmp.renameTo(target) && !target.delete()) {
                // Leave the previous cache in place on failure.
            } else if (!target.isFile()) {
                // rename failed; nothing else to do this round.
            }
        } catch (Exception ignored) {
            // Cache is best-effort; the share remains the source of truth.
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
