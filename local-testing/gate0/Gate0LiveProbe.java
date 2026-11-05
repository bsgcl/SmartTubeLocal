import com.liskovsoft.smartyoutubetv2.common.local.SmbjArchiveClient;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** Gate 0 item 2: standalone proof that the production SmbjArchiveClient reads the live fixtures. */
public class Gate0LiveProbe {
    public static void main(String[] args) throws Exception {
        SmbjArchiveClient client = new SmbjArchiveClient(
                "192.168.1.1", 445, "volume(sda1)", "guest", "", "");
        int failures = 0;
        try {
            client.connect();
            System.out.println("CONNECT : OK");

            boolean sawPointer = false;
            List<SmbjArchiveClient.Entry> entries = client.list("_stage3_gate0");
            for (SmbjArchiveClient.Entry e : entries) {
                if ("pointer.json".equals(e.name)) {
                    sawPointer = true;
                    System.out.println("LIST    : pointer.json present, length=" + e.length);
                }
            }
            if (!sawPointer) { System.out.println("LIST    : FAIL pointer.json missing"); failures++; }

            String ptr = readAll(client, "pointer.json");
            JSONObject pointer = new JSONObject(ptr);
            int current = pointer.getInt("current");
            System.out.println("POINTER : parsed, current=" + current);

            String snapText = readAll(client, "catalogue.v" + current + ".json");
            JSONObject snapshot = new JSONObject(snapText);
            if (snapshot.getInt("gen") != current) { failures++; }
            System.out.println("SNAPSHOT: parsed catalogue.v" + current + ".json, gen="
                    + snapshot.getInt("gen") + ", videos="
                    + snapshot.getJSONArray("videos").length());

            String broken = readAll(client, "broken.json");
            try {
                new JSONObject(broken);
                System.out.println("INVALID : FAIL broken.json unexpectedly parsed");
                failures++;
            } catch (RuntimeException ok) {
                System.out.println("INVALID : correctly rejected broken.json -> consumer defers refresh");
            }
        } finally {
            client.close();
        }
        System.out.println(failures == 0 ? "RESULT  : PASS" : "RESULT  : FAIL (" + failures + ")");
        if (failures > 0) System.exit(1);
    }

    private static String readAll(SmbjArchiveClient client, String rel) throws Exception {
        try (InputStream in = client.openRead("_stage3_gate0/" + rel, 0)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
