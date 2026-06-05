# GPU B1 — result (part 1 of 2): the shader-downport gate is GREEN

> Branch `gpu`. This settles the **"real gate"** the GPU brief called out: *do RuneLite's actual base-`gpu`-plugin
> shaders survive a downport to GLSL ES 3.00 and compile + render on a real native GLES 3.0 driver?* **Yes.**
> The second half of B1 — standing up **LWJGL bound to iOS's EAGL context** — is a separate, multi-week native
> port and is briefed for review in `ios/AUDIT-BRIEF-w3-gpu-b1-lwjgl-ios.md` (NOT yet built).

## Verdict

**GO** on the shader downport. md5-gated `B1_SHADER_GATE_GREEN_AS_EXPECTED`
(`ios/dev-tools/gpu-b1/gpu-b1-verdict.sh`; log `evidence/gpu-b1-sim-verdict-20260605.log`,
md5 `56e6ef3ceeaa3458897b1b0da5df6615`). On the iOS Simulator's real GLES 3.00 driver
(`GL_VERSION = OpenGL ES 3.0 APPLE-23.1.1`, `GLSL ES 3.00`):

| program | shaders | compile | link | validate | render→FBO→readback |
| --- | --- | --- | --- | --- | --- |
| SCENE | `vert.glsl` + `frag.glsl` | OK | OK | OK | non-blank, HSL colour `(106,208,213)`, 722/4096 px (≈ test-triangle area) |
| UI | `vertui.glsl` + `fragui.glsl` | OK | OK | OK | exact texture `(40,80,160,255)`, 4096/4096 px |

