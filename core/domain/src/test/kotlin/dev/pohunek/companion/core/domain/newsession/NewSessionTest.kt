package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.protocol.AgentRuntime
import dev.pohunek.companion.protocol.ErrorClass
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.ProtocolError
import dev.pohunek.companion.protocol.ProtocolVersionRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class NewSessionTest {
    private val route = HostRoute("127.0.0.1", 7001)

    private fun runtime(agent: String, available: Boolean = true, supported: Boolean? = null, version: String? = null) =
        AgentRuntime(agent, available, version, supported)

    private val choices = NewSessionChoices(
        projects = listOf(ProjectChoice("p-1", "alpha")),
        agents = agentChoices(listOf(runtime("shell"), runtime("claude"), runtime("codex", available = false))),
    )

    private val form = NewSessionForm(host = route, project = "p-1", agent = "claude", name = "  my session ", prompt = "\n do it  ")

    private fun blocker(
        form: NewSessionForm = this.form,
        choices: NewSessionChoices? = this.choices,
        activity: NewSessionActivity = NewSessionActivity(),
    ) = createBlocker(form, choices, activity)

    @Test
    fun `launchable agents come first and every other one names its reason`() {
        val agents = agentChoices(
            listOf(
                runtime("missing", available = false),
                runtime("old", supported = false, version = "0.1.2"),
                runtime("odd", supported = false),
                runtime("ok", supported = true),
            ),
        )
        assertEquals(listOf("ok", "missing", "old", "odd"), agents.map { it.name })
        assertEquals(
            listOf(null, "not available: binary not found on the daemon's PATH", "unsupported version (0.1.2)", "unsupported version"),
            agents.map { it.unlaunchable },
        )
    }

    @Test
    fun `a complete form on a loaded host is not blocked`() {
        assertNull(blocker())
    }

    @Test
    fun `blockers follow the order the owner fixes them in`() {
        val unlaunchable = NewSessionChoices(choices.projects, agentChoices(listOf(runtime("codex", available = false))))
        val all = NewSessionActivity(loading = true, loadError = "down", inFlight = true, unconfirmed = true)
        assertEquals(CreateBlocker.NoHost, blocker(form.copy(host = null), activity = all))
        assertEquals(CreateBlocker.Loading, blocker(activity = all))
        assertEquals(CreateBlocker.LoadFailed("down"), blocker(activity = all.copy(loading = false)))
        assertEquals(CreateBlocker.InFlight, blocker(activity = all.copy(loading = false, loadError = null)))
        assertEquals(CreateBlocker.Unconfirmed, blocker(activity = NewSessionActivity(unconfirmed = true)))
        assertEquals(CreateBlocker.NoProjectsOnDaemon, blocker(form.copy(project = null), choices.copy(projects = emptyList())))
        assertEquals(CreateBlocker.NoProject, blocker(form.copy(project = null, agent = null), unlaunchable))
        assertEquals(CreateBlocker.NoLaunchableAgent, blocker(form, unlaunchable))
        assertEquals(CreateBlocker.NoAgent, blocker(form.copy(agent = null)))
        assertEquals(CreateBlocker.AgentNotLaunchable("codex"), blocker(form.copy(agent = "codex")))
    }

    @Test
    fun `a host whose answer has not arrived blocks as loading`() {
        assertEquals(CreateBlocker.Loading, blocker(choices = null))
    }

    @Test
    fun `a new host keeps the project and agent it offers and falls back otherwise`() {
        assertEquals(form.copy(project = "p-1", agent = "claude"), settleSelection(form, choices))
        val other = NewSessionChoices(listOf(ProjectChoice("p-9", "other")), agentChoices(listOf(runtime("shell"), runtime("codex"))))
        val settled = settleSelection(form, other)
        assertNull(settled.project)
        assertEquals("shell", settled.agent)
        assertNull(settleSelection(form, NewSessionChoices(emptyList(), emptyList())).agent)
    }

    @Test
    fun `the request trims name and prompt and omits blank ones`() {
        val params = sessionNewParams(form, 80, 24)
        assertEquals("my session", params?.name)
        assertEquals("do it", params?.input)
        assertEquals("p-1", params?.project)
        val blank = sessionNewParams(form.copy(name = " \t", prompt = "\n\n"), 80, 24)
        assertNull(blank?.name)
        assertNull(blank?.input)
        assertNull(sessionNewParams(form.copy(project = null), 80, 24))
    }

    @Test
    fun `a daemon error is a refusal with its code and recovery hint`() {
        val error = ProtocolError(ErrorClass.Runtime, "agent_binary_missing", "claude is missing\u0007", "install it")
        val outcome = classifyCreateFailure(ProtocolClientException.Daemon(error))
        assertEquals(CreateOutcome.Refused("agent_binary_missing", "claude is missing (install it)"), outcome)
    }

    @Test
    fun `only a failure to open the connection proves the request did not leave`() {
        val refused = classifyCreateFailure(ProtocolClientException.ConnectionFailed(route, IOException("refused")))
        assertEquals(CreateOutcome.NotSent("cannot connect to $route: refused"), refused)
        assert(classifyCreateFailure(ProtocolClientException.ConnectTimeout(route, 10)) is CreateOutcome.NotSent)
        assert(classifyCreateFailure(ProtocolClientException.InvalidRoute("x", "bad")) is CreateOutcome.NotSent)
    }

    @Test
    fun `a failure after the request left is unconfirmed`() {
        val failures = listOf(
            ProtocolClientException.RequestTimeout(120_000),
            ProtocolClientException.ConnectionLost("reset"),
            ProtocolClientException.MalformedResponse("bad"),
            ProtocolClientException.Framing("bad"),
            ProtocolClientException.ResponseIdMismatch("a", "b"),
            ProtocolClientException.VersionMismatch(ProtocolVersionRange(4, 4), 3, null),
        )
        failures.forEach { assertEquals(it.message, CreateOutcome.Unconfirmed, classifyCreateFailure(it)) }
    }
}
