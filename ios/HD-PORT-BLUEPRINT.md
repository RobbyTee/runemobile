# Blueprint — 117HD-quality rendering on iOS via MetalANGLE (Path C)

> **Status:** proposal for review. Branch `gpu`. This is a build plan, not yet executed.
> **Goal:** bring the full 117HD (RLHD) graphical look to the on-device iOS client, exposed as native settings,
> by running 117HD's **modern (Zone) renderer** on an **OpenGL ES 3.0 / MetalANGLE** backend — *not* by
> reimplementing effects from scratch. Reviewers: please stress-test the feasibility claims in §2, the
> prerequisites in §4, the ES-3.0 blocker list in §5, the data/asset workstream in §7, and the risks in §10.

---

## 0. Executive summary

- Today we run the **base `gpu` plugin** (RuneLite's non-HD renderer) on a thin JNI bridge ("Route C") over a
  native **EAGL GLES 3.0** context. The 3D scene renders correctly on device (verified 2026-06-06). Two artifacts
  remain: depth z-fighting (root cause: native iOS GLES has no `glClipControl`) and distant aliasing.
- An on-device probe (`ios/dev-tools/gpu-angle`, md5-sealed) proved **MetalANGLE (ANGLE-over-Metal) on the A19 Pro
  GPU exposes `EXT_clip_control` (works: far-plane depth 0.508→0.0167, desktop-equivalent) and 8× MSAA**, but is
  **ES 3.0 only** (no compute). So swapping the backend EAGL→MetalANGLE fixes the depth z-fight at the root and
  enables MSAA — independent of 117HD.
- **Key finding (this blueprint's foundation):** 117HD's **ZoneRenderer** (the modern default, `legacyRenderer=false`)
  needs **no compute, no shader SSBOs, no geometry shaders**. Its look is produced by standard vertex/fragment
  shaders fed by **std140 UBOs** — all ES-3.0-core. Compute/SSBO exist only in 117HD's **LegacyRenderer** (which we
  do not use; 117HD itself already routes around "no GL compute" on Apple via OpenCL). Therefore the *real* 117HD
  look is portable to ES 3.0 with a bounded, enumerable set of fixes (§5), the largest being a texture-buffer →
  2D-texture repack and the scene **data/asset** pipeline (§7).
- **Recommended approach:** port 117HD's ZoneRenderer onto the MetalANGLE backend (reusing 117HD's own shaders and
  CPU geometry path), delivered in phased, individually-gated milestones, each surfaced as a native setting. An
  incremental "cheap wins on the base plugin" on-ramp (§6 Phase A) precedes the full port (Phase B) so there is
  visible progress and a fallback at every step.

---

## 1. Goal & success criteria

**Definition of done:** on the iPhone 17 Pro Max (A19 Pro, iOS 26.5), the client renders OSRS with 117HD's signature
qualities — HD textures/materials with normal+specular mapping, ground blending, dynamic directional **shadows**,
**HD water** (animated normals, fresnel, foam, caustics, depth color), ambient+directional+**point/dynamic
lighting** with per-area environmental lighting, distance/ground **fog**, **post** (tone/gamma, color filters,
saturation/contrast, colorblind), and **MSAA** — at a playable, thermally-sustainable framerate, with each feature
a native toggle.

**Non-goals:** literal byte-for-byte parity with desktop 117HD; the LegacyRenderer / compute path; Windows/Linux
specifics (HDR correction, Intel-Mac workarounds).

**Gates (project methodology — every phase ends in an md5-sealed, anti-fabrication device verdict like
`gpu-angle`/`gpu-b1`):** each milestone must produce a pulled-from-device log + a screenshot A/B vs the previous
state, and must not regress depth/perf.

---

## 2. The enabling finding — 117HD's modern renderer is ES-3.0-portable

From a line-by-line read of `github.com/117HD/RLHD@master` (see Appendix A):

| | **ZoneRenderer** (default, `legacyRenderer=false`) | **LegacyRenderer** (we do NOT use) |
|---|---|---|
| Compute shaders | **none** | `comp.glsl` (`glDispatchCompute`) / OpenCL on Apple |
| Shader SSBOs | **none** | 7 `std430` buffers (geometry only) |
| Geometry shaders | **none** | `scene_geom.glsl`, `shadow_geom.glsl` |
| Geometry to GPU | VBO/VAO + texture-buffer; **CPU** alpha/face sort | compute-built buffers |
| GL gate (`supportsGpu`) | `glCaps.OpenGL33` (`ZoneRenderer.java:168`) | `OpenGL43` or `OpenGL31`(OpenCL) |
| Look data feed | **std140 UBOs** (`UBOGlobal/Materials/Lights/WaterTypes`) + `sampler2DArray` | same |

