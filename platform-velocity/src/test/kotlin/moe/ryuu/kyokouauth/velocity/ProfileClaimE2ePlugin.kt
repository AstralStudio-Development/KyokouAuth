package moe.ryuu.kyokouauth.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.LoginPhaseConnection
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.authme.AuthMeLegacyVerifier
import moe.ryuu.kyokouauth.core.authme.AuthMeSourceConfig
import moe.ryuu.kyokouauth.core.authme.AuthMeVerification
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.storage.Database
import moe.ryuu.kyokouauth.core.storage.DatabaseDescriptor
import moe.ryuu.kyokouauth.core.storage.Dialect
import moe.ryuu.kyokouauth.core.storage.DriverLibrary
import moe.ryuu.kyokouauth.core.storage.IdentityRepository
import moe.ryuu.kyokouauth.core.storage.LegacyProfile
import moe.ryuu.kyokouauth.core.storage.ProfileClaimException
import moe.ryuu.kyokouauth.core.storage.ProfileClaimFailure
import moe.ryuu.kyokouauth.core.storage.ProfileClaimRepository
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.ConfirmationMenu
import moe.ryuu.kyokouauth.limbo.LimboListener
import moe.ryuu.kyokouauth.limbo.LimboLogger
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.LimboServer
import moe.ryuu.kyokouauth.proxy.ProfileClaimFlow
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.RoleSkinProvider
import moe.ryuu.kyokouauth.proxy.RoleSkinService
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolated test-only native Velocity bootstrap for synthetic AuthMe -> Java U profile UUID mapping.
 *
 * Never included in production artifacts. Uses only synthetic test fixtures under `.narrafork`,
 * never claims real Xbox/AuthMe/Mojang credentials and relies solely
 * on the real shared/native `LoginGate` public `GameProfileRequestEvent` seam (zero reflection or
 * post-login `Player` identity mutation).
 */
