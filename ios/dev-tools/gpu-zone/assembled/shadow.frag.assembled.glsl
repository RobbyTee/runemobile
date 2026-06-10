/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2023, Hooder <ahooder@protonmail.com>
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
#version 330

// #pragma once: first include

// #pragma once: first include

#define EPS 1.0e-10
#define PI 3.14159265f // max 32-bit float precision
#define HALF_PI (.5*PI)
#define TAU (2*PI)

#define SHORT_MAX 32767 // 2^15 - 1

#define VERTEX_SHADER 35633
#define FRAGMENT_SHADER 35632
#define GEOMETRY_SHADER 36313
#define COMPUTE_SHADER 37305
#define SHADER_TYPE 35632
#define LEGACY_RENDERER 0
#define ZONE_RENDERER 1

// Any changes here may need to be reflected in OpenCL's constants.cl
// They are kept separate to avoid accidentally breaking OpenCL compatibility
#define MATERIAL_INDEX_SHIFT 21
#define MATERIAL_INDEX_MASK ((1 << (32 - MATERIAL_INDEX_SHIFT)) - 1)
#define MATERIAL_SHADOW_OPACITY_THRESHOLD_SHIFT 15
#define MATERIAL_FLAG_WIND_MODIFIER 12
#define MATERIAL_FLAG_WIND_SWAYING 9
#define MATERIAL_FLAG_INVERT_DISPLACEMENT_STRENGTH 8
#define MATERIAL_FLAG_UNDO_VANILLA_SHADING 7
#define MATERIAL_FLAG_TERRAIN_VERTEX_SNAPPING 6
#define MATERIAL_FLAG_DISABLE_SHADOW_RECEIVING 5
#define MATERIAL_FLAG_UPWARDS_NORMALS 4
#define MATERIAL_FLAG_FLAT_NORMALS 3
#define MATERIAL_FLAG_WORLD_UVS 2
#define MATERIAL_FLAG_VANILLA_UVS 1
#define MATERIAL_FLAG_IS_OVERLAY 0

#define SHADOW_MODE 0
#define SHADOW_MODE_OFF 0
#define SHADOW_MODE_FAST 1
#define SHADOW_MODE_DETAILED 2

#define SHADOW_TRANSPARENCY_BIAS 0.006
#define SHADOW_DEPTH_BITS 16
#define SHADOW_ALPHA_BITS 8
#define SHADOW_COMBINED_BITS (SHADOW_DEPTH_BITS + SHADOW_ALPHA_BITS)
#define SHADOW_DEPTH_MAX ((1 << SHADOW_DEPTH_BITS) - 1)
#define SHADOW_ALPHA_MAX ((1 << SHADOW_ALPHA_BITS) - 1)
#define SHADOW_COMBINED_MAX ((1 << SHADOW_COMBINED_BITS) - 1)

#define SHADOW_RESOLUTION 2048

#define SHADOW_FILTERING 0
#define SHADOW_FILTERING_PCF 0
#define SHADOW_FILTERING_DITHER 1
#define SHADOW_FILTERING_AVERAGE 2

#define SHADOW_TRANSPARENCY 0
#if SHADOW_TRANSPARENCY
    #define SHADOW_DEFAULT_OPACITY_THRESHOLD 0.01 // Remove shadows from clickboxes
#else
    #define SHADOW_DEFAULT_OPACITY_THRESHOLD 0.71 // Lowest while keeping Prifddinas glass walkways transparent
#endif

