#!/bin/bash
# One script for the maintainer's routine jobs, so nothing depends on remembering the steps.
#
#   ./deploy.sh status    where things stand: branch, local main vs GitHub, versions here, in the
#                         latest release, in the last build and (with DASH=<ip>) on a dash
#   ./deploy.sh pull      switch to main and bring it up to date with GitHub (refuses with local
#                         changes or local commits)
#   ./deploy.sh check     refuse if something changed since the latest release without a version
#                         bump (the dash only offers a higher versionCode, so a missed bump means
#                         the update is silently never offered)
#   ./deploy.sh build     check, then ./release.sh (asks for the keystore password)
#   ./deploy.sh publish   publish the last build as the latest GitHub release, after checking it is
#                         from this commit, is on GitHub's main and matches its info file; asks first
#   ./deploy.sh release   pull, check, build and publish, stopping at the first problem
#   ./deploy.sh install   DASH=<ip> ./deploy.sh install: install the last build on a dash over adb,
#                         CarPlay app first, only what is newer (FORCE=1: also the same version);
#                         saves the installed copies first and prints how to put them back; asks first
#
# Environment: DASH=<ip>[:port] (status, install), NOTES=<file> release notes for publish (default:
# GitHub's generated list of merged PRs), FORCE=1 (install), APP_ID (as release.sh, default
# com.xbs.launcher), ROLLBACK_DIR (install, default build/rollback/<date-time>).
# Nothing here touches the car: install is a plain `adb install -r`, as the docs describe by hand.
set -euo pipefail
cd "$(dirname "$0")"

APP_ID="${APP_ID:-com.xbs.launcher}"
CARPLAY_ID=com.shilapi.xcertplay
GH_DIR=build/release/github
# What each app is built from. Docs (*.md) don't need a new version.
DASH_PATHS="app buildSrc"
CARPLAY_PATHS="carplay"
LOGGER_PATHS="capture"

die() { echo "deploy: $*" >&2; exit 1; }
say() { echo; echo "== $*"; }
ask() { local a; read -rp "$1 [y/N] " a; [ "$a" = y ] || [ "$a" = Y ]; }

