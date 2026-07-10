#!/usr/bin/env bash
# publish-rolling-release.sh — publish the built artifacts as a rolling prerelease on the PUBLIC repo.
#
# The build runs on the PRIVATE origin (audaro/Rune-Mobile) where merges land; this step cross-publishes the
# resulting APK/IPA/jit-script to a single, always-latest "latest-master" prerelease on the SEPARATE public
# repo (audaro/runemobile), replacing the previous build's assets. Because the public repo is a periodically
# force-pushed scrubbed snapshot, its SOURCE can lag these binaries — so the release body records the exact
# origin commit the binaries were built from, for traceability.
#
# Auth: needs a token with contents:write on $PUBLISH_REPO, provided as $PUBLISH_TOKEN (preferred) or
# $GH_TOKEN. This is the ONE piece of key material the pipeline needs from outside $HOME; store it as the
# GitHub Actions secret PUBLISH_TOKEN on the private origin repo (see tools/ci/README.md). The token is never
# written to disk or the repo.
#
# Usage:
#   PUBLISH_TOKEN=<pat> tools/ci/publish-rolling-release.sh [DIST_DIR]
#
# Env knobs:
#   PUBLISH_REPO   target repo (default: audaro/runemobile)
#   RELEASE_TAG    rolling tag (default: latest-master)
#   RELEASE_TITLE  release title (default: "RuneMobile (latest master)")
#   PRERELEASE     1 = mark prerelease (default: 1)
#   BUILD_DATE     human timestamp for the notes (default: `date -u`)
set -euo pipefail

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
DIST="${1:-${DIST:-$ROOT/dist/ci}}"
PUBLISH_REPO="${PUBLISH_REPO:-audaro/runemobile}"
RELEASE_TAG="${RELEASE_TAG:-latest-master}"
RELEASE_TITLE="${RELEASE_TITLE:-RuneMobile (latest master)}"
PRERELEASE="${PRERELEASE:-1}"
BUILD_DATE="${BUILD_DATE:-$(date -u '+%Y-%m-%d %H:%M UTC')}"

die() { printf '!! %s\n' "$*" >&2; exit 1; }

[ -f "$DIST/manifest.env" ] || die "no manifest.env in $DIST — run build-release-artifacts.sh first"
# shellcheck disable=SC1091
. "$DIST/manifest.env"

export GH_TOKEN="${PUBLISH_TOKEN:-${GH_TOKEN:-}}"
[ -n "$GH_TOKEN" ] || die "no PUBLISH_TOKEN / GH_TOKEN — need a token with contents:write on $PUBLISH_REPO"
command -v gh >/dev/null || die "gh CLI not found"

# Collect the assets that exist (Android-only runs won't have an .ipa).
ASSETS=()
for f in "RuneMobile-${VERSION}.apk" "RuneMobile-${VERSION}.ipa" "runelite-jit.js"; do
	[ -f "$DIST/$f" ] && ASSETS+=("$DIST/$f")
done
[ "${#ASSETS[@]}" -gt 0 ] || die "no assets found in $DIST for version $VERSION"

FP_LINE=""
[ -n "${APK_FINGERPRINT:-}" ] && FP_LINE=$'\nAPK signer SHA-256: `'"${APK_FINGERPRINT}"'`'

NOTES="Rolling build of RuneMobile from the latest \`master\`.

- Version: \`${VERSION}\`
- Built from origin commit: \`${GIT_SHA}\`
- Built: ${BUILD_DATE}${FP_LINE}

This is an automated pre-release that is **replaced on every merge to master** — the assets always reflect the
newest build. For a stable, curated build use a tagged \`v*\` release instead.

Install instructions are in the [README](../../blob/master/README.md). RuneMobile is an unofficial, self-built
client and is not on the approved-client list for live Old School RuneScape — use a throwaway account."

# Rolling release: delete the old one (and its tag, so it re-points at current master) then recreate.
echo ">> publishing $RELEASE_TAG to $PUBLISH_REPO (${#ASSETS[@]} assets, version $VERSION)"
gh release delete "$RELEASE_TAG" --repo "$PUBLISH_REPO" --yes --cleanup-tag 2>/dev/null || true

PRE_FLAG=(); [ "$PRERELEASE" = 1 ] && PRE_FLAG=(--prerelease)
gh release create "$RELEASE_TAG" \
	--repo "$PUBLISH_REPO" \
	--target master \
	"${PRE_FLAG[@]}" \
	--title "$RELEASE_TITLE" \
	--notes "$NOTES" \
	"${ASSETS[@]}"

echo ">> published: https://github.com/$PUBLISH_REPO/releases/tag/$RELEASE_TAG"
