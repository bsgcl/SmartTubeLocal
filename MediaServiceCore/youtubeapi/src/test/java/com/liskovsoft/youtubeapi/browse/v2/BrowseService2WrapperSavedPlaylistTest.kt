package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.youtubeapi.channelgroups.models.ItemGroupImpl
import com.liskovsoft.youtubeapi.channelgroups.models.ItemImpl
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import com.liskovsoft.youtubeapi.service.internal.LocalProfileManager
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowseService2WrapperSavedPlaylistTest {
    private lateinit var profiles: LocalProfileManager
    private var originalProfileId: String? = null
    private val createdProfileIds = mutableListOf<String>()

    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        profiles = LocalProfileManager.instance()
        originalProfileId = profiles.getActiveId()
    }

    @After
    fun tearDown() {
        if (!::profiles.isInitialized) return
        originalProfileId?.let { profiles.select(it) }
        createdProfileIds.asReversed().forEach { assertTrue(profiles.delete(it)) }
    }

    @Test
    fun savedPlaylistCardUsesPlaylistIdentityAndCachedGroupBeforeRemoteLookup() {
        val dad = profiles.create("saved-playlist-dad")
        createdProfileIds.add(dad.id)
        val kids = profiles.create("saved-playlist-kids")
        createdProfileIds.add(kids.id)

        profiles.select(dad.id)
        addPlaylist("PL-dad", "Dad AI", listOf(
            ItemImpl("UCai", "AI first", "https://img/first.jpg", "video-1", "Author 1", "HD"),
            ItemImpl("UChome", "HomeLab", "https://img/second.jpg", "video-2", "Author 2", "4K")
        ))

        val remoteCards = YouTubeMediaGroup(MediaGroup.TYPE_USER_PLAYLISTS).apply {
            mediaItems = mutableListOf<MediaItem>(
                YouTubeMediaItem().apply { playlistId = "PL-foreign"; title = "Dad AI" },
                YouTubeMediaItem().apply { playlistId = "PL-dad"; title = "Remote title" }
            )
        }
        val cards = BrowseService2Wrapper.getCachedPlaylists(remoteCards)!!.mediaItems!!
        assertEquals("A same-title foreign playlist must remain remote", listOf("PL-dad", "PL-foreign"), cards.map { it.playlistId })
        val card = cards.first()
        assertEquals("PL-dad", card.playlistId)
        assertNull("Saved playlists must not route through channel browse", card.channelId)
        assertEquals("PL-dad", card.reloadPageKey)
        assertEquals("Dad AI", card.title)
        assertEquals("https://img/first.jpg", card.cardImageUrl)

        var remoteCalls = 0
        val group = BrowseService2Wrapper.resolveGroup("PL-dad", MediaGroup.TYPE_UNDEFINED, null) { _, _, _ ->
            remoteCalls++
            null
        }!!
        assertEquals(0, remoteCalls)
        assertEquals("Dad AI", group.title)
        assertEquals(listOf("video-1", "video-2"), group.mediaItems!!.map { it.videoId })
        assertEquals("AI first", group.mediaItems!![0].title)
        assertEquals("https://img/second.jpg", group.mediaItems!![1].cardImageUrl)
        assertEquals("UChome", group.mediaItems!![1].channelId)
        assertEquals("PL-dad", group.mediaItems!![0].playlistId)

        profiles.select(kids.id)
        assertTrue(PlaylistGroupServiceImpl.getPlaylistGroups().isEmpty())
        addPlaylist("PL-kids", "Kids videos", listOf(ItemImpl(videoId = "kids-video")))
        assertEquals(listOf("PL-kids"), BrowseService2Wrapper.getCachedPlaylists(null)!!.mediaItems!!.map { it.playlistId })

        profiles.select(dad.id)
        assertEquals(listOf("PL-dad"), BrowseService2Wrapper.getCachedPlaylists(null)!!.mediaItems!!.map { it.playlistId })
    }

    @Test
    fun emptySavedPlaylistResolvesLocallyAndUnknownPlaylistUsesRemoteFallback() {
        val profile = profiles.create("saved-playlist-empty")
        createdProfileIds.add(profile.id)
        profiles.select(profile.id)
        addPlaylist("PL-empty", "Empty saved list", emptyList())

        var remoteCalls = 0
        val empty = BrowseService2Wrapper.resolveGroup("PL-empty", MediaGroup.TYPE_UNDEFINED, null) { _, _, _ ->
            remoteCalls++
            null
        }
        assertNotNull(empty)
        assertTrue(empty!!.isEmpty)
        assertEquals(0, remoteCalls)

        val remote = BrowseService2Wrapper.resolveGroup("PL-remote", MediaGroup.TYPE_UNDEFINED, "Remote") { key, _, title ->
            remoteCalls++
            assertEquals("PL-remote", key)
            assertEquals("Remote", title)
            null
        }
        assertNull(remote)
        assertEquals(1, remoteCalls)
    }

    private fun addPlaylist(id: String, title: String, items: List<ItemImpl>) {
        PlaylistGroupServiceImpl.addPlaylistGroup(
            ItemGroupImpl(id = id, title = title, items = items.toMutableList())
        )
    }
}
