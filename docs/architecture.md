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
- Owner state: the store derives, per session, one of Needs owner, Working, Idle,
  Runtime problem, Ended, and No agent state, with the rules of the desktop's
  `owner_state.rs`. `agent_state`, `subagent_state` and `background_state` apply only
  when the event's runtime matches the snapshot and its revision is newer for the same
  (epoch, runtime); the attention set holds the unread or read `agent_blocked` and
  `approval_required` records that the seed lists (`notification.list` per kind, paged
  by `notification_page_size`, halved on `notification_list_too_large`) and the
  `notification_*` events keep current. A session idle without running work becomes Idle
  after `idle_threshold_secs`; the live loop wakes at that deadline, so no event is needed.
- Store lifetime (departs from the desktop on purpose): the desktop builds a new store per connection. Here the registry keeps one
  store per followed route across reconnects, loop restarts and foreground returns, and drops it when the route is no longer
  followed. A seed on a new connection reconciles into it like a re-seed: idle clocks and activity baselines of unchanged sessions
  stay, a session on a different worker instance or runtime generation starts its idle clock again, and a list equal to the last
  one published is not published again. `idle_threshold_secs` is read from the configuration in force each time a loop starts.
- Last known sessions (departs from the desktop on purpose; decided on #160): the list of each configured host is written to
  `last_sessions.json` (app-private files, `AtomicFile`) whenever the host publishes a changed list, and the entry is dropped when the
  host is no longer configured. Discovered peers are not stored. The file is excluded from cloud backup and device transfer
  (`data_extraction_rules.xml` excludes the whole `file` domain; `allowBackup` is off). After a cold start the host's feed holds the
  stored rows as `lastKnown`, separate from `sessions`: the rows are shown dimmed (link `Connecting`), are not under the grace rule,
  never count in Needs you, and offer no action, until the host's first seed replaces them. If the host does not answer the link is
  `Down` at once, with the unreachable text. A file that cannot be read or parsed is ignored, logged as `last_sessions.unreadable`
  (no content), and replaced by the next published list. Log lines about the file carry a host key and an error code only.
- Liveness (departs from the desktop on purpose): the desktop keeps its store
  when a periodic re-seed fails and has no heartbeat, which on a phone leaves a
  frozen list that looks live. Here a `daemon.health` probe (own connection, every `liveness_interval_ms`, bounded by
  `liveness_timeout_ms`) and a periodic re-seed are checks, and `liveness_failures` (default 2) failed checks in a row end the
  connection; the loop then reconnects with backoff. A failed check is logged (`live.probe_failed`, `live.reseed_failed`, with the
  count and the error code), the next probe or re-seed follows after `liveness_retry_ms` (default 2 s), and a successful probe or
  re-seed resets the count. A broken subscription stream still ends the connection at once. A dead path is noticed within
  `liveness_interval_ms + liveness_failures × liveness_timeout_ms + (liveness_failures − 1) × liveness_retry_ms` (22 s by
  default). The probe detects a dead network path; it does not look at the subscription socket itself, and the daemon does not answer
  requests on a subscribed connection. A subscription that died while new connections still work is noticed by the periodic re-seed
  instead: a re-seed that changes the store with no buffered event to explain it (`live.reseed_corrected`) makes the connection
  suspect, and it ends if no event arrives within `liveness_retry_ms`. An event still in flight on a healthy stream arrives in that
  window and clears the suspicion. A dead socket is thus replaced within `reseed_interval_secs + liveness_retry_ms` of the first
  change it missed.
- Backoff reset (departs from the desktop on purpose): the desktop resets the failure count only after a connection held
  `reconnect_max`, so on a link that drops briefly every half minute the wait ratchets to the cap while the rows stay dimmed. Here a
  connection that was seeded and held at least `backoff_reset_ms` (default 5 s, never above `reconnect_max_ms`) resets it; failed
  attempts in a row, and connections that drop sooner, still double the wait up to `reconnect_max_ms`.
- Network events (departs from the desktop on purpose): while the loops run, a `ConnectivityManager` network callback (a request
  with `NET_CAPABILITY_INTERNET` and without `NOT_VPN`, so a physical network and a VPN network such as NetBird's both match) reports a
  network that becomes `VALIDATED`. Every loop that is waiting out a backoff then attempts at once, one attempt per event; a connected
  loop or one mid-attempt ignores it, no re-seed runs, and the failure count is kept. The callback is unregistered with the loops.
- Lifecycle (until M4's foreground service): the live loops run only while the
  app is visible (`ProcessLifecycleOwner` started) and stop when it goes to the
  background, which closes their sockets. On return they reconnect and re-seed
  at once; the hosts keep looking connected for `unreachable_grace_ms` meanwhile,
  so a quick app switch is invisible. M4 replaces this with the foreground service where background
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
- Re-attach: a failed or broken attachment is redone whole (`session.attach`,
  then the prelude, because the stream id is one-shot) with a doubling wait
  (`reattach_initial_ms` up to `reattach_max_ms`), the terminal is reset and
  repainted from the snapshot, and input typed meanwhile is discarded. Retrying
  ends with a final error when the daemon reports the session not found, not
  running, external, or its runtime lost (`session.resume` is the recovery for
  those), when the route or protocol version is unusable, or after
  `reattach_give_up_ms` without an attachment.
- Session actions: Stop and Remove each open a connection of their own (`session.stop`, `session.remove`, and
  `session.remove_accepting_unconfirmed` only after the owner confirmed that one call), whose request timeout is
  `session_action_timeout_ms`; a removal waits for the daemon to end processes and delete a checkout. Offers follow the desktop's
  `offer_stop` and `offer_remove`. Nothing is updated optimistically: the row changes when the daemon's `session_stopped` or
  `session_removed` event arrives. A timeout or a connection that broke after the request left is reported as an unknown outcome.
- New session: `NewSessions` reads a host's offer over a connection of its own (`project.list`, whose answer is shown at once, then
  `host.inspect` for the agents; an agent is launchable when `available` and `supported != false`) and starts a session with exactly one
  `session.new` over another, whose request timeout is `create_timeout_ms` (the cols and rows are `new_session_cols` and `new_session_rows`).
  `session.new` has no idempotency key, so the request is never resent: only a failure to open the connection proves it did not leave,
  a daemon error whose code the daemon documents as raised before anything is recorded is a refusal and leaves the form editable, any
  other daemon error (the daemon can fail after it recorded the session) and any other failure after the request left is unconfirmed,
  which disables Create on that form. A reply whose
  `applied_input` is not true after a prompt was sent leaves the session and disables Create too. A session started from a row's
  "New session here" is created in that row's group: the form resolves the row's group id to its name from the layout and, for a
  group other than Inbox, sends `{"companion.group": "<name>"}` as the `metadata` of the `session.new`, so every client sees the
  session in the group and the phone keeps no pending placement of its own. An unknown group id (deleted since), Inbox, or a name
  that is not a valid group value sends no metadata, and the session starts unplaced. A fork needs no handling: the daemon
  copies the source's metadata, so a fork lands in its parent's group.
  Templates are the selected project's actions with provider `none` (`project.actions`, read when the host and project are known, over a
  connection of its own); choosing one reads its recipe (`project.action`) and fills agent and prompt, and its `branch` and `base_branch`
  go into the request. A prompt containing `${` (only static templates are supported), a recipe that cannot be read, or an agent the host
  cannot launch leaves the form on Blank with the reason shown. A Blank session may carry a branch and a base branch (Advanced); a branch
  or base branch is blocked when `host.inspect` reports no `git_available` or no `worktree_supported`, and a base branch without a branch
  is blocked because the daemon refuses it (`session.new base_branch requires branch`). The daemon's errors for a bad branch (`invalid_branch`,
  `worktree_*`, `not_a_git_repo`) are not documented as raised before anything is recorded, so they stay unconfirmed outcomes.
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

