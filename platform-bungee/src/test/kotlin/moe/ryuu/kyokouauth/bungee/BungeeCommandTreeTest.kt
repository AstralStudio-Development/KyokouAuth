package moe.ryuu.kyokouauth.bungee

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.RootCommandNode
import org.junit.jupiter.api.Test
import kotlin.test.*

class BungeeCommandTreeTest {
    @Test fun `native aliases namespace roots and redirect aliases are hidden without mutating the original graph`() {
        val h = BungeeCommandFixture()
        h.alias("server", "switch")
        val root = RootCommandNode<Any?>()
        val server = LiteralArgumentBuilder.literal<Any?>("server").executes { 0 }.build()
        val help = LiteralArgumentBuilder.literal<Any?>("help").executes { 0 }
            .then(LiteralArgumentBuilder.literal<Any?>("server").executes { 0 }).build()
        root.addChild(server)
        root.addChild(help)
        root.addChild(LiteralArgumentBuilder.literal<Any?>("switch").executes { 0 }.build())
        root.addChild(LiteralArgumentBuilder.literal<Any?>("bungeecord:SeRvEr").executes { 0 }.build())
        root.addChild(LiteralArgumentBuilder.literal<Any?>("go").redirect(server).build())
        root.addChild(LiteralArgumentBuilder.literal<Any?>("again").redirect(root).build())

        val filtered = BungeeCommandTree.filter(root) { h.guard.isBlocked(h.fixture.player, it) }
        assertEquals(setOf("help", "again"), filtered.children.map { it.name }.toSet())
        assertNotNull(filtered.getChild("help").getChild("server"), "An argument/subcommand is not a configured root")
        assertSame(help.command, filtered.getChild("help").command)
        assertSame(filtered, filtered.getChild("again").redirect, "Root redirects must not expose the old unfiltered root")
        assertSame(root, root.getChild("again").redirect)
        assertNotNull(root.getChild("server"))
        assertNotNull(root.getChild("go"))

        h.authenticate()
        assertSame(root, BungeeCommandTree.filter(root) { h.guard.isBlocked(h.fixture.player, it) })
    }

    @Test fun `wildcard removes every root and empty list keeps the supplied tree unchanged`() {
        for (blocked in listOf("[*]", "[]")) {
            val h = BungeeCommandFixture(blocked)
            val root = RootCommandNode<Any?>().also {
                it.addChild(LiteralArgumentBuilder.literal<Any?>("server").build())
                it.addChild(LiteralArgumentBuilder.literal<Any?>("help").build())
            }
            val filtered = BungeeCommandTree.filter(root) { h.guard.isBlocked(h.fixture.player, it) }
            if (blocked == "[]") assertSame(root, filtered) else assertTrue(filtered.children.isEmpty())
            assertEquals(2, root.children.size)
        }
    }
}
