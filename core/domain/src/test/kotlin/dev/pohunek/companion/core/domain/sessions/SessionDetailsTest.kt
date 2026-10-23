package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class SessionDetailsTest {
    private fun info(
        cwd: String = "/work/a  b",
        worktree: String? = null,
        projectId: String? = null,
        projectLabel: String? = null,
        metadata: Map<String, String>? = null,
    ) = SessionInfo(
        id = "s-1",
        capabilities = SessionCapabilities(resume = false, fork = false),
        agent = "claude",
        agentBase = "claude",
        cwd = cwd,
        pid = 1,
        cols = 80,
        rows = 24,
        state = SessionState.Running,
        stateSource = StateSource.Screen,
        projectId = projectId,
        projectLabel = projectLabel,
        worktreePath = worktree,
        metadata = metadata,
        createdAt = "2026-01-01T09:00:00Z",
        updatedAt = "2026-01-01T10:00:00Z",
    )

    private fun fields(details: SessionDetails) = details.sections.flatMap { it.fields }.associate { it.key to it.value }

    private val route = HostRoute.parse("127.0.0.1:1")

    private fun details(info: SessionInfo, label: String = "127.0.0.1:1", waiting: String? = null) =
        sessionDetails(OwnerSession(info, OwnerState.Working, waiting?.let(OffsetDateTime::parse)), label, route)

    @Test
    fun `empty fields and sections are left out, spaces in paths are kept and an unnamed host has no route line`() {
        val shown = details(info())
        assertEquals(listOf("Identity", "Agent", "Location"), shown.sections.map { it.title })
        assertEquals("/work/a  b", fields(shown)["cwd"])
        assertNull(fields(shown)["host_route"])
        assertEquals("127.0.0.1:1", fields(shown)["host"])
    }

    @Test
    fun `worktree and project id appear only when they differ from the working directory and the label`() {
        val same = fields(details(info(worktree = "/work/a  b", projectId = "p", projectLabel = "p")))
        assertNull(same["worktree"])
        assertNull(same["project_id"])
        val different = fields(details(info(worktree = "/wt", projectId = "p-1", projectLabel = "Shop")))
        assertEquals("/wt", different["worktree"])
        assertEquals("Shop", different["project"])
        assertEquals("p-1", different["project_id"])
    }

    @Test
    fun `a named host shows its route and the waiting time is an RFC 3339 instant`() {
        val shown = fields(details(info(), label = "lab", waiting = "2026-01-01T10:00:00+01:00"))
        assertEquals("lab", shown["host"])
        assertEquals("127.0.0.1:1", shown["host_route"])
        assertEquals("2026-01-01T10:00:00+01:00", shown["waiting_since"])
    }

    @Test
    fun `work fields come from metadata and the link only when it may be opened`() {
        val meta = mapOf("work.link.provider" to "github", "work.link.id" to "12", "work.link.url" to "javascript:alert(1)")
        assertNull(fields(details(info(metadata = meta)))["work_link"])
        val safe = fields(details(info(metadata = meta + ("work.link.url" to "https://example.invalid/pull/12"))))
        assertEquals("https://example.invalid/pull/12", safe["work_link"])
        assertEquals("12", safe["work_id"])
    }

    @Test
    fun `copy all is the sanitized name then one label and value line per field`() {
        val all = details(info(cwd = "/w")).copyAll("al‮pha")
        assertEquals("alpha\nSession id: s-1\nHost: 127.0.0.1:1\nAgent: claude\nState: running\nWorking directory: /w", all)
    }
}
