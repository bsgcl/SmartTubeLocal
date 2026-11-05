package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

    // ---- Crash-window recovery tests (Phase 2.2) ----

    @Test
    public void crashAfterBackupMoveRestoresPreviousCacheOnNextLaunch() throws Exception {
        // Process death between "target -> .bak" and "tmp -> target" leaves
        // the valid cache ONLY in .bak. recoverInterruptedUpdates (run at
        // construction, i.e. next launch) must restore it.
        File dir = folder.newFolder();
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "library.json", "{\"valid\":1}"));
        // Simulate the interrupted state: target renamed to .bak, tmp left behind.
        assertTrue(new File(dir, "library.json").renameTo(new File(dir, "library.json.bak")));
        write(new File(dir, "library.json.tmp"), "{\"half-written\":1}");
        assertFalse(new File(dir, "library.json").exists());

        ArchiveCatalogueClient.recoverInterruptedUpdates(dir);

        assertTrue("previous valid cache restored from .bak",
                "{\"valid\":1}".equals(read(new File(dir, "library.json"))));
        assertFalse("backup consumed", new File(dir, "library.json.bak").exists());
        assertFalse("stray tmp discarded", new File(dir, "library.json.tmp").exists());
    }

    @Test
    public void crashRecoveryWithValidTargetDropsStaleBackup() throws Exception {
        // If the update actually completed but .bak deletion was interrupted,
        // the next launch must keep the valid target and discard the backup.
        File dir = folder.newFolder();
        write(new File(dir, "library.json"), "{\"new\":2}");
        write(new File(dir, "library.json.bak"), "{\"old\":1}");
        write(new File(dir, "library.json.tmp"), "junk");

        ArchiveCatalogueClient.recoverInterruptedUpdates(dir);

        assertTrue("{\"new\":2}".equals(read(new File(dir, "library.json"))));
        assertFalse(new File(dir, "library.json.bak").exists());
        assertFalse(new File(dir, "library.json.tmp").exists());
    }

    @Test
    public void crashRecoveryCoversChannelCaches() throws Exception {
        // Per-channel caches get the same recovery treatment.
        File dir = folder.newFolder();
        write(new File(dir, "channel-UCx.json.bak"), "{\"channel\":\"x\"}");
        assertFalse(new File(dir, "channel-UCx.json").exists());

        ArchiveCatalogueClient.recoverInterruptedUpdates(dir);

        assertTrue("{\"channel\":\"x\"}".equals(read(new File(dir, "channel-UCx.json"))));
    }

    @Test
    public void restartAfterCrashWindowThenNewUpdateSucceeds() throws Exception {
        // Full lifecycle: valid cache -> crash window -> recovery -> new
        // update installs normally.
        File dir = folder.newFolder();
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "library.json", "v1"));
        assertTrue(new File(dir, "library.json").renameTo(new File(dir, "library.json.bak")));
        ArchiveCatalogueClient.recoverInterruptedUpdates(dir);
        assertTrue("v1".equals(read(new File(dir, "library.json"))));
        assertTrue(ArchiveCatalogueClient.updateCache(dir, "library.json", "v2"));
        assertTrue("v2".equals(read(new File(dir, "library.json"))));
    }

    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(UTF8));
        }
    }
}
