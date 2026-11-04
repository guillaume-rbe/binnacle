#!/bin/bash
# ABSOLUTE RULE, second line of defence: check_readonly.sh reads the source text; this reads what
# javac actually produced, so whitespace, comments, unicode escapes, symlinks or string tricks in
# the source can't hide a write path. build.sh runs it on build/classes after javac and on the
# packed APK before signing, and the build fails if anything here fails.
#   usage: tools/check_bytecode.sh <classes dir>      the compiled classes
#          tools/check_bytecode.sh --apk <apk>        the packed APK (one dex, nothing else executable)
set -uo pipefail
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

# 4. No processes; sockets only in LocalShell (loopback ADB), HTTP only in CarPacks.
never '^java/lang/(Runtime\.exec|ProcessBuilder|Process)'
never '^java/net/(ServerSocket|DatagramSocket|MulticastSocket)|^java/nio/channels/|^android/net/Local(Server)?Socket'
only '^java/net/(Socket|InetSocketAddress|InetAddress)(\.|$)' 'app/binnacle/LocalShell'
only '^java/net/(URL|URLConnection|HttpURLConnection)|^javax/net/' 'app/binnacle/CarPacks'
never '^java/net/(HttpURLConnection|URLConnection)\.(setRequestMethod|setDoOutput|getOutputStream)$'

# 5. Files: FileOutputStream only in CarPacks (its own files dir), FileWriter only in CameraPane
#    (the camera decoder's sysfs nodes); nothing else may open a file for writing.
only '^java/io/FileOutputStream' 'app/binnacle/CarPacks'
only '^java/io/FileWriter' 'app/binnacle/CameraPane'
never '^java/io/(RandomAccessFile|PrintStream\.<init>|PrintWriter\.<init>)$|^java/io/RandomAccessFile|^java/nio/file/|^android/os/ParcelFileDescriptor|^android/system/Os'

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

# 8. No string anywhere that names a car-write path, even if nothing calls it yet.
never '^String:.*(com\.xbs\.carsetup|com\.xbs\.can\.provider|/dev/tty|ttyHS|McuSerial|McuManager|com\.mct\.serial|txMessengerPack|txGeneralProtocol|txPerformUartPack|txUpdateProtocol|setUartNormalTransmitEnable|writeData)'

# 9. LocalShell: its string constants are exactly these. A new command, a different ADB service
#    (exec:, root:, reboot:, ...) or a concatenated one would add a string here.
got=$(refs '^String:' | awk -F'\t' '$1 ~ /^app\/binnacle\/LocalShell(\$|$)/ { print $4 }' | sort -u)
expected=$(sort -u <<'EOF'
String:127.0.0.1
String:ADB on the unit asks for a key
String:BINNACLE_OK
String:BT_AUTOCONNECT_GET
String:BT_AUTOCONNECT_OFF
String:BT_AUTOCONNECT_ON
String:END_CARPLAY
String:WIFI_REJOIN
String:WIFI_SCANS_OFF
String:WIFI_SCANS_ON
String:service call wifi 58 i32 0 && echo BINNACLE_OK
String:service call wifi 58 i32 1 && echo BINNACLE_OK
String:service call wifi 58 i32 1 && svc wifi enable && echo BINNACLE_OK
String:UTF-8
String:\u0000
String:bad ADB message
String:host::\u0000
String:no reply from the shell
String:settings get system xbs_isautoconnect && echo BINNACLE_OK
String:settings put system xbs_isautoconnect false && echo BINNACLE_OK
String:settings put system xbs_isautoconnect true && echo BINNACLE_OK
String:shell:
String:svc wifi disable && sleep 2 && svc wifi enable && echo BINNACLE_OK
String:unexpected ADB reply
EOF
)
[ "$got" = "$expected" ] || { diff <(printf '%s\n' "$expected") <(printf '%s\n' "$got") >&2; bad "LocalShell strings changed: review, then update this guard"; }

[ $fail -eq 0 ] && echo "check_bytecode: OK (compiled code is receive-only)"
exit $fail
