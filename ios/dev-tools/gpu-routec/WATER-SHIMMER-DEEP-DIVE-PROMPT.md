# Deep-dive prompt — GPU Route C water/flat-surface shimmer

> Paste everything inside the fenced block below into a fresh Opus 4.8 (max-effort) session, run from the repo
> root `/Users/jordansimpson/runelite-ios` on the branch `gpu`. It is self-contained.

```
You are debugging a rendering artifact in a native iOS port of RuneLite. Work on branch `gpu` of the repo at
/Users/jordansimpson/runelite-ios. Be rigorous and methodical: ISOLATE THE ROOT CAUSE WITH A CONTROLLED
EXPERIMENT BEFORE attempting any fix. A previous pass tried several plausible depth fixes WITHOUT first
isolating the cause and every one backfired (see "WHAT WAS TRIED AND REVERTED"); do not repeat that pattern.

================================================================================
THE PROBLEM
================================================================================
The project runs the REAL, UNMODIFIED RuneLite base `gpu` plugin on an on-device iOS JVM, with the plugin's
LWJGL/rlawt GL calls served by a custom JNI "Route C" GL bridge over native EAGL OpenGL ES 3.0. The 3D scene
now renders correctly and fast (locked 60fps via CAEAGLLayer direct present). BUT: WATER (and, when provoked,
other large flat surfaces) shows flickering BLACK shapes/patches/bands — a z-fighting-like shimmer that
changes frame to frame. The rest of the scene (terrain, trees, players, objects) renders and depth-sorts
correctly. The artifact is most visible on the river in Lumbridge and gets worse at grazing camera angles.

This SAME unmodified plugin renders water perfectly on desktop OpenGL. So the bug is in the iOS/GLES path,
NOT the plugin logic. Your job: find the EXACT root cause and a fix that lives in the bridge / shader-downport
/ boot-config layer (the base plugin source must stay unmodified — that is the whole point of Route C).

================================================================================
ORIENT FIRST (read, in order)
================================================================================
- ios/dev-tools/gpu-routec/BLACK-SCENE-DIAGNOSIS.md      (the prior, now-resolved black-scene saga; context)
- ios/dev-tools/gpu-routec/README.md                     (Route C architecture: facade + JNI bridge)
- CLAUDE.md                                              (project + STRICT commit/attribution rules)
- the cross-session memory: ~/.claude/projects/-Users-jordansimpson-runelite-ios/memory/project_ios_gpu_track.md
  (read the whole DEVICE ROUND section onward — it's the running log of this GPU work)
- the GL bridge: ios/dev-tools/gpu-routec/app/RouteCBridge.{h,m} and ios/dev-tools/gpu-routec/jni/routec_gl_jni.m
- the plugin: runelite-client/src/main/java/net/runelite/client/plugins/gpu/{GpuPlugin.java, Mat4.java,
  Zone.java, VBO.java, VAO.java}
- the SHADERS as actually run on device: pull Documents/routec-shaders/*.glsl from the device (the bridge dumps
  the downported GLSL ES 3.00 it feeds glShaderSource). Scene vertex = the one with "gl_Position = screenPos;";
  scene fragment = the one with "fTextureId"/"fFogAmount".

================================================================================
CONFIRMED FACTS — do NOT re-derive or re-litigate
================================================================================
- The bridge works end-to-end: EAGL ES3 context, the unmodified plugin renders the real scene, CAEAGLLayer
  direct present, 60fps. GPU render time is ~2.8 ms/frame (huge headroom) — so an EXPENSIVE depth approach
  (e.g. one that disables early-Z) is affordable; perf is NOT a constraint for the fix.
- iOS GLES 3.0 on this device (iPhone 17 Pro Max / A19 Pro, iOS 26.5) exposes NEITHER GL_EXT_clip_control NOR
  any depth_clamp/clip_control extension (verified on device via glGetString(GL_EXTENSIONS)). So real
  glClipControl is unavailable.
- The depth renderbuffer is GL_DEPTH_COMPONENT32F (float). The scene FBO (fboScene) is MSAA (samples=2,
  RGBA8 color + 32F depth), resolved to the default FBO via glBlitFramebuffer (COLOR only, GL_NEAREST).
- The water shimmer PRE-EXISTED all depth experiments: it was present the moment the scene first rendered
  correctly (right after a vertex-buffer endianness fix). The depth experiments only SPREAD it; they did not
  create it and did not fix it.
- IMPORTANT UNKNOWN (your first question to answer): it is NOT actually confirmed that the water shimmer is
  depth z-fighting. THREE different depth fixes failed to resolve the water specifically, which is a strong hint
  the water cause may be something else (alpha pass / animated texture / fog / coplanar water-vs-riverbed).
  Treat "it's z-fighting" as an UNVERIFIED hypothesis, not a fact.

================================================================================
THE DEPTH SETUP (the leading-but-unproven suspect)
================================================================================
The base plugin uses REVERSE-Z:
- Mat4.projection(w,h,50) (Mat4.java:95) builds a projection whose clip.z is the CONSTANT 2*near = 100 and
  whose clip.w = view-space Z. So NDC.z = clip.z/clip.w = 100/viewZ, in [0,1], near=1 / far=0.
- GpuPlugin sets glClearDepth(0) and glDepthFunc(GL_GREATER) (GpuPlugin.java ~973/987/993) so "nearer = larger
  depth, kept by GREATER", with the far plane cleared to 0.
- On DESKTOP it ALSO calls glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE) (GpuPlugin.java ~357, only when
  glCapabilities.OpenGL45) so the clip-z convention is [0,1] and the 32F depth buffer keeps full reverse-Z
  precision (best precision near 0 = the far plane). THIS IS THE KNOWN DESKTOP-vs-iOS DELTA.
- On iOS there is no glClipControl, so the GL pipeline uses the standard [-1,1] clip-z convention and
  window_z = (NDC.z+1)/2, i.e. NDC.z in [0,1] maps to window_z in [0.5,1] — HALF the depth range, and the
  reverse-Z float-near-0 precision benefit is lost. THEORY: this causes z-fighting, worst on large/flat/far
  surfaces like water. (The bridge's glClipControl shim is intentionally a no-op.)
- There is ALSO a per-vertex depth bias in the scene vertex shader: `screenPos.z += float(bias)/128.0`
  (bias = abhsl>>16 & 0xff). This is the plugin's mechanism to layer near-coplanar faces — any depth fix must
  preserve it.

================================================================================
WHAT WAS TRIED AND REVERTED — do NOT repeat these as-is
================================================================================
All three of the following were implemented, tested on device, and REVERTED because they did not fix the water
and/or made things worse. They are reverted in the current HEAD; the current state is the clean baseline
(lossy [0.5,1] window depth, stable, only the original water shimmer).
1. worldProj clip-z REMAP (commit history): in the bridge's glUniformMatrix4fv, left-multiply the scene's
   worldProj by Z (row2' = 2*row2 - row3) to map clip-z [0,1]->[-1,1] so window depth fills [0,1]. RESULT:
   recovered the full [0,1] RANGE but NOT the precision (catastrophic cancellation near -1 for far geometry),
   water still z-fought, and the artifact spread beyond water. REVERTED.
2. gl_FragDepth WRITE (shader downport): scene vertex passes clip.z as a varying; scene fragment writes
   gl_FragDepth = clip.z * gl_FragCoord.w (= clip.z/clip.w = NDC.z) to bypass the lossy window transform.
   RESULT: cleaned up TOP-DOWN depth but made GRAZING angles WORSE (large flickering dark regions over the
   ground) and didn't fix water. Likely cause: the varying clip.z is perspective-correct-interpolated, but the
   correct window depth is SCREEN-LINEAR interpolated, and GLSL ES 3.00 has NO `noperspective` qualifier (the
   downport even has to strip it), so at grazing angles (huge depth gradient) the per-fragment depth diverged.
   REVERTED.
3. glDepthFunc GL_GREATER -> GL_GEQUAL (bridge shim): so coplanar ties draw (last-writer wins) instead of being
   rejected. RESULT: did not fix water. REVERTED.
Lesson: incremental depth hacks in the rasterized-depth path keep trading one artifact for another. If the
cause IS depth, the fix probably needs to be PRINCIPLED and COMPLETE (see "candidate fixes"), not incremental.

================================================================================
STEP 1 — ISOLATE THE CAUSE (do this before any fix). Each is a cheap, gated, A/B experiment in the bridge.
================================================================================
Run each as a controlled toggle (a #define or a gated branch in the bridge / shader downport), build, have the
user run on device, and OBSERVE whether the water shimmer changes. Decide the cause from evidence, not theory.
a) FOG TEST. The device config has gpu.fogDepth=0, which makes fogFactorLinear(dist,0, fogDepth*TILE_SIZE)
   divide by zero (the scene fragment does mix(color, fogColor.rgb, fFogAmount)). Force fFogAmount=0 in the
   scene fragment downport. If the shimmer/dark stops -> it's the FOG (fix: sane fogDepth via config/profile,
   or guard the degenerate fogFactorLinear, or the fogColor uniform). NOTE: gpu.fogDepth lives in the device
   profile Documents/.runelite/profiles2/default-*.properties (key gpu.fogDepth); you can edit+push it like
   gpu.fpsTarget was changed. Also check what `fogColor` actually is on device (is it black?).
b) TEXTURE TEST. Water uses an ANIMATED texture (scene vertex: fUv += float(tick) * textureAnim *
   (1.0/128.0)). Force the scene fragment to output a solid color (ignore the texture sample). If the shimmer
   stops -> it's the animated-texture sampling (suspects: GL_TEXTURE_2D_ARRAY wrap mode not GL_REPEAT so
   scrolled UVs sample black at edges; or float(tick) precision at large tick; or a texture-array layer/marshal
   bug). Inspect how the texture array + sampler params are set up through the bridge.
c) DEPTH TEST. Force glDepthFunc(GL_ALWAYS) (or disable GL_DEPTH_TEST) for one run. The scene will look wrong,
   but if the WATER shimmer specifically vanishes -> it IS depth (then pursue a principled depth fix). If the
   water still shimmers with depth test off -> it is NOT depth (focus on a/b/d).
d) ALPHA-PASS TEST. Determine whether water is drawn in the OPAQUE pass (DrawCallbacks.drawZoneOpaque) or the
   ALPHA pass (drawZoneAlpha, translucent, depth-write off, blended). Log which pass touches the water tiles.
   If alpha: investigate blend order / depth-write interaction. (The gpu plugin's per-pass GL state is in
   GpuPlugin.drawZoneOpaque/drawZoneAlpha/drawPass.)
e) DISTANCE/PRECISION TEST. Have the user zoom in close vs far on the water. If the shimmer only appears at
   distance -> consistent with depth precision; if it appears even up close -> not precision.

================================================================================
STEP 2 — GROUND TRUTH AGAINST DESKTOP
================================================================================
The unmodified base gpu plugin renders water cleanly on DESKTOP OpenGL. Capture the EXACT desktop GL state for
the scene draw (depth func, depth range, clip-control state, the projection matrix values, depth buffer format,
texture wrap modes, blend state, which pass draws water) and DIFF it against the iOS bridge's state (the bridge
has a one-shot GLSTATE dump at the first scene draw -> routec-trace.log; extend it as needed). The fix is
"replicate, on GLES, the one desktop behavior we're missing." If the only delta is glClipControl, the fix is a
depth-precision fix; if there's another delta (wrap mode, blend, fog), that's your cause. W2 is desktop-first;
you can run the real client on desktop OpenJDK (see ios/dev-tools/headless/ harnesses) with the real gpu plugin
to read desktop GL state.

================================================================================
CANDIDATE FIXES (only after STEP 1 names the cause)
================================================================================
- If DEPTH precision: the principled fix is to give GLES a [0,1]-style high-precision reverse-Z without
  glClipControl. Options, in rough order of soundness:
  (i) Convert the scene to a STANDARD FORWARD-Z projection in the bridge: intercept the worldProj uniform to
      replace its z-row with a finite-far forward-Z mapping, AND intercept glDepthFunc(GREATER->LESS) +
      glClearDepth(0->1). Standard GLES depth has good precision and needs no glClipControl. THE HARD PART: the
      final clip = worldProj * entityProj * vert, and entityProj is IDENTITY for terrain/water tiles but a
      per-entity matrix for models — so a z-row swap at worldProj is only clean when entityProj is affine
      (w-preserving). Verify entityProj is affine for ALL scene geometry (check FloatProjection / how
      uniEntityProj is set in GpuPlugin.preSceneDraw/drawPass/drawDynamic) before relying on this.
  (ii) A CORRECT gl_FragDepth (the previous attempt's interpolation was wrong). You need the screen-linear
      window depth at full precision. Derivation must account for: no `noperspective` in ES 3.00, the per-vertex
      bias, and that gl_FragCoord.w = 1/clip.w is the high-precision quantity available per fragment. Prove the
      formula on paper (and ideally in the desktop/Simulator GLES shader harness in ios/dev-tools/gpu-b1/)
      BEFORE shipping to device.
  (iii) Polygon offset (glPolygonOffset) for the water/coplanar pass if the cause is coplanar water-vs-riverbed
      ties specifically.
- If FOG: set gpu.fogDepth to a sane non-zero value (config/profile), and/or guard the divide-by-zero, and/or
  fix the fogColor. Confirm by toggling.
- If TEXTURE: fix the GL_TEXTURE_2D_ARRAY wrap mode (ensure GL_REPEAT for the animated water), or the tick/UV
  marshalling.

================================================================================
DEVICE / BUILD / TEST WORKFLOW (the user drives the phone; you build + install + pull logs)
================================================================================
- JAVA_HOME=/opt/homebrew/opt/openjdk@21 . Device id DEV=AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B . Bundle
  dev.audaro.helloshell .
- BRIDGE-ONLY change (RouteCBridge.* / routec_gl_jni.m / shader downport): rebuild the app only:
  xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell -destination "id=$DEV" \
    -derivedDataPath build/dd-gpu -allowProvisioningUpdates build
  then: xcrun devicectl device install app --device "$DEV" \
    build/dd-gpu/Build/Products/Debug-iphoneos/HelloShell.app
  (the "Failed to load provisioning paramter list ... No provider was found" line is NON-FATAL; the install
  still succeeds — look for "App installed: bundleID: dev.audaro.helloshell".)
- JAVA (client jar) change: ./gradlew :client:shadowJar -> ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh ->
  ios/app/build-probes.sh -> ios/dev-tools/gpu-routec/l1/build-l1-probe.sh -> (cd ios/app && xcodegen generate)
  -> xcodebuild -> install.
- RUN GPU on device: the user attaches StikDebug, then taps the "▶ Run GPU (Route C)" button. Ask the user to
  attach + trigger whenever you need a device run. The user logs in; the autologin + WelcomePlayProbe enter the
  world automatically.
- IN CAEAGLLAYER PRESENT MODE THERE IS NO READBACK, so the on-device PNG frame dump (gpuframedump) does NOT
  capture the GPU frame. To SEE the artifact: the user takes a SCREEN RECORDING; pull it from ~/Downloads and
  frame-extract with ffmpeg (e.g. `ffmpeg -i in.MP4 -vf fps=15 f%03d.png`; to find artifact frames, crop to the
  water region and sort PNGs by file size — black patches change the size). Static screenshots from the user
  also work. (If you need pixel-level GPU readback for a diagnostic, temporarily route swapBuffers back through
  the offscreen-FBO + readback path that still exists as the L1/CPU fallback in RouteCBridge.)
- LOGS to pull (xcrun devicectl device copy from --device "$DEV" --domain-type appDataContainer
  --domain-identifier dev.audaro.helloshell --source Documents/<f> --destination /tmp/<f>):
  routec-trace.log (per-launch GL bridge trace: GLSTATE dump, perf counter, shimable one-shots),
  routec-shaders/ (the downported GLSL it actually compiled), .runelite/logs/client.log (cumulative slf4j,
  GpuPlugin lifecycle; LARGE — the copy often drops, retry; or truncate on device first).
- The bridge already has gated diagnostics you can extend: rc_trace(), the first-scene GLSTATE dump
  (rc_dump_gl_state), the shader dump in RouteCBridge.downportShaderSource, and the perf counter in
  rc_AWT_swapBuffers_0. Add new gated one-shots rather than spamming.

================================================================================
CONSTRAINTS
================================================================================
- Do NOT modify the base gpu plugin source (runelite-client/.../plugins/gpu/*) or its shader .glsl resources.
  Route C keeps the plugin SOURCE unmodified; all fixes go in the bridge (RouteCBridge / routec_gl_jni / the
  glShaderSource-time downport), the W2 headless layer, or boot/config.
- Commit + push under the user's name ONLY (Jordan Simpson <sayhijordy@gmail.com>). NO AI/tool attribution
  anywhere — no Co-Authored-By, no "generated with", no mention of Claude/Codex/Anthropic in commits, comments,
  or any committed doc. Scrub staged content before committing. Auto-commit + push at logical checkpoints.
- Be honest about uncertainty; verify on device; don't declare a fix until a screen recording shows clean water.

================================================================================
DELIVERABLE
================================================================================
1. A DEFINITIVE root cause for the water shimmer, proven by a controlled isolation experiment (STEP 1), not by
   plausibility. State which of fog/texture/depth/alpha/other it is and the evidence.
2. A fix in the bridge/downport/config layer, verified clean on device (the user confirms via a screen
   recording / screenshot showing the river without the shimmer), with no regression elsewhere (check grazing
   angles + entity depth sorting).
3. Update ios/dev-tools/gpu-routec/BLACK-SCENE-DIAGNOSIS.md (or a new doc) and the memory file with the result.
```
