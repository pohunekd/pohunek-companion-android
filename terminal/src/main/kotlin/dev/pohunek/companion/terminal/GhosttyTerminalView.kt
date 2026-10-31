// Copied from arcboxlabs/expo-libghostty (MIT) android/src/main/java/expo/modules/libghostty/GhosttyTerminalView.kt at f53920f; package renamed. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.terminal

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.provider.Settings
import android.text.InputType
import android.view.ActionMode
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Canvas-backed ghostty terminal grid.
 *
 * Owns a libghostty-vt session via [GhosttyVt]. PTY output is fed in with
 * [write]; user input (IME text, key escape sequences, VT query responses)
 * comes back through [onInputBytes]. Rendering pulls flattened dirty-row
 * snapshots from JNI once per Choreographer frame and patches them onto a
 * grid-sized bitmap; [onDraw] blits the bitmap and overlays the cursor.
 */
internal class GhosttyTerminalView(context: Context, private val settings: TerminalSettings) : View(context) {
  var onInputBytes: ((ByteArray) -> Unit)? = null
  var onGridResize: ((cols: Int, rows: Int) -> Unit)? = null
  var onBell: (() -> Unit)? = null
  var onTitleChange: ((title: String) -> Unit)? = null
  var onDirectoryChange: ((path: String) -> Unit)? = null

  /** Sticky-modifier source for accessory-bar keys and IME text (optional). */
  var accessoryBar: TerminalAccessoryBar? = null

  private var handle: Long = GhosttyVt.nativeCreate(INITIAL_COLS, INITIAL_ROWS, MAX_SCROLLBACK)
  private var finished = false

