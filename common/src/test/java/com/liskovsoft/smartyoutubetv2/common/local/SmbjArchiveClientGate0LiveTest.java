package com.liskovsoft.smartyoutubetv2.common.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Gate 0 item 2: prove the production SmbjArchiveClient can read the live
 * catalogue fixtures written by the SMB publication probe.
 * Reads only; the fixtures live in the isolated _stage3_gate0 test directory.
 */
public class SmbjArchiveClientGate0LiveTest {

    private static final String HOST = "192.168.1.1";
    private static final String SHARE = "volume(sda1)";
    private static final String DIR = "_stage3_gate0";

    private static String readAll(SmbjArchiveClient client, String rel) throws Exception {
        try (InputStream in = client.openRead(DIR + "/" + rel, 0)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    @Test
    public void readsPointerAndSnapshotFixtures() throws Exception {
        SmbjArchiveClient client = new SmbjArchiveClient(
                HOST, 445, SHARE, "guest", "", "");
        try {
            client.connect();

            // directory listing sees the fixtures
            boolean sawPointer = false;
            for (SmbjArchiveClient.Entry e : client.list(DIR)) {
                if ("pointer.json".equals(e.name)) {
                    sawPointer = true;
                    assertTrue(e.length > 0);
                }
            }
            assertTrue("pointer.json visible via list()", sawPointer);

            // pointer parses and selects a snapshot
            JSONObject pointer = new JSONObject(readAll(client, "pointer.json"));
            int current = pointer.getInt("current");
            assertTrue(current == 1 || current == 2);

            JSONObject snapshot = new JSONObject(
                    readAll(client, "catalogue.v" + current + ".json"));
            assertEquals(current, snapshot.getInt("gen"));
            assertNotNull(snapshot.getJSONArray("videos"));
        } finally {
            client.close();
        }
    }

    @Test
    public void invalidJsonIsDetectableBeforeUse() throws Exception {
        SmbjArchiveClient client = new SmbjArchiveClient(
                HOST, 445, SHARE, "guest", "", "");
        try {
            client.connect();
            String broken = readAll(client, "broken.json");
            try {
                new JSONObject(broken);
                throw new AssertionError("broken.json unexpectedly parsed");
            } catch (RuntimeException expected) {
                // consumer treats this as "defer refresh, keep last valid cache"
            }
        } finally {
            client.close();
        }
    }
}
