#!/usr/bin/env bash
# FIX-13 / Phase-0 fps + frame-time measurement harness.
#
# Reads two once/sec engine probes that Hooks.frame emits in 117HD test mode
# (enabled by -Drunelite.fpsprobe=true):
#   [fpsprobe]   fps=N gs=STATE                              -- smoothed engine fps
#   [frameprobe] n=N min=.. avg=.. p95=.. max=.. gs=STATE    -- per-frame interval (ms), windowed/sec
# the native GPU-time probe (librlawt, only with debug.routec.gputime=1):
#   [gpuprobe]   frames=N avg=.. min=.. max=.. ms disjoint=N hitch=N
# and ThermalGovernor's 117HD load changes (MainActivity tag):
#   thermal status=S -> sceneResolutionScale PREV->NEW (baseline=B)
# captures a window, and prints fps mean/median/min/max AND frame-time
# min/avg/p95/max (ms) + device thermal. Use it to A/B a 117HD preset in-world
# on real Mali: hold a fixed spot + camera, capture a "baseline" window, change
# the preset (redeploy or flip the config live via SET_HD_CONFIG), capture a
# second window, compare. Frame-time percentiles (not one smoothed fps) are what
# let a 2-3 fps tuning delta be told apart from noise.
#
# The probes log once a second in every game state while frames are flowing, so
# capture while the scene is actually rendering. fps here is the engine's own
# render rate (Client.getFPS); frame-time is the wall-clock interval between
# frame() calls.
#
# Usage:
#   fps-measure.sh [-s SERIAL] [-d SECONDS] [-t SECONDS] [-o OUT.csv] [LABEL]   # live capture over adb
#   fps-measure.sh --from-file LOGCAT.txt [-o OUT.csv] [LABEL]                  # parse an existing dump
#   cat logcat.txt | fps-measure.sh --stdin [LABEL]                            # parse from a pipe
#
# Notes:
#   - macOS has no `timeout`; live capture backgrounds `adb logcat` and kills it after -d seconds.
#   - Only LOGGED_IN samples are counted toward the stats (login-screen frames are excluded). [gpuprobe] and
#     the governor carry no game state, so each takes the gs of the last Hooks probe line before it; a window
#     logged before the first Hooks line is untagged and excluded (the summary says how many).
#   - gpu disjoint = frames whose timer-query drain saw GL_GPU_DISJOINT (Mali DVFS raises it under load): the
#     absolute GPU ms then carry a caveat, a relative A/B still holds. hitch = kept results over 100 ms.
#   - Thermal (dumpsys thermalservice) is sampled at the start and end of the adb window, and every -t seconds
#     in between (default 1, 0 = off) as `thermal` CSV rows. File/stdin input has no thermal rows.
#   - A governor scale change mid-capture means the windows before and after it are different workloads; the
#     summary flags it with `!!`.
#   - CSV columns are positional per kind (label,kind,idx,a,b,c,d,e,gs,f,g):
#       fps       a=fps                                       gs
#       frame     a=n b=min c=avg d=p95 e=max (ms)            gs
#       gpu       a=frames b=min c=avg e=max (ms GPU)         gs  f=disjoint g=hitch
#       gpuempty  (a window with no usable GPU-time)          gs
#       thermal   a=elapsed s b=hottest C c=status
#       govern    a=status b=prev scale c=new scale d=baseline e=log time   gs
set -euo pipefail

SERIAL=""
DUR=20
THERMAL_EVERY=1
OUT=""
LABEL="run"
SRC="adb"
FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    -d) DUR="$2"; shift 2 ;;
    -t) THERMAL_EVERY="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --from-file) SRC="file"; FILE="$2"; shift 2 ;;
    --stdin) SRC="stdin"; shift ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) LABEL="$1"; shift ;;
  esac
done

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="adb"
# Every adb call spells the serial as ${SERIAL:+-s "$SERIAL"}: macOS /bin/bash 3.2 calls an empty "${arr[@]}"
# unbound under set -u, and a wrapper function would make the backgrounded logcat's $! the function's subshell,
# so the kill below would leave adb running.

