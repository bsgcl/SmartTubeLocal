package com.liskovsoft.smartyoutubetv2.common.local;

/** Selection/profile token for asynchronous Local Library UI results. */
public final class LocalArchiveRequestGuard implements com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener {
    private long generation;

    public synchronized long begin() {
        return ++generation;
    }

    public synchronized void invalidate() {
        generation++;
    }

    @Override
    public void onProfileChanged() {
        invalidate();
    }

    public synchronized boolean isCurrent(long request, String requestedVideoId, String currentVideoId,
                                          String requestedProfileId, String currentProfileId) {
        return isCurrent(request, requestedVideoId, currentVideoId, requestedProfileId, currentProfileId, 0, 0);
    }

    public synchronized boolean isCurrent(long request, String requestedVideoId, String currentVideoId,
                                          String requestedProfileId, String currentProfileId,
                                          long requestedDialogGeneration, long currentDialogGeneration) {
        return request == generation && requestedVideoId != null && requestedVideoId.equals(currentVideoId) &&
                requestedProfileId != null && requestedProfileId.equals(currentProfileId) &&
                requestedDialogGeneration == currentDialogGeneration;
    }
}
