#!/bin/bash
# Builds the dash and the drive logger with Gradle and signs both with the release key:
#   ./release.sh   -> build/release/Binnacle-<version>.apk and BinnacleLogger-<version>.apk
# Asks for the keystore password once, on the terminal, and hands it to apksigner on stdin; nothing
# stores it. Both APKs use the same key (they share a signature permission), and every release must
# use it too, or `adb install -r` refuses the upgrade.
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

# Unsigned release builds; the guards, tests and iCloud-copy check run inside Gradle and stop it
# on any failure.
./gradlew -Pbinnacle.unsigned=true :app:assembleRelease :logger:assembleRelease

version() { sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$1/AndroidManifest.xml"; }
OUT=build/release
rm -rf "$OUT"; mkdir -p "$OUT"
APP_IN=app/build/gradle/outputs/apk/release/app-release-unsigned.apk
LOG_IN=capture/build/gradle/outputs/apk/release/logger-release-unsigned.apk
APP_OUT="$OUT/Binnacle-$(version app).apk"
LOG_OUT="$OUT/BinnacleLogger-$(version capture).apk"

read -rsp "Keystore password for $KS_ALIAS ($KEYSTORE): " KS_PASS; echo
sign() {   # sign <in> <out> <module>
    # AGP's build-info file (META-INF/com/android/build/gradle/app-metadata.properties) out, so the
    # APK holds what build.sh's does; then align and sign as build.sh does.
    cp "$1" "$OUT/tmp.apk"
    zip -qd "$OUT/tmp.apk" 'META-INF/com/android/build/gradle/*' || [ $? -eq 12 ]   # 12: nothing to remove
    "$BT/zipalign" -f -p 4 "$OUT/tmp.apk" "$OUT/aligned.apk"
    printf '%s\n' "$KS_PASS" | "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
        --ks-pass stdin --out "$2" "$OUT/aligned.apk"
    rm "$OUT/tmp.apk" "$OUT/aligned.apk"
    "$BT/apksigner" verify "$2"
    "$3/tools/check_bytecode.sh" --apk "$(pwd)/$2"    # the signed file holds only the checked code
}
sign "$APP_IN" "$APP_OUT" app
sign "$LOG_IN" "$LOG_OUT" capture
unset KS_PASS
echo "release copies: $APP_OUT ($(du -h "$APP_OUT" | cut -f1)), $LOG_OUT"
