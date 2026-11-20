package moe.ryuu.kyokouauth.bungee

import io.netty.channel.Channel
import net.md_5.bungee.UserConnection
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.netty.ChannelWrapper

/**
 * Check the native contract, not a build label or archive fingerprint.
 * Profile setters, login states and packet codecs retain their own scoped checks.
 */
internal object BungeeRuntime {
    private val compatible: Unit by lazy {
        try {
            val handler = Class.forName("net.md_5.bungee.connection.InitialHandler", false,
                UserConnection::class.java.classLoader)
            check(PendingConnection::class.java.isAssignableFrom(handler)) {
                "Unsupported Bungee InitialHandler contract"
            }
            check(ProxiedPlayer::class.java.isAssignableFrom(UserConnection::class.java)) {
                "Unsupported Bungee UserConnection contract"
            }
            check(UserConnection::class.java.getMethod("getCh").returnType == ChannelWrapper::class.java) {
                "Unsupported Bungee UserConnection.getCh return type"
            }
            check(ChannelWrapper::class.java.getMethod("getHandle").returnType == Channel::class.java) {
                "Unsupported Bungee ChannelWrapper.getHandle return type"
            }
            val login = net.md_5.bungee.protocol.packet.LoginSuccess::class.java
            val properties = Array<net.md_5.bungee.protocol.data.Property>::class.java
            check(login.getMethod("getProperties").returnType == properties &&
                login.getMethod("setProperties", properties).returnType == Void.TYPE) {
                "Unsupported Bungee client login property isolation contract"
            }
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("Unsupported Bungee native contract: ${failure.message}; login remains closed", failure)
        } catch (failure: LinkageError) {
            throw IllegalStateException("Unsupported Bungee native linkage; login remains closed", failure)
        }
    }

    fun requireCompatible() { compatible }
}
