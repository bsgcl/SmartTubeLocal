package com.liskovsoft.smartyoutubetv2.common.local;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;

/** Small AUTO-mode availability state; local content is usable during GRACE and OFFLINE. */
public final class AutoOfflineState {
    public enum State { ONLINE, GRACE, OFFLINE }

    public static final long GRACE_PERIOD_MS = 15 * 60 * 1000L;

    private State mState = State.ONLINE;
    private long mGraceStartedAt;

    public State getState() {
        return mState;
    }

    public boolean shouldUseLocalContent() {
        return mState != State.ONLINE;
    }

    public boolean shouldDiscardRemoteUpdate(boolean localSource, boolean localOnly) {
        return !localSource && (localOnly || shouldUseLocalContent());
    }

    public boolean shouldContinueRemote(boolean localOnly) {
        return !localOnly && mState == State.ONLINE;
    }

    /** Returns true only when the effective availability state changes to GRACE. */
    public boolean onConnectivityUnvalidated(long nowMs) {
        if (mState == State.ONLINE) {
            mGraceStartedAt = nowMs;
            mState = State.GRACE;
            return true;
        }
        return false;
    }

    /** A failed YouTube request confirms unavailability, bypassing the grace timer. */
    /** Returns true only on the transition into OFFLINE. */
    public boolean onRequestFailure() {
        if (mState == State.OFFLINE) return false;
        mState = State.OFFLINE;
        return true;
    }

    public boolean onProbeSuccess() {
        boolean wasUnavailable = shouldUseLocalContent();
        mState = State.ONLINE;
        mGraceStartedAt = 0;
        return wasUnavailable;
    }

    /** Profile availability is process-local state and must not leak to the next profile. */
    public boolean resetForProfile(boolean internetValidated, long nowMs) {
        State previous = mState;
        if (internetValidated) {
            mState = State.ONLINE;
            mGraceStartedAt = 0;
        } else {
            mState = State.GRACE;
            mGraceStartedAt = nowMs;
        }
        return previous != mState;
    }

    public boolean expireGrace(long nowMs) {
        if (mState != State.GRACE || nowMs - mGraceStartedAt < GRACE_PERIOD_MS) return false;
        mState = State.OFFLINE;
        return true;
    }

    public static boolean hasLocalFallback(int sectionId) {
        return sectionId == MediaGroup.TYPE_HOME || sectionId == MediaGroup.TYPE_CHANNEL_UPLOADS ||
                sectionId == MediaGroup.TYPE_SUBSCRIPTIONS || sectionId == MediaGroup.TYPE_USER_PLAYLISTS ||
                sectionId == MediaGroup.TYPE_HISTORY;
    }
}
