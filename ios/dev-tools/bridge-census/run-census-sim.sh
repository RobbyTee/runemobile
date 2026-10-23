#!/usr/bin/env bash
# run-census-sim.sh — the iOS bridge census. Checks, in the iOS Simulator, that every class, method and field the
# app's native code looks up through JNI resolves in the jars the app ships, inside the app's own VM.
#
#   ios/dev-tools/bridge-census/run-census-sim.sh             # build, install, run the census, adjudicate
#   ios/dev-tools/bridge-census/run-census-sim.sh --jars      # restage the jars first (after a Java change)
#   ios/dev-tools/bridge-census/run-census-sim.sh --no-build  # reuse the last simulator build
#
# The list comes from the native source (extract-lookups.py), not from a hand-kept contract, so a lookup added to
# JVMLauncher.m is checked from the next run on. The app runs it under -RMBridgeCensus (JVMLauncher.m,
# simBridgeCensusRun) once the client has drawn a frame. It runs the CPU renderer: every renderer has the same
# classpath, so one run covers them all. Needs what run-sim.sh needs (the iOS JRE, staged jars). Exits 0 exactly
# when GREEN; output and list land in build/bridge-census/.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
BUNDLE_ID=dev.audaro.runemobile
OUT="$REPO/build/bridge-census"
LIST_NAME=bridge-census.tsv
RESULT_NAME=bridge-census-result.txt
# The app waits up to 300 s for a first frame; a fresh install also downloads the game cache first.
TIMEOUT="${CENSUS_TIMEOUT:-420}"
for a in "$@"; do
  case "$a" in
    --jars | --no-build) ;;
    *) echo "unknown argument: $a" >&2; exit 1 ;;
  esac
done

red() {
  echo "!! $*" >&2
  echo "IOS_BRIDGE_CENSUS_RED"
  echo "VERDICT: IOS_BRIDGE_CENSUS_RED"
  exit 1
}

mkdir -p "$OUT"
rm -f "$OUT/result.txt"
/usr/bin/python3 "$HERE/extract-lookups.py" > "$OUT/lookups.tsv" || red "extract-lookups.py failed"

SIM_UDID="$("$REPO/ios/dev-tools/run-sim.sh" --print-udid)"
export SIM_UDID
RENDERER=cpu "$REPO/ios/dev-tools/run-sim.sh" --no-launch "$@"

DOCS="$(xcrun simctl get_app_container "$SIM_UDID" "$BUNDLE_ID" data)/Documents"
mkdir -p "$DOCS"
cp "$OUT/lookups.tsv" "$DOCS/$LIST_NAME"
rm -f "$DOCS/$RESULT_NAME"
printf '\n\033[1;35m>> Census: %s lookups, CPU renderer\033[0m\n' "$(wc -l < "$OUT/lookups.tsv" | tr -d ' ')"
# -RMSkipLogin: the lookups need a booted client, not a logged-in one, so a simulator with a session doesn't log in.
xcrun simctl launch --terminate-running-process "$SIM_UDID" "$BUNDLE_ID" \
  -rendererPref cpu -RMAutoPlay YES -RMSkipLogin YES -RMBridgeCensus "$LIST_NAME"

start=$SECONDS
while [ ! -f "$DOCS/$RESULT_NAME" ]; do
  [ $((SECONDS - start)) -lt "$TIMEOUT" ] || red "no census result after ${TIMEOUT}s; see $DOCS/jvm-stdio.log"
  if ! xcrun simctl spawn "$SIM_UDID" launchctl list | grep -qE "^[0-9]+[[:space:]].*UIKitApplication:$BUNDLE_ID\\["; then
    tail -20 "$DOCS/jvm-stdio.log" 2>/dev/null >&2 || true
    red "the app exited before the census finished; log: $DOCS/jvm-stdio.log"
  fi
  sleep 2
done
echo "   result after $((SECONDS - start)) s (the app keeps running)"
cp "$DOCS/$RESULT_NAME" "$OUT/result.txt"
exec bash "$HERE/bridge-census-verdict.sh" "$OUT/result.txt" "$OUT/lookups.tsv"
