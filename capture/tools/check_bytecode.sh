#!/bin/bash
# The logger's second line of defence (same as app/tools/check_bytecode.sh, pinned to the logger):
# checks what javac produced, so no source trick hides a write path. build.sh runs it on
# build/classes after javac and on the packed APK before signing.
#   usage: tools/check_bytecode.sh <classes dir>      the compiled classes
#          tools/check_bytecode.sh --apk <apk>        the packed APK
set -uo pipefail
cd "$(dirname "$0")/.."
P=app/binnacle/logger
fail=0
bad() { echo "check_bytecode (logger): $*" >&2; fail=1; }

# The APK: one dex, no native libraries, no assets, nothing else executable.
if [ "${1:-}" = --apk ]; then
    APK="${2:?usage: check_bytecode.sh --apk <apk>}"
    entries=$(unzip -Z1 "$APK") || { bad "can't list $APK"; exit 1; }
    [ "$(printf '%s\n' "$entries" | grep -cE '\.dex$')" = 1 ] && printf '%s\n' "$entries" | grep -qx 'classes.dex' || bad "APK must hold classes.dex and no other dex"
    printf '%s\n' "$entries" | grep -E '^lib/|^assets/|\.(so|jar|apk|odex|vdex|oat)$' && bad "native libraries, assets or nested code in the APK (above)"
    while IFS= read -r e; do
        case "$e" in classes.dex|AndroidManifest.xml|resources.arsc|*/) continue;; esac
        magic=$(unzip -p "$APK" "$e" | head -c 4 | od -An -tx1 | tr -d ' \n')
        case "$magic" in 6465780a|7f454c46|504b0304|cafebabe) bad "executable or archive in the APK: $e";; esac
    done <<< "$entries"
    [ $fail -eq 0 ] && echo "check_bytecode (logger): OK (APK holds only the checked code)"
    exit $fail
fi

CLASSES="${1:?usage: check_bytecode.sh <classes dir> | --apk <apk>}"

files=$(find "$CLASSES" -name '*.class' | sort)
[ -n "$files" ] || { bad "no classes in $CLASSES"; exit 1; }
[ -z "$(find "$CLASSES" -type l)" ] || bad "symlinks in $CLASSES"

# One line per instruction: class <TAB> method <TAB> opcode <TAB> reference (see app/tools/check_bytecode.sh).
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
# invokedynamic names its target only in the bootstrap table, which the checks below don't read.
printf '%s\n' "$TABLE" | awk -F'\t' '$3 == "invokedynamic"' | grep . >&2 && bad "invokedynamic is not allowed (above)"

# (patterns go through the environment: awk -v would eat their backslashes)
refs() { printf '%s\n' "$TABLE" | RE="$1" awk -F'\t' '$4 ~ ENVIRON["RE"]'; }
only() {
    local hits; hits=$(refs "$1" | OK="^($2)([$].*)?$" awk -F'\t' '$1 !~ ENVIRON["OK"]')
    [ -z "$hits" ] || { printf '%s\n' "$hits" >&2; bad "$1 used outside $2 (above)"; }
}
never() {
    local hits; hits=$(refs "$1")
    [ -z "$hits" ] || { printf '%s\n' "$hits" >&2; bad "forbidden: $1 (above)"; }
}

