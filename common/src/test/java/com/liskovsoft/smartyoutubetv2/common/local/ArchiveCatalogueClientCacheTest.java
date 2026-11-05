package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Regression tests for the cache-protection fix (Stage 3 corrective ticket,
 * Fix 2): a failed cache update must always preserve the previous valid cache
 * file. Exercises ArchiveCatalogueClient.updateCache directly (package-private
 * static, no Android runtime needed).
 */
public class ArchiveCatalogueClientCacheTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String read(File file) throws Exception {
        ByteArrayOutputStream2 out = new ByteArrayOutputStream2();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), UTF8);
    }

    private static class ByteArrayOutputStream2 extends java.io.ByteArrayOutputStream {}

    @Test
    public void successfulUpdateReplacesPreviousCache() throws Exception {
        File dir = folder.newFolder();
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "library.json", "v1"));
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "library.json", "v2"));
        assertTrue(new File(dir, "library.json").isFile());
        assertTrue("v2".equals(read(new File(dir, "library.json"))));
        assertFalse("backup must be cleaned up", new File(dir, "library.json.bak").exists());
        assertFalse("tmp must be cleaned up", new File(dir, "library.json.tmp").exists());
    }

    @Test
    public void failedWritePreservesPreviousCache() throws Exception {
        File dir = folder.newFolder();
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "library.json", "v1"));
        // Make the tmp write impossible: occupy the tmp path with a directory.
        File tmp = new File(dir, "library.json.tmp");
        assertTrue(tmp.mkdirs());
        assertFalse(ArchiveCatalogueClient.updateCache(dir, "library.json", "v2-bad"));
        assertTrue("previous cache preserved", "v1".equals(read(new File(dir, "library.json"))));
    }

    @Test
    public void failedRenamePreservesPreviousCache() throws Exception {
        File dir = folder.newFolder();
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "library.json", "v1"));
        // Make the target->backup rename impossible: occupy the backup path.
        File backup = new File(dir, "library.json.bak");
        assertTrue(backup.mkdirs());
        assertFalse(ArchiveCatalogueClient.updateCache(dir, "library.json", "v2-bad"));
        assertTrue("previous cache preserved", "v1".equals(read(new File(dir, "library.json"))));
        assertFalse("failed replacement must not be installed",
                new File(dir, "library.json").isDirectory());
    }

    // NOTE: the mid-swap branch (backup rename succeeds, tmp->target rename
    // fails, previous cache restored from backup) cannot be triggered through
    // this seam: updateCache controls both renames internally and the failure
    // injection points available from outside (occupying tmp or backup paths)
    // are exercised above. Reported as a test limitation, not claimed PASS.

    @Test
    public void firstUpdateWithoutPreviousCacheSucceeds() throws Exception {
        File dir = folder.newFolder();
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "channel-a.json", "c1"));
        assertTrue("c1".equals(read(new File(dir, "channel-a.json"))));
    }

    @Test
    public void malformedJsonIsRejectedBeforeCacheWrite() {
        // Callers validate before writeCache; malformed JSON must throw and
        // therefore never reach updateCache.
        boolean threw = false;
        try {
            ArchiveCatalogue.parseLibrary("{\"formatVersion\":99,\"revision\":1,\"channels\":[]}");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue("unsupported format version must be rejected", threw);
        threw = false;
        try {
            ArchiveCatalogue.parseLibrary("{not json");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue("malformed JSON must be rejected", threw);
    }

    @Test
    public void restartAfterFailedUpdateRestoresPreviousCatalogue() throws Exception {
        // Simulates a restart: a fresh read of the cache file must return the
        // previous valid content after any failed update.
        File dir = folder.newFolder();
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "library.json", "{\"valid\":1}"));
        File tmp = new File(dir, "library.json.tmp");
        assertTrue(tmp.mkdirs()); // force the next update to fail
        ArchiveCatalogueClient.updateCache(dir, "library.json", "{\"bad\":2}");
        assertTrue("{\"valid\":1}".equals(read(new File(dir, "library.json"))));
    }
}
