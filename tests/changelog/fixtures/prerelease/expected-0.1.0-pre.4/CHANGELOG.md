# Changelog

User-facing changes of example/app.

## 0.1.0-pre.4 - 2026-10-26

### Added

- **Attach** can run from the session row ([#149](https://github.com/example/app/issues/149))

### Changed

- **Breaking:** Sessions show the live state reported by the daemon. ([#151](https://github.com/example/app/issues/151))

### Fixed

- Reconnect keeps the selected session. ([#152](https://github.com/example/app/issues/152))

### Security

- Session tokens are rotated on every reattach. ([#152](https://github.com/example/app/issues/152))

## 0.1.0-pre.3 - 2026-10-20

### Fixed

- Session list no longer flickers on reconnect. ([#140](https://github.com/example/app/issues/140))