class ProfileClaimE2ePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val folder: Path,
) {
    private val oldName = GameName.parseOrThrow("OldSteve")
    private val newName = GameName.parseOrThrow("NewSteve")
    private val legacyProfileUuid: UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:${oldName.value}".toByteArray(StandardCharsets.UTF_8)).also {
            check(it.version() == 3) { "Expected explicit Java OfflinePlayer version-3 UUID" }
        }
    private val newOfflineUuid: UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:${newName.value}".toByteArray(StandardCharsets.UTF_8)).also {
            check(it != legacyProfileUuid) { "NewSteve offline UUID must differ from OldSteve legacy UUID" }
        }
    private val syntheticXuid = Xuid(2535498765432101L)
    private val defaultXuidUuid = Identity.ofXuid(syntheticXuid).also {
        check(it != legacyProfileUuid && it != newOfflineUuid) { "XUID default UUID must differ from legacy UUID" }
    }
    private val baselineIdentity = Identity.Microsoft(oldName, Xuid(2535411111111111L), "SyntheticLegacySeed", legacyProfileUuid)
    private val baselineRecord = BoundIdentity.of(baselineIdentity, 1L)
    private val syntheticGamertag = "SyntheticMappedXbox"
    private val seedStarted = AtomicBoolean()
    @Volatile private var seedDisconnected = false
    private val claimant = AtomicReference<InboundConnection?>()

    private val migrationConfigEnabled = System.getProperty("kyokou.claimE2e.migrationEnabled", "false").toBooleanStrict()
    private val supportsMapped = System.getProperty("kyokou.claimE2e.supportsMapped", "true").toBooleanStrict()
    private val skinsRestorerEnabled = System.getProperty("kyokou.claimE2e.skinsrestorer", "false").toBooleanStrict()
    private val legacySkinValue = syntheticTexture('c')
    private val wrongSkinValue = syntheticTexture('d')
    private val syntheticSignature = "claim-e2e-synthetic-not-mojang-signature"

    private val io = Executors.newFixedThreadPool(2)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val stages = ConcurrentHashMap<UUID, String>()
    private val decideCount = AtomicInteger()
    private val permitChecks = AtomicInteger()
    private var gate: LoginGate? = null
    private var skinService: RoleSkinService? = null
    @Volatile private var accepting = false

    @Subscribe(order = PostOrder.FIRST)
    fun denyUnavailablePreLogin(event: PreLoginEvent) {
        val connection = event.connection
        val expected = accepting && event.result.isAllowed && connection.remoteAddress.address?.isLoopbackAddress == true &&
            when (event.username) {
                oldName.value -> seedStarted.compareAndSet(false, true)
                newName.value -> seedDisconnected && claimant.compareAndSet(null, connection)
                else -> false
            }
        if (!expected) {
            event.result = PreLoginEvent.PreLoginComponentResult.denied(Component.text("CLAIM_E2E_UNAVAILABLE"))
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun denyUnavailableLogin(event: LoginEvent) {
        if (!accepting) {
            event.result = ResultedEvent.ComponentResult.denied(Component.text("CLAIM_E2E_UNAVAILABLE"))
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun denyUnavailableConnect(event: ServerPreConnectEvent) {
        if (!accepting) {
            event.result = ServerPreConnectEvent.ServerResult.denied()
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    fun observePreProfile(event: GameProfileRequestEvent) = checked {
        if (!accepting) return@checked
        val connection = event.connection
        check(connection is LoginPhaseConnection) { "Expected real native LoginPhaseConnection" }
        check(connection.identifiedKey == null) { "Unsigned Java U SDK fixture must have null identifiedKey" }
        val current = checkNotNull(gate)
        val requiresMapped = current.common.requiresMappedProfile(current.ports.inbound(connection))
        logger.info(
            "CLAIM_E2E_PRE_PROFILE session={} remotePort={} incomingName={} incomingUuid={} requiresMapped={} identifiedKey=null",
            connection.sessionId,
            connection.remoteAddress.port,
            event.username,
            event.gameProfile.id,
            requiresMapped,
        )
    }

    @Subscribe(priority = Short.MIN_VALUE)
    fun observeFinalProfile(event: GameProfileRequestEvent) = checked {
        if (!accepting) return@checked
        val profile = event.gameProfile
        check(profile.id == legacyProfileUuid) { "Expected legacy profile UUID $legacyProfileUuid, got ${profile.id}" }
        val expectedName = if (profile.name == oldName.value) oldName.value else newName.value
        check(profile.name == expectedName) { "Unexpected profile name ${profile.name}" }
        val nonces = profile.properties.filter { it.name == LoginGate.CONNECTION_PROPERTY }
        check(nonces.size == 1 && nonces.single().value == event.connection.sessionId.toString()) {
            "Expected exact connection nonce property"
        }
        val textures = profile.properties.filter { it.name == "textures" }
        val skinTag = if (skinsRestorerEnabled) {
            check(textures.size == 1 && textures.single().value == legacySkinValue && textures.single().signature == syntheticSignature) {
                "SkinsRestorer did not resolve the legacy UUID skin or leaked offline decoy texture"
            }
            val stored = checkNotNull(skinService).microsoft(profile.id)
            check(stored != null && stored.value == legacySkinValue && stored.signature == syntheticSignature) {
                "RoleSkinService lookup for mapped legacy UUID did not return the seeded legacy skin"
            }
            "legacy-sr"
        } else {
            "untested"
        }
        logger.info(
            "CLAIM_E2E_FINAL_PROFILE session={} name={} uuid={} nonce={} skin={}",
            event.connection.sessionId,
            profile.name,
            profile.id,
            nonces.single().value,
            skinTag,
        )
    }

    @Subscribe(order = PostOrder.LAST)
    fun observePostLogin(event: PostLoginEvent) = checked {
        val current = checkNotNull(gate)
        val player = event.player
        check(current.common.isAuthenticated(current.ports.player(player))) { "Admitted native player is not authenticated" }
        check(!player.isOnlineMode) { "Synthetic Java U login must force offline mode on proxy" }
        check(player.uniqueId == legacyProfileUuid) { "Native Velocity Player UUID differs from legacy profile UUID" }
        val stage = if (player.username == oldName.value) "seed" else "mapped"
        check(player.username == if (stage == "seed") oldName.value else newName.value)
        stages[player.sessionId] = stage
        logger.info(
            "CLAIM_E2E_POST_LOGIN stage={} session={} remotePort={} name={} uuid={}",
            stage,
            player.sessionId,
            player.remoteAddress.port,
            player.username,
            player.uniqueId,
        )
    }

    @Subscribe(order = PostOrder.FIRST)
    fun observeBackendPreConnect(event: ServerPreConnectEvent) = checked {
        if (!accepting) return@checked
        val target = event.result.server.orElse(null) ?: return@checked
        check(target.serverInfo.name == "backend") { "Unexpected target server ${target.serverInfo.name}" }
        val current = checkNotNull(gate)
        val player = event.player
        check(current.common.isAuthenticated(current.ports.player(player))) { "Unauthenticated player attempted backend connect" }
        check(player.uniqueId == legacyProfileUuid) { "Backend pre-connect UUID changed" }
    }

    @Subscribe(order = PostOrder.LAST)
    fun observeBackendConnected(event: ServerConnectedEvent) = checked {
        val player = event.player
        val stage = checkNotNull(stages[player.sessionId]) { "Unknown connected session ${player.sessionId}" }
        check(event.server.serverInfo.name == "backend") { "Unexpected connected server" }
        check(player.uniqueId == legacyProfileUuid) { "Connected player UUID differs from legacy profile UUID" }
        logger.info(
            "CLAIM_E2E_BACKEND_CONNECTED stage={} session={} name={} uuid={} server=backend",
            stage,
            player.sessionId,
            player.username,
            player.uniqueId,
        )
    }

    @Subscribe(order = PostOrder.LAST)
    fun observeDisconnect(event: DisconnectEvent) {
        val stage = stages.remove(event.player.sessionId) ?: return
        if (stage == "seed") seedDisconnected = true
        logger.info(
            "CLAIM_E2E_DISCONNECTED stage={} session={} name={} uuid={}",
            stage,
            event.player.sessionId,
            event.player.username,
            event.player.uniqueId,
        )
    }

    @Subscribe(order = PostOrder.LAST)
    fun initialize(event: ProxyInitializeEvent) = checked {
        val isolatedRoot = Path.of(checkNotNull(System.getProperty("kyokou.claimE2e.root"))).toRealPath()
        val isolatedRun = Path.of(checkNotNull(System.getProperty("kyokou.claimE2e.run"))).toRealPath()
        val workspaceRoot = isolatedRoot.parent.parent.toRealPath()
        check(isolatedRoot.fileName.toString() == "profile-claim-e2e" && isolatedRoot.parent.fileName.toString() == ".narrafork") {
            "Bootstrap must run only under .narrafork/profile-claim-e2e"
        }
        check(isolatedRun.startsWith(isolatedRoot) && isolatedRun != isolatedRoot) {
            "Run directory escapes isolated root"
        }
        for (productionDir in listOf("run-live", "run-proxy")) {
            val candidate = workspaceRoot.resolve(productionDir)
            if (Files.exists(candidate)) {
                val real = candidate.toRealPath()
                check(!isolatedRun.startsWith(real) && !folder.toAbsolutePath().normalize().startsWith(real)) {
                    "Refusing to run inside live/production directory $real"
                }
            }
        }
        val markerFile = isolatedRun.resolve("isolated-profile-claim-fixture.json")
        check(Files.isRegularFile(markerFile)) { "Missing isolated non-production fixture marker" }
        val markerText = Files.readString(markerFile, StandardCharsets.UTF_8)
        check("\"isolatedFixture\": true" in markerText && "\"syntheticFixture\": true" in markerText) {
            "Invalid isolated synthetic fixture marker"
        }
        check(folder.toAbsolutePath().normalize().startsWith(isolatedRun)) {
            "Plugin data directory escapes isolated run directory"
        }

        val forwardingMode = proxy.configuration.javaClass.getMethod("getPlayerInfoForwardingMode")
            .invoke(proxy.configuration) as? Enum<*>
        check(forwardingMode?.name == "MODERN") { "ProfileClaimE2ePlugin requires Velocity modern forwarding" }
        val secret = proxy.configuration.javaClass.getMethod("getForwardingSecret")
            .invoke(proxy.configuration) as? ByteArray
        check(secret != null && secret.size >= 32) { "Expected non-trivial isolated modern forwarding secret" }

        if (skinsRestorerEnabled) {
            seedSkinsRestorer()
        }

        // migration.enabled intentionally defaults to false in the positive run to prove that existing
        // mapped profiles rely on permitsMappedProfile(support bit), not the new-claim feature flag.
        val config = ProxyConfig(
            mapOf(
                "post-login.server" to "backend",
                "bedrock.enabled" to "false",
                "role-selection.enabled" to "false",
                "migration.enabled" to migrationConfigEnabled.toString(),
                "verification.poll-interval-seconds" to "1",
            ),
            folder.toFile(),
        )
        check(config.profileClaimsEnabled == migrationConfigEnabled)
        val messagesFolder = folder.resolve("messages")
        Files.createDirectories(messagesFolder)
        val messages = MessageCatalogue("en_US", messagesFolder.toFile()).also { it.load() }

        val services = object : LoginGate.Services {
            override fun allow(key: String): Boolean = true

            override fun permitsMappedProfile(record: BoundIdentity): Boolean {
                val count = permitChecks.incrementAndGet()
                val mapped = record.kind == BoundIdentity.Kind.XUID &&
                    record.uuid != Identity.ofXuid(Xuid(record.subject.toLong()))
                val allowed = !mapped || supportsMapped
                logger.info(
                    "CLAIM_E2E_PERMIT_CHECK check={} name={} uuid={} subject={} mapped={} migrationFlag={} supportsMapped={} allowed={}",
                    count,
                    record.name.value,
                    record.uuid,
                    record.subject,
                    mapped,
                    config.profileClaimsEnabled,
                    supportsMapped,
                    allowed,
                )
                return allowed
            }

            override fun decide(name: GameName, address: String): AuthCoordinator.Decision {
                val call = decideCount.incrementAndGet()
                return when (name) {
                    oldName -> {
                        logger.info("CLAIM_E2E_DECIDE call={} stage=seed name={} uuid={} synthetic=authme-baseline", call, name.value, baselineIdentity.uuid)
                        AuthCoordinator.Decision.Admit(baselineIdentity, baselineRecord)
                    }
                    newName -> {
                        val record = claimAndReopen(isolatedRun, checkNotNull(claimant.get()))
                        logger.info(
                            "CLAIM_E2E_DECIDE call={} stage=mapped name={} uuid={} xuid={} defaultXuidUuid={} synthetic=microsoft-mapped",
                            call, name.value, record.uuid, record.subject, defaultXuidUuid,
                        )
                        AuthCoordinator.Decision.Admit(record.toIdentity(), record)
                    }
                    else -> error("Unexpected player name in isolated fixture: ${name.value}")
                }
            }

            override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome =
                error("Unexpected premium bind in the legacy Microsoft profile fixture")

            override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome =
                error("Trusted pre-seeded synthetic fixture must not run interactive device-code bind")

            override fun grantSession(uuid: UUID, address: String) =
                error("Trusted pre-seeded synthetic fixture must not mint runtime sessions")

            override fun submit(task: () -> Unit) {
                io.execute(task)
            }

            override fun schedule(delayMillis: Long, task: () -> Unit) =
                scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)

            override fun begin(): DeviceCodeFlow.Challenge = error("No network device-code flow in isolated fixture")
            override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult = error("No network poll in isolated fixture")
            override fun exchange(accessToken: String): XboxLive.XstsToken = error("No Xbox Live network call in isolated fixture")
        }

        val nativeGate = LoginGate(proxy, config, messages, services, logger).also { this.gate = it }
        proxy.eventManager.register(this, nativeGate)
        nativeGate.activate()
        accepting = true
        logger.info(
            "CLAIM_E2E_STARTED syntheticFixture=true legacyName={} newName={} legacyUuid={} newOfflineUuid={} xuid={} defaultXuidUuid={} migrationFlag={} supportsMapped={} skinsRestorer={}",
            oldName.value,
            newName.value,
            legacyProfileUuid,
            newOfflineUuid,
            syntheticXuid.value,
            defaultXuidUuid,
            config.profileClaimsEnabled,
            supportsMapped,
            skinsRestorerEnabled,
        )
    }

    /** Synthetic old/new proofs at the service seam; the transaction and both pool lifetimes are real. */
    private fun claimAndReopen(run: Path, connection: InboundConnection): BoundIdentity {
        fun current() = seedDisconnected && proxy.getPlayer(oldName.value).isEmpty &&
            proxy.getPlayer(legacyProfileUuid).isEmpty && connection.isActive && accepting
        check(supportsMapped && current())
        val session = connection.sessionId
        val descriptor = DatabaseDescriptor(Dialect.H2, database = Files.createTempDirectory(run, "repository-").resolve("claim").toString())
        DriverLibrary(run.resolve("fixture-libraries").toFile(), "", false, {}).use { library ->
            fun open() = Database(descriptor, driverLoader = library::classLoader)
            val committed = open().use { db ->
                val claims = ProfileClaimRepository(db)
                val batch = "synthetic-backup"
                claims.preflightLegacy(batch, oldName.value, legacyProfileUuid)
                db.withConnection { c -> c.createStatement().use { sql ->
                    for (table in listOf("legacy_profile", "profile_owner", "claim_grant", "profile_claim_audit", "identity", "name", "session")) {
                        sql.executeQuery("SELECT COUNT(*) FROM kyokouauth_$table").use { check(it.next() && it.getLong(1) == 0L) }
                    }
                } }
                logger.info("CLAIM_E2E_PREFLIGHT_EMPTY session={}", session)
                val source = claims.importLegacy(batch, oldName.value, legacyProfileUuid)
                check(claims.byId(source.id)?.status == LegacyProfile.Status.AVAILABLE)
                val grant = claims.issue(source.id, session, newName)
                val otherSession = UUID.randomUUID()
                val otherName = GameName.parseOrThrow("OtherSteve")
                val competing = claims.issue(source.id, otherSession, otherName)
                val bound = claims.commit(grant.token, session, newName, syntheticXuid, syntheticGamertag, current = ::current)
                check(bound.uuid == legacyProfileUuid && bound.name == newName && bound.kind == BoundIdentity.Kind.XUID &&
                    bound.subject == syntheticXuid.value.toString() && bound.gamertag == syntheticGamertag)
                logger.info("CLAIM_E2E_REPOSITORY_COMMITTED session={} uuid={} oldProof=synthetic newProof=synthetic", session, bound.uuid)
                val conflict = try {
                    claims.commit(competing.token, otherSession, otherName, Xuid(24680L), "SyntheticOther")
                    error("Second grant claimed the same legacy source")
                } catch (failure: ProfileClaimException) { failure.reason }
                check(conflict == ProfileClaimFailure.LEGACY_UNAVAILABLE)
                logger.info("CLAIM_E2E_SECOND_CLAIM_REFUSED session={} reason={}", session, conflict)
                bound
            }
            return open().use { db ->
                val claims = ProfileClaimRepository(db)
                val identities = IdentityRepository(db)
                check(claims.resolveMicrosoftProfile(syntheticXuid) == legacyProfileUuid)
                check(claims.byProfile(legacyProfileUuid)?.status == LegacyProfile.Status.CLAIMED)
                val persisted = checkNotNull(identities.byName(newName))
                check(persisted == committed && identities.byUuid(legacyProfileUuid) == persisted)
                val defaultIdentity = Identity.Microsoft(newName, syntheticXuid, syntheticGamertag)
                check(defaultIdentity.uuid == defaultXuidUuid)
                try {
                    identities.claim(defaultIdentity, System.currentTimeMillis())
                    error("Default XUID UUID claim was not rejected")
                } catch (_: IllegalArgumentException) { /* Mapped subject cannot move to the default UUID. */ }
                check(identities.byUuid(defaultXuidUuid) == null && identities.all() == listOf(persisted))
                logger.info("CLAIM_E2E_REOPENED session={} uuid={} defaultUuidRefused=true", session, persisted.uuid)
                persisted
            }
        }
    }

    private fun seedSkinsRestorer() {
        val container = proxy.pluginManager.getPlugin("skinsrestorer").orElse(null)
            ?: error("SkinsRestorer plugin is not loaded")
        val instance = container.instance.orElse(null) ?: error("SkinsRestorer plugin instance is unavailable")
        val loader = instance.javaClass.classLoader
        val providerHolder = loader.loadClass("net.skinsrestorer.api.SkinsRestorerProvider")
        val apiType = loader.loadClass("net.skinsrestorer.api.SkinsRestorer")
        val api = checkNotNull(providerHolder.getMethod("get").invoke(null))
        check(apiType.isInstance(api))
        val version = apiType.getMethod("getVersion").invoke(api) as String
        val skinStorageType = loader.loadClass("net.skinsrestorer.api.storage.SkinStorage")
        val playerStorageType = loader.loadClass("net.skinsrestorer.api.storage.PlayerStorage")
        val skinPropertyType = loader.loadClass("net.skinsrestorer.api.property.SkinProperty")
        val skinIdentifierType = loader.loadClass("net.skinsrestorer.api.property.SkinIdentifier")
        val skinOf = skinPropertyType.getMethod("of", String::class.java, String::class.java)
        val idOfCustom = skinIdentifierType.getMethod("ofCustom", String::class.java)
        val setCustom = skinStorageType.getMethod("setCustomSkinData", String::class.java, skinPropertyType)
        val setSkinId = playerStorageType.getMethod("setSkinIdOfPlayer", UUID::class.java, skinIdentifierType)

        val skinStorage = apiType.getMethod("getSkinStorage").invoke(api)
        val playerStorage = apiType.getMethod("getPlayerStorage").invoke(api)
        val legacySkin = skinOf.invoke(null, legacySkinValue, syntheticSignature)
        val decoySkin = skinOf.invoke(null, wrongSkinValue, syntheticSignature)
        setCustom.invoke(skinStorage, "claim-e2e-legacy", legacySkin)
        setCustom.invoke(skinStorage, "claim-e2e-new-offline-decoy", decoySkin)
        setSkinId.invoke(playerStorage, legacyProfileUuid, idOfCustom.invoke(null, "claim-e2e-legacy"))
        setSkinId.invoke(playerStorage, newOfflineUuid, idOfCustom.invoke(null, "claim-e2e-new-offline-decoy"))
        setSkinId.invoke(playerStorage, defaultXuidUuid, idOfCustom.invoke(null, "claim-e2e-new-offline-decoy"))

        val service = RoleSkinService {
            val current = proxy.pluginManager.getPlugin("skinsrestorer").orElse(null) ?: return@RoleSkinService null
            val live = current.instance.orElse(null) ?: return@RoleSkinService null
            RoleSkinProvider(live, live.javaClass.classLoader) {
                val latest = proxy.pluginManager.getPlugin("skinsrestorer").orElse(null)
                latest === current && latest.instance.orElse(null) === live
            }
        }.also { skinService = it }

        val readLegacy = checkNotNull(service.microsoft(legacyProfileUuid)) { "SkinsRestorer failed to persist legacy UUID custom skin" }
        val readDecoy = checkNotNull(service.microsoft(newOfflineUuid)) { "SkinsRestorer failed to persist NewSteve offline UUID decoy skin" }
        check(readLegacy.value == legacySkinValue && readLegacy.signature == syntheticSignature)
        check(readDecoy.value == wrongSkinValue && readDecoy.signature == syntheticSignature)
        logger.info(
            "CLAIM_E2E_SR_SEEDED version={} storage=FILE legacyUuid={} decoyOfflineUuid={} decoyXuidUuid={} synthetic=true",
            version,
            legacyProfileUuid,
            newOfflineUuid,
            defaultXuidUuid,
        )
    }

    private fun syntheticTexture(fill: Char): String {
        val payload = "{\"timestamp\":0,\"profileId\":\"${legacyProfileUuid.toString().replace("-", "")}\"," +
            "\"profileName\":\"SyntheticClaimE2E\",\"textures\":{\"SKIN\":{\"url\":" +
            "\"https://textures.minecraft.net/texture/${fill.toString().repeat(64)}\"}}}"
        return Base64.getEncoder().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private inline fun checked(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            logger.error("CLAIM_E2E_ASSERTION_FAILED", failure)
            throw failure
        }
    }

    @Subscribe
    fun shutdown(event: ProxyShutdownEvent) {
        accepting = false
        gate?.deactivate()
        skinService?.close()
        io.shutdownNow()
        scheduler.shutdownNow()
    }
}

/** Native Limbo/menu/ordinary-reconnect fixture. Only old-account and Xbox proofs are synthetic. */
class InteractiveProfileClaimE2ePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val folder: Path,
) {
    private val oldName = GameName.parseOrThrow("OldSteve")
    private val newName = GameName.parseOrThrow("NewSteve")
    private val oldUuid = AuthCoordinator.provisionalUuid(oldName)
    private val provisionalUuid = AuthCoordinator.provisionalUuid(newName)
    private val xuid = Xuid(2535498765432101L)
    private val gamertag = "SyntheticMappedXbox"
    private val scenario = System.getProperty("kyokou.claimE2e.case", "claim")
    private val resumed = System.getProperty("kyokou.claimE2e.resume", "false").toBooleanStrict()
    private val io = Executors.newFixedThreadPool(2)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val handles = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<LimboPlayer, LimboPlayer>())
    private val menus = ConcurrentHashMap<UUID, AtomicInteger>()
    private val stageBySession = ConcurrentHashMap<UUID, String>()
    private val nativeClaimant = AtomicReference<UUID?>()
    private val commits = AtomicInteger()
    private val begins = AtomicInteger()
    private val exchanges = AtomicInteger()
    private val privateSourceChecks = AtomicInteger()
    private val publicClaimEvents = AtomicInteger()
    private val publicClaimCompletions = AtomicInteger()
    private var authMeVerifier: AuthMeLegacyVerifier? = null
    private var gate: LoginGate? = null
    private var limbo: LimboServer? = null
    private var database: Database? = null
    private var drivers: DriverLibrary? = null
    private lateinit var claims: ProfileClaimRepository
    private lateinit var identities: IdentityRepository
    private lateinit var source: LegacyProfile
    @Volatile private var accepting = false
    @Volatile private var seedLeft = false

    private fun currentSourceIsOffline() = seedLeft && proxy.getPlayer(oldName.value).isEmpty &&
        proxy.getPlayer(oldUuid).isEmpty

    private inline fun checked(action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            logger.error("CLAIM_E2E_ASSERTION_FAILED", failure)
            throw failure
        }
    }

    @Subscribe(priority = Short.MAX_VALUE)
    fun publicCommandSpy(event: com.velocitypowered.api.event.command.CommandExecuteEvent) {
        if (accepting && moe.ryuu.kyokouauth.proxy.PrivateClaimCommands.isSensitive(event.command)) {
            publicClaimEvents.incrementAndGet()
            logger.error("CLAIM_E2E_ASSERTION_FAILED private command escaped into public events")
            event.result = com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult.denied()
        }
    }

    @Subscribe(priority = Short.MAX_VALUE)
    fun publicCompletionSpy(event: com.velocitypowered.api.event.player.TabCompleteEvent) {
        if (accepting && moe.ryuu.kyokouauth.proxy.PrivateClaimCommands.isSensitive(event.partialMessage)) {
            publicClaimCompletions.incrementAndGet()
            logger.error("CLAIM_E2E_ASSERTION_FAILED private completion escaped into public events")
            event.suggestions.clear()
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    fun prelogin(event: PreLoginEvent) {
        if (!accepting || event.connection.remoteAddress.address?.isLoopbackAddress != true ||
            event.username !in setOf(oldName.value, newName.value) ||
            event.username == newName.value && !resumed && !currentSourceIsOffline() ||
            event.username == oldName.value && (resumed || claims.byId(source.id)?.status != LegacyProfile.Status.AVAILABLE)) {
            event.result = PreLoginEvent.PreLoginComponentResult.denied(Component.text("CLAIM_E2E_UNAVAILABLE"))
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun postlogin(event: PostLoginEvent) = checked {
        val native = checkNotNull(gate)
        val player = event.player
        val held = player.username == newName.value
        check(!player.isOnlineMode && player.uniqueId == if (held && !resumed) provisionalUuid else oldUuid)
        check(native.common.isAuthenticated(native.ports.player(player)) == !held)
        val stage = if (held) (if (resumed) "reauth" else "claim") else "seed"
        stageBySession[player.sessionId] = stage
        if (held) check(nativeClaimant.compareAndSet(null, player.sessionId))
        logger.info("CLAIM_NATIVE_LOGIN stage={} session={} uuid={} authenticated={}", stage, player.sessionId, player.uniqueId, !held)
    }

    @Subscribe(order = PostOrder.FIRST)
    fun backend(event: ServerPreConnectEvent) = checked {
        if (event.originalServer.serverInfo.name != "backend") return@checked
        val native = checkNotNull(gate)
        check(accepting && native.common.isAuthenticated(native.ports.player(event.player)))
        check(event.player.uniqueId == oldUuid && stageBySession[event.player.sessionId] != "claim")
    }

    @Subscribe(order = PostOrder.LAST)
    fun connected(event: ServerConnectedEvent) = checked {
        if (event.server.serverInfo.name == "backend") {
            check(event.player.uniqueId == oldUuid)
            logger.info("CLAIM_NATIVE_BACKEND stage={} session={} uuid={}", stageBySession[event.player.sessionId], event.player.sessionId, oldUuid)
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun disconnected(event: DisconnectEvent) = checked {
        val stage = stageBySession.remove(event.player.sessionId) ?: return@checked
        if (stage == "seed") seedLeft = true
        logger.info("CLAIM_NATIVE_DISCONNECTED stage={} session={}", stage, event.player.sessionId)
    }

    @Subscribe(order = PostOrder.LAST)
    fun initialize(event: ProxyInitializeEvent) = checked {
        check(scenario in setOf("claim", "authme", "cancel", "disconnect", "expired"))
        check(!resumed || scenario in setOf("claim", "authme"))
        val run = Path.of(checkNotNull(System.getProperty("kyokou.claimE2e.run"))).toRealPath()
        val root = Path.of(checkNotNull(System.getProperty("kyokou.claimE2e.root"))).toRealPath()
        check(root.fileName.toString() == "profile-claim-e2e" && root.parent.fileName.toString() == ".narrafork")
        check(run.startsWith(root) && run != root && folder.toAbsolutePath().normalize().startsWith(run))
        val marker = Files.readString(run.resolve("isolated-profile-claim-fixture.json"))
        check("\"isolatedFixture\": true" in marker && "\"syntheticFixture\": true" in marker)
        val driver = DriverLibrary(run.resolve("fixture-libraries").toFile(), "", false, {}).also { drivers = it }
        if (scenario == "authme" && !resumed) {
            authMeVerifier = AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE,
                run.resolve("authme-snapshot.db").toString()), driver.classLoader(Dialect.SQLITE))
        }
        val db = Database(DatabaseDescriptor(Dialect.H2, database = run.resolve("interactive-claims").toString()),
            driverLoader = driver::classLoader).also { database = it }
        claims = ProfileClaimRepository(db)
        identities = IdentityRepository(db)
        source = if (resumed) {
            check(!System.getProperty("kyokou.claimE2e.migrationEnabled", "false").toBooleanStrict())
            check(claims.resolveMicrosoftProfile(xuid) == oldUuid)
            checkNotNull(claims.byProfile(oldUuid)).also { check(it.status == LegacyProfile.Status.CLAIMED) }
        } else {
            claims.preflightLegacy("synthetic-native", oldName.value, oldUuid)
            check(claims.byProfile(oldUuid) == null && identities.all().isEmpty())
            claims.importLegacy("synthetic-native", oldName.value, oldUuid)
        }
        val config = ProxyConfig(mapOf("post-login.server" to "backend", "bedrock.enabled" to "false",
            "role-selection.enabled" to "false", "migration.enabled" to (!resumed).toString(),
            "verification.poll-interval-seconds" to "1", "verification.timeout-seconds" to "30",
            "limbo.join-timeout-seconds" to "15"), folder.toFile())
        val messagesFolder = folder.resolve("messages")
        Files.createDirectories(messagesFolder)
        Files.writeString(messagesFolder.resolve("en_US.yml"), "migration:\n  offer-title: CLAIM_OFFER\n  confirm-old-title: CLAIM_SOURCE\n  confirm-bind-title: CLAIM_BIND\n  cancelled: CLAIM_CANCELLED\n  expired: CLAIM_EXPIRED\n  claim-rejected: CLAIM_BAD_PROOF\nverification:\n  reconnect: CLAIM_RECONNECT\n")
        val messages = MessageCatalogue("en_US", messagesFolder.toFile()).also { it.load() }
        val baseline = BoundIdentity.of(Identity.Microsoft(oldName, Xuid(2535411111111111L), "SyntheticLegacySeed", oldUuid), 1L)
        val sessions = moe.ryuu.kyokouauth.core.storage.SessionRepository(db)
        check(sessions.find(oldUuid) == null)
        val coordinator = AuthCoordinator(identities, sessions, sessionMillis = 60_000L,
            directory = { moe.ryuu.kyokouauth.core.auth.MojangDirectory.Lookup.Free },
            microsoftProfile = { subject -> claims.resolveMicrosoftProfile(subject) ?: Identity.ofXuid(subject) })
        val services = object : LoginGate.Services {
            override fun allow(key: String) = true
            override fun decide(name: GameName, address: String): AuthCoordinator.Decision = when (name) {
                oldName -> AuthCoordinator.Decision.Admit(baseline.toIdentity(), baseline)
                newName -> coordinator.decide(name, address).also { decision ->
                    check(decision is AuthCoordinator.Decision.Verify && decision.provisional == !resumed &&
                        decision.uuid == if (resumed) oldUuid else provisionalUuid)
                    if (resumed) logger.info("CLAIM_NATIVE_RESTART_LOADED uuid={} migrationFlag=false", decision.uuid)
                }
                else -> error("Unexpected isolated player")
            }
            override fun permitsMappedProfile(record: BoundIdentity) = record == baseline && !resumed ||
                record.subject == xuid.value.toString() && record.uuid == claims.resolveMicrosoftProfile(xuid)
            override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome = error("Unexpected premium bind in the legacy Microsoft claim fixture")
            override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome {
                check(resumed && name == newName && xuid == this@InteractiveProfileClaimE2ePlugin.xuid &&
                    gamertag == this@InteractiveProfileClaimE2ePlugin.gamertag && begins.get() == 1 && exchanges.get() == 1)
                val bound = coordinator.bindMicrosoft(name, xuid, gamertag)
                check(bound is AuthCoordinator.ClaimOutcome.Bound && bound.record.uuid == oldUuid)
                val session = checkNotNull(nativeClaimant.get())
                stageBySession[session] = "mapped"
                logger.info("CLAIM_NATIVE_REAUTH_BOUND session={} uuid={}", session, bound.record.uuid)
                return bound
            }
            override fun grantSession(uuid: UUID, address: String) {
                check(resumed && uuid == oldUuid && begins.get() == 1 && exchanges.get() == 1)
                coordinator.grantSession(uuid, address)
                check(sessions.find(uuid) != null)
                logger.info("CLAIM_NATIVE_REAUTH_GRANTED session={} uuid={}", nativeClaimant.get(), uuid)
            }
            override fun submit(task: () -> Unit) { io.execute(task) }
            override fun schedule(delayMillis: Long, task: () -> Unit) = scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
            override fun begin(): DeviceCodeFlow.Challenge {
                check(begins.incrementAndGet() == 1)
                logger.info("CLAIM_NATIVE_MICROSOFT_BEGIN session={} synthetic=true", nativeClaimant.get())
                return DeviceCodeFlow.Challenge("SYNTHETIC", "https://example.invalid", "https://example.invalid", 30, 1, "fixture-only")
            }
            override fun poll(challenge: DeviceCodeFlow.Challenge) = DeviceCodeFlow.PollResult.Authorized("synthetic-only", null, 30)
            override fun exchange(accessToken: String): XboxLive.XstsToken {
                check(accessToken == "synthetic-only" && exchanges.incrementAndGet() == 1)
                logger.info("CLAIM_NATIVE_MICROSOFT_PROVED session={} synthetic=true", nativeClaimant.get())
                return XboxLive.XstsToken("synthetic", xuid.value, gamertag, "synthetic-only")
            }
            override fun authMeClaimsAvailable() = scenario == "authme" && !resumed
            override fun proveAuthMeProfile(legacyName: GameName, password: CharArray, session: UUID, name: GameName,
                                            current: () -> Boolean): ProfileClaimFlow.PreparedClaim? {
                check(scenario == "authme" && !resumed && current() && currentSourceIsOffline() &&
                    legacyName == oldName && name == newName && session == nativeClaimant.get())
                privateSourceChecks.incrementAndGet()
                val verified = checkNotNull(authMeVerifier).verify(legacyName, password)
                if (verified != AuthMeVerification.VERIFIED) return null
                check(current())
                val grant = claims.issue(source.id, session, name,
                    proofMethod = moe.ryuu.kyokouauth.core.storage.LegacyProofMethod.AUTHME_PASSWORD)
                logger.info("CLAIM_NATIVE_OLD_PROOF session={} synthetic=true", session)
                return ProfileClaimFlow.PreparedClaim(grant.token, source.id, oldName, oldUuid, grant.expiresAt)
            }
            override fun approveLegacyProfile(recordId: UUID, session: UUID, name: GameName): ProfileClaimFlow.PreparedClaim {
                check(scenario != "authme") { "Private source proof cannot fall back to console approval" }
                check(currentSourceIsOffline() && recordId == source.id && session == nativeClaimant.get() && name == newName)
                val grant = claims.issue(recordId, session, name, if (scenario == "expired") 2_000L else 60_000L)
                logger.info("CLAIM_NATIVE_OLD_PROOF session={} synthetic=true", session)
                return ProfileClaimFlow.PreparedClaim(grant.token, source.id, oldName, oldUuid, grant.expiresAt)
            }
            override fun commitLegacyProfile(token: ProfileClaimFlow.ClaimToken, session: UUID, name: GameName,
                xuid: Xuid, gamertag: String, current: () -> Boolean): BoundIdentity {
                check(scenario in setOf("claim", "authme") && begins.get() == 1 && exchanges.get() == 1 &&
                    menus[session]?.get() == 3 && currentSourceIsOffline() && commits.incrementAndGet() == 1)
                return claims.commit(token.value, session, name, xuid, gamertag, current = current).also {
                    logger.info("CLAIM_NATIVE_COMMITTED session={} uuid={} sourceOffline=true", session, it.uuid)
                    if (scenario == "authme") {
                        check(privateSourceChecks.get() == 2 && publicClaimEvents.get() == 0 && publicClaimCompletions.get() == 0)
                        val event = db.withConnection { c -> c.createStatement().use { sql ->
                            sql.executeQuery("SELECT event_type FROM kyokouauth_profile_claim_audit").use { rows ->
                                check(rows.next()); rows.getString(1).also { check(!rows.next()) }
                            }
                        } }
                        check(event == "AUTHME_PASSWORD_CLAIM")
                        logger.info("CLAIM_NATIVE_PRIVATE_SOURCE session={} attempts=2 commandEvents=0 completionEvents=0 audit=AUTHME_PASSWORD_CLAIM", session)
                    }
                }
            }
        }
        val native = LoginGate(proxy, config, messages, services, logger).also { gate = it }
        proxy.eventManager.register(this, native)
        val listener = object : LimboListener {
            override fun onJoin(player: LimboPlayer) {
                val recording = object : LimboPlayer by player {
                    override fun openConfirmation(menu: ConfirmationMenu) {
                        val count = menus.computeIfAbsent(player.connectionId) { AtomicInteger() }.incrementAndGet()
                        check(count in 1..3 && !native.common.isAuthenticated(native.ports.player(checkNotNull(proxy.getPlayer(newName.value).orElse(null)))))
                        logger.info("CLAIM_NATIVE_MENU session={} index={} generation={}", player.connectionId, count, menu.generation)
                        player.openConfirmation(menu)
                    }
                }
                handles[player] = recording
                native.onJoin(recording)
            }
            override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) {
                val handle = handles[player] ?: return
                native.onRoleSelect(handle, generation, optionId)
                val request = native.common.pendingProfileClaims().singleOrNull { it.sessionId == player.connectionId }
                if (scenario != "authme" && request?.stage == ProfileClaimFlow.Stage.WAITING_OLD) {
                    native.common.approveProfileClaim(request.sessionId, source.id).whenComplete { _, failure ->
                        if (failure != null) logger.error("CLAIM_E2E_ASSERTION_FAILED", failure)
                    }
                }
            }
            override fun onLeave(player: LimboPlayer) {
                handles.remove(player)?.let(native::onLeave)
                if (scenario !in setOf("claim", "authme")) io.execute {
                    checked {
                        check(commits.get() == 0 && claims.byId(source.id)?.status == LegacyProfile.Status.AVAILABLE &&
                            claims.ownerByProfile(oldUuid) == null && identities.all().isEmpty())
                        check(db.withConnection { c -> c.createStatement().use { sql ->
                            sql.executeQuery("SELECT COUNT(*) FROM kyokouauth_profile_claim_audit").use { it.next(); it.getInt(1) }
                        } } == 0)
                        logger.info("CLAIM_NATIVE_NO_MUTATION case={} menus={} begins={} exchanges={} backend=false",
                            scenario, menus[player.connectionId]?.get(), begins.get(), exchanges.get())
                    }
                }
            }
        }
        val forwarding = proxy.configuration.javaClass.getMethod("getForwardingSecret").invoke(proxy.configuration) as ByteArray
        limbo = LimboServer(config.limbo, listener, LimboLogger { message, error ->
            if (error == null) logger.info(message) else logger.warn(message, error)
        }, forwardingSecret = forwarding).also { engine ->
            engine.startAll().forEach { (protocol, address) -> native.ports.registerEngine(protocol, address) }
        }
        native.activate()
        accepting = true
        logger.info("CLAIM_E2E_STARTED nativeMenus=true case={} resumed={} proofs=synthetic", scenario, resumed)
    }

    @Subscribe
    fun shutdown(event: ProxyShutdownEvent) {
        accepting = false
        gate?.deactivate()
        limbo?.close()
        gate?.ports?.unregisterEngines()
        io.shutdown()
        scheduler.shutdownNow()
        io.awaitTermination(5, TimeUnit.SECONDS)
        authMeVerifier?.close()
        database?.close()
        drivers?.close()
    }
}
