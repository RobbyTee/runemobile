#!/usr/bin/env bash
# Post a CI build notice to the #ci-builds Discord channel via webhook.
#
# Reads CI_WEBHOOK from the environment, or from ~/.runemobile-discord/webhooks.env
# (written by provision-server.py --save-webhooks). No-ops quietly if unset, so it
# is safe to call unconditionally from the release workflow.
#
# Usage:
#   tools/discord/post-build.sh <version> [release-url] [notes]
#
# Example (from the release workflow, after a successful publish):
#   tools/discord/post-build.sh "$VERSION" "$RELEASE_URL" "merge ${GITHUB_SHA:0:7}"
set -euo pipefail

CONF="$HOME/.runemobile-discord/webhooks.env"
# shellcheck disable=SC1090  # runtime-only secrets file, intentionally not shipped in-repo
[ -f "$CONF" ] && . "$CONF"
: "${CI_WEBHOOK:=}"

if [ -z "$CI_WEBHOOK" ]; then
  echo "post-build: CI_WEBHOOK not set — skipping Discord notice." >&2
  exit 0
fi

VERSION="${1:?usage: post-build.sh <version> [release-url] [notes]}"
URL="${2:-}"
NOTES="${3:-}"

DESC="A new signed build is available."
[ -n "$NOTES" ] && DESC="$NOTES"
[ -n "$URL" ] && DESC="$DESC"$'\n'"$URL"

# Build the embed JSON with jq if present (safe escaping); else a minimal fallback.
if command -v jq >/dev/null 2>&1; then
  PAYLOAD=$(jq -n --arg t "🟢 Build $VERSION" --arg d "$DESC" \
    '{embeds:[{title:$t,description:$d,color:3066993}]}')
else
  # Best-effort escaping for the no-jq case.
  esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' ' '; }
  PAYLOAD="{\"embeds\":[{\"title\":\"🟢 Build $(esc "$VERSION")\",\"description\":\"$(esc "$DESC")\",\"color\":3066993}]}"
fi

curl -sS -f -H "Content-Type: application/json" -X POST -d "$PAYLOAD" "$CI_WEBHOOK" >/dev/null \
  && echo "post-build: posted build $VERSION to #ci-builds."
