import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A fake premium client, used to verify the server half of the Mojang handshake.
 *
 * It performs everything a real premium client does *except* register the session with Mojang - it
 * cannot, without a real account. That is the point: it exercises the entire cryptographic handshake
 * and then proves the server refuses a session Mojang never confirmed, which is the exact path that
 * stops a cracked client from using a premium name.
 *
 * Usage: java HandshakeProbe <host> <port> <protocol> <name>
 * Exit: 0 = admitted (unexpected without a real session), 1 = rejected (expected), 3 = protocol error
 */
public final class HandshakeProbe {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int protocol = Integer.parseInt(args[2]);
        String name = args[3];

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(20000);

            OutputStream out = socket.getOutputStream();
            InputStream plain = socket.getInputStream();
            // Reassigned once the server switches the connection to encrypted, which Floodgate forces
            // on every connection. Without following it, this probe can only ever see the Encryption
            // Request and never the Login Success that carries the UUID.
            InputStream in = plain;

            sendHandshake(out, protocol, host, port);
            sendLoginStart(out, name, protocol);

            int compressionThreshold = -1;
            while (true) {
                int length = readVarInt(in);
                if (length < 0) {
                    System.out.println("RESULT=eof");
                    System.exit(3);
                }
                byte[] frame = new byte[length];
                new DataInputStream(in).readFully(frame);
                if (compressionThreshold >= 0) frame = decompress(frame);

                int[] cursor = {0};
                int packetId = readVarInt(frame, cursor);

                if (packetId == 0x03) {
                    compressionThreshold = readVarInt(frame, cursor);
                    System.out.println("COMPRESSION=" + compressionThreshold);
                    continue;
                }

                if (packetId == 0x01) {
                    // Encryption Request: serverId, publicKey, verifyToken, [shouldAuthenticate]
                    String serverId = readString(frame, cursor);
                    byte[] publicKeyBytes = readByteArray(frame, cursor);
                    byte[] verifyToken = readByteArray(frame, cursor);

                    System.out.println("ENCRYPTION_REQUEST received");
                    System.out.println("  serverId=\"" + serverId + "\" publicKey=" + publicKeyBytes.length +
                        "B verifyToken=" + verifyToken.length + "B");

                    byte[] sharedSecret = respondEncryption(out, publicKeyBytes, verifyToken);
                    System.out.println("ENCRYPTION_RESPONSE sent");

                    // Everything from here on arrives encrypted with AES/CFB8 keyed and IV'd by the
                    // shared secret, exactly as vanilla does it.
                    in = new DecryptingInputStream(plain, sharedSecret);
                    System.out.println("ENCRYPTION enabled");
                    continue;
                }

                if (packetId == 0x00) {
                    String json = readString(frame, cursor);
                    System.out.println("RESULT=disconnect");
                    System.out.println("MESSAGE=" + json);
                    System.exit(1);
                    return;
                }

                if (packetId == 0x02) {
                    // 1.16 (protocol 735) changed the UUID field from a string to 16 raw bytes.
                    String uuid;
                    if (protocol >= 735) {
                        long high = readLong(frame, cursor);
                        long low = readLong(frame, cursor);
                        uuid = new java.util.UUID(high, low).toString();
                    } else {
                        uuid = readString(frame, cursor);
                    }
                    String resolvedName = readString(frame, cursor);

                    System.out.println("RESULT=login_success");
                    System.out.println("UUID=" + uuid);
                    System.out.println("NAME=" + resolvedName);
                    System.exit(0);
                    return;
                }
            }
        }
    }

    /**
     * Encrypts a random shared secret and the verify token, both reversed as vanilla does.
     *
     * The reversal is what a naive client implementation gets wrong, and the server rejects it - so
     * this probe failing here would mean the probe is wrong, not the server.
     */
    private static byte[] respondEncryption(OutputStream out, byte[] publicKeyBytes, byte[] verifyToken)
        throws Exception {
        java.security.KeyFactory factory = KeyFactory.getInstance("RSA");
        PublicKey serverKey = factory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

        byte[] sharedSecret = new byte[16];
        new SecureRandom().nextBytes(sharedSecret);

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, serverKey);

        // No byte reversal: vanilla's Crypt.cipherData is plain `doFinal`. This probe reversed too
        // once, which matched a matching bug on the server side and made both "pass".
        byte[] encryptedSecret = cipher.doFinal(sharedSecret);
        byte[] encryptedToken = cipher.doFinal(verifyToken);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x01); // Encryption Response
        writeByteArray(payload, encryptedSecret);
        writeByteArray(payload, encryptedToken);

        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, payload.size());
        framed.write(payload.toByteArray());
        out.write(framed.toByteArray());
        out.flush();

        return sharedSecret;
    }

    /** AES/CFB8 decryption of the inbound stream, keyed and IV'd by the shared secret. */
    private static final class DecryptingInputStream extends InputStream {

        private final InputStream source;
        private final javax.crypto.Cipher cipher;
        private final java.util.ArrayDeque<Byte> pending = new java.util.ArrayDeque<>();

        DecryptingInputStream(InputStream source, byte[] sharedSecret) throws Exception {
            this.source = source;
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(sharedSecret, "AES");
            javax.crypto.spec.IvParameterSpec iv = new javax.crypto.spec.IvParameterSpec(sharedSecret);
            this.cipher = javax.crypto.Cipher.getInstance("AES/CFB8/NoPadding");
            this.cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, iv);
        }

        @Override
        public int read() throws IOException {
            if (pending.isEmpty()) {
                int raw = source.read();
                if (raw < 0) return -1;
                byte[] plain = cipher.update(new byte[] {(byte) raw});
                if (plain.length == 0) return read();
                for (byte value : plain) pending.add(value);
            }
            return pending.poll() & 0xFF;
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
        writeVarInt(payload, 2);
        frame(out, payload);
    }

    private static void sendLoginStart(OutputStream out, String name, int protocol) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x00);
        writeString(payload, name);
        if (protocol >= 764) {
            java.util.UUID uuid = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            writeLong(payload, uuid.getMostSignificantBits());
            writeLong(payload, uuid.getLeastSignificantBits());
        }
        frame(out, payload);
    }

    private static void frame(OutputStream out, ByteArrayOutputStream payload) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, payload.size());
        framed.write(payload.toByteArray());
        out.write(framed.toByteArray());
        out.flush();
    }

    private static byte[] decompress(byte[] data) throws IOException {
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
            inflater.inflate(out);
        } catch (DataFormatException e) {
            throw new IOException("bad compressed frame", e);
        } finally {
            inflater.end();
        }
        return out;
    }

    // ---- primitives ----

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0, position = 0;
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
        int value = 0, position = 0;
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

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) out.write((int) (value >>> (8 * (7 - i))) & 0xFF);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeByteArray(ByteArrayOutputStream out, byte[] value) {
        writeVarInt(out, value.length);
        out.write(value, 0, value.length);
    }

    private static String readString(byte[] data, int[] cursor) {
        int length = readVarInt(data, cursor);
        String result = new String(data, cursor[0], length, StandardCharsets.UTF_8);
        cursor[0] += length;
        return result;
    }

    private static long readLong(byte[] data, int[] cursor) {
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (data[cursor[0]++] & 0xFFL);
        return value;
    }

    private static byte[] readByteArray(byte[] data, int[] cursor) {
        int length = readVarInt(data, cursor);
        byte[] out = new byte[length];
        System.arraycopy(data, cursor[0], out, 0, length);
        cursor[0] += length;
        return out;
    }
}
