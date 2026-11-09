package dev.pohunek.companion.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class TrailingFragmentTest {
    private val config = ClientConfig(connectTimeout = 5.seconds, requestTimeout = 5.seconds)

    @Test
    fun `a response cut off before its newline is a lost connection, never a decoded response`() = runBlocking {
        val complete = """{"v":5,"id":"android-1","ok":{"status":"ok","daemon_version":"0.41.0","protocol_version":5}}"""
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val daemon = thread {
                server.accept().use { socket ->
                    socket.getInputStream().read()
                    socket.getOutputStream().write(complete.toByteArray())
                }
            }
            val route = HostRoute(InetAddress.getLoopbackAddress().hostAddress, server.localPort)
            val failure = PohunekConnection.open(route, config, Dispatchers.IO).use { connection ->
                runCatching { connection.health() }.exceptionOrNull()
            }
            daemon.join()
            assertTrue("was $failure", failure is ProtocolClientException.ConnectionLost)
        }
    }
}
