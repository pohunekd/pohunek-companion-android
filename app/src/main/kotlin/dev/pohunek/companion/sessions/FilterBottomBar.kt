package dev.pohunek.companion.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/**
 * The bar at the bottom of the session list: the search field, and [trailing] (the filter chips and Clear) at its
 * intrinsic width. The field takes the width that is left, collapses to a search icon below
 * [PohunekTheme.filterBarSearchMinWidth], and takes the whole bar while it has the focus.
 */
@Composable
internal fun SessionFilterBar(query: String, onEvent: (SessionListEvent) -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = expanded) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    Surface(modifier.fillMaxWidth().testTag("SessionList.filterBar"), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider()
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .heightIn(min = PohunekTheme.filterBarMinHeight)
                    .padding(horizontal = PohunekTheme.spacing.gutter, vertical = PohunekTheme.spacing.sm),
            ) {
                FilterBarRow(
                    expanded = expanded,
                    minFieldWidth = PohunekTheme.filterBarSearchMinWidth,
                    field = { SearchInput(query, onEvent, onFocusChange = { expanded = it }, requestFocus = expanded) },
                    icon = {
                        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("SessionList.searchIcon")) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.session_list_search_action))
                        }
                    },
                    trailing = trailing,
                )
            }
        }
    }
}

/** Lays out the field or its icon at the start and [trailing] at the end, deciding by the width that [trailing] leaves. */
@Composable
private fun FilterBarRow(
    expanded: Boolean,
    minFieldWidth: Dp,
    field: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val total = constraints.maxWidth
        val trailingPlaceables = if (expanded) {
            emptyList()
        } else {
            subcompose("trailing", trailing).map { it.measure(Constraints(maxWidth = total)) }
        }
        val trailingWidth = trailingPlaceables.sumOf { it.width }
        val remaining = total - trailingWidth
        val collapsed = !expanded && remaining < minFieldWidth.roundToPx()
        val main = if (collapsed) {
            subcompose("icon", icon).map { it.measure(Constraints(maxWidth = remaining.coerceAtLeast(0))) }
        } else {
            subcompose("field", field).map { it.measure(Constraints.fixedWidth(remaining)) }
        }
        val height = maxOf(constraints.minHeight, main.maxOfOrNull { it.height } ?: 0, trailingPlaceables.maxOfOrNull { it.height } ?: 0)
        layout(total, height) {
            main.forEach { it.placeRelative(0, (height - it.height) / 2) }
            var x = total - trailingWidth
            trailingPlaceables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width
            }
        }
    }
}

/** The always-present search input; it does not take the focus by itself, and Search closes the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInput(query: String, onEvent: (SessionListEvent) -> Unit, onFocusChange: (Boolean) -> Unit, requestFocus: Boolean) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }
    val label = stringResource(R.string.search_placeholder)
    LaunchedEffect(requestFocus) { if (requestFocus && !hadFocus) focus.requestFocus() }
    SearchBarDefaults.InputField(
        query = query,
        onQueryChange = { onEvent(SessionListEvent.EditSearch(it)) },
        onSearch = { keyboard?.hide() },
        expanded = false,
        onExpandedChange = {},
        placeholder = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onEvent(SessionListEvent.EditSearch("")) }, modifier = Modifier.testTag("SessionList.searchClear")) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        modifier = Modifier
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) {
                    hadFocus = true
                    onFocusChange(true)
                } else if (hadFocus) {
                    hadFocus = false
                    onFocusChange(false)
                }
            }
            .testTag("SessionList.search"),
    )
}
