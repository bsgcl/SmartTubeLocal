package com.liskovsoft.smartyoutubetv2.common.local;

import org.json.JSONException;

import java.util.List;
import java.util.Map;

/** Android compatibility wrapper around the pure-JVM archive index rules. */
public final class LocalArchiveIndex {
    private LocalArchiveIndex() {
    }

    public static List<LocalArchiveItem> build(Map<String, String> sidecars,
                                               Map<String, Long> fileLengths) throws JSONException {
        try {
            return LocalArchiveIndexCore.build(sidecars, fileLengths);
        } catch (LocalArchiveIndexCore.InvalidArchiveMetadataException e) {
            throw new JSONException(e.getMessage());
        }
    }

    public static String requireSafeRelativePath(String path) throws JSONException {
        try {
            return LocalArchiveIndexCore.requireSafeRelativePath(path);
        } catch (LocalArchiveIndexCore.InvalidArchiveMetadataException e) {
            throw new JSONException(e.getMessage());
        }
    }
}
