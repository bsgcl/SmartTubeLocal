package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stage 3 catalogue consumer tests: synthetic 1,500-video catalogue, grouping,
 *  ordering, malformed-entry tolerance, duplicate/unsafe/unsupported rejection. */
public class ArchiveCatalogueTest {

    // ---------- synthetic 1,500-video catalogue ----------

    private static String syntheticLibrary(int channels, int perChannel) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"formatVersion\":1,\"revision\":42,\"generatedAt\":\"2026-11-05T00:00:00Z\",\"channels\":[");
        for (int c = 0; c < channels; c++) {
            if (c > 0) sb.append(',');
            String channelId = String.format("UC%010d", c);
            sb.append("{\"channelId\":\"").append(channelId).append("\",\"title\":\"Channel ")
              .append(c).append("\",\"videoCount\":").append(perChannel).append(",\"videos\":[");
            for (int v = 0; v < perChannel; v++) {
                if (v > 0) sb.append(',');
                // Zero-padded title guarantees deterministic alphabetical order.
                String videoId = String.format("v%04dc%03dxyz", v, c);
                sb.append("{\"videoId\":\"").append(videoId).append("\",\"title\":\"")
                  .append(String.format("T%05d", v)).append("\",\"duration\":").append(v * 10)
                  .append(",\"mediaPath\":\"Channel ").append(c).append("/T").append(v)
                  .append(".mp4\",\"mediaLength\":").append(v * 1000)
                  .append(",\"thumbPath\":\"Channel ").append(c).append("/T").append(v).append(".webp\"}");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    public void library1500VideosParsesGroupsAndOrdersStably() {
        String json = syntheticLibrary(15, 100); // 15 channels x 100 = 1,500 videos
        ArchiveCatalogue.CatalogueLibrary lib = ArchiveCatalogue.parseLibrary(json);
        assertEquals(15, lib.channels.size());
        assertEquals(1500, lib.videoIndex.size());

        int total = 0;
        for (ArchiveCatalogue.ChannelSummary channel : lib.channels) {
            assertEquals(100, channel.videos.size());
            // Stable ordering: titles are zero-padded, so consecutive titles ascend.
            for (int v = 1; v < channel.videos.size(); v++) {
                assertTrue(channel.videos.get(v - 1).title.compareTo(channel.videos.get(v).title) <= 0);
            }
            // Identity: every video maps back to its own channel.
            for (ArchiveCatalogue.VideoSummary video : channel.videos) {
                assertEquals(channel.channelId, lib.channelOf(video.videoId));
                total++;
            }
        }
        assertEquals(1500, total);

        // Items built for every channel with media + artwork paths.
        for (ArchiveCatalogue.ChannelSummary channel : lib.channels) {
            List<LocalArchiveItem> items = lib.itemsByChannel.get(channel.channelId);
            assertNotNull(items);
            assertEquals(100, items.size());
            for (LocalArchiveItem item : items) {
                assertNotNull(item.mediaPath);
                assertNotNull(item.artworkPath);
                assertTrue(item.isAvailable());
                assertEquals(channel.channelId, item.channelId);
            }
        }
    }

    @Test
    public void pointerAndChannelRoundTrip() {
        assertEquals(7, ArchiveCatalogue.parsePointer("{\"formatVersion\":1,\"revision\":7}"));

        String channelJson = "{\"formatVersion\":1,\"channelId\":\"UC123456789\","
                + "\"title\":\"Ch\",\"videos\":["
                + "{\"videoId\":\"abcdefghijk\",\"title\":\"V1\",\"duration\":10,"
                + "\"mediaPath\":\"Ch/V1.mp4\",\"mediaLength\":1234,\"thumbPath\":\"Ch/V1.webp\"},"
                + "{\"videoId\":\"lmnopqrstuv\",\"title\":\"V2\",\"duration\":20,"
                + "\"mediaPath\":null,\"mediaLength\":-1,\"thumbPath\":null}]}";
        List<LocalArchiveItem> items =
                ArchiveCatalogue.parseChannel("UC123456789", "Ch", channelJson);
        assertEquals(2, items.size());
        assertTrue(items.get(0).isAvailable());
        assertEquals(1234, items.get(0).mediaLength);
        assertFalse(items.get(1).isAvailable()); // metadata-only placeholder
        assertNull(items.get(1).mediaPath);
    }

    // ---------- rejection and tolerance ----------

    @Test
    public void unsupportedVersionRejected() {
        for (String json : new String[]{
                "{\"formatVersion\":2,\"revision\":1}",
                "{\"revision\":1}",
                "not json"}) {
            try {
                ArchiveCatalogue.parsePointer(json);
                throw new AssertionError("accepted: " + json);
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void duplicateVideoIdRejected() {
        String json = "{\"formatVersion\":1,\"revision\":1,\"channels\":[{"
                + "\"channelId\":\"UC123456789\",\"title\":\"C\",\"videos\":["
                + "{\"videoId\":\"abcdefghijk\",\"title\":\"A\"},"
                + "{\"videoId\":\"abcdefghijk\",\"title\":\"B\"}]}]}";
        try {
            ArchiveCatalogue.parseLibrary(json);
            throw new AssertionError("duplicate accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void invalidIdRejected() {
        String json = "{\"formatVersion\":1,\"revision\":1,\"channels\":[{"
                + "\"channelId\":\"../evil\",\"title\":\"C\",\"videos\":[]}]}";
        try {
            ArchiveCatalogue.parseLibrary(json);
            throw new AssertionError("bad channel id accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void unsafeMediaPathDroppedEntryKept() {
        String channelJson = "{\"formatVersion\":1,\"channelId\":\"UC123456789\","
                + "\"title\":\"Ch\",\"videos\":["
                + "{\"videoId\":\"abcdefghijk\",\"title\":\"V1\","
                + "\"mediaPath\":\"../../etc/passwd\",\"thumbPath\":null},"
                + "{\"videoId\":\"lmnopqrstuv\",\"title\":\"V2\","
                + "\"mediaPath\":\"Ch/V2.mp4\",\"mediaLength\":5}]}";
        List<LocalArchiveItem> items =
                ArchiveCatalogue.parseChannel("UC123456789", "Ch", channelJson);
        // The unsafe-path entry is indexed without media access; the other survives.
        assertEquals(2, items.size());
        assertFalse(items.get(0).isAvailable());
        assertTrue(items.get(1).isAvailable());
    }

    @Test
    public void malformedEntryDroppedRestKept() {
        String channelJson = "{\"formatVersion\":1,\"channelId\":\"UC123456789\","
                + "\"title\":\"Ch\",\"videos\":["
                + "{\"title\":\"no id\"},"
                + "{\"videoId\":\"lmnopqrstuv\",\"title\":\"V2\",\"mediaPath\":\"Ch/V2.mp4\"}]}";
        List<LocalArchiveItem> items =
                ArchiveCatalogue.parseChannel("UC123456789", "Ch", channelJson);
        assertEquals(1, items.size());
        assertEquals("lmnopqrstuv", items.get(0).videoId);
    }

    @Test
    public void channelIdentityMismatchRejected() {
        String channelJson = "{\"formatVersion\":1,\"channelId\":\"UC999999999\","
                + "\"title\":\"Other\",\"videos\":[]}";
        try {
            ArchiveCatalogue.parseChannel("UC123456789", "Ch", channelJson);
            throw new AssertionError("mismatch accepted");
        } catch (IllegalArgumentException expected) {
        }
    }
}
