#!/usr/bin/env bash
# run-sim.sh — build RuneMobile for the iOS Simulator, install it and launch it. No phone and no StikDebug: the
# simulator build retags the bundled JRE for the simulator (app/scripts/resign-jre.sh) and services HotSpot's JIT
# breakpoint in-process (JVMLauncher.m, simJitArm).
#
#   ios/dev-tools/run-sim.sh                 # build Debug, install, launch and press Play
#   ios/dev-tools/run-sim.sh --jars          # rebuild + stage the jars first (after a Java change)
#   ios/dev-tools/run-sim.sh --no-build      # reinstall + relaunch the last simulator build
#   ios/dev-tools/run-sim.sh --manual        # launch to the Play cover and wait for a click
#   RENDERER=gpu ios/dev-tools/run-sim.sh    # cpu (default) | gpu | 117hd, for this launch only
#   SIM_NAME="iPhone 17 Pro Max" (default) or SIM_UDID=<udid> picks the simulator.
#
# What the simulator does NOT tell you: performance (Mac CPU, no memory cap, no thermals), the device JIT path
# (StikDebug), GPU driver quirks of the phone (GPU modes draw with Apple's software renderer), real multi-touch,
# and background/PiP behaviour. Check those on the phone. With no memory cap, os_proc_available_memory() is 0
# here, so the residency log's "0MB free" lines mean nothing.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
SIM_NAME="${SIM_NAME:-iPhone 17 Pro Max}"
RENDERER="${RENDERER:-cpu}"
case "$RENDERER" in cpu|gpu|117hd) ;; *) echo "RENDERER must be cpu, gpu or 117hd (got '$RENDERER')" >&2; exit 1 ;; esac
BUNDLE_ID=dev.audaro.runemobile
DERIVED="$REPO/build/dd-sim"
APP="$DERIVED/Build/Products/Debug-iphonesimulator/RuneMobile.app"

BUILD=yes
JARS=no
AUTOPLAY=YES
for a in "$@"; do
  case "$a" in
    --jars)     JARS=yes ;;
    --no-build) BUILD=no ;;
    --manual)   AUTOPLAY=NO ;;
    *) echo "unknown argument: $a" >&2; exit 1 ;;
  esac
done

log() { printf '\n\033[1;35m>> %s\033[0m\n' "$*"; }
cd "$REPO"

# 1) Pick the simulator: an explicit UDID, else the first available device with this exact name.
if [ -z "${SIM_UDID:-}" ]; then
  SIM_UDID="$(xcrun simctl list devices available -j | /usr/bin/python3 -c '
import json, sys
name = sys.argv[1]
for devs in json.load(sys.stdin)["devices"].values():
    for d in devs:
        if d["name"] == name:
            print(d["udid"])
            sys.exit(0)
' "$SIM_NAME")" || true
fi
[ -n "${SIM_UDID:-}" ] || { echo "!! no available simulator named '$SIM_NAME' (set SIM_NAME or SIM_UDID)" >&2; exit 1; }
log "Simulator $SIM_NAME ($SIM_UDID)"
xcrun simctl bootstatus "$SIM_UDID" -b >/dev/null
open -a Simulator --args -CurrentDeviceUDID "$SIM_UDID"

# 2) Jars (only on request: the staged ones are reused otherwise). Same steps as deploy-remote.sh's local build.
if [ "$JARS" = yes ]; then
  log "Jar build + staging"
  JAVA_HOME="$JAVA_HOME" ./gradlew :client:shadowJar
  ( cd ios/dev-tools/gpu-routec && JAVA_HOME="$JAVA_HOME" ./stage-ios-gpu-jar.sh )
  ( cd ios/dev-tools/gpu-zone && JAVA_HOME="$JAVA_HOME" ./stage-ios-zone-jar.sh )
  ( cd ios/app && JAVA_HOME="$JAVA_HOME" ./build-probes.sh )
fi

# 3) Build for the simulator. No provisioning: simulator builds sign ad-hoc.
if [ "$BUILD" = yes ]; then
  # Local signing config (gitignored): exports DEVELOPMENT_TEAM for xcodegen's ${DEVELOPMENT_TEAM} expansion.
  # shellcheck disable=SC1091
  [ -f ios/dev-tools/signing.env ] && . ios/dev-tools/signing.env
  log "xcodegen generate"
  ( cd ios/app && xcodegen generate )
  log "xcodebuild (simulator, Debug)"
  xcodebuild -project ios/app/RuneMobile.xcodeproj -scheme RuneMobile -configuration Debug \
    -destination "id=$SIM_UDID" -derivedDataPath "$DERIVED" build -quiet
fi
[ -d "$APP" ] || { echo "!! no simulator build at $APP (run without --no-build)" >&2; exit 1; }

# 4) Install + launch. Launch arguments land in NSUserDefaults' argument domain, which wins over the app's own
#    saved values for this launch only: rendererPref picks the renderer, RMAutoPlay presses Play.
log "Install + launch (renderer $RENDERER, autoplay $AUTOPLAY)"
xcrun simctl install "$SIM_UDID" "$APP"
# RuneLite's audio startup enumerates input devices, which raises the first-run microphone prompt over the game.
# Answer it the way a phone that has been through first run already has, so an unattended run's screenshots show
# the game. To see the prompt again: xcrun simctl privacy <udid> reset microphone dev.audaro.runemobile
xcrun simctl privacy "$SIM_UDID" grant microphone "$BUNDLE_ID"
xcrun simctl launch --terminate-running-process "$SIM_UDID" "$BUNDLE_ID" \
  -rendererPref "$RENDERER" -RMAutoPlay "$AUTOPLAY"

DOCS="$(xcrun simctl get_app_container "$SIM_UDID" "$BUNDLE_ID" data)/Documents"
log "Running. Logs: $DOCS/jvm-stdio.log and $DOCS/hello-shell.log"
echo "   A fresh install downloads the game cache first (~40 MB): expect ~30 s to the login screen, ~7 s after that."
echo "   Screenshot: xcrun simctl io $SIM_UDID screenshot <file.png>"
