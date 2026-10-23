package dev.pohunek.companion.protocol

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSessionWireTest {
    private fun capturedLine(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/captured/$name.jsonl") ?: error("missing captured line $name")).use {
            it.readBytes().toString(Charsets.UTF_8).trim()
        }

    private fun decode(name: String) = ResponseDecoder().decode(Fixtures.captured(name).trim(), "req-1")

    @Test
    fun `requests are the lines the isolated daemon answered`() {
        assertEquals(capturedLine("project-list-request"), Requests.projectList("req-1").toLine())
        assertEquals(capturedLine("host-inspect-request"), Requests.hostInspect("req-1").toLine())
        val params = SessionNewParams("claude", cols = 80, rows = 24, project = "p-617cf33c", name = "fixture-three", input = "echo hi")
        assertEquals(capturedLine("session-new-request"), Requests.sessionNew("req-1", params).toLine())
        assertEquals(capturedLine("project-actions-request"), Requests.projectActions("req-1", "p-617cf33c").toLine())
        assertEquals(capturedLine("project-action-request"), Requests.projectAction("req-1", "p-617cf33c", "fix-bug").toLine())
        val branched = params.copy(name = "fixture-branched", branch = "fixture/branch", baseBranch = "master")
        assertEquals(capturedLine("session-new-branch-request"), Requests.sessionNew("req-1", branched).toLine())
    }

    @Test
    fun `project actions decode every provider and keep an unknown one as text`() {
        val actions = decode("project-actions-response").decodeResult(ProjectActionsResult.serializer()).actions
        assertEquals(listOf("fix-bug", "from-linear", "ghost-agent", "with-vars"), actions.map { it.name })
        assertEquals(listOf("none", "linear_issue", "none", "none"), actions.map { it.provider })
    }

    @Test
    fun `a project action decodes the recipe with its branches`() {
        val action = decode("project-action-response").decodeResult(ProjectActionResult.serializer())
        assertEquals("claude", action.agent)
        assertEquals("template/fix-bug", action.branch)
        assertEquals("master", action.baseBranch)
        assertEquals("Investigate the failing test and fix it.\n", action.promptContent)
    }

    @Test
    fun `an unknown action is a runtime error and a base branch alone is a bad request`() {
        val missing = assertThrows(ProtocolClientException.Daemon::class.java) { decode("project-action-not-found-error") }
        assertEquals("action_not_found", missing.error.code)
        val refused = assertThrows(ProtocolClientException.Daemon::class.java) { decode("session-new-base-branch-error") }
        assertEquals("bad_request", refused.error.code)
    }

    @Test
    fun `a session created in a worktree reports its branch`() {
        val session = decode("session-new-branch-response").decodeSessionNewResult().session
        assertEquals("fixture/branch", session.branch)
    }

    @Test
    fun `a request without name and prompt omits both`() {
        val line = Requests.sessionNew("req-1", SessionNewParams("shell", cols = 100, rows = 30, project = "p-1")).toLine()
        assertTrue(line, """"params":{"agent":"shell","cols":100,"rows":30,"project":"p-1"}""" in line)
    }

    @Test
    fun `project list decodes ids and labels`() {
        val projects = decode("project-list-response").decodeResult(ListSerializer(ProjectInfo.serializer()))
        assertEquals(listOf("neovim", "pohunek", "pohunek-companion", "ripgrep", "tokio", "zed"), projects.map { it.label })
        assertEquals("p-617cf33c", projects[1].id)
    }

    @Test
    fun `host inspect decodes the runtimes and their availability`() {
        val capabilities = decode("host-inspect-response").decodeResult(HostCapabilities.serializer())
        assertEquals(listOf("shell", "claude", "codex"), capabilities.runtimes.map { it.agent })
        assertTrue(capabilities.runtimes.all { it.available && it.supported == null && it.version == null })
        assertTrue(capabilities.gitAvailable && capabilities.worktreeSupported)
    }

    @Test
    fun `session new result is the session plus applied_input`() {
        val result = decode("session-new-response").decodeSessionNewResult()
        assertEquals("fixture-three", result.session.name)
        assertEquals("claude", result.session.agent)
        assertEquals(SessionState.Running, result.session.state)
        assertEquals("p-617cf33c", result.session.projectId)
        assertEquals(true, result.appliedInput)
    }

    @Test
    fun `host inspect without worktree flags is malformed`() {
        val withoutGit = JsonObject((decode("host-inspect-response") as JsonObject) - "git_available")
        assertThrows(ProtocolClientException.MalformedResponse::class.java) { withoutGit.decodeResult(HostCapabilities.serializer()) }
    }

    @Test
    fun `an absent applied_input is null and a mistyped one is malformed`() {
        val absent = decode("session-new-response").let { ok ->
            JsonObject((ok as JsonObject) - "applied_input")
        }
        assertNull(absent.decodeSessionNewResult().appliedInput)
        val mistyped = JsonObject(
            (decode("session-new-response") as JsonObject) + ("applied_input" to JsonPrimitive("yes")),
        )
        assertThrows(ProtocolClientException.MalformedResponse::class.java) { mistyped.decodeSessionNewResult() }
        val quoted = JsonObject((decode("session-new-response") as JsonObject) + ("applied_input" to JsonPrimitive("true")))
        assertThrows(ProtocolClientException.MalformedResponse::class.java) { quoted.decodeSessionNewResult() }
    }

    @Test
    fun `an unknown project is a runtime error with the daemon code`() {
        val failure = assertThrows(ProtocolClientException.Daemon::class.java) { decode("session-new-project-not-found-error") }
        assertEquals("project_not_found", failure.error.code)
        assertEquals(ErrorClass.Runtime, failure.error.errorClass)
    }
}
