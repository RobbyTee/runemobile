#!/usr/bin/env bash
# Phase B0.1 — stage the iOS-variant 117HD (RLHD) Zone jar. iOS analogue of the base-gpu
# `gpu-routec/stage-ios-gpu-jar.sh` and the Android `gpu/stage-rlhd-zone-jar.sh`.
#
# RLHD is an EXTERNAL hub plugin (vendored, gitignored, at rlhd-src/src/, rev 7dded59, targets RuneLite 1.12.+),
# so unlike the base gpu plugin it is NOT inside the client jar. This script compiles the UNMODIFIED RLHD source
# against [facade/ + a lwjgl-STRIPPED client jar] — i.e. our pure-Java org.lwjgl.{opengl,opencl,system} +
# net.runelite.rlawt.AWTContext facade is the SOLE provider of every LWJGL symbol RLHD references. Stripping real
# lwjgl from the compile classpath is deliberate: a facade symbol RLHD uses but we don't declare becomes a javac
# error HERE, not a NoClassDefFound at runtime. lombok runs as an annotation processor (equivalent to delombok).
# The OpenCL surface is a symbols-only stub (the Zone path never creates a CL context, so no cl* call ever runs);
# it exists purely so the class-load graph (HdPlugin -> UBOCompute -> UniformBuffer -> OpenCLManager, and
# ZoneRenderer -> UniformBuffer) resolves.
#
# The facade methods are declared `native` (device-bound). At B0.1 (this script) we only prove COMPILE-green;
# the native RegisterNatives bindings in RouteCBridge.m are the separate B0.2/B0.3 work. The facade here is the
# spec for that delta — it enumerates every GL/CL/system symbol the iOS bridge must service.
#
# Output: client-<ver>-rlhd.jar = rs117/** classes (facade-backed) + RLHD shader/JSON resources. It carries NO
# org.lwjgl/rlawt — at runtime the facade ships in the base-gpu client-<ver>-shaded.jar staged alongside it.
# Deterministic + md5-gateable: pass an expected class-manifest md5 as $1.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../../.. && pwd)"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
VERSION="$(sed -n 's/^project\.build\.version=//p' "$ROOT/gradle.properties")"
[ -n "$VERSION" ] || { echo "!! could not read project.build.version from gradle.properties" >&2; exit 1; }

LOMBOK="${LOMBOK:-$(find "$HOME/.gradle/caches" -name 'lombok-1.18.*.jar' 2>/dev/null | grep -vE 'sources|javadoc|plugin' | head -1)}"
FACADE="$(pwd)/facade"
RLHD="$(pwd)/rlhd-src"
SRC="${SRC:-$ROOT/runelite-client/build/libs/client-${VERSION}-shaded.jar}"
OUT="${OUT:-$ROOT/ios/app/jvm-resources/client-${VERSION}-rlhd.jar}"
EXPECT_MD5="${1:-}"

[ -f "$SRC" ]    || { echo "!! pristine shaded jar missing: $SRC (run ./gradlew :client:shadowJar)" >&2; exit 1; }
[ -d "$FACADE" ] || { echo "!! facade missing: $FACADE" >&2; exit 1; }
[ -d "$RLHD/src/main/java" ] || { echo "!! rlhd-src/src missing: $RLHD/src (vendored, gitignored — re-fetch per rlhd-src/PROVENANCE.md)" >&2; exit 1; }
[ -n "$LOMBOK" ] && [ -f "$LOMBOK" ] || { echo "!! lombok jar not found in ~/.gradle/caches" >&2; exit 1; }

# Refuse a non-pristine source (already stripped) — guards against feeding an iOS variant back in.
SRC_LWJGL=$(unzip -l "$SRC" | grep -c 'org/lwjgl/.*[.]class' || true)
[ "$SRC_LWJGL" -gt 0 ] || { echo "!! SRC has no org/lwjgl classes — not a pristine shaded jar: $SRC" >&2; exit 1; }

WORK="$(pwd)/stage-zone-work"
rm -rf "$WORK"; mkdir -p "$WORK/facade_out" "$WORK/rlhd_out" "$WORK/cp" "$WORK/jar"

# 1) facade compiles (the GL/CL/system/AWTContext surface RLHD references; methods `native`).
"$JAVAC" -nowarn -d "$WORK/facade_out" $(find "$FACADE" -name '*.java')
FACADE_LWJGL_CLASSES=$(cd "$WORK/facade_out" && find org/lwjgl -name '*.class' | wc -l | tr -d ' ')

# 2) explode the pristine client jar and STRIP real lwjgl + rlawt -> the facade is the sole LWJGL provider on the
#    compile classpath (so a missing facade symbol is a javac error, not a runtime NoClassDefFound).
(cd "$WORK/cp" && unzip -q "$SRC")
rm -rf "$WORK/cp/org/lwjgl" "$WORK/cp/net/runelite/rlawt"

