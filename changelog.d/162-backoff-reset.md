---
kind: fixed
---
After a short network drop the session list reconnects within seconds, even when another drop happened half a minute earlier. A new setting, `backoff_reset_ms`, sets how long a connection must hold before the reconnect wait starts over.
