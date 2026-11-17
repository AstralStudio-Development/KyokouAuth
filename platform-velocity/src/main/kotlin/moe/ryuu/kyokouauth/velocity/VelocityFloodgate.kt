package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.ProxyServer
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import moe.ryuu.kyokouauth.proxy.BedrockFailure
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import moe.ryuu.kyokouauth.proxy.FloodgateConnection
import moe.ryuu.kyokouauth.proxy.FloodgateProvider
import moe.ryuu.kyokouauth.proxy.FloodgateRuntime
import java.lang.reflect.Modifier

/** Owns native installation/channel discovery only; the shared runtime validates Floodgate itself. */
internal class VelocityFloodgate(
    private val proxy: ProxyServer,
    private val enabled: Boolean,
    private val allowLinked: Boolean = false,
    private val channel: (InboundConnection) -> Channel = VelocityChannels(proxy.javaClass.classLoader)::resolve,
) : AutoCloseable {
    @Volatile private var closed = false
    private val runtime = FloodgateRuntime(enabled, allowLinked, ::provider)

    fun probe(native: InboundConnection): BedrockLogin {
        if (closed) return unavailable()
        return try {
            // A Java-only install needs neither Velocity internals nor Floodgate classes.
            if (!enabled && provider() == null) return BedrockLogin.Java
            val source = channel(native)
            runtime.probe(FloodgateConnection(
                token = source,
                active = { !closed && native.isActive && source.isActive },
                attribute = { name ->
                    require(name == "floodgate-player" || name == "floodgate-kick-message")
                    val key = AttributeKey.valueOf<Any>(name)
                    if (source.hasAttr(key)) source.attr(key).get() else null
                },
            ))
        } catch (_: Exception) {
            unavailable()
        } catch (_: LinkageError) {
            unavailable()
        }
    }

    private fun provider(): FloodgateProvider? {
        if (closed) return null
        val container = proxy.pluginManager.getPlugin("floodgate").orElse(null) ?: return null
        val instance = container.instance.orElse(null) ?: return null
        val version = container.description.version.orElse("")
        return FloodgateProvider(instance, version, instance.javaClass.classLoader) {
            if (closed) false else {
                val current = proxy.pluginManager.getPlugin("floodgate").orElse(null)
                current != null && current === container && current.instance.orElse(null) === instance &&
                    current.description.version.orElse("") == version
            }
        }
    }

    override fun close() { closed = true }

    private fun unavailable() = BedrockLogin.Rejected(BedrockFailure.UNAVAILABLE)
}

/** Fixed Velocity 4.3.1 signatures; no arbitrary member walking or third-party implementation copy. */
private class VelocityChannels(private val loader: ClassLoader) {
    private class Access(loader: ClassLoader) {
        val login = Class.forName("com.velocitypowered.proxy.connection.client.LoginInboundConnection", false, loader)
        val initial = Class.forName("com.velocitypowered.proxy.connection.client.InitialInboundConnection", false, loader)
        val player = Class.forName("com.velocitypowered.proxy.connection.client.ConnectedPlayer", false, loader)
        val connection = Class.forName("com.velocitypowered.proxy.connection.MinecraftConnection", false, loader)
        val delegate = login.getDeclaredField("delegate").also {
            check(it.type === initial && !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers))
            check(it.trySetAccessible()) { "Velocity login delegate is inaccessible" }
        }
        val initialConnection = initial.getMethod("getConnection").also {
            check(it.returnType === connection && !Modifier.isStatic(it.modifiers))
        }
        val playerConnection = player.getMethod("getConnection").also {
            check(it.returnType === connection && !Modifier.isStatic(it.modifiers))
        }
        val channel = connection.getMethod("getChannel").also {
            check(it.returnType === Channel::class.java && !Modifier.isStatic(it.modifiers))
        }
    }

    // No native class is resolved for the default disabled, absent-provider configuration.
    private val access by lazy { Access(loader) }

    fun resolve(native: InboundConnection): Channel {
        val access = access
        val connection = when (native.javaClass) {
            access.login -> access.initialConnection.invoke(access.delegate.get(native))
            access.initial -> access.initialConnection.invoke(native)
            access.player -> access.playerConnection.invoke(native)
            else -> error("Unsupported Velocity inbound connection")
        }
        check(connection != null && connection.javaClass === access.connection)
        return access.channel.invoke(connection) as Channel
    }
}
