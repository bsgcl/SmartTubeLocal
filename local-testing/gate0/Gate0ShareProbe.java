import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.security.bc.BCSecurityProvider;

import java.util.concurrent.TimeUnit;

public class Gate0ShareProbe {
    public static void main(String[] args) throws Exception {
        SmbConfig config = SmbConfig.builder()
                .withSecurityProvider(new BCSecurityProvider())
                .withMultiProtocolNegotiate(true)
                .withSigningRequired(false)
                .withTimeout(8, TimeUnit.SECONDS)
                .withSoTimeout(8, TimeUnit.SECONDS)
                .build();
        SMBClient client = new SMBClient(config);
        Connection connection = client.connect("192.168.1.1", 445);
        System.out.println("negotiated dialect: " + connection.getNegotiatedProtocol().getDialect());
        Session session = connection.authenticate(AuthenticationContext.anonymous());
        System.out.println("authenticated: " + (session != null));
        String[] candidates = {"volume(sda1)", "Volume(sda1)", "sda1", "IPC$"};
        for (String share : candidates) {
            long t0 = System.currentTimeMillis();
            try {
                DiskShare ds = (DiskShare) session.connectShare(share);
                System.out.println("connectShare(" + share + ") OK in " + (System.currentTimeMillis() - t0) + "ms");
                ds.close();
            } catch (Exception e) {
                System.out.println("connectShare(" + share + ") FAILED in "
                        + (System.currentTimeMillis() - t0) + "ms: " + e.getMessage());
            }
        }
        client.close();
    }
}
