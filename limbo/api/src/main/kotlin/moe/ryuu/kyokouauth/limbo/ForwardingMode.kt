package moe.ryuu.kyokouauth.limbo

import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.function.BooleanSupplier

/** Explicit trust boundary. Invalid modern forwarding can never fall back to a private ticket. */
sealed interface ForwardingMode {
    class VelocityModern(secret: ByteArray) : ForwardingMode {
        val secret: ByteArray = secret.copyOf()
        init { require(secret.isNotEmpty()) { "A forwarding secret is required" } }
    }
    class PrivateTicket(val authority: PrivateTicketAuthority) : ForwardingMode
}

/** Reserved display name for a first-bind placeholder; never sufficient as a forwarding proof. */
object VerificationProfileName {
    private val candidate = Regex("KA\\$[0-9a-f]{13}")
    fun of(uuid: UUID): String = "KA\$" + uuid.toString().replace("-", "").take(13)
    fun isCandidate(name: String): Boolean = candidate.matches(name)
    fun matches(uuid: UUID, name: String): Boolean = uuid.version() == 3 && name == of(uuid)
}

/** A trusted proxy attempt, bound to the exact frontend connection and one protocol endpoint. */
data class TicketIdentity(
    val connectionId: UUID,
    val uuid: UUID,
    val name: String,
    val address: String,
    val protocolVersion: Int,
) {
    init {
        require(connectionId != UUID(0, 0) && uuid != UUID(0, 0))
        require(VALID_NAME.matches(name) || VerificationProfileName.matches(uuid, name))
        require(address.length in 1..64 && address.all { it.isLetterOrDigit() || it in ":.%_-" })
        require(protocolVersion in 767..777)
    }

    private companion object {
        val VALID_NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}

/** In-process, bounded, atomic single-use credentials; never accepts a self-reported connection id. */
class PrivateTicketAuthority(
    private val maxTickets: Int = 4096,
    ttl: Duration = Duration.ofSeconds(15),
) : AutoCloseable {
    val propertyKey: String = "kyokouauth:private-ticket"
    private val ttlNanos = ttl.toNanos()
    private data class Entry(val identity: TicketIdentity, val expires: Long, val alive: BooleanSupplier, var redeemed: Boolean = false)
    private val entries = LinkedHashMap<String, Entry>()
    private val attempts = HashMap<UUID, String>()
    private val random = SecureRandom()
    private var closed = false

    init { require(maxTickets in 1..65536 && ttlNanos in 1..Duration.ofMinutes(1).toNanos()) }

    @Synchronized
    fun issue(identity: TicketIdentity, isAlive: BooleanSupplier): String {
        check(!closed) { "Ticket authority is closed" }
        purge()
        require(isAlive.asBoolean) { "Frontend connection is closed" }
        check(identity.connectionId !in attempts) { "Concurrent engine attempt for the same connection" }
        check(entries.size < maxTickets) { "Private ticket capacity reached" }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        entries[token] = Entry(identity, System.nanoTime() + ttlNanos, isAlive)
        attempts[identity.connectionId] = token
        return token
    }

    /** Burns before validating; retains the bounded attempt lease until revoke/expiry to prevent concurrent retry. */
    @Synchronized
    fun redeem(token: String, protocolVersion: Int, uuid: UUID, name: String, address: String): TicketIdentity {
        check(!closed)
        require(token.length == 43 && token.all { it.isLetterOrDigit() || it in "_-" }) { "Invalid private ticket" }
        val entry = entries[token] ?: error("Unknown, expired or replayed private ticket")
        require(!entry.redeemed) { "Private ticket already redeemed" }
        entry.redeemed = true
        val trusted = entry.identity
        require(System.nanoTime() - entry.expires < 0 && entry.alive.asBoolean) { "Private ticket expired or frontend closed" }
        require(trusted.protocolVersion == protocolVersion && trusted.uuid == uuid && trusted.name == name && trusted.address == address) {
            "Private ticket identity or target engine differs"
        }
        return trusted
    }

    @Synchronized
    fun revoke(connectionId: UUID) { attempts.remove(connectionId)?.let(entries::remove) }

    @Synchronized
    fun revokeTicket(ticket: String) { entries.remove(ticket)?.let { attempts.remove(it.identity.connectionId, ticket) } }

    private fun purge() {
        val now = System.nanoTime()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (token, entry) = iterator.next()
            if (now - entry.expires >= 0 || !entry.alive.asBoolean) {
                iterator.remove()
                attempts.remove(entry.identity.connectionId, token)
            }
        }
    }

    @Synchronized
    override fun close() { closed = true; entries.clear(); attempts.clear() }
}
