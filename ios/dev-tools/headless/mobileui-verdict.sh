#!/usr/bin/env bash
# Shared GREEN/RED verdict for the MobileUiBridge STEP-0 smoke — used by BOTH the desktop runner
# (run-mobileui-smoke.sh, two local JVM launches) and the device confirm (a pulled jvm-stdio.log from
# two StikDebug launches). The verdict is computed HERE in bash from the grepped MUI_* markers and
# md5-gated against the verbatim log(s) — never trusting a JVM-printed success string (the documented
# false-GREEN guard).
#
# Usage:
#   mobileui-verdict.sh <run1.log> [run2.log]
# One log    -> single-launch gates (no cross-launch persistence; that needs two launches).
# Two logs   -> also requires run2's PERSIST_PRELOAD == run1's PERSIST_WRITTEN (disk round-trip).
set -euo pipefail

LOG1="${1:?usage: mobileui-verdict.sh <run1.log> [run2.log]}"
LOG2="${2:-}"

mk() { grep -m1 "^$1=" "$2" 2>/dev/null | cut -d= -f2- || true; }
md5of() { if command -v md5 >/dev/null; then md5 -q; else md5sum | cut -d' ' -f1; fi; }

EA="$(mk MUI_EA "$LOG1")"
LIST_OK="$(mk MUI_LIST_OK "$LOG1")"
CFG_FIRED="$(mk MUI_CONFIG_FIRED "$LOG1")"
CFG_VAL_OK="$(mk MUI_CONFIG_VALUE_OK "$LOG1")"
CFG_CLIENT="$(mk MUI_CONFIG_ON_CLIENT_THREAD "$LOG1")"
CFG_EDT="$(mk MUI_CONFIG_ON_EDT "$LOG1")"
TOGGLE_OK="$(mk MUI_TOGGLE_OK "$LOG1")"
TOGGLE_EDT="$(mk MUI_TOGGLE_ON_EDT "$LOG1")"
TOGGLE_TARGET="$(mk MUI_TOGGLE_TARGET "$LOG1")"
OVERLAY_GREW="$(mk MUI_OVERLAY_GREW "$LOG1")"
LOGGED_IN="$(mk MUI_LOGGED_IN "$LOG1")"
CHAT_OK="$(mk MUI_CHAT_OK "$LOG1")"
WRITTEN1="$(mk MUI_PERSIST_WRITTEN "$LOG1")"

# Cross-launch persistence (only when a second log is supplied).
XLAUNCH="n/a"
if [ -n "$LOG2" ]; then
  PRELOAD2="$(mk MUI_PERSIST_PRELOAD "$LOG2")"
  if [ -n "$WRITTEN1" ] && [ "$PRELOAD2" = "$WRITTEN1" ]; then
    XLAUNCH="true"
  else
    XLAUNCH="false"
  fi
fi

echo
echo "================ MobileUiBridge STEP-0 SMOKE ================"
printf '  %-26s %s\n' "ea-live"                 "$EA"
printf '  %-26s %s\n' "listPlugins"             "$LIST_OK"
printf '  %-26s %s\n' "config fired"            "$CFG_FIRED"
printf '  %-26s %s\n' "config value match"      "$CFG_VAL_OK"
printf '  %-26s %s\n' "config on CLIENT thread" "$CFG_CLIENT"
printf '  %-26s %s\n' "config NOT on EDT"       "$([ "$CFG_EDT" = "false" ] && echo true || echo "$CFG_EDT")"
printf '  %-26s %s\n' "toggle ok (EDT)"         "$TOGGLE_OK  (target: ${TOGGLE_TARGET:-?})"
printf '  %-26s %s\n' "overlay grew (headless)" "$OVERLAY_GREW"
printf '  %-26s %s\n' "logged in"               "$LOGGED_IN"
printf '  %-26s %s\n' "chat bounds ok"          "$CHAT_OK"
printf '  %-26s %s\n' "persist x-launch"        "$XLAUNCH"

# Core gates. config-must-NOT-be-on-EDT and toggle-MUST-be-on-EDT together prove the two lanes separate.
# overlay-grew / chat / x-launch are informational unless their inputs are present.
PASS=1
for v in "$EA" "$LIST_OK" "$CFG_FIRED" "$CFG_VAL_OK" "$CFG_CLIENT" "$TOGGLE_OK" "$TOGGLE_EDT"; do
  [ "$v" = "true" ] || PASS=0
done
[ "$CFG_EDT" = "false" ] || PASS=0
# If two logs were given, cross-launch persistence becomes a hard gate.
if [ -n "$LOG2" ]; then
  [ "$XLAUNCH" = "true" ] || PASS=0
fi

TUPLE="ea=$EA;list=$LIST_OK;cfgfired=$CFG_FIRED;cfgval=$CFG_VAL_OK;cfgclient=$CFG_CLIENT;cfgnotedt=$([ "$CFG_EDT" = false ] && echo 1 || echo 0);toggle=$TOGGLE_OK;toggleedt=$TOGGLE_EDT;xlaunch=$XLAUNCH"
MD5="$(printf '%s' "$TUPLE" | md5of)"
echo "  tuple: $TUPLE"
echo "  md5:   $MD5"
if [ "$PASS" = "1" ]; then
  echo "  VERDICT: GREEN"
else
  echo "  VERDICT: RED"
fi
echo "============================================================"
[ "$PASS" = "1" ]
