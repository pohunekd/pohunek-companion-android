package dev.pohunek.companion.settings

import android.net.Uri
import dev.pohunek.companion.core.domain.config.Tunable

/** One tunable in the form: [text] is what is entered, [saved] the value in force, [error] why the entered text is refused. */
data class TunableItem(
    val tunable: Tunable,
    val default: Long,
    val saved: Long,
    val text: String,
    val error: String? = null,
)

/** What the last Save did. */
sealed interface SaveOutcome {
    data object Saved : SaveOutcome

    /** Nothing was saved; each line names a setting and the rule it breaks. */
    data class Refused(val lines: List<String>) : SaveOutcome

    data class StorageFailed(val reason: String) : SaveOutcome
}

data class SettingsState(
    val items: List<TunableItem> = emptyList(),
    val outcome: SaveOutcome? = null,
    val saving: Boolean = false,
    val exporting: Boolean = false,
    val exportNote: ExportNote? = null,
) {
    /** True when an entered text differs from the value in force. */
    val dirty: Boolean get() = items.any { it.text.trim() != it.saved.toString() }

    /** True when a value in force or an entered text is not the default. */
    val anyOverride: Boolean get() = items.any { it.text.trim() != it.default.toString() || it.saved != it.default }
}

/** Why the last Export logs produced no share. */
sealed interface ExportNote {
    data object Empty : ExportNote

    data class Failed(val reason: String) : ExportNote
}

sealed interface SettingsEvent {
    data class Edit(val tunable: Tunable, val text: String) : SettingsEvent

    data class Reset(val tunable: Tunable) : SettingsEvent

    data object ResetAll : SettingsEvent

    data object Save : SettingsEvent

    data object ExportLogs : SettingsEvent
}

sealed interface SettingsEffect {
    /** The log is copied to the export file at [uri]; the screen opens the share sheet for it. */
    data class ShareLogs(val uri: Uri) : SettingsEffect
}
