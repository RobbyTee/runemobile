#!/usr/bin/env bash
# W1 Phase 3 §6.0 — desktop PRE-CHECK of the CACIO activation mechanism.
#
# Question: WHAT actually activates CACIO's CTC toolkit + graphicsenv on JDK 21?
# We control launch flags + can call code via the HelloShell launcher on device, so
# this finds what works on desktop, then asks whether it ports to the iOS JRE.
#
# OBSERVED (2026-05-29, OpenJDK 21.0.5 aarch64 macOS) — read the printed verdicts;
# the docs quote the captured output, not the other way round:
#   (a) classpath    + props (+JPMS)   => FAILED      (toolkit=LWCToolkit, GE=CGraphicsEnvironment — native)
#   (b) bootclasspath + props (no JPMS)=> FAILED      (same — native; -Dawt.toolkit is NOT honored on JDK 21)
#   (c) AGENT inject() + allowAttachSelf=> CTC ACTIVE  (toolkit AND GE both = CTC) — the JVMTI redefine THREW
#                                          ("class redefinition failed: change the schema") yet activation
#                                          still succeeded (carried by CacioExtension's field injection).
#   (d) FIELD-POKE (naive reflection)  => FAILED       (GE singleton lives in lazy holder
#                                          GraphicsEnvironment$LocalGE; there is NO `localEnv` field, so the
#                                          poke broke GE init). A correct field-poke is NOT yet found.
#
# THE FINDING: property/bootclasspath selection does NOTHING on JDK 21. The only path
# that fully activates CTC here is the byte-buddy agent (mode c) — which needs the
# attach API / Instrumentation install (NOT the JVMTI redefine; that throws and is
# non-fatal). Whether the stripped iOS jre21 supports attach-self is UNTESTED.
#
# DEVICE IMPLICATION (open): §6.0-on-device needs ONE of: (1) attach-self works on the
# iOS jre21 (then mode c ports); (2) a CORRECT no-instrumentation field-poke that seeds
# the GraphicsEnvironment$LocalGE holder; or (3) Amethyst's CTCPreloadClassLoader.
# Settle before building. Do NOT assume the agent ports.
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
"$JAVAC" -cp "$CACIO_RUNTIME:out" -d out FieldPokeActivation.java 2>/dev/null
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
  echo; echo "########## (c) AGENT inject + allowAttachSelf — OBSERVED: JVMTI redefine THREW, yet CTC ACTIVE (toolkit+GE both CTC) ##########"
  "$JAVA" -Djdk.attach.allowAttachSelf=true -Djava.awt.headless=false "${JPMS[@]}" \
    -cp "out:$CACIO_AGENT:$JUNIT" AwtActivationAgent 2>&1 | grep -E "$GREP"
}
run_d() {
  echo; echo "########## (d) FIELD-POKE naive reflection — OBSERVED: FAILED (no localEnv field; GE singleton is in lazy holder LocalGE) ##########"
  "$JAVA" -Djava.awt.headless=false "${JPMS[@]}" \
    -cp "out:$CACIO_RUNTIME" FieldPokeActivation 2>&1 | grep -E "$GREP"
}

case "$MODE" in
  a) run_a ;; b) run_b ;; c) run_c ;; d) run_d ;;
  all) run_a; run_b; run_c; run_d ;;
  *) echo "usage: $0 [a|b|c|d|all]"; exit 2 ;;
esac
