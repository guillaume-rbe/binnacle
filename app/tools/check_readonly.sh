#!/bin/bash
# ABSOLUTE RULE: this app never sends anything to the CAN bus or the MCU. It only receives.
# Dash-local writes (a setting or property on the unit itself) are allowed only when the owner has
# approved that exact change, and only as a pinned LocalShell command below (CLAUDE.md).
# This guard runs before every build (build.sh) and fails it if the source could write to the car.
set -uo pipefail
cd "$(dirname "$0")/.."
SRC="src AndroidManifest.xml res"
fail=0
bad() { echo "check_readonly: $*" >&2; fail=1; }

# 1. No reference to any car-write API: the IMcuService send/tx methods, the MCU serial writer,
#    the CAN content provider, the car-settings app, or content-resolver writes. (A content-resolver
#    delete always takes arguments; File.delete() with none, for the app's own files, is fine.)
FORBIDDEN='txMessengerPack|txGeneralProtocol|txPerformUartPack|txUpdateProtocol|setUartNormalTransmitEnable|McuSerial|McuManager|writeData|com\.mct\.serial|com\.xbs\.can\.provider|com\.xbs\.carsetup|ContentProviderClient|\.insert\(|\.update\(|\.delete\([^)]|bulkInsert|applyBatch|openOutputStream|openFileDescriptor|/dev/tty|\.put(Int|Long|Float|String)\(\s*(c\.)?getContentResolver|Settings\.(System|Global|Secure)\.put|(System|Global|Secure)\.put(Int|Long|Float|String)|SystemProperties|WRITE_SETTINGS|WRITE_SECURE_SETTINGS'
grep -rnE "$FORBIDDEN" $SRC; rc=$?
[ $rc = 1 ] || bad "forbidden car-write reference found (above), or grep failed"

# 1c. Nothing that could reach an API by name (reflection, loaded code, hidden services), write a
#     file a way the checks below don't look for, or send on a Messenger (the feed's Messenger only
#     ever receives). check_bytecode.sh also catches all of these.
DYNAMIC='java\.lang\.reflect|\.get(Declared)?(Method|Field|Constructor)s?\(|forName\(|newInstance\(|DexClassLoader|PathClassLoader|InMemoryDexClassLoader|loadLibrary|System\.load\(|ServiceManager|java\.nio\.file|java\.nio\.channels|PrintStream|PrintWriter\(\s*(new +File|")|\.send\('
grep -rnE "$DYNAMIC" src; rc=$?
[ $rc = 1 ] || bad "reflection, dynamic code or unchecked file/socket API (above), or grep failed"

# 1d. Sending to other apps only from the fixed places (logger query, camera close, logger start).
for f in $(grep -rlE 'sendBroadcast|sendOrderedBroadcast|sendStickyBroadcast' src); do
  case "$f" in src/app/binnacle/DashApp.java|src/app/binnacle/Launch.java) ;; *) bad "broadcast sent outside DashApp/Launch: $f";; esac
done
for f in $(grep -rlE 'startService|startForegroundService|bindService' src); do
  case "$f" in src/app/binnacle/DashActivity.java|src/app/binnacle/CanFeed.java|src/app/binnacle/CarPlayLink.java) ;; *) bad "service started or bound outside DashActivity/CanFeed/CarPlayLink: $f";; esac
done
grep -rnE 'sendOrderedBroadcast|sendStickyBroadcast' src && bad "ordered/sticky broadcasts are not allowed (above)"

# 1e. Audio settings: only the start-up media volume (owner OK 4 Nov), exactly once, in StartupVolume.
#     Android's own music volume, which the wheel keys change too; nothing else (ringer, other
#     streams, mute) may be set.
VOL='src/app/binnacle/StartupVolume.java'
for f in $(grep -rlE 'set(Stream|Ringer|Mode)Volume|adjust(Stream|Suggested)?Volume|setRingerMode|setMicrophoneMute|setMasterMute|setStreamMute|setSpeakerphoneOn|setMode\(' src); do
  [ "$f" = "$VOL" ] || bad "audio setting changed outside StartupVolume: $f"
done
[ "$(grep -cE 'set(Stream|Ringer|Mode)Volume|adjust(Stream|Suggested)?Volume|setRingerMode|setMicrophoneMute|setMasterMute|setStreamMute|setSpeakerphoneOn|setMode\(' "$VOL")" = 1 ] || bad "StartupVolume may change exactly one audio setting"
grep -qxE '\s*audio\.setStreamVolume\(AudioManager\.STREAM_MUSIC, level, 0\);' "$VOL" || bad "StartupVolume may only set the music volume"

