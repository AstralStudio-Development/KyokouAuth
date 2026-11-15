package moe.ryuu.kyokouauth.bukkit.inject

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import moe.ryuu.kyokouauth.core.auth.MojangSession
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture

/**
 * Performs the vanilla Mojang handshake for connections whose name Mojang owns.
 *
 * ## Why this exists at all
 *
 * An offline-mode server never asks a client to prove anything, so a premium player arrives as an
 * offline UUID and an unverified claim. Doing the handshake ourselves is the only way to turn that
 * claim into a fact without a proxy - and it is the same work that lets 1.8-1.12 assign a UUID at
 * all, since `AsyncPlayerPreLoginEvent#setPlayerProfile` does not exist there (ADR-0004, ADR-0009).
 *
 * ## Where it sits
 *
 * Inserted **after `splitter`**, so it sees decompressed frames with the length prefix already
 * stripped, and its writes must therefore frame themselves. Encryption is installed at the positions
 * vanilla uses (`decrypt` before `splitter`, `encrypt` before `prepender`), which keeps this handler
 * working on plaintext in both directions.
 *
 * ## What it deliberately does not do
 *
 * It never invents an identity. If the handshake fails for any reason the connection is dropped and
 * the player retries through the device-code path, which is slower but cannot be wrong.
 */
