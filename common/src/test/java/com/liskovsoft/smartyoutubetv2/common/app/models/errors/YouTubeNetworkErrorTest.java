package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

import org.junit.Test;

import java.io.IOException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YouTubeNetworkErrorTest {
    @Test
    public void recognisesWrappedYouTubeDnsFailure() {
        Throwable failure = new IllegalStateException(
                new IOException(new UnknownHostException(
                        "Unable to resolve host \"www.youtube.com\": No address associated with hostname")));
        assertTrue(YouTubeNetworkError.isYouTubeDnsFailure(failure));
    }

    @Test
    public void recognisesYouTubeApiDnsFailure() {
        assertTrue(YouTubeNetworkError.isYouTubeDnsFailure(
                new UnknownHostException("youtubei.googleapis.com")));
    }

    @Test
    public void doesNotHideUnrelatedServerOrSmbFailures() {
        assertFalse(YouTubeNetworkError.isYouTubeDnsFailure(
                new UnknownHostException("192.168.1.1")));
        assertFalse(YouTubeNetworkError.isYouTubeDnsFailure(
                new UnknownHostException("returnyoutubedislikeapi.com")));
        assertFalse(YouTubeNetworkError.isYouTubeDnsFailure(
                new IOException("SMB connection refused")));
        assertFalse(YouTubeNetworkError.isYouTubeDnsFailure(
                new IllegalStateException("Unexpected server response")));
    }

    @Test
    public void handlesNullAndUnlabelledDnsErrors() {
        assertFalse(YouTubeNetworkError.isYouTubeDnsFailure(null));
        assertFalse(YouTubeNetworkError.isYouTubeDnsFailure(new UnknownHostException()));
    }
}
