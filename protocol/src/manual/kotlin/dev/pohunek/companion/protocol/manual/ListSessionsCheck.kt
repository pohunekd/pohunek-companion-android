package dev.pohunek.companion.protocol.manual

import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.ProtocolClientException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private const val ARGUMENT_COUNT = 3

/**
 * Hand-run check: connects to `HOST:PORT`, sends `daemon.health` and `session.list`, and prints the typed results.
 * Usage: `java -cp "$(classpath)" dev.pohunek.companion.protocol.manual.ListSessionsCheckKt HOST:PORT CONNECT_TIMEOUT_MS REQUEST_TIMEOUT_MS`.
 * Prints ids, agents and states only, never names or directories. Exit code 1 means a typed protocol failure, 2 a usage error.
 */
fun main(args: Array<String>) {
    if (args.size != ARGUMENT_COUNT) {
        System.err.println("usage: ListSessionsCheck HOST:PORT CONNECT_TIMEOUT_MS REQUEST_TIMEOUT_MS")
        exitProcess(2)
    }
    val connectMs = args[1].toLongOrNull()
    val requestMs = args[2].toLongOrNull()
    if (connectMs == null || requestMs == null) {
        System.err.println("timeouts must be integers in milliseconds")
        exitProcess(2)
    }
    try {
        runBlocking {
            val route = HostRoute.parse(args[0])
            PohunekConnection.open(route, ClientConfig(connectMs.milliseconds, requestMs.milliseconds), Dispatchers.IO).use { connection ->
                val health = connection.health()
                println("health: status=${health.status} daemon_version=${health.daemonVersion} protocol_version=${health.protocolVersion}")
                println("negotiated_version=${connection.negotiatedVersion}")
                val sessions = connection.listSessions()
                println("sessions: ${sessions.size}")
                sessions.forEach { println("  ${it.id} agent=${it.agent} state=${it.state} activity=${it.activity} size=${it.cols}x${it.rows}") }
            }
        }
    } catch (e: ProtocolClientException) {
        println("typed error: ${e.javaClass.simpleName}: ${e.message}")
        exitProcess(1)
    }
}
