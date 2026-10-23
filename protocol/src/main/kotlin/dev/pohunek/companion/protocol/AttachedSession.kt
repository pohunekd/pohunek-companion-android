package dev.pohunek.companion.protocol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlin.time.Duration

/** How often a failed attach is redone from `session.attach`; both values are required and positive. */
class AttachPolicy(val maxAttempts: Int, val retryDelay: Duration) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(retryDelay.isPositive()) { "retryDelay must be positive" }
    }
}

/**
 * One live attach: the control connection that carries `session.resize` and `session.detach`, and the raw connection that carries the
 * terminal bytes. The first bytes read are the daemon's full repaint of the terminal state.
 */
class AttachedSession private constructor(
    val sessionId: String,
    val streamId: String,
    private val control: PohunekConnection,
    private val raw: RawAttachStream,
) : AutoCloseable {
    /** The next terminal bytes, or null when the daemon ended the stream or [close] was called. */
    suspend fun read(): ByteArray? = raw.read()

    suspend fun write(bytes: ByteArray) = raw.write(bytes)

    /** One liveness probe on the control connection; see [PohunekConnection.checkAlive]. */
    suspend fun checkAlive(timeout: Duration) = control.checkAlive(timeout)

    /** Resizes the session's PTY; returns the session summary the daemon reports afterwards. */
    suspend fun resize(dimensions: TerminalDimensions): SessionInfo = control.resize(sessionId, dimensions)

    /**
     * Asks the daemon to end the attach. After the raw stream ended with a typed worker failure, the first call returns it in
     * [SessionDetachResult.error].
     */
    suspend fun detach(): SessionDetachResult = control.detach(streamId)

    /** Closes both connections; closing the raw connection also ends the attach on the daemon. */
    override fun close() {
        raw.close()
        control.close()
    }

    companion object {
        /**
         * Attaches to [sessionId] with the view's [dimensions]. Any failure of a step, including a redemption error, discards the attempt
         * (and its control connection) and redoes the whole attach from `session.attach` after [AttachPolicy.retryDelay], up to
         * [AttachPolicy.maxAttempts] times; the last failure is thrown. An unusable route or protocol version is not retried.
         */
        suspend fun open(
            route: HostRoute,
            sessionId: String,
            dimensions: TerminalDimensions,
            config: ClientConfig,
            policy: AttachPolicy,
            ioDispatcher: CoroutineDispatcher,
        ): AttachedSession {
            var failure: ProtocolClientException? = null
            for (attempt in 1..policy.maxAttempts) {
                if (attempt > 1) delay(policy.retryDelay)
                try {
                    return attemptOnce(route, sessionId, dimensions, config, ioDispatcher)
                } catch (e: ProtocolClientException) {
                    if (e.isFinal) throw e
                    failure = e
                }
            }
            throw checkNotNull(failure) { "maxAttempts is at least 1, so an attempt ran" }
        }

        /** One whole attach on its own control connection, which is closed again when any step fails. */
        private suspend fun attemptOnce(
            route: HostRoute,
            sessionId: String,
            dimensions: TerminalDimensions,
            config: ClientConfig,
            ioDispatcher: CoroutineDispatcher,
        ): AttachedSession {
            val control = PohunekConnection.open(route, config, ioDispatcher)
            var attached = false
            try {
                val streamId = control.attach(sessionId, dimensions).streamId
                val raw = RawAttachStream.redeem(route, streamId, config, ioDispatcher)
                attached = true
                return AttachedSession(sessionId, streamId, control, raw)
            } finally {
                if (!attached) control.close()
            }
        }

        private val ProtocolClientException.isFinal: Boolean
            get() = this is ProtocolClientException.InvalidRoute || this is ProtocolClientException.VersionMismatch
    }
}
