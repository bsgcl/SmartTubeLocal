package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Plain JUnit tests: no Android framework or Robolectric runner. */
public class LocalArchiveJvmTest {
    private static final Map<String, String> EXPECTED_CHANNELS;
    private static final Map<String, Long> EXPECTED_DURATIONS;

    static {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("YE2peDF4Zqg", "UC4a-Gbdw7vOaccHmFo40b9g");
        expected.put("TBNCqRCsSvY", "UC4a-Gbdw7vOaccHmFo40b9g");
        expected.put("n6wpNhyreDE", "UCsooa4yRKGN_zEE8iknghZA");
        EXPECTED_CHANNELS = Collections.unmodifiableMap(expected);
        Map<String, Long> durations = new LinkedHashMap<>();
        durations.put("YE2peDF4Zqg", 253L);
        durations.put("TBNCqRCsSvY", 371L);
        durations.put("n6wpNhyreDE", 270L);
        EXPECTED_DURATIONS = Collections.unmodifiableMap(durations);
    }

    @Test
    public void parsesTheThreeActualRouterSidecarsAndTheirSiblings() throws Exception {
        String rootValue = System.getenv("SMARTTUBE_LOCAL_ARCHIVE_TEST_ROOT");
        assertNotNull("Set SMARTTUBE_LOCAL_ARCHIVE_TEST_ROOT to the read-only archive UNC path", rootValue);
        Path root = Paths.get(rootValue);
        assertTrue("Archive root is unavailable: " + root, Files.isDirectory(root));

        Map<String, String> sidecars = new LinkedHashMap<>();
        Map<String, JsonObject> parsedSidecars = new LinkedHashMap<>();
        Map<String, Long> fileLengths = new LinkedHashMap<>();
        List<String> unicodePaths = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String relative = relativePath(root, path);
                    long length = Files.size(path);
                    fileLengths.put(relative, length);
                    if (relative.chars().anyMatch(ch -> ch > 127)) unicodePaths.add(relative);
                    if (!relative.toLowerCase(java.util.Locale.ROOT).endsWith(".info.json")) return;

                    String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    JsonElement parsed = new JsonParser().parse(json);
                    assertTrue("Expected a JSON object: " + relative, parsed.isJsonObject());
                    JsonObject metadata = parsed.getAsJsonObject();
                    JsonElement id = metadata.get("id");
                    if (id != null && EXPECTED_CHANNELS.containsKey(id.getAsString())) {
                        sidecars.put(relative, json);
                        parsedSidecars.put(relative, metadata);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        assertEquals("Expected all three known archive sidecars", EXPECTED_CHANNELS.size(), sidecars.size());
        assertFalse("Actual archive paths should cover Unicode filenames", unicodePaths.isEmpty());

        List<LocalArchiveItem> items = LocalArchiveIndexCore.build(sidecars, fileLengths);
        assertEquals(3, items.size());
        Set<String> foundIds = new LinkedHashSet<>();
        for (LocalArchiveItem item : items) {
            foundIds.add(item.videoId);
            assertEquals(EXPECTED_CHANNELS.get(item.videoId), item.channelId);
            assertEquals(EXPECTED_DURATIONS.get(item.videoId).longValue(), item.durationSeconds);
            assertEquals("n6wpNhyreDE".equals(item.videoId) ? "TED-Ed" : "Khan Academy", item.channelName);
            assertEquals("Metadata title is read from the actual sidecar",
                    jsonValue(parsedSidecars.get(item.metadataPath), "title"), item.title);
            if (!"n6wpNhyreDE".equals(item.videoId)) {
                assertTrue("Actual Khan Academy filename keeps its Unicode separator",
                        item.metadataPath.chars().anyMatch(ch -> ch > 127));
            }
            assertTrue("Media path must stay archive-relative", !item.mediaPath.startsWith("/") && !item.mediaPath.contains(".."));
            assertTrue("Sidecar path should retain spaces/brackets", item.metadataPath.contains("[") && item.metadataPath.contains(" "));
            long sidecarBytes = fileLengths.get(item.metadataPath);
            assertTrue("Actual sidecars exceed the prior 512 KiB limit", sidecarBytes > 512L * 1024L);
            assertTrue("Sidecar reader remains bounded at 1 MiB", sidecarBytes <= 1024L * 1024L);
            assertTrue(item.isAvailable());
            assertTrue(item.mediaLength > 0);
            assertNotNull(item.artworkPath);
            assertTrue(fileLengths.containsKey(item.artworkPath));
        }
        assertEquals(EXPECTED_CHANNELS.keySet(), foundIds);
    }

    @Test
    public void rejectsMalformedAndIncompleteMetadata() {
        assertInvalid(() -> LocalArchiveIndexCore.build(singleton("x/video.info.json", "{broken"),
                Collections.<String, Long>emptyMap()));
        assertInvalid(() -> LocalArchiveIndexCore.build(singleton("x/video.info.json",
                        "{\"id\":\"YE2peDF4Zqg\",\"channel_id\":\"UCexample\"}"),
                Collections.<String, Long>emptyMap()));
    }

    @Test
    public void missingMediaDoesNotSelectAnUnrelatedFile() {
        String metadata = "{\"id\":\"n6wpNhyreDE\",\"channel_id\":\"UCsooa4yRKGN_zEE8iknghZA\",\"title\":\"Lesson\"}";
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("TED-Ed/another [aaaaaaaaaaa].mp4", 10L);
        LocalArchiveItem item = LocalArchiveIndexCore.build(singleton("TED-Ed/Lesson [n6wpNhyreDE].info.json", metadata), files).get(0);
        assertFalse(item.isAvailable());
        assertNull(item.mediaPath);
    }

    @Test
    public void rejectsAbsoluteDriveAndTraversalPaths() {
        for (String path : Arrays.asList("../escape.mp4", "a/../../escape.mp4", "/absolute.mp4", "C:/escape.mp4")) {
            assertInvalid(() -> LocalArchiveIndexCore.requireSafeRelativePath(path));
        }
        assertEquals("folder/Caf\u00e9 [video_01].mp4",
                LocalArchiveIndexCore.requireSafeRelativePath("folder/Caf\u00e9 [video_01].mp4"));
    }

    @Test
    public void keepsWholeLibraryPermissionDecisionsIsolatedByProfile() {
        Map<String, Boolean> assignments = LocalArchiveAccessPolicy.assignments("profile-a", true,
                "profile-b", false);

        assertTrue(LocalArchiveAccessPolicy.hasLibraryAccess("profile-a", assignments));
        assertFalse(LocalArchiveAccessPolicy.hasLibraryAccess("profile-b", assignments));
        assertFalse(LocalArchiveAccessPolicy.hasLibraryAccess("unknown", assignments));
        assertFalse(LocalArchiveAccessPolicy.hasLibraryAccess(null, assignments));

        assertTrue(LocalArchiveAccessPolicy.shouldAttachLocalCopy("YE2peDF4Zqg", "YE2peDF4Zqg", true));
        assertFalse(LocalArchiveAccessPolicy.shouldAttachLocalCopy("YE2peDF4Zqg", "TBNCqRCsSvY", true));
        assertFalse(LocalArchiveAccessPolicy.shouldAttachLocalCopy("YE2peDF4Zqg", "YE2peDF4Zqg", false));
        assertTrue(LocalArchiveAccessPolicy.shouldPreferLocalPlayback(true, true));
        assertFalse(LocalArchiveAccessPolicy.shouldPreferLocalPlayback(true, false));
        assertFalse(LocalArchiveAccessPolicy.shouldPreferLocalPlayback(false, true));
    }

    @Test
    public void deduplicatesActualCanonicalIdentityAcrossRepeatedSidecars() {
        String first = "{\"id\":\"TBNCqRCsSvY\",\"channel_id\":\"UC4a-Gbdw7vOaccHmFo40b9g\",\"title\":\"First\"}";
        String second = "{\"id\":\"TBNCqRCsSvY\",\"channel_id\":\"UC4a-Gbdw7vOaccHmFo40b9g\",\"title\":\"Duplicate\"}";
        Map<String, String> sidecars = new LinkedHashMap<>();
        sidecars.put("one/Video [TBNCqRCsSvY].info.json", first);
        sidecars.put("two/Video [TBNCqRCsSvY].info.json", second);
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("one/Video [TBNCqRCsSvY].mp4", 10L);
        files.put("two/Video [TBNCqRCsSvY].mp4", 20L);

        List<LocalArchiveItem> merged = LocalArchiveIndexCore.build(sidecars, files);

        assertEquals(1, merged.size());
        assertEquals("TBNCqRCsSvY", merged.get(0).videoId);
        assertEquals("UC4a-Gbdw7vOaccHmFo40b9g", merged.get(0).channelId);
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String jsonValue(JsonObject json, String key) {
        return json.get(key).getAsString();
    }

    private static Map<String, String> singleton(String key, String value) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
            fail("Expected invalid archive metadata or path");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
