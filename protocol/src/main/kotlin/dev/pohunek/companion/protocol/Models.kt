package dev.pohunek.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionState {
    @SerialName("starting")
    Starting,

    @SerialName("running")
    Running,

    @SerialName("stopped")
    Stopped,

    @SerialName("done")
    Done,

    @SerialName("failed")
    Failed,
}

@Serializable
enum class StateSource {
    @SerialName("osc_title")
    OscTitle,

    @SerialName("osc_progress")
    OscProgress,

    @SerialName("screen")
    Screen,

    @SerialName("process")
    Process,

    @SerialName("report")
    Report,
}

@Serializable
enum class AgentActivity {
    @SerialName("working")
    Working,

    @SerialName("blocked")
    Blocked,

    @SerialName("idle")
    Idle,
}

@Serializable
enum class CwdSource {
    @SerialName("launch")
    Launch,

    @SerialName("procwatch")
    Procwatch,

    @SerialName("osc7")
    Osc7,
}

@Serializable
enum class RuntimeState {
    @SerialName("starting")
    Starting,

    @SerialName("live")
    Live,

    @SerialName("reconnecting")
    Reconnecting,

    @SerialName("terminal")
    Terminal,

    @SerialName("lost")
    Lost,

    @SerialName("conflict")
    Conflict,

    @SerialName("incompatible")
    Incompatible,
}

/** Durable runtime of a worker-backed session; `runtime_generation` is a decimal string on the wire. */
@Serializable
data class SessionRuntime(
    val state: RuntimeState,
    @SerialName("runtime_generation") val runtimeGeneration: String,
    @SerialName("worker_id") val workerId: String? = null,
    @SerialName("worker_instance_id") val workerInstanceId: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("last_connected_at") val lastConnectedAt: String? = null,
    @SerialName("loss_reason") val lossReason: String? = null,
)

/** Result of `daemon.health`. */
@Serializable
data class DaemonHealth(
    val status: String,
    @SerialName("daemon_version") val daemonVersion: String,
    @SerialName("protocol_version") val protocolVersion: Int,
)

@Serializable
data class SessionCapabilities(val resume: Boolean, val fork: Boolean)

/** Why the daemon cleared a frozen capability; each value is an error code. */
@Serializable
data class CapabilityRefusals(val resume: String? = null, val fork: String? = null)

/**
 * One entry of `session.list`. Timestamps are RFC 3339 strings as sent. `warnings` is ignored on decode until a consumer needs it.
 * Strings other than ids and codes come from owner-controlled but untrusted metadata.
 */
@Serializable
data class SessionInfo(
    val id: String,
    val external: Boolean? = null,
    val capabilities: SessionCapabilities,
    @SerialName("capability_refusals") val capabilityRefusals: CapabilityRefusals? = null,
    val name: String? = null,
    val agent: String,
    @SerialName("agent_base") val agentBase: String,
    val cwd: String,
    @SerialName("cwd_source") val cwdSource: CwdSource? = null,
    val pid: Long,
    val runtime: SessionRuntime? = null,
    val cols: Int,
    val rows: Int,
    val state: SessionState,
    @SerialName("state_source") val stateSource: StateSource,
    val activity: AgentActivity? = null,
    val subagents: List<SubagentInfo> = emptyList(),
    val background: SessionBackground? = null,
    @SerialName("active_agent") val activeAgent: String? = null,
    @SerialName("active_agent_base") val activeAgentBase: String? = null,
    @SerialName("active_agent_pid") val activeAgentPid: Long? = null,
    @SerialName("active_agent_session_id") val activeAgentSessionId: String? = null,
    @SerialName("active_agent_session_path") val activeAgentSessionPath: String? = null,
    @SerialName("native_session_id") val nativeSessionId: String? = null,
    @SerialName("native_session_path") val nativeSessionPath: String? = null,
    @SerialName("native_last_activity_at") val nativeLastActivityAt: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("project_label") val projectLabel: String? = null,
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean? = null,
    val repo: String? = null,
    val branch: String? = null,
    @SerialName("worktree_path") val worktreePath: String? = null,
    @SerialName("output_force_closed") val outputForceClosed: Boolean? = null,
    val metadata: Map<String, String>? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("exit_code") val exitCode: Int? = null,
)

/** Exact-match `session.list` filter; filters combine with AND. */
@Serializable
data class SessionFilter(val key: String, val value: String) {
    companion object {
        fun state(state: SessionState) = SessionFilter("state", SessionState.serializer().descriptor.getElementName(state.ordinal))

        fun activity(activity: AgentActivity) = SessionFilter("activity", AgentActivity.serializer().descriptor.getElementName(activity.ordinal))

        fun agent(agent: String) = SessionFilter("agent", agent)

        fun id(id: String) = SessionFilter("id", id)

        fun project(projectId: String) = SessionFilter("project", projectId)
    }
}
