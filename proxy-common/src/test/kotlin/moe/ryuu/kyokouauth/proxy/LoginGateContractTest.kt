package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.RoleSelectorMenu
import moe.ryuu.kyokouauth.limbo.RoleSelectorIcon
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Delayed
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoginGateContractTest {
    @Test
    fun `new contract Java roles flag preserves ordinary trusted Microsoft session`() {
        val fixture = Fixture(roleConfig)
        fixture.services.decision = AuthCoordinator.Decision.Admit(record.toIdentity(), record)
        val player = fixture.player(777)
        fixture.login(player)
        assertEquals(record.uuid, player.uniqueId)
        assertEquals("Steve", player.username)
        assertTrue(fixture.gate.isAuthenticated(player))
        assertEquals(0, fixture.services.roleCalls)
        assertTrue(player.limbo.menus.isEmpty())
    }

    @Test
    fun `new contract single Floodgate role is final before player creation with zero reconnects`() {
        val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        fixture.services.options = RoleOptions(listOf(RoleOption(record.toIdentity(), record, null)), null)
        val proof = BedrockProof.create(Xuid(1L), record.uuid, ".Steve", "Steve", ".", Any()) { true }
        val player = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
        fixture.login(player, bedrock = BedrockLogin.Verified(proof))
        assertEquals(record.uuid, player.uniqueId)
        assertEquals("Steve", player.username)
        assertTrue(fixture.gate.isAuthenticated(player))
        assertTrue(player.limbo.menus.isEmpty())
        assertTrue(player.requests.isEmpty())
        assertEquals(0, fixture.services.grants)
    }

    @Test
    fun `new contract exact native dual role selects in place without any reconnect`() {
        val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        val chooser = fixture.joinBedrock()
        fixture.services.runWork()
        assertEquals(premiumUuid, chooser.uniqueId)
        assertEquals("Alex", chooser.username)
        assertFalse(fixture.gate.isAuthenticated(chooser))
        fixture.assertBackendDenied(chooser)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        assertTrue(fixture.gate.isAuthenticated(chooser))
        assertEquals(listOf(fixture.lobby), chooser.requests)
        assertTrue(chooser.disconnects.isEmpty())
        assertEquals(0, fixture.services.grants)
    }

    @Test
    fun `Java role flag leaves basic device code verification and exact identity unchanged`() {
        val fixture = Fixture(roleConfig)
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.services.advanceBy(1_000)
        assertEquals(record.uuid, player.uniqueId)
        assertEquals("Steve", player.username)
        assertTrue(fixture.gate.isAuthenticated(player))
        assertEquals(1, fixture.services.bindCalls)
        assertEquals(1, fixture.services.grants)
        assertEquals(0, fixture.services.roleCalls)
        assertTrue(player.limbo.menus.isEmpty())
    }

    @Test
    fun `different Floodgate role requires exactly one ordinary fresh login and never authorizes old chooser`() {
        val fixture = Fixture(roleConfig)
        val chooser = fixture.player(777)
        fixture.strongBedrock(chooser)
        val menu = chooser.limbo.menus.single()
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "microsoft")
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "microsoft")
        fixture.services.runWork()
        assertEquals(1, chooser.disconnects.size)
        assertEquals(fixture.catalogue.parsed("role-selection.reconnect"), chooser.disconnects.single())
        assertFalse(fixture.gate.isAuthenticated(chooser))
        fixture.assertBackendDenied(chooser)
        assertEquals(0, fixture.services.premiumBinds)
        fixture.gate.onDisconnect(DisconnectEvent(chooser))
        chooser.isActive = false
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val resumed = fixture.player(777).apply {
            uniqueId = proof.uniqueId; username = proof.username
            properties = listOf(GameProfile.Property("textures", "wrong", ""), GameProfile.Property("other", "keep", ""))
        }
        fixture.login(resumed, bedrock = BedrockLogin.Verified(proof))
        assertEquals(record.uuid, resumed.uniqueId)
        assertEquals("Steve", resumed.username)
        assertTrue(fixture.gate.isAuthenticated(resumed))
        assertEquals(0, fixture.services.grants)
        assertTrue(resumed.properties.none { it.name == "textures" })
        assertTrue(resumed.properties.any { it.name == "other" && it.value == "keep" })
        assertEquals(resumed.sessionId.toString(), resumed.properties.single { it.name == LoginGate.CONNECTION_PROPERTY }.value)
        assertTrue(resumed.limbo.menus.isEmpty())
        resumed.isActive = false
        fixture.gate.onDisconnect(DisconnectEvent(resumed))
        val ordinary = fixture.joinBedrock()
        fixture.services.runWork()
        assertEquals(1, ordinary.limbo.menus.size, "Consumed intent is not a remembered role preference")
        assertFalse(fixture.gate.isAuthenticated(ordinary))
    }

    @Test
    fun `renamed menu refreshes generation and an old click cannot authorize or reinterpret the name`() {
        val fixture = Fixture(roleConfig)
        val chooser = fixture.player(777)
        fixture.strongBedrock(chooser)
        val old = chooser.limbo.menus.single()
        val renamed = record.copy(name = GameName.parseOrThrow("SteveNew"))
        fixture.services.options = RoleOptions(listOf(RoleOption(renamed.toIdentity(), renamed, null),
            fixture.dualRoles().roles.single { it.identity is Identity.Premium }), premiumUuid)
        fixture.gate.onRoleSelect(chooser.limbo, old.generation, "microsoft")
        fixture.services.runWork()
        assertEquals(2, chooser.limbo.menus.size)
        assertTrue(chooser.requests.isEmpty())
        val fresh = chooser.limbo.menus.last()
        assertTrue(fresh.generation != old.generation)
        fixture.gate.onRoleSelect(chooser.limbo, old.generation, "microsoft")
        fixture.services.runWork()
        assertTrue(chooser.requests.isEmpty())
        fixture.gate.onRoleSelect(chooser.limbo, fresh.generation, "microsoft")
        fixture.services.runWork()
        fixture.gate.onDisconnect(DisconnectEvent(chooser))
        chooser.isActive = false
        val resumed = fixture.player(777)
        fixture.freshBedrockLogin(resumed)
        assertEquals("SteveNew", resumed.username)
        assertEquals(record.uuid, resumed.uniqueId)
        assertTrue(fixture.gate.isAuthenticated(resumed))
    }

    @Test
    fun `official premium rename refreshes old menu and resumes current name while retaining the actual old binding`() {
        val fixture = Fixture(roleConfig)
        val oldIdentity = Identity.Premium(GameName.parseOrThrow("Alex"), premiumUuid)
        val oldBinding = BoundIdentity.of(oldIdentity, 1L)
        val premium = fixture.dualRoles().roles.single { it.identity is Identity.Premium }
        val oldOptions = RoleOptions(listOf(fixture.dualRoles().roles.first(),
            RoleOption(oldIdentity, oldBinding, premium.skin)), premiumUuid)
        val chooser = fixture.player(777)
        fixture.strongBedrock(chooser, oldOptions)
        val oldMenu = chooser.limbo.menus.single()

        val currentIdentity = Identity.Premium(GameName.parseOrThrow("AlexNew"), premiumUuid)
        fixture.services.options = RoleOptions(listOf(oldOptions.roles.first(),
            RoleOption(currentIdentity, oldBinding, premium.skin)), premiumUuid)
        fixture.gate.onRoleSelect(chooser.limbo, oldMenu.generation, "premium")
        fixture.services.runWork()
        assertTrue(chooser.requests.isEmpty())
        assertTrue(chooser.disconnects.isEmpty())
        assertEquals(2, chooser.limbo.menus.size)
        val currentMenu = chooser.limbo.menus.last()
        assertTrue(currentMenu.generation != oldMenu.generation)
        assertEquals(premiumUuid, currentMenu.premium.profileUuid)
        assertEquals("AlexNew", currentMenu.premium.profileName)
        fixture.gate.onRoleSelect(chooser.limbo, oldMenu.generation, "premium")
        fixture.services.runWork()
        assertTrue(chooser.requests.isEmpty())

        fixture.gate.onRoleSelect(chooser.limbo, currentMenu.generation, "premium")
        fixture.services.runWork()
        fixture.gate.onDisconnect(DisconnectEvent(chooser))
        chooser.isActive = false
        val resumed = fixture.player(777)
        fixture.freshBedrockLogin(resumed)
        assertEquals(premiumUuid, resumed.uniqueId)
        assertEquals("AlexNew", resumed.username)
        assertTrue(fixture.gate.isAuthenticated(resumed))
        assertEquals(1, fixture.services.premiumBinds)
        assertEquals(0, fixture.services.grants)
        val retainedBinding = fixture.services.options.roles.single { it.identity is Identity.Premium }.existing
        assertSame(oldBinding, retainedBinding)
        assertEquals("Alex", retainedBinding?.name?.value)
    }

    @Test
    fun `unlinking while choosing premium fails closed instead of auto selecting Microsoft`() {
        val fixture = Fixture(roleConfig)
        val chooser = fixture.player(777)
        fixture.strongBedrock(chooser)
        val menu = chooser.limbo.menus.single()
        fixture.services.options = RoleOptions(listOf(RoleOption(record.toIdentity(), record, null)), null)
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "premium")
        fixture.services.runWork()
        assertTrue(chooser.requests.isEmpty())
        assertTrue(chooser.disconnects.isNotEmpty())
        assertFalse(fixture.gate.isAuthenticated(chooser))
        assertEquals(0, fixture.services.premiumBinds)
    }

    @Test
    fun `Bedrock hint rename revocation expiry gamertag and issuer reuse fail closed without Java fallback`() {
        for (change in listOf("rename", "unlink", "expire", "gamertag", "issuer")) {
            val fixture = Fixture(roleConfig)
            val chooser = fixture.player(777)
            fixture.strongBedrock(chooser)
            fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "microsoft")
            fixture.services.runWork()
            fixture.gate.onDisconnect(DisconnectEvent(chooser))
            chooser.isActive = false
            when (change) {
                "rename" -> {
                    val renamed = record.copy(name = GameName.parseOrThrow("SteveNew"))
                    fixture.services.options = RoleOptions(listOf(RoleOption(renamed.toIdentity(), renamed, null),
                        fixture.dualRoles().roles.last()), premiumUuid)
                }
                "unlink" -> fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first()), null)
                "expire" -> fixture.now += 31_000L
            }
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex",
                if (change == "gamertag") "RenamedGamer" else "Steve", Any()) { true }
            val calls = fixture.services.decideCalls
            val resumed = fixture.player(777, if (change == "issuer") chooser.sessionId else UUID.randomUUID())
            val event = PreLoginEvent(resumed, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
            fixture.gate.onPreLogin(event)
            assertFalse(event.result.isAllowed, change)
            assertEquals(calls, fixture.services.decideCalls, change)
            assertEquals(0, fixture.services.grants, change)
            assertEquals(0, fixture.services.premiumBinds, change)
        }
    }

    @Test
    fun `stale menu connection foreign handle invalid option and late timeout callbacks never transfer`() {
        val fixture = Fixture(roleConfig + ("role-selection.timeout-seconds" to "5"))
        val chooser = fixture.player(777)
        fixture.strongBedrock(chooser)
        val menu = chooser.limbo.menus.single()
        val impostor = fixture.player(777, chooser.sessionId)
        fixture.gate.onRoleSelect(impostor.limbo, menu.generation, "premium")
        fixture.gate.onRoleSelect(chooser.limbo, UUID.randomUUID(), "premium")
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "admin")
        fixture.services.runWork()
        assertTrue(chooser.requests.isEmpty())
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "premium")
        fixture.now += 5_000L
        fixture.services.runWork()
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "premium")
        fixture.services.runWork()
        assertTrue(chooser.requests.isEmpty())
        assertTrue(chooser.disconnects.isNotEmpty())
        fixture.assertBackendDenied(chooser)
    }

    @Test
    fun `disconnect stop and queue failures cannot transfer a selected role`() {
        for (failure in listOf("disconnect", "stop", "queue", "resolve")) {
            val fixture = Fixture(roleConfig)
            val chooser = fixture.player(777)
            fixture.strongBedrock(chooser)
            if (failure == "queue") fixture.services.rejectSubmissions = true
            if (failure == "resolve") fixture.services.roleFailure = IllegalStateException("unavailable")
            fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
            if (failure == "disconnect") fixture.gate.onDisconnect(DisconnectEvent(chooser))
            if (failure == "stop") fixture.gate.deactivate()
            fixture.services.runWork()
            assertTrue(chooser.requests.isEmpty(), failure)
            assertFalse(fixture.gate.isAuthenticated(chooser), failure)
            assertEquals(0, fixture.services.grants, failure)
        }
    }

    @Test
    fun `role profile LAST rejects id or name tampering and login cannot omit final validation`() {
        for (tamper in listOf("uuid", "name", "skip-last")) {
            val fixture = Fixture(roleConfig)
            fixture.services.options = fixture.dualRoles()
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
            val player = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
            val pre = PreLoginEvent(player, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
            fixture.gate.onPreLogin(pre)
            assertTrue(pre.result.isAllowed)
            val event = GameProfileRequestEvent(player, GameProfile(proof.uniqueId, proof.username, emptyList()), BedrockLogin.Verified(proof))
            fixture.gate.onPrepareGameProfile(event)
            if (tamper == "uuid") event.gameProfile = event.gameProfile.copy(id = UUID.randomUUID())
            if (tamper == "name") event.gameProfile = event.gameProfile.copy(name = "WrongName")
            if (tamper != "skip-last") fixture.gate.onGameProfile(event)
            player.uniqueId = event.gameProfile.id
            player.username = event.gameProfile.name
            val login = LoginEvent(player, BedrockLogin.Verified(proof))
            fixture.gate.onLogin(login)
            assertFalse(login.result.isAllowed, tamper)
            assertFalse(fixture.gate.isAuthenticated(player), tamper)
        }
    }

    @Test
    fun `online Microsoft role is never kicked while native premium chooser selects its other free role`() {
        val fixture = Fixture(roleConfig)
        fixture.services.decision = AuthCoordinator.Decision.Admit(record.toIdentity(), record)
        val playingU = fixture.player(777)
        fixture.login(playingU)
        val chooser = fixture.player(777)
        fixture.strongBedrock(chooser)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        assertTrue(fixture.gate.isAuthenticated(chooser))
        assertTrue(fixture.gate.isAuthenticated(playingU))
        assertTrue(playingU.disconnects.isEmpty())
        assertTrue(chooser.disconnects.isEmpty())
    }

    @Test
    fun `ordinary Java Mojang session never reverse resolves an Xbox role`() {
        val fixture = Fixture(roleConfig)
        fixture.services.decision = AuthCoordinator.Decision.MojangSession(premiumUuid)
        val player = fixture.player(777).apply { uniqueId = premiumUuid; username = "Alex"; isOnlineMode = true }
        fixture.login(player)
        assertTrue(fixture.gate.isAuthenticated(player))
        assertEquals(0, fixture.services.roleCalls)
        assertEquals(0, fixture.services.beginCalls)
        assertEquals(0, fixture.services.grants)
        assertTrue(player.limbo.menus.isEmpty())
    }

    @Test
    fun `Floodgate chooser never displaces an already online native role`() {
        val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        fixture.services.decision = AuthCoordinator.Decision.MojangSession(premiumUuid)
        val existing = fixture.player(777).apply { uniqueId = premiumUuid; username = "Alex"; isOnlineMode = true }
        fixture.login(existing)
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val incoming = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
        val pre = PreLoginEvent(incoming, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
        fixture.gate.onPreLogin(pre)
        assertFalse(pre.result.isAllowed, "Refuse before native duplicate-login processing can displace the old role")
        assertTrue(existing.disconnects.isEmpty())
        assertTrue(fixture.gate.isAuthenticated(existing))
        assertFalse(fixture.gate.isAuthenticated(incoming))
    }

    @Test
    fun `Floodgate dual chooser preserves exact legal native profile but isolates a noncandidate name`() {
        for (candidate in listOf(true, false)) {
            val fixture = Fixture(roleConfig)
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, if (candidate) "Alex" else "OldAlex", "Steve", Any()) { true }
            fixture.services.options = fixture.dualRoles()
            val chooser = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
            fixture.login(chooser, bedrock = BedrockLogin.Verified(proof))
            assertEquals(proof.uniqueId, chooser.uniqueId, "Floodgate messaging must recognize the native UUID")
            if (candidate) assertEquals("Alex", chooser.username)
            else assertTrue(chooser.username.matches(Regex("KA[A-Fa-f0-9]{14}")))
            assertEquals(if (candidate) "Alex" else "OldAlex", proof.username)
            assertFalse(fixture.gate.isAuthenticated(chooser))
            fixture.assertBackendDenied(chooser)
            fixture.gate.onJoin(chooser.limbo)
            fixture.services.runWork()
            assertFalse(fixture.gate.isAuthenticated(chooser))
            fixture.assertBackendDenied(chooser)
            assertEquals(0, fixture.services.grants)
            assertEquals(0, fixture.services.bindCalls)
        }
    }

    @Test
    fun `Floodgate menus use fixed vanilla icons with private item reset and no preparation stage`() {
        val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        val player = fixture.joinBedrock()
        fixture.services.runWork()
        val menu = player.limbo.menus.single()
        assertEquals(RoleSelectorIcon.NAME_TAG, menu.microsoft.icon)
        assertEquals(RoleSelectorIcon.DIAMOND, menu.premium.icon)
        assertEquals("official-texture", menu.premium.textureValue)
        for (item in listOf(menu.microsoft, menu.premium)) {
            assertEquals("\u00a7r\u00a7f", item.name.text)
            assertEquals("\u00a7r\u00a77", item.lore.single().text)
        }
        assertFalse(menu.title.toString().contains('\u00a7'))
        assertFalse(fixture.gate.isAuthenticated(player))
        assertEquals(0, fixture.services.beginCalls)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `linked Floodgate proof selects Microsoft with new native proof and never grants a Java IP session`() {
        val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        fixture.services.options = fixture.dualRoles()
        var proofCurrent = true
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { proofCurrent }
        val chooser = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
        fixture.login(chooser, bedrock = BedrockLogin.Verified(proof))
        fixture.gate.onJoin(chooser.limbo)
        fixture.services.runWork()
        assertEquals(0, fixture.services.beginCalls)
        assertEquals(0, fixture.services.bindCalls)
        assertEquals(1, chooser.limbo.menus.size)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "microsoft")
        fixture.services.runWork()
        assertEquals(1, chooser.disconnects.size)
        assertFalse(fixture.gate.isAuthenticated(chooser))
        proofCurrent = false
        fixture.gate.onDisconnect(DisconnectEvent(chooser))
        chooser.isActive = false
        val fresh = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val resumed = fixture.player(777).apply {
            uniqueId = fresh.uniqueId
            username = fresh.username
            properties = listOf(GameProfile.Property("textures", "Floodgate-Java-skin", ""), GameProfile.Property("other", "keep", ""))
        }
        fixture.login(resumed, bedrock = BedrockLogin.Verified(fresh))
        assertTrue(fixture.gate.isAuthenticated(resumed))
        assertEquals(record.uuid, resumed.uniqueId)
        assertEquals("Steve", resumed.username)
        assertTrue(resumed.properties.none { it.name == "textures" })
        assertTrue(resumed.properties.any { it.name == "other" && it.value == "keep" })
        assertEquals(0, fixture.services.grants)
        assertEquals(0, fixture.services.bindCalls)
        assertEquals(0, fixture.services.premiumBinds)
    }

    @Test
    fun `unlinked first Floodgate role keeps its prefix without creating a Java binding`() {
        val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        val proof = BedrockProof.create(Xuid(1L), record.uuid, ".Steve", "Steve", ".", Any()) { true }
        fixture.services.options = RoleOptions(listOf(RoleOption(proof.identity, null, null)), null)
        val chooser = fixture.player(777).apply { username = proof.username }
        fixture.login(chooser, bedrock = BedrockLogin.Verified(proof))
        assertTrue(chooser.limbo.menus.isEmpty())
        assertTrue(chooser.disconnects.isEmpty())
        assertTrue(fixture.gate.isAuthenticated(chooser))
        assertEquals(".Steve", chooser.username)
        assertEquals(record.uuid, chooser.uniqueId)
        assertEquals(0, fixture.services.bindCalls)
        assertEquals(0, fixture.services.grants)
        assertEquals(0, fixture.services.premiumBinds)
    }

    @Test
    fun `Floodgate hint cannot bypass a missing stale contradictory or expired new channel proof`() {
        for (failure in listOf("missing", "stale", "unlink", "expire")) {
            val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
            fixture.services.options = fixture.dualRoles()
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
            val chooser = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
            fixture.login(chooser, bedrock = BedrockLogin.Verified(proof))
            fixture.gate.onJoin(chooser.limbo)
            fixture.services.runWork()
            fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "microsoft")
            fixture.services.runWork()
            chooser.isActive = false
            if (failure == "expire") fixture.now += 31_000L
            val fresh = if (failure == "unlink")
                BedrockProof.create(Xuid(1L), record.uuid, ".Steve", "Steve", ".", Any()) { true }
            else BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { failure != "stale" }
            val resumed = fixture.player(777).apply { uniqueId = fresh.uniqueId; username = fresh.username }
            if (failure == "missing") {
                val pre = PreLoginEvent(resumed, "Alex", premiumUuid)
                fixture.services.decision = AuthCoordinator.Decision.Verify(record.uuid, AuthCoordinator.Reason.SESSION_EXPIRED, false, record)
                fixture.gate.onPreLogin(pre)
                assertTrue(pre.result.isAllowed)
                assertFalse(fixture.gate.isAuthenticated(resumed))
                assertEquals(0, fixture.services.premiumBinds)
            } else {
                val pre = PreLoginEvent(resumed, fresh.username, fresh.uniqueId, BedrockLogin.Verified(fresh))
                fixture.gate.onPreLogin(pre)
                assertFalse(pre.result.isAllowed, failure)
            }
            assertEquals(0, fixture.services.grants, failure)
            assertEquals(0, fixture.services.premiumBinds, failure)
        }
    }

    @Test
    fun `unavailable public profile at final login stays denied with a retry message rather than internal error`() {
        val fixture = Fixture(roleConfig)
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.last()), premiumUuid)
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val resumed = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
        val pre = PreLoginEvent(resumed, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
        fixture.gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        val profile = GameProfileRequestEvent(resumed, GameProfile(proof.uniqueId, proof.username, emptyList()), BedrockLogin.Verified(proof))
        fixture.gate.onPrepareGameProfile(profile)
        fixture.gate.onGameProfile(profile)
        resumed.uniqueId = profile.gameProfile.id
        resumed.username = profile.gameProfile.name
        fixture.services.roleFailure = moe.ryuu.kyokouauth.core.auth.OfficialJavaProfileUnavailableException()
        val login = LoginEvent(resumed, BedrockLogin.Verified(proof))
        fixture.gate.onLogin(login)
        assertFalse(login.result.isAllowed)
        assertEquals("Role unavailable » Reconnect and try again", login.result.reason?.plainText)
        assertFalse(fixture.gate.isAuthenticated(resumed))
        assertEquals(0, fixture.services.premiumBinds)
        fixture.assertBackendDenied(resumed)
    }

    @Test
    fun `role login rechecks relationship after final profile before claiming premium`() {
        val fixture = Fixture(roleConfig)
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.last()), premiumUuid)
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val resumed = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
        val pre = PreLoginEvent(resumed, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
        fixture.gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        val profile = GameProfileRequestEvent(resumed, GameProfile(proof.uniqueId, proof.username, emptyList()), BedrockLogin.Verified(proof))
        fixture.gate.onPrepareGameProfile(profile)
        fixture.gate.onGameProfile(profile)
        resumed.uniqueId = profile.gameProfile.id
        resumed.username = profile.gameProfile.name
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first()), null)
        val login = LoginEvent(resumed, BedrockLogin.Verified(proof))
        fixture.gate.onLogin(login)
        assertFalse(login.result.isAllowed)
        assertFalse(fixture.gate.isAuthenticated(resumed))
        assertEquals(0, fixture.services.premiumBinds)
    }

    @Test
    fun `choosing an online role refuses this chooser without kicking that role`() {
        val fixture = Fixture(roleConfig)
        val occupied = fixture.player(777)
        val chooser = fixture.player(777)
        fixture.strongBedrock(chooser)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "microsoft")
        fixture.services.runWork()
        assertTrue(chooser.requests.isEmpty())
        assertTrue(chooser.disconnects.isNotEmpty())
        assertTrue(occupied.disconnects.isEmpty())
    }

    @Test
    fun `role resolution wrong Xbox subject and native relationship mismatch fail closed`() {
        for (failure in listOf("xuid", "native-link")) {
            val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
            fixture.services.options = if (failure == "xuid") RoleOptions(listOf(
                RoleOption(Identity.Microsoft(record.name, Xuid(2L), "Other"), null, null)), premiumUuid)
            else RoleOptions(listOf(RoleOption(record.toIdentity(), record, null)), null)
            val chooser = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
            val pre = PreLoginEvent(chooser, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
            fixture.gate.onPreLogin(pre)
            assertFalse(pre.result.isAllowed, failure)
            assertTrue(chooser.requests.isEmpty(), failure)
            assertFalse(fixture.gate.isAuthenticated(chooser), failure)
        }
    }

    @Test
    fun `only linked premium role auto selects and is first bound only at final native admission`() {
        val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.single { it.identity is Identity.Premium }), premiumUuid)
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val chooser = fixture.player(777).apply { uniqueId = premiumUuid; username = "Alex" }
        val pre = PreLoginEvent(chooser, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
        fixture.gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        assertEquals(0, fixture.services.premiumBinds, "PreLogin resolves but cannot claim a premium binding")
        val profile = GameProfileRequestEvent(chooser, GameProfile(proof.uniqueId, proof.username, emptyList()), BedrockLogin.Verified(proof))
        fixture.gate.onPrepareGameProfile(profile)
        fixture.gate.onGameProfile(profile)
        chooser.uniqueId = profile.gameProfile.id
        chooser.username = profile.gameProfile.name
        val login = LoginEvent(chooser, BedrockLogin.Verified(proof))
        fixture.gate.onLogin(login)
        assertTrue(login.result.isAllowed)
        assertTrue(chooser.limbo.menus.isEmpty())
        assertTrue(chooser.disconnects.isEmpty())
        assertTrue(fixture.gate.isAuthenticated(chooser))
        assertEquals(premiumUuid, chooser.uniqueId)
        assertEquals("Alex", chooser.username)
        assertEquals(1, fixture.services.premiumBinds)
        assertEquals(0, fixture.services.bindCalls)
        assertEquals(0, fixture.services.grants)
    }

    @Test
    fun `each later ordinary Floodgate login proves XUID again and reopens both role choices`() {
        val fixture = Fixture(roleConfig)
        val first = fixture.player(777)
        fixture.strongBedrock(first)
        fixture.gate.onRoleSelect(first.limbo, first.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        assertTrue(fixture.gate.isAuthenticated(first))
        first.isActive = false
        fixture.gate.onDisconnect(DisconnectEvent(first))
        val next = fixture.player(777)
        fixture.strongBedrock(next)
        assertFalse(fixture.gate.isAuthenticated(next))
        assertEquals(1, next.limbo.menus.size)
        assertEquals(0, fixture.services.beginCalls)
        assertEquals(0, fixture.services.grants)
    }

    @Test
    fun `failed native property application never authorizes its frontend or reaches backend`() {
        val fixture = Fixture(roleConfig)
        val chooser = fixture.player(777)
        chooser.profileResult = CompletableFuture.failedFuture(IllegalStateException("write failed"))
        fixture.strongBedrock(chooser)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        assertFalse(fixture.gate.isAuthenticated(chooser))
        assertTrue(chooser.disconnects.isNotEmpty())
        assertEquals(0, fixture.services.premiumBinds)
        fixture.assertBackendDenied(chooser)
    }

    @Test
    fun `canonical role properties cover held applying and selected snapshots while unmanaged properties survive`() {
        val fixture = Fixture(roleConfig)
        var current = true
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { current }
        val player = fixture.player(777).apply {
            uniqueId = proof.uniqueId; username = proof.username
            properties = listOf(GameProfile.Property("textures", "untrusted", ""), GameProfile.Property("other", "keep", ""))
        }
        fixture.services.options = fixture.dualRoles()
        fixture.login(player, bedrock = BedrockLogin.Verified(proof))
        fixture.gate.onJoin(player.limbo)
        fixture.services.runWork()
        assertTrue(fixture.gate.requiresRoleProfile(player))
        val held = checkNotNull(fixture.gate.currentRoleProfile(player))
        assertEquals(premiumUuid, held.id)
        assertEquals("Alex", held.name)
        assertEquals("official-texture", held.properties.single { it.name == "textures" }.value)
        assertFalse(fixture.gate.isAuthenticated(player))
        val changedSkin = GameProfile.Property("textures", "new-official-texture", "new-signature")
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first(), fixture.dualRoles().roles.last().copy(skin = changedSkin)), premiumUuid)
        player.profileResult = CompletableFuture()
        fixture.gate.onRoleSelect(player.limbo, player.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        val applying = checkNotNull(fixture.gate.currentRoleProfile(player))
        assertEquals(listOf(changedSkin), applying.properties.filter { it.name == "textures" })
        fixture.assertBackendDenied(player)
        player.profileResult.complete(Unit)
        fixture.services.runWork()
        assertTrue(fixture.gate.isAuthenticated(player))
        assertEquals(listOf(changedSkin), player.properties.filter { it.name == "textures" })
        assertTrue(player.properties.any { it.name == "other" && it.value == "keep" })
        val selected = checkNotNull(fixture.gate.currentRoleProfile(player))
        assertEquals(player.sessionId.toString(), selected.properties.single { it.name == LoginGate.CONNECTION_PROPERTY }.value)
        assertEquals(listOf(changedSkin), selected.properties.filter { it.name == "textures" })
        val foreign = fixture.player(777, player.sessionId).apply { uniqueId = player.uniqueId; username = player.username }
        assertEquals(null, fixture.gate.currentRoleProfile(foreign))
        current = false
        assertTrue(fixture.gate.requiresRoleProfile(player), "Late native guards must refuse a null required profile")
        assertEquals(null, fixture.gate.currentRoleProfile(player))
        assertFalse(fixture.gate.isAuthenticated(player))
    }

    @Test
    fun `late native property callbacks cannot authorize after disconnect stop deadline proof link or name changes`() {
        for (ending in listOf("disconnect", "stop", "deadline", "proof", "link", "name", "record")) {
            val fixture = Fixture(roleConfig + ("role-selection.timeout-seconds" to "5"))
            var current = true
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { current }
            val player = fixture.joinBedrock(proof = proof)
            fixture.services.runWork()
            player.profileResult = CompletableFuture()
            fixture.gate.onRoleSelect(player.limbo, player.limbo.menus.single().generation, "premium")
            fixture.services.runWork()
            assertFalse(fixture.gate.isAuthenticated(player))
            when (ending) {
                "disconnect" -> fixture.gate.onDisconnect(DisconnectEvent(player))
                "stop" -> fixture.gate.deactivate()
                "deadline" -> fixture.now += 5_000
                "proof" -> current = false
                "link" -> fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first()), null)
                "name" -> fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first(),
                    fixture.dualRoles().roles.last().copy(identity = Identity.Premium(GameName.parseOrThrow("AlexNew"), premiumUuid))), premiumUuid)
                "record" -> fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first(),
                    fixture.dualRoles().roles.last().copy(existing = BoundIdentity.of(Identity.Premium(GameName.parseOrThrow("Alex"), premiumUuid), 99L))), premiumUuid)
            }
            player.profileResult.complete(Unit)
            fixture.services.runWork()
            assertFalse(fixture.gate.isAuthenticated(player), ending)
            assertTrue(player.requests.isEmpty(), ending)
            assertEquals(0, fixture.services.grants, ending)
        }
    }

    @Test
    fun `late property completion and old timers cannot authorize or clean a replacement same UUID session`() {
        val fixture = Fixture(roleConfig)
        val old = fixture.joinBedrock()
        fixture.services.runWork()
        old.profileResult = CompletableFuture()
        fixture.gate.onRoleSelect(old.limbo, old.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        val timers = fixture.services.timers.toList()
        fixture.gate.onDisconnect(DisconnectEvent(old))
        old.isActive = false
        val next = fixture.player(777, old.sessionId)
        fixture.strongBedrock(next)
        old.profileResult.complete(Unit)
        timers.forEach { it.runEvenIfCancelled() }
        fixture.gate.onDisconnect(DisconnectEvent(old))
        fixture.services.runWork()
        assertFalse(fixture.gate.isAuthenticated(next))
        assertTrue(next.disconnects.isEmpty())
        assertTrue(old.requests.isEmpty())
        fixture.gate.onRoleSelect(next.limbo, next.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        assertTrue(fixture.gate.isAuthenticated(next))
    }

    @Test
    fun `pending role UUID and case insensitive names are reserved atomically before native registration`() {
        for (collision in listOf("uuid", "name")) {
            val fixture = Fixture(roleConfig)
            val first = fixture.inbound()
            val firstProof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
            fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.last()), premiumUuid)
            val firstPre = PreLoginEvent(first, firstProof.username, firstProof.uniqueId, BedrockLogin.Verified(firstProof))
            fixture.gate.onPreLogin(firstPre)
            assertTrue(firstPre.result.isAllowed)
            val second = fixture.inbound()
            val otherUuid = if (collision == "uuid") premiumUuid else UUID.randomUUID()
            val otherName = if (collision == "name") "aLeX" else "OtherName"
            val otherProof = BedrockProof.createLinked(Xuid(2L), otherUuid, otherName, "Other", Any()) { true }
            fixture.services.options = RoleOptions(listOf(RoleOption(Identity.Premium(GameName.parseOrThrow(otherName), otherUuid), null, null)), otherUuid)
            val blocked = PreLoginEvent(second, otherName, otherUuid, BedrockLogin.Verified(otherProof))
            fixture.gate.onPreLogin(blocked)
            assertFalse(blocked.result.isAllowed, collision)
            val invalidProfile = GameProfileRequestEvent(first, GameProfile(UUID.randomUUID(), "Wrong", emptyList()), BedrockLogin.Verified(firstProof))
            fixture.gate.onGameProfile(invalidProfile)
            val retry = PreLoginEvent(second, otherName, otherUuid, BedrockLogin.Verified(otherProof))
            fixture.gate.onPreLogin(retry)
            assertTrue(retry.result.isAllowed, "Rejection must release only the first reservation: $collision")
        }
    }

    @Test
    fun `expired pending role reservations are reclaimed before the next native login`() {
        val fixture = Fixture(roleConfig)
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.last()), premiumUuid)
        fun proof() = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val first = fixture.inbound()
        fixture.gate.onPreLogin(PreLoginEvent(first, "Alex", premiumUuid, BedrockLogin.Verified(proof())))
        fixture.now += 300_000L
        val next = fixture.inbound()
        val pre = PreLoginEvent(next, "Alex", premiumUuid, BedrockLogin.Verified(proof()))
        fixture.gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
    }

    @Test
    fun `single native profile proof revocation before login cannot acquire selected authorization`() {
        val fixture = Fixture(roleConfig)
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.last()), premiumUuid)
        var current = true
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { current }
        val player = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
        val pre = PreLoginEvent(player, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
        fixture.gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        val profile = GameProfileRequestEvent(player, GameProfile(proof.uniqueId, proof.username, emptyList()), BedrockLogin.Verified(proof))
        fixture.gate.onPrepareGameProfile(profile)
        fixture.gate.onGameProfile(profile)
        current = false
        assertTrue(fixture.gate.requiresRoleProfile(player))
        assertEquals(null, fixture.gate.currentRoleProfile(player))
        val login = LoginEvent(player, BedrockLogin.Verified(proof))
        fixture.gate.onLogin(login)
        assertFalse(login.result.isAllowed)
        assertFalse(fixture.gate.isAuthenticated(player))
    }

    @Test
    fun `configured commands stay blocked until this exact connection is authenticated`() {
        val fixture = Fixture(mapOf("verification.blocked-commands" to "/SERVER\nexample:Msg"))
        val player = fixture.player(777)
        assertTrue(fixture.gate.isCommandBlocked(null, "server lobby"))
        assertTrue(fixture.gate.isCommandBlocked(player, "server lobby"))
        fixture.join(player)
        for (command in listOf("server", "/SERVER lobby", "velocity:server lobby", "msg Steve hello")) {
            assertTrue(fixture.gate.isCommandBlocked(player, command), command)
        }
        assertFalse(fixture.gate.isCommandBlocked(player, "help"))
        assertFalse(fixture.gate.allowCommand(player, "server lobby"))
        assertTrue(player.messages.isNotEmpty())

        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.services.advanceBy(1_000L)
        assertEquals(1, fixture.services.grants)
        assertFalse(fixture.gate.isCommandBlocked(player, "server lobby"))
        assertTrue(fixture.gate.allowCommand(player, "msg Steve hello"))
        val impostor = fixture.player(777, player.sessionId)
        assertTrue(fixture.gate.isCommandBlocked(impostor, "server lobby"))
        fixture.gate.deactivate()
        assertTrue(fixture.gate.isCommandBlocked(player, "server lobby"))
    }

    @Test
    fun `blocked command admission remains denied when feedback transport fails`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        player.throwOnMessage = true
        assertFalse(fixture.gate.allowCommand(player, "server lobby"))
        assertTrue(player.requests.isEmpty())
        assertEquals(0, fixture.services.grants)
    }

    @Test
    fun `wildcard ends at exact authentication while an empty list never disables backend isolation`() {
        val all = Fixture(mapOf("verification.blocked-commands" to "*"))
        val player = all.player(777)
        all.join(player)
        for (command in listOf("help", "server lobby", "example:msg Steve hello", "//wand")) {
            assertTrue(all.gate.isCommandBlocked(player, command), command)
        }
        all.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        all.services.advanceBy(1_000L)
        assertFalse(all.gate.isCommandBlocked(player, "help"))
        player.isActive = false
        assertTrue(all.gate.isCommandBlocked(player, "help"))

        val none = Fixture(mapOf("verification.blocked-commands" to ""))
        val held = none.player(777)
        none.join(held)
        assertTrue(none.gate.allowCommand(held, "server lobby"))
        val request = ServerPreConnectEvent(held, none.lobby)
        none.gate.onPreConnect(request)
        assertTrue(request.result.server.isEmpty)
        assertEquals(0, none.services.grants)
    }

    @Test
    fun `every supported protocol permits only its exact registered limbo target`() {
        for (version in 767..777) {
            val fixture = Fixture()
            val player = fixture.player(version)
            fixture.login(player)
            val expected = fixture.servers.getValue(limboName(version))
            val choose = PlayerChooseInitialServerEvent(player)
            fixture.gate.onChooseInitialServer(choose)
            assertSame(expected, choose.initialServer, "protocol $version")

            val candidates = fixture.servers.values + RegisteredServer(expected.name)
            for (target in candidates) {
                val request = ServerPreConnectEvent(player, expected)
                request.result = ServerPreConnectEvent.ServerResult(Optional.of(target))
                fixture.gate.onPreConnect(request)
                if (target === expected) {
                    assertSame(expected, request.result.server.orElse(null))
                } else {
                    assertTrue(request.result.server.isEmpty,
                        "protocol $version must reject the final target ${target.name}, including a same-name impostor")
                }
            }
            assertTrue(player.disconnects.isEmpty())
            assertTrue(fixture.services.work.isEmpty())
        }
    }

    @Test
    fun `unsupported protocols are rejected even when legacy and matching named servers exist`() {
        for (version in listOf(-1, 766, 778, Int.MAX_VALUE)) {
            val fixture = Fixture()
            fixture.servers[limboName(version)] = RegisteredServer(limboName(version))
            val player = fixture.player(version)
            fixture.login(player)
            for (target in fixture.servers.values) {
                val request = ServerPreConnectEvent(player, target)
                fixture.gate.onPreConnect(request)
                assertTrue(request.result.server.isEmpty, "An unsupported held player cannot enter ${target.name}")
            }
            val choose = PlayerChooseInitialServerEvent(player)
            choose.setInitialServer(fixture.lobby)
            fixture.gate.onChooseInitialServer(choose)
            assertEquals(null, choose.initialServer, "protocol $version must not inherit a fallback")
            assertEquals(1, player.disconnects.size)
            assertEquals(0, fixture.services.beginCalls)
            assertTrue(fixture.services.work.isEmpty())
            assertTrue(fixture.services.timers.all { it.isDone })
        }
    }

    @Test
    fun `missing exact limbo registrations never fall back across older supported protocols`() {
        for (version in 767..776) {
            val fixture = Fixture()
            fixture.servers.remove(limboName(version))
            val player = fixture.player(version)
            fixture.login(player)
            val legacy = ServerPreConnectEvent(player, fixture.servers.getValue(LoginGate.LIMBO_SERVER_NAME))
            fixture.gate.onPreConnect(legacy)
            assertTrue(legacy.result.server.isEmpty, "protocol $version cannot enter the legacy 777 limbo")
            val choose = PlayerChooseInitialServerEvent(player)
            choose.setInitialServer(fixture.lobby)
            fixture.gate.onChooseInitialServer(choose)
            assertEquals(null, choose.initialServer)
            assertEquals(1, player.disconnects.size)
        }
    }

    @Test
    fun `protocol 777 retains the legacy alias only when its exact registration is absent`() {
        val fixture = Fixture()
        fixture.servers.remove(limboName(777))
        val player = fixture.player(777)
        fixture.login(player)
        val legacy = fixture.servers.getValue(LoginGate.LIMBO_SERVER_NAME)
        val choose = PlayerChooseInitialServerEvent(player)
        fixture.gate.onChooseInitialServer(choose)
        assertSame(legacy, choose.initialServer)
        val request = ServerPreConnectEvent(player, legacy)
        fixture.gate.onPreConnect(request)
        assertSame(legacy, request.result.server.orElse(null))
    }

    @Test
    fun `successful verification skips all protocol limbos when selecting the real backend`() {
        for (version in listOf(767, 777)) {
            val fixture = Fixture(mapOf("post-login.server" to limboName(768)))
            val player = fixture.player(version)
            fixture.join(player)
            fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            fixture.services.advanceBy(1_000L)
            assertEquals(listOf(fixture.lobby), player.requests)
            assertEquals(1, fixture.services.grants)
            assertEquals(1, player.limbo.clearCount)
            assertEquals(0, fixture.gate.verifyingCount())
            assertTrue(player.disconnects.isEmpty())
        }
    }

    @Test
    fun `queued restart captures the exact player instead of following its UUID to a replacement`() {
        for (reuseSessionId in listOf(false, true)) {
            val fixture = Fixture()
            val old = fixture.player(767)
            fixture.join(old)
            val captured = fixture.proxy.getPlayer(old.uniqueId).orElseThrow()
            var restarted: Boolean? = null
            fixture.services.submit { restarted = fixture.gate.restartVerification(captured) }
            old.isActive = false
            fixture.gate.onDisconnect(DisconnectEvent(old))
            val replacement = fixture.player(777, if (reuseSessionId) old.sessionId else UUID.randomUUID())
            fixture.login(replacement)
            fixture.gate.onJoin(replacement.limbo)
            fixture.services.runWork()

            assertEquals(false, restarted)
            assertEquals(old.uniqueId, replacement.uniqueId)
            assertEquals(2, fixture.services.beginCalls, "Only the two real arrivals may request a challenge")
            assertEquals(1, old.limbo.clearCount)
            assertEquals(1, replacement.limbo.titleCount)
            assertEquals(0, replacement.limbo.clearCount)
            assertTrue(replacement.disconnects.isEmpty())
            assertEquals(1, fixture.gate.verifyingCount())
        }
    }

    @Test
    fun `stale leave disconnect and timer callbacks cannot clear a replacement session`() {
        val fixture = Fixture()
        val old = fixture.player(767)
        fixture.join(old)
        val oldTimers = fixture.services.timers.toList()
        old.isActive = false
        fixture.gate.onDisconnect(DisconnectEvent(old))
        val replacement = fixture.player(777, old.sessionId)
        fixture.join(replacement)

        fixture.gate.onLeave(old.limbo)
        fixture.gate.onDisconnect(DisconnectEvent(old))
        oldTimers.forEach { it.runEvenIfCancelled() }
        fixture.services.runWork()
        fixture.services.advanceBy(9_000L)

        assertEquals(1, old.limbo.clearCount)
        assertEquals(1, replacement.limbo.titleCount)
        assertEquals(0, replacement.limbo.clearCount)
        assertTrue(replacement.disconnects.isEmpty())
        assertTrue(replacement.requests.isEmpty())
        assertEquals(1, fixture.gate.verifyingCount())
        fixture.gate.onLeave(replacement.limbo)
        assertEquals(1, replacement.limbo.clearCount)
        assertEquals(0, fixture.gate.verifyingCount())
    }

    @Test
    fun `poll cadence honors both the production default and the upstream challenge minimum`() {
        for ((configured, upstream, expected) in listOf(Triple(null, 1, 5_000L), Triple("2", 7, 7_000L))) {
            val fixture = Fixture(configured?.let { mapOf("verification.poll-interval-seconds" to it) } ?: emptyMap(),
                fastPolling = false)
            fixture.services.challengeIntervalSeconds = upstream
            val player = fixture.player(777)
            fixture.join(player)
            fixture.services.advanceBy(expected - 1)
            assertEquals(0, fixture.services.pollCalls)
            fixture.services.advanceBy(1)
            assertEquals(1, fixture.services.pollCalls)
            assertEquals(1, player.limbo.titleCount)
            assertEquals(0, player.limbo.clearCount)
        }
    }

    @Test
    fun `latency ready prompt work consumes only the remaining response anchored interval`() {
        val fixture = Fixture(fastPolling = false)
        val player = fixture.player(777)
        val challengeReceivedAt = fixture.now
        player.limbo.duringReadyPrompt = { fixture.now += 3_000 }
        fixture.join(player)
        assertEquals(challengeReceivedAt + 5_000, fixture.services.timers.last().dueAt,
            "Three seconds spent sending the prompt must not become three extra seconds before polling")
        fixture.services.advanceBy(1_999)
        assertEquals(0, fixture.services.pollCalls)
        fixture.services.advanceBy(1)
        assertEquals(listOf(challengeReceivedAt + 5_000), fixture.services.pollRequestsAt)
        assertEquals(1, player.limbo.titleCount)
    }

    @Test
    fun `latency retry uses response anchor but never subtracts network RTT or Retry After`() {
        for ((failureCode, status, retryAfter) in listOf(
            Triple(DeviceCodeFlow.FailureCode.TRANSPORT, null, null),
            Triple(DeviceCodeFlow.FailureCode.RATE_LIMITED, 429, 12_000L),
            Triple(DeviceCodeFlow.FailureCode.UPSTREAM_UNAVAILABLE, 503, 12_000L))) {
            val fixture = Fixture(fastPolling = false)
            val player = fixture.player(777)
            fixture.join(player)
            fixture.services.pollFailure = DeviceCodeFlow.DeviceCodeException(failureCode, status, retryAfter)
            fixture.services.duringPoll = { fixture.now += 4_000 }
            fixture.duringLog = { if (it.startsWith("Microsoft sign-in poll")) fixture.now += 2_000 }
            fixture.services.timers.last().let { timer -> fixture.now = timer.dueAt; timer.run() }
            fixture.services.runWork()
            val responseReceivedAt = fixture.services.pollResponsesAt.single()
            val minimum = retryAfter ?: 5_000L
            assertEquals(responseReceivedAt + minimum, fixture.services.timers.last().dueAt,
                "Only two seconds of local warning logging may be removed; never the four second HTTP request")
            assertEquals(4_000L, responseReceivedAt - fixture.services.pollRequestsAt.single())
            fixture.services.pollFailure = null
            fixture.services.duringPoll = {}
            fixture.duringLog = {}
            fixture.services.advanceBy(minimum - 2_000)
            assertEquals(listOf(fixture.services.pollRequestsAt.first(), responseReceivedAt + minimum),
                fixture.services.pollRequestsAt)
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `latency local work exceeding the interval schedules immediately without losing the next poll`() {
        val fixture = Fixture(fastPolling = false)
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.pollFailure = DeviceCodeFlow.DeviceCodeException(DeviceCodeFlow.FailureCode.TRANSPORT)
        fixture.duringLog = {
            if (it.startsWith("Microsoft sign-in poll")) {
                fixture.now += 6_000
                fixture.services.pollFailure = null
                fixture.duringLog = {}
            }
        }
        fixture.services.timers.last().let { timer -> fixture.now = timer.dueAt; timer.run() }
        fixture.services.runWork()
        assertEquals(fixture.now, fixture.services.timers.last().dueAt)
        fixture.services.advanceBy(0)
        assertEquals(2, fixture.services.pollCalls, "A zero-delay follow-up must run after the old poll releases busy")
        assertTrue(fixture.services.pollRequestsAt[1] - fixture.services.pollResponsesAt[0] >= 5_000)
        assertEquals(0, fixture.services.grants)
        assertEquals(1, fixture.gate.verifyingCount())
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `confirmation starts immediately after a real token and timing separates waiting HTTP Xbox and binding`() {
        val fixture = Fixture(fastPolling = false)
        val player = fixture.player(777)
        fixture.services.duringBegin = { fixture.now += 125 }
        fixture.services.duringPoll = { fixture.now += 400 }
        fixture.services.duringExchange = {
            assertEquals(1, player.limbo.messages.count { it.toString().contains("confirming your Xbox identity") })
            assertEquals(fixture.services.pollResponsesAt.single(), fixture.services.exchangeRequestsAt.single(),
                "A successful token must go straight to Xbox without another interval or queue hop")
            fixture.assertBackendDenied(player)
            fixture.now += 1_200
        }
        fixture.services.duringBind = { fixture.now += 300 }
        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.join(player)
        assertFalse(player.limbo.messages.any { it.toString().contains("confirming your Xbox identity") })
        fixture.services.timers.last().let { timer -> fixture.now = timer.dueAt; timer.run() }
        fixture.now += 700 // The timer fired, but the worker was still queued.
        fixture.services.runWork()
        assertEquals(1, fixture.services.grants)
        assertEquals(listOf(fixture.lobby), player.requests)
        assertEquals(1, player.limbo.clearCount)
        val timing = fixture.logs.single { it.startsWith("Microsoft verification timing:") }
        assertEquals("Microsoft verification timing: deviceCodeMs=125 pollRequests=1 pollHttpMs=400 pollWaitMs=5700 pollQueuedMs=700 xboxMs=1200 completionMs=300",
            timing)
        assertFalse(listOf("test-token", "code-1", "device-1", "Steve", "https://", record.uuid.toString()).any { it in timing })
    }

    @Test
    fun `pending declined expired and stale authorized polls never promise Xbox confirmation`() {
        for (result in listOf(DeviceCodeFlow.PollResult.Pending, DeviceCodeFlow.PollResult.Declined,
            DeviceCodeFlow.PollResult.Expired, DeviceCodeFlow.PollResult.Authorized("test-token", null, 300))) {
            val fixture = Fixture()
            val player = fixture.player(777)
            fixture.services.pollResult = result
            if (result is DeviceCodeFlow.PollResult.Authorized) fixture.services.duringPoll = { fixture.now += 300_000 }
            fixture.join(player)
            fixture.services.advanceBy(1_000)
            assertFalse(player.limbo.messages.any { it.toString().contains("confirming your Xbox identity") })
            assertEquals(0, fixture.services.exchangeCalls)
            assertEquals(0, fixture.services.grants)
            assertFalse(fixture.logs.any { it.startsWith("Microsoft verification timing:") })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `failed or deadline crossing confirmation IO still fails closed before Xbox work`() {
        for (throwFailure in listOf(false, true)) {
            val fixture = Fixture(mapOf("verification.timeout-seconds" to "2"))
            val player = fixture.player(777)
            fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            fixture.join(player)
            player.limbo.duringMessage = {
                if (it.toString().contains("confirming your Xbox identity")) {
                    if (throwFailure) throw java.io.IOException("Synthetic closed chat channel")
                    fixture.now += 2_000
                }
            }
            fixture.services.advanceBy(1_000)
            assertEquals(1, player.disconnects.size)
            assertEquals(1, player.limbo.clearCount)
            assertEquals(0, fixture.services.exchangeCalls)
            assertEquals(0, fixture.services.bindCalls)
            assertEquals(0, fixture.services.grants)
            assertTrue(fixture.services.timers.all { it.isDone })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `duplicate poll callback during a request cannot start another request or confirmation`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        val timer = fixture.services.timers.last()
        fixture.services.duringPoll = {
            fixture.services.duringPoll = {}
            timer.runEvenIfCancelled()
            fixture.services.runWork()
            assertEquals(1, fixture.services.pollCalls)
        }
        fixture.services.advanceBy(1_000)
        assertEquals(1, fixture.services.pollCalls)
        assertEquals(0, fixture.services.exchangeCalls)
        fixture.services.advanceBy(1_000)
        assertEquals(2, fixture.services.pollCalls)
        assertFalse(player.limbo.messages.any { it.toString().contains("confirming your Xbox identity") })
    }

    @Test
    fun `consecutive slowdown uses exact five second increments without exponential waiting`() {
        for ((upstream, intervals) in listOf(
            5 to listOf(5_000L, 10_000L, 15_000L, 20_000L),
            90 to listOf(90_000L, 95_000L, 100_000L))) {
            val fixture = Fixture(mapOf("verification.timeout-seconds" to "600"), fastPolling = false)
            val player = fixture.player(777)
            fixture.services.challengeIntervalSeconds = upstream
            fixture.services.pollResult = DeviceCodeFlow.PollResult.SlowDown
            fixture.join(player)
            val readyAt = fixture.now
            for ((index, interval) in intervals.withIndex()) {
                assertEquals(fixture.now + interval, fixture.services.timers.last().dueAt,
                    "slow_down adds exactly five seconds, not an unnecessary exponential delay")
                fixture.services.advanceBy(interval - 1)
                assertEquals(index, fixture.services.pollCalls, "Never poll below the upstream minimum")
                if (index == intervals.lastIndex) {
                    fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
                }
                fixture.services.advanceBy(1)
                assertEquals(index + 1, fixture.services.pollCalls)
            }
            assertEquals(readyAt + intervals.sum(), fixture.now)
            assertEquals(1, fixture.services.grants)
            assertEquals(listOf(fixture.lobby), player.requests)
            assertEquals(1, player.limbo.titleCount)
            assertEquals(1, player.limbo.clearCount)
            assertTrue(player.disconnects.isEmpty())
        }
    }

    @Test
    fun `every slowdown adds at least five seconds even after sixty second backoff ceiling`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.services.challengeIntervalSeconds = 60
        fixture.services.pollResult = DeviceCodeFlow.PollResult.SlowDown
        fixture.join(player)
        fixture.services.advanceBy(60_000)
        assertEquals(1, fixture.services.pollCalls)
        fixture.services.advanceBy(64_999)
        assertEquals(1, fixture.services.pollCalls)
        fixture.services.advanceBy(1)
        assertEquals(2, fixture.services.pollCalls)
        assertEquals(fixture.now + 70_000, fixture.services.timers.last().dueAt)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `ready prompt sends the complete clickable URL once and successful callbacks are idempotent`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        val initialMessages = player.limbo.messages.toList()
        assertTrue(initialMessages.flatMap { it.children }.any {
            it.click == TextComponent.Click.OpenUrl("https://example.invalid?otc=code-1")
        })
        fixture.services.advanceBy(9_000)
        assertEquals(initialMessages, player.limbo.messages)
        assertEquals(1, player.limbo.titleCount)
        val timers = fixture.services.timers.toList()
        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.services.advanceBy(1_000)
        timers.forEach { it.runEvenIfCancelled() }
        fixture.services.runWork()
        fixture.gate.onLeave(player.limbo)
        assertEquals(1, fixture.services.bindCalls)
        assertEquals(1, fixture.services.grants)
        assertEquals(1, player.limbo.clearCount)
        assertEquals(listOf(fixture.lobby), player.requests)
        assertTrue(player.disconnects.isEmpty())
    }

    @Test
    fun `slowdown and retry after never reduce a challenge or configured minimum`() {
        for (upstream in listOf(1, 90)) {
            val fixture = Fixture()
            fixture.services.challengeIntervalSeconds = upstream
            fixture.services.pollResult = DeviceCodeFlow.PollResult.SlowDown
            val player = fixture.player(777)
            fixture.join(player)
            fixture.services.advanceBy(upstream * 1000L)
            assertEquals(1, fixture.services.pollCalls)
            val next = if (upstream == 1) 6_000L else 95_000L
            fixture.services.advanceBy(next - 1)
            assertEquals(1, fixture.services.pollCalls)
            fixture.services.advanceBy(1)
            assertEquals(2, fixture.services.pollCalls)
            assertEquals(1, player.limbo.titleCount)
        }
        val fixture = Fixture(mapOf("verification.poll-interval-seconds" to "7"))
        fixture.services.pollFailure = DeviceCodeFlow.DeviceCodeException(
            DeviceCodeFlow.FailureCode.RATE_LIMITED, 429, 12_000L)
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.advanceBy(7_000)
        fixture.services.pollFailure = null
        fixture.services.advanceBy(11_999)
        assertEquals(1, fixture.services.pollCalls)
        fixture.services.advanceBy(1)
        assertEquals(2, fixture.services.pollCalls)
        assertEquals(1, player.limbo.titleCount)
    }

    @Test
    fun `worker and scheduler startup rejection immediately close the captured frontend`() {
        for (rejectWorker in listOf(false, true)) {
            val fixture = Fixture()
            val player = fixture.player(777)
            fixture.login(player)
            fixture.services.rejectSubmissions = rejectWorker
            fixture.services.rejectSchedules = !rejectWorker
            fixture.gate.onJoin(player.limbo)
            assertEquals(1, player.disconnects.size)
            assertEquals(0, fixture.services.beginCalls)
            assertEquals(0, fixture.gate.verifyingCount())
            assertTrue(fixture.services.timers.all { it.isDone })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `rejected poll submission or reschedule cancels all work without a backend fallback`() {
        for (rejectWorker in listOf(false, true)) {
            val fixture = Fixture()
            val player = fixture.player(777)
            fixture.join(player)
            fixture.services.rejectSubmissions = rejectWorker
            fixture.services.rejectSchedules = !rejectWorker
            fixture.services.advanceBy(1_000)
            assertEquals(if (rejectWorker) 0 else 1, fixture.services.pollCalls)
            assertEquals(1, player.disconnects.size)
            assertEquals(1, player.limbo.clearCount)
            assertEquals(0, fixture.services.grants)
            assertTrue(fixture.services.timers.all { it.isDone })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `restart scheduler rejection cancels the old prompt once and never starts a new challenge`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        val oldTimers = fixture.services.timers.toList()
        fixture.services.rejectSchedules = true
        assertFalse(fixture.gate.restartVerification(player))
        oldTimers.forEach { it.runEvenIfCancelled() }
        fixture.services.runWork()
        fixture.gate.onLeave(player.limbo)
        fixture.gate.onDisconnect(DisconnectEvent(player))
        assertEquals(1, fixture.services.beginCalls)
        assertEquals(1, player.limbo.titleCount)
        assertEquals(1, player.limbo.clearCount)
        assertEquals(1, player.disconnects.size)
        assertTrue(fixture.services.timers.all { it.isDone })
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `queued startup after a delayed deadline performs no network or title work`() {
        val fixture = Fixture(mapOf("verification.timeout-seconds" to "1"))
        val player = fixture.player(777)
        fixture.login(player)
        fixture.gate.onJoin(player.limbo)
        fixture.now += 1_000
        fixture.services.runWork()
        assertEquals(0, fixture.services.beginCalls)
        assertEquals(0, player.limbo.titleCount)
        assertEquals(0, player.limbo.clearCount)
        assertEquals(1, player.disconnects.size)
        fixture.services.timers.forEach { it.runEvenIfCancelled() }
        assertEquals(1, player.disconnects.size)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `queued poll after a delayed deadline performs no poll exchange bind or grant`() {
        val fixture = Fixture(mapOf("verification.timeout-seconds" to "2"))
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.timers.last().run()
        fixture.now += 2_000
        fixture.services.runWork()
        assertEquals(0, fixture.services.pollCalls)
        assertEquals(0, fixture.services.exchangeCalls)
        assertEquals(0, fixture.services.bindCalls)
        assertEquals(0, fixture.services.grants)
        assertEquals(1, player.disconnects.size)
        assertEquals(1, player.limbo.clearCount)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `restart cannot extend an already elapsed deadline when its timer has not run`() {
        val fixture = Fixture(mapOf("verification.timeout-seconds" to "1"))
        val player = fixture.player(777)
        fixture.join(player)
        fixture.now += 1_000
        assertFalse(fixture.gate.restartVerification(player))
        assertEquals(1, fixture.services.beginCalls)
        assertEquals(1, player.disconnects.size)
        assertEquals(1, player.limbo.clearCount)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `late begin poll exchange and binding results cannot authenticate a timed out attempt`() {
        for (stage in listOf("begin", "poll", "exchange", "bind")) {
            val fixture = Fixture(mapOf("verification.timeout-seconds" to "2"))
            val player = fixture.player(777)
            val expire = { fixture.now += 2_000; Unit }
            when (stage) {
                "begin" -> fixture.services.duringBegin = expire
                "poll" -> fixture.services.duringPoll = expire
                "exchange" -> fixture.services.duringExchange = expire
                "bind" -> fixture.services.duringBind = expire
            }
            fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            fixture.login(player)
            fixture.gate.onJoin(player.limbo)
            fixture.services.runWork()
            if (stage != "begin") fixture.services.advanceBy(1_000)
            assertEquals(0, fixture.services.grants, stage)
            assertEquals(1, player.disconnects.size, stage)
            assertEquals(if (stage == "begin") 0 else 1, player.limbo.clearCount, stage)
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `late network completion and stale cancellation never touch a new same UUID connection`() {
        for (stage in listOf("begin", "poll", "exchange", "bind")) {
            val fixture = Fixture()
            val old = fixture.player(767)
            var replacement: FakePlayer? = null
            val reconnect = {
                fixture.services.duringBegin = {}
                fixture.services.duringPoll = {}
                fixture.services.duringExchange = {}
                fixture.services.duringBind = {}
                old.isActive = false
                fixture.gate.onDisconnect(DisconnectEvent(old))
                fixture.player(777, old.sessionId).also {
                    replacement = it
                    fixture.login(it)
                    fixture.gate.onJoin(it.limbo)
                }
                Unit
            }
            when (stage) {
                "begin" -> fixture.services.duringBegin = reconnect
                "poll" -> fixture.services.duringPoll = reconnect
                "exchange" -> fixture.services.duringExchange = reconnect
                "bind" -> fixture.services.duringBind = reconnect
            }
            fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            fixture.login(old)
            fixture.gate.onJoin(old.limbo)
            val staleTimers = fixture.services.timers.toMutableList()
            fixture.services.runWork()
            if (stage != "begin") {
                staleTimers += fixture.services.timers.filter { it !in staleTimers }
                fixture.services.advanceBy(1_000)
            }
            val next = requireNotNull(replacement)
            fixture.services.duringBegin = {}
            fixture.services.duringPoll = {}
            fixture.services.duringExchange = {}
            fixture.services.duringBind = {}
            staleTimers.forEach { it.runEvenIfCancelled() }
            fixture.gate.onLeave(old.limbo)
            fixture.gate.onDisconnect(DisconnectEvent(old))
            fixture.services.runWork()
            assertEquals(0, fixture.services.grants, stage)
            assertEquals(1, next.limbo.titleCount, stage)
            assertEquals(0, next.limbo.clearCount, stage)
            assertTrue(next.disconnects.isEmpty(), stage)
            assertEquals(1, fixture.gate.verifyingCount(), stage)
            fixture.assertBackendDenied(next)
        }
    }

    @Test
    fun `failed session storage never authorizes and duplicate completion remains cancelled`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.services.grantFailure = IllegalStateException("Synthetic storage failure")
        val timers = fixture.services.timers.toList()
        fixture.services.advanceBy(1_000)
        timers.forEach { it.runEvenIfCancelled() }
        fixture.services.runWork()
        assertEquals(1, fixture.services.bindCalls)
        assertEquals(0, fixture.services.grants)
        assertEquals(1, player.disconnects.size)
        assertEquals(1, player.limbo.clearCount)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `deactivation invalidates queued startup and all late cancellation callbacks`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.login(player)
        fixture.gate.onJoin(player.limbo)
        val timers = fixture.services.timers.toList()
        fixture.gate.deactivate()
        fixture.services.runWork()
        timers.forEach { it.runEvenIfCancelled() }
        fixture.gate.onLeave(player.limbo)
        fixture.gate.onDisconnect(DisconnectEvent(player))
        assertEquals(0, fixture.services.beginCalls)
        assertEquals(0, player.limbo.titleCount)
        assertEquals(0, player.limbo.clearCount)
        assertEquals(1, player.disconnects.size)
        assertEquals(0, fixture.services.grants)
        assertTrue(fixture.services.timers.all { it.isDone })
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `limbo arrival timeout and late onJoin cannot start authentication or inherit fallback`() {
        for (runTimeout in listOf(false, true)) {
            val fixture = Fixture(mapOf("limbo.join-timeout-seconds" to "5"))
            val player = fixture.player(777)
            fixture.login(player)
            if (runTimeout) {
                fixture.services.advanceBy(5_000)
            } else {
                fixture.now += 5_000
                fixture.gate.onJoin(player.limbo)
            }
            fixture.services.runWork()
            assertEquals(1, player.disconnects.size)
            assertEquals(0, fixture.services.beginCalls)
            assertEquals(0, player.limbo.titleCount)
            assertTrue(fixture.services.timers.all { it.isDone })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `arrival scheduler rejection denies login and never leaves a held connection`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.gate.onPreLogin(PreLoginEvent(player, player.username, player.uniqueId))
        fixture.services.rejectSchedules = true
        val event = LoginEvent(player)
        fixture.gate.onLogin(event)
        assertFalse(event.result.isAllowed)
        assertEquals(0, fixture.gate.verifyingCount())
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `pending capacity refuses before another lookup and reclaims disconnected and expired slots`() {
        for (expire in listOf(false, true)) {
            val fixture = Fixture(mapOf("safety.pending-login-limit" to "1"))
            val old = fixture.player(777)
            val first = PreLoginEvent(old, old.username, old.uniqueId)
            fixture.gate.onPreLogin(first)
            assertTrue(first.result.isAllowed)
            val next = fixture.player(777)
            val busy = PreLoginEvent(next, next.username, next.uniqueId)
            fixture.gate.onPreLogin(busy)
            assertFalse(busy.result.isAllowed)
            assertEquals(1, fixture.services.decideCalls)
            if (expire) fixture.now += 300_000 else old.isActive = false
            val retry = PreLoginEvent(next, next.username, next.uniqueId)
            fixture.gate.onPreLogin(retry)
            assertTrue(retry.result.isAllowed)
            assertEquals(2, fixture.services.decideCalls)
            val stale = LoginEvent(old)
            fixture.gate.onLogin(stale)
            assertFalse(stale.result.isAllowed)
            fixture.assertBackendDenied(old)
        }
    }

    @Test
    fun `capacity filled during a premium lookup releases its handshake lease on refusal`() {
        val fixture = Fixture(mapOf("safety.pending-login-limit" to "1"))
        val premium = fixture.player(777)
        lateinit var occupant: FakePlayer
        fixture.services.duringDecide = {
            fixture.services.duringDecide = {}
            occupant = fixture.player(777)
            fixture.gate.onPreLogin(PreLoginEvent(occupant, occupant.username, occupant.uniqueId))
            fixture.services.decision = AuthCoordinator.Decision.MojangSession(record.uuid)
        }
        val event = PreLoginEvent(premium, premium.username, premium.uniqueId)
        fixture.gate.onPreLogin(event)
        assertFalse(event.result.isAllowed)
        assertTrue(fixture.services.timers.isEmpty(), "A refused premium lookup must not leave a watch")
        fixture.assertBackendDenied(premium)
        occupant.isActive = false
        fixture.now += 30_000
        val retryPlayer = fixture.player(777)
        val retry = PreLoginEvent(retryPlayer, retryPlayer.username, retryPlayer.uniqueId)
        fixture.gate.onPreLogin(retry)
        assertTrue(retry.result.isAllowed, "The released lease's cooldown has elapsed, not just its handshake deadline")
        assertEquals(1, fixture.services.timers.size)
    }

    @Test
    fun `stale disconnect before replacement login cannot consume its pending decision`() {
        val fixture = Fixture()
        val old = fixture.player(767)
        fixture.join(old)
        old.isActive = false
        fixture.gate.onDisconnect(DisconnectEvent(old))
        val next = fixture.player(777, old.sessionId)
        val pre = PreLoginEvent(next, next.username, next.uniqueId)
        fixture.gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        fixture.gate.onDisconnect(DisconnectEvent(old))
        val login = LoginEvent(next)
        fixture.gate.onLogin(login)
        assertTrue(login.result.isAllowed)
        fixture.gate.onJoin(next.limbo)
        fixture.services.runWork()
        assertEquals(1, next.limbo.titleCount)
        assertEquals(0, next.limbo.clearCount)
        fixture.assertBackendDenied(next)
    }

    private class Fixture(values: Map<String, String> = emptyMap(), fastPolling: Boolean = true) {
        private val rolesEnabled = values["role-selection.enabled"] == "true"
        var now = 1_000_000L
        val logs = mutableListOf<String>()
        var duringLog: (String) -> Unit = {}
        val lobby = RegisteredServer("lobby")
        val servers = (767..777).associate { limboName(it) to RegisteredServer(limboName(it)) }.toMutableMap().apply {
            put(LoginGate.LIMBO_SERVER_NAME, RegisteredServer(LoginGate.LIMBO_SERVER_NAME))
            put(lobby.name, lobby)
        }
        private val players = mutableListOf<FakePlayer>()
        val proxy = object : ProxyServer {
            override fun getServer(name: String): Optional<RegisteredServer> = Optional.ofNullable(servers[name])
            override fun getPlayer(name: String): Optional<Player> =
                Optional.ofNullable(players.lastOrNull { it.isActive && it.username == name })
            override fun getPlayer(uuid: UUID): Optional<Player> =
                Optional.ofNullable(players.lastOrNull { it.isActive && it.uniqueId == uuid })
            override val allPlayers: Collection<Player> get() = players.filter { it.isActive }
            override val configuration = object : ProxyConfiguration {
                override val attemptConnectionOrder = listOf(LoginGate.LIMBO_SERVER_NAME) +
                    (767..777).map(::limboName) + lobby.name
            }
        }
        val services = MemoryServices { now }
        val catalogue = MessageCatalogue("en_US", File("unused-contract-messages"))
        val gate = LoginGate(proxy, ProxyConfig(
            (if (fastPolling) mapOf("verification.poll-interval-seconds" to "1") else emptyMap()) + values,
            File("unused-contract-config")),
            catalogue, services, ProxyLog { _, message, _ ->
                logs += message
                duringLog(message)
            }, { now },
        ).also { it.activate() }

        fun inbound(): InboundConnection = object : InboundConnection {
            override val sessionId = UUID.randomUUID()
            override val isActive = true
            override val remoteAddress = InetSocketAddress("127.0.0.1", 50000)
            override fun disconnect(message: RichText.Parsed) = Unit
        }

        fun player(version: Int, sessionId: UUID = UUID.randomUUID()) =
            FakePlayer(version, sessionId).also { players += it }

        fun login(player: FakePlayer, bedrock: BedrockLogin = BedrockLogin.Java) {
            val pre = PreLoginEvent(player, player.username, player.uniqueId, bedrock)
            gate.onPreLogin(pre)
            assertTrue(pre.result.isAllowed)
            if (rolesEnabled) {
                val profile = GameProfileRequestEvent(player, GameProfile(player.uniqueId, player.username, player.properties), bedrock)
                gate.onPrepareGameProfile(profile)
                gate.onGameProfile(profile)
                player.uniqueId = profile.gameProfile.id
                player.username = profile.gameProfile.name
                player.properties = profile.gameProfile.properties
            }
            val login = LoginEvent(player, bedrock)
            gate.onLogin(login)
            assertTrue(login.result.isAllowed)
        }

        fun strongBedrock(player: FakePlayer, options: RoleOptions = dualRoles()) {
            services.options = options
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
            player.uniqueId = proof.uniqueId
            player.username = proof.username
            login(player, bedrock = BedrockLogin.Verified(proof))
            gate.onJoin(player.limbo)
            services.runWork()
        }

        fun joinBedrock(options: RoleOptions = dualRoles(), proof: BedrockProof =
            BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }): FakePlayer {
            services.options = options
            val player = player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
            login(player, bedrock = BedrockLogin.Verified(proof))
            gate.onJoin(player.limbo)
            return player
        }

        fun freshBedrockLogin(player: FakePlayer) {
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
            player.uniqueId = proof.uniqueId
            player.username = proof.username
            login(player, bedrock = BedrockLogin.Verified(proof))
        }

        fun dualRoles(): RoleOptions = RoleOptions(listOf(
            RoleOption(record.toIdentity(), record, null),
            RoleOption(Identity.Premium(GameName.parseOrThrow("Alex"), premiumUuid), null,
                GameProfile.Property("textures", "official-texture", "official-signature"))), premiumUuid)

        fun join(player: FakePlayer) {
            login(player)
            gate.onJoin(player.limbo)
            services.runWork()
            assertEquals(1, player.limbo.titleCount)
        }

        fun assertBackendDenied(player: FakePlayer) {
            val event = ServerPreConnectEvent(player, lobby)
            gate.onPreConnect(event)
            assertTrue(event.result.server.isEmpty)
            assertTrue(player.requests.isEmpty())
        }

        inner class MemoryServices(private val clock: () -> Long) : LoginGate.Services {
            val work = ArrayDeque<() -> Unit>()
            val timers = mutableListOf<Timer>()
            var beginCalls = 0
            var grants = 0
            var pollCalls = 0
            val pollRequestsAt = mutableListOf<Long>()
            val pollResponsesAt = mutableListOf<Long>()
            val exchangeRequestsAt = mutableListOf<Long>()
            var exchangeCalls = 0
            var bindCalls = 0
            var decideCalls = 0
            var roleCalls = 0
            var premiumBinds = 0
            var roleFailure: Exception? = null
            var duringRoles: () -> Unit = {}
            var options = RoleOptions(listOf(RoleOption(record.toIdentity(), record, null)), null)
            var allow = true
            var rejectSubmissions = false
            var rejectSchedules = false
            var challengeIntervalSeconds = 1
            var pollFailure: Exception? = null
            var beginFailure: Exception? = null
            var grantFailure: Exception? = null
            var duringDecide: () -> Unit = {}
            var duringBegin: () -> Unit = {}
            var duringPoll: () -> Unit = {}
            var duringExchange: () -> Unit = {}
            var duringBind: () -> Unit = {}
            var decision: AuthCoordinator.Decision =
                AuthCoordinator.Decision.Verify(record.uuid, AuthCoordinator.Reason.SESSION_EXPIRED, false, null)
            var pollResult: DeviceCodeFlow.PollResult = DeviceCodeFlow.PollResult.Pending
            override fun allow(key: String) = allow
            override fun decide(name: GameName, address: String): AuthCoordinator.Decision {
                decideCalls++
                duringDecide()
                return decision
            }
            override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome {
                premiumBinds++
                return AuthCoordinator.ClaimOutcome.Bound(BoundIdentity.of(Identity.Premium(name, uuid), clock()))
            }
            override fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?): RoleOptions {
                roleCalls++
                duringRoles()
                roleFailure?.let { throw it }
                return options
            }
            override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome {
                bindCalls++
                duringBind()
                return AuthCoordinator.ClaimOutcome.Bound(record)
            }
            override fun grantSession(uuid: UUID, address: String) {
                grantFailure?.let { throw it }
                grants++
            }
            override fun submit(task: () -> Unit) {
                if (rejectSubmissions) throw RejectedExecutionException("Worker unavailable")
                work.addLast(task)
            }
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> {
                if (rejectSchedules) throw RejectedExecutionException("Scheduler unavailable")
                return Timer(clock() + delayMillis, clock, task).also { timers += it }
            }
            override fun begin(): DeviceCodeFlow.Challenge {
                beginCalls++
                beginFailure?.let { throw it }
                duringBegin()
                return DeviceCodeFlow.Challenge("code-$beginCalls", "https://example.invalid",
                    "https://example.invalid?otc=code-$beginCalls", 300, challengeIntervalSeconds, "device-$beginCalls")
            }
            override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult {
                pollCalls++
                pollRequestsAt += clock()
                try {
                    duringPoll()
                    pollFailure?.let { throw it }
                    return pollResult
                } finally {
                    pollResponsesAt += clock()
                }
            }
            override fun exchange(accessToken: String): XboxLive.XstsToken {
                exchangeCalls++
                exchangeRequestsAt += clock()
                duringExchange()
                return XboxLive.XstsToken("test", 1L, "Steve", "test-token")
            }
            fun runWork() { while (work.isNotEmpty()) work.removeFirst().invoke() }
            fun advanceBy(millis: Long) {
                val target = now + millis
                while (true) {
                    val timer = timers.filter { !it.isDone && it.dueAt <= target }.minByOrNull { it.dueAt } ?: break
                    now = timer.dueAt
                    timer.run()
                    runWork()
                }
                now = target
            }
        }
    }

    private class FakePlayer(override val protocolVersion: Int, override val sessionId: UUID) : Player {
        override var isActive = true
        override var uniqueId = record.uuid
        override var username = record.name.value
        override var isOnlineMode = false
        var properties = emptyList<GameProfile.Property>()
        var profileResult = CompletableFuture.completedFuture(Unit)
        val appliedProfiles = mutableListOf<GameProfile>()
        override fun applyRoleProfile(profile: GameProfile): CompletableFuture<Unit> {
            check(profile.id == uniqueId && profile.name == username)
            appliedProfiles += profile
            return profileResult.thenApply {
                properties = properties.filterNot { it.name == "textures" || it.name == LoginGate.CONNECTION_PROPERTY } + profile.properties
                Unit
            }
        }
        override val effectiveLocale = Locale.US
        override val remoteAddress = InetSocketAddress("127.0.0.1", 50000)
        val limbo = FakeLimbo(this)
        val disconnects = mutableListOf<RichText.Parsed>()
        val messages = mutableListOf<RichText.Parsed>()
        var throwOnMessage = false
        val requests = mutableListOf<RegisteredServer>()
        var backendResult = CompletableFuture.completedFuture(
            ConnectionRequestBuilder.Result(ConnectionRequestBuilder.Status.SUCCESS))
        override fun hasPermission(permission: String) = true
        override fun sendMessage(message: RichText.Parsed) {
            check(!throwOnMessage) { "controlled feedback transport failure" }
            messages += message
        }
        override fun disconnect(message: RichText.Parsed) { disconnects += message }
        override fun createConnectionRequest(target: RegisteredServer): ConnectionRequestBuilder {
            requests += target
            return object : ConnectionRequestBuilder {
                override fun connect() = backendResult
            }
        }
    }

    private class FakeLimbo(private val player: FakePlayer) : LimboPlayer {
        override val name get() = player.username
        override val uuid get() = player.uniqueId
        override val connectionId get() = player.sessionId
        override val address = "127.0.0.1"
        var titleCount = 0
        var clearCount = 0
        var menuCloseCount = 0
        val menus = mutableListOf<RoleSelectorMenu>()
        override fun openRoleSelector(menu: RoleSelectorMenu) { menus += menu }
        override fun closeRoleSelector() { menuCloseCount++ }
        val messages = mutableListOf<TextComponent>()
        var duringReadyPrompt: () -> Unit = {}
        var duringMessage: (TextComponent) -> Unit = {}
        override fun sendMessage(message: TextComponent) {
            duringMessage(message)
            messages += message
            if (message.children.any { it.click != null }) duringReadyPrompt()
        }
        override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) { titleCount++ }
        override fun clearTitle() { clearCount++ }
        override fun disconnect(reason: TextComponent) = error("No limbo-handle disconnect expected")
        override fun close() = Unit
    }

    private class Timer(val dueAt: Long, private val clock: () -> Long, private val task: () -> Unit) : ScheduledFuture<Unit> {
        private var cancelled = false
        private var completed = false
        fun run() {
            if (isDone) return
            completed = true
            task()
        }
        fun runEvenIfCancelled() = task()
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean { cancelled = true; return true }
        override fun isCancelled() = cancelled
        override fun isDone() = cancelled || completed
        override fun get() = Unit
        override fun get(timeout: Long, unit: TimeUnit) = Unit
        override fun getDelay(unit: TimeUnit) = unit.convert(dueAt - clock(), TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed) = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }

    private companion object {
        val record = BoundIdentity.of(Identity.Microsoft(GameName.parseOrThrow("Steve"), Xuid(1L), "Steve"), 1L)
        val premiumUuid: UUID = UUID.fromString("12345678-1234-4234-9234-123456789abc")
        val roleConfig = mapOf("role-selection.enabled" to "true", "bedrock.enabled" to "true")
        fun limboName(version: Int) = "${LoginGate.LIMBO_SERVER_NAME}-$version"
    }
}
