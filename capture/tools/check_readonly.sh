#!/bin/bash
# ABSOLUTE RULE: the logger never sends anything to the CAN bus or the MCU. It only receives.
# Same idea as app/tools/check_readonly.sh, pinned to what the logger needs: build.sh runs it
# before compiling and fails the build if the source could write to the car or leave the unit.
set -uo pipefail
cd "$(dirname "$0")/.."
SRC="src AndroidManifest.xml res"
SVC=src/app/binnacle/logger/CaptureService.java
UI=src/app/binnacle/logger/MainActivity.java
fail=0
bad() { echo "check_readonly (logger): $*" >&2; fail=1; }

# 1. No car-write API, settings or property writes, and no content-resolver writes other than the
#    export (checked in 4).
FORBIDDEN='txMessengerPack|txGeneralProtocol|txPerformUartPack|txUpdateProtocol|setUartNormalTransmitEnable|McuSerial|McuManager|writeData|com\.mct\.serial|com\.xbs\.can\.provider|com\.xbs\.carsetup|ContentProviderClient|\.update\(|\.delete\([^)]|bulkInsert|applyBatch|openFileDescriptor|/dev/tty|Settings\.(System|Global|Secure)|SystemProperties|WRITE_SETTINGS|WRITE_SECURE_SETTINGS'
grep -rnE "$FORBIDDEN" $SRC; rc=$?
[ $rc = 1 ] || bad "forbidden car-write reference found (above), or grep failed"

# 1a. What javac reads is what this script reads: no symlinks, no \u escapes.
links=$(find src res -type l); [ -z "$links" ] || bad "symlinks in the tree: $links"
grep -rnE '\\u+[0-9a-fA-F]{4}' --include='*.java' src; rc=$?
[ $rc = 1 ] || bad "unicode escapes in Java source (above), or grep failed"

# 1b. No reflection, loaded code, hidden services, processes, sockets or network.
DYNAMIC='java\.lang\.reflect|\.get(Declared)?(Method|Field|Constructor)s?\(|forName\(|newInstance\(|new +[A-Za-z]*ClassLoader\(|DexClassLoader|loadLibrary|System\.load\(|ServiceManager|java\.nio\.file|java\.nio\.channels|PrintStream|ProcessBuilder|getRuntime|Socket|java\.net\.|HttpURLConnection|URLConnection|\.send\('
grep -rnE "$DYNAMIC" src; rc=$?
[ $rc = 1 ] || bad "reflection, processes, sockets or network (above), or grep failed"

# 2. No AIDL stubs or proxies.
find src -name '*.aidl' | grep . && bad "AIDL files are not allowed"
grep -rnE 'IMcuService\.(Stub|Proxy)|extends +Binder|implements +IInterface' src && bad "service stub/proxy found (above)"

# 3. Binder: only CaptureService, exactly register (1) and unregister (2) on the CAN service.
hits=$(grep -rlwE 'transact' src)
[ "$hits" = "$SVC" ] || bad "binder transact outside CaptureService: $hits"
[ "$(grep -oE '(^|[^A-Za-z0-9_])transact *\(' "$SVC" | wc -l | tr -d ' ')" = 2 ] || bad "CaptureService must have exactly two transact() calls"
grep -qE '^ *binder\.transact\(1, d, r, 0\); r\.readException\(\); registered = r\.readInt\(\) != 0;$' "$SVC" || bad "register call changed"
grep -qE '^ *try \{ d\.writeInterfaceToken\(DESCRIPTOR\); d\.writeInt\(1\); messenger\.writeToParcel\(d, 0\); binder\.transact\(2, d, r, 0\); r\.readException\(\); \}$' "$SVC" || bad "unregister call changed"
grep -qxE ' *private static final String DESCRIPTOR = "com\.mcu\.iinterface\.aidl\.IMcuService";' "$SVC" || bad "DESCRIPTOR changed"
grep -qxE ' *private static final String SVC_ACTION = "com\.xbs\.canapp\.action\.mcuservice";' "$SVC" || bad "SVC_ACTION changed"
grep -qxE ' *private static final String SVC_PKG = "com\.xbs\.canapp";' "$SVC" || bad "SVC_PKG changed"

