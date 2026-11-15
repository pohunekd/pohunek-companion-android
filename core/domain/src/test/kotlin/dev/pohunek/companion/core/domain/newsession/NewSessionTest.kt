package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.protocol.ActionSummary
import dev.pohunek.companion.protocol.AgentOption
import dev.pohunek.companion.protocol.AgentOptionChoice
import dev.pohunek.companion.protocol.AgentOptionUnavailable
import dev.pohunek.companion.protocol.AgentRuntime
import dev.pohunek.companion.protocol.ErrorClass
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProfileAudience
import dev.pohunek.companion.protocol.ProjectActionResult
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.ProtocolError
import dev.pohunek.companion.protocol.ProtocolVersionRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NewSessionTest {
    private val route = HostRoute("127.0.0.1", 7001)

    private fun runtime(agent: String, available: Boolean = true, supported: Boolean? = null, version: String? = null) =
        AgentRuntime(agent, available, version, supported)

    private val choices = NewSessionChoices(
        projects = listOf(ProjectChoice("p-1", "alpha")),
        agents = agentChoices(listOf(runtime("shell"), runtime("claude"), runtime("codex", available = false))),
        gitAvailable = true,
        worktreeSupported = true,
    )

    private val form = NewSessionForm(host = route, project = "p-1", agent = "claude", name = "  my session ", prompt = "\n do it  ")

    private fun blocker(
        form: NewSessionForm = this.form,
        choices: NewSessionChoices? = this.choices,
        activity: NewSessionActivity = NewSessionActivity(),
        requested: String? = null,
    ) = createBlocker(form, choices, activity, requested)

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
    fun `the picker lists owner profiles and bare runtimes by label and keeps the rest behind show all`() {
        val agents = agentChoices(
            listOf(
                AgentRuntime("bare", true),
                AgentRuntime("reviewer", true, displayName = " Code\n reviewer ", audience = ProfileAudience.Owner),
                AgentRuntime("spawned", true, audience = ProfileAudience.Agent),
                AgentRuntime("secret", true, displayName = "Secret", audience = ProfileAudience.Hidden),
                AgentRuntime("future", true, audience = ProfileAudience.Unknown),
            ),
        )
        val all = NewSessionChoices(emptyList(), agents, gitAvailable = true, worktreeSupported = true)
        assertEquals(listOf("bare", "Code reviewer"), all.visibleAgents(showAll = false, selected = null).map { it.label })
        assertEquals(listOf("bare", "reviewer", "spawned", "secret", "future"), all.visibleAgents(showAll = true, selected = null).map { it.name })
        assertEquals(listOf("bare", "reviewer", "spawned"), all.visibleAgents(showAll = false, selected = "spawned").map { it.name })
    }

    @Test
    fun `settling prefers an agent the picker lists and falls back to a hidden one`() {
        val form = NewSessionForm(host = route)
        val mixed =
            NewSessionChoices(emptyList(), agentChoices(listOf(AgentRuntime("spawned", true, audience = ProfileAudience.Agent), runtime("claude"))), true, true)
        assertEquals("claude", settleSelection(form, mixed).agent)
        val onlyHidden = NewSessionChoices(emptyList(), agentChoices(listOf(AgentRuntime("spawned", true, audience = ProfileAudience.Agent))), true, true)
        assertEquals("spawned", settleSelection(form, onlyHidden).agent)
    }

    @Test
    fun `a complete form on a loaded host is not blocked`() {
        assertNull(blocker())
    }

    @Test
    fun `blockers follow the order the owner fixes them in`() {
        val unlaunchable = choices.copy(agents = agentChoices(listOf(runtime("codex", available = false))))
        val all = NewSessionActivity(loading = true, loadError = "down", inFlight = true, unconfirmed = true)
        assertEquals(CreateBlocker.NoHost, blocker(form.copy(host = null), activity = all))
        assertEquals(CreateBlocker.Loading, blocker(activity = all))
        assertEquals(CreateBlocker.LoadFailed("down"), blocker(activity = all.copy(loading = false)))
        assertEquals(CreateBlocker.InFlight, blocker(activity = all.copy(loading = false, loadError = null)))
        assertEquals(CreateBlocker.Unconfirmed, blocker(activity = NewSessionActivity(unconfirmed = true)))
        assertEquals(CreateBlocker.RequestedProjectMissing("p-9"), blocker(form.copy(project = null), choices.copy(projects = emptyList()), requested = "p-9"))
        assertEquals(CreateBlocker.NoProjectsOnDaemon, blocker(form.copy(project = null), choices.copy(projects = emptyList())))
        assertEquals(CreateBlocker.NoProject, blocker(form.copy(project = null, agent = null), unlaunchable))
        assertEquals(CreateBlocker.NoLaunchableAgent, blocker(form, unlaunchable))
        assertEquals(CreateBlocker.NoAgent, blocker(form.copy(agent = null)))
        assertEquals(CreateBlocker.AgentNotLaunchable("codex"), blocker(form.copy(agent = "codex")))
    }

    @Test
    fun `picking a project supersedes a requested one the host lacks`() {
        assertNull(blocker(requested = "p-9"))
        assertEquals(CreateBlocker.RequestedProjectMissing("p-9"), blocker(form.copy(project = null), requested = "p-9"))
        assertEquals(CreateBlocker.NoProject, blocker(form.copy(project = null), requested = "p-1"))
    }

    @Test
    fun `a host whose answer has not arrived blocks as loading`() {
        assertEquals(CreateBlocker.Loading, blocker(choices = null))
    }

    @Test
    fun `a new host keeps the project and agent it offers and falls back otherwise`() {
        assertEquals(form.copy(project = "p-1", agent = "claude"), settleSelection(form, choices))
        val other = choices.copy(projects = listOf(ProjectChoice("p-9", "other")), agents = agentChoices(listOf(runtime("shell"), runtime("codex"))))
        val settled = settleSelection(form, other)
        assertNull(settled.project)
        assertEquals("shell", settled.agent)
        assertNull(settleSelection(form, choices.copy(projects = emptyList(), agents = emptyList())).agent)
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
    fun `a group name becomes the companion group metadata and no name sends none`() {
        assertEquals(mapOf("companion.group" to "Later"), sessionNewParams(form, 80, 24, "Later")?.metadata)
        assertNull(sessionNewParams(form, 80, 24)?.metadata)
        assertNull(sessionNewParams(form, 80, 24, null)?.metadata)
    }

    @Test
    fun `a daemon error is a refusal with its code and recovery hint`() {
        val error = ProtocolError(ErrorClass.Runtime, "agent_binary_missing", "claude is missing\u0007", "install it")
        val outcome = classifyCreateFailure(ProtocolClientException.Daemon(error))
        assertEquals(CreateOutcome.Refused("agent_binary_missing", "claude is missing (install it)"), outcome)
    }

    @Test
    fun `an error that may follow the recording of the session is not a proven refusal`() {
        val error = ProtocolError(ErrorClass.Runtime, "session_runtime_reconnecting", "the runtime is reconnecting", null)
        val outcome = classifyCreateFailure(ProtocolClientException.Daemon(error))
        assertEquals(CreateOutcome.RefusedMaybeCreated("session_runtime_reconnecting", "the runtime is reconnecting"), outcome)
        val versionError = ProtocolError(ErrorClass.Daemon, "version_mismatch", "no common protocol", null)
        val refused = classifyCreateFailure(ProtocolClientException.VersionMismatch(ProtocolVersionRange(4, 4), 5, versionError))
        assertEquals(CreateOutcome.Refused("version_mismatch", "no common protocol"), refused)
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

    private fun action(prompt: String, branch: String? = "feat/x", baseBranch: String? = "main", agent: String = "claude") =
        ProjectActionResult(agent, prompt, branch, baseBranch)

    private fun recipe(prompt: String = "do it", branch: String? = "feat/x", baseBranch: String? = "main", agent: String = "claude") =
        (recipeFromAction("t", action(prompt, branch, baseBranch, agent)) as TemplateStep.Applied).value

    @Test
    fun `only provider-less actions are templates`() {
        val actions = listOf(
            ActionSummary("fix", "none"),
            ActionSummary("issue", "linear_issue"),
            ActionSummary("review", "github_pr"),
            ActionSummary("later", "a_kind_from_a_newer_daemon"),
            ActionSummary("docs", "none"),
        )
        assertEquals(listOf("fix", "docs"), templateNames(actions))
    }

    @Test
    fun `a prompt with variables is refused and a plain dollar sign is not`() {
        assertEquals(TemplateStep.Failed(TemplateError.Variables("fix")), recipeFromAction("fix", action("work on \${issue}")))
        assertTrue(recipeFromAction("fix", action("costs $5")) is TemplateStep.Applied)
        val trimmed = recipe(branch = "  ", baseBranch = " main ")
        assertNull(trimmed.branch)
        assertEquals("main", trimmed.baseBranch)
    }

    @Test
    fun `a template fills agent and prompt and a Blank form keeps its own branch texts`() {
        val applied = applyRecipe(form, choices, "fix", recipe(prompt = "template text", agent = "shell")) as TemplateStep.Applied
        assertEquals("shell", applied.value.agent)
        assertEquals("template text", applied.value.prompt)
        assertEquals("fix", applied.value.template)
        val params = sessionNewParams(applied.value.copy(branch = "ignored", baseBranch = "ignored"), 80, 24)
        assertEquals("feat/x" to "main", params?.branch to params?.baseBranch)
        val blank = sessionNewParams(form.copy(branch = "  topic \n", baseBranch = "release"), 80, 24)
        assertEquals("topic" to "release", blank?.branch to blank?.baseBranch)
        val none = sessionNewParams(form.copy(branch = " ", baseBranch = ""), 80, 24)
        assertEquals(null to null, none?.branch to none?.baseBranch)
    }

    @Test
    fun `a template whose agent the host cannot launch is not applied`() {
        assertEquals(
            TemplateStep.Failed(TemplateError.AgentNotLaunchable("codex")),
            applyRecipe(form, choices, "fix", recipe(agent = "codex")),
        )
        assertEquals(
            TemplateStep.Failed(TemplateError.AgentNotLaunchable("absent")),
            applyRecipe(form, choices, "fix", recipe(agent = "absent")),
        )
    }

    @Test
    fun `git and worktree support matter only when a branch will be sent`() {
        val noGit = choices.copy(gitAvailable = false, worktreeSupported = false)
        val noWorktree = choices.copy(worktreeSupported = false)
        val branched = form.copy(branch = " topic ")
        val both = branched.copy(baseBranch = "main")
        assertNull(blocker(form, noGit))
        for (f in listOf(branched, both)) {
            assertNull(blocker(f))
            assertEquals(LaunchBlocker.GitUnavailable, blocker(f, noGit))
            assertEquals(LaunchBlocker.WorktreeUnsupported, blocker(f, noWorktree))
        }
        assertNull(blocker(form.copy(branch = "  "), noGit))
        val templated = form.copy(template = "fix", recipe = recipe(), branch = "", baseBranch = "")
        assertEquals(LaunchBlocker.GitUnavailable, blocker(templated, noGit))
        val withoutBranches = form.copy(template = "fix", recipe = recipe(branch = null, baseBranch = null), branch = "typed")
        assertNull(blocker(withoutBranches, noGit))
    }

    @Test
    fun `a base branch without a branch is blocked on any host`() {
        val based = form.copy(baseBranch = "main")
        assertEquals(LaunchBlocker.BaseBranchWithoutBranch, blocker(based))
        assertEquals(LaunchBlocker.BaseBranchWithoutBranch, blocker(based, choices.copy(gitAvailable = false)))
        val templated = form.copy(template = "fix", recipe = recipe(branch = null, baseBranch = "main"))
        assertEquals(LaunchBlocker.BaseBranchWithoutBranch, blocker(templated))
    }

    @Test
    fun `a template still loading blocks after the agent checks and before the host checks`() {
        val loading = form.copy(template = "fix", templateLoading = true)
        assertEquals(LaunchBlocker.TemplateLoading, blocker(loading))
        assertEquals(CreateBlocker.AgentNotLaunchable("codex"), blocker(loading.copy(agent = "codex")))
        val noGit = choices.copy(gitAvailable = false)
        assertEquals(LaunchBlocker.TemplateLoading, blocker(loading.copy(branch = "b"), noGit))
    }

    private val withOptions = AgentRuntime(
        "work",
        available = true,
        options = listOf(
            AgentOption(
                "mode",
                "Mode",
                "calm",
                listOf(
                    AgentOptionChoice("calm", "Calm", true),
                    AgentOptionChoice("bold", "Bold", true),
                    AgentOptionChoice("next", "Next", false, AgentOptionUnavailable.RuntimeTooOld, "2.2.0"),
                ),
            ),
            AgentOption("depth", "Depth", "shallow", listOf(AgentOptionChoice("shallow", "Shallow", true), AgentOptionChoice("deep", "Deep", true))),
        ),
    )

    private fun optionChoices(supported: Boolean = true) = choices.copy(agents = agentChoices(listOf(withOptions, runtime("claude")), supported))

    private val optionForm = form.copy(agent = "work")

    @Test
    fun `the defaults are preselected and a host without support shows no options`() {
        val supported = optionChoices()
        assertEquals(listOf("calm", "shallow"), supported.optionsOf("work").map { effectiveChoice(optionForm, it)?.id })
        assertTrue(optionChoices(supported = false).optionsOf("work").isEmpty())
        assertTrue(supported.optionsOf("claude").isEmpty())
    }

    @Test
    fun `an unavailable choice cannot be selected and states its reason`() {
        val supported = optionChoices()
        assertEquals(optionForm, selectAgentOption(optionForm, supported, "mode", "next"))
        assertEquals("needs runtime 2.2.0 or newer", supported.optionsOf("work")[0].choice("next")?.unavailable)
        assertEquals(optionForm, selectAgentOption(optionForm, supported, "mode", "missing"))
        assertEquals(optionForm, selectAgentOption(optionForm, supported, "nope", "calm"))
    }

    @Test
    fun `only the picks that differ from the defaults are sent`() {
        val supported = optionChoices()
        assertNull(sessionNewParams(optionForm, 80, 24, choices = supported)?.agentOptions)
        val picked = selectAgentOption(selectAgentOption(optionForm, supported, "mode", "bold"), supported, "depth", "shallow")
        assertEquals(mapOf("mode" to "bold"), sessionNewParams(picked, 80, 24, choices = supported)?.agentOptions)
        val stale = picked.copy(agentOptions = mapOf("mode" to "gone", "removed" to "x", "depth" to "deep"))
        assertEquals(mapOf("depth" to "deep"), sessionNewParams(stale, 80, 24, choices = supported)?.agentOptions)
        assertNull(sessionNewParams(picked, 80, 24, choices = optionChoices(supported = false))?.agentOptions)
    }

    @Test
    fun `picks do not survive a change of agent and an unavailable default blocks create`() {
        val supported = optionChoices()
        val picked = selectAgentOption(optionForm, supported, "mode", "bold")
        assertTrue(settleSelection(picked.copy(agent = "gone"), supported).agentOptions.isEmpty())
        assertEquals(picked.agentOptions, settleSelection(picked, supported).agentOptions)
        val badDefault = supported.copy(
            agents = agentChoices(
                listOf(withOptions.copy(options = listOf(withOptions.options[0].copy(default = "next")))),
                optionsSupported = true,
            ),
        )
        assertEquals(CreateBlocker.OptionChoiceUnavailable("Mode", "needs runtime 2.2.0 or newer"), blocker(optionForm, badDefault))
        assertNull(blocker(selectAgentOption(optionForm, badDefault, "mode", "bold"), badDefault))
    }

    @Test
    fun `an option refusal is definite and names what to do`() {
        for (code in listOf("agent_option_unknown", "agent_option_choice_unavailable", "agent_options_locked")) {
            val outcome = classifyCreateFailure(ProtocolClientException.Daemon(ProtocolError(ErrorClass.Runtime, code, "refused", null)))
            assertTrue(code, outcome is CreateOutcome.Refused)
        }
    }

    @Test
    fun `a template that names another agent drops the picks`() {
        val supported = optionChoices()
        val picked = selectAgentOption(optionForm, supported, "mode", "bold")
        val other = Recipe(agent = "claude", branch = null, baseBranch = null, prompt = "p")
        assertTrue((applyRecipe(picked, supported, "t", other) as TemplateStep.Applied).value.agentOptions.isEmpty())
        val same = other.copy(agent = "work")
        assertEquals(picked.agentOptions, (applyRecipe(picked, supported, "t", same) as TemplateStep.Applied).value.agentOptions)
    }
}
