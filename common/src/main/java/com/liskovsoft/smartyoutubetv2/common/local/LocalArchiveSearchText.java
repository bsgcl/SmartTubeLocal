package com.liskovsoft.smartyoutubetv2.common.local;

import java.text.Normalizer;
import java.util.Locale;

/** Unicode matching rules shared by the metadata-only local search path. */
public final class LocalArchiveSearchText {
    private LocalArchiveSearchText() { }

    /** NFC, locale-independent lowercase, literal punctuation, substring match. */
    public static boolean matches(String text, String query) {
        String needle = normalize(query);
        return !needle.isEmpty() && normalize(text).contains(needle);
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
    }
}
