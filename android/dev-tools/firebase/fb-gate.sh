#!/usr/bin/env bash
# Firebase Test Lab gate runner — the FREE ($0, no expiry) real-device path.
# Spark plan = 5 physical-device runs/day at no cost, no billing account. Mirrors ../browserstack/bs-gate.sh
# but uses `gcloud firebase test android run` (Robo) instead of Appium, and reuses the SAME emulator md5 gate
# on the fetched logcat — so "real device" is just a different target for the identical GREEN check.
#
# One-time setup (free):
#   1. Create a Firebase project (Spark plan): https://console.firebase.google.com  (no billing needed)
#   2. Install the Google Cloud SDK (gcloud), then:
#        gcloud auth login
#        gcloud config set project <YOUR_PROJECT_ID>
#      (Auth lives in gcloud's own config — there is NO key file in this repo to manage.)
#
# Usage:
#   ./fb-gate.sh login  [apk]   # A0-device: pushed tokens.json -> autologin -> LOGGED_IN (Robo plain launch)
#   ./fb-gate.sh recon  [apk]   # AG0: GLES tier readout (needs the recon APK; AG0 not built yet)
#
# NOTE: `census` is NOT supported here — Robo launches the default activity and can't pass the
# `--ez census true` intent extra. The A-1b census is already GREEN on the emulator; if a device census is
# ever needed, use ../browserstack (Appium startActivity) or an owned device.
#
# Env: FB_DEVICE (model id, default "shiba"=Pixel 8), FB_VERSION (api, default 34), FB_TIMEOUT (180s), TOKENS.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
MODE="${1:-login}"
# recon = the standalone GLES-recon APK (:recon module); login = the main app.
if [ "$MODE" = "recon" ]; then
  APK="${2:-$ROOT/android/recon/build/outputs/apk/debug/recon-debug.apk}"
else
  APK="${2:-$ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"
fi
TOKENS="${TOKENS:-$ROOT/ios/dev-tools/tokens.json}"
OUT="$HERE/fb-logcat-$MODE.txt"
PKG="dev.audaro.helloshell"
DEV_TOKENS="/sdcard/Android/data/$PKG/files/tokens.json"

command -v gcloud >/dev/null || { echo "gcloud not found — install the Google Cloud SDK + run 'gcloud auth login'" >&2; exit 2; }
command -v gsutil >/dev/null || { echo "gsutil not found — it ships with the Google Cloud SDK" >&2; exit 2; }
[ -f "$APK" ] || { echo "APK not found: $APK (build it: gradlew :app:assembleDebug)" >&2; exit 2; }

# Unique results dir under Test Lab's DEFAULT bucket (omit --results-bucket so the free Spark tier needs no
# GCS bucket of its own). CONFIRM on first run: that the default bucket + this dir are readable by gsutil.
RESULTS_DIR="rl-$MODE-$(date +%s 2>/dev/null || echo run)"

OTHER=()
if [ "$MODE" != "recon" ] && [ -f "$TOKENS" ]; then
  # Push tokens.json to the app's external files dir before the test -> arms autologin.
  # CONFIRM on first run: the dir exists at push time on a fresh Test Lab device (Android creates it on
  # install); if the push is rejected, fall back to /sdcard/Download and widen JagexTokens' search path.
  OTHER=(--other-files "$DEV_TOKENS=$TOKENS")
fi

echo "== Firebase Test Lab Robo run (device=${FB_DEVICE:-shiba}, api=${FB_VERSION:-34}, mode=$MODE) =="
echo "   (pick a device id with: gcloud firebase test android models list)"
GLOG="$(mktemp)"
# Robo returns non-zero if it detects a crash; we still want the logcat, so don't let set -e abort here.
gcloud firebase test android run \
  --type robo \
  --app "$APK" \
  --device "model=${FB_DEVICE:-shiba},version=${FB_VERSION:-34},locale=en,orientation=portrait" \
  --timeout "${FB_TIMEOUT:-180s}" \
  --results-dir "$RESULTS_DIR" \
  "${OTHER[@]+"${OTHER[@]}"}" 2>&1 | tee "$GLOG" || true   # +-expansion: safe for an empty array under set -u (bash 3.2)

# Find the results path gcloud reported, then pull the device logcat from it. gcloud prints an https CONSOLE
# url (…/storage/browser/<bucket>/<dir>/), not a gs:// path, so reconstruct gs:// from it if needed.
GCS="$(grep -oE 'gs://[^] )"]+' "$GLOG" | head -1)"
if [ -z "$GCS" ]; then
  GCS="$(grep -oE 'storage/browser/[^] )"]+' "$GLOG" | head -1 | sed -E 's#.*storage/browser/#gs://#')"
fi
[ -n "$GCS" ] || { echo "could not find the results path (gs:// or console /storage/browser/) in gcloud output" >&2; exit 2; }
echo "== pull logcat from $GCS =="
LOGCAT_OBJ="$(gsutil ls "${GCS%/}/**/logcat" 2>/dev/null | head -1)"
[ -n "$LOGCAT_OBJ" ] || { echo "no logcat object under $GCS (confirm the results layout on first run)" >&2; exit 2; }
gsutil cp "$LOGCAT_OBJ" "$OUT"

echo "== md5-gate the fetched logcat (reusing the emulator gate) =="
case "$MODE" in
  login)  "$ROOT/android/dev-tools/a0-login/check-loggedin.sh" "$OUT" ;;
  recon)  grep -E "ANDROID_AG0" "$OUT" || { echo "no ANDROID_AG0 recon lines in logcat — is the recon APK built (./gradlew :recon:assembleDebug)?" >&2; exit 1; }
          grep -qE "ANDROID_AG0 VERDICT=(PASS|PASS_BASE_GPU_ONLY)" "$OUT" \
            && echo "AG0 device recon OK (real GLES tier/extensions captured above)" \
            || { echo "AG0 recon did not reach a PASS verdict" >&2; exit 1; } ;;
  *) echo "unsupported mode for Firebase: $MODE (census is emulator/BrowserStack only)" >&2; exit 2 ;;
esac
