// Ported to Kotlin from pohunek-companion `crates/core/src/badge.rs` (WorkFacts, WorkBadge, clip_display, clip_middle), `open.rs`
// (is_openable_url, link selection) and `session.rs` (project label). Repository: https://github.com/pohunekd/pohunek-companion, MIT,
// Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.SessionInfo

private const val WORK_PREFIX = "work.link."
private const val LEGACY_PREFIX = "link."
private const val ROLE_KEY = "work.role"
private const val WORK_LINK_URL_KEY = "work.link.url"
private const val LEGACY_LINK_URL_KEY = "link.url"
private const val ELLIPSIS = '…'

/** Longest link that is offered; a longer metadata value is not a URL worth opening. */
const val OPENABLE_URL_MAX_BYTES: Int = 2048

/** The Pohunek Work link fields of a session as sanitized plain text; a field that is absent or empty after sanitizing is null. */
data class WorkFacts(val provider: String?, val kind: String?, val id: String?, val role: String?)

/** The Work link of a session, ready to render as plain text: `GH PR #12`, and the session's `work.role` when set. */
data class WorkBadge(val link: String, val role: String?)

/**
 * Reads the Work facts from session metadata. The `work.link.*` keys win when any of them is present; the legacy `link.*` keys apply
 * only when none is. Null when the metadata carries none of the fields.
 */
fun workFacts(metadata: Map<String, String>): WorkFacts? {
    val prefix = if (metadata.keys.any { it.startsWith(WORK_PREFIX) }) WORK_PREFIX else LEGACY_PREFIX
    fun field(key: String) = metadata[key]?.let(::sanitizeText)?.ifEmpty { null }
    val facts = WorkFacts(field("${prefix}provider"), field("${prefix}kind"), field("${prefix}id"), field(ROLE_KEY))
    return facts.takeIf { it.provider != null || it.kind != null || it.id != null || it.role != null }
}

/** The badge of [facts]; a session without a link id has none. */
fun workBadge(facts: WorkFacts): WorkBadge? {
    val id = facts.id ?: return null
    val shownId = if (id.all { it in '0'..'9' }) "#$id" else id
    val link = listOfNotNull(facts.provider?.let(::abbreviateProvider), facts.kind?.let(::abbreviateKind), shownId).joinToString(" ")
    return WorkBadge(link, facts.role)
}

private fun abbreviateProvider(provider: String): String = when (provider.asciiLowercase()) {
    "github" -> "GH"
    "gitlab" -> "GL"
    "linear" -> "Linear"
    "jira" -> "Jira"
    else -> provider
}

/** Null for plain issues: the id alone already reads as an issue. */
private fun abbreviateKind(kind: String): String? = when (kind.asciiLowercase()) {
    "issue" -> null
    "pr", "pull_request", "pull-request", "pullrequest" -> "PR"
    "mr", "merge_request", "merge-request", "mergerequest" -> "MR"
    else -> kind
}

/** Lowercases `A-Z` only, as the desktop does; a locale or Unicode rule must not fold other characters into a provider name. */
private fun String.asciiLowercase(): String = map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")

/** The project label of [info], sanitized: the daemon's label, else the project id; null without a `project_id`. */
fun sessionProjectLabel(info: SessionInfo): String? {
    val id = info.projectId ?: return null
    val shownId = sanitizeText(id)
    return info.projectLabel?.let(::sanitizeText)?.ifEmpty { null } ?: shownId
}

/**
 * The URL the session's Work link points to, when it may be opened. The first of `work.link.url` and `link.url` that is present
 * decides: an unsafe value gives no link and the other key is not consulted.
 */
fun openableWorkLink(metadata: Map<String, String>): String? =
    (metadata[WORK_LINK_URL_KEY] ?: metadata[LEGACY_LINK_URL_KEY])?.takeIf(::isOpenableUrl)

/**
 * True for an `http` or `https` URL with a host, made of printable ASCII only and without backslashes (browsers read `\\` as `/`).
 * Session metadata is untrusted, so anything else is not opened.
 */
fun isOpenableUrl(url: String): Boolean {
    if (url.length > OPENABLE_URL_MAX_BYTES || url.any { it !in '!'..'~' || it == '\\' }) return false
    val separator = url.indexOf("://")
    if (separator < 0) return false
    val scheme = url.substring(0, separator)
    val rest = url.substring(separator + "://".length)
    val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it })
    val host = authority.substringAfterLast('@')
    return (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) && host.isNotEmpty() && !host.startsWith(':')
}

/** Clips [text] to at most [maxChars] characters, ending with an ellipsis when it was cut. */
fun clipDisplay(text: String, maxChars: Int): String {
    if (text.codePointCount(0, text.length) <= maxChars) return text
    val keep = (maxChars - 1).coerceAtLeast(0)
    return text.substring(0, text.offsetByCodePoints(0, keep)) + ELLIPSIS
}

/** Clips [text] to at most [maxChars] characters by replacing the middle with an ellipsis, so the start and the end stay readable. */
fun clipMiddle(text: String, maxChars: Int): String {
    val count = text.codePointCount(0, text.length)
    if (count <= maxChars) return text
    val keep = (maxChars - 1).coerceAtLeast(0)
    val tail = keep / 2
    val head = keep - tail
    return text.substring(0, text.offsetByCodePoints(0, head)) + ELLIPSIS + text.substring(text.offsetByCodePoints(0, count - tail))
}
