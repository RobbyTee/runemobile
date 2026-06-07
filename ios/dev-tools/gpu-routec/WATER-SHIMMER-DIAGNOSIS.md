# GPU Route C — water shimmer / distant-flicker diagnosis

> Branch `gpu`. The base `gpu` plugin renders the real OSRS world on iOS GLES 3.0 through the Route C JNI bridge
> (see `README.md`). This doc records the investigation into the on-device rendering artifacts so the work isn't
> re-tread. **Device:** iPhone 17 Pro Max / A19 Pro, iOS 26.5. The same unmodified plugin renders correctly on
> desktop OpenGL; every delta here is in the iOS / GLES path.

## TL;DR — there are TWO independent artifacts

| # | Artifact | Root cause | Status |
|---|----------|-----------|--------|
| **A** | Water "shimmer": animated water texels popping between light/dark at distance/grazing | The base plugin point-samples (`GL_NEAREST`) the **animated** water texture; on the low-res render the scrolling UVs make the sampled texel pop every frame | **FIXED** in the bridge (trilinear + anisotropy + `textureGrad`) |
| **B** | Black jagged bands on the river + red/white/blue **striped moiré** on distant terrain | **Depth z-fighting** between near-coplanar surfaces (water vs dark riverbed; terrain vs tile overlays). iOS GLES has **no `glClipControl`**, so the plugin's reverse-Z is squeezed into window-z `[0.5, ~0.55]` at distance → far precision collapses | **OPEN** — the dominant remaining artifact |

A and B were conflated early (both show up "on the water"). They are different mechanisms and need different fixes.
B is the deep-dive's original suspect and the harder problem.

---

## How the depth path works (the crux of B)

- `Mat4.projection(w,h,50)` builds a **reverse-Z** projection: `clip.z = 2*near = 100` (CONSTANT), `clip.w = viewZ`,
  so `NDC.z = clip.z/clip.w = 100/viewZ ∈ (0,1]`, near=1 / far→0.
- `GpuPlugin` sets `glClearDepth(0)` + `glDepthFunc(GL_GREATER)` → "nearer = larger depth, kept by GREATER".
- **Desktop** also calls `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` (only when `OpenGL45`), so the clip-z
  convention is `[0,1]` and `window_z = NDC.z = 100/viewZ` directly → **full 32F precision at the far plane**.
- **iOS** has no `glClipControl`, so the standard `[-1,1]` clip-z convention applies and
  `window_z = (NDC.z + 1)/2 ∈ (0.5, 1]`. Far geometry (small `NDC.z`) is crushed into `window_z ≈ 0.5..0.55`, and
  the `(NDC.z + 1)/2` mapping adds the small reverse-Z value to `1.0` (precision loss). **This is the only known
  desktop↔iOS delta**, and it is exactly what z-fights at distance / on near-coplanar surfaces.
- There is ALSO a per-vertex depth bias in the scene vertex shader: `screenPos.z += float(bias)/128.0`
  (`bias = abhsl>>16 & 0xff`) — the plugin's coplanar-layering mechanism. Any depth fix must preserve it.

**Confirmed on device:** the scene depth renderbuffer is genuine `GL_DEPTH_COMPONENT32F`, `depthBits=32`
(framebuffer-attachment probe). So B is **not** a low-bit-buffer problem; it's the half-range + `(NDC+1)/2`
precision collapse from the missing `glClipControl`.

---

## Isolation experiments and RESULTS (all gated via `Documents/routec-water-mode.txt`; see "Bridge mechanism")

Everything below is measured from device screen recordings/screenshots. For flicker we use a **per-pixel temporal
metric** over a still-camera window (a single frame hides it because it *flickers*): `everBlack` = a water pixel
dips below ~35 brightness in some frame; `flicker` = bright on average but dips dark (separates true flicker from
genuinely-dark distance). Frames extracted with ffmpeg; per-band stats fold screen-x into the 88px stripe bands.

