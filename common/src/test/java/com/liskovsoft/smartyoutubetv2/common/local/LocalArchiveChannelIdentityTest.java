package com.liskovsoft.smartyoutubetv2.common.local;

import org.junit.Test;

import static org.junit.Assert.*;

public class LocalArchiveChannelIdentityTest {
    @Test public void localIdentityIsDistinctAndRoundTripsCanonicalChannelId() {
        String canonical = "UCk0s";
        String local = LocalArchiveChannelIdentity.fromCanonicalId(canonical);
        assertTrue(LocalArchiveChannelIdentity.isLocalId(local));
        assertEquals(canonical, LocalArchiveChannelIdentity.canonicalId(local));
        assertNotEquals(canonical, local);
        assertFalse(LocalArchiveChannelIdentity.isLocalId(canonical));
    }

    @Test public void localTitleRequiresNameAndUsesLocalPrefix() {
        assertEquals("[LOCAL] Khan Academy", LocalArchiveChannelIdentity.displayTitle(" Khan Academy "));
        assertNull(LocalArchiveChannelIdentity.displayTitle("  "));
        assertNull(LocalArchiveChannelIdentity.fromCanonicalId(""));
        assertNull(LocalArchiveChannelIdentity.canonicalId("UCk0s"));
    }
}
