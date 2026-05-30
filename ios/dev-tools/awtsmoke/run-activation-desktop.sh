#!/usr/bin/env bash
# W1 Phase 3 §6.0 — desktop PRE-CHECK of the CACIO activation mechanism.
#
# Question: WHAT actually activates CACIO's CTC toolkit + graphicsenv on JDK 21?
# We control launch flags + can call code on device via the HelloShell launcher, so
# this finds the cheapest mechanism that reaches "CTC ACTIVE" on desktop, then we
# ask whether it ports to the iOS JRE.
#
# RESULT (2026-05-29, OpenJDK 21.0.5 aarch64 macOS) — see README.md:
#   (a) classpath  + props (+JPMS)         => FAILED  (toolkit/GE stay native)
#   (b) bootclasspath + props (no JPMS)    => FAILED  (toolkit/GE stay native)
#   (c) AGENT: CacioExtension.injectCTCGraphicsEnvironment() + allowAttachSelf + JPMS => CTC ACTIVE
#
# i.e. PROPERTY/BOOTCLASSPATH SELECTION DOES NOT WORK on JDK 21 — `-Dawt.toolkit`
# is not honored for selection. The working mechanism is the ByteBuddy instrumentation
# agent (attach-self + JVMTI class REDEFINITION of
# GraphicsEnvironment.getLocalGraphicsEnvironment + ClassInjector$UsingUnsafe into the
# bootstrap loader). CacioExtension does exactly this; injectCTCGraphicsEnvironment()
# is public static so it runs WITHOUT JUnit.
#
# DEVICE IMPLICATION (the real §6.0 risk, now sharp): does the iOS JRE support
# (1) the attach API / attach-self, and (2) JVMTI RedefineClasses/RetransformClasses?
# Stripped mobile JREs often disable these. If iOS can't, we need Amethyst's
# CTCPreloadClassLoader-style no-JVMTI path instead (investigate next).
set -uo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}"
JAVAC="${JAVAC:-/opt/homebrew/opt/openjdk@21/bin/javac}"
L="../cacio-smoke/lib"
CACIO_RUNTIME="$L/cacio-tta-1.18.jar:$L/cacio-shared-1.18.jar"
CACIO_AGENT="$CACIO_RUNTIME:$L/byte-buddy-1.14.11.jar:$L/byte-buddy-agent-1.14.11.jar"
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
GREP='isHeadless|getDefaultToolkit\(\) =|getLocalGraphicsEnvironment\(\) =|AWT-ACTIVATION|THREW'

mkdir -p out
echo "== compile (release 8 for AwtActivation; agent harness needs cacio on cp) =="
"$JAVAC" --release 8 -d out AwtActivation.java 2>/dev/null
"$JAVAC" -cp "$CACIO_AGENT:out" -d out AwtActivationAgent.java 2>/dev/null

run_a() {
  echo; echo "########## (a) classpath + props (+JPMS) — EXPECT FAILED ##########"
  "$JAVA" -cp "out:$CACIO_RUNTIME" "${PROPS[@]}" "${JPMS[@]}" AwtActivation 2>&1 | grep -E "$GREP"
}
run_b() {
  echo; echo "########## (b) -Xbootclasspath/a + props (no JPMS) — EXPECT FAILED ##########"
  "$JAVA" "-Xbootclasspath/a:$CACIO_RUNTIME" "${PROPS[@]}" -cp out AwtActivation 2>&1 | grep -E "$GREP"
}
run_c() {
  echo; echo "########## (c) AGENT: injectCTCGraphicsEnvironment() + allowAttachSelf + JPMS — EXPECT CTC ACTIVE ##########"
  "$JAVA" -Djdk.attach.allowAttachSelf=true -Djava.awt.headless=false "${JPMS[@]}" \
    -cp "out:$CACIO_AGENT" AwtActivationAgent 2>&1 | grep -E "$GREP"
}

case "$MODE" in
  a) run_a ;; b) run_b ;; c) run_c ;;
  all) run_a; run_b; run_c ;;
  *) echo "usage: $0 [a|b|c|all]"; exit 2 ;;
esac
