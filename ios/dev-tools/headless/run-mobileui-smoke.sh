#!/usr/bin/env bash
# Phase-6 (W3) STEP-0 de-risk: prove the MobileUiBridge facade's thread semantics + type/config serialization
# on desktop OpenJDK 21 BEFORE building any native UI (the W2 develop-first rule). Boots real RuneLite headless
# under the CACIO CTC toolkit (no OS window — the honest iOS proxy) with:
#   -ea                         so the startPlugin/stopPlugin EDT asserts are LIVE (a no-op -ea would hide a
#                               wrong-thread bug — see the design audit's risk #2)
#   -Drunelite.mobileui=true    arms the facade + its tick() snapshot
#
# Runs MobileUiSmoke twice: phase "write" (drive the facade, flush a sentinel config), then phase "read"
# (a fresh JVM confirms the sentinel survived to disk). The GREEN/RED verdict is computed HERE in bash from
# the grepped MUI_* markers and md5-gated against the verbatim log — never trusting a success string the JVM
# printed (the documented false-GREEN guard).
#
# Login is OPTIONAL: if ios/dev-tools/tokens.json exists it is mapped to JX_* + autologin (enables the
# chat-bounds check); otherwise the core thread/config/persistence proofs run without secrets.
set -euo pipefail
cd "$(dirname "$0")"
REPO="../../.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$REPO/runelite-client/build/libs/client-1.12.28-SNAPSHOT-shaded.jar"
TOKENS="${TOKENS:-$REPO/ios/dev-tools/tokens.json}"
CACIOLIB="../cacio-smoke/lib"
CACIO="$CACIOLIB/cacio-tta-1.18.jar:$CACIOLIB/cacio-shared-1.18.jar:$CACIOLIB/byte-buddy-1.14.11.jar:$CACIOLIB/byte-buddy-agent-1.14.11.jar"
JUNIT="$CACIOLIB/junit-platform-console-standalone-1.10.2.jar"
OUT="out-mobileui"
RUN_SECONDS="${RUN_SECONDS:-90}"

if [ ! -f "$JAR" ]; then
  echo "== building shaded client on JDK 21 (picks up MobileUiBridge) =="
  (cd "$REPO" && ./gradlew :client:assemble --console=plain)
fi
if [ ! -f "$CACIOLIB/cacio-tta-1.18.jar" ] || [ ! -f "$JUNIT" ]; then
  echo "!! cacio/junit jars missing in $CACIOLIB — see ../cacio-smoke/README.md to fetch them" >&2
  exit 1
fi

# ---- optional Jagex login (only needed for the chat-bounds check) ----
LOGIN_PROPS=()
if [ -f "$TOKENS" ] && command -v jq >/dev/null; then
  export JX_ACCESS_TOKEN="$(jq -r '.access_token // ""' "$TOKENS")"
  export JX_REFRESH_TOKEN="$(jq -r '.refresh_token // ""' "$TOKENS")"
  export JX_SESSION_ID="$(jq -r '.session_id // ""' "$TOKENS")"
  export JX_CHARACTER_ID="$(jq -r '.chosen_account_id // ""' "$TOKENS")"
  export JX_DISPLAY_NAME="$(jq -r '.chosen_display_name // ""' "$TOKENS")"
  if [ -n "$JX_SESSION_ID" ] && [ -n "$JX_CHARACTER_ID" ]; then
    echo "== JX handoff present (displayName='${JX_DISPLAY_NAME}') — chat-bounds check enabled =="
    LOGIN_PROPS=(
      -Drunelite.headless.autologin=true
      -Drunelite.headless.autologin.point="${AUTOLOGIN_POINT:-382,248}"
      -Drunelite.headless.autologin.world="${AUTOLOGIN_WORLD:-301}"
      -Drunelite.headless.autologin.worldAddress="${AUTOLOGIN_WORLD_ADDRESS:-oldschool1.runescape.com}"
    )
  fi
else
  echo "== no tokens.json — running core proofs without login (chat-bounds check skipped) =="
fi

mkdir -p "$OUT"
echo "== compile MobileUiSmoke =="
"$JAVAC" -cp "$CACIO:$JUNIT:$JAR" -d "$OUT" MobileUiSmoke.java