  // ── Cell metrics ──
  private var fontSizeDp = FONT_SIZE_DP
  private val textPaint = newTextPaint(Typeface.MONOSPACE)
  private val boldPaint = newTextPaint(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
  private val italicPaint = newTextPaint(Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC))
  private val boldItalicPaint =
    newTextPaint(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC))

  // Nerd Font private-use glyphs (powerline, devicons, …) are absent from the
  // system fallback chain; route those cells to the bundled symbols-only font.
  private val symbolsPaint: Paint? = try {
    newTextPaint(Typeface.createFromAsset(context.assets, SYMBOLS_FONT_ASSET))
  } catch (e: RuntimeException) {
    null
  }
  private val fillPaint = Paint()
  private val cursorPaint = Paint()
  private var cellWidth = textPaint.measureText("M")
  private var cellHeight = ceil(
    textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
  ).toInt()
  private var baseline = -textPaint.fontMetrics.ascent
  private val underlineThickness = max(1f, resources.displayMetrics.density)

  // ── Grid state ──
  private var cols = INITIAL_COLS
  private var rows = INITIAL_ROWS
  private var bitmap: Bitmap? = null
  private var bitmapCanvas: Canvas? = null
  private var snapshotBuf: ByteBuffer? = null
  private var rowChars = CharArray(0)
  private var runChars = CharArray(0)
  private var cellFg = IntArray(0)
  private var cellBg = IntArray(0)
  private var cellTextOff = IntArray(0)
  private var cellTextLen = IntArray(0)
  private var cellFlags = IntArray(0)
  private var rowSelStart = IntArray(0)
  private var rowSelEnd = IntArray(0)
  private var rowSelEndWide = BooleanArray(0)

  // ── Selection state ──
  private var selectionMode = false
  private var actionMode: ActionMode? = null
  private var draggingHandle = HANDLE_NONE
  private var dragAnchorCol = 0
  private var dragAnchorRow = 0
  private var dragBiasRows = 0
  private var lastPressCol = 0
  private var lastPressRow = 0
  private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val handleRadius = HANDLE_RADIUS_DP * resources.displayMetrics.density
  private val magnifier =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
      android.widget.Magnifier(this)
    } else {
      null
    }

  private var defaultBg = 0xFF000000.toInt()
  private var defaultFg = 0xFFFFFFFF.toInt()
  // Themed selection colors; when unset, selected cells keep the classic
  // fg/bg swap. Cursor color rides the snapshot header (0 = unconfigured).
  private var selectionBg: Int? = null
  private var selectionFg: Int? = null
  private var cursorColor = 0
  private var cursorX = -1
  private var cursorY = -1
  private var cursorStyle = CURSOR_STYLE_BLOCK
  private var cursorVisible = false
  private var cursorBlinks = false

  // The cursor is an onDraw overlay, so blinking only re-blits the cached
  // bitmap; the grid is untouched.
  private var blinkPhaseOn = true
  private var blinkScheduled = false
  private val blinkRunnable = object : Runnable {
    override fun run() {
      if (!blinkScheduled) return
      blinkPhaseOn = !blinkPhaseOn
      invalidate()
      postDelayed(this, BLINK_INTERVAL_MS)
    }
  }

  private var framePending = false
  private var syncHeldSince = NOT_HELD
  private val syncTimeoutRunnable = Runnable { scheduleFrame() }

  // A render was asked for and has not run yet. The scroll step renders in the same vsync it moved
  // the viewport in, so a frame callback that finds a scroll step pending leaves the render to it.
  private var renderRequested = false
  private val frameCallback = Choreographer.FrameCallback {
    framePending = false
    if (!scrollFramePending) renderFrame()
  }

  // Finger and fling travel not yet applied; one step per display frame turns it into scrollback
  // movement, a sub-row draw offset, or a paced batch of wheel reports.
  private val scrollAccumulator = ScrollAccumulator()
  private var fingerDown = false
  private var scrollFramePending = false

  // Pixels the local scrollback is drawn shifted up by (0 until a cell height); the row that
  // slides in below the grid is drawn from [peekBitmap].
  private var scrollOffsetPx = 0f
  private var peekBitmap: Bitmap? = null
  private var peekCanvas: Canvas? = null

  // The peek row is redrawn only when the viewport's top row changed or something was written or
  // otherwise redrawn since; [peekRowOffset] is the scrollbar offset it was drawn for.
  private var peekDirty = true
  private var peekRowOffset = -1L

  // Last touch position of a scroll gesture; the position a wheel report
  // carries while a fling keeps going after the finger lifted.
  private var wheelX = 0f
  private var wheelY = 0f

  // ── Scrollback state ──
  private val scroller = OverScroller(context)
  private var flingLastY = 0
  private var scrollbarTotal = 0L
  private var scrollbarOffset = 0L
  private var scrollbarLen = 0L
  private var lastScrollActivityAt = 0L
  private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val jumpChipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val jumpChipArrow = Path()
  private val scrollFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
    scrollFramePending = false
    scrollStep(frameTimeNanos)
    if (renderRequested) {
      if (framePending) {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePending = false
      }
      renderFrame()
    }
  }

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent) = true

      override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (scrollbarTotal > 0 && scrollbarOffset + scrollbarLen < scrollbarTotal) {
          val (cx, cy) = jumpChipCenter()
          val slop = JUMP_CHIP_RADIUS_DP * 1.5f * resources.displayMetrics.density
          if (hypot(e.x - cx, e.y - cy) <= slop) {
            GhosttyVt.nativeScrollToBottom(handle)
            scrollAccumulator.clear()
            scrollOffsetPx = 0f
            scheduleFrame()
            return true
          }
        }
        if (selectionMode || actionMode != null) {
          clearSelection()
          return true
        }
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)
          ?.showSoftInput(this@GhosttyTerminalView, 0)
        return true
      }

      override fun onLongPress(e: MotionEvent) {
        if (handle == 0L || finished || scaleDetector.isInProgress) return
        lastPressCol = (e.x / cellWidth).toInt().coerceIn(0, cols - 1)
        lastPressRow = (e.y / cellHeight).toInt().coerceIn(0, rows - 1)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selectionMode = GhosttyVt.nativeSelectWord(handle, lastPressCol, lastPressRow)
        scheduleFrame()
        if (selectionMode) {
          // Keep dragging without lifting: extend from the pressed cell.
          draggingHandle = HANDLE_END
          dragAnchorCol = lastPressCol
          dragAnchorRow = lastPressRow
          dragBiasRows = 0
        } else {
          // Even without a word under the finger, offer Paste / Select all.
          startTerminalActionMode()
        }
      }

      override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
      ): Boolean {
        if (handle == 0L || scaleDetector.isInProgress) return false
        scrollAccumulator.add(distanceY)
        wheelX = e2.x
        wheelY = e2.y
        scheduleScrollStep()
        return true
      }

      override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
      ): Boolean {
        if (handle == 0L || scaleDetector.isInProgress) return false
        // Finger up (negative velocity) scrolls content down: invert.
        wheelX = e2.x
        wheelY = e2.y
        flingLastY = 0
        scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
        scheduleScrollStep()
        return true
      }
    }
  )

  // Mirrors the iOS pinch-zoom: every 0.1 of accumulated scale steps the
  // font size by ±1dp through the same path as the fontSize prop.
  private var pinchScale = 1f
  private var pinchLastScale = 1f
  private val scaleDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        pinchScale = 1f
        pinchLastScale = 1f
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        pinchScale *= detector.scaleFactor
        val steps = ((pinchScale - pinchLastScale) / PINCH_SCALE_STEP).toInt()
        if (steps != 0) {
          pinchLastScale += steps * PINCH_SCALE_STEP
          setFontSize(fontSizeDp + steps)
        }
        return true
      }
    }
  )

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    // Consumers can override via the standard accessibilityLabel view prop.
    contentDescription = "Terminal"
  }

  private fun newTextPaint(typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.typeface = typeface
    textSize = fontSizeDp * resources.displayMetrics.density
  }

  /**
   * Base font size in dp (default 14, clamped to 4–64 like iOS); pinch-to-zoom
   * steps from the current value. Content reflows in place — libghostty-vt
   * owns the grid state, so no output is lost.
   */
  fun setFontSize(dp: Float) {
    val clamped = dp.coerceIn(MIN_FONT_SIZE_DP, MAX_FONT_SIZE_DP)
    if (clamped == fontSizeDp) return
    fontSizeDp = clamped
    val px = clamped * resources.displayMetrics.density
    for (paint in arrayOf(textPaint, boldPaint, italicPaint, boldItalicPaint, symbolsPaint)) {
      paint?.textSize = px
    }
    cellWidth = textPaint.measureText("M")
    cellHeight = ceil(textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent).toInt()
    baseline = -textPaint.fontMetrics.ascent
    // Cell geometry moved under any active selection handles.
    if (selectionMode || actionMode != null) clearSelection()
    updateGridGeometry(force = true)
  }

  /**
   * Apply theme colors. Foreground/background/cursor/palette go into the
   * terminal itself (so OSC 4/10/11/12 queries and resets stay truthful);
   * selection colors are a renderer concern and stay here. Null fields
   * clear back to the defaults.
   */
  fun setTheme(
    foreground: String?,
    background: String?,
    cursorColor: String?,
    selectionBackground: String?,
    selectionForeground: String?,
    palette: Array<String?>?
  ) {
    if (handle == 0L) return
    selectionBg = selectionBackground
      ?.let { GhosttyVt.nativeParseColor(it) }
      ?.takeIf { it != 0 }
    selectionFg = selectionForeground
      ?.let { GhosttyVt.nativeParseColor(it) }
      ?.takeIf { it != 0 }
    GhosttyVt.nativeSetTheme(handle, foreground, background, cursorColor, palette)
    scheduleFrame()
  }

  // ── Session I/O ──

  /** Feed PTY output into the grid. */
  fun write(data: ByteArray) {
    if (handle == 0L) return
    val response = GhosttyVt.nativeWrite(handle, data)
    if (response != null && response.isNotEmpty() && !finished) {
      onInputBytes?.invoke(response)
    }
    drainTerminalEvents()
    trackSyncOutput()
    holdBlinkSolid()
    scheduleFrame()
  }

  /** Records when the program took synchronized output, and forgets it once the program released it. */
  private fun trackSyncOutput() {
    if (!GhosttyVt.nativeSyncOutput(handle)) {
      syncHeldSince = NOT_HELD
      removeCallbacks(syncTimeoutRunnable)
    } else if (syncHeldSince == NOT_HELD) {
      syncHeldSince = android.os.SystemClock.uptimeMillis()
    }
  }

  /** True while the program holds synchronized output and the timeout has not run out. */
  private fun syncOutputHoldsFrame(): Boolean {
    if (syncHeldSince == NOT_HELD) return false
    val remaining = settings.syncOutputTimeout.inWholeMilliseconds - (android.os.SystemClock.uptimeMillis() - syncHeldSince)
    if (remaining <= 0) return false
    // Nothing may write again before the timeout, so a frame is scheduled for when it runs out.
    removeCallbacks(syncTimeoutRunnable)
    postDelayed(syncTimeoutRunnable, remaining)
    return true
  }

  private fun drainTerminalEvents() {
    val flags = GhosttyVt.nativeTakeEventFlags(handle)
    if (flags == 0) return
    if (flags and GhosttyVt.EVENT_BELL != 0) onBell?.invoke()
    if (flags and GhosttyVt.EVENT_TITLE != 0) {
      onTitleChange?.invoke(GhosttyVt.nativeGetTitle(handle)?.toString(Charsets.UTF_8).orEmpty())
    }
    if (flags and GhosttyVt.EVENT_PWD != 0) {
      onDirectoryChange?.invoke(GhosttyVt.nativeGetPwd(handle)?.toString(Charsets.UTF_8).orEmpty())
    }
  }

  /** The text currently visible in the grid, one line per row; null when the view is destroyed or the emulator cannot format it. */
  fun viewportText(): String? {
    if (handle == 0L) return null
    return GhosttyVt.nativeViewportText(handle, cols, rows)?.toString(Charsets.UTF_8)
  }

  /** Mark the underlying PTY as exited: stop forwarding input. */
  fun finish(@Suppress("UNUSED_PARAMETER") exitCode: Int) {
    finished = true
  }

  fun destroy() {
    if (framePending) {
      Choreographer.getInstance().removeFrameCallback(frameCallback)
      framePending = false
    }
    blinkScheduled = false
    removeCallbacks(blinkRunnable)
    removeCallbacks(syncTimeoutRunnable)
    scroller.abortAnimation()
    if (scrollFramePending) {
      Choreographer.getInstance().removeFrameCallback(scrollFrameCallback)
      scrollFramePending = false
    }
    actionMode?.finish()
    if (handle != 0L) {
      GhosttyVt.nativeFree(handle)
      handle = 0
    }
  }

  private fun sendBytes(bytes: ByteArray) {
    if (!finished && bytes.isNotEmpty()) {
      if (selectionMode) clearSelection()
      // Typing while scrolled into history jumps back to the live view.
      if (handle != 0L && scrollbarOffset + scrollbarLen < scrollbarTotal) {
        GhosttyVt.nativeScrollToBottom(handle)
        scrollAccumulator.clear()
        scrollOffsetPx = 0f
        scheduleFrame()
      }
      holdBlinkSolid()
      onInputBytes?.invoke(bytes)
    }
  }

  private fun scheduleScrollStep() {
    if (scrollFramePending) return
    scrollFramePending = true
    Choreographer.getInstance().postFrameCallback(scrollFrameCallback)
  }

  /**
   * One display frame of scrolling, the way a desktop terminal treats a wheel: wheel reports to a
   * program that asked for mouse reporting, arrow keys on the alternate screen under
   * alternate-scroll mode, else the local scrollback. The route is read again every frame.
   */
  private fun scrollStep(frameTimeNanos: Long) {
    if (handle == 0L) return
    val flinging = scroller.computeScrollOffset()
    if (flinging) {
      val y = scroller.currY
      scrollAccumulator.add((y - flingLastY).toFloat())
      flingLastY = y
    }
    val target = GhosttyVt.nativeWheelTarget(handle)
    if (target == GhosttyVt.WHEEL_LOCAL) {
      scrollLocal(settle = !fingerDown && !flinging)
    } else {
      resetScrollOffset()
      sendWheelStep(target, takeReportStep(frameTimeNanos))
      // Whole rows still waiting behind the spacing are reported after the gesture ended too.
      if (!fingerDown && !flinging && !scrollAccumulator.hasReportRow(cellHeight)) scrollAccumulator.clear()
    }
    if (fingerDown || flinging || (target != GhosttyVt.WHEEL_LOCAL && scrollAccumulator.hasReportRow(cellHeight))) {
      scheduleScrollStep()
    }
  }

  private fun takeReportStep(frameTimeNanos: Long) = scrollAccumulator.takeReportStep(
    cellHeight, frameTimeNanos, settings.wheelMinInterval.inWholeNanoseconds, settings.wheelBacklogRows
  )

  /** Sends one wheel step (negative is up) as its own write, so the program sees evenly spaced events. */
  private fun sendWheelStep(target: Int, step: Int) {
    if (step == 0) return
    val bytes = if (target == GhosttyVt.WHEEL_MOUSE) {
      GhosttyVt.nativeEncodeWheel(
        handle, step < 0, wheelX, wheelY, width, height, cellWidth.roundToInt(), cellHeight
      )
    } else {
      val keyCode = if (step < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
      GhosttyVt.nativeEncodeKey(handle, keyCode, 1, 0, 0, null)
    }
    if (bytes != null) sendBytes(bytes)
  }

  /**
   * Moves the local scrollback by the accumulated travel: whole rows go to the viewport, the
   * remainder is the draw offset. With [settle] the remainder is rounded to a row instead, so the
   * grid is row-aligned again at rest.
   */
  private fun scrollLocal(settle: Boolean) {
    val before = GhosttyVt.nativeScrollbar(handle)
    val rows = if (settle) scrollAccumulator.settle(cellHeight) else scrollAccumulator.takeFloorRows(cellHeight)
    if (rows != 0) {
      GhosttyVt.nativeScroll(handle, rows)
      markScrollActivity()
    }
    val after = GhosttyVt.nativeScrollbar(handle)
    val atEnd = after == null || after[1] + after[2] >= after[0]
    val clamped = rows != 0 && (before == null || after == null || after[1] - before[1] != rows.toLong())
    if (atEnd || clamped) {
      // No row to slide in (or no more history): the travel beyond the end is dropped.
      scrollAccumulator.clear()
      scroller.abortAnimation()
    }
    val offset = scrollAccumulator.pending
    if (rows != 0 || offset != scrollOffsetPx) {
      scrollOffsetPx = offset
      markScrollActivity()
      // A sub-row move only shifts the bitmap and the cached peek row; whole rows, or a peek that
      // does not describe the viewport, need a render in this vsync's scroll callback.
      if (rows != 0 || (offset > 0f && peekStale(after))) renderRequested = true else invalidate()
    }
  }

  private fun resetScrollOffset() {
    if (scrollOffsetPx == 0f) return
    scrollAccumulator.clear()
    scrollOffsetPx = 0f
    scheduleFrame()
  }

  private fun markScrollActivity() {
    lastScrollActivityAt = android.os.SystemClock.uptimeMillis()
  }

  /** Returns true when [text] was sent as a key chord (a lit sticky modifier) instead of as text. */
  private fun sendText(text: CharSequence): Boolean {
    if (text.isEmpty()) return false
    // A lit sticky modifier turns the next single-char IME commit into a key
    // chord (Ctrl+C etc.) through ghostty's encoder.
    val bar = accessoryBar
    val meta = bar?.stickyMetaState() ?: 0
    if (meta != 0 && text.length == 1) {
      val keyCode = charToKeyCode(text[0])
      if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
        bar?.consumeSticky()
        val bytes = GhosttyVt.nativeEncodeKey(
          handle, keyCode, 1, meta, text[0].lowercaseChar().code, null
        )
        if (bytes != null && bytes.isNotEmpty()) sendBytes(bytes)
        return true
      }
    }
    sendBytes(text.toString().toByteArray(Charsets.UTF_8))
    return false
  }

  /** Send an accessory-bar key press with the current sticky modifiers. */
  fun sendAccessoryKey(keyCode: Int) {
    if (handle == 0L || finished) return
    val meta = accessoryBar?.stickyMetaState() ?: 0
    accessoryBar?.consumeSticky()
    val bytes = GhosttyVt.nativeEncodeKey(handle, keyCode, 1, meta, 0, null) ?: return
    if (bytes.isNotEmpty()) sendBytes(bytes)
  }

  /** Send accessory-bar symbol text (sticky modifiers compose as for IME text). */
  fun sendAccessoryText(text: CharSequence) = sendText(text)

  /** Paste the clipboard, same confirmation flow as the selection menu. */
  fun requestPaste() = pasteFromClipboard()

  private fun charToKeyCode(char: Char): Int {
    val lower = char.lowercaseChar()
    return when (lower) {
      in 'a'..'z' -> KeyEvent.KEYCODE_A + (lower - 'a')
      in '0'..'9' -> KeyEvent.KEYCODE_0 + (lower - '0')
      ' ' -> KeyEvent.KEYCODE_SPACE
      '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
      ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
      '-' -> KeyEvent.KEYCODE_MINUS
      '=' -> KeyEvent.KEYCODE_EQUALS
      '\\' -> KeyEvent.KEYCODE_BACKSLASH
      '/' -> KeyEvent.KEYCODE_SLASH
      '.' -> KeyEvent.KEYCODE_PERIOD
      ',' -> KeyEvent.KEYCODE_COMMA
      ';' -> KeyEvent.KEYCODE_SEMICOLON
      '\'' -> KeyEvent.KEYCODE_APOSTROPHE
      '`' -> KeyEvent.KEYCODE_GRAVE
      else -> KeyEvent.KEYCODE_UNKNOWN
    }
  }

  // ── Layout / resize ──

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    updateGridGeometry(force = false)
  }

  private fun updateGridGeometry(force: Boolean) {
    if (width <= 0 || height <= 0 || handle == 0L) return
    val newCols = max(2, floor(width / cellWidth).toInt())
    val newRows = max(2, height / cellHeight)
    val changed = newCols != cols || newRows != rows
    cols = newCols
    rows = newRows
    if (changed || force || bitmap == null) {
      scrollAccumulator.clear()
      scrollOffsetPx = 0f
      GhosttyVt.nativeResize(handle, cols, rows, cellWidth.roundToInt(), cellHeight)
      allocateGridBuffers()
      onGridResize?.invoke(cols, rows)
    }
    scheduleFrame()
  }

  private fun allocateGridBuffers() {
    val widthPx = max(1, ceil(cols * cellWidth).toInt())
    val heightPx = max(1, rows * cellHeight)
    bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also {
      bitmapCanvas = Canvas(it)
      bitmapCanvas?.drawColor(defaultBg)
    }
    val capacity = HEADER_BYTES +
      rows * (ROW_HEADER_BYTES + cols * CELL_RECORD_BYTES + cols * MAX_CELL_TEXT_UNITS * 2 + 4)
    peekDirty = true
    peekBitmap = Bitmap.createBitmap(widthPx, cellHeight, Bitmap.Config.ARGB_8888).also {
      peekCanvas = Canvas(it)
    }
    snapshotBuf = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
    rowChars = CharArray(cols * MAX_CELL_TEXT_UNITS)
    runChars = CharArray(cols)
    cellFg = IntArray(cols)
    cellBg = IntArray(cols)
    cellTextOff = IntArray(cols)
    cellTextLen = IntArray(cols)
    cellFlags = IntArray(cols)
    rowSelStart = IntArray(rows) { -1 }
    rowSelEnd = IntArray(rows) { -1 }
    rowSelEndWide = BooleanArray(rows)
  }

  // ── Rendering ──

  private fun scheduleFrame() {
    peekDirty = true
    renderRequested = true
    if (framePending) return
    framePending = true
    Choreographer.getInstance().postFrameCallback(frameCallback)
  }

  private fun renderFrame() {
    renderRequested = false
    val buf = snapshotBuf ?: return
    val canvas = bitmapCanvas ?: return
    if (handle == 0L) return
    if (syncOutputHoldsFrame()) return

    // The peek runs first so the snapshot, selection ranges and scrollbar below describe the
    // viewport the user is actually in.
    if (scrollOffsetPx > 0f) renderPeekRow(buf)
    val rowCount = GhosttyVt.nativeSnapshot(handle, buf)
    if (rowCount < 0) return
    GhosttyVt.nativeScrollbar(handle)?.let { scrollbar ->
      scrollbarTotal = scrollbar[0]
      scrollbarOffset = scrollbar[1]
      scrollbarLen = scrollbar[2]
    }

    buf.position(0)
    val header = SnapshotHeader.read(buf)
    defaultBg = header.background
    defaultFg = header.foreground
    cursorX = header.cursorX
    cursorY = header.cursorY
    cursorStyle = header.cursorStyle
    cursorVisible = header.cursorVisible
    cursorBlinks = header.cursorBlinks
    cursorColor = header.cursorColor
    syncBlinkTimer()

    if (header.dirtyKind == DIRTY_FULL) canvas.drawColor(defaultBg)
    repeat(rowCount) { drawRowRecord(buf, canvas, header.cols) }
    syncSelectionUi()
    invalidate()
  }

  /** Whether [peekBitmap] no longer shows the row below the viewport described by [scrollbar]. */
  private fun peekStale(scrollbar: LongArray?): Boolean =
    scrollbar != null && (peekDirty || peekRowOffset != scrollbar[1])

  /**
   * Draws the row just below the viewport into [peekBitmap]: the viewport moves down one row for
   * a snapshot and back, and only the last row of that snapshot is kept.
   */
  private fun renderPeekRow(buf: ByteBuffer) {
    val canvas = peekCanvas ?: return
    // At the end there is no row below, and moving back would leave the viewport off the bottom.
    val bar = GhosttyVt.nativeScrollbar(handle)
    if (bar == null || bar[1] + bar[2] >= bar[0]) {
      scrollAccumulator.clear()
      scrollOffsetPx = 0f
      return
    }
    if (!peekStale(bar)) return
    GhosttyVt.nativeScroll(handle, 1)
    val rowCount = GhosttyVt.nativeSnapshot(handle, buf)
    GhosttyVt.nativeScroll(handle, -1)
    if (rowCount < 0) return
    buf.position(0)
    val header = SnapshotHeader.read(buf)
    if (header.dirtyKind != DIRTY_FULL) return
    peekDirty = false
    peekRowOffset = bar[1]
    canvas.save()
    canvas.translate(0f, -((rows - 1) * cellHeight).toFloat())
    canvas.drawColor(header.background)
    repeat(rowCount) { drawRowRecord(buf, canvas, header.cols) }
    canvas.restore()
  }

  // ── Selection ──

  /** Top-left / bottom-right cells of the rendered selection, or null. */
  private fun selectionBounds(): IntArray? {
    var top = -1
    var bottom = -1
    for (row in rowSelStart.indices) {
      if (rowSelStart[row] >= 0) {
        if (top < 0) top = row
        bottom = row
      }
    }
    if (top < 0) return null
    return intArrayOf(top, rowSelStart[top], bottom, rowSelEnd[bottom])
  }

  /** Extra columns the end handle shifts right when the last cell is wide. */
  private fun selEndExtraCols(row: Int): Int =
    if (row < rowSelEndWide.size && rowSelEndWide[row]) 1 else 0

  private fun syncSelectionUi() {
    if (!selectionMode) return
    if (selectionBounds() == null) {
      // Scrolled out of the viewport (or collapsed): drop the whole selection
      // so the UI and terminal state never disagree.
      clearSelection()
    } else {
      actionMode?.invalidateContentRect()
    }
  }

  private fun clearSelection() {
    if (handle != 0L) GhosttyVt.nativeClearSelection(handle)
    selectionMode = false
    draggingHandle = HANDLE_NONE
    actionMode?.finish()
    scheduleFrame()
  }

  private fun selectAllContent() {
    if (handle == 0L) return
    if (GhosttyVt.nativeSelectAll(handle)) {
      selectionMode = true
      scheduleFrame()
      actionMode?.invalidate()
    }
  }

  private fun copySelection() {
    if (handle == 0L) return
    val bytes = GhosttyVt.nativeSelectionText(handle) ?: return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(
      ClipData.newPlainText("terminal", String(bytes, Charsets.UTF_8))
    )
    clearSelection()
  }

  private fun pasteFromClipboard() {
    if (handle == 0L || finished) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return
    val text = clip.getItemAt(0).coerceToText(context)?.toString()
    if (text.isNullOrEmpty()) return
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (GhosttyVt.nativeIsPasteSafe(bytes)) {
      sendPaste(bytes)
      return
    }
    AlertDialog.Builder(context)
      .setTitle("Paste multi-line text?")
      .setMessage("The clipboard contains newlines or control sequences that the shell may run as commands.")
      .setPositiveButton("Paste") { _, _ -> sendPaste(bytes) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun sendPaste(bytes: ByteArray) {
    val encoded = GhosttyVt.nativeEncodePaste(handle, bytes) ?: return
    if (selectionMode) clearSelection() else actionMode?.finish()
    sendBytes(encoded)
  }

  private fun startTerminalActionMode() {
    val current = actionMode
    if (current != null) {
      current.invalidate()
      current.invalidateContentRect()
      return
    }
    actionMode = startActionMode(
      object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
          menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
          menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste)
          menu.add(Menu.NONE, MENU_SELECT_ALL, 2, android.R.string.selectAll)
          return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
          menu.findItem(MENU_COPY)?.isVisible = selectionMode
          return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
          when (item.itemId) {
            MENU_COPY -> copySelection()
            MENU_PASTE -> pasteFromClipboard()
            MENU_SELECT_ALL -> selectAllContent()
            else -> return false
          }
          return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
          actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
          outRect.set(selectionContentRect())
        }
      },
      ActionMode.TYPE_FLOATING
    )
  }

  private fun selectionContentRect(): Rect {
    val bounds = selectionBounds()
    return if (bounds != null) {
      val (top, topCol, bottom, bottomCol) = bounds
      val singleRow = top == bottom
      Rect(
        if (singleRow) (topCol * cellWidth).toInt() else 0,
        top * cellHeight,
        if (singleRow) ((bottomCol + 1 + selEndExtraCols(bottom)) * cellWidth).toInt() else width,
        (bottom + 1) * cellHeight
      )
    } else {
      Rect(
        (lastPressCol * cellWidth).toInt(),
        lastPressRow * cellHeight,
        ((lastPressCol + 1) * cellWidth).toInt(),
        (lastPressRow + 1) * cellHeight
      )
    }
  }

  /** Returns which handle (start/end) is under the touch, arming the drag anchor. */
  private fun hitTestHandle(x: Float, y: Float): Int {
    val bounds = selectionBounds() ?: return HANDLE_NONE
    val (top, topCol, bottom, bottomCol) = bounds
    val slop = HANDLE_HIT_SLOP_DP * resources.displayMetrics.density
    val startX = topCol * cellWidth
    val startY = (top + 1) * cellHeight + handleRadius
    val endX = (bottomCol + 1 + selEndExtraCols(bottom)) * cellWidth
    val endY = (bottom + 1) * cellHeight + handleRadius
    val distStart = hypot(x - startX, y - startY)
    val distEnd = hypot(x - endX, y - endY)
    return when {
      distEnd <= slop && distEnd <= distStart -> {
        dragAnchorCol = topCol
        dragAnchorRow = top
        dragBiasRows = 1
        HANDLE_END
      }
      distStart <= slop -> {
        dragAnchorCol = bottomCol
        dragAnchorRow = bottom
        dragBiasRows = 1
        HANDLE_START
      }
      else -> HANDLE_NONE
    }
  }

  private fun dragSelection(x: Float, y: Float) {
    if (handle == 0L) return
    val col = (x / cellWidth).toInt().coerceIn(0, cols - 1)
    val row = ((y / cellHeight).toInt() - dragBiasRows).coerceIn(0, rows - 1)
    GhosttyVt.nativeSetSelection(handle, dragAnchorCol, dragAnchorRow, col, row)
    magnifier?.show(x, y)
    scheduleFrame()
  }

  private fun drawSelectionHandles(canvas: Canvas) {
    if (!selectionMode) return
    val bounds = selectionBounds() ?: return
    val (top, topCol, bottom, bottomCol) = bounds
    handlePaint.color = HANDLE_COLOR
    val stem = max(2f, resources.displayMetrics.density * 2)
    val startX = topCol * cellWidth
    val startTop = ((top + 1) * cellHeight).toFloat()
    canvas.drawRect(startX - stem / 2, startTop - cellHeight / 3f, startX + stem / 2, startTop + handleRadius, handlePaint)
    canvas.drawCircle(startX, startTop + handleRadius, handleRadius, handlePaint)
    val endX = (bottomCol + 1 + selEndExtraCols(bottom)) * cellWidth
    val endTop = ((bottom + 1) * cellHeight).toFloat()
    canvas.drawRect(endX - stem / 2, endTop - cellHeight / 3f, endX + stem / 2, endTop + handleRadius, handlePaint)
    canvas.drawCircle(endX, endTop + handleRadius, handleRadius, handlePaint)
  }

  // ── Cursor blink ──

  private fun wantsBlink(): Boolean {
    return cursorBlinks && cursorVisible && isAttachedToWindow && hasWindowFocus() &&
      Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
      ) != 0f
  }

  private fun syncBlinkTimer() {
    val wants = wantsBlink()
    if (wants && !blinkScheduled) {
      blinkScheduled = true
      postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
    } else if (!wants && blinkScheduled) {
      blinkScheduled = false
      removeCallbacks(blinkRunnable)
      if (!blinkPhaseOn) {
        blinkPhaseOn = true
        invalidate()
      }
    }
  }

  /** Show a solid cursor and restart the blink interval (any input/output). */
  private fun holdBlinkSolid() {
    if (!blinkPhaseOn) {
      blinkPhaseOn = true
      invalidate()
    }
    if (blinkScheduled) {
      removeCallbacks(blinkRunnable)
      postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
    }
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    syncBlinkTimer()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    syncBlinkTimer()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    syncBlinkTimer()
  }

  private fun drawRowRecord(buf: ByteBuffer, canvas: Canvas, snapCols: Int) {
    val rowIndex = buf.int
    val selStart = buf.int
    val selEnd = buf.int
    val textUnits = buf.int
    if (rowIndex < rowSelStart.size) {
      rowSelStart[rowIndex] = selStart
      rowSelEnd[rowIndex] = selEnd
      rowSelEndWide[rowIndex] = false
    }
    val cellsPos = buf.position()
    val textPos = cellsPos + snapCols * CELL_RECORD_BYTES

    buf.position(textPos)
    if (textUnits > rowChars.size) rowChars = CharArray(textUnits)
    for (i in 0 until textUnits) rowChars[i] = buf.char
    val nextRecord = textPos + textUnits * 2 + if (textUnits % 2 != 0) 2 else 0

    buf.position(cellsPos)
    val colCount = minOf(snapCols, cellFg.size)
    for (col in 0 until snapCols) {
      val fg = buf.int
      val bg = buf.int
      val textOff = buf.short.toInt() and 0xFFFF
      val textLen = buf.short.toInt() and 0xFFFF
      val flags = buf.short.toInt() and 0xFFFF
      buf.short // pad
      if (col >= colCount) continue
      if (col == selEnd && flags and FLAG_WIDE != 0 && rowIndex < rowSelEndWide.size) {
        rowSelEndWide[rowIndex] = true
      }
      val selected = selStart >= 0 && col >= selStart && col <= selEnd
      val colors = resolveCellColors(fg, bg, flags, selected, defaultFg, defaultBg, selectionFg, selectionBg)
      cellFg[col] = packedFg(colors)
      cellBg[col] = packedBg(colors)
      cellTextOff[col] = textOff
      cellTextLen[col] = textLen
      cellFlags[col] = flags
    }
    buf.position(nextRecord)

    val top = (rowIndex * cellHeight).toFloat()
    val bottom = top + cellHeight

    // Background pass: row clear, then one rect per run of adjacent cells with the same background.
    fillPaint.color = defaultBg
    canvas.drawRect(0f, top, canvas.width.toFloat(), bottom, fillPaint)
    var bgRunStart = 0f
    var bgRunEnd = 0f
    var bgRunColor = defaultBg
    for (col in 0 until colCount) {
      val bg = cellBg[col]
      if (bg == defaultBg) continue
      val x = col * cellWidth
      val end = x + if (cellFlags[col] and FLAG_WIDE != 0) cellWidth * 2 else cellWidth
      if (bgRunEnd > bgRunStart && bg == bgRunColor && x <= bgRunEnd) {
        bgRunEnd = maxOf(bgRunEnd, end)
        continue
      }
      if (bgRunEnd > bgRunStart) {
        fillPaint.color = bgRunColor
        canvas.drawRect(bgRunStart, top, bgRunEnd, bottom, fillPaint)
      }
      bgRunStart = x
      bgRunEnd = end
      bgRunColor = bg
    }
    if (bgRunEnd > bgRunStart) {
      fillPaint.color = bgRunColor
      canvas.drawRect(bgRunStart, top, bgRunEnd, bottom, fillPaint)
    }

    // Text pass: adjacent printable-ASCII cells with the same paint, colour and decoration are drawn as
    // one run (every glyph of the monospace font advances by exactly one cell); other cells alone.
    var runLen = 0
    var pendingBlanks = 0
    var runPaint = textPaint
    var runFg = 0
    var runDecoration = 0
    var runStartCol = 0
    for (col in 0 until colCount) {
      val flags = cellFlags[col]
      val len = cellTextLen[col]
      if (len == 0 || flags and FLAG_SPACER != 0 || flags and FLAG_INVISIBLE != 0) {
        if (runLen > 0) pendingBlanks++
        continue
      }
      val textOff = cellTextOff[col]
      val unit = rowChars[textOff]
      if (len == 1 && unit.code in ASCII_PRINTABLE_FIRST..ASCII_PRINTABLE_LAST && flags and FLAG_WIDE == 0) {
        val paint = styledPaint(flags)
        val decoration = flags and RUN_DECORATION_FLAGS
        if (runLen > 0 && (paint !== runPaint || cellFg[col] != runFg || decoration != runDecoration)) {
          drawGlyphs(canvas, runChars, 0, runLen, runStartCol * cellWidth, runLen * cellWidth, top, runPaint, runFg, runDecoration)
          runLen = 0
        }
        if (runLen == 0) {
          runStartCol = col
          runPaint = paint
          runFg = cellFg[col]
          runDecoration = decoration
        } else {
          repeat(pendingBlanks) { runChars[runLen++] = ' ' }
        }
        pendingBlanks = 0
        runChars[runLen++] = unit
        continue
      }
      if (runLen > 0) {
        drawGlyphs(canvas, runChars, 0, runLen, runStartCol * cellWidth, runLen * cellWidth, top, runPaint, runFg, runDecoration)
        runLen = 0
      }
      pendingBlanks = 0
      val codepoint = Character.codePointAt(rowChars, textOff)
      val paint = if (symbolsPaint != null &&
        (codepoint in PUA_BMP_FIRST..PUA_BMP_LAST || codepoint >= PUA_SUPPLEMENTARY_FIRST)
      ) {
        symbolsPaint
      } else {
        styledPaint(flags)
      }
      val width = if (flags and FLAG_WIDE != 0) cellWidth * 2 else cellWidth
      drawGlyphs(canvas, rowChars, textOff, len, col * cellWidth, width, top, paint, cellFg[col], flags and RUN_DECORATION_FLAGS)
    }
    if (runLen > 0) {
      drawGlyphs(canvas, runChars, 0, runLen, runStartCol * cellWidth, runLen * cellWidth, top, runPaint, runFg, runDecoration)
    }
  }

  private fun styledPaint(flags: Int): Paint = when {
    flags and FLAG_BOLD != 0 && flags and FLAG_ITALIC != 0 -> boldItalicPaint
    flags and FLAG_BOLD != 0 -> boldPaint
    flags and FLAG_ITALIC != 0 -> italicPaint
    else -> textPaint
  }

  /** Draws [count] chars at [x] over [width] pixels with the faint, underline and strikethrough bits of [decoration]. */
  private fun drawGlyphs(
    canvas: Canvas,
    chars: CharArray,
    offset: Int,
    count: Int,
    x: Float,
    width: Float,
    top: Float,
    paint: Paint,
    fg: Int,
    decoration: Int
  ) {
    val bottom = top + cellHeight
    paint.color = fg
    if (decoration and FLAG_FAINT != 0) paint.alpha = 160
    canvas.drawText(chars, offset, count, x, top + baseline, paint)
    if (decoration and FLAG_UNDERLINE != 0) {
      canvas.drawRect(x, bottom - underlineThickness, x + width, bottom, paint)
    }
    if (decoration and FLAG_STRIKETHROUGH != 0) {
      val mid = top + cellHeight / 2f
      canvas.drawRect(x, mid - underlineThickness / 2, x + width, mid + underlineThickness / 2, paint)
    }
    paint.alpha = 255
  }

  override fun onDraw(canvas: Canvas) {
    canvas.drawColor(defaultBg)
    canvas.save()
    canvas.translate(0f, -scrollOffsetPx)
    bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    if (scrollOffsetPx > 0f) peekBitmap?.let { canvas.drawBitmap(it, 0f, (rows * cellHeight).toFloat(), null) }
    drawCursor(canvas)
    canvas.restore()
    drawSelectionHandles(canvas)
    drawScrollIndicator(canvas)
    drawJumpToBottomChip(canvas)
  }

  private fun drawScrollIndicator(canvas: Canvas) {
    if (scrollbarTotal <= scrollbarLen) return
    val elapsed = android.os.SystemClock.uptimeMillis() - lastScrollActivityAt
    if (elapsed > SCROLLBAR_FADE_END_MS) return
    val alpha = if (elapsed <= SCROLLBAR_FADE_START_MS) {
      255
    } else {
      (255 * (SCROLLBAR_FADE_END_MS - elapsed) / (SCROLLBAR_FADE_END_MS - SCROLLBAR_FADE_START_MS)).toInt()
    }
    val density = resources.displayMetrics.density
    val barWidth = 3 * density
    val right = width - 2 * density
    val trackHeight = height.toFloat()
    val top = trackHeight * scrollbarOffset / scrollbarTotal
    val thumbHeight = max(16 * density, trackHeight * scrollbarLen / scrollbarTotal)
    scrollbarPaint.color = SCROLLBAR_COLOR
    scrollbarPaint.alpha = alpha
    canvas.drawRoundRect(
      right - barWidth, top, right, (top + thumbHeight).coerceAtMost(trackHeight),
      barWidth / 2, barWidth / 2, scrollbarPaint
    )
    if (elapsed > SCROLLBAR_FADE_START_MS) invalidate()
  }

  private fun jumpChipCenter(): Pair<Float, Float> {
    val density = resources.displayMetrics.density
    return Pair(width - 32 * density, height - 32 * density)
  }

  private fun drawJumpToBottomChip(canvas: Canvas) {
    if (scrollbarTotal <= 0 || scrollbarOffset + scrollbarLen >= scrollbarTotal) return
    val density = resources.displayMetrics.density
    val (cx, cy) = jumpChipCenter()
    val radius = JUMP_CHIP_RADIUS_DP * density
    jumpChipPaint.style = Paint.Style.FILL
    jumpChipPaint.color = JUMP_CHIP_BACKGROUND
    canvas.drawCircle(cx, cy, radius, jumpChipPaint)
    // Downward chevron.
    val span = radius * 0.45f
    jumpChipArrow.reset()
    jumpChipArrow.moveTo(cx - span, cy - span * 0.5f)
    jumpChipArrow.lineTo(cx, cy + span * 0.5f)
    jumpChipArrow.lineTo(cx + span, cy - span * 0.5f)
    jumpChipPaint.style = Paint.Style.STROKE
    jumpChipPaint.strokeWidth = 2 * density
    jumpChipPaint.color = JUMP_CHIP_ARROW_COLOR
    canvas.drawPath(jumpChipArrow, jumpChipPaint)
  }

  private fun drawCursor(canvas: Canvas) {
    if (!cursorVisible || cursorX < 0 || cursorY < 0) return
    if (cursorBlinks && blinkScheduled && !blinkPhaseOn) return
    val x = cursorX * cellWidth
    val top = (cursorY * cellHeight).toFloat()
    val bottom = top + cellHeight
    cursorPaint.color = if (cursorColor != 0) cursorColor else defaultFg
    when (cursorStyle) {
      CURSOR_STYLE_BAR -> {
        cursorPaint.style = Paint.Style.FILL
        canvas.drawRect(x, top, x + underlineThickness * 2, bottom, cursorPaint)
      }
      CURSOR_STYLE_UNDERLINE -> {
        cursorPaint.style = Paint.Style.FILL
        canvas.drawRect(x, bottom - underlineThickness * 2, x + cellWidth, bottom, cursorPaint)
      }
      CURSOR_STYLE_BLOCK_HOLLOW -> {
        cursorPaint.style = Paint.Style.STROKE
        cursorPaint.strokeWidth = underlineThickness
        canvas.drawRect(x, top, x + cellWidth, bottom, cursorPaint)
      }
      else -> {
        cursorPaint.style = Paint.Style.FILL
        cursorPaint.alpha = 170
        canvas.drawRect(x, top, x + cellWidth, bottom, cursorPaint)
        cursorPaint.alpha = 255
      }
    }
  }

  // ── Input ──

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        scroller.abortAnimation()
        fingerDown = true
        // Reports the previous gesture left unsent are stale once a new one starts.
        if (handle != 0L && GhosttyVt.nativeWheelTarget(handle) != GhosttyVt.WHEEL_LOCAL) {
          scrollAccumulator.clear()
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        fingerDown = false
        // A fling takes over from the gesture detector below; otherwise the grid settles on a row.
        if (scrollAccumulator.pending != 0f) scheduleScrollStep()
      }
    }
    scaleDetector.onTouchEvent(event)
    if (scaleDetector.isInProgress) {
      if (draggingHandle != HANDLE_NONE) {
        draggingHandle = HANDLE_NONE
        magnifier?.dismiss()
      }
      // Keep the tap/long-press detector's stream consistent; its callbacks
      // are guarded on isInProgress.
      gestureDetector.onTouchEvent(event)
      return true
    }
    if (selectionMode) {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          draggingHandle = hitTestHandle(event.x, event.y)
          if (draggingHandle != HANDLE_NONE) {
            // Hide the toolbar while dragging; it comes back on release.
            actionMode?.finish()
            return true
          }
        }
        MotionEvent.ACTION_MOVE -> if (draggingHandle != HANDLE_NONE) {
          dragSelection(event.x, event.y)
          return true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
          if (draggingHandle != HANDLE_NONE) {
            draggingHandle = HANDLE_NONE
            magnifier?.dismiss()
            startTerminalActionMode()
            return true
          }
      }
    }
    return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
  }

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    // A plain text field with no suggestions: keyboards keep language switching
    // and diacritics (a password variation limits them to Latin), and no
    // autocorrect rewrites what was already sent.
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
      InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
      EditorInfo.IME_FLAG_NO_FULLSCREEN or
      EditorInfo.IME_FLAG_NO_EXTRACT_UI or
      EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    composing.clear()
    return TerminalInputConnection()
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    return handleKeyEvent(event) || super.onKeyDown(keyCode, event)
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    return handleKeyEvent(event) || super.onKeyUp(keyCode, event)
  }

  internal fun handleKeyEvent(event: KeyEvent): Boolean {
    if (handle == 0L || finished) return false
    @Suppress("DEPRECATION")
    if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
      sendText(event.characters)
      return true
    }
    val action = when (event.action) {
      KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) 2 else 1
      KeyEvent.ACTION_UP -> 0
      else -> return false
    }
    // Sticky accessory-bar modifiers apply to hardware keys too.
    val bar = accessoryBar
    val stickyMeta = if (action == 1) bar?.stickyMetaState() ?: 0 else 0
    if (stickyMeta != 0) bar?.consumeSticky()
    val metaState = event.metaState or stickyMeta
    val unicode = event.getUnicodeChar(
      metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv()
    )
    // A dead key is held until the next key and typed combined with it.
    val deadKeyText = if (action != 0) deadKeys.feed(unicode) else null
    if (deadKeyText != null && deadKeyText.isEmpty()) return true
    val utf8 = if (deadKeyText != null) {
      deadKeyText.toByteArray(Charsets.UTF_8)
    } else if (unicode != 0 && unicode and KeyCharacterMap.COMBINING_ACCENT == 0) {
      String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8)
    } else {
      null
    }
    val unshifted = event.getUnicodeChar(0)
    val bytes = GhosttyVt.nativeEncodeKey(
      handle, event.keyCode, action, metaState, unshifted, utf8
    ) ?: return false
    if (bytes.isEmpty()) return false
    sendBytes(bytes)
    return true
  }

  // The composition of the current input connection; reset when a connection is created.
  private val composing = ComposingText()
  private val deadKeys = DeadKeyComposer()
  private var deleteKeyConsumed = false

  private fun applyEdit(edit: TextEdit) {
    repeat(edit.backspaces) {
      handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
      handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }
    if (sendText(edit.text)) composing.markNotErasable(edit.text.codePointCount(0, edit.text.length))
  }

  // The editable of BaseInputConnection stays empty: composition is forwarded
  // to the PTY as it changes, so the IME must never see sent text again.
  private inner class TerminalInputConnection :
    BaseInputConnection(this@GhosttyTerminalView, false) {
    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
      applyEdit(composing.update(text))
      return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
      composing.clear()
      return true
    }

    override fun finishComposingText(): Boolean {
      composing.clear()
      return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
      applyEdit(composing.commit(text))
      return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
      applyEdit(composing.deleteBefore(beforeLength))
      return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
      composing.clear()
      sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
      sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
      return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
      // A backspace key event while composing shortens the composition like a text edit would.
      if (event.keyCode == KeyEvent.KEYCODE_DEL) {
        if (event.action == KeyEvent.ACTION_DOWN && composing.isComposing) {
          applyEdit(composing.deleteBefore(1))
          deleteKeyConsumed = true
          return true
        }
        if (event.action == KeyEvent.ACTION_UP && deleteKeyConsumed) {
          deleteKeyConsumed = false
          return true
        }
      }
      if (handleKeyEvent(event)) return true
      return super.sendKeyEvent(event)
    }
  }

  private companion object {
    const val FONT_SIZE_DP = 14f
    // Same bounds and step as the iOS pinch-zoom (UITerminalView).
    const val MIN_FONT_SIZE_DP = 4f
    const val MAX_FONT_SIZE_DP = 64f
    const val PINCH_SCALE_STEP = 0.1f
    const val INITIAL_COLS = 80
    const val INITIAL_ROWS = 24
    const val MAX_SCROLLBACK = 10_000L
    const val BLINK_INTERVAL_MS = 600L
    const val SYMBOLS_FONT_ASSET = "SymbolsNerdFontMono-Regular.ttf"

    // Nerd Font glyph ranges: BMP private-use area plus the supplementary
    // private-use planes (material icons live at U+F0000+ since v3).
    const val RUN_DECORATION_FLAGS = FLAG_FAINT or FLAG_UNDERLINE or FLAG_STRIKETHROUGH
    const val ASCII_PRINTABLE_FIRST = 0x20
    const val ASCII_PRINTABLE_LAST = 0x7E
    const val PUA_BMP_FIRST = 0xE000
    const val PUA_BMP_LAST = 0xF8FF
    const val PUA_SUPPLEMENTARY_FIRST = 0xF0000

    const val HANDLE_NONE = 0
    const val HANDLE_START = 1
    const val HANDLE_END = 2
    const val HANDLE_RADIUS_DP = 8f
    const val HANDLE_HIT_SLOP_DP = 24f
    const val HANDLE_COLOR = 0xFF4C8DF5.toInt()

    const val NOT_HELD = -1L

    const val MENU_COPY = 1
    const val MENU_PASTE = 2
    const val MENU_SELECT_ALL = 3

    const val SCROLLBAR_FADE_START_MS = 800L
    const val SCROLLBAR_FADE_END_MS = 1400L
    const val SCROLLBAR_COLOR = 0xB3AAAAAA.toInt()
    const val JUMP_CHIP_RADIUS_DP = 18f
    const val JUMP_CHIP_BACKGROUND = 0xCC2A2A2E.toInt()
    const val JUMP_CHIP_ARROW_COLOR = 0xFFE0E0E0.toInt()

  }
}
