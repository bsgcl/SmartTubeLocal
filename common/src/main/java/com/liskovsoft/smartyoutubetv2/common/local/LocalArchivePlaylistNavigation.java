package com.liskovsoft.smartyoutubetv2.common.local;

/** Index rules for a finite saved playlist containing archive entries. */
public final class LocalArchivePlaylistNavigation {
    public interface RandomIndex { int choose(int currentIndex, int size); }

    private LocalArchivePlaylistNavigation() { }

    public static int nextIndex(int size, int currentIndex, int mode, int shuffleMode,
                                int listMode, int reverseMode, RandomIndex random) {
        if (size <= 0 || currentIndex < 0 || currentIndex >= size) return -1;
        if (mode == shuffleMode && size > 1) {
            int next = random.choose(currentIndex, size);
            return next >= 0 && next < size && next != currentIndex ? next : -1;
        }
        if (mode == reverseMode) return currentIndex > 0 ? currentIndex - 1 : size > 1 ? size - 1 : -1;
        if (currentIndex + 1 < size) return currentIndex + 1;
        return mode == listMode ? 0 : -1;
    }

    public static int previousIndex(int size, int currentIndex) {
        return size > 0 && currentIndex > 0 && currentIndex < size ? currentIndex - 1 : -1;
    }
}
