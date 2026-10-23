/*
 * Written in 2025 by Casey Zwicker <caseypzwicker@gmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
// GLSL ES port (Android shader-overlay) of RLHD v1.6.0.1's scaling/hybrid.glsl: the ivec2 uboUi.sourceDimensions is
// cast to vec2 and `1 - texelUv` takes vec2(1.0). GLSL ES has no implicit int<->float conversion. iOS applies the
// same fix at load time (RouteCBridge rlhdCasts). RE-MERGE on RLHD rev bumps.

#include <uniforms/ui.glsl>

// Anti-aliased UI scaling that respects pixel sharpness
// Approach taken from https://colececil.dev/blog/2017/scaling-pixel-art-without-destroying-it/
vec4 textureHybrid(sampler2D tex, vec2 uv) {
    uv *= vec2(uboUi.sourceDimensions);
    vec2 texelUv = fract(uv);
    vec2 pixelsPerTexel = vec2(uboUi.targetDimensions) / vec2(uboUi.sourceDimensions);
    vec2 interpolationAmount = min(texelUv * pixelsPerTexel, .5) - min((vec2(1.0) - texelUv) * pixelsPerTexel, .5);
    return texture(tex, (floor(uv) + .5 + interpolationAmount) / vec2(uboUi.sourceDimensions));
}
