#!/bin/bash
# Builds the dash, the drive logger and the CarPlay app (carplay/, com.shilapi.xcertplay) with
# Gradle and signs all three with the release key:
#   ./release.sh   -> build/release/Binnacle-<version>.apk, BinnacleLogger-<version>.apk and
#                     BinnacleCarPlay-<version>.apk
# Asks for the keystore password once, on the terminal, and hands it to apksigner on stdin; nothing
# stores it. All APKs use the same key (they share signature permissions: the dash draws CarPlay
# through the CarPlay app's signature-protected service), and every release must use it too, or
# `adb install -r` refuses the upgrade.
#   KEYSTORE   default ~/.android/binnacle-release.jks      KS_ALIAS   default binnacle
#   APP_ID     installed package of the dash (default com.xbs.launcher, see app/README.md)
#   ANDROID_HOME   SDK path (default ~/Library/Android/sdk)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
KEYSTORE="${KEYSTORE:-$HOME/.android/binnacle-release.jks}"
KS_ALIAS="${KS_ALIAS:-binnacle}"
[ -f "$KEYSTORE" ] || { echo "no keystore at $KEYSTORE"; exit 1; }

# Stops here if carplay/ changed since the last release tag without a higher CarPlay versionCode
# (the dash's updater would skip it). ALLOW_SAME_CARPLAY_VERSION=1 builds anyway. ./deploy.sh build
# and ./deploy.sh release also pull main first and publish the release.
[ -n "${BINNACLE_DEPLOY:-}" ] || ./deploy.sh check
# The commit this build is from, only when nothing is changed locally (./deploy.sh release
# --no-build publishes only a build stamped with the current commit).
# Untracked files outside the build's folders (a _to_delete/, notes) don't count; see deploy.sh dirty().
COMMIT=""
if [ -z "$(git status --porcelain --untracked-files=no)" ] &&
   [ -z "$(git ls-files --others --exclude-standard -- app capture carplay buildSrc gradle settings.gradle.kts build.gradle.kts gradle.properties gradlew release.sh)" ]; then
    COMMIT=$(git rev-parse HEAD)
fi

# Unsigned release builds; the guards, tests and iCloud-copy check run inside Gradle and stop it
# on any failure.
# The source guards also run here directly, so a Gradle run with -x checkReadonly can't skip them.
app/tools/check_readonly.sh
capture/tools/check_readonly.sh
./gradlew -Pbinnacle.unsigned=true :app:assembleRelease :logger:assembleRelease
# The CarPlay app is its own Gradle build (carplay/, GPL-3.0). Its guard runs before every build of
# it (carplay/guard/check_carplay.sh); here its bypass tests and unit tests run too.
carplay/guard/test_carplay_guard.sh
(cd carplay && ./gradlew -Pxcertplay.unsigned=true :shared:testDebugUnitTest :mobile:assembleRelease)

version() { sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$1/AndroidManifest.xml"; }
OUT=build/release
rm -rf "$OUT"; mkdir -p "$OUT"
APP_IN=app/build/gradle/outputs/apk/release/app-release-unsigned.apk
LOG_IN=capture/build/gradle/outputs/apk/release/logger-release-unsigned.apk
CP_IN=carplay/mobile/build/outputs/apk/release/mobile-release-unsigned.apk
APP_OUT="$OUT/Binnacle-$(version app).apk"
LOG_OUT="$OUT/BinnacleLogger-$(version capture).apk"
cp_field() { sed -n "s/^ *$1 = \"*\([^\"]*\)\"*$/\1/p" carplay/mobile/build.gradle.kts; }
CP_NAME=$(cp_field versionName); CP_CODE=$(cp_field versionCode)
[ -n "$CP_NAME" ] && [ -n "$CP_CODE" ] || { echo "no CarPlay version in carplay/mobile/build.gradle.kts"; exit 1; }
CP_OUT="$OUT/BinnacleCarPlay-$CP_NAME.apk"

read -rsp "Keystore password for $KS_ALIAS ($KEYSTORE): " KS_PASS; echo
sign() {   # sign <in> <out> <guard command...>
    # AGP's build-info file (META-INF/com/android/build/gradle/app-metadata.properties) out, so the
    # APK holds what build.sh's does; then align and sign as build.sh does.
    cp "$1" "$OUT/tmp.apk"
    zip -qd "$OUT/tmp.apk" 'META-INF/com/android/build/gradle/*' || [ $? -eq 12 ]   # 12: nothing to remove
    "$BT/zipalign" -f -p 4 "$OUT/tmp.apk" "$OUT/aligned.apk"
    printf '%s\n' "$KS_PASS" | "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
        --ks-pass stdin --out "$2" "$OUT/aligned.apk"
    rm "$OUT/tmp.apk" "$OUT/aligned.apk"
    "$BT/apksigner" verify "$2"
    local out="$(pwd)/$2"; shift 2
    "$@" --apk "$out"    # the signed file holds only the checked code
}
sign "$APP_IN" "$APP_OUT" app/tools/check_bytecode.sh
sign "$LOG_IN" "$LOG_OUT" capture/tools/check_bytecode.sh
sign "$CP_IN" "$CP_OUT" carplay/guard/check_carplay.sh
unset KS_PASS
echo "release copies: $APP_OUT ($(du -h "$APP_OUT" | cut -f1)), $LOG_OUT, $CP_OUT ($(du -h "$CP_OUT" | cut -f1))"

# In-app updates (Advanced -> Updates, app/src/app/binnacle/Updater.java) read the LATEST GitHub
# release's binnacle-update.txt, then download its Binnacle.apk. Both names are fixed (the guards
# pin them), so the release carries the dash under that name, next to this info file.
GH="$OUT/github"; mkdir -p "$GH"
cp "$APP_OUT" "$GH/Binnacle.apk"
cp "$CP_OUT" "$GH/BinnacleCarPlay.apk"
code=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' app/AndroidManifest.xml)
sha256() { local s; s=$(shasum -a 256 "$1" 2>/dev/null || sha256sum "$1"); echo "${s%% *}"; }
sha=$(sha256 "$GH/Binnacle.apk")
cat > "$GH/binnacle-update.txt" <<EOF
# Binnacle update info: read by Advanced -> Updates on the dash (Updater.java)
versionCode=$code
versionName=$(version app)
size=$(wc -c < "$GH/Binnacle.apk" | tr -d ' ')
sha256=$sha
# the CarPlay app (com.shilapi.xcertplay), BinnacleCarPlay.apk in the same release
carplayVersionCode=$CP_CODE
carplayVersionName=$CP_NAME
carplaySize=$(wc -c < "$GH/BinnacleCarPlay.apk" | tr -d ' ')
carplaySha256=$(sha256 "$GH/BinnacleCarPlay.apk")
EOF
[ -z "$COMMIT" ] || echo "$COMMIT" > "$OUT/BUILD_COMMIT"
echo
echo "to publish: ./deploy.sh release --no-build (or by hand, as below)"
echo "for in-app updates, publish both files in $GH as the latest release, e.g.:"
echo "  gh release create v$(version app) $GH/Binnacle.apk $GH/BinnacleCarPlay.apk $GH/binnacle-update.txt $LOG_OUT --title \"Binnacle $(version app)\" --latest"
echo "(bump versionCode first: the dash only offers a higher one; bump the CarPlay app's versionCode"
echo " in carplay/mobile/build.gradle.kts when it changed. Car-image releases: --latest=false.)"
