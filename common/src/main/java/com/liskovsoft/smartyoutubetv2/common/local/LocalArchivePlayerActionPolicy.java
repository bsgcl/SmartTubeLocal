package com.liskovsoft.smartyoutubetv2.common.local;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/** Keeps unsupported online actions out of local playback while allowing local Video Info. */
public final class LocalArchivePlayerActionPolicy {
    private LocalArchivePlayerActionPolicy() {
    }

    public static boolean shouldIgnoreButton(Video video, int buttonId) {
        return LocalArchiveSubscriptionPolicy.isLocalArchiveItem(video) &&
                (buttonId == R.id.action_channel || buttonId == R.id.action_share ||
                        buttonId == R.id.action_search || buttonId == R.id.action_playlist_add ||
                        buttonId == R.id.action_thumbs_down || buttonId == R.id.action_thumbs_up);
    }
}
