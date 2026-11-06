# Changelog

User-facing changes of Pohunek Companion Android, newest version first. Each
section is folded from the fragments in `changelog.d/` when its release is prepared.

## 0.1.0-pre.9 - 2026-11-06

### Fixed

- The session list shows a project's name instead of its id for sessions that appear while the app is open, without waiting for Refresh. ([#197](https://github.com/pohunekd/pohunek-companion-android/issues/197))
- The Clear button in the session list filter bar stays fully on screen on narrow phones when State, a project and a host are all set. ([#234](https://github.com/pohunekd/pohunek-companion-android/issues/234))
- A terminal fling no longer shows one short scroll step at the moment the finger lifts; the scroll carries on at the release speed. ([#238](https://github.com/pohunekd/pohunek-companion-android/issues/238))
- A terminal fling no longer jumps a double step when the finger lifts in the same frame as its last move. ([#240](https://github.com/pohunekd/pohunek-companion-android/issues/240))

### Security

- Host keys in the exported log are now derived with a secret that stays on the phone, so a shared log can no longer be used to confirm a host's address. Log lines written by earlier versions are removed on upgrade. ([#121](https://github.com/pohunekd/pohunek-companion-android/issues/121))

## 0.1.0-pre.8 - 2026-11-06

### Added

- The project picker lists the projects you chose last first, per view, so the projects you switch between are two taps away. The number is the new recent_projects_size setting. ([#203](https://github.com/pohunekd/pohunek-companion-android/issues/203))

### Changed

- The session list top bar shows the Pohunek logo, and Refresh, Hosts and Settings are in the More options menu instead of four text buttons. ([#198](https://github.com/pohunekd/pohunek-companion-android/issues/198))
- The view switch (My groups, By state, By project, By Work item) is a menu on the title of the session list, which frees the row of view chips for sessions. ([#199](https://github.com/pohunekd/pohunek-companion-android/issues/199))
- Search lives in a bar at the bottom of the session list, next to the keyboard and within thumb reach, instead of the top bar. ([#200](https://github.com/pohunekd/pohunek-companion-android/issues/200))
- The state filter is a State chip in the bottom bar that opens a sheet with the number of sessions per state, and a Clear button resets the view's filters in one tap. ([#201](https://github.com/pohunekd/pohunek-companion-android/issues/201))
- The project filter is a Project chip in the bottom bar that opens a searchable list of projects with their session counts, so a project among many is reached in a few taps. ([#202](https://github.com/pohunekd/pohunek-companion-android/issues/202))
- The host filter is a Host chip in the bottom bar that opens a sheet with the connection and session count of each host, so no filter row scrolls sideways any more. ([#204](https://github.com/pohunekd/pohunek-companion-android/issues/204))
- Hosts that are reconnecting share one status line above the session list, and tapping it opens the Hosts screen; a refused connection keeps its own line. ([#205](https://github.com/pohunekd/pohunek-companion-android/issues/205))
- The bottom filter bar slides away while the session list is scrolled down and comes back on any scroll up, so the list has the full height for reading. It stays while the keyboard is open or TalkBack is on. ([#206](https://github.com/pohunekd/pohunek-companion-android/issues/206))

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