- **Stripe A/B (`stripes` mode):** the scene fragment is split into 4 vertical 88px bands — `0 baseline ·
  1 force-LOD0 · 2 no-discard · 3 no-fog` — so one device frame A/Bs the texture suspects.
  - **Moderate-angle pond:** baseline `everBlack 0.27`, **LOD0 `0.066` (4× cleaner)**, no-discard `0.24`,
    no-fog `0.31`. → at moderate angles the water shimmer is the **texture LOD/sampling** (LOD0 helps).
  - **Grazing river:** baseline `0.32`, LOD0 `0.29`, no-discard `0.48`, no-fog `0.38`. → at grazing **no fragment
    band helps** — the artifact there is NOT the fragment colour path.
- **`solidtex` (ignore the texture colour sample entirely, flat blue):** grazing-river flicker **0.351 → 0.020**.
  → the grazing water flicker IS the texture **colour sample** (Artifact A). This is what the texture fix targets.
- **Mip-content probe (FBO-read the array's mip levels):** mip levels are **populated**, not black (rgb 32–152,
  alpha mostly 255). So the early "black mips" theory was wrong; it's `GL_NEAREST` popping of the populated texels.
- **Fog (`fogDepth`):** profile `fogDepth=0` → `useFog=0` → `fFogAmount = …*0 ≈ 0`. Fog is effectively off and is
  NOT the cause near the player. (Default `fogDepth` is 0 on desktop too.)
- **`depthalways` (force `glDepthFunc(GL_ALWAYS)`):** the river black band does **not** vanish — with the depth
  test off, the **dark surface takes over** (big black bands). → the band IS depth-test-driven z-fighting against a
  dark coplanar surface (Artifact B). Same toggle removed the distant-terrain flicker too (earlier round).
- **`fragdepth` (the depth fix, below):** **re-opened — Simulator-validated, pending a clean device re-test.** The
  first on-device run was inconclusive (it looked at the birdseye horizon, which is dominated by a *different*
  problem — sub-pixel aliasing of thin far geometry that `gl_FragDepth` does not address — and reported broad
  white/grey depth-ordering planes; the clean river-band z-fight was never isolated). A controlled off-device repro
  (`../gpu-depthsim/`, md5-gated GREEN) shows Apple's GLES **honors** `gl_FragDepth`, that it carries **64×** finer
  float32 depth at the far plane (the device buffer is a confirmed 32F, so this applies on-device), that it cuts a
  real `DEPTH_COMPONENT32F` z-fight ~15×, and that it **preserves** the coplanar bias layering. So the route is
  *not* dead — re-test it on the river band specifically. Caveat: the Simulator GLES is a *software* renderer, so the
  device confirmation still matters. See [`../gpu-depthsim/DEPTHSIM-RESULT.md`](../gpu-depthsim/DEPTHSIM-RESULT.md).
- **2× supersample (`fix2x`):** scene FBO rendered at 2608×1200 single-sample, downsampled `GL_LINEAR` to
  1304×600 (confirmed in trace; 120fps — perf is a non-issue). It **reduces the sub-pixel aliasing** of thin far
  geometry, but **does NOT fix the z-fighting** (depth problem, not spatial) — the LINEAR downsample even turns the
  fine z-fight into the bold moiré. So supersampling is a quality win for aliasing but orthogonal to B.

---

## Artifact A — the texture fix (shipped in the bridge, plugin source unchanged)

Mechanism: `frag.glsl` colour path is `texture(textures, …)` (implicit-LOD) of the animated water texture, point-
sampled (`GL_NEAREST` min filter; aniso level 1 = max-anisotropy 1.0 = off). On the 1304×600 render at
distance/grazing the scrolling `fUv += tick*textureAnim/128` makes the nearest texel pop between light/dark stipples
every frame → flickering black. The implicit-LOD `texture()` derivative is also corrupted by the scrolling-alpha
`discard` later in `main()`, adding a shoreline-edge shimmer.

