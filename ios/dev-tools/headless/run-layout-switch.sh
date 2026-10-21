#!/usr/bin/env bash
# DESKTOP live check of the one-tap layout switch (HudVeneerBridge.switchToModernLayout -> GameLayoutSwitch):
# presses the game's own Settings > Display "Game client layout" dropdown and confirms the server changed the
# layout. Boots the real RuneLite client under CACIO + autologin with -Drunelite.hudveneer.test.layoutCycle=true,
# which, about 10 s after login, switches the account to Resizable - Classic and back to Resizable - Modern (an
# account already on Classic is only switched to Modern). It always ends on Modern. Pass/fail is the
# "[layout] VERDICT:" line in out-layout-switch/run.log; the [layout] lines before it give the timings.
#
# Tier [A]: logs in to LIVE OSRS with the tokens.json account (throwaway only), and the switch changes that
# account's layout setting on every client it uses. If a run dies between the two steps, the account is left on
# Classic: run this again, which switches it straight back to Modern.
#
# Env overrides: RESIZE, RUN_SECONDS.
set -euo pipefail
cd "$(dirname "$0")"
REPO="../../.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
JAVA="$JAVA_HOME/bin/java"; JAVAC="$JAVA_HOME/bin/javac"
# QA-P1-15: derive the version from gradle.properties instead of a hardcoded literal (broken on the next bump).
VERSION="$(sed -n 's/^project\.build\.version=//p' "$REPO/gradle.properties")"
[ -n "$VERSION" ] || { echo "!! could not read project.build.version from $REPO/gradle.properties" >&2; exit 1; }
SHADED="$REPO/runelite-client/build/libs/client-${VERSION}-shaded.jar"
CACIOLIB="../cacio-smoke/lib"
CACIO="$CACIOLIB/cacio-tta-1.18.jar:$CACIOLIB/cacio-shared-1.18.jar"
AWTSMOKE="../awtsmoke"
OUT="out-layout-switch"
TOKENS="${TOKENS:-../tokens.json}"
# 1269x720 is the canvas where the shared autologin reliably reaches the WORLD (the welcome-screen "CLICK HERE TO
# PLAY" point is canvas-size-dependent and misses at the device's 1304x600). The switch reads no geometry, so the
# canvas size does not change what this checks.
RUN_SECONDS="${RUN_SECONDS:-170}"
RESIZE="${RESIZE:-1269x720}"

if [ ! -f "$SHADED" ]; then
  echo "== building shaded client on JDK 21 =="
  (cd "$REPO" && ./gradlew :client:shadowJar --console=plain)
fi
if [ ! -f "$TOKENS" ]; then echo "!! $TOKENS missing — mint via ../fetch-shield-tokens.sh" >&2; exit 1; fi
command -v jq >/dev/null || { echo "!! jq required" >&2; exit 1; }

# Session and character only, like the phones' in-app login. With the launcher-flow access/refresh tokens a
# fresh fetch-shield-tokens.sh mint also writes, the game server refused the login ("Failed to login. Please try
# again.", loginIndex 9) on 2026-10-21; the same session without them logged straight in.
unset JX_ACCESS_TOKEN JX_REFRESH_TOKEN
export JX_SESSION_ID="$(jq -r '.session_id // ""' "$TOKENS")"
export JX_CHARACTER_ID="$(jq -r '.chosen_account_id // ""' "$TOKENS")"
export JX_DISPLAY_NAME="$(jq -r '.chosen_display_name // ""' "$TOKENS")"
if [ -z "$JX_SESSION_ID" ] || [ -z "$JX_CHARACTER_ID" ]; then echo "!! tokens.json missing session/account" >&2; exit 1; fi
echo "== JX handoff: displayName='${JX_DISPLAY_NAME}' resize=${RESIZE} =="

rm -rf "$OUT"; mkdir -p "$OUT/cap"
echo "== compile DeviceHeadlessBoot + activation =="
"$JAVAC" -cp "$SHADED:$CACIO" -d "$OUT" \
  "$AWTSMOKE/AwtActivation.java" "$AWTSMOKE/UnsafePokeActivation.java" "$AWTSMOKE/GateCensus.java" "$AWTSMOKE/DeviceHeadlessBoot.java"

LOG="$OUT/run.log"
echo "== launching real RuneLite under UnsafePoke CTC + autologin + layout-switch cycle @${RESIZE} (${RUN_SECONDS}s) =="
set +e
"$JAVA" \
  -Dheadless.runSeconds="$RUN_SECONDS" \
  -Dheadless.captureTargetFrames=999 \
  -Drunelite.headless.capture.dir="$PWD/$OUT/cap" \
  -Drunelite.headless.capture.interval="${CAPTURE_INTERVAL:-100}" \
  -Drunelite.headless.capture.preLoginMax="${PRELOGIN_MAX:-3}" \
  -Drunelite.headless.capture.max="${CAPTURE_MAX:-40}" \
  -Drunelite.headless.resize="${RESIZE}" \
  -Drunelite.headless.autologin=true \
  -Drunelite.headless.autologin.eula=true \
  -Drunelite.headless.autologin.eula.point="302,312" \
  -Drunelite.headless.autologin.eula.loginIndex=12 \
  -Drunelite.headless.autologin.point="382,248" \
  -Drunelite.headless.autologin.world=301 \
  -Drunelite.headless.autologin.worldAddress="oldschool1.runescape.com" \
  -Drunelite.hudveneer=true \
  -Drunelite.hudveneer.test.layoutCycle=true \
  ${EXTRA_JVM_ARGS:-} \
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
  -cp "$OUT:$CACIO:$SHADED" DeviceHeadlessBoot > "$LOG" 2>&1 &
PID=$!
for i in $(seq 1 "$((RUN_SECONDS + 15))"); do
  sleep 1
  if grep -q "\[layout\] VERDICT:" "$LOG" 2>/dev/null; then
    echo "== verdict reached at ~${i}s =="
    sleep 2   # one more capture of the final layout
    break
  fi
  kill -0 "$PID" 2>/dev/null || break
done
kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null
set -e

echo "== login progression =="; grep -E "gameState .* LOGGED_IN" "$LOG" | tail -1 || echo "(none)"
echo "== [layout] =="; grep -E "\[layout\]" "$LOG" || echo "(none)"
echo "== LOGGED_IN frames =="; ls "$OUT/cap"/frame-LOGGED_IN-*.png 2>/dev/null | tail -6 || echo "(none - check $LOG)"
echo "== full log: $LOG =="
VERDICT="$(grep -oE "\[layout\] VERDICT: [A-Z]+" "$LOG" | tail -1 | awk '{print $3}')"
echo "VERDICT: ${VERDICT:-NO_VERDICT}"
case "${VERDICT:-}" in
  GREEN) exit 0 ;;
  RED) exit 4 ;;
  *) exit 2 ;;
esac