#define VANILLA_COLOR_BANDING 0
#define UNDO_VANILLA_SHADING 0
#define LEGACY_GREY_COLORS 0
#define DISABLE_DIRECTIONAL_SHADING 0
#define FLAT_SHADING 0
#define APPLY_COLOR_FILTER 0
#define WIREFRAME 0
#define WIND_DISPLACEMENT 0
#define WIND_DISPLACEMENT_NOISE_RESOLUTION 128
#define CHARACTER_DISPLACEMENT 0
#define DYNAMIC_LIGHTS 0
#define MAX_LIGHT_COUNT 1
#define TILED_LIGHTING 0
#define TILED_LIGHTING_LAYER_COUNT 0
#define TILED_LIGHTING_TILE_SIZE 16
#define TILED_LIGHTING_MAX_TILE_LIGHT_COUNT (TILED_LIGHTING_LAYER_COUNT * 4 * 2)
#define WINDOWS_HDR_CORRECTION 0


layout(std140) uniform UBOGlobal {
    int expandedMapLoadingChunks;
    float drawDistance;

    float colorBlindnessIntensity;
    float gammaCorrection;
    float saturation;
	float contrast;
    int colorFilterPrevious;
    int colorFilter;
    float colorFilterFade;

    ivec2 sceneResolution;
    ivec2 tiledLightingResolution;

    vec3 ambientColor;
    float ambientStrength;
    vec3 lightColor;
    float lightStrength;
    vec3 underglowColor;
    float underglowStrength;

    int useFog;
    float fogDepth;
    vec3 fogColor;
    float groundFogStart;
    float groundFogEnd;
    float groundFogOpacity;

    vec3 waterColorLight;
    vec3 waterColorMid;
    vec3 waterColorDark;

    bool underwaterEnvironment;
    bool underwaterCaustics;
    vec3 underwaterCausticsColor;
    float underwaterCausticsStrength;

    vec3 lightDir;

    int pointLightsCount;

    vec3 cameraPos;
    mat4 viewMatrix;
    mat4 projectionMatrix;
    mat4 invProjectionMatrix;
    mat4 lightProjectionMatrix;

    float lightningBrightness;
    float elapsedTime;
};


// #pragma once: already included

/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
// #pragma once: first include

// #pragma once: already included


/**
 * Row-major transformation matrices for conversion between RGB and XYZ color spaces.
 *
 * Fairman, H. S., Brill, M. H., & Hemmendinger, H. (1997).
 * How the CIE 1931 color-matching functions were derived from Wright-Guild data.
 * Color Research & Application, 22(1), 11–23.
 * doi:10.1002/(sici)1520-6378(199702)22:1<11::aid-col4>3.0.co;2-7
 */
const mat3 RGB_TO_XYZ_MATRIX = mat3(
    .49, .1769, .0,
    .31, .8124, .0099,
    .2,  .0107, .9901
);
const mat3 XYZ_TO_RGB_MATRIX = mat3(
    2.36449,  -.514935,  0.00514883,
    -.896553, 1.42633,   -.0142619,
    -.467937,  .0886025, 1.00911
);

/**
 * Transform from CIE 1931 XYZ color space to linear RGB.
 * @param XYZ coordinates
 * @return linear RGB coordinates
 */
vec3 XYZtoRGB(vec3 XYZ) {
    return XYZ_TO_RGB_MATRIX * XYZ;
}

/**
 * Transform from linear RGB to CIE 1931 XYZ color space.
 * @param RGB linear RGB color coordinates
 * @return XYZ color coordinates
 */
vec3 RGBtoXYZ(vec3 RGB) {
    return RGB_TO_XYZ_MATRIX * RGB;
}

/**
 * Approximate UV coordinates in the CIE 1960 UCS color space from a color temperature specified in degrees Kelvin.
 * @param kelvin temperature in degrees Kelvin. Valid from 1000 to 15000.
 * @see <a href="https://doi.org/10.1002/col.5080100109">
 *     Krystek, M. (1985). An algorithm to calculate correlated colour temperature.
 *     Color Research & Application, 10(1), 38–40. doi:10.1002/col.5080100109
 * </a>
 * @return UV coordinates in the UCS color space
 */
