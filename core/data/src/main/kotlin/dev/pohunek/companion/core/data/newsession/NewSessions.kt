package dev.pohunek.companion.core.data.newsession

import dev.pohunek.companion.core.data.commands.display
import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.data.log.logCode
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.core.domain.newsession.CreateOutcome
import dev.pohunek.companion.core.domain.newsession.NewSessionChoices
import dev.pohunek.companion.core.domain.newsession.ProjectChoice
import dev.pohunek.companion.core.domain.newsession.Recipe
import dev.pohunek.companion.core.domain.newsession.TemplateError
import dev.pohunek.companion.core.domain.newsession.TemplateStep
import dev.pohunek.companion.core.domain.newsession.agentChoices
import dev.pohunek.companion.core.domain.newsession.classifyCreateFailure
import dev.pohunek.companion.core.domain.newsession.createdOutcome
import dev.pohunek.companion.core.domain.newsession.projectChoices
import dev.pohunek.companion.core.domain.newsession.recipeFromAction
import dev.pohunek.companion.core.domain.newsession.templateNames
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.LineFramer
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.Requests
import dev.pohunek.companion.protocol.SessionNewParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What reading the choices of one host has produced so far. */
sealed interface ChoicesUpdate {
    /** `project.list` answered; the agents are still being read. */
    data class Projects(val projects: List<ProjectChoice>) : ChoicesUpdate

    data class Loaded(val choices: NewSessionChoices) : ChoicesUpdate

    /** [detail] is the daemon's text or the transport failure, cleaned for display. */
    data class Failed(val detail: String) : ChoicesUpdate
}

/**
 * Reads what a host offers for a new session and starts one. Every call has a connection of its own, so neither waits on the live
 * loop of the host. A session is created by exactly one `session.new` that is never repeated.
 */
class NewSessions(
    private val client: () -> ClientConfig,
    private val createClient: () -> ClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: AppLog,
) {
    /** The projects as soon as `project.list` answers, then the projects with the agents, or the failure; the flow ends after either. */
    fun choices(route: HostRoute): Flow<ChoicesUpdate> = flow {
        val fields = listOf(hostKeyField(route))
        try {
            PohunekConnection.open(route, client(), ioDispatcher).use { connection ->
                val projects = projectChoices(connection.listProjects())
                emit(ChoicesUpdate.Projects(projects))
                val capabilities = connection.inspectHost()
                val agents = agentChoices(capabilities.runtimes)
                emit(ChoicesUpdate.Loaded(NewSessionChoices(projects, agents, capabilities.gitAvailable, capabilities.worktreeSupported)))
                val counts = listOf(LogField.number("projects", projects.size.toLong()), LogField.number("agents", agents.size.toLong()))
                log.info("new_session.choices_loaded", fields + counts)
            }
        } catch (e: ProtocolClientException) {
            log.warn("new_session.choices_failed", fields + LogField.errorCode(e.logCode()))
            emit(ChoicesUpdate.Failed(e.display()))
        }
    }

    /** The names of [project]'s static templates on [route], or why they could not be read. */
    suspend fun templates(route: HostRoute, project: String): TemplateStep<List<String>> {
        val fields = listOf(hostKeyField(route), LogField.method("project.actions"))
        return try {
            val names = PohunekConnection.open(route, client(), ioDispatcher).use { templateNames(it.listProjectActions(project)) }
            log.info("new_session.templates_loaded", fields + LogField.number("templates", names.size.toLong()))
            TemplateStep.Applied(names)
        } catch (e: ProtocolClientException) {
            log.warn("new_session.templates_failed", fields + LogField.errorCode(e.logCode()))
            TemplateStep.Failed(TemplateError.Daemon(e.display()))
        }
    }

    /** The recipe of the template [name] of [project] on [route]; a prompt with variables is a [TemplateError.Variables]. */
    suspend fun recipe(route: HostRoute, project: String, name: String): TemplateStep<Recipe> {
        val fields = listOf(hostKeyField(route), LogField.method("project.action"))
        return try {
            val result = PohunekConnection.open(route, client(), ioDispatcher).use { it.projectAction(project, name) }
            recipeFromAction(name, result).also { step ->
                if (step is TemplateStep.Failed) log.warn("new_session.template_refused", fields) else log.info("new_session.template_loaded", fields)
            }
        } catch (e: ProtocolClientException) {
            log.warn("new_session.template_failed", fields + LogField.errorCode(e.logCode()))
            TemplateStep.Failed(TemplateError.Daemon(e.display()))
        }
    }

    /**
     * Sends one `session.new` to [route] and classifies how it ended. A request too large for a control line is refused before a
     * connection opens; once the request left, no failure is retried, because a retry could create a second session.
     */
    suspend fun create(route: HostRoute, params: SessionNewParams): CreateOutcome {
        val fields = listOf(hostKeyField(route), LogField.method("session.new"))
        if (!fitsOneLine(params)) {
            log.warn("new_session.create_refused", fields + LogField.errorCode("request_too_large"))
            return CreateOutcome.NotSent(REQUEST_TOO_LARGE)
        }
        log.info("new_session.create", fields)
        return try {
            val result = PohunekConnection.open(route, createClient(), ioDispatcher).use { it.newSession(params) }
            createdOutcome(result, sentPrompt = params.input != null).also { outcome ->
                val created = fields + LogField.sessionId(result.session.id)
                if (outcome.promptDelivered) log.info("new_session.created", created) else log.warn("new_session.prompt_unconfirmed", created)
            }
        } catch (e: ProtocolClientException) {
            log.error("new_session.create_failed", fields + LogField.errorCode(e.logCode()))
            classifyCreateFailure(e)
        }
    }

    private fun fitsOneLine(params: SessionNewParams): Boolean = try {
        LineFramer.encode(Requests.sessionNew(SIZE_PROBE_ID, params).toLine())
        true
    } catch (_: ProtocolClientException.Framing) {
        false
    }

    private companion object {
        const val SIZE_PROBE_ID = "android-size-probe"
        const val REQUEST_TOO_LARGE = "The prompt is too long to send."
    }
}
