# gpu-depthsim — off-device validation of the `gl_FragDepth` reverse-Z depth fix

> Branch `gpu`. A self-contained iOS Simulator GLES harness that isolates the river **z-fighting** artifact
> (Artifact B in [`../gpu-routec/WATER-SHIMMER-DIAGNOSIS.md`](../gpu-routec/WATER-SHIMMER-DIAGNOSIS.md)) into a
> controlled repro and tests whether writing the reverse-Z depth via `gl_FragDepth` resolves it — **before**
> spending a device round. Mirrors the `gpu-b1` harness (EAGL ES3 offscreen FBO + `glReadPixels` + md5-gated
> verdict). Run: `xcodegen generate && xcodebuild … && simctl install/launch`, then `./depthsim-verdict.sh`.

## TL;DR — md5-gated **GREEN**: the fix is mechanistically validated; do the single device river-band confirm

`gl_FragDepth = clip.z * gl_FragCoord.w` ( = `NDC.z` = `100/viewZ`, the desktop `glClipControl(ZERO_TO_ONE)` value)
is the right fix for the depth z-fight, and Apple's GLES **honors** it. The earlier on-device `fragdepth` rejection
does **not** survive a controlled test. Confidence is moderate-high, bounded by one caveat (the Simulator GLES is a
**software** renderer — see below), so the handoff's Step 2 (one device confirmation on the Lumbridge river band)
is still warranted, but the risk is now low.

| gate | result | meaning |
|------|:------:|---------|
| `FRAGDEPTH_HONORED` | **YES** | writing `gl_FragDepth` changes the GLES depth result → Apple's GLES honors it |
| `P1_FIX_FINER` | **YES** | in float32 (= the device's confirmed-32F buffer) the fix resolves **64×** finer depth at the far plane |
| `P2_FIX_CLEANER` | **YES** | on a real `DEPTH_COMPONENT32F` pipeline the fix cuts a real z-fight **~15×** (FAR: 131072→8704 px) |
| `P3_FIX_PRESERVES_LAYERING` | **YES** | the fix keeps the plugin's coplanar bias layering (decorations-on-floors) intact |

## The harness

Three controlled experiments, all faithful to the plugin's reverse-Z (`Mat4.projection`: `clip.z = 2*near = 100`
constant, `clip.w = viewZ`, so `NDC.z = 100/viewZ`; `glDepthFunc(GL_GREATER)` + `glClearDepthf(0)`; **no**
`glClipControl`). The vertex shader reconstructs the exact clip coords; the only difference between *control* and
*fix* fragment shaders is that *fix* writes `gl_FragDepth = vClipDepth * gl_FragCoord.w` (`vClipDepth` = `clip.z`
carried `flat`).

- **Phase 1 — precision (CPU float32).** Over a 48.83-unit `viewZ` window at the far plane (`Zfar=6000`, sampled at
  the float32 `viewZ` resolution), count how many *distinct* stored depth values each path yields. Computed in
  genuine `float` (`volatile` to force per-step rounding, no FMA). This is **renderer-independent** and models the
  device's depth buffer directly (it is a confirmed true 32F), which is why Phase 1 is CPU-side — see the caveat.
- **Phase 2 — real z-fight (GPU).** Real `DEPTH_COMPONENT32F` attachment, `GL_GREATER`, two screen-covering planes
  that **intersect at screen-center** at distance (A blue flat at `Zfar`; B red tilted, coincident at center). The
  analytic image is a clean blue-over-red split; z-fighting = the wrong colour bleeding across a band near center
  (and, in full collapse, the second-drawn surface taking over the whole frame). Metric = contamination pixels.
