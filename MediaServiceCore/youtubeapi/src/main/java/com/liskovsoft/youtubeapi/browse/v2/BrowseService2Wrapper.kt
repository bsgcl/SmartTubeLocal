package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.youtubeapi.channelgroups.ChannelGroupServiceImpl
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl
import com.liskovsoft.youtubeapi.rss.RssService
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService
import com.liskovsoft.youtubeapi.service.internal.LocalProfileManager
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem

internal object BrowseService2Wrapper: BrowseService2() {
    override fun getSubscriptions(): MediaGroup? {
        return RssService.getSubscriptionFeed(MediaGroup.TYPE_SUBSCRIPTIONS)
    }

    override fun getSubscribedChannels(): MediaGroup? {
        return getLocalSubscribedChannels()
    }

    override fun getSubscribedChannelsByName(): MediaGroup? {
        return getLocalSubscribedChannels()
    }

    override fun getSubscribedChannelsByNewContent(): MediaGroup? {
        return getLocalSubscribedChannels()
    }

    private fun getLocalSubscribedChannels(): MediaGroup? {
        val channelGroup = ChannelGroupServiceImpl.getSubscribedChannelGroup()
        if (channelGroup.isEmpty) return null

        return YouTubeMediaGroup(MediaGroup.TYPE_CHANNEL_UPLOADS).apply {
            mediaItems = channelGroup.items?.map {
                YouTubeMediaItem().apply {
                    title = it.title
                    secondTitle = it.subtitle
                    channelId = it.channelId
                    cardImageUrl = it.iconUrl
                    badgeText = it.badge
                }
            }
        }
    }

    // Bound online metadata repair to a few missing entries per visit.
    // It never changes a LOCAL subscription or replaces valid saved metadata.
    private const val MAX_REPAIRS_PER_VISIT = 1
    private const val RETRY_DELAY_MS = 5 * 60 * 1_000L
    private val lastRepairAttempt = mutableMapOf<String, Long>()
    internal var metadataLoader: (String) -> Pair<String?, String?>? = ::loadChannelMetadata

    @JvmStatic
    fun repairMissingSubscribedChannelMetadata(): Boolean {
        val profileId = LocalProfileManager.instance().activeId
        val now = System.currentTimeMillis()
        val missing = ChannelGroupServiceImpl.getSubscribedChannelGroup().items
            .filter { item ->
                val id = item.channelId
                id != null && id.isNotBlank() &&
                    !id.startsWith("local-archive-channel:") &&
                    (item.title.isNullOrBlank() || item.iconUrl.isNullOrBlank()) &&
                    synchronized(lastRepairAttempt) {
                        now - (lastRepairAttempt["$profileId:$id"] ?: 0L) >= RETRY_DELAY_MS
                    }
            }
            .take(MAX_REPAIRS_PER_VISIT)

        var changed = false
        missing.forEach { item ->
            val channelId = item.channelId ?: return@forEach
            if (LocalProfileManager.instance().activeId != profileId) return@forEach
            synchronized(lastRepairAttempt) {
                lastRepairAttempt["$profileId:$channelId"] = System.currentTimeMillis()
            }
            try {
                val result = metadataLoader(channelId)
                if (result != null && ChannelGroupServiceImpl.updateSubscribedChannelMetadata(
                    channelId, result.first, result.second, profileId
                )) changed = true
            } catch (e: Exception) {
                android.util.Log.w("ChannelMetadataRepair", "Failed to repair channel metadata", e)
            }
        }
        return changed
    }

    private fun loadChannelMetadata(channelId: String): Pair<String?, String?>? {
        // Video images are not channel icons. Only use the author image from watch metadata.
        val group = super.getChannelAsGrid(channelId)
        val video = group?.mediaItems?.firstOrNull { !it.videoId.isNullOrBlank() }
        if (video == null) return null
        val metadata = try {
            video.videoId?.let { YouTubeMediaItemService.instance().getMetadata(it) }
        } catch (e: Exception) {
            android.util.Log.w("ChannelMetadataRepair", "Video metadata unavailable: $channelId", e)
            null
        }
        if (metadata?.channelId != null && metadata.channelId != channelId) return null
        val title = metadata?.author?.takeIf { it.isNotBlank() } ?: video.author
        return Pair(title, metadata?.authorImageUrl)
    }

    internal fun resetMetadataRepairForTests() {
        synchronized(lastRepairAttempt) { lastRepairAttempt.clear() }
        metadataLoader = ::loadChannelMetadata
    }

