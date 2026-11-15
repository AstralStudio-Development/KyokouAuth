package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.storage.IdentityStore
import moe.ryuu.kyokouauth.core.storage.PlaySession
import moe.ryuu.kyokouauth.core.storage.SessionRepository
import java.util.UUID

/**
 * Decides what happens to an incoming connection, and owns every rule about who may be who.
 *
 * The decision is a pure function of (name, address, stored state, Mojang's answer). Keeping it out
 * of the Bukkit layer is what makes it testable and what stops "the listener got a bit clever" from
 * becoming a security hole.
 *
 * [directory] is a parameter rather than a direct call into [MojangDirectory] so that the rules can
 * be exercised without a network. Every branch that refuses a login is a branch that must never
 * start letting people through, and none of them are reachable in a test otherwise.
 */
class AuthCoordinator(
    private val identities: IdentityStore,
    private val sessions: SessionRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionMillis: Long = 0L,
    private val directory: (String) -> MojangDirectory.Lookup = MojangDirectory::lookup,
) {

    sealed class Decision {
        /** Straight in. The UUID is final and the session is still trusted. */
        data class Admit(val identity: Identity, val bound: BoundIdentity) : Decision()

        /**
         * Enter the world frozen, prove identity, then continue.
         *
         * [uuid] is the UUID the connection must use *now*. For a bound player it is the real one -
         * verification only unfreezes them. For a first bind it is a provisional UUID and the player
         * must reconnect once, because the real UUID is not known until Microsoft answers.
         */
        data class Verify(
            val uuid: UUID,
            val reason: Reason,
            val provisional: Boolean,
            val existing: BoundIdentity?,
        ) : Decision()

        data class Reject(val reason: RejectReason, val detail: String = "") : Decision()
    }

    enum class Reason {
        /** Bound already, but the session expired or the address changed. */
        SESSION_EXPIRED,

        /** First time this name is seen, and Mojang does not own it: it will belong to an XUID. */
        FIRST_BIND,
    }

    enum class RejectReason {
        /** The name is malformed. */
        INVALID_NAME,

        /** Another identity owns this name. */
        NAME_TAKEN,

        /** This XUID or Mojang UUID already holds a different name. */
        IDENTITY_RENAMED,

        /**
         * The name belongs to a Mojang account, and this connection did not come through a Mojang
         * handshake.
         *
         * There is deliberately no fallback. A premium name has exactly one door: the handshake that
         * proves the *client* holds the session. Offering a second door - a device code - would let
         * an offline client sit in Limbo under someone else's name and then be bound to whichever
         * Microsoft account happened to complete the sign-in, which is precisely the confusion the
         * name reservation exists to prevent.
         */
        PREMIUM_NAME,

        /**
         * This name *was* a Microsoft player's, and a Mojang account has since claimed it.
         *
         * Reported separately from [PREMIUM_NAME] because it is an operational incident, not a
         * stranger trying their luck: the player needs to be told their data is intact and that an
         * administrator has to rename them, and the administrator needs to be told it happened.
         */
        PREMIUM_NAME_CLAIMED,

        /** Mojang could not be reached, so we cannot tell whether this name is premium. */
        DIRECTORY_UNAVAILABLE,

        /** Proxy forwarding detected; the login handshake never reaches this server. */
        PROXY_FORWARDING,

        INTERNAL_ERROR,
    }

    /**
     * Resolves what to do with an incoming connection.
     *
     * Blocking: walks the database and may call Mojang. Call from the IO executor.
     */
    fun decide(name: GameName, address: String): Decision {
        val now = clock()

        // Ownership by Mojang is checked first, before any stored binding, because it decides which
        // door this connection is allowed to use at all - not what state it is in.
        //
        // Reaching this point with a Mojang-owned name means the handshake did NOT happen for this
        // connection: either the client could not complete it, or it is not installed. Either way
        // there is no second way in. See [RejectReason.PREMIUM_NAME].
        when (val lookup = directory(name.key)) {
            is MojangDirectory.Lookup.Failed ->
                return Decision.Reject(RejectReason.DIRECTORY_UNAVAILABLE, lookup.reason)

            is MojangDirectory.Lookup.Premium -> {
                // Distinguish "someone else's name" from "your name, taken from you". The second is a
                // real incident the operator has to act on, and the player needs to hear that their
                // data still exists rather than a flat refusal.
                val bound = identities.byName(name)
                return if (bound != null && bound.kind == BoundIdentity.Kind.XUID) {
                    Decision.Reject(RejectReason.PREMIUM_NAME_CLAIMED, "uuid=${bound.uuid} xuid=${bound.subject}")
                } else {
                    Decision.Reject(RejectReason.PREMIUM_NAME, "'${name.value}' is owned by Mojang")
                }
            }

            MojangDirectory.Lookup.Free -> Unit
        }

        val bound = identities.byName(name)
        if (bound != null) return decideForBound(bound, address, now)

        return Decision.Verify(provisionalUuid(name), Reason.FIRST_BIND, provisional = true, existing = null)
    }

    private fun decideForBound(bound: BoundIdentity, address: String, now: Long): Decision {
        // No Mojang re-check here: `decide` already rejected the connection if the name belongs to a
        // Mojang account, which also covers the case of a name that became premium *after* it was
        // bound - that binding simply never reaches this function again.
        val session = sessions.find(bound.uuid)
        if (session != null && session.isValidFor(address, now) && sessionMillis > 0) {
            return Decision.Admit(bound.toIdentity(), bound)
        }
        return Decision.Verify(bound.uuid, Reason.SESSION_EXPIRED, provisional = false, existing = bound)
    }

    /** Issues a fresh trust record after a successful verification. */
    fun grantSession(identityUuid: UUID, address: String) {
        if (sessionMillis <= 0) return
        val now = clock()
        sessions.put(
            PlaySession(
                uuid = identityUuid,
                address = address,
                issuedAt = now,
                expiresAt = now + sessionMillis,
            ),
        )
    }

    fun revokeSession(uuid: UUID) = sessions.delete(uuid)

    /**
     * Turns a verified XUID into a binding, refusing the states the domain forbids.
     *
     * Called only after Microsoft has proven the XUID, so the XUID is trustworthy here - what can
     * still go wrong is the name being taken, or this XUID already having a different name.
     */
    fun bindMicrosoft(name: GameName, xuid: moe.ryuu.kyokouauth.core.identity.Xuid, gamertag: String): ClaimOutcome {
        val identity = Identity.Microsoft(name, xuid, gamertag)

        // A name Mojang owns can never be claimed by an XUID, even if nothing is bound to it yet.
        when (directory(name.key)) {
            MojangDirectory.Lookup.Free -> Unit
            is MojangDirectory.Lookup.Premium ->
                return ClaimOutcome.Refused(RejectReason.NAME_TAKEN, "name is owned by a Mojang account")
            is MojangDirectory.Lookup.Failed ->
                return ClaimOutcome.Refused(RejectReason.DIRECTORY_UNAVAILABLE, "could not verify name ownership")
        }

        return when (val result = identities.claim(identity, clock())) {
            is moe.ryuu.kyokouauth.core.storage.ClaimResult.Bound -> ClaimOutcome.Bound(result.record)
            is moe.ryuu.kyokouauth.core.storage.ClaimResult.NameTaken ->
                ClaimOutcome.Refused(RejectReason.NAME_TAKEN, "name belongs to ${result.by.uuid}")
            is moe.ryuu.kyokouauth.core.storage.ClaimResult.IdentityAlreadyNamed ->
                ClaimOutcome.Refused(RejectReason.IDENTITY_RENAMED, "this account is already named ${result.existing.name}")
            is moe.ryuu.kyokouauth.core.storage.ClaimResult.UnknownIdentity ->
                ClaimOutcome.Refused(RejectReason.INTERNAL_ERROR, "unknown identity")
        }
    }

    /** Same as [bindMicrosoft], for an identity whose Mojang UUID has just been proven. */
    fun bindPremium(mojangUuid: UUID, mojangName: GameName): ClaimOutcome {
        MojangDirectory.rememberPremium(mojangName.key, mojangUuid)
        val identity = Identity.Premium(mojangName, mojangUuid)
        return when (val result = identities.claim(identity, clock())) {
            is moe.ryuu.kyokouauth.core.storage.ClaimResult.Bound -> ClaimOutcome.Bound(result.record)

            is moe.ryuu.kyokouauth.core.storage.ClaimResult.NameTaken ->
                ClaimOutcome.Refused(RejectReason.NAME_TAKEN, "name belongs to ${result.by.uuid}")

            // The handshake just proved this client owns this Mojang UUID, and Mojang says its name is
            // `mojangName`. For a premium identity the name is whatever Mojang currently calls them,
            // so this is a rename, not a conflict.
            //
            // Refusing here would lock out every premium player who renames - including the very
            // common case of them having done it outside the server - and would freeze their old name
            // forever, since that row still holds the primary key.
            is moe.ryuu.kyokouauth.core.storage.ClaimResult.IdentityAlreadyNamed -> {
                val existing = result.existing
                // The old name may be free at Mojang again; drop any stale "this is premium" answer.
                MojangDirectory.forget(existing.name.key)

                when (val renamed = identities.rename(existing.uuid, mojangName, clock())) {
                    is moe.ryuu.kyokouauth.core.storage.ClaimResult.Bound ->
                        ClaimOutcome.Bound(renamed.record)

                    is moe.ryuu.kyokouauth.core.storage.ClaimResult.NameTaken ->
                        ClaimOutcome.Refused(RejectReason.NAME_TAKEN, "name belongs to ${renamed.by.uuid}")

                    else -> ClaimOutcome.Refused(RejectReason.INTERNAL_ERROR, "rename failed")
                }
            }

            is moe.ryuu.kyokouauth.core.storage.ClaimResult.UnknownIdentity ->
                ClaimOutcome.Refused(RejectReason.INTERNAL_ERROR, "unknown identity")
        }
    }

    sealed class ClaimOutcome {
        data class Bound(val record: BoundIdentity) : ClaimOutcome()
        data class Refused(val reason: RejectReason, val detail: String) : ClaimOutcome()
    }

    companion object {
        /**
         * The standard offline UUID of a name.
         *
         * Used only as a placeholder: the data written under it is deleted when the player leaves
         * unverified, and it is never stored. It is deterministic rather than random so that a player
         * who drops mid-verification gets the same placeholder back and does not accumulate junk.
         */
        fun provisionalUuid(name: GameName): UUID =
            UUID.nameUUIDFromBytes(("OfflinePlayer:" + name.value).toByteArray(Charsets.UTF_8))
    }
}
