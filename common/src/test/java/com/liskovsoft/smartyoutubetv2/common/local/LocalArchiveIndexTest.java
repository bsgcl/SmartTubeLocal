package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class LocalArchiveIndexTest {
    @Test
    public void buildsCanonicalEntryFromUnicodeFilenameAndSidecar() throws Exception {
        Map<String, String> sidecars = new LinkedHashMap<>();
        sidecars.put("Khan Academy/Algebra [YE2peDF4Zqg].info.json",
                "{\"id\":\"YE2peDF4Zqg\",\"channel_id\":\"UC4a-Gbdw7vOaccHmFo40b9g\",\"title\":\"Algebra — café\",\"channel\":\"Khan Academy\",\"duration\":73}");
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("Khan Academy/Algebra [YE2peDF4Zqg].info.json", 200L);
        files.put("Khan Academy/Algebra [YE2peDF4Zqg].mp4", 12345L);
        files.put("Khan Academy/Algebra [YE2peDF4Zqg].webp", 900L);

        List<LocalArchiveItem> entries = LocalArchiveIndex.build(sidecars, files);

        assertEquals(1, entries.size());
        LocalArchiveItem item = entries.get(0);
        assertEquals("YE2peDF4Zqg", item.videoId);
        assertEquals("UC4a-Gbdw7vOaccHmFo40b9g", item.channelId);
        assertEquals("Algebra — café", item.title);
        assertEquals("Khan Academy", item.channelName);
        assertEquals("Khan Academy/Algebra [YE2peDF4Zqg].mp4", item.mediaPath);
        assertEquals("Khan Academy/Algebra [YE2peDF4Zqg].webp", item.artworkPath);
        assertEquals(12345L, item.mediaLength);
        assertTrue(item.isAvailable());
    }

    @Test
    public void deduplicatesSidecarsByCanonicalVideoId() throws Exception {
        Map<String, String> sidecars = new LinkedHashMap<>();
        sidecars.put("one/Video [TBNCqRCsSvY].info.json", metadata("TBNCqRCsSvY", "First title"));
        sidecars.put("two/Video [TBNCqRCsSvY].info.json", metadata("TBNCqRCsSvY", "Duplicate title"));
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("one/Video [TBNCqRCsSvY].mp4", 10L);
        files.put("two/Video [TBNCqRCsSvY].mp4", 20L);

        List<LocalArchiveItem> entries = LocalArchiveIndex.build(sidecars, files);

        assertEquals(1, entries.size());
        assertEquals("TBNCqRCsSvY", entries.get(0).videoId);
    }

    @Test
    public void missingMediaIsUnavailableAndDoesNotSelectAnUnrelatedFile() throws Exception {
        Map<String, String> sidecars = new LinkedHashMap<>();
        sidecars.put("TED-Ed/Lesson [n6wpNhyreDE].info.json", metadata("n6wpNhyreDE", "Lesson"));
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("TED-Ed/another [aaaaaaaaaaa].mp4", 10L);

        LocalArchiveItem item = LocalArchiveIndex.build(sidecars, files).get(0);

        assertFalse(item.isAvailable());
        assertNull(item.mediaPath);
    }

    @Test
    public void rejectsMalformedMetadataAndPathTraversal() throws Exception {
        try {
            LocalArchiveIndex.build(singleton("root/video.info.json", "{bad json"), new LinkedHashMap<>());
            fail("Expected malformed JSON to be rejected");
        } catch (JSONException expected) {
            assertNotNull(expected.getMessage());
        }

        for (String path : new String[] {"../escape.mp4", "a/../../escape.mp4", "/absolute.mp4", "C:/escape.mp4"}) {
            try {
                LocalArchiveIndex.requireSafeRelativePath(path);
                fail("Expected unsafe path rejection: " + path);
            } catch (JSONException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    private static Map<String, String> singleton(String path, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(path, value);
        return values;
    }

    private static String metadata(String id, String title) {
        return "{\"id\":\"" + id + "\",\"channel_id\":\"UCexample\",\"title\":\"" + title + "\",\"channel\":\"Example\"}";
    }
}
