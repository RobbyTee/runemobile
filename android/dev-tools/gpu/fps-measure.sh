#!/usr/bin/env bash
# FIX-13 fps measurement harness.
#
# Reads the once/sec engine-fps probe that Hooks.frame emits in 117HD test mode
# (logcat line:  [fpsprobe] fps=N gs=STATE), captures a window, and prints
# mean/median/min/max + a per-sample CSV. Use it to A/B a 117HD preset in-world
# on real Mali: hold a fixed spot + camera, capture a "baseline" window, change
# the preset (redeploy or flip the config live), capture a second window, compare.
#
# The probe only logs while gs=LOGGED_IN frames are flowing, so capture while the
# scene is actually rendering. fps here is the engine's own render rate
# (Client.getFPS) — it drops when the GPU is the bottleneck, which is exactly the
# signal these levers move.
#
# Usage:
#   fps-measure.sh [-s SERIAL] [-d SECONDS] [-o OUT.csv] [LABEL]   # live capture over adb
#   fps-measure.sh --from-file LOGCAT.txt [-o OUT.csv] [LABEL]     # parse an existing dump
#   cat logcat.txt | fps-measure.sh --stdin [LABEL]               # parse from a pipe
#
# Notes:
#   - macOS has no `timeout`; live capture backgrounds `adb logcat` and kills it after -d seconds.
#   - Only LOGGED_IN samples are counted toward fps stats (login-screen frames are excluded).
set -euo pipefail

SERIAL=""
DUR=20
OUT=""
LABEL="run"
SRC="adb"
FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    -d) DUR="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --from-file) SRC="file"; FILE="$2"; shift 2 ;;
    --stdin) SRC="stdin"; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) LABEL="$1"; shift ;;
  esac
done

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="adb"
adb_args=(); [[ -n "$SERIAL" ]] && adb_args=(-s "$SERIAL")

raw=""
case "$SRC" in
  stdin)
    raw="$(cat)"
    ;;
  file)
    [[ -f "$FILE" ]] || { echo "no such file: $FILE" >&2; exit 1; }
    raw="$(cat "$FILE")"
    ;;
  adb)
    echo ">> capturing ${DUR}s of fps probe (label=$LABEL)${SERIAL:+ from $SERIAL} ..." >&2
    "$ADB" "${adb_args[@]}" logcat -c 2>/dev/null || true
    tmp="$(mktemp)"
    "$ADB" "${adb_args[@]}" logcat -v time > "$tmp" 2>/dev/null &
    logpid=$!
    sleep "$DUR"
    kill "$logpid" 2>/dev/null || true
    wait "$logpid" 2>/dev/null || true
    raw="$(cat "$tmp")"
    rm -f "$tmp"
    ;;
esac

# Pull "[fpsprobe] fps=N gs=STATE" lines; keep only LOGGED_IN samples for the fps stats.
samples="$(printf '%s\n' "$raw" \
  | grep -oE '\[fpsprobe\] fps=[0-9]+ gs=[A-Z_]+' \
  | sed -E 's/\[fpsprobe\] fps=([0-9]+) gs=([A-Z_]+)/\1 \2/')"

if [[ -z "$samples" ]]; then
  echo "no [fpsprobe] samples found. Is 117HD test mode on (rlhd.mode) and the scene rendering?" >&2
  exit 2
fi

# CSV
if [[ -n "$OUT" ]]; then
  { echo "label,idx,fps,gs"; i=0; while read -r fps gs; do echo "$LABEL,$i,$fps,$gs"; i=$((i+1)); done <<< "$samples"; } > "$OUT"
  echo ">> wrote $(wc -l < "$OUT" | tr -d ' ') rows to $OUT" >&2
fi

# Stats over LOGGED_IN samples only.
printf '%s\n' "$samples" | awk -v label="$LABEL" '
  $2=="LOGGED_IN" { v[n++]=$1; sum+=$1 }
  END {
    if (n==0) { print "no LOGGED_IN samples (only login-screen frames captured)"; exit 3 }
    # sort for median/min/max
    for (i=0;i<n;i++) for (j=i+1;j<n;j++) if (v[j]<v[i]) { t=v[i]; v[i]=v[j]; v[j]=t }
    med = (n%2) ? v[int(n/2)] : (v[n/2-1]+v[n/2])/2
    printf "%-10s  n=%-3d  mean=%.1f  median=%.1f  min=%d  max=%d\n", label, n, sum/n, med, v[0], v[n-1]
  }'
