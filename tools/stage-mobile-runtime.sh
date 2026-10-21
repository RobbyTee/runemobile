#!/usr/bin/env bash
#
# Stages the jar both mobile ports share (probes.jar) and writes Android's runtime.tag.
#
# Both ports start from the same two build outputs:
#   - client-<version>-shaded.jar  : real RuneLite + all deps  (gradle :client:shadowJar)
#   - probes.jar                   : headless boot + CACIO (folded in) + W1 probes
#                                    (ios/app/build-probes.sh; needs the shaded jar present)
#
# Each port then rebuilds the shaded jar for its own GL stack, so only probes.jar is the same bytes on both:
#   - iOS bundles client-shaded.jar (the GL-facade variant from ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh),
#     client-rlhd.jar, probes.jar and routec-l1.jar.
#   - Android bundles client-<version>-gpu.jar and client-<version>-rlhd.jar (android/dev-tools/gpu/stage-*.sh),
#     probes.jar and runtime.tag, and not the shaded jar (SLM-JAR-01).
# runtime.tag folds every one of those SHAs, so on Android this runs AFTER the gpu/rlhd stages. On iOS it copies
# the PRISTINE shaded jar, which stage-ios-gpu-jar.sh must then replace (ios/app/scripts/check-staged-jar.sh
# fails the Xcode build until it does). PORTS.md gives the full order for each port.
#
# Usage:
#   tools/stage-mobile-runtime.sh                 # build shaded + probes, stage both ports
#   tools/stage-mobile-runtime.sh --no-build      # re-stage already-built jars (skip gradle/probes)
#   tools/stage-mobile-runtime.sh --check         # no build: probes.jar current on both, iOS shaded is the GPU variant
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
    -h|--help)       sed -n '2,26p' "$0" ; exit 0 ;;
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
  # Only probes.jar is the same bytes on both ports. Android omits the shaded jar (SLM-JAR-01), and iOS replaces
  # it with the GL-facade variant, whose SHA never matches build/libs -- so iOS checks that variant's contents.
  for pair in "iOS:$IOS_DEST" "Android:$ANDROID_DEST"; do
    port="${pair%%:*}" ; dir="${pair#*:}" ; path="$dir/probes.jar"
    if [ ! -f "$path" ]; then echo "MISSING $port $path"; fail=1
    elif [ "$(sha "$path")" != "$PROBES_SHA" ]; then echo "STALE   $port $path"; fail=1
    else echo "ok      $port $path"; fi
  done
  if SRCROOT="$ROOT/ios/app" ios/app/scripts/check-staged-jar.sh; then
    echo "ok      iOS $IOS_DEST/client-shaded.jar (the GPU variant)"
  else
    echo "BAD     iOS $IOS_DEST/client-shaded.jar (see the error above)"; fail=1
  fi
  [ "$fail" = 0 ] && echo "== probes.jar current on both ports; iOS client-shaded.jar is the GPU variant ==" || echo "== drift detected; re-stage in the order PORTS.md gives ==" >&2
  exit "$fail"
fi

# --- stage --------------------------------------------------------------------
copy_jar() {            # $1=src  $2=dest path — skip when src and dest are the same file
  [ "$1" -ef "$2" ] || cp -f "$1" "$2"
}
stage_one() {           # $1=label  $2=dest dir
  local label="$1" dest="$2"
  mkdir -p "$dest"
  # SLM-JAR-01 (device-verified, all 3 renderers boot): shaded.jar is NOT on the Android boot classpath — the
  # [gpu : rlhd : probes] closure is complete — so on Android it only bloats the APK (~26 MB). iOS still boots
  # shaded, so stage it there; for Android, skip it (and remove any previously-staged copy so re-staging is
  # idempotent). The runtime.tag still folds SHADED_SHA from build/libs below, so a shaded change still
  # invalidates Android installs. NB the bridge-census dev tool reads shaded from build/libs, not this dir.
  if [ "$label" = "Android" ]; then
    rm -f "$dest/$SHADED_NAME"
  else
    # iOS stages under the version-AGNOSTIC bundle name (project.yml/JVMLauncher.m reference it as a fixed
    # string). NB this is the PRISTINE jar — the gpu deploy flows overwrite it with the facade variant.
    copy_jar "$SHADED_SRC" "$dest/client-shaded.jar"
  fi
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
# Guard the documented stage ORDER (the gpu/rlhd stages must run BEFORE this one). The Android
# client boots with useGpuJar=true, so an APK staged without the gpu jar is un-bootable; fail loudly
# here rather than fold an incomplete runtime.tag and ship a broken bundle. (iOS-only / --check skip.)
if [ "$STAGE_ANDROID" = 1 ] && [ "$CHECK_ONLY" = 0 ]; then
  if ! ls "$ANDROID_DEST"/client-*-gpu.jar >/dev/null 2>&1; then
    echo "!! no client-*-gpu.jar in $ANDROID_DEST — run the GPU/117HD stages FIRST:" >&2
    echo "     android/dev-tools/gpu/stage-android-gpu-jar.sh   # -> client-*-gpu.jar" >&2
    echo "     android/dev-tools/gpu/stage-rlhd-zone-jar.sh     # -> client-*-rlhd.jar" >&2
    echo "   then re-run this stage so their SHAs fold into runtime.tag (un-bootable APK otherwise)." >&2
    exit 1
  fi
fi
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
echo "== done (content-tag $STAMP_TAG). Each port's own jar stages are listed in PORTS.md."
if [ "$STAGE_IOS" = 1 ]; then
  echo "   iOS: client-shaded.jar is now the PRISTINE jar -- run ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh next."
fi
echo "   Android re-unpacks automatically via jvm/runtime.tag; bump BASE in AssetInstaller.kt"
echo "   only when the embedded JRE / native libs change (jars are tracked by the tag)."
