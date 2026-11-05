package dev.pohunek.companion.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.layout.LayoutView
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The current view's name with a dropdown that switches the list between its views. */
@Composable
internal fun ViewTitle(selected: LayoutView, onEvent: (SessionListEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val name = stringResource(selected.labelRes())
    val description = stringResource(R.string.view_menu_description, name)
    Box {
        Row(
            Modifier
                .heightIn(min = PohunekTheme.minTouchTarget)
                .clickable(role = Role.Button) { expanded = true }
                .semantics { contentDescription = description }
                .testTag("SessionList.viewMenu"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("SessionList.viewMenuItems"),
        ) {
            for (view in LayoutView.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(view.labelRes())) },
                    leadingIcon = {
                        if (view == selected) Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.view_menu_current))
                    },
                    onClick = {
                        expanded = false
                        onEvent(SessionListEvent.SelectView(view))
                    },
                    modifier = Modifier.testTag("SessionList.view.${view.id}"),
                )
            }
        }
    }
}
