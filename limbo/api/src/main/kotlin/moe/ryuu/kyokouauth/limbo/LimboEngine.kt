package moe.ryuu.kyokouauth.limbo

import java.net.InetSocketAddress
import java.util.concurrent.ScheduledExecutorService

/** Service boundary shared by eleven separately shaded protocol engines. Only JDK/API types cross it. */
interface LimboEngineFactory {
    val protocolVersion: Int
    val versionName: String
    fun biomeKeys(): List<String>
    fun create(context: EngineContext): LimboEngine
}

data class EngineContext(
    val settings: LimboSettings,
    val listener: LimboListener,
    val logger: LimboLogger,
    val bind: InetSocketAddress,
    val forwardingMode: ForwardingMode,
    val executor: ScheduledExecutorService,
)

interface LimboEngine : AutoCloseable {
    fun start(): InetSocketAddress
    fun connectionCount(): Int
}
