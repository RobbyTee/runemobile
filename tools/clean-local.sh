#!/usr/bin/env bash
# clean-local.sh — reclaim disk from build output, by allowlist. Dry run unless --apply.
#
# Never run `git clean -X` / `-Xdf` here: the ignore rules also cover local-only files that cannot be
# regenerated (working notes, auth tokens, the patched JRE in ios/dev-tools/jre), and -X deletes them all.
#
# A directory is removed only if every one of these holds:
#   - it matches a category below (nothing else is ever considered);
#   - it is a real directory reached through no symlink, git ignores it, and it contains no tracked file;
#   - it is not on the KEEP list (incremental caches that tracked scripts reuse);
#   - nothing inside it changed in the last --days N days (default 7), so a build in progress survives.
#
# Categories (default: xcode headless misc; gradle is opt-in because the rebuild takes minutes):
#   xcode     derived-data and export trees under build/, ios/build/, ios/app/build/
#   headless  ios/dev-tools/headless/out-*/        (re-run the harness; out-mobileui-device needs the device)
#   misc      ios/dev-tools/gpu-*/build/, ios/app/jvm-resources/classes/, __pycache__/
#   gradle    runelite-client/build, runelite-api/build, android/app/build, android/recon/build
#
# Usage:
#   tools/clean-local.sh                     # dry run: what would go, with sizes
#   tools/clean-local.sh --apply             # delete it
#   tools/clean-local.sh --days 0 --apply    # ignore age
#   tools/clean-local.sh --only gradle       # one category (repeatable)
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
ROOT_P="$(pwd -P)"

# Incremental caches: "<dir> <script that points -derivedDataPath at it>". Removing one only costs a full
# rebuild on that script's next run, but they are kept even when stale so that cost is never a surprise.
KEEP_LIST="build/dd-mui ios/dev-tools/remote-build.sh
build/dd-gpu ios/dev-tools/macbook-transition.sh
build/dd-serveipa ios/dev-tools/serve-ipa.sh
ios/app/build/dd-ci tools/ci/build-release-artifacts.sh"

APPLY=0
DAYS=7
CATS=""
usage() { sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'; }
while [ $# -gt 0 ]; do
	case "$1" in
		--apply) APPLY=1 ;;
		--days) DAYS="${2:-}"; shift ;;
		--only) CATS="$CATS ${2:-}"; shift ;;
		-h|--help) usage; exit 0 ;;
		*) echo "clean-local: unknown argument: $1 (see --help)" >&2; exit 2 ;;
	esac
	shift
done
case "$DAYS" in ''|*[!0-9]*) echo "clean-local: --days needs a whole number, got '$DAYS'" >&2; exit 2 ;; esac
[ -z "$CATS" ] && CATS="xcode headless misc"

# Fail loudly if a KEEP entry no longer matches the script it cites, rather than quietly guarding nothing.
while read -r dir script; do
	if [ ! -f "$script" ] || ! grep -q "$(basename "$dir")" "$script"; then
		echo "clean-local: KEEP entry '$dir' no longer appears in $script -- update KEEP_LIST" >&2
		exit 2
	fi
done <<< "$KEEP_LIST"

shopt -s nullglob
candidates() {
	local p
	case "$1" in
		xcode)
			for p in build/dd-* ios/build/dd-* ios/app/build/dd-* ios/app/build/dd ios/app/build/DerivedData \
				ios/app/build/Payload ios/app/build/ipa; do echo "$p"; done ;;
		headless)
			for p in ios/dev-tools/headless/out-*; do echo "$p"; done ;;
		misc)
			for p in ios/dev-tools/gpu-*/build ios/app/jvm-resources/classes; do echo "$p"; done
			find ios android tools -type d -name __pycache__ -prune 2>/dev/null || true ;;
		gradle)
			for p in runelite-client/build runelite-api/build android/app/build android/recon/build; do echo "$p"; done ;;
		*)
			echo "clean-local: unknown category '$1' (xcode headless misc gradle)" >&2; exit 2 ;;
	esac
}

# Prints why a path must stay; prints nothing when it may go.
hold_reason() {
	local p="$1" dir script
	[ -L "$p" ] && { echo "symlink"; return; }
	[ -e "$p" ] || { echo "absent"; return; }
	[ -d "$p" ] || { echo "not a directory"; return; }
	# A symlinked parent (build/ -> elsewhere) would pass every git check below, since git matches path
	# strings, and rm -rf would then delete outside the repo. Require the resolved path to be the literal one.
	[ "$(cd -P -- "$p" 2>/dev/null && pwd -P)" = "$ROOT_P/$p" ] || { echo "path runs through a symlink"; return; }
	while read -r dir script; do
		[ "$p" = "$dir" ] && { echo "cache for $script"; return; }
	done <<< "$KEEP_LIST"
	git check-ignore -q -- "$p" || { echo "not ignored by git"; return; }
	[ -n "$(git ls-files -- "$p" | head -n 1)" ] && { echo "contains tracked files"; return; }
	if [ "$DAYS" -gt 0 ] && [ -n "$(find "$p" -mtime -"$DAYS" -print -quit 2>/dev/null)" ]; then
		echo "changed in the last $DAYS days"; return
	fi
}

human() { awk -v k="$1" 'BEGIN { if (k >= 1048576) printf "%.1fG", k/1048576; else if (k >= 1024) printf "%.0fM", k/1024; else printf "%dK", k }'; }

total=0
for cat in $CATS; do
	list="$(candidates "$cat")"
	[ -z "$list" ] && continue
	while IFS= read -r p; do
		why="$(hold_reason "$p")"
		if [ -n "$why" ]; then
			printf '  keep  %7s  %s  (%s)\n' "" "$p" "$why"
			continue
		fi
		kb="$(du -sk "$p" | cut -f1)"
		total=$((total + kb))
		printf '  %s  %7s  %s\n' "$([ "$APPLY" -eq 1 ] && echo "rm  " || echo "drop")" "$(human "$kb")" "$p"
		[ "$APPLY" -eq 1 ] && rm -rf -- "$p"
	done <<< "$list"
done

if [ "$APPLY" -eq 1 ]; then
	echo "freed $(human "$total")"
else
	echo "would free $(human "$total")  (dry run -- re-run with --apply to delete)"
fi
