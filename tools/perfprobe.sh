#!/usr/bin/env bash
# Arm or disarm the perf probe on a phone without a rebuild. The app reads the switch once, when the JVM starts, so a
# change takes effect at the next launch: force-stop the app and start it again.
#
# Usage:
#   tools/perfprobe.sh android on|off|status [-s SERIAL]
#   tools/perfprobe.sh ios     on|off|status [-d UDID] [-b BUNDLE_ID]
#
# Armed, the VM runs with -Drunelite.fpsprobe (headless.FrameStats: [fpsprobe] [frameprobe] [framebins] [framehist])
# and -Xlog:gc,safepoint to stdout, and the present path logs [presentprobe] (the GPU direct present on both ports,
# and Android's CPU blit).
#   android  A `perfprobe` file in the app's external files dir. A debug build is armed without it. A release build
#            forwards the probe's lines to logcat and drops all other VM output (A8). Measure with
#            android/dev-tools/gpu/fps-measure.sh.
#   ios      Documents/perfprobe, in Debug and Release alike. Debug keeps the lines in jvm-stdio.log, Release in
#            perf-probe.log (the probe's lines only); memwatch and the [coldstart] stamps land in the same log. Pull
#            it with the harness's ios_pull_logs, which also summarises it with fps-measure.sh. devicectl can't
#            delete a file in an app container, so `off` overwrites the marker with "off" and the app reads that as
#            disarmed. The phone must be unlocked; no JIT attach needed.
set -euo pipefail

usage() { sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

[[ $# -ge 2 ]] || usage 1
PORT="$1" ACTION="$2"
shift 2
SERIAL="" UDID="" BUNDLE="dev.audaro.runemobile"
while getopts "s:d:b:h" opt; do
  case "$opt" in
    s) SERIAL="$OPTARG" ;;
    d) UDID="$OPTARG" ;;
    b) BUNDLE="$OPTARG" ;;
    h) usage 0 ;;
    *) usage 1 ;;
  esac
done
case "$ACTION" in on|off|status) ;; *) usage 1 ;; esac

TMP="$(mktemp -d "${TMPDIR:-/tmp}/perfprobe.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# ---- android --------------------------------------------------------------------------------------------------
# Every adb call spells the serial as ${SERIAL:+-s "$SERIAL"}: macOS /bin/bash 3.2 treats an empty "${arr[@]}" as
# unbound under set -u.
android() {
  command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
  local dir="/sdcard/Android/data/$BUNDLE/files" marker
  marker="$dir/perfprobe"
  case "$ACTION" in
    on) adb ${SERIAL:+-s "$SERIAL"} shell "mkdir -p '$dir' && touch '$marker'" ;;
    off) adb ${SERIAL:+-s "$SERIAL"} shell "rm -f '$marker'" ;;
  esac
  local state="off"
  if adb ${SERIAL:+-s "$SERIAL"} shell "test -f '$marker'" 2>/dev/null; then state="ARMED (marker)"; fi
  if adb ${SERIAL:+-s "$SERIAL"} shell dumpsys package "$BUNDLE" 2>/dev/null | grep -q 'flags=\[.*DEBUGGABLE'; then
    state="ARMED (debug build; the marker is ${state%% *})"
  fi
  echo "android $BUNDLE: perf probe $state"
  # The running process logs what it booted with, and the splash line carries the cold start.
  adb ${SERIAL:+-s "$SERIAL"} logcat -d 2>/dev/null | grep -E 'perfprobe: (ARMED|off)|splash dismissed .*sinceStartMs' \
    | tail -2 | sed 's/^/  last launch: /' || true
  [[ "$ACTION" == status ]] || echo "  takes effect at the next launch: adb shell am force-stop $BUNDLE, then start it"
}

# ---- ios ------------------------------------------------------------------------------------------------------
ios_device() {
  [[ -n "$UDID" ]] && return
  xcrun devicectl list devices --json-output "$TMP/devices.json" >/dev/null 2>&1 \
    || { echo "devicectl list devices failed (Xcode command-line tools?)" >&2; exit 1; }
  UDID="$(/usr/bin/python3 -c '
import json, sys
devs = json.load(open(sys.argv[1])).get("result", {}).get("devices", [])
ids = [d["identifier"] for d in devs if d.get("hardwareProperties", {}).get("platform") == "iOS"
       and d.get("connectionProperties", {}).get("pairingState") == "paired"]
print(ids[0] if len(ids) == 1 else "")
' "$TMP/devices.json")"
  [[ -n "$UDID" ]] || { echo "need exactly one paired iPhone; pass -d UDID" >&2; exit 1; }
}

ios_copy() { # ios_copy to|from SOURCE DESTINATION
  xcrun devicectl device copy "$1" --device "$UDID" --domain-type appDataContainer --domain-identifier "$BUNDLE" \
    --source "$2" --destination "$3" >"$TMP/devicectl.log" 2>&1
}

ios() {
  command -v xcrun >/dev/null || { echo "xcrun not found" >&2; exit 1; }
  ios_device
  case "$ACTION" in
    on) : >"$TMP/perfprobe" ;;
    off) echo off >"$TMP/perfprobe" ;;
  esac
  if [[ "$ACTION" != status ]] && ! ios_copy to "$TMP/perfprobe" Documents/perfprobe; then
    cat "$TMP/devicectl.log" >&2
    echo "could not write Documents/perfprobe in $BUNDLE on $UDID (is the phone unlocked, the app installed?)" >&2
    exit 1
  fi
  local state="off (no marker)"
  if ios_copy from Documents/perfprobe "$TMP/readback"; then
    if [[ "$(tr -d '[:space:]' <"$TMP/readback")" == off ]]; then state='off (marker says "off")'; else state="ARMED"; fi
  fi
  echo "ios $BUNDLE on $UDID: perf probe $state"
  [[ "$ACTION" == status ]] || echo "  takes effect at the next launch (swipe the app away, start it, attach StikDebug)"
}

case "$PORT" in
  android) android ;;
  ios) ios ;;
  *) usage 1 ;;
esac
