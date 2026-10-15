#!/usr/bin/env bash
# Refuse to build a bundle carrying the PRISTINE desktop shaded jar.
#
# jvm-resources/client-shaded.jar must be the iOS variant produced by
# dev-tools/gpu-routec/stage-ios-gpu-jar.sh: org/lwjgl + net/runelite/rlawt stripped and replaced by the
# native-backed facade. The desktop jar is a drop-in by name and size, so copying it here "works" --
# the app builds, boots, logs in and renders, and only the GPU renderer is dead:
#
#   * no org/lwjgl/opengl/GLALL  -> RouteC_registerGLNatives returns JNI_ERR, the GL bridge never registers
#   * librlawt still in the jar  -> stock GpuPlugin extracts the macOS dylib and dlopen fails with
#                                   "incompatible platform (have 'macOS', need 'iOS')", 30 times
#
# That is what the 2026-10-13 bump to 1.13.2-SNAPSHOT did: the jar was refreshed with a plain copy, the
# staging step was skipped, and since ios/.gitignore ignores client-*shaded.jar there was no diff to notice.
# The failure then reads as a renderer crash rather than a missing build step, which is why this is a gate.
set -euo pipefail

JAR="${SRCROOT:-$(cd "$(dirname "$0")/.." && pwd)}/jvm-resources/client-shaded.jar"
[ -f "$JAR" ] || { echo "error: missing $JAR -- run dev-tools/gpu-routec/stage-ios-gpu-jar.sh" >&2; exit 1; }

LIST=$(unzip -Z1 "$JAR")
GLALL=$(printf '%s\n' "$LIST" | grep -c '^org/lwjgl/opengl/GLALL\.class$' || true)
RLAWT=$(printf '%s\n' "$LIST" | grep -c 'librlawt' || true)

if [ "$GLALL" -ne 1 ] || [ "$RLAWT" -ne 0 ]; then
    echo "error: jvm-resources/client-shaded.jar is the pristine desktop jar, not the iOS variant" >&2
    echo "error:   org/lwjgl/opengl/GLALL.class = $GLALL (want 1), librlawt entries = $RLAWT (want 0)" >&2
    echo "error: the GPU renderer cannot start against this jar. Stage it:" >&2
    echo "error:   ( cd ios/dev-tools/gpu-routec && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./stage-ios-gpu-jar.sh )" >&2
    exit 1
fi
