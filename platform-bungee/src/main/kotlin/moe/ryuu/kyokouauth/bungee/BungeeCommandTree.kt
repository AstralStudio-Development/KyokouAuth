package moe.ryuu.kyokouauth.bungee

import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.RootCommandNode
import java.util.Collections
import java.util.IdentityHashMap

/** Uses only Brigadier builders: no node flags, parser properties or packet fields are encoded here. */
internal object BungeeCommandTree {
    @Suppress("UNCHECKED_CAST")
    fun filter(root: RootCommandNode<*>, blocked: (String) -> Boolean): RootCommandNode<*> {
        val source = root as RootCommandNode<Any?>
        val denied = Collections.newSetFromMap(IdentityHashMap<CommandNode<Any?>, Boolean>())
        val queue = ArrayDeque<CommandNode<Any?>>()
        source.children.filter { blocked(it.name) }.forEach(queue::addLast)
        if (queue.isEmpty()) return root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!denied.add(node)) continue
            check(denied.size <= MAX_NODES) { "Native command tree exceeds filtering budget" }
            node.children.forEach(queue::addLast)
        }

        val result = RootCommandNode<Any?>()
        val copies = IdentityHashMap<CommandNode<Any?>, CommandNode<Any?>>()
        val building = Collections.newSetFromMap(IdentityHashMap<CommandNode<Any?>, Boolean>())
        copies[source] = result
        fun copy(node: CommandNode<Any?>): CommandNode<Any?>? {
            if (node in denied) return null
            copies[node]?.let { return it }
            check(building.add(node)) { "Unsupported cyclic native command redirect" }
            try {
                val builder = node.createBuilder()
                node.redirect?.let { target ->
                    // Root redirects point to the new root, never back to the unfiltered graph.
                    val redirect = copy(target) ?: return null
                    builder.forward(redirect, node.redirectModifier, node.isFork)
                }
                val value = builder.build()
                copies[node] = value
                check(copies.size <= MAX_NODES) { "Native command tree exceeds filtering budget" }
                node.children.forEach { child -> copy(child)?.let(value::addChild) }
                return value
            } finally { building.remove(node) }
        }
        source.children.forEach { child -> copy(child)?.let(result::addChild) }
        return result
    }

    private const val MAX_NODES = 65_536
}