class PremiumHandshakeHandler(
    private val keys: HandshakeKeys,
    private val isPremiumName: (String) -> Boolean,
    private val onVerified: (name: String, profile: MojangSession.Profile, address: String) -> Unit,
    private val supportsShouldAuthenticate: Boolean,
    /** Where the session confirmation is asked. Defaults to Mojang; a mirror or a test stub may differ. */
    private val sessionServerUrl: String,
    /**
     * Renders the disconnect text a player sees, already `§`-coded.
     *
     * A String rather than a Component on purpose: this handler sits on the pipeline where every
     * supported version meets, and a Component would make the whole class unloadable on a server
     * without Adventure. Clients honour `§`-codes inside a JSON text field, so nothing is lost.
     */
    private val renderKick: (reason: KickReason) -> String,
    private val logger: (String) -> Unit,
) : ChannelDuplexHandler() {

    /** Why a handshake was refused. Separated from the log text so both can be specific. */
    enum class KickReason {
        /** Mojang never confirmed the session: almost always a cracked client using a premium name. */
        NOT_AUTHENTICATED,

        /** Mojang could not be reached, so the claim could not be checked. */
        SESSION_SERVER_UNAVAILABLE,

        /** The client answered the challenge incorrectly. */
        HANDSHAKE_FAILED,
    }

    private enum class State { AWAITING_LOGIN_START, AWAITING_ENCRYPTION_RESPONSE, PASS_THROUGH }

    private var state = State.AWAITING_LOGIN_START
    private var pendingName: String? = null
    private var pendingLoginStart: ByteBuf? = null
    private var verifyToken: ByteArray? = null
    private var pendingSecret: ByteArray? = null
    private var remoteAddress: String = "?"

    override fun channelActive(ctx: ChannelHandlerContext) {
        remoteAddress = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "?"
        super.channelActive(ctx)
    }

    /**
     * True until the intention packet has been seen.
     *
     * The Handshake packet and Login Start are both id `0x00`, and the Handshake arrives first. A
     * handler that treats the first `0x00` as a login start reads the protocol version as a name -
     * which is exactly the kind of bug that looks like "the server just drops connections".
     */
    private var awaitingIntention = true

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (state == State.PASS_THROUGH || msg !is ByteBuf) {
            ctx.fireChannelRead(msg)
            return
        }

        val cursor = Cursor(msg.readerIndex())
        val packetId = cursor.readVarInt(msg)

        if (awaitingIntention) {
            awaitingIntention = false
            ctx.fireChannelRead(msg)
            return
        }

        when {
            state == State.AWAITING_LOGIN_START && packetId == 0x00 -> handleLoginStart(ctx, msg)
            state == State.AWAITING_ENCRYPTION_RESPONSE && packetId == 0x01 -> handleEncryptionResponse(ctx, msg)
            else -> ctx.fireChannelRead(msg)
        }
    }

    private fun handleLoginStart(ctx: ChannelHandlerContext, packet: ByteBuf) {
        val name = try {
            val cursor = Cursor(packet.readerIndex())
            cursor.readVarInt(packet) // packet id
            cursor.readString(packet)
        } catch (malformed: Throwable) {
            // A frame too short to hold a name is not a login start we can act on. Standing down
            // lets the server decide, which is always safer than guessing at a malformed frame -
            // and this path is reached in practice, by Bedrock connections that arrive without the
            // packet shape assumed here.
            state = State.PASS_THROUGH
            ctx.fireChannelRead(packet)
            return
        }

        // The premium check walks the Mojang directory, which is an HTTP call on a cache miss.
        // Running it here would block the event loop that every other connection shares, so the
        // channel stops reading until the answer arrives - the client simply waits, which is exactly
        // what it is doing at this point in the handshake anyway.
        ctx.channel().config().isAutoRead = false

        val held = packet.retain()
        packet.release()

        java.util.concurrent.CompletableFuture
            .supplyAsync { runCatching { isPremiumName(name) }.getOrDefault(false) }
            .whenComplete { premium, _ ->
                ctx.channel().eventLoop().execute {
                    ctx.channel().config().isAutoRead = true
                    if (premium) {
                        beginHandshake(ctx, name, held)
                    } else {
                        // Not a Mojang name: vanilla carries on and the login gate takes over.
                        state = State.PASS_THROUGH
                        ctx.fireChannelRead(held)
                    }
                }
            }
    }

    private fun beginHandshake(ctx: ChannelHandlerContext, name: String, heldStart: ByteBuf) {
        pendingName = name
        verifyToken = keys.newVerifyToken()

        // Hold the packet: vanilla must not process a login start until the claim is proven, or it
        // would hand out an offline UUID for a name that is about to be rejected.
        pendingLoginStart = heldStart

        sendEncryptionRequest(ctx, verifyToken!!)
        state = State.AWAITING_ENCRYPTION_RESPONSE
    }

    private fun handleEncryptionResponse(ctx: ChannelHandlerContext, packet: ByteBuf) {
        val name = pendingName
        val token = verifyToken
        if (name == null || token == null) {
            kick(ctx, KickReason.HANDSHAKE_FAILED, "handshake state lost")
            return
        }

        try {
            val cursor = Cursor(packet.readerIndex())
            cursor.readVarInt(packet) // packet id
            val encryptedSecret = cursor.readByteArray(packet)
            val encryptedToken = cursor.readByteArray(packet)
            packet.release()

            val sharedSecret = keys.decrypt(encryptedSecret)
            val returnedToken = keys.decrypt(encryptedToken)

            if (!keys.challengeMatches(returnedToken, token)) {
                kick(ctx, KickReason.HANDSHAKE_FAILED, "verify token mismatch")
                return
            }

            // Retained so `finish` can install the ciphers once Mojang confirms the claim. Without
            // this the encryption handlers would never be added and the client would decrypt
            // nothing, hanging at the loading screen.
            pendingSecret = sharedSecret

            val hash = MojangSession.serverHash("", sharedSecret, keys.keyPair.public)

            // The session server is a blocking HTTP call. It must not run on the event loop, so the
            // connection is resumed from a worker once Mojang answers.
            CompletableFuture.supplyAsync { MojangSession.hasJoined(name, hash, sessionServerUrl) }
                .whenComplete { profile, failure ->
                    ctx.channel().eventLoop().execute { finish(ctx, name, profile, failure) }
                }
        } catch (failure: Throwable) {
            kick(ctx, KickReason.HANDSHAKE_FAILED, "malformed encryption response: ${failure.message}")
        }
    }

    private fun finish(
        ctx: ChannelHandlerContext,
        name: String,
        profile: MojangSession.Profile?,
        failure: Throwable?,
    ) {
        if (failure != null) {
            kick(ctx, KickReason.SESSION_SERVER_UNAVAILABLE, "session server unreachable: ${failure.message}")
            return
        }
        if (profile == null) {
            // Mojang does not confirm this client owns the account: either a cracked client using a
            // premium name, or a session that expired between the challenge and the check.
            kick(ctx, KickReason.NOT_AUTHENTICATED, "hasJoined returned no session for '$name'")
            return
        }
        if (!profile.name.equals(name, ignoreCase = true)) {
            kick(ctx, KickReason.HANDSHAKE_FAILED, "session server returned '${profile.name}' for '$name'")
            return
        }

        installEncryption(ctx)
        onVerified(name, profile, remoteAddress)

        // Now that the claim is proven, let vanilla process the login start it never saw.
        state = State.PASS_THROUGH
        pendingLoginStart?.let {
            ctx.fireChannelRead(it)
            pendingLoginStart = null
        }
    }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    // ---- packet construction ----

    private fun sendEncryptionRequest(ctx: ChannelHandlerContext, token: ByteArray) {
        val payload = ctx.alloc().buffer()
        writeVarInt(payload, 0x01)
        writeString(payload, "")
        writeByteArray(payload, keys.publicKeyEncoded)
        writeByteArray(payload, token)
        // 1.20.5 (protocol 766) added this flag. Sending it to a client that does not expect it
        // shifts every subsequent field, so it is gated on the server actually speaking that era.
        if (supportsShouldAuthenticate) payload.writeBoolean(true)

        ctx.writeAndFlush(frame(ctx, payload))
    }

    private fun installEncryption(ctx: ChannelHandlerContext) {
        val secret = pendingSecret ?: return
        val (decrypt, encrypt) = keys.ciphers(secret)
        val pipeline = ctx.pipeline()

        if (pipeline.get("decrypt") == null) {
            pipeline.addBefore("splitter", "decrypt", DecryptHandler(decrypt))
        }
        if (pipeline.get("encrypt") == null) {
            pipeline.addBefore("prepender", "encrypt", EncryptHandler(encrypt))
        }
    }

    /**
     * Sends a login-phase Disconnect and closes the channel.
     *
     * Closing the socket alone would surface on the client as "Connection lost" with no reason,
     * which is indistinguishable from a crash. A real Disconnect packet carries the message that
     * tells the player their client did not authenticate.
     */
    private fun kick(ctx: ChannelHandlerContext, reason: KickReason, detail: String) {
        logger("rejected a premium handshake from $remoteAddress: $detail")
        pendingLoginStart?.release()
        pendingLoginStart = null

        val json = try {
            "{\"text\":\"" + escapeJson(renderKick(reason)) + "\"}"
        } catch (_: Throwable) {
            "{\"text\":\"could not complete the login handshake\"}"
        }

        try {
            val payload = ctx.alloc().buffer()
            writeVarInt(payload, 0x00) // Login Disconnect
            writeString(payload, json)
            ctx.writeAndFlush(frame(ctx, payload)).addListener { _ -> ctx.close() }
        } catch (_: Throwable) {
            ctx.close()
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        // Frames written by handlers before us are already framed; ours carry no length prefix yet.
        // `splitter` is inbound-only, so anything we send must be framed here.
        super.write(ctx, msg, promise)
    }

    // ---- framing helpers ----

    private fun frame(ctx: ChannelHandlerContext, payload: ByteBuf): ByteBuf {
        val header = ctx.alloc().buffer(5)
        writeVarInt(header, payload.readableBytes())
        return ctx.alloc().compositeBuffer(2).addComponents(true, header, payload)
    }

    /** Minimal read cursor over a frame, used for the handful of login-phase fields this needs. */
    private class Cursor(private var index: Int) {

        fun readVarInt(buffer: ByteBuf): Int {
            var value = 0
            var position = 0
            while (true) {
                val read = buffer.getByte(index++).toInt()
                value = value or ((read and 0x7F) shl position)
                if (read and 0x80 == 0) return value
                position += 7
                if (position > 35) throw IllegalStateException("varint too long")
            }
        }

        fun readString(buffer: ByteBuf): String {
            val length = readVarInt(buffer)
            val bytes = ByteArray(length)
            buffer.getBytes(index, bytes)
            index += length
            return String(bytes, Charsets.UTF_8)
        }

        fun readByteArray(buffer: ByteBuf): ByteArray {
            val length = readVarInt(buffer)
            val bytes = ByteArray(length)
            buffer.getBytes(index, bytes)
            index += length
            return bytes
        }
    }

    private fun writeVarInt(buffer: ByteBuf, value: Int) {
        var remaining = value
        while (true) {
            if (remaining and 0x7F.inv() == 0) {
                buffer.writeByte(remaining)
                return
            }
            buffer.writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    private fun writeString(buffer: ByteBuf, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(buffer, bytes.size)
        buffer.writeBytes(bytes)
    }

    private fun writeByteArray(buffer: ByteBuf, value: ByteArray) {
        writeVarInt(buffer, value.size)
        buffer.writeBytes(value)
    }

    /** AES/CFB8 decrypt, installed before `splitter` so the frame decoder sees plaintext. */
    private class DecryptHandler(private val cipher: javax.crypto.Cipher) : ChannelDuplexHandler() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) {
                val length = msg.readableBytes()
                val bytes = ByteArray(length)
                msg.readBytes(bytes)
                msg.release()
                val plain = cipher.update(bytes)
                ctx.fireChannelRead(ctx.alloc().buffer(plain.size).writeBytes(plain))
                return
            }
            ctx.fireChannelRead(msg)
        }
    }

    /** AES/CFB8 encrypt, installed before `prepender` so the length prefix is encrypted too. */
    private class EncryptHandler(private val cipher: javax.crypto.Cipher) : ChannelDuplexHandler() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is ByteBuf) {
                val length = msg.readableBytes()
                val bytes = ByteArray(length)
                msg.readBytes(bytes)
                msg.release()
                val sealed = cipher.update(bytes)
                super.write(ctx, ctx.alloc().buffer(sealed.size).writeBytes(sealed), promise)
                return
            }
            super.write(ctx, msg, promise)
        }
    }
}
