package com.liskovsoft.smartyoutubetv2.common.local;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

/** Owns the one active local SMB stream and closes it on player/profile transitions. */
public final class LocalArchivePlaybackManager implements AppPrefs.ProfileChangeListener {
    private static LocalArchivePlaybackManager sInstance;
    private final Context context;
    private LoopbackHttpStream active;

    private LocalArchivePlaybackManager(Context context) {
        this.context = context.getApplicationContext();
        AppPrefs.instance(context).addListener(this);
    }

    public static synchronized LocalArchivePlaybackManager instance(Context context) {
        if (sInstance == null) sInstance = new LocalArchivePlaybackManager(context);
        return sInstance;
    }

    public synchronized void activate(LoopbackHttpStream stream) {
        closeActive();
        active = stream;
    }

    public synchronized void closeActive() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    public synchronized boolean isActiveFor(String streamUrl) {
        return active != null && streamUrl != null && streamUrl.equals(active.url());
    }

    @Override
    public void onProfileChanged() {
        Video current = PlaybackPresenter.instance(context).getVideo();
        synchronized (this) {
            closeActive();
        }
        if (current != null && current.isLocalArchive) {
            PlaybackPresenter.instance(context).forceFinish();
        }
    }
}