The "look-producing" code is `scene_vert.glsl` + `scene_frag.glsl` (~540 lines) + `utils/*.glsl`
(normals, specular, water, caustics, fog, shadows, lights, color, displacement) + `shadow_*`, `tiled_lighting_*`,
`ui_*` and the `scaling/*` upscalers. These read **textures + UBOs only**. The compute/SSBO subsystem is purely the
geometry-sort/push stage, which the ZoneRenderer already performs on the **CPU** (`SceneUploader`,
`StaticAlphaSortingJob`) — the exact role our base-gpu `SceneUploader/Zone/FacePrioritySorter` already fills.

**Consequence:** we are not inventing effects. We are downporting 117HD's existing modern shaders + passes to
GLSL ES 3.00 and feeding them with a CPU geometry pipeline we already have. clip_control (now available via
MetalANGLE) gives the reverse-Z the precision 117HD assumes on desktop.

---

## 3. Target architecture

```
RuneLite engine (headless, on-device JVM)
   │  DrawCallbacks (drawZoneOpaque/Alpha, drawPass, postSceneDraw, draw)
   ▼
117HD ZoneRenderer (Java, downported)        ◄── scene data: environments/lights/materials/ground_materials/
   │  CPU SceneUploader + alpha sort              model_overrides JSON + HD texture atlas (normal/specular/flow/caustics)
   │  org.lwjgl.opengl GL3x/GL45 calls
   ▼
Route C facade (org.lwjgl.opengl.* shims, EXTENDED)  ──► JNI table (routec_gl_jni.m, EXTENDED)
   │
   ▼
MetalANGLE backend (NEW: EGL, replaces EAGL)  ──► ANGLE → Metal → A19 Pro GPU
       clip_control(ZERO_TO_ONE) ✓   MSAA 8× ✓   ES 3.0 / GLSL ES 3.00
```

**Per-frame GL passes (ported from 117HD ZoneRenderer):**
1. **Tiled-lighting pass** (optional; `RGBA16UI` `TEXTURE_2D_ARRAY`) — *use the fragment-shader fallback*, not
   image-store. Skippable (fall back to the simple per-light loop) for first light.
2. **Directional shadow depth pass** — depth-only FBO, `texShadowMap` (`DEPTH_COMPONENT24` 2D texture), replays the
   scene from the sun's matrix with `fast`/`detailed` shadow programs.
3. **Scene pass** — `fboScene`: MSAA color renderbuffer (`RGBA8`) + `DEPTH_COMPONENT32F` depth, reverse-Z
   (`clearDepth(0)` + `GEQUAL`), with `clipControl(ZERO_TO_ONE)` for precise depth.
