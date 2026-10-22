package dev.pohunek.companion.debug

private const val ESC = "\u001b"
private const val CSI = "$ESC["
private const val RESET = "${CSI}0m"

private const val ANSI_COLOURS = 8
private const val CUBE_BASE = 16
private const val CUBE_STEPS = 6
private const val CUBE_COLOURS = CUBE_STEPS * CUBE_STEPS * CUBE_STEPS
private const val GRAY_BASE = 232
private const val GRAY_COLOURS = 24
private const val SGR_FG = 30
private const val SGR_BG = 40
private const val SGR_FG_BRIGHT = 90
private const val SGR_BG_BRIGHT = 100
private const val CHANNEL_MAX = 255
private const val CUBE_ROW_WIDTH = 36
private const val PROMPT_LINE_COLS = 4

/** Escape sequences that exercise colours, text attributes, cursor movement, Unicode, and the alternate screen. */
internal object AnsiSample {
    const val ENTER_ALT_SCREEN = "$CSI?1049h"
    const val LEAVE_ALT_SCREEN = "$CSI?1049l"
    const val CLEAR_ALL = "${CSI}2J${CSI}3J${CSI}H"
    const val PROMPT = "\r\n$ESC[1;32m$$RESET "

    /** Colours, attributes, cursor movement, and wide and box-drawing characters, ending at a prompt. */
    fun colours(cols: Int): String = buildString {
        append("${CSI}1mpohunek terminal demo$RESET  ${CSI}2mlibghostty-vt$RESET\r\n\r\n")
        append("16 colours\r\n")
        for (colour in 0 until ANSI_COLOURS) append("${CSI}${SGR_BG + colour}m  ")
        append("$RESET\r\n")
        for (colour in 0 until ANSI_COLOURS) append("${CSI}${SGR_BG_BRIGHT + colour}m  ")
        append("$RESET\r\n")
        for (colour in 0 until ANSI_COLOURS) append("${CSI}${SGR_FG + colour}mText ")
        append("$RESET\r\n")
        for (colour in 0 until ANSI_COLOURS) append("${CSI}${SGR_FG_BRIGHT + colour}mText ")
        append("$RESET\r\n\r\n")
        append("256 colours\r\n")
        for (row in 0 until CUBE_COLOURS / CUBE_ROW_WIDTH) {
            for (column in 0 until CUBE_ROW_WIDTH) append("${CSI}48;5;${CUBE_BASE + row * CUBE_ROW_WIDTH + column}m ")
            append("$RESET\r\n")
        }
        for (shade in 0 until GRAY_COLOURS) append("${CSI}48;5;${GRAY_BASE + shade}m ")
        append("$RESET\r\n\r\n")
        append("true colour\r\n")
        val width = cols.coerceAtLeast(1)
        for (column in 0 until width) {
            val level = column * CHANNEL_MAX / width
            append("${CSI}48;2;${CHANNEL_MAX - level};$level;${CHANNEL_MAX / 2}m ")
        }
        append("$RESET\r\n\r\n")
        append("${CSI}1mbold$RESET ${CSI}3mitalic$RESET ${CSI}4munderline$RESET ${CSI}9mstrike$RESET ")
        append("${CSI}7minverse$RESET ${CSI}2mfaint$RESET\r\n")
        append("cursor: [.....]${CSI}6D${CSI}1;33m12345${RESET}${CSI}6C|\r\n")
        append("unicode: 日本語 ✓ ┌──┐ │██│ └──┘\r\n")
        append(PROMPT)
    }

    /** One frame of the alternate-screen redraw: a frame border, a moving marker, and a progress bar. */
    fun altScreenFrame(frame: Int, frames: Int, cols: Int, rows: Int): String = buildString {
        val innerWidth = (cols - 2).coerceAtLeast(1)
        val innerRows = (rows - 2).coerceAtLeast(1)
        append("${CSI}H")
        append("${CSI}44;97m+${"-".repeat(innerWidth)}+$RESET")
        for (row in 1..innerRows) {
            append("${CSI}${row + 1};1H${CSI}44;97m|$RESET${CSI}K${CSI}${row + 1};${cols}H${CSI}44;97m|$RESET")
        }
        append("${CSI}$rows;1H${CSI}44;97m+${"-".repeat(innerWidth)}+$RESET")
        val markerRow = 2 + frame % innerRows
        val markerCol = 2 + frame * innerWidth / frames.coerceAtLeast(1)
        append("${CSI}$markerRow;${markerCol}H${CSI}1;31m@$RESET")
        val barWidth = (innerWidth - PROMPT_LINE_COLS).coerceAtLeast(1)
        val filled = (frame + 1) * barWidth / frames.coerceAtLeast(1)
        append("${CSI}3;3H${CSI}42m${" ".repeat(filled)}$RESET${" ".repeat(barWidth - filled)}")
        append("${CSI}4;3Hframe ${frame + 1}/$frames")
    }

    /** Hint printed under the last alternate-screen frame. */
    fun altScreenDone(): String = "${CSI}6;3H${CSI}1mpress q to leave the alternate screen$RESET"
}
