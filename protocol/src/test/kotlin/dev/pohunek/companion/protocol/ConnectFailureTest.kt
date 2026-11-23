package dev.pohunek.companion.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectFailureTest {
    private val config = ClientConfig(connectTimeout = 5.seconds, requestTimeout = 5.seconds)

    // runBlocking, not runTest: the test scheduler would skip the connect timeout ahead of the real name lookup.
    @Test
    fun aNameThatDoesNotResolveIsAConnectionFailureWithAReadableMessage() = runBlocking {
        val route = HostRoute("no-such-name.invalid", 1)
        val failure = runCatching { PohunekConnection.open(route, config, Dispatchers.IO) }.exceptionOrNull()
        assertTrue("was $failure", failure is ProtocolClientException.ConnectionFailed)
        val message = failure!!.message!!
        assertTrue(message, message.startsWith("cannot connect to $route: "))
        assertEquals("cannot connect to $route: the host name cannot be resolved", message)
        assertEquals(route, (failure as ProtocolClientException.ConnectionFailed).route)
    }

    @Test
    fun aReservedInvalidNameIsRefusedWithoutALookup() = runTest {
        var lookups = 0
        val resolver: (String) -> InetAddress = { lookups++; throw UnknownHostException() }
        for (route in listOf(HostRoute("showcase.demo.invalid", 1), HostRoute("Second-Host.Demo.INVALID.", 1))) {
            val failure = runCatching { PohunekConnection.openChannel(route, config, Dispatchers.IO, resolver) }.exceptionOrNull()
            assertTrue("was $failure", failure is ProtocolClientException.ConnectionFailed)
            assertEquals(route, (failure as ProtocolClientException.ConnectionFailed).route)
            assertEquals("cannot connect to $route: the host name cannot be resolved", failure.message)
        }
        assertEquals(0, lookups)
    }

    // runBlocking, not runTest: the test scheduler would skip the connect timeout ahead of the failing lookup.
    @Test
    fun aNameTheResolverCannotFindIsAConnectionFailureWithAReadableMessage() = runBlocking {
        val route = HostRoute("no-such-name.example", 1)
        val failure = runCatching {
            PohunekConnection.openChannel(route, config, Dispatchers.IO) { throw UnknownHostException() }
        }.exceptionOrNull()
        assertTrue("was $failure", failure is ProtocolClientException.ConnectionFailed)
        assertEquals("cannot connect to no-such-name.example:1: the host name cannot be resolved", failure!!.message)
    }

    @Test
    fun aNameLookupThatNeverReturnsEndsInAConnectTimeoutOnceTheConfiguredTimeHasPassed() = runTest {
        val release = CountDownLatch(1)
        val route = HostRoute("slow.example", 1)
        try {
            val failure = async {
                runCatching { PohunekConnection.openChannel(route, config, Dispatchers.IO) { release.await(); throw UnknownHostException() } }.exceptionOrNull()
            }
            advanceTimeBy(config.connectTimeout + 1.milliseconds)
            assertTrue("was ${failure.await()}", failure.await() is ProtocolClientException.ConnectTimeout)
        } finally {
            release.countDown()
        }
    }
}
