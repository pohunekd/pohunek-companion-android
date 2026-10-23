package dev.pohunek.companion.sessions

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.layout.StateSelection
import dev.pohunek.companion.core.domain.layout.filterKey
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The chip row that narrows the list by owner state; a chip toggles its state, and the row scrolls sideways when the labels do not fit. */
@Composable
internal fun StateChips(chips: List<OwnerState>, selection: StateSelection, onEvent: (SessionListEvent) -> Unit) {
    val spacing = PohunekTheme.spacing
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        for (state in chips) {
            FilterChip(
                selected = state in selection,
                onClick = { onEvent(SessionListEvent.ToggleState(state)) },
                label = { Text(stringResource(state.chipLabelRes())) },
                modifier = Modifier.testTag("SessionList.state.${state.filterKey}"),
            )
        }
    }
}

@StringRes
private fun OwnerState.chipLabelRes(): Int = when (this) {
    OwnerState.NeedsOwner -> R.string.state_chip_needs_you
    OwnerState.Working -> R.string.state_chip_working
    OwnerState.Idle -> R.string.state_chip_idle
    OwnerState.RuntimeProblem -> R.string.state_chip_problem
    OwnerState.Ended -> R.string.state_chip_ended
    OwnerState.NoAgentState -> R.string.state_chip_no_agent
}

/** The search field that takes the place of the title in the top app bar; it takes the focus when it opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchField(query: String, onEvent: (SessionListEvent) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SearchBarDefaults.InputField(
        query = query,
        onQueryChange = { onEvent(SessionListEvent.EditSearch(it)) },
        onSearch = {},
        expanded = false,
        onExpandedChange = {},
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        trailingIcon = {
            TextButton(onClick = { onEvent(SessionListEvent.CloseSearch) }, modifier = Modifier.testTag("SessionList.searchClose")) {
                Text(stringResource(R.string.search_close))
            }
        },
        modifier = modifier.focusRequester(focus).testTag("SessionList.search"),
    )
}

/** The title, or the search field while it is open, with the actions that the open field replaces by its Close button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionListTopBar(
    state: SessionListState,
    listShown: Boolean,
    onEvent: (SessionListEvent) -> Unit,
    onOpenHosts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val search = state.search.takeIf { listShown }
    TopAppBar(
        title = {
            if (search != null) {
                SearchField(search, onEvent, Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = {
            if (search != null) return@TopAppBar
            if (listShown) {
                TextButton(onClick = { onEvent(SessionListEvent.OpenSearch) }, modifier = Modifier.testTag("SessionList.openSearch")) {
                    Text(stringResource(R.string.session_list_search))
                }
                TextButton(onClick = { onEvent(SessionListEvent.Refresh) }, modifier = Modifier.testTag("SessionList.refresh")) {
                    Text(stringResource(R.string.session_list_refresh))
                }
            }
            if (state.hostsRead && state.storageError == null) {
                TextButton(onClick = onOpenHosts, modifier = Modifier.testTag("SessionList.hosts")) {
                    Text(stringResource(R.string.session_list_hosts))
                }
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("SessionList.settings")) {
                Text(stringResource(R.string.session_list_settings))
            }
        },
    )
}
