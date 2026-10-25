Closes #

<!-- Use "Closes #N" only when this PR meets the issue's full DoD; otherwise
"Refs #N". Never write a closing keyword (close, fix, resolve) next to an
issue number anywhere else in the body, even negated: GitHub closes it. -->

## What and why

<!-- The change, the reason, and any autonomous decision taken. Before/after
narrative belongs here, not in code comments. -->

## Definition of done

| Item | Status | Evidence |
| --- | --- | --- |
| D1 | met / unmet | |

## Changelog

<!-- A user-visible change adds its fragment changelog.d/<N>-<slug>.md (see
.agents/instructions/changelog.md); otherwise the pull request carries the
no-changelog label. -->

- [ ] Fragment added, or `no-changelog` label set

## Verification

- CI: <!-- check result and run URL -->
- `scripts/gates`: Spotless passed/failed, detekt passed/failed, lint
  passed/failed, test passed/failed, assembleDebug passed/failed
- Manual: <!-- what was run and observed; screenshot for visible changes;
  anything needing the owner's phone = unverified -->

## Self-review

<!-- Reviewer (subagent/Codex), findings, and for each: fixed / filed #N /
rejected with evidence. -->

## Filed during this work

<!-- Issues filed under found-during-work, or None. -->
