package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.proxy.RoleSkinProvider
import net.md_5.bungee.api.plugin.Plugin

/** Installed public plugin instance only. No SR SDK, username lookup or authentication proof. */
internal class BungeeRoleSkins(private val lookup: () -> Plugin?) : AutoCloseable {
    @Volatile private var closed = false

    fun provider(): RoleSkinProvider? {
        if (closed) return null
        val installed = lookup() ?: return null
        val version = installed.description.version
        val loader = installed.javaClass.classLoader
        return RoleSkinProvider(installed, loader) {
            !closed && lookup() === installed && installed.javaClass.classLoader === loader && installed.description.version == version
        }
    }

    override fun close() { closed = true }
}
