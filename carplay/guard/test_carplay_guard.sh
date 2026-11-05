#!/bin/bash
# Checks that check_carplay.sh still refuses known bypasses: each case copies the CarPlay sources,
# plants one change and expects the guard to fail. The unchanged copy must pass.
#   carplay/guard/test_carplay_guard.sh
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
CALL=shared/src/main/java/com/shilapi/xcertplay/orchestration/HeadUnitCallState.kt
OTHER=shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt
I2C=shared/src/main/java/com/shilapi/xcertplay/transport/LinuxI2cTransport.kt
fails=0; n=0

copy() {
  rm -rf "$WORK/c"; mkdir -p "$WORK/c/guard"
  cp "$ROOT/guard/check_carplay.sh" "$WORK/c/guard/"
  for m in "$ROOT"/*/src/main; do
    rel=${m#"$ROOT"/}; mkdir -p "$WORK/c/$(dirname "$rel")"; cp -R "$m" "$WORK/c/$rel"
  done
}

# expect <pass|fail> <name> <command run in the copy>
expect() {
  n=$((n + 1)); copy
  (cd "$WORK/c" && eval "$3") || { echo "FAIL: $2: the planted change didn't apply"; fails=$((fails + 1)); return; }
  if (cd "$WORK/c" && guard/check_carplay.sh >/dev/null 2>&1); then got=pass; else got=fail; fi
  if [ "$got" = "$1" ]; then echo "ok:   $2"; else echo "FAIL: $2 (guard said $got, expected $1)"; fails=$((fails + 1)); fi
}

append() { printf '%s\n' "$2" >> "$1"; }
edit() { python3 - "$@" <<'EOF'
import sys
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p).read()
if old not in s: sys.exit(1)
open(p, "w").write(s.replace(old, new, 1))
EOF
}

expect pass "unchanged sources" "true"
expect fail "Siri message to the CAN app" "edit $CALL 'const val STATUS_CALL_OFF = \"PHONE_CALL_OFF\"' 'const val STATUS_CALL_OFF = \"SIRI_ON\"'"
expect fail "another status value" "edit $CALL 'const val STATUS_CALL_OFF = \"PHONE_CALL_OFF\"' 'const val STATUS_CALL_OFF = \"PHONE_CALL_HOLD\"'"
expect fail "broadcast not addressed to the CAN app" "edit $CALL '                    .setPackage(CANAPP_PACKAGE)' '                    .setPackage(null)'"
expect fail "broadcast to every app" "edit $CALL '                    .setPackage(CANAPP_PACKAGE)
' ''"
expect fail "another target package" "edit $CALL '\"com.xbs.canapp\"' '\"com.xbs.carservice\"'"
expect fail "a third extra" "edit $CALL '.putExtra(EXTRA_STATUS, status),' '.putExtra(EXTRA_STATUS, status).putExtra(\"cmd\", 1),'"
expect fail "a second broadcast in HeadUnitCallState" "edit $CALL '    private companion object {' '    fun again() = appContext.sendBroadcast(Intent(ACTION))
    private companion object {'"
expect fail "broadcast from another file" "append $OTHER 'internal fun ping(c: android.content.Context) = c.sendBroadcast(android.content.Intent(\"x\"))'"
expect fail "CAN app named in another file" "append $OTHER 'private const val P = \"com.x\" + \"bs.canapp\"'"
expect fail "ZLink action in another file" "append $OTHER 'private const val A = \"com.zjinnova.zlink\"'"
expect fail "IMcuService" "append $OTHER '// com.mcu.iinterface.aidl.IMcuService'"
expect fail "MCU tx call" "append $OTHER 'fun tx(s: Any) = s.toString() // txGeneralProtocol'"
expect fail "CAN provider" "append $OTHER 'private const val U = \"content://com.xbs.can.provider\"'"
expect fail "car settings app" "append $OTHER 'private const val S = \"com.xbs.carsetup\"'"
expect fail "serial port" "append $OTHER 'private const val T = \"/dev/ttyHS0\"'"
expect fail "shell command" "append $OTHER 'fun sh() = Runtime.getRuntime().exec(\"id\")'"
expect fail "process builder" "append $OTHER 'fun sh2() = ProcessBuilder(\"id\").start()'"
expect fail "settings write" "append $OTHER 'fun w(c: android.content.Context) = android.provider.Settings.System.putInt(c.contentResolver, \"x\", 1)'"
expect fail "system property" "append $OTHER '// android.os.SystemProperties.set'"
expect fail "local ADB daemon" "append $OTHER 'private const val ADB = \"127.0.0.1:5555\"'"
expect fail "car property write" "append $OTHER 'fun p(m: Any) = m.javaClass.getMethod(\"setProperty\")'"
expect fail "reflection in a new file" "append shared/src/main/java/com/shilapi/xcertplay/media/CarAudioGate.kt 'internal fun r() = Class.forName(\"x\")'"
expect fail "another native library" "edit $I2C 'System.loadLibrary(\"xcertplay_i2c\")' 'System.loadLibrary(\"mcu\")'"
expect fail "native code by path" "append $OTHER 'fun l() = System.load(\"/data/local/tmp/x.so\")'"
expect fail "I2C path check loosened" "edit $I2C 'Regex(\"/dev/i2c-[0-9]+\")' 'Regex(\"/dev/.+\")'"
expect fail "native source changed" "append shared/src/main/jni/linux_i2c_jni.c '/* changed */'"
expect fail "new native source" "printf 'int x;\n' > shared/src/main/jni/extra.c"

echo "$((n - fails))/$n cases as expected"
[ $fails = 0 ]
