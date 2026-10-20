# GAP-B-2 — RuneLite api/hub version-pin review ledger.
#
# runelite.properties intentionally pins runelite.pluginhub.version + runelite.api.base to a version that
# LAGS gradle.properties' project.build.version. The pin exists because a newer minor's api path 404s
# (commit a310e583c8: "1.12.29 never released -> 404"). `git merge upstream/master` never rewrites our pin,
# so without this ledger the stale pin rides every future merge forward invisibly.
#
# check-hub-pin.sh fails the release gate when project.build.version advances past reviewed_project_version.
# When that happens: verify whether https://api.runelite.net/runelite-<newver> and
# repo.runelite.net/plugins/<newver> now resolve. If they do, lift the pin back to ${project.version} in
# runelite.properties; if they still 404, keep the pin. Either way, bump reviewed_project_version to re-arm.
#
# 2026-07-01 review at project 1.12.31: probed the newer api/hub paths; could not confirm they resolve
# (inconclusive from outside the client's exact request shape), so the known-good pin is RETAINED.
#
# 2026-10-15 review at project 1.13.2, probed with the client's exact URL shapes this time:
#   repo.runelite.net/plugins/manifest/<v>_full.js   1.12.28 200 | 1.13.0 200 | 1.13.1 200 | 1.13.2 200
#                                                    1.13.2-SNAPSHOT 404  <- why a release pin is required
#   api.runelite.net/runelite-<v>/pluginhub (POST)   1.12.28 404 | 1.13.1 200 | 1.13.2 200 | 1.13.2-SNAPSHOT 200
# The old assumption has INVERTED: it is 1.12.28 that 404s now, so the api.base pin was the broken one, and
# the upstream merge that reverted it to ${project.version} repaired it by accident. api.base therefore stays
# on ${project.version} and the gate checks for that instead of requiring the two pins to match.
# Hub pin bumped 1.12.28 -> 1.13.1: the release our tree is based on (tag runelite-parent-1.13.1). 1.13.2 also
# answers, but it is a release we have NOT merged -- a plugin built against it can call API this snapshot does
# not have, while 1.13.1's is certainly present.
#
# 2026-10-20 review at project 1.13.4-SNAPSHOT (after merging upstream through 330e6507cf, which carries
# Release 1.13.2 and Release 1.13.3), probed with the client's exact URL shapes:
#   repo.runelite.net/plugins/manifest/<v>_full.js   1.13.1 200 | 1.13.2 200 | 1.13.3 200 | 1.13.4 404
#   api.runelite.net/runelite-<v>/pluginhub (POST)   1.13.1 200 | 1.13.3 200 | 1.13.4 404 | 1.13.4-SNAPSHOT 200
# api.base stays on ${project.version}: the bare 1.13.4 path 404s, but the -SNAPSHOT path the client actually
# requests answers 200, so the pin is correct as-is. The hub pin is RETAINED at 1.13.1 for this merge.
# Hub pin bumped 1.13.1 -> 1.13.3 later the same day, as its own step rather than folded into the merge.
# By the rule in the 2026-10-15 entry it is the newest release this tree has MERGED, so its API is certainly
# present in this 1.13.4-SNAPSHOT client. Re-probed at the bump -- these are TWO different services, so do
# not read one service's codes against the other's:
#   repo.runelite.net/plugins/manifest/<v>_full.js   1.13.3 200 | 1.13.4 404 | 1.13.4-SNAPSHOT 404
#   api.runelite.net/runelite-<v>/pluginhub (POST)   1.13.3 200 | 1.13.4-SNAPSHOT 200
# The hub's 404 for -SNAPSHOT is EXPECTED -- it is the whole reason the hub needs a RELEASE pin -- and says
# nothing about api.base, which the client requests at the -SNAPSHOT path and which answers 200. So the hub
# gets a pin and api.base stays on ${project.version}; one 404 does not implicate the other service.
# This changes which plugin builds load on device, so it was held for a device check: VERIFIED 2026-10-20
# on iPhone 17 Pro Max / iOS 27.2, debug build of 74b8444e66 -- hub catalog lists, a plugin installs and
# enables, GPU renderer still boots (owner-confirmed on the handset, not an automated check). Clear to ship.
reviewed_project_version=1.13.4
pinned_hub_version=1.13.3
