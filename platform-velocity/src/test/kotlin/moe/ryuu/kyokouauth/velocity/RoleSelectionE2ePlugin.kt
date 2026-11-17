package moe.ryuu.kyokouauth.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.util.GameProfile
import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.LimboListener
import moe.ryuu.kyokouauth.limbo.LimboLogger
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.LimboServer
import moe.ryuu.kyokouauth.limbo.RoleSelectorMenu
import moe.ryuu.kyokouauth.limbo.RoleAvatarIcon
import moe.ryuu.kyokouauth.proxy.avatar.RoleAvatarService
import moe.ryuu.kyokouauth.proxy.avatar.RoleAvatarOffer
import moe.ryuu.kyokouauth.proxy.avatar.RoleAvatarStatusClient
import java.net.URI
import java.util.concurrent.CompletableFuture
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.RoleOption
import moe.ryuu.kyokouauth.proxy.RoleOptions
import moe.ryuu.kyokouauth.proxy.RoleSkinProvider
import moe.ryuu.kyokouauth.proxy.RoleSkinService
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import moe.ryuu.kyokouauth.proxy.GameProfile as PortProfile

/** Test-only native adapter bootstrap; never included in the production artifact. */
class RoleSelectionE2ePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val folder: Path,
) {
    private val mode = System.getProperty("kyokou.roleE2e.mode", "java")
    private val chosen = System.getProperty("kyokou.roleE2e.role", "microsoft")
    private val skinPolicy = System.getProperty("kyokou.roleE2e.skinPolicy", "none")
    private val skinsEnabled get() = skinPolicy != "none"
    private val floodgateMessages = java.lang.Boolean.getBoolean("kyokou.roleE2e.floodgateMessages")
    private val roleSkins = RoleSkinService(::skinProvider)
    private val avatars = java.lang.Boolean.getBoolean("kyokou.roleE2e.avatars")
    private val avatarCase = System.getProperty("kyokou.roleE2e.avatarCase", "none")
    private var avatarService: RoleAvatarService? = null
    private var avatarStatus: RoleAvatarStatusClient? = null
    private lateinit var isolatedRun: Path
    private val avatarPrepares = AtomicInteger()
    private val xuid = Xuid(System.getProperty("kyokou.roleE2e.xuid", "2535412345678901").toLong())
    private val microsoft = Identity.Microsoft(GameName.parseOrThrow("URole"), xuid, "R")
    private val premium = Identity.Premium(GameName.parseOrThrow("JRole"), UUID.fromString("12345678-1234-4234-9234-123456789abc"))
    private val uRecord = BoundIdentity.of(microsoft, 1L)
    private var jRecord: BoundIdentity? = null
    private val exchanges = AtomicInteger()
    private val grants = AtomicInteger()
    private val claims = AtomicInteger()
    private val io = Executors.newFixedThreadPool(2)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val handles = Collections.synchronizedMap(IdentityHashMap<LimboPlayer, LimboPlayer>())
    private val chooserSessions = ConcurrentHashMap.newKeySet<UUID>()
    private val probes = ConcurrentHashMap<UUID, AtomicInteger>()
    private var bedrock: VelocityFloodgate? = null
    private var gate: LoginGate? = null
    private var limbo: LimboServer? = null
    @Volatile private var accepting = false

    private val jSkin: PortProfile.Property get() = PortProfile.Property("textures",
        checkNotNull(System.getProperty("kyokou.roleE2e.premiumTexture")), "role-e2e-synthetic-signature")

    @Subscribe(order = PostOrder.LAST)
    fun unavailable(event: PreLoginEvent) {
        if (!accepting || !event.connection.remoteAddress.address.isLoopbackAddress) {
            event.result = PreLoginEvent.PreLoginComponentResult.denied(Component.text("ROLE_E2E_UNAVAILABLE"))
        }
    }

    /** A hostile NORMAL skin write must not survive the production Gate's LAST role policy. */
    @Subscribe(priority = 1)
    fun profileNoise(event: GameProfileRequestEvent) {
        if (!accepting) return
        val profile = event.gameProfile
        event.gameProfile = GameProfile(profile.id, profile.name,
            profile.properties.filterNot { it.name == "textures" || it.name == "role-e2e:marker" } + listOf(
                GameProfile.Property("role-e2e:marker", "preserved-non-texture-property", ""),
                GameProfile.Property("textures", "must-be-removed-by-last-role-policy", "")))
    }

    /** Bracket the real SR NORMAL EventTask, not a canned replacement skin listener. */
    @Subscribe(priority = -1)
    fun afterSkinsRestorer(event: GameProfileRequestEvent) = checked {
        if (!accepting || !skinsEnabled) return@checked
        val profile = event.gameProfile
        if (profile.id == premium.uuid) {
            val textures = profile.properties.filter { it.name == "textures" }
            check(textures.size == 1 && textures.single().value == System.getProperty("kyokou.roleE2e.decoyTexture")) {
                "The actual SR NORMAL listener did not apply its explicitly stored J decoy"
            }
            logger.info("ROLE_E2E_SR_NORMAL_J_DECOY uuid={} native=true", profile.id)
        } else if (profile.id == microsoft.uuid && skinPolicy == "sr-explicit") {
            check(profile.properties.single { it.name == "textures" }.value == System.getProperty("kyokou.roleE2e.microsoftTexture"))
            logger.info("ROLE_E2E_SR_NORMAL_U_EXPLICIT uuid={} native=true", profile.id)
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun postLogin(event: PostLoginEvent) = checked {
        val current = checkNotNull(gate)
        val player = event.player
        val authenticated = current.common.isAuthenticated(current.ports.player(player))
        if (avatars) logger.info("ROLE_E2E_AVATAR_NATIVE_SOCKET session={} remotePort={} state={}",
            player.sessionId, player.remoteAddress.port, if (authenticated) "final" else "held")
        if (!authenticated) {
            // Only Java requires a provisional profile. Floodgate needs its own
            // SDK-addressable native profile, but remains HELD until role selection.
            if (mode == "java") {
                check(player.uniqueId != microsoft.uuid && player.uniqueId != premium.uuid)
                check(player.username != microsoft.name.value && player.username != premium.name.value)
                check(player.username.length == 16)
            }
            check(player.gameProfileProperties.none { it.name == "textures" })
            chooserSessions += player.sessionId
            logger.info("ROLE_E2E_NATIVE_HELD session={} uuid={} name={}", player.sessionId, player.uniqueId, player.username)
            return@checked
        }
        val identity = if (chosen == "microsoft") microsoft else premium
        check(player.uniqueId == identity.uuid && player.username == identity.name.value)
        check(!player.isOnlineMode && player.sessionId !in chooserSessions)
        val textures = player.gameProfileProperties.filter { it.name == "textures" }
        if (chosen == "microsoft") {
            if (skinPolicy == "sr-explicit") check(textures.size == 1 &&
                textures.single().value == System.getProperty("kyokou.roleE2e.microsoftTexture") &&
                textures.single().signature == "role-e2e-synthetic-signature")
            else check(textures.isEmpty())
        } else check(textures.size == 1 && textures.single().value == jSkin.value && textures.single().signature == jSkin.signature)
        check(player.gameProfileProperties.single { it.name == "role-e2e:marker" }.value == "preserved-non-texture-property")
        check(player.gameProfileProperties.single { it.name == LoginGate.CONNECTION_PROPERTY }.value == player.sessionId.toString())
        check(grants.get() == if (mode == "java" && chosen == "microsoft") 1 else 0)
        check(claims.get() == if (chosen == "premium") 1 else 0)
        check(exchanges.get() == if (mode == "java") 1 else 0)
        if (mode == "floodgate") check((probes[player.sessionId]?.get() ?: 0) >= 4)
        if (skinsEnabled) logger.info("ROLE_E2E_SR_FINAL_POLICY role={} policy={} nonce=preserved marker=preserved", chosen, skinPolicy)
        logger.info("ROLE_E2E_NATIVE_FINAL session={} role={} uuid={} name={} texture={} nonce=1 marker=1 microsoftProof={}",
            player.sessionId, chosen, player.uniqueId, player.username, if (chosen == "microsoft") (if (skinPolicy == "sr-explicit") "explicit-u" else "default") else "synthetic-j",
            if (mode == "java") "synthetic-services" else "official-floodgate-channel")
    }

    /** A wire round-trip barrier after Floodgate NORMAL, never an event-result bypass.
     * The runner additionally requires FormChannel's own unknown-form log; this
     * observer cannot prove that the official handler processed the response alone.
     */
    @Subscribe(order = PostOrder.LAST)
    fun floodgateMessageBarrier(event: PluginMessageEvent) = checked {
        if (!accepting || !floodgateMessages || event.identifier.id != "floodgate:form") return@checked
        val player = event.source as? Player ?: error("Expected native client-originated Floodgate probe")
        val data = event.data
        check(data.size == 3 && java.nio.ByteBuffer.wrap(data).short.toInt() == -12345 && data[2] == '0'.code.toByte()) {
            "Probe must be an unknown proxy form ID plus valid nonempty JSON 0, never a parse-error payload"
        }
        val current = checkNotNull(gate)
        val authenticated = current.common.isAuthenticated(current.ports.player(player))
        if (mode == "java") {
            check(!authenticated && player.sessionId in chooserSessions)
            // The same valid message from a Java player must be rejected by Floodgate.
            // No ACK or GUI click is permitted even if a disconnect is still pending.
            logger.info("ROLE_E2E_FG_JAVA_REJECT_BARRIER session={} source=PLAYER held=true bytes={}", player.sessionId, data.size)
            return@checked
        }
        val phase = if (authenticated) "final" else "chooser"
        check((player.sessionId in chooserSessions) == !authenticated)
        if (authenticated) {
            val identity = if (chosen == "microsoft") microsoft else premium
            check(player.uniqueId == identity.uuid && player.username == identity.name.value)
        }
        logger.info("ROLE_E2E_FG_MESSAGE_BARRIER session={} phase={} source=PLAYER held={} bytes={}", player.sessionId, phase, !authenticated, data.size)
        // Deliberately do not read or write event.result. The actual official listener
        // must independently perform source/API lookup and FormChannel dispatch.
        player.sendPluginMessage(MinecraftChannelIdentifier.from("kyokouauth:role_e2e_fg_ack"), data)
    }

    @Subscribe(order = PostOrder.FIRST)
    fun backendAttempt(event: ServerPreConnectEvent) = checked {
        if (!accepting || event.originalServer.serverInfo.name != "backend") return@checked
        val current = checkNotNull(gate)
        check(current.common.isAuthenticated(current.ports.player(event.player))) { "Chooser attempted a real backend" }
        check(event.player.sessionId !in chooserSessions)
        logger.info("ROLE_E2E_AUTHORIZED_BACKEND role={} uuid={} name={}", chosen, event.player.uniqueId, event.player.username)
    }

    @Subscribe(order = PostOrder.LAST)
    fun initialize(event: ProxyInitializeEvent) = checked {
        check(mode in setOf("java", "floodgate") && chosen in setOf("microsoft", "premium"))
        check(skinPolicy in setOf("none", "sr-explicit", "sr-default"))
        val isolated = Path.of(checkNotNull(System.getProperty("kyokou.roleE2e.run"))).toRealPath()
        val root = Path.of(checkNotNull(System.getProperty("kyokou.roleE2e.root"))).toRealPath()
        check(isolated.startsWith(root) && isolated != root && Files.isRegularFile(isolated.resolve("isolated-role-fixture.json")))
        check(folder.toAbsolutePath().normalize().startsWith(isolated))
        isolatedRun = isolated
        if (avatars) {
            check(mode == "floodgate" && floodgateMessages && !skinsEnabled)
            val red = Files.readAllBytes(isolated.resolve("avatar-red64.png"))
            val blue = Files.readAllBytes(isolated.resolve("avatar-blue64.png"))
            val liveSkin = moe.ryuu.kyokouauth.core.avatar.AvatarJson.skinLocation(jSkin.value)
            avatarService = RoleAvatarService(System.getProperty("kyokou.roleE2e.avatarBridgePort").toInt(),
                downloader = { location ->
                    check(Thread.currentThread().name == "role-avatar-prepare") { "Avatar source work escaped the real bounded presentation worker" }
                    if (location == liveSkin) blue.copyOf() else red.copyOf()
                }, defaultSkin = { red.copyOf() }).also { it.start() }
            avatarStatus = RoleAvatarStatusClient(URI("http://127.0.0.1:${System.getProperty("kyokou.roleE2e.avatarStatusPort")}/v1/packs/status"))
        }
        if (skinsEnabled) seedSkinsRestorer()
        val configValues = mutableMapOf(
            "post-login.server" to "backend", "verification.poll-interval-seconds" to "1",
            "verification.timeout-seconds" to "25", "limbo.join-timeout-seconds" to "10",
            "role-selection.enabled" to "true", "role-selection.timeout-seconds" to "25",
            "role-selection.transfer-ttl-seconds" to "20", "role-selection.reconnect-host" to "127.0.0.1",
            "role-selection.reconnect-port" to checkNotNull(System.getProperty("kyokou.roleE2e.port")),
            "bedrock.enabled" to (mode == "floodgate").toString())
        if (avatars) configValues.putAll(mapOf("role-selection.avatars.enabled" to "true",
            "role-selection.avatars.bridge-port" to System.getProperty("kyokou.roleE2e.avatarBridgePort"),
            "role-selection.avatars.status-endpoint" to "http://127.0.0.1:${System.getProperty("kyokou.roleE2e.avatarStatusPort")}/v1/packs/status"))
        val config = ProxyConfig(configValues, folder.toFile())
        val messages = MessageCatalogue("en_US", folder.resolve("messages").toFile()).also { it.load() }
        if (mode == "floodgate") bedrock = VelocityFloodgate(proxy, true, allowLinked = true)
        val services = object : LoginGate.Services {
            override fun allow(key: String) = true
            override fun decide(name: GameName, address: String): AuthCoordinator.Decision {
                check(mode == "java" && name == microsoft.name)
                // Deliberately start with an IP Admit: enabled roles must still demand a fresh XUID proof.
                return AuthCoordinator.Decision.Admit(microsoft, uRecord)
            }
            override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome {
                check(mode == "java" && name == microsoft.name && xuid == this@RoleSelectionE2ePlugin.xuid && gamertag == "R")
                return AuthCoordinator.ClaimOutcome.Bound(uRecord)
            }
            override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome {
                check(chosen == "premium" && uuid == premium.uuid && name == premium.name)
                check(claims.incrementAndGet() == 1)
                return AuthCoordinator.ClaimOutcome.Bound(BoundIdentity.of(premium, 1L).also { jRecord = it })
            }
            override fun grantSession(uuid: UUID, address: String) {
                check(mode == "java" && chosen == "microsoft" && uuid == microsoft.uuid)
                check(grants.incrementAndGet() == 1)
            }
            override fun submit(task: () -> Unit) { io.execute(task) }
            override fun schedule(delayMillis: Long, task: () -> Unit) = scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
            override fun begin(): DeviceCodeFlow.Challenge {
                check(mode == "java")
                return DeviceCodeFlow.Challenge("ROLE-E2E", "https://example.invalid", "https://example.invalid/role-e2e", 60, 1, "synthetic-device-code")
            }
            override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult {
                check(mode == "java")
                return DeviceCodeFlow.PollResult.Authorized("synthetic-token-not-a-credential", null, 60)
            }
            override fun exchange(accessToken: String): XboxLive.XstsToken {
                check(mode == "java" && exchanges.incrementAndGet() == 1)
                return XboxLive.XstsToken("synthetic", xuid.value, "R", "synthetic-not-a-credential")
            }
            override fun prepareRoleAvatars(xuid: Xuid, options: RoleOptions): CompletableFuture<String> {
                check(avatars && xuid == this@RoleSelectionE2ePlugin.xuid)
                check(options.roles.map { it.identity.uuid }.toSet() == setOf(microsoft.uuid, premium.uuid))
                val u = options.roles.single { it.identity is Identity.Microsoft }
                val j = options.roles.single { it.identity is Identity.Premium }
                check(u.skin == null && j.skin?.value == jSkin.value && j.skin?.signature == jSkin.signature)
                val call = avatarPrepares.incrementAndGet()
                return checkNotNull(avatarService).prepare(xuid.value, u.skin?.value, j.skin?.value).thenApply { snapshot ->
                    check(snapshot.microsoftDefault && !snapshot.premiumDefault)
                    logger.info("ROLE_E2E_AVATAR_PREPARED call={} xuid={} fingerprint={} source=real-service syntheticSkins=true", call, xuid.value, snapshot.fingerprint)
                    snapshot.fingerprint
                }
            }
            override fun roleAvatarOffer(xuid: Xuid): RoleAvatarOffer? {
                check(avatars && xuid == this@RoleSelectionE2ePlugin.xuid)
                return checkNotNull(avatarStatus).status(xuid.value).also { offered ->
                    logger.info("ROLE_E2E_AVATAR_OFFER xuid={} fingerprint={} fallback={} source=controlled-loopback-not-geyser renderAck=false",
                        xuid.value, offered?.fingerprint, offered?.fallback)
                }
            }
            override fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?): RoleOptions {
                check(xuid == this@RoleSelectionE2ePlugin.xuid && gamertag == "R")
                val skin = roleSkins.microsoft(microsoft.uuid)
                check((skin != null) == (skinPolicy == "sr-explicit"))
                if (skin != null) check(skin.value == System.getProperty("kyokou.roleE2e.microsoftTexture") &&
                    skin.signature == "role-e2e-synthetic-signature")
                if (skinsEnabled) logger.info("ROLE_E2E_SR_SERVICE_READ policy={} assigned={} productionReflection=true", skinPolicy, skin != null)
                return RoleOptions(listOf(RoleOption(microsoft, uRecord, skin), RoleOption(premium, jRecord, jSkin)), premium.uuid)
            }
        }
        val native = LoginGate(proxy, config, messages, services, logger, bedrockProbe = { source ->
            if (mode == "java") BedrockLogin.Java else checkNotNull(bedrock).probe(source).also { proof ->
                check(proof is BedrockLogin.Verified && proof.proof.xuid == xuid && proof.proof.linkedJavaUuid == premium.uuid)
                val phase = probes.computeIfAbsent(source.sessionId) { AtomicInteger() }.incrementAndGet()
                logger.info("ROLE_E2E_FG_PROOF session={} phase={} linked=true", source.sessionId, phase)
            }
        }).also { gate = it }
        proxy.eventManager.register(this, native)
        val listener = object : LimboListener {
            override fun onJoin(player: LimboPlayer) {
                val recording = object : LimboPlayer by player {
                    override fun openRoleSelector(menu: RoleSelectorMenu) {
                        check(menu.microsoft.profileUuid == microsoft.uuid && menu.premium.profileUuid == premium.uuid)
                        check(menu.microsoft.textureValue == (if (skinPolicy == "sr-explicit") System.getProperty("kyokou.roleE2e.microsoftTexture") else null) &&
                            menu.premium.textureValue == jSkin.value)
                        check(menu.microsoft.avatarIcon == if (avatars) RoleAvatarIcon.MICROSOFT else null)
                        check(menu.premium.avatarIcon == if (avatars) RoleAvatarIcon.PREMIUM else null)
                        if (avatars) logger.info("ROLE_E2E_AVATAR_MENU session={} uCmd={} jCmd={} profile=unchanged", player.connectionId,
                            menu.microsoft.avatarIcon?.modelData, menu.premium.avatarIcon?.modelData)
                        logger.info("ROLE_E2E_MENU generation={} independent=true", menu.generation)
                        player.openRoleSelector(menu)
                    }
                }
                handles[player] = recording
                native.onJoin(recording)
                logger.info("ROLE_E2E_LIMBO_READY session={} nativePlayReady=true", player.connectionId)
            }
            override fun onLeave(player: LimboPlayer) { handles.remove(player)?.let(native::onLeave) }
            override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) {
                handles[player]?.let { native.onRoleSelect(it, generation, optionId) }
            }
        }
        val forwarding = proxy.configuration.javaClass.getMethod("getForwardingSecret").invoke(proxy.configuration) as ByteArray
        limbo = LimboServer(settings = config.limbo, listener = listener,
            logger = LimboLogger { message, error -> if (error == null) logger.info(message) else logger.warn(message, error) },
            forwardingSecret = forwarding).also { engine ->
            engine.startAll().forEach { (protocol, address) -> native.ports.registerEngine(protocol, address) }
        }
        check(proxy.allServers.none { it.serverInfo.name.startsWith(LoginGate.LIMBO_SERVER_NAME) })
        native.activate()
        val warmup = if (avatars && avatarCase in setOf("hit", "changed", "new-user")) {
            val subject = if (avatarCase == "new-user") 2535412345678901L else xuid.value
            val texture = if (avatarCase == "hit") jSkin.value else checkNotNull(System.getProperty("kyokou.roleE2e.avatarBaselineTexture"))
            checkNotNull(avatarService).prepare(subject, null, texture).thenAccept { snapshot ->
                Files.writeString(isolated.resolve("avatar-baseline.json"), """{"xuid":$subject,"fingerprint":"${snapshot.fingerprint}"}""")
                Files.writeString(isolated.resolve("avatar-presentation.json"),
                    """{"schema":1,"state":"offered","xuid":$subject,"nonce":"${UUID.randomUUID()}","fingerprint":"${snapshot.fingerprint}","fallback":false}""")
                logger.info("ROLE_E2E_AVATAR_BASELINE xuid={} fingerprint={} bootstrapPresentationOnly=true", subject, snapshot.fingerprint)
            }
        } else CompletableFuture.completedFuture(null)
        warmup.whenComplete { _, failure -> checked {
            check(failure == null) { "Isolated real-service presentation baseline failed" }
            accepting = true
            logger.info("ROLE_E2E_STARTED mode={} role={} microsoft=synthetic-services relation=synthetic texture=synthetic-not-rendered", mode, chosen)
        } }
    }

    private fun skinProvider(): RoleSkinProvider? {
        if (!skinsEnabled) return null
        val container = proxy.pluginManager.getPlugin("skinsrestorer").orElse(null) ?: error("Pinned SR plugin is missing")
        val instance = container.instance.orElse(null) ?: error("Pinned SR plugin is not initialized")
        return RoleSkinProvider(instance, instance.javaClass.classLoader) {
            proxy.pluginManager.getPlugin("skinsrestorer").orElse(null)?.instance?.orElse(null) === instance
        }
    }

    /** Only the copied official plugin's public FILE storage API is seeded; no user data is read. */
    private fun seedSkinsRestorer() {
        val provider = checkNotNull(skinProvider())
        val loader = provider.loader
        val apiType = loader.loadClass("net.skinsrestorer.api.SkinsRestorer")
        val holder = loader.loadClass("net.skinsrestorer.api.SkinsRestorerProvider")
        val propertyType = loader.loadClass("net.skinsrestorer.api.property.SkinProperty")
        val identifierType = loader.loadClass("net.skinsrestorer.api.property.SkinIdentifier")
        val expectedJar = Path.of(checkNotNull(System.getProperty("kyokou.roleE2e.skinsJar"))).toRealPath()
        for (type in listOf(apiType, holder, propertyType)) {
            check(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath() == expectedJar)
        }
        val hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(expectedJar)))
        check(hash == "a85b4a370f988741c9a38f4d0498262edacbf3220314790ac1c127461996359c")
        val api = holder.getMethod("get").invoke(null)
        check(apiType.getMethod("getVersion").invoke(api) == "15.12.6")
        val skinStorageType = loader.loadClass("net.skinsrestorer.api.storage.SkinStorage")
        val playerStorageType = loader.loadClass("net.skinsrestorer.api.storage.PlayerStorage")
        val skins = apiType.getMethod("getSkinStorage").invoke(api)
        val players = apiType.getMethod("getPlayerStorage").invoke(api)
        val of = propertyType.getMethod("of", String::class.java, String::class.java)
        val identifier = identifierType.getMethod("ofCustom", String::class.java)
        val save = skinStorageType.getMethod("setCustomSkinData", String::class.java, propertyType)
        val assign = playerStorageType.getMethod("setSkinIdOfPlayer", UUID::class.java, identifierType)
        val explicit = of.invoke(null, checkNotNull(System.getProperty("kyokou.roleE2e.microsoftTexture")), "role-e2e-synthetic-signature")
        val decoy = of.invoke(null, checkNotNull(System.getProperty("kyokou.roleE2e.decoyTexture")), "role-e2e-synthetic-signature")
        save.invoke(skins, "role-e2e-u-explicit", explicit)
        save.invoke(skins, "role-e2e-j-decoy", decoy)
        if (skinPolicy == "sr-explicit") assign.invoke(players, microsoft.uuid, identifier.invoke(null, "role-e2e-u-explicit"))
        assign.invoke(players, premium.uuid, identifier.invoke(null, "role-e2e-j-decoy"))
        val value = propertyType.getMethod("getValue")
        val lookup = playerStorageType.getMethod("getSkinOfPlayer", UUID::class.java)
        val actualU = lookup.invoke(players, microsoft.uuid) as java.util.Optional<*>
        check(actualU.isPresent == (skinPolicy == "sr-explicit"))
        if (actualU.isPresent) check(value.invoke(actualU.get()) == System.getProperty("kyokou.roleE2e.microsoftTexture"))
        val actualJ = lookup.invoke(players, premium.uuid) as java.util.Optional<*>
        check(actualJ.isPresent && value.invoke(actualJ.get()) == System.getProperty("kyokou.roleE2e.decoyTexture"))
        check((roleSkins.microsoft(microsoft.uuid) != null) == (skinPolicy == "sr-explicit"))
        logger.info("ROLE_E2E_SR_SEEDED version=15.12.6 storage=FILE policy={} nativePublicApi=true", skinPolicy)
    }

    private inline fun checked(action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            logger.error("ROLE_E2E_ASSERTION_FAILED", failure)
            throw failure
        }
    }

    @Subscribe
    fun shutdown(event: ProxyShutdownEvent) {
        accepting = false
        gate?.deactivate()
        limbo?.close()
        gate?.ports?.unregisterEngines()
        bedrock?.close()
        roleSkins.close()
        avatarService?.close()
        handles.clear()
        io.shutdownNow()
        scheduler.shutdownNow()
    }
}
