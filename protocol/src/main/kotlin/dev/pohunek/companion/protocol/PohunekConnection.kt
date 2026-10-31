package dev.pohunek.companion.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SocketChannel
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/** Timeouts of a connection; both are required and positive. */
class ClientConfig(val connectTimeout: Duration, val requestTimeout: Duration) {
    init {
        require(connectTimeout.isPositive() && connectTimeout.inWholeMilliseconds in 1..Int.MAX_VALUE) { "connectTimeout must be 1 ms to ${Int.MAX_VALUE} ms" }
        require(requestTimeout.isPositive() && requestTimeout.inWholeMilliseconds >= 1) { "requestTimeout must be at least 1 ms" }
    }
}

/** A daemon's control endpoint, `<host>:<port>`; IPv6 literals are written `[addr]:port`. */
data class HostRoute(val host: String, val port: Int) {
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(route: String): HostRoute {
            val separator = route.lastIndexOf(':')
            if (separator <= 0) throw ProtocolClientException.InvalidRoute(route, "expected <host>:<port>")
            return HostRoute(parseHost(route, route.substring(0, separator)), parsePort(route, route.substring(separator + 1)))
        }

        private fun parseHost(route: String, raw: String): String {
            val bracketed = raw.startsWith("[") && raw.endsWith("]")
            val host = if (bracketed) raw.substring(1, raw.length - 1) else raw
            if (host.isEmpty() || (':' in host && !bracketed)) throw ProtocolClientException.InvalidRoute(route, "an IPv6 address needs brackets")
            return host
        }

        private fun parsePort(route: String, raw: String): Int =
            raw.toIntOrNull()?.takeIf { it in PORT_RANGE } ?: throw ProtocolClientException.InvalidRoute(route, "port must be 1 to 65535")

        private val PORT_RANGE = 1..65_535
    }
}

/**
 * One TCP control connection to a daemon. Requests run one at a time. After a timeout, cancellation, or any failure other than a daemon
 * error the connection is closed and every later call fails with [ProtocolClientException.ConnectionUnusable].
 */
