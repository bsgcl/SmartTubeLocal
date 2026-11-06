package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadingManager;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveChannelIdentity;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchivePlaybackManager;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchivePlaylistIdentity;

public class VideoActionPresenter extends BasePresenter<Void> {
    private static final String TAG = VideoActionPresenter.class.getSimpleName();

    enum ClickRoute {
        NO_ACTION,
        LOCAL_PLAYBACK,
        PLAY_VIDEO,
        OPEN_CHANNEL,
        OPEN_LOCAL_CONTENTS,
        OPEN_PLAYLIST_CONTENTS,
        PLAY_FIRST_PLAYLIST_ITEM,
        SEEK_TO_CHAPTER,
        SEARCH,
        INVALID_ITEM
    }

    private VideoActionPresenter(Context context) {
        super(context);
    }

    public static VideoActionPresenter instance(Context context) {
        return new VideoActionPresenter(context);
    }

    public void apply(Video item) {
        ClickRoute route = selectRoute(item);
        switch (route) {
            case NO_ACTION:
                return;
            case LOCAL_PLAYBACK:
                LocalArchivePlaybackManager.instance(getContext()).play(item, item.isLocalArchive || item.isLocalArchiveOnly);
                return;
            case PLAY_VIDEO:
                PlaybackPresenter.instance(getContext()).openVideo(item);
                return;
            case OPEN_CHANNEL:
                MediaServiceManager.chooseChannelPresenter(getContext(), item);
                return;
            case OPEN_LOCAL_CONTENTS:
            case OPEN_PLAYLIST_CONTENTS:
                ChannelUploadsPresenter.instance(getContext()).openChannel(item);
                return;
            case PLAY_FIRST_PLAYLIST_ITEM:
                startFistPlaylistItem(item);
                return;
            case SEEK_TO_CHAPTER:
                PlaybackPresenter.instance(getContext()).setPosition(item.startTimeMs);
                return;
            case SEARCH:
                SearchPresenter.instance(getContext()).onSearch(item.searchQuery);
                return;
            case INVALID_ITEM:
                MessageHelpers.showMessage(getContext(), "Video item doesn't contain needed data!");
                return;
        }
    }

    static ClickRoute selectRoute(Video item) {
        if (item == null) {
            return ClickRoute.NO_ACTION;
        }

        if (LocalArchiveChannelIdentity.isLocalId(item.channelId) ||
                LocalArchivePlaylistIdentity.isLocalId(item.channelId)) {
            return ClickRoute.OPEN_LOCAL_CONTENTS;
        }

        if (item.isLocalArchive || item.isLocalArchiveOnly || item.hasLocalArchiveCopy ||
                (item.belongsToUserPlaylists() && hasValidVideoId(item))) {
            return ClickRoute.LOCAL_PLAYBACK;
        }

        // Show playlist contents in channel instead of instant playback.
        if (hasValidVideoId(item) && !item.isBadgePlaylistInChannel()) {
            return ClickRoute.PLAY_VIDEO;
        }
        if (item.hasChannel() || (item.belongsToChannelUploads() && item.hasNestedItems())) {
            return ClickRoute.OPEN_CHANNEL;
        }
        if (item.hasPlaylist() || item.hasNestedItems()) {
            return item.belongsToMusic() ? ClickRoute.PLAY_FIRST_PLAYLIST_ITEM : ClickRoute.OPEN_PLAYLIST_CONTENTS;
        }
        if (item.isChapter) {
            return ClickRoute.SEEK_TO_CHAPTER;
        }
        if (item.searchQuery != null) {
            return ClickRoute.SEARCH;
        }

        return ClickRoute.INVALID_ITEM;
    }

    private static boolean hasValidVideoId(Video item) {
        return item.videoId != null && !item.videoId.trim().isEmpty();
    }

    private void startFistPlaylistItem(Video item) {
        LoadingManager.showLoading(getContext(), true);
        ChannelUploadsPresenter.instance(getContext()).obtainGroup(item, mediaGroup -> {
            LoadingManager.showLoading(getContext(), false);
            if (!mediaGroup.isEmpty()) {
                PlaybackPresenter.instance(getContext()).openVideo(Video.from(mediaGroup.getMediaItems().get(0)));
            }
        },
        e -> LoadingManager.showLoading(getContext(), false),
        () -> LoadingManager.showLoading(getContext(), false));
    }
}
