# gpu-zone — iOS Zone Z0 conformance harness (RLHD zone shaders → ES 3.00 on EAGL)

> Branch `gpu`. Phase **Z0** of `ios/HD-ZONE-B0-SCOPE.md` / `ios/HD-PORT-BLUEPRINT.md` §5: a fail-closed gate that
> proves the **entire RLHD zone-path shader surface compiles on native EAGL GLES 3.0** before any renderer work.
> Clones the proven `gpu-b1` pattern (xcodegen + EAGL offscreen compile + md5-sealed verdict). Backend = **EAGL**
> (the 2026-06-09 device A/B locked EAGL + `gl_FragDepth`; ANGLE is not used).

## Layout

- `rlhd-src/` — vendored RLHD @ `7dded59` (BSD-2): `shaders/hd/**/*.glsl` (47 zone shaders, `#include`/`#define`
  unresolved) + `java/…/shader/{ShaderIncludes,ShaderTemplate,ShaderException}.java` (the assembler) + `LICENSE` +
  `PROVENANCE.md`.
- `app/` — the probe app: `ZoneDownport.{h,m}` (GLSL→ES 3.00 rewriter), `ZoneProbe.{h,m}` (compile loop), `main.m`.
- `assembled/` — RLHD-Template-assembled shaders, bundled flat as `<name>.<stage>.assembled.glsl`. **Empty until the
  assemble step exists** (see Status).
- `project.yml` — xcodegen (`xcodegen generate` → `GpuZone.xcodeproj`).
- `gpu-zone-z0-verdict.sh` — md5-gated verdict over the pulled `gpu-zone-z0-verdict.log`.

## The pipeline

```
RLHD .glsl (#include/#define)  ──assemble──►  flat desktop GLSL (#version 420/430)
                                                      │  ZoneDownport
                                                      ▼
                                            GLSL ES 3.00  ──glCompileShader──►  EAGL ES3 (Sim → device)
```

## ZoneDownport — what's implemented (`app/ZoneDownport.m`)

