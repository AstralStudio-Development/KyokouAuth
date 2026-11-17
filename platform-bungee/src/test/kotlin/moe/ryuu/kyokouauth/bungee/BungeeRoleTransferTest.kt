package moe.ryuu.kyokouauth.bungee

import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.*

class BungeeRoleTransferTest {
    private class Fixture {
        var protocol = 777
        var connected = true
        var calls = 0
        val native = CompletableFuture<ByteArray?>()
        val connection = nativeStub<PendingConnection> { method, args -> when (method) {
            "getVersion" -> protocol
            "isConnected" -> connected
            "retrieveCookie" -> { assertEquals("kyokouauth:role_transfer", args[0]); calls++; native }
            else -> error(method)
        } }
    }

    @Test fun `native absent differs from errors and cookie bytes are copied`() {
        val absent = Fixture()
        BungeeRoleTransfer().use { bridge ->
            val future = bridge.retrieve(absent.connection)
            absent.native.complete(null)
            assertNull(future.join())
            val present = Fixture()
            val received = bridge.retrieve(present.connection)
            val bytes = byteArrayOf(5, 8)
            present.native.complete(bytes)
            bytes[0] = 9
            assertContentEquals(byteArrayOf(5, 8), received.join())
            val failed = Fixture()
            val rejected = bridge.retrieve(failed.connection)
            failed.native.completeExceptionally(IllegalStateException("native failure"))
            assertFailsWith<CompletionException> { rejected.join() }
        }
    }

    @Test fun `duplicate retrieve is rejected without a second native request`() {
        val fixture = Fixture()
        BungeeRoleTransfer().use { bridge ->
            val first = bridge.retrieve(fixture.connection)
            assertFailsWith<CompletionException> { bridge.retrieve(fixture.connection).join() }
            assertEquals(1, fixture.calls)
            fixture.native.complete(byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), first.join())
        }
    }

    @Test fun `shutdown disconnect cancel protocol and timeout fail closed and ignore late completion`() {
        for (case in listOf("shutdown", "disconnect", "cancel", "protocol", "timeout")) {
            val fixture = Fixture()
            val bridge = BungeeRoleTransfer(if (case == "timeout") 1 else 3000)
            try {
                if (case == "protocol") fixture.protocol = 766
                val future = bridge.retrieve(fixture.connection)
                when (case) {
                    "shutdown" -> bridge.close()
                    "disconnect" -> { fixture.connected = false; fixture.native.complete(byteArrayOf(1)) }
                    "cancel" -> bridge.cancel(fixture.connection)
                }
                assertFails { future.get(1, TimeUnit.SECONDS) }
                fixture.native.complete(byteArrayOf(2))
                assertTrue(future.isCompletedExceptionally)
                if (case == "protocol") assertEquals(0, fixture.calls)
                if (case in listOf("shutdown", "cancel", "timeout")) assertTrue(fixture.native.isCancelled)
            } finally { bridge.close() }
        }
    }

    @Test fun `native store precedes transfer and hint transfer stores no cookie`() {
        val fixture = Fixture()
        val calls = mutableListOf<String>()
        var stored: ByteArray? = null
        val player = nativeStub<ProxiedPlayer> { method, args -> when (method) {
            "isConnected" -> fixture.connected
            "getPendingConnection" -> fixture.connection
            "storeCookie" -> { assertEquals("kyokouauth:role_transfer", args[0]); stored = args[1] as ByteArray; calls += "store"; null }
            "transfer" -> { assertEquals("proxy.example", args[0]); assertEquals(25565, args[1]); calls += "transfer"; null }
            else -> error(method)
        } }
        val bridge = BungeeRoleTransfer()
        try {
            val bytes = byteArrayOf(1, 2)
            bridge.transfer(player, "proxy.example", 25565, bytes).join()
            bytes[0] = 9
            assertContentEquals(byteArrayOf(1, 2), stored)
            assertEquals(listOf("store", "transfer"), calls)
            bridge.transfer(player, "proxy.example", 25565, null).join()
            assertEquals(listOf("store", "transfer", "transfer"), calls)
            for ((host, port) in listOf("" to 25565, "bad host" to 25565, "proxy.example" to 0))
                assertFailsWith<CompletionException> { bridge.transfer(player, host, port, null).join() }
            assertFailsWith<CompletionException> { bridge.transfer(player, "proxy.example", 25565, ByteArray(513)).join() }
            bridge.close()
            assertFailsWith<CompletionException> { bridge.transfer(player, "proxy.example", 25565, null).join() }
            assertEquals(3, calls.size)
        } finally { bridge.close() }
    }

    @Test fun `oversized native cookie fails closed instead of becoming absent`() {
        val response = CompletableFuture<ByteArray?>()
        val pending = nativeStub<PendingConnection> { method, args -> when (method) {
            "getVersion" -> 777
            "isConnected" -> true
            "retrieveCookie" -> { assertEquals("kyokouauth:role_transfer", args[0]); response }
            else -> error(method)
        } }
        BungeeRoleTransfer().use { transfer ->
            val result = transfer.retrieve(pending)
            response.complete(ByteArray(513))
            assertFailsWith<CompletionException> { result.join() }
        }
    }
}
