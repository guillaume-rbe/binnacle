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
DYNAMIC='java\.lang\.reflect|\.get(Declared)?(Method|Field|Constructor)s?\(|forName\(|newInstance\(|DexClassLoader|PathClassLoader|InMemoryDexClassLoader|loadLibrary|System\.load\(|ServiceManager|java\.nio\.file|java\.nio\.channels|PrintStream|PrintWriter\(\s*(new +File|")|\.send\b|\.send$'
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
# Each send is pinned to its exact code, not just its file (audit #8a): a call-state broadcast to
# ZLink's receivers, added to Launch or DashApp, would be an MCU write. The two broadcasts and the one
# service start are the lines below; Launch.closeCamera and DashActivity.startLogger are pinned whole.
[ "$(grep -rcE 'sendBroadcast\s*\(' src | awk -F: '{ n += $2 } END { print n }')" = 2 ] \
    && grep -qxF '        else sendBroadcast(new android.content.Intent("app.binnacle.logger.QUERY").setPackage(LOGGER_PKG), LOGGER_PERMISSION);' src/app/binnacle/DashApp.java \
    && grep -qxF '            c.sendBroadcast(i);' src/app/binnacle/Launch.java \
    || bad "broadcasts changed: only the logger query (DashApp) and the camera close (Launch)"
body() { awk -v head="$2" 'index($0, head) == 1 { on = 1; next } on && /^    \}$/ { exit } on' "$1"; }
CLOSE_CAMERA=$(cat <<'BODY'
        Intent i = new Intent("com.xbs.action.reqcam").setPackage("com.xbs.frontcam");
        i.putExtra("reqVal", false);
        try {
            c.sendBroadcast(i);
        } catch (RuntimeException ignored) {}
BODY
)
[ "$(body src/app/binnacle/Launch.java '    static void closeCamera(Context c) {')" = "$CLOSE_CAMERA" ] \
    || bad "Launch.closeCamera changed: review it, then update this guard"
