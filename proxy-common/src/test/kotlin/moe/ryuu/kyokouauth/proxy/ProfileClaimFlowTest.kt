package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.ConfirmationMenu
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.RoleSelectorIcon
import moe.ryuu.kyokouauth.limbo.RoleSelectorMenu
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Delayed
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileClaimFlowTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `reserved native alias keeps the original claim name and cannot substitute another name`() {
        val original = GameName.parseOrThrow("NewSteve")
        val id = moe.ryuu.kyokouauth.core.auth.AuthCoordinator.provisionalUuid(original)
        val alias = moe.ryuu.kyokouauth.limbo.VerificationProfileName.of(id)
        for (claimed in listOf(original, GameName.parseOrThrow("OtherName"))) {
            val h = Harness()
            val session = UUID.randomUUID()
            val player = FakePlayer(session, id, alias)
            val limbo = FakeLimboPlayer(session, id, alias)
            h.flow.open(player, limbo, claimed)
            if (claimed == original) {
                assertEquals(ProfileClaimFlow.Stage.OFFER, h.flow.pending().single().stage)
                assertTrue(limbo.menus.isNotEmpty())
                assertTrue(h.failures.isEmpty())
            } else {
                assertTrue(h.flow.pending().isEmpty())
                assertTrue(h.failures.isNotEmpty())
            }
        }
    }

    @Test
    fun `private password proof starts from the offer and still requires source and Xbox confirmations`() {
        val h = Harness()
        val connection = h.connection("NewSteve")
        h.flow.open(connection.player, connection.limbo, GameName.parseOrThrow("NewSteve"))
        val password = "PRIVATE_TEST_SENTINEL".toCharArray()
        val proof = h.flow.proveAuthMe(connection.player, connection.limbo, GameName.parseOrThrow("OldSteve"), password)
        assertFalse(proof.isDone)
        assertEquals(ProfileClaimFlow.Stage.WAITING_OLD, h.flow.pending().single().stage)
        h.runTasks()
        assertTrue(proof.get())
        assertTrue(password.all { it == '\u0000' })
        assertEquals(ProfileClaimFlow.Stage.CONFIRM_OLD, h.flow.pending().single().stage)
        assertEquals(0, h.commitCalls)
        assertTrue(h.startedVerifications.isEmpty())
        h.flow.select(connection.limbo, connection.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        assertEquals(1, h.startedVerifications.size)
        h.flow.verified(connection.player, connection.limbo, Xuid(123L), "SyntheticGT")
        assertEquals(ProfileClaimFlow.Stage.CONFIRM_BIND, h.flow.pending().single().stage)
        assertEquals(0, h.commitCalls)
        h.flow.select(connection.limbo, connection.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        h.runTasks()
        assertEquals(1, h.commitCalls)
        assertEquals(1, h.claimedRecords.size)
    }

    @Test
    fun `disconnect and cancel immediately erase queued old passwords and late work cannot authorize`() {
        for (cancel in listOf(false, true)) {
            val h = Harness()
            val conn = h.connection("NewSteve")
            h.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("NewSteve"))
            val password = "PRIVATE_QUEUED_SECRET".toCharArray()
            val result = h.flow.proveAuthMe(conn.player, conn.limbo, GameName.parseOrThrow("OldSteve"), password)
            if (cancel) assertTrue(h.flow.cancel(conn.player, conn.limbo)) else h.flow.close(conn.sessionId)
            assertTrue(password.all { it == '\u0000' })
            h.runTasks()
            assertFalse(result.get())
            assertEquals(0, h.oldProofCalls)
            assertEquals(0, h.commitCalls)
            assertTrue(h.startedVerifications.isEmpty())
        }
    }

    @Test
    fun `wrong old proof allows a bounded gate retry without ordinary verification fallback`() {
        val h = Harness()
        val conn = h.connection("NewSteve")
        h.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("NewSteve"))
        val original = h.passwordProof
        h.passwordProof = { _, _, _, _, _ -> null }
        val wrong = "PRIVATE_WRONG_SECRET".toCharArray()
        val first = h.flow.proveAuthMe(conn.player, conn.limbo, GameName.parseOrThrow("OldSteve"), wrong)
        h.runTasks()
        assertFalse(first.get())
        assertTrue(wrong.all { it == '\u0000' })
        assertEquals(ProfileClaimFlow.Stage.WAITING_OLD, h.flow.pending().single().stage)
        assertTrue(h.startedVerifications.isEmpty())
        assertTrue(h.failures.isEmpty())
        h.passwordProof = original
        val good = h.flow.proveAuthMe(conn.player, conn.limbo, GameName.parseOrThrow("OldSteve"), "good".toCharArray())
        h.runTasks()
        assertTrue(good.get())
        assertEquals(ProfileClaimFlow.Stage.CONFIRM_OLD, h.flow.pending().single().stage)
    }

    @Test
    fun `parallel old proof duplicate and source errors expose no secret or second authorization`() {
        val h = Harness()
        val conn = h.connection("NewSteve")
        h.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("NewSteve"))
        h.passwordProof = { _, _, _, _, _ -> throw IllegalStateException("PRIVATE_SOURCE_EXCEPTION_SENTINEL") }
        val first = h.flow.proveAuthMe(conn.player, conn.limbo, GameName.parseOrThrow("OldSteve"), "first".toCharArray())
        val duplicate = "PRIVATE_DUPLICATE_SECRET".toCharArray()
        assertFalse(h.flow.proveAuthMe(conn.player, conn.limbo, GameName.parseOrThrow("OldSteve"), duplicate).get())
        assertTrue(duplicate.all { it == '\u0000' })
        h.runTasks()
        val failure = assertFailsWith<ExecutionException> { first.get() }
        assertFalse(failure.toString().contains("PRIVATE_SOURCE_EXCEPTION_SENTINEL"))
        assertFalse(failure.cause.toString().contains("PRIVATE_SOURCE_EXCEPTION_SENTINEL"))
        assertEquals(1, h.oldProofCalls)
        assertEquals(0, h.commitCalls)
        assertTrue(h.startedVerifications.isEmpty())
    }

    @Test
    fun `expiry during source proof cannot advance to confirmation or Microsoft verification`() {
        val h = Harness(ttlMillis = 1000L)
        val conn = h.connection("NewSteve")
        h.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("NewSteve"))
        h.passwordProof = { legacy, _, session, target, _ ->
            h.now.addAndGet(1000L)
            h.preparedFactory(UUID.randomUUID(), session, target).copy(legacyName = legacy)
        }
        val password = "PRIVATE_EXPIRED_SECRET".toCharArray()
        val result = h.flow.proveAuthMe(conn.player, conn.limbo, GameName.parseOrThrow("OldSteve"), password)
        h.runTasks()
        assertFalse(result.get())
        h.runScheduledDue()
        assertTrue(password.all { it == '\u0000' })
        assertTrue(h.flow.pending().isEmpty())
        assertTrue(h.startedVerifications.isEmpty())
        assertEquals(0, h.commitCalls)
    }

    @Test
    fun `happy path binds exact session through console approval and fresh Xbox proof without leaking token`() {
        val harness = Harness()
        val conn = harness.connection("NewSteve")
        val legacyId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val legacyProfileUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val secretValue = "SECRET_CLAIM_CAPABILITY_987654"
        harness.preparedFactory = { recordId, session, _ ->
            ProfileClaimFlow.PreparedClaim(
                token = secretValue,
                recordId = recordId,
                legacyName = GameName.parseOrThrow("OldSteve"),
                profileUuid = legacyProfileUuid,
                expiresAt = harness.now.get() + 300_000L,
            ).also {
                assertEquals(conn.sessionId, session)
                assertFalse(it.toString().contains(secretValue))
                assertFalse(it.token.toString().contains(secretValue))
            }
        }

        harness.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("NewSteve"))
        assertTrue(harness.flow.isActive(conn.sessionId))
        assertEquals(1, conn.limbo.menus.size)

        val offerMenu = conn.limbo.menus.last()
        assertEquals(ConfirmationMenu.CONFIRM_ID, offerMenu.microsoft.optionId)
        assertEquals(ConfirmationMenu.CANCEL_ID, offerMenu.premium.optionId)
        assertEquals(RoleSelectorIcon.DIAMOND, offerMenu.microsoft.icon)
        assertEquals(RoleSelectorIcon.NAME_TAG, offerMenu.premium.icon)
        assertEquals(ConfirmationMenu.CONFIRM_PLACEHOLDER_UUID, offerMenu.microsoft.profileUuid)
        assertEquals(ConfirmationMenu.CANCEL_PLACEHOLDER_UUID, offerMenu.premium.profileUuid)

        // Confirm initial offer -> WAITING_OLD
        assertTrue(harness.flow.select(conn.limbo, offerMenu.generation, ConfirmationMenu.CONFIRM_ID))
        assertEquals(ProfileClaimFlow.Stage.WAITING_OLD, harness.flow.pending().single().stage)
        assertTrue(conn.limbo.messages.any { it.toString().contains("人工") || it.toString().contains("manual") })

        // Console authorizes concrete legacy record
        val authFuture = harness.flow.authorize(conn.sessionId, legacyId)
        assertFalse(authFuture.isDone)
        harness.runTasks()
        assertTrue(authFuture.isDone && !authFuture.isCompletedExceptionally)

        val pendingConfirmOld = harness.flow.pending().single()
        assertEquals(ProfileClaimFlow.Stage.CONFIRM_OLD, pendingConfirmOld.stage)
        assertEquals("OldSteve", pendingConfirmOld.legacyName?.value)
        assertFalse(pendingConfirmOld.toString().contains(secretValue))

        // First confirmation -> startVerification while flow stays active in MICROSOFT
        val confirmOldMenu = conn.limbo.menus.last()
        assertNotEquals(offerMenu.generation, confirmOldMenu.generation)
        assertTrue(harness.flow.select(conn.limbo, confirmOldMenu.generation, ConfirmationMenu.CONFIRM_ID))
        assertEquals(1, harness.startedVerifications.size)
        assertSame(conn.player, harness.startedVerifications.single().first)
        assertTrue(harness.flow.isActive(conn.sessionId))
        assertEquals(ProfileClaimFlow.Stage.MICROSOFT, harness.flow.pending().single().stage)

        // Fresh Microsoft/Xbox proof -> CONFIRM_BIND
        assertTrue(harness.flow.verified(conn.player, conn.limbo, Xuid(2535412345678901L), "NewSteveXbox"))
        assertEquals(ProfileClaimFlow.Stage.CONFIRM_BIND, harness.flow.pending().single().stage)
        val confirmBindMenu = conn.limbo.menus.last()
        val allMenuText = listOf(confirmBindMenu.title, confirmBindMenu.microsoft.name, confirmBindMenu.premium.name)
            .plus(confirmBindMenu.microsoft.lore)
            .plus(confirmBindMenu.premium.lore)
            .joinToString("\n") { flattenText(it) }
        assertTrue(allMenuText.contains("OldSteve"))
        assertTrue(allMenuText.contains("NewSteve"))
        assertTrue(allMenuText.contains("NewSteveXbox"))
        assertFalse(allMenuText.contains(secretValue))

        // Final confirmation -> single commit submission -> claimed callback
        assertTrue(harness.flow.select(conn.limbo, confirmBindMenu.generation, ConfirmationMenu.CONFIRM_ID))
        assertEquals(0, harness.commitCalls)
        harness.runTasks()
        assertEquals(1, harness.commitCalls)
        assertEquals(1, harness.claimedRecords.size)
        val claimed = harness.claimedRecords.single()
        assertSame(conn.player, claimed.first)
        assertSame(conn.limbo, claimed.second)
        assertEquals(legacyProfileUuid, claimed.third.uuid)
        assertEquals("NewSteve", claimed.third.name.value)
        assertFalse(harness.flow.isActive(conn.sessionId))
        assertTrue(harness.failures.isEmpty())
        assertTrue(conn.limbo.messages.none { flattenText(it).contains(secretValue) })
    }

    @Test
    fun `cancelling at OFFER exits claim flow and delegates to ordinary verification`() {
        val harness = Harness()
        val conn = harness.connection("FreshPlayer")
        harness.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("FreshPlayer"))
        val offerMenu = conn.limbo.menus.single()

        assertTrue(harness.flow.select(conn.limbo, offerMenu.generation, ConfirmationMenu.CANCEL_ID))
        assertFalse(harness.flow.isActive(conn.sessionId))
        assertTrue(harness.flow.pending().isEmpty())
        assertEquals(1, harness.startedVerifications.size)
        assertTrue(harness.failures.isEmpty())
        // Subsequent verified proof is not intercepted by claim flow
        assertFalse(harness.flow.verified(conn.player, conn.limbo, Xuid(42L), "FreshTag"))
    }

    @Test
    fun `cancelling at CONFIRM_OLD or CONFIRM_BIND fails closed without falling back to new account`() {
        for (cancelAtBind in listOf(false, true)) {
            val harness = Harness()
            val conn = harness.connection("CarefulUser")
            harness.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("CarefulUser"))
            assertTrue(harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID))
            harness.flow.authorize(conn.sessionId, UUID.randomUUID())
            harness.runTasks()

            if (!cancelAtBind) {
                assertTrue(harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CANCEL_ID))
                assertEquals(0, harness.startedVerifications.size)
            } else {
                assertTrue(harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID))
                assertEquals(1, harness.startedVerifications.size)
                assertTrue(harness.flow.verified(conn.player, conn.limbo, Xuid(99L), "CarefulXbox"))
                assertTrue(harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CANCEL_ID))
            }

            assertFalse(harness.flow.isActive(conn.sessionId))
            assertEquals(0, harness.commitCalls)
            assertTrue(harness.claimedRecords.isEmpty())
            assertEquals(listOf("migration.cancelled"), harness.failures.map { it.third })
        }
    }

    @Test
    fun `unknown session select returns false while stale generation on active flow is swallowed`() {
        val harness = Harness()
        val unknown = harness.connection("BedrockChooser")
        assertFalse(harness.flow.select(unknown.limbo, UUID.randomUUID(), "microsoft"))

        val conn = harness.connection("Claimer")
        harness.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("Claimer"))
        val offerGen = conn.limbo.menus.single().generation

        // Stale / wrong generation on an active claim connection is swallowed (returns true) without changing stage
        assertTrue(harness.flow.select(conn.limbo, UUID.randomUUID(), ConfirmationMenu.CONFIRM_ID))
        assertEquals(ProfileClaimFlow.Stage.OFFER, harness.flow.pending().single().stage)

        // Valid click advances to WAITING_OLD; replaying the consumed generation is swallowed
        assertTrue(harness.flow.select(conn.limbo, offerGen, ConfirmationMenu.CONFIRM_ID))
        assertEquals(ProfileClaimFlow.Stage.WAITING_OLD, harness.flow.pending().single().stage)
        assertTrue(harness.flow.select(conn.limbo, offerGen, ConfirmationMenu.CANCEL_ID))
        assertEquals(ProfileClaimFlow.Stage.WAITING_OLD, harness.flow.pending().single().stage)
        assertTrue(harness.failures.isEmpty())
    }

    @Test
    fun `authorize rejects non-WAITING_OLD stages and source approval or commit failures never fall back to new account`() {
        val harness = Harness()
        val conn = harness.connection("TargetName")
        harness.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("TargetName"))

        // Rejects authorize while still in OFFER
        val premature = harness.flow.authorize(conn.sessionId, UUID.randomUUID())
        assertTrue(premature.isCompletedExceptionally)
        assertFailsWith<ExecutionException> { premature.get() }

        // Move to WAITING_OLD and simulate approve failure
        assertTrue(harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID))
        harness.preparedFactory = { _, _, _ -> throw IllegalStateException("Legacy record already claimed") }
        val failedApprove = harness.flow.authorize(conn.sessionId, UUID.randomUUID())
        harness.runTasks()
        assertTrue(failedApprove.isCompletedExceptionally)
        assertFalse(harness.flow.isActive(conn.sessionId))
        assertEquals(0, harness.startedVerifications.size)
        assertEquals(listOf("migration.failed"), harness.failures.map { it.third })

        // Now test commit failure: also fails closed and submits commit only once
        val harness2 = Harness()
        val conn2 = harness2.connection("TargetName2")
        harness2.commitBehavior = { _, _, _, _, _ -> throw IllegalStateException("Concurrent conflict") }
        harness2.flow.open(conn2.player, conn2.limbo, GameName.parseOrThrow("TargetName2"))
        harness2.flow.select(conn2.limbo, conn2.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        harness2.flow.authorize(conn2.sessionId, UUID.randomUUID())
        harness2.runTasks()
        harness2.flow.select(conn2.limbo, conn2.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        harness2.flow.verified(conn2.player, conn2.limbo, Xuid(777L), "Gamer777")
        val bindGen = conn2.limbo.menus.last().generation
        assertTrue(harness2.flow.select(conn2.limbo, bindGen, ConfirmationMenu.CONFIRM_ID))
        // Duplicate confirm while COMMITTING is swallowed without a second commit
        assertTrue(harness2.flow.select(conn2.limbo, bindGen, ConfirmationMenu.CONFIRM_ID))
        harness2.runTasks()

        assertEquals(1, harness2.commitCalls)
        assertTrue(harness2.claimedRecords.isEmpty())
        assertEquals(listOf("migration.failed"), harness2.failures.map { it.third })
        assertFalse(harness2.flow.isActive(conn2.sessionId))
    }

    @Test
    fun `session replacement with same sessionId but different handles isolates late commit from releasing successor`() {
        val harness = Harness()
        val sharedSessionId = UUID.randomUUID()
        val first = harness.connection("SharedPlayer", sessionId = sharedSessionId)

        harness.flow.open(first.player, first.limbo, GameName.parseOrThrow("SharedPlayer"))
        harness.flow.select(first.limbo, first.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        harness.flow.authorize(sharedSessionId, UUID.randomUUID())
        harness.runTasks()
        harness.flow.select(first.limbo, first.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        harness.flow.verified(first.player, first.limbo, Xuid(555L), "Tag555")
        harness.flow.select(first.limbo, first.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)

        // Before the queued commit worker runs, first disconnects and a new physical Player/LimboPlayer reuses sessionId
        first.player.active = false
        harness.flow.close(sharedSessionId)
        val second = harness.connection("SharedPlayer", sessionId = sharedSessionId)
        harness.flow.open(second.player, second.limbo, GameName.parseOrThrow("SharedPlayer"))
        assertTrue(harness.flow.isActive(sharedSessionId))

        // Late commit worker executes; it must not claim or close the second connection
        harness.runTasks()
        assertTrue(harness.claimedRecords.isEmpty())
        assertTrue(harness.failures.isEmpty())
        assertTrue(harness.flow.isActive(sharedSessionId))
        assertEquals(ProfileClaimFlow.Stage.OFFER, harness.flow.pending().single().stage)
    }

    @Test
    fun `ttl bounds timeout scheduling capacity limit submit rejection and flow close fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            val h = Harness()
            ProfileClaimFlow(h.messages, h.services, h.callbacks, ttlMillis = ProfileClaimFlow.MAX_TTL_MILLIS + 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            val h = Harness()
            ProfileClaimFlow(h.messages, h.services, h.callbacks, maxEntries = 0)
        }

        // Capacity enforcement
        val capped = Harness(maxEntries = 2, ttlMillis = 60_000L)
        val c1 = capped.connection("PlayerOne")
        val c2 = capped.connection("PlayerTwo")
        val c3 = capped.connection("PlayerThree")
        capped.flow.open(c1.player, c1.limbo, GameName.parseOrThrow("PlayerOne"))
        capped.flow.open(c2.player, c2.limbo, GameName.parseOrThrow("PlayerTwo"))
        capped.flow.open(c3.player, c3.limbo, GameName.parseOrThrow("PlayerThree"))
        assertEquals(2, capped.flow.pending().size)
        assertEquals(1, capped.failures.count { it.first === c3.player && it.second === c3.limbo && it.third == "migration.busy" })

        // Expiration disconnects with migration.expired
        capped.now.addAndGet(60_000L)
        capped.runScheduledDue()
        assertTrue(capped.flow.pending().isEmpty())
        assertEquals(2, capped.failures.count { it.third == "migration.expired" })

        // Submit rejection during authorize fails closed
        val rejecting = Harness()
        val rc = rejecting.connection("RejectMe")
        rejecting.flow.open(rc.player, rc.limbo, GameName.parseOrThrow("RejectMe"))
        rejecting.flow.select(rc.limbo, rc.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        rejecting.rejectSubmits = true
        val rejectedFuture = rejecting.flow.authorize(rc.sessionId, UUID.randomUUID())
        assertTrue(rejectedFuture.isCompletedExceptionally)
        assertFalse(rejecting.flow.isActive(rc.sessionId))
        assertEquals(listOf("migration.failed"), rejecting.failures.map { it.third })

        // Flow shutdown closes remaining active sessions
        val closing = Harness()
        val activeConn = closing.connection("StillWaiting")
        closing.flow.open(activeConn.player, activeConn.limbo, GameName.parseOrThrow("StillWaiting"))
        closing.flow.close()
        assertFalse(closing.flow.isActive(activeConn.sessionId))
        assertEquals(listOf("login.internal-error"), closing.failures.map { it.third })
    }

    @Test
    fun `synchronous callback reentry inside startVerification and failed does not deadlock or corrupt state`() {
        val harness = Harness()
        val conn = harness.connection("ReentrantUser")
        var reentered = false
        harness.onStartVerification = { player, limbo ->
            reentered = true
            // Synchronously deliver verified proof inside startVerification callback
            assertTrue(harness.flow.isActive(player.sessionId))
            assertTrue(harness.flow.verified(player, limbo, Xuid(8888L), "SyncGamer"))
        }

        harness.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("ReentrantUser"))
        harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        harness.flow.authorize(conn.sessionId, UUID.randomUUID())
        harness.runTasks()
        assertTrue(harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID))

        assertTrue(reentered)
        assertEquals(ProfileClaimFlow.Stage.CONFIRM_BIND, harness.flow.pending().single().stage)
    }

    @Test
    fun `commit rejects mismatched profileUuid and post-stop late commit never fires claimed`() {
        // 1. Mismatched bound.uuid vs prepared.profileUuid must fail closed
        val mismatchHarness = Harness()
        val c1 = mismatchHarness.connection("UuidCheckUser")
        mismatchHarness.commitBehavior = { _, _, name, xuid, gamertag ->
            BoundIdentity(
                uuid = UUID.fromString("99999999-9999-9999-9999-999999999999"),
                name = name,
                kind = BoundIdentity.Kind.XUID,
                subject = xuid.value.toString(),
                gamertag = gamertag,
                createdAt = mismatchHarness.now.get(),
                lastSeenAt = mismatchHarness.now.get(),
            )
        }
        mismatchHarness.flow.open(c1.player, c1.limbo, GameName.parseOrThrow("UuidCheckUser"))
        mismatchHarness.flow.select(c1.limbo, c1.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        mismatchHarness.flow.authorize(c1.sessionId, UUID.randomUUID())
        mismatchHarness.runTasks()
        mismatchHarness.flow.select(c1.limbo, c1.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        mismatchHarness.flow.verified(c1.player, c1.limbo, Xuid(3333L), "UuidCheckTag")
        mismatchHarness.flow.select(c1.limbo, c1.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        mismatchHarness.runTasks()

        assertTrue(mismatchHarness.claimedRecords.isEmpty())
        assertEquals(listOf("migration.failed"), mismatchHarness.failures.map { it.third })
        assertFalse(mismatchHarness.flow.hasActive(c1.player, c1.limbo))

        // 2. Flow stopped while commit is already in flight inside services.commit must never call claimed
        val stopDuringCommit = Harness()
        val c2 = stopDuringCommit.connection("StopMidCommit")
        stopDuringCommit.commitBehavior = { _, _, name, xuid, gamertag ->
            stopDuringCommit.flow.close()
            BoundIdentity(
                uuid = stopDuringCommit.defaultProfileUuid,
                name = name,
                kind = BoundIdentity.Kind.XUID,
                subject = xuid.value.toString(),
                gamertag = gamertag,
                createdAt = stopDuringCommit.now.get(),
                lastSeenAt = stopDuringCommit.now.get(),
            )
        }
        stopDuringCommit.flow.open(c2.player, c2.limbo, GameName.parseOrThrow("StopMidCommit"))
        stopDuringCommit.flow.select(c2.limbo, c2.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        stopDuringCommit.flow.authorize(c2.sessionId, UUID.randomUUID())
        stopDuringCommit.runTasks()
        stopDuringCommit.flow.select(c2.limbo, c2.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        stopDuringCommit.flow.verified(c2.player, c2.limbo, Xuid(4444L), "StopTag")
        stopDuringCommit.flow.select(c2.limbo, c2.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        stopDuringCommit.runTasks()

        assertEquals(1, stopDuringCommit.commitCalls)
        assertTrue(stopDuringCommit.claimedRecords.isEmpty())
        assertEquals(listOf("login.internal-error"), stopDuringCommit.failures.map { it.third })
    }

    @Test
    fun `expired claim proof is consumed as a failure even when its timer has not fired`() {
        val harness = Harness(ttlMillis = 20_000L)
        val conn = harness.connection("NewSteve")
        harness.flow.open(conn.player, conn.limbo, GameName.parseOrThrow("NewSteve"))
        harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        val approval = harness.flow.authorize(conn.sessionId, UUID.randomUUID())
        harness.runTasks()
        approval.join()
        harness.flow.select(conn.limbo, conn.limbo.menus.last().generation, ConfirmationMenu.CONFIRM_ID)
        harness.now.addAndGet(20_000L)
        assertFalse(harness.flow.hasActive(conn.player, conn.limbo))
        assertTrue(harness.flow.verified(conn.player, conn.limbo, Xuid(1001), "Proof"))
        assertEquals(listOf("migration.expired"), harness.failures.map { it.third })
        assertEquals(0, harness.commitCalls)
    }

    @Test
    fun `hasActive and verified require exact handles without swallowing non-matching ordinary callbacks`() {
        val harness = Harness()
        val sharedSession = UUID.randomUUID()
        val claimConn = harness.connection("SameSession", sessionId = sharedSession)
        val otherConn = harness.connection("SameSession", sessionId = sharedSession)

        harness.flow.open(claimConn.player, claimConn.limbo, GameName.parseOrThrow("SameSession"))
        assertTrue(harness.flow.hasActive(claimConn.player, claimConn.limbo))
        assertFalse(harness.flow.hasActive(otherConn.player, otherConn.limbo))
        assertFalse(harness.flow.hasActive(claimConn.player, otherConn.limbo))
        assertFalse(harness.flow.hasActive(otherConn.player, claimConn.limbo))

        // Non-matching physical handle returns false so ordinary LoginGate callback is not swallowed,
        // and does not abort the real claim entry either.
        assertFalse(harness.flow.verified(otherConn.player, otherConn.limbo, Xuid(1234L), "OrdinaryTag"))
        assertFalse(harness.flow.select(otherConn.limbo, UUID.randomUUID(), "microsoft"))
        assertTrue(harness.flow.hasActive(claimConn.player, claimConn.limbo))
        assertTrue(harness.failures.isEmpty())

        // Read-only queries after deadline return false without firing side-effect callbacks
        harness.now.addAndGet(ProfileClaimFlow.DEFAULT_TTL_MILLIS + 1L)
        assertFalse(harness.flow.hasActive(claimConn.player, claimConn.limbo))
        assertFalse(harness.flow.isActive(sharedSession))
        assertTrue(harness.flow.pending().isEmpty())
        assertTrue(harness.failures.isEmpty())
    }

    private fun flattenText(component: TextComponent): String =
        buildString {
            append(component.text)
            component.children.forEach { append(flattenText(it)) }
        }

    private inner class Harness(
        ttlMillis: Long = ProfileClaimFlow.DEFAULT_TTL_MILLIS,
        maxEntries: Int = ProfileClaimFlow.DEFAULT_CAPACITY,
    ) {
        val now = AtomicLong(1_000_000L)
        val messages = MessageCatalogue("zh_CN", directory.toFile()).also { it.load() }
        private val tasks = ArrayDeque<() -> Unit>()
        private val scheduled = ArrayList<ScheduledTask>()
        var rejectSubmits = false
        var commitCalls = 0
        var oldProofCalls = 0
        var passwordProof: (GameName, CharArray, UUID, GameName, () -> Boolean) -> ProfileClaimFlow.PreparedClaim? =
            { legacy, _, session, name, current ->
                if (current()) preparedFactory(UUID.randomUUID(), session, name).copy(legacyName = legacy) else null
            }

        val defaultProfileUuid: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        var preparedFactory: (UUID, UUID, GameName) -> ProfileClaimFlow.PreparedClaim = { recordId, _, _ ->
            ProfileClaimFlow.PreparedClaim(
                token = "tok-${UUID.randomUUID()}",
                recordId = recordId,
                legacyName = GameName.parseOrThrow("LegacyUser"),
                profileUuid = defaultProfileUuid,
                expiresAt = now.get() + 300_000L,
            )
        }

        var commitBehavior: (ProfileClaimFlow.ClaimToken, UUID, GameName, Xuid, String) -> BoundIdentity =
            { _, _, name, xuid, gamertag ->
                BoundIdentity(
                    uuid = defaultProfileUuid,
                    name = name,
                    kind = BoundIdentity.Kind.XUID,
                    subject = xuid.value.toString(),
                    gamertag = gamertag,
                    createdAt = now.get(),
                    lastSeenAt = now.get(),
                )
            }

        val startedVerifications = ArrayList<Pair<Player, LimboPlayer>>()
        val claimedRecords = ArrayList<Triple<Player, LimboPlayer, BoundIdentity>>()
        val failures = ArrayList<Triple<Player, LimboPlayer, String>>()
        var onStartVerification: ((Player, LimboPlayer) -> Unit)? = null

        val services = object : ProfileClaimFlow.Services {
            override fun submit(task: () -> Unit) {
                if (rejectSubmits) throw RejectedExecutionException("busy")
                tasks.addLast(task)
            }

            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> {
                val handle = ScheduledTask(now.get() + delayMillis, task)
                scheduled.add(handle)
                return handle
            }

            override fun approve(recordId: UUID, session: UUID, name: GameName): ProfileClaimFlow.PreparedClaim =
                preparedFactory(recordId, session, name)
            override fun proveAuthMe(legacyName: GameName, password: CharArray, session: UUID, name: GameName,
                                     current: () -> Boolean): ProfileClaimFlow.PreparedClaim? {
                oldProofCalls++
                return passwordProof(legacyName, password, session, name, current)
            }

            override fun commit(
                token: ProfileClaimFlow.ClaimToken,
                session: UUID,
                name: GameName,
                xuid: Xuid,
                gamertag: String,
            ): BoundIdentity {
                commitCalls++
                return commitBehavior(token, session, name, xuid, gamertag)
            }
        }

        val callbacks = object : ProfileClaimFlow.Callbacks {
            override fun startVerification(player: Player, limbo: LimboPlayer) {
                startedVerifications.add(player to limbo)
                onStartVerification?.invoke(player, limbo)
            }

            override fun claimed(player: Player, limbo: LimboPlayer, record: BoundIdentity) {
                claimedRecords.add(Triple(player, limbo, record))
            }

            override fun failed(player: Player, limbo: LimboPlayer, key: String) {
                failures.add(Triple(player, limbo, key))
            }
        }

        val flow = ProfileClaimFlow(messages, services, callbacks, ttlMillis = ttlMillis, maxEntries = maxEntries, clock = now::get)

        fun runTasks() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().invoke()
            }
        }

        fun runScheduledDue() {
            val current = now.get()
            val due = scheduled.filter { !it.isCancelled && !it.done && it.runAt <= current }
            for (item in due) {
                item.done = true
                item.task.invoke()
            }
        }

        fun connection(username: String, sessionId: UUID = UUID.randomUUID()): ConnPair {
            val uuid = UUID.nameUUIDFromBytes("offline:$username".toByteArray())
            val player = FakePlayer(sessionId, uuid, username)
            val limbo = FakeLimboPlayer(sessionId, uuid, username)
            return ConnPair(sessionId, player, limbo)
        }
    }

    private data class ConnPair(val sessionId: UUID, val player: FakePlayer, val limbo: FakeLimboPlayer)

    private class ScheduledTask(val runAt: Long, val task: () -> Unit) : ScheduledFuture<Unit> {
        private var cancelled = false
        var done = false
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            if (done || cancelled) return false
            cancelled = true
            return true
        }
        override fun isCancelled(): Boolean = cancelled
        override fun isDone(): Boolean = done || cancelled
        override fun get(): Unit = Unit
        override fun get(timeout: Long, unit: TimeUnit): Unit = Unit
        override fun getDelay(unit: TimeUnit): Long = 0L
        override fun compareTo(other: Delayed): Int = 0
    }

    private class FakePlayer(
        override val sessionId: UUID,
        override val uniqueId: UUID,
        override val username: String,
    ) : Player {
        var active: Boolean = true
        override val isActive: Boolean get() = active
        override val remoteAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 25565)
        override val isOnlineMode: Boolean = false
        override val effectiveLocale: Locale = Locale.SIMPLIFIED_CHINESE
        override val protocolVersion: Int = 777
        val disconnects = ArrayList<RichText.Parsed>()

        override fun disconnect(message: RichText.Parsed) {
            active = false
            disconnects.add(message)
        }

        override fun hasPermission(permission: String): Boolean = false
        override fun sendMessage(message: RichText.Parsed) = Unit
        override fun createConnectionRequest(target: RegisteredServer): ConnectionRequestBuilder =
            throw UnsupportedOperationException()
    }

    private class FakeLimboPlayer(
        override val connectionId: UUID,
        override val uuid: UUID,
        override val name: String,
    ) : LimboPlayer {
        override val address: String = "127.0.0.1"
        val messages = ArrayList<TextComponent>()
        val menus = ArrayList<RoleSelectorMenu>()
        var closedSelectors = 0
        var clearedTitles = 0

        override fun sendMessage(message: TextComponent) {
            messages.add(message)
        }

        override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) = Unit

        override fun clearTitle() {
            clearedTitles++
        }

        override fun openRoleSelector(menu: RoleSelectorMenu) {
            menus.add(menu)
        }

        override fun closeRoleSelector() {
            closedSelectors++
        }

        override fun disconnect(reason: TextComponent) = Unit
        override fun close() = Unit
    }
}
