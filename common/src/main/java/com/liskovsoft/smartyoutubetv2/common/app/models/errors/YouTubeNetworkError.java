package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

import java.net.UnknownHostException;
import java.util.Locale;

/** Detect YouTube DNS failures without hiding unrelated network or archive errors. */
public final class YouTubeNetworkError {
    private YouTubeNetworkError() {
    }

    public static boolean isYouTubeDnsFailure(Throwable error) {
        for (int i = 0; error != null && i < 16; i++, error = error.getCause()) {
            if (!(error instanceof UnknownHostException)) {
                continue;
            }
            String message = error.getMessage();
            if (message == null) {
                continue;
            }
            String host = message.toLowerCase(Locale.ROOT);
            if (host.contains("youtube.com") || host.contains("youtubei.googleapis.com")) {
                return true;
            }
        }
        return false;
    }
}