# 3) compile the UNMODIFIED RLHD sources against [facade : stripped-client], lombok as annotation processor.
set +e
"$JAVAC" -nowarn -proc:full -cp "$WORK/facade_out:$WORK/cp:$LOMBOK" -processorpath "$LOMBOK" \
	-d "$WORK/rlhd_out" $(find "$RLHD/src/main/java" -name '*.java') 2>"$WORK/err.txt"
RC=$?
set -e
if [ "$RC" != 0 ]; then echo "!! RLHD recompile FAILED ($(grep -c 'error:' "$WORK/err.txt") errors):" >&2; grep 'error:' "$WORK/err.txt" | head -40 >&2; exit 2; fi

# 4) package: rs117/** classes + ALL RLHD resources (shaders/JSON). NO org.lwjgl/rlawt (facade ships in the gpu
#    jar). Keep the resource tree exactly as RLHD loads it via getResourceAsStream. The shaders ship pristine
#    (#version 330/420); the iOS downporter rewrites them to ES 3.00 at glShaderSource time (same seam as base gpu).
cp -R "$WORK/rlhd_out/." "$WORK/jar/"
[ -d "$RLHD/src/main/resources" ] && cp -R "$RLHD/src/main/resources/." "$WORK/jar/"

# 4b) optional iOS ES-3.0 shader overlay (full int->float ported copies the load-time downport can't safely do).
#     Mirrors the Android overlay mechanism; empty/absent until the Z0 ios-overrides are promoted to a runtime
#     overlay (B0.2+). When present it replaces the pristine rlhd resources, keeping rlhd-src untouched.
OVERLAY="$(pwd)/shader-overlay"
if [ -d "$OVERLAY" ]; then
	cp -R "$OVERLAY/." "$WORK/jar/"
	OVR=$(cd "$OVERLAY" && find . -name '*.glsl' | wc -l | tr -d ' ')
	echo "shader overlay applied : $OVR file(s)"
fi

# 5) asserts.
RLHD_CLASSES=$(cd "$WORK/jar" && { find rs117 -name '*.class' 2>/dev/null || true; } | wc -l | tr -d ' ')
LWJGL_LEAK=$(cd "$WORK/jar" && { find org/lwjgl net/runelite/rlawt -name '*.class' 2>/dev/null || true; } | wc -l | tr -d ' ')
GLSL=$(cd "$WORK/jar" && find . -name '*.glsl' 2>/dev/null | wc -l | tr -d ' ')
JSON=$(cd "$WORK/jar" && find . -name '*.json' 2>/dev/null | wc -l | tr -d ' ')
ZONE=$([ -f "$WORK/jar/rs117/hd/renderer/zone/ZoneRenderer.class" ] && echo 1 || echo 0)
OCL=$([ -f "$WORK/jar/rs117/hd/opengl/compute/OpenCLManager.class" ] && echo 1 || echo 0)

# 6) rezip + class-manifest md5 (deterministic).
NEW="$WORK/staged.jar"
"$JAR" --create --no-manifest --file "$NEW" -C "$WORK/jar" .
STAGED_MD5=$(cd "$WORK/jar" && find . -name '*.class' | sort | xargs cat | { md5 -q 2>/dev/null || md5sum | awk '{print $1}'; })

echo "facade lwjgl classes : $FACADE_LWJGL_CLASSES"
echo "rlhd classes         : $RLHD_CLASSES   (facade-backed; must be >=290)"
echo "lwjgl/rlawt leak     : $LWJGL_LEAK   (must be 0; facade ships in the gpu jar)"
echo "glsl shaders         : $GLSL   (must be >=40)"
echo "loader json          : $JSON   (must be >=6)"
echo "ZoneRenderer.class   : $ZONE   (must be 1)"
echo "OpenCLManager.class  : $OCL   (must be 1)"
echo "staged class md5     : $STAGED_MD5${EXPECT_MD5:+  (expected $EXPECT_MD5)}"

OK=yes
[ "$RLHD_CLASSES" -ge 290 ] || OK=no
[ "$LWJGL_LEAK" = 0 ] || OK=no
[ "$GLSL" -ge 40 ] || OK=no
[ "$JSON" -ge 6 ] || OK=no
[ "$ZONE" = 1 ] || OK=no
[ "$OCL" = 1 ] || OK=no
if [ -n "$EXPECT_MD5" ] && [ "$STAGED_MD5" != "$EXPECT_MD5" ]; then OK=no; fi

if [ "$OK" != yes ]; then echo "MEGAVERDICT = IOS_RLHD_STAGE_NOT_GREEN" >&2; exit 3; fi

mkdir -p "$(dirname "$OUT")"
mv "$NEW" "$OUT"
echo "staged iOS rlhd jar  : $OUT"
echo "MEGAVERDICT = IOS_RLHD_STAGE_GREEN"