# 1a. What javac reads must be what this script reads: no symlinks (grep -r skips them, find and
#     javac follow them) and no \u escapes (javac decodes them before parsing, anywhere in a file,
#     so \u0074ransact is transact). Write the character itself instead.
links=$(find src res assets -type l); [ -z "$links" ] || bad "symlinks in the tree: $links"
grep -rnE '\\u+[0-9a-fA-F]{4}' --include='*.java' src; rc=$?
[ $rc = 1 ] || bad "unicode escapes in Java source (above), or grep failed"

# 1b. A content resolver may be used in StockScreens.java only, for exactly one read:
#     Settings.System.getInt(c.getContentResolver(), KEY_LAYOUT, 0). Fails closed on grep errors.
ALLOWED='src/app/binnacle/StockScreens.java'
hits=$(grep -rlE 'ContentResolver|getContentResolver' $SRC); rc=$?
[ $rc -le 1 ] || bad "grep failed scanning for content resolver use"
for f in $hits; do [ "$f" = "$ALLOWED" ] || bad "content resolver used outside StockScreens: $f"; done
[ "$(grep -oE 'getContentResolver|ContentResolver' "$ALLOWED" | wc -l | tr -d ' ')" = 1 ] || bad "StockScreens must use the content resolver exactly once"
grep -q 'Settings.System.getInt(c.getContentResolver(), KEY_LAYOUT, 0)' "$ALLOWED" || bad "StockScreens may only read Settings.System xbs_carmsgdisppost"
grep -nE '\.(put|call|query|register|notify|acquire)[A-Za-z]*\(' "$ALLOWED" && bad "StockScreens may only read (above)"

# 2. No AIDL stubs or proxies for the stock service (so no write method can even be declared).
find src -name '*.aidl' | grep . && bad "AIDL files are not allowed"
grep -rnE 'IMcuService\.(Stub|Proxy)|extends +Binder|implements +IInterface' src && bad "service stub/proxy found (above)"

# 3. CAN feed: exactly one class (CanFeed) may make binder calls to the CAN service, and only IMcuService
#    transactions 1 and 2. (The only other binder calls are CarPlayLink to xcertplay, pinned in 3b.)
#    (Whole-line matches, so a pinned text kept in a comment beside a changed one doesn't pass.
#    tools/check_bytecode.sh checks the same on the compiled class.)
hits=$(grep -rlwE 'transact' src | sort | tr '\n' ' ')
[ "$hits" = "src/app/binnacle/CanFeed.java src/app/binnacle/CarPlayLink.java " ] || bad "binder transact outside CanFeed/CarPlayLink: $hits"
[ "$(grep -E '(^|[^A-Za-z0-9_])transact *\(' src/app/binnacle/CanFeed.java | grep -cvE '^ *(\*|//)')" = 1 ] || bad "CanFeed must have exactly one transact() call"
grep -qxE ' *b\.transact\(txn, data, r, 0\);' src/app/binnacle/CanFeed.java || bad "CanFeed's transact call changed"
grep -qxE ' *private static final int TX_REGISTER = 1, TX_UNREGISTER = 2;' src/app/binnacle/CanFeed.java || bad "transaction codes changed"
[ "$(grep -cwE 'TX_REGISTER|TX_UNREGISTER' src/app/binnacle/CanFeed.java)" = 4 ] || bad "unexpected use of the transaction codes"
grep -qxE ' *if \(txn != TX_REGISTER && txn != TX_UNREGISTER\) \{' src/app/binnacle/CanFeed.java || bad "transaction allow-list changed"
[ "$(grep -cE 'call\(TX_(REGISTER|UNREGISTER), ' src/app/binnacle/CanFeed.java)" = 2 ] || bad "call() used with something other than TX_REGISTER / TX_UNREGISTER"
[ "$(grep -cE '[^.a-zA-Z]call\(' src/app/binnacle/CanFeed.java)" = 3 ] || bad "unexpected call() use in CanFeed"

