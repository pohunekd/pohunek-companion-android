# Captured wire fixtures

`sdk/ts/protocol/fixtures` of Pohunek `v0.35.2` has no fixture for `session_*`
events or for runtime-bearing `session.list` entries, so these files were
captured from the isolated `pohunekd` 0.35.2 that `scripts/ui-agent` runs (same
tag as `POHUNEK_VERSION` in `container/Containerfile`). Working directories of the fixed sessions are rewritten to
`/work/...` and sessions created for the capture run in `/tmp`; ids, names, and timestamps are the synthetic ones of the run.

Captured as received (keys sorted, pretty-printed):

- `session_created.json`, `session_updated.json` (rename), `session_stopped.json`,
  `session_removed.json`: one `subscribe` event each.
- `agent_state.json`, `notification_created.json`, `notification_updated.json` (marked read),
  `notification_deleted.json`: one `subscribe` event each, from a `--showcase` run (its sessions
  report activity, so `agent_state` arrives continuously; the notification events come from
  `scripts/ui-agent pohunek notification create|read|delete` on an idle session).
- `notification-list-page-1.json`, `notification-list-page-2.json`: `notification.list` for
  `kind: agent_blocked` with `limit: 2`, the second page with the first one's `next_cursor`
  (request ids rewritten to `req-1` and `req-2`; the second page holds the records the daemon's own
  projector created for its blocked sessions).
- `session-rename-response.json`, `session-rename-clear-response.json`: the `session.rename` responses
  that set `renamed-agent` and that clear the name (no `name` in the result);
  `session-rename-control-char-error.json`: the `daemon/bad_request` refusal of a name with a control
  character (request ids rewritten to `req-1` to `req-3`, working directories to `/work/...`).
- `session-rename-request.jsonl`, `session-rename-clear-request.jsonl`: the request lines of the client's encoder that the isolated daemon answered `ok` (session id rewritten to `s-1`), sent with `socat` as in the capture command below.
- `session-resume-request.jsonl`: the bare-id request line of the client's encoder (session id rewritten to `s-1`); the isolated daemon parsed it and answered with `session-resume-not-resumable-error.json`, the `runtime/agent_not_resumable` refusal of a stopped `claude` session. No session of the isolated daemon is resumable (none has a native session), so a successful `session.resume` result is not captured; its `{session}` body is the shape of `session.rename`.
- `project-list-response.json`, `host-inspect-response.json`, `session-new-response.json`, `session-new-project-not-found-error.json`: the responses of
  the `--showcase` daemon to `project.list` (params `{}`), `host.inspect` and `session.new` (a `claude` session in the `pohunek` project with a name and
  an initial prompt, which the daemon applied) and to a `session.new` naming an unknown project (request ids rewritten to `req-1`, working directories to
  `/work/...`). `project-list-request.jsonl`, `host-inspect-request.jsonl` and `session-new-request.jsonl` are the request lines of the client's encoder that
  the daemon answered. The daemon does not answer while the client has closed its write side, so hold the input open (`(printf ...; sleep 10) | socat - TCP:...`).
- `host-discover-netbird-cli-missing-response.json`: the `host.discover` response of the isolated daemon, which has no NetBird CLI
  (`discovery/netbird_cli_missing`; request id rewritten to `req-1`), sent with `socat` as in the capture command below.
- `session-list-response.json`: the `session.list` response with the four fixed
  synthetic sessions and one created later; `alpha-agent` carries
  `runtime.state` `lost` with `loss_reason`, as left by a daemon restart.

- `session-stop-response.json`, `session-stop-ended-response.json` (stop of a session that had ended: `stopped:false`),
  `session-remove-response.json` (a live session: `stopped:true`), `session-remove-ended-response.json`,
  `session-remove-not-found-response.json` (the error of a repeated removal): the response lines to raw `session.stop` and
  `session.remove` requests (bare session id as `params`) on the daemon socket, as received.

