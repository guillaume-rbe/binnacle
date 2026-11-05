#!/bin/bash
# CarPlay guard (Binnacle repo, carplay/). ABSOLUTE RULE: nothing in this repo ever sends anything
# onto the car's CAN bus. The CarPlay app (com.shilapi.xcertplay) has exactly one approved message
# towards the dash's MCU: ZLink's own call-state broadcast, `com.zjinnova.zlink` with
# phoneMode=carplay_wireless and status PHONE_CALL_ON / PHONE_CALL_OFF, addressed to the CAN app
# (com.xbs.canapp) only, sent from HeadUnitCallState (owner OK 4-5 Nov 2026; see the repo's
# CLAUDE.md, "MCU writes"). Everything else that could reach the CAN app, the MCU or the car is
# refused here. The Siri message (142) is not approved.
#
#   carplay/guard/check_carplay.sh             check the source (runs before every CarPlay build)
#   carplay/guard/check_carplay.sh --apk FILE  also check a built APK (release.sh runs this on the
#                                              signed copy)
# carplay/guard/test_carplay_guard.sh plants known bypasses and checks that this refuses each one.
# Never weaken or bypass it.
set -uo pipefail
cd "$(dirname "$0")/.."
APK=""
if [ "${1:-}" = "--apk" ]; then APK="${2:?--apk needs a file}"; fi

fail=0
bad() { echo "check_carplay: $*" >&2; fail=1; }

# Every module's shipped sources (tests are not in the APK). automotive/ isn't released but is
# held to the same rules.
SRC=$(ls -d */src/main 2>/dev/null)
[ -n "$SRC" ] || { echo "check_carplay: no sources found" >&2; exit 1; }
CODE=(--include='*.kt' --include='*.java' --include='*.aidl' --include='*.c' --include='*.h' --include='*.cpp' --include='*.xml' --include='CMakeLists.txt')
CALL=shared/src/main/java/com/shilapi/xcertplay/orchestration/HeadUnitCallState.kt
[ -f "$CALL" ] || bad "missing $CALL"

# 1. No car-write or MCU API anywhere: the CAN app's IMcuService and its tx side, the MCU serial
#    writer, the CAN content provider, the car-settings app, serial ports, system properties,
#    Android settings writes, shell commands, the local ADB daemon, Android Automotive property
#    writes, and the unapproved Siri call-state message.
FORBIDDEN='IMcuService|McuManager|McuSerial|txMessengerPack|txGeneralProtocol|txPerformUartPack|txUpdateProtocol|setUartNormalTransmitEnable|com\.mcu\.|com\.mct\.|com\.xbs\.can\.provider|com\.xbs\.carsetup|/dev/tty|ttyHS|SystemProperties|setprop|Settings\.(System|Global|Secure)\.put|(System|Global|Secure)\.put(Int|Long|Float|String)|WRITE_SETTINGS|WRITE_SECURE_SETTINGS|Runtime\.getRuntime|ProcessBuilder|"su"|/system/(x)?bin|:5555|\b5555\b|set(Int|Float|Boolean)?Property(\(|")|SIRI_ON|SIRI_OFF|DexClassLoader|PathClassLoader|InMemoryDexClassLoader|ServiceManager|System\.load\(|Runtime\.load|\.nativeLoad'
grep -rnE "${CODE[@]}" "$FORBIDDEN" $SRC; rc=$?
[ $rc = 1 ] || bad "forbidden car/MCU/system write reference (above), or grep failed"

# 2. The head unit's own apps (com.xbs.*, the CAN app, ZLink's action) are named in one file only,
#    HeadUnitCallState, once each. Case-insensitive, so a split or re-cased name elsewhere is
#    caught too.
for f in $(grep -rliE "${CODE[@]}" 'com\.xbs|\bxbs\b|canapp|zjinnova' $SRC); do
  [ "$f" = "$CALL" ] || bad "head-unit app named outside HeadUnitCallState: $f"
