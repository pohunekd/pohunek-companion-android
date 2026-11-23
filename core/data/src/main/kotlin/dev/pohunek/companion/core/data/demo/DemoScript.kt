package dev.pohunek.companion.core.data.demo

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val ESC = "\u001b"
private const val CSI = "$ESC["
private const val RESET = "${CSI}0m"
private const val BOLD = "${CSI}1m"
private const val DIM = "${CSI}2m"
private const val CRLF = "\r\n"

/** How wide the showcase terminal is; every visible line of the script fits it. */
private const val TERMINAL_CELLS = 42

/** `%12s` of the build log: the stage names are right-aligned in this many cells. */
private const val STAGE_CELLS = 12

/** `%3d` of the progress bar and of the test counts. */
private const val PAD_CELLS = 3

private const val BAR_CELLS = 20
private const val PROGRESS_STEPS = 10
private const val PERCENT_STEP = 10
private const val TEST_CASES = 216

/** The build log's version sequence: `version = (version * 7 + 3) % 40`, printed as `v1.major.minor`. */
private const val VERSION_FACTOR = 7
private const val VERSION_ADDEND = 3
private const val VERSION_MODULUS = 40
private const val VERSION_RADIX = 10

private val HEADER_PAUSE = 300.milliseconds
private val TICK_PAUSE = 250.milliseconds
private val CHECKLIST_PAUSE = 300.milliseconds
private val DIFF_PAUSE = 400.milliseconds
private val BUILD_LINE_PAUSE = 20.milliseconds
private val BUILD_PAUSE = 400.milliseconds
private val PROGRESS_PAUSE = 120.milliseconds

private fun trueColour(red: Int, green: Int, blue: Int): String = "${CSI}38;2;$red;$green;${blue}m"

/** The script's true-colour palette. */
@Suppress("MagicNumber") // Colour literals are the script's token definitions, as in the theme.
private object Palette {
    val accent = trueColour(125, 207, 255)
    val green = trueColour(126, 211, 110)
    val red = trueColour(247, 118, 142)
    val amber = trueColour(224, 175, 104)
    val violet = trueColour(187, 154, 247)
    val grey = trueColour(120, 128, 150)
}

/** One write of the script: [bytes] appear at once, then the terminal idles for [delayAfter]. */
class DemoStep(val bytes: ByteArray, val delayAfter: Duration)

/** The static screens `showcase-agent` draws for the daemon's activity states. */
enum class DemoScreen { Working, Blocked, Idle, Ended, Shell }

/**
 * The `showcase-demo` terminal program of `scripts/ui-agent-guest`, ported to bytes without a shell.
 *
 * The script runs in about 42 cells. It carries no state, no input and no clock of its own: the
 * pauses are the only timing, so two runs produce the same bytes.
 */
object DemoScript {
    private val CHECKLIST = listOf(
        "Read the workspace manifest",
        "Bump tokio and tokio-util",
        "Fix the changed trait bounds",
        "Run the build and the tests",
    )

    private val CRATES = listOf(
        "tokio", "tokio-util", "tokio-stream", "tokio-macros", "bytes", "mio", "socket2",
        "parking_lot", "libc", "pin-project-lite", "futures-core", "futures-sink", "futures-util",
        "hashbrown", "indexmap", "serde", "serde_json", "itoa", "ryu", "tracing",
        "tracing-core", "once_cell", "smallvec", "memchr",
    )

    private val STAGES = listOf(
        "Downloading" to Palette.accent,
        "Unpacking" to Palette.amber,
        "Compiling" to Palette.green,
        "Checking" to Palette.amber,
        "Optimizing" to Palette.violet,
        "Linking" to Palette.violet,
    )

    private val TEST_RESULTS = listOf(
        "net::tcp_connect        " to "0.41s",
        "io::copy_bidirectional  " to "0.12s",
        "sync::semaphore_fairness " to "1.07s",
        "time::paused_clock       " to "0.03s",
    )

    /**
     * The `draw` run cut at every pause of the script.
     *
     * Each step carries the pause that follows it in the shell script; those delays are part of the
     * scripted content, not tunables. The last step ends the run with [Duration.ZERO].
     */
    fun steps(): List<DemoStep> {
        val out = StepWriter()
        header(out)
        checklist(out)
        diff(out)
        build(out)
        tests(out)
        out.cut(Duration.ZERO)
        return out.steps()
    }

    /** Everything the script's WINCH trap prints: a clear, then every step at once, without pauses. */
    fun redraw(): ByteArray {
        val clear = "${CSI}H${CSI}2J${CSI}3J".toByteArray(Charsets.UTF_8)
        return steps().fold(clear) { bytes, step -> bytes + step.bytes }
    }

    /** The screen `showcase-agent` prints before it idles; `\n` of the shell becomes `\r\n`. */
    fun screen(kind: DemoScreen): ByteArray = when (kind) {
        DemoScreen.Working -> "~ Showcase task running (42s elapsed)$CRLF"
        DemoScreen.Blocked ->
            "Approval needed: apply the showcase patch?$CRLF  1. Approve$CRLF  2. Decline$CRLF" +
                "Answer with 1 or 2$CRLF"
        DemoScreen.Idle -> "~ Showcase task finished (3s)$CRLF$CRLF> $CRLF"
        DemoScreen.Ended -> "~ Showcase task finished (3s)$CRLF"
        DemoScreen.Shell -> "$ "
    }.toByteArray(Charsets.UTF_8)

