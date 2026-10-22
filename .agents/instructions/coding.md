# Coding rules

## Boundaries

- The app is an external client of the Pohunek daemon. It talks to it only
  through the public protocol in `docs/public-api.md` of the pinned Pohunek tag
  (see `docs/architecture.md`). Other Pohunek internals are not a stable API: do
  not depend on them.
- The daemon owns session, herd, and notification state. The app only reads it
  and calls public methods; it never infers or caches authority.
- Layout (groups, order, filters) is client-side state in the app's own storage.
  Never write it into session metadata.
- Herd membership and Pohunek Work links (`work.link.*`, `work.role`, legacy
  `link.*`) are read-only structure for the app. A layout change never adds or
  removes a herd member or relinks a work item.
- No network calls other than to Pohunek daemons. GitHub, Linear and similar
  providers are reached only through metadata Pohunek Work already wrote, and
  only when the owner opens a link.
- The security boundary is the NetBird overlay. The daemon has no auth of its
  own: the app never exposes or proxies a daemon.

## Kotlin

- Spotless (ktlint) and detekt are the style and static-analysis gates; do not
  suppress a rule without a one-line reason next to the suppression.
- No `!!` and no unchecked casts (`as`, `@Suppress("UNCHECKED_CAST")`) on data
  from the daemon, files, or the user. Parse into typed values and return a
  typed result or error; reserve `requireNotNull` and `check` for real
  invariants and say why in the message.
- Errors are typed (sealed result or exception hierarchy) at module boundaries
  and shown to the user in the UI; never swallow an exception or replace it with
  a silent default. Rethrow `CancellationException`.
- Pure logic (reconcile, needs-you, filters, layout, discovery, protocol
  parsing) lives in `:core:domain` and `:protocol`, which are plain Kotlin/JVM
  and do not depend on Android or Compose, so they can be tested without a
  device.
- Keep UI code thin: Composables render state and send events; state holders
  (`:core:mvi`) own the logic. Time comes from an injected clock and
  coroutine dispatchers are injected, never `System.currentTimeMillis()` or
  `Dispatchers.*` inline in logic.
- Add a dependency only when it clearly pays for itself; prefer libraries
  already in the version catalog. APK size and build time are product
  requirements.

## Compose

- Design tokens (spacing, colours, sizes, typography) live in the theme, not as
  literals scattered across Composables.
- A choice among a few fixed options is a chip row, not a dropdown.
- Give every element an agent needs to target a stable `testTag`
  (`Component.id`); `scripts/ui-agent tree` lists nodes by it.

## Configuration

- Tunables (timeouts, thresholds, intervals, sizes, retry delays) come from a
  config source (build config generated from a checked-in file, or the app's
  settings storage), never constants in code. Document each key next to its
  declaration. Host-side tooling (`scripts/dev`) reads `config/dev.toml`.
- A missing or invalid required value fails at startup with a clear message
  shown in the UI; never invent a default for it. Optional values may have
  documented defaults at their declaration.

## Security

- Never log or display secrets. Session metadata is owner-controlled but
  untrusted input for rendering and for anything that builds a command, URL, or
  path from it. The terminal byte stream is untrusted too: never assume UTF-8,
  never interpret it outside the terminal emulator.
- Never read `.env*`, key, or credential files.
- Do not widen Android permissions, exported components, or cleartext network
  rules beyond what the issue needs; say why in the PR when you add one.

## Third-party code

Code copied from another project keeps its original header and licence, is
adapted in place (package, naming) only as far as the issue says, and is listed
in `THIRD_PARTY_NOTICES.md` in the same PR with repository, commit, licence, and
the files taken. The pinned sources and commits are in `docs/architecture.md`.
Logic ported from the desktop companion is attributed in the file's header or
in `THIRD_PARTY_NOTICES.md`.

## Logging

Structured JSON logs (one object per line) to `logs/` in development and to the
app's private files directory in installed builds: daemon connection lifecycle,
reconcile results, user actions (attach, detach, layout changes), and errors.
Session names, working directories, and terminal contents are not logged in
builds that can be published.

## Comments

A comment states what the code does now or why it must be this way. Never
narrate history ("previously", "no longer", "now also", "replaces ..."); that
belongs in the PR description. One or two lines; prefer the non-obvious why.
