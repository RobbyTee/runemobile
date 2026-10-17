#!/usr/bin/env bash
# serve-ipa.sh — build the dev IPA, write an AltStore/SideStore source manifest, and serve both over
# Tailscale HTTPS. Add the printed source URL in SideStore ONCE; thereafter each run bumps the version
# and SideStore shows an "Update" you install on-device from ANY network (the install runs over the
# phone's loopback, which iOS allows — see ios/dev-tools/DEPLOY-REMOTE.md for why direct-to-phone can't).
#
#   ios/dev-tools/serve-ipa.sh              # reuse staged jars, build IPA, (re)start the tailnet HTTPS source
#   ios/dev-tools/serve-ipa.sh --jars       # rebuild the jars first (shaded + gpu jar-surgery + probes + l1)
#   ios/dev-tools/serve-ipa.sh --cpu        # with --jars: plain shaded jar instead of the gpu jar-surgery
#   ios/dev-tools/serve-ipa.sh --build-only # build IPA + manifest only; don't touch `tailscale serve`
#   ios/dev-tools/serve-ipa.sh --stop       # tear down: stop the static server + `tailscale serve reset`
#
# ONE-TIME on the phone: install SideStore, then Sources -> + -> add the printed https://<host>.ts.net/apps.json
# EACH deploy: run this, then on the phone (Tailscale ON) open SideStore -> the app shows an Update -> install.
# JIT is unchanged: SideStore dev-signs (get-task-allow), so StikDebug attaches per-launch exactly as before.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
APPDIR="$REPO/ios/app"
WORK="$HERE/.serve-ipa"           # local-only (see .git/info/exclude)
DIST="$WORK/dist"
STATE="$WORK/build.txt"
HTTPD_PID="$WORK/httpd.pid"
DD="$REPO/build/dd-serveipa"
PORT="${PORT:-8787}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

MODE=gpu; BUILD_JARS=0; SERVE=1; STOP=0
for a in "$@"; do case "$a" in
  --jars)       BUILD_JARS=1 ;;
  --cpu)        MODE=cpu ;;
  --build-only) SERVE=0 ;;
  --stop)       STOP=1 ;;
  -h|--help)    sed -n '2,16p' "$0"; exit 0 ;;
  *) echo "unknown arg: $a" >&2; exit 2 ;;
esac; done

