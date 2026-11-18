package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.Unpooled
import org.geysermc.mcprotocollib.auth.GameProfile
import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class ForwardedIdentity(val profile: GameProfile, val address: String, val connectionId: UUID) {
    companion object {
        const val CONNECTION_PROPERTY = "kyokouauth:connection-id"
        private val RAW_UUID = Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        private val UNDASHED_GROUPS = Regex("(.{8})(.{4})(.{4})(.{4})(.{12})")
        private val VALID_NAME = Regex("[A-Za-z0-9_]{3,16}")

        /** Only explicitly selected PrivateTicket mode may inspect bounded legacy forwarding fields. */
        fun redeem(hostname: String, authority: PrivateTicketAuthority, requestedName: String, protocol: Int): ForwardedIdentity {
            require(hostname.length in 1..32767)
            val fields = hostname.split('\u0000')
            require(fields.size == 4 && fields[0].length in 1..255 && fields[1].length in 1..64)
            val rawUuid = fields[2]
            require(RAW_UUID.matches(rawUuid))
            val canonical = if (rawUuid.length == 32) rawUuid.replace(UNDASHED_GROUPS, "$1-$2-$3-$4-$5") else rawUuid
            val uuid = UUID.fromString(canonical)
            require(fields[3].length <= 24576)
            val properties = JsonParser.parseString(fields[3]).asJsonArray
            require(properties.size() in 1..16)
            val tickets = properties.filter { it.asJsonObject.get("name")?.asString == authority.propertyKey }
            require(tickets.size == 1) { "Missing or duplicate private ticket" }
            val token = tickets.single().asJsonObject.get("value").asString
            val trusted = authority.redeem(token, protocol, uuid, requestedName, fields[1])
            // The signed authority record, not the legacy request's arbitrary properties, is authoritative.
            return ForwardedIdentity(GameProfile(trusted.uuid, trusted.name), trusted.address, trusted.connectionId)
        }

        /** Velocity modern forwarding v1: HMAC || version || address || static GameProfile. */
        fun verify(bytes: ByteArray, secret: ByteArray, requestedName: String): ForwardedIdentity {
            require(secret.isNotEmpty() && bytes.size in 33..65536) { "Invalid forwarding envelope" }
            val data = bytes.copyOfRange(32, bytes.size)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), mac.doFinal(data))) { "Invalid forwarding signature" }
            val input = Unpooled.wrappedBuffer(data)
            try {
                require(Wire.readVarInt(input) == 1) { "Unsupported forwarding version" }
                val address = Wire.readString(input)
                require(address.length in 1..64 && address.all { it.isLetterOrDigit() || it in ":.%_-" })
                val uuid = Wire.readUUID(input)
                val name = Wire.readString(input)
                require(name == requestedName && VALID_NAME.matches(name)) { "Forwarded name differs" }
                require(uuid != UUID(0, 0))
                val count = Wire.readVarInt(input)
                require(count in 1..16) { "Invalid profile property count" }
                val properties = List(count) { Wire.readProperty(input) }
                require(!input.isReadable) { "Trailing forwarding data" }
                val binding = properties.filter { it.name == CONNECTION_PROPERTY }.singleOrNull()
                    ?: error("Missing or duplicate connection identity")
                val connectionId = UUID.fromString(binding.value)
                require(connectionId.toString() == binding.value && connectionId != UUID(0, 0))
                val profile = GameProfile(uuid, name).apply {
                    setProperties(properties.filter { it.name != CONNECTION_PROPERTY })
                }
                return ForwardedIdentity(profile, address, connectionId)
            } finally { input.release() }
        }
    }
}
