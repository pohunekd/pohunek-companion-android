# Third-party notices

This project copies code from the sources below. Each copied file keeps its
original header. All copied sources and everything linked into the shipped
libghostty-vt are MIT licensed; the license text of each is in the `LICENSE`
file of that repository at the pinned commit. The libghostty-vt section also
inventories packages with other licenses that its build fetches but does not
ship. Entries marked
"planned" list files not copied yet; the issue that copies them updates the
entry. The decisions behind this list are in
[docs/architecture.md](docs/architecture.md).

## expo-libghostty

- Repository: https://github.com/arcboxlabs/expo-libghostty
- Commit: `f53920f2bc6945706cff21abf21fd55dd333dc87`
- License: MIT, Copyright (c) 2026 ArcBox, Inc.
  (https://github.com/arcboxlabs/expo-libghostty/blob/f53920f2bc6945706cff21abf21fd55dd333dc87/LICENSE)
- Files (`android/` only), copied into `:terminal` with the package renamed to
  `dev.pohunek.companion.terminal` (JNI function names, library name, and log tag
  follow; each file's first line says where it came from):
  - `GhosttyTerminalView.kt`, `GhosttyVt.kt`, `SnapshotFormat.kt`,
    `StickyModifier.kt`, `TerminalAccessoryBar.kt` (from
    `src/main/java/expo/modules/libghostty/`); `GhosttyTerminalView.kt`
    takes input through `ComposingText` and `DeadKeyComposer` (own files,
    `terminal/src/main/kotlin/dev/pohunek/companion/terminal/`): IME composition
    and dead keys reach the PTY once, and the input type is a plain text field
  - `cpp/ghostty_jni.cpp` (from `src/main/cpp/`); `nativeViewportText` is added
    to read the visible grid without touching the selection, and the selection
    formatting is factored into a helper shared with `nativeSelectionText`
  - `CMakeLists.txt`: library name and the libghostty-vt paths (the staged
    `vendor/libghostty-vt/`) adapted; the 16 KB page-size link option is kept.
- Not copied: the Expo glue (`ExpoLibghosttyModule.kt`, `ExpoLibghosttyView.kt`),
  the Gradle build file, the unit tests, and the Nerd Fonts symbols font
  (`GhosttyTerminalView` falls back to the system fonts when the asset is absent).
- The copied Kotlin files keep their upstream formatting and are excluded from
  Spotless and detekt (`copiedSources` in the root `build.gradle.kts`).

## ghostty (libghostty-vt)

- Repository: https://github.com/ghostty-org/ghostty
- Commit: `b0947378349eff70f7030dda0e6d022fae1e6fbd`
- License: MIT, Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors
  (https://github.com/ghostty-org/ghostty/blob/b0947378349eff70f7030dda0e6d022fae1e6fbd/LICENSE)
- Use: `scripts/build-libghostty-vt` builds libghostty-vt from source at this
  commit with Zig 0.15.2 and NDK 27.1.12297006 into the gitignored
  `vendor/libghostty-vt/`; no source files are vendored into this repository.
  Pins are in `config/libghostty-vt.toml`.

### What the library contains

`libghostty-vt.a` (arm64-v8a, x86_64) is built with `-Dsimd=false`. Its
undefined symbols are libc and libm functions only, and it contains exactly
three kinds of code (no symbol of ghostty's vendored `stb` is defined in it):

| Component | Version | License | Source |
| --- | --- | --- | --- |
| ghostty `src/` | `b0947378` | MIT, Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors | the pinned commit |
| uucode | 0.2.0 | MIT, Copyright (c) 2026 Jacob Sandlund | https://github.com/jacobsandlund/uucode, fetched by Zig from `deps.files.ghostty.org` |
| Zig `compiler_rt` | 0.15.2 | MIT (Expat), Copyright (c) Zig contributors | the Zig 0.15.2 distribution in the toolchain image |

The Nerd Fonts symbols font and the JetBrains Mono font listed below are
fetched by ghostty's build graph but are not part of the library output. A
terminal view that bundles the Nerd Fonts symbols font adds its own entry in
the issue that bundles it (MIT; the package carries its license file).

### Every dependency in `build.zig.zon`

All entries of ghostty's `build.zig.zon` at the pinned commit, with the
license found in the fetched package (Zig checks each download against the
package hash in the manifest). `Fetched` marks packages the
`scripts/build-libghostty-vt` run downloads because ghostty's build graph
evaluates them; none except uucode ends up in the output.

| Dependency | Version | License | Fetched | In the library |
| --- | --- | --- | --- | --- |
| libxev | `34fa5087` | MIT | yes | no |
| vaxis (libvaxis) | 0.5.1 | MIT | yes | no |
| z2d | 0.10.0 | MPL-2.0 (`SPDX-License-Identifier` in its `build.zig.zon`; the package omits the license text, which is in the upstream repository) | yes | no |
| zig-objc | `f356ed02` | MIT | no | no |
| zig-js | `04db83c6` | MIT | no | no |
| uucode | 0.2.0 | MIT | yes | **yes** |
| zig-wayland | `1b5c038e` (0.5.0-dev) | MIT | no | no |
| zf | 0.10.3 | MIT | yes | no |
| gobject (ghostty-org/zig-gobject) | 0.3.0 | MIT (upstream repository license; the package omits the license file) | yes | no |
| dcimgui (Dear Bindings, Dear ImGui) | 1.92.5 | MIT | yes | no |
| fontconfig | 2.14.2 | fontconfig license (MIT-style) | yes | no |
| freetype | 2.13.2 | FreeType License or GPL-2.0 (dual) | yes | no |
| gtk4-layer-shell | 1.1.0 | MIT | no | no |
| harfbuzz | 11.0.0 | "Old MIT" | yes | no |
| highway | 1.2.0 | Apache-2.0 or BSD-3-Clause | no | no |
| libintl (gettext) | 0.24 | LGPL-2.1-or-later (libintl); the gettext tools are GPL-3.0 | no | no |
| libpng | 1.6.43 | PNG Reference Library License v2 | yes | no |
| macos | in-tree | ghostty (MIT), no third-party code | no | no |
| oniguruma | 6.9.9 | BSD-2-Clause | yes | no |
| opengl | in-tree | ghostty (MIT), no third-party code | no | no |
| sentry (sentry-native) | 0.7.8 | MIT | no | no |
| simdutf | 5.2.8 | Apache-2.0 or MIT (upstream dual license; vendored in the ghostty repository) | no | no |
| wuffs | 0.0.0 (`122037b3`) | MIT or Apache-2.0; its test images (`pixels`) are CC0-1.0 | yes | no |
| zlib | 1.3.1 | zlib license | yes | no |
| glslang | 14.2.0 | BSD-3-Clause, BSD-2-Clause, MIT, Apache-2.0 and others (see the package's `LICENSE.txt`) | yes | no |
| SPIRV-Cross | 13.1.1 | Apache-2.0; Khronos Free Use for parts | yes | no |
| wayland | `9cb3d7aa` | MIT | no | no |
| wayland-protocols | 1.47 | MIT | no | no |
| plasma-wayland-protocols | `12207e08` | LGPL-2.1-or-later, MIT, BSD-3-Clause, CC0-1.0, MIT-CMU (see the package's `LICENSES/`) | no | no |
| JetBrains Mono | 2.304 | OFL-1.1 | yes | no |
| Nerd Fonts Symbols Only | 3.4.0 | MIT (see the package's `LICENSE`) | yes | no |
| apple-sdk | in-tree | ghostty (MIT), no third-party code | no | no |
| android-ndk | in-tree | ghostty (MIT); reads the installed NDK and copies none of it | no | no |
| iTerm2-Color-Schemes | `8c97c3c` (release 20260629) | MIT | yes | no |

Packages that dependencies above pull in transitively and that the build also
fetches: libxml2 2.11.5 (MIT, via fontconfig), Dear ImGui 1.92.5 (MIT, via
dcimgui), zigimg 0.1.0 (MIT) and uucode 0.1.0 (MIT). Not fetched by the
build, but referenced by the dependencies above: breakpad (BSD-3-Clause, via
sentry).

The inventory was taken by running `zig build --fetch=all` on the pinned
commit in the toolchain container and reading the license files of the fetched
packages. Re-take it when `ghostty_commit` changes.

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
  - adapted Android resources under `app/src/main/res/`: `drawable/ic_launcher_foreground.xml`,
    `mipmap-anydpi-v26/ic_launcher.xml` (as `mipmap-anydpi/ic_launcher.xml`),
    `values/{colors,strings,themes}.xml`, `values-night/colors.xml`,
    `xml/data_extraction_rules.xml`, and `app/src/main/AndroidManifest.xml`
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
- Files ported to Kotlin: `attach.rs` (only the attach refusal rules, in
  `app/.../sessions/AttachRefusal.kt`) and `model.rs` (session seed and event
  reconcile, without attention, owner state, and the `agent_state`,
  `subagent_state`, `background_state` handling) as
  `core/domain/.../sessions/SessionStore.kt`. Planned: `live.rs`, `needs_you.rs`, `filter.rs`,
  `layout.rs`, `discovery.rs`, `view.rs`, `sort_key.rs`. Pinned commit for the license text:
  `0b9d150170bb4829ad59ddcead9d4a4cd02b00b8`.

## Pohunek wire fixtures

- Repository: https://github.com/zajca/pohunek
- Tag: `v0.35.2` (commit `e4fcb1f32380841491625bfc9363689ff8e304ba`)
- License: MIT
- Files, copied unchanged from `sdk/ts/protocol/fixtures` into
  `protocol/src/test/resources/fixtures/`: `request-session-list.json`,
  `response-ok-session-list.json`, `response-err-with-recover.json`,
  `protocol-error-with-recover.json`, `protocol-error-without-recover.json`,
  `session-info-full.json`, `session-info-minimal.json`,
  `session-list-filter-state.json`. The remaining fixtures are planned for the
  issues that cover their methods and events. Where the SDK has none, fixtures
  are captured from the isolated daemon of the same tag
  (`protocol/src/test/resources/captured/`, see its README); those are this
  project's own files.
