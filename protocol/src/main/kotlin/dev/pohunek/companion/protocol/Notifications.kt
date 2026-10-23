package dev.pohunek.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NotificationKind {
    @SerialName("agent_blocked")
    AgentBlocked,

    @SerialName("approval_required")
    ApprovalRequired,

    @SerialName("turn_completed")
    TurnCompleted,

    @SerialName("session_finished")
    SessionFinished,

    @SerialName("error")
    Error,

    @SerialName("system")
    System,

    ;

    /** Whether a record of this kind means an agent waits for the owner. */
    val isAttention: Boolean get() = this in ATTENTION

    companion object {
        /** The kinds the owner state reads; the seed lists exactly these. */
        val ATTENTION: List<NotificationKind> = listOf(AgentBlocked, ApprovalRequired)
    }
}

@Serializable
enum class NotificationStatus {
    @SerialName("unread")
    Unread,

    @SerialName("read")
    Read,

    @SerialName("acknowledged")
    Acknowledged,

    @SerialName("archived")
    Archived,

    @SerialName("deleted")
    Deleted,

    ;

    /** Unread and read records are open; the owner has not dealt with them yet. */
    val isOpen: Boolean get() = this == Unread || this == Read
}

/**
 * The fields of a notification record that owner state reads. `title` and `body` are untrusted text that nothing here needs, so they are
 * not decoded.
 */
@Serializable
data class NotificationRecord(
    val id: String,
    val kind: NotificationKind,
    val status: NotificationStatus,
    @SerialName("created_at") val createdAt: String,
    @SerialName("session_id") val sessionId: String? = null,
)

/** Parameters of `notification.list`; unset fields are omitted from the request. */
@Serializable
data class NotificationListParams(val kind: NotificationKind? = null, val limit: Int? = null, val cursor: String? = null)

/** Result of `notification.list`; [nextCursor] is set only when `limit` cut the result. */
@Serializable
data class NotificationListResult(
    val notifications: List<NotificationRecord>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** Daemon error code (class `runtime`) for a page that does not fit one control line. */
const val NOTIFICATION_LIST_TOO_LARGE: String = "notification_list_too_large"

/**
 * Lists every record of [kind] by following `next_cursor` in pages of [pageSize], halving the page whenever the daemon reports it as too
 * large; a page of one that is still too large fails with the daemon's error. [fetchPage] runs one `notification.list` request.
 */
suspend fun listAllNotifications(
    kind: NotificationKind,
    pageSize: Int,
    fetchPage: suspend (NotificationListParams) -> NotificationListResult,
): List<NotificationRecord> {
    require(pageSize >= 1) { "pageSize must be at least 1" }
    val records = ArrayList<NotificationRecord>()
    var limit = pageSize
    var cursor: String? = null
    while (true) {
        val page = try {
            fetchPage(NotificationListParams(kind = kind, limit = limit, cursor = cursor))
        } catch (e: ProtocolClientException.Daemon) {
            if (!e.error.isListTooLarge || limit <= 1) throw e
            limit /= 2
            continue
        }
        records += page.notifications
        val next = page.nextCursor ?: return records
        if (next == cursor || page.notifications.isEmpty()) {
            throw ProtocolClientException.MalformedResponse("notification.list returned a cursor that does not advance")
        }
        cursor = next
    }
}

private val ProtocolError.isListTooLarge: Boolean get() = errorClass == ErrorClass.Runtime && code == NOTIFICATION_LIST_TOO_LARGE
