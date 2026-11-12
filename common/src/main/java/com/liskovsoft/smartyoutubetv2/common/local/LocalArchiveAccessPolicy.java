package com.liskovsoft.smartyoutubetv2.common.local;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure permission, identity-merge, and local-playback decisions. */
public final class LocalArchiveAccessPolicy {
    private LocalArchiveAccessPolicy() {
    }

    public static boolean hasLibraryAccess(String profileId, Map<String, Boolean> assignments) {
        String key = profileId == null || profileId.trim().isEmpty() ? "default" : profileId;
        return assignments != null && Boolean.TRUE.equals(assignments.get(key));
    }

    public static boolean shouldAttachLocalCopy(String remoteVideoId, String localVideoId,
                                                boolean permitted) {
        return permitted && remoteVideoId != null && remoteVideoId.equals(localVideoId);
    }

    public static boolean shouldPreferLocalPlayback(boolean localCopyAvailable, boolean permitted) {
        return localCopyAvailable && permitted;
    }

    public static Map<String, Boolean> assignments(String profileA, boolean enabledA,
                                                    String profileB, boolean enabledB) {
        LinkedHashMap<String, Boolean> result = new LinkedHashMap<>();
        result.put(profileA, enabledA);
        result.put(profileB, enabledB);
        return Collections.unmodifiableMap(result);
    }
}
