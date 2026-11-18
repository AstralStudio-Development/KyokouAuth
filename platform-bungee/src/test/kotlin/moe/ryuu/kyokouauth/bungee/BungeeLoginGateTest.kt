package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import moe.ryuu.kyokouauth.proxy.BedrockFailure
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import moe.ryuu.kyokouauth.proxy.BedrockProof
import moe.ryuu.kyokouauth.proxy.GameProfile
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.ProxyLog
import net.md_5.bungee.api.event.*
import net.md_5.bungee.api.plugin.Plugin
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.*

class BungeeLoginGateTest {
    private class Harness(
        bedrockEnabled: Boolean = false,
        bedrock: (BungeePlayer) -> BedrockLogin = { BedrockLogin.Java },
        rolesEnabled: Boolean = false,
    ) {
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy)
        val fixtures = java.util.IdentityHashMap<net.md_5.bungee.api.connection.PendingConnection, PendingFixture>()
        val services = TestServices()
        val profiles = TestProfiles().also(platform::installProfiles)
        val closeWatchers = java.util.IdentityHashMap<BungeePlayer, () -> Unit>()
        val authority = PrivateTicketAuthority()
        val tickets = BungeeTickets(platform, profiles, authority)
        val log = ProxyLog { _, _, _ -> }
        val config = ProxyConfig(mapOf("post-login.server" to "backend", "bedrock.enabled" to bedrockEnabled.toString(),
            "role-selection.enabled" to rolesEnabled.toString()), File("build/bungee-tests"))
        val gate = LoginGate(platform, config, MessageCatalogue("en_US", File("build/bungee-tests/messages")), services, log)
        var accepting = true
        val adapter = BungeeLoginGate(object : Plugin(proxy, net.md_5.bungee.api.plugin.PluginDescription()) {}, platform, profiles, tickets, gate, log,
            services::submit, services::schedule, { accepting }, bedrock = bedrock, rolesEnabled = rolesEnabled,
            watchClosed = { player, action ->
                closeWatchers[player] = action
                AutoCloseable { if (closeWatchers[player] === action) closeWatchers.remove(player) }
            },
            prepareRole = { player, profile ->
                val fixture = checkNotNull(fixtures[player.pending])
                fixture.pending.uniqueId = profile.id
                fixture.name = profile.name
                player.rememberRoleProfile(profile)
                profiles.apply(player.pending, profile)
            })
        val backend = proxy.constructServerInfo("backend", InetSocketAddress("127.0.0.1", 29001), "test", false)
        init {
            if (rolesEnabled && bedrockEnabled) {
                val local = moe.ryuu.kyokouauth.core.identity.Identity.Microsoft(
                    moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow("OracleLocal"),
                    moe.ryuu.kyokouauth.core.identity.Xuid(2535412345678901L), "Controlled")
                val premium = moe.ryuu.kyokouauth.core.identity.Identity.Premium(
                    moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow("PremiumRole"),
                    UUID.fromString("f4688ebd-ccf7-4dea-922e-b18f68014f71"))
                services.roles = moe.ryuu.kyokouauth.proxy.RoleOptions(listOf(
                    moe.ryuu.kyokouauth.proxy.RoleOption(local, moe.ryuu.kyokouauth.core.storage.BoundIdentity.of(local, 1L), null),
                    moe.ryuu.kyokouauth.proxy.RoleOption(premium, null, null)), premium.uuid)
            }
            proxy.registered["backend"] = backend
            (767..777).forEach { platform.registerEngine(it, InetSocketAddress("127.0.0.1", 30000 + it)) }
            gate.activate()
        }
        fun pre(fixture: PendingFixture): PreLoginEvent {
            fixtures[fixture.pending] = fixture
            profiles.add(fixture)
            val event = PreLoginEvent(fixture.pending, null)
            adapter.onPreLogin(event)
            services.drain()
            return event
        }
        fun login(fixture: PendingFixture) {
            assertFalse(pre(fixture).isCancelled)
            val login = LoginEvent(fixture.pending, null)
            adapter.onLogin(login)
            services.drain()
            assertFalse(login.isCancelled)
            proxy.online += fixture.player
            adapter.onPostLogin(PostLoginEvent(fixture.player, backend, null))
        }
    }

    @Test fun `external cancellation during queued prelogin closes physical connection and completes once`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.fixtures[fixture.pending] = fixture
        h.profiles.add(fixture)
        var callbacks = 0
        val event = PreLoginEvent(fixture.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        event.isCancelled = true
        h.services.drain()
        assertEquals(1, callbacks)
        assertFalse(fixture.connected)
        assertTrue(fixture.setterOrder.isEmpty())
        assertNull(h.platform.find(fixture.pending))
        h.adapter.close()
        assertEquals(1, callbacks)
    }

    @Test fun `enabling Bedrock roles leaves Java on ordinary Microsoft admission without cookie or chooser`() {
        val h = Harness(rolesEnabled = true)
        val fixture = PendingFixture()
        assertFalse(h.pre(fixture).isCancelled)
        val player = assertNotNull(h.platform.find(fixture.pending))
        assertEquals(0, fixture.cookieRequests)
        assertEquals("OracleLocal", fixture.name)
        assertEquals(h.services.stableUuid, fixture.uuid)
        assertEquals("preserved", h.profiles.read(fixture.pending).properties.single { it.name == "textures" }.value)
        assertNull(player.currentRoleProfile())
        assertFalse(h.gate.requiresRoleProfile(player))
        h.adapter.close()
    }

    @Test fun `Java role flag retains Mojang handshake mode without asking for a role cookie`() {
        val h = Harness(rolesEnabled = true)
        val fixture = PendingFixture()
        h.services.decision = AuthCoordinator.Decision.MojangSession(assertNotNull(fixture.uuid))
        assertFalse(h.pre(fixture).isCancelled)
        assertEquals(0, fixture.cookieRequests)
        assertTrue(fixture.online)
        assertEquals("OracleLocal", fixture.name)
        assertFalse(h.gate.requiresRoleProfile(assertNotNull(h.platform.find(fixture.pending))))
        h.adapter.close()
    }

    @Test fun `exact half-login close cancels only its queued admission and never waits for cookie response`() {
        val h = Harness(rolesEnabled = true)
        val fixture = PendingFixture()
        h.fixtures[fixture.pending] = fixture
        h.profiles.add(fixture)
        var callbacks = 0
        val event = PreLoginEvent(fixture.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        val player = assertNotNull(h.platform.find(fixture.pending))
        val closed = assertNotNull(h.closeWatchers[player])
        fixture.connected = false
        closed()
        assertEquals(1, callbacks)
        assertTrue(event.isCancelled)
        assertTrue(h.closeWatchers.isEmpty())
        assertTrue(fixture.setterOrder.isEmpty())
        assertEquals(0, fixture.cookieRequests)
        h.services.drain()
        assertEquals(1, callbacks)
        assertTrue(fixture.setterOrder.isEmpty())
        h.adapter.close()
    }

    @Test fun `FG roles keep native proof separate from chooser and reset replaced skins at Login PostLogin and backend boundary`() {
        val fixture = linkedBedrockFixture()
        val proof = linkedBedrockProof(fixture, Any())
        val h = Harness(bedrockEnabled = true, rolesEnabled = true, bedrock = { BedrockLogin.Verified(proof) })
        assertFalse(h.pre(fixture).isCancelled)
        val player = assertNotNull(h.platform.find(fixture.pending))
        val chooser = assertNotNull(player.currentRoleProfile())
        assertEquals(proof.uniqueId, fixture.uuid, "Official Floodgate messages must resolve this Player")
        assertEquals(proof.username, fixture.name, "A proven candidate identity is retained while chooser stays HELD")
        assertFalse(h.gate.isAuthenticated(player))
        assertEquals(0, fixture.cookieRequests)
        assertSame(proof, player.currentBedrockProof())
        h.profiles.apply(fixture.pending, chooser.withProperties(chooser.properties +
            GameProfile.Property("textures", "unwanted-linked-skin", "old") + GameProfile.Property("pre-extra", "keep", "")))
        val mutations = fixture.setterOrder.toList()
        val login = LoginEvent(fixture.pending, null)
        h.adapter.onLogin(login)
        h.services.drain()
        assertFalse(login.isCancelled)
        assertEquals(mutations, fixture.setterOrder, "FINISHING must never rewrite UUID/name")
        assertTrue(h.profiles.read(fixture.pending).properties.none { it.name == "textures" })
        val preExtras = h.profiles.read(fixture.pending).properties.filterNot { it.name == "textures" }
        h.profiles.apply(fixture.pending, chooser.withProperties(preExtras + listOf(
            GameProfile.Property("textures", "FG-PostLogin-skin", "old"), GameProfile.Property("post-extra", "keep", ""))))
        h.adapter.onPostLogin(PostLoginEvent(fixture.player, h.backend, null))
        assertTrue(h.profiles.read(fixture.pending).properties.none { it.name == "textures" })
        assertTrue(h.profiles.read(fixture.pending).properties.any { it.name == "pre-extra" })
        assertTrue(h.profiles.read(fixture.pending).properties.any { it.name == "post-extra" })
        val engine = assertNotNull(h.platform.nativeServer(h.platform.getServer("kyokouauth-limbo-777").orElseThrow()))
        h.profiles.apply(fixture.pending, h.profiles.read(fixture.pending).withProperties(
            h.profiles.read(fixture.pending).properties + GameProfile.Property("textures", "later-plugin-skin", "old")))
        val connect = ServerConnectEvent(fixture.player, engine, ServerConnectEvent.Reason.JOIN_PROXY)
        h.adapter.onServerConnect(connect)
        assertFalse(connect.isCancelled)
        assertTrue(h.profiles.read(fixture.pending).properties.none { it.name == "textures" })
        assertEquals("PremiumRole", proof.username)
        assertSame(proof, player.currentBedrockProof())
        h.adapter.close()
    }

    @Test fun `role identity changes during FINISHING are refused instead of repaired`() {
        for (field in listOf("uuid", "name")) {
            val fixture = linkedBedrockFixture()
            val proof = linkedBedrockProof(fixture, Any())
            val h = Harness(bedrockEnabled = true, rolesEnabled = true, bedrock = { BedrockLogin.Verified(proof) })
            assertFalse(h.pre(fixture).isCancelled)
            val setters = fixture.setterOrder.toList()
            if (field == "uuid") fixture.uuid = UUID.randomUUID() else fixture.name = "TamperedRole"
            val event = LoginEvent(fixture.pending, null)
            h.adapter.onLogin(event)
            h.services.drain()
            assertTrue(event.isCancelled)
            assertFalse(fixture.connected)
            assertEquals(setters, fixture.setterOrder)
            h.adapter.close()
        }
    }

    @Test fun `single Bedrock role settles actual profile before Player with zero Transfer and no cookie`() {
        val fixture = bedrockFixture()
        val proof = bedrockProof(fixture, Any())
        val h = Harness(bedrockEnabled = true, rolesEnabled = true, bedrock = { BedrockLogin.Verified(proof) })
        val identity = moe.ryuu.kyokouauth.core.identity.Identity.Microsoft(
            moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow("OracleLocal"),
            moe.ryuu.kyokouauth.core.identity.Xuid(h.services.stableUuid.leastSignificantBits), "Controlled")
        val record = moe.ryuu.kyokouauth.core.storage.BoundIdentity.of(identity, 1L)
        val skin = GameProfile.Property("textures", "explicit-role-skin", "role-signature")
        h.services.roles = moe.ryuu.kyokouauth.proxy.RoleOptions(listOf(
            moe.ryuu.kyokouauth.proxy.RoleOption(identity, record, skin)), null)
        h.login(fixture)
        assertEquals(identity.uuid, fixture.uuid)
        assertEquals("OracleLocal", fixture.name)
        assertEquals(listOf(skin), h.profiles.read(fixture.pending).properties.filter { it.name == "textures" })
        val player = assertNotNull(h.platform.find(fixture.player))
        assertTrue(h.gate.isAuthenticated(player))
        val mutations = fixture.setterOrder.toList()
        val backend = ServerConnectEvent(fixture.player, h.backend, ServerConnectEvent.Reason.JOIN_PROXY)
        h.adapter.onServerConnect(backend)
        assertFalse(backend.isCancelled)
        assertEquals(mutations, fixture.setterOrder, "The admitted Player's identity must remain immutable")
        assertEquals(0, fixture.cookieRequests)
        assertTrue(fixture.storedCookies.isEmpty())
        assertTrue(fixture.transfers.isEmpty())
        assertEquals(0, h.services.premiumBindings)
        h.adapter.close()
    }

    @Test fun `same-identity Bedrock click authorizes current Player and late skin guard keeps selected profile`() {
        val fixture = linkedBedrockFixture()
        val proof = linkedBedrockProof(fixture, Any())
        val h = Harness(bedrockEnabled = true, rolesEnabled = true, bedrock = { BedrockLogin.Verified(proof) })
        h.services.authorizePremium = true
        h.login(fixture)
        val player = assertNotNull(h.platform.find(fixture.player))
        assertFalse(h.gate.isAuthenticated(player))
        val ready = ReadyPlayer(player)
        h.gate.onJoin(ready)
        h.services.drain()
        val generation = assertNotNull(ready.menu).generation
        val setters = fixture.setterOrder.toList()
        val id = fixture.uuid
        val name = fixture.name
        h.gate.onRoleSelect(ready, generation, "premium")
        h.services.drain()
        assertTrue(h.gate.isAuthenticated(player))
        assertTrue(fixture.connected)
        assertEquals(id, fixture.uuid)
        assertEquals(name, fixture.name)
        assertEquals(setters, fixture.setterOrder)
        assertEquals(listOf(h.backend), fixture.connects)
        assertTrue(fixture.transfers.isEmpty())
        assertTrue(fixture.storedCookies.isEmpty())
        val extra = GameProfile.Property("after-selection-extra", "keep", "")
        val expected = h.profiles.read(fixture.pending)
        h.profiles.apply(fixture.pending, expected.withProperties(expected.properties + extra +
            GameProfile.Property("textures", "wrong-FG-late-skin", "old")))
        val connect = ServerConnectEvent(fixture.player, h.backend, ServerConnectEvent.Reason.PLUGIN)
        h.adapter.onServerConnect(connect)
        assertFalse(connect.isCancelled)
        assertEquals(emptyList(), h.profiles.read(fixture.pending).properties.filter { it.name == "textures" })
        assertTrue(extra in h.profiles.read(fixture.pending).properties)
        assertEquals(setters, fixture.setterOrder)
        assertSame(proof, player.currentBedrockProof())
        h.adapter.close()
    }

    @Test fun `closing adapter refuses fresh FG roles even when outer accepting flag remains true`() {
        var probes = 0
        val fixture = bedrockFixture()
        val proof = bedrockProof(fixture, Any())
        val h = Harness(bedrockEnabled = true, rolesEnabled = true, bedrock = { probes++; BedrockLogin.Verified(proof) })
        h.adapter.close()
        assertTrue(h.pre(fixture).isCancelled)
        assertEquals(0, probes)
        assertEquals(0, fixture.cookieRequests)
        assertNull(h.platform.find(fixture.pending))
    }

    @Test fun `intent holds USERNAME and stable UUID is applied after forcing offline`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.profiles.add(fixture)
        var callbacks = 0
        val event = PreLoginEvent(fixture.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        assertEquals(0, callbacks)
        assertTrue(fixture.setterOrder.isEmpty())
        val bridge = h.platform.find(fixture.pending)
        h.services.drain()
        assertEquals(1, callbacks)
        assertEquals(listOf("online:false", "uuid"), fixture.setterOrder)
        assertEquals(0, fixture.cookieRequests, "Feature-off Java must not request cookies or initialize a role bridge")
        assertEquals(h.services.stableUuid, fixture.uuid)
        assertSame(bridge, h.platform.find(fixture.pending))
        assertEquals("preserved", h.profiles.read(fixture.pending).properties.first { it.name == "textures" }.value)
    }

    private fun bedrockProof(fixture: PendingFixture, source: Any, current: () -> Boolean = { fixture.connected }): BedrockProof =
        BedrockProof.create(moe.ryuu.kyokouauth.core.identity.Xuid(2535412345678901L),
            UUID(0L, 2535412345678901L), fixture.name, "Controlled", ".", source, current)

    private fun linkedBedrockFixture(): PendingFixture = PendingFixture("PremiumRole").also {
        it.uuid = UUID.fromString("f4688ebd-ccf7-4dea-922e-b18f68014f71")
        it.profile = GameProfile(checkNotNull(it.uuid), it.name, it.profile.properties)
    }

    private fun linkedBedrockProof(fixture: PendingFixture, source: Any): BedrockProof = BedrockProof.createLinked(
        moe.ryuu.kyokouauth.core.identity.Xuid(2535412345678901L), checkNotNull(fixture.uuid), fixture.name,
        "Controlled", source) { fixture.connected }

    private fun bedrockFixture(): PendingFixture = PendingFixture(".Controlled").also {
        it.uuid = UUID(0L, 2535412345678901L)
        it.profile = GameProfile(checkNotNull(it.uuid), it.name, it.profile.properties)
    }

    @Test fun `Bedrock samples exact source before async and again at profile and Login without rewriting Floodgate profile`() {
        val fixture = bedrockFixture()
        val source = Any()
        val samples = mutableListOf<BungeePlayer>()
        val h = Harness(bedrockEnabled = true, bedrock = { player ->
            samples += player
            BedrockLogin.Verified(bedrockProof(fixture, source))
        })
        h.profiles.add(fixture)
        var preCallbacks = 0
        val pre = PreLoginEvent(fixture.pending) { _, _ -> preCallbacks++ }
        h.adapter.onPreLogin(pre)
        pre.postCall()
        assertEquals(1, samples.size, "PreLogin proof must be read synchronously after Floodgate LOWEST")
        assertEquals(0, preCallbacks)
        h.services.drain()
        assertFalse(pre.isCancelled)
        assertEquals(2, samples.size)
        assertEquals(1, preCallbacks)
        assertTrue(fixture.setterOrder.isEmpty(), "Floodgate already fixed offline UUID/name in USERNAME")
        assertSame(fixture.profile, h.profiles.read(fixture.pending))
        val nativeProof = assertNotNull(h.platform.find(fixture.pending)?.currentBedrockProof())
        assertEquals(fixture.uuid, nativeProof.uniqueId)
        assertEquals(fixture.name, nativeProof.username)
        var loginCallbacks = 0
        val login = LoginEvent(fixture.pending) { _, _ -> loginCallbacks++ }
        h.adapter.onLogin(login)
        login.postCall()
        assertEquals(3, samples.size, "Login must use a fresh native read, not the PreLogin proof")
        h.services.drain()
        assertFalse(login.isCancelled)
        assertEquals(1, loginCallbacks)
        assertTrue(samples.all { it === samples.first() })
        assertTrue(fixture.setterOrder.isEmpty(), "Login must not repair UUID or authentication mode")
        assertEquals(0, h.services.premiumBindings)
        assertTrue(h.services.granted.isEmpty())
        val post = PostLoginEvent(fixture.player, h.backend, null)
        h.adapter.onPostLogin(post)
        assertSame(h.backend, post.target)
        val connect = ServerConnectEvent(fixture.player, h.backend, ServerConnectEvent.Reason.JOIN_PROXY)
        h.adapter.onServerConnect(connect)
        assertFalse(connect.isCancelled)
        assertTrue(h.profiles.read(fixture.pending).properties.none { it.name == h.authority.propertyKey })
    }

    @Test fun `Bedrock source replacement and rejected fresh reads fail closed without Java fallback`() {
        for (stage in listOf("profile", "Login")) for (failure in listOf("rejected", "wrong channel")) {
            val fixture = bedrockFixture()
            val source = Any()
            var samples = 0
            val h = Harness(bedrockEnabled = true, bedrock = {
                samples++
                if (samples == (if (stage == "profile") 2 else 3)) {
                    if (failure == "rejected") BedrockLogin.Rejected(BedrockFailure.STALE)
                    else BedrockLogin.Verified(bedrockProof(fixture, Any()))
                } else BedrockLogin.Verified(bedrockProof(fixture, source))
            })
            val pre = h.pre(fixture)
            val rejected = if (stage == "profile") pre.isCancelled else {
                assertFalse(pre.isCancelled)
                val login = LoginEvent(fixture.pending, null)
                h.adapter.onLogin(login)
                h.services.drain()
                login.isCancelled
            }
            assertTrue(rejected, stage)
            assertFalse(fixture.connected, stage)
            assertTrue(fixture.setterOrder.isEmpty(), stage)
            assertEquals(0, h.services.premiumBindings, stage)
            assertEquals(0, h.gate.verifyingCount(), stage)
            assertNull(h.platform.find(fixture.pending), stage)
        }
    }

    @Test fun `Bedrock native UUID and profile mismatch are refused rather than repaired`() {
        for (mismatch in listOf("uuid", "profile uuid", "profile name", "online")) {
            val fixture = bedrockFixture()
            val source = Any()
            val h = Harness(bedrockEnabled = true, bedrock = { BedrockLogin.Verified(bedrockProof(fixture, source)) })
            when (mismatch) {
                "uuid" -> fixture.uuid = UUID.randomUUID()
                "profile uuid" -> fixture.profile = fixture.profile.withId(UUID.randomUUID())
                "profile name" -> fixture.profile = fixture.profile.copy(name = ".Other")
                "online" -> fixture.online = true
            }
            assertTrue(h.pre(fixture).isCancelled, mismatch)
            assertFalse(fixture.connected, mismatch)
            assertTrue(fixture.setterOrder.isEmpty(), mismatch)
        }
    }

    @Test fun `Bedrock provider disappearing after Login revokes backend route`() {
        val fixture = bedrockFixture()
        val source = Any()
        var current = true
        val h = Harness(bedrockEnabled = true, bedrock = {
            BedrockLogin.Verified(bedrockProof(fixture, source) { current && fixture.connected })
        })
        h.login(fixture)
        current = false
        assertNull(h.platform.find(fixture.player)?.currentBedrockProof(), "Native skin-only view must not outlive its provider")
        val connect = ServerConnectEvent(fixture.player, h.backend, ServerConnectEvent.Reason.PLUGIN)
        h.adapter.onServerConnect(connect)
        assertTrue(connect.isCancelled)
        assertFalse(fixture.connected)
        assertEquals(0, h.services.premiumBindings)
    }

    @Test fun `already cancelled native events keep original reason and never read provider or mutate UUID`() {
        var samples = 0
        val h = Harness(bedrock = { samples++; error("Cancelled native event must not be reinterpreted") })
        val fixture = PendingFixture()
        val reason = net.md_5.bungee.api.chat.TextComponent("another plugin refused")
        val pre = PreLoginEvent(fixture.pending, null)
        pre.isCancelled = true
        pre.reason = reason
        h.adapter.onPreLogin(pre)
        assertSame(reason, pre.reason)
        assertNull(h.platform.find(fixture.pending))
        val login = LoginEvent(fixture.pending, null)
        login.isCancelled = true
        login.reason = reason
        h.adapter.onLogin(login)
        assertSame(reason, login.reason)
        assertEquals(0, samples)
        assertTrue(fixture.setterOrder.isEmpty())
    }

    @Test fun `native cancellation during an outstanding intent preserves refusal and completes once`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.profiles.add(fixture)
        var callbacks = 0
        val pre = PreLoginEvent(fixture.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(pre)
        pre.postCall()
        val reason = net.md_5.bungee.api.chat.TextComponent("late native refusal")
        pre.isCancelled = true
        pre.reason = reason
        h.services.drain()
        h.services.tasks.forEach { it.fire() }
        assertTrue(pre.isCancelled)
        assertSame(reason, pre.reason)
        assertEquals(1, callbacks)
        assertTrue(fixture.setterOrder.isEmpty())
        assertNull(h.platform.find(fixture.pending))
    }

    @Test fun `native proof read failure refuses before starting async work or mutating USERNAME`() {
        val h = Harness(bedrock = { error("injected native source failure") })
        val fixture = PendingFixture()
        h.profiles.add(fixture)
        var callbacks = 0
        val event = PreLoginEvent(fixture.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        h.services.drain()
        assertTrue(event.isCancelled)
        assertEquals(1, callbacks)
        assertTrue(fixture.setterOrder.isEmpty())
        assertNull(h.platform.find(fixture.pending))
    }

    @Test fun `explicit native Bedrock rejection cannot fall back to a Java-looking name`() {
        for (reason in listOf(BedrockFailure.DISABLED, BedrockFailure.PROVIDER_DENIED, BedrockFailure.UNAVAILABLE)) {
            val h = Harness(bedrockEnabled = true, bedrock = { BedrockLogin.Rejected(reason) })
            val fixture = PendingFixture()
            assertTrue(h.pre(fixture).isCancelled, reason.name)
            assertTrue(fixture.setterOrder.isEmpty(), reason.name)
            assertEquals(0, h.gate.verifyingCount(), reason.name)
            assertEquals(0, h.services.premiumBindings, reason.name)
        }
    }

    @Test fun `Login and PostLogin retain the same exact player and protocol engine`() {
        for (protocol in 767..777) {
            val h = Harness()
            val fixture = PendingFixture(protocol = protocol)
            assertFalse(h.pre(fixture).isCancelled)
            val bridge = h.platform.find(fixture.pending)
            h.adapter.onLogin(LoginEvent(fixture.pending, null))
            h.services.drain()
            val post = PostLoginEvent(fixture.player, h.backend, null)
            h.adapter.onPostLogin(post)
            assertSame(bridge, h.platform.find(fixture.player))
            assertEquals("kyokouauth-limbo-$protocol", post.target.name)
            assertNull(h.proxy.getServerInfo(post.target.name))
            assertSame(h.platform.getServer(post.target.name).orElseThrow(), h.platform.commonServer(post.target))
            assertEquals(0, h.gate.verifyingCount(), "Native post-login/TCP completion is not Limbo READY")
        }
    }

    @Test fun `held connections fail closed for every connect reason and fallback has no current server`() {
        for (reason in ServerConnectEvent.Reason.entries) {
            val h = Harness()
            val fixture = PendingFixture()
            h.login(fixture)
            assertNull(fixture.player.server)
            val event = ServerConnectEvent(fixture.player, h.backend, reason)
            h.adapter.onServerConnect(event)
            assertTrue(event.isCancelled, reason.name)
            assertFalse(fixture.connected, reason.name)
            assertEquals(0, h.gate.verifyingCount())
            assertTrue(h.profiles.read(fixture.pending).properties.none { it.name == h.authority.propertyKey })
        }
    }

    @Test fun `wrong private engine forged object and public same-name conflict fail closed`() {
        for (attempt in listOf("wrong protocol", "forged object", "public conflict")) {
            val h = Harness()
            val fixture = PendingFixture()
            h.login(fixture)
            val name = if (attempt == "wrong protocol") "kyokouauth-limbo-767" else "kyokouauth-limbo-777"
            val privateTarget = assertNotNull(h.platform.nativeServer(h.platform.getServer(name).orElseThrow()))
            val target = if (attempt == "forged object")
                h.proxy.constructServerInfo(name, InetSocketAddress("127.0.0.1", 9), "forged", true) else privateTarget
            if (attempt == "public conflict") {
                h.proxy.registered[name] = h.proxy.constructServerInfo(name, InetSocketAddress("127.0.0.1", 9), "public conflict", false)
            }
            val event = ServerConnectEvent(fixture.player, target, ServerConnectEvent.Reason.PLUGIN)
            h.adapter.onServerConnect(event)
            assertTrue(event.isCancelled, attempt)
            assertFalse(fixture.connected, attempt)
        }
    }

    @Test fun `timeout completes intent once and late work never mutates the pending connection`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.profiles.add(fixture)
        var callbacks = 0
        val event = PreLoginEvent(fixture.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        h.services.tasks.first().fire()
        assertEquals(1, callbacks)
        assertTrue(event.isCancelled)
        assertFalse(fixture.connected)
        h.services.drain()
        h.services.tasks.first().fire()
        assertEquals(1, callbacks)
        assertTrue(fixture.setterOrder.isEmpty())
        assertNull(h.platform.find(fixture.pending))
    }

    @Test fun `half-login disconnect releases intent and cannot affect replacement exact connection`() {
        val h = Harness()
        val old = PendingFixture()
        h.profiles.add(old)
        var callbacks = 0
        val event = PreLoginEvent(old.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        old.connected = false
        h.adapter.sweepClosed()
        assertEquals(1, callbacks)
        val fresh = PendingFixture()
        h.login(fresh)
        h.services.drain()
        h.adapter.sweepClosed()
        assertTrue(fresh.connected)
        assertEquals(1, callbacks)
        assertNotEquals(h.platform.find(fresh.pending)?.sessionId, h.platform.find(old.pending)?.sessionId)
    }

    @Test fun `native online-mode tampering never downgrades to device-code or premium binding`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.services.decision = AuthCoordinator.Decision.MojangSession(UUID.randomUUID())
        assertFalse(h.pre(fixture).isCancelled)
        assertEquals(listOf("online:true"), fixture.setterOrder)
        fixture.online = false
        val login = LoginEvent(fixture.pending, null)
        h.adapter.onLogin(login)
        h.services.drain()
        assertTrue(login.isCancelled)
        assertFalse(fixture.connected)
        assertEquals(0, h.services.premiumBindings)
        assertEquals(0, h.gate.verifyingCount())
        assertEquals(listOf("online:true"), fixture.setterOrder)
    }

    @Test fun `premium final UUID mismatch is refused before a storage claim`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.services.decision = AuthCoordinator.Decision.MojangSession(UUID.randomUUID())
        assertFalse(h.pre(fixture).isCancelled)
        val event = LoginEvent(fixture.pending, null)
        h.adapter.onLogin(event)
        h.services.drain()
        assertTrue(event.isCancelled)
        assertEquals(0, h.services.premiumBindings)
    }

    private class ReadyPlayer(private val frontend: BungeePlayer) : moe.ryuu.kyokouauth.limbo.LimboPlayer {
        override val name get() = frontend.username
        override val uuid get() = frontend.uniqueId
        override val connectionId get() = frontend.sessionId
        override val address get() = frontend.remoteAddress.address.hostAddress
        var titles = 0
        var clears = 0
        var kicked = false
        var menu: moe.ryuu.kyokouauth.limbo.RoleSelectorMenu? = null
        override fun openRoleSelector(menu: moe.ryuu.kyokouauth.limbo.RoleSelectorMenu) { this.menu = menu }
        override fun closeRoleSelector() { menu = null }
        override fun sendMessage(message: moe.ryuu.kyokouauth.limbo.protocol.TextComponent) = Unit
        override fun showTitle(title: moe.ryuu.kyokouauth.limbo.protocol.TextComponent,
            subtitle: moe.ryuu.kyokouauth.limbo.protocol.TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) { titles++ }
        override fun clearTitle() { clears++ }
        override fun disconnect(reason: moe.ryuu.kyokouauth.limbo.protocol.TextComponent) { kicked = true }
        override fun close() = Unit
    }

    @Test fun `stable Microsoft verification only releases after trustworthy join and strips ticket on backend event`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.services.authorize = true
        h.login(fixture)
        val frontend = h.platform.find(fixture.player)!!
        val engine = assertNotNull(h.platform.nativeServer(h.platform.getServer("kyokouauth-limbo-777").orElseThrow()))
        val connect = ServerConnectEvent(fixture.player, engine, ServerConnectEvent.Reason.JOIN_PROXY)
        h.adapter.onServerConnect(connect)
        assertFalse(connect.isCancelled)
        assertTrue(fixture.connects.isEmpty())
        assertEquals(0, h.gate.verifyingCount())
        val ready = ReadyPlayer(frontend)
        h.gate.onJoin(ready)
        h.services.drain()
        assertEquals(1, ready.titles)
        h.services.tasks.last().fire()
        h.services.drain()
        assertEquals(listOf(h.services.stableUuid), h.services.granted)
        assertEquals(listOf(h.backend), fixture.connects)
        val backend = ServerConnectEvent(fixture.player, h.backend, ServerConnectEvent.Reason.PLUGIN)
        h.adapter.onServerConnect(backend)
        assertFalse(backend.isCancelled)
        assertEquals(h.services.stableUuid, h.profiles.read(fixture.pending).id)
        assertTrue(h.profiles.read(fixture.pending).properties.none { it.name == h.authority.propertyKey })
        assertEquals(1, ready.clears)
    }

    @Test fun `first binding grants stable session but provisional frontend must reconnect before backend`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.services.authorize = true
        h.services.decision = AuthCoordinator.Decision.Verify(checkNotNull(fixture.uuid), AuthCoordinator.Reason.FIRST_BIND, true, null)
        h.login(fixture)
        val old = h.platform.find(fixture.player)!!
        val ready = ReadyPlayer(old)
        h.gate.onJoin(ready)
        h.services.drain()
        h.services.tasks.last().fire()
        h.services.drain()
        assertFalse(fixture.connected, "First binding disconnects the frontend, not merely the private backend")
        assertTrue(fixture.connects.isEmpty())
        assertNotEquals(h.services.stableUuid, fixture.uuid)
        assertEquals(listOf(h.services.stableUuid), h.services.granted)
        fixture.connected = false
        h.adapter.sweepClosed()
        h.proxy.online.clear()
        val name = moe.ryuu.kyokouauth.core.identity.GameName.parse(fixture.name)!!
        val identity = moe.ryuu.kyokouauth.core.identity.Identity.Microsoft(name,
            moe.ryuu.kyokouauth.core.identity.Xuid(h.services.stableUuid.leastSignificantBits), "Controlled")
        h.services.decision = AuthCoordinator.Decision.Admit(identity,
            moe.ryuu.kyokouauth.core.storage.BoundIdentity.of(identity, 1L))
        val reconnect = PendingFixture()
        h.login(reconnect)
        assertNotSame(old, h.platform.find(reconnect.player))
        val backend = ServerConnectEvent(reconnect.player, h.backend, ServerConnectEvent.Reason.JOIN_PROXY)
        h.adapter.onServerConnect(backend)
        assertFalse(backend.isCancelled)
        assertEquals(h.services.stableUuid, reconnect.uuid)
        assertTrue(h.profiles.read(reconnect.pending).properties.none { it.name == h.authority.propertyKey })
    }

    @Test fun `missing login context and shutdown keep admission closed`() {
        val h = Harness()
        val fixture = PendingFixture()
        val event = LoginEvent(fixture.pending, null)
        h.adapter.onLogin(event)
        assertTrue(event.isCancelled)
        h.accepting = false
        assertTrue(h.pre(PendingFixture("ClosedLocal")).isCancelled)
        h.adapter.onPostLogin(PostLoginEvent(fixture.player, h.backend, null))
        assertFalse(fixture.connected)
    }
}