vec3 colorTemperatureToLinearRgb(float kelvin) {
    // UV coordinates in CIE 1960 UCS color space
    vec2 uv = vec2(
        (0.860117757 + 1.54118254e-4 * kelvin + 1.28641212e-7 * kelvin * kelvin)
            / (1 + 8.42420235e-4 * kelvin + 7.08145163e-7 * kelvin * kelvin),
        (0.317398726 + 4.22806245e-5 * kelvin + 4.20481691e-8 * kelvin * kelvin)
            / (1 - 2.89741816e-5 * kelvin + 1.61456053e-7 * kelvin * kelvin)
    );

    // xy coordinates in CIES 1931 xyY space
    vec2 xy = vec2(3 * uv.x, 2 * uv.y) / (2 * uv.x - 8 * uv.y + 4);

    // CIE XYZ space
    const float Y = 1;
    vec3 XYZ = Y * vec3(xy.x / xy.y, 1, (1 - xy.x - xy.y) / xy.y);

    vec3 linearRgb = XYZtoRGB(XYZ);
    float m = max(linearRgb.x, max(linearRgb.y, linearRgb.z));
    linearRgb = linearRgb / m;
    return linearRgb;
}

// Conversion functions to and from sRGB and linear color space.
// The implementation is based on the sRGB EOTF given in the Khronos Data Format Specification.
// Source: https://web.archive.org/web/20220808015852/https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.pdf
// Page number 130 (146 in the PDF)
vec3 srgbToLinear(vec3 srgb) {
  return mix(
    srgb / 12.92,
    pow((srgb + vec3(0.055)) / vec3(1.055), vec3(2.4)),
    step(vec3(0.04045), srgb));
}

vec3 linearToSrgb(vec3 rgb) {
  return mix(
    rgb * 12.92,
    1.055 * pow(rgb, vec3(1 / 2.4)) - 0.055,
    step(vec3(0.0031308), rgb));
}

float srgbToLinear(float srgb) {
  return mix(
    srgb / 12.92,
    pow((srgb + float(0.055)) / float(1.055), float(2.4)),
    step(float(0.04045), srgb));
}

float linearToSrgb(float rgb) {
  return mix(
    rgb * 12.92,
    1.055 * pow(rgb, 1 / 2.4) - 0.055,
    step(0.0031308, rgb));
}

// https://web.archive.org/web/20230619214343/https://en.wikipedia.org/wiki/HSL_and_HSV#Color_conversion_formulae
vec3 srgbToHsl(vec3 srgb) {
    float V = max(max(srgb.r, srgb.g), srgb.b);
    float X_min = min(min(srgb.r, srgb.g), srgb.b);
    float C = V - X_min;

    float H = 0;
    if (C > 0) {
        if (V == srgb.r) {
            H = mod((srgb.g - srgb.b) / C, 6);
        } else if (V == srgb.g) {
            H = (srgb.b - srgb.r) / C + 2;
        } else {
            H = (srgb.r - srgb.g) / C + 4;
        }
    }

    float L = (V + X_min) / 2;
    float divisor = 1 - abs(2 * L - 1);
    float S_L = abs(divisor) < EPS ? 0 : C / divisor;
    return vec3(H / 6, S_L, L);
}

vec3 hslToSrgb(vec3 hsl) {
    float C = (1 - abs(2 * hsl[2] - 1)) * hsl[1];
    float H_prime = fract(hsl[0]) * 6;
    float m = hsl[2] - C / 2;

    float r = clamp(abs(H_prime - 3) - 1, 0, 1);
    float g = clamp(2 - abs(H_prime - 2), 0, 1);
    float b = clamp(2 - abs(H_prime - 4), 0, 1);
    return vec3(r, g, b) * C + m;
}

vec3 hslToHsv(vec3 hsl) {
    float v = hsl[2] + hsl[1] * min(hsl[2], 1 - hsl[2]);
    return vec3(hsl[0], abs(v) < EPS ? 0 : 2 * (1 - hsl[2] / v), v);
}

