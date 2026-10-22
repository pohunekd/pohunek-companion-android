---
name: gates
description: >-
  Run the pohunek-companion-android gate set (Spotless, detekt, Android lint,
  unit tests, assembleDebug) locally and report each gate's real result. Use
  before any commit, push, or claim that a change builds or is done.
---

# gates

Gates land in M1 (#10). Until `scripts/gates` exists, report "no gates yet" in
the PR; do not substitute an ad hoc command and call it the gate set.

Once it exists, run `scripts/gates` from the repository root (or the worktree
root). It runs through `scripts/dev` (the toolchain container) and executes, in
order: Spotless (ktlint) check, detekt, Android lint, unit tests, and
`assembleDebug`. It records each exit code, prints a per-gate summary, and exits
non-zero if any gate failed.

- Read the summary, not a piped tail of the output: report each gate as
  `passed` or `failed (exit N)` exactly as printed.
- A formatting failure is fixed with the Spotless apply task through
  `scripts/dev`; then rerun the gates.
- Never mark a gate passed that did not run, and never weaken a lint, detekt
  rule, or test to get green.
