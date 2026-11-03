package com.liskovsoft.youtubeapi.playlist

import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import com.liskovsoft.youtubeapi.service.internal.LocalProfileManager
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistServiceWrapperLocalArchiveTest {
    private lateinit var profiles: LocalProfileManager
    private var originalProfileId: String? = null
    private val createdProfileIds = mutableListOf<String>()

    @Before
    fun setUp() {
        // LocalProfileManager obtains its Android context through AppService, which
        // in turn requires GlobalPreferences to have been initialized first.
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
        profiles = LocalProfileManager.instance()
        originalProfileId = profiles.getActiveId()
    }

    @After
    fun tearDown() {
        if (!::profiles.isInitialized) return

        originalProfileId?.let { profiles.select(it) }
        createdProfileIds.asReversed().forEach { id ->
            assertTrue("Test profile $id should be deleted", profiles.delete(id))
        }
        createdProfileIds.clear()
    }

    @Test
    fun localCreateAndAddPreserveOrderMetadataAndProfileAcrossRestore() {
        val profile = profiles.create("stage2-playlist-local")
        createdProfileIds.add(profile.id)
        val other = profiles.create("stage2-playlist-isolation")
        createdProfileIds.add(other.id)
        val localBadge = "\u001fST_LOCAL_ARCHIVE_ONLY:LOCAL"
        profiles.select(profile.id)
        val first = video("khan-1", "UCkhan", "Khan lesson 1", "Khan Academy", "file:///cache/khan1.jpg", localBadge)
        val second = video("ted-1", "UCted", "TED lesson", "TED-Ed", "file:///cache/ted.jpg", localBadge)
        val online = video("online-1", "UConline", "Online video", "Online author", "https://img/online.jpg", "HD")

        PlaylistServiceWrapper.instance().createPlaylist("Offline lessons", first)
        val playlist = PlaylistGroupServiceImpl.getPlaylistGroups().first()
        PlaylistServiceWrapper.instance().addToPlaylist(playlist.id, second)
        PlaylistServiceWrapper.instance().addToPlaylist(playlist.id, online)
        // ItemGroupImpl.add inserts at the front; preserve that established playlist order.
        assertEquals(listOf("online-1", "ted-1", "khan-1"), playlist.items.mapNotNull { it.videoId })
        val savedLocalItem = playlist.items.first { it.videoId == "khan-1" }
        assertEquals("UCkhan", savedLocalItem.channelId)
        assertEquals("Khan lesson 1", savedLocalItem.title)
        assertEquals("Khan Academy", savedLocalItem.subtitle)
        assertEquals("file:///cache/khan1.jpg", savedLocalItem.iconUrl)
        assertEquals(localBadge, savedLocalItem.badge)

        PlaylistServiceWrapper.instance().removeFromPlaylist(playlist.id, "ted-1")
        PlaylistServiceWrapper.instance().renamePlaylist(playlist.id, "Saved lessons")
        assertEquals(listOf("online-1", "khan-1"), playlist.items.mapNotNull { it.videoId })

        profiles.select(other.id)
        assertTrue(PlaylistGroupServiceImpl.getPlaylistGroups().isEmpty())
        profiles.select(profile.id)
        val restored = PlaylistGroupServiceImpl.getPlaylistGroups().first()
        assertEquals("Saved lessons", restored.title)
        assertEquals(listOf("online-1", "khan-1"), restored.items.mapNotNull { it.videoId })
        assertEquals("HD", restored.items.first().badge)
    }

    private fun video(id: String, channel: String, title: String, author: String, artwork: String, badge: String) =
        YouTubeMediaItem().apply {
            videoId = id
            channelId = channel
            this.title = title
            secondTitle = author
            setAuthor(author)
            cardImageUrl = artwork
            badgeText = badge
        }
}
