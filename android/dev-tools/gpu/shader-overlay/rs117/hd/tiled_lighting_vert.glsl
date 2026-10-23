/*
 * GLSL ES override (Android shader-overlay) of rs117/hd/tiled_lighting_vert.glsl @ RLHD v1.6.0.1: every int-in-float
 * context is made explicit, which GLSL ES requires and upstream's GLSL 330 does not. The code is the iOS override's
 * (ios/dev-tools/gpu-zone/ios-overrides/tiled_lighting_vert.glsl), whose header lists the changes; keep the two in
 * step. rc_downport (librlawt.cpp) handles the version line and precision, not typing. Logic identical.
 * RE-MERGE on RLHD rev bumps (tools/shader-matrix/run-matrix.sh android).
 * RLHD is BSD 2-Clause, Copyright (c) 2021, 117: the full text is in android/dev-tools/gpu/rlhd-src/LICENSE.
 */
#version 330

#include <uniforms/global.glsl>

layout (location = 0) in vec2 vPos;
layout (location = 1) in vec2 vUv;

out vec2 fUv;

void main() {
    gl_Position = vec4(vPos, 0.0, 1.0);
    fUv = vUv;
}
