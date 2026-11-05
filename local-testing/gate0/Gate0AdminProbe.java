import com.liskovsoft.smartyoutubetv2.common.local.SmbjArchiveClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Gate 0 T5: production SmbjArchiveClient reads small JSON fixtures.
 *  Password from env GATE0_PW. Args: <user> */
public class Gate0AdminProbe {
    public static void main(String[] args) throws Exception {
        String user = args.length > 0 ? args[0] : "admin";
        String pass = System.getenv("GATE0_PW");
        if (pass == null) { System.err.println("GATE0_PW not set"); System.exit(2); }

        SmbjArchiveClient client = new SmbjArchiveClient(
                "192.168.1.1", 445, "volume(sda1)", user, pass, "");
        client.connect();
        System.out.println("connect OK (user=" + user + ")");

        int failures = 0;
        failures += check(client, "YouTube-Local/_stage3_gate/fixtures/library.json",
                "\"revision\":1");
        failures += check(client, "YouTube-Local/_stage3_gate/fixtures/channel_UC123.json",
                "\"videoId\":\"abc123\"");
        client.close();
        System.out.println(failures == 0
                ? "T5 PASS: production SmbjArchiveClient read both fixtures"
                : "T5 FAIL (" + failures + ")");
        if (failures > 0) System.exit(1);
    }

    private static int check(SmbjArchiveClient c, String path, String mustContain) {
        try (InputStream in = c.openRead(path, 0)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            String s = out.toString("UTF-8");
            if (!s.trim().startsWith("{")) { System.out.println("BAD  " + path); return 1; }
            boolean ok = s.contains(mustContain);
            System.out.println((ok ? "READ OK  " : "CONTENT MISS  ") + path + "  bytes=" + s.trim().length());
            System.out.println("  " + s.trim());
            return ok ? 0 : 1;
        } catch (Exception e) {
            System.out.println("FAIL " + path + " : " + e);
            return 1;
        }
    }
}
