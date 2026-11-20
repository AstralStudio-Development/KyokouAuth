package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.Unpooled
import org.geysermc.mcprotocollib.auth.GameProfile
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

internal object SignedTestProfile {
    val secret = "local-test-secret-not-production".toByteArray()
    val uuid = UUID.fromString("38aef317-7922-4c5a-aaf4-e973691f0c15")
    val connectionId = UUID.fromString("9ad924e6-cbef-473d-8a7a-871c65414f45")
    fun payload(name: String = "OraclePlayer", binding: Boolean = true, extra: Boolean = false, profileId: UUID = uuid): ByteArray {
        val buffer = Unpooled.buffer()
        val body = try {
            MinecraftTypes.writeVarInt(buffer, 1)
            MinecraftTypes.writeString(buffer, "127.0.0.1")
            val profile = GameProfile(profileId, name).apply {
                setProperties(if (binding) listOf(GameProfile.Property(ForwardedIdentity.CONNECTION_PROPERTY, connectionId.toString())) else emptyList())
            }
            MinecraftTypes.writeStaticGameProfile(buffer, profile)
            if (extra) buffer.writeByte(0)
            ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }
        } finally { buffer.release() }
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(body) + body
    }
}

class ForwardedIdentityTest {
    @Test fun `only a signed proxy profile carries the trusted connection identity`() {
        val result = ForwardedIdentity.verify(SignedTestProfile.payload(), SignedTestProfile.secret, "OraclePlayer")
        assertEquals(SignedTestProfile.uuid, result.profile.id)
        assertEquals(SignedTestProfile.connectionId, result.connectionId)
        assertTrue(result.profile.properties.isEmpty(), "internal connection binding must not be forwarded to clients")
    }
    @Test fun `a reserved alias still requires the signed matching placeholder identity`() {
        val id = UUID.nameUUIDFromBytes("OfflinePlayer:Temporary".toByteArray())
        val alias = moe.ryuu.kyokouauth.limbo.VerificationProfileName.of(id)
        val result = ForwardedIdentity.verify(SignedTestProfile.payload(alias, profileId = id), SignedTestProfile.secret, alias)
        assertEquals(id, result.profile.id)
        assertEquals(alias, result.profile.name)
        assertFails { ForwardedIdentity.verify(SignedTestProfile.payload(alias), SignedTestProfile.secret, alias) }
        assertFails { ForwardedIdentity.verify(SignedTestProfile.payload(alias, profileId = id), "wrong".toByteArray(), alias) }
        assertFails { ForwardedIdentity.verify(SignedTestProfile.payload("other\$name", profileId = id),
            SignedTestProfile.secret, "other\$name") }
    }
    @Test fun `wrong secret and tampering are rejected`() {
        assertFailsWith<IllegalArgumentException> { ForwardedIdentity.verify(SignedTestProfile.payload(), "wrong".toByteArray(), "OraclePlayer") }
        val tampered = SignedTestProfile.payload().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { ForwardedIdentity.verify(tampered, SignedTestProfile.secret, "OraclePlayer") }
    }
    @Test fun `a client name cannot replace the signed profile name`() {
        assertFailsWith<IllegalArgumentException> { ForwardedIdentity.verify(SignedTestProfile.payload(), SignedTestProfile.secret, "OtherName") }
    }
    @Test fun `missing binding truncated and unsigned payloads never fall back`() {
        assertFails { ForwardedIdentity.verify(SignedTestProfile.payload(binding = false), SignedTestProfile.secret, "OraclePlayer") }
        assertFails { ForwardedIdentity.verify(byteArrayOf(1), SignedTestProfile.secret, "OraclePlayer") }
        assertFails { ForwardedIdentity.verify(SignedTestProfile.payload().drop(32).toByteArray(), SignedTestProfile.secret, "OraclePlayer") }
        assertFails { ForwardedIdentity.verify(SignedTestProfile.payload(extra = true), SignedTestProfile.secret, "OraclePlayer") }
    }
}
