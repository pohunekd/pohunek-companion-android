// Adapted from opencode-mobile (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.mvi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread state with independent, replayable optimistic changes. */
class OptimisticState<State>(initial: State) {
    private var confirmed = initial
    private val visible = MutableStateFlow(initial)
    private val changes = linkedMapOf<Any, (State) -> State>()

    val value: State get() = visible.value
    fun asStateFlow(): StateFlow<State> = visible.asStateFlow()

    fun update(transform: (State) -> State) {
        confirmed = transform(confirmed)
        publish()
    }

    fun begin(transform: (State) -> State): Change {
        val key = Any()
        changes[key] = transform
        publish()
        return Change(key)
    }

    private fun publish() {
        visible.value = changes.values.fold(confirmed) { state, transform -> transform(state) }
    }

    inner class Change internal constructor(private val key: Any) {
        /** Replace the prediction with the authoritative result when one is returned. */
        fun confirm(transform: ((State) -> State)? = null) {
            val prediction = changes.remove(key) ?: return
            confirmed = (transform ?: prediction)(confirmed)
            publish()
        }

        /** Idempotent so this can also be called from finally, including cancellation. */
        fun rollback() {
            if (changes.remove(key) != null) publish()
        }
    }
}
