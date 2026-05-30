#!/usr/bin/env bash
# W1 Phase 3 §6.0 — desktop PRE-CHECK of the CACIO activation mechanism.
#
# Question: WHAT actually activates CACIO's CTC toolkit + graphicsenv on JDK 21?
# We control launch flags + can call code via the HelloShell launcher on device, so
# this finds what works on desktop, then asks whether it ports to the iOS JRE.
#
# OBSERVED (2026-05-29, OpenJDK 21.0.5 aarch64 macOS) — read the printed verdicts,
# do not trust this comment over the output:
#   (a) classpath  + props (+JPMS)      => FAILED   (toolkit=LWCToolkit, GE=CGraphicsEnvironment — both native)
#   (b) bootclasspath + props (no JPMS) => FAILED   (same — both native)
#   (c) AGENT: CacioExtension.injectCTCGraphicsEnvironment() + allowAttachSelf + JPMS
#                                       => PARTIAL: GE => CTCGraphicsEnvironment, but
#                                          Toolkit stays LWCToolkit => FAILED by the full gate.
#
# So: property/bootclasspath selection does NOTHING on JDK 21. The byte-buddy
# instrumentation agent (attach-self + JVMTI redefine of
# GraphicsEnvironment.getLocalGraphicsEnvironment) swaps the GE but not the Toolkit.
# Full toolkit+GE activation was only seen under the @CacioTest JUnit harness
# (Gate 1b/1c). Either way the mechanism is JVMTI class redefinition / instrumentation
# attach — which is the capability that may be ABSENT on the iOS JRE.
#
# DEVICE IMPLICATION (the real, sharpened §6.0 risk): does the iOS jre21 support
# (1) the attach API / attach-self and (2) JVMTI RedefineClasses? Stripped mobile
# JREs often disable both. If so, we need Amethyst's CTCPreloadClassLoader-style
# no-JVMTI installer instead (investigate before any device build).
set -uo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}"
JAVAC="${JAVAC:-/opt/homebrew/opt/openjdk@21/bin/javac}"
L="../cacio-smoke/lib"
CACIO_RUNTIME="$L/cacio-tta-1.18.jar:$L/cacio-shared-1.18.jar"
CACIO_AGENT="$CACIO_RUNTIME:$L/byte-buddy-1.14.11.jar:$L/byte-buddy-agent-1.14.11.jar"
JUNIT="$L/junit-platform-console-standalone-1.10.2.jar"   # CacioExtension implements a junit iface -> needed to COMPILE AwtActivationAgent
MODE="${1:-all}"

PROPS=(
  -Djava.awt.headless=false
  -Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit
  -Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment
)
JPMS=(
  --add-exports=java.desktop/java.awt=ALL-UNNAMED
  --add-exports=java.desktop/java.awt.peer=ALL-UNNAMED
  --add-exports=java.desktop/sun.awt.image=ALL-UNNAMED
  --add-exports=java.desktop/sun.java2d=ALL-UNNAMED
  --add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED
  --add-exports=java.desktop/sun.awt.event=ALL-UNNAMED
  --add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED
  --add-exports=java.base/sun.security.action=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.desktop/java.awt=ALL-UNNAMED
  --add-opens=java.desktop/sun.java2d=ALL-UNNAMED
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
)
GREP='isHeadless|getDefaultToolkit\(\) =|getLocalGraphicsEnvironment\(\) =|AWT-ACTIVATION|injectCTC|THREW'

mkdir -p out
echo "== compile =="
"$JAVAC" --release 8 -d out AwtActivation.java 2>/dev/null
# AwtActivationAgent references CacioExtension (implements a junit iface) -> junit on cp.
"$JAVAC" -cp "$CACIO_AGENT:$JUNIT:out" -d out AwtActivationAgent.java || { echo "!! agent harness failed to compile"; exit 1; }

run_a() {
  echo; echo "########## (a) classpath + props (+JPMS) — OBSERVED: FAILED ##########"
  "$JAVA" -cp "out:$CACIO_RUNTIME" "${PROPS[@]}" "${JPMS[@]}" AwtActivation 2>&1 | grep -E "$GREP"
}
run_b() {
  echo; echo "########## (b) -Xbootclasspath/a + props (no JPMS) — OBSERVED: FAILED ##########"
  "$JAVA" "-Xbootclasspath/a:$CACIO_RUNTIME" "${PROPS[@]}" -cp out AwtActivation 2>&1 | grep -E "$GREP"
}
run_c() {
  echo; echo "########## (c) AGENT inject + allowAttachSelf + JPMS — OBSERVED: GE swaps, Toolkit does NOT => FAILED ##########"
  "$JAVA" -Djdk.attach.allowAttachSelf=true -Djava.awt.headless=false "${JPMS[@]}" \
    -cp "out:$CACIO_AGENT:$JUNIT" AwtActivationAgent 2>&1 | grep -E "$GREP"
}

case "$MODE" in
  a) run_a ;; b) run_b ;; c) run_c ;;
  all) run_a; run_b; run_c ;;
  *) echo "usage: $0 [a|b|c|all]"; exit 2 ;;
esac
