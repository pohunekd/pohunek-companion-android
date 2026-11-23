package dev.pohunek.companion.core.data.demo

import dev.pohunek.companion.protocol.AgentActivity
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource

/**
 * The synthetic sessions of the demo mode. Names, projects, agents, states and metadata are the showcase dataset of
 * `scripts/ui-agent start --showcase`, which is the source of truth for what the screenshots show.
 *
 * Rows are `name|project|agent|state|meta` in script order. The session id is `demo-` plus the name with every `.` replaced by `-`, so
 * the scripted session of the demo terminal is [SCRIPTED_SESSION_ID].
 */
object DemoSessions {
    const val SCRIPTED_SESSION_ID = "demo-bump-tokio-1-48"

    /** The sessions the demo shows on [route], or none for a route that is not a demo host. */
    fun forHost(route: HostRoute): List<SessionInfo> = when (route) {
        DemoHosts.primary.route -> primaryRows.mapIndexed { index, row -> session(index, row) }
        DemoHosts.second.route -> secondRows.mapIndexed { index, row -> session(index, row) }
        else -> emptyList()
    }

    fun find(route: HostRoute, sessionId: String): SessionInfo? = forHost(route).firstOrNull { it.id == sessionId }

    private val primaryRows = listOf(
        "review-pr-1412|ripgrep|claude|blocked|work.link.provider=github work.link.kind=pr work.link.id=1412 " +
            "work.link.url=https://github.com/BurntSushi/ripgrep/pull/1412 work.role=reviewer",
        "fix-flaky-pty-test|pohunek|codex|blocked|work.link.provider=github work.link.kind=issue work.link.id=388 " +
            "work.link.url=https://github.com/zajca/pohunek/issues/388 work.role=implementer",
        "bump-tokio-1.48|tokio|claude|demo|work.link.provider=github work.link.kind=pr work.link.id=7210 " +
            "work.link.url=https://github.com/tokio-rs/tokio/pull/7210 work.role=implementer",
        "docs-install-guide|pohunek-companion|claude|working|work.link.provider=github work.link.kind=issue work.link.id=200 " +
            "work.link.url=https://github.com/pohunekd/pohunek-companion/issues/200 work.role=implementer",
        "lsp-semantic-tokens|neovim|codex|working|work.link.provider=github work.link.kind=pr work.link.id=31877 " +
            "work.link.url=https://github.com/neovim/neovim/pull/31877 work.role=implementer",
        "review-pr-1655|zed|codex|idle|work.link.provider=github work.link.kind=pr work.link.id=1655 " +
            "work.link.url=https://github.com/zed-industries/zed/pull/1655 work.role=reviewer",
        "triage-open-issues|pohunek|claude|idle|",
        "tray-badge-polish|pohunek-companion|codex|idle|work.link.provider=github work.link.kind=issue work.link.id=164 " +
            "work.link.url=https://github.com/pohunekd/pohunek-companion/issues/164 work.role=implementer",
        "rustfmt-edition-2024|ripgrep|codex|idle|work.link.provider=github work.link.kind=pr work.link.id=2951 " +
            "work.link.url=https://github.com/BurntSushi/ripgrep/pull/2951 work.role=implementer",
        "multibuffer-scroll-fix|zed|claude|idle|work.link.provider=github work.link.kind=issue work.link.id=19402 " +
            "work.link.url=https://github.com/zed-industries/zed/issues/19402 work.role=implementer",
        "release-notes-0.1.2|pohunek-companion|claude|stopped|work.link.provider=github work.link.kind=issue " +
            "work.link.id=181 work.link.url=https://github.com/pohunekd/pohunek-companion/issues/181 work.role=implementer",
        "ci-cache-warmup|tokio|codex|stopped|",
        "scratch-shell|pohunek-companion|shell|shell|",
    )

    private val secondRows = listOf(
        "migrate-config-schema|pohunek|claude|working|work.link.provider=github work.link.kind=issue work.link.id=401 " +
            "work.link.url=https://github.com/zajca/pohunek/issues/401 work.role=implementer companion.group=Dependencies",
        "nightly-bench-sweep|tokio|codex|working|companion.group=Dependencies",
        "audit-unsafe-blocks|ripgrep|claude|idle|companion.group=Dependencies",
        "profile-startup-time|neovim|codex|idle|work.link.provider=github work.link.kind=pr work.link.id=31902 " +
            "work.link.url=https://github.com/neovim/neovim/pull/31902 work.role=reviewer companion.group=Dependencies",
        "retire-old-fixtures|zed|claude|stopped|companion.group=Dependencies",
    )

    /** Builds one row; [index] is its position in its host's table. */
    private fun session(index: Int, row: String): SessionInfo {
        val columns = row.split('|', limit = COLUMN_COUNT)
        val name = columns[0]
        val project = columns[1]
        val agent = columns[2]
        val state = columns[STATE_COLUMN]
        val meta = columns[META_COLUMN]
        val sessionState: SessionState
        val activity: AgentActivity?
        val stateSource: StateSource
        var exitCode: Int? = null
        when (state) {
            "working", "demo" -> {
                sessionState = SessionState.Running
                activity = AgentActivity.Working
                stateSource = StateSource.Screen
            }

            "blocked" -> {
                sessionState = SessionState.Running
                activity = AgentActivity.Blocked
                stateSource = StateSource.Screen
            }

            "idle" -> {
                sessionState = SessionState.Running
                activity = AgentActivity.Idle
                stateSource = StateSource.Screen
            }

            "stopped" -> {
                sessionState = SessionState.Stopped
                activity = null
                stateSource = StateSource.Process
                exitCode = 0
            }

            else -> {
                sessionState = SessionState.Running
                activity = null
                stateSource = StateSource.Process
            }
        }
        return SessionInfo(
            id = "demo-" + name.replace('.', '-'),
            capabilities = SessionCapabilities(resume = false, fork = false),
            name = name,
            agent = agent,
            agentBase = "shell",
            cwd = "/home/demo/$project",
            pid = (1000 + index).toLong(),
            cols = 80,
            rows = 24,
            state = sessionState,
            stateSource = stateSource,
            activity = activity,
            projectId = project,
            projectLabel = project,
            metadata = metadata(meta),
            createdAt = CREATED_AT,
            updatedAt = "2026-11-01T09:${index.toString().padStart(2, '0')}:00Z",
            exitCode = exitCode,
        )
    }

    /** Splits the space-separated `key=value` pairs of the meta column; the value keeps any later `=` and may hold a URL. */
    private fun metadata(meta: String): Map<String, String>? = meta.split(' ')
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val separator = pair.indexOf('=')
            pair.substring(0, separator) to pair.substring(separator + 1)
        }
        .ifEmpty { null }

    private const val CREATED_AT = "2026-11-01T09:00:00Z"

    /** A row has `name|project|agent|state|meta`; [STATE_COLUMN] and [META_COLUMN] name the columns that are not 0, 1 or 2. */
    private const val COLUMN_COUNT = 5
    private const val STATE_COLUMN = 3
    private const val META_COLUMN = 4
}
