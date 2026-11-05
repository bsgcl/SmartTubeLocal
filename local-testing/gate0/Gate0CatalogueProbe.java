import com.liskovsoft.smartyoutubetv2.common.local.ArchiveCatalogue;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveItem;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveIndex;
import com.liskovsoft.smartyoutubetv2.common.local.SmbjArchiveClient;

import java.nio.charset.Charset;
import java.util.List;

/** Gate 0/Stage 3 cross-check: production SmbjArchiveClient + ArchiveCatalogue
 *  parser against a catalogue published by the real Pi producer script.
 *  Password from env GATE0_PW. */
public class Gate0CatalogueProbe {
    public static void main(String[] args) throws Exception {
        String pass = System.getenv("GATE0_PW");
        if (pass == null) { System.err.println("GATE0_PW not set"); System.exit(2); }
        String folder = "YouTube-Local/_stage3_gate/producer_test";
        SmbjArchiveClient client = new SmbjArchiveClient(
                "192.168.1.1", 445, "volume(sda1)", "admin", pass, "");
        client.connect();
        int failures = 0;

        String pointer = text(client, folder + "/_catalogue/pointer.json");
        long revision = ArchiveCatalogue.parsePointer(pointer);
        System.out.println("pointer OK, revision=" + revision);

        String library = text(client, folder + "/_catalogue/v" + revision + "/library.json");
        ArchiveCatalogue.CatalogueLibrary lib = ArchiveCatalogue.parseLibrary(library);
        System.out.println("library OK, channels=" + lib.channels.size()
                + ", videos=" + lib.videoIndex.size());

        for (ArchiveCatalogue.ChannelSummary channel : lib.channels) {
            String json = text(client, folder + "/_catalogue/v" + revision
                    + "/channels/" + channel.channelId + ".json");
            List<LocalArchiveItem> items = ArchiveCatalogue.parseChannel(
                    channel.channelId, channel.title, json);
            boolean idsMatch = items.size() == channel.videos.size();
            System.out.println("channel " + channel.channelId + " items=" + items.size()
                    + (idsMatch ? " MATCH" : " MISMATCH"));
            if (!idsMatch) failures++;
        }

        // Rejection checks against live-produced + synthetic bad data.
        try {
            ArchiveCatalogue.parsePointer("{\"formatVersion\":99,\"revision\":5}");
            System.out.println("REJECT unsupported version: FAIL"); failures++;
        } catch (Exception ok) { System.out.println("REJECT unsupported version: OK"); }

        client.close();
        System.out.println(failures == 0 ? "CATALOGUE PROBE: PASS" : "CATALOGUE PROBE: FAIL");
        if (failures > 0) System.exit(1);
    }

    private static String text(SmbjArchiveClient client, String path) throws Exception {
        byte[] bytes = client.readSmallFile(path, 8 * 1024 * 1024);
        return new String(bytes, Charset.forName("UTF-8"));
    }
}
