# Verification

## Gate set

`scripts/gates` runs through `scripts/dev` (the toolchain container), in order,
each with its own exit code:

1. Spotless (ktlint) check (`spotlessCheck`)
2. detekt (`detekt`, rules in `config/detekt.yml`)
3. Android lint (`lintDebug`)
4. unit tests (`unitTest`, `maxParallelForks` = `test_forks` in `config/dev.toml`)
5. `assembleDebug`

Every gate runs even when an earlier one fails. The script prints one result per
gate (`passed` or `failed (exit N)`) and exits non-zero if any failed. A
formatting failure is fixed with `scripts/dev ./gradlew spotlessApply`.

CI runs the same set in the same container image, so a green local run is the
expected predictor of a green PR. Run it on the final revision before every push
and report each gate's real result. Never report a gate as passed when it was
skipped or its output was cut off.

## Testing policy

The app changes fast, and tests that pin UI or internal structure only slow it
down. Write the minimum set of tests that catch real regressions.

Write a test only for:

- **Non-trivial pure logic** where a bug is silent and costly: reconciling the
  daemon snapshot with the event stream, protocol framing and parsing, layout
  ordering and persistence, needs-you derivation, config validation, terminal
  input encoding.
- **A fixed bug in such logic**, as a regression test that fails without the
  fix. Do not add a regression test for UI glitches or one-line fixes.

Do not write:

- tests for UI layout, Composables, styling, or rendering;
- tests per function, getters, trivial mappings, or anything the type system
  already guarantees;
- mocks of the Pohunek daemon. Test logic on plain data (snapshots and event
  sequences as values, wire fixtures); exercise the real daemon only through
  manual verification;
- coverage targets.

Tests must be deterministic and parallel-safe:

- no sleeps and no real clocks: inject the clock and use virtual time from
  `kotlinx-coroutines-test`;
- no network;
- no shared mutable singletons;
- files only in the test's own temporary directory.

A flaky test is a bug: fix it or delete it. Delete a test that keeps breaking on
intentional changes without ever having caught a bug, and say so in the PR.

## Manual verification

UI and integration changes are verified by running the app in an emulator
against an isolated Pohunek daemon through `scripts/ui-agent`. The PR records
what was done and observed, with a screenshot for visible changes. Behaviour
that needs the owner's phone (NetBird, real network conditions) is left to an
owner-run milestone check described on the issue; it is reported as unverified
until run.

Agent sessions share the owner's machine. Never touch the owner's Pohunek
runtime directory or real daemon, never run the toolchain on the host outside
`scripts/dev`, and never point an emulator at the owner's daemon.

```bash
scripts/ui-agent start [--sessions N] [--showcase] [--device phone] [--owner-daemon]
scripts/ui-agent install | restart | stop | status
scripts/ui-agent screenshot OUT.png | tree | find TAG | click TAG [N]
scripts/ui-agent type TEXT [--enter] | key esc|ctrl-c|tab|up|down|left|right|enter
scripts/ui-agent rotate | net-drop | net-restore | host-stop | host-start
scripts/ui-agent health | log [app|daemon|emulator]
scripts/ui-agent screen | session-screen ID [--size]
scripts/ui-agent swipe TAG up|down|left|right | long-press TAG [N] | drag TAG [N] --to TAG [M] [--after]
scripts/ui-agent background | foreground | clipboard | sockets
scripts/ui-agent app-file cat|put|rm PATH
scripts/ui-agent pohunek ARGS...
```

- **Slots.** A run is one `scripts/dev` container named
  `pohunek-ui-agent-slot-N` (N below `slots` in `config/dev.toml`, default 2),
  claimed under a host `flock`; its owner is recorded in the container's labels,
  so there is no state file. One run per worktree; a slot whose worktree no
  longer exists is reclaimed. The container holds the emulator (console port
  `emulator_console_port_base + 2N`), the isolated `pohunekd --dev-subprocess`
  with its own XDG directories and synthetic sessions running `/bin/sh`, and a
  `socat` bridge from `127.0.0.1:(bridge_port_base + N)` to the daemon's Unix
  socket, reversed into the device with `adb reverse`. The app reaches the
  daemon at `127.0.0.1:(bridge_port_base + N)`; `status` prints the port.
  The container is the containment: `stop` removes it and with it every
  process and temporary directory of the run. Nothing of the host's
  environment, home directory, or Pohunek runtime directory enters it.
