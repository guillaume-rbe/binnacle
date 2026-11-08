#!/bin/bash
# One command for the release chores, run from the repo on the owner's Mac.
#
#   ./deploy.sh               a menu (on a terminal): pick what to do by number, no flags to remember
#   ./deploy.sh status        where things stand: branch, versions, the last release, what changed
#                             since it and which versionCode needs a bump (changes nothing)
#   ./deploy.sh pull          switch to main and fast-forward it from GitHub
#   ./deploy.sh check         the version checks alone (release.sh runs this too)
#   ./deploy.sh build         pull, check, then ./release.sh (signed APKs, nothing published)
#   ./deploy.sh release       pull, check, build, then publish the GitHub release v<versionName>
#                             as the LATEST release (what the dash's Advanced -> Updates reads)
#       --draft      publish it as a draft instead (not visible to the dash until you publish it)
#       --no-build   reuse build/release from an earlier `build` of this same commit
#       --yes        don't ask before publishing
#   ./deploy.sh bump [--yes]  bump the dash's version (and CarPlay's if carplay/ changed) on a new
#                             branch and open the PR; merge it, then release
#   ./deploy.sh cars TAG FILE... [--yes]   publish car-image packs (TAG = cars-vN) as a release that never
#                             becomes the latest (--latest=false), or add the files to it
#
# Checks (they stop the command):
#   - carplay/ changed since the last v* release tag but its versionCode in
#     carplay/mobile/build.gradle.kts isn't higher: the dash's updater would skip the new CarPlay app.
#     Markdown-only changes don't count. To build anyway: ALLOW_SAME_CARPLAY_VERSION=1 ./deploy.sh ...
#   - for `release`: the dash's versionCode in app/AndroidManifest.xml isn't higher than the last
#     release's, or the tag v<versionName> already exists.
#   - for `build`/`release`: not on main, local changes, or main has commits GitHub doesn't.
#
# Needs git; `release` and `cars` need the GitHub CLI (gh), signed in. release.sh still asks for
# the keystore password itself. Nothing here talks to the dash.
set -euo pipefail
cd "$(dirname "$0")"

REPO=lotech/binnacle
OUT=build/release
GH_DIR="$OUT/github"
STAMP="$OUT/BUILD_COMMIT"

# Colour only on a terminal, and not when NO_COLOR is set (https://no-color.org).
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    B=$'\033[1m' D=$'\033[2m' RED=$'\033[31m' GRN=$'\033[32m' YEL=$'\033[33m' CYN=$'\033[36m' N=$'\033[0m'
else
    B="" D="" RED="" GRN="" YEL="" CYN="" N=""
fi
if [ -t 2 ] && [ -z "${NO_COLOR:-}" ]; then E_RED=$'\033[1;31m' E_YEL=$'\033[33m' E_N=$'\033[0m'
else E_RED="" E_YEL="" E_N=""; fi

say()  { printf '%s\n' "$*"; }
good() { printf '%s\n' "$GRN$*$N"; }        # a check that passed
bad()  { printf '%s\n' "$RED$*$N"; }        # a check that stops the command
note() { printf '%s\n' "$YEL$*$N"; }        # worth knowing, doesn't stop anything
title() { printf '%s\n' "$B$CYN$*$N"; }      # a heading
warn() { printf '%s\n' "${E_YEL}warning: $*$E_N" >&2; }
die()  { printf '%s\n' "${E_RED}stopped: $*$E_N" >&2; exit 1; }

