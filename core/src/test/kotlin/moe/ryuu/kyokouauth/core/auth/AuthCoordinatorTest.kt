package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.Database
import moe.ryuu.kyokouauth.core.storage.DatabaseDescriptor
import moe.ryuu.kyokouauth.core.storage.Dialect
import moe.ryuu.kyokouauth.core.storage.IdentityRepository
import moe.ryuu.kyokouauth.core.storage.SessionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The login rules, exercised without a server and without a network.
 *
 * Every test here names a way a player could get in without proving who they say they are. A change
 * that makes one of them pass for the wrong reason is a security regression, not a style issue.
 */
class AuthCoordinatorTest {

    private lateinit var directory: File
    private lateinit var database: Database
    private lateinit var identities: IdentityRepository
    private lateinit var sessions: SessionRepository
    private lateinit var coordinator: AuthCoordinator

    private var now = 1_000_000L

    /** What Mojang will claim this test. Flipped per test rather than mocked per call. */
    private var premiumNames: MutableSet<String> = mutableSetOf()

    /** When true, the directory reports an outage instead of an answer. */
    private var directoryDown = false

    /** When true, the plugin declines to ask at all - the state a spent lookup budget produces. */
    private var directoryWithheld = false

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("kyokouauth-auth").toFile()
        database = Database(
            DatabaseDescriptor(dialect = Dialect.SQLITE, database = File(directory, "test.db").absolutePath),
        )
        identities = IdentityRepository(database)
        sessions = SessionRepository(database)
        coordinator = AuthCoordinator(
            identities = identities,
            sessions = sessions,
            clock = { now },
            sessionMillis = 60_000L,
            directory = { key ->
                if (directoryWithheld) {
                    MojangDirectory.Lookup.Failed("simulated spent budget", withheld = true)
                } else if (directoryDown) {
                    MojangDirectory.Lookup.Failed("simulated outage")
                } else if (key in premiumNames) {
                    MojangDirectory.Lookup.Premium(
                        UUID.nameUUIDFromBytes(("premium:" + key).toByteArray(Charsets.UTF_8)),
                        key,
                    )
                } else {
                    MojangDirectory.Lookup.Free
                }
            },
        )
    }

    @AfterEach
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun name(raw: String) = GameName.parseOrThrow(raw)

    @Test
    fun `an unknown free name enters verification as a first bind`() {
        val decision = coordinator.decide(name("Steve"), "10.0.0.1")

        val verify = assertIs<AuthCoordinator.Decision.Verify>(decision)
        assertEquals(AuthCoordinator.Reason.FIRST_BIND, verify.reason)
        assertEquals(true, verify.provisional)
        assertEquals(AuthCoordinator.provisionalUuid(name("Steve")), verify.uuid)
    }

    @Test
    fun `a premium name is proved by the mojang session, never by a device code`() {
        premiumNames += "notch"

        val decision = coordinator.decide(name("Notch"), "10.0.0.1")

        // The name is Mojang's, so the connection has to pass Mojang's own session check before
        // anything else. A device code would ask the owner to prove again something they proved by
        // starting the game; a trust record would outlive the account it was written for.
        assertIs<AuthCoordinator.Decision.MojangSession>(decision)
    }

    @Test
    fun `a bound name that becomes premium is refused before the session is even consulted`() {
        coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        coordinator.grantSession(Identity.ofXuid(Xuid(1001L)), "10.0.0.1")

        // Someone buys the game and takes this name.
        premiumNames += "steve"

        val reject = assertIs<AuthCoordinator.Decision.Reject>(coordinator.decide(name("Steve"), "10.0.0.1"))
        assertEquals(AuthCoordinator.RejectReason.PREMIUM_NAME_CLAIMED, reject.reason)
    }

    @Test
    fun `a directory outage refuses the login instead of assuming the name is free`() {
        directoryDown = true

        val decision = coordinator.decide(name("Steve"), "10.0.0.1")

        val reject = assertIs<AuthCoordinator.Decision.Reject>(decision)
        assertEquals(AuthCoordinator.RejectReason.DIRECTORY_UNAVAILABLE, reject.reason)
    }

    /**
     * The lookup budget is spent.
     *
     * That is a local condition, not an outage: nothing was asked, so nothing is known about the name
     * - but a binding already on record still describes its owner. Serving those players is what
     * keeps a spent budget from being a server-wide lockout, which is exactly what someone spending
     * the budget on purpose would be aiming for.
     */
    @Test
    fun `a spent lookup budget still admits a bound player with a live session`() {
        coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        coordinator.grantSession(Identity.ofXuid(Xuid(1001L)), "10.0.0.1")

        directoryWithheld = true

        val admitted = assertIs<AuthCoordinator.Decision.Admit>(coordinator.decide(name("Steve"), "10.0.0.1"))
        assertEquals(Identity.ofXuid(Xuid(1001L)), admitted.identity.uuid)
    }

    @Test
    fun `a spent lookup budget still sends a bound player to verification`() {
        coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")

        directoryWithheld = true

        val decision = assertIs<AuthCoordinator.Decision.Verify>(coordinator.decide(name("Steve"), "10.0.0.1"))
        assertEquals(AuthCoordinator.Reason.SESSION_EXPIRED, decision.reason)
        assertEquals(false, decision.provisional)
    }

    @Test
    fun `a stored premium identity still requires a handshake when the directory budget is spent`() {
        val premiumUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
        identities.claim(Identity.Premium(name("Notch"), premiumUuid), now)
        coordinator.grantSession(premiumUuid, "10.0.0.1")
        directoryWithheld = true
        val decision = assertIs<AuthCoordinator.Decision.MojangSession>(coordinator.decide(name("Notch"), "10.0.0.1"))
        assertEquals(premiumUuid, decision.expectedUuid)
    }

    @Test
    fun `a spent lookup budget refuses an unknown name instead of binding it unexamined`() {
        directoryWithheld = true

        val decision = coordinator.decide(name("Notch"), "10.0.0.1")

        // The one thing that must not happen here: admitting the name because we could not ask. That
        // is the window in which a premium name could be bound to whichever Microsoft account
        // completes a sign-in first.
        val reject = assertIs<AuthCoordinator.Decision.Reject>(decision)
        assertEquals(AuthCoordinator.RejectReason.DIRECTORY_THROTTLED, reject.reason)
    }

    @Test
    fun `a bound player with a live session from the same address is admitted`() {
        assertIs<AuthCoordinator.ClaimOutcome.Bound>(
            coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT"),
        )

        val decision = coordinator.decide(name("Steve"), "10.0.0.1")
        assertIs<AuthCoordinator.Decision.Verify>(decision)

        // The bind itself does not grant trust; only a completed verification does.
        coordinator.grantSession(Identity.ofXuid(Xuid(1001L)), "10.0.0.1")

        val admitted = assertIs<AuthCoordinator.Decision.Admit>(coordinator.decide(name("Steve"), "10.0.0.1"))
        assertEquals(Identity.ofXuid(Xuid(1001L)), admitted.identity.uuid)
    }

    @Test
    fun `the same address after the session expires must verify again`() {
        coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        coordinator.grantSession(Identity.ofXuid(Xuid(1001L)), "10.0.0.1")

        now += 60_001L

        val decision = assertIs<AuthCoordinator.Decision.Verify>(coordinator.decide(name("Steve"), "10.0.0.1"))
        assertEquals(AuthCoordinator.Reason.SESSION_EXPIRED, decision.reason)
        assertEquals(false, decision.provisional)
    }

    @Test
    fun `the same player from a different address must verify again`() {
        coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        coordinator.grantSession(Identity.ofXuid(Xuid(1001L)), "10.0.0.1")

        val decision = assertIs<AuthCoordinator.Decision.Verify>(coordinator.decide(name("Steve"), "10.0.0.2"))
        assertEquals(AuthCoordinator.Reason.SESSION_EXPIRED, decision.reason)
    }

    @Test
    fun `a bound name that later becomes a mojang name loses its binding`() {
        coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        coordinator.grantSession(Identity.ofXuid(Xuid(1001L)), "10.0.0.1")

        // Someone buys the game and takes this name.
        premiumNames += "steve"

        val decision = coordinator.decide(name("Steve"), "10.0.0.1")
        val reject = assertIs<AuthCoordinator.Decision.Reject>(decision)

        // Deliberately a *different* reason from an unknown premium name: this one is an incident the
        // operator has to resolve, and the rejection detail has to name the affected identity.
        assertEquals(AuthCoordinator.RejectReason.PREMIUM_NAME_CLAIMED, reject.reason)
        assertTrue(reject.detail.contains("xuid=1001"), "the operator needs the affected XUID: ${reject.detail}")
    }

    @Test
    fun `an xuid cannot bind a name mojang owns`() {
        premiumNames += "notch"

        val outcome = coordinator.bindMicrosoft(name("Notch"), Xuid(1001L), "NotchGT")

        // The name is Mojang's, and a device code is not its door: only the session check the proxy
        // runs before the limbo can prove it. So this binding never happens.
        val refused = assertIs<AuthCoordinator.ClaimOutcome.Refused>(outcome)
        assertEquals(AuthCoordinator.RejectReason.PREMIUM_NAME, refused.reason)
    }

    @Test
    fun `an xuid cannot bind a name during a directory outage`() {
        directoryDown = true

        val outcome = coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")

        val refused = assertIs<AuthCoordinator.ClaimOutcome.Refused>(outcome)
        assertEquals(AuthCoordinator.RejectReason.DIRECTORY_UNAVAILABLE, refused.reason)
    }

    @Test
    fun `a second xuid cannot take a bound name`() {
        assertIs<AuthCoordinator.ClaimOutcome.Bound>(
            coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT"),
        )

        val outcome = coordinator.bindMicrosoft(name("Steve"), Xuid(2002L), "Attacker")
        val refused = assertIs<AuthCoordinator.ClaimOutcome.Refused>(outcome)
        assertEquals(AuthCoordinator.RejectReason.NAME_TAKEN, refused.reason)
    }

    @Test
    fun `one xuid cannot accumulate a second name`() {
        assertIs<AuthCoordinator.ClaimOutcome.Bound>(
            coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT"),
        )

        val outcome = coordinator.bindMicrosoft(name("Alex"), Xuid(1001L), "SteveGT")
        val refused = assertIs<AuthCoordinator.ClaimOutcome.Refused>(outcome)
        assertEquals(AuthCoordinator.RejectReason.IDENTITY_RENAMED, refused.reason)
        assertEquals(name("Steve"), refused.existingName, "disabled mode must tell the actual bound name")
    }

    private fun autoRenaming(store: moe.ryuu.kyokouauth.core.storage.IdentityStore = identities) = AuthCoordinator(
        identities = store, sessions = sessions, clock = { now }, sessionMillis = 60_000L,
        autoRenameMicrosoft = true,
        directory = { key -> when {
            directoryDown -> MojangDirectory.Lookup.Failed("simulated outage")
            directoryWithheld -> MojangDirectory.Lookup.Failed("simulated limit", withheld = true)
            key in premiumNames -> MojangDirectory.Lookup.Premium(UUID.randomUUID(), key)
            else -> MojangDirectory.Lookup.Free
        } },
    )

    @Test
    fun `enabled Microsoft rename keeps the identity and admits the new name after one reconnect`() {
        val auth = autoRenaming()
        val first = assertIs<AuthCoordinator.ClaimOutcome.Bound>(auth.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT"))
        now += 1_000
        val pending = assertIs<AuthCoordinator.Decision.Verify>(auth.decide(name("Alex"), "10.0.0.1"))
        assertTrue(pending.provisional)
        val renamed = assertIs<AuthCoordinator.ClaimOutcome.Bound>(auth.bindMicrosoft(name("Alex"), Xuid(1001L), "SteveGT"))
        assertEquals(first.record.uuid, renamed.record.uuid)
        assertEquals(first.record.createdAt, renamed.record.createdAt)
        assertEquals(name("Steve"), renamed.previousName)
        assertEquals(name("Alex"), renamed.record.name)
        assertEquals(null, identities.byName(name("Steve")))
        assertEquals(1, identities.all().size)
        auth.grantSession(renamed.record.uuid, "10.0.0.1")
        val reconnect = assertIs<AuthCoordinator.Decision.Admit>(auth.decide(name("Alex"), "10.0.0.1"))
        assertEquals(first.record.uuid, reconnect.identity.uuid)
        assertEquals(name("Alex"), reconnect.identity.name)
    }

    @Test
    fun `automatic rename cannot take another identity's name`() {
        val auth = autoRenaming()
        auth.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        auth.bindMicrosoft(name("Alex"), Xuid(2002L), "AlexGT")
        val refused = assertIs<AuthCoordinator.ClaimOutcome.Refused>(auth.bindMicrosoft(name("Alex"), Xuid(1001L), "SteveGT"))
        assertEquals(AuthCoordinator.RejectReason.NAME_TAKEN, refused.reason)
        assertEquals(Identity.ofXuid(Xuid(1001L)), identities.byName(name("Steve"))?.uuid)
        assertEquals(Identity.ofXuid(Xuid(2002L)), identities.byName(name("Alex"))?.uuid)
    }

    @Test
    fun `automatic rename cannot claim a premium or unexamined name`() {
        val auth = autoRenaming()
        auth.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        premiumNames += "notch"
        assertEquals(AuthCoordinator.RejectReason.PREMIUM_NAME,
            assertIs<AuthCoordinator.ClaimOutcome.Refused>(auth.bindMicrosoft(name("Notch"), Xuid(1001L), "SteveGT")).reason)
        directoryDown = true
        assertIs<AuthCoordinator.ClaimOutcome.Refused>(auth.bindMicrosoft(name("Alex"), Xuid(1001L), "SteveGT"))
        directoryDown = false
        directoryWithheld = true
        assertIs<AuthCoordinator.ClaimOutcome.Refused>(auth.bindMicrosoft(name("Alex"), Xuid(1001L), "SteveGT"))
        assertEquals(name("Steve"), identities.byUuid(Identity.ofXuid(Xuid(1001L)))?.name)
    }

    @Test
    fun `automatic rename rolls back both name and profile refresh if its write fails`() {
        val auth = autoRenaming()
        val original = assertIs<AuthCoordinator.ClaimOutcome.Bound>(auth.bindMicrosoft(name("Steve"), Xuid(1001L), "OldGT"))
        database.withConnection { connection ->
            connection.createStatement().use { it.execute("""
                CREATE TRIGGER reject_auto_rename BEFORE UPDATE ON kyokouauth_name
                WHEN NEW.name_key = 'alex'
                BEGIN SELECT RAISE(ABORT, 'simulated name write failure'); END
            """.trimIndent()) }
        }
        assertFails { auth.bindMicrosoft(name("Alex"), Xuid(1001L), "NewGT") }
        assertEquals(original.record, identities.byUuid(original.record.uuid), "profile and name updates must roll back together")
        assertEquals(null, identities.byName(name("Alex")))
    }

    @Test
    fun `automatic rename invalidates the identity cache`() {
        val cached = moe.ryuu.kyokouauth.core.storage.CachedIdentityRepository(identities)
        val auth = autoRenaming(cached)
        val first = assertIs<AuthCoordinator.ClaimOutcome.Bound>(auth.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT"))
        assertEquals(name("Steve"), cached.cached(first.record.uuid)?.name)
        assertIs<AuthCoordinator.ClaimOutcome.Bound>(auth.bindMicrosoft(name("Alex"), Xuid(1001L), "SteveGT"))
        assertEquals(name("Alex"), cached.cached(first.record.uuid)?.name)
    }

    @Test
    fun `revoking a session forces verification again`() {
        coordinator.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        coordinator.grantSession(Identity.ofXuid(Xuid(1001L)), "10.0.0.1")
        coordinator.revokeSession(Identity.ofXuid(Xuid(1001L)))

        val decision = assertIs<AuthCoordinator.Decision.Verify>(coordinator.decide(name("Steve"), "10.0.0.1"))
        assertEquals(AuthCoordinator.Reason.SESSION_EXPIRED, decision.reason)
    }

    @Test
    fun `a session is not granted when sessions are disabled`() {
        val disabled = AuthCoordinator(
            identities = identities,
            sessions = sessions,
            clock = { now },
            sessionMillis = 0L,
            directory = { MojangDirectory.Lookup.Free },
        )
        disabled.bindMicrosoft(name("Steve"), Xuid(1001L), "SteveGT")
        disabled.grantSession(Identity.ofXuid(Xuid(1001L)), "10.0.0.1")

        val decision = assertIs<AuthCoordinator.Decision.Verify>(disabled.decide(name("Steve"), "10.0.0.1"))
        assertEquals(AuthCoordinator.Reason.SESSION_EXPIRED, decision.reason)
    }

    @Test
    fun `a premium bind keeps the mojang uuid rather than deriving one`() {
        val mojangUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

        val outcome = coordinator.bindPremium(mojangUuid, name("Notch"))

        val bound = assertIs<AuthCoordinator.ClaimOutcome.Bound>(outcome)
        assertEquals(mojangUuid, bound.record.uuid)
    }

    @Test
    fun `a premium player who renames is followed, not locked out`() {
        val mojangUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
        assertIs<AuthCoordinator.ClaimOutcome.Bound>(coordinator.bindPremium(mojangUuid, name("OldName")))

        // They changed their Mojang name and the handshake now reports the new one. Refusing here
        // would lock them out of their own account and freeze the old name forever.
        val outcome = coordinator.bindPremium(mojangUuid, name("NewName"))

        val bound = assertIs<AuthCoordinator.ClaimOutcome.Bound>(outcome)
        assertEquals(name("NewName"), bound.record.name)
        assertEquals(mojangUuid, bound.record.uuid, "a rename must not change the UUID")
        val nextLogin = assertIs<AuthCoordinator.Decision.MojangSession>(coordinator.decide(name("NewName"), "10.0.0.1"))
        assertEquals(mojangUuid, nextLogin.expectedUuid, "a renamed premium identity still uses the real session handshake")
    }

    @Test
    fun `the old name is released after a premium rename`() {
        val mojangUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
        coordinator.bindPremium(mojangUuid, name("OldName"))
        coordinator.bindPremium(mojangUuid, name("NewName"))

        // The name row held the primary key; if the rename did not move it, this name would be
        // permanently unusable by anyone - including its former owner if they renamed back.
        val freedForAnother = coordinator.bindMicrosoft(name("OldName"), Xuid(5555L), "OtherGT")
        assertIs<AuthCoordinator.ClaimOutcome.Bound>(freedForAnother)
    }
}
