package moe.ryuu.kyokouauth.bungee

import net.md_5.bungee.api.event.ChatEvent
import net.md_5.bungee.api.event.TabCompleteEvent
import net.md_5.bungee.api.event.TabCompleteResponseEvent
import org.junit.jupiter.api.Test
import kotlin.test.*

class BungeeCommandGuardTest {
    @Test fun `held player cannot execute or forward configured native server command`() {
        val h = BungeeCommandFixture()
        assertTrue(h.gate.isCommandBlocked(h.player, "/server lobby"))
        val event = ChatEvent(h.fixture.player, null, "/server lobby")
        h.guard.onChat(event)
        assertTrue(event.isCancelled, "Held command must be stopped before native dispatch or backend forwarding")
    }

    @Test fun `denial precedes feedback and a broken message sink never enables execution`() {
        val h = BungeeCommandFixture()
        val event = ChatEvent(h.fixture.player, null, "/server lobby")
        var deniedAtFeedback = false
        h.fixture.beforeMessage = { deniedAtFeedback = event.isCancelled }
        h.fixture.failMessages = true
        h.guard.onChat(event)
        assertTrue(event.isCancelled)
        assertTrue(deniedAtFeedback)
    }

    @Test fun `authentication of this exact frontend restores command execution`() {
        val h = BungeeCommandFixture()
        h.authenticate()
        val event = ChatEvent(h.fixture.player, null, "/server lobby")
        h.guard.onChat(event)
        assertFalse(event.isCancelled)
    }

    @Test fun `missing and retired native connections do not borrow a replacement authentication`() {
        val h = BungeeCommandFixture()
        h.authenticate()
        val old = h.reconnect()
        h.authenticate()
        for (native in listOf(old.player, PendingFixture(old.name).player)) {
            val event = ChatEvent(native, null, "/server lobby")
            h.guard.onChat(event)
            assertTrue(event.isCancelled)
        }
        val current = ChatEvent(h.fixture.player, null, "/server lobby")
        h.guard.onChat(current)
        assertFalse(current.isCancelled)
    }

    @Test fun `native aliases namespaces and case are blocked but arguments and ordinary chat are not roots`() {
        val h = BungeeCommandFixture()
        h.alias("server", "switch")
        for (text in listOf("/SeRvEr lobby", "/bungeecord:server lobby", "/switch lobby", "/bungeecord:SWITCH lobby")) {
            val event = ChatEvent(h.fixture.player, null, text)
            h.guard.onChat(event)
            assertTrue(event.isCancelled, text)
        }
        for (text in listOf("/help server", "server lobby")) {
            val event = ChatEvent(h.fixture.player, null, text)
            h.guard.onChat(event)
            assertFalse(event.isCancelled, text)
        }
    }

    @Test fun `empty restriction list does not block anything and wildcard blocks every command root`() {
        for (blocked in listOf("[]", "[*]")) {
            val h = BungeeCommandFixture(blocked)
            for (text in listOf("/server lobby", "/help", "/namespace:other")) {
                val event = ChatEvent(h.fixture.player, null, text)
                h.guard.onChat(event)
                assertEquals(blocked != "[]", event.isCancelled, text)
            }
        }
    }

    @Test fun `API root completion hides blocked roots while argument completion is denied`() {
        val h = BungeeCommandFixture()
        h.alias("server", "switch")
        val roots = TabCompleteEvent(h.fixture.player, null, "/", mutableListOf("server", "/switch", "bungeecord:server", "help"))
        h.guard.onTab(roots)
        assertFalse(roots.isCancelled)
        assertEquals(listOf("help"), roots.suggestions)
        val arguments = TabCompleteEvent(h.fixture.player, null, "/server ", mutableListOf("backend"))
        h.guard.onTab(arguments)
        assertTrue(arguments.isCancelled)
        assertTrue(arguments.suggestions.isEmpty())
        val late = TabCompleteResponseEvent(null, h.fixture.player, mutableListOf("backend"))
        h.guard.onTabResponse(late)
        assertTrue(late.isCancelled)
        assertTrue(late.suggestions.isEmpty())
        h.authenticate()
        val restored = TabCompleteEvent(h.fixture.player, null, "/server ", mutableListOf("backend"))
        h.guard.onTab(restored)
        assertFalse(restored.isCancelled)
        assertEquals(listOf("backend"), restored.suggestions)
    }

    @Test fun `API response root filtering and prior cancellation preserve other plugin decisions`() {
        val h = BungeeCommandFixture()
        h.guard.onTab(TabCompleteEvent(h.fixture.player, null, "/se", mutableListOf()))
        val response = TabCompleteResponseEvent(null, h.fixture.player, mutableListOf("server", "help"))
        h.guard.onTabResponse(response)
        assertEquals(listOf("help"), response.suggestions)
        val cancelled = ChatEvent(h.fixture.player, null, "/server").also { it.isCancelled = true }
        h.guard.onChat(cancelled)
        assertTrue(cancelled.isCancelled)
        assertTrue(h.fixture.messages.isEmpty())
        val cancelledTab = TabCompleteEvent(h.fixture.player, null, "/", mutableListOf("server", "help")).also { it.isCancelled = true }
        h.guard.onTab(cancelledTab)
        assertTrue(cancelledTab.isCancelled)
        assertEquals(listOf("server", "help"), cancelledTab.suggestions)
        val nonPlayer = ChatEvent(PendingFixture().pending, null, "/server")
        h.guard.onChat(nonPlayer)
        assertFalse(nonPlayer.isCancelled)
    }

    @Test fun `an immutable response cannot leak a configured root and empty configuration stays untouched`() {
        val held = BungeeCommandFixture()
        val blocked = TabCompleteResponseEvent(null, held.fixture.player, java.util.List.of("server", "help"))
        held.guard.onTabResponse(blocked)
        assertTrue(blocked.isCancelled)
        val open = BungeeCommandFixture("[]")
        val allowed = TabCompleteResponseEvent(null, open.fixture.player, java.util.List.of("server", "help"))
        open.guard.onTabResponse(allowed)
        assertFalse(allowed.isCancelled)
        assertEquals(listOf("server", "help"), allowed.suggestions)
    }
}