done
[ "$(grep -oiE '"[^"]*(xbs|canapp|zjinnova)[^"]*"' "$CALL" | wc -l | tr -d ' ')" = 2 ] || bad "HeadUnitCallState may name only the CAN app and ZLink's action, once each"
grep -qxF '        const val ACTION = "com.zjinnova.zlink"' "$CALL" || bad "HeadUnitCallState: action must be exactly com.zjinnova.zlink"
grep -qxF '        const val CANAPP_PACKAGE = "com.xbs.canapp"' "$CALL" || bad "HeadUnitCallState: package must be exactly com.xbs.canapp"

# 3. Broadcasts to other apps: exactly one, in HeadUnitCallState, addressed to the CAN app only.
for f in $(grep -rlE "${CODE[@]}" 'send(Ordered|Sticky)?Broadcast|sendBroadcastAsUser|sendBroadcastWithMultiplePermissions' $SRC); do
  [ "$f" = "$CALL" ] || bad "broadcast sent outside HeadUnitCallState: $f"
done
[ "$(grep -cE 'send[A-Za-z]*Broadcast' "$CALL")" = 1 ] || bad "HeadUnitCallState may send exactly one broadcast"
grep -qxF '            appContext.sendBroadcast(' "$CALL" || bad "HeadUnitCallState: plain sendBroadcast only"
grep -qxF '                Intent(ACTION)' "$CALL" || bad "HeadUnitCallState: the broadcast must be Intent(ACTION)"
grep -qxF '                    .setPackage(CANAPP_PACKAGE)' "$CALL" || bad "HeadUnitCallState: the broadcast must be addressed to the CAN app only"
[ "$(grep -cE '\.setPackage\(|\.setComponent\(|\.setClass|ComponentName|\.setAction\(|\.setData\(|\.putExtras?\(|Intent\(' "$CALL")" = 4 ] \
    || bad "HeadUnitCallState: the intent may only be Intent(ACTION), setPackage and the two extras"
[ "$(grep -cE '\.putExtra\(' "$CALL")" = 2 ] || bad "HeadUnitCallState: exactly two extras (phoneMode, status)"
grep -qxF '                    .putExtra(EXTRA_PHONE_MODE, PHONE_MODE_WIRELESS)' "$CALL" || bad "HeadUnitCallState: phoneMode extra changed"
grep -qxF '                    .putExtra(EXTRA_STATUS, status),' "$CALL" || bad "HeadUnitCallState: status extra changed"
grep -qxF '        val status = if (on) STATUS_CALL_ON else STATUS_CALL_OFF' "$CALL" || bad "HeadUnitCallState: status must be PHONE_CALL_ON or PHONE_CALL_OFF"
# Every string literal in the file, pinned: no other status, extra or target can appear.
LITERALS=$(grep -oE '"[^"]*"' "$CALL" | LC_ALL=C sort -u | tr '\n' '|')
EXPECT='"CarPlay call"|"PHONE_CALL_OFF"|"PHONE_CALL_ON"|"appStateID"|"appStates"|"carplay_wireless"|"com.xbs.canapp"|"com.zjinnova.zlink"|"entity"|"head unit call state $status not sent: ${error.message}"|"head unit call state $status"|"on"|"phoneMode"|"state"|"status"|'
[ "$LITERALS" = "$EXPECT" ] || bad "HeadUnitCallState: string literals changed: $LITERALS"

# 4. Nothing else reaches another app's service by binder on the head unit's side: the CAN app is
#    named only in HeadUnitCallState (2), which binds nothing.
grep -nE 'bindService|startService|startForegroundService|ContentResolver|contentResolver|IBinder|transact\(' "$CALL" && bad "HeadUnitCallState may only send its broadcast (above)"

# 5. Reflection only in the files that use it today (Wi-Fi hotspot getters, Bluetooth isConnected,
#    the Android Automotive read-only backend, the automotive check). A new file needs review.
REFLECT='java\.lang\.reflect|forName\(|getMethod\(|getDeclared(Method|Field|Constructor)'
ALLOWED_REFLECT=" common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotManager.kt shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt shared/src/main/java/com/shilapi/xcertplay/vehicle/AndroidCarPropertyBackend.kt "
for f in $(grep -rlE --include='*.kt' --include='*.java' "$REFLECT" $SRC); do
  case "$ALLOWED_REFLECT" in *" $f "*) ;; *) bad "reflection outside the reviewed files: $f";; esac
