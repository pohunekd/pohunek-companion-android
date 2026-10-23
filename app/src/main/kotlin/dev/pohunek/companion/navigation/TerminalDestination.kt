package dev.pohunek.companion.navigation

import kotlinx.serialization.Serializable

/** [route] is the host's `HOST:PORT` as `HostRoute.toString` writes it. */
@Serializable
data class TerminalDestination(val route: String, val sessionId: String)
