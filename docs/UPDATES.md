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
3. **Checks before it starts**: the car must be in Park, because installing restarts the dash. The first time, Android has to allow Binnacle to install apps: Binnacle opens Android's **Install unknown apps** screen for Binnacle straight away ("Allow from this source"), and when you go back the update carries on by itself.
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

`./deploy.sh` does the chores. Run it with no arguments for a menu: it first shows where things stand (branch, versions, the last release, what changed since it, which versionCode still needs a bump), then offers each step below by number, so no flags to remember. The commands below do the same from the command line.

1. Bump `android:versionCode` (and `versionName`) in `app/AndroidManifest.xml`. The dash only offers a higher versionCode.
2. If the CarPlay app changed, bump its `versionCode` (and `versionName`) in `carplay/mobile/build.gradle.kts`. `deploy.sh` and `release.sh` stop if `carplay/` changed since the last `v*` tag (Markdown aside) without a higher CarPlay versionCode, because the dash's updater would skip it. `ALLOW_SAME_CARPLAY_VERSION=1` builds anyway.
3. `./deploy.sh release`: switches to `main`, fast-forwards it from GitHub (it stops on local changes or local-only commits), runs the version checks, runs `./release.sh` (which asks for the keystore password and signs everything with the release key), shows what it is about to publish and asks, then runs `gh release create v<versionName> … --latest --generate-notes` with `Binnacle.apk`, `BinnacleCarPlay.apk`, `binnacle-update.txt` and the logger APK, and checks that the latest release now serves the new `binnacle-update.txt`. `--draft` publishes a draft instead; `./deploy.sh build` then `./deploy.sh release --no-build` splits building from publishing.

   By hand it is the same as before: `./release.sh` writes `build/release/github/{Binnacle.apk,BinnacleCarPlay.apk,binnacle-update.txt}` (the info file also lists `carplayVersionCode`, `carplayVersionName`, `carplaySize` and `carplaySha256`) and prints the `gh release create` command, for example:
   ```
   gh release create v0.9 build/release/github/Binnacle.apk build/release/github/BinnacleCarPlay.apk \
       build/release/github/binnacle-update.txt build/release/BinnacleLogger-0.9.apk --title "Binnacle 0.9" --latest
   ```
4. Car-image releases (`cars-v…`) must **not** become the latest release, or the dash would look for an update there and say "no update published yet". `./deploy.sh cars cars-vN FILE...` always publishes them with `--latest=false` (or adds the files to that release if it exists).

Undo a bad release: mark the previous release as latest again (or delete the new one). Dashes that already installed it stay on it until a higher versionCode is published; Android doesn't allow going back through the updater, only `adb install -r -d` or uninstall + install.

## Not included (yet)

- The drive logger isn't updated in the app (it is a separate developer tool, installed by hand; its own guard refuses network access).
- No automatic or background checks. A "new version available" hint on opening Advanced could come later.
