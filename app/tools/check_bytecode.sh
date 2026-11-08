#!/bin/bash
# ABSOLUTE RULE, second line of defence: check_readonly.sh reads the source text; this reads what
# javac actually produced, so whitespace, comments, unicode escapes, symlinks or string tricks in
# the source can't hide a write path. build.sh runs it on build/classes after javac and on the
# packed APK before signing, and the build fails if anything here fails.
#   usage: tools/check_bytecode.sh <classes dir>      the compiled classes
#          tools/check_bytecode.sh --apk <apk>        the packed APK (one dex, nothing else executable)
set -uo pipefail
# One byte order for every sort and match, so the pinned listings hash the same on macOS and Linux
# (a locale sort put LocalShell.class before LocalShell$1.class on the Mac and changed the hash).
export LC_ALL=C
cd "$(dirname "$0")/.."
fail=0
bad() { echo "check_bytecode: $*" >&2; fail=1; }

# The APK: exactly one dex (built from the classes checked below), no native libraries, and no
# code hidden in any other entry (assets, res/raw, ...).
if [ "${1:-}" = --apk ]; then
    APK="${2:?usage: check_bytecode.sh --apk <apk>}"
    entries=$(unzip -Z1 "$APK") || { bad "can't list $APK"; exit 1; }
    [ "$(printf '%s\n' "$entries" | grep -cE '\.dex$')" = 1 ] && printf '%s\n' "$entries" | grep -qx 'classes.dex' || bad "APK must hold classes.dex and no other dex"
    printf '%s\n' "$entries" | grep -E '^lib/|\.(so|jar|apk|odex|vdex|oat)$' && bad "native libraries or nested code in the APK (above)"
    while IFS= read -r e; do
        case "$e" in classes.dex|AndroidManifest.xml|resources.arsc|*/) continue;; esac
        magic=$(unzip -p "$APK" "$e" | head -c 4 | od -An -tx1 | tr -d ' \n')
        case "$magic" in 6465780a|7f454c46|504b0304|cafebabe) bad "executable or archive in the APK: $e";; esac
    done <<< "$entries"
    [ $fail -eq 0 ] && echo "check_bytecode: OK (APK holds only the checked code)"
    exit $fail
fi

CLASSES="${1:?usage: check_bytecode.sh <classes dir> | --apk <apk>}"

files=$(find "$CLASSES" -name '*.class' | sort)
[ -n "$files" ] || { bad "no classes in $CLASSES"; exit 1; }
[ -z "$(find "$CLASSES" -type l)" ] || bad "symlinks in $CLASSES"

