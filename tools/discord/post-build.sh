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

# An explicit CI_WEBHOOK in the environment wins; only fall back to the secrets
# file when it is unset, so callers can override it.
if [ -z "${CI_WEBHOOK:-}" ]; then
  CONF="$HOME/.runemobile-discord/webhooks.env"
  # shellcheck disable=SC1090  # runtime-only secrets file, intentionally not shipped in-repo
  [ -f "$CONF" ] && . "$CONF"
fi
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

# Best-effort: a Discord hiccup must never fail the build. Retry on 429 (rate
# limit) up to a few times honouring Retry-After, then give up with a warning.
hdrs="$(mktemp)"; body="$(mktemp)"
trap 'rm -f "$hdrs" "$body"' EXIT
for attempt in 1 2 3 4; do
  code="$(curl -sS -o "$body" -D "$hdrs" -w '%{http_code}' \
    -H "Content-Type: application/json" -X POST -d "$PAYLOAD" "$CI_WEBHOOK" || echo 000)"
  case "$code" in
    2*)
      echo "post-build: posted build $VERSION to #ci-builds."
      exit 0 ;;
    429)
      retry="$(awk 'tolower($0) ~ /^retry-after:/ {print $2}' "$hdrs" | tr -dc '0-9.')"
      [ -z "$retry" ] && retry=1
      echo "post-build: rate-limited (attempt $attempt), retrying in ${retry}s." >&2
      sleep "$retry" ;;
    *)
      echo "post-build: Discord returned HTTP $code — skipping notice (build unaffected)." >&2
      exit 0 ;;
  esac
done
echo "post-build: still rate-limited after retries — giving up (build unaffected)." >&2
exit 0
