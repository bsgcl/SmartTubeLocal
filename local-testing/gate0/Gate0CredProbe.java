import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

public class Gate0CredProbe {
    public static void main(String[] args) throws Exception {
        String[][] creds = {
                {"GUESTFLAG", ""},
                {"guest", ""},
                {"guest", "guest"},
                {"ANON", ""},
                {"", ""},
        };
        for (String[] c : creds) {
            long t0 = System.currentTimeMillis();
            try {
                SmbConfig config = SmbConfig.builder()
                        .withSecurityProvider(new BCSecurityProvider())
                        .withMultiProtocolNegotiate(true)
                        .withSigningRequired(false)
                        .withTimeout(8, TimeUnit.SECONDS)
                        .withSoTimeout(8, TimeUnit.SECONDS)
                        .build();
                SMBClient client = new SMBClient(config);
                Connection connection = client.connect("192.168.1.1", 445);
                Session session;
                if ("ANON".equals(c[0])) {
                    session = connection.authenticate(AuthenticationContext.anonymous());
                } else if ("GUESTFLAG".equals(c[0])) {
                    session = connection.authenticate(AuthenticationContext.guest());
                } else {
                    session = connection.authenticate(
                            new AuthenticationContext(c[0], c[1].toCharArray(), ""));
                }
                try (DiskShare share = (DiskShare) session.connectShare("volume(sda1)")) {
                    // production-style open: FILE_OPEN via openFile, then stream read
                    com.hierynomus.smbj.share.File f = share.openFile("_stage3_gate0\\pointer.json",
                            EnumSet.of(AccessMask.FILE_READ_DATA),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_READONLY),
                            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                            SMB2CreateDisposition.FILE_OPEN,
                            EnumSet.of(SMB2CreateOptions.FILE_RANDOM_ACCESS));
                    try (InputStream in = f.getInputStream()) {
                        byte[] buf = new byte[256];
                        int n = in.read(buf);
                        String txt = new String(buf, 0, Math.max(n, 0), "UTF-8").trim();
                        System.out.println("creds=" + c[0] + "/" + (c[1].isEmpty() ? "(empty)" : c[1])
                                + " -> OK in " + (System.currentTimeMillis() - t0) + "ms, pointer.json=" + txt);
                    } finally {
                        f.close();
                    }
                }
                client.close();
                return; // success stops the loop
            } catch (Exception e) {
                System.out.println("creds=" + c[0] + "/" + (c[1].isEmpty() ? "(empty)" : c[1])
                        + " -> FAILED in " + (System.currentTimeMillis() - t0) + "ms: " + e.getMessage());
            }
        }
        System.out.println("NO-CREDENTIAL-VARIANT-SUCCEEDED");
    }
}
