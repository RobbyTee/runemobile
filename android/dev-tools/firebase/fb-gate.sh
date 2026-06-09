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
#   ./fb-gate.sh gpu    [apk]   # AG2-P5: base gpu plugin renders on the REAL driver. Pushes tokens + a gpu.mode
#                               # marker (Robo can't pass --ez routecgpu), pulls the dumped frames, gates the log
#                               # for isGpu/createGLContext/gpuPublish + NO ShaderException, and reports the PNGs.
#
# NOTE: `census` is NOT supported here — Robo launches the default activity and can't pass the
# `--ez census true` intent extra. The A-1b census is already GREEN on the emulator; if a device census is
# ever needed, use ../browserstack (Appium startActivity) or an owned device. `gpu` works around the same
# no-intent-extra limit with a pushed marker file (--other-files) the app checks at launch.
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
DEV_FILES="/sdcard/Android/data/$PKG/files"
DEV_TOKENS="$DEV_FILES/tokens.json"
DEV_MARKER="$DEV_FILES/gpu.mode"
DEV_FRAMES="$DEV_FILES/routec-frames"

command -v gcloud >/dev/null || { echo "gcloud not found — install the Google Cloud SDK + run 'gcloud auth login'" >&2; exit 2; }
command -v gsutil >/dev/null || { echo "gsutil not found — it ships with the Google Cloud SDK" >&2; exit 2; }
[ -f "$APK" ] || { echo "APK not found: $APK (build it: gradlew :app:assembleDebug)" >&2; exit 2; }

# Unique results dir under Test Lab's DEFAULT bucket (omit --results-bucket so the free Spark tier needs no
# GCS bucket of its own). CONFIRM on first run: that the default bucket + this dir are readable by gsutil.
RESULTS_DIR="rl-$MODE-$(date +%s 2>/dev/null || echo run)"

OTHER=()
PULL=()
if [ "$MODE" != "recon" ] && [ -f "$TOKENS" ]; then
  # Push tokens.json to the app's external files dir before the test -> arms autologin.
  # CONFIRM on first run: the dir exists at push time on a fresh Test Lab device (Android creates it on
  # install); if the push is rejected, fall back to /sdcard/Download and widen JagexTokens' search path.
  OTHER=(--other-files "$DEV_TOKENS=$TOKENS")
fi
if [ "$MODE" = "gpu" ]; then
  # Seed the gpu.mode marker (Robo can't pass --ez routecgpu); MainActivity boots GPU Route-C when it exists.
  # Combine with the tokens push into ONE --other-files (comma-separated dev=local pairs).
  MARKER="$(mktemp)"; printf 'routecgpu\n' > "$MARKER"
  if [ -f "$TOKENS" ]; then OTHER=(--other-files "$DEV_TOKENS=$TOKENS,$DEV_MARKER=$MARKER"); else OTHER=(--other-files "$DEV_MARKER=$MARKER"); fi
  # Pull the dumped GPU frames (app-private external storage) back to the results bucket.
  PULL=(--directories-to-pull "$DEV_FRAMES")
  : "${FB_TIMEOUT:=300s}"   # GPU boot + login + a few frame dumps needs longer than the 180s default
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
  "${OTHER[@]+"${OTHER[@]}"}" \
  "${PULL[@]+"${PULL[@]}"}" 2>&1 | tee "$GLOG" || true   # +-expansion: safe for an empty array under set -u (bash 3.2)

# Find the results path gcloud reported, then pull the device logcat from it. gcloud prints an https CONSOLE
# url (…/storage/browser/<bucket>/<dir>/), not a gs:// path, so reconstruct gs:// from it if needed.
# NOTE: each command-substitution below ends in `|| true` — a grep/gsutil that finds nothing returns non-zero,
# which under `set -eo pipefail` would otherwise abort the script before the fallback / the friendly error.
GCS="$(grep -oE 'gs://[^] )"]+' "$GLOG" | head -1 || true)"
if [ -z "$GCS" ]; then
  GCS="$(grep -oE 'storage/browser/[^] )"]+' "$GLOG" | head -1 | sed -E 's#.*storage/browser/#gs://#' || true)"
