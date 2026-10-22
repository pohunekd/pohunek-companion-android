# Verification

## Gate set

`scripts/dev` exists (#9). The gate set and `scripts/ui-agent` land in M1 (#10, #13). Until then a PR
reports "no gates yet" and is verified by its DoD evidence and the independent
self-review. This section describes the set as it will exist.

`scripts/gates` runs through `scripts/dev` (the toolchain container), in order,
each with its own exit code:

1. Spotless (ktlint) check
2. detekt
3. Android lint
4. unit tests (`maxParallelForks` from `config/dev.toml`)
5. `assembleDebug`

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
against an isolated Pohunek daemon through `scripts/ui-agent` (lands in M1,
#13; before that, a manual `adb` run is acceptable). The PR records what was
done and observed, with a screenshot for visible changes. Behaviour that needs
the owner's phone (NetBird, real network conditions) is left to an owner-run
milestone check described on the issue; it is reported as unverified until run.

Agent sessions share the owner's machine. Never touch the owner's Pohunek
runtime directory or real daemon, never run the toolchain on the host outside
`scripts/dev`, and never point an emulator at the owner's daemon.

```bash
scripts/ui-agent start [--sessions N] [--showcase] [--device phone] [--owner-daemon]
scripts/ui-agent install | restart | stop | status
scripts/ui-agent screenshot OUT.png | tree | find TAG | click TAG [N]
scripts/ui-agent type TEXT [--enter] | key esc|ctrl-c|tab|up|down|left|right|enter
scripts/ui-agent rotate | net-drop | net-restore | host-stop | host-start
scripts/ui-agent log
```

Each run takes one slot (a `flock` on the host, one `scripts/dev` container with
an emulator and an isolated daemon behind a `socat` bridge); worktrees run in
parallel on different slots. Always `stop` when done. The exact semantics are in
#13 and are documented here when it lands.

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

`.github/workflows/ci.yml` lands with #10: one job named `ci` builds the same
container image and runs `scripts/gates`. It is the only required status check
once the owner adds it to the `main` ruleset (agents may not change rulesets).

Keep CI fast: before adding a job or a step, measure its cost on a PR run and
state it in the PR. A slow or flaky CI is a bug: file it.