START_LOGGER=$(cat <<'BODY'
        if (!app.prefs().getBoolean("logger_autostart", false)) return;   // opt-in only (Advanced -> Drive logger)
        try {
            android.content.Intent i = new android.content.Intent().setComponent(
                    new android.content.ComponentName("app.binnacle.logger", "app.binnacle.logger.CaptureService"));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (RuntimeException e) {
            android.util.Log.i("binnacle", "drive logger not started: " + e.getMessage());
        }
BODY
)
[ "$(grep -rcE 'start(Foreground)?Service\s*\(' src | awk -F: '{ n += $2 } END { print n }')" = 1 ] \
    && [ "$(body src/app/binnacle/DashActivity.java '    private void startLogger() {')" = "$START_LOGGER" ] \
    || bad "service starts changed: only DashActivity.startLogger may start the logger"
# ZLink's call-state message (the one the CAN app forwards to the MCU) is the CarPlay app's alone
grep -rnE 'PHONE_CALL|phoneMode|carplay_wireless' src && bad "call-state message names in the dash (above)"
ZLINK_LINES=$(cat <<'BODY'
src/app/binnacle/Launch.java:11:    static final String ZLINK_PKG = "com.zjinnova.zlink";
src/app/binnacle/Launch.java:12:    static final String ZLINK_ACTION = "zjinnova.android.intent.action.ZLINK_MAIN";
BODY
)
[ "$(grep -rn 'zjinnova' src)" = "$ZLINK_LINES" ] || bad "ZLink named outside Launch's two constants"

# Sources only in the app's own packages (audit #8c): a class under android.* or elsewhere would
# shadow the framework or slip past the per-class rules; AGP's default source sets (src/release,
# src/main, ...) would be another package here and are refused too. No whitespace in any name
# under src, res or assets (the loops above split on it).
find src -name '*.java' | grep -vE '^src/(app/binnacle|com/xbs/launcher)/[A-Za-z0-9_]+\.java$' && bad "sources outside app/binnacle and com/xbs/launcher (above)"
find src -type f ! -name '*.java' | grep . && bad "non-Java files under src (above)"
find src res assets -name '*[[:space:]]*' 2>/dev/null | grep . && bad "whitespace in file names (above)"
# binder calls that run code in another process without transact (IBinder.dump/dumpAsync/shellCommand)
grep -rnE '\.(dump|dumpAsync|shellCommand)\s*\(' src && bad "binder dump/shell calls (above)"

# 1e. Audio settings: only the start-up media volume (owner OK 4 Nov), exactly once, in StartupVolume.
#     Android's own music volume, which the wheel keys change too; nothing else (ringer, other
#     streams, mute) may be set.
VOL='src/app/binnacle/StartupVolume.java'
for f in $(grep -rlE 'set(Stream|Ringer|Mode)Volume|adjust(Stream|Suggested)?Volume|setRingerMode|setMicrophoneMute|setMasterMute|setStreamMute|setSpeakerphoneOn|setMode\(' src); do
  [ "$f" = "$VOL" ] || bad "audio setting changed outside StartupVolume: $f"
done
# nothing that changes the audio mode or passes parameters to the audio HAL (this ROM's HAL may
# talk to the MCU), anywhere, StartupVolume included
grep -rnE 'setParameters|AudioSystem|setBluetoothScoOn|startBluetoothSco|setWiredHeadsetOn|setStreamSolo|adjustVolume' src && bad "audio mode or HAL parameters changed (above)"
for f in $(grep -rlwE 'AudioManager|AUDIO_SERVICE' src); do
  [ "$f" = "$VOL" ] || bad "AudioManager used outside StartupVolume: $f"
done
# two calls: the music level, and unmuting music first (the CAN app mutes it when the car sleeps)
[ "$(grep -cE 'set(Stream|Ringer|Mode)Volume|adjust(Stream|Suggested)?Volume|setRingerMode|setMicrophoneMute|setMasterMute|setStreamMute|setSpeakerphoneOn|setMode\(' "$VOL")" = 2 ] || bad "StartupVolume may change exactly the music level and its mute"
grep -qxE '\s*audio\.setStreamVolume\(AudioManager\.STREAM_MUSIC, level, 0\);' "$VOL" || bad "StartupVolume may only set the music volume"
grep -qxE '\s*if \(muted\) audio\.adjustStreamVolume\(AudioManager\.STREAM_MUSIC, AudioManager\.ADJUST_UNMUTE, 0\);' "$VOL" || bad "StartupVolume may only unmute music"

# 1f. Intents delivered later or as another app: only Updater's one PendingIntent (the install
#     result, back to its own Status receiver). No alarms, isolated services or intent senders.
#     Screens open only from Launch, the hub, setup and Updater's install confirm.
for f in $(grep -rlwE 'PendingIntent|IntentSender|getIntentSender' src); do
  [ "$f" = src/app/binnacle/Updater.java ] || bad "PendingIntent/IntentSender outside Updater: $f"
done
[ "$(grep -cE 'PendingIntent\.get[A-Za-z]*\(' src/app/binnacle/Updater.java)" = 1 ] \
    && grep -qxF '                PendingIntent sender = PendingIntent.getBroadcast(c, id, status, PendingIntent.FLAG_UPDATE_CURRENT);' src/app/binnacle/Updater.java \
    || bad "Updater may make exactly one PendingIntent, a broadcast to its Status receiver"
grep -rnwE 'AlarmManager|ALARM_SERVICE|bindIsolatedService|sendIntent|startIntentSender|startActivities|AtomicFile|openFileOutput|FileHandler' src \
    && bad "alarms, intent senders, isolated services or other file writers (above)"
for f in $(grep -rlE 'startActivit' src); do
  case "$f" in src/app/binnacle/Launch.java|src/app/binnacle/HubActivity.java|src/app/binnacle/SetupActivity.java|src/app/binnacle/Updater.java) ;;
    *) bad "activity started outside Launch/HubActivity/SetupActivity/Updater: $f";; esac
