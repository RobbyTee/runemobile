#!/usr/bin/env bash
#
# Single front door for the SHARED Java runtime that both mobile ports embed.
#
# The runtime is two jars, identical on iOS and Android:
#   - client-<version>-shaded.jar  : real RuneLite + all deps  (gradle :client:shadowJar)
#   - probes.jar                   : headless boot + CACIO (folded in) + W1 probes
#                                    (ios/app/build-probes.sh; needs the shaded jar present)
#
# Without this, each port copies those jars by hand (ios/SETUP-NEW-MAC.md step 4 for iOS,
# unscripted for Android) and the two drift silently. Run this and both ports build from the
# same freshly-staged bytes, with a SHA/version manifest proving they match.
#
# Usage:
#   tools/stage-mobile-runtime.sh                 # build everything, stage both ports
#   tools/stage-mobile-runtime.sh --no-build      # re-stage already-built jars (skip gradle/probes)
#   tools/stage-mobile-runtime.sh --check         # verify staged jars match across ports, no build
#   tools/stage-mobile-runtime.sh --ios-only      # stage only iOS
#   tools/stage-mobile-runtime.sh --android-only  # stage only Android
#
# Env: JAVA_HOME (defaults to /opt/homebrew/opt/openjdk@21)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

# --- args ---------------------------------------------------------------------
DO_BUILD=1 ; STAGE_IOS=1 ; STAGE_ANDROID=1 ; CHECK_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --no-build)      DO_BUILD=0 ;;
    --check)         DO_BUILD=0 ; CHECK_ONLY=1 ;;
    --ios-only)      STAGE_ANDROID=0 ;;
    --android-only)  STAGE_IOS=0 ;;
    -h|--help)       sed -n '2,25p' "$0" ; exit 0 ;;
    *) echo "unknown arg: $arg (try --help)" >&2 ; exit 2 ;;
  esac
done

# --- paths --------------------------------------------------------------------
VERSION="$(sed -n 's/^project\.build\.version=//p' gradle.properties)"
[ -n "$VERSION" ] || { echo "could not read project.build.version from gradle.properties" >&2; exit 1; }

SHADED_NAME="client-${VERSION}-shaded.jar"
SHADED_SRC="runelite-client/build/libs/${SHADED_NAME}"
PROBES_SRC="ios/app/jvm-resources/probes.jar"     # build-probes.sh writes it here

IOS_DEST="ios/app/jvm-resources"
ANDROID_DEST="android/app/src/main/assets/jvm"

MANIFEST="tools/last-stage.manifest"              # gitignored record of the most recent stage

sha() { shasum -a 256 "$1" | awk '{print $1}'; }

# --- build --------------------------------------------------------------------
if [ "$DO_BUILD" = 1 ]; then
  echo "== build shaded client jar (:client:shadowJar, JAVA_HOME=$JAVA_HOME) =="
  ./gradlew :client:shadowJar
  [ -f "$SHADED_SRC" ] || { echo "expected $SHADED_SRC after shadowJar" >&2; exit 1; }

  echo "== build probes.jar (ios/app/build-probes.sh, folds in CACIO; picks up the shaded jar) =="
  ( cd ios/app && ./build-probes.sh )
  [ -f "$PROBES_SRC" ] || { echo "expected $PROBES_SRC after build-probes.sh" >&2; exit 1; }
fi

# In --no-build / --check we still need the source jars to exist.
[ -f "$SHADED_SRC" ] || { echo "missing $SHADED_SRC — run without --no-build first" >&2; exit 1; }
[ -f "$PROBES_SRC" ] || { echo "missing $PROBES_SRC — run without --no-build first" >&2; exit 1; }

SHADED_SHA="$(sha "$SHADED_SRC")"
PROBES_SHA="$(sha "$PROBES_SRC")"

# --- check-only: verify what's already staged in each port matches the source -
if [ "$CHECK_ONLY" = 1 ]; then
  fail=0
  for pair in "iOS:$IOS_DEST" "Android:$ANDROID_DEST"; do
    port="${pair%%:*}" ; dir="${pair#*:}"
    for jar in "$SHADED_NAME:$SHADED_SHA" "probes.jar:$PROBES_SHA"; do
      name="${jar%%:*}" ; want="${jar#*:}" ; path="$dir/$name"
      if [ ! -f "$path" ]; then echo "MISSING $port $path"; fail=1
      elif [ "$(sha "$path")" != "$want" ]; then echo "STALE   $port $path"; fail=1
      else echo "ok      $port $path"; fi
    done
  done
  [ "$fail" = 0 ] && echo "== all staged jars match the freshly-built source ==" || echo "== drift detected; re-run without --check to re-stage ==" >&2
  exit "$fail"
fi

# --- stage --------------------------------------------------------------------
copy_jar() {            # $1=src  $2=dest path — skip when src and dest are the same file
  [ "$1" -ef "$2" ] || cp -f "$1" "$2"
}
stage_one() {           # $1=label  $2=dest dir
  local label="$1" dest="$2"
  mkdir -p "$dest"
  copy_jar "$SHADED_SRC" "$dest/$SHADED_NAME"
  copy_jar "$PROBES_SRC" "$dest/probes.jar"
  echo "  staged -> $dest ($label)"
}
echo "== stage shared runtime (version $VERSION) =="
[ "$STAGE_IOS"     = 1 ] && stage_one "iOS"     "$IOS_DEST"
[ "$STAGE_ANDROID" = 1 ] && stage_one "Android" "$ANDROID_DEST"

# --- manifest -----------------------------------------------------------------
# Short content tag = first 12 hex of sha256(shaded+probes+gpu-variants). Android's AssetInstaller
# reads this from the bundle (jvm/runtime.tag) and re-unpacks when it changes, so jar
# updates can't go stale without a hand-typed version bump. (iOS needs no tag — Xcode
# re-bundles jvm-resources/ every build.) The GPU-variant jars (staged separately by
# android/dev-tools/gpu/stage-*.sh) are folded in when present: bootJvm's copy-from-assets
# fallback only fires when the device file is ABSENT, so without tag coverage an install
# would keep stale gpu/rlhd jars forever.
GPU_SHA=""
for j in "$ANDROID_DEST"/client-*-gpu.jar "$ANDROID_DEST"/client-*-rlhd.jar; do
  [ -f "$j" ] && GPU_SHA="${GPU_SHA}$(sha "$j")"
done
STAMP_TAG="$(printf '%s%s%s' "$SHADED_SHA" "$PROBES_SHA" "$GPU_SHA" | shasum -a 256 | cut -c1-12)"
if [ "$STAGE_ANDROID" = 1 ]; then
  printf '%s\n' "$STAMP_TAG" > "$ANDROID_DEST/runtime.tag"
  echo "  wrote $ANDROID_DEST/runtime.tag ($STAMP_TAG)"
fi
{
  echo "# mobile runtime stage manifest — DO NOT COMMIT (generated)"
  echo "version       $VERSION"
  echo "content-tag   $STAMP_TAG"
  echo "$SHADED_SHA  $SHADED_NAME"
  echo "$PROBES_SHA  probes.jar"
  echo "ios-dest      $IOS_DEST"
  echo "android-dest  $ANDROID_DEST"
} > "$MANIFEST"

echo
cat "$MANIFEST"
echo
echo "== done. Both ports now embed identical runtime jars (content-tag $STAMP_TAG)."
echo "   Android re-unpacks automatically via jvm/runtime.tag; bump BASE in AssetInstaller.kt"
echo "   only when the embedded JRE / native libs change (jars are tracked by the tag)."