# Hottest temperature (deg C) across all thermal sensors + the device's throttling status, as "HOT STATUS".
# Unprivileged (dumpsys thermalservice), so it works without root; either is "n/a" if the service doesn't say.
# Every pipeline ends in `|| true`: a grep that finds nothing must not end the capture under pipefail.
read_thermal() {
  local dump hot status
  dump="$("$ADB" ${SERIAL:+-s "$SERIAL"} shell dumpsys thermalservice 2>/dev/null || true)"
  hot="$(printf '%s\n' "$dump" | grep -oE 'mValue=[-0-9.]+' | sed -E 's/mValue=//' \
        | awk 'BEGIN{m=-1000} {if($1>m)m=$1} END{if(m>-1000)printf "%.1f",m; else print "n/a"}' || true)"
  status="$(printf '%s\n' "$dump" | grep -oE 'Thermal Status: [0-9]+' | sed -E 's/Thermal Status: //' | head -1 || true)"
  [[ -z "$status" ]] && status="$(printf '%s\n' "$dump" | grep -oE 'mStatus=[0-9]+' | sed -E 's/mStatus=//' | head -1 || true)"
  echo "${hot:-n/a} ${status:-n/a}"
}

sample_thermal() {
  local r
  r="$(read_thermal)"
  echo ">> thermal[$1]: hottest=${r% *}C status=${r#* }" >&2
}

# "ELAPSED_S HOT STATUS" every THERMAL_EVERY seconds until killed.
thermal_loop() {
  local t0
  t0="$(date +%s)"
  while :; do
    echo "$(( $(date +%s) - t0 )) $(read_thermal)"
    sleep "$THERMAL_EVERY"
  done
}

raw=""
thermal_rows=""
case "$SRC" in
  stdin)
    raw="$(cat)"
    ;;
  file)
    [[ -f "$FILE" ]] || { echo "no such file: $FILE" >&2; exit 1; }
    raw="$(cat "$FILE")"
    ;;
  adb)
    echo ">> capturing ${DUR}s of fps/frame probe (label=$LABEL)${SERIAL:+ from $SERIAL} ..." >&2
    sample_thermal start
    "$ADB" ${SERIAL:+-s "$SERIAL"} logcat -c 2>/dev/null || true
    tmp="$(mktemp)"
    ttmp="$(mktemp)"
    "$ADB" ${SERIAL:+-s "$SERIAL"} logcat -v time > "$tmp" 2>/dev/null &
    logpid=$!
    thermpid=""
    if [[ "$THERMAL_EVERY" != 0 ]]; then
      thermal_loop > "$ttmp" 2>/dev/null &
      thermpid=$!
    fi
    sleep "$DUR"
    kill "$logpid" ${thermpid:+"$thermpid"} 2>/dev/null || true
    wait "$logpid" 2>/dev/null || true
    [[ -n "$thermpid" ]] && { wait "$thermpid" 2>/dev/null || true; }
    raw="$(cat "$tmp")"
    thermal_rows="$(cat "$ttmp")"
    rm -f "$tmp" "$ttmp"
    sample_thermal end
    ;;
esac

# One ordered pass turns every probe line into a "kind fields..." record. Empty fields are "-" so the
# whitespace-split readers below keep their columns. gs is the last Hooks probe's state: both Hooks probes log
# once a second in every state, so a [gpuprobe] or governor line's tag is at most about a second old.
records="$(printf '%s\n' "$raw" | awk '
  function kv(s, k,   i, r) { i = index(s, " " k "="); if (!i) return "-"; r = substr(s, i + length(k) + 2); sub(/ .*/, "", r); return r }
  function tag() { return gs == "" ? "-" : gs }
  { tod = ($1 ~ /^[0-9][0-9]-[0-9][0-9]$/ && $2 ~ /^[0-9][0-9]:[0-9][0-9]:/) ? $2 : "-" }
  match($0, /\[fpsprobe\] fps=[0-9]+ gs=[A-Z_]+/) {
    s = substr($0, RSTART, RLENGTH); gs = kv(s, "gs")
    print "fps", kv(s, "fps"), gs; next
  }
  match($0, /\[frameprobe\] n=[0-9]+ min=[0-9.]+ avg=[0-9.]+ p95=[0-9.]+ max=[0-9.]+ gs=[A-Z_]+/) {
    s = substr($0, RSTART, RLENGTH); gs = kv(s, "gs")
    print "frame", kv(s, "n"), kv(s, "min"), kv(s, "avg"), kv(s, "p95"), kv(s, "max"), gs; next
  }
  match($0, /\[gpuprobe\] frames=[0-9]+ avg=[0-9.]+ min=[0-9.]+ max=[0-9.]+ ms( disjoint=[0-9]+)?( hitch=[0-9]+)?/) {
    s = substr($0, RSTART, RLENGTH)
    print "gpu", kv(s, "frames"), kv(s, "avg"), kv(s, "min"), kv(s, "max"), kv(s, "disjoint"), kv(s, "hitch"), tag(); next
  }
  /\[gpuprobe\] no usable GPU-time this window/ { print "gpuempty", tag(); next }
  match($0, /thermal status=-?[0-9]+ -> sceneResolutionScale -?[0-9]+->[0-9]+ \(baseline=[0-9]+\)/) {
    s = substr($0, RSTART, RLENGTH); split(s, w, " "); split(w[5], sc, "->")
    b = s; sub(/.*baseline=/, "", b); sub(/\).*/, "", b)
    print "govern", kv(s, "status"), sc[1], sc[2], b, tod, tag(); next
  }
  match($0, /thermal: recovered a leaked throttle -> restored sceneResolutionScale=[0-9]+/) {
    s = substr($0, RSTART, RLENGTH); sub(/.*=/, "", s)
    print "govleak", s, tod; next
  }
