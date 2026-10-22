# Architecture

Authoritative product and architecture document for Pohunek Companion Android.
Change it deliberately, in the same PR as the code that departs from it, and
say so in the PR. Owner decisions recorded here date from 2026-10-22.

## Product

A native Android client (Kotlin, Jetpack Compose) for remote Pohunek daemons.
It does the same job as the desktop companion
([pohunekd/pohunek-companion](https://github.com/pohunekd/pohunek-companion)):

- a session list with live state (working, needs the owner, idle, ended);
- a "Needs you" section above everything else;
- groups, views, and filters over the sessions;
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
as the desktop companion: `v0.35.2` today. Moving the pin moves both clients and
is its own issue.

- Transport: newline-delimited JSON over TCP on the overlay.
- Version: the request envelope advertises `v:{4,4}`. There are no protocol-3
  shape adapters; a `daemon/version_mismatch` answer is shown clearly.
- State: seed with `session.list` and `notification.list`, then keep current
  with one `subscribe` connection. Re-seed on every reconnect and
  periodically.
- Attach: `session.attach` returns a one-shot `stream_id` (10 s TTL). A second
  TCP connection sends `{"attach":"<id>"}\n` and from then on carries raw PTY
  bytes: the snapshot repaint arrives first, and the bytes are never assumed to
  be UTF-8. `session.resize` and `session.detach` go over the control
  connection, not the attach connection.
- Wire fixtures come from Pohunek `sdk/ts/protocol/fixtures` at the pinned tag.

## Security boundary

The security boundary is the NetBird overlay. The daemon has no authentication
of its own. Consequently the app:

- never exposes or proxies a daemon;
- logs no secrets;
- treats all session metadata as untrusted input (names, paths, titles, and
  work links are rendered as text and never interpreted).

## Modules

| Module | Content |
|---|---|
| `:app` | Application, navigation, dependency wiring, foreground service. |
| `:core:ui` | Theme and shared Compose components. |
| `:core:mvi` | State/intent/effect base types for feature view models. |
| `:core:domain` | Pure Kotlin: reconcile, needs-you, filters, layout, discovery. |
| `:core:data` | Repositories, host store, persistence. |
| `:protocol` | Pure Kotlin/JVM, no Android: wire types, framing, daemon client. |
| `:terminal` | Terminal view and the JNI bridge to libghostty-vt. |
| `:feature:*` | One module per user-facing feature. |

Dependency direction: `:feature:*` and `:app` depend on `:core:*`, `:protocol`,
and `:terminal`; `:core:domain` and `:protocol` depend on nothing Android.

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
  pinned and moves only together with the JNI layer.
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
| M0 Repository and rules | Repository, agent rules, skills, and this document. |
| M1 Toolchain and agent loop | Podman toolchain, Gradle skeleton, gates and CI, libghostty-vt build, terminal module, ui-agent with emulator slots and an isolated daemon. |
| M2 Terminal over NetBird | Protocol client and terminal attach, verified on the owner's phone against a real daemon. Go/no-go for libghostty-vt. |
| M3 Session list parity | Hosts, live list, needs-you, filters, views, actions, new session, assistant, groups. |
| M4 Background notifications | Foreground service monitoring and needs-you notifications within Android background limits. |
| M5 Release | Signed release APKs on GitHub releases with checksums and attestation. |