done
# a class named with a '$' would pass for a nested class of another (check_bytecode.sh checks too)
find src -name '*$*' | grep . && bad "file names with '$' (above)"
grep -rnE '(class|interface|enum)\s+[A-Za-z0-9_]*\$' --include='*.java' src && bad "class names with '$' (above)"

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
expected=$(cat <<'EOF'
END_CARPLAY("svc wifi disable && sleep 2 && svc wifi enable && echo BINNACLE_OK")
BT_AUTOCONNECT_GET("settings get system xbs_isautoconnect && echo BINNACLE_OK")
BT_AUTOCONNECT_ON("settings put system xbs_isautoconnect true && echo BINNACLE_OK")
BT_AUTOCONNECT_OFF("settings put system xbs_isautoconnect false && echo BINNACLE_OK")
WIFI_SCANS_ON("service call wifi 58 i32 1 && echo BINNACLE_OK")
WIFI_SCANS_OFF("service call wifi 58 i32 0 && echo BINNACLE_OK")
SCREENSHOT("mkdir -p /sdcard/Binnacle/screenshots && screencap -p /sdcard/Binnacle/screenshots/$(date +%Y%m%d-%H%M%S).png && echo BINNACLE_OK")
XCERTPLAY_LOCATION("appops set --uid com.shilapi.xcertplay COARSE_LOCATION allow && echo BINNACLE_OK")
WIFI_REJOIN("service call wifi 58 i32 1 && svc wifi enable && echo BINNACLE_OK")
UNIT_STATUS("echo adb=$(getprop persist.adb.tcp.port) dumps=$(getprop persist.vendor.sys.core.enabled) bt=$(getprop persist.car.use_system_bt) absvol=$(getprop persist.bluetooth.disableabsvol) su=$(command -v su) && appops get com.xbs.launcher1 TOAST_WINDOW; echo BINNACLE_OK")
ADB_KEEP_ON("su 0 setprop persist.adb.tcp.port 5555 && echo BINNACLE_OK")
ADB_KEEP_OFF("su 0 setprop persist.adb.tcp.port '' && echo BINNACLE_OK")
ADB_LOCAL_ONLY("su 0 sh -c 'U=$(stat -c %u /data/data/com.xbs.launcher) && [ $U -ge 10000 ] && for T in iptables ip6tables; do $T -w -C INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null || $T -w -I INPUT -p tcp --dport 5555 ! -i lo -j DROP || exit 1; if $T -w -N binnacle_adb 2>/dev/null; then $T -w -A binnacle_adb -m owner --uid-owner 0 -j RETURN && $T -w -A binnacle_adb -m owner --uid-owner 2000 -j RETURN && $T -w -A binnacle_adb -m owner --uid-owner $U -j RETURN && $T -w -A binnacle_adb -p tcp -j REJECT --reject-with tcp-reset || { $T -w -F binnacle_adb; $T -w -X binnacle_adb; exit 1; }; fi; $T -w -C binnacle_adb -m owner --uid-owner $U -j RETURN && $T -w -C binnacle_adb -p tcp -j REJECT --reject-with tcp-reset || exit 1; $T -w -C OUTPUT -o lo -p tcp --dport 5555 -j binnacle_adb 2>/dev/null || $T -w -I OUTPUT -o lo -p tcp --dport 5555 -j binnacle_adb || exit 1; done' && echo BINNACLE_OK")
ADB_NETWORK("su 0 sh -c 'for T in iptables ip6tables; do while $T -w -D INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null; do :; done; while $T -w -D OUTPUT -o lo -p tcp --dport 5555 -j binnacle_adb 2>/dev/null; do :; done; $T -w -F binnacle_adb 2>/dev/null; $T -w -X binnacle_adb 2>/dev/null; done; true' && echo BINNACLE_OK")
GPS_DUMPS_OFF("su 0 setprop persist.vendor.sys.core.enabled 0 && su 0 sh -c 'rm -rf /data/corefile/*' && echo BINNACLE_OK")
GPS_DUMPS_ON("su 0 setprop persist.vendor.sys.core.enabled 1 && echo BINNACLE_OK")
STOCK_CAMERA_OFF("su 0 pm disable com.xbs.frontcam/com.xbs.frontcam.CameraActivity && echo BINNACLE_OK")
STOCK_CAMERA_ON("su 0 pm enable com.xbs.frontcam/com.xbs.frontcam.CameraActivity && echo BINNACLE_OK")
STOCK_TOASTS_OFF("appops set com.xbs.launcher1 TOAST_WINDOW ignore && echo BINNACLE_OK")
STOCK_TOASTS_ON("appops set com.xbs.launcher1 TOAST_WINDOW allow && echo BINNACLE_OK")
ANDROID_BT_ON("su 0 setprop persist.car.use_system_bt 2 && echo BINNACLE_OK")
ANDROID_BT_OFF("su 0 setprop persist.car.use_system_bt '' && echo BINNACLE_OK")
BT_VOLUME_FIX_ON("su 0 setprop persist.bluetooth.disableabsvol true && echo BINNACLE_OK")
BT_VOLUME_FIX_OFF("su 0 setprop persist.bluetooth.disableabsvol '' && echo BINNACLE_OK")
CARPLAY_CALLS_ON("pm grant com.shilapi.xcertplay android.permission.CALL_PHONE && telecom set-phone-account-enabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && telecom set-user-selected-outgoing-phone-account com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && su 0 pm disable com.android.dialer/com.android.incallui.InCallServiceImpl && echo BINNACLE_OK")
CARPLAY_CALLS_OFF("su 0 pm enable com.android.dialer/com.android.incallui.InCallServiceImpl && telecom set-phone-account-disabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && pm revoke com.shilapi.xcertplay android.permission.CALL_PHONE && echo BINNACLE_OK")
EOF
)
[ "$cmds" = "$expected" ] || bad "LocalShell command list changed: review it, then update this guard"
[ "$(grep -c 'shell:' src/app/binnacle/LocalShell.java)" = 1 ] && grep -qF '("shell:" + cmd.shell + "\0")' src/app/binnacle/LocalShell.java || bad "LocalShell may only open shell:<Command.shell>"
[ "$(grep -cw 'A_OPEN' src/app/binnacle/LocalShell.java)" = 2 ] || bad "LocalShell may open exactly one ADB stream"
# exactly its four messages (connect, open the one shell stream, ack, close) plus send's own
# declaration; no payload built from bytes or numbers (a second stream needs no new string)
LS=src/app/binnacle/LocalShell.java
[ "$(grep -cE '(^|[^A-Za-z0-9_])send\s*\(' "$LS")" = 5 ] || bad "LocalShell may send exactly its four ADB messages"
grep -qxF '            send(out, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));' "$LS" \
    && grep -qxF '            send(out, A_OPEN, LOCAL_ID, 0, ("shell:" + cmd.shell + "\0").getBytes(StandardCharsets.UTF_8));' "$LS" \
    && grep -qxF '                    send(out, A_OKAY, LOCAL_ID, m.arg0, new byte[0]);' "$LS" \
    && grep -qxF '                    send(out, A_CLSE, LOCAL_ID, remote != 0 ? remote : m.arg0, new byte[0]);' "$LS" \
    || bad "LocalShell's ADB messages changed"
