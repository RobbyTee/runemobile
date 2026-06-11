#!/usr/bin/env bash
# remote-build.sh — runs ON the remote Mac (the one tethered to the iPhone).
#
# Does only the Mac-and-device-only steps: xcodegen -> xcodebuild -> devicectl install.
# The heavy Java compile (shaded jar, gpu jar-surgery, probe jars) is done on the DEV Mac and
# rsync'd in via deploy-remote.sh, so by the time we get here ios/app/jvm-resources/ already holds
# the staged jars. Pass --with-jars to make this box self-contained (build the jars here too).
#
# Required env:
#   DEV          devicectl device UUID of the iPhone as seen by THIS Mac (xcrun devicectl list devices)
# Optional env:
#   JAVA_HOME    default /opt/homebrew/opt/openjdk@21   (only used with --with-jars)
#   MODE         gpu | cpu   (default gpu; selects jar-surgery vs plain copy, only with --with-jars)
#   REPO         repo root   (default: two levels up from this script)
set -euo pipefail

WITH_JARS=0
[ "${1:-}" = "--with-jars" ] && WITH_JARS=1

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
MODE="${MODE:-gpu}"
REPO="${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$REPO"

: "${DEV:?set DEV to the iPhone devicectl UUID (xcrun devicectl list devices)}"

VERSION="$(grep -E '^runeLiteVersion' gradle.properties 2>/dev/null | sed 's/.*=//' | tr -d ' ' || true)"
VERSION="${VERSION:-1.12.29}"

log() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

if [ "$WITH_JARS" = 1 ]; then
  log "Building jars on remote (MODE=$MODE, VERSION=$VERSION)"
  JAVA_HOME="$JAVA_HOME" ./gradlew :client:shadowJar
  if [ "$MODE" = gpu ]; then
    ( cd ios/dev-tools/gpu-routec && JAVA_HOME="$JAVA_HOME" ./stage-ios-gpu-jar.sh )
  else
    cp "runelite-client/build/libs/client-${VERSION}-SNAPSHOT-shaded.jar" ios/app/jvm-resources/
  fi
  ( cd ios/app && JAVA_HOME="$JAVA_HOME" ./build-probes.sh )
  ( cd ios/dev-tools/gpu-routec/l1 && JAVA_HOME="$JAVA_HOME" ./build-l1-probe.sh )
fi

# Sanity: the staged shaded jar must be present (carried by rsync, or built above).
SHADED="ios/app/jvm-resources/client-${VERSION}-SNAPSHOT-shaded.jar"
[ -f "$SHADED" ] || { echo "!! staged shaded jar missing: $SHADED — run deploy with jars, or pass --with-jars" >&2; exit 1; }

log "xcodegen generate"
( cd ios/app && xcodegen generate )

log "xcodebuild (device, allowProvisioningUpdates)"
xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell \
  -destination "id=$DEV" -derivedDataPath build/dd-mui -allowProvisioningUpdates build

log "devicectl install -> $DEV"
xcrun devicectl device install app --device "$DEV" \
  build/dd-mui/Build/Products/Debug-iphoneos/HelloShell.app

log "DONE — app installed on device $DEV"
echo "Next (manual, phone-side): attach StikDebug, then launch the app for per-launch JIT."