done

# 6. Native code: the one I2C library, from pinned sources, loaded once; its open() takes only
#    /dev/i2c-N (checked in Kotlin and in C).
for f in $(grep -rlE --include='*.kt' --include='*.java' 'loadLibrary' $SRC); do
  [ "$f" = shared/src/main/java/com/shilapi/xcertplay/transport/LinuxI2cTransport.kt ] || bad "native library loaded outside LinuxI2cTransport: $f"
done
[ "$(grep -rhoE --include='*.kt' --include='*.java' 'loadLibrary\("[^"]*"\)' $SRC | sort -u)" = 'loadLibrary("xcertplay_i2c")' ] || bad "only libxcertplay_i2c may be loaded"
[ "$(grep -c 'loadLibrary' shared/src/main/java/com/shilapi/xcertplay/transport/LinuxI2cTransport.kt)" = 1 ] || bad "LinuxI2cTransport may load its library once"
grep -qxF '        private val I2C_DEVICE_PATH = Regex("/dev/i2c-[0-9]+")' shared/src/main/java/com/shilapi/xcertplay/transport/LinuxI2cTransport.kt || bad "LinuxI2cTransport: device path check changed"
NATIVE=$(find $SRC -type f \( -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.S' -o -name '*.s' -o -name 'CMakeLists.txt' -o -name 'Android.mk' -o -name '*.so' \) | LC_ALL=C sort | tr '\n' ' ')
[ "$NATIVE" = "shared/src/main/jni/CMakeLists.txt shared/src/main/jni/linux_i2c_jni.c " ] || bad "native sources changed: $NATIVE"
sha() { (shasum -a 256 "$1" 2>/dev/null || sha256sum "$1") | cut -d' ' -f1; }
# Pins: update only after reviewing the change (and say so in the commit).
[ "$(sha shared/src/main/jni/CMakeLists.txt)" = "8412282f852c73551c6819ae595281253930a1f9589ad6682ec2bd27e428104d" ] || bad "CMakeLists.txt changed: review and update the pin"
[ "$(sha shared/src/main/jni/linux_i2c_jni.c)" = "97c6c96c85cdcc8c4ef55d270710fc3d0ab89d8b22327e8a71392eb29fcf2d86" ] || bad "linux_i2c_jni.c changed: review and update the pin"

# 7. The built APK, if given: one native library (plus Compose's graphics one), and none of the
#    forbidden names in its code (dex strings are plain text for these ASCII names).
if [ -n "$APK" ]; then
  [ -f "$APK" ] || bad "no APK at $APK"
  if [ -f "$APK" ]; then
    LIBS=$(unzip -Z1 "$APK" | grep -E '^lib/' | sed 's#^lib/[^/]*/##' | LC_ALL=C sort -u | tr '\n' ' ')
    case "$LIBS" in
      "libxcertplay_i2c.so "|"libandroidx.graphics.path.so libxcertplay_i2c.so ") ;;
      *) bad "unexpected native libraries in the APK: $LIBS";;
    esac
    TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
    unzip -qo "$APK" 'classes*.dex' -d "$TMP" || bad "no dex in the APK"
    DEXBAD='IMcuService|McuSerial|McuManager|txMessengerPack|txGeneralProtocol|txPerformUartPack|txUpdateProtocol|setUartNormalTransmitEnable|com\.xbs\.can\.provider|com\.xbs\.carsetup|com\.mcu\.iinterface|com/mcu/iinterface|com\.mct\.serial|SIRI_ON|SIRI_OFF|/dev/tty'
    LC_ALL=C grep -aoE "$DEXBAD" "$TMP"/classes*.dex | sort -u | grep . && bad "forbidden names in the APK's code (above)"
    [ "$(LC_ALL=C grep -aoE 'com\.xbs\.[A-Za-z0-9_.]*' "$TMP"/classes*.dex | sed 's/^[^:]*://' | sort -u)" = com.xbs.canapp ] \
        || bad "the APK names a head-unit app other than com.xbs.canapp"
  fi
fi

[ $fail = 0 ] && echo "check_carplay: OK${APK:+ (source and $APK)}"
exit $fail
