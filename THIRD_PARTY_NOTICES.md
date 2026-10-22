# Third-party notices

This project copies code from the sources below. Each copied file keeps its
original header. All sources are MIT licensed; the license text of each is in
the `LICENSE` file of that repository at the pinned commit. Entries marked
"planned" list files not copied yet; the issue that copies them updates the
entry. The decisions behind this list are in
[docs/architecture.md](docs/architecture.md).

## expo-libghostty

- Repository: https://github.com/arcboxlabs/expo-libghostty
- Commit: `f53920f2bc6945706cff21abf21fd55dd333dc87`
- License: MIT, Copyright (c) 2026 ArcBox, Inc.
  (https://github.com/arcboxlabs/expo-libghostty/blob/f53920f2bc6945706cff21abf21fd55dd333dc87/LICENSE)
- Files (`android/` only), all planned:
  - `GhosttyTerminalView.kt`
  - `GhosttyVt.kt`
  - `SnapshotFormat.kt`
  - `StickyModifier.kt`
  - `TerminalAccessoryBar.kt`
  - `cpp/ghostty_jni.cpp`
  - `CMakeLists.txt`
- Not copied: the Expo glue (`ExpoLibghosttyModule.kt`, `ExpoLibghosttyView.kt`).

## ghostty (libghostty-vt)

- Repository: https://github.com/ghostty-org/ghostty
- Commit: `b0947378349eff70f7030dda0e6d022fae1e6fbd`
- License: MIT, Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors
  (https://github.com/ghostty-org/ghostty/blob/b0947378349eff70f7030dda0e6d022fae1e6fbd/LICENSE)
- Use: libghostty-vt is built from source at this commit with Zig 0.15.2 and
  NDK 27.1.12297006; no source files are vendored into this repository.
  Status: planned. The transitive dependencies and bundled fonts of
  libghostty-vt are inventoried in #11.

## opencode-mobile

- Repository: https://github.com/beppe2880/opencode-mobile
- Commit: `4393d5f6e9dc49b6dc593d563a7dc969695f982d`
- License: MIT, Copyright (c) 2026 OpenCode Mobile contributors
  (https://github.com/beppe2880/opencode-mobile/blob/4393d5f6e9dc49b6dc593d563a7dc969695f982d/LICENSE)
- Files (paths under `android/` at the pinned commit; "adapted" means package,
  naming, and contents changed to this project):
  - copied unchanged: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
    `gradle/wrapper/gradle-wrapper.properties`, `.editorconfig`
  - adapted: `gradle/libs.versions.toml` (version catalog, trimmed to the
    dependencies in use), `settings.gradle.kts` (module layout),
    `build.gradle.kts` (Spotless setup), `gradle.properties`, `.gitignore`,
    `app/build.gradle.kts`, `app/proguard-rules.pro`,
    `core/{ui,mvi,domain,data}/build.gradle.kts`
  - adapted Kotlin: `core/mvi/.../BaseViewModel.kt`, `OptimisticState.kt`,
    `CollectEffect.kt`, `core/mvi/src/test/.../OptimisticStateTest.kt`,
    `core/ui/.../theme/OcTheme.kt` (as `PohunekTheme.kt`),
    `app/.../OpenCodeApplication.kt` (as `PohunekApplication.kt`),
    `app/.../AppModule.kt`, `app/.../MainActivity.kt`,
    `app/.../navigation/AppNavHost.kt`, `core/data/.../di/DataModule.kt`
  - launcher icon foreground `app/src/main/res/drawable/ic_launcher_foreground.xml`
  - the commit has no convention plugins (no `build-logic`); shared Android
    settings are configured in the root `build.gradle.kts` instead.
- Planned, not copied yet: connect flow, `SessionMonitorService`
  (foreground service type `dataSync`) with its notifier and notification
  action receiver.
- Not copied: its OpenCode API and network layers.

## pohunek-companion (`crates/core`)

- Repository: https://github.com/pohunekd/pohunek-companion
- License: MIT, Copyright (c) 2026 pohunekd
  (https://github.com/pohunekd/pohunek-companion/blob/0b9d150170bb4829ad59ddcead9d4a4cd02b00b8/LICENSE)
- Files ported to Kotlin, all planned: `live.rs`, `needs_you.rs`, `filter.rs`,
  `layout.rs`, `discovery.rs`, `view.rs`, `sort_key.rs`. Pinned commit for the license text:
  `0b9d150170bb4829ad59ddcead9d4a4cd02b00b8`.

## Pohunek wire fixtures

- Repository: https://github.com/zajca/pohunek
- Tag: `v0.35.2`
- License: MIT
- Files, planned: `sdk/ts/protocol/fixtures`.
