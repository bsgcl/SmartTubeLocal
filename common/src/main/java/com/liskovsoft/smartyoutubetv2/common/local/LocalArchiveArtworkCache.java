package com.liskovsoft.smartyoutubetv2.common.local;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Atomic writes for temporary archive thumbnail files. */
final class LocalArchiveArtworkCache {
    private LocalArchiveArtworkCache() {
    }

    static boolean isUsable(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    static void save(File destination, byte[] bytes, int maxBytes) throws IOException {
        if (destination == null || bytes == null || bytes.length == 0 || bytes.length > maxBytes) {
            throw new IOException("Invalid thumbnail size");
        }

        File directory = destination.getParentFile();
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            throw new IOException("Unable to create thumbnail cache directory");
        }

        File temporary = File.createTempFile("local-thumb-", ".part", directory);
        try {
            try (FileOutputStream file = new FileOutputStream(temporary)) {
                file.write(bytes);
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Unable to replace incomplete thumbnail cache file");
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("Unable to finalise thumbnail cache file");
            }
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }
}
