// Adapted from opencode-mobile (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base class for MVI ViewModels.
 *
 * Provides:
 * - the state as a read-only [StateFlow] ([state]);
 * - a buffered [Channel] of one-shot effects exposed as a [Flow] ([effect]);
 * - [updateState] / [currentState] / [sendEffect] helpers.
 *
 * Screens collect `state` with `collectAsStateWithLifecycle` and `effect` with [CollectEffect].
 *
 * Subclasses only implement [onEvent] and call the helpers:
 *
 * ```
 * class TasksViewModel(...) : BaseViewModel<TasksState, TasksEvent, TasksEffect>(TasksState()) {
 *     override fun onEvent(event: TasksEvent) { ... }
 *     private fun load() = updateState { it.copy(isLoading = true) }
 * }
 * ```
 *
 * @param initialState the state emitted before any event is handled.
 */
abstract class BaseViewModel<State, Event, Effect>(
    initialState: State,
) : ViewModel() {

    private val _state = OptimisticState(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect: Flow<Effect> = _effect.receiveAsFlow()

    /** Current state snapshot. */
    protected val currentState: State get() = _state.value

    /** Single entry point for UI events. */
    abstract fun onEvent(event: Event)

    /** Atomically updates the state. */
    protected fun updateState(transform: (State) -> State) = _state.update(transform)

    protected fun optimisticUpdate(transform: (State) -> State) = _state.begin(transform)

    private val pendingMutations = mutableSetOf<String>()

    protected fun isMutationPending(key: String): Boolean = key in pendingMutations

    /** A key serializes conflicting writes; independent changes can remain in flight. */
    // Any request failure is reported through onError; cancellation is rethrown.
    @Suppress("TooGenericExceptionCaught")
    @OptIn(ExperimentalCoroutinesApi::class)
    protected fun runOptimistic(
        key: String,
        transform: (State) -> State,
        onError: (Exception) -> Unit,
        onSettled: () -> Unit = {},
        request: suspend () -> ((State) -> State)?,
    ) {
        if (!pendingMutations.add(key)) return
        val change = optimisticUpdate(transform)
        // Enter try/finally even when the scope is cancelled before dispatch.
        viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            try {
                coroutineContext.ensureActive()
                change.confirm(request())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error)
            } finally {
                change.rollback()
                pendingMutations.remove(key)
                onSettled()
            }
        }
    }

    /** Emits a one-shot effect. */
    protected fun sendEffect(effect: Effect) {
        _effect.trySend(effect)
    }
}