# 1. Binder: two transacts, both in CaptureService: register passes 1, unregister passes 2.
only '\.transact$' "$P/CaptureService"
[ "$(refs '\.transact$' | wc -l | tr -d ' ')" = 2 ] || bad "exactly two transacts allowed"
codes=$(printf '%s\n' "$TABLE" | awk -F'\t' -v c="$P/CaptureService" '
    $1 == c { op[NR] = $3; m[NR] = $2; r[NR] = $4 }
    END { for (i in r) if (r[i] ~ /\.transact$/) print m[i] "=" op[i-4] }' | sort | tr '\n' ' ')
[ "$codes" = "register=iconst_1 unregister=iconst_2 " ] || bad "transact codes must be register=1, unregister=2 (got: $codes)"
never '^android/os/(ServiceManager|HwBinder|HwParcel|SystemProperties)'
never '^android/os/Messenger\.send$'
never '\.(queryLocalInterface|linkToDeath)$'
javap -p $files 2>/dev/null | grep -nE ' native |extends android\.os\.Binder|implements .*android\.os\.IInterface' \
    && bad "native methods or binder stubs (above)"

# 2. No reflection, dynamic code, native libraries, processes, sockets or network at all.
never '^java/lang/reflect/'
never '^java/lang/invoke/(MethodHandles?|VarHandle)'
never '^java/lang/Class\.(forName|getMethods?|getDeclaredMethods?|getFields?|getDeclaredFields?|getConstructors?|getDeclaredConstructors?|newInstance)$'
never '^java/lang/ClassLoader\.(loadClass|defineClass)'
never '^dalvik/system/'
never '^java/lang/(System|Runtime)\.(load|loadLibrary)$'
never '^java/lang/(Runtime\.exec|ProcessBuilder|Process)'
never '^java/net/|^javax/net/|^java/nio/channels/|^android/net/Local(Server)?Socket'

# 3. Files: FileWriter only in CaptureService; content resolver only in MainActivity's export.
only '^java/io/FileWriter' "$P/CaptureService"
never '^java/io/(FileOutputStream|RandomAccessFile)|^java/io/(PrintStream|PrintWriter)\.<init>$|^java/nio/file/|^android/os/ParcelFileDescriptor|^android/system/Os'
only '^android/content/ContentResolver|\.getContentResolver$|^android/content/ContentValues|^android/provider/MediaStore' "$P/MainActivity"
[ "$(refs '^android/content/ContentResolver\.' | awk -F'\t' '{ print $4 }' | sort -u | tr '\n' ' ')" = 'android/content/ContentResolver.insert android/content/ContentResolver.openOutputStream ' ] \
    || bad "MainActivity may only insert into Downloads and write that row"
never '^android/provider/Settings|^android/content/ContentProviderClient'
# exactly one insert, into the Downloads collection (the URI is loaded straight before the values),
# and one openOutputStream; no URI built from text anywhere (it could name the CAN provider)
[ "$(refs '^android/content/ContentResolver\.insert$' | wc -l | tr -d ' ')" = 1 ] && [ "$(refs '^android/content/ContentResolver\.openOutputStream$' | wc -l | tr -d ' ')" = 1 ] \
    || bad "MainActivity may insert once and write once"
printf '%s\n' "$TABLE" | awk -F'\t' '{ r[NR] = $4 } END { for (i = 1; i <= NR; i++) if (r[i] == "android/content/ContentResolver.insert") print r[i-2] }' \
    | grep -qx 'android/provider/MediaStore$Downloads.EXTERNAL_CONTENT_URI' || bad "the logger may insert only into MediaStore.Downloads"
never '^android/net/Uri\.(parse|fromFile|withAppendedPath|fromParts)$|^android/net/Uri\$Builder|^android/content/ContentUris'


# 4. Other apps: bind (CaptureService), state broadcast (StateReceiver), own service (MainActivity).
only '\.bindService$' "$P/CaptureService"
only '\.sendBroadcast$' "$P/StateReceiver"
only '\.(startService|startForegroundService)$' "$P/MainActivity"
never '\.(sendOrderedBroadcast|sendStickyBroadcast|sendBroadcastAsUser|startActivity|startInstrumentation)$'

# 5. No string anywhere that names a car-write path.
never '^String:.*(com\.xbs\.carsetup|com\.xbs\.can\.provider|/dev/tty|ttyHS|McuSerial|McuManager|com\.mct\.serial|txMessengerPack|txGeneralProtocol|txPerformUartPack|txUpdateProtocol|setUartNormalTransmitEnable|writeData)'

[ $fail -eq 0 ] && echo "check_bytecode (logger): OK (compiled code is receive-only)"
exit $fail
