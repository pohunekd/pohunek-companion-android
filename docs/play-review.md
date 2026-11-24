# Google Play review

A Google Play reviewer has no NetBird overlay and no Pohunek host, and the app
needs both for its real work: the session list, the actions and the terminal
all come from a daemon on the owner's private overlay. The app therefore
carries an in-app demo with synthetic data, so the reviewer can use the session
list and the terminal with neither a host nor a network. Fill Play Console >
App content > App access with the text below before submitting a build; nothing
in the app reaches a daemon while the demo is active.

## App access instructions

Play Console > App content > App access: choose **All or some functionality is
restricted** and paste this text into the instruction field. It is 391
characters long (the field takes 500), needs no account and names no
credentials. Every label in it is a string from
`app/src/main/res/values/strings.xml`: `hosts_try_demo` ("Try the demo"),
`demo_leave` ("Leave demo") and `session_list_hosts` ("Hosts").

```text
No login or account is needed. The app controls Pohunek daemons on the owner's private NetBird network, which a reviewer cannot reach. On first start the Hosts screen opens: tap "Try the demo". The app then shows synthetic sessions on two made-up hosts; tap a session to open its terminal, which plays a scripted run. The demo uses no network. To leave it, open the menu, Hosts, Leave demo.
```

## What the demo shows

- The session list as the real one renders it, on two made-up hosts: views,
  filters, search and session details.
- A scripted terminal for `bump-tokio-1.48`, which plays the showcase run; every
  other session opens a static screen that matches its state.
- Actions that need a Pohunek host answer "Not available in the demo", and the
  list says that it shows synthetic data.
- No network: the demo hosts have no connection outside the app.

## Checking it before a submission

The demo needs no daemon, but the isolated run gives a clean install and a
device to look at. In the toolchain container:

```bash
scripts/ui-agent start                    # isolated emulator and daemon, fresh app data
scripts/ui-agent click HostForm.tryDemo   # first start lands on the Hosts screen
scripts/ui-agent tree                     # the demo rows; find the bump-tokio-1.48 one
scripts/ui-agent click SessionList.row N  # N is that row's 0-based index in the tree
scripts/ui-agent screen                   # the grid ends with "showcase demo: standing by"
scripts/ui-agent sockets                  # no connection of the app
scripts/ui-agent stop
```
