#!/usr/bin/env bash
# publish-stable-release.sh — publish a STABLE release, with the AltStore feed attached and verified.
#
# WHY THIS EXISTS: the AltStore source is served from /releases/latest/download/altstore.json, which resolves
# to whichever release is Latest. Publish a stable release without that asset and the feed silently keeps
# serving the PREVIOUS version's manifest -- every AltStore user is told they are up to date while pinned to
# an old build. That is the exact failure the feed was added to prevent, so it cannot be a checklist item:
# this script refuses to publish without the manifest and re-reads the live feed afterwards to prove it took.
#
# Usage: tools/ci/publish-stable-release.sh <tag> [--notes-file <path>] [--dist <dir>]
set -euo pipefail

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
REPO="${PUBLISH_REPO:-audaro/runemobile}"
DIST="${DIST:-$ROOT/dist/ci}"
TAG=""; NOTES=""

while [ $# -gt 0 ]; do
	case "$1" in
		--notes-file) NOTES="$2"; shift 2 ;;
		--dist)       DIST="$2"; shift 2 ;;
		-*)           echo "unknown flag: $1" >&2; exit 2 ;;
		*)            TAG="$1"; shift ;;
	esac
done
die() { echo "publish-stable: $*" >&2; exit 1; }

[ -n "$TAG" ] || die "usage: publish-stable-release.sh <tag> [--notes-file F]"
[ -f "$DIST/manifest.env" ] || die "no $DIST/manifest.env — build the artifacts first"
# shellcheck disable=SC1090
. "$DIST/manifest.env"
[ -n "${VERSION:-}" ] || die "VERSION missing from manifest.env"

# The feed addresses the IPA by tag, so a tag that disagrees with the built version yields a manifest whose
# downloadURL 404s. Catch it here rather than after everyone's AltStore has cached it.
[ "$TAG" = "v$VERSION" ] || die "tag '$TAG' does not match built version '$VERSION' (expected v$VERSION)"

APK="$DIST/RuneMobile-$VERSION.apk"
IPA="$DIST/RuneMobile-$VERSION.ipa"
FEED="$DIST/altstore.json"
[ -f "$APK" ] || die "missing $APK"
[ -f "$IPA" ] || die "missing $IPA"
[ -f "$FEED" ] || RELEASE_TAG="$TAG" "$ROOT/tools/ci/make-altstore-source.sh" "$DIST"

# The manifest must describe THIS release, not a leftover from the previous build sitting in dist/.
python3 - "$FEED" "$VERSION" "$TAG" "$REPO" <<'PY' || die "altstore.json does not describe $TAG — rerun make-altstore-source.sh"
import json, sys
feed, ver, tag, repo = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
a = json.load(open(feed))["apps"][0]
want = f"https://github.com/{repo}/releases/download/{tag}/RuneMobile-{ver}.ipa"
assert a["version"] == ver, f"feed version {a['version']} != {ver}"
assert a["downloadURL"] == want, f"feed downloadURL {a['downloadURL']} != {want}"
assert a["versions"][0]["version"] == ver, "versions[0] disagrees with the legacy top-level version"
PY

ASSETS=("$APK" "$IPA" "$FEED")
[ -f "$DIST/runelite-jit.js" ] && ASSETS+=("$DIST/runelite-jit.js")

NEW_RELEASE=0
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
	echo ">> $TAG exists — uploading assets and marking latest"
	gh release upload "$TAG" "${ASSETS[@]}" --repo "$REPO" --clobber
	gh release edit "$TAG" --repo "$REPO" --latest --prerelease=false
else
	NEW_RELEASE=1
	echo ">> creating $TAG on $REPO with ${#ASSETS[@]} assets"
	if [ -n "$NOTES" ]; then
		gh release create "$TAG" "${ASSETS[@]}" --repo "$REPO" --title "RuneMobile $VERSION" --notes-file "$NOTES" --latest
	else
		gh release create "$TAG" "${ASSETS[@]}" --repo "$REPO" --title "RuneMobile $VERSION" --generate-notes --latest
	fi
fi

# Prove the feed users actually fetch now serves THIS version. Publishing "succeeded" while /latest/ still
# returns the old manifest is the silent failure this whole script exists to make impossible.
echo ">> verifying the live feed"
LIVE=$(curl -fsSL "https://github.com/$REPO/releases/latest/download/altstore.json") \
	|| die "published, but /releases/latest/download/altstore.json does not resolve"
printf '%s' "$LIVE" | python3 -c "
import json, sys
v = json.load(sys.stdin)['apps'][0]['version']
want = sys.argv[1]
sys.exit(0) if v == want else sys.exit(f'live feed serves {v}, expected {want}')
" "$VERSION" || die "the live feed is stale — users would be told they are up to date on an older build"
echo ">> OK — $TAG is Latest and the feed serves $VERSION"

# Announce it in Discord, here rather than by hand afterwards: at this point the release is Latest and the
# feed has been proven to serve it, so the announcement cannot point people at something they can't install.
# Only for a release this run created — re-uploading an asset to an existing tag must not re-announce it.
# The Discord tooling is a local-only checkout (tools/discord/ is in .git/info/exclude, so it is absent from
# a fresh clone and from the public mirror); publishing must work exactly the same without it.
ANNOUNCE="$ROOT/tools/discord/post-release.sh"
if [ ! -x "$ANNOUNCE" ]; then
	echo ">> no local Discord tooling — skipping the announcement"
elif [ "$NEW_RELEASE" = 1 ] || [ -n "${FORCE_ANNOUNCE:-}" ]; then
	"$ANNOUNCE" "$VERSION" "https://github.com/$REPO/releases/tag/$TAG" "$NOTES" || true
else
	echo ">> $TAG already existed — not re-announcing (FORCE_ANNOUNCE=1 to override)"
fi