log()  { printf '\n\033[1;35m>> %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }
mkdir -p "$WORK"

# The tailscale CLI. The standalone Mac app does not put it on PATH, and a symlink into /usr/local/bin does
# not survive a reinstall, so look inside the app bundle before giving up — the old `command -v` failure
# surfaced as "is Tailscale up?" while the tunnel was up, connected, and serving the phone.
TS="$(command -v tailscale || true)"
if [ -z "$TS" ]; then
  for c in /Applications/Tailscale.app/Contents/MacOS/Tailscale \
           "$HOME/Applications/Tailscale.app/Contents/MacOS/Tailscale"; do
    if [ -x "$c" ]; then TS="$c"; break; fi
  done
fi
[ -n "$TS" ] || die "no tailscale CLI on PATH or in /Applications/Tailscale.app — is Tailscale installed?"

# --- teardown -------------------------------------------------------------------------------------
if [ "$STOP" = 1 ]; then
  [ -f "$HTTPD_PID" ] && kill "$(cat "$HTTPD_PID")" 2>/dev/null && rm -f "$HTTPD_PID" && echo "static server stopped"
  "$TS" serve reset 2>/dev/null && echo "tailscale serve reset" || true
  exit 0
fi

cd "$REPO"
# Local signing config (gitignored): exports DEVELOPMENT_TEAM for xcodegen's ${DEVELOPMENT_TEAM} expansion.
[ -f ios/dev-tools/signing.env ] && . ios/dev-tools/signing.env
FQDN="$("$TS" status --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')" \
  || die "couldn't read this Mac's tailnet name (is Tailscale up?)"
[ -n "$FQDN" ] || die "empty tailnet FQDN"
# QA-P1-15: read the real version key (full version, e.g. 1.12.29-SNAPSHOT). The old grep for the non-existent
# `runeLiteVersion` key silently fell back to a hardcoded 1.12.29 — correct only by luck, broken on the next bump.
JARVER="$(sed -n 's/^project\.build\.version=//p' gradle.properties)"; [ -n "$JARVER" ] || die "could not read project.build.version from gradle.properties"
MARKETING="$(grep -E 'MARKETING_VERSION:' "$APPDIR/project.yml" 2>/dev/null | head -1 | sed -E 's/.*: *"?([0-9.]+)"?.*/\1/' || true)"; MARKETING="${MARKETING:-0.1}"
BUILD="$(( $(cat "$STATE" 2>/dev/null || echo 0) + 1 ))"
VERSTR="${MARKETING}.${BUILD}"
# The counter is gitignored, so a fresh clone / cleaned .serve-ipa silently restarts at 1 — and a version
# LOWER than the one already on the phone is not an update, so SideStore just shows nothing to do and the
# build looks installed when it isn't. Say so rather than let it look like a successful publish.
if [ ! -f "$STATE" ]; then
  printf '\033[1;33m!! no build counter at %s — starting at %s. If the phone has a HIGHER build already (an\n   earlier worktree, or the hosted release channel), SideStore will not offer this as an update.\n   Bump it first:  echo <n> > %s\033[0m\n' "$STATE" "$BUILD" "$STATE" >&2
fi

# 1) jars (default: reuse what's staged; --jars rebuilds the same set deploy-remote.sh does) ---------
if [ "$BUILD_JARS" = 1 ]; then
  log "Rebuild jars (MODE=$MODE, VERSION=$JARVER)"
  JAVA_HOME="$JAVA_HOME" ./gradlew :client:shadowJar
  if [ "$MODE" = gpu ]; then ( cd ios/dev-tools/gpu-routec && JAVA_HOME="$JAVA_HOME" ./stage-ios-gpu-jar.sh )
  else cp "runelite-client/build/libs/client-${JARVER}-shaded.jar" ios/app/jvm-resources/client-shaded.jar; fi
  ( cd ios/dev-tools/gpu-zone && JAVA_HOME="$JAVA_HOME" ./stage-ios-zone-jar.sh )
  ( cd ios/app && JAVA_HOME="$JAVA_HOME" ./build-probes.sh )
  ( cd ios/dev-tools/gpu-routec/l1 && JAVA_HOME="$JAVA_HOME" ./build-l1-probe.sh )
fi
for j in client-shaded.jar client-rlhd.jar probes.jar routec-l1.jar; do
  [ -f "$APPDIR/jvm-resources/$j" ] || die "staged jar missing: $j — re-run with --jars"
done

# 2) regenerate the xcodeproj + build a device .app (no device needed; signed dev — SideStore resigns) -
log "xcodegen generate"
( cd "$APPDIR" && xcodegen generate >/dev/null )

# Build configuration. Default Debug (today's behaviour). CONFIG=Release builds the hardened daily-driver
# (QA-P1-12/13): Documents file-sharing off, log/capture sinks compiled out, .runelite in App Support. NOTE:
# StikDebug attach / JIT under Release is device-unverified — confirm on-device before relying on it.
CONFIG="${CONFIG:-Debug}"
case "$CONFIG" in Debug|Release) ;; *) die "CONFIG must be Debug or Release (got '$CONFIG')" ;; esac
log "xcodebuild RuneMobile $VERSTR ($CONFIG, generic/iOS, signed)"
xcodebuild -project "$APPDIR/RuneMobile.xcodeproj" -scheme RuneMobile -configuration "$CONFIG" \
  -destination 'generic/platform=iOS' -derivedDataPath "$DD" -allowProvisioningUpdates \
  MARKETING_VERSION="$VERSTR" CURRENT_PROJECT_VERSION="$BUILD" build

APP="$DD/Build/Products/${CONFIG}-iphoneos/RuneMobile.app"
[ -d "$APP" ] || die "build produced no app at $APP"

