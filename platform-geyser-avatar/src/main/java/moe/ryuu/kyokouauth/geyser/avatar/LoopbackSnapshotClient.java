package moe.ryuu.kyokouauth.geyser.avatar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Fixed GET-only loopback snapshot client; a request cannot prepare avatars or follow redirects. */
public final class LoopbackSnapshotClient implements AvatarSnapshotSource {
    private final URI endpoint;
    private final Set<HttpURLConnection> active = new HashSet<>();
    private boolean closed;

    public LoopbackSnapshotClient(URI endpoint) { AvatarConfig.requireProxyEndpoint(endpoint); this.endpoint = endpoint; }

    /** The caller's absolute deadline includes queue wait, headers and the complete bounded body. */
    public AvatarSnapshot fetch(String xuid, long deadlineNanos) throws IOException {
        if (!AvatarConfig.validXuid(xuid)) throw new IOException("Invalid presentation XUID");
        checkDeadline(deadlineNanos);
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint.toASCIIString() + "?xuid=" + xuid)
                .toURL().openConnection(Proxy.NO_PROXY);
        synchronized (active) {
            if (closed || !active.isEmpty()) throw new IOException("Avatar snapshot client unavailable");
            active.add(connection);
        }
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(readBudget(deadlineNanos));
            connection.setReadTimeout(readBudget(deadlineNanos));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");
            int status = connection.getResponseCode();
            checkDeadline(deadlineNanos);
            if (status != 200 && status != 202) throw new IOException("Avatar snapshot bridge unavailable");
            long length = connection.getContentLengthLong();
            if (length > AvatarSnapshot.MAX_JSON_BYTES) throw new IOException("Avatar snapshot body exceeds limit");
            byte[] body;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                while (true) {
                    checkDeadline(deadlineNanos);
                    connection.setReadTimeout(readBudget(deadlineNanos));
                    int count = input.read(buffer, 0, Math.min(buffer.length, AvatarSnapshot.MAX_JSON_BYTES - output.size() + 1));
                    if (count < 0) break;
                    if (output.size() + count > AvatarSnapshot.MAX_JSON_BYTES) throw new IOException("Avatar snapshot body exceeds limit");
                    output.write(buffer, 0, count);
                }
                checkDeadline(deadlineNanos);
                body = output.toByteArray();
            }
            try {
                if (status == 202) { AvatarSnapshot.notReady(body); return null; }
                return AvatarSnapshot.ready(body);
            } catch (IllegalArgumentException invalid) { throw new IOException("Invalid avatar snapshot schema or content"); }
        } finally {
            connection.disconnect();
            synchronized (active) { active.remove(connection); }
        }
    }

    private static void checkDeadline(long deadline) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new InterruptedIOException("Avatar snapshot deadline exceeded");
    }
    private static int readBudget(long deadline) throws InterruptedIOException {
        checkDeadline(deadline);
        long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        return (int) Math.min(50, remaining);
    }
    @Override public void close() {
        synchronized (active) {
            closed = true;
            for (HttpURLConnection connection : active) connection.disconnect();
            active.clear();
        }
    }
}
