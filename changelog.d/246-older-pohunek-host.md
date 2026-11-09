---
kind: changed
---
A host that runs a Pohunek older than 0.41 now says "Runs an older Pohunek (protocol 4). Upgrade it to Pohunek 0.41 or newer." instead of showing a raw `version_mismatch` refusal, and a host on a newer protocol says to update the app. The same applies to hosts found by discovery, and an upgraded host connects by itself.
