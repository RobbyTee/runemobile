#!/usr/bin/env bash
# gpu-tbo2d — 117HD blocker #1 (texture-buffer -> 2D integer-texture repack) verdict (md5-gated, anti-fabrication).
#
# Recomputes pass/fail in bash from the VERBATIM Simulator-pulled log and pins its md5, so a fabricated/edited verdict
# can't pass. GREEN = on native iOS GLES 3.0, the RLHD per-face GL_TEXTURE_BUFFER (isamplerBuffer, ES 3.2-only) can be
# repacked into a 2D RGBA32I integer texture and fetched via texelFetch(tex, ivec2(faceId % W, faceId / W)) BYTE-EXACT
# for every face, in BOTH the fragment AND the vertex stage (the RLHD scene_vert/shadow_vert pathway), at a face count
# (50000) that EXCEEDS GL_MAX_TEXTURE_SIZE (so a 1D layout is impossible and 2D is required). Integer 2D textures +
# texelFetch + vertex texture fetch are ES 3.0 CORE, so a software-renderer pass is high-confidence for the device GPU.
#
# Usage: tbo2d-verdict.sh [log] [expected_md5]
set -euo pipefail
cd "$(dirname "$0")"

LOG="${1:-evidence/gpu-tbo2d-sim-verdict.log}"
EXPECT_MD5="${2:-70f3b20460526f1a24c4e818aede174b}"

[ -f "$LOG" ] || { echo "!! log not found: $LOG" >&2; exit 1; }
ACTUAL_MD5="$(md5 -q "$LOG" 2>/dev/null || md5sum "$LOG" | awk '{print $1}')"

ES300=$(grep -q "GL_SLVERSION = OpenGL ES GLSL ES 3.00" "$LOG" && echo YES || echo NO)
TWOD_REQ=$(grep -q "NO -> 2D required" "$LOG" && echo YES || echo NO)
PA=$(grep -qF "[tbo] PHASE A: RGBA32I" "$LOG" && grep -q "upload glErr=NONE" "$LOG" && echo OK || echo FAIL)
PFRAG=$(grep -qF "[tbo] PROGRAM P_FRAG = OK" "$LOG" && echo OK || echo MISSING)
PVERT=$(grep -qF "[tbo] PROGRAM P_VERT = OK" "$LOG" && echo OK || echo MISSING)
B_EXACT=$(grep -qF "[tbo] B_FRAG_EXACT = YES" "$LOG" && echo YES || echo NO)
C_EXACT=$(grep -qF "[tbo] C_VERT_WRAP_EXACT = YES" "$LOG" && echo YES || echo NO)
VERDICT=$(grep -qF "[tbo] VERDICT tbo2d_repack_exact = YES" "$LOG" && echo YES || echo NO)
NO_FAIL=$(grep -qE "= FAIL|-> NO-GO|INCOMPLETE -> SKIP|EXCEPTION|FAILED" "$LOG" && echo DIRTY || echo CLEAN)

echo "log                     : $LOG"
echo "md5 actual              : $ACTUAL_MD5"
echo "md5 expected            : $EXPECT_MD5"
echo "es300 driver            : $ES300"
echo "2D required (>max 1D)   : $TWOD_REQ"
echo "PHASE A integer upload  : $PA"
echo "programs (frag/vert)    : $PFRAG/$PVERT"
echo "B fragment byte-exact   : $B_EXACT"
echo "C vertex wrap byte-exact: $C_EXACT"
echo "VERDICT line            : $VERDICT"
echo "no-FAIL/skip lines      : $NO_FAIL"

TUPLE="$ES300/$TWOD_REQ/$PA/$PFRAG/$PVERT/$B_EXACT/$C_EXACT/$VERDICT/$NO_FAIL"
EXPECT="YES/YES/OK/OK/OK/YES/YES/YES/CLEAN"

if [ "$ACTUAL_MD5" = "$EXPECT_MD5" ] && [ "$TUPLE" = "$EXPECT" ]; then
	echo "MEGAVERDICT = TBO2D_REPACK_EXACT_GREEN_AS_EXPECTED"
	exit 0
else
	echo "MEGAVERDICT = TBO2D_NOT_GREEN (tuple=$TUPLE md5_match=$([ "$ACTUAL_MD5" = "$EXPECT_MD5" ] && echo yes || echo no))"
	exit 2
fi
