package moe.ryuu.kyokouauth.bungee

import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.PluginDescription
import org.junit.jupiter.api.Test
import kotlin.test.*

class BungeeRoleSkinsTest {
    private fun plugin(): Plugin {
        val description = PluginDescription().apply { name = "SkinsRestorer"; version = "15.12.6" }
        val plugin = object : Plugin(TestProxy(), description) {}
        // The real native init requires PluginClassloader. Match the existing metadata-only FG
        // fixture by initializing the real descriptor field, without relaxing provider checks.
        Plugin::class.java.getDeclaredField("description").apply {
            check(trySetAccessible())
            set(plugin, description)
        }
        return plugin
    }

    @Test fun `absent plugin is optional and installed instance classloader and generation are exact`() {
        var installed: Plugin? = null
        val source = BungeeRoleSkins { installed }
        assertNull(source.provider())
        installed = plugin()
        val first = assertNotNull(source.provider())
        assertSame(installed, first.instance)
        assertSame(checkNotNull(installed).javaClass.classLoader, first.loader)
        assertTrue(first.current())
        installed = plugin()
        assertFalse(first.current())
        val second = assertNotNull(source.provider())
        assertTrue(second.current())
        source.close()
        source.close()
        assertNull(source.provider())
        assertFalse(second.current())
    }

    @Test fun `provider removal or changed descriptor invalidates the old generation`() {
        var installed: Plugin? = plugin()
        val source = BungeeRoleSkins { installed }
        val original = assertNotNull(source.provider())
        checkNotNull(installed).description.version = "different-build"
        assertFalse(original.current())
        installed = null
        assertNull(source.provider())
        source.close()
    }
}
