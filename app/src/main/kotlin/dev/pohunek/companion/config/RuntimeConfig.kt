package dev.pohunek.companion.config

import android.content.res.Resources
import dev.pohunek.companion.core.data.settings.SettingsLoad
import dev.pohunek.companion.core.data.settings.SettingsStore
import dev.pohunek.companion.core.data.settings.SettingsStoreException
import dev.pohunek.companion.core.domain.config.Tunable
import dev.pohunek.companion.core.domain.config.TunableProblem
import dev.pohunek.companion.core.domain.config.checkOverrideValues
import dev.pohunek.companion.core.domain.config.tunableProblems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The overrides live in this file of the app's private files directory. */
const val SETTINGS_FILE = "settings.json"

/** What the tunables are now: the defaults of `config.xml` under the owner's overrides. */
sealed interface ConfigState {
    /** [defaults] and [overrides] are keyed by every tunable / by the overridden ones; [config] is built from both. */
    data class Valid(val config: AppConfig, val defaults: Map<Tunable, Long>, val overrides: Map<Tunable, Long>) : ConfigState

    /**
     * [key] names the setting, or the settings file, that failed validation. [fromOverrides] tells a saved override (the owner can
     * discard it) from a default in `config.xml` (only a new build fixes it).
     */
    data class Invalid(val key: String, val detail: String, val fromOverrides: Boolean) : ConfigState
}

sealed interface ApplyResult {
    data object Applied : ApplyResult

    /** The values break a rule; nothing was saved. */
    data class Refused(val problems: List<TunableProblem>) : ApplyResult

    data class StorageFailed(val reason: String) : ApplyResult
}

/**
 * The tunables in force: `config.xml` defaults under the overrides stored by [store]. Consumers call [current] when they use a value,
 * so an applied change reaches them without a restart. Overrides that no longer validate (a changed default can do that) or an
 * unreadable settings file leave the state [ConfigState.Invalid] until the owner discards them.
 */
class RuntimeConfig(resources: Resources, private val store: SettingsStore) {
    private val defaults: Map<Tunable, Long> = Tunable.entries.associateWith { resources.getInteger(it.resources().default).toLong() }
    private val writes = Mutex()
    private val mutableState = MutableStateFlow(evaluate(store.load()))

    val state: StateFlow<ConfigState> get() = mutableState

    /** The valid configurations over time; the app is only shown while one exists. */
    val configs: Flow<AppConfig> get() = state.filterIsInstance<ConfigState.Valid>().map { it.config }

    /** The configuration in force. Called only after the start-up check accepted the configuration, which stays valid from then on. */
    fun current(): AppConfig = (mutableState.value as? ConfigState.Valid)?.config ?: error("the configuration is read only after it was accepted")

    /** The default of [tunable] in `config.xml`. */
    fun defaultOf(tunable: Tunable): Long = defaults.getValue(tunable)

    /** Replaces the overrides by [overrides] when the values they give obey the rules; also the way out of an invalid saved state. */
    suspend fun apply(overrides: Map<Tunable, Long>): ApplyResult = writes.withLock {
        val kept = overrides.filter { (tunable, value) -> value != defaults.getValue(tunable) }
        val problems = checkOverrideValues(defaults, kept)
        if (problems.isNotEmpty()) return ApplyResult.Refused(problems)
        try {
            store.save(kept)
        } catch (e: SettingsStoreException) {
            return ApplyResult.StorageFailed(e.message.orEmpty())
        }
        mutableState.value = evaluate(SettingsLoad.Loaded(kept))
        ApplyResult.Applied
    }

    private fun evaluate(load: SettingsLoad): ConfigState {
        tunableProblems(defaults).firstOrNull()?.let { return ConfigState.Invalid(it.tunable.key, it.message, fromOverrides = false) }
        return when (load) {
            is SettingsLoad.Unreadable -> ConfigState.Invalid(SETTINGS_FILE, load.reason, fromOverrides = true)
            is SettingsLoad.Loaded -> {
                val problem = checkOverrideValues(defaults, load.overrides).firstOrNull()
                if (problem != null) {
                    ConfigState.Invalid(problem.tunable.key, problem.message, fromOverrides = true)
                } else {
                    ConfigState.Valid(AppConfig.build(defaults + load.overrides), defaults, load.overrides)
                }
            }
        }
    }
}
