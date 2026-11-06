---
kind: security
---
Host keys in the exported log are now derived with a secret that stays on the phone, so a shared log can no longer be used to confirm a host's address. Log lines written by earlier versions are removed on upgrade.