fi
[ -n "$GCS" ] || { echo "could not find the results path (gs:// or console /storage/browser/) in gcloud output" >&2; exit 2; }
echo "== pull logcat from $GCS =="
LOGCAT_OBJ="$(gsutil ls "${GCS%/}/**/logcat" 2>/dev/null | head -1 || true)"
[ -n "$LOGCAT_OBJ" ] || { echo "no logcat object under $GCS (confirm the results layout on first run)" >&2; exit 2; }
gsutil cp "$LOGCAT_OBJ" "$OUT"

echo "== md5-gate the fetched logcat (reusing the emulator gate) =="
case "$MODE" in
  login)  "$ROOT/android/dev-tools/a0-login/check-loggedin.sh" "$OUT" ;;
  recon)  echo "--- AG0 (GLES tier/extensions) ---"; grep -E "ANDROID_AG0" "$OUT" || { echo "no ANDROID_AG0 lines — is the recon APK built (./gradlew :recon:assembleDebug)?" >&2; exit 1; }
          echo "--- AG1b (real base-gpu shaders compile/link/validate on this driver) ---"; grep -E "ANDROID_AG1B" "$OUT" || true
          A0="$(grep -oE 'ANDROID_AG0 VERDICT=[A-Z_]+' "$OUT" | tail -1)"; A1="$(grep -oE 'ANDROID_AG1B VERDICT=[A-Z_]+' "$OUT" | tail -1)"
          echo ">> $A0    >> $A1"
          printf '%s' "$A0" | grep -qE 'VERDICT=(PASS|PASS_BASE_GPU_ONLY)' || { echo "AG0 did not PASS" >&2; exit 1; } ;;
  gpu)    echo "--- AG2-P5: base gpu plugin on the real driver ---"
          grep -E "GpuPlugin - Using (device|driver)|createGLContext DONE|routecgpu\] .* GPU active|gpuframedump\] wrote" "$OUT" | head -12 || true
          echo "--- failures (want NONE) ---"
          grep -E "ShaderException|EGL bring-up FAILED|no-such-plugin" "$OUT" | head -5 || echo "  (none)"
          # Pull the dumped frames (Firebase --directories-to-pull lands them under the device artifacts dir).
          FRAMEDIR="$HERE/fb-gpu-frames"; rm -rf "$FRAMEDIR"; mkdir -p "$FRAMEDIR"
          PNGS="$(gsutil ls "${GCS%/}/**/routec-frames/*.png" 2>/dev/null || true)"
          if [ -n "$PNGS" ]; then echo "$PNGS" | while read -r p; do [ -n "$p" ] && gsutil cp "$p" "$FRAMEDIR/" 2>/dev/null; done; fi
          N="$(ls "$FRAMEDIR"/*.png 2>/dev/null | wc -l | tr -d ' ')"
          echo "== pulled $N GPU frame PNG(s) -> $FRAMEDIR =="
          ACTIVE="$(grep -c "GPU active (isGpu=true)" "$OUT" || true)"
          CTX="$(grep -c "createGLContext DONE" "$OUT" || true)"
          SHADERR="$(grep -c "ShaderException" "$OUT" || true)"
          # PASS = the gpu plugin became the active renderer (isGpu=true) AND produced frames. A ShaderException
          # count >0 is a NOTE not a fail: the enabledByDefault auto-start compiles HYBRID before RouteCGpuStarter
          # forces LINEAR; it recovers (isGpu goes true). Only a never-recovering failure (active=0/frames=0) is RED.
          [ "$SHADERR" -gt 0 ] && echo ">> NOTE: $SHADERR transient HYBRID ShaderException(s) before LINEAR recovery"
          echo ">> isGpu-active=$ACTIVE  createGLContext=$CTX  ShaderException=$SHADERR  frames=$N"
          if [ "$ACTIVE" -ge 1 ] && [ "$N" -ge 1 ]; then echo ">> VERDICT=AG2_DEVICE_PASS (base gpu renders on the real driver)"; else echo ">> VERDICT=AG2_DEVICE_NOT_GREEN (see logcat $OUT)" >&2; exit 1; fi ;;
  *) echo "unsupported mode for Firebase: $MODE (census is emulator/BrowserStack only)" >&2; exit 2 ;;
esac
