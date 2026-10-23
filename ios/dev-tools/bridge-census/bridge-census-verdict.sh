#!/usr/bin/env bash
# bridge-census-verdict.sh — adjudicate an iOS bridge census result (run-census-sim.sh writes one).
#
#   bridge-census-verdict.sh <result.txt> [lookups.tsv]
#
# GREEN when the result checked exactly the lookups the native source makes and every one resolved. Without a list
# it extracts one from the tree as it is now, so a result from an older build (it checked a different set) is RED:
# rerun the census. Exits 0 exactly when GREEN; the last line is VERDICT: IOS_BRIDGE_CENSUS_GREEN or _RED.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RESULT="${1:-}"
LIST="${2:-}"

red() {
  echo "!! $*"
  echo "IOS_BRIDGE_CENSUS_RED"
  echo "VERDICT: IOS_BRIDGE_CENSUS_RED"
  exit 1
}

[ -n "$RESULT" ] || { echo "usage: bridge-census-verdict.sh <result.txt> [lookups.tsv]" >&2; exit 2; }
[ -f "$RESULT" ] || red "no census result at $RESULT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if [ -z "$LIST" ]; then
  LIST="$TMP/lookups.tsv"
  /usr/bin/python3 "$HERE/extract-lookups.py" > "$LIST" 2> "$TMP/extract.err" \
    || red "extract-lookups.py failed: $(tail -1 "$TMP/extract.err")"
fi
[ -f "$LIST" ] || red "no lookup list at $LIST"

ERR="$(grep '^ERROR' "$RESULT" | head -1)"
[ -z "$ERR" ] || red "the census could not run: ${ERR#ERROR	}"
DONE="$(tail -1 "$RESULT")"
case "$DONE" in DONE*) ;; *) red "the result has no DONE line (the census stopped part-way): $RESULT" ;; esac
field() { printf '%s\n' "$DONE" | tr '\t' '\n' | sed -n "s/^$1=//p"; }
CHECKED="$(field checked)"
MISSING="$(field missing)"
SEQ="$(field frameSeq)"
WAITED="$(field waitedMs)"

# The entries the app checked, against the entries the source looks up now.
awk -F'\t' '$1 == "OK" || $1 == "MISSING" { print $2 "\t" $3 "\t" $4 "\t" $5 }' "$RESULT" | LC_ALL=C sort > "$TMP/got"
awk -F'\t' 'NF { print $1 "\t" $2 "\t" $3 "\t" $4 }' "$LIST" | LC_ALL=C sort > "$TMP/want"
WANT="$(wc -l < "$TMP/want" | tr -d ' ')"
GOT="$(wc -l < "$TMP/got" | tr -d ' ')"
[ "$GOT" = "$CHECKED" ] || red "DONE says checked=$CHECKED but the result has $GOT entries"
grep '^MISSING' "$RESULT" | cut -f2- | tr '\t' ' ' | sed 's/^/   missing: /'
if ! cmp -s "$TMP/got" "$TMP/want"; then
  ONLY_SRC="$(LC_ALL=C comm -13 "$TMP/got" "$TMP/want" | wc -l | tr -d ' ')"
  ONLY_RES="$(LC_ALL=C comm -23 "$TMP/got" "$TMP/want" | wc -l | tr -d ' ')"
  LC_ALL=C comm -13 "$TMP/got" "$TMP/want" | head -5 | tr '\t' ' ' | sed 's/^/   not checked: /'
  red "the result is for a different build ($ONLY_SRC source lookups not checked, $ONLY_RES checked lookups not in the source). Rerun the census."
fi
[ "$MISSING" = 0 ] || red "$MISSING of $CHECKED lookups did not resolve"

BODY="$(grep -E '^(OK|MISSING)	' "$RESULT" | cut -f1-5 | LC_ALL=C sort)"
MD5="$(printf '%s' "$BODY" | md5)"
echo "result=$RESULT"
echo "checked=$CHECKED missing=$MISSING (list $WANT) first frame after ${WAITED} ms, seq $SEQ"
echo "census_md5=$MD5"
[ "${SEQ:-0}" -gt 0 ] || echo "   note: no frame was drawn within the wait, so the lookups ran before the client finished booting."
echo "IOS_BRIDGE_CENSUS_GREEN md5=$MD5"
echo "VERDICT: IOS_BRIDGE_CENSUS_GREEN"
