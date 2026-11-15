package moe.ryuu.kyokouauth.link;

import org.geysermc.floodgate.api.link.LinkRequestResult;
import org.geysermc.floodgate.link.CommonPlayerLink;
import org.geysermc.floodgate.util.LinkedPlayer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A read-only Floodgate linking database backed by GeyserMC's global linking service.
 *
 * ## Why this exists
 *
 * Floodgate 2.2.5 asks {@code https://api.geysermc.org/v2/link/bedrock/<xuid>} — with no trailing
 * slash — and that URL answers an empty JSON object even for a link that the reverse endpoint
 * {@code /v2/link/java/<uuid>} reports as present. Adding the trailing slash makes the very same
 * endpoint return {@code java_id} and {@code java_name}. So the association is real and the service
 * is fine; only the URL Floodgate builds is wrong for the current routing.
 *
 * Because the answer arrives through this interface, Floodgate itself then resolves the connection
 * to the Java identity ({@code getCorrectUniqueId()}), which is the only way a Bedrock player can
 * occupy a Java identity's UUID — a plugin cannot achieve that through
 * {@code AsyncPlayerPreLoginEvent#setPlayerProfile}, because Floodgate writes the game profile
 * through NMS and bypasses the event.
 *
 * ## Why linking is refused
 *
 * Establishing a link is a write to GeyserMC's service, performed on their website with both
 * accounts' consent. This class deliberately cannot create one: nothing here can prove that the
 * caller owns the Java account it names.
 */
public final class GlobalLinkDatabase extends CommonPlayerLink {

    private static final String ENDPOINT = "https://api.geysermc.org/v2/link/bedrock/";
    private static final int TIMEOUT_MILLIS = 5000;

    @Override
    public void load() {
        // Stateless: the association lives in the global service, not on disk.
    }

    @Override
    public CompletableFuture<LinkedPlayer> getLinkedPlayer(UUID bedrockId) {
        return CompletableFuture.supplyAsync(() -> fetch(bedrockId), getExecutorService());
    }

    @Override
    public CompletableFuture<Boolean> isLinkedPlayer(UUID bedrockId) {
        return getLinkedPlayer(bedrockId).thenApply(linked -> linked != null);
    }

    @Override
    public CompletableFuture<Void> linkPlayer(UUID bedrockId, UUID javaId, String javaUsername) {
        return failed();
    }

    @Override
    public CompletableFuture<Void> unlinkPlayer(UUID javaId) {
        return failed();
    }

    @Override
    public CompletableFuture<?> createLinkRequest(UUID javaId, String javaUsername, String bedrockId) {
        return failed();
    }

    @Override
    public CompletableFuture<LinkRequestResult> verifyLinkRequest(
        UUID javaId, String javaUsername, String bedrockId, String code
    ) {
        return failed();
    }

    @Override
    public String getName() {
        return "global";
    }

    /** @return the linked Java identity, or null when this Bedrock account has no link. */
    private LinkedPlayer fetch(UUID bedrockId) {
        long xuid = bedrockId.getLeastSignificantBits();
        HttpURLConnection connection = null;
        try {
            // The trailing slash is the whole point — see the class comment.
            connection = (HttpURLConnection) new URL(ENDPOINT + xuid + "/").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");

            if (connection.getResponseCode() != 200) {
                return null;
            }

            String body = read(connection.getInputStream());
            String javaId = textField(body, "java_id");
            String javaName = textField(body, "java_name");
            if (javaId == null || javaName == null) {
                // The service answers `{}` for "no link", and may answer an object carrying only a
                // `message` for an error. Both mean the same thing to the caller.
                return null;
            }

            return LinkedPlayer.of(javaName, UUID.fromString(javaId), bedrockId);
        } catch (Exception ignored) {
            // A missing link and an unreachable service must both resolve to "not linked":
            // Floodgate falls back to the Bedrock UUID, which is always safe.
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream stream) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int count;
            while ((count = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }

    /** Reads a string field without pulling a JSON library into a plugin-loaded jar. */
    private static String textField(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyIndex);
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return json.substring(start + 1, end);
    }

    private static <T> CompletableFuture<T> failed() {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(
            new UnsupportedOperationException("linking is managed by the global linking service")
        );
        return future;
    }
}
