package dev.pohunek.companion.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ConnectFailureTest {
    private val config = ClientConfig(connectTimeout = 5.seconds, requestTimeout = 5.seconds)

    @Test
    fun aNameThatDoesNotResolveIsAConnectionFailureWithAReadableMessage() = runTest {
        val route = HostRoute("no-such-name.invalid", 1)
        val failure = runCatching { PohunekConnection.open(route, config, Dispatchers.IO) }.exceptionOrNull()
        assertTrue("was $failure", failure is ProtocolClientException.ConnectionFailed)
        val message = failure!!.message!!
        assertTrue(message, message.startsWith("cannot connect to $route: "))
        assertEquals("cannot connect to $route: the host name cannot be resolved", message)
        assertEquals(route, (failure as ProtocolClientException.ConnectionFailed).route)
    }
}
