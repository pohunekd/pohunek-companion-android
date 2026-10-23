package dev.pohunek.companion.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SocketChannel

@Serializable
private data class SubscribeResult(val subscribed: Boolean)

/** Complete lines received so far, in arrival order; one TCP read may complete several. */
internal class LineQueue {
    private val framer = LineFramer()
    private val lines = ArrayDeque<String>()

    fun feed(chunk: ByteArray, length: Int) {
        lines.addAll(framer.feed(chunk, 0, length))
    }

    fun poll(): String? = lines.removeFirstOrNull()
}

/**
 * A connection that [subscribe][open]d to the daemon's events. Opening returns after the daemon acknowledged the subscription, so a
 * seed fetched afterwards cannot miss an event. The connection carries only events from then on; [events] is single use and closes
 * the connection when it ends.
 */
class PohunekSubscription private constructor(
    private val channel: SocketChannel,
    private val config: ClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val lines = LineQueue()
    private val readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
    private var collected = false

    /**
     * Decoded events in arrival order. The flow never completes normally: an end of stream, a read failure, a framing error or an
     * invalid event envelope ends it with the matching [ProtocolClientException]. A payload that does not match its event is a
     * [DaemonEvent.Malformed] element and the flow goes on.
     */
    val events: Flow<DaemonEvent> = flow {
        check(!collected) { "the event stream of a subscription can be collected once" }
        collected = true
        while (true) emit(EventDecoder.decode(runInterruptible(ioDispatcher) { nextLine() }))
    }.onCompletion { close() }

    override fun close() {
        try {
            channel.close()
        } catch (_: IOException) {
            // Nothing is left to release when closing a channel fails.
        }
    }

    private suspend fun acknowledge() {
        val request = Requests.subscribe(REQUEST_ID)
        val line = LineFramer.encode(request.toLine())
        val response = withTimeoutOrNull(config.requestTimeout) {
            runInterruptible(ioDispatcher) {
                write(line)
                nextLine()
            }
        } ?: throw ProtocolClientException.RequestTimeout(config.requestTimeout.inWholeMilliseconds)
        val result = ResponseDecoder().decode(response, REQUEST_ID).decodeResult(SubscribeResult.serializer())
        if (!result.subscribed) throw ProtocolClientException.MalformedResponse("the daemon did not confirm the subscription")
    }

    private fun write(line: ByteArray) {
        try {
            val out = ByteBuffer.wrap(line)
            while (out.hasRemaining()) channel.write(out)
        } catch (e: ClosedByInterruptException) {
            throw CancellationException("interrupted while subscribing", e)
        } catch (e: IOException) {
            throw ProtocolClientException.ConnectionLost(e.message ?: e.javaClass.simpleName, e)
        }
    }

    /** Blocks for the next complete line; interrupting the thread closes the channel, which surfaces as cancellation. */
    private fun nextLine(): String {
        while (true) {
            lines.poll()?.let { return it }
            readBuffer.clear()
            val count = read()
            if (count < 0) throw ProtocolClientException.ConnectionLost("the daemon closed the event stream")
            lines.feed(readBuffer.array(), count)
        }
    }

    /** Reads into the buffer and returns the byte count, negative at the end of the stream; interruption is cancellation. */
    private fun read(): Int {
        val count = try {
            channel.read(readBuffer)
        } catch (e: ClosedByInterruptException) {
            throw CancellationException("interrupted while waiting for the daemon", e)
        } catch (e: IOException) {
            throw ProtocolClientException.ConnectionLost(e.message ?: e.javaClass.simpleName, e)
        }
        return count
    }

    companion object {
        private const val REQUEST_ID = "android-subscribe"
        private const val READ_BUFFER_BYTES = 8192

        /** Connects to [route] and subscribes; closes the connection when the daemon does not confirm. */
        suspend fun open(route: HostRoute, config: ClientConfig, ioDispatcher: CoroutineDispatcher): PohunekSubscription {
            val subscription = PohunekSubscription(PohunekConnection.openChannel(route, config, ioDispatcher), config, ioDispatcher)
            var acknowledged = false
            try {
                subscription.acknowledge()
                acknowledged = true
            } finally {
                if (!acknowledged) subscription.close()
            }
            return subscription
        }
    }
}
