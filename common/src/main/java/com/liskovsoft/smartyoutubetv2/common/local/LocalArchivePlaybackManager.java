package com.liskovsoft.smartyoutubetv2.common.local;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;

/** Owns the one active local SMB stream and closes it on player/profile transitions. */
public final class LocalArchivePlaybackManager implements AppPrefs.ProfileChangeListener {
    private static LocalArchivePlaybackManager sInstance;
    private final Context context;
    private LoopbackHttpStream active;
    private LoopbackHttpStream pending;
    private long requestId;

    private LocalArchivePlaybackManager(Context context) {
        this.context = context.getApplicationContext();
        AppPrefs.instance(context).addListener(this);
    }

    public static synchronized LocalArchivePlaybackManager instance(Context context) {
        if (sInstance == null) sInstance = new LocalArchivePlaybackManager(context);
        return sInstance;
    }

    public synchronized void activate(LoopbackHttpStream stream) {
        closePending();
        pending = stream;
    }

    public synchronized void closeActive() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    public synchronized void closeAll() {
        closePending();
        closeActive();
    }

    private void closePending() {
        if (pending != null) {
            pending.close();
            pending = null;
        }
    }

    public synchronized boolean isActiveFor(String streamUrl) {
        return streamUrl != null && ((active != null && streamUrl.equals(active.url())) ||
                (pending != null && streamUrl.equals(pending.url())));
    }

    /** Invalidate an archive lookup superseded by an explicit player transition. */
    public synchronized void onNewVideo(Video video) {
        if (video != null && video.isLocalArchive && isActiveFor(video.localStreamUrl)) return;
        requestId++;
        closePending();
    }

    public void play(Video video) {
        play(video, video != null && (video.isLocalArchive || video.isLocalArchiveOnly));
    }

    /** Resolve by canonical ID; ordinary playlist entries may fall back online when absent. */
    public void play(Video video, boolean localOnly) {
        if (video == null) return;
        final long request;
        synchronized (this) {
            request = ++requestId;
        }
        LocalArchiveRepository.instance(context).startPlayback(video.videoId,
                new LocalArchiveRepository.Callback<LocalArchiveRepository.PlaybackSource>() {
                    @Override public void onSuccess(LocalArchiveRepository.PlaybackSource source) {
                        synchronized (LocalArchivePlaybackManager.this) {
                            if (request != requestId) {
                                source.stream.close();
                                return;
                            }
                            activate(source.stream);
                        }
                        Video resolved = Video.from(video);
                        resolved.isLocalArchive = true;
                        resolved.isLocalArchiveOnly = localOnly;
                        resolved.hasLocalArchiveCopy = source.video.hasLocalArchiveCopy;
                        resolved.localArchivePath = source.video.localArchivePath;
                        resolved.localArchiveLength = source.video.localArchiveLength;
                        resolved.localStreamUrl = source.video.localStreamUrl;
                        resolved.badge = source.video.badge;
                        if (resolved.title == null) resolved.title = source.video.title;
                        if (resolved.author == null) resolved.author = source.video.author;
                        if (resolved.cardImageUrl == null) resolved.cardImageUrl = source.video.cardImageUrl;
                        PlaybackPresenter.instance(context).openVideo(resolved);
                    }
                    @Override public void onError(String message) {
                        synchronized (LocalArchivePlaybackManager.this) {
                            if (request != requestId) return;
                        }
                        if (!localOnly) PlaybackPresenter.instance(context).openVideo(video);
                        else MessageHelpers.showMessage(context, message);
                    }
                });
    }

    @Override
    public void onProfileChanged() {
        synchronized (this) {
            requestId++;
            closeAll();
        }
        Video current = PlaybackPresenter.instance(context).getVideo();
        if (current != null && current.isLocalArchive) {
            PlaybackPresenter.instance(context).forceFinish();
        }
    }

    /** Called when the player reports that a replacement video is loaded. */
    public synchronized void onVideoLoaded(Video video) {
        if (video == null || !video.isLocalArchive) {
            closeAll();
            return;
        }
        if (pending != null && video.localStreamUrl != null && video.localStreamUrl.equals(pending.url())) {
            closeActive();
            active = pending;
            pending = null;
        }
    }
}