# --- versions -------------------------------------------------------------------------------
# dash_code/cp_code [REF]: versionCode in the working tree, or at a git ref.
file_at() { if [ -n "${2:-}" ]; then git show "$2:$1" 2>/dev/null || true; else cat "$1"; fi; }
dash_code() { file_at app/AndroidManifest.xml "${1:-}" | sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' | head -1; }
dash_name() { file_at app/AndroidManifest.xml "${1:-}" | sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' | head -1; }
cp_code()   { file_at carplay/mobile/build.gradle.kts "${1:-}" | sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' | head -1; }
cp_name()   { file_at carplay/mobile/build.gradle.kts "${1:-}" | sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' | head -1; }
logger_name() { sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' capture/AndroidManifest.xml | head -1; }

last_tag() { git tag -l 'v[0-9]*' --sort=-v:refname | head -1; }

# carplay/ changed between the tag and the working tree, ignoring Markdown.
carplay_changed() {
    ! git diff --quiet "$1" -- carplay ':(exclude,glob)carplay/**/*.md'
}

fetch() {   # best effort: offline still lets status/check run on what is here
    git fetch --quiet --tags origin main 2>/dev/null || warn "couldn't reach GitHub; using the tags and main already here"
}

# --- checks ---------------------------------------------------------------------------------
# check_versions [release]: 0 if fine, prints why not and returns 1 otherwise.
check_versions() {
    local mode="${1:-}" tag ok=0
    tag=$(last_tag)
    if [ -z "$tag" ]; then
        warn "no v* release tag here, skipping the version checks (git fetch --tags?)"
        return 0
    fi
    local cp_now cp_then
    cp_now=$(cp_code); cp_then=$(cp_code "$tag")
    [ -n "$cp_now" ] || { say "no CarPlay versionCode in carplay/mobile/build.gradle.kts"; return 1; }
    if [ -z "$cp_then" ]; then
        good "CarPlay: not in $tag, versionCode $cp_now is new"
    elif carplay_changed "$tag"; then
        if [ "$cp_now" -gt "$cp_then" ]; then
            good "CarPlay: changed since $tag, versionCode $cp_then -> $cp_now (ok)"
        elif [ "${ALLOW_SAME_CARPLAY_VERSION:-}" = 1 ]; then
            warn "CarPlay changed since $tag but versionCode is still $cp_now (ALLOW_SAME_CARPLAY_VERSION=1): the dash's updater won't offer it"
        else
            bad "CarPlay: carplay/ changed since $tag but versionCode is still $cp_now."
            say "  Bump versionCode (and versionName) in carplay/mobile/build.gradle.kts, or the dash's"
            say "  updater skips the new CarPlay app. Changed files:"
            git diff --name-only "$tag" -- carplay ':(exclude,glob)carplay/**/*.md' | sed 's/^/    /' | head -20
            ok=1
        fi
    else
        good "CarPlay: unchanged since $tag (versionCode $cp_now)"
    fi

    local d_now d_then name
    d_now=$(dash_code); d_then=$(dash_code "$tag"); name=$(dash_name)
    [ -n "$d_now" ] && [ -n "$name" ] || { say "no versionCode/versionName in app/AndroidManifest.xml"; return 1; }
    if [ -n "$d_then" ] && [ "$d_now" -le "$d_then" ]; then
        if [ "$mode" = release ]; then
            bad "Dash: versionCode is still $d_now, as in $tag. Bump android:versionCode and versionName in"
            say "  app/AndroidManifest.xml: the dash only offers a higher one."
            ok=1
        else
            note "Dash: versionCode $d_now, same as $tag (fine for a test build; bump it before a release)"
        fi
    else
        good "Dash: versionCode ${d_then:-?} -> $d_now, versionName $name"
    fi
    if [ "$mode" = release ] && git rev-parse -q --verify "refs/tags/v$name" >/dev/null; then
        bad "Tag v$name already exists: bump versionName in app/AndroidManifest.xml."
        ok=1
    fi
    return $ok
}

need_clean() {
    [ -z "$(git status --porcelain)" ] || { git status --short | head -20; die "local changes; commit or stash them first"; }
}

# Switch to main and fast-forward it. Refuses local changes and local-only commits on main.
pull_main() {
    need_clean
    git fetch --quiet --tags origin main || die "couldn't fetch from GitHub"
    [ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { say "switching to main"; git checkout --quiet main; }
    local ahead
    ahead=$(git rev-list --count origin/main..main)
    [ "$ahead" = 0 ] || die "main has $ahead commit(s) GitHub doesn't; main is protected, so put them on a branch and open a PR"
    local before after
    before=$(git rev-parse HEAD)
    git merge --quiet --ff-only origin/main
    after=$(git rev-parse HEAD)
    if [ "$before" = "$after" ]; then
        good "main is up to date ($(git log -1 --format='%h %s'))"
    else
        good "main updated:"; git log --format='  %h %s' "$before..$after" | head -30
    fi
}

need_gh() {
    command -v gh >/dev/null || die "the GitHub CLI isn't installed (brew install gh)"
    gh auth status >/dev/null 2>&1 || die "gh isn't signed in (gh auth login)"
}

confirm() {   # confirm QUESTION: yes unless the answer isn't y
    [ "${YES:-}" = 1 ] && return 0
    local a; read -rp "$1 [y/N] " a
    case "$a" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

build() {   # release.sh writes $STAMP itself (the commit, when the tree was clean)
    BINNACLE_DEPLOY=1 ./release.sh   # the checks already ran; release.sh skips its own copy
}

# --- commands -------------------------------------------------------------------------------
# next_name VERSION: the last number plus one (0.9 -> 0.10, 0.9.1 -> 0.9.2, 1.4.2 -> 1.4.3).
next_name() { local v="$1"; echo "${v%.*}.$(( ${v##*.} + 1 ))"; }

ask() {   # ask QUESTION DEFAULT: what was typed, or DEFAULT on Return
    local a; read -rp "$1 [$2]: " a; echo "${a:-$2}"
}

# Bump the dash's version (and the CarPlay app's when carplay/ changed since the last release) on a
# new branch and open a PR for it: main is protected, so the bump lands through a merge like any
# other change. After merging it, release from the menu (or ./deploy.sh release).
cmd_bump() {
    while [ $# -gt 0 ]; do
        case "$1" in --yes|-y) YES=1 ;; *) die "unknown option $1" ;; esac
        shift
    done
    need_gh
    pull_main
    local tag d_code d_name cp_c cp_n new_name new_code cp_new_name="" cp_new_code="" branch
    tag=$(last_tag)
    d_code=$(dash_code); d_name=$(dash_name); cp_c=$(cp_code); cp_n=$(cp_name)
    [ -n "$d_code" ] && [ -n "$d_name" ] && [ -n "$cp_c" ] && [ -n "$cp_n" ] || die "couldn't read the current versions"

    say "Dash is $d_name (versionCode $d_code)."
    if [ -n "$tag" ] && [ -n "$(dash_code "$tag")" ] && [ "$d_code" -gt "$(dash_code "$tag")" ] &&
        ! git rev-parse -q --verify "refs/tags/v$d_name" >/dev/null; then
        say "  It is already bumped past $tag: nothing to do for the dash."
    else
        if [ "${YES:-}" = 1 ]; then new_name=$(next_name "$d_name"); else new_name=$(ask "New dash versionName" "$(next_name "$d_name")"); fi
        new_code=$((d_code + 1))
        git rev-parse -q --verify "refs/tags/v$new_name" >/dev/null && die "tag v$new_name already exists; pick another name"
    fi

    if [ -n "$tag" ] && [ -n "$(cp_code "$tag")" ] && carplay_changed "$tag" && [ "$cp_c" -le "$(cp_code "$tag")" ]; then
        say "CarPlay changed since $tag and is still $cp_n (versionCode $cp_c)."
        if [ "${YES:-}" = 1 ]; then cp_new_name=$(next_name "$cp_n"); else cp_new_name=$(ask "New CarPlay versionName" "$(next_name "$cp_n")"); fi
        cp_new_code=$((cp_c + 1))
    else
        say "CarPlay: no bump needed ($cp_n, versionCode $cp_c)."
    fi
    [ -n "${new_name:-}" ] || [ -n "$cp_new_name" ] || { say "Nothing to bump."; return 0; }

    say
    [ -z "${new_name:-}" ] || say "  dash:    $d_name ($d_code) -> $new_name ($new_code)"
    [ -z "$cp_new_name" ] || say "  CarPlay: $cp_n ($cp_c) -> $cp_new_name ($cp_new_code)"
    branch="release-${new_name:-carplay-$cp_new_name}"
    git rev-parse -q --verify "refs/heads/$branch" >/dev/null && die "branch $branch already exists here"
    git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1 && die "branch $branch already exists on GitHub (an open bump PR?)"
    confirm "Make branch $branch with this bump and open a PR?" || die "nothing changed"

    git checkout --quiet -b "$branch"
    local msg="" body=""
    if [ -n "${new_name:-}" ]; then
        sed -i.bak "s/android:versionCode=\"$d_code\" android:versionName=\"$d_name\"/android:versionCode=\"$new_code\" android:versionName=\"$new_name\"/" app/AndroidManifest.xml
        rm -f app/AndroidManifest.xml.bak
        [ "$(dash_code)" = "$new_code" ] && [ "$(dash_name)" = "$new_name" ] || die "couldn't edit app/AndroidManifest.xml (branch $branch left as is)"
        msg="Release $new_name (versionCode $new_code)"
        body="Dash $d_name ($d_code) -> $new_name ($new_code)."
    fi
    if [ -n "$cp_new_name" ]; then
        sed -i.bak -e "s/^\( *versionCode = \)$cp_c\$/\1$cp_new_code/" \
            -e "s/^\( *versionName = \)\"$cp_n\"\$/\1\"$cp_new_name\"/" carplay/mobile/build.gradle.kts
        rm -f carplay/mobile/build.gradle.kts.bak
        [ "$(cp_code)" = "$cp_new_code" ] && [ "$(cp_name)" = "$cp_new_name" ] || die "couldn't edit carplay/mobile/build.gradle.kts (branch $branch left as is)"
        msg="${msg:+$msg, }CarPlay $cp_new_name ($cp_new_code)"
        body="$body CarPlay $cp_n ($cp_c) -> $cp_new_name ($cp_new_code), because carplay/ changed since $tag. Touches carplay/: merge with a merge commit, not squash."
    fi
    git commit --quiet -am "$msg"
    git push --quiet -u origin "$branch"
    gh pr create --repo "$REPO" --base main --head "$branch" --title "$msg" \
        --body "$body

Opened by \`./deploy.sh bump\`. After merging: \`./deploy.sh\` -> 6 (build and publish the release)."
    git checkout --quiet main
    good "Back on main. Merge the PR, then choose 6 in the menu to build and publish."
}

cmd_status() {
    fetch
    local branch tag
    branch=$(git rev-parse --abbrev-ref HEAD)
    title "Branch: $branch ($(git log -1 --format='%h %s'))"
    if git rev-parse -q --verify origin/main >/dev/null; then
        local behind ahead
        behind=$(git rev-list --count HEAD..origin/main); ahead=$(git rev-list --count origin/main..HEAD)
        if [ "$behind" = 0 ] && [ "$ahead" = 0 ]; then say "  same commit as GitHub's main"
        else say "  $behind behind and $ahead ahead of GitHub's main"; fi
    fi
    [ -z "$(git status --porcelain)" ] || say "  local changes: $(git status --porcelain | wc -l | tr -d ' ') file(s)"
    say "Dash:    $(dash_name) (versionCode $(dash_code))"
    say "CarPlay: $(cp_name) (versionCode $(cp_code))"
    tag=$(last_tag)
    if [ -n "$tag" ]; then
        say "Last release tag: $tag, $(git rev-list --count "$tag"..HEAD) commit(s) since"
        local n
        n=$(git log --oneline "$tag"..HEAD -- carplay | wc -l | tr -d ' ')
        [ "$n" = 0 ] || say "  $n of them touch carplay/"
    fi
    if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
        local latest
        latest=$(gh release view --repo "$REPO" --json tagName -q .tagName 2>/dev/null || true)
        say "Latest GitHub release (what the dash's updater reads): ${latest:-none}"
    fi
    if [ -f "$STAMP" ]; then
        local built
        built=$(cat "$STAMP")
        if [ "$built" = "$(git rev-parse HEAD)" ]; then say "build/release was built from this commit"
        else say "build/release is from another commit (${built:0:7})"; fi
    fi
    say
    title "Checks for a release:"
    if check_versions release | sed 's/^/  /'; then :; fi
}

cmd_check() {
    fetch
    check_versions "${1:-}" || die "fix the versions above first"
}

cmd_pull() { pull_main; }

cmd_build() {
    pull_main
    check_versions || die "fix the versions above first"
    build
    say; good "Built from $(git log -1 --format='%h %s'). Publish with ./deploy.sh release --no-build"
}

cmd_release() {
    local draft="" nobuild=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --draft) draft=1 ;;
            --no-build) nobuild=1 ;;
            --yes|-y) YES=1 ;;
            *) die "unknown option $1 (see the top of deploy.sh)" ;;
        esac
        shift
    done
    need_gh
    pull_main
    check_versions release || die "fix the versions above first"
    local name tag
    name=$(dash_name); tag="v$name"
    git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1 && die "tag $tag already exists on GitHub"

    if [ -n "$nobuild" ]; then
        [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$(git rev-parse HEAD)" ] ||
            die "build/release isn't from this commit (or was built with local changes); run without --no-build"
    else
        build || die "the build failed"
    fi
    # Whichever way, publish only a build release.sh finished and stamped with this commit.
    [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$(git rev-parse HEAD)" ] || die "build/release isn't a finished build of this commit"

    local logger f
    logger="$OUT/BinnacleLogger-$(logger_name).apk"
    for f in "$GH_DIR/Binnacle.apk" "$GH_DIR/BinnacleCarPlay.apk" "$GH_DIR/binnacle-update.txt" "$logger"; do
        [ -f "$f" ] || die "missing $f"
    done
    grep -qx "versionCode=$(dash_code)" "$GH_DIR/binnacle-update.txt" || die "binnacle-update.txt doesn't match app/AndroidManifest.xml; rebuild"
    grep -qx "carplayVersionCode=$(cp_code)" "$GH_DIR/binnacle-update.txt" || die "binnacle-update.txt doesn't match the CarPlay version; rebuild"

    say
    title "About to publish $tag on GitHub ($REPO), from $(git log -1 --format='%h %s'):"
    say "  Binnacle.apk          dash $name (versionCode $(dash_code))"
    say "  BinnacleCarPlay.apk   CarPlay $(cp_name) (versionCode $(cp_code))"
    say "  binnacle-update.txt   and $(basename "$logger")"
    if [ -n "$draft" ]; then note "  as a DRAFT (dashes won't see it until you publish it on GitHub)"
    else note "  as the LATEST release: dashes will offer it under Advanced -> Updates"; fi
    confirm "Publish?" || die "not published (the build stays in $OUT)"

    local flags
    if [ -n "$draft" ]; then flags="--draft"; else flags="--latest"; fi
    gh release create "$tag" --repo "$REPO" --target "$(git rev-parse HEAD)" \
        --title "Binnacle $name" --generate-notes $flags \
        "$GH_DIR/Binnacle.apk" "$GH_DIR/BinnacleCarPlay.apk" "$GH_DIR/binnacle-update.txt" "$logger"
    git fetch --quiet --tags origin || true

    if [ -z "$draft" ]; then
        # What a dash will download: the latest release's info file must be this one.
        local live
        live=$(curl -fsSL "https://github.com/$REPO/releases/latest/download/binnacle-update.txt" 2>/dev/null || true)
        if [ "$live" = "$(cat "$GH_DIR/binnacle-update.txt")" ]; then
            good "Checked: the latest release now serves this binnacle-update.txt."
        else
            warn "the latest release's binnacle-update.txt doesn't match yet (GitHub may still be updating); check $tag on GitHub"
        fi
    fi
}

cmd_cars() {
    local tag="" a
    set -- "$@" --end
    while [ "$1" != --end ]; do
        a="$1"; shift
        case "$a" in
            --yes|-y) YES=1 ;;
            *) if [ -z "$tag" ]; then tag="$a"; else set -- "$@" "$a"; fi ;;
        esac
    done
    shift   # --end; what is left are the files
    [ -n "$tag" ] && [ $# -ge 1 ] || die "usage: ./deploy.sh cars cars-vN FILE... [--yes]"
    case "$tag" in cars-v[0-9]*) ;; *) die "car-image tags look like cars-vN, not $tag" ;; esac
    local f
    for f in "$@"; do [ -f "$f" ] || die "no file $f"; done
    need_gh
    if gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; then
        say "Adding $# file(s) to the existing release $tag (replacing files of the same name)."
        confirm "Upload?" || die "not uploaded"
        gh release upload "$tag" --repo "$REPO" --clobber "$@"
    else
        say "Publishing $tag with $# file(s), never as the latest release (the dash's updater reads that)."
        confirm "Publish?" || die "not published"
        gh release create "$tag" --repo "$REPO" --target main --title "Car images ($tag)" \
            --notes "Car-image packs for Car -> Car images. Not a Binnacle release." --latest=false "$@"
    fi
}

# The menu, in the order a release goes: look, prepare, build and publish; car images on their own.
menu_items() {
    local line="${D}  ──────────────────────────────────────────────────────${N}"
    item() { printf '  %s%s)%s %-40s %s%s%s\n' "$B" "$1" "$N" "$2" "$D" "$3" "$N"; }
    say
    say "$line"
    title "  Where things stand"
    item 1 "Show the status"                    "status"
    item 2 "Check the version numbers"          "check"
    say "$line"
    title "  Get ready"
    item 3 "Get the latest main from GitHub"    "pull"
    item 4 "Bump the version numbers (opens a PR)" "bump"
    say "$line"
    title "  Build and publish"
    item 5 "Build signed APKs, publish nothing" "build"
    item 6 "Build and publish the release"      "release"
    item 7 "Build and publish it as a draft"    "release --draft"
    item 8 "Publish the last build, no rebuild" "release --no-build"
    say "$line"
    title "  Car images"
    item 9 "Publish car-image packs"            "cars"
    say "$line"
    item q "Quit"                               ""
}

cmd_menu() {
    cmd_status
    local choice tag last files
    while :; do
        menu_items
        read -rp "${B}Choose:${N} " choice || exit 0
        say
        # Each runs as its own ./deploy.sh, so it keeps `set -e` (a failed step stops that step) and
        # a stop goes back to the menu instead of quitting.
        case "$choice" in
            1) cmd_status ;;
            2) "$0" check release || true ;;
            3) "$0" pull || true ;;
            4) "$0" bump || true ;;
            5) "$0" build || true ;;
            6) "$0" release || true ;;
            7) "$0" release --draft || true ;;
            8) "$0" release --no-build || true ;;
            9)
                last=$(git tag -l 'cars-v[0-9]*' --sort=-v:refname | head -1)
                say "Last car-image release: ${last:-none}"
                read -rp "Tag (cars-vN): " tag
                # Without -r, read keeps backslash-escaped spaces, as Terminal writes dragged files.
                say "Drag the pack files here (or type their paths), then press Return:"
                # shellcheck disable=SC2162
                read -a files
                if [ -n "$tag" ] && [ "${#files[@]}" -gt 0 ]; then "$0" cars "$tag" "${files[@]}" || true
                else say "nothing to publish"; fi ;;
            q|Q|"") exit 0 ;;
            *) say "pick 1-9 or q" ;;
        esac
    done
}

if [ $# -eq 0 ]; then
    if [ -t 0 ] && [ -t 1 ]; then cmd_menu; else cmd_status; fi
    exit 0
fi
cmd="$1"; shift
case "$cmd" in
    status)  cmd_status ;;
    pull)    cmd_pull ;;
    check)   cmd_check "${1:-}" ;;
    build)   cmd_build ;;
    release) cmd_release "$@" ;;
    bump)    cmd_bump "$@" ;;
    cars)    cmd_cars "$@" ;;
    -h|--help|help) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' ;;
    *) die "unknown command $cmd (try ./deploy.sh help)" ;;
esac
