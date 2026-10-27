// Copied from arcboxlabs/expo-libghostty (MIT) android/src/main/java/expo/modules/libghostty/GhosttyVt.kt at f53920f; package renamed. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.terminal

import java.nio.ByteBuffer

/**
 * JNI bindings for libghostty-vt (see src/main/cpp/ghostty_jni.cpp).
 *
 * All functions must be called from the main thread; the native side keeps
 * per-handle state without locking.
 */
internal object GhosttyVt {
  init {
    System.loadLibrary("pohunekterminal")
  }

  // Event flags returned by nativeTakeEventFlags; mirror ghostty_jni.cpp.
  const val EVENT_BELL = 1
  const val EVENT_TITLE = 1 shl 1
  const val EVENT_PWD = 1 shl 2

  // Wheel routing returned by nativeWheelTarget; mirror ghostty_jni.cpp.
  const val WHEEL_LOCAL = 0
  const val WHEEL_MOUSE = 1
  const val WHEEL_ARROWS = 2

  /** Create a terminal session. Returns 0 on failure. */
  external fun nativeCreate(cols: Int, rows: Int, maxScrollback: Long): Long

  external fun nativeFree(handle: Long)

  /**
   * Feed PTY output bytes through the VT parser. Returns query-response bytes
   * the terminal wants written back to the PTY (DSR etc.), or null.
   */
  external fun nativeWrite(handle: Long, data: ByteArray): ByteArray?

  external fun nativeResize(handle: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)

  /** EVENT_* flags for effects observed since the last call; clears on read. */
  external fun nativeTakeEventFlags(handle: Long): Int

  /** Terminal title set via OSC 0/2 as UTF-8, or null when unset. */
  external fun nativeGetTitle(handle: Long): ByteArray?

  /** Working directory set via OSC 7/9/1337 as UTF-8, or null when unset. */
  external fun nativeGetPwd(handle: Long): ByteArray?

  /**
   * Set the terminal's default colors. Colors use ghostty config syntax
   * (hex with or without '#', X11 names); null clears back to the built-in
   * default, invalid strings are logged and skipped. [palette] overrides
   * entries by index on top of ghostty's default 256-color palette.
   */
  external fun nativeSetTheme(
    handle: Long,
    foreground: String?,
    background: String?,
    cursor: String?,
    palette: Array<String?>?
  )

  /** Parse a ghostty-syntax color to ARGB, or 0 when invalid. */
  external fun nativeParseColor(color: String): Int

  /** Scroll the viewport by [deltaRows]; negative is up (into scrollback). */
  external fun nativeScroll(handle: Long, deltaRows: Int)

  external fun nativeScrollToBottom(handle: Long)

  /** Viewport scrollbar as [total, offset, len] rows, or null. */
  external fun nativeScrollbar(handle: Long): LongArray?

  /**
   * Update the render state and flatten dirty rows into [buffer] (direct,
   * little-endian; layout documented in ghostty_jni.cpp). Returns the number
   * of row records written, or -1 on error/too-small buffer.
   */
  external fun nativeSnapshot(handle: Long, buffer: ByteBuffer): Int

  /**
   * Encode a key event ([action]: 0 release / 1 press / 2 repeat) into the
   * escape-sequence bytes to send to the PTY, or null when the key encodes
   * to nothing under the terminal's active modes.
   */
  external fun nativeEncodeKey(
    handle: Long,
    keyCode: Int,
    action: Int,
    metaState: Int,
    unshiftedCodepoint: Int,
    utf8: ByteArray?
  ): ByteArray?

  /**
   * Where a vertical wheel step goes in the terminal's current state:
   * [WHEEL_MOUSE] when the program requested mouse reporting, [WHEEL_ARROWS]
   * on the alternate screen under alternate-scroll mode (1007), else
   * [WHEEL_LOCAL] (the local scrollback).
   */
  external fun nativeWheelTarget(handle: Long): Int

  /**
   * Encode one wheel step ([up] = button 4, else button 5) at the view pixel
   * ([x], [y]) for the active mouse mode and format, or null when the mode
   * reports no wheel.
   */
  external fun nativeEncodeWheel(
    handle: Long,
    up: Boolean,
    x: Float,
    y: Float,
    widthPx: Int,
    heightPx: Int,
    cellWidthPx: Int,
    cellHeightPx: Int
  ): ByteArray?

  /** Select the word at a viewport cell; false when there is nothing there. */
  external fun nativeSelectWord(handle: Long, col: Int, row: Int): Boolean

  external fun nativeSelectAll(handle: Long): Boolean

  /** Install a linear selection between two viewport cells (inclusive). */
  external fun nativeSetSelection(
    handle: Long,
    anchorCol: Int,
    anchorRow: Int,
    col: Int,
    row: Int
  ): Boolean

  external fun nativeClearSelection(handle: Long)

  /** Active selection as UTF-8 (plain, unwrapped, trimmed), or null if none. */
  external fun nativeSelectionText(handle: Long): ByteArray?

  /** Text of the [cols] x [rows] viewport as UTF-8 (plain, unwrapped, trimmed), without touching the selection; null on failure. */
  external fun nativeViewportText(handle: Long, cols: Int, rows: Int): ByteArray?

  /** Conservative check: newlines / bracketed-paste escapes are unsafe. */
  external fun nativeIsPasteSafe(data: ByteArray): Boolean

  /** Encode clipboard bytes for the PTY (control-byte strip + mode-2004 wrap). */
  external fun nativeEncodePaste(handle: Long, data: ByteArray): ByteArray?
}