')"

samples="$(printf '%s\n' "$records" | awk '$1=="fps" { print $2, $3 }')"
frames="$(printf '%s\n' "$records" | awk '$1=="frame" { print $2, $3, $4, $5, $6, $7 }')"
gpu="$(printf '%s\n' "$records" | awk '$1=="gpu" { print $2, $3, $4, $5, $6, $7, $8 }')"
gpuempty="$(printf '%s\n' "$records" | awk '$1=="gpuempty" { print $2 }')"
govern="$(printf '%s\n' "$records" | awk '$1=="govern" { print $2, $3, $4, $5, $6, $7 }')"
govleak="$(printf '%s\n' "$records" | awk '$1=="govleak" { print $2, $3 }')"

if [[ -z "$samples" && -z "$frames" && -z "$gpu" && -z "$gpuempty" ]]; then
  echo "no [fpsprobe]/[frameprobe]/[gpuprobe] samples found. Is fpsprobe on (-Drunelite.fpsprobe / 117HD test mode) and the scene rendering?" >&2
  exit 2
fi

# CSV (every kind, tagged by `kind`; column meanings per kind are in the header comment).
if [[ -n "$OUT" ]]; then
  {
    echo "label,kind,idx,a,b,c,d,e,gs,f,g"
    printf '%s\n' "$records" | awk -v L="$LABEL" '
      function v(x) { return x == "-" ? "" : x }
      $1=="fps"      { print L ",fps," nf++ "," $2 ",,,,," $3 ",," }
      $1=="frame"    { print L ",frame," nr++ "," $2 "," $3 "," $4 "," $5 "," $6 "," $7 ",," }
      $1=="gpu"      { print L ",gpu," ng++ "," $2 "," $4 "," $3 ",," $5 "," v($8) "," v($6) "," v($7) }
      $1=="gpuempty" { print L ",gpuempty," ne++ ",,,,,," v($2) ",," }
      $1=="govern"   { print L ",govern," nv++ "," $2 "," $3 "," $4 "," $5 "," v($6) "," v($7) ",," }'
    printf '%s\n' "$thermal_rows" | awk -v L="$LABEL" '
      function v(x) { return x == "n/a" ? "" : x }
      NF==3 { print L ",thermal," n++ "," $1 "," v($2) "," v($3) ",,,,," }'
  } > "$OUT"
  echo ">> wrote $(($(wc -l < "$OUT" | tr -d ' ') - 1)) rows to $OUT" >&2
fi

# fps stats over LOGGED_IN samples only.
if [[ -n "$samples" ]]; then
  printf '%s\n' "$samples" | awk -v label="$LABEL" '
    $2=="LOGGED_IN" { v[n++]=$1; sum+=$1 }
    END {
      if (n==0) { print "fps:   no LOGGED_IN samples (only login-screen frames captured)"; exit }
      for (i=0;i<n;i++) for (j=i+1;j<n;j++) if (v[j]<v[i]) { t=v[i]; v[i]=v[j]; v[j]=t }
      med = (n%2) ? v[int(n/2)] : (v[n/2-1]+v[n/2])/2
      printf "%-10s  fps    n=%-3d  mean=%.1f  median=%.1f  min=%d  max=%d\n", label, n, sum/n, med, v[0], v[n-1]
    }'
