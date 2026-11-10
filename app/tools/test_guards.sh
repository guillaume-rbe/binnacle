#!/bin/bash
# Tests the read-only guards themselves (app and logger): each case copies the source to a temp
# folder, slips in one write path, and expects the guards to refuse it. Run by hand after changing
# a guard (it compiles ~30 copies, so build.sh doesn't run it):
#   app/tools/test_guards.sh
#   ANDROID_HOME   SDK path (default ~/Library/Android/sdk), as for build.sh
set -uo pipefail
cd "$(dirname "$0")/.."
APP=$(pwd)
CAP=$(cd ../capture && pwd)
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
failures=0

# R.java for the app copies (the sources reference resources)
mkdir -p "$T/res" "$T/gen"
"$BT/aapt2" compile --dir res -o "$T/res/" && "$BT/aapt2" link -o "$T/r.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --java "$T/gen" "$T"/res/*.flat >/dev/null || { echo "test_guards: can't generate R.java"; exit 1; }

# guards <dir> <java dirs...>: "source" / "bytecode" for whichever refused it, "none" if both passed
guards() {
    local d=$1; shift
    local by=""
    bash "$d/tools/check_readonly.sh" >/dev/null 2>&1 || by="source"
    mkdir -p "$d/classes"
    if javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -d "$d/classes" $(find "$@" -name '*.java') >/dev/null 2>&1; then
        bash "$d/tools/check_bytecode.sh" "$d/classes" >/dev/null 2>&1 || by="$by bytecode"
    else
        by="$by (did not compile)"
    fi
    echo "${by:-none}"
}
copy_app() { local d; d=$(mktemp -d "$T/case.XXXX"); cp -r src tools res AndroidManifest.xml "$d/"; mkdir -p "$d/assets"; echo "$d"; }
copy_cap() { local d; d=$(mktemp -d "$T/case.XXXX"); cp -r "$CAP/src" "$CAP/tools" "$CAP/res" "$CAP/AndroidManifest.xml" "$d/"; echo "$d"; }
# expect <name> <want: pass|both|any> <result>
expect() {
    local name=$1 want=$2 got=$3 ok=0
    case "$want" in
        pass) [ "$got" = none ] && ok=1 ;;
        both) [[ "$got" == *source* && "$got" == *bytecode* ]] && ok=1 ;;
        any)  [[ "$got" == *source* || "$got" == *bytecode* ]] && ok=1 ;;
    esac
    if [ $ok = 1 ]; then printf 'ok   %-50s refused by: %s\n' "$name" "$got"
    else printf 'FAIL %-50s refused by: %s (wanted %s)\n' "$name" "$got" "$want"; failures=$((failures + 1)); fi
}
# app case with an extra class Evil holding $2
evil() {
    local d; d=$(copy_app)
    printf 'package app.binnacle;\nfinal class Evil { static void go(android.content.Context c, android.os.IBinder b) throws Exception {\n%s\n} }\n' "$2" > "$d/src/app/binnacle/Evil.java"
    expect "$1" both "$(guards "$d" "$d/src" "$T/gen")"
}
# app case with a sed edit of one file
edit() {
    local d; d=$(copy_app)
    sed -i.bak "$3" "$d/src/app/binnacle/$2" && rm "$d/src/app/binnacle/$2.bak"
    cmp -s "$d/src/app/binnacle/$2" "src/app/binnacle/$2" && { printf 'FAIL %-50s (the edit did not apply)\n' "$1"; failures=$((failures + 1)); return; }
    expect "$1" "${4:-both}" "$(guards "$d" "$d/src" "$T/gen")"
}

