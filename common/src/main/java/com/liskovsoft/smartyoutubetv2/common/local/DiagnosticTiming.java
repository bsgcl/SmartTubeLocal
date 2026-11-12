package com.liskovsoft.smartyoutubetv2.common.local;

import android.os.SystemClock;
import android.util.Log;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Temporary monotonic diagnostics for the Samsung section-navigation investigation. */
public final class DiagnosticTiming {
    private static final String TAG = "LocalDiagTiming";
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicLong LAST_USABLE_SECTION = new AtomicLong(-1);
    private static final Map<Object, Long> SECTION_IDS = new WeakHashMap<>();
    private static volatile long currentSectionId;

    private DiagnosticTiming() {
    }

    public static long beginSection(String detail) {
        long id = IDS.incrementAndGet();
        currentSectionId = id;
        LAST_USABLE_SECTION.set(-1);
        mark(id, "section.selection_observed", detail);
        return id;
    }

    public static void associate(Object owner, long sectionId) {
        if (owner == null || sectionId <= 0) return;
        synchronized (SECTION_IDS) {
            SECTION_IDS.put(owner, sectionId);
        }
    }

    public static void event(Object owner, String phase, String detail) {
        long sectionId = currentSectionId;
        if (owner != null) {
            synchronized (SECTION_IDS) {
                Long associated = SECTION_IDS.get(owner);
                if (associated != null) sectionId = associated;
            }
        }
        mark(sectionId, phase, detail);
    }

    public static void firstUsableContent(String detail) {
        long sectionId = currentSectionId;
        if (sectionId > 0 && LAST_USABLE_SECTION.getAndSet(sectionId) != sectionId) {
            mark(sectionId, "content.first_card_bound", detail);
        }
    }

    public static long nextTaskId() {
        return IDS.incrementAndGet();
    }

    public static long nowNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }

    public static void mark(long id, String phase, String detail) {
        Log.i(TAG, "tNs=" + nowNanos() + " id=" + id + " phase=" + phase + " " + detail);
    }
}
