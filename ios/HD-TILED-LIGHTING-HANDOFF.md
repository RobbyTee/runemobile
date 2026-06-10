# Handoff → B1 Tiled Lighting (Phase 2: runtime + device)

> Branch `gpu`. Self-contained kickoff for a fresh session. Project memory auto-loads
> (`MEMORY.md` + `project_ios_gpu_track.md`) — read the tiled-lighting bullet there for full detail.
> Everything below is pushed to `origin/gpu`.

---

## Status

**B0 gate met** — 117HD renders the live OSRS scene on the iPhone 17 Pro Max (A19 Pro). **Five B1 features
shipped + device-confirmed:** shadows (DETAILED + transparency / dappled foliage), normal mapping, dynamic lights,
MSAA_4, plus 2× SSAA and the player-flashing fix. **Tiled lighting is the last big portable feature** (the user
explicitly chose it) and is at **Phase 1 done / Phase 2 = runtime + device.**

Not portable (don't attempt): wind / character displacement (compute-only — the zone renderer has no compute path
on iOS GLES 3.0). POM is portable but subtle (deferred). Deferred non-render: config persistence (`flushConfig`
never wired), `ShaderOverlay` smoothstep (dev-overlay log-noise), the "floating" perception (soft contact shadow).

## Tiled lighting — Phase 1 DONE (committed `a525d49d1`, INERT)

Phase 1 is committed but **inert** — `RLHDStarter` still forces `tiledLighting=false`, so there is **no device
behaviour change yet**. It's the host-side foundation only.

**★ KEY SHORTCUT:** the 16 KB-UBO blocker was `MAX_LIGHTS=1000` (struct is 32 B/light → 32 KB, over Apple's 16 KB
uniform-block cap). Capping `UBOLights.MAX_LIGHTS` to **480** (15360 B) fits the cap → **NO texture-backed light
store needed** (unlike `UBOMaterials`). 480 scene-wide lights is ~10× the non-tiled cap (50) and covers all but
pathologically dense scenes. `UBOLightsCulling` at 480 = 7680 B also fits.

Shipped (inert):
- `gpu-zone/java-overrides/rs117/hd/opengl/uniforms/UBOLights.java` — `MAX_LIGHTS` 1000 → 480.
- `gpu-zone/ios-overrides/tiled_lighting_frag.glsl` — ES3 port of the 185-line per-tile light-culling shader. The
  `TILED_IMAGE_STORE` branch stays `#if`'d out (no image load/store on GLES 3.0); the per-layer path
  (`out uvec4 TiledData`) is the one that compiles. Casts: `sceneResolution`/`tiledLightingResolution` are `ivec2`
  (global UBO) → `vec2(...)`; `int(ceil(pointLightsCount / 32.0))` → `float(...)`; `uvec4(0.0)` → `uvec4(0u)`.
- `gpu-zone/ios-overrides/tiled_lighting_vert.glsl` — `gl_Position` `vec4` literal floats.
- `gpu-zone/ios-overrides/utils/lights.glsl` — the `TILED_LIGHTING` `tileXY` branch `ivec2` → `vec2()` casts.

Stages clean: `cd ios/dev-tools/gpu-zone && ./stage-ios-zone-jar.sh` (9 shader overrides, 6 java-overrides).

## Phase 2 TODO

1. **GATE (host).** Extend `ios/dev-tools/gpu-zone/assemble/ZoneShaderAssemble.java` to assemble the
   `tiled_lighting` frag+vert targets **and** the scene shader at `TILED_LIGHTING=true`. New defines to add:
   `TILED_LIGHTING_LAYER` (per-layer, the pass compiles one program per layer), `TILED_IMAGE_STORE=false`,
   `TILED_LIGHTING_MAX_TILE_LIGHT_COUNT`, plus `DYNAMIC_LIGHTS=true` (already on). Verify the scene shader's
   `tiledLightingArray` `usampler2DArray` is declared / in scope. Run the **Simulator EAGL probe** (`xcodegen
   generate && xcodebuild -sdk iphonesimulator … && simctl install/launch dev.audaro.gpuzone`, sim UDID
   `45728504-BCC6-46B6-B53E-6959E4E00BF1`, pull `Documents/gpu-zone-z0-verdict.log`) → confirm compile + link
   **BEFORE any device round** (project discipline — every feature was sim-validated first).