echo "-- app"
d=$(copy_app); expect "unchanged source passes" pass "$(guards "$d" "$d/src" "$T/gen")"
evil "transact(5) in another class"      'b.transact(5, android.os.Parcel.obtain(), null, 0);'
evil "space before the paren"            'b.transact (5, android.os.Parcel.obtain(), null, 0);'
evil "newline before the paren"          'b.transact
(5, android.os.Parcel.obtain(), null, 0);'
evil "unicode escape"                    'b.\u0074ransact(5, android.os.Parcel.obtain(), null, 0);'
evil "Settings.System.putInt"            'android.provider.Settings.System.putInt(c.getContentResolver(), "x", 1);'
evil "reflection"                        'android.os.IBinder.class.getMethod("trans" + "act", int.class, android.os.Parcel.class, android.os.Parcel.class, int.class).invoke(b, 5, null, null, 0);'
evil "Class.forName"                     'Class.forName("java.lang.Process" + "Builder");'
evil "Runtime exec"                      'Runtime .getRuntime().exec(new String[]{"su", "0"});'
evil "SocketChannel"                     'java.nio.channels.SocketChannel.open(new java.net.InetSocketAddress("127.0.0.1", 5555));'
evil "Socket outside LocalShell"         'new java.net.Socket("127.0.0.1", 5555).close();'
evil "Files.write to a tty"              'java.nio.file.Files.write(java.nio.file.Paths.get("/dev/", "ttyHS0"), new byte[]{1});'
evil "PrintStream to a tty"              'new java.io.PrintStream("/dev/" + "tty" + "HS0").write(1);'
evil "FileOutputStream outside CarPacks" 'new java.io.FileOutputStream("/dev/" + "x").write(1);'
evil "broadcast to canapp"               'c.sendBroadcast(new android.content.Intent("x").setPackage("com.xbs.canapp"));'
evil "service start to canapp"           'c.startService(new android.content.Intent().setPackage("com.xbs.canapp"));'
evil "Messenger.send"                    'new android.os.Messenger(b).send(android.os.Message.obtain());'
evil "DexClassLoader"                    'new dalvik.system.DexClassLoader("/sdcard/x.dex", null, null, null);'
evil "System.loadLibrary"                'System.loadLibrary("x");'
edit "CanFeed: transact(txn + 4)"        CanFeed.java    's/b.transact(txn, data, r, 0);/b.transact(txn + 4, data, r, 0);/'
edit "CanFeed: codes 5/6, old in comment" CanFeed.java   's|private static final int TX_REGISTER = 1, TX_UNREGISTER = 2;|private static final int TX_REGISTER = 5, TX_UNREGISTER = 6; // TX_REGISTER = 1, TX_UNREGISTER = 2;|'
edit "CanFeed: call(TX_REGISTER + 2)"    CanFeed.java    's|Parcel r = call(TX_REGISTER, d);|Parcel r = call(TX_REGISTER + 2, d);|'
edit "CanFeed: allow-list widened"       CanFeed.java    's|if (txn != TX_REGISTER \&\& txn != TX_UNREGISTER) {|if (txn != TX_REGISTER \&\& txn != TX_UNREGISTER \&\& txn != 7) {|'
edit "LocalShell: concatenated command"  LocalShell.java 's|        WIFI_REJOIN("service call wifi 58 i32 1 \&\& svc wifi enable \&\& echo BINNACLE_OK"),|        WIFI_REJOIN("service call wifi 58 i32 1 \&\& svc wifi enable \&\& echo BINNACLE_OK"),\n        EVIL("su 0 sh -c " + "x \&\& echo BINNACLE_OK"),|'
edit "LocalShell: another root command"  LocalShell.java 's|        WIFI_REJOIN("service call wifi 58 i32 1 \&\& svc wifi enable \&\& echo BINNACLE_OK"),|        WIFI_REJOIN("service call wifi 58 i32 1 \&\& svc wifi enable \&\& echo BINNACLE_OK"),\n        EVIL("su 0 reboot \&\& echo BINNACLE_OK"),|'
edit "LocalShell: root command widened"  LocalShell.java 's|        GPS_DUMPS_ON("su 0 setprop persist.vendor.sys.core.enabled 1 \&\& echo BINNACLE_OK"),|        GPS_DUMPS_ON("su 0 setprop persist.vendor.sys.core.enabled 1 \&\& su 0 setenforce 0 \&\& echo BINNACLE_OK"),|'
edit "LocalShell: status check runs su"  LocalShell.java 's|su=$(command -v su)|su=$(su 0 id)|'
edit "LocalShell: loopback ADB rule widened" LocalShell.java 's|--uid-owner 2000 -j RETURN|--uid-owner 0-99999 -j RETURN|'
edit "LocalShell: loopback ADB rule dropped" LocalShell.java 's|\$T -w -C OUTPUT -o lo -p tcp --dport 5555 -j binnacle_adb 2>/dev/null \|\| \$T -w -I OUTPUT -o lo -p tcp --dport 5555 -j binnacle_adb \|\| exit 1; ||'
edit "LocalShell: second ADB stream"     LocalShell.java 's|            String text = result.toString("UTF-8").trim();|            send(out, A_OPEN, 2, 0, "exec:su 0 reboot\\0".getBytes(StandardCharsets.UTF_8));\n            String text = result.toString("UTF-8").trim();|'
edit "LocalShell: another ADB service"   LocalShell.java 's|("shell:" + cmd.shell + "\\0")|("re" + "boot:" + "\\0")|'
edit "CarPlayLink: code check widened"     CarPlayLink.java 's|        if (txn < TX_VERSION \|\| txn > TX_SIZES) throw new IllegalArgumentException("not an ICarPlayEmbed call");|        if (txn < TX_VERSION \|\| txn > TX_SIZES + 5) throw new IllegalArgumentException("not an ICarPlayEmbed call");|'
edit "CarPlayLink: binds the CAN app"      CarPlayLink.java 's|    private static final String PKG = "com.shilapi.xcertplay";|    private static final String PKG = "com.xbs.canapp";|'
edit "CarPlayLink: txn changed on the way" CarPlayLink.java 's|            if (!b.transact(txn, data, reply, oneway ? IBinder.FLAG_ONEWAY : 0)) return false;|            if (!b.transact(txn + 1, data, reply, oneway ? IBinder.FLAG_ONEWAY : 0)) return false;|'
edit "CarPlayLink: sends on a Messenger"   CarPlayLink.java 's|    void start() { call(TX_START, token(), false); }|    void start() { call(TX_START, token(), false); try { new Messenger(binder).send(Message.obtain()); } catch (RemoteException e) { } }|'
edit "CarPlayLink: a second transact"      CarPlayLink.java 's|    void disconnect() { call(TX_DISCONNECT, token(), false); }|    void disconnect() { try { binder.transact(99, token(), null, 0); } catch (RemoteException e) { } }|'
# sends added in place, in the files allowed to send (audit #8a)
edit "Launch: ZLink call state in closeCamera"  Launch.java 's|        i.putExtra("reqVal", false);|        i.putExtra("reqVal", false); i.setAction("com.zjinnova.zlink.CALL"); i.putExtra("status", "PHONE_CALL_ON");|'
edit "Launch: a second broadcast"               Launch.java 's|    static void closeCamera(Context c) {|    static void call(Context c) { c.sendBroadcast(new Intent("x").setPackage("com.xbs.canapp")); }\n    static void closeCamera(Context c) {|'
edit "DashApp: logger query sent elsewhere"     DashApp.java 's|("app.binnacle.logger.QUERY").setPackage(LOGGER_PKG)|("app.binnacle.logger.QUERY").setPackage("com.xbs.canapp")|'
edit "DashActivity: a second service start"     DashActivity.java 's|    private void startLogger() {|    private void evil() { startService(new android.content.Intent().setPackage("com.xbs.canapp")); }\n    private void startLogger() {|'
edit "DashActivity: logger start retargeted"    DashActivity.java 's|"app.binnacle.logger", "app.binnacle.logger.CaptureService"|"com.xbs.canapp", "com.xbs.canapp.Svc"|'
evil "IBinder.dump"                             'b.dump(new java.io.FileDescriptor(), new String[0]);'
d=$(copy_app); mkdir -p "$d/src/android/os"
printf 'package android.os;\nfinal class Evil { }\n' > "$d/src/android/os/Evil.java"
expect "source in another package" any "$(guards "$d" "$d/src" "$T/gen")"
evil "network outside CarPacks/Updater"   'new java.net.URL("https://example.com/x").openConnection().getInputStream();'
evil "PackageInstaller outside Updater"  'c.getPackageManager().getPackageInstaller().createSession(new android.content.pm.PackageInstaller.SessionParams(1));'
edit "Updater: another address"          Updater.java 's|releases/latest/download/";|releases/download/other/";|'
edit "Updater: installs another package" Updater.java 's|            params.setAppPackageName(target(c, carplay));|            params.setAppPackageName("com.xbs.canapp");|'
edit "Updater: session not locked"       Updater.java 's|            params.setAppPackageName(target(c, carplay));|            params.setSize(1);|'
edit "Updater: CarPlay target is another app" Updater.java 's|CARPLAY_PACKAGE = "com.shilapi.xcertplay";|CARPLAY_PACKAGE = "com.xbs.canapp";|'
edit "Updater: target() from other text" Updater.java 's|        return carplay ? CARPLAY_PACKAGE : c.getPackageName();|        return carplay ? CARPLAY_PACKAGE : c.getString(1);|'
edit "Updater: target() is caller's choice" Updater.java 's|        return carplay ? CARPLAY_PACKAGE : c.getPackageName();|        return carplay ? CARPLAY_PACKAGE : c.getPackageName() + "x";|'
edit "Updater: fetches any file"         Updater.java 's|        if (!INFO.equals(file) \&\& !APK.equals(file) \&\& !CARPLAY_APK.equals(file)) throw new IOException("not an update file");|        if (file == null) throw new IOException("not an update file");|' any
edit "Updater: another CarPlay file"     Updater.java 's|CARPLAY_APK = "BinnacleCarPlay.apk";|CARPLAY_APK = "Other.apk";|'
edit "Updater: sends a body"             Updater.java 's|        http.setConnectTimeout(TIMEOUT_MS);|        http.setConnectTimeout(TIMEOUT_MS); http.setDoOutput(true);|'
edit "StartupVolume: another stream"     StartupVolume.java 's|audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);|audio.setStreamVolume(AudioManager.STREAM_RING, level, 0);|' any
edit "StartupVolume: mute instead"       StartupVolume.java 's|AudioManager.ADJUST_UNMUTE, 0);|AudioManager.ADJUST_MUTE, 0);|' any
edit "StartupVolume: a second setting"   StartupVolume.java 's|audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);|audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0); audio.setRingerMode(0);|' any
# audit 5 Nov (B1-B5): bypasses that used to pass both guards
edit "LocalShell: second stream from bytes" LocalShell.java 's|            String text = result.toString("UTF-8").trim();|            byte[] p = new byte[]{101,120,101,99,58,115,117,32,48,32,105,100,0};\n            send(out, 0x4e45504f, 2, 0, p);\n            String text = result.toString("UTF-8").trim();|'
edit "LocalShell: a second method sends"  LocalShell.java 's|    private static final class Msg { int cmd, arg0, arg1; byte\[\] data; }|    private static final class Msg { int cmd, arg0, arg1; byte[] data; }\n    static void more(OutputStream out, byte[] p) throws IOException { send(out, A_OPEN, 2, 0, p); }|'
evil "PendingIntent .send ()"            'android.app.PendingIntent.getBroadcast(c, 0, new android.content.Intent("x").setPackage("com.xbs.canapp"), 0).send ();'
evil "IntentSender.sendIntent"           'android.app.PendingIntent.getBroadcast(c, 0, new android.content.Intent("x"), 0).getIntentSender().sendIntent(c, 0, null, null, null);'
evil "PendingIntent.getService"          'android.app.PendingIntent.getService(c, 0, new android.content.Intent().setPackage("com.xbs.canapp"), 0).cancel();'
evil "AlarmManager"                      '((android.app.AlarmManager) c.getSystemService("alarm")).set(0, 0L, null);'
evil "bindIsolatedService"               'c.bindIsolatedService(new android.content.Intent().setPackage("com.xbs.canapp"), 1, "x", null, null);'
evil "startActivity outside its places"  'c.startActivity(new android.content.Intent("x").setPackage("com.xbs.carsetup".substring(0)));'
evil "AtomicFile"                        'new android.util.AtomicFile(new java.io.File("/dev/" + "x")).startWrite().write(1);'
evil "openFileOutput"                    'c.openFileOutput("x", 0).write(1);'
evil "java.util.logging FileHandler"     'new java.util.logging.FileHandler("/sdcard/x").close();'
edit "CameraPane: path out of the decoder" CameraPane.java 's|        writeDecoder("channel", INPUT);|        writeDecoder("../" + "channel", INPUT);|'
edit "CameraPane: a second FileWriter"   CameraPane.java 's|    private static void setUpDecoder() {|    static void more(String path) throws java.io.IOException { new java.io.FileWriter(path).close(); }\n\n    private static void setUpDecoder() {|'
edit "StartupVolume: audio HAL parameters" StartupVolume.java 's|            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);|            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);\n            audio.setParameters("x=1");|'
d=$(copy_app)
printf 'package app.binnacle;\nfinal class LocalShell$X { static void go() throws Exception { new java.net.Socket("127.0.0.1", 5555).close(); } }\n' > "$d/src/app/binnacle/Evil.java"
expect "top-level class named LocalShell\$X" both "$(guards "$d" "$d/src" "$T/gen")"
d=$(copy_app)
printf 'package app.binnacle;\nfinal class Evil { static void go(android.content.Context c) {\n((android.media.AudioManager) c.getSystemService("audio")).setStreamVolume(3, 0, 0);\n} }\n' > "$d/src/app/binnacle/Evil.java"
expect "volume set outside StartupVolume" any "$(guards "$d" "$d/src" "$T/gen")"
d=$(copy_app); ln -s "$APP/src/app/binnacle/CanFeed.java" "$d/src/app/binnacle/Link.java"
r=""; bash "$d/tools/check_readonly.sh" >/dev/null 2>&1 || r="source"; expect "symlinked source file" any "${r:-none}"

