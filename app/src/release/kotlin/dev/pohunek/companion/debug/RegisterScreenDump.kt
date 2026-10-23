package dev.pohunek.companion.debug

import androidx.compose.runtime.Composable
import dev.pohunek.companion.terminal.TerminalController

/** Release builds carry no screen-dump hook; the debug source set provides the receiver under the same name. */
@Composable
@Suppress("UNUSED_PARAMETER") // The signature matches the debug variant.
fun RegisterScreenDump(controller: TerminalController) = Unit