Fix (in `routec_gl_jni.m` / `RouteCBridge` / the glShaderSource downport, gated `waterFixActive`):
1. `glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, …)` → forced `GL_LINEAR_MIPMAP_LINEAR` (trilinear)
   so the animated texels blend instead of pop. MAG stays `NEAREST` (crisp close-ups).
2. `glTexParameterf(… GL_TEXTURE_MAX_ANISOTROPY_EXT …)` → raised `1.0 → 16.0` so grazing water stays sharp.
3. The scene-fragment downport replaces `texture(textures, …)` with
   `textureGrad(textures, …, dFdx(fUv), dFdy(fUv))` (explicit gradients computed before the discard → stable LOD).

Measured effect: grazing river flicker `0.351 → 0.061` (the trilinear half) and the shoreline edge addressed by
`textureGrad`. This is real but **secondary** to Artifact B, which dominates what the user sees.

---

## Artifact B — depth z-fighting (OPEN). Candidate fixes, in order

1. **`gl_FragDepth` reverse-Z (gated `fragdepth`) — LEADING CANDIDATE (Simulator-validated; device re-test pending).**
   Scene vertex carries `clip.z` as a **flat** varying `vClipDepth = screenPos.z`; scene fragment writes
   `gl_FragDepth = vClipDepth * gl_FragCoord.w` (= `clip.z/clip.w = 100/viewZ`). This puts `window_z = 100/viewZ`
   **exactly equal to desktop's `glClipControl` values, with the same 32F precision** → it fixes B if the
   surfaces are at distinct depths. `clip.z` is carried `flat` (it is ~constant per face) specifically to avoid the
   deep-dive's earlier `gl_FragDepth` failure, which passed `clip.z` as a *perspective* varying and diverged at
   grazing. Math: with constant `clip.z = C`, `C*gl_FragCoord.w = C*screenLinearInterp(1/w) = screenLinearInterp
   (NDC.z)` = the correct planar window depth at all angles. The off-device controlled repro (`../gpu-depthsim/`,
   md5-gated GREEN) confirms all of this: gl_FragDepth honored, 64× finer far-plane float32 precision, ~15× less
   z-fight, coplanar bias layering preserved. The earlier on-device "global scene corruption" was on the confounded
   birdseye view, not a clean z-fight — re-test on the river band before shipping. On-device watch item: the
   per-vertex bias under the `flat` varying (if global ordering breaks on-device while the band clears).
2. **Polygon offset** for the coplanar water-vs-riverbed / terrain-vs-overlay specifically. Active test:
   `offsetalpha` keeps the texture fix on, leaves `fragdepth` off, and applies `glPolygonOffset(1, +32)` only while
   the plugin's alpha pass has depth writes disabled (`glDepthMask(false)`). If this clears the river band without
   global scene damage, water/overlays are indeed separate alpha draws losing to the opaque riverbed/terrain in the
   squeezed reverse-Z depth range. If it fails, try the already-wired strengths/sign check: `offsetalpha8`,
   `offsetalpha128`, `offsetalpha-32`.
3. **Forward-Z projection** (deep-dive candidate i) — intercept `worldProj`'s z-row + flip `glDepthFunc`/
   `glClearDepth`. NOTE: pure matrix remaps (`row2' = 2*row2 - row3`, tried historically) hit **catastrophic
   cancellation** for far geometry (small `100/viewZ` minus 1) — that's why a *value* fix (`gl_FragDepth`) is
   preferred over a *matrix* remap. Forward-Z is also affected by the `entityProj`-affine caveat (IDENTITY for
   terrain/water — the artifact surfaces — so OK there; per-entity `FloatProjection` for models).

### What did NOT work / is NOT the cause (don't re-pursue)
- Black mips / `glGenerateMipmap` failure — **falsified** (mip probe: levels populated).
- Fog — effectively off (`useFog=0`); not the river cause.
- The alpha `discard` as the *primary* water cause — `solidtex` keeps the discard and is clean; `no-discard` made
  grazing *worse*. (It only contributes the secondary shoreline edge, handled by `textureGrad`.)
