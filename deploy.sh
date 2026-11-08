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

say()  { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'stopped: %s\n' "$*" >&2; exit 1; }

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
        say "CarPlay: not in $tag, versionCode $cp_now is new"
    elif carplay_changed "$tag"; then
        if [ "$cp_now" -gt "$cp_then" ]; then
            say "CarPlay: changed since $tag, versionCode $cp_then -> $cp_now (ok)"
        elif [ "${ALLOW_SAME_CARPLAY_VERSION:-}" = 1 ]; then
            warn "CarPlay changed since $tag but versionCode is still $cp_now (ALLOW_SAME_CARPLAY_VERSION=1): the dash's updater won't offer it"
        else
            say "CarPlay: carplay/ changed since $tag but versionCode is still $cp_now."
            say "  Bump versionCode (and versionName) in carplay/mobile/build.gradle.kts, or the dash's"
            say "  updater skips the new CarPlay app. Changed files:"
            git diff --name-only "$tag" -- carplay ':(exclude,glob)carplay/**/*.md' | sed 's/^/    /' | head -20
            ok=1
        fi
    else
        say "CarPlay: unchanged since $tag (versionCode $cp_now)"
    fi

    local d_now d_then name
    d_now=$(dash_code); d_then=$(dash_code "$tag"); name=$(dash_name)
    [ -n "$d_now" ] && [ -n "$name" ] || { say "no versionCode/versionName in app/AndroidManifest.xml"; return 1; }
    if [ -n "$d_then" ] && [ "$d_now" -le "$d_then" ]; then
        if [ "$mode" = release ]; then
            say "Dash: versionCode is still $d_now, as in $tag. Bump android:versionCode and versionName in"
            say "  app/AndroidManifest.xml: the dash only offers a higher one."
            ok=1
        else
            say "Dash: versionCode $d_now, same as $tag (fine for a test build; bump it before a release)"
        fi
    else
        say "Dash: versionCode ${d_then:-?} -> $d_now, versionName $name"
    fi
    if [ "$mode" = release ] && git rev-parse -q --verify "refs/tags/v$name" >/dev/null; then
        say "Tag v$name already exists: bump versionName in app/AndroidManifest.xml."
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
        say "main is up to date ($(git log -1 --format='%h %s'))"
    else
        say "main updated:"; git log --format='  %h %s' "$before..$after" | head -30
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
cmd_status() {
    fetch
    local branch tag
    branch=$(git rev-parse --abbrev-ref HEAD)
    say "Branch: $branch ($(git log -1 --format='%h %s'))"
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
    say "Checks for a release:"
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
    say; say "Built from $(git log -1 --format='%h %s'). Publish with ./deploy.sh release --no-build"
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
        build
    fi

    local logger f
    logger="$OUT/BinnacleLogger-$(logger_name).apk"
    for f in "$GH_DIR/Binnacle.apk" "$GH_DIR/BinnacleCarPlay.apk" "$GH_DIR/binnacle-update.txt" "$logger"; do
        [ -f "$f" ] || die "missing $f"
    done
    grep -qx "versionCode=$(dash_code)" "$GH_DIR/binnacle-update.txt" || die "binnacle-update.txt doesn't match app/AndroidManifest.xml; rebuild"
    grep -qx "carplayVersionCode=$(cp_code)" "$GH_DIR/binnacle-update.txt" || die "binnacle-update.txt doesn't match the CarPlay version; rebuild"

    say
    say "About to publish $tag on GitHub ($REPO), from $(git log -1 --format='%h %s'):"
    say "  Binnacle.apk          dash $name (versionCode $(dash_code))"
    say "  BinnacleCarPlay.apk   CarPlay $(cp_name) (versionCode $(cp_code))"
    say "  binnacle-update.txt   and $(basename "$logger")"
    if [ -n "$draft" ]; then say "  as a DRAFT (dashes won't see it until you publish it on GitHub)"
    else say "  as the LATEST release: dashes will offer it under Advanced -> Updates"; fi
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
            say "Checked: the latest release now serves this binnacle-update.txt."
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

cmd_menu() {
    cmd_status
    local choice tag last files
    while :; do
        say
        say "What next?"
        say "  1) Get the latest main from GitHub            (pull)"
        say "  2) Check the version numbers                  (check)"
        say "  3) Build signed APKs, publish nothing         (build)"
        say "  4) Build and publish the release              (release)"
        say "  5) Build and publish it as a draft            (release --draft)"
        say "  6) Publish the last build, no rebuild         (release --no-build)"
        say "  7) Publish car-image packs                    (cars)"
        say "  8) Show where things stand again              (status)"
        say "  q) Quit"
        read -rp "Choose: " choice || exit 0
        say
        # Each runs in a subshell, so a stop goes back to the menu instead of quitting.
        case "$choice" in
            1) ( cmd_pull ) || true ;;
            2) ( cmd_check release ) || true ;;
            3) ( cmd_build ) || true ;;
            4) ( cmd_release ) || true ;;
            5) ( cmd_release --draft ) || true ;;
            6) ( cmd_release --no-build ) || true ;;
            7)
                last=$(git tag -l 'cars-v[0-9]*' --sort=-v:refname | head -1)
                say "Last car-image release: ${last:-none}"
                read -rp "Tag (cars-vN): " tag
                # Without -r, read keeps backslash-escaped spaces, as Terminal writes dragged files.
                say "Drag the pack files here (or type their paths), then press Return:"
                # shellcheck disable=SC2162
                read -a files
                if [ -n "$tag" ] && [ "${#files[@]}" -gt 0 ]; then ( cmd_cars "$tag" "${files[@]}" ) || true
                else say "nothing to publish"; fi ;;
            8) cmd_status ;;
            q|Q|"") exit 0 ;;
            *) say "pick 1-8 or q" ;;
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
    cars)    cmd_cars "$@" ;;
    -h|--help|help) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' ;;
    *) die "unknown command $cmd (try ./deploy.sh help)" ;;
esac