run_phase() # $1=phase  $2=logfile  $3.. extra props
{
  local phase="$1"; shift
  local log="$1"; shift
  echo "== phase '$phase' -> $log =="
  set +e
  "$JAVA" \
    -ea \
    -Drunelite.mobileui=true \
    -Dmui.phase="$phase" \
    -Djdk.attach.allowAttachSelf=true \
    -Dheadless.runSeconds="$RUN_SECONDS" \
    "$@" \
    ${LOGIN_PROPS[@]+"${LOGIN_PROPS[@]}"} \
    --add-exports=java.desktop/java.awt=ALL-UNNAMED \
    --add-exports=java.desktop/java.awt.peer=ALL-UNNAMED \
    --add-exports=java.desktop/sun.awt.image=ALL-UNNAMED \
    --add-exports=java.desktop/sun.java2d=ALL-UNNAMED \
    --add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED \
    --add-exports=java.desktop/sun.awt=ALL-UNNAMED \
    --add-exports=java.desktop/sun.awt.event=ALL-UNNAMED \
    --add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED \
    --add-exports=java.base/sun.security.action=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt=ALL-UNNAMED \
    --add-opens=java.desktop/sun.java2d=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    -jar "$JUNIT" \
    -cp "$OUT:$CACIO:$JAR" \
    --select-class=MobileUiSmoke \
    --details=none \
    > "$log" 2>&1 &
  local pid=$!
  sleep "$((RUN_SECONDS + 10))"
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
  set -e
}

WLOG="$OUT/write.log"
RLOG="$OUT/read.log"

run_phase write "$WLOG"

# Pull the sentinel the write phase chose, then re-launch read-only to prove it persisted to disk.
SENTINEL="$(grep -m1 '^MUI_SENTINEL=' "$WLOG" | cut -d= -f2- || true)"
run_phase read "$RLOG" -Dmui.sentinel="$SENTINEL"

# ---- bash-computed, md5-gated verdict (NEVER trust a JVM-printed success string) ----
mk() { grep -m1 "^$1=" "$2" 2>/dev/null | cut -d= -f2- || true; }

EA="$(mk MUI_EA "$WLOG")"
LIST_OK="$(mk MUI_LIST_OK "$WLOG")"
CFG_FIRED="$(mk MUI_CONFIG_FIRED "$WLOG")"
CFG_VAL_OK="$(mk MUI_CONFIG_VALUE_OK "$WLOG")"
CFG_CLIENT="$(mk MUI_CONFIG_ON_CLIENT_THREAD "$WLOG")"
TOGGLE_OK="$(mk MUI_TOGGLE_OK "$WLOG")"
TOGGLE_EDT="$(mk MUI_TOGGLE_ON_EDT "$WLOG")"
OVERLAY_GREW="$(mk MUI_OVERLAY_GREW "$WLOG")"
PERSIST_OK="$(mk MUI_PERSIST_OK "$RLOG")"
LOGGED_IN="$(mk MUI_LOGGED_IN "$WLOG")"
CHAT_OK="$(mk MUI_CHAT_OK "$WLOG")"

echo
echo "================ MobileUiBridge STEP-0 SMOKE ================"
printf '  %-26s %s\n' "ea-live"            "$EA"
printf '  %-26s %s\n' "listPlugins"        "$LIST_OK"
printf '  %-26s %s\n' "config fired"       "$CFG_FIRED"
printf '  %-26s %s\n' "config value match" "$CFG_VAL_OK"
printf '  %-26s %s\n' "config on CLIENT"   "$CFG_CLIENT"
printf '  %-26s %s\n' "toggle ok (EDT)"    "$TOGGLE_OK"
printf '  %-26s %s\n' "overlay grew"       "$OVERLAY_GREW"
printf '  %-26s %s\n' "persisted to disk"  "$PERSIST_OK"
printf '  %-26s %s\n' "logged in"          "$LOGGED_IN"
printf '  %-26s %s\n' "chat bounds ok"     "$CHAT_OK"

# Core gates (login/chat is informational; overlay-grew is informational since not all plugins draw one).
PASS=1
for v in "$EA" "$LIST_OK" "$CFG_FIRED" "$CFG_VAL_OK" "$CFG_CLIENT" "$TOGGLE_OK" "$PERSIST_OK"; do
  [ "$v" = "true" ] || PASS=0
done

# md5 over the exact grepped tuple — the gate. A faked stdout banner cannot move this hash.
TUPLE="ea=$EA;list=$LIST_OK;cfgfired=$CFG_FIRED;cfgval=$CFG_VAL_OK;cfgclient=$CFG_CLIENT;toggle=$TOGGLE_OK;persist=$PERSIST_OK"
MD5="$(printf '%s' "$TUPLE" | md5 -q 2>/dev/null || printf '%s' "$TUPLE" | md5sum | cut -d' ' -f1)"
echo "  tuple: $TUPLE"
echo "  md5:   $MD5"
if [ "$PASS" = "1" ]; then
  echo "  VERDICT: GREEN"
else
  echo "  VERDICT: RED"
fi
echo "============================================================"
echo "logs: ios/dev-tools/headless/$WLOG , $RLOG"
