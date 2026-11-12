package dev.pohunek.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable(with = SessionStateSerializer::class)
enum class SessionState(override val wire: String, override val isUnknown: Boolean = false) : WireEnum {
    Starting("starting"),
    Running("running"),
    Stopped("stopped"),
    Done("done"),
    Failed("failed"),
    Unknown("", true),
}

internal object SessionStateSerializer : WireEnumSerializer<SessionState>("SessionState", SessionState.entries, SessionState.Unknown)

@Serializable(with = StateSourceSerializer::class)
enum class StateSource(override val wire: String, override val isUnknown: Boolean = false) : WireEnum {
    OscTitle("osc_title"),
    OscProgress("osc_progress"),
    Screen("screen"),
    Process("process"),
    Report("report"),
    Unknown("", true),
}

internal object StateSourceSerializer : WireEnumSerializer<StateSource>("StateSource", StateSource.entries, StateSource.Unknown)

@Serializable(with = AgentActivitySerializer::class)
enum class AgentActivity(override val wire: String, override val isUnknown: Boolean = false) : WireEnum {
    Working("working"),
    Blocked("blocked"),
    Idle("idle"),
    Unknown("", true),
}

internal object AgentActivitySerializer : WireEnumSerializer<AgentActivity>("AgentActivity", AgentActivity.entries, AgentActivity.Unknown)

@Serializable(with = CwdSourceSerializer::class)
enum class CwdSource(override val wire: String, override val isUnknown: Boolean = false) : WireEnum {
    Launch("launch"),
    Procwatch("procwatch"),
    Osc7("osc7"),
    Unknown("", true),
}

internal object CwdSourceSerializer : WireEnumSerializer<CwdSource>("CwdSource", CwdSource.entries, CwdSource.Unknown)

@Serializable(with = RuntimeStateSerializer::class)
enum class RuntimeState(override val wire: String, override val isUnknown: Boolean = false) : WireEnum {
    Starting("starting"),
    Live("live"),
    Reconnecting("reconnecting"),
    Terminal("terminal"),
    Lost("lost"),
    Conflict("conflict"),
    Incompatible("incompatible"),
    Unknown("", true),
}

internal object RuntimeStateSerializer : WireEnumSerializer<RuntimeState>("RuntimeState", RuntimeState.entries, RuntimeState.Unknown)

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
data class CapabilityRefusals(val resume: String? = null)

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
        fun state(state: SessionState) = SessionFilter("state", state.requireWire())

        fun activity(activity: AgentActivity) = SessionFilter("activity", activity.requireWire())

        fun agent(agent: String) = SessionFilter("agent", agent)

        fun id(id: String) = SessionFilter("id", id)

        fun project(projectId: String) = SessionFilter("project", projectId)
    }
}
