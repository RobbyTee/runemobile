/*
 * From RLHD (https://github.com/117HD/RLHD), BSD 2-Clause License, Copyright (c) 2021, 117: the full text is in
 * ios/dev-tools/gpu-zone/rlhd-src/LICENSE. The upstream file has no header of its own.
 *
 * ===========================================================================================================
 * iOS ES-3.0 OVERRIDE (gpu-zone B1, dynamic lights). Verbatim copy of rs117/hd/utils/lights.glsl @ RLHD v1.6.0.1
 * EXCEPT the int-in-float-context sites are made explicit (blueprint #13) so the SCENE program compiles on
 * GLES 3.00 when DYNAMIC_LIGHTS != NONE (this whole file is a no-op macro at DYNAMIC_LIGHTS off).
 * The UBO blocks (uniforms/lights.glsl) are sized at MAX_LIGHT_COUNT (<=100 for dynamic, NOT the 1000 tiled cap),
 * so they fit Apple's 16 KB uniform-block limit -> no texture-backed store needed (unlike UBOMaterials); the tiled
 * path caps lights at MAX_LIGHTS=480 (java-overrides/UBOLights.java) so the 480-entry UBO stays under the cap. B1
 * tiled lighting also ports the TILED_LIGHTING branch's tileXY (sceneResolution/tiledLightingResolution are ivec2
 * -> vec2()). Logic identical. RE-MERGE on RLHD rev bumps.
 * ===========================================================================================================
 */
#pragma once

#include <uniforms/global.glsl>
#include <uniforms/lights.glsl>

#include <utils/constants.glsl>
#include <utils/specular.glsl>

float thinSheetTransmission(float incidence, float viewTowardLight, float subsurface) {
    if (subsurface <= 0.0 || incidence >= 0.0)
        return 0.0;
    return -incidence * subsurface * 1.5 * max(viewTowardLight, 0.0);
}

#if DYNAMIC_LIGHTS
void calculateLight(
    int lightIdx, vec3 position, vec3 normals, vec3 viewDir,
    vec3 texBlend, vec3 specularGloss, vec3 specularStrength, float subsurface,
    inout vec3 pointLightsOut, inout vec3 pointLightsSpecularOut
) {
    PointLight light = uboLights.PointLightArray[lightIdx];
    vec3 lightToFrag = light.position.xyz - position;
    float distanceSquared = dot(lightToFrag, lightToFrag);
    float radiusSquared = light.position.w;
    if (distanceSquared <= radiusSquared) {
        float attenuation = 1.0 - sqrt(distanceSquared / radiusSquared);
        attenuation *= attenuation;

        vec3 pointLightColor = light.color.rgb * attenuation;
        vec3 pointLightDir = normalize(lightToFrag);

        float pointLightDotNormals = dot(normals, pointLightDir);
        pointLightsOut += pointLightColor * max(pointLightDotNormals, 0.0);
        if (subsurface > 0.0)
            pointLightsOut += pointLightColor * thinSheetTransmission(
                pointLightDotNormals, dot(-pointLightDir, viewDir), subsurface);

        vec3 pointLightReflectDir = reflect(-pointLightDir, normals);
        pointLightsSpecularOut += pointLightColor * specular(texBlend, viewDir, pointLightReflectDir, specularGloss, specularStrength);
    }
}

void calculateLighting(
    vec3 position, vec3 normals, vec3 viewDir,
    vec3 texBlend, vec3 specularGloss, vec3 specularStrength, float subsurface,
    inout vec3 pointLightsOut, inout vec3 pointLightsSpecularOut
) {
    #if TILED_LIGHTING
        // iOS: sceneResolution / tiledLightingResolution are ivec2 (global UBO) -> vec2() before the float math.
        ivec2 tileXY = ivec2(gl_FragCoord.xy / vec2(uboGlobal.sceneResolution) * vec2(uboGlobal.tiledLightingResolution));

        for (int tileLayer = 0; tileLayer < TILED_LIGHTING_LAYER_COUNT; tileLayer++) {
            uvec4 tileLayerData = texelFetch(tiledLightingArray, ivec3(tileXY, tileLayer), 0);
            ivec2 unpackedData = ivec2(0);

            #define PROCESS_TILED_LIGHT_COMPONENT(c)                           \
                if (tileLayerData[c] <= 0u)                                    \
                    break;                                                     \
                unpackedData = decodePackedLight(tileLayerData[c]);            \
                                                                               \
                if (unpackedData[0] >= 0)                                      \
                    calculateLight(unpackedData[0],                            \
                        position, normals, viewDir,                            \
                        texBlend, specularGloss, specularStrength, subsurface, \
                        pointLightsOut, pointLightsSpecularOut);               \
                                                                               \
                if (unpackedData[1] >= 0)                                      \
                    calculateLight(unpackedData[1],                            \
                        position, normals, viewDir,                            \
                        texBlend, specularGloss, specularStrength, subsurface, \
                        pointLightsOut, pointLightsSpecularOut);

            PROCESS_TILED_LIGHT_COMPONENT(0);
            PROCESS_TILED_LIGHT_COMPONENT(1);
            PROCESS_TILED_LIGHT_COMPONENT(2);
            PROCESS_TILED_LIGHT_COMPONENT(3);
        }
    #else
        for (int lightIdx = 0; lightIdx < uboGlobal.pointLightsCount; lightIdx++)
            calculateLight(lightIdx, position, normals, viewDir,
                texBlend, specularGloss, specularStrength, subsurface,
                pointLightsOut, pointLightsSpecularOut);
    #endif
}
#else
#define calculateLighting(position, normals, viewDir, texBlend, specularGloss, specularStrength, subsurface, pointLightsOut, pointLightsSpecularOut)
#endif
