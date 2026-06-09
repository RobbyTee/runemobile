# Device kit — gl_FragDepth early-Z perf A/B + RGB32I device confirm

> Branch `gpu`. The off-device proofs (`gpu-clipremap`, `gpu-tbo2d`) left exactly two device-only unknowns. This kit
> measures both in one StikDebug session, writing a pullable `Documents/routec-perf.log`. **Temporary scaffolding —
> STRIP after the EAGL-vs-ANGLE decision** (grep `DEVICE KIT` in `RouteCBridge.{h,m}` + `routec_gl_jni.m`).

## What it measures

1. **The gl_FragDepth early-Z perf cost** — the one thing that decides EAGL-`gl_FragDepth` vs ANGLE-`clip_control`.
   `gpu-clipremap` proved `gl_FragDepth` is the only full-precision EAGL depth fix, but writing `gl_FragDepth`
   **disables Apple's TBDR Hidden Surface Removal / early-Z** — a perf hit a software renderer can't measure. The kit
   logs **GPU render time/frame (`gpuFinish`), avg + p95**, tagged with the active depth mode. Because early-Z can't
   be a runtime branch (any shader that *can* write `gl_FragDepth` loses early-Z), the A/B is **two launches**:
   `mode=baseline` (no `gl_FragDepth`, early-Z **ON**) vs `mode=fragdepth` (early-Z **OFF**).
2. **`GL_RGB32I` 2D sampleability on the real Metal GPU** — the one device-checkable item from `gpu-tbo2d` (117HD
   blocker #1). A one-shot probe at context creation logs `RGB32I_DEVICE_SAMPLEABLE=YES/NO` (runs every launch, free).

## Build + install (EAGL backend — the path under test)

```sh
DEV=AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B   # iPhone 17 Pro Max
xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell -destination "id=$DEV" \
  -derivedDataPath build/dd-gpu -allowProvisioningUpdates build
xcrun devicectl device install app --device "$DEV" \
  build/dd-gpu/Build/Products/Debug-iphoneos/HelloShell.app    # "No provider was found" is non-fatal
```

EAGL is the **active** backend on this branch (`ios/app/project.yml`: `RC_BACKEND_ANGLE` commented +
`OpenGLES.framework` linked — so it also builds on a fresh Mac with no vendored ANGLE frameworks). To switch to
MetalANGLE later: uncomment `RC_BACKEND_ANGLE=1` and the `libEGL`/`libGLESv2` frameworks in `project.yml`, re-comment
`OpenGLES.framework`, then `cd ios/app && xcodegen generate`. The RGB32I probe + perf tagging are backend-agnostic,
so an ANGLE build measures the same way if you want the ANGLE-side number.

## Run — the two-launch A/B

For each of the two modes, do a full launch and let it log on a **busy, high-overdraw scene** (Grand Exchange or a
dense bank — the early-Z cost only shows under overdraw; an empty field won't reveal it). Keep the **camera position
the same** across the two runs.

```sh
# ---- run 1: baseline (early-Z ON) ----
printf baseline > /tmp/mode.txt
xcrun devicectl device copy to --device "$DEV" --domain-type appDataContainer \
  --domain-identifier dev.audaro.helloshell --source /tmp/mode.txt --destination Documents/routec-water-mode.txt
# attach StikDebug -> tap "▶ Run GPU (Route C)" -> autologin -> walk to the GE -> stand still ~60s

# ---- run 2: fragdepth (early-Z OFF) ----
printf fragdepth > /tmp/mode.txt
xcrun devicectl device copy to --device "$DEV" --domain-type appDataContainer \
  --domain-identifier dev.audaro.helloshell --source /tmp/mode.txt --destination Documents/routec-water-mode.txt
# attach StikDebug -> Run GPU -> same GE spot -> stand still ~60s
```

`routec-perf.log` **appends** across launches, so both runs land in one file.

## Pull + read the result

```sh
xcrun devicectl device copy from --device "$DEV" --domain-type appDataContainer \
  --domain-identifier dev.audaro.helloshell --source Documents/routec-perf.log --destination ./routec-perf.log
cat routec-perf.log
```

You'll see per-second lines like:

```
… [probe] RGB32I_DEVICE_SAMPLEABLE=YES RGBA32I=YES GL_RENDERER=Apple … GL_MAX_TEXTURE_SIZE=…  (117HD blocker#1 device confirm)
… [perf] mode=baseline/earlyZ-ON(no-fragdepth)   engine=… fps  gpuFinish avg=A.AA p95=B.BB ms  copy=… ms  (… frames, WxH)
… [perf] mode=fragdepth/earlyZ-OFF(fragdepth)    engine=… fps  gpuFinish avg=C.CC p95=D.DD ms  copy=… ms  (… frames, WxH)
```

## The decision rule

Compare **`gpuFinish` (avg and p95)** between the two modes on the same busy scene — that is the GPU fragment time
where early-Z matters (ignore `copy`/`engine fps`, which are dominated by the readback path, not the depth choice):

- **`fragdepth` gpuFinish ≈ `baseline` (within ~10–15%)** → early-Z loss is cheap on the A19 GPU → **EAGL +
  `gl_FragDepth` is the renderer.** Wedge bug moot, no ANGLE, no newer-ANGLE build. Proceed to iOS Zone B0 on EAGL.
- **`fragdepth` gpuFinish materially worse (e.g. >1.5×, or p95 spikes / fps drops under load)** → the early-Z cost is
  real → the depth fix must come from **ANGLE `clip_control`** instead, which means fixing the ANGLE near-plane
  **wedge bug** (vertex `w`-guard) is back on the critical path.

Also confirm **`RGB32I_DEVICE_SAMPLEABLE=YES`** — if it's `NO` on real Metal (unlikely; it's ES 3.0 core and the
Simulator passed), the 117HD `textureFaces` repack uses the `RGBA32I` fallback (also probed) instead of RLHD's native
RGB32I.

## Cleanup

After the decision, strip the kit: the `DEVICE KIT` block in `RouteCBridge.{h,m}`, the `runDeviceProbesOnce` calls in
both `awtCreateGLContextWidth:height:`, and the perf block's mode-tag/p95/`appendPerfLine` additions in
`routec_gl_jni.m` (revert to the prior one-line `rc_trace` perf counter or remove it).
