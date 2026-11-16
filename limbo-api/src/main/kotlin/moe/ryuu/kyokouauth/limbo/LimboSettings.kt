package moe.ryuu.kyokouauth.limbo

import moe.ryuu.kyokouauth.limbo.protocol.TextComponent

/** Bounded, library-neutral settings. Legacy biomeId is interpreted through protocol 777's keys. */
data class LimboSettings(
    val motd: String = "KyokouAuth",
    val versionName: String = "26.3",
    val protocolVersion: Int = 777,
    val maxPlayers: Int = 0,
    val viewDistance: Int = 2,
    val biomeId: Int = 41,
    val spawn: Spawn = Spawn(0.5, 64.0, 0.5, 0f, 0f),
    val keepAliveSeconds: Long = 10,
    val keepAliveTimeoutSeconds: Long = 30,
    val joinTimeoutSeconds: Long = 30,
    val messages: Messages = Messages.defaults("1.21–26.3 (protocols 767–777)"),
    val biomeKey: String? = null,
) {
    init {
        require(protocolVersion in 767..777)
        require(biomeId >= 0)
        require(biomeKey == null || biomeKey.matches(Regex("[a-z0-9_.-]+:[a-z0-9_/.-]+")))
        require(viewDistance in 2..8 && maxPlayers >= 0)
        require(keepAliveSeconds in 1..300 && keepAliveTimeoutSeconds in 1..600 && joinTimeoutSeconds in 1..600)
        require(spawn.x.isFinite() && spawn.y.isFinite() && spawn.z.isFinite())
        require(kotlin.math.abs(spawn.x) < 29_000_000 && kotlin.math.abs(spawn.z) < 29_000_000)
        require(spawn.y >= -64 && spawn.y < 320 && spawn.yaw.isFinite() && spawn.pitch.isFinite())
    }

    data class Spawn(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)
    data class Messages(val unsupportedVersion: TextComponent) {
        companion object {
            fun defaults(version: String) = Messages(TextComponent("This login screen requires Minecraft $version."))
        }
    }
}
