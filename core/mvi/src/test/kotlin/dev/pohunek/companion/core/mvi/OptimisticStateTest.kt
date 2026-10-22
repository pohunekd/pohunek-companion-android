// Adapted from opencode-mobile (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.mvi

import org.junit.Assert.assertEquals
import org.junit.Test

class OptimisticStateTest {
    private data class State(val items: List<String> = listOf("a", "b"), val title: String = "Old")

    @Test
    fun rejectedDeleteRestoresItemWithoutLosingConcurrentConfirmedChanges() {
        val state = OptimisticState(State())
        val deletion = state.begin { it.copy(items = it.items - "a") }
        val addition = state.begin { it.copy(items = it.items + "c") }
        assertEquals(listOf("b", "c"), state.value.items)
        addition.confirm()
        state.update { it.copy(title = "New") }
        deletion.rollback()
        assertEquals(State(listOf("a", "b", "c"), "New"), state.value)
    }

    @Test
    fun serverRefreshDoesNotErasePendingEdits() {
        val state = OptimisticState(State())
        val edit = state.begin { it.copy(title = "Pending") }
        state.update { it.copy(items = listOf("a", "b", "c"), title = "Server") }
        assertEquals("Pending", state.value.title)
        edit.confirm { it.copy(title = "Canonical") }
        edit.rollback()
        assertEquals(State(listOf("a", "b", "c"), "Canonical"), state.value)
    }

    @Test
    fun temporaryCreateCanBeReplacedByServerIdentity() {
        val state = OptimisticState(State())
        val create = state.begin { it.copy(items = it.items + "temporary") }
        create.confirm { it.copy(items = it.items + "real-id") }
        assertEquals(listOf("a", "b", "real-id"), state.value.items)
    }
}
