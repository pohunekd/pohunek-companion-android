# Architecture

Authoritative product and architecture document for Pohunek Companion Android.
Change it deliberately, in the same PR as the code that departs from it, and
say so in the PR. Owner decisions recorded here date from 2026-10-22.

## Product

A native Android client (Kotlin, Jetpack Compose) for remote Pohunek daemons.
It does the same job as the desktop companion
([pohunekd/pohunek-companion](https://github.com/pohunekd/pohunek-companion)):

- a session list with live state;
- a "Needs you" section;
- groups and filters;
- session actions and a new-session flow;
- the assistant;
- multiple hosts.

On top of that it has an **embedded terminal** that attaches to a session and
controls it. The desktop companion lists an embedded terminal as a non-goal;
here it is a goal.

Nothing runs on the phone except this app and the official NetBird app.

Non-goals: a local daemon, a tray, a desktop entry, a setup or install screen,
Open folder, iOS (out of scope for now).

## Platform

- applicationId `dev.pohunek.companion`.
- minSdk 33 (Android 13).
- compileSdk and targetSdk: the latest stable release at M1.

## Protocol boundary

The app speaks the Pohunek public protocol only, pinned to the same Pohunek tag
as the desktop companion: `v0.35.2` today.

- Transport: newline-delimited JSON over TCP on the overlay.
- Version: the request envelope advertises `v:{4,4}`. There are no protocol-3
  shape adapters; a `daemon/version_mismatch` answer is shown clearly.
- State: seed with `session.list` and `notification.list`, then keep current
  with one `subscribe` connection. Re-seed on every reconnect and
  periodically.
- Liveness (departs from the desktop on purpose): the desktop keeps its store
  when a periodic re-seed fails and has no heartbeat, which on a phone leaves a
  frozen list that looks live. Here a failed re-seed, or a `daemon.health` probe
  (own connection, every `liveness_interval_ms`, bounded by
  `liveness_timeout_ms`) that fails, ends the connection and the loop reconnects
  with backoff. The probe detects a dead network path; it does not look at the
  subscription socket itself.
- Lifecycle (until M4's foreground service): the live loops run only while the
  app is visible (`ProcessLifecycleOwner` started) and stop when it goes to the
  background, which closes their sockets. On return they reconnect and re-seed
  at once. M4 replaces this with the foreground service where background
  monitoring is wanted.
- Attach: `session.attach` returns a one-shot `stream_id` (10 s TTL). A second
  TCP connection sends `{"attach":"<id>"}\n` and from then on carries raw PTY
  bytes: the snapshot repaint arrives first, and the bytes are never assumed to
  be UTF-8. `session.resize` and `session.detach` go over the control
  connection, not the attach connection. The attach is idle most of the time, so
  a connection that died without FIN or RST would show a frozen screen: while
  attached, a `daemon.health` probe on the control connection runs every
  `attach_liveness_interval_ms` and must answer within `attach_liveness_timeout_ms`, else the
  attach fails with a typed liveness error (at most the sum of both after the
  connection died).
- Wire fixtures come from two sources: Pohunek `sdk/ts/protocol/fixtures` at the
  pinned tag (`protocol/src/test/resources/fixtures/`), and, where that
  directory has no fixture for a method or event the app uses, captures from
  the isolated `pohunekd` of the same tag that `scripts/ui-agent` runs
  (`protocol/src/test/resources/captured/`, with the capture command and the
  list of payloads built from captured ones in its README).
- Events: a `subscribe` line is decoded into a sealed `DaemonEvent`; an event
  name the app does not model decodes to `Unknown` and is ignored, so a daemon
  that adds events never breaks the stream.

## App data and configuration

- **Tunables** live in `app/src/main/res/values/config.xml`, each key documented
  next to its value, and are read once at start-up into a validated `AppConfig`;
  an invalid value shows a configuration error screen that names the key. Later
  issues add their keys to the same file.
- **App data** (hosts now; layout and settings later) is JSON written with
  kotlinx-serialization through `AtomicFile` in the app's files directory. An
  unreadable file is shown as an error and never overwritten.

## Security boundary

The security boundary is the NetBird overlay. The daemon has no authentication
of its own. Consequently the app:

- never exposes or proxies a daemon;
- logs no secrets;
- treats all session metadata as untrusted input.

## Modules

| Module | Content |
|---|---|
| `:app` | Application shell. |
| `:core:ui` | Shared UI. |
| `:core:mvi` | MVI base types. |
| `:core:domain` | Pure Kotlin: reconcile, needs-you, filters, layout, discovery. |
| `:core:data` | Data layer. |
| `:protocol` | Pure Kotlin/JVM, no Android. |
| `:terminal` | Terminal view and the JNI bridge to libghostty-vt. |
| `:feature:*` | Feature modules. |

## Copied sources

Code is copied with attribution, not forked. Each copied file keeps its header.
`THIRD_PARTY_NOTICES.md` lists every file taken; the issue that copies a file
updates its entry.

- **expo-libghostty** (MIT), `https://github.com/arcboxlabs/expo-libghostty`
  at `f53920f2bc6945706cff21abf21fd55dd333dc87`, `android/` only:
  `GhosttyTerminalView.kt`, `GhosttyVt.kt`, `SnapshotFormat.kt`,
  `StickyModifier.kt`, `TerminalAccessoryBar.kt`, `cpp/ghostty_jni.cpp`,
  `CMakeLists.txt`. The Expo glue (`ExpoLibghosttyModule.kt`,
  `ExpoLibghosttyView.kt`) is not copied.
- **ghostty** (MIT), `https://github.com/ghostty-org/ghostty`: libghostty-vt
  built from source at commit `b0947378349eff70f7030dda0e6d022fae1e6fbd`,
  with Zig 0.15.2 and NDK 27.1.12297006. This is the commit expo-libghostty's
  JNI targets. The libghostty-vt C ABI is not stable, so the commit is always
  pinned.
- **opencode-mobile** (MIT), `https://github.com/beppe2880/opencode-mobile`
  at `4393d5f6e9dc49b6dc593d563a7dc969695f982d`, `android/`: Gradle setup
  (version catalog, convention plugins), module skeleton, Koin wiring,
  navigation, theme, connect flow, `SessionMonitorService` (foreground service
  type `dataSync`) with its notifier and notification action receiver. Its
  OpenCode API and network layers are not copied.
- **pohunek-companion `crates/core`** (MIT): logic ported to Kotlin from
  `live.rs`, `needs_you.rs`, `filter.rs`, `layout.rs`, `discovery.rs`,
  `view.rs`, `sort_key.rs`.
- **Pohunek wire fixtures** (MIT): `sdk/ts/protocol/fixtures` at the pinned tag
  `v0.35.2`.

## Development environment

A rootless Podman container image holds the whole toolchain. `scripts/gates`
and `scripts/ui-agent` run in it from the host worktree.

- Only `/dev/kvm` is passed into the container.
- Nothing of the toolchain is installed on the host.
- Each container has cgroup limits.
- Two parallel slots by default.
- CI builds the same image.

## Verification model

- **Unit tests:** few and deterministic, for pure logic only.
- **Main check:** an agent drives the real app in an emulator against an
  isolated daemon with synthetic sessions. The daemon's TCP listener binds only
  to an overlay address, so the emulator reaches it through a `socat` bridge to
  its Unix socket plus `adb reverse`.
- **Milestone checks:** the owner's phone over adb and NetBird, reported in
  words only.

## Phases

The phases are the repository milestones:

| Milestone | Outcome |
|---|---|
| M0 Repository and rules | Repository, agent rules, skills and the architecture document adapted from pohunek-companion. |
| M1 Toolchain and agent loop | Rootless Podman toolchain container, Gradle skeleton, gates and CI, libghostty-vt build, terminal module, ui-agent with emulator slots and an isolated daemon. |
| M2 Terminal over NetBird | Protocol client and terminal attach, verified on the owner's phone against a real daemon over NetBird. Go/no-go for libghostty-vt. |
| M3 Session list parity | Hosts, live session list, needs-you, filters, views, actions, new session, assistant and groups, at parity with the desktop companion where it applies. |
| M4 Background notifications | Foreground service monitoring and needs-you notifications within Android background limits. |
| M5 Release | Signed release APKs on GitHub releases with checksums and attestation. |
