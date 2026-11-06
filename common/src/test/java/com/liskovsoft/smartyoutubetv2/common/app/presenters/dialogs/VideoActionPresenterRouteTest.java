package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveChannelIdentity;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchivePlaylistIdentity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VideoActionPresenterRouteTest {
    @Test
    public void savedYouTubePlaylistCardOpensContentsInsteadOfLocalPlayback() {
        Video playlist = new Video();
        playlist.playlistId = "PL-saved";
        playlist.reloadPageKey = "PL-saved";

        assertFalse(playlist.hasVideo());
        assertEquals(VideoActionPresenter.ClickRoute.OPEN_PLAYLIST_CONTENTS, routeInUserPlaylists(playlist));
    }

    @Test
    public void savedYouTubeVideoEntriesKeepLocalFirstPlaybackWithAValidId() {
        Video savedVideo = new Video();
        savedVideo.videoId = "youtube-video";
        assertTrue(savedVideo.hasVideo());
        assertEquals(VideoActionPresenter.ClickRoute.LOCAL_PLAYBACK, routeInUserPlaylists(savedVideo));

        Video savedVideoWithArchiveCopy = new Video();
        savedVideoWithArchiveCopy.videoId = "youtube-video-with-copy";
        savedVideoWithArchiveCopy.hasLocalArchiveCopy = true;
        assertEquals(VideoActionPresenter.ClickRoute.LOCAL_PLAYBACK, routeInUserPlaylists(savedVideoWithArchiveCopy));
    }

    @Test
    public void localPlaylistAndVideoKeepTheirLocalRoutes() {
        Video localPlaylist = new Video();
        localPlaylist.channelId = LocalArchivePlaylistIdentity.fromPlaylistId("learning-movements");
        assertEquals(VideoActionPresenter.ClickRoute.OPEN_LOCAL_CONTENTS, VideoActionPresenter.selectRoute(localPlaylist));

        Video localChannel = new Video();
        localChannel.channelId = LocalArchiveChannelIdentity.fromCanonicalId("UC-learning");
        assertEquals(VideoActionPresenter.ClickRoute.OPEN_LOCAL_CONTENTS, VideoActionPresenter.selectRoute(localChannel));

        Video localVideo = new Video();
        localVideo.videoId = "learning-video";
        localVideo.isLocalArchive = true;
        assertEquals(VideoActionPresenter.ClickRoute.LOCAL_PLAYBACK, VideoActionPresenter.selectRoute(localVideo));
    }

    @Test
    public void ordinaryYouTubeVideoChannelAndForeignPlaylistKeepTheirRoutes() {
        Video video = new Video();
        video.videoId = "ordinary-video";
        assertEquals(VideoActionPresenter.ClickRoute.PLAY_VIDEO, VideoActionPresenter.selectRoute(video));

        Video channel = new Video();
        channel.channelId = "UC-remote";
        assertEquals(VideoActionPresenter.ClickRoute.OPEN_CHANNEL, VideoActionPresenter.selectRoute(channel));

        Video foreignPlaylist = new Video();
        foreignPlaylist.playlistId = "PL-foreign";
        foreignPlaylist.reloadPageKey = "PL-foreign";
        assertEquals(VideoActionPresenter.ClickRoute.OPEN_PLAYLIST_CONTENTS, VideoActionPresenter.selectRoute(foreignPlaylist));
    }

    @Test
    public void emptySavedPlaylistStillOpensItsContentsView() {
        Video emptyPlaylist = new Video();
        emptyPlaylist.playlistId = "PL-empty";
        emptyPlaylist.reloadPageKey = "PL-empty";
        assertEquals(VideoActionPresenter.ClickRoute.OPEN_PLAYLIST_CONTENTS, routeInUserPlaylists(emptyPlaylist));
    }

    @Test
    public void userPlaylistEntryWithBlankVideoIdDoesNotEnterPlayback() {
        Video missingVideo = new Video();
        missingVideo.videoId = "  ";
        missingVideo.playlistId = "PL-parent";
        assertEquals(VideoActionPresenter.ClickRoute.OPEN_PLAYLIST_CONTENTS, routeInUserPlaylists(missingVideo));
    }

    private VideoActionPresenter.ClickRoute routeInUserPlaylists(Video item) {
        VideoGroup group = new VideoGroup();
        group.setType(MediaGroup.TYPE_USER_PLAYLISTS);
        item.setGroup(group);
        assertTrue(item.belongsToUserPlaylists());
        return VideoActionPresenter.selectRoute(item);
    }
}
