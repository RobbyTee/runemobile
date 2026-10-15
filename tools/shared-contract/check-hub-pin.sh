#!/usr/bin/env bash
# GAP-B-2 tripwire — force a conscious review of the RuneLite api/hub version pin on upstream drift.
#
# runelite.pluginhub.version pins the PLUGIN HUB to a released version, because the hub serves manifests for
# releases only and our client is a -SNAPSHOT (manifest/1.13.2-SNAPSHOT_full.js 404s). runelite.api.base is a
# different question and now follows ${project.version}, which resolves. They were once pinned together to
# 1.12.28 on the theory that a newer minor 404s; by 2026-10-15 that had inverted -- api.runelite.net
# /runelite-1.12.28 404s and the 1.13.x paths answer -- so requiring the two to match is what would break.
#
# Neither is visible to `git merge upstream/master`: the merge silently reverted api.base to ${project.version}
# and left the hub pin stale, which is this tripwire's whole reason for existing. It fails when
# project.build.version advances past the last human-reviewed value in hub-pin.ack, so the gate can't go green
# again until someone re-verifies the pin against the live endpoints.
#
# Exit: 0 = pin reviewed + consistent, 1 = review needed (or the pin/ack drifted apart).
set -euo pipefail

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
PROPS="$ROOT/runelite-client/src/main/resources/net/runelite/client/runelite.properties"
GRADLE_PROPS="$ROOT/gradle.properties"
ACK="$ROOT/tools/shared-contract/hub-pin.ack"

for f in "$PROPS" "$GRADLE_PROPS" "$ACK"; do
	[ -f "$f" ] || { echo "✋ hub-pin: missing $f" >&2; exit 1; }
done

val() { grep -E "^$1=" "$2" | head -1 | cut -d= -f2- | tr -d ' \r'; }

proj="$(val 'project.build.version' "$GRADLE_PROPS" | sed 's/-SNAPSHOT//')"
hub_pin="$(val 'runelite.pluginhub.version' "$PROPS")"
api_pin="$(val 'runelite.api.base' "$PROPS" | sed -E 's#.*/runelite-##')"
reviewed="$(val 'reviewed_project_version' "$ACK")"
acked_pin="$(val 'pinned_hub_version' "$ACK")"

fail=0
if [ -z "$proj" ] || [ -z "$hub_pin" ] || [ -z "$reviewed" ]; then
	echo "✋ hub-pin: could not parse versions (proj='$proj' hub='$hub_pin' reviewed='$reviewed')" >&2
	exit 1
fi

# api.base follows the project version (verified resolving). A literal pinned here is what died silently in
# 2026: 1.12.28's api path now 404s, and nothing in the client says so.
# shellcheck disable=SC2016  # the literal ${project.version} is the wanted value, not something to expand
if [ "$api_pin" != '${project.version}' ]; then
	echo "✋ hub-pin: api.base is pinned to '$api_pin', not \${project.version}." >&2
	echo "   Confirm https://api.runelite.net/runelite-<that>/pluginhub still answers; 1.12.28 no longer does." >&2
	fail=1
fi

# The hub pin must be a release. A -SNAPSHOT has no manifest, so following the client version breaks the hub.
case "$hub_pin" in
	*-SNAPSHOT|"")
		echo "✋ hub-pin: pluginhub.version ('$hub_pin') must be a RELEASED version — the hub has no snapshot manifest." >&2
		fail=1
		;;
esac

# iOS passes the same pin as a -D at launch, which overrides the jar (and guards against an unstaged, pristine
# jar). Two copies can drift, so they are held equal here rather than trusted to stay in step.
IOS_LAUNCHER="$ROOT/ios/app/RuneMobile/JVMLauncher.m"
if [ -f "$IOS_LAUNCHER" ]; then
	ios_pin="$(grep -oE '\-Drunelite\.pluginhub\.version=[0-9][^"]*' "$IOS_LAUNCHER" | head -1 | cut -d= -f2)"
	if [ -n "$ios_pin" ] && [ "$ios_pin" != "$hub_pin" ]; then
		echo "✋ hub-pin: iOS JVMLauncher.m pins $ios_pin but runelite.properties pins $hub_pin." >&2
		echo "   The -D overrides the jar, so iOS would use $ios_pin — change both." >&2
		fail=1
	fi
fi

if [ "$hub_pin" != "$acked_pin" ]; then
	echo "✋ hub-pin: the pin in runelite.properties ($hub_pin) doesn't match hub-pin.ack ($acked_pin)." >&2
	echo "   If you deliberately changed the pin, update pinned_hub_version in tools/shared-contract/hub-pin.ack." >&2
	fail=1
fi

if [ "$proj" != "$reviewed" ]; then
	echo "✋ hub-pin: project.build.version is now $proj but the api/hub pin was last reviewed at $reviewed." >&2
	echo "   RuneLite's api/hub is pinned to $hub_pin (a newer minor historically 404'd — commit a310e583c8)." >&2
	echo "   Verify whether https://api.runelite.net/runelite-$proj and https://repo.runelite.net/plugins/$proj" >&2
	echo "   now resolve for the client's requests. If they do, lift the pin to \${project.version} in" >&2
	echo "   runelite.properties; if they still 404, keep it. Either way, set reviewed_project_version=$proj" >&2
	echo "   in tools/shared-contract/hub-pin.ack to re-arm this tripwire." >&2
	fail=1
fi

if [ "$fail" -eq 0 ]; then
	echo "hub-pin: OK (pin $hub_pin, reviewed at project $reviewed)."
fi
exit "$fail"
