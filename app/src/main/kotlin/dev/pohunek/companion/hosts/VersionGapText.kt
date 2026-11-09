package dev.pohunek.companion.hosts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pohunek.companion.R
import dev.pohunek.companion.protocol.VersionGap

/** What the owner can do about a host that refused the connection over its protocol version. */
@Composable
internal fun versionGapText(gap: VersionGap): String = when (gap) {
    is VersionGap.Older -> stringResource(R.string.host_version_older, gap.daemonProtocol)
    is VersionGap.Newer -> stringResource(R.string.host_version_newer, gap.daemonProtocol)
    VersionGap.Unknown -> stringResource(R.string.host_version_unknown)
}
