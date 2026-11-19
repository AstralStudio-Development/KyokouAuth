package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
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
    private val sessionBindsAddress: Boolean = true,
    private val autoRenameMicrosoft: Boolean = false,
    private val directory: (String) -> MojangDirectory.Lookup = MojangDirectory::lookup,
    private val microsoftProfile: (Xuid) -> UUID = { xuid ->
        identities.bySubject(BoundIdentity.Kind.XUID, xuid.value.toString())?.uuid ?: Identity.ofXuid(xuid)
    },
) {

    sealed class Decision {
        /** Straight in. The UUID is final and the session is still trusted. */
        data class Admit(val identity: Identity, val bound: BoundIdentity) : Decision()

        /** Supplied only by a connection-verified proxy adapter; no Java binding/session is implied. */
        data class VerifiedBedrock(val identity: Identity) : Decision()

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

        /**
         * The name is Mojang's, so this connection has to prove it by passing Mojang's own session
         * check before anything else happens.
         *
         * This is the only door that is invisible to the player: a premium client answers the
         * challenge by itself, and the proxy then knows the real profile. A device code would ask the
         * owner of the name to prove something they already proved by starting the game.
         */
        data class MojangSession(val expectedUuid: UUID) : Decision()
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

        /**
         * The plugin declined to ask Mojang - its own lookup budget is spent, or Mojang asked us to
         * back off - and this name has no binding to fall back on.
         *
         * Reported separately from [DIRECTORY_UNAVAILABLE] because it is almost always the result of
         * someone spending the budget on purpose, and the remedy is to wait for the window rather
         * than to go looking for a network problem.
         */
        DIRECTORY_THROTTLED,

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
        // The proxy can force online mode per connection. A directory match is not authentication:
        // it selects Mojang's session handshake and provides the UUID the verified profile must own.
        when (val lookup = directory(name.key)) {
            is MojangDirectory.Lookup.Failed -> {
                if (!lookup.withheld) {
                    return Decision.Reject(RejectReason.DIRECTORY_UNAVAILABLE, lookup.reason)
                }

                // Nothing was asked, so nothing is known about this name. A binding already on record
                // still decides what happens to its owner; a name with no binding is refused,
                // because admitting one unexamined is exactly the window this check closes.
                //
                // The trade this makes: a bound name that Mojang has since claimed keeps working
                // until the window resets. The alternative is worse - whoever spends the lookup
                // budget would also disconnect every regular player on the server.
                val known = identities.byName(name)
                    ?: return Decision.Reject(RejectReason.DIRECTORY_THROTTLED, lookup.reason)

                return decideForBound(known, address, now)
            }

            is MojangDirectory.Lookup.Premium -> {
                val bound = identities.byName(name)

                // Distinguish "someone else's name" from "your name, taken from you". The second is a
                // real incident the operator has to act on, and the player needs to hear that their
                // data still exists rather than a flat refusal.
                if (bound != null && bound.kind == BoundIdentity.Kind.XUID) {
                    return Decision.Reject(RejectReason.PREMIUM_NAME_CLAIMED, "uuid=${bound.uuid} xuid=${bound.subject}")
                }

                // Bound or not, every connection under a Mojang-owned name is proved the same way:
                // the session check runs before anything else, the profile arrives final, and the
                // binding is recorded on the way in. A trust record cannot stand in for it - a name
                // Mojang owns belongs to whoever holds that account now, not to whoever held it when
                // the record was written.
                return Decision.MojangSession(lookup.uuid)
            }

            MojangDirectory.Lookup.Free -> Unit
        }

        val bound = identities.byName(name)
        if (bound != null) return decideForBound(bound, address, now)

        return Decision.Verify(provisionalUuid(name), Reason.FIRST_BIND, provisional = true, existing = null)
    }

    private fun decideForBound(bound: BoundIdentity, address: String, now: Long): Decision {
        // A local play-session record can never replace Mojang authentication, even if the
        // directory is throttled. Only XUID identities may use the device-code/session path.
        if (bound.kind == BoundIdentity.Kind.MOJANG) return Decision.MojangSession(bound.uuid)
        if (microsoftProfile(Xuid(bound.subject.toLong())) != bound.uuid) {
            return Decision.Reject(RejectReason.INTERNAL_ERROR, "Microsoft profile ownership changed")
        }
        val session = sessions.find(bound.uuid)
        if (session != null && session.isValidFor(address, now, sessionBindsAddress) && sessionMillis > 0) {
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
     * Turns a verified Microsoft sign-in into a binding, refusing the states the domain forbids.
     *
     * Called only after Microsoft has proven the account, so the XUID is trustworthy here - what can
     * still go wrong is the name being taken, or this XUID already having a different name.
     */
    fun bindMicrosoft(
        name: GameName,
        xuid: moe.ryuu.kyokouauth.core.identity.Xuid,
        gamertag: String,
    ): ClaimOutcome {
        // A name Mojang owns can never be claimed by an XUID, even if nothing is bound to it yet. It
        // has one door - the session check the proxy runs before the player ever reaches the limbo -
        // and a device code is not that door. See [RejectReason.PREMIUM_NAME].
        when (val lookup = directory(name.key)) {
            MojangDirectory.Lookup.Free -> Unit

            is MojangDirectory.Lookup.Premium ->
                return ClaimOutcome.Refused(RejectReason.PREMIUM_NAME, "name is owned by a Mojang account")

            is MojangDirectory.Lookup.Failed ->
                return ClaimOutcome.Refused(
                    if (lookup.withheld) RejectReason.DIRECTORY_THROTTLED else RejectReason.DIRECTORY_UNAVAILABLE,
                    lookup.reason,
                )
        }

        return claim(Identity.Microsoft(name, xuid, gamertag, microsoftProfile(xuid)))
    }

    /** Same as [bindMicrosoft], for an identity whose Mojang UUID has just been proven. */
    fun bindPremium(mojangUuid: UUID, mojangName: GameName): ClaimOutcome {
        MojangDirectory.rememberPremium(mojangName.key, mojangUuid)
        return claim(Identity.Premium(mojangName, mojangUuid))
    }

    private fun claim(identity: Identity): ClaimOutcome {
        val result = if (identity is Identity.Microsoft && autoRenameMicrosoft) {
            identities.claimRenamingMicrosoft(identity, clock())
        } else {
            identities.claim(identity, clock())
        }
        return when (result) {
            is moe.ryuu.kyokouauth.core.storage.ClaimResult.Bound -> ClaimOutcome.Bound(result.record, result.previousName)

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
                if (identity !is Identity.Premium) {
                    return ClaimOutcome.Refused(RejectReason.IDENTITY_RENAMED,
                        "this account is already named ${result.existing.name}", result.existing.name)
                }

                val existing = result.existing
                // The old name may be free at Mojang again; drop any stale "this is premium" answer.
                MojangDirectory.forget(existing.name.key)

                when (val renamed = identities.rename(existing.uuid, identity.name, clock())) {
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
        data class Bound(val record: BoundIdentity, val previousName: GameName? = null) : ClaimOutcome()
        data class Refused(val reason: RejectReason, val detail: String, val existingName: GameName? = null) : ClaimOutcome()
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
