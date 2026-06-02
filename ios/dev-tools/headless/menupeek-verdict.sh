#!/usr/bin/env bash
# GREEN/RED verdict for the press-and-peek menu-peek primitive de-risk. Computed HERE in bash from the
# grepped PEEK_* markers and md5-gated against the verbatim log — never trusting a JVM-printed success string
# (the documented false-GREEN guard). Used by the desktop runner (run-menupeek.sh) and, later, a device
# confirm (a pulled jvm-stdio.log).
#
# Usage: menupeek-verdict.sh <menupeek.log>
#
# Gates (all must hold for GREEN):
#   - probe completed (PEEK_DONE=1) and no PEEK_FATAL
#   - at least MIN_STATIONARY (default 4) NAMED entities held still through their peek [else INCONCLUSIVE]
#   - tailMatchStat == stationaryNamed : EVERY named entity that held still had its hovered TOP (tail) entry
#                            target it — the core claim that getMenuEntries() reflects the synthetic-hover
#                            point, tail = left-click. (Movers/offscreen are reported, NOT gated: a wanderer
#                            sliding off its clickbox mid-peek is exactly what release-revalidation handles.)
#   - menuOpenDuringPeek == 0 : peek is hover-only, it never opened a right-click menu
#   - maxTicks <= MAX_TICKS_BOUND (default 10) : the read stabilizes within a few client cycles (latency)
set -euo pipefail

LOG="${1:?usage: menupeek-verdict.sh <menupeek.log>}"
MAX_TICKS_BOUND="${MAX_TICKS_BOUND:-10}"
MIN_STATIONARY="${MIN_STATIONARY:-4}"

md5of() { if command -v md5 >/dev/null; then md5 -q; else md5sum | cut -d' ' -f1; fi; }

# Pull a field out of the single "PEEK_VERDICT key=val key=val" line.
vfield() { grep -m1 "^PEEK_VERDICT " "$LOG" 2>/dev/null | grep -oE "$1=[^ ]+" | head -1 | cut -d= -f2- || true; }

DONE="$(grep -cE '^PEEK_DONE=1' "$LOG" 2>/dev/null || true)"
FATAL="$(grep -m1 '^PEEK_FATAL=' "$LOG" 2>/dev/null | cut -d= -f2- || true)"
HAS_VERDICT="$(grep -cE '^PEEK_VERDICT ' "$LOG" 2>/dev/null || true)"

CANDS="$(vfield cands)";              CANDS="${CANDS:--1}"
NAMED="$(vfield named)";              NAMED="${NAMED:--1}"
STAT="$(vfield stationaryNamed)";     STAT="${STAT:--1}"
TAILSTAT="$(vfield tailMatchStat)";   TAILSTAT="${TAILSTAT:--1}"
MOVED="$(vfield movedNamed)";         MOVED="${MOVED:--1}"
OFFSCR="$(vfield offscreenNamed)";    OFFSCR="${OFFSCR:--1}"
TAILMATCH="$(vfield tailMatch)";      TAILMATCH="${TAILMATCH:--1}"
ANYMATCH="$(vfield anyMatch)";        ANYMATCH="${ANYMATCH:--1}"
EMPTY="$(vfield emptyMenus)";         EMPTY="${EMPTY:--1}"
MAXTICKS="$(vfield maxTicks)";        MAXTICKS="${MAXTICKS:--1}"
AVGTICKS="$(vfield avgTicks)";        AVGTICKS="${AVGTICKS:--1}"
MENUOPEN="$(vfield menuOpenDuringPeek)"; MENUOPEN="${MENUOPEN:--1}"

echo
echo "================ PRESS-AND-PEEK PRIMITIVE DE-RISK ================"
printf '  %-30s %s\n' "probe completed"            "$([ "${DONE:-0}" -ge 1 ] && echo true || echo false)"
printf '  %-30s %s\n' "no fatal"                   "$([ -z "$FATAL" ] && echo true || echo "FATAL: $FATAL")"
printf '  %-30s %s\n' "candidates peeked"          "$CANDS  (named NPCs/players: $NAMED)"
printf '  %-30s %s\n' "** stationary tail-match **" "$TAILSTAT / $STAT   (the core gate)"
printf '  %-30s %s\n' "movers / offscreen"         "$MOVED / $OFFSCR   (excluded — release re-read handles)"
printf '  %-30s %s\n' "tail-match (all named)"     "$TAILMATCH / $NAMED   (incl. movers; informational)"
printf '  %-30s %s\n' "some entry targets entity"  "$ANYMATCH / $NAMED"
printf '  %-30s %s\n' "empty menus"                "$EMPTY"
printf '  %-30s %s\n' "stabilize ticks (max/avg)"  "$MAXTICKS / $AVGTICKS  (bound <= $MAX_TICKS_BOUND)"
printf '  %-30s %s\n' "menu opened during peek"    "$MENUOPEN  (must be 0)"

STATE="RED"
if [ "${DONE:-0}" -lt 1 ] || [ "${HAS_VERDICT:-0}" -lt 1 ] || [ -n "$FATAL" ]; then
  STATE="RED"
elif { [ "$STAT" -lt "$MIN_STATIONARY" ]; } 2>/dev/null; then
  # Too few entities held still to validate the claim (distinct from a real failure).
  STATE="INCONCLUSIVE"
else
  PASS=1
  [ "$TAILSTAT" = "$STAT" ]              || PASS=0
  [ "$MENUOPEN" = "0" ]                  || PASS=0
  { [ "$MAXTICKS" -ge 0 ] && [ "$MAXTICKS" -le "$MAX_TICKS_BOUND" ]; } 2>/dev/null || PASS=0
  [ "$PASS" = "1" ] && STATE="GREEN" || STATE="RED"
fi

TUPLE="done=${DONE:-0};fatal=$([ -z "$FATAL" ] && echo none || echo "$FATAL");cands=$CANDS;named=$NAMED;stat=$STAT;tailstat=$TAILSTAT;moved=$MOVED;offscr=$OFFSCR;tailmatch=$TAILMATCH;anymatch=$ANYMATCH;empty=$EMPTY;maxticks=$MAXTICKS;menuopen=$MENUOPEN;bound=$MAX_TICKS_BOUND;minstat=$MIN_STATIONARY"
MD5="$(printf '%s' "$TUPLE" | md5of)"
echo "  tuple: $TUPLE"
echo "  md5:   $MD5"
echo "  VERDICT: $STATE"
echo "================================================================="
[ "$STATE" = "GREEN" ]
