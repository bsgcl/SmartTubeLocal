package com.liskovsoft.smartyoutubetv2.common.app.presenters;

/** Tracks the single active channel-content load and rejects late terminal callbacks. */
final class ChannelUploadsLoadState {
    private long generation;
    private boolean loading;

    long begin() {
        loading = true;
        return ++generation;
    }

    boolean isCurrent(long request) {
        return request == generation && loading;
    }

    boolean finish(long request) {
        if (!isCurrent(request)) return false;
        loading = false;
        return true;
    }

    boolean cancel() {
        boolean wasLoading = loading;
        loading = false;
        generation++;
        return wasLoading;
    }

    boolean isLoading() {
        return loading;
    }
}
