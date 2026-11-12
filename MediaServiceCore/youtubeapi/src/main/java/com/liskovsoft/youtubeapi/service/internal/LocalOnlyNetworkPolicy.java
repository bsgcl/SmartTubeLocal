package com.liskovsoft.youtubeapi.service.internal;

import okhttp3.HttpUrl;
import java.io.IOException;

/** Identifies YouTube and YouTube-specific Google API requests at the HTTP boundary. */
public final class LocalOnlyNetworkPolicy {
    private LocalOnlyNetworkPolicy() {
    }

    public static boolean blocks(HttpUrl url, boolean localOnly) {
        return localOnly && isYouTubeRequest(url.host(), url.encodedPath());
    }

    public static void checkRequest(HttpUrl url, boolean localOnly) throws IOException {
        if (blocks(url, localOnly)) {
            throw new IOException("YouTube request blocked by LOCAL ONLY mode");
        }
    }

    static boolean isYouTubeRequest(String host, String path) {
        String cleanHost = host == null ? "" : host.toLowerCase(java.util.Locale.US);
        String cleanPath = path == null ? "" : path.toLowerCase(java.util.Locale.US);
        return isOrSubdomain(cleanHost, "youtube.com") ||
                isOrSubdomain(cleanHost, "youtube-nocookie.com") ||
                isOrSubdomain(cleanHost, "youtu.be") ||
                isOrSubdomain(cleanHost, "googlevideo.com") ||
                isOrSubdomain(cleanHost, "ytimg.com") ||
                isOrSubdomain(cleanHost, "ggpht.com") ||
                isOrSubdomain(cleanHost, "youtubei.googleapis.com") ||
                isOrSubdomain(cleanHost, "jnn-pa.googleapis.com") ||
                ("www.googleapis.com".equals(cleanHost) && cleanPath.startsWith("/youtube/")) ||
                ("clients1.google.com".equals(cleanHost) && cleanPath.startsWith("/complete/")) ||
                ("oauth2.googleapis.com".equals(cleanHost) &&
                        (cleanPath.startsWith("/device/code") || cleanPath.startsWith("/token"))) ||
                ("accounts.google.com".equals(cleanHost) && cleanPath.startsWith("/o/oauth2/"));
    }

    private static boolean isOrSubdomain(String host, String domain) {
        return domain.equals(host) || host.endsWith("." + domain);
    }
}
