package moe.ryuu.kyokouauth.limbo.lifecycle

/** Pure connection invariants, also exercised without a socket or a Microsoft request. */
class ConnectionLifecycle {
    enum class Phase { HANDSHAKE, STATUS, HELLO, FORWARDING, LOGIN_ACK, KNOWN_PACKS, CONFIG_ACK, JOINING, READY, CLOSED }
    var phase = Phase.HANDSHAKE
        private set
    var batchAcknowledged = false
        private set
    var teleportAcknowledged = false
        private set
    var playerLoaded = false
        private set
    private var pendingPing: Pair<Long, Long>? = null

    fun move(from: Phase, to: Phase) {
        check(phase == from) { "Unexpected packet during $phase" }
        phase = to
    }
    fun acknowledgeBatch(rate: Float) {
        check(phase == Phase.JOINING || phase == Phase.READY)
        require(rate.isFinite() && rate > 0)
        batchAcknowledged = true
    }
    fun acknowledgeTeleport(id: Int, expected: Int) {
        check(phase == Phase.JOINING || phase == Phase.READY)
        require(id == expected)
        teleportAcknowledged = true
    }
    fun loaded(): Boolean {
        check(phase == Phase.JOINING || phase == Phase.READY)
        playerLoaded = true
        if (phase == Phase.JOINING) {
            phase = Phase.READY
            return true
        }
        return false
    }
    fun needsPing(now: Long, timeoutNanos: Long): Boolean {
        pendingPing?.let { check(now - it.second < timeoutNanos) { "Keepalive timeout" }; return false }
        return true
    }
    fun ping(id: Long, now: Long) { check(pendingPing == null); pendingPing = id to now }
    fun pong(id: Long) { require(pendingPing?.first == id) { "Unexpected keepalive response" }; pendingPing = null }
    fun close(): Boolean {
        if (phase == Phase.CLOSED) return false
        phase = Phase.CLOSED
        pendingPing = null
        return true
    }
}
