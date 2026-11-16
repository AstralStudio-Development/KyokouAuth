package moe.ryuu.kyokouauth.limbo

import java.net.InetSocketAddress
import java.util.ServiceLoader
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Public, library-neutral owner of the eleven independently shaded loopback engines. */
class LimboServer @JvmOverloads constructor(
    private val settings: LimboSettings,
    private val listener: LimboListener = LimboListener.None,
    private val logger: LimboLogger = LimboLogger.Default,
    private val bind: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    forwardingSecret: ByteArray = byteArrayOf(),
    forwardingMode: ForwardingMode? = null,
) : AutoCloseable {
    private val mode = forwardingMode ?: ForwardingMode.VelocityModern(forwardingSecret)
    private val engines = LinkedHashMap<Int, LimboEngine>()
    private var executor: ScheduledThreadPoolExecutor? = null
    private var started = false
    private var closed = false

    init { require(bind.address?.isLoopbackAddress == true) { "Limbo must listen on loopback" } }

    /** Existing callers deliberately retain the single-777 backend contract. */
    @Synchronized
    fun start(): InetSocketAddress = startProtocols(setOf(777)).getValue(777)

    /** All endpoints are private loopback listeners. Startup is all-or-nothing. */
    @Synchronized
    fun startAll(): Map<Int, InetSocketAddress> = startProtocols((767..777).toSet())

    private fun startProtocols(protocols: Set<Int>): Map<Int, InetSocketAddress> {
        check(!started && !closed) { "LimboServer cannot be started twice or after close" }
        NettyRuntimeGuard.check(javaClass.classLoader)
        started = true
        require(protocols.size == 1 || bind.port == 0) { "Multi-engine startup requires an ephemeral port per engine" }
        val tasks = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "KyokouAuth-Limbo").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        executor = tasks
        try {
            val factories = ServiceLoader.load(LimboEngineFactory::class.java, javaClass.classLoader).toList()
            require(factories.map { it.protocolVersion }.distinct().size == factories.size) { "Duplicate protocol engine provider" }
            val directory = factories.associateBy { it.protocolVersion }
            require(directory.keys.containsAll(protocols)) { "Missing required engines: ${protocols - directory.keys}" }
            val legacy = directory[777] ?: error("Protocol 777 is required for legacy biome mapping")
            val key = settings.biomeKey ?: legacy.biomeKeys().getOrNull(settings.biomeId)
                ?: error("Legacy protocol-777 biome-id ${settings.biomeId} is invalid")
            // Validate every selected fixture and key before opening a single socket.
            protocols.forEach { protocol ->
                require(key in directory.getValue(protocol).biomeKeys()) { "Biome $key is absent in protocol $protocol" }
            }
            val result = LinkedHashMap<Int, InetSocketAddress>()
            protocols.sorted().forEach { protocol ->
                val factory = directory.getValue(protocol)
                val context = EngineContext(settings.copy(protocolVersion = protocol, versionName = factory.versionName, biomeKey = key),
                    listener, logger, bind, mode, tasks)
                val engine = factory.create(context)
                engines[protocol] = engine
                result[protocol] = engine.start()
            }
            return result.toMap()
        } catch (failure: Throwable) { close(); throw failure }
    }

    @Synchronized
    fun connectionCount(): Int = engines.values.sumOf { it.connectionCount() }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        engines.values.toList().asReversed().forEach { engine ->
            try { engine.close() } catch (failure: Throwable) { logger.log("Limbo engine cleanup failed", failure) }
        }
        engines.clear()
        executor?.let { tasks ->
            try { tasks.submit {}.get(5, TimeUnit.SECONDS) }
            finally { tasks.shutdownNow() }
        }
        executor = null
        (mode as? ForwardingMode.VelocityModern)?.secret?.fill(0)
    }
}
