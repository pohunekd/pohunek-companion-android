package dev.pohunek.companion.settings

import androidx.lifecycle.viewModelScope
import dev.pohunek.companion.config.ApplyResult
import dev.pohunek.companion.config.ConfigState
import dev.pohunek.companion.config.RuntimeConfig
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.domain.config.OverrideCheck
import dev.pohunek.companion.core.domain.config.Tunable
import dev.pohunek.companion.core.domain.config.TunableProblem
import dev.pohunek.companion.core.domain.config.checkOverrides
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.core.mvi.BaseViewModel
import kotlinx.coroutines.launch

/**
 * The form over every tunable. Edits stay in the form until Save; Save checks the whole set (with the same rules as the start-up check),
 * stores the values that differ from the defaults and restarts the live loops so that they read the new values.
 */
class SettingsViewModel(
    private val runtime: RuntimeConfig,
    private val registry: HostRegistry,
    private val log: AppLog,
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(initialState(runtime)) {
    private val defaults: Map<Tunable, Long> = Tunable.entries.associateWith { runtime.defaultOf(it) }

    override fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.Edit -> edit(event.tunable) { event.text }
            is SettingsEvent.Reset -> edit(event.tunable) { defaults.getValue(event.tunable).toString() }
            SettingsEvent.ResetAll -> updateState {
                checked(it.copy(items = it.items.map { item -> item.copy(text = item.default.toString()) }, outcome = null))
            }
            SettingsEvent.Save -> save()
        }
    }

    private fun edit(tunable: Tunable, text: () -> String) {
        updateState { state ->
            checked(state.copy(items = state.items.map { if (it.tunable == tunable) it.copy(text = text()) else it }, outcome = null))
        }
    }

    /** Sets each item's error from the rules applied to the whole entered set. */
    private fun checked(state: SettingsState): SettingsState {
        val problems = (checkOverrides(defaults, state.items.associate { it.tunable to it.text }) as? OverrideCheck.Invalid)?.problems.orEmpty()
        return state.copy(
            items = state.items.map { item ->
                item.copy(error = problems.filter { it.tunable == item.tunable }.joinToString("; ") { it.message }.ifEmpty { null })
            },
        )
    }

    private fun save() {
        val state = currentState
        if (state.saving) return
        when (val check = checkOverrides(defaults, state.items.associate { it.tunable to it.text })) {
            is OverrideCheck.Invalid -> updateState { it.copy(outcome = SaveOutcome.Refused(check.problems.lines())) }
            is OverrideCheck.Valid -> {
                updateState { it.copy(saving = true, outcome = null) }
                viewModelScope.launch { apply(check.overrides) }
            }
        }
    }

    private suspend fun apply(overrides: Map<Tunable, Long>) {
        val before = currentState.items.associate { it.tunable to it.saved }
        val result = try {
            runtime.apply(overrides)
        } finally {
            // Left set by a cancelled or failed apply, the flag would keep Save disabled.
            updateState { it.copy(saving = false) }
        }
        when (result) {
            ApplyResult.Applied -> {
                val effective = defaults + overrides
                // A restart resets the idle clocks, so only a change a running loop captured at its start triggers one.
                if (Tunable.entries.any { it.readByLiveLoops && before.getValue(it) != effective.getValue(it) }) registry.restart()
                logChanges(before, effective)
                updateState { state ->
                    checked(
                        state.copy(
                            items = state.items.map { it.copy(saved = effective.getValue(it.tunable), text = effective.getValue(it.tunable).toString()) },
                            outcome = SaveOutcome.Saved,
                        ),
                    )
                }
            }
            is ApplyResult.Refused -> updateState { it.copy(outcome = SaveOutcome.Refused(result.problems.lines())) }
            is ApplyResult.StorageFailed -> {
                log.warn("settings.save_failed", listOf(LogField.errorCode("storage")))
                updateState { it.copy(outcome = SaveOutcome.StorageFailed(result.reason)) }
            }
        }
    }

    private fun logChanges(before: Map<Tunable, Long>, after: Map<Tunable, Long>) {
        for (tunable in Tunable.entries) {
            if (before.getValue(tunable) == after.getValue(tunable)) continue
            log.info(
                "settings.changed",
                listOf(
                    LogField.tunable(tunable.key),
                    LogField.number("value", after.getValue(tunable)),
                    LogField.number("overridden", if (after.getValue(tunable) == defaults.getValue(tunable)) 0 else 1),
                ),
            )
        }
    }

    private fun List<TunableProblem>.lines(): List<String> = map { "${it.tunable.key} ${it.message}" }
}

private fun initialState(runtime: RuntimeConfig): SettingsState {
    val valid = runtime.state.value as? ConfigState.Valid ?: return SettingsState()
    return SettingsState(
        Tunable.entries.map { tunable ->
            val effective = valid.defaults.getValue(tunable).let { valid.overrides[tunable] ?: it }
            TunableItem(tunable, valid.defaults.getValue(tunable), effective, effective.toString())
        },
    )
}
