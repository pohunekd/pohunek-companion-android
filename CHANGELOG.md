# Changelog

User-facing changes of Pohunek Companion Android, newest version first. Each
section is folded from the fragments in `changelog.d/` when its release is prepared.

## 0.1.0-pre.7 - 2026-10-31

### Changed

- The terminal draws text and backgrounds in runs instead of cell by cell, which makes each redraw cheaper and scrolling smoother. ([#183](https://github.com/pohunekd/pohunek-companion-android/issues/183))

### Fixed

- Scrolling the terminal history while output is arriving is smoother: the grid is drawn once per display frame instead of up to four times. ([#183](https://github.com/pohunekd/pohunek-companion-android/issues/183))
- A program that repaints the screen in synchronized-output mode, such as Claude Code, no longer shows half-drawn frames in the terminal. ([#183](https://github.com/pohunekd/pohunek-companion-android/issues/183))
- Keystrokes and scrolling in the terminal reach the host without waiting for network acknowledgements, so input feels smoother over Wi-Fi. ([#183](https://github.com/pohunekd/pohunek-companion-android/issues/183))
- Scrolling a full-screen program such as Claude Code by touch now sends one evenly spaced scroll step at a time instead of bursts, so it scrolls steadily on any display rate. ([#183](https://github.com/pohunekd/pohunek-companion-android/issues/183))

## 0.1.0-pre.6 - 2026-10-31

### Added

- After the app was closed by Android, the session list opens with each host's last known sessions, dimmed, instead of a loading screen; they turn live when the host answers and never count as needing you before that. ([#178](https://github.com/pohunekd/pohunek-companion-android/issues/178))

### Fixed

- A session list that stopped receiving live updates because its connection to the daemon died silently now reconnects at the next periodic refresh instead of staying stale. ([#83](https://github.com/pohunekd/pohunek-companion-android/issues/83))
- Idle sessions stay Idle and the list no longer redraws when you switch apps or the connection drops and returns, as long as nothing changed on the host. ([#86](https://github.com/pohunekd/pohunek-companion-android/issues/86))
- A host is shown as unreachable only after 10 seconds without a connection, so switching apps or a short network drop no longer greys out the session list; the time is the new `unreachable_grace_ms` setting. ([#161](https://github.com/pohunekd/pohunek-companion-android/issues/161))
- After a short network drop the session list reconnects within seconds, even when another drop happened half a minute earlier. A new setting, `backoff_reset_ms`, sets how long a connection must hold before the reconnect wait starts over. ([#162](https://github.com/pohunekd/pohunek-companion-android/issues/162))
- A single slow health check or failed refresh no longer drops a healthy connection to a host on a poor network. Two new settings, `liveness_failures` and `liveness_retry_ms`, set how many failures in a row end the connection and how soon the next check follows. ([#163](https://github.com/pohunekd/pohunek-companion-android/issues/163))
- When Wi-Fi, mobile data or the VPN comes back, the session list reconnects at once instead of waiting out the rest of its reconnect delay. ([#164](https://github.com/pohunekd/pohunek-companion-android/issues/164))
- The Needs you section stays in view when it comes back after a reconnect, instead of sitting above the visible part of the list. ([#168](https://github.com/pohunekd/pohunek-companion-android/issues/168))
- A host whose name lookup hangs now times out after the connect timeout instead of stalling the connection attempt. ([#175](https://github.com/pohunekd/pohunek-companion-android/issues/175))

## 0.1.0-pre.5 - 2026-10-30

### Fixed

- Scrolling the terminal follows the finger smoothly instead of jumping a row at a time, and a swipe over a full-screen program such as Claude Code now sends its wheel input at a steady, per-frame pace. ([#154](https://github.com/pohunekd/pohunek-companion-android/issues/154))

## 0.1.0-pre.4 - 2026-10-27

### Fixed

- Swiping in the terminal now scrolls full-screen programs such as Claude Code: the swipe reaches the program as mouse wheel input or arrow keys, as in a desktop terminal, and still scrolls the local history for ordinary output. ([#151](https://github.com/pohunekd/pohunek-companion-android/issues/151))
