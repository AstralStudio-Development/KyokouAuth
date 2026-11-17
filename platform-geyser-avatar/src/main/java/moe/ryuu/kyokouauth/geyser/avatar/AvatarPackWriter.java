package moe.ryuu.kyokouauth.geyser.avatar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Own namespace only. An overlay replaces exactly two PNGs, never a Geyser skull/integrated pack. */
final class AvatarPackWriter {
    static final int MAX_PACK_BYTES = 32_768;
    static final String U_PATH = "textures/kyokouauth/role_u.png";
    static final String J_PATH = "textures/kyokouauth/role_j.png";

    static UUID uuid(String fingerprint, boolean base) {
        return derived((base ? "base:" : "overlay:") + fingerprint);
    }
    private static UUID derived(String suffix) {
        return UUID.nameUUIDFromBytes(("kyokouauth-role-avatar-pack-v1:" + suffix).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] build(AvatarSnapshot snapshot, boolean base) throws IOException {
        UUID uuid = uuid(snapshot.fingerprint(), base);
        UUID module = derived("module:" + uuid);
        String manifest = "{\"format_version\":2,\"header\":{\"name\":\"KyokouAuth role avatars\","
                + "\"description\":\"Presentation only; generic icons mean unavailable avatars\",\"uuid\":\"" + uuid
                + "\",\"version\":[1,0,0],\"min_engine_version\":[1,21,0]},\"modules\":[{\"type\":\"resources\","
                + "\"uuid\":\"" + module + "\",\"version\":[1,0,0]}]}";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        if (base) entries.put("textures/item_texture.json", ("{\"resource_pack_name\":\"kyokouauth_role_avatar\","
                + "\"texture_name\":\"atlas.items\",\"texture_data\":{"
                + "\"kyokouauth_role_u\":{\"textures\":\"textures/kyokouauth/role_u\"},"
                + "\"kyokouauth_role_j\":{\"textures\":\"textures/kyokouauth/role_j\"}}}").getBytes(StandardCharsets.UTF_8));
        entries.put(U_PATH, snapshot.microsoftPng());
        entries.put(J_PATH, snapshot.premiumPng());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setLevel(9);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry path = new ZipEntry(entry.getKey());
                path.setTime(0);
                zip.putNextEntry(path);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        byte[] pack = out.toByteArray();
        if (pack.length > MAX_PACK_BYTES) throw new IOException("Avatar pack exceeds 32KiB");
        return pack;
    }
    private AvatarPackWriter() {}
}
