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

# Assets are uploaded under STABLE names (RuneMobile-latest-master.{apk,ipa}) so the "latest" release has stable
# download URLs and a --clobber upload cleanly replaces the previous build in place (versioned names would
# accumulate). The precise version lives inside the artifacts and in the notes below.
STAGE="$DIST/publish-stage"; rm -rf "$STAGE"; mkdir -p "$STAGE"
ASSETS=()
[ -f "$DIST/RuneMobile-${VERSION}.apk" ] && { cp "$DIST/RuneMobile-${VERSION}.apk" "$STAGE/RuneMobile-latest-master.apk"; ASSETS+=("$STAGE/RuneMobile-latest-master.apk"); }
[ -f "$DIST/RuneMobile-${VERSION}.ipa" ] && { cp "$DIST/RuneMobile-${VERSION}.ipa" "$STAGE/RuneMobile-latest-master.ipa"; ASSETS+=("$STAGE/RuneMobile-latest-master.ipa"); }
[ -f "$DIST/runelite-jit.js" ] && ASSETS+=("$DIST/runelite-jit.js")
[ "${#ASSETS[@]}" -gt 0 ] || die "no assets found in $DIST for version $VERSION"

FP_LINE=""
[ -n "${APK_FINGERPRINT:-}" ] && FP_LINE=$'\nAPK signer SHA-256: `'"${APK_FINGERPRINT}"'` (verify with `apksigner verify --print-certs`)'

# Note on traceability: GIT_SHA is the PRIVATE origin commit and is recorded only as a maintainer build id —
# a public reader cannot view it, and this pre-release does NOT ship matching source (the public source is the
# repo's own scrubbed snapshot, refreshed on a separate manual sweep). So the notes frame it as a build id, not
# an auditable public commit.
NOTES="Rolling build of RuneMobile, rebuilt on every merge to master.

- Version: \`${VERSION}\`
- Build id: \`${GIT_SHA}\` (internal maintainer reference)
- Built: ${BUILD_DATE}${FP_LINE}

This automated pre-release is **replaced on every merge** — the \`RuneMobile-latest-master.*\` assets always
reflect the newest build. For a stable, curated build use a tagged \`v*\` release instead.

Install instructions are in the [README](../../blob/master/README.md). RuneMobile is an unofficial, self-built
client and is not on the approved-client list for live Old School RuneScape — use a throwaway account."

PRE_FLAG=(); [ "$PRERELEASE" = 1 ] && PRE_FLAG=(--prerelease)

# Idempotent upsert (no delete-then-recreate gap): create the release once if it's missing, then replace the
# assets in place and refresh the notes. If any step fails, the previous release + its assets remain intact.
echo ">> upserting $RELEASE_TAG on $PUBLISH_REPO (${#ASSETS[@]} assets, version $VERSION)"
if ! gh release view "$RELEASE_TAG" --repo "$PUBLISH_REPO" >/dev/null 2>&1; then
	gh release create "$RELEASE_TAG" --repo "$PUBLISH_REPO" --target master "${PRE_FLAG[@]}" \
		--title "$RELEASE_TITLE" --notes "$NOTES"
fi
gh release upload "$RELEASE_TAG" --repo "$PUBLISH_REPO" --clobber "${ASSETS[@]}"
gh release edit "$RELEASE_TAG" --repo "$PUBLISH_REPO" "${PRE_FLAG[@]}" --title "$RELEASE_TITLE" --notes "$NOTES"
rm -rf "$STAGE"

echo ">> published: https://github.com/$PUBLISH_REPO/releases/tag/$RELEASE_TAG"