echo "-- logger"
F=src/app/binnacle/logger
cap() {   # cap <name> <want> <sed on CaptureService.java | manifest:sed>
    local d; d=$(copy_cap) f="$F/CaptureService.java" e=$3
    case "$e" in manifest:*) f=AndroidManifest.xml; e=${e#manifest:};; esac
    sed -i.bak "$e" "$d/$f" && rm "$d/$f.bak"
    cmp -s "$d/$f" "$CAP/$f" && { printf 'FAIL %-50s (the edit did not apply)\n' "$1"; failures=$((failures + 1)); return; }
    expect "$1" "$2" "$(guards "$d" "$d/src")"
}
d=$(copy_cap); expect "unchanged source passes" pass "$(guards "$d" "$d/src")"
cap "register with txn 3"     both 's/binder.transact(1, d, r, 0)/binder.transact(3, d, r, 0)/'
cap "socket to adbd"          both 's|    private void register() {|    void evil() throws Exception { new java.net.Socket("127.0.0.1", 5555).close(); }\n    private void register() {|'
cap "broadcast to canapp"     both 's|    private void register() {|    void evil() { sendBroadcast(new Intent("x").setPackage("com.xbs.canapp")); }\n    private void register() {|'
cap "Settings.System.putInt"  both 's|    private void register() {|    void evil() { android.provider.Settings.System.putInt(getContentResolver(), "x", 1); }\n    private void register() {|'
cap "unicode escape"          both 's|    private void register() {|    void evil() throws Exception { binder.\\u0074ransact(5, null, null, 0); }\n    private void register() {|'
capui() {   # capui <name> <sed on MainActivity.java>: expects both guards to refuse
    local d; d=$(copy_cap) f="$F/MainActivity.java"
    sed -i.bak "$2" "$d/$f" && rm "$d/$f.bak"
    cmp -s "$d/$f" "$CAP/$f" && { printf 'FAIL %-50s (the edit did not apply)\n' "$1"; failures=$((failures + 1)); return; }
    expect "$1" both "$(guards "$d" "$d/src")"
}
capui "insert into any URI"   's|                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);|                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);\n                getContentResolver ().insert(Uri.parse("content://x"), v);|'
capui "insert, URI from text"  's|getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);|getContentResolver().insert(Uri.parse("content://" + "x"), v);|'
cap "INTERNET permission"     any  'manifest:s|<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />|<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.INTERNET" />|'

echo
[ $failures -eq 0 ] && echo "test_guards: all cases refused as expected" || echo "test_guards: $failures case(s) FAILED"
exit $failures
