package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LocalArchiveChannelRowsTest {
    private static final LocalArchiveChannelRows.Metadata<TestVideo> METADATA =
            new LocalArchiveChannelRows.Metadata<TestVideo>() {
                @Override
                public String channelId(TestVideo video) {
                    return video.channelId;
                }

                @Override
                public String channelName(TestVideo video) {
                    return video.channelName;
                }
            };

    @Test
    public void groupsVideosByCanonicalIdAndKeepsDifferentIdsWithTheSameNameSeparate() {
        TestVideo first = new TestVideo("UC-one", "Same name");
        TestVideo second = new TestVideo("UC-one", "Same name");
        TestVideo third = new TestVideo("UC-two", "Same name");

        List<LocalArchiveChannelRows.Row<TestVideo>> rows =
                LocalArchiveChannelRows.group(Arrays.asList(first, second, third), METADATA);

        assertEquals(2, rows.size());
        assertEquals("UC-one", rows.get(0).channelId);
        assertEquals(Arrays.asList(first, second), rows.get(0).videos);
        assertEquals("UC-two", rows.get(1).channelId);
        assertEquals(Collections.singletonList(third), rows.get(1).videos);
    }

    @Test
    public void prefixesEachChannelNameOnce() {
        List<LocalArchiveChannelRows.Row<TestVideo>> rows = LocalArchiveChannelRows.group(
                Collections.singletonList(new TestVideo("UC-one", "Khan Academy")), METADATA);

        assertEquals("[LOCAL] Khan Academy", rows.get(0).title);
        assertEquals(1, occurrences(rows.get(0).title, "[LOCAL]"));
    }

    @Test
    public void emptyOrNullArchiveHasNoRows() {
        assertTrue(LocalArchiveChannelRows.group(Collections.<TestVideo>emptyList(), METADATA).isEmpty());
        assertTrue(LocalArchiveChannelRows.group(null, METADATA).isEmpty());
    }

    @Test
    public void rebuildingRowsForRefreshDoesNotAccumulateOrChangeTheirIds() {
        List<TestVideo> archive = Arrays.asList(
                new TestVideo("UC-one", "First"), new TestVideo("UC-one", "First"),
                new TestVideo("UC-two", "Second"));

        List<LocalArchiveChannelRows.Row<TestVideo>> first = LocalArchiveChannelRows.group(archive, METADATA);
        List<LocalArchiveChannelRows.Row<TestVideo>> refreshed = LocalArchiveChannelRows.group(archive, METADATA);

        assertEquals(2, first.size());
        assertEquals(first.size(), refreshed.size());
        assertEquals(first.get(0).id, refreshed.get(0).id);
        assertEquals(first.get(1).id, refreshed.get(1).id);
    }

    @Test
    public void distinctChannelIdsWithCollidingHashesGetDistinctAdapterIds() {
        // Java String.hashCode collision: "Aa" and "BB" have the same hash.
        List<LocalArchiveChannelRows.Row<TestVideo>> rows = LocalArchiveChannelRows.group(
                Arrays.asList(new TestVideo("Aa", "First"), new TestVideo("BB", "Second")), METADATA);

        assertEquals(2, rows.size());
        assertNotEquals(rows.get(0).id, rows.get(1).id);
    }

    @Test
    public void missingChannelIdsDoNotCrashOrMergeByName() {
        List<LocalArchiveChannelRows.Row<TestVideo>> rows = LocalArchiveChannelRows.group(
                Arrays.asList(new TestVideo(null, "Same name"), new TestVideo("  ", "Same name")), METADATA);

        assertEquals(2, rows.size());
        assertEquals("[LOCAL] Same name", rows.get(0).title);
        assertEquals("[LOCAL] Same name", rows.get(1).title);
    }

    private static int occurrences(String value, String token) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            result++;
            offset += token.length();
        }
        return result;
    }

    private static final class TestVideo {
        final String channelId;
        final String channelName;

        TestVideo(String channelId, String channelName) {
            this.channelId = channelId;
            this.channelName = channelName;
        }
    }
}