Both **real, unmodified** plugin shaders (assembled by RuneLite's own `Template`, then downported) run on native
GLES 3.0 — **no ANGLE, no GL→Metal layer needed** for the base `gpu` plugin. This is consistent with B0
(native EAGL = GLES 3.0 / GLSL ES 3.00, offscreen FBO + `glReadPixels` works; compute absent → 117HD still needs
ANGLE, out of scope here).

## What B1 actually does (so the proof is legible)

1. `assemble/ShaderAssemble.java` runs RuneLite's **own** `net.runelite.client.plugins.gpu.template.Template`
   with the exact `GpuPlugin.createTemplate()` token map (`TEXTURE_COUNT=256`, `SAMPLING_MODE=0` NEAREST,
   `COLORBLIND_MODE=0`) → emits the **byte-authentic desktop-GLSL 330** sources the plugin would hand to
   `glShaderSource` (`assembled/*.assembled.glsl`). (Template inlines *every* `#include` unconditionally — even
   ones inside `#if 0` blocks — so the assembled UI source still contains the `scale/*` + colorblind fragments,
   `#if`-gated off at these modes. We reproduce the live plugin exactly rather than approximate it.)
2. `app/B1Downport.m` applies the GLSL ES 3.00 downport (the transform an in-process iOS shim would do at
   `glShaderSource` time) — see the catalogue below.
3. `app/B1Probe.m` (a tiny EAGL-only iOS app, no JVM) compiles/links/validates both programs on the native ES3
   context, renders each into a 64×64 offscreen FBO, and `glReadPixels` to assert non-blank shader-shaped output.
   The downported sources are written to the app container (`evidence/*.es300.glsl`) for inspection.

## The downport catalogue (what it took — 5 classes)

The first four are pure string transforms; the **fifth is the real finding** — it is semantic, not lexical.

1. `#version 330[ core]` → `#version 300 es`.
2. Inject `precision` qualifiers after the version line (`highp float/int`, and `highp sampler2DArray/sampler2D`
   in fragments — ES fragments have **no default precision**).
3. Drop **`noperspective`** (desktop-only interpolation qualifier; absent from ES 3.00 — `centroid`/`flat` stay).
   Sites: `vert.glsl:71`, `frag.glsl:39` (`fHsl`).
4. Strip **`f`/`F` float-literal suffixes** (`1.0f`, `255.f`, `0.f`) — illegal in ES 3.00. (Pervasive via
   `hsl_to_rgb.glsl`.) The strip requires a decimal point, so hex (`0xff`) is untouched.
5. **Explicit numeric casts (scene vertex shader only).** GLSL ES 3.00 performs **no implicit int→float /
   ivec→vec conversions**, which desktop GLSL 330 *does*. The scene vertex shader mixes the engine's integer
   uniforms/attributes with floats in arithmetic and so needs explicit casts. Without them the **first compile of
   `scene.vert` failed** with a wall of `'+'/'-'/'*' does not operate on int/float` errors (captured in
   `evidence/gpu-b1-sim-verdict-20260605.log` history is overwritten, but the cast sites are):
   - `vertf + base` → `vertf + vec3(base)`  (vec3 + ivec3)
   - `(entityTint.xyz - hsl) * entityTint.w) / 128` → `(vec3(entityTint.xyz) - hsl) * float(entityTint.w)) / 128.0`
   - `FOG_SCENE_EDGE_MIN/MAX` macros: `(int expr) * TILE_SIZE` → `float(int expr) * TILE_SIZE`
   - `cameraX/Z ± drawDistance` → `cameraX/Z ± float(drawDistance)`
   - `fogDepth * TILE_SIZE) * useFog` → `float(fogDepth) * TILE_SIZE) * float(useFog)`
   `frag.glsl`, `vertui.glsl`, `fragui.glsl` needed **none** of class 5 — only the scene vertex shader.

**Implication for B2 (productionising):** classes 1–4 are a safe generic string pass; class 5 is shader-specific.
For the eventual unmodified-plugin port, the clean representation is either (a) hand-maintained iOS shader variants,
or (b) the cast pass keyed to known sites (what B1 does) — brittle to upstream shader edits, so (a) is preferable
for B2. Either way, **the shaders are reachable** — the gate is not the blocker.

## Caveats / honesty

- **Simulator, not device.** B1 ran on the iOS **Simulator** (`APPLE-23.1.1`, Metal-backed GLES on this Mac),
  the specified proxy. B0 already confirmed the **device** native EAGL is GLES 3.0 / GLSL ES 3.00 with working
  offscreen FBO + readback, so the tier matches; but the Simulator GLES compiler is not bit-identical to the
  A19's. **Final B1 confirm = re-run this probe on the primary machine's device.** Likely Simulator-specific:
  none seen here (clean compile/link/validate/render), but the validate pass is driver-conservative (it needs a
  bound VAO + complete FBO + sampler state, which we set up — RuneLite binds a VAO before validating for the same
  reason, `GpuPlugin.java:574`).
- **This is the shader gate only.** It does **not** prove the LWJGL Java→GL binding, `librlawt` offscreen shim,
  geometry upload, or the live-scene route into the `int[]` blit. Those are B1-part-2 (LWJGL) + B2.
- **Render proofs are hand-driven**, not the plugin's real geometry — a single triangle (scene) and a textured
  quad (UI) with hand-set uniforms. They prove the *programs execute and produce colour on GLES 3.00*, which is
  what the gate needs; they are not a scene render.

## Files

- `assemble/ShaderAssemble.java` — Template-authentic assembler (build against the shaded client jar).
- `assembled/*.assembled.glsl` — the byte-authentic desktop-GLSL 330 inputs (regenerable).
- `app/B1Downport.{h,m}` — the GLSL ES 3.00 downport (5-class transform).
- `app/B1Probe.{h,m}`, `app/main.m` — the EAGL ES3 compile/link/validate/render probe (no JVM).
- `project.yml` — xcodegen target (`GpuB1`, links `OpenGLES.framework`; `.xcodeproj`/`build/` gitignored).
- `gpu-b1-verdict.sh` + `evidence/` — md5-gated verdict + verbatim pulled log + downported ES sources.

## Reproduce

```
cd ios/dev-tools/gpu-b1
JAVA_HOME=/opt/homebrew/opt/openjdk@21 javac -cp ../../app/jvm-resources/client-1.12.28-SNAPSHOT-shaded.jar \
  -d assemble/out assemble/ShaderAssemble.java
JAVA_HOME=/opt/homebrew/opt/openjdk@21 java -cp assemble/out:../../app/jvm-resources/client-1.12.28-SNAPSHOT-shaded.jar \
  ShaderAssemble assembled
xcodegen generate
xcodebuild -project GpuB1.xcodeproj -scheme GpuB1 -sdk iphonesimulator -derivedDataPath build/dd \
  -destination 'platform=iOS Simulator,id=<iPhone17ProMax-udid>' build
xcrun simctl install <udid> build/dd/Build/Products/Debug-iphonesimulator/GpuB1.app
xcrun simctl launch  <udid> dev.audaro.gpub1
# pull Documents/gpu-b1-verdict.log from `simctl get_app_container <udid> dev.audaro.gpub1 data`, then:
./gpu-b1-verdict.sh <pulled-log>
```

## Next

`ios/AUDIT-BRIEF-w3-gpu-b1-lwjgl-ios.md` — the LWJGL-on-iOS binding port (B1 part 2), for review before building.
The single biggest unknown there: LWJGL's GL function loader assumes a desktop context (GLFW/WGL/GLX/EGL); iOS
uses **EAGL**, and the base plugin imports **`org.lwjgl.opengl.GL33C`** (desktop GL), not `org.lwjgl.opengles`.