- **Phase 3 — bias layering (GPU).** Two fully coplanar quads at the same far `viewZ`: floor (`clip.z=100`) then a
  decoration (`clip.z=100+bias/128`, the plugin's coplanar-layering hack). The decoration's larger `clip.z` must
  keep it on top everywhere; layering fails where the floor bleeds through. This checks the fix does not **break**
  the scene (the earlier device run reported "broad depth-ordering planes").

## Results (md5-sealed: `evidence/gpu-depthsim-sim-verdict.log`)

```
GL_VERSION  = OpenGL ES 3.0 APPLE-23.1.1     GL_SLVERSION = OpenGL ES GLSL ES 3.00
GL_RENDERER = Apple Software Renderer         EXT_color_buffer_float = NO

PHASE 1  viewZ=6000  control window_z=0.508333  (near 0.5 → low float32 precision)
                     fix     window_z=0.016667  (near 0   → high float32 precision)
         far-plane distinct depths over 48.83 units: control=1129  fix=72231  → 64.0× finer

PHASE 2  NEAR(600)    control=11776  fix=1024      (~11×)
         FAR(6000)    control=131072 fix=8704      (~15×; control = total collapse, red takes the whole frame)
         VERYFAR(9000)control=131072 fix=13312     (~10×)

PHASE 3  bias=4/128 @ 6000   control floor_bleed=0   fix floor_bleed=0   (layering intact, no regression)
```

The Phase 2 control collapse — the second-drawn surface (red) taking over the **entire** frame at distance —
mirrors the device symptom from the diagnosis (`depthalways`: "the dark surface takes over"). The fix reduces it to
a thin band at the genuine plane intersection.

## ⚠️ Caveat — the Simulator GLES is a **software** renderer

`GL_RENDERER = Apple Software Renderer` (the Apple-Silicon Simulator GLES path), **not** the device's Metal GPU, and
it has **no** `EXT_color_buffer_float` (so an on-GPU float depth readback can't run here — hence Phase 1 is CPU
float32). Consequences:

- **Phase 1 is device-relevant.** It is pure float32 arithmetic, and the device depth buffer is a confirmed true
  32F, so the 64× far-plane precision gain applies on-device as computed. This is the core of the fix's case.
- **Phase 2 magnitudes are indicative, not device-exact.** The software renderer is *lower* precision than true
  32F (its `NEAR` band is ~100× wider than true-float32 math predicts), so it is a **worst-case** stress for
  control; the fix winning here is encouraging but does not quantify the device benefit. What it *does* prove
  device-relevantly: Apple's GLES **honors `gl_FragDepth`** and the fix produces **correct ordering**.
- **Phase 3 uses a per-draw (uniform) bias.** Real plugin bias is a per-*vertex* attribute; layer offsets are
  constant per face in practice (so `flat` is exact), but a triangle with a per-vertex-varying bias gradient is not
  exercised here. Considered low-risk but unproven off-device.

## Why this overturns the earlier on-device `fragdepth` rejection

The diagnosis recorded `fragdepth` as **rejected** ("black band remains; broad white/grey depth-ordering planes
across the whole scene"). The handoff's read — that the device run was **confounded** (it looked at the birdseye
horizon, dominated by a *different* problem: sub-pixel aliasing of thin far geometry, which `gl_FragDepth` does not
address; the clean river-band z-fight was never isolated) — is consistent with this controlled test, where with all
confounds removed the fix is correct and resolves the z-fight. The "broad planes" worry is specifically addressed
by Phase 3 (layering preserved). Net: the rejection was not conclusive; the fix deserves the clean device re-test.

## Recommendation / next steps

1. **Device confirm (handoff Step 2), scoped to the river band.** Mode `fragdepth` is already wired and was last set
   on-device. Reconnect the device, rebuild the bridge, attach StikDebug, run GPU Route C, navigate to the Lumbridge
   river, and capture the band (no readback in present mode — screenshot/recording). **Watch specifically for**: the
   river black band clearing, and any *global* depth-ordering change (the Phase-3 risk) — if global ordering breaks
   on-device while the band clears, the suspect is the per-vertex bias under the `flat` varying, not the precision.
2. **If the band clears:** ship `fragdepth` + the texture fix as the final build — set `depthFixActive` true for the
   shipping `fix` path too (not just the `fragdepth` A/B), then **strip all gated diagnostics** (probes, modes, the
   non-shipping experiments, this `gpu-depthsim` tool can stay as a dev-tool) before any merge.
3. **If it does not clear / global ordering breaks:** fall back to the wired-but-untested `offsetalpha*`
   polygon-offset path (validate it here on the Simulator first, the same way).
