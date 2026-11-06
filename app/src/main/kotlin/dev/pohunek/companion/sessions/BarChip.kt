package dev.pohunek.companion.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/**
 * A filter chip of the bottom bar with a dropdown arrow, narrower than a Material filter chip so the bar fits search and three chips at
 * 411 dp. Only [labelMaxWidth] cuts the label, which the caller sets for user-supplied values.
 */
@Composable
internal fun BarChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier, labelMaxWidth: Dp? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = FilterChipDefaults.shape,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(PohunekTheme.chipBorderWidth, colors.outlineVariant),
        modifier = modifier.minimumInteractiveComponentSize().semantics { role = Role.Button },
    ) {
        Row(
            Modifier.heightIn(min = PohunekTheme.filterBarChipHeight).padding(start = PohunekTheme.filterBarChipPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (labelMaxWidth == null) Modifier else Modifier.widthIn(max = labelMaxWidth),
            )
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(PohunekTheme.filterBarChipIconSize))
        }
    }
}
