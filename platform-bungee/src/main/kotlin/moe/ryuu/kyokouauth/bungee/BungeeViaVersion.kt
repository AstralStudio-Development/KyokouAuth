package moe.ryuu.kyokouauth.bungee

import java.util.LinkedHashMap

/** Optional public Via/ViaBungee APIs only; private engines never enter the proxy directory. */
internal class BungeeViaVersion(
    private val lookup: () -> Any?,
    private val resolve: (Any) -> Pair<Any, Any> = ::resolvePublicApi,
) : AutoCloseable {
    private val protocols = linkedMapOf<String, Int>()
    private var binding: Binding? = null
    private var closed = false

    @Synchronized fun register(name: String, protocol: Int) {
        check(!closed && binding == null) { "Private ViaVersion declarations are already active" }
        require(protocol in 767..777 && name == "kyokouauth-limbo-$protocol") { "Not a KyokouAuth private engine" }
        check(protocols.putIfAbsent(name, protocol) == null) { "Private ViaVersion declaration already exists" }
    }

    /** Called before accepting connections, while the HashMap can be populated without live logins. */
    @Synchronized fun install() = access { current ->
        val map = configuration(current)
        protocols.forEach { (name, protocol) -> declareCache(current, map, name, protocol) }
    }

    /** Any API/configuration failure propagates to the native connection's refusal path. */
    @Synchronized fun prepare(name: String, protocol: Int) {
        check(protocols[name] == protocol) { "Unknown private ViaVersion declaration" }
        access { current -> declareCache(current, configuration(current), name, protocol) }
    }

    private fun access(action: (Binding) -> Unit) {
        check(!closed) { "Private ViaVersion declarations are closed" }
        try {
            val plugin = lookup()
            if (plugin == null) {
                check(binding == null) { "Previously active ViaVersion provider disappeared" }
                return
            }
            val current = binding ?: resolve(plugin).let { (platform, monitor) ->
                Binding(plugin, platform, monitor).also { binding = it }
            }
            check(current.plugin === plugin) { "ViaVersion provider changed during private-engine lifetime" }
            // ViaBungee's ping-save writer uses this same public configuration-provider monitor.
            synchronized(current.monitor) { action(current) }
        } catch (failure: Exception) {
            throw IllegalStateException("ViaVersion cannot safely declare KyokouAuth private engine protocols", failure)
        } catch (failure: LinkageError) {
            throw IllegalStateException("ViaVersion public private-engine APIs are incompatible", failure)
        }
    }

    private fun configuration(current: Binding): MutableMap<String, Int> {
        val map = current.configuration()
        if (current.claim?.map !== map) {
            current.claim?.let(::restoreConfiguration)
            // Reload replaces ViaBungee's HashMap. Populate this generation just once, not per login.
            val originals = protocols.keys.associateWith { map[it] }
            current.claim = ConfigurationClaim(map, originals)
            protocols.forEach { (name, protocol) -> map[name] = protocol }
        }
        return map
    }

    private fun declareCache(current: Binding, map: MutableMap<String, Int>, name: String, protocol: Int) {
        check(map[name] == protocol) { "ViaVersion private engine '$name' declaration was changed externally" }
        val previous = current.detected()[name]
        if (!current.cacheOriginals.containsKey(name) || previous != protocol) {
            current.cacheOriginals[name] = previous
        }
        // A stale detected 47 overrides config; refresh through the thread-safe public detector API.
        current.setProtocol(name, protocol)
        check(current.serverProtocol(name) == protocol && current.configuration() === map) {
            "ViaVersion private engine '$name' protocol declaration changed during connection preparation"
        }
    }

    private fun restoreConfiguration(claim: ConfigurationClaim) {
        claim.originals.forEach { (name, original) ->
            val installed = checkNotNull(protocols[name])
            // Never undo another plugin/operator's later edit, nor touch default/real backends.
            if (original == null) claim.map.remove(name, installed)
            else claim.map.replace(name, installed, original)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val current = binding ?: return
        synchronized(current.monitor) {
            current.claim?.let(::restoreConfiguration)
            current.claim = null
            var failure: Exception? = null
            current.cacheOriginals.forEach { (name, original) ->
                try {
                    if (current.detected()[name] == protocols[name]) {
                        if (original == null) current.uncache(name) else current.setProtocol(name, original)
                    }
                } catch (error: Exception) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            current.cacheOriginals.clear()
            failure?.let { throw IllegalStateException("Could not release private ViaVersion protocol declarations", it) }
        }
    }

    private data class ConfigurationClaim(val map: MutableMap<String, Int>, val originals: Map<String, Int?>)

    private class Binding(val plugin: Any, private val platform: Any, val monitor: Any) {
        private val detector = call(platform, "protocolDetectorService")
        private val set = detector.javaClass.getMethod("setProtocolVersion", String::class.java, Int::class.javaPrimitiveType)
        private val remove = detector.javaClass.getMethod("uncacheProtocolVersion", String::class.java)
        private val version = detector.javaClass.getMethod("serverProtocolVersion", String::class.java)
        private val snapshot = detector.javaClass.getMethod("detectedProtocolVersions")
        var claim: ConfigurationClaim? = null
        val cacheOriginals = LinkedHashMap<String, Int?>()

        @Suppress("UNCHECKED_CAST")
        fun configuration(): MutableMap<String, Int> = call(call(platform, "getBungeeConfig"), "getBungeeServerProtocols")
            as? MutableMap<String, Int> ?: error("ViaBungee server protocol configuration is unavailable")

        @Suppress("UNCHECKED_CAST")
        fun detected(): Map<String, Int> = snapshot.invoke(detector) as? Map<String, Int>
            ?: error("ViaVersion detected protocol snapshot is unavailable")

        fun setProtocol(name: String, protocol: Int) { set.invoke(detector, name, protocol) }
        fun uncache(name: String) { remove.invoke(detector, name) }
        fun serverProtocol(name: String): Int = (call(checkNotNull(version.invoke(detector, name)), "getVersion") as Number).toInt()
    }

    private companion object {
        fun call(receiver: Any, method: String): Any = checkNotNull(receiver.javaClass.getMethod(method).invoke(receiver)) {
            "ViaVersion public API '$method' returned null"
        }

        fun resolvePublicApi(plugin: Any): Pair<Any, Any> {
            val via = Class.forName("com.viaversion.viaversion.api.Via", true, plugin.javaClass.classLoader)
            val platform = checkNotNull(via.getMethod("proxyPlatform").invoke(null))
            val manager = checkNotNull(via.getMethod("getManager").invoke(null))
            return platform to call(manager, "getConfigurationProvider")
        }
    }
}
