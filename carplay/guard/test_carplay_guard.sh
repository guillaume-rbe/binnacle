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
  for m in "$ROOT"/*/src/*; do
    case "$m" in */src/test*|*/src/androidTest*) continue;; esac
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

NEWF=shared/src/main/java/com/shilapi/xcertplay/media/CarAudioGate.kt
expect fail "settings write by import alias" "append $OTHER 'import android.provider.Settings.System as S'"
expect fail "settings write by member import" "append $OTHER 'import android.provider.Settings.System.putInt'"
expect fail "process by member import" "append $OTHER 'import java.lang.Runtime.getRuntime'"
expect fail "another class loader" "append $OTHER 'fun cl(p: String) = dalvik.system.DelegateLastClassLoader(p, null)'"
expect fail "reflection by constructor in a new file" "append $NEWF 'internal fun r(c: Class<*>) = c.getConstructor().newInstance()'"
expect fail "reflection by method handle in a new file" "append $NEWF 'internal fun r() = java.lang.invoke.MethodHandles.lookup()'"
expect fail "bindService in another file" "append $NEWF 'internal fun b(c: android.content.Context, s: android.content.ServiceConnection) = c.bindService(android.content.Intent(), s, 1)'"
expect fail "a third bindService in a reviewed file" "append $OTHER 'internal fun b(c: android.content.Context, s: android.content.ServiceConnection) = c.bindService(android.content.Intent(), s, 1)'"
expect fail "service start" "append $OTHER 'internal fun s(c: android.content.Context) = c.startService(android.content.Intent())'"
expect fail "raw transact" "append $OTHER 'internal fun t(b: android.os.IBinder) = b.transact(1, android.os.Parcel.obtain(), null, 0)'"
expect fail "Messenger send in another file" "append $OTHER 'internal fun m(x: android.os.Messenger) = x.send(Message.obtain())'"
expect fail "ContentResolver write" "append $OTHER 'internal fun w(c: android.content.Context, u: android.net.Uri) = c.contentResolver.delete(u, null, null)'"
expect fail "ContentResolver in a new file" "append $NEWF 'internal fun q(c: android.content.Context) = c.contentResolver'"
MEDIA=shared/src/main/java/com/shilapi/xcertplay/media/CarPlayMediaSessionService.kt
expect fail "VPN binding retargeted on its constructor" "edit $OTHER 'Intent(appContext, CarPlayVpnService::class.java)' 'Intent(appContext, CarPlayVpnService::class.java).setClassName(\"com.example\", \"com.example.S\")'"
expect fail "VPN binding retargeted on a line in between" "edit $OTHER '            if (!appContext.bindService(intent,' '            intent.setPackage(\"com.example\")
            if (!appContext.bindService(intent,'"
expect fail "media binding retargeted" "edit $MEDIA '.setAction(MediaSessionService.SERVICE_INTERFACE)' '.setAction(MediaSessionService.SERVICE_INTERFACE).setPackage(\"com.example\")'"
expect fail "Messenger send with a variable message" "append $NEWF 'internal fun s(t: android.os.Messenger, m: android.os.Message) = t.send(m)'"
expect fail "ContentResolver write through a scoped receiver" "append $OTHER 'internal fun d(c: android.content.Context, u: android.net.Uri) = c.contentResolver.let { it.delete(u, null, null) }'"
expect fail "ContentResolver write through a renamed variable" "edit $OTHER '                    resolver.openInputStream(privateKeyUri)' '                    resolver.also { r -> r.delete(privateKeyUri, null, null) }.openInputStream(privateKeyUri)'"
expect fail "service start by callable reference" "append $NEWF 'internal fun s(c: android.content.Context, i: android.content.Intent) { val start = c::startService; start(i) }'"
expect fail "bindService by callable reference" "append $NEWF 'internal fun b(c: android.content.Context) = c::bindService'"
expect fail "resolver write by callable reference" "append $OTHER 'internal fun d(r: android.content.ContentResolver) = r::delete'"
expect fail "native load by static import" "printf 'package x;\nimport static java.lang.System.load;\nclass L { static void l(String p) { load(p); } }\n' > shared/src/main/java/com/shilapi/xcertplay/media/L.java"
expect fail "member import from a platform class" "append $OTHER 'import java.lang.System.loadLibrary'"
expect fail "star import of a platform class's members" "append $OTHER 'import java.lang.System.*'"
expect fail "raw transact with an implicit receiver" "append $NEWF 'internal fun raw(b: android.os.IBinder, p: android.os.Parcel) = b.run { transact(1, p, null, 0) }'"
expect fail "service start with an implicit receiver" "append $NEWF 'internal fun s(c: android.content.Context, i: android.content.Intent) = with(c) { startService(i) }'"
expect fail "code in src/release" "mkdir -p shared/src/release/java && printf 'fun x() = Runtime.getRuntime()\n' > shared/src/release/java/X.kt"

echo "$((n - fails))/$n cases as expected"
[ $fails = 0 ]
