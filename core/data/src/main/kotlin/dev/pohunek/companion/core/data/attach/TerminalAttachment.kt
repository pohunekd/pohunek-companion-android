package dev.pohunek.companion.core.data.attach

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.data.log.logCode
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.protocol.AttachPolicy
import dev.pohunek.companion.protocol.AttachedSession
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.LivenessPolicy
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.TerminalDimensions
import dev.pohunek.companion.protocol.watchLiveness
import dev.pohunek.companion.terminal.TerminalController
import dev.pohunek.companion.terminal.TerminalSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Where one [TerminalAttachment] stands. */
sealed interface AttachState {
    data object Connecting : AttachState

    /** [reportedSize] is the PTY size the daemon last confirmed for a resize, null before the first one. */
    data class Attached(val streamId: String, val reportedSize: TerminalSize?) : AttachState

    /** The attach failed or broke; the attachment is finished and a new one is needed to try again. */
    data class Failed(val cause: ProtocolClientException) : AttachState

    /** [stop] completed; [detachFailure] is why the daemon could not confirm the detach, if it could not. */
    data class Detached(val detachFailure: ProtocolClientException?) : AttachState
}

/**
 * Binds a [TerminalController] to one Pohunek session: the first view size becomes the attach geometry, daemon output is written to the
 * view, the user's input goes to the daemon, later size changes send `session.resize`, and [stop] sends `session.detach`. An attachment
 * is single-use, because a reused emulator would layer the new repaint over the old screen; create one per entry into a session.
 */
class TerminalAttachment(
    private val route: HostRoute,
    private val sessionId: String,
    private val controller: TerminalController,
    private val config: ClientConfig,
    private val policy: AttachPolicy,
    private val liveness: LivenessPolicy,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val log: AppLog,
) {
    private val identity = listOf(hostKeyField(route), LogField.sessionId(sessionId))
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutableState = MutableStateFlow<AttachState>(AttachState.Connecting)
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var job: Job? = null

    @Volatile
    private var session: AttachedSession? = null

    val state: StateFlow<AttachState> = mutableState.asStateFlow()

    /** Starts attaching as soon as the view has a size; call once. */
    fun start() {
        check(started.compareAndSet(false, true)) { "TerminalAttachment is single-use" }
        log.info("attach.start", identity)
        job = scope.launch { run() }
    }

    /**
     * Ends the attachment: detaches from the daemon, then closes both connections. Returns at once; the work runs in the attachment's own
     * scope, so it also completes when the screen that called it is already gone. The outcome shows in [state].
     */
    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        scope.launch { teardown() }
    }

    private suspend fun run() {
        val initial = controller.size.filterNotNull().first()
        val attached = try {
            AttachedSession.open(route, sessionId, TerminalDimensions(initial.cols, initial.rows), config, policy, ioDispatcher)
        } catch (e: ProtocolClientException) {
            fail(e)
            return
        }
        session = attached
        log.info("attach.opened", identity)
        mutableState.value = AttachState.Attached(attached.streamId, null)
        try {
            coroutineScope {
                launch { pumpOutput(attached) }
                launch { controller.input.collect { attached.write(it) } }
                launch { pumpResizes(attached, TerminalDimensions(initial.cols, initial.rows)) }
                launch { watchLiveness(liveness) { attached.checkAlive(it) } }
            }
        } catch (e: ProtocolClientException) {
            fail(e)
            attached.close()
        }
    }

    private fun fail(cause: ProtocolClientException) {
        log.error("attach.failed", identity + LogField.errorCode(cause.logCode()))
        mutableState.value = AttachState.Failed(cause)
    }

    /** Ends by throwing: a stream that ended without [stop] is a failure, with the worker's own error when the daemon recorded one. */
    private suspend fun pumpOutput(attached: AttachedSession) {
        while (true) {
            val bytes = attached.read() ?: break
            // The view may be released between two chunks, and its emulator only exists while it is attached.
            withContext(mainDispatcher) { if (controller.isAttached) controller.write(bytes) }
        }
        if (stopping.get()) return
        val ended = attached.detach()
        throw ended.error?.let { ProtocolClientException.Daemon(it) }
            ?: ProtocolClientException.ConnectionLost("the daemon ended the attach stream")
    }

    private suspend fun pumpResizes(attached: AttachedSession, initial: TerminalDimensions) {
        var applied = initial
        controller.size.filterNotNull().distinctUntilChanged().conflate().collect { size ->
            val dimensions = TerminalDimensions(size.cols, size.rows)
            if (dimensions == applied) return@collect
            val info = attached.resize(dimensions)
            applied = dimensions
            mutableState.value = AttachState.Attached(attached.streamId, TerminalSize(info.cols, info.rows))
        }
    }

    private suspend fun teardown() {
        var detachFailure: ProtocolClientException? = null
        val live = session
        if (live != null && mutableState.value !is AttachState.Failed) {
            detachFailure = try {
                live.detach().error?.let { ProtocolClientException.Daemon(it) }
            } catch (e: ProtocolClientException) {
                e
            }
        }
        job?.cancelAndJoin()
        session?.close()
        if (mutableState.value !is AttachState.Failed) {
            if (detachFailure == null) {
                log.info("attach.detached", identity)
            } else {
                log.warn("attach.detach_unconfirmed", identity + listOf(LogField.method("session.detach"), LogField.errorCode(detachFailure.logCode())))
            }
            mutableState.value = AttachState.Detached(detachFailure)
        }
        scope.cancel()
    }
}
