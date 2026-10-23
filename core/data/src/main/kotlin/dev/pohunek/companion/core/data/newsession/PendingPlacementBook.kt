package dev.pohunek.companion.core.data.newsession

import dev.pohunek.companion.core.domain.newsession.PendingPlacements
import dev.pohunek.companion.core.domain.newsession.Settlement
import dev.pohunek.companion.core.domain.sessions.SessionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import kotlin.time.Duration

/**
 * The placements of sessions the new-session form created, held in memory for the process: the form records one, the session list
 * takes it once the session is in the layout. [maxAge] is read when it is used, so a changed setting applies at once.
 */
class PendingPlacementBook(private val clock: Clock, private val maxAge: () -> Duration) {
    private val current = MutableStateFlow(PendingPlacements())

    /** The entries waiting for their session; changes whenever one is recorded or settled. */
    val state: StateFlow<PendingPlacements> get() = current

    /** Records that [key] goes to [group]; entries that outlived the maximum age are dropped on the way. */
    fun record(key: SessionKey, group: String) {
        val now = clock.millis()
        current.update { it.settle(emptySet(), now, maxAge()).remaining.add(key, group, now) }
    }

    /** Takes the entries of the sessions in [present] and drops the expired ones. */
    fun take(present: Set<SessionKey>): Settlement {
        val now = clock.millis()
        var taken: Settlement? = null
        current.update { before -> before.settle(present, now, maxAge()).also { taken = it }.remaining }
        return checkNotNull(taken) { "update runs its transform at least once" }
    }
}
