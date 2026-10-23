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
- `session-list-response.json`: the `session.list` response with the four fixed
  synthetic sessions and one created later; `alpha-agent` carries
  `runtime.state` `lost` with `loss_reason`, as left by a daemon restart.

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
- `notification-list-too-large-response.json`: the `runtime/notification_list_too_large` error
  envelope. The code and class are the documented ones; the `msg` and `recover` text are written
  for the test, because the isolated daemon never produces a result that does not fit one line.
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