2. **RUNTIME.** `ZoneRenderer.tiledLightingPass` renders to `texTiledLighting` (`GL_RGBA16UI` `TEXTURE_2D_ARRAY`)
   per-layer via `glFramebufferTextureLayer` (ES3.0 core, OK). **BUT** `HdPlugin.updateTiledLightingFbo`'s initial
   attach is `glFramebufferTexture` (the **LAYERED** version — GL 3.2 / ES 3.2, **NOT in GLES 3.0**) → needs a
   **bridge no-op / handle** in `gpu-routec/jni/routec_gl_jni.m` or `gpu-zone/jni/routec_zone_jni.m` (the per-layer
   path re-attaches via `glFramebufferTextureLayer` anyway, so the layered attach is only for the image-store path
   that's off on iOS). Verify integer-array (`RGBA16UI`) **color rendering** works on iOS. Multiple
   `tiledLightingShaderPrograms` (one per layer) compile via the runtime `RouteCBridge.downportShaderSource`.

3. **ENABLE.** `runelite-client/src/main/java/net/runelite/client/headless/RLHDStarter.java` — flip
   `tiledLighting` `false` → `true` (the file has a user BSD license header — keep it). `dynamicLights` is already
   `SOME`; tiled uses `getTiledLightingLayers()` + `getMaxSceneLights()`.

4. **REBUILD.** `RLHDStarter` is shared-core (base shaded jar), so:
   `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :client:shadowJar -q` →
   `cd ios/dev-tools/gpu-routec && JAVA_HOME=… ./stage-ios-gpu-jar.sh` (FACADE_SRC now **defaults to the superset
   `../gpu-zone/facade`** — do NOT override, or RLHD dies `NoClassDefFound org/lwjgl/system/Platform`) →
   `cd ../gpu-zone && JAVA_HOME=… ./stage-ios-zone-jar.sh` →
   `xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell -destination "generic/platform=iOS"
   -derivedDataPath ios/app/build/dd-gpu -allowProvisioningUpdates build` →
   `xcrun devicectl device install app --device AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B
   ios/app/build/dd-gpu/Build/Products/Debug-iphoneos/HelloShell.app` ("No provider was found" line is non-fatal).

5. **DEVICE ROUND.** User attaches **StikDebug** + taps **`▶ 117HD (Zone)`**. **GL errors are INVISIBLE on device**
   (RLHD `checkGLErrors` is gated behind `OpenGL43=false`) → reason from the ES3 spec + the visual symptom, not
   logs. Pull `Documents/jvm-stdio.log` (the headless stdout — **NOT** `client.log`, which is empty) and read the
   **LAST** session only (it's cumulative). Expect: in dense-light areas (cities at night, torch-lined rooms) the
   whole scene lights up (vs ~50 nearby with non-tiled). In sparse Lumbridge there may be little visible change.

### Phase 2 risks
The ES3.2 layered FBO attach (`glFramebufferTexture`), integer-texture-array (`RGBA16UI`) color rendering on iOS,
the per-layer pass correctness, the scene shader's `tiledLightingArray` binding/declaration, and per-layer program
count (each layer is a separate program).

## The per-feature port template (proven 5×)
1. Port the cast-heavy include(s) to `ios-overrides/` ES3 (or reuse downport rules for isolated casts).
2. **Simulator EAGL probe** to confirm compile + link BEFORE the device round.
3. Fix the runtime GL — the ES3.2-only enums/formats Apple rejects, remapped in the bridge (precedents:
   `CLAMP_TO_BORDER`→`CLAMP_TO_EDGE`, `TEXTURE_BORDER_COLOR` no-op, `DEPTH_COMPONENT24`+`FLOAT`→`UNSIGNED_INT`).
4. Enable in `RLHDStarter`.
5. Device-confirm; reason from spec + symptom (no GL-error logs on device).

## Conventions
- Commit + push under **Jordan Simpson** only; **no AI attribution** anywhere (scrub staged content first).
- Auto-commit at logical checkpoints (standing authorization).
- Device UDID `AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B`; full app needs StikDebug per launch (JIT). The `gpu-zone` Z0/
  Simulator probe needs NO StikDebug (pure EAGL).
- The staged jars (`client-*-shaded.jar`, `client-*-rlhd.jar`) are gitignored build artifacts (regen via the stage
  scripts). The `gpu-zone/assembled/*.glsl` gate outputs ARE tracked.
