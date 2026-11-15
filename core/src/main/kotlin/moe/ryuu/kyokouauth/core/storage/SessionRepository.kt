package moe.ryuu.kyokouauth.core.storage

import java.util.UUID

/**
 * A trust record left by a verified login.
 *
 * It answers one question only: "has this same identity already proven itself from this same address
 * recently enough that we can skip Limbo?" It is deliberately keyed by address - a session that
 * followed the player to a new IP would be a session an attacker could inherit.
 */
data class PlaySession(
    val uuid: UUID,
    val address: String,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    fun isValidFor(address: String, now: Long): Boolean =
        this.address == address && now < expiresAt
}

class SessionRepository(private val database: Database) {

    fun find(uuid: UUID): PlaySession? = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT player_uuid, address, issued_at, expires_at FROM ${Schema.SESSION} WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@withConnection null
                PlaySession(
                    uuid = UUID.fromString(rows.getString("player_uuid")),
                    address = rows.getString("address"),
                    issuedAt = rows.getLong("issued_at"),
                    expiresAt = rows.getLong("expires_at"),
                )
            }
        }
    }

    fun put(session: PlaySession) {
        database.withConnection { connection ->
            val updated = connection.prepareStatement(
                "UPDATE ${Schema.SESSION} SET address = ?, issued_at = ?, expires_at = ? WHERE player_uuid = ?",
            ).use { statement ->
                statement.setString(1, session.address)
                statement.setLong(2, session.issuedAt)
                statement.setLong(3, session.expiresAt)
                statement.setString(4, session.uuid.toString())
                statement.executeUpdate()
            }
            if (updated > 0) return@withConnection

            connection.prepareStatement(
                "INSERT INTO ${Schema.SESSION} (player_uuid, address, issued_at, expires_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, session.uuid.toString())
                statement.setString(2, session.address)
                statement.setLong(3, session.issuedAt)
                statement.setLong(4, session.expiresAt)
                statement.executeUpdate()
            }
        }
    }

    fun delete(uuid: UUID) {
        database.withConnection { connection ->
            connection.prepareStatement("DELETE FROM ${Schema.SESSION} WHERE player_uuid = ?").use { statement ->
                statement.setString(1, uuid.toString())
                statement.executeUpdate()
            }
        }
    }

    /** Removes every session that has expired. Called periodically; keeps the table from growing forever. */
    fun purgeExpired(now: Long): Int = database.withConnection { connection ->
        connection.prepareStatement("DELETE FROM ${Schema.SESSION} WHERE expires_at <= ?").use { statement ->
            statement.setLong(1, now)
            statement.executeUpdate()
        }
    }
}