4. **Resolve + present** — MSAA blit → resolve FBO → default FBO (the bridge's offscreen FBO / CAEAGLLayer),
   scaled by `sceneScalingMode`.
5. **UI pass** — composite the engine interface texture (existing path), optional tiled-lighting overlay.

**What changes vs today:** (a) backend EAGL→MetalANGLE/EGL; (b) the renderer becomes 117HD's ZoneRenderer instead
of the base gpu plugin; (c) new FBOs/passes (shadow, tiled-lighting, resolve); (d) the scene data/asset pipeline.
**What carries over unchanged:** the facade/JNI pattern, the CPU geometry feed concept, the present path, the
DrawCallbacks integration, the shader-downport-at-`glShaderSource` mechanism.

---

## 4. Prerequisites (Phase 0) — do these first, on the base plugin

**P0.1 — Productize the MetalANGLE/EGL backend in Route C.** Replace the EAGL context/FBO/present in
`RouteCBridge.{h,m}` with an EGL path on ANGLE (the `gpu-angle` probe is the reference: `eglGetPlatformDisplayEXT`
Metal, pbuffer or a CAEAGLLayer-backed window surface, ES 3.0 context). The 189 GL stubs + shader downporter are
backend-agnostic and carry over. **Wins immediately, on the base gpu plugin, independent of 117HD:**
- Turn the `glClipControl` shim from a **no-op into a real `glClipControlEXT(LOWER_LEFT, ZERO_TO_ONE)`** → the
  river depth z-fight is fixed at the root (no `gl_FragDepth` hack; strip those diagnostics).
- Enable **MSAA** (already wired in the plugin; ANGLE gives `GL_MAX_SAMPLES=8`) → fixes distant aliasing.
- **Gate P0.1:** base gpu renders on MetalANGLE; depth z-fight gone (clip_control); MSAA on; perf ≥ EAGL path
  (measure — translation overhead, see §9). Screenshot A/B vs today. This validates the backend before any HD work.

**P0.2 — Decide present-surface strategy.** ANGLE window surface from the `CAEAGLLayer` vs offscreen pbuffer +
blit-to-layer. Reference: the probe used a pbuffer; production wants a window surface for zero-copy present.

**P0.3 — Confirm the scene-callback path under MetalANGLE.** The base gpu's zone callbacks already fire on device
(the 3D scene renders today). Re-confirm under the EGL backend before building on it.

> Phase 0 is independently valuable: even if the 117HD port stalls, P0.1 alone delivers the depth fix + AA the base
> plugin needs. **It is the safe on-ramp.**

---

## 5. The ES-3.0 port — concrete blocker list & fixes

From the shader/source audit (Appendix A). These are the *complete* set; most are mechanical.

| # | Blocker | Where | Fix | Size |
|---|---|---|---|---|
| 1 | `isamplerBuffer textureFaces` (texture-buffer object, not in ES 3.0) | `scene_vert.glsl`, `shadow_vert.glsl` (zone path) | Repack per-face data into a `usampler2D`/`isampler2D`; `texelFetch` by computed (x,y) | **Medium (the only real one)** |
| 2 | Geometry shaders | `scene_geom.glsl`, `shadow_geom.glsl` | **Avoid** — they are Legacy-only; the zone path uses none | none |
| 3 | Compute + SSBO geometry sort | `comp*.glsl`, `priority_render.glsl` | **Avoid** — use the ZoneRenderer's CPU sort (already its default) | none |
| 4 | `imageStore`/`uimage2DArray` (tiled lighting fast path) | `tiled_lighting_frag.glsl` | Use the in-file **fragment fallback** (render `uvec4`→array layer, `texelFetch` prior layers); or skip tiled lighting initially | small |
| 5 | `#version 330/420/430` + no precision | all shaders; `HdPlugin.java:905` `VERSION_HEADER` | `#version 300 es` + default precision header (the downporter already does this for base gpu) | mechanical |
| 6 | `glClipControl`/reverse-Z | ZoneRenderer depth setup | Now **native** via ANGLE `EXT_clip_control` (probe-confirmed) | trivial (a win) |
| 7 | TBO bind `GL_TEXTURE_BUFFER` | `Zone.java` (117HD) | replace with the 2D-texture path from #1 | folds into #1 |
| 8 | `GL_CLAMP_TO_BORDER` + border color (shadow map) | `HdPlugin.java:1430` | clamp-to-edge + in-shader border test | small |
| 9 | `glDrawBuffer`/`glReadBuffer` singular | `HdPlugin.java:1438` | `glDrawBuffers` plural (`glReadBuffer` is ES 3.0) | trivial |
| 10 | `scaling/hybrid.glsl` `vec2 *= ivec2` | UI upscaler | explicit `vec2(ivec2)` cast (already a known issue) | one line |
| 11 | `windowsHdrCorrection` | post | drop on iOS | none |
| 12 | int↔float implicit-conversion strictness | many `.glsl` | explicit casts (same class as the base-gpu B1 downport) | tedious, not architectural |
| 13 | `supportsGpu` `OpenGL33` gate rejects ES | `ZoneRenderer.java:168` | facade reports `OpenGL33=true` (same trick already used for base gpu's L1 kill-test) | trivial |

**Not blockers (verified ES-3.0-core):** all `std140 uniform` UBO blocks, `usampler2DArray`/`isampler*`,
`texelFetch`, `textureSize`, `flat`, in/out interface blocks, `dFdx/dFdy/fwidth`, `inverse()`, non-square matrices
(`mat4x3`/`mat2x3`), `gl_FragDepth`, `switch`, integer bit-ops. Shadows use **custom PCF via `texelFetch`** (not
`sampler2DShadow`), so no shadow-sampler dependency. Water is **screen-space** (no reflection/refraction render
targets). Post is just sRGB+gamma+HSV grade (no ACES/Reinhard). All portable.

---

## 6. Phased delivery (each milestone = a native setting + a device gate)

### Phase A — cheap wins on the base plugin (on-ramp; weeks)
- **A1** P0.1 backend swap → **depth fix (clip_control) + MSAA**. *(Already half-proven.)*
- **A2** Post-process color on the base render via the bridge: **tone/gamma, color filters
  (`ColorFilter`: greyscale/sepia/high-contrast/cartoon/invert/B&W/cel), saturation, contrast, colorblind** — these
  operate on the final image and need **no scene data**, so they land before the full port. Add a post-process FBO
  at the `blitSceneFbo` seam.
- **A3** Improve base water sampling (already started: trilinear + `textureGrad`); add fresnel/animated-normal
  approximation if cheap.
- *Gate A:* base plugin looks materially better (clean depth, AA, gradeable color) — shippable interim.

### Phase B — the full 117HD ZoneRenderer port (the real look; multi-month)
Bring 117HD's ZoneRenderer up on MetalANGLE, one subsystem at a time. Each is a native toggle.

- **B0 — Renderer bring-up:** stage the 117HD plugin (ZoneRenderer path) into the app like base gpu
  (`stage-ios-gpu-jar.sh` analogue), facade reports `OpenGL33=true`, downport its shaders (§5), apply the
  TBO→2D-texture repack (#1/#7), get the **untextured/vanilla-shaded scene** rendering through 117HD's pipeline.
  *Gate: 117HD ZoneRenderer renders the scene (even flat-shaded) on device.*
- **B1 — Materials & HD textures:** materials.json + ground_materials.json + model_overrides.json data pipeline +
  the HD texture atlas (`sampler2DArray`); enables **objectTextures, groundTextures, groundBlending,
  textureResolution, anisotropic filtering**. *(This is the big data workstream — see §7.)*
- **B2 — Lighting model:** ambient + directional sun + sky light + **normalMapping** + specular; **environmental
  lighting** from environments.json (per-area ambient/sun color+direction). Toggle: `normalMapping`,
  `environmentalLighting`, brightness.
- **B3 — Shadows:** the directional shadow depth pass + custom PCF (`ShadowMode` OFF/FAST/DETAILED,
  `ShadowResolution`, `ShadowFiltering`, transparency). Validate `gl_FragDepth` transparency packing on Apple GPU
  (start with non-transparent).
- **B4 — Water & caustics:** HD water (WaterType params, animated normals, fresnel, foam, `depthColor`) +
  **underwaterCaustics** + underwater color. Toggle: `underwaterCaustics`, `hideVanillaWaterEffects`.
- **B5 — Dynamic lights:** point lights from lights.json (`DynamicLights` NONE/FEW/SOME/MANY) + **tiled lighting**
  via the fragment fallback (#4); projectile/npc lights. 
- **B6 — Atmosphere:** fog model (`FogDepthMode`, `fogDepth`, `groundFog`), sky color (`DefaultSkyColor`),
  seasonal themes (snow etc.), environment data.
- **B7 — Quality/scaling:** `sceneResolutionScale` (super/subsample), `sceneScalingMode`, `uiScalingMode` (fix
  hybrid.glsl), `AntiAliasingMode` levels.
- **B8 — Extras:** parallax-occlusion mapping, wind/character displacement, removeVertexSnapping, flatShading,
  vanillaColorBanding, infernal-cape/TzHaar variants, accessibility, frame pacing.

Ordering rationale: each phase builds on the prior (materials before lighting before shadows/water/lights), and
each is independently visible + togglable, so we can ship partial HD and stop/reprioritize at any gate.

---

## 7. Scene data & HD asset pipeline (the most underestimated workstream)

117HD's look is **heavily data-driven**, not just GLSL. Matching it requires bundling and loading:
- **`environments.json`** — per-area ambient/sun/sky/fog/water colors + light direction (drives the whole mood &
  day/night-by-area). 
- **`lights.json`** — catalogue of point lights (position, color, radius, animation).
- **`materials.json` + `ground_materials.json`** — per-material color/normal/roughness/AO/displacement/flow-map
  layer indices + scroll/specular params.
- **`model_overrides.json`** — assigns materials/flags to specific models/NPCs/objects (this is how HD textures map
  onto the right geometry). Large.
- **`WaterType.java` params** — the water surface dials.
- **HD texture assets** — the actual color/normal/specular/flow/caustics images (`caustics_map.jpg`, the texture
  atlas). **Size + licensing must be assessed** (these are 117HD's assets; bundling them in our app needs a
  decision).

This implies porting 117HD's **scene-data loaders + the model-override/material-assignment system**, plus an asset
bundling step in the app. **This is likely the single largest chunk of Phase B and the biggest risk to "matches the
look exactly."** Without it, B0–B3 give HD *lighting/shadows* on vanilla textures; the full HD *material* look needs
this pipeline. Reviewers: scrutinize asset size (app bundle budget), the loaders' RuneLite-API coupling, and
whether the override data tracks OSRS content updates.

---

## 8. Bridge / facade extensions required

The base gpu used ~85 GL calls; 117HD's ZoneRenderer uses more. Add to `facade/.../GLALL.java` (+ `facade-native`
`native` decls) and the `kGLALL[]` JNI table (`routec_gl_jni.m`):
- **MRT / FBO:** `glDrawBuffers`, `glFramebufferTexture2D`, `glFramebufferTextureLayer`, depth-texture attachment.
- **clip_control:** real `glClipControlEXT` (replace the no-op shim).
- **UBO management:** `glBindBufferRange`/`glBindBufferBase`, `glGetUniformBlockIndex`, `glUniformBlockBinding`
  (some already present) for 117HD's multiple UBOs.
- **Uniforms:** `glUniform3fv`/`glUniformMatrix3fv`/`glUniform1iv` etc. for light dirs/normal matrices/material
  indices.
- **Textures:** array-layer + integer-texture (`RGBA16UI`) creation for tiled lighting; sampler params for the
  shadow map (clamp-to-edge per #8).
- **Downporter upgrades** (`RouteCBridge.downportShaderSource`): it must learn 117HD's new constructs — the
  TBO→2D-texture rewrite (#1), MRT outputs, multi-UBO blocks, `#include` expansion if 117HD's `Template` isn't
  reused, and the int/float casts across 117HD's shaders. **Decision:** reuse 117HD's own `Template`/`#include`
  system (it has one) vs our downporter — prefer reusing 117HD's Template and only post-processing the result to
  ES 3.00.

---

## 9. Performance, thermal & validation

- **Translation overhead:** ANGLE→Metal is one well-optimized layer (lighter than the MoltenVK/gl4es "Path B"
  stack). But 117HD does far more GPU work than base gpu (shadow pass = a second scene draw; per-pixel lighting;
  water). Expect **lower FPS than base-gpu-today**, mostly from doing more rendering, not the layer. A19 Pro has
  headroom (base gpu ran 60–120fps), so target playable, not maximal.
- **Thermal/battery:** a heavier renderer + translation draws more power/heat than the lean path — measure
  sustained (10-min) FPS + thermal state; expose quality toggles (shadow res, draw distance, dynamic-light count,
  resolution scale) so users can trade.
- **Per-phase device gate (anti-fabrication, like `gpu-angle`/`gpu-b1`):** each milestone ships a probe/log pulled
  via `devicectl`, an md5-sealed verdict script, and a screenshot A/B. No phase merges without its gate green +
  no perf/depth regression.
- **Measurement harness:** add an on-device frame-time counter (the bridge already has a perf counter) + a
  scripted "stand at Lumbridge river, pan" capture for consistent A/Bs. Xcode Metal GPU frame capture (a MetalANGLE
  benefit) for per-pass cost.

---

## 10. Risks, unknowns & questions for review

1. **Asset/data pipeline (highest risk):** size in the app bundle, loader complexity, licensing of 117HD assets,
   keeping `model_overrides.json` current. Is bundling 117HD's assets acceptable? (See §7.)
2. **Upstream divergence:** this forks 117HD (shader downport, TBO repack, GL gate, ES fixes). Maintenance burden
   as 117HD evolves. Mitigation: keep edits as a thin patch layer / the glShaderSource-time downporter so upstream
   `.glsl` stay mostly pristine.
3. **MetalANGLE production-readiness:** the probe used a pbuffer; a window-surface present path + sustained
   multi-pass rendering through ANGLE is unproven at this scale. ANGLE 2.1.2440 from the Amethyst IPA is a fixed
   build — do we vendor it, or build ANGLE from source (heavier, but ours)?
4. **Perf of CPU geometry at 117HD's vertex counts:** 117HD's ZoneRenderer CPU-sorts; on the device JVM, is the
   per-frame CPU geometry cost acceptable with shadows (which re-traverse geometry)? Needs a measured spike.
5. **TBO→2D-texture repack (#1):** the one non-mechanical shader change — verify per-face data fits a 2D texture
   layout + `texelFetch` indexing matches.
6. **`gl_FragDepth` shadow-transparency packing** relies on implementation-defined float-depth behavior; validate
   on Apple GPU or ship non-transparent shadows first.
7. **Bundle/JVM memory:** 117HD + HD assets on top of the JVM heap — fits the `increased-memory-limit` budget?
8. **Scope:** this is a multi-month effort. Is the phased on-ramp (Phase A) enough value to justify starting, with
   Phase B as a longer track? Should we instead pursue **Android** for true 117HD (native GLES 3.1 + compute = no
   fork, no ANGLE) and keep iOS on Phase A + cherry-picked effects?

---

## 11. Effort sizing (rough, for sequencing — not commitments)

| Phase | Scope | Size |
|---|---|---|
| P0 / A1 | MetalANGLE backend + clip_control depth fix + MSAA | S–M (probe done; productize) |
| A2–A3 | Post color + water polish on base plugin | S–M |
| B0 | 117HD ZoneRenderer bring-up (stage, gate, downport, TBO) | **L** |
| B1 | Materials + HD textures + data pipeline + assets | **XL (the big one)** |
| B2–B3 | Lighting + normal/specular + shadows | L |
| B4–B5 | Water/caustics + dynamic/tiled lights | L |
| B6–B8 | Atmosphere + scaling/AA + extras | M–L |

---

## 12. Fallbacks & decision gates

- **If P0.1 perf is bad** (ANGLE overhead unacceptable): keep EAGL for base gpu, abandon the HD-on-iOS track, do
  HD on Android.
- **If B0 stalls** (ZoneRenderer won't come up on ES 3.0): fall back to **C1 — cherry-pick effects onto the base
  plugin** (post color [A2], a standalone shadow pass reusing base-gpu VBOs, improved water) for a partial HD look
  without 117HD's data pipeline.
- **If the asset/data pipeline (B1) is too heavy:** ship B0+B2+B3 (HD lighting + shadows on vanilla textures) as
  the iOS HD tier; full materials remain Android-only.
- **Standing recommendation:** Android is the low-friction path to *real, unmodified* 117HD (native compute). This
  blueprint is the iOS-specific route; weigh effort against doing it on Android first.

---

## Appendix A — research sources

**117HD (`github.com/117HD/RLHD@master`):** `HdPluginConfig.java` + `config/*` (feature inventory);
`HdPlugin.java`, `renderer/zone/ZoneRenderer.java`, `renderer/legacy/LegacyRenderer.java`, `SceneUploader`,
`ModelPusher`, `comp*.glsl`, `comp_common.glsl` (pipeline + compute/SSBO = geometry-only, Legacy-only);
`scene_vert.glsl`, `scene_frag.glsl`, `shadow_*`, `tiled_lighting_*`, `ui_*`, `uniforms/*.glsl` (UBOs),
`utils/*.glsl` (effects), `scaling/*` (TBO is the one zone-path ES-3.0 blocker; everything else UBO/texture/portable).
**Local base gpu + bridge:** `runelite-client/.../plugins/gpu/{GpuPlugin,Mat4,Zone,SceneUploader,FacePrioritySorter,
TextureManager,Shader}.java`, `.../gpu/*.glsl`; `ios/dev-tools/gpu-routec/app/RouteCBridge.{h,m}`,
`.../jni/routec_gl_jni.m`, `.../facade*`, `.../stage-ios-gpu-jar.sh`; `ios/app/HelloShell/{JVMLauncher,ViewController}.m`.
**MetalANGLE probe:** `ios/dev-tools/gpu-angle/` (md5-sealed verdict: ANGLE-Metal on A19 Pro, `EXT_clip_control`
works, ES 3.0 only / no compute, `GL_MAX_SAMPLES=8`).
**Backend ceiling:** ANGLE's Metal backend is ES-3.0-only by design (no ES-3.1/compute planned) — so 117HD's
compute path is permanently unavailable on ANGLE; this blueprint relies on the ZoneRenderer needing no compute.
