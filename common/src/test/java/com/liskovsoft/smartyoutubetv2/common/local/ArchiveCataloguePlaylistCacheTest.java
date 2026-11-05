package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/** Crash recovery for a revision-keyed playlist detail cache. */
public class ArchiveCataloguePlaylistCacheTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void restoresPlaylistDetailAfterInterruptedCacheReplacement() throws Exception {
        File dir = folder.newFolder();
        String cacheName = "playlist-r94-PL1234567890.json";
        File target = new File(dir, cacheName);
        File backup = new File(dir, cacheName + ".bak");
        File tmp = new File(dir, cacheName + ".tmp");
        write(backup, "{\"revision\":94,\"videoIds\":[\"abcdefghijk\"]}");
        write(tmp, "{\"partial\":");

        ArchiveCatalogueClient.recoverInterruptedUpdates(dir);

        assertTrue(target.isFile());
        assertTrue(read(target).contains("\"revision\":94"));
        assertFalse(backup.exists());
        assertFalse(tmp.exists());

        assertTrue(ArchiveCatalogueClient.updateCache(dir, cacheName,
                "{\"revision\":94,\"videoIds\":[\"lmnopqrstuv\"]}"));
        assertTrue(read(target).contains("lmnopqrstuv"));
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(Charset.forName("UTF-8")));
        }
    }

    private static String read(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = in.read(bytes);
            return new String(bytes, 0, Math.max(0, read), Charset.forName("UTF-8"));
        }
    }
}
