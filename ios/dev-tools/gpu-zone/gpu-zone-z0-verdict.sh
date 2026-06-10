#!/usr/bin/env bash
# GPU Zone Z0 — RLHD zone-shader ES3 conformance gate verdict (md5-gated, anti-fabrication).
#
# Recomputes pass/fail in bash from the VERBATIM device/Simulator-pulled log and pins its md5, so a fabricated or
# edited verdict can't pass (same discipline as gpu-b1-verdict.sh). GREEN = EVERY assembled RLHD zone shader, after
# the ES-3.00 downport (ZoneDownport), COMPILES on a real GLES 3.0 driver with zero FAIL lines.
#
# Usage: gpu-zone-z0-verdict.sh <log> [expected_md5]
# The expected_md5 is pinned only once the gate is first GREEN (seal the run). Until then, pass none to dry-run.
set -euo pipefail
cd "$(dirname "$0")"

LOG="${1:-evidence/gpu-zone-z0-sim-GREEN.log}"
EXPECT_MD5="${2:-2d00c0e5958e5b30a9a2f1a6c175de38}"   # sealed Simulator GREEN (6/6 zone shaders compile ES 3.00)
[ -n "$LOG" ] && [ -f "$LOG" ] || { echo "!! usage: gpu-zone-z0-verdict.sh <log> [expected_md5]   (log not found)" >&2; exit 1; }

ACTUAL_MD5="$(md5 -q "$LOG" 2>/dev/null || md5sum "$LOG" | awk '{print $1}')"

ES300=$(grep -q "GL_SLVERSION = OpenGL ES GLSL ES 3.00" "$LOG" && echo YES || echo NO)
PENDING=$(grep -q "NO_ASSEMBLED_SHADERS" "$LOG" && echo YES || echo NO)
NO_FAIL=$(grep -qE "= FAIL|-> FAIL|FAIL:" "$LOG" && echo DIRTY || echo CLEAN)
VERDICT_LINE=$(grep -qF "VERDICT = ALL_ZONE_SHADERS_COMPILE_ES300" "$LOG" && echo OK || echo MISSING)
COMPILED=$(grep -oE "SUMMARY compiled=[0-9]+/[0-9]+" "$LOG" | tail -1 || true)

echo "log               : $LOG"
echo "md5 actual        : $ACTUAL_MD5"
echo "md5 expected      : ${EXPECT_MD5:-<unpinned>}"
echo "es300 driver      : $ES300"
echo "$COMPILED"
echo "no-FAIL lines     : $NO_FAIL"
echo "verdict line      : $VERDICT_LINE"

if [ "$PENDING" = YES ]; then
	echo "MEGAVERDICT = Z0_PENDING (assemble step not wired — no shaders bundled; see README 'Status')"
	exit 3
fi

TUPLE="$ES300/$NO_FAIL/$VERDICT_LINE"
EXPECT="YES/CLEAN/OK"
MD5_OK=$([ -n "$EXPECT_MD5" ] && [ "$ACTUAL_MD5" = "$EXPECT_MD5" ] && echo yes || echo no)

if [ "$TUPLE" = "$EXPECT" ] && { [ -z "$EXPECT_MD5" ] || [ "$MD5_OK" = yes ]; }; then
	echo "MEGAVERDICT = Z0_ZONE_SHADER_GATE_GREEN${EXPECT_MD5:+_AS_EXPECTED}"
	exit 0
else
	echo "MEGAVERDICT = Z0_ZONE_SHADER_GATE_NOT_GREEN (tuple=$TUPLE expect=$EXPECT md5_match=$MD5_OK)"
	exit 2
fi
