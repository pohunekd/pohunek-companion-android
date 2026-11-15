package dev.pohunek.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One entry of `project.list`; `id` is the handle `session.new` takes and `label` the display name. Only the fields the app uses are decoded. */
@Serializable
data class ProjectInfo(val id: String, val label: String)

/** Who a host profile is shown to (`ProfileAudience`); a value this client does not model decodes to [Unknown]. */
@Serializable(with = ProfileAudienceSerializer::class)
enum class ProfileAudience(override val wire: String, override val isUnknown: Boolean = false) : WireEnum {
    Owner("owner"),
    Agent("agent"),
    Hidden("hidden"),
    Unknown("", true),
}

internal object ProfileAudienceSerializer : WireEnumSerializer<ProfileAudience>("ProfileAudience", ProfileAudience.entries, ProfileAudience.Unknown)

/** Why an [AgentOptionChoice] cannot be selected (`AgentOptionUnavailable`); a reason this client does not model decodes to [Unknown]. */
@Serializable(with = AgentOptionUnavailableSerializer::class)
enum class AgentOptionUnavailable(override val wire: String, override val isUnknown: Boolean = false) : WireEnum {
    RuntimeTooOld("runtime_too_old"),
    RuntimeVersionUnknown("runtime_version_unknown"),
    Unknown("", true),
}

internal object AgentOptionUnavailableSerializer :
    WireEnumSerializer<AgentOptionUnavailable>("AgentOptionUnavailable", AgentOptionUnavailable.entries, AgentOptionUnavailable.Unknown)

/**
 * One choice of an [AgentOption]. A choice with [available] false cannot be selected; [reason] says why and [minRuntime] is the floor as
 * the profile wrote it.
 */
@Serializable
data class AgentOptionChoice(
    val id: String,
    val label: String,
    val available: Boolean,
    val reason: AgentOptionUnavailable? = null,
    @SerialName("min_runtime") val minRuntime: String? = null,
)

/** One option a host profile declares: [default] is the id of the choice a launch takes when the selection does not name the option. */
@Serializable
data class AgentOption(
    val id: String,
    val label: String,
    val default: String,
    val choices: List<AgentOptionChoice>,
)

/**
 * One agent of `host.inspect`'s `runtimes`. A launch is possible when [available] holds and [supported] is not false; an absent
 * [supported] means the runtime has no version policy. [displayName] is the profile's label for pickers and [audience] the pickers it
 * belongs in; an absent [audience] means [ProfileAudience.Owner]. Neither changes launchability. [options] are the profile's declared
 * options in option-id order; empty for a bare runtime, a profile without options and a daemon that predates them.
 */
@Serializable
data class AgentRuntime(
    val agent: String,
    val available: Boolean,
    val version: String? = null,
    val supported: Boolean? = null,
    @SerialName("display_name") val displayName: String? = null,
    val audience: ProfileAudience? = null,
    val options: List<AgentOption> = emptyList(),
)

/**
 * The part of `host.inspect`'s `HostCapabilities` the app reads. [gitAvailable] and [worktreeSupported] have no wire default at the
 * pinned tag, so a snapshot without them fails to decode. [sessionNewAgentOptionsSupported] is false on a daemon that predates the
 * flag, which would drop `agent_options` silently.
 */
@Serializable
data class HostCapabilities(
    val runtimes: List<AgentRuntime>,
    @SerialName("git_available") val gitAvailable: Boolean,
    @SerialName("worktree_supported") val worktreeSupported: Boolean,
    @SerialName("session_new_agent_options_supported") val sessionNewAgentOptionsSupported: Boolean = false,
)

/**
 * The feature flags of `host.inspect` the app reads without the runtime inventory. A daemon that predates a flag omits it, which means
 * the capability is absent.
 */
@Serializable
data class HostFeatureFlags(
    @SerialName("session_attention_isolation_supported") val sessionAttentionIsolationSupported: Boolean = false,
)

/**
 * One entry of `project.actions`. [provider] stays a string so that a kind a newer daemon adds does not break the list; a template is
 * an action whose provider is [PROVIDER_NONE]. Only the fields the app uses are decoded.
 */
@Serializable
data class ActionSummary(val name: String, val provider: String) {
    companion object {
        /** The `provider` of an action that needs no Linear or GitHub data: a static template. */
        const val PROVIDER_NONE = "none"
    }
}

@Serializable
data class ProjectActionsResult(val actions: List<ActionSummary>)

/**
 * Result of `project.action`: the launch recipe of one action. [branch] and [baseBranch] are absent when the template sets none;
 * [promptContent] is the raw template text with any `${var}` placeholder intact.
 */
@Serializable
data class ProjectActionResult(
    val agent: String,
    @SerialName("prompt_content") val promptContent: String,
    val branch: String? = null,
    @SerialName("base_branch") val baseBranch: String? = null,
)

/** The parameters of a `session.new` that runs [agent] in [project] (a project id of `project.list`), in a worktree when [branch] is set. */
data class SessionNewParams(
    val agent: String,
    val cols: Int,
    val rows: Int,
    val project: String,
    /** Owner name; the daemon trims and validates it. Omitted when null. */
    val name: String? = null,
    /** Initial prompt injected into the new terminal; omitted when null. */
    val input: String? = null,
    /** Branch of the worktree the session runs in; omitted when null. */
    val branch: String? = null,
    /** Branch the worktree's branch is created from; omitted when null. */
    val baseBranch: String? = null,
    /** Session metadata written at creation; omitted when null. The daemon validates it like `session.set_metadata`. */
    val metadata: Map<String, String>? = null,
    /** Option id to choice id for the options that differ from the profile's defaults; omitted when null or empty. */
    val agentOptions: Map<String, String>? = null,
)

/**
 * Result of `session.new`: the created session, flattened, plus `applied_input`. [appliedInput] is true when the daemon injected the
 * initial input and null when none was sent or the daemon does not report it.
 */
data class SessionNewResult(val session: SessionInfo, val appliedInput: Boolean?)

/** Decodes a `session.new` result; a mistyped `applied_input` is a typed error, never a default. */
fun JsonElement.decodeSessionNewResult(): SessionNewResult {
    val session = decodeResult(SessionInfo.serializer())
    val applied = (this as? JsonObject)?.get("applied_input")
    return when {
        applied == null || applied is JsonNull -> SessionNewResult(session, null)
        applied is JsonPrimitive && !applied.isString && applied.booleanOrNull != null -> SessionNewResult(session, applied.booleanOrNull)
        else -> throw ProtocolClientException.MalformedResponse("result does not match the documented shape: applied_input is not a boolean")
    }
}
