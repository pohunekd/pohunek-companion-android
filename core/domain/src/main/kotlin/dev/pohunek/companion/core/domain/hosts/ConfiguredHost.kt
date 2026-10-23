// Host rules ported to Kotlin from pohunek-companion `crates/core/src/config.rs` (HostConfig::problem, display_label, the duplicate check).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.hosts

import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.protocol.HostRoute

/** A daemon the owner added by address; [label] is an optional display name. */
data class ConfiguredHost(val route: HostRoute, val label: String?)

/** The name shown on the host's rows and status lines: its sanitized label, else its route. */
fun hostDisplayLabel(host: ConfiguredHost): String =
    host.label?.let(::sanitizeText)?.ifEmpty { null } ?: sanitizeText(host.route.toString())

/** Why a list of hosts is not acceptable. Messages are shown to the owner; they name routes and are never logged. */
sealed interface HostProblem {
    val message: String

    /** A label is set but blank. */
    data class BlankLabel(val route: HostRoute) : HostProblem {
        override val message: String get() = "$route has a blank name; leave it empty or give it a name"
    }

    /** The route is already listed; a session key is `(route, id)`, so one daemon is listed once per route. */
    data class DuplicateRoute(val route: HostRoute) : HostProblem {
        override val message: String get() = "$route is already listed"
    }
}

/** The first problem of [hosts], or null when every route is unique and no label is blank. */
fun hostProblem(hosts: List<ConfiguredHost>): HostProblem? {
    val seen = HashSet<HostRoute>()
    for (host in hosts) {
        if (host.label != null && host.label.isBlank()) return HostProblem.BlankLabel(host.route)
        if (!seen.add(host.route)) return HostProblem.DuplicateRoute(host.route)
    }
    return null
}
