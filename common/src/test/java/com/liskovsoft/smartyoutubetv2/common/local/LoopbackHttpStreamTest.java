package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class LoopbackHttpStreamTest {
    @Test
    public void parsesClosedOpenAndSuffixByteRanges() throws Exception {
        assertRange("bytes=10-19", 100, 10, 19);
        assertRange("bytes=90-", 100, 90, 99);
        assertRange("bytes=-8", 100, 92, 99);
    }

    @Test
    public void clampsEndAndRejectsInvalidRanges() throws Exception {
        assertRange("bytes=95-200", 100, 95, 99);
        for (String header : new String[] {"bytes=100-", "bytes=9-2", "bytes=-0", "bytes=1-2,4-5", "bytes=x-y"}) {
            try {
                LoopbackHttpStream.parseRange(header, 100);
                fail("Expected invalid range rejection: " + header);
            } catch (LoopbackHttpStream.InvalidRangeException expected) {
                // Expected.
            }
        }
    }

    @Test
    public void servesOnlyFromIpv4Loopback() throws Exception {
        SmbjArchiveClient client = new SmbjArchiveClient("127.0.0.1", 445, "unused", "", "", "");
        try (LoopbackHttpStream stream = new LoopbackHttpStream(client, "archive/video.mp4", 100)) {
            String url = stream.start();
            assertTrue(url.startsWith("http://127.0.0.1:"));
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
                assertTrue(probe.isConnected());
            }
        }
    }

    @Test
    public void cancellationClosesPendingRequestAndListener() throws Exception {
        SmbjArchiveClient client = new SmbjArchiveClient("127.0.0.1", 445, "unused", "", "", "");
        LoopbackHttpStream stream = new LoopbackHttpStream(client, "archive/video.mp4", 100);
        Socket pending = new Socket();
        try {
            String url = stream.start();
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            pending.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            pending.setSoTimeout(2_000);
            pending.getOutputStream().write("GET /pending HTTP/1.1".getBytes(StandardCharsets.US_ASCII));
            pending.getOutputStream().flush();

            stream.close();

            try {
                assertEquals(-1, pending.getInputStream().read());
            } catch (SocketException expected) {
                // Closing the stream may reset a socket whose request line was incomplete.
            }
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(false);
                try {
                    probe.bind(new InetSocketAddress("127.0.0.1", port));
                } catch (IOException expected) {
                    fail("Loopback listener remained bound after stream cancellation");
                }
            }
        } finally {
            try {
                pending.close();
            } finally {
                stream.close();
            }
        }
    }

    private static void assertRange(String header, long size, long start, long end) throws Exception {
        LoopbackHttpStream.ByteRange range = LoopbackHttpStream.parseRange(header, size);
        assertEquals(start, range.start);
        assertEquals(end, range.end);
        assertEquals(end - start + 1, range.length());
    }
}
