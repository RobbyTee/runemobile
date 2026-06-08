#!/usr/bin/env bash
# Phase A4 (seam) GREEN gate — the Android HOST reaches the plugin control plane across the VM boundary.
#
# A-1b proved MobileUiBridge.listPlugins() returns a live ~32 KB plugin list when called IN the HotSpot VM.
# A4's seam proves the ART host can pull that same list across the ART<->HotSpot boundary, on the dedicated
# rl-bridge thread (A3.5) — the exact path A4's plugin UI will use. Census mode (`--ez census true`) runs a
# read-only host self-test that logs the received length; GREEN = a non-empty list crossed the boundary.
#
# Usage:
#   check-a4seam.sh                # live `adb logcat -d`
#   check-a4seam.sh saved.log      # a saved/cloud dump
set -euo pipefail

SRC="${1:-}"
if [ -n "$SRC" ]; then
	[ -f "$SRC" ] || { echo "logcat file not found: $SRC" >&2; exit 2; }
	RAW="$(cat "$SRC")"
else
	command -v adb >/dev/null || { echo "adb not on PATH and no logcat file given" >&2; exit 2; }
	RAW="$(adb logcat -d 2>/dev/null)"
fi

LINE="$(printf '%s\n' "$RAW" | grep -oE 'ANDROID_A4_SEAM_(GREEN|RED).*' | tail -1 || true)"
echo "$LINE"
if printf '%s' "$LINE" | grep -q 'ANDROID_A4_SEAM_GREEN'; then
	echo "(host pulled the live plugin list across the VM boundary on the rl-bridge thread)"
	exit 0
else
	echo "ANDROID_A4_SEAM_RED (no GREEN sentinel — check the mobileui gate / boot)"; exit 1
fi
