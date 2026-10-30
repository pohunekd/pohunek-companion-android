---
kind: fixed
---
A single slow health check or failed refresh no longer drops a healthy connection to a host on a poor network. Two new settings, `liveness_failures` and `liveness_retry_ms`, set how many failures in a row end the connection and how soon the next check follows.
