# Handoff — Phase A1: productize the MetalANGLE backend (depth fix + MSAA) for the iOS GPU renderer

> Paste the fenced block below into a fresh session, run from the repo root `/Users/jordansimpson/runelite-ios`
> on branch `gpu`. It is self-contained. This is the first executable step of `ios/HD-PORT-BLUEPRINT.md`.

```
You are continuing the W3 GPU track on branch `gpu` of /Users/jordansimpson/runelite-ios. The end goal is
117HD-quality rendering on the on-device iOS RuneLite client. The full, reviewed plan is ios/HD-PORT-BLUEPRINT.md
(v2) — READ IT FIRST (esp. §0, §3, §4 Phase 0/A1, §5 ES3 conformance, §9 perf gate, §12 fallbacks). Your job is
PHASE A1: the foundational backend swap that also fixes the two artifacts that have dogged the base renderer. Do
NOT start Phase B (the 117HD port) — A1 first.

================================================================================
READ FIRST
================================================================================
- ios/HD-PORT-BLUEPRINT.md — the plan (and why A1 is first).
- ios/dev-tools/gpu-angle/ — the MetalANGLE probe (md5-sealed GREEN). It PROVES ANGLE-over-Metal on the A19 Pro GPU
  exposes EXT_clip_control (works) + MSAA 8x, ES 3.0 only (no compute). app/AngleProbe.m is your reference for the
  EGL/ANGLE bring-up (eglGetPlatformDisplayEXT Metal, config, context). README.md has how to regenerate the ANGLE
  frameworks (libEGL.framework + libGLESv2.framework, extracted from the Amethyst IPA; arm64 device-only).
- ios/dev-tools/gpu-routec/app/RouteCBridge.{h,m} — the current EAGL context + offscreen FBO + present. THIS is the
  swap point (the EAGL surface is ~3 places: context create, rcBuildFbo, present).
- ios/dev-tools/gpu-routec/jni/routec_gl_jni.m — the GL stub table + the 5 shims, including the glClipControl
  NO-OP you will flip to a real call, and the present/readback paths.
- project memory note project_ios_gpu_track (auto-loaded) — the full GPU-track history.

================================================================================
CONTEXT (what is already proven — do not re-litigate)
================================================================================
- The base `gpu` plugin renders the full 3D scene on device via the Route C JNI bridge over a native EAGL GLES 3.0
  context. TWO artifacts remain: (B) depth z-fighting — root cause: native iOS EAGL GLES has NO glClipControl, so
  the plugin's reverse-Z is squeezed into window_z=(NDC.z+1)/2 (low float precision at distance); and distant
  ALIASING.
- gl_FragDepth was tried as the depth fix and FAILED the on-device labeled A/B (the Simulator's software renderer
  mispredicted it). It is SUPERSEDED — do NOT pursue gl_FragDepth.
- The gpu-angle probe proved MetalANGLE (ANGLE→Metal; libEGL+libGLESv2 from the Amethyst IPA) gives a REAL,
  working EXT_clip_control (far-plane stored depth 0.508 -> 0.0167, i.e. desktop-equivalent full precision) and
  GL_MAX_SAMPLES=8, on the real device GPU — but ES 3.0 only. So swapping the renderer backend EAGL -> MetalANGLE
  fixes BOTH artifacts at the root, independent of 117HD. That is Phase A1.

================================================================================
THE TASK — Phase A1
================================================================================
Productize a MetalANGLE/EGL backend in Route C and prove the BASE gpu plugin renders on it on device, with the
depth z-fight fixed (clip_control) and aliasing reduced (MSAA).
1. Add an EGL-on-ANGLE backend to RouteCBridge (context + FBO + present), replacing/alongside the EAGL path. Embed
   libEGL.framework + libGLESv2.framework into HelloShell (mirror gpu-angle's project: link+embed+codesign, team
   5NZ6CPA994, HEADER_SEARCH_PATHS for the Khronos headers). Bring up the context with eglGetPlatformDisplayEXT
   (Metal) exactly like AngleProbe.m.
2. PRESENT PATH (the main risk — solve/de-risk it FIRST): the probe used an offscreen pbuffer; A1 needs the frame
   on screen. Either (a) create an ANGLE WINDOW surface from the app's CAEAGLLayer/UIView, or (b) keep an offscreen
   FBO and blit/copy it to the on-screen layer each frame. Prototype this in isolation before the full integration.
3. Flip the glClipControl shim from no-op to a real glClipControlEXT(GL_LOWER_LEFT_EXT=0x8CA1,
   GL_ZERO_TO_ONE_EXT=0x935F) (the symbol is exported by libGLESv2). This is what fixes the depth z-fight.
4. Enable MSAA: the plugin already honors antiAliasingMode; ANGLE gives GL_MAX_SAMPLES=8. Ensure the scene FBO uses
   a sized internal format (RGBA8) for the MSAA color renderbuffer.
5. VERIFY ON DEVICE: the Lumbridge river depth z-fight is GONE and distant aliasing is reduced. Get a screenshot
   A/B vs the current EAGL build (ASK THE USER — present mode has no readback).
6. MEASURE PERF: frame time on ANGLE vs EAGL (the blueprint §9 flags translation overhead). Must be acceptable
   (>= playable; base gpu ran 60-120fps on EAGL).
7. md5-SEAL the verdict (extend the gpu-angle/gpu-b1 pattern: a pulled-from-device log + a verdict script that
   recomputes the result and pins its md5 — the project has been burned by fabricated GREENs, so verify by md5).
8. ONCE GREEN: strip the now-superseded gl_FragDepth / depthsplit / fragdepth diagnostic scaffolding from
   RouteCBridge (clip_control replaces it). Keep the texture-fix (trilinear+textureGrad) — it still helps.

================================================================================
DEVICE / BUILD FACTS
================================================================================
- JAVA_HOME=/opt/homebrew/opt/openjdk@21 ; DEV=AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B (iPhone 17 Pro Max, A19 Pro,
  iOS 26.5) ; bundle dev.audaro.helloshell ; team 5NZ6CPA994.
- Build: xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell -destination "generic/platform=iOS"
  -derivedDataPath build/dd-gpu -allowProvisioningUpdates build  (generic destination avoids device-lock build
  failures; keep the phone unlocked for install). Install: xcrun devicectl device install app --device "$DEV"
  build/dd-gpu/Build/Products/Debug-iphoneos/HelloShell.app  ("No provider was found" is non-fatal).
- The full app needs StikDebug per launch (the JVM uses JIT). The USER attaches StikDebug + taps
  "▶ Run GPU (Route C)", logs in via autologin, navigates to the Lumbridge river. ASK THE USER for screenshots.
- The ANGLE frameworks are arm64 iOS-device only -> device builds only (no Simulator). The full app cannot run on
  the Simulator anyway (JIT).

================================================================================
CONSTRAINTS
================================================================================
- Do NOT modify the base gpu plugin SOURCE (runelite-client/.../plugins/gpu/*). All backend work lives in the
  bridge (RouteCBridge / routec_gl_jni / facade) or the app. Route C = unmodified plugin.
- Commit + push under Jordan Simpson <sayhijordy@gmail.com> ONLY. NO automated-tooling attribution of any kind
  anywhere (no Claude/Codex/AI/anthropic/"generated with"/co-authored — in commits, code comments, or committed
  docs). Scrub staged content before committing. Auto-commit + push at logical checkpoints (standing authorization).
- Be efficient and honest: de-risk the present path before the full integration; md5-gate every device verdict;
  state uncertainty plainly.

================================================================================
NEXT (after A1 is green)
================================================================================
- Phase Z0 — the Zone ES3 conformance gate: extend gpu-angle to (1) run EVERY 117HD zone-path shader through an
  upgraded downporter and compile each on device via ANGLE (fail-closed on non-ES3 symbols), (2) probe ANGLE
  renderability for the RGBA16UI 2D-array FBO + DEPTH_COMPONENT24 shadow sampling + clamp-to-edge, (3) scan RLHD
  source for the facade GL-call delta. See blueprint §5/§6.
- Then Phase B — port the 117HD ZoneRenderer subsystem-by-subsystem. See blueprint §6.
```
