#!/usr/bin/env bash
# Build the Android app and install it onto the phone over Wi-Fi — no USB cable.
#
# One-time bootstrap (cable plugged in, run once):   ./deploy-wireless.sh --bootstrap
# Every time after that (no cable):                  ./deploy-wireless.sh            # debug build
#                                                     ./deploy-wireless.sh release   # signed release build
#                                                     ./deploy-wireless.sh debug launch   # install + launch
#
# The phone must be on the SAME Wi-Fi/LAN as this Mac (or reachable via the PHONE override below, e.g. a
# Tailscale IP). After a phone REBOOT, adb's TCP mode resets — re-run `--bootstrap` once with the cable in.
set -euo pipefail

# --- config (override via env) ----------------------------------------------------------------------------
PHONE="${PHONE:-192.168.0.123:5555}"          # wireless adb endpoint (ip:port)
USB_SERIAL="${USB_SERIAL:-33171JEHN06239}"     # only used by --bootstrap (the cabled serial)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# JAVA_HOME: prefer JDK 21 (AGP needs 17+); fall back to whatever's on PATH.
if [ -z "${JAVA_HOME:-}" ]; then
  for c in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /opt/homebrew/opt/openjdk@21; do
    [ -x "$c/bin/java" ] && export JAVA_HOME="$c" && break
  done
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$PATH"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # android/ project root
cd "$HERE"

say() { printf '\033[1;36m[deploy-wireless]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[deploy-wireless] %s\033[0m\n' "$*" >&2; exit 1; }

# --- one-time bootstrap: flip the cabled phone into wireless-adb mode --------------------------------------
if [ "${1:-}" = "--bootstrap" ]; then
  command -v adb >/dev/null || die "adb not found (ANDROID_HOME=$ANDROID_HOME)"
  adb -s "$USB_SERIAL" get-state >/dev/null 2>&1 || die "phone $USB_SERIAL not on USB — plug in the cable and approve the debugging prompt"
  ip="${PHONE%%:*}"; port="${PHONE##*:}"
  say "switching adbd to TCP/IP on port $port (over USB)…"
  adb -s "$USB_SERIAL" tcpip "$port"
  say "connecting to $PHONE over Wi-Fi…"
  until adb connect "$PHONE" 2>&1 | grep -qiE "connected"; do sleep 1; done
  if adb devices | grep -qE "$PHONE[[:space:]]+unauthorized"; then
    say "approve the 'Allow wireless debugging' prompt on the phone (tick 'Always allow'), then re-run --bootstrap"
  fi
  adb devices -l | grep -E "$PHONE|$USB_SERIAL" || true
  say "bootstrap done — you can unplug the cable. Future runs: ./deploy-wireless.sh"
  exit 0
fi

# --- normal run: ensure the wireless link, build, install --------------------------------------------------
CONFIG="${1:-debug}"; ACTION="${2:-}"
case "$CONFIG" in
  debug)   TASK="assembleDebug" ;;
  release) TASK="assembleRelease" ;;
  *) die "unknown config '$CONFIG' (use: debug | release)" ;;
esac

ensure_connected() {
  local state
  state="$(adb -s "$PHONE" get-state 2>/dev/null || true)"
  if [ "$state" != "device" ]; then
    say "connecting to $PHONE…"
    adb disconnect "$PHONE" >/dev/null 2>&1 || true
    adb connect "$PHONE" >/dev/null 2>&1 || true
    state="$(adb -s "$PHONE" get-state 2>/dev/null || true)"
  fi
  case "$state" in
    device) say "wireless link up: $PHONE" ;;
    unauthorized) die "phone shows 'unauthorized' — approve the debugging prompt on the phone, then re-run" ;;
    *) die "phone $PHONE not reachable (state='$state'). Same Wi-Fi? Rebooted? -> re-run with the cable: ./deploy-wireless.sh --bootstrap" ;;
  esac
}

ensure_connected

say "building :app:$TASK…"
./gradlew ":app:$TASK" -q
APK="app/build/outputs/apk/$CONFIG/app-$CONFIG.apk"
[ -f "$APK" ] || die "APK not found: $APK"

say "installing $(basename "$APK") ($(du -h "$APK" | cut -f1)) over Wi-Fi…"
adb -s "$PHONE" install -r "$APK"
adb -s "$PHONE" shell am force-stop dev.audaro.helloshell || true

if [ "$ACTION" = "launch" ]; then
  say "launching…"
  adb -s "$PHONE" shell am start -n dev.audaro.helloshell/.MainActivity >/dev/null
fi
say "done — installed on $PHONE (cable not required)."
