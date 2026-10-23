package dev.pohunek.companion.core.data.attach

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.data.log.logCode
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.protocol.AttachedSession
import dev.pohunek.companion.protocol.HostRoute
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
import kotlinx.coroutines.delay
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
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Where one [TerminalAttachment] stands. */
sealed interface AttachState {
    data object Connecting : AttachState

    /** [reportedSize] is the PTY size the daemon last confirmed for a resize, null before the first one. */
    data class Attached(val streamId: String, val reportedSize: TerminalSize?) : AttachState

    /** The attachment is down and attach number [attempt] since it was lost starts in [retryIn]; input is discarded meanwhile. */
    data class Reconnecting(val attempt: Int, val cause: ProtocolClientException, val retryIn: Duration) : AttachState

    /** The attachment ended for good and will not retry; [kind] says why. */
    data class Failed(val cause: ProtocolClientException, val kind: FailureKind) : AttachState

    /** [stop] completed; [detachFailure] is why the daemon could not confirm the detach, if it could not. */
    data class Detached(val detachFailure: ProtocolClientException?) : AttachState
}

/**
 * Binds a [TerminalController] to one Pohunek session: the first view size becomes the attach geometry, daemon output is written to the
 * view, the user's input goes to the daemon, later size changes send `session.resize`, and [stop] sends `session.detach`. When the
 * attachment breaks, the whole attach (`session.attach`, then the stream prelude) is redone with a growing wait per [ReattachPolicy], the
 * screen is cleared and repainted from the daemon's snapshot, and input typed meanwhile is discarded. Retrying ends in
 * [AttachState.Failed] when the daemon reports the session gone, the route is unusable, or the host stays unreachable too long. An
 * attachment is single-use per view: create one per entry into a session.
 */
class TerminalAttachment(
    private val route: HostRoute,
    private val sessionId: String,
    private val controller: TerminalController,
    private val settings: AttachSettings,
    private val timeSource: TimeSource.WithComparableMarks,
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
        job = scope.launch { supervise() }
    }

    /**
     * Ends the attachment: stops any retrying, detaches from the daemon when attached, then closes both connections. Returns at once; the
     * work runs in the attachment's own scope, so it also completes when the screen that called it is already gone. The outcome shows in
     * [state].
     */
    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        scope.launch { teardown() }
    }

    /** Attaches, and re-attaches after every failure, until a final failure or [stop]. */
    private suspend fun supervise() {
        var failures = 0
        var outageStart: ComparableTimeMark? = null
        var repaint = false
        while (true) {
            val outcome = attachOnce(repaint)
            val heldFor = outcome.attachedAt?.elapsedNow()
            if (heldFor != null) outageStart = null
            if (heldFor != null && heldFor >= settings.reattach.maxDelay) failures = 0
            failures++
            val error = outcome.error
            val start = outageStart ?: timeSource.markNow().also { outageStart = it }
            val final = error.finalKind() ?: if (start.elapsedNow() >= settings.reattach.giveUpAfter) FailureKind.GaveUp else null
            if (final != null) {
                fail(error, final)
                return
            }
            val wait = settings.reattach.delayFor(failures)
            val fields = listOf(
                LogField.errorCode(error.logCode()),
                LogField.number("attempt", failures.toLong()),
                LogField.number("delay_ms", wait.inWholeMilliseconds),
            )
            log.warn("attach.reconnecting", identity + fields)
            mutableState.value = AttachState.Reconnecting(failures, error, wait)
            repaint = true
            delay(wait)
        }
    }

    /** How one attach ended: [attachedAt] is when it was established, null when it never was. */
    private class Outcome(val error: ProtocolClientException, val attachedAt: ComparableTimeMark?)

    /** One whole attach and its pumps; returns the failure that ended it. Input is discarded except while attached. */
    private suspend fun attachOnce(repaint: Boolean): Outcome = coroutineScope {
        val discard = launch { controller.input.collect { } }
        var attached: AttachedSession? = null
        var attachedAt: ComparableTimeMark? = null
        val error = try {
            val initial = controller.size.filterNotNull().first()
            val opened = AttachedSession.open(
                route, sessionId, TerminalDimensions(initial.cols, initial.rows), settings.client, settings.attach, ioDispatcher,
            )
            attached = opened
            session = opened
            attachedAt = timeSource.markNow()
            log.info("attach.opened", identity)
            // The daemon repaints from its snapshot, which lands on a blank screen only after the old content is cleared.
            if (repaint) withContext(mainDispatcher) { if (controller.isAttached) controller.write(RESET_TERMINAL) }
            discard.cancelAndJoin()
            mutableState.value = AttachState.Attached(opened.streamId, null)
            coroutineScope {
                launch { pumpOutput(opened) }
                launch { controller.input.collect { opened.write(it) } }
                launch { pumpResizes(opened, TerminalDimensions(initial.cols, initial.rows)) }
                launch { watchLiveness(settings.liveness) { opened.checkAlive(it) } }
            }
            error("the attach pumps never return normally")
        } catch (e: ProtocolClientException) {
            e
        } finally {
            session = null
            attached?.close()
            discard.cancel()
        }
        Outcome(error, attachedAt)
    }

    private fun fail(cause: ProtocolClientException, kind: FailureKind) {
        log.error("attach.failed", identity + listOf(LogField.errorCode(cause.logCode()), LogField.number("final", 1)))
        mutableState.value = AttachState.Failed(cause, kind)
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
        if (live != null) {
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

/** ESC c (RIS): the terminal's full reset. */
private val RESET_TERMINAL = "\u001bc".toByteArray(Charsets.US_ASCII)