# One line per instruction: class <TAB> method <TAB> opcode <TAB> reference (Class.member, class
# name or String constant; empty if none). Members of the class itself get its name prefixed.
# The javap run must succeed: a class it can't read must not pass unchecked.
dump=$(javap -c -p $files 2>/dev/null) || { bad "javap failed"; exit 1; }
TABLE=$(printf '%s\n' "$dump" | awk '
    /^[a-z ]*(class|interface|enum) [^ ]+/ {
        for (i = 1; i <= NF; i++) if ($i == "class" || $i == "interface" || $i == "enum") { cls = $(i+1); break }
        sub(/<.*/, "", cls); gsub(/\./, "/", cls); next
    }
    /^  [^ ]/ {
        m = $0; if (m ~ /static \{\};/) { method = "<clinit>"; next }
        sub(/\(.*/, "", m); n = split(m, w, " "); method = w[n]; sub(/.*\./, "", method); next
    }
    /^ +[0-9]+: [a-z]/ {
        op = $2; ref = ""
        k = index($0, "// ")
        if (k > 0) {
            ref = substr($0, k + 3)
            if (ref ~ /^String /) ref = "String:" substr(ref, 8)
            else {
                sub(/^(Method|InterfaceMethod|Field|class|InvokeDynamic) /, "", ref)
                sub(/:.*/, "", ref)
                gsub(/"/, "", ref)
                if ($0 ~ /\/\/ (Method|InterfaceMethod|Field) / && ref !~ /\//) ref = cls "." ref
            }
        }
        printf "%s\t%s\t%s\t%s\n", cls, method, op, ref
    }')
[ -n "$TABLE" ] || { bad "no instructions found"; exit 1; }
# invokedynamic (lambdas, method references) names its target only in the bootstrap table, which
# the checks below don't read. The app has none (javac can't build them against android.jar).
printf '%s\n' "$TABLE" | awk -F'\t' '$3 == "invokedynamic"' | grep . >&2 && bad "invokedynamic is not allowed (above)"

# refs <regex>: every instruction whose reference matches (extended regex on the reference)
# (patterns go through the environment: awk -v would eat their backslashes)
refs() { printf '%s\n' "$TABLE" | RE="$1" awk -F'\t' '$4 ~ ENVIRON["RE"]'; }
# only <regex> <class regex>: the reference may appear only in classes matching the second regex
only() {
    local hits; hits=$(refs "$1" | OK="^($2)([$].*)?$" awk -F'\t' '$1 !~ ENVIRON["OK"]')
    [ -z "$hits" ] || { printf '%s\n' "$hits" >&2; bad "$1 used outside $2 (above)"; }
}
# only_method <regex> <class> <method>: the reference may appear only in that one method of that class
only_method() {
    local hits; hits=$(refs "$1" | C="$2" M="$3" awk -F'\t' '$1 != ENVIRON["C"] || $2 != ENVIRON["M"]')
    [ -z "$hits" ] || { printf '%s\n' "$hits" >&2; bad "$1 used outside $2.$3 (above)"; }
}
# never <regex>: the reference must not appear at all
never() {
    local hits; hits=$(refs "$1")
    [ -z "$hits" ] || { printf '%s\n' "$hits" >&2; bad "forbidden: $1 (above)"; }
}

# 1. Binder: only CanFeed.call (the CAN feed) and CarPlayLink.binderCall (xcertplay's CarPlay
#    service, see 2b) may transact; no raw service lookup, binder stubs or native code.
only '\.transact$' 'app/binnacle/(CanFeed|CarPlayLink)'
[ "$(refs '\.transact$' | awk -F'\t' '$1 == "app/binnacle/CanFeed" && $2 == "call"' | wc -l | tr -d ' ')" = 1 ] || bad "CanFeed.call must hold CanFeed's one and only transact"
[ "$(refs '\.transact$' | awk -F'\t' '$1 == "app/binnacle/CarPlayLink" && $2 == "binderCall"' | wc -l | tr -d ' ')" = 1 ] || bad "CarPlayLink.binderCall must hold CarPlayLink's one and only transact"
[ "$(refs '\.transact$' | wc -l | tr -d ' ')" = 2 ] || bad "exactly two transacts allowed (CanFeed.call, CarPlayLink.binderCall)"
never '^android/os/(ServiceManager|HwBinder|HwParcel|SystemProperties)'
never '^android/os/Messenger\.send$'               # we only ever receive on our Messenger
never '\.(queryLocalInterface|linkToDeath)$'
javap -p $files 2>/dev/null | grep -nE ' native |extends android\.os\.Binder|implements .*android\.os\.IInterface' \
    && bad "native methods or binder stubs (above)"

# 2. CanFeed.call: refuses anything but transaction 1 and 2 before it touches the binder, passes
#    its own txn argument straight to transact, and is only ever called with 1 or 2.
call=$(printf '%s\n' "$TABLE" | awk -F'\t' '$1 == "app/binnacle/CanFeed" && $2 == "call" { print $3 }')
[ "$(printf '%s\n' "$call" | head -6 | tr '\n' ' ')" = "iload_1 iconst_1 if_icmpeq iload_1 iconst_2 if_icmpeq " ] \
    || bad "CanFeed.call must start by allowing only txn 1 and 2"
printf '%s\n' "$call" | grep -qxE 'istore_1|iinc|i(add|sub|mul|div|rem|or|and|xor|shl|shr|ushr)' \
    && bad "CanFeed.call must not change or compute the txn"
[ "$(printf '%s\n' "$call" | grep -cE '^(if|tableswitch|lookupswitch)')" = 3 ] \
    || bad "CanFeed.call must make exactly its two txn checks and one null check"
printf '%s\n' "$TABLE" | awk -F'\t' '$1 == "app/binnacle/CanFeed" && $2 == "call"' \
    | awk -F'\t' '{ op[NR] = $3; r[NR] = $4 } END { for (i = 1; i <= NR; i++) if (r[i] ~ /\.transact$/) { print op[i-4]; exit } }' \
    | grep -qx 'iload_1' || bad "CanFeed.call must pass its txn argument unchanged to transact"
sites=$(printf '%s\n' "$TABLE" | awk -F'\t' '
    { op[NR] = $3; r[NR] = $4 }
    END { for (i = 1; i <= NR; i++) if (r[i] == "app/binnacle/CanFeed.call") print op[i-2] }')
[ "$(printf '%s\n' "$sites" | sort | tr '\n' ' ')" = "iconst_1 iconst_2 " ] || bad "CanFeed.call may only be called with 1 and 2 (got: $sites)"

# 2b. CarPlayLink.binderCall: exactly the pinned code. It refuses any code outside 1..10 (the
#     ICarPlayEmbed calls) before it touches the binder and passes its txn argument unchanged.
#     Any edit to the method changes this list and fails the build until it is reviewed here.
LINK_CLASS=$(printf '%s\n' "$files" | grep '/app/binnacle/CarPlayLink\.class$')
if [ -n "$LINK_CLASS" ]; then
    link_dump=$(javap -c -p "$LINK_CLASS" 2>/dev/null) || bad "javap failed on CarPlayLink"
    bc=$(printf '%s\n' "$link_dump" | awk '/ binderCall\(int, android.os.Parcel, android.os.Parcel, boolean\);/{on=1;next} on && /^  [a-z]/{exit} on')
    # ldc_w is ldc with a wider constant index (the class's constant pool passed 255 entries);
    # the constants themselves are pinned by the string list below
    ops=$(printf '%s\n' "$bc" | grep -E '^ +[0-9]+:' | awk '{print $2}' | sed 's/^ldc_w$/ldc/' | tr '\n' ' ')
    [ "$ops" = "iload_1 iconst_1 if_icmplt iload_1 bipush if_icmple new dup ldc invokespecial athrow aload_0 getfield astore aload ifnonnull iconst_0 ireturn aload iload_1 aload_2 aload_3 iload ifeq iconst_1 goto iconst_0 invokeinterface ifne iconst_0 ireturn aload_3 ifnull aload_3 invokevirtual iconst_1 ireturn astore ldc new dup invokespecial ldc invokevirtual iload_1 invokevirtual ldc invokevirtual aload invokevirtual invokevirtual invokestatic pop iconst_0 ireturn " ] \
        || bad "CarPlayLink.binderCall changed: review it, then update this guard (got: $ops)"
    printf '%s\n' "$bc" | grep -qE '^ +6: bipush +10$' || bad "CarPlayLink.binderCall must refuse codes above 10"
    # its strings: the one service it binds, the interface token, log text and the keys of the
    # state / layout / now-playing messages it receives, nothing else
    got=$(refs '^String:' | awk -F'\t' '$1 ~ /^app\/binnacle\/CarPlayLink(\$|$)/ { print $4 }' | sort -u)
    expected=$(sort -u <<'EOF'
String: failed:
String:, need
String:CarPlay link call
String:CarPlay link not allowed:
String:CarPlay link: xcertplay API
String:CarPlay link: bad message
String: or later
String::
String:binnacle
String:com.shilapi.xcertplay
String:com.shilapi.xcertplay.CarPlayEmbedService
String:com.shilapi.xcertplay.embed.ICarPlayEmbed
String:not an ICarPlayEmbed call
String:album
String:app
String:art
String:artId
String:artist
String:controls
String:duration
String:playing
String:position
String:stage
String:title
String:video
EOF
)
    [ "$got" = "$expected" ] || { diff <(printf '%s\n' "$expected") <(printf '%s\n' "$got") >&2; bad "CarPlayLink strings changed: review, then update this guard"; }
fi
# 3. No reflection, dynamic code or native libraries (each could reach any API by name).
never '^java/lang/reflect/'
never '^java/lang/invoke/(MethodHandles?|VarHandle)'
never '^java/lang/Class\.(forName|getMethods?|getDeclaredMethods?|getFields?|getDeclaredFields?|getConstructors?|getDeclaredConstructors?|newInstance)$'
never '^java/lang/ClassLoader\.(loadClass|defineClass)'
never '^dalvik/system/'
never '^java/lang/(System|Runtime)\.(load|loadLibrary)$'

# 4. No processes; sockets only in LocalShell (loopback ADB), HTTP only in CarPacks and Updater.
never '^java/lang/(Runtime\.exec|ProcessBuilder|Process)'
never '^java/net/(ServerSocket|DatagramSocket|MulticastSocket)|^java/nio/channels/|^android/net/Local(Server)?Socket'
only '^java/net/(Socket|InetSocketAddress|InetAddress)(\.|$)' 'app/binnacle/LocalShell'
only '^java/net/(URL|URLConnection|HttpURLConnection)|^javax/net/' 'app/binnacle/(CarPacks|Updater)'
never '^java/net/(HttpURLConnection|URLConnection)\.(setRequestMethod|setDoOutput|getOutputStream)$'

# 4b. Installing apps: only Updater, one session, locked by target() to this app's own package or
#     the CarPlay app's fixed one (Android then needs the installed copy's signing key and the
#     user's tap). Its only web addresses are the latest release's three fixed files (javac folds
#     RELEASE_BASE + name into one constant).
only '^android/content/pm/PackageInstaller' 'app/binnacle/Updater'
only '^android/content/pm/PackageManager\.(getPackageInstaller|canRequestPackageInstalls)$' 'app/binnacle/Updater'
never '^android/content/pm/PackageInstaller\.(uninstall|setPermissionsResult)$|^android/content/pm/PackageInstaller\$SessionParams\.(setInstallerPackageName|setGrantedRuntimePermissions|setWhitelistedRestrictedPermissions|setInstallReason)$'
[ "$(refs '^android/content/pm/PackageInstaller\$SessionParams\.setAppPackageName$' | wc -l | tr -d ' ')" = 1 ] || bad "Updater must lock its install session to one package, once"
[ "$(refs '^android/content/pm/PackageInstaller\.createSession$' | wc -l | tr -d ' ')" = 1 ] || bad "exactly one install session allowed (Updater)"
printf '%s\n' "$TABLE" | awk -F'\t' '$1 == "app/binnacle/Updater" && $2 == "install"' \
    | awk -F'\t' '{ op[NR] = $3; r[NR] = $4 } END { for (i = 1; i <= NR; i++) if (r[i] ~ /SessionParams\.setAppPackageName$/) { print r[i-1]; exit } }' \
    | grep -qx 'app/binnacle/Updater.target' || bad "Updater must lock its install session to target()"
# target(): getPackageName() or the CarPlay app's name, nothing else (no other call, field or text)
t=$(printf '%s\n' "$TABLE" | awk -F'\t' '$1 == "app/binnacle/Updater" && $2 == "target" && $4 != "" { print $4 }' | sort -u | tr '\n' ' ')
[ "$t" = "String:com.shilapi.xcertplay android/content/Context.getPackageName " ] || bad "Updater's target() changed: $t"
got=$(refs '^String:.*(://|\.apk|\.txt)' | awk -F'\t' '$1 ~ /^app\/binnacle\/Updater(\$|$)/ { print $4 }' | sort -u)
expected=$(sort -u <<'EOF'
String:Binnacle.apk
String:BinnacleCarPlay.apk
String:base.apk
String:binnacle-update.txt
String:https://github.com/lotech/binnacle/releases/latest/download/
EOF
)
[ "$got" = "$expected" ] || { diff <(printf '%s\n' "$expected") <(printf '%s\n' "$got") >&2; bad "Updater addresses changed: review, then update this guard"; }
refs '^String:.*://' | awk -F'\t' '$1 !~ /^app\/binnacle\/(CarPacks|Updater)(\$|$)/' | grep -E 'String:https?://' >&2 && bad "web addresses outside CarPacks/Updater (above)"

# 5. Files: FileOutputStream only in CarPacks (its own files dir), FileWriter only in CameraPane
#    (the camera decoder's sysfs nodes); nothing else may open a file for writing.
only '^java/io/FileOutputStream' 'app/binnacle/CarPacks'
only_method '^java/io/FileWriter' 'app/binnacle/CameraPane' 'writeDecoder'
never '^java/io/(RandomAccessFile|PrintStream\.<init>|PrintWriter\.<init>)$|^java/io/RandomAccessFile|^java/nio/file/|^android/os/ParcelFileDescriptor|^android/system/Os'
# other ways to open a file for writing: AtomicFile (a FileOutputStream on any path), the app's
# private files through Context, java.util.logging's file handler
never '^android/util/AtomicFile|\.openFileOutput$|^java/util/logging/FileHandler'
# CameraPane: writeDecoder only ever gets the decoder folder and its two nodes. It is called exactly
# twice, from setUpDecoder, with the fixed node name and value as constants; its File is built on
# the fixed folder (so no other path, and no "../", can reach the FileWriter).
only_method '^app/binnacle/CameraPane\.writeDecoder$' 'app/binnacle/CameraPane' 'setUpDecoder'
got=$(printf '%s\n' "$TABLE" | awk -F'\t' '$1 == "app/binnacle/CameraPane" && $2 == "setUpDecoder" { print $3 " " $4 }')
[ "$got" = "$(printf '%s\n' 'ldc String:channel' 'ldc String:2' 'invokestatic app/binnacle/CameraPane.writeDecoder' \
    'ldc String:N_P_pre_format' 'ldc String:AHD_1080P_30' 'invokestatic app/binnacle/CameraPane.writeDecoder' 'return ')" ] \
    || bad "CameraPane.setUpDecoder may only write channel=2 and N_P_pre_format=AHD_1080P_30 (got: $got)"
got=$(printf '%s\n' "$TABLE" | awk -F'\t' '$1 == "app/binnacle/CameraPane" && $2 == "writeDecoder" { print $3 " " $4 }' | head -5)
[ "$got" = "$(printf '%s\n' 'new java/io/File' 'dup ' 'ldc String:/sys/ziqi/camera' 'aload_0 ' 'invokespecial java/io/File.<init>')" ] \
    || bad "CameraPane.writeDecoder must open DECODER_DIR/<node> (got: $got)"
[ "$(refs '^java/io/File\.' | awk -F'\t' '$1 == "app/binnacle/CameraPane" && $2 == "writeDecoder" && $4 ~ /<init>/' | wc -l | tr -d ' ')" = 1 ] \
    || bad "CameraPane.writeDecoder may build exactly one File"

# 6. Content providers and settings: one Settings.System read in StockScreens, nothing else.
only '^android/content/ContentResolver|\.getContentResolver$|^android/provider/Settings\$' 'app/binnacle/StockScreens'
[ "$(refs '^android/provider/Settings\$' | awk -F'\t' '{ print $4 }' | sort -u)" = 'android/provider/Settings$System.getInt' ] \
    || bad "StockScreens may only call Settings.System.getInt"
never '^android/content/(ContentProviderClient|ContentValues)|^android/provider/Settings\$[A-Za-z]+\.put'

# 7. Talking to other apps: fixed places only (CAN bind in CanFeed; camera close broadcast in Launch;
#    logger query in DashApp; logger start in DashActivity). No ordered or sticky broadcasts.
only '\.bindService$' 'app/binnacle/(CanFeed|CarPlayLink)'
only '\.sendBroadcast$' 'app/binnacle/(Launch|DashApp)'
only '\.(startService|startForegroundService)$' 'app/binnacle/DashActivity'
never '\.(sendOrderedBroadcast|sendStickyBroadcast|sendBroadcastAsUser|startInstrumentation)$'
# PendingIntents and IntentSenders deliver an intent later, or as another app: only Updater's one
# broadcast back to its own Status receiver (the install result). No alarms, no isolated services.
never '^android/app/PendingIntent\.(getService|getForegroundService|getActivity|getActivities|send)$|^android/content/IntentSender\.sendIntent$|\.bindIsolatedService$|^android/app/AlarmManager(\.|$)'
only_method '^android/app/PendingIntent\.getBroadcast$|^android/app/PendingIntent\.getIntentSender$' 'app/binnacle/Updater' 'commit'
[ "$(refs '^android/app/PendingIntent\.getBroadcast$' | wc -l | tr -d ' ')" = 1 ] || bad "exactly one PendingIntent allowed (Updater's install result)"
refs '^class app/binnacle/Updater\$Status$|^app/binnacle/Updater\$Status$' | awk -F'\t' '$1 == "app/binnacle/Updater" && $2 == "commit"' | grep -q . \
    || bad "Updater's PendingIntent must target its own Status receiver"
# Activities: only the places that open screens (Launch for other apps, the hub and setup for their
# own and Android's settings screens, Updater's install confirm screen).
only '\.startActivit(y|ies|yForResult|yIfNeeded|yFromChild|yFromFragment)$' 'app/binnacle/(Launch|HubActivity|SetupActivity|Updater[$]Status)'
never '\.startActivities$|\.startIntentSender[A-Za-z]*$'
# Audio: only StartupVolume, and only the music level and its mute (owner OK 4 Nov). Nothing that
# changes the audio mode or passes parameters to the audio HAL (this ROM's HAL may talk to the MCU).
only '^android/media/AudioManager\.' 'app/binnacle/StartupVolume'
never '^android/media/AudioManager\.(setParameters|setMode|setRingerMode|setMicrophoneMute|setSpeakerphoneOn|setBluetoothScoOn|startBluetoothSco|setStreamMute|setStreamSolo|setWiredHeadsetOn|adjustVolume|adjustSuggestedStreamVolume|setAllowedCapturePolicy)$|^android/media/AudioSystem'
[ "$(refs '^android/media/AudioManager\.(setStreamVolume|adjustStreamVolume)$' | wc -l | tr -d ' ')" = 2 ] || bad "StartupVolume may set the music level and unmute it, once each"

# 8. No string anywhere that names a car-write path, even if nothing calls it yet.
never '^String:.*(com\.xbs\.carsetup|com\.xbs\.can\.provider|/dev/tty|ttyHS|McuSerial|McuManager|com\.mct\.serial|txMessengerPack|txGeneralProtocol|txPerformUartPack|txUpdateProtocol|setUartNormalTransmitEnable|writeData)'

# 9. LocalShell: its string constants are exactly these. A new command, a different ADB service
#    (exec:, root:, reboot:, ...) or a concatenated one would add a string here.
got=$(refs '^String:' | awk -F'\t' '$1 ~ /^app\/binnacle\/LocalShell(\$|$)/ { print $4 }' | sort -u)
expected=$(sort -u <<'EOF'
String:127.0.0.1
String:ADB on the unit asks for a key
String:ADB_KEEP_OFF
String:ADB_KEEP_ON
String:ADB_LOCAL_ONLY
String:ADB_NETWORK
String:ANDROID_BT_OFF
String:ANDROID_BT_ON
String:BINNACLE_OK
String:BT_AUTOCONNECT_GET
String:BT_AUTOCONNECT_OFF
String:BT_AUTOCONNECT_ON
String:BT_VOLUME_FIX_OFF
String:BT_VOLUME_FIX_ON
String:CARPLAY_CALLS_OFF
String:CARPLAY_CALLS_ON
String:END_CARPLAY
String:GPS_DUMPS_OFF
String:GPS_DUMPS_ON
String:SCREENSHOT
String:STOCK_CAMERA_OFF
String:STOCK_CAMERA_ON
String:STOCK_TOASTS_OFF
String:STOCK_TOASTS_ON
String:UNIT_STATUS
String:UTF-8
String:WIFI_REJOIN
String:WIFI_SCANS_OFF
String:WIFI_SCANS_ON
String:XCERTPLAY_LOCATION
String:\u0000
String:appops set --uid com.shilapi.xcertplay COARSE_LOCATION allow && echo BINNACLE_OK
String:appops set com.xbs.launcher1 TOAST_WINDOW allow && echo BINNACLE_OK
String:appops set com.xbs.launcher1 TOAST_WINDOW ignore && echo BINNACLE_OK
String:bad ADB message
String:echo adb=$(getprop persist.adb.tcp.port) dumps=$(getprop persist.vendor.sys.core.enabled) bt=$(getprop persist.car.use_system_bt) absvol=$(getprop persist.bluetooth.disableabsvol) && appops get com.xbs.launcher1 TOAST_WINDOW && echo BINNACLE_OK
String:host::\u0000
String:mkdir -p /sdcard/Binnacle/screenshots && screencap -p /sdcard/Binnacle/screenshots/$(date +%Y%m%d-%H%M%S).png && echo BINNACLE_OK
String:no reply from the shell
String:pm grant com.shilapi.xcertplay android.permission.CALL_PHONE && telecom set-phone-account-enabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && telecom set-user-selected-outgoing-phone-account com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && su 0 pm disable com.android.dialer/com.android.incallui.InCallServiceImpl && echo BINNACLE_OK
String:service call wifi 58 i32 0 && echo BINNACLE_OK
String:service call wifi 58 i32 1 && echo BINNACLE_OK
String:service call wifi 58 i32 1 && svc wifi enable && echo BINNACLE_OK
String:settings get system xbs_isautoconnect && echo BINNACLE_OK
String:settings put system xbs_isautoconnect false && echo BINNACLE_OK
String:settings put system xbs_isautoconnect true && echo BINNACLE_OK
String:shell:
String:su 0 pm disable com.xbs.frontcam/com.xbs.frontcam.CameraActivity && echo BINNACLE_OK
String:su 0 pm enable com.android.dialer/com.android.incallui.InCallServiceImpl && telecom set-phone-account-disabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && pm revoke com.shilapi.xcertplay android.permission.CALL_PHONE && echo BINNACLE_OK
String:su 0 pm enable com.xbs.frontcam/com.xbs.frontcam.CameraActivity && echo BINNACLE_OK
String:su 0 setprop persist.adb.tcp.port \'\' && echo BINNACLE_OK
String:su 0 setprop persist.adb.tcp.port 5555 && echo BINNACLE_OK
String:su 0 setprop persist.bluetooth.disableabsvol \'\' && echo BINNACLE_OK
String:su 0 setprop persist.bluetooth.disableabsvol true && echo BINNACLE_OK
String:su 0 setprop persist.car.use_system_bt \'\' && echo BINNACLE_OK
String:su 0 setprop persist.car.use_system_bt 2 && echo BINNACLE_OK
String:su 0 setprop persist.vendor.sys.core.enabled 0 && su 0 sh -c \'rm -rf /data/corefile/*\' && echo BINNACLE_OK
String:su 0 setprop persist.vendor.sys.core.enabled 1 && echo BINNACLE_OK
String:svc wifi disable && sleep 2 && svc wifi enable && echo BINNACLE_OK
String:unexpected ADB reply
String:while su 0 iptables -D INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null; do :; done; while su 0 ip6tables -D INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null; do :; done; echo BINNACLE_OK
String:{ su 0 iptables -C INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null || su 0 iptables -I INPUT -p tcp --dport 5555 ! -i lo -j DROP; } && { su 0 ip6tables -C INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null || su 0 ip6tables -I INPUT -p tcp --dport 5555 ! -i lo -j DROP; } && echo BINNACLE_OK
EOF
)
[ "$got" = "$expected" ] || { diff <(printf '%s\n' "$expected") <(printf '%s\n' "$got") >&2; bad "LocalShell strings changed: review, then update this guard"; }

# 9b. LocalShell: its whole compiled code is pinned, not just its strings. The ADB message codes
#     are int constants, so a second stream (an OPEN of "exec:su 0 ..." spelled as bytes) would add
#     no string at all; it changes this listing instead. The listing leaves out the constant-pool
#     numbers and offsets, so only a real change to the code fails it. After a reviewed change to
#     LocalShell: run this script, check the listing it prints, then put its new hash here.
SHELL_CLASSES=$(printf '%s\n' "$files" | grep -E '/app/binnacle/LocalShell(\$[^/]*)?\.class$')
if [ -n "$SHELL_CLASSES" ]; then
    shell_code=$(javap -c -p $SHELL_CLASSES 2>/dev/null | grep -v '^Compiled from' \
        | sed -E 's/^ +[0-9]+: /  /; s/#[0-9]+(, *[0-9]+)?/#/; s/^( +)ldc(_w|2_w)? /\1ldc /; s/^( +(if[a-z_]*|goto(_w)?|jsr(_w)?)) +[0-9]+$/\1/; s/^ +[0-9-]+: [0-9]+$/  case/; s/ +$//; s/  +/ /g')
    # sha256sum (Linux, GNU coreutils) or shasum (macOS); no tool means no hash, which fails below.
    if command -v sha256sum >/dev/null 2>&1; then hash_cmd='sha256sum'
    elif command -v shasum >/dev/null 2>&1; then hash_cmd='shasum -a 256'
    else bad "no sha256sum or shasum found: cannot check LocalShell's code"; hash_cmd='false'; fi
    sum=$(printf '%s\n' "$shell_code" | $hash_cmd | cut -c1-64)
    [ "$sum" = '7c99412d5a362a08bff8ce1b6407f2ade418e5f39d04fb7b20b8669217389a87' ] || { printf '%s\n' "$shell_code" >&2; bad "LocalShell's code changed (hash $sum): review the listing above, then update this guard"; }
    # and, readably: send() only from run(), four times (connect, open the one shell stream, ack, close)
    [ "$(refs '^app/binnacle/LocalShell\.send$' | awk -F'\t' '$1 == "app/binnacle/LocalShell" && $2 == "run"' | wc -l | tr -d ' ')" = 4 ] \
        && [ "$(refs '^app/binnacle/LocalShell\.send$' | wc -l | tr -d ' ')" = 4 ] || bad "LocalShell.run must send exactly its four ADB messages"
fi

# 10. Nested classes are what they say they are: a class whose name holds a '$' is let through the
#     per-class rules above as part of its outer class, so it must come from that class's own source
#     file. A top-level class named "LocalShell$X" written in Evil.java is refused.
#     (check_readonly.sh also refuses a '$' in any class name written in the source.)
nested=$(printf '%s\n' "$files" | grep '\$')
if [ -n "$nested" ]; then
    fake=$(javap $nested 2>/dev/null | awk '
        /^Compiled from / { src = $3; gsub(/"/, "", src); next }
        /(class|interface|enum) / {
            for (i = 1; i <= NF; i++) if ($i == "class" || $i == "interface" || $i == "enum") { n = $(i+1); break }
            sub(/<.*/, "", n); outer = n; sub(/\$.*/, "", outer); sub(/.*\./, "", outer)
            # AGP writes the R classes straight to R.jar, with no source file: those are checked below
            if (src == "" && n ~ /^app\.binnacle\.R\$[a-z_]+$/) { src = ""; next }
            if (src != outer ".java") print n " (from " src ")"; src = ""
        }')
    [ -z "$fake" ] || { printf '%s\n' "$fake" >&2; bad "classes named like nested ones that are not nested (above)"; }
fi
# 10b. The R classes (app/binnacle/R and R$<type>) hold resource ids and nothing else: only static
#      int / int[] fields and a constructor, and no code beyond setting those fields. Checked for
#      every R class, from build.sh's javac or AGP's R.jar alike.
R_CLASSES=$(printf '%s\n' "$files" | grep -E '/app/binnacle/R(\$[^/]*)?\.class$')
if [ -n "$R_CLASSES" ]; then
    r_members=$(javap -p $R_CLASSES 2>/dev/null | grep -vE '^(Compiled from |\}$)' \
        | grep -vE '^public (final )?class app\.binnacle\.R(\$[a-z_]+)? \{$' \
        | grep -vE '^  public static (final )?int(\[\])? [A-Za-z_][A-Za-z0-9_]*;$' \
        | grep -vE '^  (public |private )?app\.binnacle\.R(\$[a-z_]+)?\(\);$' \
        | grep -vE '^  static \{\};$')
    [ -z "$r_members" ] || { printf '%s\n' "$r_members" >&2; bad "R classes may hold only resource ids (above)"; }
    r_calls=$(printf '%s\n' "$TABLE" | awk -F'\t' '$1 ~ /^app\/binnacle\/R(\$|$)/ && $4 != "" && $4 != "java/lang/Object.<init>" && $4 !~ /^app\/binnacle\/R(\$[a-z_]+)?\.[A-Za-z_][A-Za-z0-9_]*$/')
    [ -z "$r_calls" ] || { printf '%s\n' "$r_calls" >&2; bad "R classes may only set their own ids (above)"; }
fi

[ $fail -eq 0 ] && echo "check_bytecode: OK (compiled code is receive-only)"
exit $fail
