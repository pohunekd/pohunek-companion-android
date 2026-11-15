# F-Droid

How the app reaches F-Droid users, the build recipe kept in this repository,
and the steps that stay with the owner.

## Route

1. **IzzyOnDroid first** (owner-gated, #280). It serves the owner-signed APK of
   the GitHub release, accepts pre-releases marked as testing builds, and needs
   only the fastlane store listing and the changelogs.
2. **F-Droid main repository after the first stable `v0.1.0`** (owner-gated,
   #281). F-Droid rebuilds the app from source; when its unsigned build equals the
   GitHub release apart from the signature, it publishes the owner-signed APK
   (`Binaries:` and `AllowedAPKSigningKeys:` in the recipe). Pre-releases
   (`vX.Y.Z-pre.N`) never go there.
3. No self-hosted repository (#282).

## The recipe

`fdroid/metadata/dev.pohunek.companion.yml` is the file the fdroiddata merge
request adds as `metadata/dev.pohunek.companion.yml`. It is kept in the form
`fdroid rewritemeta` writes (no comments), so its reasons are here:

- `sudo`: F-Droid's buildserver image is Debian without `xz-utils`, Zig, platform 37,
  build-tools 37.0.0 or CMake 4.1.2 (it provisions the SDK up to level 33). The
  Zig tarball is the official one, pinned by sha256 like `container/Containerfile`.
- `prebuild` runs the fetch phase of `scripts/build-libghostty-vt` (the pinned
  ghostty commit and every Zig package, hash-checked); `build` runs its offline
  phase on the host's Zig and NDK. Gradle then builds `:app:assembleRelease`.
- `gradleprops`: `releaseTag=<tag>` gives the tag's `versionName` and
  `versionCode`, `unsignedRelease=true` lets the tagged build go unsigned
  (see `docs/release.md`, "Rebuilding a release").
- `scanignore: build/libghostty-vt-fetch`: the Zig package cache holds test data
  with binaries and static libraries of third-party packages; none of it is linked
  into the app, only the compiled `libghostty-vt.a` is.
- `ndk`: the `ndk_version` of `config/libghostty-vt.toml`; the build script looks
  for it at `$ANDROID_HOME/ndk/<version>`.
- `versionName`, `versionCode` and `commit` are placeholders for `v0.1.0`
  (`versionCode` = `X*10_000_000 + Y*100_000 + Z*1_000 + 999`); the owner fills in
  the real tag's commit in the merge request. `AuthorName` is a placeholder too.

## `scripts/fdroid-build <tag> [--commit SHA]`

Runs the recipe the way F-Droid does, in a Podman container of fdroidserver's
buildserver image (`registry.gitlab.com/fdroid/fdroidserver`, pinned by digest in
`config/fdroid-build.toml`), with fdroidserver installed at a pinned version:

1. `fdroid readmeta`, `fdroid rewritemeta` (the recipe must not change) and
   `fdroid lint` on the committed recipe, with fdroiddata's category list.
2. The recipe's `Builds` entry is pointed at `<tag>`: version, versionCode,
   commit and `releaseTag`.
3. `fdroid build --on-server -v -l`: runs the `sudo` commands as the buildserver
   does, then prebuild, the scan, the build and, because of `Binaries:`, the
   comparison of the unsigned build with the GitHub release's APK
   (apksigcopier, the same tool as `scripts/rebuild-release`).

Exit 0: identical apart from the signature. Exit 1: built, but differs from the
published APK (fdroid prints the differing files). Exit 2: the script, the
download or the build failed. `--commit SHA` builds another commit under the tag's
version, to try the recipe before a tag carries what it needs; differences from
the published APK are then expected.

`.github/workflows/fdroid.yml` runs it weekly on the newest release and by hand
(`workflow_dispatch`, optional `tag` and `commit`). It has a read-only token, no
secrets, and is not a required check.

Differences from the real buildserver, none of which the script can close:

- The container has a network for the whole build; F-Droid's buildserver may
  not. The `prebuild` fetch phase needs it (36 Zig packages, including one from
  `gitlab.freedesktop.org`), and so do Gradle's dependency downloads and
  `sdkmanager`.
- fdroidserver comes from PyPI, the real one from git; the NDK is installed with
  `sdkmanager` where the real buildserver provisions it.
- Whether F-Droid's reviewers accept the downloaded Zig tarball and the network
  use is a question for the merge request (#281).

## Update checking

`fdroid checkupdates` cannot derive our `versionCode` from a tag: the gradle
files hold it as an expression of `-PreleaseTag`, not a literal. The decision
between manual bumps, a committed version file and an HTTP update check is #297;
until it is made the recipe's `UpdateCheckMode: Tags` line is a placeholder.

## Owner-gated steps

Workers never do these: the IzzyOnDroid request (#280), the fdroiddata merge
request and the questions to F-Droid about Zig and the network (#281), and a
self-hosted repository (#282). The merge request needs the real `v0.1.0` tag, its
commit in the recipe, the author name and the update-mode decision (#297).
