package moe.ryuu.kyokouauth.geyser.avatar;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AvatarSnapshotTest {
    @Test void fingerprintUsesTheFrozenVersionedBigEndianWireVector() {
        assertEquals("a3e1cc6d2f0e1532d5afddaac26a847718f38ee9df82daaf47b9bb6912b91556",
                AvatarSnapshot.fingerprint(new byte[]{1, 2}, new byte[]{3}));
    }
    @Test void readyIsImmutableContentCheckedAndLegitimateDefaultFlagsAreNotGenericFallback() throws Exception {
        AvatarSnapshot first = AvatarTestData.snapshot(0xFF112233);
        AvatarSnapshot parsed = AvatarSnapshot.ready(AvatarTestData.json(first));
        assertEquals(first.fingerprint(), parsed.fingerprint());
        byte[] getter = parsed.microsoftPng();
        getter[20] ^= 1;
        assertArrayEquals(first.microsoftPng(), parsed.microsoftPng());
        byte[] u = first.microsoftPng(), j = first.premiumPng();
        AvatarSnapshot defaults = new AvatarSnapshot(first.fingerprint(), u, j, true, true);
        u[20] ^= 1;
        assertArrayEquals(first.microsoftPng(), defaults.microsoftPng());
        assertEquals(first.fingerprint(), defaults.fingerprint(), "Default labels are not image-content keys");
        assertThrows(IllegalArgumentException.class, () -> new AvatarSnapshot("0".repeat(64), first.microsoftPng(), first.premiumPng(), false, false));
        AvatarSnapshot nextSkin = AvatarTestData.snapshot(0xFF445566);
        assertNotEquals(first.fingerprint(), nextSkin.fingerprint());
    }

    @Test void jsonBoundsSchemaDuplicateFieldsAndCanonicalBase64FailClosed() throws Exception {
        String valid = new String(AvatarTestData.json(AvatarTestData.snapshot(0xFF112233)), StandardCharsets.UTF_8);
        for (String invalid : new String[]{valid.replace("\"schema\":1", "\"schema\":2"),
                valid.replace("\"state\":\"ready\"", "\"state\":\"applied\""),
                valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                valid.replace("\"schema\":1", "\"unknown\":true,\"schema\":1"),
                valid.replace("\"microsoft_default\":false", "\"microsoft_default\":null"),
                valid.replace("\"premium_default\":false", "\"premium_default\":\"false\""),
                valid.replace("\"microsoft_png\":\"", "\"microsoft_png\":\"data:image/png;base64,"),
                valid + "trailing", valid.substring(0, valid.length() - 1)}) {
            assertThrows(IllegalArgumentException.class, () -> AvatarSnapshot.ready(invalid.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IllegalArgumentException.class, () -> AvatarSnapshot.ready(new byte[24577]));
        assertThrows(IllegalArgumentException.class, () -> AvatarSnapshot.ready(new byte[]{(byte) 0xC0, (byte) 0xAF}));
        assertDoesNotThrow(() -> AvatarSnapshot.notReady("{\"schema\":1,\"state\":\"not-ready\"}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AvatarSnapshot.notReady(valid.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void pngRejectsHugeShapesUnexpectedChunksCorruptionAndCompressedPixelBudgetBeforeDecoding() throws Exception {
        byte[] valid = AvatarTestData.png(0xFF123456);
        byte[] huge = valid.clone();
        ByteBuffer.wrap(huge, 16, 4).putInt(1_000_000);
        CRC32 crc = new CRC32(); crc.update(huge, 12, 17);
        ByteBuffer.wrap(huge, 29, 4).putInt((int) crc.getValue());
        assertThrows(IllegalArgumentException.class, () -> AvatarPng.validate(huge));
        byte[] corrupted = valid.clone(); corrupted[corrupted.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> AvatarPng.validate(corrupted));
        assertThrows(IllegalArgumentException.class, () -> AvatarPng.validate(Arrays.copyOf(valid, 8193)));
        byte[] ihdr = Arrays.copyOfRange(valid, 8, 33);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) { deflater.write(new byte[16384]); }
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        bomb.write(valid, 0, 8); bomb.write(ihdr); bomb.write(chunk("IDAT", compressed.toByteArray())); bomb.write(chunk("IEND", new byte[0]));
        assertTrue(bomb.size() < 8192);
        assertThrows(IllegalArgumentException.class, () -> AvatarPng.validate(bomb.toByteArray()));
        ByteArrayOutputStream metadata = new ByteArrayOutputStream();
        metadata.write(valid, 0, 33); metadata.write(chunk("zTXt", new byte[16])); metadata.write(valid, 33, valid.length - 33);
        assertThrows(IllegalArgumentException.class, () -> AvatarPng.validate(metadata.toByteArray()));
    }

    @Test void twoGenericFallbacksAreDistinctBoundedRgbaSpritesNotAClaimedPlayerSkin() {
        byte[] microsoft = AvatarPng.fallback(true), premium = AvatarPng.fallback(false);
        assertDoesNotThrow(() -> AvatarPng.validate(microsoft));
        assertDoesNotThrow(() -> AvatarPng.validate(premium));
        assertFalse(Arrays.equals(microsoft, premium));
    }

    private static byte[] chunk(String name, byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        out.write(type); out.write(data);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return out.toByteArray();
    }
}
