package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.geyser.api.pack.ResourcePack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

final class AvatarTestData {
    static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, color);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", output)) throw new IOException("PNG writer absent");
        return output.toByteArray();
    }
    static AvatarSnapshot snapshot(int color) throws IOException {
        byte[] u = png(color), j = png(color ^ 0x0000FF00);
        return new AvatarSnapshot(AvatarSnapshot.fingerprint(u, j), u, j, false, false);
    }
    static byte[] json(AvatarSnapshot snapshot) {
        return ("{\"schema\":1,\"state\":\"ready\",\"fingerprint\":\"" + snapshot.fingerprint()
                + "\",\"microsoft_png\":\"" + Base64.getEncoder().encodeToString(snapshot.microsoftPng())
                + "\",\"premium_png\":\"" + Base64.getEncoder().encodeToString(snapshot.premiumPng())
                + "\",\"microsoft_default\":" + snapshot.microsoftDefault() + ",\"premium_default\":" + snapshot.premiumDefault() + "}")
                .getBytes(StandardCharsets.UTF_8);
    }
    static Map<String, byte[]> entries(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entries.put(entry.getName(), zip.readNBytes(32769)) != null) throw new IOException("Duplicate ZIP entry");
            }
        }
        return entries;
    }
    static ResourcePack pack(AvatarPackCache.Pack pack) {
        return (ResourcePack) Proxy.newProxyInstance(ResourcePack.class.getClassLoader(), new Class<?>[]{ResourcePack.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "uuid" -> pack.uuid();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "test-pack-" + pack.uuid();
                    default -> throw new AssertionError("Unexpected pack API call: " + method.getName());
                });
    }
    private AvatarTestData() {}
}
