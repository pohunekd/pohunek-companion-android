package dev.pohunek.companion.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pohunek.companion.core.data.attach.AttachSettings
import dev.pohunek.companion.core.data.attach.ReattachPolicy
import dev.pohunek.companion.core.data.attach.TerminalAttachment
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.terminal.TerminalController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/** One entry into a session: the attachment and the controller of the terminal view it drives. */
internal class ActiveAttach(val sessionId: String, val controller: TerminalController, val attachment: TerminalAttachment)

/** State of the attach debug screen: the route, the session list of that daemon, and the attach in progress, if any. */
internal class AttachDebugModel(
    private val config: AttachDebugConfig,
    private val reattach: ReattachPolicy,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val log: AppLog,
) {
    var route by mutableStateOf("")
    private var listedRoute: HostRoute? = null
    var sessions by mutableStateOf<List<SessionInfo>?>(null)
        private set
    var listing by mutableStateOf(false)
        private set
    var listError by mutableStateOf<String?>(null)
        private set
    var active by mutableStateOf<ActiveAttach?>(null)
        private set

    fun list() {
        if (listing) return
        listing = true
        listError = null
        scope.launch {
            try {
                val parsed = HostRoute.parse(route.trim())
                PohunekConnection.open(parsed, config.client, ioDispatcher).use { sessions = it.listSessions() }
                listedRoute = parsed
            } catch (e: ProtocolClientException) {
                sessions = null
                listError = e.message
            } finally {
                listing = false
            }
        }
    }

    /** Attaches with a fresh controller and view, so the repaint never lands on an earlier screen. */
    fun attach(session: SessionInfo) {
        val listed = listedRoute
        if (active != null || listed == null) return
        val controller = TerminalController()
        val attachment = TerminalAttachment(
            listed,
            session.id,
            controller,
            AttachSettings(config.client, config.policy, config.liveness, reattach),
            TimeSource.Monotonic,
            ioDispatcher,
            mainDispatcher,
            log,
        )
        attachment.start()
        active = ActiveAttach(session.id, controller, attachment)
    }

    /** Leaves the terminal screen; the detach itself completes in the attachment's own scope. */
    fun detach() {
        active?.attachment?.stop()
        active = null
    }
}