@Suppress("TooManyFunctions") // One method per daemon request, each a single line over call().
class PohunekConnection private constructor(
    private val channel: SocketChannel,
    private val config: ClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val mutex = Mutex()
    private val framer = LineFramer()
    private val decoder = ResponseDecoder()
    private val readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
    private var nextRequestNumber = 1L
    private var unusableReason: String? = null

    /** The protocol version the first response selected, or null before one arrived. */
    val negotiatedVersion: Int? get() = decoder.selectedVersion

    suspend fun health(): DaemonHealth = call { Requests.health(it) }.decodeResult(DaemonHealth.serializer())

    /**
     * Round-trips `daemon.health` and fails with [ProtocolClientException.LivenessTimeout] when no answer arrives within [timeout]. The
     * clock starts once the connection is free, so a slow request ahead of it is bounded by the request timeout instead.
     */
    suspend fun checkAlive(timeout: Duration) {
        call(timeout, { ProtocolClientException.LivenessTimeout(timeout.inWholeMilliseconds) }) { Requests.health(it) }
            .decodeResult(DaemonHealth.serializer())
    }

    suspend fun listSessions(filters: List<SessionFilter> = emptyList()): List<SessionInfo> =
        call { Requests.sessionList(it, filters) }.decodeResult(ListSerializer(SessionInfo.serializer()))

    suspend fun listProjects(): List<ProjectInfo> = call { Requests.projectList(it) }.decodeResult(ListSerializer(ProjectInfo.serializer()))

    suspend fun listProjectActions(reference: String): List<ActionSummary> =
        call { Requests.projectActions(it, reference) }.decodeResult(ProjectActionsResult.serializer()).actions

    suspend fun projectAction(reference: String, name: String): ProjectActionResult =
        call { Requests.projectAction(it, reference, name) }.decodeResult(ProjectActionResult.serializer())

    suspend fun inspectHost(): HostCapabilities = call { Requests.hostInspect(it) }.decodeResult(HostCapabilities.serializer())

    /** Starts a session. The request has no idempotency key, so a caller must never send it twice for one launch. */
    suspend fun newSession(params: SessionNewParams): SessionNewResult = call { Requests.sessionNew(it, params) }.decodeSessionNewResult()

    /**
     * Asks the daemon for its overlay peers. The daemon probes peers before it answers, so [timeout] replaces the request timeout of the
     * connection. Peers speaking another protocol come back as [HostClass.VersionMismatch], because the request advertises this client's range.
     */
    suspend fun discoverHosts(timeout: Duration): List<HostRecord> =
        call(timeout, { ProtocolClientException.RequestTimeout(timeout.inWholeMilliseconds) }) { Requests.hostDiscover(it) }
            .decodeResult(ListSerializer(HostRecord.serializer()))

    suspend fun listNotifications(params: NotificationListParams): NotificationListResult =
        call { Requests.notificationList(it, params) }.decodeResult(NotificationListResult.serializer())

    /** Mints the one-shot stream id for [sessionId]; [dimensions] is the geometry the worker applies before it emits the first byte. */
    suspend fun attach(sessionId: String, dimensions: TerminalDimensions?): SessionAttachResult =
        call { Requests.sessionAttach(it, sessionId, dimensions) }.decodeResult(SessionAttachResult.serializer())

    suspend fun resize(sessionId: String, dimensions: TerminalDimensions): SessionInfo =
        call { Requests.sessionResize(it, sessionId, dimensions) }.decodeResult(SessionResizeResult.serializer()).session

    /** Sets the owner name of [sessionId]; a null [name] clears it. The daemon trims and validates the name. */
    suspend fun rename(sessionId: String, name: String?): SessionInfo =
        call { Requests.sessionRename(it, sessionId, name) }.decodeResult(SessionRenameResult.serializer()).session

    /** Merges [patch] into the metadata of [sessionId]: a string sets its key and a null value removes it. */
    suspend fun setMetadata(sessionId: String, patch: Map<String, String?>): SessionInfo =
        call { Requests.sessionSetMetadata(it, sessionId, patch) }.decodeResult(SessionSetMetadataResult.serializer()).session

    /** Starts a new generation of the ended [sessionId]; the returned summary says whether it already runs. */
    suspend fun resume(sessionId: String): SessionInfo =
        call { Requests.sessionResume(it, sessionId) }.decodeResult(SessionResumeResult.serializer()).session

    suspend fun detach(streamId: String): SessionDetachResult =
        call { Requests.sessionDetach(it, streamId) }.decodeResult(SessionDetachResult.serializer())

    suspend fun stop(sessionId: String): SessionStopResult = call { Requests.sessionStop(it, sessionId) }.decodeResult(SessionStopResult.serializer())

    suspend fun remove(sessionId: String): SessionRemoveResult =
        call { Requests.sessionRemove(it, sessionId) }.decodeResult(SessionRemoveResult.serializer())

    /** Removes like [remove], but proceeds when the only obstacle is processes whose environment cannot be read; the owner consented to this call. */
    suspend fun removeAcceptingUnconfirmed(sessionId: String): SessionRemoveResult =
        call { Requests.sessionRemoveAcceptingUnconfirmed(it, sessionId) }.decodeResult(SessionRemoveResult.serializer())

    override fun close() {
        if (unusableReason == null) unusableReason = "the connection was closed"
        try {
            channel.close()
        } catch (_: IOException) {
            // Nothing is left to release when closing a channel fails.
        }
    }

    private suspend fun call(
        timeout: Duration = config.requestTimeout,
        timedOut: () -> ProtocolClientException = { ProtocolClientException.RequestTimeout(config.requestTimeout.inWholeMilliseconds) },
        build: (String) -> Request,
    ) = mutex.withLock {
        unusableReason?.let { throw ProtocolClientException.ConnectionUnusable(it) }
        val request = build("$REQUEST_ID_PREFIX${nextRequestNumber++}")
        // An oversized request fails before anything is written, so the connection stays usable.
        val line = LineFramer.encode(request.toLine())
        var reusable = false
        try {
            val ok = withTimeoutOrNull(timeout) { runInterruptible(ioDispatcher) { exchange(request, line) } } ?: throw timedOut()
            reusable = true
            if (framer.hasPending) {
                unusableReason = "the daemon sent bytes beyond the response"
                close()
            }
            ok
        } catch (e: ProtocolClientException.Daemon) {
            reusable = true
            throw e
        } finally {
            if (!reusable) {
                unusableReason = "an earlier request failed"
                close()
            }
        }
    }

    /** Interrupting the blocked thread closes the channel; that surfaces as cancellation, not as a lost connection. */
    private fun exchange(request: Request, line: ByteArray) = decoder.decode(
        try {
            val out = ByteBuffer.wrap(line)
            while (out.hasRemaining()) channel.write(out)
            receiveLine()
        } catch (e: ClosedByInterruptException) {
            throw CancellationException("interrupted while waiting for the daemon", e)
        } catch (e: IOException) {
            throw ProtocolClientException.ConnectionLost(e.message ?: e.javaClass.simpleName, e)
        },
        request.id,
    )

    private fun receiveLine(): String {
        while (true) {
            readBuffer.clear()
            val count = channel.read(readBuffer)
            if (count < 0) return framer.finish() ?: throw ProtocolClientException.ConnectionLost("the daemon closed the connection without a response")
            val lines = framer.feed(readBuffer.array(), 0, count)
            if (lines.size > 1) throw ProtocolClientException.MalformedResponse("more than one line answered a single request")
            if (lines.isNotEmpty()) return lines.single()
        }
    }

    companion object {
        private const val REQUEST_ID_PREFIX = "android-"

        /** Size of one TCP read; not a protocol limit. */
        private const val READ_BUFFER_BYTES = 8192

        suspend fun open(route: HostRoute, config: ClientConfig, ioDispatcher: CoroutineDispatcher): PohunekConnection =
            PohunekConnection(openChannel(route, config, ioDispatcher), config, ioDispatcher)

        /** A connected blocking channel to [route]; shared with the raw attach connection. */
        internal suspend fun openChannel(route: HostRoute, config: ClientConfig, ioDispatcher: CoroutineDispatcher): SocketChannel {
            // A connect that finishes as the timeout fires or the caller is cancelled has its result discarded by
            // the coroutine machinery, so the channel is recorded here and closed unless it is handed to the caller.
            val connected = AtomicReference<SocketChannel?>(null)
            var handedOver = false
            try {
                val channel = withTimeoutOrNull(config.connectTimeout) {
                    runInterruptible(ioDispatcher) { connect(route, config).also { connected.set(it) } }
                } ?: throw ProtocolClientException.ConnectTimeout(route, config.connectTimeout.inWholeMilliseconds)
                handedOver = true
                return channel
            } finally {
                if (!handedOver) {
                    try {
                        connected.getAndSet(null)?.close()
                    } catch (_: IOException) {
                        // The cancellation or timeout in flight must not be replaced by a failed close.
                    }
                }
            }
        }

        /** Resolves and connects; the socket timeout bounds the handshake, the caller's timeout also bounds name resolution. */
        private fun connect(route: HostRoute, config: ClientConfig): SocketChannel {
            var opened: SocketChannel? = null
            try {
                opened = SocketChannel.open()
                opened.socket().connect(InetSocketAddress(route.host, route.port), config.connectTimeout.inWholeMilliseconds.toInt())
                return opened
            } catch (e: IOException) {
                opened?.close()
                throw connectFailure(route, config, e)
            } catch (e: UnresolvedAddressException) {
                // The channel API's own signal for a name that does not resolve; the socket adaptor normally translates it to UnknownHostException.
                opened?.close()
                throw connectFailure(route, config, e)
            }
        }

        private fun connectFailure(route: HostRoute, config: ClientConfig, e: Exception): Exception = when (e) {
            is SocketTimeoutException -> ProtocolClientException.ConnectTimeout(route, config.connectTimeout.inWholeMilliseconds).also { it.addSuppressed(e) }
            is ClosedByInterruptException -> CancellationException("interrupted while connecting to $route", e)
            else -> ProtocolClientException.ConnectionFailed(route, e)
        }
    }
}
