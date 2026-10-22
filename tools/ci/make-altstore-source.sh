#!/usr/bin/env bash
# make-altstore-source.sh — emit the AltStore/SideStore source manifest for the built IPA.
#
# WHY: without a source, every iOS update means hunting the releases page for an IPA and sideloading it by
# hand. That is not just friction — an AltStore "refresh" RE-SIGNS the app already installed without changing
# its version, so a user who refreshes believes they updated and did not. A source makes AltStore show the
# real version and install the right build in one tap.
#
# URLs: the manifest is served from the LATEST STABLE release (/releases/latest/download/ resolves to the
# newest NON-prerelease), so the rolling per-merge prereleases never hijack the feed. The IPA is addressed by
# its immutable tag instead, so a cached manifest can never point at a moved asset.
#
# Usage: tools/ci/make-altstore-source.sh [DIST_DIR]
# Env:   PUBLISH_REPO (default audaro/runemobile), RELEASE_TAG (default v<VERSION>)
set -euo pipefail

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
DIST="${1:-${DIST:-$ROOT/dist/ci}}"
REPO="${PUBLISH_REPO:-audaro/runemobile}"
MANIFEST="$DIST/manifest.env"

[ -f "$MANIFEST" ] || { echo "make-altstore-source: no $MANIFEST — build the artifacts first" >&2; exit 1; }
# shellcheck disable=SC1090
. "$MANIFEST"
[ -n "${VERSION:-}" ] || { echo "make-altstore-source: VERSION missing from $MANIFEST" >&2; exit 1; }

IPA="$DIST/RuneMobile-$VERSION.ipa"
[ -f "$IPA" ] || { echo "make-altstore-source: no $IPA — the manifest would advertise a build nobody can download" >&2; exit 1; }

TAG="${RELEASE_TAG:-v$VERSION}"
SIZE=$(/usr/bin/stat -f%z "$IPA")
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# "What's new" = this version's CHANGELOG section, so AltStore shows the same notes as the release page.
NOTES=$(awk -v v="## [$VERSION]" '
  index($0, v) == 1 {grab=1; next}
  grab && /^## \[/ {exit}
  grab {print}
' "$ROOT/CHANGELOG.md" | sed -e 's/^### //' -e '/^$/d' | head -20)
[ -n "$NOTES" ] || NOTES="See the release notes for $VERSION."

VERSIONS_DESC="$NOTES" VER="$VERSION" TAG="$TAG" REPO="$REPO" SIZE="$SIZE" DATE="$DATE" \
python3 - > "$DIST/altstore.json" <<'PY'
import json, os
ver, tag, repo = os.environ["VER"], os.environ["TAG"], os.environ["REPO"]
dl = f"https://github.com/{repo}/releases/download/{tag}/RuneMobile-{ver}.ipa"
entry = {
    "version": ver,
    "date": os.environ["DATE"],
    "localizedDescription": os.environ["VERSIONS_DESC"],
    "downloadURL": dl,
    "size": int(os.environ["SIZE"]),
    "minOSVersion": "17.0",
}
app = {
    "name": "RuneMobile",
    "bundleIdentifier": "dev.audaro.runemobile",
    "developerName": "Jordan Simpson",
    "subtitle": "Old School RuneScape with the RuneLite plugin ecosystem.",
    "localizedDescription": (
        "RuneMobile runs the real RuneLite client on a JIT-enabled OpenJDK on your device, with the plugin "
        "ecosystem unmodified and a touch interface built for phones.\n\n"
        "JIT must be attached with StikDebug at each launch."
    ),
    "iconURL": f"https://raw.githubusercontent.com/{repo}/master/docs/brand/runemobile-icon.png",
    "tintColor": "E6B14C",
    "screenshotURLs": [],
    "versions": [entry],
    # Legacy top-level fields: older AltStore builds read these instead of versions[].
    "version": ver,
    "versionDate": entry["date"],
    "versionDescription": entry["localizedDescription"],
    "downloadURL": dl,
    "size": entry["size"],
}
src = {
    "name": "RuneMobile",
    "identifier": "dev.audaro.runemobile.source",
    "subtitle": "Old School RuneScape with RuneLite plugins, on iOS.",
    "sourceURL": f"https://github.com/{repo}/releases/latest/download/altstore.json",
    "iconURL": app["iconURL"],
    "tintColor": "E6B14C",
    "apps": [app],
    "news": [],
}
print(json.dumps(src, indent=2))
PY

python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$DIST/altstore.json"
echo "make-altstore-source: wrote $DIST/altstore.json (version $VERSION, tag $TAG, ipa ${SIZE} bytes)"
