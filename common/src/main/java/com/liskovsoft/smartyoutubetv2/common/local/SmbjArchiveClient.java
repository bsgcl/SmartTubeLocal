/*
 * The SMB connection, authentication, share, and read-open sequence is adapted from
 * NovaPlayerKids/FileCoreLibrary SmbjUtils and SmbjFileEditor (Apache-2.0), pinned at
 * NovaPlayerKids HEAD 6d70850bc508cd9075460090fd7bf6af7f3284fc.
 * This adapter deliberately exposes only read and directory-list operations.
 */
package com.liskovsoft.smartyoutubetv2.common.local;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
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
import com.hierynomus.smbj.share.File;
import org.json.JSONException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Read-only SMB2/SMB3 access for a single configured archive share. */
public final class SmbjArchiveClient implements AutoCloseable {
    public static final class Entry {
        public final String name;
        public final boolean directory;
        public final long length;

        Entry(String name, boolean directory, long length) {
            this.name = name;
            this.directory = directory;
            this.length = length;
        }
    }

    private final String host;
    private final int port;
    private final String shareName;
    private final String username;
    private final String password;
    private final String domain;
    private SMBClient client;
    private Connection connection;
    private Session session;
    private DiskShare share;

    public SmbjArchiveClient(String host, int port, String shareName,
                             String username, String password, String domain) {
        this.host = host;
        this.port = port;
        this.shareName = shareName;
        this.username = username;
        this.password = password;
        this.domain = domain == null ? "" : domain;
    }

    public synchronized void connect() throws IOException {
        if (share != null && share.isConnected()) {
            return;
        }
        close();
        try {
            SmbConfig config = SmbConfig.builder()
                    .withSecurityProvider(new BCSecurityProvider())
                    .withMultiProtocolNegotiate(true)
                    .withSigningRequired(false)
                    .withTimeout(12, TimeUnit.SECONDS)
                    .withSoTimeout(12, TimeUnit.SECONDS)
                    .build();
            client = new SMBClient(config);
            connection = client.connect(host, port);
            AuthenticationContext authentication = new AuthenticationContext(
                    username == null ? "" : username,
                    password == null ? new char[0] : password.toCharArray(), domain);
            session = connection.authenticate(authentication);
            share = (DiskShare) session.connectShare(shareName);
        } catch (Exception e) {
            close();
            throw new IOException("Unable to connect to the configured SMB archive", e);
        }
    }

    public synchronized List<Entry> list(String relativeDirectory) throws IOException {
        connect();
        String safePath = relativeDirectory == null || relativeDirectory.isEmpty() ? "" :
                smbPath(safeRelativePath(relativeDirectory));
        try {
            List<FileIdBothDirectoryInformation> rows = share.list(safePath);
            List<Entry> result = new ArrayList<>();
            for (FileIdBothDirectoryInformation row : rows) {
                String name = row.getFileName();
                if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                    continue;
                }
                long attributes = row.getFileAttributes();
                boolean isDirectory = EnumWithValue.EnumUtils.isSet(
                        attributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
                long length = isDirectory ? 0 : row.getEndOfFile();
                result.add(new Entry(name, isDirectory, length));
            }
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            throw new IOException("Unable to list the configured SMB archive", e);
        }
    }

    public synchronized InputStream openRead(String relativePath, long offset) throws IOException {
        connect();
        if (offset < 0) {
            throw new IOException("Invalid SMB read offset");
        }
        File openedFile = null;
        try {
            openedFile = share.openFile(smbPath(safeRelativePath(relativePath)),
                    EnumSet.of(AccessMask.FILE_READ_DATA),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_READONLY),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_RANDOM_ACCESS));
            final File file = openedFile;
            final InputStream input = file.getInputStream();
            skipFully(input, offset);
            return new FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        file.close();
                    }
                }
            };
        } catch (Exception e) {
            if (openedFile != null) {
                try {
                    openedFile.close();
                } catch (Exception ignored) {
                    // Preserve the original read failure.
                }
            }
            throw new IOException("Unable to read the requested SMB archive file", e);
        }
    }

    public synchronized byte[] readSmallFile(String relativePath, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        try (InputStream input = openRead(relativePath, 0)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maxBytes) {
                    throw new IOException("SMB archive metadata file exceeds the size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String smbPath(String relativePath) {
        return relativePath.replace('/', '\\');
    }

    private static String safeRelativePath(String path) throws IOException {
        try {
            return LocalArchiveIndex.requireSafeRelativePath(path);
        } catch (JSONException e) {
            throw new IOException("Unsafe archive-relative path", e);
        }
    }

    private static void skipFully(InputStream input, long offset) throws IOException {
        long remaining = offset;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new IOException("SMB file ended before the requested seek position");
            }
            remaining--;
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (share != null) share.close();
        } catch (Exception ignored) {
        }
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (connection != null) connection.close(true);
        } catch (Exception ignored) {
        }
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {
        }
        share = null;
        session = null;
        connection = null;
        client = null;
    }
}
