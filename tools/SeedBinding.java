import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Seeds a bound identity directly into the plugin's SQLite database.
 *
 * Verification needs a name that is *already* bound to a UUID the client would never send. Only then
 * does a Login Success UUID prove that `AsyncPlayerPreLoginEvent#setPlayerProfile` actually rewrote
 * the profile - for an unbound name the plugin uses the same offline UUID the client derives, so the
 * two are indistinguishable and the test proves nothing.
 *
 * Usage: java SeedBinding <dbPath> <name> <xuid>
 */
public final class SeedBinding {

    public static void main(String[] args) throws Exception {
        String dbPath = args[0];
        String name = args[1];
        long xuid = Long.parseLong(args[2]);

        UUID playerUuid = new UUID(0L, xuid);
        long now = System.currentTimeMillis();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR REPLACE INTO kyokouauth_identity " +
                    "(player_uuid, kind, subject, gamertag, created_at, last_seen_at) VALUES (?,?,?,?,?,?)")) {
                insert.setString(1, playerUuid.toString());
                insert.setString(2, "XUID");
                insert.setString(3, Long.toString(xuid));
                insert.setString(4, "SeedGamertag");
                insert.setLong(5, now);
                insert.setLong(6, now);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR REPLACE INTO kyokouauth_name " +
                    "(name_key, display_name, player_uuid, claimed_at) VALUES (?,?,?,?)")) {
                insert.setString(1, name.toLowerCase());
                insert.setString(2, name);
                insert.setString(3, playerUuid.toString());
                insert.setLong(4, now);
                insert.executeUpdate();
            }
        }

        System.out.println("SEEDED name=" + name + " uuid=" + playerUuid);
    }
}
