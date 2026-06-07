# Blueprint — 117HD-quality rendering on iOS via MetalANGLE (Path C)

> **Status:** proposal, v2 (revised after external review against RLHD `master`@`7dded59` + local Route C/probe).
> Branch `gpu`. Not yet executed.
> **Goal:** bring the full 117HD (RLHD) look to the on-device iOS client as native settings, by porting 117HD's
> **modern (Zone) renderer** onto an **OpenGL ES 3.0 / MetalANGLE** backend — not by reimplementing effects.
> **Review verdict carried into v2:** no fatal "Zone needs GL 4.3 compute/SSBO/geometry for visuals" blocker was
> found, so the approach is viable — **but this is a renderer + backend + data-system port, not a shader-only
> downport**, and portability must be *proven* by conformance + performance gates (below) before it is claimed.

## Revision note (v2) — what the review changed
- The ES-3.0 blocker list was **incomplete**: added `GL_DRAW_INDIRECT_BUFFER` (bound even when indirect draw is
  off), `glFramebufferTexture` (ES 3.0 has only `glFramebufferTextureLayer`), `#version 420/430` (our downporter
  only rewrites `330`), `RGBA16UI` 2D-array FBO renderability (must probe, not assume), `GL_CLAMP_TO_BORDER`
  (incl. the dummy-shadow path), and 117HD's own `glMultiDrawArrays` usage.
- Factual fixes: the **shadow pass is normal-Z** (clearDepth 1 / `LEQUAL`); only the **scene pass is reverse-Z**
  (clearDepth 0 / `GEQUAL`). Scene color format is **dynamic** (not always `RGBA8`). The **resolve FBO is
  conditional** (only `msaaSamples>1 && resolutionScale!=1`).
- **clip_control is not auto-provided by RLHD's zone path** — we must wire `glClipControlEXT(ZERO_TO_ONE)`
  intentionally (proven to work + improve depth by the `gpu-angle` probe).
- The **backend swap is not self-contained**: it touches the present path and requires expanding the LWJGL
  facade/caps for RLHD's larger GL surface.
- The **data/asset workstream is larger** than stated (more JSON roots, active loader systems with RuneLite-API
  coupling, real licensing constraints) — and licensing is a **gate**, not a "later."
- Added gates: a **Zone ES3 conformance gate** before B0, a **facade-expansion (source/bytecode scan)** step, and a
  **staged perf microbenchmark**. The plan only claims iOS portability *after* these pass.

---

## 1. Goal & success criteria

**Done:** on iPhone 17 Pro Max (A19 Pro, iOS 26.5), OSRS renders with 117HD's signature qualities — HD
textures/materials w/ normal+specular, ground blending, directional **shadows**, **HD water** (animated normals,
fresnel, foam, caustics, depth color), ambient+directional+**point/dynamic** lighting w/ per-area environmental
lighting, distance/ground **fog**, **post** (tone/gamma, color filters, saturation/contrast, colorblind), **MSAA** —
playable + thermally sustainable, each feature a native toggle.

**Non-goals:** byte parity with desktop 117HD; the Legacy renderer / compute path; Windows/Linux specifics.

**Methodology:** every milestone ends in an md5-sealed, anti-fabrication device verdict (like `gpu-angle`/`gpu-b1`):
a pulled-from-device log + a screenshot A/B + a perf sample; no phase merges with a non-ES3 symbol, a regression, or
a red conformance/perf gate.

---

## 2. The enabling finding — 117HD's modern renderer has no GL-4.3 *visual* killer

From a line-by-line read of RLHD `master` (Appendix A), **confirmed by the review at `7dded59`**:

| | **ZoneRenderer** (default, `legacyRenderer=false`) | **LegacyRenderer** (NOT used) |
|---|---|---|
| Compute shaders | **none** | `comp.glsl` / OpenCL on Apple |
| Shader SSBOs | **none** | 7 `std430` (geometry only) |
| Geometry shaders | **none** (legacy subclasses only) | `scene_geom`, `shadow_geom` |
| GL gate (`supportsGpu`) | `glCaps.OpenGL33` (`ZoneRenderer.java:167`) | `OpenGL43` / `OpenGL31`(OpenCL) |
| Look-data feed | **std140 UBOs** + `sampler2DArray` **+ a `GL_TEXTURE_BUFFER` face-data path** ⚠ | same |