    private fun header(out: StepWriter) {
        val rule = "─".repeat(TERMINAL_CELLS - 2)
        out.append(Palette.violet).append("╭$rule╮").append(RESET).append(CRLF)
        out.append(Palette.violet).append("│").append(RESET).append(" ").append(BOLD)
            .append(Palette.accent).append("◆ pohunek showcase").append(RESET).append("  ")
            .append(Palette.amber).append("bump-tokio-1.48").append(RESET).append("    ")
            .append(Palette.violet).append("│").append(RESET).append(CRLF)
        out.append(Palette.violet).append("│").append(RESET).append(" ").append(Palette.grey)
            .append("Raise tokio to 1.48 in the workspace").append(RESET).append("   ")
            .append(Palette.violet).append("│").append(RESET).append(CRLF)
        out.append(Palette.violet).append("╰$rule╯").append(RESET).append(CRLF).append(CRLF)
        out.cut(HEADER_PAUSE)
    }

    private fun checklist(out: StepWriter) {
        // The items start open and tick off in place, so every tick rewrites one line above the cursor.
        out.append(BOLD).append("Plan").append(RESET).append(CRLF)
        for (item in CHECKLIST) {
            out.append(" ").append(Palette.grey).append("[ ]").append(RESET).append(" ")
                .append(item).append(CRLF)
        }
        out.cut(TICK_PAUSE)
        CHECKLIST.forEachIndexed { index, item ->
            val up = CHECKLIST.size - index
            out.append(ESC).append("[$up").append("A").append('\r')
            out.append(" ").append(Palette.green).append("[✓]").append(RESET).append(" ").append(DIM)
                .append(item).append(RESET).append(ESC).append("[$up").append("B").append('\r')
            if (index == CHECKLIST.lastIndex) {
                out.append(CRLF)
                out.cut(CHECKLIST_PAUSE)
            } else {
                out.cut(TICK_PAUSE)
            }
        }
    }

    private fun diff(out: StepWriter) {
        out.append(BOLD).append("Diff").append(RESET).append(" ").append(Palette.accent)
            .append("Cargo.toml").append(RESET).append(CRLF)
        out.append(Palette.violet).append("@@ -14,7 +14,7 @@ [workspace]").append(RESET).append(CRLF)
        out.append(" members = [\"core\", \"net\", \"cli\"]").append(CRLF)
        out.append(" ").append(CRLF)
        out.append(Palette.red).append("-tokio = \"1.47\"").append(RESET).append(CRLF)
        out.append(Palette.green).append("+tokio = \"1.48\"").append(RESET).append(CRLF)
        out.append(Palette.red).append("-tokio-util = \"0.7.12\"").append(RESET).append(CRLF)
        out.append(Palette.green).append("+tokio-util = \"0.7.13\"").append(RESET).append(CRLF)
        out.append(" serde = \"1\"").append(CRLF).append(CRLF)
        out.cut(DIFF_PAUSE)
    }

    private fun build(out: StepWriter) {
        out.append(BOLD).append("Build").append(RESET).append(CRLF)
        var version = 0
        for ((stage, colour) in STAGES) {
            for (crate in CRATES) {
                version = (version * VERSION_FACTOR + VERSION_ADDEND) % VERSION_MODULUS
                out.append(colour).append(stage.padStart(STAGE_CELLS)).append(RESET).append(" ")
                    .append(crate).append(" v1.").append(version / VERSION_RADIX).append(".")
                    .append(version % VERSION_RADIX).append(CRLF)
                out.cut(BUILD_LINE_PAUSE)
            }
        }
        out.append(Palette.green).append("    Finished").append(RESET)
            .append(" dev profile in 38.2s").append(CRLF).append(CRLF)
        out.cut(BUILD_PAUSE)
    }

    private fun tests(out: StepWriter) {
        out.append(BOLD).append("Tests").append(RESET).append(CRLF)
        for (step in 0..PROGRESS_STEPS) {
            val filled = step * 2
            out.append('\r').append(" ").append(Palette.green).append("█".repeat(filled))
                .append(Palette.grey).append("░".repeat(BAR_CELLS - filled)).append(RESET)
                .append(" ").append((step * PERCENT_STEP).toString().padStart(PAD_CELLS))
                .append("%").append("  ")
                .append((step * TEST_CASES / PROGRESS_STEPS).toString().padStart(PAD_CELLS))
                .append("/$TEST_CASES")
            out.cut(PROGRESS_PAUSE)
        }
        out.append(CRLF)
        for ((name, took) in TEST_RESULTS) {
            out.append(" ").append(Palette.green).append("✓").append(RESET).append(" ").append(name)
                .append(Palette.grey).append(took).append(RESET).append(CRLF)
        }
        out.append(CRLF).append(BOLD).append(Palette.green).append(" $TEST_CASES passed ")
            .append(RESET).append(" ").append(Palette.grey).append("0 failed ").append(RESET)
            .append(" ").append(Palette.amber).append("2 skipped ").append(RESET)
            .append(CRLF).append(CRLF)
        out.append(Palette.violet).append("◆ showcase demo: standing by").append(RESET).append(CRLF)
    }
}

/** Collects the script text and cuts it into [DemoStep]s wherever the shell script pauses. */
private class StepWriter {
    private val text = StringBuilder()
    private val blocks = mutableListOf<DemoStep>()

    fun append(value: String): StepWriter = apply { text.append(value) }

    fun append(value: Char): StepWriter = apply { text.append(value) }

    fun append(value: Int): StepWriter = apply { text.append(value) }

    /** Ends the current step, which the player holds for [delayAfter] before writing the next one. */
    fun cut(delayAfter: Duration) {
        blocks += DemoStep(text.toString().toByteArray(Charsets.UTF_8), delayAfter)
        text.setLength(0)
    }

    fun steps(): List<DemoStep> = blocks.toList()
}
