package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import java.util.UUID

/** A role reference is server-selected metadata, never a client credential. */
data class RoleReference(val kind: BoundIdentity.Kind, val uuid: UUID, val name: String)
enum class RoleOrigin { MICROSOFT, FLOODGATE }
data class RoleHandoff(val xuid: Xuid, val gamertag: String, val role: RoleReference,
    val linkedJavaUuid: UUID?, val origin: RoleOrigin, val issuerSession: UUID)

/** Short-lived selection resumption; ordinary UUID/name/IP lookups cannot mint an entry. */
class RoleHandoffStore(private val capacity: Int, private val ttlMillis: Long,
                       private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private data class Entry(val grant: RoleHandoff, val expiresAt: Long)
    private val random = java.security.SecureRandom()
    private val tokens = HashMap<String, Entry>()
    private val hints = HashMap<Long, Entry>()
    private var closed = false

    init { require(capacity > 0 && ttlMillis in 1L..120_000L) }

    @Synchronized fun issueJava(grant: RoleHandoff): ByteArray {
        check(!closed && grant.origin == RoleOrigin.MICROSOFT)
        sweep()
        check(tokens.size + hints.size < capacity) { "Role handoff capacity reached" }
        var nonce: ByteArray
        var key: String
        do {
            nonce = ByteArray(32).also(random::nextBytes)
            key = key(nonce)
        } while (tokens.containsKey(key))
        tokens[key] = entry(grant)
        return nonce
    }

    @Synchronized fun consumeJava(cookie: ByteArray, newSession: UUID): RoleHandoff? {
        if (closed || cookie.size != 32) return null
        val entry = tokens.remove(key(cookie)) ?: return null
        return entry.grant.takeIf { clock() < entry.expiresAt && it.issuerSession != newSession }
    }

    @Synchronized fun issueBedrock(grant: RoleHandoff) {
        check(!closed && grant.origin == RoleOrigin.FLOODGATE)
        sweep()
        check(tokens.size + hints.size < capacity && !hints.containsKey(grant.xuid.value)) {
            "A role handoff is already pending or capacity was reached"
        }
        hints[grant.xuid.value] = entry(grant)
    }

    /** A hint is not a credential: a new exact-channel proof is mandatory even to consume it. */
    @Synchronized fun consumeBedrock(proof: BedrockProof, newSession: UUID): RoleHandoff? {
        if (closed || !proof.isCurrent()) return null
        val entry = hints[proof.xuid.value] ?: return null
        if (clock() >= entry.expiresAt) { hints.remove(proof.xuid.value); return null }
        if (entry.grant.issuerSession == newSession) return null
        hints.remove(proof.xuid.value)
        return entry.grant
    }

    @Synchronized fun revoke(grant: RoleHandoff) {
        tokens.entries.removeIf { it.value.grant == grant }
        hints.entries.removeIf { it.value.grant == grant }
    }

    /** Includes an expired pending hint until it is consumed or swept; never authorizes anything. */
    @Synchronized fun hasBedrockHint(xuid: Xuid): Boolean = !closed && hints.containsKey(xuid.value)
    @Synchronized fun isEmpty(): Boolean { sweep(); return tokens.isEmpty() && hints.isEmpty() }
    @Synchronized override fun close() { closed = true; tokens.clear(); hints.clear() }
    private fun key(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun entry(grant: RoleHandoff): Entry {
        val now = clock()
        return Entry(grant, if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis)
    }
    private fun sweep() {
        val now = clock()
        tokens.entries.removeIf { now >= it.value.expiresAt }
        hints.entries.removeIf { now >= it.value.expiresAt }
    }
}
