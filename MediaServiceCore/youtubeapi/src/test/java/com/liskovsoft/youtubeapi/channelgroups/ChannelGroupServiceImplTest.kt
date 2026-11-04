package com.liskovsoft.youtubeapi.channelgroups

import com.liskovsoft.youtubeapi.service.internal.LocalProfileManager
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChannelGroupServiceImplTest {
    @Before
    fun setUp() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication())
    }

    @Test
    fun firstSubscriptionPersistsWithMetadataUnavailable() {
        val profiles = LocalProfileManager.instance()
        val original = profiles.getActiveId()
        val profile = profiles.create("subscription-write-regression")

        try {
            profiles.select(profile.id)
            ChannelGroupServiceImpl.cachedChannel = null
            ChannelGroupServiceImpl.subscribe(true, "metadata-unavailable-channel", null, null)

            assertTrue(ChannelGroupServiceImpl.isSubscribed("metadata-unavailable-channel"))

            profiles.select(original)
            profiles.select(profile.id)
            assertTrue(ChannelGroupServiceImpl.isSubscribed("metadata-unavailable-channel"))
        } finally {
            profiles.select(original)
            profiles.delete(profile.id)
        }
    }

    @Test
    fun localMembershipIsProfileScopedDurableAndMetadataIndependent() {
        val profiles = LocalProfileManager.instance()
        val original = profiles.getActiveId()
        val profileA = profiles.create("subscription-test-a")
        val profileB = profiles.create("subscription-test-b")

        try {
            profiles.select(profileA.id)
            ChannelGroupServiceImpl.cachedChannel = null
            ChannelGroupServiceImpl.subscribe(false, "channel_a", null, null)
            ChannelGroupServiceImpl.subscribe(false, "channel_b", null, null)

            ChannelGroupServiceImpl.subscribe(true, "channel_a", null, null)
            ChannelGroupServiceImpl.subscribe(true, "channel_a", null, null)
            ChannelGroupServiceImpl.subscribe(true, "channel_b", "Channel B", "icon-b")

            assertTrue(ChannelGroupServiceImpl.isSubscribed("channel_a"))
            assertTrue(ChannelGroupServiceImpl.isSubscribed("channel_b"))
            val savedChannels = ChannelGroupServiceImpl.getSubscribedChannelGroup().items
            assertEquals("Channel B", savedChannels.first { it.channelId == "channel_b" }.title)
            assertEquals("icon-b", savedChannels.first { it.channelId == "channel_b" }.iconUrl)
            assertTrue(savedChannels.any { it.channelId == "channel_a" })
            assertEquals(listOf("channel_b", "channel_a"),
                ChannelGroupServiceImpl.getSubscribedChannelIds()?.toList())
            assertEquals(listOf("channel_b", "channel_a"),
                BrowseService2Wrapper.getSubscribedChannels()?.mediaItems?.mapNotNull { it.channelId })

            profiles.select(profileB.id)
            ChannelGroupServiceImpl.subscribe(true, "channel_c", null, null)
            assertEquals(listOf("channel_c"), ChannelGroupServiceImpl.getSubscribedChannelIds()?.toList())

            profiles.select(profileA.id)
            assertTrue(ChannelGroupServiceImpl.isSubscribed("channel_a"))
            assertTrue(ChannelGroupServiceImpl.isSubscribed("channel_b"))
            assertFalse(ChannelGroupServiceImpl.isSubscribed("channel_c"))

            ChannelGroupServiceImpl.subscribe(false, "channel_a", null, null)
            assertFalse(ChannelGroupServiceImpl.isSubscribed("channel_a"))
            assertTrue(ChannelGroupServiceImpl.isSubscribed("channel_b"))

            profiles.select(profileA.id)
            assertFalse(ChannelGroupServiceImpl.isSubscribed("channel_a"))
            assertTrue(ChannelGroupServiceImpl.isSubscribed("channel_b"))
        } finally {
            profiles.select(original)
            profiles.delete(profileB.id)
            profiles.delete(profileA.id)
        }
    }

    @Test
    fun localAndOnlineSubscriptionsToSameChannelAreIndependent() {
        val profiles = LocalProfileManager.instance()
        val original = profiles.getActiveId()
        val profile = profiles.create("local-online-subscription-test")
        val canonical = "UCkhanacademy"
        val local = "local-archive-channel:$canonical"
        try {
            profiles.select(profile.id)
            ChannelGroupServiceImpl.subscribe(false, canonical, null, null)
            ChannelGroupServiceImpl.subscribe(false, local, null, null)

            ChannelGroupServiceImpl.subscribe(true, canonical, "Khan Academy", "online-icon")
            ChannelGroupServiceImpl.subscribe(true, local, "[LOCAL] Khan Academy", "archive-icon")

            val saved = ChannelGroupServiceImpl.getSubscribedChannelGroup().items
            assertEquals("Khan Academy", saved.first { it.channelId == canonical }.title)
            assertEquals("[LOCAL] Khan Academy", saved.first { it.channelId == local }.title)
            assertTrue(ChannelGroupServiceImpl.isSubscribed(canonical))
            assertTrue(ChannelGroupServiceImpl.isSubscribed(local))

            ChannelGroupServiceImpl.subscribe(false, local, null, null)
            assertFalse(ChannelGroupServiceImpl.isSubscribed(local))
            assertTrue(ChannelGroupServiceImpl.isSubscribed(canonical))
        } finally {
            profiles.select(original)
            profiles.delete(profile.id)
        }
    }

    @Test
    fun repeatedSubscriptionDoesNotEraseExistingChannelMetadata() {
        val profiles = LocalProfileManager.instance()
        val original = profiles.getActiveId()
        val profile = profiles.create("metadata-preservation-test")
        try {
            profiles.select(profile.id)
            ChannelGroupServiceImpl.cachedChannel = null
            ChannelGroupServiceImpl.subscribe(true, "UCsailinguma", "Sailing Uma", "uma-icon")
            ChannelGroupServiceImpl.subscribe(true, "UCsailinguma", null, null)
            val saved = ChannelGroupServiceImpl.getSubscribedChannelGroup().items
                .first { it.channelId == "UCsailinguma" }
            assertEquals("Sailing Uma", saved.title)
            assertEquals("uma-icon", saved.iconUrl)
        } finally {
            profiles.select(original)
            profiles.delete(profile.id)
        }
    }

    @Test
    fun missingOnlineMetadataIsRepairedWithoutChangingLocalChannelsOrOrder() {
        val profiles = LocalProfileManager.instance()
        val original = profiles.getActiveId()
        val profile = profiles.create("metadata-repair-test")
        val onlineId = "UCsailinguma"
        val localId = "local-archive-channel:$onlineId"
        try {
            profiles.select(profile.id)
            ChannelGroupServiceImpl.cachedChannel = null
            ChannelGroupServiceImpl.subscribe(true, localId, "[LOCAL] Sailing Uma", "local-icon")
            ChannelGroupServiceImpl.subscribe(true, onlineId, null, null)
            ChannelGroupServiceImpl.subscribe(true, "UCother", "Other", "other-icon")
            val initialOrder = ChannelGroupServiceImpl.getSubscribedChannelGroup().items.map { it.channelId }
            BrowseService2Wrapper.resetMetadataRepairForTests()
            BrowseService2Wrapper.metadataLoader = { channelId ->
                if (channelId == onlineId) Pair("Sailing Uma", "uma-icon") else null
            }

            assertTrue(BrowseService2Wrapper.repairMissingSubscribedChannelMetadata())
            val saved = ChannelGroupServiceImpl.getSubscribedChannelGroup().items
            assertEquals(initialOrder, saved.map { it.channelId })
            assertEquals("Sailing Uma", saved.first { it.channelId == onlineId }.title)
            assertEquals("uma-icon", saved.first { it.channelId == onlineId }.iconUrl)
            assertEquals("[LOCAL] Sailing Uma", saved.first { it.channelId == localId }.title)
            assertEquals("local-icon", saved.first { it.channelId == localId }.iconUrl)
            assertEquals("Sailing Uma", BrowseService2Wrapper.getSubscribedChannels()
                ?.mediaItems?.first { it.channelId == onlineId }?.title)
        } finally {
            BrowseService2Wrapper.resetMetadataRepairForTests()
            profiles.select(original)
            profiles.delete(profile.id)
        }
    }

    @Test
    fun aLateMetadataResultCannotChangeAnotherProfile() {
        val profiles = LocalProfileManager.instance()
        val original = profiles.getActiveId()
        val source = profiles.create("metadata-source-profile")
        val destination = profiles.create("metadata-destination-profile")
        try {
            profiles.select(source.id)
            ChannelGroupServiceImpl.cachedChannel = null
            ChannelGroupServiceImpl.subscribe(true, "UCsailinguma", null, null)
            profiles.select(destination.id)
            ChannelGroupServiceImpl.subscribe(true, "UCsailinguma", "Other profile", "icon")
            assertFalse(ChannelGroupServiceImpl.updateSubscribedChannelMetadata(
                "UCsailinguma", "Sailing Uma", "uma-icon", source.id
            ))
            assertEquals("Other profile", ChannelGroupServiceImpl.getSubscribedChannelGroup()
                .items.first { it.channelId == "UCsailinguma" }.title)
            profiles.select(source.id)
            assertTrue(ChannelGroupServiceImpl.getSubscribedChannelGroup().items
                .first { it.channelId == "UCsailinguma" }.title == null)
        } finally {
            profiles.select(original)
            profiles.delete(destination.id)
            profiles.delete(source.id)
        }
    }
}