# 4. Writes: log files only from CaptureService (FileWriter in its private files dir); the content
#    resolver only in MainActivity's export to the shared Downloads folder, when the user asks.
for f in $(grep -rlE 'FileWriter|FileOutputStream|RandomAccessFile' src); do
  [ "$f" = "$SVC" ] || bad "file writes outside CaptureService: $f"
done
grep -rnE 'FileOutputStream|RandomAccessFile' src && bad "only FileWriter is allowed (above)"
grep -qF 'static File logDir(Context c) { return new File(c.getFilesDir(), "logs"); }' "$SVC" || bad "logs must stay in the private files dir"
for f in $(grep -rlE 'ContentResolver|getContentResolver|ContentValues|openOutputStream|\.insert\(' src); do
  [ "$f" = "$UI" ] || bad "content resolver used outside MainActivity: $f"
done
# counted with all whitespace removed, so "getContentResolver ()" or a line break can't hide a third
[ "$(tr -d ' \t\r\n' < "$UI" | grep -o 'getContentResolver' | wc -l | tr -d ' ')" = 2 ] || bad "MainActivity may use the content resolver exactly twice (insert, openOutputStream)"
[ "$(tr -d ' \t\r\n' < "$UI" | grep -oE '\.(insert|openOutputStream)\(' | wc -l | tr -d ' ')" = 2 ] || bad "MainActivity may insert once and write once"
grep -rnwE 'Uri\.(parse|fromFile|withAppendedPath|fromParts)|Uri\.Builder|ContentUris' src && bad "the logger may not build content URIs (above)"
grep -qF 'getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);' "$UI" || bad "export may only insert into MediaStore.Downloads"
grep -qF 'getContentResolver().openOutputStream(uri)' "$UI" || bad "export may only write the row it inserted"

# 5. Talking to other apps: bind the CAN service (CaptureService), announce the state to same-key
#    apps (StateReceiver), start/stop its own service (MainActivity). Nothing else.
for f in $(grep -rlE 'bindService' src); do [ "$f" = "$SVC" ] || bad "bindService outside CaptureService: $f"; done
for f in $(grep -rlE 'sendBroadcast|sendOrderedBroadcast|sendStickyBroadcast' src); do
  [ "$f" = src/app/binnacle/logger/StateReceiver.java ] || bad "broadcast outside StateReceiver: $f"
done
grep -rnE 'sendOrderedBroadcast|sendStickyBroadcast' src && bad "ordered/sticky broadcasts are not allowed (above)"
grep -qF 'c.sendBroadcast(new Intent(ACTION_STATE).putExtra("running", running), PERMISSION);' src/app/binnacle/logger/StateReceiver.java \
    || bad "the state broadcast must stay limited to CONTROL_LOGGER holders"
for f in $(grep -rlE 'startService|startForegroundService' src); do [ "$f" = "$UI" ] || bad "service started outside MainActivity: $f"; done

# 6. Manifest: exactly these permissions. No internet (nothing can leave the unit), no location,
#    no shared-storage or settings writes.
perms=$(grep -oE '<uses-permission android:name="[^"]+"' AndroidManifest.xml | sed -E 's/.*name="([^"]+)"/\1/' | sort)
expected=$(printf '%s\n' android.permission.FOREGROUND_SERVICE app.binnacle.permission.CONTROL_LOGGER com.xbs.permission.READ_CAN_DATABASE | sort)
[ "$perms" = "$expected" ] || bad "logger permissions changed (got: $(echo $perms))"
grep -nE 'android:debuggable|android:allowBackup="true"|android:sharedUserId' AndroidManifest.xml && bad "debuggable, backup or shared user id (above)"

[ $fail -eq 0 ] && echo "check_readonly (logger): OK (receive-only: IMcuService register/unregister only)"
exit $fail
