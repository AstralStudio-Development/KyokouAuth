import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A minimal Minecraft login-phase client, used to verify the login gate end to end.
 *
 * The unit tests prove the decision logic. They say nothing about whether the decision is actually
 * reached on a real connection, or whether the resulting kick message survives the wire. This probe
 * does exactly one thing: connect, send Handshake + Login Start, and report what the server answers.
 *
 * Deliberately not a test-framework artefact - it needs a running server, and spawning one inside a
 * unit test would make the suite depend on ports and timing.
 *
 * Usage: java LoginProbe <host> <port> <protocolVersion> <name> [encryptionRequestsToWaitFor]
 *
 * The optional fifth argument exists to answer one specific question: whether more than one
 * Encryption Request reaches the client. Two handlers both driving the handshake would send two,
 * and a client cannot answer the second - so counting them is the difference between "these two
 * plugins coexist" and "premium players hang". Default 1, which is the plain login-gate check.
 *
 * Exit: 0 = Login Success, 1 = Disconnect, 2 = Encryption Request, 3 = protocol error
 */
public final class LoginProbe {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int protocol = Integer.parseInt(args[2]);
        String name = args[3];
        int wanted = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(15000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, protocol, host, port);
            sendLoginStart(out, name, protocol);

            int compressionThreshold = -1;
            int seenEncryptionRequests = 0;
            while (true) {
                byte[] frame;
                try {
                    frame = readFrame(in, compressionThreshold);
                } catch (java.net.SocketTimeoutException idle) {
                    // Nothing more arrived. With a count target this is the answer - the server sent
                    // fewer Encryption Requests than asked for and is now waiting on a client that
                    // will never answer.
                    if (seenEncryptionRequests > 0) {
                        System.out.println("TOTAL_ENCRYPTION_REQUESTS=" + seenEncryptionRequests);
                        System.exit(2);
                    }
                    throw idle;
                }
                if (frame == null) {
                    System.out.println("RESULT=eof");
                    System.exit(3);
                }
                int[] cursor = {0};
                int packetId = readVarInt(frame, cursor);
                int bodyStart = cursor[0];

                switch (packetId) {
                    case 0x03: { // Set Compression
                        int[] c = {bodyStart};
                        compressionThreshold = readVarInt(frame, c);
                        System.out.println("COMPRESSION=" + compressionThreshold);
                        continue;
                    }
                    case 0x01: { // Encryption Request
                        seenEncryptionRequests++;
                        System.out.println("RESULT=encryption_request");
                        System.out.println("ENCRYPTION_REQUESTS=" + seenEncryptionRequests);
                        if (seenEncryptionRequests >= wanted) {
                            System.exit(2);
                            return;
                        }
                        continue;
                    }
                    case 0x00: { // Disconnect
                        int[] c = {bodyStart};
                        String json = readString(frame, c);
                        System.out.println("RESULT=disconnect");
                        System.out.println("MESSAGE=" + json);
                        System.exit(1);
                        return;
                    }
                    case 0x02: { // Login Success
                        int[] c = {bodyStart};
                        // 1.16 (protocol 735) changed the UUID field from a string to 16 raw bytes.
                        String uuid;
                        if (protocol >= 735) {
                            long high = readLong(frame, c);
                            long low = readLong(frame, c);
                            uuid = new java.util.UUID(high, low).toString();
                        } else {
                            uuid = readString(frame, c);
                        }
                        String username = readString(frame, c);
                        System.out.println("RESULT=login_success");
                        System.out.println("UUID=" + uuid);
                        System.out.println("NAME=" + username);
                        System.exit(0);
                        return;
                    }
                    default:
                        System.out.println("SKIP packet=0x" + Integer.toHexString(packetId));
                }
            }
        }
    }

    // ---- packets ----

    private static void sendHandshake(OutputStream out, int protocol, String host, int port)
        throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x00);
        writeVarInt(payload, protocol);
        writeString(payload, host);
        payload.write((port >> 8) & 0xFF);
        payload.write(port & 0xFF);
        writeVarInt(payload, 2); // next state: login

        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, payload.size());
        framed.write(payload.toByteArray());
        out.write(framed.toByteArray());
        out.flush();
    }

    /**
     * Sends Login Start in the shape the given protocol expects.
     *
     * 1.20.2 (protocol 764) made the client-supplied UUID a required field of ServerboundHello. An
     * older shape on a newer server fails inside Netty's decoder before any plugin sees it, which is
     * what the first probe run hit.
     */
    private static void sendLoginStart(OutputStream out, String name, int protocol) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x00);
        writeString(payload, name);
        if (protocol >= 764) {
            // An offline-mode client sends its derived UUID; the value only matters to the server
            // that trusts it, and this plugin overrides whatever arrives.
            java.util.UUID uuid = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            payload.write(longToBytes(uuid.getMostSignificantBits()));
            payload.write(longToBytes(uuid.getLeastSignificantBits()));
        }

        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, payload.size());
        framed.write(payload.toByteArray());
        out.write(framed.toByteArray());
        out.flush();
    }

    private static byte[] longToBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) out[i] = (byte) (value >>> (8 * (7 - i)));
        return out;
    }

    private static long readLong(byte[] data, int[] cursor) {
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (data[cursor[0]++] & 0xFFL);
        return value;
    }

    // ---- framing ----

    private static byte[] readFrame(InputStream in, int compressionThreshold) throws IOException {
        int length = readVarInt(in);
        if (length < 0) return null;
        byte[] data = new byte[length];
        new DataInputStream(in).readFully(data);

        if (compressionThreshold < 0) return data;

        // With compression enabled every packet is prefixed by the uncompressed size;
        // zero means the body was sent verbatim.
        int[] cursor = {0};
        int uncompressed = readVarInt(data, cursor);
        if (uncompressed == 0) {
            byte[] trimmed = new byte[data.length - cursor[0]];
            System.arraycopy(data, cursor[0], trimmed, 0, trimmed.length);
            return trimmed;
        }
        Inflater inflater = new Inflater();
        inflater.setInput(data, cursor[0], data.length - cursor[0]);
        byte[] out = new byte[uncompressed];
        try {
            int written = inflater.inflate(out);
            if (written != uncompressed) throw new IOException("short inflate");
        } catch (DataFormatException e) {
            throw new IOException("bad compressed frame", e);
        } finally {
            inflater.end();
        }
        return out;
    }

    // ---- primitives ----

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int read = in.read();
            if (read < 0) return -1;
            value |= (read & 0x7F) << position;
            if ((read & 0x80) == 0) return value;
            position += 7;
            if (position > 35) throw new IOException("varint too long");
        }
    }

    private static int readVarInt(byte[] data, int[] cursor) {
        int value = 0;
        int position = 0;
        while (true) {
            int read = data[cursor[0]++] & 0xFF;
            value |= (read & 0x7F) << position;
            if ((read & 0x80) == 0) return value;
            position += 7;
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static String[] strings = new String[4];

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static String readString(byte[] data, int[] cursor) {
        int length = readVarInt(data, cursor);
        String result = new String(data, cursor[0], length, StandardCharsets.UTF_8);
        cursor[0] += length;
        return result;
    }
}
