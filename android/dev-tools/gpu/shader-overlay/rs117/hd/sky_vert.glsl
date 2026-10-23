#version 330

// GLSL ES port (Android shader-overlay) of RLHD v1.6.0.1's sky_vert.glsl: the int gl_VertexID bit tests are cast to
// float. GLSL ES has no implicit int<->float conversion. iOS applies the same fix at load time (RouteCBridge
// rlhdCasts). RE-MERGE on RLHD rev bumps.
// RLHD is BSD 2-Clause, Copyright (c) 2021, 117: the full text is in android/dev-tools/gpu/rlhd-src/LICENSE.

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

out vec2 fScreenPos;

void main() {
    // Fullscreen triangle - generates vertices at (-1,-1), (3,-1), (-1,3)
    vec2 pos = vec2(float(gl_VertexID & 1) * 4.0 - 1.0, float(gl_VertexID & 2) * 2.0 - 1.0);
    gl_Position = vec4(pos, -1.0, 1.0); // At the far plane

    // Pass screen position to fragment shader for per-pixel ray calculation
    fScreenPos = pos;
}
