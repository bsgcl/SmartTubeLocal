package com.liskovsoft.smartyoutubetv2.common.local;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LocalArchivePlaylistNavigationTest {
    private static final int SHUFFLE = 4;
    private static final int LIST = 5;
    private static final int REVERSE = 6;

    @Test public void nextAndPreviousFollowSavedOrder() {
        assertEquals(1, next(3, 0, 2, (current, size) -> 2));
        assertEquals(1, LocalArchivePlaylistNavigation.previousIndex(3, 2));
        assertEquals(-1, LocalArchivePlaylistNavigation.previousIndex(3, 0));
    }

    @Test public void listRepeatsAndReverseWalksBackThroughEntries() {
        assertEquals(0, next(3, 2, LIST, (current, size) -> 1));
        assertEquals(1, next(3, 2, REVERSE, (current, size) -> 1));
        assertEquals(2, next(3, 0, REVERSE, (current, size) -> 1));
    }

    @Test public void shuffleChoosesAnotherValidEntry() {
        assertEquals(2, next(3, 0, SHUFFLE, (current, size) -> 2));
        assertEquals(-1, next(1, 0, SHUFFLE, (current, size) -> current));
    }

    private int next(int size, int current, int mode, LocalArchivePlaylistNavigation.RandomIndex random) {
        return LocalArchivePlaylistNavigation.nextIndex(size, current, mode, SHUFFLE, LIST, REVERSE, random);
    }
}
