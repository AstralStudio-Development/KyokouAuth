import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Asks a server for its status JSON, the way the multiplayer server list does.
 *
 * Geyser reports "Failed to ping the remote Java server" against this server while ordinary logins
 * work, so something disagrees about the status exchange specifically. This probe separates the two:
 * if it gets a JSON body back, the server is fine and the disagreement is on Geyser's side; if it
 * gets nothing, the server is refusing or mangling the status handshake.
 *
 * Usage: java StatusProbe <host> <port> [protocol]
 * Exit: 0 = status received, 3 = protocol error
 */
public final class StatusProbe {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int protocol = args.length > 2 ? Integer.parseInt(args[2]) : 777;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(10000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Handshake with nextState 1: "I am about to ask for status", not "I am about to log in".
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, protocol);
            writeString(handshake, host);
            handshake.write((port >> 8) & 0xFF);
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);
            frame(out, handshake);

            ByteArrayOutputStream request = new ByteArrayOutputStream();
            writeVarInt(request, 0x00);
            frame(out, request);

            int length = readVarInt(in);
            if (length < 0) {
                System.out.println("RESULT=eof");
                System.exit(3);
            }

            byte[] payload = new byte[length];
            new DataInputStream(in).readFully(payload);

            int[] cursor = {0};
            int packetId = readVarInt(payload, cursor);
            String json = readString(payload, cursor);

            System.out.println("RESULT=status packetId=0x" + Integer.toHexString(packetId));
            System.out.println(json);
        }
    }

    private static void frame(OutputStream out, ByteArrayOutputStream payload) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, payload.size());
        framed.write(payload.toByteArray());
        out.write(framed.toByteArray());
        out.flush();
    }

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