# 3b. CarPlayLink (owner OK 4 Nov, see CLAUDE.md "CarPlay in the dash"): binder calls only to
#     xcertplay's CarPlay service, an app on the unit. One bind to a fixed component, one transact
#     whose code is checked against the ICarPlayEmbed range first, fixed codes and interface token.
#     tools/check_bytecode.sh checks the same on the compiled class.
L=src/app/binnacle/CarPlayLink.java
grep -qxF '    private static final String PKG = "com.shilapi.xcertplay";' "$L" || bad "CarPlayLink package changed"
grep -qxF '    private static final String SERVICE = "com.shilapi.xcertplay.CarPlayEmbedService";' "$L" || bad "CarPlayLink service changed"
grep -qxF '    private static final String DESCRIPTOR = "com.shilapi.xcertplay.embed.ICarPlayEmbed";' "$L" || bad "CarPlayLink interface changed"
grep -qxF '    private static final int TX_VERSION = 1, TX_LISTENER = 2, TX_ATTACH = 3, TX_DETACH = 4, TX_TOUCH = 5,' "$L" \
    && grep -qxF '            TX_KEY = 6, TX_START = 7, TX_DISCONNECT = 8, TX_SETTINGS = 9, TX_SIZES = 10;' "$L" || bad "CarPlayLink transaction codes changed"
[ "$(grep -E '(^|[^A-Za-z0-9_])transact *\(' "$L" | grep -cvE '^ *(\*|//)')" = 1 ] || bad "CarPlayLink must have exactly one transact() call"
grep -qxF '            if (!b.transact(txn, data, reply, oneway ? IBinder.FLAG_ONEWAY : 0)) return false;' "$L" || bad "CarPlayLink's transact call changed"
grep -qxF '        if (txn < TX_VERSION || txn > TX_SIZES) throw new IllegalArgumentException("not an ICarPlayEmbed call");' "$L" || bad "CarPlayLink's code check changed"
[ "$(grep -cE '[^n]bindService\(' "$L")" = 1 ] && grep -qxF '        Intent i = new Intent().setComponent(new ComponentName(PKG, SERVICE));' "$L" || bad "CarPlayLink may bind only xcertplay's CarPlay service"
grep -nE '\.send\(|Messenger\(binder|Messenger\(service|new Messenger\([^n]' "$L" && bad "CarPlayLink may only receive on its Messenger (above)"

# 4. Local shell (the unit's own ADB on 127.0.0.1): one class, fixed commands, nothing near the car.
hits=$(grep -rlwE 'Socket|ServerSocket|SocketChannel|ProcessBuilder|getRuntime' src)
[ -z "$hits" ] || [ "$hits" = "src/app/binnacle/LocalShell.java" ] || bad "sockets/processes outside LocalShell: $hits"
grep -nwE 'ProcessBuilder|getRuntime|exec' src/app/binnacle/LocalShell.java && bad "LocalShell must not start processes (above)"
# every line of the Command list is one constant with one plain string: no concatenation, no
# constant split over lines (comments and blank lines aside)
awk '/enum Command \{/ { on = 1; next } /final String shell;/ { on = 0 } on' src/app/binnacle/LocalShell.java \
    | grep -vE '^ *(/\*\*|\*|\*/|//)|^ *$' | grep -vxE ' *[A-Z_]+\("[^"\\]*"\)[,;]' && bad "LocalShell commands must each be one plain string on one line (above)"
cmds=$(grep -oE '^ *[A-Z_]+\("[^"]*"\)' src/app/binnacle/LocalShell.java | sed -E 's/^ *//')
expected='END_CARPLAY("svc wifi disable && sleep 2 && svc wifi enable && echo BINNACLE_OK")
BT_AUTOCONNECT_GET("settings get system xbs_isautoconnect && echo BINNACLE_OK")
BT_AUTOCONNECT_ON("settings put system xbs_isautoconnect true && echo BINNACLE_OK")
BT_AUTOCONNECT_OFF("settings put system xbs_isautoconnect false && echo BINNACLE_OK")
WIFI_SCANS_ON("service call wifi 58 i32 1 && echo BINNACLE_OK")
WIFI_SCANS_OFF("service call wifi 58 i32 0 && echo BINNACLE_OK")
WIFI_REJOIN("service call wifi 58 i32 1 && svc wifi enable && echo BINNACLE_OK")'
[ "$cmds" = "$expected" ] || bad "LocalShell command list changed: review it, then update this guard"
[ "$(grep -c 'shell:' src/app/binnacle/LocalShell.java)" = 1 ] && grep -qF '("shell:" + cmd.shell + "\0")' src/app/binnacle/LocalShell.java || bad "LocalShell may only open shell:<Command.shell>"
[ "$(grep -cw 'A_OPEN' src/app/binnacle/LocalShell.java)" = 2 ] || bad "LocalShell may open exactly one ADB stream"
grep -rnE 'LocalShell\.run\(' src | grep -vE 'LocalShell\.run\(cmd\)' && bad "LocalShell.run must take a Command (above)"