# 3) package Payload/*.app -> IPA ------------------------------------------------------------------
log "package IPA"
rm -rf "$DIST/Payload"; mkdir -p "$DIST/Payload"
cp -R "$APP" "$DIST/Payload/"
# Info.plist hardcodes the version, so MARKETING_VERSION on the build line is ignored — stamp it here
# instead so the installed app's CFBundleShortVersionString matches the manifest (SideStore resigns,
# so touching the bundle's signature is harmless).
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSTR" -c "Set :CFBundleVersion $BUILD" \
  "$DIST/Payload/RuneMobile.app/Info.plist" >/dev/null
( cd "$DIST" && rm -f RuneLite.ipa && zip -qry RuneLite.ipa Payload && rm -rf Payload )
SIZE="$(stat -f%z "$DIST/RuneLite.ipa")"
echo "$BUILD" > "$STATE"   # only burn the build number on success

# 4) icon + AltStore/SideStore source manifest ----------------------------------------------------
python3 - "$DIST" "$FQDN" "$VERSTR" "$SIZE" "$BUILD" <<'PY'
import sys, struct, zlib, json, datetime, os
dist, fqdn, ver, size, build = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
def solid_png(path, w=256, h=256, rgb=(139,0,0)):
    row = b"\x00" + bytes(rgb + (255,)) * w
    raw = row * h
    def ck(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    png = b"\x89PNG\r\n\x1a\n" + ck(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) \
          + ck(b"IDAT", zlib.compress(raw, 9)) + ck(b"IEND", b"")
    open(path, "wb").write(png)
solid_png(os.path.join(dist, "icon.png"))
base = "https://" + fqdn
manifest = {
  "name": "RuneLite iOS (dev)",
  "identifier": "dev.audaro.source",
  "sourceURL": base + "/apps.json",
  "apps": [{
    "name": "RuneLite",
    "bundleIdentifier": "dev.audaro.runemobile",
    "developerName": "Jordan Simpson",
    "subtitle": "RuneLite on iOS (on-device JVM)",
    "version": ver,
    "versionDate": datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "versionDescription": "Dev build " + ver,
    "downloadURL": base + "/RuneLite.ipa",
    "localizedDescription": "RuneLite on iOS via an on-device JVM. Personal dev build #" + build + ".",
    "iconURL": base + "/icon.png",
    "tintColor": "8b0000",
    "size": size,
    "permissions": []
  }]
}
open(os.path.join(dist, "apps.json"), "w").write(json.dumps(manifest, indent=2))
PY

# 5) serve dist over the tailnet via HTTPS (local static server + tailscale serve port-proxy) ------
if [ "$SERVE" = 1 ]; then
  log "serve over Tailscale HTTPS"
  [ -f "$HTTPD_PID" ] && kill "$(cat "$HTTPD_PID")" 2>/dev/null || true
  # range/resume-capable static server (stdlib http.server ignores Range -> a flaky phone tunnel can't resume)
  nohup python3 "$HERE/serve-static.py" "$DIST" "$PORT" >"$WORK/httpd.log" 2>&1 &
  echo $! > "$HTTPD_PID"; disown 2>/dev/null || true
  sleep 1
  "$TS" serve --bg "$PORT" >/dev/null
fi

printf '\n\033[1;32m=== built RuneLite %s (%d MB) ===\033[0m\n' "$VERSTR" "$(( SIZE / 1048576 ))"
printf '  IPA      %s\n' "$DIST/RuneLite.ipa"
printf '  manifest %s\n' "$DIST/apps.json"
if [ "$SERVE" = 1 ]; then
  printf '  source   https://%s/apps.json   <- add this in SideStore -> Sources (once)\n' "$FQDN"
  printf '  download https://%s/RuneLite.ipa\n' "$FQDN"
  printf '\nStatic server backgrounded (pid %s); stop with: %s --stop\n' "$(cat "$HTTPD_PID")" "$0"
fi
printf '\nPhone (Tailscale ON): open SideStore -> RuneLite shows version %s -> Update/Install.\n' "$VERSTR"
printf 'Then attach StikDebug and launch for per-launch JIT (unchanged).\n'
