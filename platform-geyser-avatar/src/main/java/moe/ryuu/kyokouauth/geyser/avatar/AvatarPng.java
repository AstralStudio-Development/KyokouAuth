package moe.ryuu.kyokouauth.geyser.avatar;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

/** Strict 32x32, 8-bit RGBA, non-interlaced sprite boundary before any image decoder allocation. */
final class AvatarPng {
    static final int MAX_BYTES = 8192;
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    static void validate(byte[] png) {
        if (png == null || png.length < 57 || png.length > MAX_BYTES ||
                !Arrays.equals(SIGNATURE, Arrays.copyOf(png, 8))) throw new IllegalArgumentException("Invalid avatar PNG envelope");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        int at = 8, chunks = 0;
        boolean header = false, ended = false, data = false;
        while (at < png.length) {
            if (++chunks > 32 || at + 12 > png.length) throw new IllegalArgumentException("Invalid avatar PNG chunks");
            int size = ByteBuffer.wrap(png, at, 4).getInt();
            if (size < 0 || size > MAX_BYTES || at + 12L + size > png.length) throw new IllegalArgumentException("Invalid avatar PNG chunk size");
            String type = new String(png, at + 4, 4, StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(png, at + 4, size + 4);
            if ((int) crc.getValue() != ByteBuffer.wrap(png, at + 8 + size, 4).getInt())
                throw new IllegalArgumentException("Avatar PNG checksum differs");
            if (!header) {
                if (!type.equals("IHDR") || size != 13) throw new IllegalArgumentException("Avatar PNG header absent");
                ByteBuffer shape = ByteBuffer.wrap(png, at + 8, 13);
                if (shape.getInt() != 32 || shape.getInt() != 32 || shape.get() != 8 || shape.get() != 6 ||
                        shape.get() != 0 || shape.get() != 0 || shape.get() != 0)
                    throw new IllegalArgumentException("Avatar sprite must be 32x32 RGBA8 without interlace");
                header = true;
            } else if (type.equals("IDAT") && !ended) {
                compressed.write(png, at + 8, size);
                data = true;
            } else if (type.equals("IEND") && size == 0 && data) {
                ended = true;
                if (at + 12 != png.length) throw new IllegalArgumentException("Trailing avatar PNG data");
            } else throw new IllegalArgumentException("Non-canonical avatar PNG chunk");
            at += size + 12;
        }
        if (!ended) throw new IllegalArgumentException("Avatar PNG is incomplete");
        // Reject compressed allocation bombs before ImageIO; exactly 32 scanlines of RGBA pixels.
        int rawLength = 32 * (1 + 32 * 4);
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            byte[] raw = input.readNBytes(rawLength + 1);
            if (raw.length != rawLength || input.read() != -1) throw new IllegalArgumentException("Avatar PNG pixel budget differs");
            for (int row = 0; row < 32; row++) if ((raw[row * 129] & 255) > 4)
                throw new IllegalArgumentException("Invalid avatar PNG filter");
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid avatar PNG compression"); }
    }

    /** Clearly generic question-mark placeholders, never an unknown player's Steve skin. */
    static byte[] fallback(boolean microsoft) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        int accent = microsoft ? 0xFF22D3EE : 0xFFC084FC;
        for (int y = 3; y < 29; y++) for (int x = 3; x < 29; x++) {
            if (Math.abs(x - 16) + Math.abs(y - 16) <= 18) image.setRGB(x, y, 0xFF172033);
        }
        int[][] question = {{11, 7, 10, 3}, {18, 9, 3, 7}, {15, 14, 5, 3}, {14, 17, 3, 4}, {14, 24, 3, 3}};
        for (int[] block : question) for (int y = block[1]; y < block[1] + block[3]; y++)
            for (int x = block[0]; x < block[0] + block[2]; x++) image.setRGB(x, y, accent);
        // Separate role badges make the two generic fallbacks distinguishable at inventory scale.
        for (int y = 25; y < 30; y++) for (int x = microsoft ? 3 : 25; x < (microsoft ? 7 : 29); x++) image.setRGB(x, y, accent);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", out)) throw new IllegalStateException("PNG encoder unavailable");
            byte[] png = out.toByteArray();
            validate(png);
            return png;
        } catch (IOException failure) { throw new IllegalStateException("Could not encode generic avatar", failure); }
    }
    private AvatarPng() {}
}
