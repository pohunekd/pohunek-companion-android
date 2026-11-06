package dev.pohunek.companion.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.hosts.HostStatusSummary
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** One tappable line for all reconnecting hosts, and a line of its own for each refusal. */
@Composable
internal fun HostStatusLines(summary: HostStatusSummary, onOpenHosts: () -> Unit) {
    summary.refused.forEach { outage ->
        Text(
            text = stringResource(R.string.host_status_refused, outage.hostLabel, outage.code.orEmpty(), outage.reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(PohunekTheme.spacing.gutter).testTag("SessionList.statusRefused"),
        )
    }
    val first = summary.firstReconnecting ?: return
    val count = summary.reconnecting.size
    val text = if (count == 1) {
        stringResource(R.string.host_status_reconnecting, first.hostLabel, first.attempt, first.reason)
    } else {
        stringResource(R.string.host_status_reconnecting_many, count, summary.followed, first.hostLabel, first.attempt, first.reason)
    }
    val label = stringResource(R.string.host_status_reconnecting_open_hosts, count, summary.followed)
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = PohunekTheme.minTouchTarget)
            .clickable(role = Role.Button, onClick = onOpenHosts)
            .semantics { contentDescription = label }
            .padding(horizontal = PohunekTheme.spacing.gutter)
            .testTag("SessionList.status"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