    override fun getMyPlaylists(): MediaGroup? {
        return getCachedPlaylists(super.getMyPlaylists())
    }

    internal fun getCachedPlaylists(myPlaylists: MediaGroup?): MediaGroup? {
        val playlistGroups = PlaylistGroupServiceImpl.getPlaylistGroups()
        if (playlistGroups.isNotEmpty()) {
            val result: MutableList<MediaItem> = mutableListOf()
            val matchedRemotePlaylists = mutableSetOf<MediaItem>()
            fun isSavedPlaylist(item: MediaItem): Boolean = playlistGroups.any {
                it.id == item.playlistId || it.id == item.reloadPageKey
            }

            // Keep the special entries pinned unless the active profile has a local saved
            // copy, which must resolve from its stored items like every other saved list.
            myPlaylists?.mediaItems?.firstOrNull {
                it.playlistId == BrowseApiHelper.WATCH_LATER_PLAYLIST ||
                    it.reloadPageKey == BrowseApiHelper.WATCH_LATER_PLAYLIST
            }?.let {
                if (isSavedPlaylist(it)) matchedRemotePlaylists.add(it) else result.add(it)
            } // WatchLater
            myPlaylists?.mediaItems?.firstOrNull { it.channelId?.startsWith(BrowseApiHelper.LIKED_CHANNEL_ID) ?: false }?.let {
                if (isSavedPlaylist(it)) matchedRemotePlaylists.add(it) else result.add(it)
            } // Liked

            playlistGroups.forEach { group ->
                // Suppress only a remote card with the same playlist ID. A title match can
                // capture a different YouTube playlist with a coincidentally equal name.
                if (myPlaylists?.mediaItems?.isNotEmpty() == true) {
                    myPlaylists.mediaItems?.firstOrNull { it.playlistId == group.id || it.reloadPageKey == group.id }?.let {
                        matchedRemotePlaylists.add(it)
                    }
                }

                // Add remained local playlists
                result.add(YouTubeMediaItem().apply {
                    title = group.title
                    cardImageUrl = group.items?.firstOrNull()?.iconUrl ?: group.iconUrl
                    playlistId = group.id
                    // A saved playlist is a playlist page, not a channel page. The reload
                    // key makes the click resolve through getGroup() without remote metadata.
                    reloadPageKey = group.id
                    badgeText = group.badge ?: "${group.items.size} videos"
                })
            }

            // Preserve saved playlist order. Append unmatched remote playlists afterwards.
            myPlaylists?.mediaItems?.forEach { item ->
                if (!matchedRemotePlaylists.contains(item) && !result.contains(item)) {
                    result.add(item)
                }
            }

            return YouTubeMediaGroup(myPlaylists?.type ?: MediaGroup.TYPE_USER_PLAYLISTS).apply {
                mediaItems = result
            }
        }

        return myPlaylists
    }

    override fun getGroup(reloadPageKey: String, type: Int, title: String?): MediaGroup? {
        return resolveGroup(reloadPageKey, type, title) { key, groupType, groupTitle ->
            super.getGroup(key, groupType, groupTitle)
        }
    }

    internal fun resolveGroup(
        reloadPageKey: String,
        type: Int,
        title: String?,
        remoteLoader: (String, Int, String?) -> MediaGroup?
    ): MediaGroup? {
        return getCachedGroup(reloadPageKey, type) ?: remoteLoader(reloadPageKey, type, title)
    }

    override fun getChannel(channelId: String?, params: String?): Pair<List<MediaGroup?>?, String?>? {
        return super.getChannel(channelId, params)
    }

    override fun getChannelAsGrid(channelId: String?): MediaGroup? {
        return super.getChannelAsGrid(channelId)
    }

    internal fun getCachedGroup(reloadPageKey: String?, type: Int): MediaGroup? {
        val group = PlaylistGroupServiceImpl.findPlaylistGroup(reloadPageKey)
        if (group != null) {
            return YouTubeMediaGroup(type).apply {
                title = group.title
                mediaItems = group.items?.map {
                    YouTubeMediaItem().apply {
                        title = it.title
                        secondTitle = it.subtitle
                        cardImageUrl = it.iconUrl
                        videoId = it.videoId
                        channelId = it.channelId
                        playlistId = reloadPageKey
                        badgeText = it.badge
                    }
                }
            }
        }

        return null
    }
}