vec3 hsvToHsl(vec3 hsv) {
    float l = hsv[2] * (1 - hsv[1] / 2);
    float divisor = min(l, 1 - l);
    return vec3(hsv[0], abs(divisor) < EPS ? 0 : (hsv[2] - l) / divisor, l);
}

vec3 srgbToHsv(vec3 rgb) {
    return hslToHsv(srgbToHsl(rgb));
}

vec3 hsvToSrgb(vec3 hsv) {
    return hslToSrgb(hsvToHsl(hsv));
}

// Pack HSL int Jagex format
int packRawHsl(ivec3 hsl) {
    int H = clamp(hsl[0], 0, 63);
    int S = clamp(hsl[1], 0, 7);
    int L = clamp(hsl[2], 0, 127);
    return H << 10 | S << 7 | L;
}

// Pack HSL int Jagex format
int packHsl(vec3 hsl) {
    ivec3 rawHsl = ivec3(
        round((hsl[0] - .0078125f) * 64),
        round((hsl[1] - .0625f) * 8),
        round(hsl[2] * 128)
    );
    return packRawHsl(rawHsl);
}

// Unpack HSL from Jagex format
vec3 unpackRawHsl(int hsl) {
    // 6-bit hue | 3-bit saturation | 7-bit lightness
    float H = hsl >> 10 & 63;
    float S = hsl >> 7 & 7;
    float L = hsl & 127;
    return vec3(H, S, L);
}

// Unpack HSL from Jagex format
vec3 convertHsl(vec3 HSL) {
    // 6-bit hue | 3-bit saturation | 7-bit lightness
    float H = HSL[0] / 64.f + .0078125f;
    float S = HSL[1] / 8.f + .0625f;
    float L = HSL[2] / 128.f;
    return vec3(H, S, L);
}

int srgbToPackedHsl(vec3 srgb) {
    return packHsl(srgbToHsl(srgb));
}

vec3 packedHslToSrgb(int hsl) {
    return hslToSrgb(convertHsl(unpackRawHsl(hsl)));
}


#if SHADOW_MODE == SHADOW_MODE_DETAILED
    uniform sampler2DArray textureArray;
    in vec4 fUvw;
    flat in int fMaterialData;
#endif

#if SHADOW_TRANSPARENCY
    in float fOpacity;
#endif

void main() {
    float opacity = 1;
    #if SHADOW_TRANSPARENCY
        opacity = fOpacity;
    #endif

    #if SHADOW_MODE == SHADOW_MODE_DETAILED
        if (fUvw.z != -1) {
            vec4 uvw = fUvw;

            // Vanilla tree textures rely on UVs being clamped horizontally,
            // which HD doesn't do, so we instead opt to hide these fragments
            if ((fMaterialData >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1)
                uvw.x = clamp(uvw.x, 0, .984375);

            if (uvw.z != -1)
                opacity *= texture(textureArray, uvw.xyz).a;
            if (uvw.w != -1)
                opacity *= linearToSrgb(texture(textureArray, uvw.xyw).r);

            #if !SHADOW_TRANSPARENCY
                if (opacity < SHADOW_DEFAULT_OPACITY_THRESHOLD)
                    discard;
            #endif
        }
    #endif

    #if SHADOW_TRANSPARENCY
        // We pack the transparency and depth of each fragment into the upper and lower bits
        // of the output depth respectively, such that less-transparent fragments overwrite
        // more-transparent fragments first, and equally transparent fragments second, based on depth.
        // Unfortunately, the exact handling of floats is implementation dependant, so this may not work
        // the same across all GPUs.
        float depth = gl_FragCoord.z;
        gl_FragDepth = (
            int((1 - opacity) * SHADOW_ALPHA_MAX) << SHADOW_DEPTH_BITS |
            int(depth * SHADOW_DEPTH_MAX)
        ) / float(SHADOW_COMBINED_MAX);
    #endif
}