# 5. File writes: only CameraPane (the camera decoder's two nodes: local hardware, not the car) and
#    CarPacks (car pictures, only inside the app's own files dir, only entries that stay inside it).
for f in $(grep -rlE 'FileWriter|FileOutputStream|RandomAccessFile' src); do
  case "$f" in src/app/binnacle/CameraPane.java|src/app/binnacle/CarPacks.java) ;; *) bad "file writes outside CameraPane/CarPacks: $f";; esac
done
grep -q 'DECODER_DIR = "/sys/ziqi/camera", INPUT = "2", SIGNAL = "AHD_1080P_30";' src/app/binnacle/CameraPane.java || bad "camera decoder write target changed"
[ "$(grep -cE 'writeDecoder\("' src/app/binnacle/CameraPane.java)" = 2 ] || bad "CameraPane may write only channel and N_P_pre_format"
[ "$(grep -c 'new FileOutputStream(' src/app/binnacle/CarPacks.java)" = 1 ] || bad "CarPacks must write files in one place only"
grep -q 'if (!isInside(tmp, out)) throw' src/app/binnacle/CarPacks.java || bad "CarPacks must check every entry stays inside the pack folder"
grep -q 'File root = c.getExternalFilesDir(null);' src/app/binnacle/CarPacks.java || bad "CarPacks must write only under the app's own files dir"

# 6. Internet: only CarPacks and Updater, only GET from the project's own GitHub releases, HTTPS.
for f in $(grep -rlE 'HttpURLConnection|HttpsURLConnection|new URL\(|openConnection|URLConnection' src); do
  case "$f" in src/app/binnacle/CarPacks.java|src/app/binnacle/Updater.java) ;; *) bad "network access outside CarPacks/Updater: $f";; esac
done
grep -qF 'RELEASE_BASE = "https://github.com/lotech/binnacle/releases/download/cars-v3/";' src/app/binnacle/CarPacks.java || bad "CarPacks download source changed"
[ "$(grep -c 'new URL(' src/app/binnacle/CarPacks.java)" = 1 ] && grep -qF 'new URL(RELEASE_BASE + packName(hand, colour))' src/app/binnacle/CarPacks.java || bad "CarPacks may only fetch RELEASE_BASE + packName"
grep -nE 'setRequestMethod|setDoOutput|getOutputStream' src/app/binnacle/CarPacks.java && bad "CarPacks may only download (GET), never send (above)"

# 6b. Updater (Advanced -> Updates): GET only, from the latest release's two fixed files, and it may
#     install only this app itself (the session is locked to our own package name; Android then
#     insists on the same signing key and asks the user). Nothing else may use the installer.
U=src/app/binnacle/Updater.java
grep -qxF '    static final String RELEASE_BASE = "https://github.com/lotech/binnacle/releases/latest/download/";' "$U" || bad "Updater download source changed"
grep -qxF '    static final String INFO = "binnacle-update.txt";' "$U" && grep -qxF '    static final String APK = "Binnacle.apk";' "$U" || bad "Updater file names changed"
[ "$(grep -c 'new URL(' "$U")" = 1 ] && grep -qxF '        HttpURLConnection http = (HttpURLConnection) new URL(RELEASE_BASE + file).openConnection();' "$U" || bad "Updater may only fetch RELEASE_BASE + file"
grep -qxF '        if (!INFO.equals(file) && !APK.equals(file)) throw new IOException("not an update file");' "$U" || bad "Updater may only fetch INFO or APK"
grep -nE 'setRequestMethod|setDoOutput|getOutputStream' "$U" && bad "Updater may only download (GET), never send (above)"
for f in $(grep -rlE 'PackageInstaller|getPackageInstaller|INSTALL_PACKAGE|package-archive' src); do
  [ "$f" = "$U" ] || bad "app installs outside Updater: $f"
done
[ "$(grep -c 'setAppPackageName(' "$U")" = 1 ] && grep -qxF '            params.setAppPackageName(c.getPackageName());' "$U" || bad "Updater's install session must be locked to this app's own package"
[ "$(grep -c 'createSession(' "$U")" = 1 ] || bad "Updater may create exactly one install session"
grep -nE '\.uninstall\(|setInstallerPackageName|setInstallReason|setGrantedRuntimePermissions|setWhitelistedRestrictedPermissions' "$U" && bad "Updater may only install this app (above)"

[ $fail -eq 0 ] && echo "check_readonly: OK (receive-only: IMcuService register/unregister only)"
exit $fail
