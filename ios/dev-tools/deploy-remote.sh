#!/usr/bin/env bash
# deploy-remote.sh — ONE COMMAND from the dev Mac: build jars here, ship the tree over Tailscale,
# build + install on the remote Mac that's tethered to the iPhone.
#
#   ios/dev-tools/deploy-remote.sh                 # full: build jars locally, sync, remote xcodebuild+install
#   ios/dev-tools/deploy-remote.sh --no-jars       # skip local jar build (reuse what's already staged)
#   ios/dev-tools/deploy-remote.sh --remote-jars   # build the jars on the remote instead of locally
#
# Config: copy deploy-remote.conf.example -> deploy-remote.conf and fill it in (gitignored).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CONF="$HERE/deploy-remote.conf"
[ -f "$CONF" ] || { echo "!! missing $CONF — copy deploy-remote.conf.example and fill it in" >&2; exit 1; }
# shellcheck disable=SC1090
. "$CONF"

: "${REMOTE_HOST:?set REMOTE_HOST in deploy-remote.conf (tailnet name, e.g. jordys-mac-mini)}"
: "${REMOTE_USER:?set REMOTE_USER in deploy-remote.conf}"
: "${REMOTE_REPO:?set REMOTE_REPO in deploy-remote.conf (repo path on the remote Mac)}"
: "${DEV_UUID:?set DEV_UUID in deploy-remote.conf (iPhone devicectl UUID as seen by the remote Mac)}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
MODE="${MODE:-gpu}"
REMOTE="$REMOTE_USER@$REMOTE_HOST"

BUILD_JARS=local           # local | remote | none
for a in "$@"; do
  case "$a" in
    --no-jars)     BUILD_JARS=none ;;
    --remote-jars) BUILD_JARS=remote ;;
  esac
done

log() { printf '\n\033[1;35m>> %s\033[0m\n' "$*"; }
cd "$REPO"
VERSION="$(grep -E '^runeLiteVersion' gradle.properties 2>/dev/null | sed 's/.*=//' | tr -d ' ' || true)"
VERSION="${VERSION:-1.12.29}"

# 1) Build the jars on the dev Mac (fast box, no Xcode needed).
if [ "$BUILD_JARS" = local ]; then
  log "Local jar build (MODE=$MODE, VERSION=$VERSION)"
  JAVA_HOME="$JAVA_HOME" ./gradlew :client:shadowJar
  if [ "$MODE" = gpu ]; then
    ( cd ios/dev-tools/gpu-routec && JAVA_HOME="$JAVA_HOME" ./stage-ios-gpu-jar.sh )
  else
    cp "runelite-client/build/libs/client-${VERSION}-SNAPSHOT-shaded.jar" ios/app/jvm-resources/
  fi
  ( cd ios/app && JAVA_HOME="$JAVA_HOME" ./build-probes.sh )
  ( cd ios/dev-tools/gpu-routec/l1 && JAVA_HOME="$JAVA_HOME" ./build-l1-probe.sh )
fi

# 2) Sync the tree over Tailscale. Carries the staged jars + the gitignored JRE/tokens (git doesn't).
#    Excludes are regenerable on the remote (gradle/xcode outputs, xcodegen project, logs).
log "rsync -> $REMOTE:$REMOTE_REPO"
ssh "$REMOTE" "mkdir -p '$REMOTE_REPO'"
rsync -az --delete --info=progress2 \
  --exclude '.git/' \
  --exclude '.DS_Store' \
  --exclude 'build/' \
  --exclude 'dd-*/' \
  --exclude 'DerivedData/' \
  --exclude '*.log' \
  --exclude 'ios/app/evidence/' \
  --exclude 'ios/dev-tools/headless/out-*/' \
  --exclude 'ios/app/*.xcodeproj/' \
  --exclude 'ios/dev-tools/deploy-remote.conf' \
  ./ "$REMOTE:$REMOTE_REPO/"

# 3) Build + install on the remote, streaming output back.
RB_ARGS=""; [ "$BUILD_JARS" = remote ] && RB_ARGS="--with-jars"
log "Remote build + install on $REMOTE_HOST (device $DEV_UUID)"
ssh -t "$REMOTE" \
  "cd '$REMOTE_REPO' && DEV='$DEV_UUID' MODE='$MODE' JAVA_HOME='$JAVA_HOME' REPO='$REMOTE_REPO' bash ios/dev-tools/remote-build.sh $RB_ARGS"

log "Deployed. Phone: attach StikDebug, then launch HelloShell for per-launch JIT."