# Versions, read from file text on stdin.
manifest_code() { sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' | head -1; }
manifest_name() { sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' | head -1; }
gradle_field()  { sed -n "s/^ *$1 = \"*\([^\"]*\)\"*$/\1/p" | head -1; }
at() { git show "$1:$2" 2>/dev/null || true; }     # a file at a ref; empty if it wasn't there
dash_code()    { at "$1" app/AndroidManifest.xml | manifest_code; }
dash_name()    { at "$1" app/AndroidManifest.xml | manifest_name; }
carplay_code() { at "$1" carplay/mobile/build.gradle.kts | gradle_field versionCode; }
carplay_name() { at "$1" carplay/mobile/build.gradle.kts | gradle_field versionName; }
logger_code()  { at "$1" capture/AndroidManifest.xml | manifest_code; }
logger_name()  { at "$1" capture/AndroidManifest.xml | manifest_name; }
# The same, for the working tree (uncommitted edits included).
here_dash_code()    { manifest_code < app/AndroidManifest.xml; }
here_dash_name()    { manifest_name < app/AndroidManifest.xml; }
here_carplay_code() { gradle_field versionCode < carplay/mobile/build.gradle.kts; }
here_carplay_name() { gradle_field versionName < carplay/mobile/build.gradle.kts; }
here_logger_code()  { manifest_code < capture/AndroidManifest.xml; }
here_logger_name()  { manifest_name < capture/AndroidManifest.xml; }

sha256() { local s; s=$(shasum -a 256 "$1" 2>/dev/null || sha256sum "$1"); echo "${s%% *}"; }
info() { sed -n "s/^$1=//p" "$GH_DIR/binnacle-update.txt"; }   # a line of the built info file
built() { sed -n "s/^$1=//p" "$GH_DIR/built-from.txt" 2>/dev/null || true; }

fetch() { git fetch -q --tags origin 2>/dev/null || echo "(couldn't reach GitHub: using what this checkout already knows)"; }

# The latest published release (never a car-image release, which are published as not-latest).
last_tag() {
    local t=""
    t=$(gh release view --json tagName -q .tagName 2>/dev/null) || t=""
    [ -n "$t" ] || t=$(git tag --list 'v*' --sort=-v:refname | head -1)
    echo "$t"
}

# Changed between a ref and the working tree, ignoring docs.
changed() { local ref=$1; shift; ! git diff --quiet "$ref" -- "$@" ':!*.md'; }

dirty() { [ -n "$(git status --porcelain --untracked-files=no)" ]; }
NOTHING_NEW=   # set by check when no app code changed since the latest release

# ---------------------------------------------------------------------------------------------
check() {
    local tag; tag=$(last_tag)
    if [ -z "$tag" ]; then echo "no earlier release to compare with"; return 0; fi
    git rev-parse -q --verify "refs/tags/$tag" >/dev/null || { echo "tag $tag isn't in this checkout (run ./deploy.sh pull)"; return 1; }
    local bad=0 d c l
    d=no; c=no; l=no
    changed "$tag" $DASH_PATHS && d=yes
    changed "$tag" $CARPLAY_PATHS && c=yes
    changed "$tag" $LOGGER_PATHS && l=yes
    echo "latest release: $tag"
    if [ $d = no ] && [ $c = no ] && [ $l = no ]; then
        echo "nothing to release: no app code changed since $tag"
        NOTHING_NEW=1
        return 0
    fi
    local now was
    now=$(here_dash_code); was=$(dash_code "$tag")
    # Every release needs a new dash version: it names the release (v<versionName>) and is what the
    # dash compares, even when only the CarPlay app changed.
    if [ "${now:-0}" -gt "${was:-0}" ]; then
        echo "ok    Binnacle   $(dash_name "$tag") ($was) -> $(here_dash_name) ($now)$([ $d = yes ] || echo ', no dash code changes')"
    else
        echo "FAIL  Binnacle   versionCode is still $now (as in $tag), but there are changes to release:"
        echo "                 bump android:versionCode and versionName in app/AndroidManifest.xml"
        bad=1
    fi
    now=$(here_carplay_code); was=$(carplay_code "$tag")
    if [ $c = yes ] && [ "${now:-0}" -le "${was:-0}" ]; then
        echo "FAIL  CarPlay    carplay/ changed since $tag but versionCode is still $now:"
        echo "                 bump versionCode (and versionName) in carplay/mobile/build.gradle.kts,"
        echo "                 or the dash will never offer the new CarPlay app"
        bad=1
    elif [ "${now:-0}" -gt "${was:-0}" ]; then
        echo "ok    CarPlay    ${was:-none} -> $now ($(here_carplay_name)): the dash will offer it"
    else
        echo "ok    CarPlay    unchanged ($now): the dash won't offer it"
    fi
    now=$(here_logger_code); was=$(logger_code "$tag")
    if [ $l = yes ] && [ "${now:-0}" -le "${was:-0}" ]; then
        echo "warn  Logger     capture/ changed but versionCode is still $now (it is installed by hand,"
        echo "                 so this only matters for adb install -r)"
    fi
    return $bad
}

pull() {
    if dirty; then git status --short --untracked-files=no; die "local changes in the checkout (above): commit or stash them first"; fi
    local b; b=$(git rev-parse --abbrev-ref HEAD)
    if [ "$b" != main ]; then echo "switching from $b to main"; git checkout -q main; fi
    local before; before=$(git rev-parse HEAD)
    git fetch -q --tags origin || die "couldn't reach GitHub"
    git merge --ff-only -q origin/main || die "local main has commits that aren't on GitHub; sort that out by hand"
    if [ "$before" = "$(git rev-parse HEAD)" ]; then
        echo "main is up to date: $(git log -1 --format='%h %s')"
    else
        echo "new on main:"; git log --oneline "$before..HEAD"
    fi
}

build() {
    local b; b=$(git rev-parse --abbrev-ref HEAD)
    if [ "$b" != main ]; then ask "This checkout is on '$b', not main. Build it anyway?" || exit 1; fi
    if dirty; then echo "note: uncommitted changes are included; fine for a test build, but publish will refuse it"; fi
    check || ask "The version check failed (above). Build anyway, as a test build that can't be published?" || exit 1
    if [ -n "$NOTHING_NEW" ]; then ask "Nothing new to release. Build anyway, as a test build?" || exit 1; fi
    DEPLOY_CHECKED=1 ./release.sh
}

publish() {
    local f
    for f in Binnacle.apk BinnacleCarPlay.apk binnacle-update.txt built-from.txt; do
        [ -f "$GH_DIR/$f" ] || die "no $GH_DIR/$f: run ./deploy.sh build first"
    done
    fetch
    dirty && die "uncommitted changes in the checkout: commit them (and build again) first"
    local commit; commit=$(built commit)
    [ "$(built dirty)" = no ] || die "the last build included uncommitted changes: build again from a clean checkout"
    [ "$commit" = "$(git rev-parse HEAD)" ] || die "the last build is from ${commit:0:7}, this checkout is at $(git rev-parse --short HEAD): build again"
    git merge-base --is-ancestor "$commit" origin/main || die "${commit:0:7} isn't on GitHub's main yet: merge it there first"
    # The info file must describe exactly these files (the dash checks size and SHA-256 against it).
    [ "$(sha256 "$GH_DIR/Binnacle.apk")" = "$(info sha256)" ] || die "Binnacle.apk doesn't match binnacle-update.txt"
    [ "$(wc -c < "$GH_DIR/Binnacle.apk" | tr -d ' ')" = "$(info size)" ] || die "Binnacle.apk's size doesn't match binnacle-update.txt"
    [ "$(sha256 "$GH_DIR/BinnacleCarPlay.apk")" = "$(info carplaySha256)" ] || die "BinnacleCarPlay.apk doesn't match binnacle-update.txt"
    [ "$(wc -c < "$GH_DIR/BinnacleCarPlay.apk" | tr -d ' ')" = "$(info carplaySize)" ] || die "BinnacleCarPlay.apk's size doesn't match binnacle-update.txt"
    [ "$(info versionCode)" = "$(here_dash_code)" ] || die "the build's versionCode ($(info versionCode)) isn't this checkout's ($(here_dash_code)): build again"
    [ "$(info carplayVersionCode)" = "$(here_carplay_code)" ] || die "the build's CarPlay versionCode isn't this checkout's: build again"
    check || die "fix the versions first (above)"
    local name tag; name=$(info versionName); tag="v$name"
    git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "$tag already exists: bump the version"
    local files=("$GH_DIR/Binnacle.apk" "$GH_DIR/BinnacleCarPlay.apk" "$GH_DIR/binnacle-update.txt")
    local logger; logger="build/release/BinnacleLogger-$(here_logger_name).apk"
    [ -f "$logger" ] && files+=("$logger")
    local notes=(--generate-notes)
    if [ -n "${NOTES:-}" ]; then [ -f "$NOTES" ] || die "no notes file $NOTES"; notes=(--notes-file "$NOTES"); fi
    say "about to publish"
    echo "release   $tag \"Binnacle $name\", marked latest, tag on ${commit:0:7} ($(git log -1 --format=%s "$commit"))"
    echo "Binnacle  $(info versionName) ($(info versionCode))   CarPlay $(info carplayVersionName) ($(info carplayVersionCode))"
    echo "files     ${files[*]}"
    echo "notes     ${NOTES:-generated from the merged PRs}"
    ask "Publish it on GitHub now? Dashes will be offered it from Advanced -> Updates." || exit 1
    gh release create "$tag" "${files[@]}" --target "$commit" --title "Binnacle $name" --latest "${notes[@]}"
    git fetch -q --tags origin || true
    echo "published. On the dash: Apps -> Advanced -> Updates -> Check for updates (in Park)."
}

install_dash() {
    local dash="${DASH:-}"
    [ -n "$dash" ] || die "which dash? DASH=<ip> ./deploy.sh install"
    case "$dash" in *:*) ;; *) dash="$dash:5555" ;; esac
    for f in Binnacle.apk BinnacleCarPlay.apk binnacle-update.txt; do
        [ -f "$GH_DIR/$f" ] || die "no $GH_DIR/$f: run ./deploy.sh build first"
    done
    adb connect "$dash" >/dev/null 2>&1 || true
    [ "$(adb -s "$dash" get-state 2>/dev/null)" = device ] || die "can't reach the dash at $dash (is it on Wi-Fi?)"
    local A=(adb -s "$dash")
    local out="${ROLLBACK_DIR:-build/rollback/$(date +%Y-%m-%d_%H%M%S)}"
    local plan=() pkg file new have
    # CarPlay first: installing the dash restarts it.
    for pkg in "$CARPLAY_ID" "$APP_ID"; do
        if [ "$pkg" = "$CARPLAY_ID" ]; then file=BinnacleCarPlay.apk; new=$(info carplayVersionCode); else file=Binnacle.apk; new=$(info versionCode); fi
        have=$("${A[@]}" shell dumpsys package "$pkg" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
        if [ -z "$have" ]; then echo "skip  $pkg: not installed on the dash (install it the first time by hand)"; continue; fi
        if [ "$new" -gt "$have" ] || { [ "$new" = "$have" ] && [ "${FORCE:-}" = 1 ]; }; then
            plan+=("$pkg:$file:$have:$new"); echo "plan  $pkg: $have -> $new"
        elif [ "$new" = "$have" ]; then echo "skip  $pkg: already $have (FORCE=1 to reinstall this build)"
        else echo "skip  $pkg: the dash has $have, newer than this build's $new"
        fi
    done
    [ ${#plan[@]} -gt 0 ] || { echo "nothing to install"; return 0; }
    ask "Save the installed copies to $out and install the above on $dash?" || exit 1
    mkdir -p "$out"
    local p path since undo=()
    for p in "${plan[@]}"; do
        IFS=: read -r pkg file have new <<< "$p"
        path=$("${A[@]}" shell pm path "$pkg" | sed -n 's/^package://p' | head -1 | tr -d '\r')
        "${A[@]}" pull "$path" "$out/$pkg-$have.apk" >/dev/null
        undo+=("adb -s $dash install -r -d $out/$pkg-$have.apk")
    done
    since=$("${A[@]}" shell date +'%m-%d %H:%M:%S.000' | tr -d '\r')
    for p in "${plan[@]}"; do
        IFS=: read -r pkg file have new <<< "$p"
        echo "installing $file ($pkg $new)"
        "${A[@]}" install -r -d "$GH_DIR/$file"
    done
    say "after the install"
    "${A[@]}" shell dumpsys package "$CARPLAY_ID" | grep -m1 versionCode || true
    "${A[@]}" shell dumpsys package "$APP_ID" | grep -m1 versionCode || true
    echo "CarPlay location app-op (Binnacle sets it back to allow when it links up with CarPlay):"
    "${A[@]}" shell appops get "$CARPLAY_ID" COARSE_LOCATION || true
    echo "crashes since the install (none is good):"
    "${A[@]}" logcat -b crash -d -T "$since" | grep -E "FATAL|Process:" || echo "  none"
    say "to undo"
    printf '  %s\n' "${undo[@]}"
}

status() {
    fetch
    echo "checkout: $(git rev-parse --abbrev-ref HEAD) at $(git log -1 --format='%h %s')"
    local behind; behind=$(git rev-list --count main..origin/main 2>/dev/null || echo "?")
    if [ "$behind" = 0 ]; then echo "main:     up to date with GitHub"; else echo "main:     $behind commit(s) behind GitHub (./deploy.sh pull)"; fi
    if dirty; then echo "changes:"; git status --short --untracked-files=no; fi
    echo "here:     Binnacle $(here_dash_name) ($(here_dash_code)), CarPlay $(here_carplay_name) ($(here_carplay_code)), Logger $(here_logger_name) ($(here_logger_code))"
    if [ -f "$GH_DIR/binnacle-update.txt" ]; then
        local c from; c=$(built commit)
        if [ -z "$c" ]; then from="an unrecorded commit (built before deploy.sh)"
        else
            from=${c:0:7}
            [ "$(built dirty)" = yes ] && from="$from with uncommitted changes"
            [ "$c" != "$(git rev-parse HEAD)" ] && from="$from, not this checkout"
        fi
        echo "built:    Binnacle $(info versionName) ($(info versionCode)), CarPlay $(info carplayVersionName) ($(info carplayVersionCode)), $(date -r "$GH_DIR/binnacle-update.txt" '+%d %b %H:%M'), from $from"
    else
        echo "built:    nothing yet (./deploy.sh build)"
    fi
    if [ -n "${DASH:-}" ]; then
        local dash="$DASH"; case "$dash" in *:*) ;; *) dash="$dash:5555" ;; esac
        adb connect "$dash" >/dev/null 2>&1 || true
        if [ "$(adb -s "$dash" get-state 2>/dev/null)" = device ]; then
            echo "dash:     Binnacle $(adb -s "$dash" shell dumpsys package "$APP_ID" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1), CarPlay $(adb -s "$dash" shell dumpsys package "$CARPLAY_ID" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1) (versionCodes)"
        else
            echo "dash:     can't reach $dash"
        fi
    fi
    echo
    check || true
}

case "${1:-}" in
    status)  status ;;
    pull)    pull ;;
    check)   fetch; check ;;
    build)   fetch; build ;;
    publish) publish ;;
    release) pull; check || die "fix the versions first (above)"
             [ -z "$NOTHING_NEW" ] || die "nothing new to release"
             DEPLOY_CHECKED=1 ./release.sh; publish ;;
    install) install_dash ;;
    *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; [ -z "${1:-}" ] || exit 1 ;;
esac
