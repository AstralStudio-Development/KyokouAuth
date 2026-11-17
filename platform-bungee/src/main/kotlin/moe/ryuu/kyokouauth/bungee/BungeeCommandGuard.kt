package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.proxy.CommandNames
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.ProxyLog
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.ChatEvent
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.TabCompleteEvent
import net.md_5.bungee.api.event.TabCompleteResponseEvent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.util.IdentityHashMap
import java.util.Locale

/** Native command boundary; authentication policy stays exclusively in the exact-connection Gate. */
internal class BungeeCommandGuard(
    private val platform: BungeePlatform,
    private val gate: LoginGate,
    private val hasConfiguredBlocks: Boolean = true,
    private val commands: () -> Collection<Map.Entry<String, Command>> = { platform.proxy.pluginManager.commands },
) : Listener, AutoCloseable {
    /** No raw cursor, argument, username or UUID is retained for completion correlation. */
    internal data class Completion(val command: Boolean, val root: Boolean, val restricted: Boolean, val cancelled: Boolean = false)
    private class Queries {
        val pending = LinkedHashMap<Int, Completion>()
        var latest: Completion? = null
        var latestId: Int? = null
    }
    private val queries = IdentityHashMap<ProxiedPlayer, Queries>()
    private var packets: BungeeCommandPackets? = null

    fun enablePacketFilter(logger: ProxyLog) {
        check(packets == null)
        packets = BungeeCommandPackets(this, logger)
    }

    fun isAuthenticated(native: ProxiedPlayer): Boolean = gate.isAuthenticated(platform.find(native))
    fun shouldFilter(native: ProxiedPlayer): Boolean = hasConfiguredBlocks && !isAuthenticated(native)

    /** Alias resolution uses the native registry; the shared Gate alone decides authentication. */
    private fun restrictedRoot(command: String): String? {
        if (!hasConfiguredBlocks) return null
        val root = CommandNames.root(command)
        if (root.isEmpty()) return null
        if (gate.isCommandBlocked(null, root)) return root
        val typed = command.trimStart().takeWhile { !it.isWhitespace() }.trimStart('/').lowercase(Locale.ROOT)
        val registered = commands()
        val native = registered.firstOrNull { it.key.equals(typed, ignoreCase = true) }?.value
            ?: registered.firstOrNull { it.key.equals(root, ignoreCase = true) }?.value
            ?: return null
        return CommandNames.root(native.name).takeIf { gate.isCommandBlocked(null, it) }
    }

    fun isBlocked(native: ProxiedPlayer, command: String): Boolean {
        val root = restrictedRoot(command) ?: return false
        return gate.isCommandBlocked(platform.find(native), root)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onChat(event: ChatEvent) {
        if (event.isCancelled || !event.isCommand) return
        val native = event.sender as? ProxiedPlayer ?: return
        val root = try { restrictedRoot(event.message) } catch (_: Exception) {
            if (shouldFilter(native)) event.isCancelled = true
            return
        } ?: return
        val player = platform.find(native)
        if (!gate.isCommandBlocked(player, root)) return
        // Denial precedes feedback: a broken message sink must never re-enable execution/forwarding.
        event.isCancelled = true
        if (player != null) runCatching { gate.allowCommand(player, root) }
    }

    @Synchronized private fun describe(cursor: String): Completion {
        val leading = cursor.trimStart()
        val command = leading.startsWith('/')
        val root = CommandNames.root(cursor).isEmpty() || leading.none(Char::isWhitespace)
        return Completion(command, root, command && restrictedRoot(cursor) != null)
    }

    /** Called before native TabExecutor dispatch by the narrow packet bridge. */
    @Synchronized fun beginCompletion(native: ProxiedPlayer, id: Int, cursor: String): Boolean {
        val requested = describe(cursor)
        val denied = requested.restricted && !isAuthenticated(native)
        val context = requested.copy(cancelled = denied)
        val state = queries.getOrPut(native) { Queries() }
        state.latest = context
        state.latestId = id
        state.pending[id] = context
        if (state.pending.size > MAX_PENDING_COMPLETIONS) state.pending.remove(state.pending.keys.first())
        return !denied
    }

    @Synchronized fun takeCompletion(native: ProxiedPlayer, id: Int): Completion? {
        val state = queries[native] ?: return null
        return state.pending.remove(id) ?: state.latest.takeIf { state.latestId == id }
    }
    @Synchronized private fun cancelLatest(native: ProxiedPlayer) {
        queries[native]?.let { state ->
            state.latest = state.latest?.copy(cancelled = true)
            state.latestId?.let { id -> state.latest?.let { state.pending[id] = it } }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onTab(event: TabCompleteEvent) {
        val native = event.sender as? ProxiedPlayer ?: return
        if (event.isCancelled) { cancelLatest(native); return }
        val context = try { describe(event.cursor) } catch (_: Exception) {
            if (shouldFilter(native)) {
                event.isCancelled = true
                runCatching { event.suggestions.clear() }
                cancelLatest(native)
            }
            return
        }
        synchronized(this) { queries.getOrPut(native) { Queries() }.latest = context }
        if (!context.command) return
        if (context.restricted && !isAuthenticated(native)) {
            event.isCancelled = true
            runCatching { event.suggestions.clear() }
            cancelLatest(native)
        } else if (context.root) filterSuggestions(native, event.suggestions) { event.isCancelled = true }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onTabResponse(event: TabCompleteResponseEvent) {
        if (event.isCancelled) return
        val native = event.receiver as? ProxiedPlayer ?: return
        val state = synchronized(this) { queries[native]?.let { it.latest to it.pending.isNotEmpty() } }
        // This public event has no transaction ID. Tracked modern responses are filtered by the
        // outbound native packet bridge, after this event, using their exact request ID.
        if (state?.second == true) return
        val context = state?.first
        if (context?.cancelled == true) {
            event.isCancelled = true
            runCatching { event.suggestions.clear() }
            return
        }
        if (context?.command == false) return
        if (context?.restricted == true && !isAuthenticated(native)) {
            event.isCancelled = true
            runCatching { event.suggestions.clear() }
        } else if (context == null || context.root) filterSuggestions(native, event.suggestions) { event.isCancelled = true }
    }

    private fun filterSuggestions(native: ProxiedPlayer, suggestions: MutableList<String>, deny: () -> Unit) {
        if (!shouldFilter(native)) return
        try {
            if (suggestions.any { isBlocked(native, it) }) suggestions.removeIf { isBlocked(native, it) }
        } catch (_: Exception) { deny() } // Never forward an unfiltered immutable/failed suggestion list.
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPostLogin(event: PostLoginEvent) {
        // Native listeners with equal priority need not run in registration order. The LoginEvent
        // already owns this exact PendingConnection; install before the native PostLogin callback
        // can connect a backend, even if BungeeLoginGate has not bound ProxiedPlayer yet.
        val frontend = platform.find(event.player.pendingConnection) ?: return
        if (!frontend.isActive || !frontend.loggedIn) return
        try { packets?.install(event.player) }
        catch (_: Exception) { event.player.disconnect(unavailable().toBungee()) }
    }

    @EventHandler fun onDisconnect(event: PlayerDisconnectEvent) { forget(event.player); packets?.remove(event.player) }
    @Synchronized fun forget(native: ProxiedPlayer) { queries.remove(native) }
    override fun close() { packets?.close(); packets = null; synchronized(this) { queries.clear() } }

    private companion object { const val MAX_PENDING_COMPLETIONS = 64 }
}