- **Start.** `start` boots the emulator from a quick-boot snapshot (created once
  per AVD in the `pohunek-companion-android-avd` volume, keyed by system image,
  emulator build, cores, RAM, and screen), starts the daemon and sessions while
  `assembleDebug` runs, installs the APK, and launches `launch_activity` (`MainActivity`, the
  session list). `--sessions N` adds N generated sessions to the four
  fixed ones; `--showcase` replaces them with a realistic dataset of synthetic
  `claude` and `codex` profiles that run a harmless shell program. `type` cannot
  enter a literal `%s` (`input text` turns it into a space).
- **Install and restart.** `install` builds the debug APK and installs it; it
  then compares the SHA-256 of the installed `base.apk` with the built file and
  fails if they differ, so a success always means the device runs the current
  build. `restart` is `install` followed by a force-stop and launch. Builds are
  signed with a debug key in the Android home volume, the same for every
  worktree; an app on a device that carries another key fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and is never uninstalled automatically
  (that would erase its data); an app installed before the shared key existed needs one manual `adb uninstall dev.pohunek.companion`.
- **libghostty-vt.** `start`, `install`, and `restart` verify
  `vendor/libghostty-vt` (`scripts/build-libghostty-vt --verify`) and run
  `scripts/build-libghostty-vt` when it is missing or stale, as CI does. In a
  fresh worktree the first `start` therefore spends about a minute on that
  build and fetches the pinned ghostty commit.
- **Commands.** `tree` prints one JSON array of the nodes that have a test tag
  (`id`), text, or description, with `bounds` and `center` in screen pixels;
  `find TAG` filters by exact tag and `click TAG [N]` taps the centre of the Nth
  match. `type` and `key` go to the focused view (tap the terminal first).
  `net-drop` and `net-restore` cut and restore the bridge and the `adb reverse`;
  `host-stop` and `host-start` stop and restart the daemon with the same
  directories. `health` sends one `daemon.health` line from the device with
  toybox `nc` through the reversed port and prints the reply.
- **Session list.** `MainActivity` opens the Hosts screen with the host form on a fresh install (no host
  configured): tap `HostForm.route`, `type 127.0.0.1:PORT` (PORT from `status`), optionally tap
  `HostForm.label` and type a name, then `click HostForm.save`; saving the first host returns to the list.
  `SessionList.hosts` (and `SessionList.addHost` on an empty list) opens the Hosts screen: `Hosts.row` per
  host (tap to edit), `Hosts.status` with its connection (Connected, Connecting, `Reconnecting (attempt N):
  reason`, `Connection refused (code): reason`), `Hosts.add`, `Hosts.back`. The form shows `HostForm.error`
  for a refused route or a duplicate route; `HostForm.cancel` (when a host exists) and `HostForm.clear`
  (remove the host being edited; removing the last host reopens the empty form) appear. A route is listed
  once: for the same daemon twice use `localhost:PORT` next to `127.0.0.1:PORT`. The list shows
  `SessionList.row` nodes from every host (the row text is the title, then `<owner state> · <agent>`, then
  the runtime detail of a conflict or incompatible runtime, then `host unreachable` when its host is down)
  with the host's label, else its route, in `SessionList.host`, and follows the daemons without a tap
  (subscribe, periodic re-seed, reconnect with backoff, one loop per host). Sessions that need the owner on
  a connected host are under the `SessionList.needsYou` header, oldest wait first, with `SessionList.others`
  above the rest. The rows of a host that is not connected stay, dimmed, never count in Needs you, and a tap
  opens nothing and shows `SessionList.refusal`; one `SessionList.status` line per such host names it, the
  attempt and the reason (`<label>: Reconnecting (attempt N): reason`, `<label>: Connection refused (code):
  reason` for a daemon refusal such as `version_mismatch`). Recovery can take up to the current backoff, so
  wait up to `reconnect_max_ms` after `net-restore` or `host-start`. Pull-to-refresh and
  `SessionList.refresh` ask every host for a re-seed, `SessionList.empty` and `SessionList.loading` carry the
  other states, each row has `SessionList.rowWork` (project, Work badge and role) and, on a connected host, `SessionRow.more`, which, like a long press on the row, opens the action sheet `RowSheet` (`RowSheet.title`, `RowSheet.openTerminal`, `RowSheet.details`, `RowSheet.openLink` when the session has an openable link; the sheet is a separate window, so `tree` lists it only while it is open), `RowSheet.details` opens `SessionDetails.list` full screen (`SessionDetails.title`, `SessionDetails.back`,
  `SessionDetails.copyAll`, and per field `SessionDetails.value.KEY` and `SessionDetails.copy.KEY`, KEY being `session_id`, `host`,
  `cwd`, `work_link`, ...; scroll with `swipe SessionDetails.list up`); it follows the daemon live and shows
  `SessionDetails.removed` once the session is gone, and `ui-agent clipboard` reads what Copy put on the clipboard, and `ConfigError.message` replaces the app when a value in
  `app/src/main/res/values/config.xml` is invalid. The app log
  (`scripts/ui-agent app-file cat logs/app.log`) records `host.added`, `host.edited` and `host.removed` with
  the host key, and `live.owner_state` with the session id and the previous and new owner state each time a
  session changes state, which is how a `pohunek notification create|delete` is observed without waiting for
  a re-seed. The loops run
  only while the app is visible: after `background` (the process lifecycle stops about 700 ms
  later) `sockets` shows no ESTABLISHED connection to the daemon, and `foreground` reconnects and
  re-seeds. The saved hosts survive
  `restart`. `ui-agent` has no way to clear a text field yet: delete characters
  with `podman exec pohunek-ui-agent-slot-N adb -s SERIAL shell input keyevent
  KEYCODE_DEL` (SERIAL from `status`).