- Low-bit depth buffer — **falsified** (32F confirmed on device).
- 2× supersampling for B — reduces aliasing, not z-fighting (orthogonal).
- Historical reverts (don't redo as-is): worldProj clip-z `[0,1]→[-1,1]` remap (cancellation); `GL_GREATER→GEQUAL`
  (no effect); the *perspective*-varying `gl_FragDepth` (grazing divergence — the `flat` version here is the fix to
  that).

---

## Bridge mechanism (how to reproduce / A-B without rebuilding)

All experiments are gated by the file `Documents/routec-water-mode.txt` (push with `devicectl device copy to …
--domain-identifier dev.audaro.helloshell --destination Documents/routec-water-mode.txt`; read once per launch).
`RouteCBridge.waterDiagMode` returns the trimmed string; `waterFixActive` / `depthFixActive` / `superSampleActive` /
`alphaOffsetUnits` derive from it. Modes:

| mode | texture fix | frag depth | alpha offset | 2× SS | purpose |
|------|:--:|:--:|:--:|:--:|---------|
| `baseline` | – | – | – | – | raw plugin (reproduce the artifact) |
| `stripes` | – | – | – | – | 4-band scene-fragment A/B (LOD0/discard/fog) |
| `solidtex` | – | – | – | – | flat-colour the textured faces (isolate texture-colour) |
| `depthalways` | – | – | – | – | force `glDepthFunc(GL_ALWAYS)` (isolate depth) |
| `fragdepth` | – | ✓ | – | – | rejected `gl_FragDepth` reverse-Z test alone |
| `offsetalpha` | ✓ | – | +32 | – | texture fix + alpha-pass polygon-offset test |
| `offsetalpha8` / `offsetalpha128` | ✓ | – | +8 / +128 | – | offset strength checks |
| `offsetalpha-32` | ✓ | – | -32 | – | offset sign check |
| `fix` | ✓ | – | – | – | shipping texture fix |
| `fix2x` | ✓ | – | – | ✓ | texture fix + 2× scene supersample |
| absent / other | ✓ | – | – | – | default = the texture fix |

Diagnostics in the trace (`Documents/routec-trace.log`): first-scene `GLSTATE` dump, the depth-attachment probe
(`DEPTH attach … depthBits=… intFmt=…`), the mip-content probe (`mipprobe layer …`), `glViewport`/`glBlit` traces,
and the perf counter. The downported shaders the device actually compiled are dumped to `Documents/routec-shaders/`.
**All of this is gated diagnostic scaffolding and must be STRIPPED before any merge** (along with the unused depth/
supersample experiments if they don't ship).

## Device / build / settings notes
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21`; device `id=AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B`; bundle
  `dev.audaro.helloshell`. Bridge-only change → rebuild the app (xcodebuild) + `devicectl … install`. The user
  attaches StikDebug per launch and taps **▶ Run GPU (Route C)**.
- The user's GPU config (stock): Draw distance **50** (=default/max), Fog depth **0**, Anti-aliasing toggled
  (MSAA helps Artifact A's aliasing a lot but cannot fix B), Anisotropic **1** (=off), UI scaling NEAREST,
  Unlock FPS on / 120 target. At these stock settings desktop renders fine — so B is genuinely iOS-specific.
- In CAEAGLLayer present mode there is no readback; capture the artifact via a screen recording (the on-device PNG
  dump won't see the GPU frame). To find flicker frames: extract at fps, compute the per-pixel temporal min/range.

## Next step
Run `offsetalpha` on the **river band** (mode already set on device; needs StikDebug attach + app launch). If the
band clears, tune the offset and keep the fix scoped to alpha draws. If it does not, try `offsetalpha8`,
`offsetalpha128`, then `offsetalpha-32` to separate wrong strength from wrong mechanism.
