package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Validation and source-order checks for the versioned local playlist index. */
public class ArchiveCataloguePlaylistTest {
    private static final String PLAYLIST = "PLb61ADzf2-R2cwR-4ucpZKPARz5YeM5XV";
    private static final String CHANNEL = "UC1234567890";

    private String index(int revision) {
        return "{\"formatVersion\":1,\"revision\":" + revision + ",\"playlists\":["
                + "{\"playlistId\":\"" + PLAYLIST + "\",\"title\":\"Hip - LearningMovements\","
                + "\"channelId\":\"" + CHANNEL + "\",\"channelTitle\":\"LearningMovements\","
                + "\"videoCount\":3,\"sourceVideoCount\":4,\"missingVideoCount\":1,"
                + "\"thumbnailVideoId\":\"abcdefghijk\"}]}";
    }

    private String detail(int revision) {
        return "{\"formatVersion\":1,\"revision\":" + revision + ",\"playlistId\":\"" + PLAYLIST
                + "\",\"title\":\"Hip - LearningMovements\",\"channelId\":\"" + CHANNEL
                + "\",\"channelTitle\":\"LearningMovements\",\"sourceVideoCount\":4,"
                + "\"missingVideoCount\":1,\"thumbnailVideoId\":\"abcdefghijk\","
                + "\"videoIds\":[\"abcdefghijk\",\"lmnopqrstuv\",\"abcdefghijk\"]}";
    }

    @Test
    public void preservesPlaylistIdentityTitleOrderDuplicatesAndMissingCount() {
        List<ArchiveCatalogue.PlaylistSummary> summaries =
                ArchiveCatalogue.parsePlaylistsIndex(index(12), 12);
        assertEquals(1, summaries.size());
        ArchiveCatalogue.PlaylistSummary summary = summaries.get(0);
        assertEquals(PLAYLIST, summary.playlistId);
        assertEquals("Hip - LearningMovements", summary.title);
        assertEquals(1, summary.missingVideoCount);
        assertEquals("abcdefghijk", summary.thumbnailVideoId);

        ArchiveCatalogue.PlaylistDocument parsed =
                ArchiveCatalogue.parsePlaylist(detail(12), 12, summary);
        assertEquals(3, parsed.videoIds.size());
        assertEquals("abcdefghijk", parsed.videoIds.get(0));
        assertEquals("lmnopqrstuv", parsed.videoIds.get(1));
        assertEquals("abcdefghijk", parsed.videoIds.get(2));
    }

    @Test
    public void permitsEmptyPlaylistWithNoThumbnail() {
        String json = "{\"formatVersion\":1,\"revision\":1,\"playlists\":["
                + "{\"playlistId\":\"PL1234567890\",\"title\":\"Empty\","
                + "\"channelId\":\"UC1234567890\",\"channelTitle\":\"LearningMovements\","
                + "\"videoCount\":0,\"sourceVideoCount\":0,\"missingVideoCount\":0,"
                + "\"thumbnailVideoId\":null}]}";
        ArchiveCatalogue.PlaylistSummary summary =
                ArchiveCatalogue.parsePlaylistsIndex(json, 1).get(0);
        assertEquals(0, summary.videoCount);
        assertNull(summary.thumbnailVideoId);
    }

    @Test
    public void rejectsRevisionMismatchDuplicateIdsAndInconsistentCounts() {
        try {
            ArchiveCatalogue.parsePlaylistsIndex(index(12), 13);
            throw new AssertionError("revision mismatch accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("revision"));
        }
        String index = index(12);
        String record = index.substring(index.indexOf("{\"playlistId\""), index.lastIndexOf("}]") + 1);
        String duplicate = "{\"formatVersion\":1,\"revision\":12,\"playlists\":["
                + record + "," + record + "]}";
        try {
            ArchiveCatalogue.parsePlaylistsIndex(duplicate, 12);
            throw new AssertionError("duplicate playlist ID accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate"));
        }
        String inconsistent = index(12).replace("\"missingVideoCount\":1", "\"missingVideoCount\":0");
        try {
            ArchiveCatalogue.parsePlaylistsIndex(inconsistent, 12);
            throw new AssertionError("inconsistent counts accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    public void rejectsStaleOrMismatchedPlaylistDetail() {
        ArchiveCatalogue.PlaylistSummary summary =
                ArchiveCatalogue.parsePlaylistsIndex(index(12), 12).get(0);
        try {
            ArchiveCatalogue.parsePlaylist(detail(11), 12, summary);
            throw new AssertionError("stale detail accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("revision"));
        }
        String wrongIdentity = detail(12).replace(PLAYLIST, "PL9999999999");
        try {
            ArchiveCatalogue.parsePlaylist(wrongIdentity, 12, summary);
            throw new AssertionError("mismatched detail accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("identity"));
        }
    }
}
