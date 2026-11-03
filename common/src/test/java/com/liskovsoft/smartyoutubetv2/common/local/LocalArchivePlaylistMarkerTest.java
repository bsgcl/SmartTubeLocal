package com.liskovsoft.smartyoutubetv2.common.local;

import org.junit.Test;

import static org.junit.Assert.*;

public class LocalArchivePlaylistMarkerTest {
    @Test public void markerRoundTripsWithoutChangingVisibleBadge() {
        String encoded = LocalArchivePlaylistMarker.encode("LOCAL");
        assertTrue(LocalArchivePlaylistMarker.isMarked(encoded));
        assertEquals("LOCAL", LocalArchivePlaylistMarker.visibleBadge(encoded));
    }

    @Test public void ordinaryBadgesRemainUnmarked() {
        assertFalse(LocalArchivePlaylistMarker.isMarked("HD"));
        assertEquals("LOCAL", LocalArchivePlaylistMarker.visibleBadge("LOCAL"));
        assertNull(LocalArchivePlaylistMarker.visibleBadge(null));
    }
}
