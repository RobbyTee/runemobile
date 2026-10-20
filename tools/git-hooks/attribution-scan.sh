#!/usr/bin/env bash
# Scan text on stdin against the local attribution denylist; print the lines that match.
#
# Commits here carry the author's name only. The patterns are deliberately NOT in this file: the hooks
# are published with the repo, so the list lives outside it -- one extended regex per line, matched
# case-insensitively, blank lines and '#' comments ignored:
#   ${RUNEMOBILE_ATTRIBUTION_DENYLIST:-${XDG_CONFIG_HOME:-$HOME/.config}/rune-mobile/attribution-denylist}
#
# Used by pre-commit (staged added lines) and commit-msg (the message).
# Exit: 0 = clean, or no list (one notice on stderr -- a clone without the list still commits)
#       1 = match (matching lines on stdout, cut to 160 chars)
#       2 = the list exists but is unusable (unreadable, not a file, bad regex) -- fail loudly
set -euo pipefail

list="${RUNEMOBILE_ATTRIBUTION_DENYLIST:-${XDG_CONFIG_HOME:-$HOME/.config}/rune-mobile/attribution-denylist}"
# Only a list that is absent passes; one that is there but cannot be read must not switch the guard off.
if [ ! -e "$list" ] && [ ! -L "$list" ]; then
	echo "attribution guard: no denylist at $list -- skipped" >&2
	exit 0
fi
if [ ! -f "$list" ] || [ ! -r "$list" ]; then
	echo "attribution guard: $list exists but is not a readable file -- fix it or remove it" >&2
	exit 2
fi
patterns="$(grep -vE '^[[:space:]]*(#|$)' "$list" || true)"
if [ -z "$patterns" ]; then
	echo "attribution guard: $list has no patterns -- skipped" >&2
	exit 0
fi

set +e
hits="$(grep -iE -f <(printf '%s\n' "$patterns"))"
rc=$?
set -e
case "$rc" in
	0) printf '%s\n' "$hits" | cut -c1-160; exit 1 ;;
	1) exit 0 ;;
	*) echo "attribution guard: grep failed on $list (rc=$rc) -- fix the pattern file" >&2; exit 2 ;;
esac
