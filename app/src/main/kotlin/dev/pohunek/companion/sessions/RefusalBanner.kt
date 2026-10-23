package dev.pohunek.companion.sessions

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme

@Composable
internal fun RefusalBanner(refusal: AttachRefusal, onDismiss: () -> Unit) {
    val spacing = PohunekTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(refusalMessage(refusal)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).testTag("SessionList.refusal"),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("SessionList.refusalDismiss")) {
            Text(stringResource(R.string.session_list_refusal_dismiss))
        }
    }
}

private fun refusalMessage(refusal: AttachRefusal): Int = when (refusal) {
    AttachRefusal.External -> R.string.attach_refusal_external
    AttachRefusal.Starting -> R.string.attach_refusal_starting
    AttachRefusal.Ended -> R.string.attach_refusal_ended
    AttachRefusal.HostUnreachable -> R.string.attach_refusal_host_unreachable
}

/** The outcome of the last resume: a failure or refusal in the error colour, a started session as plain information. */
@Composable
internal fun ResumeNoteBanner(note: ResumeNote, onDismiss: () -> Unit) {
    val spacing = PohunekTheme.spacing
    val text = when (note) {
        is ResumeNote.Started -> stringResource(R.string.resume_started, note.name)
        is ResumeNote.Refused -> note.sentence
        is ResumeNote.Failed ->
            note.hostLabel?.let { stringResource(R.string.resume_failed_on_host, note.sessionId, it, note.detail) }
                ?: stringResource(R.string.resume_failed, note.sessionId, note.detail)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (note is ResumeNote.Started) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).testTag("SessionList.resumeNote"),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("SessionList.resumeNoteDismiss")) {
            Text(stringResource(R.string.session_list_refusal_dismiss))
        }
    }
}
