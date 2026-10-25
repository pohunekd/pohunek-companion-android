# AGENTS.md

Canonical entry point for every agent working in this repository. Keep it
short: it routes to the scoped guides instead of repeating them. When a command,
convention, or boundary changes, update the guide that owns it in the same PR.

All repository text, code, comments, commit messages, issues, and PRs are in
English. Priorities: security > correctness > simplicity > speed of delivery.

## About

Pohunek Companion Android is a native Android app (Kotlin, Jetpack Compose) for
the owner of remote [Pohunek](https://github.com/zajca/pohunek) daemons: a
session list with live state, and an embedded terminal that attaches to a
session. It reaches the daemons over the NetBird overlay. Nothing runs on the
phone except this app and the official NetBird app. It is an external client of
the Pohunek protocol and owns no session state. The desktop companion
([pohunekd/pohunek-companion](https://github.com/pohunekd/pohunek-companion))
is the source of the ported client logic. See
[docs/architecture.md](docs/architecture.md).

The app is expected to change quickly. Optimize for small, fast, reversible
changes: little ceremony, few tests, a fast CI.

## Instruction routes

Read a guide when its trigger applies.

- **Every task:** [.agents/instructions/workflow.md](.agents/instructions/workflow.md)
  for autonomy, issues, branches, PRs, merge, and the rule to file problems
  instead of fixing them.
- **Any code change:** [.agents/instructions/coding.md](.agents/instructions/coding.md)
  for Kotlin, Compose, the Pohunek protocol boundary, configuration, security,
  copied third-party code, and comment rules.
- **Before any commit or PR:** [.agents/instructions/verification.md](.agents/instructions/verification.md)
  for the gate set, the testing policy, CI, and evidence.
- **Architecture or product decisions:** [docs/architecture.md](docs/architecture.md)
  is authoritative; revise it explicitly in the same PR when a decision changes.

## Skills

Canonical skills live in `.agents/skills/`; `.claude/skills/` contains only
links to them. Edit only `.agents/skills/`.

- [github-workflow](.agents/skills/github-workflow/SKILL.md): issues, epics,
  milestones, project status, and writing reproducible issues.
- [deliver-issue](.agents/skills/deliver-issue/SKILL.md): take one issue from
  intake to a merged, verified, closed result without asking.
- [plan-epic](.agents/skills/plan-epic/SKILL.md): split an epic or milestone
  into ready, well-formed issues.
- [gates](.agents/skills/gates/SKILL.md): run the local gate set and report it
  honestly.

## Commands

The toolchain runs in a rootless Podman container; nothing of it is installed on
the host. `scripts/dev <command...>` runs a command in it from the current
worktree (image pinned in `container/Containerfile`, built on first use; CPU and
memory limits in `config/dev.toml`; `--env NAME` and `--ro-dir DIR` pass a named variable and a read-only directory in). The container sees the whole worktree
(including ignored files) but not its git metadata, so run git on the host; the
SDK and tools under `/opt` are read-only.

```bash
scripts/dev ./gradlew :app:assembleDebug   # any command in the toolchain container
scripts/build-libghostty-vt                # libghostty-vt.a for arm64-v8a and x86_64 into vendor/libghostty-vt/ (--verify checks it)
scripts/gates                              # Spotless, detekt, lint, unit tests, assembleDebug: the set CI runs
scripts/ui-agent start                     # emulator + app + isolated daemon; then screenshot, tree, click, type, key, screen, stop (see verification.md)
scripts/check-screenshots                  # docs/screenshots matches its manifest (the release workflow runs it)
```

The repository is public: published screenshots, logs, and PR or issue text
come only from the isolated mode of `scripts/ui-agent` (synthetic sessions on
its own daemon). Checks against the owner's daemon or phone are described in
words only; see "Published evidence" in verification.md.

Never read `.env*`, key, or credential files, and never print a secret value.
Never call `env` or `printenv` (the owner's agent settings stop on them):
check a variable with `test -n "$VAR"` and list names with `compgen -e`.