Superset of the base-gpu B1 downporter. Per the blueprint §5 blocker list:
1. `#version <any>` (RLHD injects **420/430**, not 330) → `#version 300 es`  *(#7)*
2. inject `precision …` qualifiers (float/int/sampler2DArray/sampler2D/**isampler2D/usampler2D**)
3. drop `noperspective`
4. strip float-literal `f`/`F` suffixes
5. **`textureFaces` texture-buffer → 2D `isampler2D` repack** (3 rules, proven byte-exact in `gpu-tbo2d`)  *(#1)*
6. explicit int↔float casts — **site set discovered from Z0 compile errors** (empty until the compile loop runs;
   prefer hand-maintained variants for the cast-heavy files per the blueprint).

## Status — done / in progress / remaining

**Done:** vendored RLHD source; the **assemble step (`assemble/ZoneShaderAssemble.java`)** — a dependency-free
host-JVM replica of RLHD's `ShaderIncludes.parse()` that resolves all `#include`/`#define` from the fixed
minimal-config define map AND applies the **GLSL 330→ES 3.00 downport** (the single source of truth for the rules;
`app/ZoneDownport.m` is now superseded for the gate — the probe compiles the pre-downported `assembled/` shaders
as-is). The `MAX_SIMULTANEOUS_WORLD_VIEWS`/`WORLD_VIEW_GETTER` overrides `ZoneRenderer.addShaderIncludes()` does and
the `MATERIAL_CONSTANTS` named-material defines are replicated in the config. **`ios-overrides/`** shadow-dir
(resolved before `rlhd-src/`) holds hand-maintained ES3 copies of pervasively cast-heavy files
(`utils/color_utils.glsl`). **`glslang-check.sh`** = an offline fast-filter (glslang at `#version 310 es` to suppress
its interface-block false-positives) that reports many #13 sites per pass — works for shadow/ui, but **not scene**
(glslang bails at scene's in/out interface blocks, which Apple's EAGL accepts).

**Gate run (Apple Simulator ES 3.0 = the authority): 5/6 COMPILE** — `scene.vert`, `shadow.vert`, `shadow.frag`,
`ui.vert`, `ui.frag`. The `#version`-first, precision, zero-array, MAT_*-constant, and most #13 classes are cleared.

**Remaining — `scene.frag` only (2253 lines).** Its tail of #13 sites lives in the water/uvs/lighting includes
(`worldUvs(int)`, `/128`, `/255`, `/127`, `N * waterType.duration`, …) — many sites. Because glslang can't pre-scan
scene (interface blocks), this is an **Apple-sim one-error-per-round grind**; `utils/water.glsl` + `utils/uvs.glsl`
are good **ios-override** candidates (like `color_utils`) to clear clusters at once. Once `scene.frag` compiles:
extend the probe to **link** the vert+frag programs (not just compile), **device confirm** (Sim compiler ≠ A19), then
seal the md5 in `gpu-zone-z0-verdict.sh`.

**Iterate loop:** edit `assemble/ZoneShaderAssemble.java` (CAST rules) or add an `ios-overrides/` file →
`cd assemble && javac ZoneShaderAssemble.java && java ZoneShaderAssemble` → for shadow/ui: `./glslang-check.sh`
(fast); for scene: `xcodegen generate && xcodebuild -sdk iphonesimulator … && simctl install/launch
dev.audaro.gpuzone` → pull `Documents/gpu-zone-z0-verdict.log`.

---

### Reference — the assembler's fixed define-list (`HdPlugin.getShaderIncludes()` @ `7dded59`)

```
addIncludePath(SHADER_PATH)
addInclude  VERSION_HEADER = "#version 420\n…"  (Linux) | "#version 430\n" (Windows)  ← downporter rewrites anyway
define UI_SCALING_MODE, COLOR_BLINDNESS, APPLY_COLOR_FILTER
define MATERIAL_COUNT (= MaterialManager.MATERIALS.length), WATER_TYPE_COUNT
define DYNAMIC_LIGHTS, TILED_LIGHTING, TILED_LIGHTING_LAYER_COUNT, TILED_LIGHTING_TILE_SIZE, MAX_LIGHT_COUNT
define NORMAL_MAPPING, PARALLAX_OCCLUSION_MAPPING
define SHADOW_MODE, SHADOW_TRANSPARENCY, SHADOW_FILTERING, SHADOW_RESOLUTION
define VANILLA_COLOR_BANDING, UNDO_VANILLA_SHADING, LEGACY_GREY_COLORS, DISABLE_DIRECTIONAL_SHADING, FLAT_SHADING
define WIND_DISPLACEMENT(+NOISE_RESOLUTION), CHARACTER_DISPLACEMENT, MAX_CHARACTER_POSITION_COUNT, WIREFRAME
define WINDOWS_HDR_CORRECTION
define LEGACY_RENDERER=false, ZONE_RENDERER=true          ← we are the zone renderer
define MAX_SIMULTANEOUS_WORLD_VIEWS=0, WORLD_VIEW_GETTER=""
addInclude MATERIAL_CONSTANTS (#define MAT_<NAME> getMaterial(idx) … from MaterialManager.MATERIAL_MAP)
addInclude MATERIAL_GETTER  = generateGetter("Material", MATERIAL_COUNT)
addInclude WATER_TYPE_GETTER = generateGetter("WaterType", WATER_TYPE_COUNT)
addUniformBuffer  uboGlobal, uboLights, uboLightsCulling, uboUI, uboMaterials, uboWaterTypes   ← std140 block emit
renderer.addShaderIncludes(includes)   ← ZoneRenderer-specific includes
```

The material/UBO dynamic includes (`MATERIAL_CONSTANTS`, the getters, the `addUniformBuffer` std140 blocks) need
minimal data — either drive them from the vendored material/UBO Java (pull from the pinned rev) or stub to a small
fixed set for the flat-scene gate, then grow with B1. Once `assembled/` is populated: `xcodegen generate`, build for
the Simulator (cheap inner loop), pull the log, iterate rule #6 until clean, then **device confirm** (Sim compiler ≠
A19), then seal the md5.

## Run (once assembled/ is populated)

```sh
cd ios/dev-tools/gpu-zone && xcodegen generate
xcodebuild -project GpuZone.xcodeproj -scheme GpuZone -sdk iphonesimulator \
  -derivedDataPath build -destination 'generic/platform=iOS Simulator' build
# boot a sim, install, launch, then:
xcrun simctl get_app_container booted dev.audaro.gpuzone data   # → Documents/gpu-zone-z0-verdict.log
./gpu-zone-z0-verdict.sh <pulled-log>
```
