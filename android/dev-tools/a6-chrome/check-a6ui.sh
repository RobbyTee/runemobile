#!/usr/bin/env bash
# Phase A6 (chrome UI) GREEN gate — the native cover-and-proxy tab-rails draw over the live engine stones and a
# rail tap proxies onto the live stone.
#
# The overlay (ChromeOverlayView) reads the render-thread-published MobileChromeBridge snapshot each frame
# (lock-free, no HotSpot attach), maps the 14 tab-stone bounds game-canvas -> view via the blit full-stretch,
# and draws a native stone over each — highlighting the active tab. A tap on a native stone proxies
# JavaBridge.onClickPrecise onto the live engine stone centre (the engine + plugins stay authoritative).
#
#   DRAW  : ANDROID_A6_UI_GREEN tabsDrawn=N ...   (the overlay parsed the snapshot + laid out N rails)
#   PROXY : a6: native tab <i> tap -> onClickPrecise(x,y)   (a rail tap proxied to the live stone) — optional,
#           only present if a tap was injected this run; the gate passes on DRAW alone (proxy is verified by a
#           synthesized tap when run interactively — see README).
#
# Usage: check-a6ui.sh [logcat-file]
set -euo pipefail
SRC="${1:-}"
if [ -n "$SRC" ]; then
	[ -f "$SRC" ] || { echo "logcat file not found: $SRC" >&2; exit 2; }
	RAW="$(cat "$SRC")"
else
	command -v adb >/dev/null || { echo "adb not on PATH and no logcat file given" >&2; exit 2; }
	RAW="$(adb logcat -d 2>/dev/null)"
fi

DRAW="$(printf '%s\n' "$RAW" | grep -oE 'ANDROID_A6_UI_(GREEN|RED).*' | tail -1 || true)"
PROXY="$(printf '%s\n' "$RAW" | grep -oE 'a6: native tab [0-9]+ tap -> onClickPrecise.*' | tail -1 || true)"
echo "$DRAW"
[ -n "$PROXY" ] && echo "$PROXY"
if printf '%s' "$DRAW" | grep -q 'ANDROID_A6_UI_GREEN'; then
	echo "A6 chrome UI GREEN — native cover-and-proxy rails drew over the live tab stones${PROXY:+ (+ a rail tap proxied onClickPrecise onto the live stone)}."
	exit 0
else
	echo "A6 chrome UI not GREEN (need ANDROID_A6_UI_GREEN — the overlay parsed the snapshot and laid out rails)"; exit 1
fi
