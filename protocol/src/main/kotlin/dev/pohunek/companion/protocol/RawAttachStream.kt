package dev.pohunek.companion.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SocketChannel

/** The attach prelude and the classification of what the daemon sends first on the raw connection. */
object AttachRedemption {
    private const val NEWLINE: Byte = 0x0a
    private const val OPEN_BRACE: Byte = '{'.code.toByte()

    /** `{"attach":"<stream id>"}` and the newline; the prelude has no version field. */
    fun prelude(streamId: String): ByteArray = LineFramer.encode(buildJsonObject { put("attach", streamId) }.toString())

    /**
     * The typed failure when [bytes] begin with a complete error response line, else null: the bytes are PTY output and must be replayed
     * unchanged. A redemption failure is the only framed response the daemon sends on this connection, stamped with the stream id.
     */
    fun failureIn(bytes: ByteArray, streamId: String): ProtocolClientException? {
        val line = errorResponseLine(bytes) ?: return null
        return try {
            ResponseDecoder().decode(line, streamId)
            ProtocolClientException.MalformedResponse("the attach redemption answered without an error")
        } catch (e: ProtocolClientException) {
            e
        }
    }

    /** The first line of [bytes] when it is a complete JSON object that carries an `err` member. */
    private fun errorResponseLine(bytes: ByteArray): String? {
        val newline = bytes.indexOf(NEWLINE)
        val line = if (newline < 0) null else decodeOrNull(bytes.copyOfRange(0, newline))
        return line?.takeIf { parseObject(it)?.containsKey("err") == true }
    }

    private fun decodeOrNull(bytes: ByteArray): String? = try {
        LineFramer.decodeLine(bytes)
    } catch (_: ProtocolClientException.Framing) {
        null
    }

    private fun parseObject(line: String): JsonObject? = try {
        Wire.json.parseToJsonElement(line) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    /** True while a first chunk could still be the start of an error response line, so more bytes are needed before it is replayed. */
    internal fun mayBeResponse(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == OPEN_BRACE && NEWLINE !in bytes
}

/**
 * The raw bidirectional byte connection of one attach. Bytes are opaque: nothing here assumes UTF-8 or line framing. One reader and one
 * writer may run at the same time. Closing the stream makes a pending [read] return null.
 */
class RawAttachStream private constructor(
    private val channel: SocketChannel,
    private val ioDispatcher: CoroutineDispatcher,
    private var firstBytes: ByteArray?,
) : AutoCloseable {
    private val writeMutex = Mutex()
    private val readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES)

    @Volatile
    private var closed = false

    /** The next bytes the daemon sent, or null when the stream ended or was closed. */
    suspend fun read(): ByteArray? {
        firstBytes?.let {
            firstBytes = null
            return it
        }
        return runInterruptible(ioDispatcher) { receive() }
    }

    suspend fun write(bytes: ByteArray) {
        writeMutex.withLock {
            runInterruptible(ioDispatcher) { send(bytes) }
        }
    }

    override fun close() {
        closed = true
        try {
            channel.close()
        } catch (_: IOException) {
            // Nothing is left to release when closing a channel fails.
        }
    }

    private fun receive(): ByteArray? {
        try {
            readBuffer.clear()
            val count = channel.read(readBuffer)
            if (count < 0) return null
            return readBuffer.array().copyOf(count)
        } catch (e: ClosedByInterruptException) {
            throw CancellationException("interrupted while reading the attach stream", e)
        } catch (e: IOException) {
            if (closed) return null
            throw ProtocolClientException.ConnectionLost(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun send(bytes: ByteArray) {
        try {
            val out = ByteBuffer.wrap(bytes)
            while (out.hasRemaining()) channel.write(out)
        } catch (e: ClosedByInterruptException) {
            throw CancellationException("interrupted while writing to the attach stream", e)
        } catch (e: IOException) {
            throw ProtocolClientException.ConnectionLost(e.message ?: e.javaClass.simpleName, e)
        }
    }

    companion object {
        /** Size of one TCP read; not a protocol limit. */
        private const val READ_BUFFER_BYTES = 16_384

        /**
         * Opens a second connection to [route], redeems [streamId], and waits for the first bytes within the request timeout. A redemption
         * error arrives as a normal error response and is thrown typed; the stream id is one-shot, so a failure here is never retried with
         * the prelude alone.
         */
        suspend fun redeem(route: HostRoute, streamId: String, config: ClientConfig, ioDispatcher: CoroutineDispatcher): RawAttachStream {
            val channel = PohunekConnection.openChannel(route, config, ioDispatcher)
            var redeemed = false
            try {
                val first = withTimeoutOrNull(config.requestTimeout) { runInterruptible(ioDispatcher) { redeemBlocking(channel, streamId) } }
                    ?: throw ProtocolClientException.RequestTimeout(config.requestTimeout.inWholeMilliseconds)
                redeemed = true
                return RawAttachStream(channel, ioDispatcher, first)
            } finally {
                if (!redeemed) channel.close()
            }
        }

        private fun redeemBlocking(channel: SocketChannel, streamId: String): ByteArray = try {
            exchangeFirstBytes(channel, streamId)
        } catch (e: ClosedByInterruptException) {
            throw CancellationException("interrupted while redeeming the attach stream", e)
        } catch (e: IOException) {
            throw ProtocolClientException.ConnectionLost(e.message ?: e.javaClass.simpleName, e)
        }

        private fun exchangeFirstBytes(channel: SocketChannel, streamId: String): ByteArray {
            val prelude = ByteBuffer.wrap(AttachRedemption.prelude(streamId))
            while (prelude.hasRemaining()) channel.write(prelude)
            val first = readFirstBytes(channel)
            val failure = AttachRedemption.failureIn(first, streamId)
            if (failure != null) throw failure
            return first
        }

        /** Reads until the bytes cannot be the start of an error response line any more, so a split error line is never replayed as output. */
        private fun readFirstBytes(channel: SocketChannel): ByteArray {
            val buffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
            var received = ByteArray(0)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) {
                    if (received.isEmpty()) throw ProtocolClientException.ConnectionLost("the daemon closed the attach connection before sending any bytes")
                    return received
                }
                received += buffer.array().copyOf(count)
                if (received.size > Wire.MAX_CONTROL_LINE_BYTES || !AttachRedemption.mayBeResponse(received)) return received
            }
        }
    }
}
