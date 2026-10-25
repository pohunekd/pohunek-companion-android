# Releases

A pushed tag `vX.Y.Z-pre.N` (pre-release) or `vX.Y.Z` builds an APK signed with
the owner's release key and publishes it as a GitHub release
(`.github/workflows/release.yml`). Only a commit of `main` whose `ci` check
passed is built.

## Install and update

1. Download `pohunek-companion-<version>.apk` and `pohunek-companion-<version>.apk.sha256`
   from the [releases page](https://github.com/pohunekd/pohunek-companion-android/releases).
2. Check the download: `sha256sum -c pohunek-companion-<version>.apk.sha256`.
3. Check the signer: `apksigner verify --print-certs pohunek-companion-<version>.apk`
   (Android SDK build-tools). The `certificate SHA-256 digest` must equal the one
   in the release notes, and the same on every release.
4. Install the APK on the phone (allow installs from the app you open it with).
   A later release installs over it and keeps the data, because it carries the
   same key and a higher version code.

## Versions

The tag gives `versionName` (`0.1.0-pre.1`) and `versionCode`
(`X * 10,000,000 + Y * 100,000 + Z * 1,000 + N`, with `N` = 999 for a release
without `-pre.N`), so `0.1.0-pre.1` < `0.1.0-pre.2` < `0.1.0` < `0.1.1`.
Minor and patch stay below 100 and `N` is 1 to 998. A build without a tag keeps
`versionCode` 1 and `versionName` 0.1.0, and its release APK is unsigned.

## One-time key setup (owner)

Run these yourself; no agent runs them or sees the values.

1. Generate the key outside the repository (RSA 4096, 30 years). `keytool`
   prompts for the passwords; do not put them on the command line. A PKCS12
   keystore uses one password for store and key, so the same value goes into both
   password secrets below.

   ```bash
   mkdir -p ~/keys
   keytool -genkeypair -keystore ~/keys/pohunek-companion-release.jks -storetype PKCS12 \
     -alias pohunek-companion -keyalg RSA -keysize 4096 -validity 10958
   ```

2. Back the file and the password up somewhere that survives this machine.
   Losing the key means no more in-place updates: every phone would have to
   uninstall the app and lose its data.
3. Create the `release` environment (Settings > Environments). Restrict its
   deployment branches and tags to `v*` tags so no other ref can read the secrets.
4. Set the four environment secrets. `gh` prompts for a value when no input is
   piped, so the passwords never appear in the shell history.

   ```bash
   repo=pohunekd/pohunek-companion-android
   base64 -w0 ~/keys/pohunek-companion-release.jks | gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --env release --repo "$repo"
   gh secret set ANDROID_RELEASE_KEYSTORE_PASSWORD --env release --repo "$repo"
   gh secret set ANDROID_RELEASE_KEY_ALIAS --env release --repo "$repo"
   gh secret set ANDROID_RELEASE_KEY_PASSWORD --env release --repo "$repo"
   ```

   The alias is `pohunek-companion` if you used the command above.

## Publish a release

1. Refresh the screenshots. The tagged tree must carry a set taken from its own
   app sources, so on current `main` compare `git rev-parse HEAD:app/src/main`
   with `appTree` in `docs/screenshots/manifest.json`. Equal: nothing to do. Different:
   run `scripts/capture-screenshots` (it needs a clean `app/`), review the images,
   and merge the result in a PR. `scripts/check-screenshots` validates the set
   without an emulator; the `prepare` job runs it on the tag.
2. On a commit of `main` with a green `ci`: `git tag -a v0.1.0-pre.1 -m "0.1.0-pre.1"`
   and `git push origin v0.1.0-pre.1`. The workflow checks the tag, the commit,
   the screenshot set (a missing manifest, a missing file or a wrong `sha256` fails
   it; an `appTree` that differs from the tag's `app/src/main` only warns, naming
   the changed paths) and the secrets, builds, verifies the signature (v2 or newer, one signer) and the
   version, and creates the release with the APK, its checksum, the certificate
   SHA-256 and the commit. A run that fails before the publish job leaves no release; one that fails
   afterwards may leave a draft. Fix the cause, delete the draft (if any) and the
   remote tag (`git push --delete origin <tag>`), and push the tag again; pushing an
   existing tag does nothing.
3. After the release is published, bump the Android pin on the site: in
   [pohunekd/pohunek.dev](https://github.com/pohunekd/pohunek.dev) set `ref` in
   `android.config.json` to the new tag, run `bun run build` (it verifies the
   manifest and every digest at that tag), and open a PR with that one-line change.
   The release procedure ends when that PR is open.

## Local signed build

Gradle reads `ANDROID_RELEASE_KEYSTORE_FILE`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`,
`ANDROID_RELEASE_KEY_ALIAS` and `ANDROID_RELEASE_KEY_PASSWORD` from the
environment and `-PreleaseTag=vX.Y.Z[-pre.N]` for the version. With none of the
four set, `assembleRelease` produces an unsigned APK; with only some, it fails
and names the missing ones. The debug key is never used for a release build.
`scripts/dev --env NAME --ro-dir DIR` passes a variable and a read-only
directory (the keystore) into the toolchain container.
