package moe.ryuu.kyokouauth.proxy

/** Platforms supply their own logger; the shared flow never binds a proxy's logging libraries. */
fun interface ProxyLog {
    enum class Level { INFO, WARN, ERROR }
    fun log(level: Level, message: String, error: Throwable?)
    fun info(message: String, vararg args: Any?) = emit(Level.INFO, message, args)
    fun warn(message: String, vararg args: Any?) = emit(Level.WARN, message, args)
    fun error(message: String, vararg args: Any?) = emit(Level.ERROR, message, args)

    private fun emit(level: Level, template: String, args: Array<out Any?>) {
        val failure = args.lastOrNull() as? Throwable
        val values = if (failure == null) args.asList() else args.dropLast(1)
        var index = 0
        val message = Regex("\\{\\}").replace(template) { if (index < values.size) values[index++].toString() else "{}" }
        log(level, message, failure)
    }
}
