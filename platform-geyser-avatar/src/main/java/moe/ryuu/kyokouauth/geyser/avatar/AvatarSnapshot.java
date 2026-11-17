package moe.ryuu.kyokouauth.geyser.avatar;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** Immutable, content-checked public presentation bytes; never a role or identity credential. */
public record AvatarSnapshot(String fingerprint, byte[] microsoftPng, byte[] premiumPng,
                             boolean microsoftDefault, boolean premiumDefault) {
    public static final int MAX_JSON_BYTES = 24_576;
    private static final Set<String> READY = Set.of("schema", "state", "fingerprint", "microsoft_png", "premium_png",
            "microsoft_default", "premium_default");

    public AvatarSnapshot {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid avatar fingerprint");
        AvatarPng.validate(microsoftPng);
        AvatarPng.validate(premiumPng);
        if (!fingerprint.equals(fingerprint(microsoftPng, premiumPng))) throw new IllegalArgumentException("Avatar content fingerprint differs");
        microsoftPng = microsoftPng.clone();
        premiumPng = premiumPng.clone();
    }
    @Override public byte[] microsoftPng() { return microsoftPng.clone(); }
    @Override public byte[] premiumPng() { return premiumPng.clone(); }

    static AvatarSnapshot ready(byte[] json) {
        Map<String, Object> object = AvatarJson.object(json);
        if (!object.keySet().equals(READY) || !Long.valueOf(1).equals(object.get("schema")) ||
                !"ready".equals(object.get("state")) || !(object.get("fingerprint") instanceof String fingerprint) ||
                !(object.get("microsoft_png") instanceof String u) || !(object.get("premium_png") instanceof String j) ||
                !(object.get("microsoft_default") instanceof Boolean defaultU) || !(object.get("premium_default") instanceof Boolean defaultJ))
            throw new IllegalArgumentException("Invalid ready avatar schema");
        byte[] microsoft = decode(u), premium = decode(j);
        return new AvatarSnapshot(fingerprint, microsoft, premium, defaultU, defaultJ);
    }

    static void notReady(byte[] json) {
        Map<String, Object> object = AvatarJson.object(json);
        if (!object.keySet().equals(Set.of("schema", "state")) || !Long.valueOf(1).equals(object.get("schema")) ||
                !"not-ready".equals(object.get("state"))) throw new IllegalArgumentException("Invalid avatar MISS schema");
    }

    private static byte[] decode(String value) {
        if (value.length() > 10_924 || value.length() % 4 != 0 || !value.matches("[A-Za-z0-9+/]*={0,2}"))
            throw new IllegalArgumentException("Avatar base64 exceeds canonical limit");
        byte[] decoded = Base64.getDecoder().decode(value);
        if (decoded.length > AvatarPng.MAX_BYTES || !Base64.getEncoder().encodeToString(decoded).equals(value))
            throw new IllegalArgumentException("Avatar base64 is not canonical");
        return decoded;
    }

    public static String fingerprint(byte[] microsoft, byte[] premium) {
        MessageDigest digest = sha256();
        digest.update("kyokouauth-role-avatar-v1".getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(ByteBuffer.allocate(4).putInt(microsoft.length).array());
        digest.update(microsoft);
        digest.update(ByteBuffer.allocate(4).putInt(premium.length).array());
        digest.update(premium);
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
