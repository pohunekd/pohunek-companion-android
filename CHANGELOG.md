# Changelog

User-facing changes of Pohunek Companion Android, newest version first. Each
section is folded from the fragments in `changelog.d/` when its release is prepared.

## 0.1.0-pre.5 - 2026-10-30

### Fixed

- Scrolling the terminal follows the finger smoothly instead of jumping a row at a time, and a swipe over a full-screen program such as Claude Code now sends its wheel input at a steady, per-frame pace. ([#154](https://github.com/pohunekd/pohunek-companion-android/issues/154))

## 0.1.0-pre.4 - 2026-10-27

### Fixed

- Swiping in the terminal now scrolls full-screen programs such as Claude Code: the swipe reaches the program as mouse wheel input or arrow keys, as in a desktop terminal, and still scrolls the local history for ordinary output. ([#151](https://github.com/pohunekd/pohunek-companion-android/issues/151))