fi

# frame-time stats over LOGGED_IN windows: frame-weighted avg, min-of-min, max-of-max, mean per-sec p95 (p95~).
if [[ -n "$frames" ]]; then
  printf '%s\n' "$frames" | awk -v label="$LABEL" '
    $6=="LOGGED_IN" {
      w++; frames+=$1; wavg+=$1*$3; p95sum+=$4;
      if (mn==0 || $2<mn) mn=$2;
      if ($5>mx) mx=$5;
    }
    END {
      if (w==0) { print "frame: no LOGGED_IN windows captured"; exit }
      printf "%-10s  frame  windows=%-3d frames=%-4d  min=%.2f  avg=%.2f  p95~=%.2f  max=%.2f  (ms)\n", \
        label, w, frames, mn, wavg/frames, p95sum/w, mx
    }'
fi

# GPU-time stats over LOGGED_IN windows (frame-weighted avg, min-of-min, max-of-max, summed disjoint/hitch). Only
# present when debug.routec.gputime=1. Windows in another state, or untagged, are counted so none vanish silently.
if [[ -n "$gpu" || -n "$gpuempty" ]]; then
  { printf '%s\n' "$gpu" | awk 'NF { print "w", $0 }'; printf '%s\n' "$gpuempty" | awk 'NF { print "e", $1 }'; } \
  | awk -v label="$LABEL" '
    $1=="e" { if ($2=="LOGGED_IN") empty++; next }
    $8=="-" { untagged++; next }
    $8!="LOGGED_IN" { other++; next }
    {
      w++; frames+=$2; wavg+=$2*$3; if (mn==0 || $4<mn) mn=$4; if ($5>mx) mx=$5;
      if ($6=="-") nodj=1; else dj+=$6;
      if ($7=="-") nohi=1; else hi+=$7;
    }
    END {
      skipped = (untagged || other) ? sprintf("  (excluded: %d untagged, %d other-state)", untagged, other) : ""
      if (w==0) { printf "gpu:   no LOGGED_IN windows with GPU-time (%d empty)%s\n", empty, skipped; exit }
      printf "%-10s  gpu    windows=%-3d frames=%-4d  min=%.2f  avg=%.2f  max=%.2f  (ms GPU)  disjoint=%s  hitch=%s  empty=%d%s\n", \
        label, w, frames, mn, wavg/frames, mx, nodj ? "n/a" : dj, nohi ? "n/a" : hi, empty, skipped
    }'
fi

# Per-second thermal over the adb window.
if [[ -n "$thermal_rows" ]]; then
  printf '%s\n' "$thermal_rows" | awk -v label="$LABEL" '
    NF==3 {
      n++
      if ($2 != "n/a") { h++; if (h==1 || $2<hmn) hmn=$2; if (h==1 || $2>hmx) hmx=$2 }
      if ($3 != "n/a") { s++; if (s==1) s0=$3; s1=$3; if ($3>smx) smx=$3 }
    }
    END {
      if (n==0) exit
      printf "%-10s  thermal samples=%-3d  hottest=%s..%sC  status=%s->%s (max %s)\n", label, n, \
        h ? sprintf("%.1f", hmn) : "n/a", h ? sprintf("%.1f", hmx) : "n/a", s ? s0 : "n/a", s ? s1 : "n/a", s ? smx : "n/a"
    }'
fi

# ThermalGovernor. The arm-time apply logs prev=-1 and sets the scale it already had, so it isn't a change.
if [[ -n "$govern" ]]; then
  printf '%s\n' "$govern" | awk -v label="$LABEL" '
    NF==6 {
      last=$3; base=$4
      if (mn=="" || $3<mn) mn=$3
      if ($2>0 && $2!=$3) { ch++; moves = moves sprintf(" %s->%s@%s", $2, $3, $5) }
    }
    END {
      printf "%-10s  govern changes=%d  scale=%s  minScale=%s  baseline=%s\n", label, ch, last, mn, base
      if (ch) printf "!! ThermalGovernor changed sceneResolutionScale during the capture (%s): windows before and after are different workloads, not one A/B arm\n", substr(moves, 2)
    }'
fi
if [[ -n "$govleak" ]]; then
  printf '%s\n' "$govleak" | awk '{ printf "!! ThermalGovernor restored a leaked throttle (sceneResolutionScale=%s at %s)\n", $1, $2 }'
fi
