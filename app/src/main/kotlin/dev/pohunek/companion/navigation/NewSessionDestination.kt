package dev.pohunek.companion.navigation

import kotlinx.serialization.Serializable

/**
 * The new session form. [host] is the `HOST:PORT` to preselect, [project] a project id of that host and [group] the id of the owner
 * group the created session is placed in; each is optional.
 */
@Serializable
data class NewSessionDestination(val host: String? = null, val project: String? = null, val group: String? = null)
