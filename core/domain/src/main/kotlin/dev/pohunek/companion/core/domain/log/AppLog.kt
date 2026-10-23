package dev.pohunek.companion.core.domain.log

enum class LogLevel { Debug, Info, Warn, Error }

/** The value of a [LogField]; only kinds that cannot carry session names, paths, prompts or terminal content exist. */
sealed interface LogValue {
    data class Text(val value: String) : LogValue

    data class Number(val value: Long) : LogValue
}

/**
 * One typed field of a log line. The factories are the field policy: a line carries host keys, session ids, method names, error codes
 * and numbers, never a session name, a working directory, a prompt or terminal content. Text is cut to [MAX_TEXT_LENGTH] characters.
 */
class LogField private constructor(val name: String, val value: LogValue) {
    companion object {
        /** Longest text a field keeps. */
        const val MAX_TEXT_LENGTH: Int = 128

        /** A short stable key of a host (see `hostKeyField`), not its address. */
        fun hostKey(key: String): LogField = text("host", key)

        /** The daemon's opaque session id. */
        fun sessionId(id: String): LogField = text("session", id)

        /** A protocol method name such as `session.detach`. */
        fun method(name: String): LogField = text("method", name)

        /** A machine-readable error code, never a free-text message. */
        fun errorCode(code: String): LogField = text("error", code)

        /** A counter, size or duration; [name] is a fixed identifier chosen in code. */
        fun number(name: String, value: Long): LogField = LogField(name, LogValue.Number(value))

        private fun text(name: String, value: String) = LogField(name, LogValue.Text(value.take(MAX_TEXT_LENGTH)))
    }
}

/**
 * Structured application log: one event per call, named `area.action`, with typed fields. Safe to call from any thread; a call never
 * throws, a sink that cannot write reports it through its own failure channel.
 */
interface AppLog {
    fun log(level: LogLevel, event: String, fields: List<LogField> = emptyList())
}

fun AppLog.debug(event: String, fields: List<LogField> = emptyList()) = log(LogLevel.Debug, event, fields)

fun AppLog.info(event: String, fields: List<LogField> = emptyList()) = log(LogLevel.Info, event, fields)

fun AppLog.warn(event: String, fields: List<LogField> = emptyList()) = log(LogLevel.Warn, event, fields)

fun AppLog.error(event: String, fields: List<LogField> = emptyList()) = log(LogLevel.Error, event, fields)
