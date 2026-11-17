package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.identity.Xuid
import java.util.UUID

/** Verification metadata only: neither this row nor its age grants permission to select a role. */
data class VerifiedAccountLink(
    val xuid: Xuid,
    val javaUuid: UUID,
    val source: String,
    val scope: String,
    val verifiedAt: Long,
) {
    init {
        require(xuid.value > 0) { "Invalid verified XUID" }
        require(javaUuid.version() in setOf(3, 4) && javaUuid.variant() == 2) { "Invalid verified Java UUID" }
        require(source.length in 1..64 && source.all { it.isLetterOrDigit() || it in "-_.:" }) { "Invalid link source" }
        require(scope.length in 1..64 && scope.all { it.isLetterOrDigit() || it in "-_.:" }) { "Invalid link scope" }
        require(verifiedAt >= 0) { "Invalid verification time" }
    }
}

/** No token, Java name, identity claim or authorization cache is stored here. Reads are uncached. */
class AccountLinkRepository(private val database: Database) {
    fun find(xuid: Xuid): VerifiedAccountLink? = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT java_uuid, source, scope, verified_at FROM ${Schema.ACCOUNT_LINK} WHERE xuid = ?",
        ).use { statement ->
            statement.setString(1, xuid.toString())
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@withConnection null
                VerifiedAccountLink(
                    xuid,
                    UUID.fromString(rows.getString("java_uuid")),
                    rows.getString("source"),
                    rows.getString("scope"),
                    rows.getLong("verified_at"),
                )
            }
        }
    }

    /** Record a fresh current proof; an older in-flight response cannot roll a newer mapping back. */
    fun recordVerified(record: VerifiedAccountLink) {
        database.transaction { connection ->
            val updated = connection.prepareStatement(
                "UPDATE ${Schema.ACCOUNT_LINK} SET java_uuid = ?, source = ?, scope = ?, verified_at = ? " +
                    "WHERE xuid = ? AND verified_at <= ?",
            ).use { statement ->
                statement.setString(1, record.javaUuid.toString())
                statement.setString(2, record.source)
                statement.setString(3, record.scope)
                statement.setLong(4, record.verifiedAt)
                statement.setString(5, record.xuid.toString())
                statement.setLong(6, record.verifiedAt)
                statement.executeUpdate()
            }
            if (updated > 0) return@transaction
            val exists = connection.prepareStatement("SELECT xuid FROM ${Schema.ACCOUNT_LINK} WHERE xuid = ?").use { statement ->
                statement.setString(1, record.xuid.toString())
                statement.executeQuery().use { it.next() }
            }
            if (exists) return@transaction
            // A cross-process insertion race is arbitrated by the primary key and rolls back on
            // conflict. Callers must treat database failures as failures, never as authorization.
            connection.prepareStatement(
                "INSERT INTO ${Schema.ACCOUNT_LINK} (xuid, java_uuid, source, scope, verified_at) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, record.xuid.toString())
                statement.setString(2, record.javaUuid.toString())
                statement.setString(3, record.source)
                statement.setString(4, record.scope)
                statement.setLong(5, record.verifiedAt)
                statement.executeUpdate()
            }
        }
    }

    /** A delayed unlinked observation must not delete metadata from a newer verified response. */
    fun revoke(xuid: Xuid, notAfter: Long = Long.MAX_VALUE): Boolean = database.withConnection { connection ->
        connection.prepareStatement("DELETE FROM ${Schema.ACCOUNT_LINK} WHERE xuid = ? AND verified_at <= ?").use { statement ->
            statement.setString(1, xuid.toString())
            statement.setLong(2, notAfter)
            statement.executeUpdate() > 0
        }
    }
}
