#!/usr/bin/env bash
# GPU Route C step 2A — stage the iOS-variant gpu jar (jar surgery).
#
# Reads the PRISTINE desktop shaded jar (build/libs, left untouched — desktop + Android keep using it) and writes
# an iOS variant into the app bundle staging dir (jvm-resources). In the variant, every `org.lwjgl.*`,
# `net.runelite.rlawt.AWTContext` and `net.runelite.client.plugins.gpu.*` class is provided ONLY by our native
# facade (`facade-native/`, GL/AWTContext methods turned `native`) + a recompile of the unmodified plugin against
# it; LWJGL and librlawt are physically stripped. So at runtime there is exactly one provider of each of those
# symbols, all bridge-backed — no macOS dylib `dlopen` is attempted on device.
#
# Deterministic + md5-gateable. Pass an expected md5 of the staged jar's sorted class manifest as $1 to gate.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
SRC="${SRC:-../../../runelite-client/build/libs/client-1.12.28-SNAPSHOT-shaded.jar}"
OUT="${OUT:-../../app/jvm-resources/client-1.12.28-SNAPSHOT-shaded.jar}"
DL="${DL:-../../../runelite-client/build/generated/sources/delombok/java/main/net/runelite/client/plugins/gpu}"
EXPECT_MD5="${1:-}"

[ -f "$SRC" ] || { echo "!! pristine shaded jar missing: $SRC (run ./gradlew :client:assemble)" >&2; exit 1; }
[ -d "$DL" ]  || { echo "!! delombok gpu sources missing: $DL (run ./gradlew :client:delombok)" >&2; exit 1; }

# Absolutize — SRC/DL are used inside `(cd "$WORK/..." && ...)` subshells where a relative path would misresolve.
SRC=$(cd "$(dirname "$SRC")" && pwd)/$(basename "$SRC")
DL=$(cd "$DL" && pwd)
case "$OUT" in /*) ;; *) OUT="$(pwd)/$OUT" ;; esac

# Refuse a non-pristine source (already stripped) — guards against accidentally feeding the iOS variant back in.
# (grep -c consumes all input, so it doesn't SIGPIPE unzip under `set -o pipefail` the way grep -q would.)
SRC_LWJGL=$(unzip -l "$SRC" | grep -c 'org/lwjgl/.*[.]class' || true)
[ "$SRC_LWJGL" -gt 0 ] || { echo "!! SRC has no org/lwjgl classes — not a pristine shaded jar: $SRC" >&2; exit 1; }

WORK="$(pwd)/stage-work"
rm -rf "$WORK"; mkdir -p "$WORK/facade_out" "$WORK/gpu_out" "$WORK/jar"

# 1) facade-native compiles (the GL/AWTContext surface the plugin references, methods `native`).
"$JAVAC" -d "$WORK/facade_out" $(find facade-native -name '*.java')
FACADE_LWJGL_CLASSES=$(cd "$WORK/facade_out" && find org/lwjgl -name '*.class' | wc -l | tr -d ' ')

# 2) explode the pristine jar; strip lwjgl + rlawt + the gpu package (the only LWJGL/rlawt users in the tree).
(cd "$WORK/jar" && unzip -q "$SRC")
rm -rf "$WORK/jar/org/lwjgl" "$WORK/jar/net/runelite/rlawt" "$WORK/jar/net/runelite/client/plugins/gpu"

# 3) recompile the unmodified delombok'd gpu sources against [facade-native : stripped-jar]. Recompiling (not
#    reusing the real-build bytecode) guarantees every reference resolves against facade classes we control.
set +e
"$JAVAC" -nowarn -proc:none -cp "$WORK/facade_out:$WORK/jar" -d "$WORK/gpu_out" $(find "$DL" -name '*.java') 2>"$WORK/err.txt"
RC=$?
set -e
if [ "$RC" != 0 ]; then echo "!! gpu recompile FAILED ($(grep -c 'error:' "$WORK/err.txt") errors):" >&2; cat "$WORK/err.txt" >&2; exit 2; fi

# 4) repackage: drop the facade + recompiled gpu classes into the stripped tree.
cp -R "$WORK/facade_out/." "$WORK/jar/"
cp -R "$WORK/gpu_out/." "$WORK/jar/"

# 5) asserts — exactly one (facade) provider of each stripped namespace, no stale service providers.
SURV=$(cd "$WORK/jar" && find org/lwjgl -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
RLAWT_SURV=$(find "$WORK/jar/net/runelite/rlawt" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
GPU_CLASSES=$(cd "$WORK/jar" && find net/runelite/client/plugins/gpu -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
SVC_LEAK=$( { grep -rIlE 'org\.lwjgl|net\.runelite\.rlawt' "$WORK/jar/META-INF/services" 2>/dev/null || true; } | wc -l | tr -d ' ')

# 6) rezip (keep the on-disk MANIFEST; -M = don't synthesize one).
NEW="$WORK/staged.jar"
"$JAR" --create --no-manifest --file "$NEW" -C "$WORK/jar" .
STAGED_MD5=$(cd "$WORK/jar" && find . -name '*.class' | sort | xargs cat | { md5 -q 2>/dev/null || md5sum | awk '{print $1}'; })

echo "facade lwjgl classes : $FACADE_LWJGL_CLASSES"
echo "org/lwjgl survivors  : $SURV   (must == facade lwjgl classes; all bridge-backed)"
echo "rlawt survivors      : $RLAWT_SURV   (facade AWTContext only)"
echo "gpu classes          : $GPU_CLASSES   (recompiled against facade-native; 21 sources + inner)"
echo "stale svc providers  : $SVC_LEAK   (must be 0)"
echo "staged class md5     : $STAGED_MD5${EXPECT_MD5:+  (expected $EXPECT_MD5)}"

OK=yes
[ "$SURV" = "$FACADE_LWJGL_CLASSES" ] || OK=no
[ "$RLAWT_SURV" -ge 1 ] || OK=no
[ "$GPU_CLASSES" -ge 21 ] || OK=no
[ "$SVC_LEAK" = 0 ] || OK=no
if [ -n "$EXPECT_MD5" ] && [ "$STAGED_MD5" != "$EXPECT_MD5" ]; then OK=no; fi

if [ "$OK" != yes ]; then echo "MEGAVERDICT = ROUTEC_STAGE_NOT_GREEN" >&2; exit 3; fi

mv "$NEW" "$OUT"
echo "staged iOS gpu jar   : $OUT"
echo "MEGAVERDICT = ROUTEC_STAGE_GREEN"