[ "$(grep -ciE '0x4e45504f' "$LS")" = 1 ] && grep -qxF '    private static final int A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,' "$LS" \
    || bad "LocalShell may name the ADB open code once, in its constants"
grep -nE 'new byte\[\]\s*\{|\(byte\)|\(char\)|1313165391|exec:|root:|reboot:' "$LS" && bad "LocalShell may not build ADB payloads from numbers (above)"
grep -rnE 'LocalShell\.run\(' src | grep -vE 'LocalShell\.run\(cmd\)' && bad "LocalShell.run must take a Command (above)"

# 5. File writes: only CameraPane (the camera decoder's two nodes: local hardware, not the car) and
#    CarPacks (car pictures, only inside the app's own files dir, only entries that stay inside it).
for f in $(grep -rlE 'FileWriter|FileOutputStream|RandomAccessFile' src); do
  case "$f" in src/app/binnacle/CameraPane.java|src/app/binnacle/CarPacks.java) ;; *) bad "file writes outside CameraPane/CarPacks: $f";; esac
done
grep -q 'DECODER_DIR = "/sys/ziqi/camera", INPUT = "2", SIGNAL = "AHD_1080P_30";' src/app/binnacle/CameraPane.java || bad "camera decoder write target changed"
CP=src/app/binnacle/CameraPane.java
[ "$(grep -cE 'writeDecoder\s*\(' "$CP")" = 3 ] \
    && grep -qxF '        writeDecoder("channel", INPUT);' "$CP" && grep -qxF '        writeDecoder("N_P_pre_format", SIGNAL);' "$CP" \
    && grep -qxF '    private static void writeDecoder(String node, String value) {' "$CP" \
    && grep -qxF '        java.io.File file = new java.io.File(DECODER_DIR, node);' "$CP" \
    || bad "CameraPane may write only channel and N_P_pre_format in DECODER_DIR"