The look lives in `scene_vert/frag.glsl` + `utils/*.glsl` + `shadow_*`, `tiled_lighting_*`, `ui_*`, `scaling/*`,
fed by UBOs and textures. The compute/SSBO subsystem is purely geometry sort/push, which the ZoneRenderer already
does on the **CPU** — the role our base-gpu `SceneUploader/Zone/FacePrioritySorter` already fill.

**⚠ Caveat the review added:** "all ES-3.0 core" is too strong. The zone *visual* path also feeds per-face data via
`uniform isamplerBuffer textureFaces` (a `GL_TEXTURE_BUFFER`, `scene_vert.glsl:43`, `shadow_vert.glsl:42`,
`GLTextureBuffer.java:15`) — **not in ES 3.0**. So the visual path is portable, but not ES-3.0-clean as-is; it needs
the repack in §5 (#1) plus the additional fixes the review found.

---

## 3. Target architecture

```
RuneLite engine (headless on-device JVM)
   │ DrawCallbacks (drawZoneOpaque/Alpha, drawPass, postSceneDraw, draw)
   ▼
117HD ZoneRenderer (Java, downported)   ◄── scene data: environments/lights/materials/ground_materials/
   │ CPU SceneUploader + alpha sort          model_overrides/areas/tile_overrides/gamevals JSON + HD texture atlas
   │ org.lwjgl.opengl GL3x/GL45 calls         (active loader systems — see §7)
   ▼
Route C facade (EXPANDED for RLHD) ──► JNI table (EXPANDED) ──► MetalANGLE (NEW: EGL, replaces EAGL)
                                                                     │ clip_control(ZERO_TO_ONE) ✓ (wired by us)
                                                                     ▼ ANGLE → Metal → A19 Pro GPU  (ES 3.0)
```

**Per-frame passes (from `ZoneRenderer.draw()` ~`:1087/:1094/:1127`):**
1. **Tiled-lighting pass** — *optional* (`tiledLighting && dynamicLights!=NONE`); has an **image-store fast path
   and a per-layer FBO fallback** (`ZoneRenderer.java:678`). Target `texTiledLighting` = `RGBA16UI`
   `TEXTURE_2D_ARRAY`. **Use the fallback; renderability must be probed (§5 #4).**
2. **Directional shadow depth pass** — depth-only FBO, `texShadowMap` (`DEPTH_COMPONENT24` 2D), **normal-Z**:
   `clearDepth(1)` + `GL_LEQUAL` (`ZoneRenderer.java:724/736`). Replays geometry from the sun matrix.
3. **Scene pass** — `fboScene`: MSAA color renderbuffer (**format chosen dynamically** from
   `RENDERBUFFER_FORMATS_LINEAR`, `HdPlugin.java:1302`) + `DEPTH_COMPONENT32F` depth, **reverse-Z**:
   `clearDepth(0)` + `GL_GEQUAL` (`ZoneRenderer.java:776/785`), with **`clipControl(ZERO_TO_ONE)` that WE wire**.
4. **Resolve + present** — `glBlitFramebuffer` `fboScene → fboSceneResolve` **only when `msaaSamples>1 &&
   resolutionScale!=1`** (`HdPlugin.java:1356`), then blit → default FBO (bridge offscreen FBO / CAEAGLLayer),
   scaled by `sceneScalingMode`.
5. **UI pass** — composite engine interface texture (existing path) + optional tiled-lighting overlay.

---

## 4. Prerequisites (Phase 0) — on the base plugin first

**P0.1 — Productize the MetalANGLE/EGL backend (HARD prerequisite, incl. a window-surface test).** Replace the
EAGL/CAEAGLLayer context/FBO/present in `RouteCBridge.{h,m}` (`:9/:75`) with an EGL path on ANGLE (probe is the
reference: `eglGetPlatformDisplayEXT` Metal). **This is not just context creation** — it touches the present path
and the facade/caps; do a **window-surface present test** (not only the probe's pbuffer). Immediate wins on the
**base gpu plugin**, independent of 117HD:
- Turn the `glClipControl` shim from **no-op → real `glClipControlEXT(LOWER_LEFT, ZERO_TO_ONE)`** → river depth
  z-fight fixed at the root; strip the `gl_FragDepth` diagnostics.
- Enable **MSAA** (`GL_MAX_SAMPLES=8`) → distant aliasing fixed.
- **Gate P0.1:** base gpu renders on a MetalANGLE **window surface**; depth z-fight gone; MSAA on; perf ≥ EAGL
  (measure, §9). Screenshot A/B.

**P0.2 — clip_control is ours to wire.** RLHD's zone path does not itself call `glClipControl` (the call is in the
base RuneLite plugin, `GpuPlugin.java:356`); the ZoneRenderer relies on reverse-Z matrices (`Mat4.java:120`). So
the port must **intentionally** issue `glClipControlEXT(ZERO_TO_ONE)` for precise reverse-Z. Validate it doesn't
double-map vs RLHD's projection (the base plugin is designed to run with-or-without clip_control; confirm RLHD is
too).

> Phase 0 is independently valuable: P0.1 alone delivers the base plugin's depth fix + AA even if Phase B stalls.

---

## 5. ES 3.0 conformance — the *complete* blocker list (v2)

These are the constructs that are NOT ES-3.0/iOS-ANGLE and must be handled. **The review found this list is not
shader-only — there are Java/GL-side blockers too.**

| # | Blocker | Where | Fix | Class |
|---|---|---|---|---|
| 1 | `isamplerBuffer textureFaces` (texture-buffer object) | `scene_vert.glsl:43`, `shadow_vert.glsl:42`, `GLTextureBuffer.java:15` | repack per-face data into `usampler2D`/`isampler2D`, `texelFetch` by computed (x,y) | shader+Java (the big one) |
| 2 | **`GL_DRAW_INDIRECT_BUFFER` bound unconditionally** (even with indirect draw off) | `ZoneRenderer.java:252`, `RenderState.java:106` | make the bind/alloc conditional; ES 3.0 has no draw-indirect target | Java |
| 3 | **`glFramebufferTexture`** (layered attach) for the tiled-lighting array | `HdPlugin.java:1233` | ES 3.0 has only `glFramebufferTextureLayer` → use the per-layer path | Java |
| 4 | **`RGBA16UI` 2D-array as a color attachment** (tiled lighting fallback) | `tiled_lighting_frag.glsl:11` | **must probe** color-renderability on ANGLE-Metal before relying on it | probe |
| 5 | `glMultiDrawArrays` (117HD's own usage) | `Zone.java:831`, `DynamicModelVAO.java:292` | existing Route C shim loops it (`RouteCBridge.m:237`) — confirm it covers RLHD's call sites | covered, verify |
| 6 | `GL_CLAMP_TO_BORDER` + border color (real + **dummy** shadow map) | `HdPlugin.java:1430/1450` | clamp-to-edge + in-shader border test | Java+shader |
| 7 | **`#version 420/430`** injected headers | `HdPlugin.java:343` | downporter currently rewrites only `#version 330` (`RouteCBridge.m:284`) → extend to 420/430; prefer reusing RLHD's own `Template`/`#include` and post-processing to `#version 300 es` | bridge |
| 8 | Geometry shaders | `scene_geom`, `shadow_geom` | **avoid** — legacy-only (`SceneShaderProgram.java:15`, `ShadowShaderProgram.java:46`) | none |
| 9 | Compute + SSBO geometry sort | `comp*.glsl`, `comp_common.glsl:69` | **avoid** — zone path CPU-sorts | none |
| 10 | image-store tiled-lighting fast path | `tiled_lighting_frag.glsl` | use the fragment fallback (#3/#4) | small |
| 11 | `glDrawBuffer`/`glReadBuffer` singular | `HdPlugin.java:1438` | `glDrawBuffers` plural | trivial |
| 12 | `scaling/hybrid.glsl` `vec2 *= ivec2` | UI upscaler | explicit `vec2(ivec2)` cast | one line |
| 13 | int↔float implicit-conversion strictness | many `.glsl` | explicit casts (same class as base-gpu B1 downport) | tedious |
| 14 | `supportsGpu` `OpenGL33` gate | `ZoneRenderer.java:167` | facade reports `OpenGL33=true` (same trick as base-gpu L1) | trivial |

**Not blockers (ES-3.0-core, confirmed):** std140 UBOs, `usampler2DArray`/`isampler*`, `texelFetch`, `textureSize`,
`flat`, in/out interface blocks, `dFdx/dFdy/fwidth`, `inverse()`, non-square matrices, `gl_FragDepth`, `switch`,
integer bit-ops. Shadows use custom PCF via `texelFetch` (no `sampler2DShadow`). Water is screen-space (no
reflection/refraction targets). Post is sRGB+gamma+HSV (no ACES/Reinhard).

### NEW — Phase Z0: Zone ES3 conformance gate (run before B0)
A mechanical, fail-closed gate that proves the shader/GL surface *before* renderer work:
1. Run **every** zone-path shader (`scene_*`, `shadow_*`, `tiled_lighting_*`, `ui_*`, `utils/*`, `scaling/*`,
   `uniforms/*`) through the (upgraded) downporter and **compile each on the device via ANGLE** (extend the
   `gpu-b1` harness). **Fail on any non-ES3 symbol.**
2. ANGLE renderability probes (extend `gpu-angle`): `RGBA16UI` 2D-array color attachment (#4), `glClipControlEXT`
   reverse-Z precision in a real FBO, `DEPTH_COMPONENT24` shadow sampling, clamp-to-edge border handling.
3. Static scan of RLHD bytecode/source for GL calls not in the facade (drives §8). Output: the exact delta to
   implement. **Gate Z0:** all zone shaders compile on device + all probes green + facade delta enumerated.

---

## 6. Phased delivery (each milestone = native setting + device gate)

### Phase A — base-plugin on-ramp (weeks)
- **A1** P0.1 backend swap → depth fix (clip_control) + MSAA. *(half-proven)*
- **A2** Post color on the base render (tone/gamma, `ColorFilter` set, saturation, contrast, colorblind) — operates
  on the final image, **no scene data**; add a post-process FBO at the `blitSceneFbo` seam.
- **A3** Base water sampling polish (trilinear + `textureGrad` done; add fresnel/animated-normal if cheap).
- *Gate A:* base plugin visibly better; shippable interim.

### Phase Z0 — conformance gate (see §5) — **before any B work**

### Phase B — 117HD ZoneRenderer port (multi-month)
- **B0 — Renderer bring-up:** stage the RLHD ZoneRenderer into the app (`stage-ios-gpu-jar.sh` analogue), facade
  `OpenGL33=true`, downport shaders, repack the texture-buffer (#1), make draw-indirect conditional (#2). **B0 is
  not shader-only** — even a flat scene needs minimal UBO + texture-face + a stub material/water plumbing.
  *Gate: ZoneRenderer renders the scene (even flat-shaded) on device.*
- **B1 — Materials & HD textures + the data system:** the **largest** phase — see §7. Sub-phases:
  B1a loaders + data (materials/ground_materials/model_overrides/areas/tile_overrides/gamevals JSON + RuneLite
  texture-provider coupling); B1b **licensing/bundle policy decision (gate — must pass before vendoring assets)**;
  B1c texture atlas + compression; B1d enable `objectTextures/groundTextures/groundBlending/textureResolution/
  anisotropic`.
- **B2 — Lighting:** ambient+sun+sky, `normalMapping`, specular, `environmentalLighting` (environments.json).
- **B3 — Shadows:** normal-Z depth pass + custom PCF (`ShadowMode/Resolution/Filtering`, clamp-to-edge per #6);
  start non-transparent (validate `gl_FragDepth` packing on Apple GPU).
- **B4 — Water & caustics:** WaterType params, animated normals, fresnel, foam, depth color, `underwaterCaustics`.
- **B5 — Dynamic + tiled lighting:** lights.json (`DynamicLights`), then **tiled lighting LAST** (its `RGBA16UI`
  array-FBO path is the least-proven ES3/ANGLE piece — only after #4 probe is green and B0–B4 are stable).
- **B6 — Atmosphere:** fog model, ground fog, sky color, seasonal themes.
- **B7 — Quality/scaling:** `sceneResolutionScale`, `sceneScalingMode`, `uiScalingMode` (fix hybrid.glsl), AA
  levels.
- **B8 — Extras:** POM, wind/character displacement, removeVertexSnapping, banding, accessibility, frame pacing.

---

## 7. Scene data & HD asset pipeline — the largest workstream (expanded per review)

117HD's look is **data-driven**, via **active loader systems** (not passive assets) with RuneLite-API coupling:
- **JSON roots:** `environments.json`, `lights.json` (~48k lines), `materials.json`, `ground_materials.json`,
  `model_overrides.json` (~89k lines), **`areas.json`, `tile_overrides.json`, `gamevals.json` (~94k lines)** —
  the last three were missing from v1.
- **Systems:** `GamevalManager` (`:73`) resolves game values that `ModelOverrideManager` (`:57`) depends on;
  `AreaManager` (`:58`) + `TileOverrideManager` (`:72`) drive scene-reload behavior. These run at runtime, coupled
  to RuneLite's client/DB.
- **Texture ingestion** is coupled to RuneLite's **texture provider** + Java image processing
  (`TextureManager.java:111/191`) — not a static file copy.
- **Licensing is a GATE, not "later":** `scene/textures/000_licenses.txt` includes Jagex-derived / fan-content-
  policy material and **project-specific grants** (`:88/:117`). Bundling 117HD's textures into our app needs an
  explicit licensing decision **before** B1c. (If it fails, B0–B3 still give HD lighting/shadows on vanilla
  textures.)

This is a **renderer + data-system port**, and B1 is its own multi-week effort. Reviewers: scrutinize app-bundle
budget, the loaders' RuneLite-API coupling under our headless client, gameval/DB availability on device, and the
licensing of redistributing assets.

---

## 8. Facade / bridge expansion (now a phase, driven by a scan)

The base gpu used ~85 GL calls; RLHD uses more. **Phase: scan RLHD source/bytecode for every `org.lwjgl.opengl`
call (the Z0 step 3 output), diff against `GLALL.java`/`kGLALL[]`, implement the delta.** Known additions:
- FBO/MRT: `glDrawBuffers`, `glFramebufferTextureLayer` (not `glFramebufferTexture`), depth-texture attach.
- `glClipControlEXT` (real, replacing the no-op shim).
- UBO mgmt: `glBindBufferRange/Base`, `glGetUniformBlockIndex`, `glUniformBlockBinding` (some present).
- Uniforms: `glUniform3fv`/`glUniformMatrix3fv`/`glUniform1iv` (light dirs/normal matrices/material indices).
- Textures: `RGBA16UI` array creation; shadow sampler params (clamp-to-edge).
- Draw: confirm `glMultiDrawArrays` shim coverage; **gate/strip `GL_DRAW_INDIRECT_BUFFER`** for ES 3.0.
- Downporter: handle `#version 420/430`, unsigned-integer samplers, the TBO→2D rewrite, RLHD shader idioms — prefer
  reusing RLHD's `Template` and post-processing the output to `#version 300 es`.

---

## 9. Performance, thermal & validation (concrete gate per review)

The existing Route C perf evidence is the **base** single-pass GPU plugin — **not** RLHD's multi-pass renderer with
CPU alpha-sort (`Zone.java:687`, `StaticAlphaSortingJob.java:43`, `FacePrioritySorter.java:66`) + dynamic model
streaming / async upload + a second geometry traversal for shadows (`ModelStreamingManager.java:117/299`), all
through JNI→ANGLE→Metal. **A dedicated Zone microbenchmark is required before claiming playability.**

**Staged perf gate (each = device measurement, sustained 10-min for thermals):**
1. one **static zone** (geometry only, no shadows/lights),
2. **alpha-heavy** scene (stress the CPU alpha sort),
3. **+ shadows** (the second traversal),
4. **+ tiled/dynamic lights**.
Expose quality knobs (shadow res, draw distance, dynamic-light count, resolution scale) so users trade. Xcode Metal
GPU frame capture (a MetalANGLE benefit) for per-pass cost. **No claim of "portable/playable" until stages 1–4 pass.**

---

## 10. Risks, unknowns & questions for review

1. **Data/asset pipeline (highest):** bundle size, loader/RuneLite-API + gameval/DB coupling on device, **asset
   licensing** (Jagex-derived/granted), keeping `model_overrides`/`gamevals` current. (§7.)
2. **ES3 conformance unknowns:** `RGBA16UI` array-FBO renderability on ANGLE-Metal (#4), `GL_DRAW_INDIRECT_BUFFER`
   removal (#2), TBO→2D repack correctness (#1), `gl_FragDepth` shadow-transparency packing on Apple GPU.
3. **Backend production-readiness:** window-surface present + sustained multi-pass through ANGLE 2.1.2440 (vendored
   from the Amethyst IPA) — vendor it or build ANGLE from source?
4. **Perf:** CPU geometry/alpha-sort at RLHD vertex counts on the device JVM + shadow re-traversal (§9).
5. **Upstream divergence/maintenance:** this forks RLHD; keep edits as a thin downporter/patch layer.
6. **Facade surface:** the RLHD GL-call delta may be larger than enumerated until the Z0 scan runs.
7. **Scope decision:** is Phase A + a partial B worth starting, vs doing **real, unmodified 117HD on Android**
   (native GLES 3.1 + compute — no fork, no ANGLE)?

---

## 11. Effort sizing (rough)

| Phase | Scope | Size |
|---|---|---|
| P0/A1 | MetalANGLE backend (window surface) + clip_control + MSAA | S–M |
| A2–A3 | Post color + water polish on base | S–M |
| Z0 | Zone ES3 conformance gate (shader compile + probes + facade scan) | M |
| Facade expansion | implement the scanned GL-call delta | M |
| B0 | ZoneRenderer bring-up (stage, downport, TBO, draw-indirect, min plumbing) | **L** |
| B1 | Materials + data systems + assets + **licensing gate** | **XL (the big one)** |
| B2–B3 | Lighting + normal/specular + shadows | L |
| B4–B5 | Water/caustics + dynamic/tiled lights | L |
| B6–B8 | Atmosphere + scaling/AA + extras | M–L |

---

## 12. Fallbacks & decision gates

- **P0.1 perf bad** → keep EAGL for base gpu; abandon iOS HD; do HD on Android.
- **Z0 red** (a non-ES3 symbol or a failed renderability probe with no workaround) → that subsystem is cut or
  re-scoped before B starts.
- **B0 stalls** → fall back to **C1: cherry-pick effects onto the base plugin** (post color [A2], a standalone
  shadow pass reusing base-gpu VBOs, improved water) for a partial HD look without RLHD's data pipeline.
- **B1 licensing/size fails** → ship B0+B2+B3 (HD lighting + shadows on vanilla textures) as the iOS HD tier;
  full materials stay Android-only.
- **Standing recommendation:** Android is the low-friction path to real, unmodified 117HD (native compute). This is
  the iOS-specific route; weigh effort against doing it on Android first.

---

## Appendix A — research & review sources
**RLHD** `github.com/117HD/RLHD` (audited `master`; review at `7dded59`): `HdPluginConfig.java`+`config/*` (features);
`HdPlugin.java`, `renderer/zone/ZoneRenderer.java`, `renderer/legacy/LegacyRenderer.java`, `opengl/RenderState.java`,
`opengl/shader/{Scene,Shadow}ShaderProgram.java`, `renderer/zone/{Zone,DynamicModelVAO,StaticAlphaSortingJob,
FacePrioritySorter,ModelStreamingManager}.java`, `scene/{Gameval,ModelOverride,Area,TileOverride,Texture}Manager.java`,
`utils/buffer/GLTextureBuffer.java`, `utils/Mat4.java`; shaders `scene_*`, `shadow_*`, `tiled_lighting_*`, `ui_*`,
`uniforms/*`, `utils/*`, `scaling/*`, `comp*`; data `scene/*.json`, `scene/textures/000_licenses.txt`.
**Local:** `runelite-client/.../plugins/gpu/*`; `ios/dev-tools/gpu-routec/{app/RouteCBridge.{h,m},jni/routec_gl_jni.m,
facade*,stage-ios-gpu-jar.sh}`; `ios/dev-tools/gpu-angle/` (md5-sealed: ANGLE-Metal A19 Pro, `EXT_clip_control`
works, ES 3.0 only / no compute, `GL_MAX_SAMPLES=8`); iOS SDK `OpenGLES/ES3/gl.h` (confirms no `glFramebufferTexture`,
no `GL_DRAW_INDIRECT_BUFFER`, no texture-buffer in ES 3.0).
