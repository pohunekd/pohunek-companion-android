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

    suspend fun listSessions(filters: List<SessionFilter> = emptyList()): List<SessionInfo> =
        call { Requests.sessionList(it, filters) }.decodeResult(ListSerializer(SessionInfo.serializer()))

    override fun close() {
        if (unusableReason == null) unusableReason = "the connection was closed"
        try {
            channel.close()
        } catch (_: IOException) {
            // Nothing is left to release when closing a channel fails.
        }
    }

    private suspend fun call(build: (String) -> Request) = mutex.withLock {
        unusableReason?.let { throw ProtocolClientException.ConnectionUnusable(it) }
        val request = build("$REQUEST_ID_PREFIX${nextRequestNumber++}")
        // An oversized request fails before anything is written, so the connection stays usable.
        val line = LineFramer.encode(request.toLine())
        var reusable = false
        try {
            val ok = withTimeoutOrNull(config.requestTimeout) { runInterruptible(ioDispatcher) { exchange(request, line) } }
                ?: throw ProtocolClientException.RequestTimeout(config.requestTimeout.inWholeMilliseconds)
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

    private fun exchange(request: Request, line: ByteArray) = decoder.decode(readLine(line), request.id)

    /** Interrupting the blocked thread closes the channel; that surfaces as cancellation, not as a lost connection. */
    private fun readLine(line: ByteArray): String = try {
        val out = ByteBuffer.wrap(line)
        while (out.hasRemaining()) channel.write(out)
        receiveLine()
    } catch (e: ClosedByInterruptException) {
        throw CancellationException("interrupted while waiting for the daemon", e)
    } catch (e: IOException) {
        throw ProtocolClientException.ConnectionLost(e.message ?: e.javaClass.simpleName, e)
    }

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

        suspend fun open(route: HostRoute, config: ClientConfig, ioDispatcher: CoroutineDispatcher): PohunekConnection {
            val channel = withTimeoutOrNull(config.connectTimeout) { runInterruptible(ioDispatcher) { connect(route, config) } }
                ?: throw ProtocolClientException.ConnectTimeout(route, config.connectTimeout.inWholeMilliseconds)
            return PohunekConnection(channel, config, ioDispatcher)
        }

        /** Resolves and connects; the socket timeout bounds the handshake, the caller's timeout also bounds name resolution. */
        private fun connect(route: HostRoute, config: ClientConfig): SocketChannel {
            val opened = SocketChannel.open()
            try {
                opened.socket().connect(InetSocketAddress(route.host, route.port), config.connectTimeout.inWholeMilliseconds.toInt())
                return opened
            } catch (e: IOException) {
                opened.close()
                throw connectFailure(route, config, e)
            }
        }

        private fun connectFailure(route: HostRoute, config: ClientConfig, e: IOException): Exception = when (e) {
            is SocketTimeoutException -> ProtocolClientException.ConnectTimeout(route, config.connectTimeout.inWholeMilliseconds).also { it.addSuppressed(e) }
            is ClosedByInterruptException -> CancellationException("interrupted while connecting to $route", e)
            else -> ProtocolClientException.ConnectionFailed(route, e)
        }
    }
}
