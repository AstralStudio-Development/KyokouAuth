package moe.ryuu.kyokouauth.proxy

/** Platforms supply their own logger; the shared flow never binds a proxy's logging libraries. */
fun interface ProxyLog {
    enum class Level { INFO, WARN, ERROR }
    fun log(level: Level, message: String, error: Throwable?)
    fun info(message: String, vararg args: Any?) = emit(Level.INFO, message, args)
    fun warn(message: String, vararg args: Any?) = emit(Level.WARN, message, args)
    fun error(message: String, vararg args: Any?) = emit(Level.ERROR, message, args)

    private fun emit(level: Level, template: String, args: Array<out Any?>) {
        if (args.isEmpty()) {
            log(level, template, null)
            return
        }
        val failure = args.lastOrNull() as? Throwable
        val valueCount = if (failure == null) args.size else args.size - 1
        if (valueCount == 0) {
            log(level, template, failure)
            return
        }
        val out = StringBuilder(template.length + valueCount * 16)
        var cursor = 0
        var argIndex = 0
        while (argIndex < valueCount) {
            val found = template.indexOf("{}", cursor)
            if (found < 0) break
            out.append(template, cursor, found).append(args[argIndex++])
            cursor = found + 2
        }
        out.append(template, cursor, template.length)
        log(level, out.toString(), failure)
    }
}
