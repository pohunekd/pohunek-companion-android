package dev.pohunek.companion.sessions

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.layout.HostOption
import dev.pohunek.companion.core.domain.layout.HostReach
import dev.pohunek.companion.core.domain.layout.ProjectChip
import dev.pohunek.companion.core.domain.layout.ProjectSelection
import dev.pohunek.companion.core.domain.layout.StateSelection
import dev.pohunek.companion.core.domain.layout.filterKey
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The logo and the view menu; the overflow menu carries Refresh, Hosts and Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionListTopBar(
    state: SessionListState,
    listShown: Boolean,
    onEvent: (SessionListEvent) -> Unit,
    onOpenHosts: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            Image(
                painter = painterResource(R.drawable.ic_pohunek_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.padding(start = PohunekTheme.spacing.gutter).size(PohunekTheme.logoSize),
            )
        },
        title = {
            if (listShown) {
                ViewTitle(state.view, onEvent)
            } else {
                Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = {
            OverflowMenu(state, listShown, onEvent, onOpenHosts, onOpenSettings)
        },
    )
}

@Composable
private fun OverflowMenu(
    state: SessionListState,
    listShown: Boolean,
    onEvent: (SessionListEvent) -> Unit,
    onOpenHosts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("SessionList.overflow")) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.session_list_more))
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("SessionList.overflowMenu"),
    ) {
        if (listShown) {
            MenuItem(R.string.session_list_refresh, "SessionList.refresh") { expanded = false; onEvent(SessionListEvent.Refresh) }
        }
        if (state.hostsRead && state.storageError == null) {
            MenuItem(R.string.session_list_hosts, "SessionList.hosts") { expanded = false; onOpenHosts() }
        }
        MenuItem(R.string.session_list_settings, "SessionList.settings") { expanded = false; onOpenSettings() }
    }
}

@Composable
private fun MenuItem(@StringRes label: Int, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick, modifier = Modifier.testTag(tag))
}
