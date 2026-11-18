package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import java.util.UUID

/** Server-selected metadata; neither a role reference nor a pending choice is a credential. */
data class RoleReference(val kind: BoundIdentity.Kind, val uuid: UUID, val name: String)
data class BedrockRoleChoice(val xuid: Xuid, val gamertag: String, val role: RoleReference,
    val linkedJavaUuid: UUID?, val issuerSession: UUID)

/** A bounded one-shot intent for a new, independently proved Floodgate login. No Java/IP/name lookup. */
class BedrockRoleHints(private val capacity: Int, private val ttlMillis: Long,
                       private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private data class Entry(val choice: BedrockRoleChoice, val expiresAt: Long)
    private val hints = HashMap<Long, Entry>()
    private var closed = false

    init { require(capacity > 0 && ttlMillis in 1L..120_000L) }

    @Synchronized fun issue(choice: BedrockRoleChoice) {
        check(!closed)
        sweep()
        check(hints.size < capacity && !hints.containsKey(choice.xuid.value)) {
            "A Bedrock role choice is already pending or capacity was reached"
        }
        val now = clock()
        hints[choice.xuid.value] = Entry(choice,
            if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis)
    }

    /** Invalid proof/session cannot spend another channel's choice. The gate re-reads current records. */
    @Synchronized fun consume(proof: BedrockProof, newSession: UUID): BedrockRoleChoice? {
        if (closed || !proof.isCurrent()) return null
        val entry = hints[proof.xuid.value] ?: return null
        if (clock() >= entry.expiresAt) { hints.remove(proof.xuid.value); return null }
        val choice = entry.choice
        if (choice.issuerSession == newSession || choice.gamertag != proof.gamertag ||
            choice.linkedJavaUuid != proof.linkedJavaUuid || !proof.isCurrent()) return null
        hints.remove(proof.xuid.value)
        return choice
    }

    @Synchronized fun revoke(choice: BedrockRoleChoice) {
        if (hints[choice.xuid.value]?.choice == choice) hints.remove(choice.xuid.value)
    }

    /** Expired entries remain observable until consumed/swept so that an attempted resume fails closed. */
    @Synchronized fun hasHint(xuid: Xuid): Boolean = !closed && hints.containsKey(xuid.value)
    @Synchronized fun isEmpty(): Boolean { sweep(); return hints.isEmpty() }
    @Synchronized override fun close() { closed = true; hints.clear() }
    private fun sweep() {
        val now = clock()
        hints.entries.removeIf { now >= it.value.expiresAt }
    }
}
