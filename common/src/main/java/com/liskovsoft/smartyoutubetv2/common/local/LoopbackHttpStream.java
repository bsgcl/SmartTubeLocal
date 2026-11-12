package com.liskovsoft.smartyoutubetv2.common.local;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Short-lived HTTP byte-range endpoint. It binds only to IPv4 loopback. */
public final class LoopbackHttpStream implements java.io.Closeable {
    public static final class ByteRange {
        public final long start;
        public final long end;

        ByteRange(long start, long end) {
            this.start = start;
            this.end = end;
        }

        public long length() {
            return end - start + 1;
        }
    }

    public static final class InvalidRangeException extends Exception {
        InvalidRangeException() {
            super("Range not satisfiable");
        }
    }

    private final SmbjArchiveClient smb;
    private final String relativePath;
    private final long length;
    private final String mimeType;
    private final String token = UUID.randomUUID().toString();
    private final Object socketLock = new Object();
    private final java.util.Set<Socket> activeSockets = new java.util.HashSet<>();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(
            0, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), new DaemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;

    public LoopbackHttpStream(SmbjArchiveClient smb, String relativePath, long length) {
        if (length < 0) {
            throw new IllegalArgumentException("A known file length is required for range playback");
        }
        this.smb = smb;
        this.relativePath = relativePath;
        this.length = length;
        this.mimeType = mimeType(relativePath);
    }

    public synchronized String start() throws IOException {
        if (serverSocket != null) {
            return url();
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        serverSocket = socket;
        acceptThread = new Thread(this::acceptLoop, "local-smb-loopback");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return url();
    }

    public synchronized String url() {
        if (serverSocket == null) {
            throw new IllegalStateException("Local stream has not started");
        }
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/" + token;
    }

    public static ByteRange parseRange(String value, long length) throws InvalidRangeException {
        if (value == null || !value.startsWith("bytes=") || length <= 0 || value.indexOf(',') >= 0) {
            throw new InvalidRangeException();
        }
        String range = value.substring("bytes=".length()).trim();
        int dash = range.indexOf('-');
        if (dash < 0 || dash != range.lastIndexOf('-')) {
            throw new InvalidRangeException();
        }
        try {
            String first = range.substring(0, dash).trim();
            String last = range.substring(dash + 1).trim();
            long start;
            long end;
            if (first.isEmpty()) {
                long suffix = Long.parseLong(last);
                if (suffix <= 0) throw new InvalidRangeException();
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(first);
                end = last.isEmpty() ? length - 1 : Long.parseLong(last);
            }
            if (start < 0 || end < start || start >= length) {
                throw new InvalidRangeException();
            }
            return new ByteRange(start, Math.min(end, length - 1));
        } catch (NumberFormatException e) {
            throw new InvalidRangeException();
        }
    }

    private void acceptLoop() {
        while (!isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(5_000);
                synchronized (socketLock) {
                    activeSockets.add(socket);
                }
                try {
                    requests.execute(() -> serve(socket));
                } catch (RuntimeException e) {
                    unregisterAndClose(socket);
                }
            } catch (SocketException e) {
                if (!isClosed()) close();
                return;
            } catch (IOException e) {
                if (!isClosed()) close();
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket requestSocket = socket) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    requestSocket.getInputStream(), Charset.forName("US-ASCII")));
            String request = reader.readLine();
            if (request == null || request.length() > 4096) {
                writeError(requestSocket, 400, "Bad Request");
                return;
            }
            String[] requestParts = request.split(" ");
            if (requestParts.length != 3 || !("GET".equals(requestParts[0]) || "HEAD".equals(requestParts[0]))) {
                writeError(requestSocket, 405, "Method Not Allowed");
                return;
            }
            String requestedPath = URLDecoder.decode(requestParts[1], "UTF-8");
            if (!("/" + token).equals(requestedPath)) {
                writeError(requestSocket, 404, "Not Found");
                return;
            }
            String rangeHeader = null;
            int headerBytes = request.length();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headerBytes += line.length();
                if (headerBytes > 16_384) {
                    writeError(requestSocket, 431, "Request Header Fields Too Large");
                    return;
                }
                int colon = line.indexOf(':');
                if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    rangeHeader = line.substring(colon + 1).trim();
                }
            }

            ByteRange range;
            int status;
            try {
                if (rangeHeader == null) {
                    range = new ByteRange(0, Math.max(0, length - 1));
                    status = 200;
                } else {
                    range = parseRange(rangeHeader, length);
                    status = 206;
                }
            } catch (InvalidRangeException e) {
                writeRangeError(requestSocket);
                return;
            }
            OutputStream output = new BufferedOutputStream(requestSocket.getOutputStream());
            String reason = status == 206 ? "Partial Content" : "OK";
            writeAscii(output, "HTTP/1.1 " + status + " " + reason + "\r\n");
            writeAscii(output, "Content-Type: " + mimeType + "\r\n");
            writeAscii(output, "Accept-Ranges: bytes\r\n");
            writeAscii(output, "Content-Length: " + (length == 0 ? 0 : range.length()) + "\r\n");
            if (status == 206) {
                writeAscii(output, "Content-Range: bytes " + range.start + "-" + range.end + "/" + length + "\r\n");
            }
            writeAscii(output, "Connection: close\r\n\r\n");
            output.flush();
            if ("GET".equals(requestParts[0]) && length > 0) {
                try (InputStream input = smb.openRead(relativePath, range.start)) {
                    copyExactly(input, output, range.length());
                }
                output.flush();
            }
        } catch (Exception ignored) {
            // Connection failures are isolated to this request; no credentials or paths are logged.
        } finally {
            synchronized (socketLock) {
                activeSockets.remove(socket);
            }
        }
    }

    private void writeRangeError(Socket socket) throws IOException {
        OutputStream output = new BufferedOutputStream(socket.getOutputStream());
        writeAscii(output, "HTTP/1.1 416 Range Not Satisfiable\r\n");
        writeAscii(output, "Content-Range: bytes */" + length + "\r\n");
        writeAscii(output, "Content-Length: 0\r\nConnection: close\r\n\r\n");
        output.flush();
    }

    private void writeError(Socket socket, int status, String reason) throws IOException {
        OutputStream output = new BufferedOutputStream(socket.getOutputStream());
        writeAscii(output, "HTTP/1.1 " + status + " " + reason + "\r\n");
        writeAscii(output, "Content-Length: 0\r\nConnection: close\r\n\r\n");
        output.flush();
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(Charset.forName("US-ASCII")));
    }

    private static void copyExactly(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("SMB source ended during the HTTP response");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static String mimeType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        return "application/octet-stream";
    }

    private boolean isClosed() {
        return serverSocket == null || serverSocket.isClosed();
    }

    private void unregisterAndClose(Socket socket) {
        synchronized (socketLock) {
            activeSockets.remove(socket);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        ServerSocket listener;
        synchronized (this) {
            listener = serverSocket;
            serverSocket = null;
        }
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        }
        synchronized (socketLock) {
            for (Socket socket : activeSockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            activeSockets.clear();
        }
        requests.shutdownNow();
        smb.close();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "local-smb-http-request");
            thread.setDaemon(true);
            return thread;
        }
    }
}