[ "$(grep -cE 'FileWriter\s*\(' "$CP")" = 1 ] || bad "CameraPane may open one FileWriter (writeDecoder)"
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

# 6b. Updater (Advanced -> Updates): GET only, from the latest release's three fixed files, and it
#     may install only this app itself or, when it is already installed, Binnacle's CarPlay app
#     (com.shilapi.xcertplay, carplay/ in this repo): the session is locked to one of those two
#     package names (target()); Android then insists on the installed copy's signing key and asks
#     the user. Nothing else may use the installer.
U=src/app/binnacle/Updater.java
grep -qxF '    static final String RELEASE_BASE = "https://github.com/lotech/binnacle/releases/latest/download/";' "$U" || bad "Updater download source changed"
grep -qxF '    static final String INFO = "binnacle-update.txt";' "$U" && grep -qxF '    static final String APK = "Binnacle.apk";' "$U" \
    && grep -qxF '    static final String CARPLAY_APK = "BinnacleCarPlay.apk";' "$U" || bad "Updater file names changed"
[ "$(grep -c 'new URL(' "$U")" = 1 ] && grep -qxF '        HttpURLConnection http = (HttpURLConnection) new URL(RELEASE_BASE + file).openConnection();' "$U" || bad "Updater may only fetch RELEASE_BASE + file"
grep -qxF '        if (!INFO.equals(file) && !APK.equals(file) && !CARPLAY_APK.equals(file)) throw new IOException("not an update file");' "$U" || bad "Updater may only fetch INFO, APK or CARPLAY_APK"
grep -nE 'setRequestMethod|setDoOutput|getOutputStream' "$U" && bad "Updater may only download (GET), never send (above)"
for f in $(grep -rlE 'PackageInstaller|getPackageInstaller|INSTALL_PACKAGE|package-archive' src); do
  [ "$f" = "$U" ] || bad "app installs outside Updater: $f"
done
[ "$(grep -c 'setAppPackageName(' "$U")" = 1 ] && grep -qxF '            params.setAppPackageName(target(c, carplay));' "$U" || bad "Updater's install session must be locked to target()"
# target(): this app's own package, or the CarPlay app's fixed name; nothing else
grep -qxF '    static final String CARPLAY_PACKAGE = "com.shilapi.xcertplay";' "$U" || bad "Updater's CarPlay package changed"
[ "$(grep -v '^ *\*' "$U" | grep -c 'CARPLAY_PACKAGE')" = 3 ] || bad "Updater: CARPLAY_PACKAGE used somewhere new (declared, target(), success toast)"
grep -qxF '    private static String target(Context c, boolean carplay) {' "$U" \
    && grep -qxF '        return carplay ? CARPLAY_PACKAGE : c.getPackageName();' "$U" || bad "Updater's target() changed"
[ "$(grep -c 'createSession(' "$U")" = 1 ] || bad "Updater may create exactly one install session"
grep -nE '\.uninstall\(|setInstallerPackageName|setInstallReason|setGrantedRuntimePermissions|setWhitelistedRestrictedPermissions' "$U" && bad "Updater may only install this app (above)"

[ $fail -eq 0 ] && echo "check_readonly: OK (receive-only: IMcuService register/unregister only)"
exit $fail
