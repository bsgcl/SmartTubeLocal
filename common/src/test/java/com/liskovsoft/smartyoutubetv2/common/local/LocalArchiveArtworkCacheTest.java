package com.liskovsoft.smartyoutubetv2.common.local;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class LocalArchiveArtworkCacheTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void missingAndEmptyFilesAreNotValidThumbnails() throws Exception {
        File empty = directory.newFile("empty.webp");
        assertFalse(LocalArchiveArtworkCache.isUsable(empty));
        assertFalse(LocalArchiveArtworkCache.isUsable(new File(directory.getRoot(), "missing.webp")));
    }

    @Test
    public void validThumbnailWritesToExistingCacheFile() throws Exception {
        File output = new File(directory.getRoot(), "thumbnail.webp");
        byte[] bytes = new byte[] { 'R', 'I', 'F', 'F', 'W', 'E', 'B', 'P' };
        LocalArchiveArtworkCache.save(output, bytes, 512 * 1024);
        assertTrue(LocalArchiveArtworkCache.isUsable(output));
        assertArrayEquals(bytes, Files.readAllBytes(output.toPath()));
    }

    @Test
    public void rejectsEmptyAndOversizedFilesWithoutDamagingPreviousImage() throws Exception {
        File output = new File(directory.getRoot(), "thumbnail.webp");
        byte[] original = new byte[] { 1, 2, 3, 4 };
        LocalArchiveArtworkCache.save(output, original, 4);
        for (byte[] rejected : new byte[][] { new byte[0], new byte[5] }) {
            try {
                LocalArchiveArtworkCache.save(output, rejected, 4);
                fail("Expected size limit");
            } catch (IOException expected) {
                assertArrayEquals(original, Files.readAllBytes(output.toPath()));
            }
        }
    }

    @Test
    public void replacesIncompleteCacheFile() throws Exception {
        File output = directory.newFile("incomplete.webp");
        assertFalse(LocalArchiveArtworkCache.isUsable(output));
        byte[] image = new byte[] { 1, 2, 3 };
        LocalArchiveArtworkCache.save(output, image, 16);
        assertTrue(LocalArchiveArtworkCache.isUsable(output));
        assertArrayEquals(image, Files.readAllBytes(output.toPath()));
    }

    @Test
    public void missingDirectoryIsCreatedAndTemporaryFilesAreRemoved() throws Exception {
        File output = new File(directory.getRoot(), "new/cache/thumbnail.webp");
        LocalArchiveArtworkCache.save(output, new byte[] { 1, 2, 3 }, 16);
        assertTrue(LocalArchiveArtworkCache.isUsable(output));
        assertEquals(1, output.getParentFile().listFiles().length);
    }
}
