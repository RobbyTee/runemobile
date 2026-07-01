#!/usr/bin/env bash
# Build jvm-resources/probes.jar (the resource project.yml bundles into the app).
# Compiled `--release 8` so the device JDK 21 loads them (same as the desktop A/B).
#
# Contents:
#   - Phase-1 probes (jvmbench): JitProbe, Bench, GlyphSmoke
#   - W1 P3 §6.0 activation confirm (awtsmoke): AwtActivation + UnsafePokeActivation
#   - the CACIO cacio17 classes (cacio-tta + cacio-shared 1.18) FOLDED IN, so the device
#     classpath stays just probes.jar (JVMLauncher sets -Djava.class.path to it alone).
#     GPLv3 jars used as-is for a LOCAL device test (same jars we run on desktop) — not distributed.
#
# Usage: ./build-probes.sh   (run from ios/app/)
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="${JAVAC:-/opt/homebrew/opt/openjdk@21/bin/javac}"
JAR="${JAR:-/opt/homebrew/opt/openjdk@21/bin/jar}"
JFLAGS="--release 8"
JVMBENCH=../dev-tools/jvmbench
AWTSMOKE=../dev-tools/awtsmoke
CACIO_LIB=../dev-tools/cacio-smoke/lib
CACIO_CP="$CACIO_LIB/cacio-tta-1.18.jar:$CACIO_LIB/cacio-shared-1.18.jar"
OUT=build/probes-classes
JARFILE=jvm-resources/probes.jar

rm -rf "$OUT"; mkdir -p "$OUT" "$(dirname "$JARFILE")"
here="$(pwd)"

echo "== fold in CACIO classes =="
( cd "$OUT" && unzip -oq "$here/$CACIO_LIB/cacio-tta-1.18.jar"    -x 'META-INF/*' \
            && unzip -oq "$here/$CACIO_LIB/cacio-shared-1.18.jar" -x 'META-INF/*' )

echo "== compile Phase-1 probes (javac $JFLAGS) =="
"$JAVAC" $JFLAGS -d "$OUT" \
  "$JVMBENCH/JitProbe.java" "$JVMBENCH/Bench.java" "$JVMBENCH/GlyphSmoke.java"

echo "== compile §6.0 activation + §6.1 smoke probes (javac $JFLAGS, cacio on cp) =="
"$JAVAC" $JFLAGS -cp "$CACIO_CP" -d "$OUT" \
  "$AWTSMOKE/AwtActivation.java" "$AWTSMOKE/UnsafePokeActivation.java" "$AWTSMOKE/AwtSmoke.java"

# §6.2: DeviceHeadlessBoot references net.runelite.client.headless.RuneLiteHeadless (in the shaded
# client jar), so it only compiles when the shaded jar is present. Folded into probes.jar; the device
# classpath is shaded.jar:probes.jar, so RuneLiteHeadless resolves from shaded at runtime.
SHADED="${SHADED:-$(ls ../../runelite-client/build/libs/client-*-shaded.jar 2>/dev/null | tail -1 || true)}"
if [ -f "$SHADED" ]; then
  echo "== compile §6.2 DeviceHeadlessBoot + GateCensus (javac $JFLAGS, prior classes+cacio+shaded on cp) =="
  "$JAVAC" $JFLAGS -cp "$OUT:$CACIO_CP:$SHADED" -d "$OUT" "$AWTSMOKE/DeviceHeadlessBoot.java" "$AWTSMOKE/GateCensus.java"
elif [ "${ALLOW_NO_SHADED:-0}" = "1" ]; then
  echo "== SKIP §6.2 DeviceHeadlessBoot (ALLOW_NO_SHADED=1; probes.jar will NOT boot the client) =="
else
  # Without the shaded jar this would silently produce a probes.jar MISSING the boot entry point
  # (DeviceHeadlessBoot) — the app then dies at launch with a confusing NoClassDefFoundError. Build the
  # shaded jar first; ALLOW_NO_SHADED=1 restores the old skip for probe-only builds.
  echo "ERROR: shaded client jar not found (looked at: ${SHADED:-<none>})." >&2
  echo "       Run ./gradlew :client:shadowJar at the repo root first, or set ALLOW_NO_SHADED=1." >&2
  exit 1
fi

echo "== pack $JARFILE =="
"$JAR" cf "$JARFILE" -C "$OUT" .

echo "== done =="
ls -la "$JARFILE"
echo "key classes:"; "$JAR" tf "$JARFILE" | grep -E 'UnsafePokeActivation|AwtActivation\.class|ctc/CTCToolkit\.class|ctc/CTCGraphicsEnvironment\.class' || true
