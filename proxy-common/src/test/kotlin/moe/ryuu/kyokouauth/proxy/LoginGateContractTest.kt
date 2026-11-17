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
import moe.ryuu.kyokouauth.limbo.RoleAvatarIcon
import moe.ryuu.kyokouauth.proxy.avatar.RoleAvatarOffer
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
    fun `role selection enabled never admits a Microsoft IP session without fresh proof`() {
        val fixture = Fixture(mapOf("role-selection.enabled" to "true", "role-selection.reconnect-host" to "proxy.invalid"))
        fixture.services.decision = AuthCoordinator.Decision.Admit(
            Identity.Microsoft(record.name, Xuid(1L), "Steve"), record)
        val player = fixture.player(777)
        fixture.login(player)
        assertFalse(fixture.gate.isAuthenticated(player))
        fixture.assertBackendDenied(player)
        fixture.gate.onJoin(player.limbo)
        fixture.services.runWork()
        assertEquals(1, fixture.services.beginCalls)
        assertEquals(0, fixture.services.grants)
    }

    @Test
    fun `strong Microsoft proof opens two roles without admitting or occupying either real profile`() {
        val fixture = Fixture(roleConfig)
        val player = fixture.player(777)
        player.properties = listOf(GameProfile.Property("textures", "untrusted", ""), GameProfile.Property("other", "keep", ""))
        fixture.strongMicrosoft(player)
        assertFalse(fixture.gate.isAuthenticated(player))
        fixture.assertBackendDenied(player)
        assertTrue(player.uniqueId != record.uuid && player.uniqueId != premiumUuid)
        assertTrue(player.username != "Steve" && player.username != "Alex" && player.username.length == 16)
        assertEquals(0, fixture.services.grants)
        assertEquals(0, fixture.services.premiumBinds)
        assertEquals(1, fixture.services.bindCalls)
        assertEquals(1, player.limbo.menus.size)
        assertTrue(player.properties.none { it.name == "textures" })
        assertTrue(player.properties.any { it.name == "other" && it.value == "keep" })
    }

    @Test
    fun `choosing premium transfers once then admits only a new final profile without Microsoft IP grant`() {
        val fixture = Fixture(roleConfig)
        val chooser = fixture.player(777)
        fixture.strongMicrosoft(chooser)
        val menu = chooser.limbo.menus.single()
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "premium")
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "premium")
        fixture.services.runWork()
        assertEquals(1, chooser.transfers.size)
        assertFalse(fixture.gate.isAuthenticated(chooser))
        fixture.assertBackendDenied(chooser)
        assertEquals(0, fixture.services.premiumBinds)
        val cookie = checkNotNull(chooser.transfers.single().third)
        val resumed = fixture.player(777)
        resumed.properties = listOf(GameProfile.Property("textures", "wrong-SR", ""), GameProfile.Property("other", "keep", ""))
        fixture.login(resumed, cookie)
        assertEquals(premiumUuid, resumed.uniqueId)
        assertEquals("Alex", resumed.username)
        assertFalse(resumed.isOnlineMode)
        assertTrue(fixture.gate.isAuthenticated(resumed))
        assertEquals(1, fixture.services.premiumBinds)
        assertEquals(0, fixture.services.grants)
        assertEquals(listOf(GameProfile.Property("textures", "official-texture", "official-signature")),
            resumed.properties.filter { it.name == "textures" })
        assertTrue(resumed.properties.any { it.name == "other" && it.value == "keep" })
        assertEquals(resumed.sessionId.toString(), resumed.properties.single { it.name == LoginGate.CONNECTION_PROPERTY }.value)
        assertTrue(resumed.limbo.menus.isEmpty())
        val replay = PreLoginEvent(fixture.player(777), "Steve", record.uuid, roleCookie = cookie)
        fixture.gate.onPreLogin(replay)
        assertFalse(replay.result.isAllowed)
    }

    @Test
    fun `single Microsoft role auto selects but only its final reconnect receives a session and explicit skin`() {
        val fixture = Fixture(roleConfig)
        val skin = GameProfile.Property("textures", "explicit-SR", "SR-signature")
        val chooser = fixture.player(777)
        fixture.strongMicrosoft(chooser, RoleOptions(listOf(RoleOption(record.toIdentity(), record, skin)), null))
        assertTrue(chooser.limbo.menus.isEmpty())
        assertEquals(1, chooser.transfers.size)
        assertEquals(0, fixture.services.grants)
        val resumed = fixture.player(777)
        fixture.login(resumed, checkNotNull(chooser.transfers.single().third))
        assertEquals(record.uuid, resumed.uniqueId)
        assertEquals(record.name.value, resumed.username)
        assertTrue(fixture.gate.isAuthenticated(resumed))
        assertEquals(1, fixture.services.grants)
        assertEquals(listOf(skin), resumed.properties.filter { it.name == "textures" })
    }

    @Test
    fun `renamed menu refreshes generation and an old click cannot authorize or reinterpret the name`() {
        val fixture = Fixture(roleConfig)
        val chooser = fixture.player(777)
        fixture.strongMicrosoft(chooser)
        val old = chooser.limbo.menus.single()
        val renamed = record.copy(name = GameName.parseOrThrow("SteveNew"))
        fixture.services.options = RoleOptions(listOf(RoleOption(renamed.toIdentity(), renamed, null),
            fixture.dualRoles().roles.single { it.identity is Identity.Premium }), premiumUuid)
        fixture.gate.onRoleSelect(chooser.limbo, old.generation, "microsoft")
        fixture.services.runWork()
        assertEquals(2, chooser.limbo.menus.size)
        assertTrue(chooser.transfers.isEmpty())
        val fresh = chooser.limbo.menus.last()
        assertTrue(fresh.generation != old.generation)
        fixture.gate.onRoleSelect(chooser.limbo, old.generation, "microsoft")
        fixture.services.runWork()
        assertTrue(chooser.transfers.isEmpty())
        fixture.gate.onRoleSelect(chooser.limbo, fresh.generation, "microsoft")
        fixture.services.runWork()
        val resumed = fixture.player(777)
        fixture.login(resumed, checkNotNull(chooser.transfers.single().third))
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
        fixture.strongMicrosoft(chooser, oldOptions)
        val oldMenu = chooser.limbo.menus.single()

        val currentIdentity = Identity.Premium(GameName.parseOrThrow("AlexNew"), premiumUuid)
        fixture.services.options = RoleOptions(listOf(oldOptions.roles.first(),
            RoleOption(currentIdentity, oldBinding, premium.skin)), premiumUuid)
        fixture.gate.onRoleSelect(chooser.limbo, oldMenu.generation, "premium")
        fixture.services.runWork()
        assertTrue(chooser.transfers.isEmpty())
        assertTrue(chooser.disconnects.isEmpty())
        assertEquals(2, chooser.limbo.menus.size)
        val currentMenu = chooser.limbo.menus.last()
        assertTrue(currentMenu.generation != oldMenu.generation)
        assertEquals(premiumUuid, currentMenu.premium.profileUuid)
        assertEquals("AlexNew", currentMenu.premium.profileName)
        fixture.gate.onRoleSelect(chooser.limbo, oldMenu.generation, "premium")
        fixture.services.runWork()
        assertTrue(chooser.transfers.isEmpty())

        fixture.gate.onRoleSelect(chooser.limbo, currentMenu.generation, "premium")
        fixture.services.runWork()
        val resumed = fixture.player(777)
        fixture.login(resumed, checkNotNull(chooser.transfers.single().third))
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
        fixture.strongMicrosoft(chooser)
        val menu = chooser.limbo.menus.single()
        fixture.services.options = RoleOptions(listOf(RoleOption(record.toIdentity(), record, null)), null)
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "premium")
        fixture.services.runWork()
        assertTrue(chooser.transfers.isEmpty())
        assertTrue(chooser.disconnects.isNotEmpty())
        assertFalse(fixture.gate.isAuthenticated(chooser))
        assertEquals(0, fixture.services.premiumBinds)
    }

    @Test
    fun `cookie rename revocation expiry malformed and issuer reuse never downgrade to ordinary login`() {
        for (change in listOf("rename", "unlink", "expire", "malformed", "issuer")) {
            val fixture = Fixture(roleConfig)
            val chooser = fixture.player(777)
            fixture.strongMicrosoft(chooser)
            fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
            fixture.services.runWork()
            var cookie = checkNotNull(chooser.transfers.single().third)
            when (change) {
                "rename" -> fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first(),
                    RoleOption(Identity.Premium(GameName.parseOrThrow("AlexNew"), premiumUuid), null, null)), premiumUuid)
                "unlink" -> fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first()), null)
                "expire" -> fixture.now += 31_000L
                "malformed" -> cookie = byteArrayOf(1, 2, 3)
            }
            val calls = fixture.services.decideCalls
            val resumed = fixture.player(777, if (change == "issuer") chooser.sessionId else UUID.randomUUID())
            val event = PreLoginEvent(resumed, "Steve", record.uuid, roleCookie = cookie)
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
        fixture.strongMicrosoft(chooser)
        val menu = chooser.limbo.menus.single()
        val impostor = fixture.player(777, chooser.sessionId)
        fixture.gate.onRoleSelect(impostor.limbo, menu.generation, "premium")
        fixture.gate.onRoleSelect(chooser.limbo, UUID.randomUUID(), "premium")
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "admin")
        fixture.services.runWork()
        assertTrue(chooser.transfers.isEmpty())
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "premium")
        fixture.now += 5_000L
        fixture.services.runWork()
        fixture.gate.onRoleSelect(chooser.limbo, menu.generation, "premium")
        fixture.services.runWork()
        assertTrue(chooser.transfers.isEmpty())
        assertTrue(chooser.disconnects.isNotEmpty())
        fixture.assertBackendDenied(chooser)
    }

    @Test
    fun `disconnect stop and queue failures cannot transfer a selected role`() {
        for (failure in listOf("disconnect", "stop", "queue", "resolve")) {
            val fixture = Fixture(roleConfig)
            val chooser = fixture.player(777)
            fixture.strongMicrosoft(chooser)
            if (failure == "queue") fixture.services.rejectSubmissions = true
            if (failure == "resolve") fixture.services.roleFailure = IllegalStateException("unavailable")
            fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
            if (failure == "disconnect") fixture.gate.onDisconnect(DisconnectEvent(chooser))
            if (failure == "stop") fixture.gate.deactivate()
            fixture.services.runWork()
            assertTrue(chooser.transfers.isEmpty(), failure)
            assertFalse(fixture.gate.isAuthenticated(chooser), failure)
            assertEquals(0, fixture.services.grants, failure)
        }
    }

    @Test
    fun `role profile LAST rejects id or name tampering and login cannot omit final validation`() {
        for (tamper in listOf("uuid", "name", "skip-last")) {
            val fixture = Fixture(roleConfig)
            val player = fixture.player(777)
            val pre = PreLoginEvent(player, "Steve", record.uuid)
            fixture.gate.onPreLogin(pre)
            assertTrue(pre.result.isAllowed)
            val event = GameProfileRequestEvent(player, GameProfile(record.uuid, "Steve", emptyList()))
            fixture.gate.onPrepareGameProfile(event)
            if (tamper == "uuid") event.gameProfile = event.gameProfile.copy(id = UUID.randomUUID())
            if (tamper == "name") event.gameProfile = event.gameProfile.copy(name = "WrongName")
            if (tamper != "skip-last") fixture.gate.onGameProfile(event)
            player.uniqueId = event.gameProfile.id
            player.username = event.gameProfile.name
            val login = LoginEvent(player)
            fixture.gate.onLogin(login)
            assertFalse(login.result.isAllowed, tamper)
            assertFalse(fixture.gate.isAuthenticated(player), tamper)
        }
    }

    @Test
    fun `online role is never kicked and a chooser may still select the other role`() {
        val fixture = Fixture(roleConfig)
        val playingU = fixture.player(777)
        fixture.services.decision = AuthCoordinator.Decision.MojangSession(premiumUuid)
        val playingJ = fixture.player(777).apply { uniqueId = premiumUuid; username = "Alex"; isOnlineMode = true }
        fixture.login(playingJ)
        playingU.isActive = false
        fixture.services.decision = AuthCoordinator.Decision.Verify(record.uuid, AuthCoordinator.Reason.SESSION_EXPIRED, false, record)
        val chooser = fixture.player(777)
        fixture.strongMicrosoft(chooser)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "microsoft")
        fixture.services.runWork()
        assertEquals(1, chooser.transfers.size)
        assertTrue(playingJ.disconnects.isEmpty())
        val resumed = fixture.player(777)
        fixture.login(resumed, checkNotNull(chooser.transfers.single().third))
        assertTrue(fixture.gate.isAuthenticated(resumed))
        assertTrue(playingJ.disconnects.isEmpty())
        assertTrue(fixture.gate.isAuthenticated(playingJ))
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
    fun `Floodgate chooser preserves the provider registered profile while backend admission stays held`() {
        for (linked in listOf(true, false)) {
            val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
            val proof = if (linked) BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
                else BedrockProof.create(Xuid(1L), record.uuid, ".Steve", "Steve", ".", Any()) { true }
            fixture.services.options = if (linked) fixture.dualRoles()
                else RoleOptions(listOf(fixture.dualRoles().roles.first()), null)
            val chooser = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
            fixture.login(chooser, bedrock = BedrockLogin.Verified(proof))
            assertEquals(proof.uniqueId, chooser.uniqueId, "Official Floodgate message lookup must recognize this native Player UUID")
            assertTrue(chooser.username.matches(Regex("KA[A-Fa-f0-9]{14}")), "Only the display alias is temporary; Limbo still requires a Java-compatible name")
            assertEquals(if (linked) "Alex" else ".Steve", proof.username, "The native connection proof must retain its original name")
            assertFalse(fixture.gate.isAuthenticated(chooser), "Native messaging identity is not backend admission")
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
    fun `strong Floodgate dual roles wait for asynchronous avatar preparation without selecting or authorizing`() {
        val fixture = Fixture(avatarConfig)
        fixture.services.options = fixture.dualRoles()
        fixture.services.avatarPreparation = CompletableFuture()
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val chooser = fixture.player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
        fixture.login(chooser, bedrock = BedrockLogin.Verified(proof))
        fixture.gate.onJoin(chooser.limbo)
        fixture.services.runWork()
        assertTrue(chooser.limbo.menus.isEmpty(), "The role menu must not promise avatars before asynchronous preparation completes")
        assertTrue(chooser.transfers.isEmpty())
        assertFalse(fixture.gate.isAuthenticated(chooser))
        fixture.assertBackendDenied(chooser)
        assertEquals(0, fixture.services.beginCalls)
        assertEquals(0, fixture.services.grants)
        assertEquals(0, fixture.services.premiumBinds)
        assertEquals(1, fixture.services.avatarPrepareCalls)
        assertEquals(0, fixture.services.avatarOfferCalls)
        fixture.services.avatarOffer = RoleAvatarOffer(UUID.randomUUID(), avatarFingerprint, false)
        fixture.services.avatarPreparation.complete(avatarFingerprint)
        assertEquals(0, fixture.services.avatarOfferCalls, "Loopback status must be queued on the bounded IO service")
        fixture.services.runWork()
        val menu = chooser.limbo.menus.single()
        assertEquals(RoleAvatarIcon.MICROSOFT, menu.microsoft.avatarIcon)
        assertEquals(RoleAvatarIcon.PREMIUM, menu.premium.avatarIcon)
        assertEquals(record.uuid, menu.microsoft.profileUuid)
        assertEquals(premiumUuid, menu.premium.profileUuid)
        assertEquals("official-texture", menu.premium.textureValue)
        assertEquals("official-signature", menu.premium.textureSignature)
        assertEquals(1, menu.microsoft.lore.size)
        assertEquals(1, menu.premium.lore.size)
        assertEquals(1, fixture.services.avatarOfferCalls)
        assertTrue(chooser.transfers.isEmpty())
        assertFalse(fixture.gate.isAuthenticated(chooser))
    }

    @Test
    fun `avatar miss transfers only visuals and fresh Floodgate proof still needs an explicit role choice`() {
        val fixture = Fixture(avatarConfig)
        val first = fixture.joinBedrock()
        fixture.services.runWork()
        fixture.services.avatarPreparation.complete(avatarFingerprint)
        fixture.services.runWork()
        assertEquals(1, first.transfers.size)
        assertEquals(null, first.transfers.single().third)
        assertTrue(first.limbo.menus.isEmpty())
        assertFalse(fixture.gate.isAuthenticated(first))
        fixture.assertBackendDenied(first)
        assertEquals(0, fixture.services.grants)
        assertEquals(0, fixture.services.premiumBinds)
        fixture.gate.onDisconnect(DisconnectEvent(first))
        first.isActive = false

        fixture.services.avatarOffer = RoleAvatarOffer(UUID.randomUUID(), avatarFingerprint, false)
        val second = fixture.joinBedrock()
        fixture.services.runWork()
        assertEquals(1, second.limbo.menus.size, "Visual refresh must not mint a selected-role hint")
        assertTrue(second.transfers.isEmpty())
        assertFalse(fixture.gate.isAuthenticated(second))
        assertEquals(0, fixture.services.premiumBinds)
        fixture.gate.onRoleSelect(second.limbo, second.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        assertEquals(1, second.transfers.size)
        assertEquals(null, second.transfers.single().third)
        fixture.gate.onDisconnect(DisconnectEvent(second))
        second.isActive = false
        val finalProof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val final = fixture.player(777).apply { uniqueId = finalProof.uniqueId; username = finalProof.username }
        fixture.login(final, bedrock = BedrockLogin.Verified(finalProof))
        assertTrue(fixture.gate.isAuthenticated(final))
        assertEquals(premiumUuid, final.uniqueId)
        assertEquals("Alex", final.username)
        assertEquals(1, fixture.services.premiumBinds)
        assertEquals(0, fixture.services.grants)
        assertEquals(0, fixture.services.beginCalls)
    }

    @Test
    fun `same avatar fingerprint missing after one visual refresh opens explicit fallback without a transfer loop`() {
        val fixture = Fixture(avatarConfig)
        fixture.services.avatarPreparation.complete(avatarFingerprint)
        val first = fixture.joinBedrock()
        fixture.services.runWork()
        assertEquals(1, first.transfers.size)
        fixture.gate.onDisconnect(DisconnectEvent(first))
        first.isActive = false
        repeat(2) {
            val next = fixture.joinBedrock()
            fixture.services.runWork()
            assertTrue(next.transfers.isEmpty())
            val menu = next.limbo.menus.single()
            assertEquals(RoleAvatarIcon.MICROSOFT, menu.microsoft.avatarIcon)
            assertEquals(RoleAvatarIcon.PREMIUM, menu.premium.avatarIcon)
            assertTrue(next.limbo.messages.contains(fixture.catalogue.parsed("role-selection.avatar-unavailable").toLimbo()))
            assertFalse(fixture.gate.isAuthenticated(next))
            fixture.assertBackendDenied(next)
            fixture.gate.onDisconnect(DisconnectEvent(next))
            next.isActive = false
        }
        assertEquals(0, fixture.services.grants)
        assertEquals(0, fixture.services.premiumBinds)
    }

    @Test
    fun `avatar status requires exact prepared content and a non fallback offer`() {
        for (offer in listOf(null, RoleAvatarOffer(UUID.randomUUID(), "b".repeat(64), false),
            RoleAvatarOffer(UUID.randomUUID(), avatarFingerprint, true))) {
            val fixture = Fixture(avatarConfig)
            fixture.services.avatarOffer = offer
            fixture.services.avatarPreparation.complete(avatarFingerprint)
            val player = fixture.joinBedrock()
            fixture.services.runWork()
            assertEquals(1, player.transfers.size)
            assertEquals(null, player.transfers.single().third)
            assertTrue(player.limbo.menus.isEmpty())
            assertFalse(fixture.gate.isAuthenticated(player))
            assertEquals(0, fixture.services.grants)
        }
    }

    @Test
    fun `avatar preparation is bypassed by Java feature off and single role while menu lore stays concise`() {
        val java = Fixture(avatarConfig)
        val javaPlayer = java.player(777)
        java.strongMicrosoft(javaPlayer)
        val javaMenu = javaPlayer.limbo.menus.single()
        assertEquals(null, javaMenu.microsoft.avatarIcon)
        assertEquals(null, javaMenu.premium.avatarIcon)
        assertEquals(listOf(java.catalogue.parsed("role-selection.enter").toLimbo()), javaMenu.microsoft.lore)
        assertEquals(listOf(java.catalogue.parsed("role-selection.enter").toLimbo()), javaMenu.premium.lore)
        assertEquals(0, java.services.avatarPrepareCalls)
        assertEquals(0, java.services.avatarOfferCalls)

        val disabled = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        val disabledPlayer = disabled.joinBedrock()
        disabled.services.runWork()
        assertEquals(null, disabledPlayer.limbo.menus.single().microsoft.avatarIcon)
        assertEquals(null, disabledPlayer.limbo.menus.single().premium.avatarIcon)
        assertEquals(0, disabled.services.avatarPrepareCalls)

        val single = Fixture(avatarConfig)
        val singlePlayer = single.joinBedrock(RoleOptions(listOf(single.dualRoles().roles.last()), premiumUuid))
        single.services.runWork()
        assertTrue(singlePlayer.limbo.menus.isEmpty())
        assertEquals(1, singlePlayer.transfers.size, "The one role's existing selection transfer must not wait for avatars")
        assertEquals(0, single.services.avatarPrepareCalls)
        assertEquals(0, single.services.avatarOfferCalls)
        assertEquals(0, single.services.grants)
    }

    @Test
    fun `avatar prepare failure malformed content or bounded queue rejection shows explicit fallback without authorization`() {
        for (failure in listOf("prepare", "future", "fingerprint", "prepare-queue", "status-queue")) {
            val fixture = Fixture(avatarConfig)
            when (failure) {
                "prepare" -> fixture.services.avatarPrepareFailure = IllegalStateException("presentation unavailable")
                "future" -> fixture.services.avatarPreparation.completeExceptionally(IllegalStateException("image unavailable"))
                "fingerprint" -> fixture.services.avatarPreparation.complete("invalid")
                "prepare-queue" -> fixture.services.duringRoles = { fixture.services.rejectSubmissions = true }
                "status-queue" -> {
                    fixture.services.avatarPreparation.complete(avatarFingerprint)
                    fixture.services.duringAvatarPrepare = { fixture.services.rejectSubmissions = true }
                }
            }
            val player = fixture.joinBedrock()
            fixture.services.runWork()
            val menu = player.limbo.menus.single()
            assertEquals(RoleAvatarIcon.MICROSOFT, menu.microsoft.avatarIcon, failure)
            assertEquals(RoleAvatarIcon.PREMIUM, menu.premium.avatarIcon, failure)
            assertTrue(player.limbo.messages.contains(fixture.catalogue.parsed("role-selection.avatar-unavailable").toLimbo()), failure)
            assertTrue(player.transfers.isEmpty(), failure)
            assertFalse(fixture.gate.isAuthenticated(player), failure)
            assertEquals(0, fixture.services.avatarOfferCalls, failure)
            assertEquals(0, fixture.services.grants, failure)
        }
    }

    @Test
    fun `avatar callbacks after disconnect stop deadline stale proof or queued status cannot open or refresh a menu`() {
        for (ending in listOf("disconnect", "stop", "deadline", "proof", "queued-status")) {
            val fixture = Fixture(avatarConfig + ("role-selection.timeout-seconds" to "5"))
            var current = true
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { current }
            val player = fixture.joinBedrock(proof = proof)
            fixture.services.runWork()
            assertEquals(1, fixture.services.avatarPrepareCalls)
            when (ending) {
                "disconnect" -> fixture.gate.onDisconnect(DisconnectEvent(player))
                "stop" -> fixture.gate.deactivate()
                "deadline" -> fixture.now += 5_000L
                "proof" -> current = false
                "queued-status" -> {
                    fixture.services.avatarPreparation.complete(avatarFingerprint)
                    fixture.gate.onDisconnect(DisconnectEvent(player))
                }
            }
            fixture.services.avatarPreparation.complete(avatarFingerprint)
            fixture.services.runWork()
            assertTrue(player.limbo.menus.isEmpty(), ending)
            assertTrue(player.transfers.isEmpty(), ending)
            assertEquals(0, fixture.services.avatarOfferCalls, ending)
            assertFalse(fixture.gate.isAuthenticated(player), ending)
            assertFalse(fixture.services.avatarPreparation.isCancelled, "Closing a connection must not cancel shared image preparation")
        }
    }

    @Test
    fun `late old avatar future cannot populate a new same Floodgate UUID connection`() {
        val fixture = Fixture(avatarConfig)
        fixture.services.avatarOffer = RoleAvatarOffer(UUID.randomUUID(), avatarFingerprint, false)
        val old = fixture.joinBedrock()
        fixture.services.runWork()
        val oldFuture = fixture.services.avatarPreparation
        fixture.gate.onDisconnect(DisconnectEvent(old))
        old.isActive = false
        fixture.services.avatarPreparation = CompletableFuture()
        val replacement = fixture.joinBedrock()
        fixture.services.runWork()
        assertEquals(old.uniqueId, replacement.uniqueId)
        oldFuture.complete(avatarFingerprint)
        fixture.services.runWork()
        assertTrue(old.limbo.menus.isEmpty())
        assertTrue(replacement.limbo.menus.isEmpty())
        assertEquals(0, fixture.services.avatarOfferCalls)
        fixture.services.avatarPreparation.complete(avatarFingerprint)
        fixture.services.runWork()
        assertEquals(1, replacement.limbo.menus.size)
        assertEquals(1, fixture.services.avatarOfferCalls)
        assertTrue(old.transfers.isEmpty())
        assertFalse(fixture.gate.isAuthenticated(replacement))
    }

    @Test
    fun `failed visual transfer falls back once and duplicate completion cannot retry or select a role`() {
        val fixture = Fixture(avatarConfig)
        val player = fixture.joinBedrock()
        player.transferResult = CompletableFuture.failedFuture(IllegalStateException("native transfer unavailable"))
        fixture.services.runWork()
        fixture.services.avatarPreparation.complete(avatarFingerprint)
        fixture.services.runWork()
        assertEquals(1, player.transfers.size)
        assertEquals(null, player.transfers.single().third)
        assertEquals(1, player.limbo.menus.size)
        assertTrue(player.limbo.messages.contains(fixture.catalogue.parsed("role-selection.avatar-unavailable").toLimbo()))
        assertFalse(fixture.services.avatarPreparation.complete(avatarFingerprint))
        fixture.services.runWork()
        assertEquals(1, player.transfers.size)
        assertFalse(fixture.gate.isAuthenticated(player))
        assertEquals(0, fixture.services.grants)
        assertEquals(0, fixture.services.premiumBinds)
    }

    @Test
    fun `avatar refresh budget is bounded across XUIDs and a full budget only changes presentation`() {
        val fixture = Fixture(avatarConfig + ("safety.pending-login-limit" to "1"))
        fixture.services.avatarPreparation.complete(avatarFingerprint)
        val first = fixture.joinBedrock()
        fixture.services.runWork()
        assertEquals(1, first.transfers.size)
        fixture.gate.onDisconnect(DisconnectEvent(first))
        first.isActive = false
        val otherIdentity = Identity.Microsoft(GameName.parseOrThrow("Other"), Xuid(2L), "Other")
        val otherOptions = RoleOptions(listOf(RoleOption(otherIdentity, BoundIdentity.of(otherIdentity, 1L), null),
            fixture.dualRoles().roles.last()), premiumUuid)
        val otherProof = BedrockProof.createLinked(Xuid(2L), premiumUuid, "Alex", "Other", Any()) { true }
        val other = fixture.joinBedrock(otherOptions, otherProof)
        fixture.services.runWork()
        assertTrue(other.transfers.isEmpty())
        assertEquals(1, other.limbo.menus.size)
        assertTrue(other.limbo.messages.contains(fixture.catalogue.parsed("role-selection.avatar-unavailable").toLimbo()))
        assertFalse(fixture.gate.isAuthenticated(other))
        fixture.assertBackendDenied(other)
    }

    @Test
    fun `open avatar menu name and skin refresh keeps the same session pack and true role profile`() {
        val fixture = Fixture(avatarConfig)
        fixture.services.avatarPreparation.complete(avatarFingerprint)
        fixture.services.avatarOffer = RoleAvatarOffer(UUID.randomUUID(), avatarFingerprint, false)
        val player = fixture.joinBedrock()
        fixture.services.runWork()
        val old = player.limbo.menus.single()
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first(),
            RoleOption(Identity.Premium(GameName.parseOrThrow("AlexNew"), premiumUuid), null,
                GameProfile.Property("textures", "new-official-texture", "new-signature"))), premiumUuid)
        fixture.gate.onRoleSelect(player.limbo, old.generation, "premium")
        fixture.services.runWork()
        val refreshed = player.limbo.menus.last()
        assertEquals(2, player.limbo.menus.size)
        assertEquals("AlexNew", refreshed.premium.profileName)
        assertEquals("new-official-texture", refreshed.premium.textureValue)
        assertEquals(RoleAvatarIcon.PREMIUM, refreshed.premium.avatarIcon)
        assertEquals(1, fixture.services.avatarPrepareCalls, "An open resource pack cannot be hot replaced by a menu/name refresh")
        assertEquals(1, fixture.services.avatarOfferCalls)
        assertTrue(player.transfers.isEmpty())
        assertFalse(fixture.gate.isAuthenticated(player))
    }

    @Test
    fun `visual avatar refresh still rejects a stale new proof or revoked current relationship`() {
        for (failure in listOf("proof", "relationship")) {
            val fixture = Fixture(avatarConfig)
            fixture.services.avatarPreparation.complete(avatarFingerprint)
            val first = fixture.joinBedrock()
            fixture.services.runWork()
            assertEquals(1, first.transfers.size)
            fixture.gate.onDisconnect(DisconnectEvent(first))
            first.isActive = false
            fixture.services.avatarOffer = RoleAvatarOffer(UUID.randomUUID(), avatarFingerprint, false)
            val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { failure != "proof" }
            if (failure == "proof") {
                val retry = fixture.player(777)
                val pre = PreLoginEvent(retry, proof.username, proof.uniqueId, BedrockLogin.Verified(proof))
                fixture.gate.onPreLogin(pre)
                assertFalse(pre.result.isAllowed)
                assertFalse(fixture.gate.isAuthenticated(retry))
            } else {
                val revoked = RoleOptions(listOf(fixture.dualRoles().roles.first()), null)
                val retry = fixture.joinBedrock(revoked, proof)
                fixture.services.runWork()
                assertTrue(retry.limbo.menus.isEmpty())
                assertTrue(retry.transfers.isEmpty())
                assertTrue(retry.disconnects.isNotEmpty())
                assertFalse(fixture.gate.isAuthenticated(retry))
            }
            assertEquals(1, fixture.services.avatarPrepareCalls, failure)
            assertEquals(1, fixture.services.avatarOfferCalls, failure)
            assertEquals(0, fixture.services.grants, failure)
            assertEquals(0, fixture.services.premiumBinds, failure)
        }
    }

    @Test
    fun `avatar loopback timeout can refresh once then shows unavailable without changing authentication`() {
        val fixture = Fixture(avatarConfig)
        fixture.services.avatarPreparation.complete(avatarFingerprint)
        fixture.services.avatarOfferFailure = java.net.SocketTimeoutException("isolated status timeout")
        val first = fixture.joinBedrock()
        fixture.services.runWork()
        assertEquals(1, first.transfers.size)
        fixture.gate.onDisconnect(DisconnectEvent(first))
        first.isActive = false
        val next = fixture.joinBedrock()
        fixture.services.runWork()
        assertTrue(next.transfers.isEmpty())
        assertEquals(1, next.limbo.menus.size)
        assertTrue(next.limbo.messages.contains(fixture.catalogue.parsed("role-selection.avatar-unavailable").toLimbo()))
        assertFalse(fixture.gate.isAuthenticated(next))
        assertEquals(0, fixture.services.grants)
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
        assertEquals(1, chooser.transfers.size)
        assertEquals(null, chooser.transfers.single().third)
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
        fixture.gate.onJoin(chooser.limbo)
        fixture.services.runWork()
        assertTrue(chooser.limbo.menus.isEmpty())
        assertEquals(1, chooser.transfers.size)
        chooser.isActive = false
        val fresh = BedrockProof.create(Xuid(1L), record.uuid, ".Steve", "Steve", ".", Any()) { true }
        val resumed = fixture.player(777).apply { username = fresh.username }
        fixture.login(resumed, bedrock = BedrockLogin.Verified(fresh))
        assertTrue(fixture.gate.isAuthenticated(resumed))
        assertEquals(".Steve", resumed.username)
        assertEquals(record.uuid, resumed.uniqueId)
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
            fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
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
    fun `role login rechecks relationship after final profile before claiming premium`() {
        val fixture = Fixture(roleConfig)
        val chooser = fixture.player(777)
        fixture.strongMicrosoft(chooser)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        val resumed = fixture.player(777)
        val pre = PreLoginEvent(resumed, "Steve", record.uuid, roleCookie = checkNotNull(chooser.transfers.single().third))
        fixture.gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        val profile = GameProfileRequestEvent(resumed, GameProfile(record.uuid, "Steve", emptyList()))
        fixture.gate.onPrepareGameProfile(profile)
        fixture.gate.onGameProfile(profile)
        resumed.uniqueId = profile.gameProfile.id
        resumed.username = profile.gameProfile.name
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.first()), null)
        val login = LoginEvent(resumed)
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
        fixture.strongMicrosoft(chooser)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "microsoft")
        fixture.services.runWork()
        assertTrue(chooser.transfers.isEmpty())
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
            fixture.login(chooser, bedrock = BedrockLogin.Verified(proof))
            fixture.gate.onJoin(chooser.limbo)
            fixture.services.runWork()
            assertTrue(chooser.transfers.isEmpty(), failure)
            assertTrue(chooser.disconnects.isNotEmpty(), failure)
            assertFalse(fixture.gate.isAuthenticated(chooser), failure)
        }
    }

    @Test
    fun `only linked premium role auto selects and is first bound only at final native admission`() {
        val fixture = Fixture(roleConfig + ("bedrock.enabled" to "true"))
        fixture.services.options = RoleOptions(listOf(fixture.dualRoles().roles.single { it.identity is Identity.Premium }), premiumUuid)
        val proof = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val chooser = fixture.player(777).apply { uniqueId = premiumUuid; username = "Alex" }
        fixture.login(chooser, bedrock = BedrockLogin.Verified(proof))
        fixture.gate.onJoin(chooser.limbo)
        fixture.services.runWork()
        assertTrue(chooser.limbo.menus.isEmpty())
        assertEquals(1, chooser.transfers.size)
        assertEquals(0, fixture.services.premiumBinds)
        chooser.isActive = false
        val fresh = BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }
        val resumed = fixture.player(777).apply { uniqueId = premiumUuid; username = "Alex" }
        fixture.login(resumed, bedrock = BedrockLogin.Verified(fresh))
        assertTrue(fixture.gate.isAuthenticated(resumed))
        assertEquals(premiumUuid, resumed.uniqueId)
        assertEquals("Alex", resumed.username)
        assertEquals(1, fixture.services.premiumBinds)
        assertEquals(0, fixture.services.bindCalls)
        assertEquals(0, fixture.services.grants)
    }

    @Test
    fun `each later ordinary Microsoft login proves XUID again and reopens both role choices`() {
        val fixture = Fixture(roleConfig)
        val first = fixture.player(777)
        fixture.strongMicrosoft(first)
        fixture.gate.onRoleSelect(first.limbo, first.limbo.menus.single().generation, "microsoft")
        fixture.services.runWork()
        first.isActive = false
        val resumed = fixture.player(777)
        fixture.login(resumed, checkNotNull(first.transfers.single().third))
        assertEquals(1, fixture.services.grants)
        resumed.isActive = false
        fixture.gate.onDisconnect(DisconnectEvent(resumed))
        fixture.services.decision = AuthCoordinator.Decision.Admit(record.toIdentity(), record)
        val next = fixture.player(777)
        fixture.strongMicrosoft(next)
        assertFalse(fixture.gate.isAuthenticated(next))
        assertEquals(1, next.limbo.menus.size)
        assertEquals(2, fixture.services.beginCalls)
        assertEquals(1, fixture.services.grants)
    }

    @Test
    fun `failed native transfer revokes the issued Java cookie instead of authorizing its frontend`() {
        val fixture = Fixture(roleConfig)
        val chooser = fixture.player(777)
        chooser.transferResult = CompletableFuture<Unit>().also { it.completeExceptionally(IllegalStateException("write failed")) }
        fixture.strongMicrosoft(chooser)
        fixture.gate.onRoleSelect(chooser.limbo, chooser.limbo.menus.single().generation, "premium")
        fixture.services.runWork()
        val cookie = checkNotNull(chooser.transfers.single().third)
        assertFalse(fixture.gate.isAuthenticated(chooser))
        assertTrue(chooser.disconnects.isNotEmpty())
        val replay = PreLoginEvent(fixture.player(777), "Steve", record.uuid, roleCookie = cookie)
        fixture.gate.onPreLogin(replay)
        assertFalse(replay.result.isAllowed)
        assertEquals(0, fixture.services.premiumBinds)
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

        fun player(version: Int, sessionId: UUID = UUID.randomUUID()) =
            FakePlayer(version, sessionId).also { players += it }

        fun login(player: FakePlayer, cookie: ByteArray? = null, bedrock: BedrockLogin = BedrockLogin.Java) {
            val pre = PreLoginEvent(player, player.username, player.uniqueId, bedrock, cookie)
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

        fun strongMicrosoft(player: FakePlayer, options: RoleOptions = dualRoles()) {
            services.options = options
            login(player)
            gate.onJoin(player.limbo)
            services.runWork()
            services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            services.advanceBy(1_000L)
        }

        fun joinBedrock(options: RoleOptions = dualRoles(), proof: BedrockProof =
            BedrockProof.createLinked(Xuid(1L), premiumUuid, "Alex", "Steve", Any()) { true }): FakePlayer {
            services.options = options
            val player = player(777).apply { uniqueId = proof.uniqueId; username = proof.username }
            login(player, bedrock = BedrockLogin.Verified(proof))
            gate.onJoin(player.limbo)
            return player
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
            var avatarPreparation = CompletableFuture<String>()
            var avatarOffer: RoleAvatarOffer? = null
            var avatarPrepareCalls = 0
            var avatarOfferCalls = 0
            var avatarPrepareFailure: Exception? = null
            var avatarOfferFailure: Exception? = null
            var duringAvatarPrepare: () -> Unit = {}
            var duringAvatarOffer: () -> Unit = {}
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
            override fun prepareRoleAvatars(xuid: Xuid, options: RoleOptions): CompletableFuture<String> {
                avatarPrepareCalls++
                duringAvatarPrepare()
                avatarPrepareFailure?.let { throw it }
                return avatarPreparation
            }
            override fun roleAvatarOffer(xuid: Xuid): RoleAvatarOffer? {
                avatarOfferCalls++
                duringAvatarOffer()
                avatarOfferFailure?.let { throw it }
                return avatarOffer
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
        val transfers = mutableListOf<Triple<String, Int, ByteArray?>>()
        var transferResult = CompletableFuture.completedFuture(Unit)
        override fun transferRole(host: String, port: Int, cookie: ByteArray?): CompletableFuture<Unit> {
            transfers += Triple(host, port, cookie)
            return transferResult
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
        val roleConfig = mapOf("role-selection.enabled" to "true", "role-selection.reconnect-host" to "proxy.invalid")
        val avatarConfig = roleConfig + mapOf("bedrock.enabled" to "true", "role-selection.avatars.enabled" to "true")
        val avatarFingerprint = "a".repeat(64)
        fun limboName(version: Int) = "${LoginGate.LIMBO_SERVER_NAME}-$version"
    }
}