- **Gestures and device state.** These commands work only in isolated emulator
  runs and refuse `--device phone` and `--owner-daemon` runs with a message.
  They address elements by test tag like `click`; `android:id/content` (the
  whole screen) is a tag too, for scrolling a list that has none of its own.
  `swipe TAG DIR` moves the finger across the middle half of the element in
  that direction (`swipe ... down` on a list is pull-to-refresh, `up` scrolls
  down). `long-press TAG [N]` holds the Nth match. `drag TAG [N] --to TAG [M]
  [--after]` long-presses the source, moves in steps, sends a last move event
  at the drop point, then releases, so the drop always registers; the drop
  point is the target's centre, or three quarters down its height with
  `--after`. Hold time, step count and step pause are `gesture_*` keys of
  `config/dev.toml`. `background` presses home and `foreground` brings the
  app's task back by its launcher intent; both print the app's pid, and
  `foreground` fails if the process changed. `clipboard` prints the device
  clipboard text through a debug-only broadcast receiver (the app must be in
  the foreground: Android lets only the focused app read the clipboard).
  `app-file cat|put|rm PATH` reads (stdout), writes (stdin) or removes a file
  below the app's private `files/` directory through `run-as` (debug build
  only; PATH is relative with plain components). `sockets` prints the app's
  TCP connections in the device as `STATE LOCAL REMOTE` lines, read from
  `/proc/net/tcp*` by the app's uid. `pohunek ARGS...` runs the Pohunek CLI
  of the slot against the isolated daemon (`session new|rename|stop|rm`, ...);
  `--host` is refused. The CLI of v0.35.2 has no `notifications create`, so
  `pohunek notification create --title T [--body B] [--session ID] [--kind K]
  [--severity S] [--provider P] [--dedupe-key KEY]` sends `notification.create`
  on the daemon socket (default kind `agent_blocked`, severity
  `action_required`, provider `ui-agent`; a session-scoped create with a hooked
  provider such as `claude` is refused by the daemon), and `pohunek
  notification delete|list|read|ack|archive ...` is the CLI's `notifications`
  group. The debug build's `GestureDemoActivity` (open it with `am start -n
  dev.pohunek.companion/.debug.GestureDemoActivity`) is a scratch list for
  `drag`: rows `GestureDemo.row`, the current order in `GestureDemo.order`,
  and `GestureDemo.copy` puts that order on the clipboard.
- **Terminal.** Tapping a `SessionList.row` of a running session opens `Terminal` full
  screen: `Terminal.view` is the terminal (tap it before `type` or `key`), `Terminal.status`
  and `Terminal.error` (with `Terminal.back`) float over it while connecting, while
  reconnecting (`Terminal.status` reads "Reconnecting (attempt N)", `Terminal.back` leaves)
  or after a final failure. After `net-drop` the status appears within the liveness bound and
  `net-restore` re-attaches on its own, within `reattach_max_ms`; `host-stop` loses the
  session's runtime in the isolated daemon (its workers are children of the daemon), so the
  terminal ends with a final message there; `kill -STOP`/`-CONT` of `pohunekd` in the slot
  container freezes the daemon without losing the sessions. System back detaches and returns to the list (the daemon records
  `attach_closed`; press it twice while the soft keyboard is open, the first press hides
  it, and never past the list, which leaves the app). A stopped, done, failed, external, or
  starting session opens nothing and shows `SessionList.refusal` (`SessionList.refusalDismiss`
  clears it). `screen` and `session-screen ID` work on it (see "Screen dumps"), and
  rotation resizes the PTY in place.
- **Screen dumps.** `screen` writes the visible grid of the open `Terminal` as text through
  a debug-only broadcast receiver (absent from release builds) and `session-screen ID` prints
  the isolated daemon's rendering of the same session; both are trimmed of trailing whitespace
  and blank lines, so `diff` compares them, and `session-screen ID --size` prints the PTY size
  as `COLSxROWS`. `screen` fails with a message when no terminal is open. It can print session
  content: on `--owner-daemon` or `--device phone` runs it is not publishable. Rotation is
  handled in place and sends `session.resize`. The debug build's `TerminalDemoActivity`
  (`am start -n dev.pohunek.companion/.debug.TerminalDemoActivity`) shows a terminal on a local
  demo session without a daemon.
- **Phone.** `--device phone` uses the adb Wi-Fi address in
  `UI_AGENT_PHONE_ADB=HOST:PORT` instead of an emulator and never changes the
  phone's settings. Pair it once with `scripts/dev --android-home adb pair
  HOST:PAIRING_PORT` (the key persists in the Android home volume). With
  `--owner-daemon` no daemon or bridge starts and the app uses the daemons it is
  configured for, which an emulator cannot reach: use it with `--device phone`.
  Both are exercised only by the owner (#16).
- **Cleanup.** After `stop`, `podman ps -a` shows no `pohunek-ui-agent-slot-*`
  container and `pgrep -af 'qemu|pohunekd|socat'` shows nothing of the run.
  The AVD volume is shared and kept; remove it with `podman volume rm
  pohunek-companion-android-avd` when no slot is running.

### Protocol client check

`:protocol` has a hand-run check (`protocol/src/manual`, not part of the suite)
that sends `daemon.health` and `session.list` through the real client. The
bridge listens on the loopback of the run's own slot container, so the check
runs there with a classpath built by one `scripts/dev` call:

```bash
CP=$(scripts/dev ./gradlew -q :protocol:manualClasspath | sed -n 's/^MANUAL_CLASSPATH=//p')
podman exec -w "$PWD" pohunek-ui-agent-slot-N java -cp "$CP" \
  dev.pohunek.companion.protocol.manual.ListSessionsCheckKt 127.0.0.1:PORT CONNECT_MS REQUEST_MS