- **Tunables** live in `app/src/main/res/values/config.xml`: each key is an
  integer default with a `<key>_help` string next to it. The rules (minimum,
  upper bound, a longest wait not below the first) are pure logic in
  `:core:domain` (`Tunable`, `checkOverrides`) and are shared by the start-up
  check and the Settings form. An invalid default shows a configuration error
  screen that names the key. A new key is added to the file, to `Tunable` and to
  `TunableResources`.
- **Overrides** are the values the owner changed on the Settings screen, stored
  in `settings.json` (only values that differ from the default, so a changed
  default still reaches the rest). `RuntimeConfig` merges them over the defaults
  and consumers read the value in force when they use it. Save checks the whole
  set, writes it and restarts the live loops, so a change applies at once to the
  connections; attach settings apply to the next terminal opened. Overrides that
  stop validating, or an unreadable file, show the configuration error screen
  with an action that discards them.
- **App data** (hosts, layout and settings) is JSON written with
  kotlinx-serialization through `AtomicFile` in the app's files directory. An
  unreadable file is shown as an error and never overwritten.
- **Layout** (`layout.json`, schema version 2: the desktop's layout schema with its field names, plus `migrated_hosts`; a version-1 file loads as unmigrated and an older build shows a version-2 file as invalid and keeps it) holds the owner's groups, the
  placement of each session by `(route, session id)` and the collapsed state. Group membership is shared with the desktop
  companion through the session metadata key `companion.group`, whose value is the group's display name; group order, session
  order, collapse, views and filters stay on the phone and never go into metadata. A value is trimmed and is unusable (treated as
  absent, one warn line with route, session id and reason, never the value) when it is empty, longer than `group_name_max_bytes`
  UTF-8 bytes or holds a control or bidirectional-format character (`GroupKey.kt`). Names merge ignoring case across all sessions of all hosts
  and `Inbox` means Inbox. A session is placed by, in order: a usable key; else its local manual placement in `layout.json` (a
  session that cannot carry the key); else Inbox. A usable key clears the item's `manual` flag, so a key that another client removes
  sends the session to Inbox rather than back to a stale local group. A key naming a group the phone lacks appends a learned group
  (`"origin": "learned"` in the file, absent for created groups); a learned group with no member is dropped once every host has sent a
  list, a created group stays until the owner deletes it. A session arriving in a group goes to its end. `Layout.applyGroupValues`
  does this on plain values after each reconcile, and the next `session_updated` or re-seed corrects the placement, since the
  list queues a host's list again whenever its ids, group values or external flags change. The layout lives in a process-wide `LayoutRepository`: every list a connected host sends is reconciled into it for that host
  only (new sessions join the end of Inbox, that host's missing ones leave), and it is saved only when its content
  changed. A host that is connecting or down never prunes. A file that cannot be read or is invalid is shown as an error
  above the list and kept as it is; the app then runs with an unsaved Inbox-only layout. A chip row selects the view the list
  shows, stored in the file: My groups (the owner's groups, editable), By state, By project and By Work item. The last three
  are read-only regroupings of the same sessions (ported from desktop `view.rs`) that keep the My groups order inside a
  section and keep Needs you first; their collapsed sections are stored per view in `collapsed_sections` and ids no
  section has any more are dropped once every host has sent a list. A By state session that waits for the owner on a host
  that is not connected is not in Needs you, so it is shown in a trailing "Waiting (host not connected)" section. The owner creates, renames and
  deletes groups (a deletion has no confirmation and moves the sessions to the end of Inbox in their order; Inbox has
  neither) and moves a session to the end of a group from its action sheet, also into a new group. Create, reorder and
  collapse stay local. Reordering is a long press on a session row or a group header of My groups, dragged to a drop
  indicator (a session lands before or after a row or at the end of a group, a group before or after another group), or
  "Move up" / "Move down" in the row and group sheets for the same moves without a drag. The rest write `companion.group` with `session.set_metadata` (a merge patch, one call per session, from
  `SessionCommands.setGroup`; the daemon applies it under its sessions lock, so the last write wins per key) and change the
  layout only once the daemon holds the value, so there is no optimistic membership change: a move writes the group's name
  (`Inbox` for Inbox) and then places the row; a rename writes the new name to every member session (the sessions whose key
  names the group plus the group's local placements) and renames the group here only when every write that could be made
  succeeded; a delete writes `Inbox` to every member and removes the group here under the same condition. A member on a host
  that is not connected is not written and counts as not updated; a failed or refused write leaves that session where it is,
  and the owner sees one banner (`SessionList.groupNote`): how many of how many sessions were not updated, and the first
  failure's host and reason. Repeating the rename or delete writes only to the sessions that still carry the old name; a
  member that already got the new name shows under a learned group of that name, which the rename merges into the renamed
  group (a created group of that name refuses it). There is no retry queue. A name is written only when it is trimmed,
  non-empty, at most `group_name_max_bytes` UTF-8 bytes and free of control and bidirectional-format characters, and the same
  rules refuse a group name in the dialog. A session that cannot carry the key (`external`, or refused with
  `session_external_read_only`, a runtime that is not launchable on its host, or `method_not_found` from an older daemon)
  gets no write (an external session is never sent one); it keeps its local placement and is added to the in-memory
  `LayoutRepository.notShared` set. The list marks such a session, and every external one, with an "Only on this phone" label
  next to the Work badge (read by TalkBack as "This session's group is kept only on this phone") and the details screen shows a
  "Group: only on this phone" line; a session that gets a usable key leaves the set and loses both. The manual placements already in `layout.json` are migrated once per host route, after
  that host's first full list: each manual item outside Inbox whose session is listed, not external and without any
  `companion.group` key gets its group's name written (a present key is never overwritten, manual Inbox items need no
  write). A refused session is marked not shared and not retried; the route goes into `migrated_hosts` only when no write
  failed, and a failed write is tried again when the host reconnects or its list changes. Each edit is one
  `LayoutRepository` call that is applied, saved and logged by group and session id, or refused with nothing changed.
  A chip row of owner states and a search field in the top app bar narrow the list in every view (ported from desktop
  `filter.rs`): the states are stored per view in the layout file's `filters` (the default, every state but Ended, is stored
  as no entry, and Problem is re-added to a stored selection on load), the query is kept only in memory. A query splits on
  whitespace and every token must match a name, project label, agent or Work badge as a subsequence, or a session id or Work
  id as a substring. With a query, sections without a match are dropped and the rest shown expanded; with only states,
  sections the selection emptied are dropped. A chip row of projects (All, labels ordered ignoring case, No project while some
  session has none) and, with two or more hosts, a row of hosts (All hosts, each with connected or unreachable) narrow the
  list as well; they are stored per view in the same `filters` entry (`project_label`, `no_project`, `host`), a tap on the
  selected chip clears it, and a selected project or host that has gone keeps its chip and filters as empty. Like a query,
  they expand the sections that match.
- **Hosts** are a list of `(route, optional label)` in `hosts.json`; a route is
  listed once, so the same daemon under two spellings of its address (such as
  `127.0.0.1:PORT` and `localhost:PORT`) counts as two hosts. One live loop
  per host runs in a process-wide registry (`HostRegistry`); a loop never
  touches another host's loop or rows. The list is the union of the hosts'
  latest sessions keyed by host and session id. The rows of a host that has been
  without a connection for `unreachable_grace_ms` stay, dimmed, with their last
  known facts; until then (a loss, or a loop start that keeps the last list) the
  host looks connected: rows undimmed, Needs you unchanged, no status line, a tap
  opens the terminal, and writes that need the daemon still require a connected
  host and report their own failure. A daemon refusal with a code, and a host
  that discovery no longer reports, are shown at once. The desktop marks a host
  down only after a reconnect update, never while it first connects; the grace
  period is the nearest equivalent that still shows a long outage.
- **Discovered hosts** join the configured ones without being stored. While the app is
  visible, every connected configured host is asked for `host.discover {force:false}`
  (the request keeps `v:{4,4}`, so a protocol-3 peer comes back as `version_mismatch`)
  when it connects and every `discover_interval_secs`, each question with its own
  connection and `discover_timeout_secs`. Every `reachable_daemon` peer with an IP
  `address` is followed at `address:port` without confirmation (the owner's choice for
  the desktop, pohunek-companion #139); `version_mismatch` peers are listed as incompatible,
  `unreachable` and `candidate` ones are ignored. A configured host naming the peer (its
  host part equals the peer's key, address, peer id, fqdn or name) keeps its entry and the
  peer is not followed twice; the address is compared too because the owner types the
  address of a host on the phone. A peer that leaves a successful answer stops its loop and
  keeps its rows, dimmed. A failed question keeps what the host's last answer listed and is
  shown on the Hosts screen, logged once per distinct cause.

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
| `:core:domain` | Pure Kotlin: reconcile, needs-you, filters, layout, discovery plan. |
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
