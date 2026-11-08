# In-app updates

Binnacle can update itself from this project's GitHub releases: **Apps → Advanced → Updates → Check for updates**. It is there from 0.8 on; 0.7 and earlier have to be updated once by hand (USB stick or `adb install -r`). From 0.9 on it also updates Binnacle's CarPlay app (`BinnacleCarPlay.apk`, from `carplay/`) when that is installed: see "The CarPlay app" below.

## What happens on the dash

1. **Check** (only when you tap; nothing runs in the background): Binnacle downloads `binnacle-update.txt` from the latest release, `https://github.com/lotech/binnacle/releases/latest/download/binnacle-update.txt`. It is a few `key=value` lines:
   ```
   versionCode=8
   versionName=0.8
   size=41234567
   sha256=<64 hex digits of Binnacle.apk>
   ```
   If `versionCode` isn't higher than the installed one, the button says **Up to date**.
2. **Ask**: a Binnacle pop-up shows the version and download size. **Update** goes on; **Not now** stops.
3. **Checks before it starts**: the car must be in Park, because installing restarts the dash. The first time, Android has to allow Binnacle to install apps: Binnacle explains this and opens Android's **Install unknown apps** screen for Binnacle ("Allow from this source"). Come back and tap Update again.
4. **Download**: `Binnacle.apk` from the same release is streamed straight into an Android `PackageInstaller` session, so Binnacle writes no file of its own. The size and SHA-256 must match the info file, or the session is thrown away.
5. **Install**: Android shows its own **Do you want to install an update to this app?** screen. Only after you tap **Install** does Android replace the app, and only if:
   - it is the same app (the session is locked to Binnacle's own package name, so the download can't install anything else);
   - it is signed with the same key as the installed copy (Android's own rule for updates);
   - its version is higher (Android refuses a downgrade).

   The dash closes while it installs and comes back as the Home app. Settings and downloaded car images are kept.

If something goes wrong the button goes back to **Update to …** and a message says why: no Wi-Fi (the dash is on CarPlay's hotspot, for example), no release published yet, a damaged download, cancelled, or "isn't signed with the same key" (a copy you built yourself with a different key can't be updated from the project's releases; uninstall it first).

## The CarPlay app

Each release from 0.9 on also carries `BinnacleCarPlay.apk` (package `com.shilapi.xcertplay`), and `binnacle-update.txt` has four more lines for it: `carplayVersionCode`, `carplayVersionName`, `carplaySize`, `carplaySha256` (all four, or none). When the CarPlay app is installed and the release's is newer, the Updates card shows **Update CarPlay to …** under the dash's button and offers it first (a dash update restarts the dash). The steps are the same: Park only, a Binnacle pop-up, a download streamed into Android's installer with the size and SHA-256 checked, and Android's own confirm screen. CarPlay stops while it installs; tap CarPlay again afterwards. Its settings and pairing are kept.

It is never installed fresh from here, only updated: Android then insists on the installed copy's signing key, as for the dash. To install it the first time, use a USB stick or `adb install`.

## Safety

- Nothing here touches the car, the CAN bus or the MCU. The guards (`app/tools/check_readonly.sh`, `check_bytecode.sh`) still refuse any of that.
- Network: only `Updater` and `CarPacks` may use HTTP, both HTTPS GET only from this repo's releases. `Updater` may fetch exactly three names (`binnacle-update.txt`, `Binnacle.apk`, `BinnacleCarPlay.apk`) under `releases/latest/download/`.
- Installer: only `Updater` may use `PackageInstaller`, with exactly one session, locked by `setAppPackageName(target(c, carplay))`, where `target()` returns this app's own package name or the fixed `com.shilapi.xcertplay`, nothing else (both guards pin it). No uninstall, no granting permissions, no installer-name tricks. `app/tools/test_guards.sh` checks that changing any of this fails the build.
- No root and no `LocalShell` command: a root `pm install` would update silently, without Android's confirm screen, and would need a new pinned shell command. The Android installer is the normal way an app updates itself and keeps the user in charge.
- The only change on the unit is Android's own **Install unknown apps** switch for Binnacle, which you turn on yourself (listed in `docs/UNIT-CHANGES.md` with its undo).
- The SHA-256 comes from the same release as the APK, so it guards against a damaged download, not a hostile release. Against that, Android's same-key rule is the real protection: only an APK signed with the project's release key can update an installed release build.

## Publishing a release (maintainer)

`./deploy.sh` does the routine steps and refuses the usual mistakes. `./deploy.sh` alone lists the commands.

1. `./deploy.sh pull`: switch to `main` and bring it up to date with GitHub (`release.sh` builds whatever is checked out, and doesn't pull).
2. Bump `android:versionCode` (and `versionName`) in `app/AndroidManifest.xml`; the dash only offers a higher versionCode, and the release is named after it. If `carplay/` changed, also bump `versionCode` (and `versionName`) in `carplay/mobile/build.gradle.kts`, or the dash never offers the new CarPlay app. `./deploy.sh check` compares with the latest release and says which bump is missing (changes to `*.md` files don't count). The bump goes through a PR like any other change.
3. `./deploy.sh build`: the check, then `./release.sh` (asks for the keystore password). It writes `build/release/github/Binnacle.apk`, `BinnacleCarPlay.apk` and `binnacle-update.txt` (with the CarPlay app's `carplay*` lines), plus `built-from.txt`, which records the commit (not uploaded). `./release.sh` on its own still works and only warns about versions, for test builds.
4. `./deploy.sh publish`: refuses a build with uncommitted changes, from another commit, not yet on GitHub's `main`, whose files don't match the info file, or whose version already has a release. Then it shows what it will publish, asks, and runs `gh release create v<versionName> … --latest` with the three files and the logger APK, tagged on the built commit. Release notes are GitHub's list of merged PRs, or `NOTES=notes.md ./deploy.sh publish`.
5. On the dash: **Apps → Advanced → Updates → Check for updates**, in Park. Or over adb: `DASH=<ip> ./deploy.sh install` installs what is newer (CarPlay app first), after saving the installed copies to `build/rollback/` and printing the commands to put them back.

`./deploy.sh release` runs 1, 3 and 4 in a row (after the bump is merged). `./deploy.sh status` shows the checkout, the versions here, in the last build and (with `DASH=<ip>`) on a dash, and what a release would need.

Car-image releases (`cars-v…`) must **not** become the latest release: publish them by hand with `--latest=false`, or the dash would look for an update there and say "no update published yet".

Undo a bad release: mark the previous release as latest again (or delete the new one). Dashes that already installed it stay on it until a higher versionCode is published; Android doesn't allow going back through the updater, only `adb install -r -d` or uninstall + install.

## Not included (yet)

- The drive logger isn't updated in the app (it is a separate developer tool, installed by hand; its own guard refuses network access).
- No automatic or background checks. A "new version available" hint on opening Advanced could come later.
