# Changelog

The user-facing changelog is written by the pull request that makes the change.
That pull request adds a fragment to `changelog.d/`. A release preparation
folds the fragments into `CHANGELOG.md`, and the release publishes that version
section as its notes. The format is the ecosystem contract shared by the
pohunek repositories (zajca/pohunek#1588); `scripts/changelog` implements it.

## When to write a fragment

- A change with a user-visible effect (behaviour, screens, settings, install or
  update, a bug a user could see) adds one fragment per effect.
- A change with no user-visible effect (CI, tests, refactors, internal docs,
  release preparation) carries the `no-changelog` label instead. The `changelog`
  check fails a pull request that has neither.

## How to write one

File: `changelog.d/<N>-<slug>.md`. `N` is the issue number (the pull request
number when there is no issue), without leading zeros; `slug` is lowercase
words joined by single hyphens.

```markdown
---
kind: fixed
---
Reconnecting keeps the selected session instead of opening the first one.
```

Header keys, each at most once, `key: value` with one space and no trailing space:

- `kind` (required): `added`, `changed`, `deprecated`, `removed`, `fixed` or `security`.
- `breaking` (optional): `true` when users must act on the change; the entry then starts with **Breaking:**.
- No other key. `surfaces` is not used: this repository is a single product.

Body: one paragraph of one to three sentences, for the user. Say what changes
for them, in the present tense. Use Markdown inline syntax only. No blank line
inside the body, and no line starting with `#`.

Files are UTF-8 with LF line endings and no byte order mark.

## What the tooling checks

```bash
scripts/changelog check      # every fragment in changelog.d/; one message per problem
scripts/test-changelog       # the tooling against the contract fixtures
```

The `changelog` workflow runs both on every pull request.

## What not to do

- Do not edit `CHANGELOG.md` in a feature pull request, and do not write a
  version section by hand; the release preparation writes it from the fragments.
- Do not write an entry for an internal change so that the check passes; use
  `no-changelog`.
- Do not describe the implementation. Describe what the user sees.
