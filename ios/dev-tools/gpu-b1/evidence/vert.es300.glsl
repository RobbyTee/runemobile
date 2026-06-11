/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#version 300 es
precision highp float;
precision highp int;


//#define ZBUF_DEBUG
//#define BIAS_DEBUG

#define TEXTURE_COUNT 256

// smallest unit of the texture which can be moved per tick. textures are all
// 128x128px - so this is equivalent to +1px
#define TEXTURE_ANIM_UNIT (1.0 / 128.0)

#define TILE_SIZE 128.

#define FOG_SCENE_EDGE_MIN (float(-expandedMapLoadingChunks * 8 + 1) * TILE_SIZE)
#define FOG_SCENE_EDGE_MAX (float(104 + expandedMapLoadingChunks * 8 - 1) * TILE_SIZE)
#define FOG_CORNER_ROUNDING 1.5
#define FOG_CORNER_ROUNDING_SQUARED (FOG_CORNER_ROUNDING * FOG_CORNER_ROUNDING)

layout(location = 0) in vec3 vertf;
layout(location = 1) in int abhsl;
layout(location = 2) in ivec4 tex;

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  float cameraX;
  float cameraY;
  float cameraZ;
};

uniform mat4 worldProj;
uniform mat4 entityProj;
uniform ivec4 entityTint;
uniform float brightness;
uniform int useFog;
uniform int fogDepth;
uniform int drawDistance;
uniform int expandedMapLoadingChunks;
uniform ivec3 base;
uniform int tick;
uniform vec2 textureAnimations[TEXTURE_COUNT];

out vec4 fColor;
centroid out float fHsl;
flat out int fTextureId;
out vec2 fUv;
out float fFogAmount;
#ifdef ZBUF_DEBUG
out float fDepth;
#endif

/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

vec3 hslToRgb(vec3 hsl) {
  float hue = hsl.x / 64.0 + 0.0078125;
  float sat = hsl.y / 8.0 + 0.0625;
  float lum = hsl.z;

  float var11 = lum / 128.0;
  float r = var11;
  float g = var11;
  float b = var11;

  float var19;
  if (var11 < 0.5) {
    var19 = var11 * (1.0 + sat);
  } else {
    var19 = var11 + sat - var11 * sat;
  }

  float var21 = 2.0 * var11 - var19;
  float var23 = hue + 0.3333333333333333;
  if (var23 > 1.0) {
    var23 -= 1.;
  }

  float var27 = hue - 0.3333333333333333;
  if (var27 < 0.0) {
    var27 += 1.;
  }

  if (6.0 * var23 < 1.0) {
    r = var21 + (var19 - var21) * 6.0 * var23;
  } else if (2.0 * var23 < 1.0) {
    r = var19;
  } else if (3.0 * var23 < 2.0) {
    r = var21 + (var19 - var21) * (0.6666666666666666 - var23) * 6.0;
  } else {
    r = var21;
  }

  if (6.0 * hue < 1.0) {
    g = var21 + (var19 - var21) * 6.0 * hue;
  } else if (2.0 * hue < 1.0) {
    g = var19;
  } else if (3.0 * hue < 2.0) {
    g = var21 + (var19 - var21) * (0.6666666666666666 - hue) * 6.0;
  } else {
    g = var21;
  }

  if (6.0 * var27 < 1.0) {
    b = var21 + (var19 - var21) * 6.0 * var27;
  } else if (2.0 * var27 < 1.0) {
    b = var19;
  } else if (3.0 * var27 < 2.0) {
    b = var21 + (var19 - var21) * (0.6666666666666666 - var27) * 6.0;
  } else {
    b = var21;
  }

  return vec3(pow(r, brightness), pow(g, brightness), pow(b, brightness));
}

float fogFactorLinear(const float dist, const float start, const float end) {
  return 1.0 - clamp((dist - start) / (end - start), 0.0, 1.0);
}

void main() {
  vec4 vert = vec4(vertf + vec3(base), 1);
  float a = float(abhsl >> 24 & 0xff) / 255.;
  int bias = (abhsl >> 16) & 0xff;

  vec3 hsl = vec3(abhsl >> 10 & 63, abhsl >> 7 & 7, abhsl & 127);
  hsl += ((vec3(entityTint.xyz) - hsl) * float(entityTint.w)) / 128.0;
  vec3 rgb = hslToRgb(hsl);

  vec4 worldPos = entityProj * vert;
  vec4 screenPos = worldProj * worldPos;
#ifdef ZBUF_DEBUG
  fDepth = screenPos.z / screenPos.w;
#endif
  screenPos.z += float(bias) / 128.0;
  gl_Position = screenPos;
#ifdef BIAS_DEBUG
  fColor = vec4(clamp(bias, 0, 12) / 12.0, 0.0, 0.0, 1.0);
#else
  fColor = vec4(rgb, 1. - a);
#endif

  fTextureId = tex.x;  // the texture id + 1
  fUv = vec2(float(tex.y) / 256., float(tex.z) / 256.);
  if (fTextureId > 0) {
    vec2 textureAnim = textureAnimations[min(fTextureId - 1, TEXTURE_COUNT - 1)];
    fUv += float(tick) * textureAnim * TEXTURE_ANIM_UNIT;
    fHsl = float(abhsl & 0xffff);  // only used for texture lighting, which isn't affected by tint
  } else {
    fHsl = float(((int(hsl[0]) & 63) << 10) | ((int(hsl[1]) & 7) << 7) | (int(hsl[2]) & 127));
  }

  float fogWest = max(FOG_SCENE_EDGE_MIN, cameraX - float(drawDistance));
  float fogEast = min(FOG_SCENE_EDGE_MAX, cameraX + float(drawDistance));
  float fogSouth = max(FOG_SCENE_EDGE_MIN, cameraZ - float(drawDistance));
  float fogNorth = min(FOG_SCENE_EDGE_MAX, cameraZ + float(drawDistance));

  // Calculate distance from the scene edge
  float xDist = min(worldPos.x - fogWest, fogEast - worldPos.x);
  float zDist = min(worldPos.z - fogSouth, fogNorth - worldPos.z);
  float nearestEdgeDistance = min(xDist, zDist);
  float secondNearestEdgeDistance = max(xDist, zDist);
  float fogDistance =
      nearestEdgeDistance - FOG_CORNER_ROUNDING * TILE_SIZE *
                                max(0., (nearestEdgeDistance + FOG_CORNER_ROUNDING_SQUARED) / (secondNearestEdgeDistance + FOG_CORNER_ROUNDING_SQUARED));

  fFogAmount = fogFactorLinear(fogDistance, 0., float(fogDepth) * TILE_SIZE) * float(useFog);
}