```

`PORT` is the bridge port from `scripts/ui-agent status`. The check prints ids,
agents, and states only; scan any output for paths before publishing it.

`AttachCheckKt` (same classpath and invocation, arguments `HOST:PORT SESSION_ID
CONNECT_MS REQUEST_MS`) attaches to a synthetic session with a 100x30 view, types
`echo hi`, resizes to 60x20, and detaches through the real client, printing byte
counts and the printable output.

### Measurements

Measured on the owner's machine (24 threads, 60 GiB, KVM), API 36 `google_apis`
x86_64 emulator, 720x1600, 2 cores and 2 GiB RAM, container limit 4 CPUs and
10 GiB, two slots running:

| What | Result |
|---|---|
| AVD creation, once: cold boot, settle, snapshot save | 40 s |
| Cold boot without snapshot | 16 s |
| Boot from the quick-boot snapshot (`boot_secs`) | 4 to 6 s |
| `start` end to end, two worktrees at once, warm Gradle cache | 20 s |
| `assembleDebug` incremental, one debug source file changed | 9 s (6 to 7 s when nothing changed) |
| Slot idle (terminal demo screen) | 0.06 cores, 4.3 GiB container memory |
| Slot busy (alt-screen animation, repeated taps) | 0.9 cores |

CPU is the delta of the container's cgroup `cpu.stat` over the interval, not
`podman stats` (which reports a lifetime average).

## Published evidence

The repository is public. Screenshots, logs, and PR or issue text must not show
the owner's real sessions (names, ids, working directories, Work links) or any
host name, overlay address, or token.

- `scripts/ui-agent start` (the default) runs the app against an isolated
  Pohunek daemon with synthetic sessions in its own temporary directories.
  Everything published comes from this mode.
- `--owner-daemon` and `--device phone` connect to the owner's real daemons or
  phone. Describe what was checked in words only; never attach or paste their
  screenshots, element trees, logs, or session lists. Do not tap rows or edit
  anything through them.
- Raw `tree`, `find`, and `log` output can contain session names: treat it like
  a screenshot.

## CI

`.github/workflows/ci.yml` has one job named `ci`: it builds the same container
image and runs `scripts/gates`. It is the only required status check once the
owner adds it to the `main` ruleset (agents may not change rulesets).

Keep CI fast: before adding a job or a step, measure its cost on a PR run and
state it in the PR. A slow or flaky CI is a bug: file it.