- `project-actions-response.json`, `project-action-response.json`, `project-action-not-found-error.json`, `session-new-branch-response.json`,
  `session-new-base-branch-error.json`: the responses of the `--showcase` daemon to `project.actions` and `project.action` of the `pohunek`
  project, to `project.action` of an undefined action, to a `session.new` with `branch` and `base_branch` (a worktree session) and to a
  `session.new` with `base_branch` alone (`daemon/bad_request`, `session.new base_branch requires branch`). The project's
  `.pohunek/templates.toml`, `actions.toml` and `prompts/fix-bug.tmpl` were written into its work directory in the slot container (a static
  `fix-bug` action with `branch`, a `from-linear` action with provider `linear_issue`, an action with a `${...}` prompt and one whose template
  names an agent the host lacks). Request ids are `req-1`, working and worktree directories are rewritten to `/work/...` and
  `/data/worktrees/...`; `project-actions-request.jsonl`, `project-action-request.jsonl` and `session-new-branch-request.jsonl` are the
  request lines of the client's encoder that the daemon answered.

Built from captured payloads, because the isolated daemon cannot be made to
emit them (`--dev-subprocess` has no agent with a native session, and the
restart-time events fire before a subscriber can connect):

- `session_runtime_lost.json`: the envelope of the table in `docs/public-api.md`
  around the captured `lost` session of the list.
- `session_runtime_reconnected.json`, `session_runtime_conflict.json`,
  `session_native_recovered.json`: the captured `session_updated` snapshot with
  the event name, and for conflict `runtime.state` and for native recovery the
  new generation and `worker_instance_id` plus the two extra payload fields,
  set as documented.
- `subagent_state.json`, `background_state.json`: shapes of the table in `docs/public-api.md` and
  the `SubagentInfo` / `SessionBackground` types of the pinned tag; the showcase sessions run a
  plain shell, so no subagent or background task ever reports.
- `session-remove-accepted-processes-response.json`, `session-remove-ambiguous-response.json`: the `session.remove_accepting_unconfirmed`
  result with `accepted_unconfirmed_processes` (shape of `UnconfirmedProcess` at the pinned tag, `start_identity` a decimal string, one
  `command` omitted as documented) and the `runtime/runtime_supervision_ambiguous` error of `session.remove`. The isolated daemon has no
  process with an unreadable environment, so it never produces either; the error's `msg` and `recover` are written for the test, the code
  and class are the documented ones. The captured `session-remove-response.json` of the same method is the shape without the list.
- `notification-list-too-large-response.json`: the `runtime/notification_list_too_large` error
  envelope. The code and class are the documented ones; the `msg` and `recover` text are written
  for the test, because the isolated daemon never produces a result that does not fit one line.
- `host-discover-response.json`: the `ok` list of `host.discover` built from the `HostRecord` and `HostClass` types of the pinned tag
  (`crates/protocol/src/discovery.rs`: flat records with an internal `classification` tag), one record per classification and one with a
  classification the client does not model. The isolated daemon has no overlay, so it never produces a peer.
- `unknown_event.json`: an event name the daemon does not emit today.

## Capture command

With `scripts/ui-agent start` running in slot 0 (`PORT` from `scripts/ui-agent
status`), start a subscriber inside the slot container, drive the daemon with
the CLI, and read the file:

```bash
podman exec -d pohunek-ui-agent-slot-0 sh -c '(printf "%s\n" \
  "{\"v\":{\"minimum\":4,\"maximum\":4},\"id\":\"sub-1\",\"method\":\"subscribe\",\"params\":null}"; \
  sleep 100) | socat - TCP:127.0.0.1:PORT > /tmp/ev.jsonl'
scripts/ui-agent pohunek session new --name capture-a --cwd /tmp
scripts/ui-agent pohunek session rename ID capture-b
scripts/ui-agent pohunek session stop ID
scripts/ui-agent pohunek session rm ID
podman exec pohunek-ui-agent-slot-0 cat /tmp/ev.jsonl
```

The list response comes from one `session.list` request sent the same way.
