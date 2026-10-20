#!/usr/bin/env bash
# Point this clone at the versioned hooks (run once per machine/clone).
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" config core.hooksPath tools/git-hooks
chmod +x "$ROOT"/tools/git-hooks/pre-commit "$ROOT"/tools/git-hooks/commit-msg "$ROOT"/tools/git-hooks/attribution-scan.sh 2>/dev/null || true
chmod +x "$ROOT"/tools/shared-contract/*.sh "$ROOT"/tools/shared-contract/*.py 2>/dev/null || true
chmod +x "$ROOT"/tools/lint/*.sh 2>/dev/null || true
echo "core.hooksPath = tools/git-hooks  (hygiene + cross-port contract guards active)"
list="${RUNEMOBILE_ATTRIBUTION_DENYLIST:-${XDG_CONFIG_HOME:-$HOME/.config}/rune-mobile/attribution-denylist}"
[ -r "$list" ] || echo "note: no attribution denylist at $list -- that guard is a no-op until one exists"
