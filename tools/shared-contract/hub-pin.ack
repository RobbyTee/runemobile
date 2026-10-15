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
reviewed_project_version=1.13.2
pinned_hub_version=1.13.1
