package dev.pohunek.companion.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pohunek.companion.core.data.attach.TerminalAttachment
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.terminal.TerminalController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One entry into a session: the attachment and the controller of the terminal view it drives. */
internal class ActiveAttach(val sessionId: String, val controller: TerminalController, val attachment: TerminalAttachment)

/** State of the attach debug screen: the route, the session list of that daemon, and the attach in progress, if any. */
internal class AttachDebugModel(
    private val config: AttachDebugConfig,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
) {
    var route by mutableStateOf("")
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
                PohunekConnection.open(HostRoute.parse(route.trim()), config.client, ioDispatcher).use { sessions = it.listSessions() }
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
        if (active != null) return
        val controller = TerminalController()
        val attachment = TerminalAttachment(
            HostRoute.parse(route.trim()),
            session.id,
            controller,
            config.client,
            config.policy,
            ioDispatcher,
            mainDispatcher,
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
