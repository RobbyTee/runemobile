# Handoff — validate the iOS GPU depth-z-fighting fix on the Simulator GLES (off-device), then confirm on device

> Paste everything inside the fenced block below into a fresh session, run from the repo root
> `/Users/jordansimpson/runelite-ios` on branch `gpu`. It is self-contained. The goal is to break the slow
> one-hypothesis-per-device-round loop by validating the depth fix on the iOS Simulator GLES first.

```
You are continuing a focused, well-diagnosed task on branch `gpu` of /Users/jordansimpson/runelite-ios. The
project runs the REAL, UNMODIFIED RuneLite base `gpu` plugin on an on-device iOS JVM, its LWJGL/rlawt GL calls
served by a custom JNI "Route C" GL bridge over native EAGL OpenGL ES 3.0. The world renders correctly at
60-120fps. ONE artifact remains and it is fully diagnosed; your job is to land the fix EFFICIENTLY (validate
off-device first, then a single device confirmation), NOT to re-diagnose.

================================================================================
READ FIRST (the diagnosis is done — do not re-tread it)
================================================================================
- ios/dev-tools/gpu-routec/WATER-SHIMMER-DIAGNOSIS.md   ← THE full diagnosis: every experiment + result. Read it.
- the project's cross-session note `project_ios_gpu_track` (auto-loaded) — the "WATER-SHIMMER DEEP-DIVE" section.
- the bridge: ios/dev-tools/gpu-routec/app/RouteCBridge.{h,m} + jni/routec_gl_jni.m (the fix + the gated modes).
- the Simulator GLES harness you will adapt: ios/dev-tools/gpu-b1/ (app/B1Probe.m, B1Downport.m, main.m,
  project.yml; it already compiles + renders REAL GLES ES-3.00 shaders to an offscreen FBO + glReadPixels on the
  iOS Simulator — `GL_VERSION OpenGL ES 3.0 APPLE-23.1.1`, the same tier as the device, and ALSO no glClipControl).

================================================================================
THE CONFIRMED PROBLEM (do NOT re-litigate — these are proven)
================================================================================
There are TWO artifacts; only B remains:
- A) Water TEXTURE shimmer — FIXED in the bridge (trilinear + anisotropy + textureGrad). Proven by the `solidtex`
     A/B (0.351->0.020). Leave it.
- B) DEPTH Z-FIGHTING (the target) — black jagged bands on the river (water vs dark riverbed, coplanar) + a
     red/white/blue STRIPED MOIRÉ on distant terrain. iOS GLES has NO glClipControl, so the plugin's reverse-Z
     (Mat4.projection: clip.z = 2*near = 100 CONSTANT, clip.w = viewZ, NDC.z = 100/viewZ in (0,1]) is squeezed
     into window_z = (NDC.z+1)/2 in [0.5, ~0.55] at distance -> far precision collapses. Desktop has
     glClipControl(ZERO_TO_ONE) so window_z = NDC.z = 100/viewZ directly (full 32F precision at far) and is clean.
     CONFIRMED depth-driven: `depthalways` (force glDepthFunc GL_ALWAYS) does NOT remove the river band — the dark
     surface takes over (z-fight, dark surface wins with the test off). Depth buffer is genuine 32F (depthBits=32
     framebuffer-attachment probe) — NOT buffer-limited. Mips are populated (not the cause). Fog is off (not it).

================================================================================
THE FIX TO VALIDATE (already wired in the bridge as mode "fragdepth")
================================================================================
Write the reverse-Z depth via gl_FragDepth so window_z = NDC.z = 100/viewZ DIRECTLY (= desktop's glClipControl
values, same 32F precision), bypassing the lossy (NDC+1)/2 half-range:
- Scene VERTEX downport adds:  flat out float vClipDepth;   and   vClipDepth = screenPos.z;  (after gl_Position).
- Scene FRAGMENT downport adds: flat in float vClipDepth;   and   gl_FragDepth = vClipDepth * gl_FragCoord.w;
  (= clip.z/clip.w = 100/viewZ). clip.z is carried FLAT (it is ~constant per face: 2*near + per-vertex bias/128) —
  this is the key fix vs the deep-dive's earlier gl_FragDepth attempt, which passed clip.z as a PERSPECTIVE varying
  and diverged at grazing. Math: with constant clip.z=C, C*gl_FragCoord.w = C*screenLinearInterp(1/w) =
  screenLinearInterp(NDC.z) = the correct planar window depth at ALL angles, at full float precision.
- Depth func stays GL_GREATER + glClearDepthf(0) (near=1, far=0). gl_FragDepth auto-clamps to [0,1].
It is already applied for mode "fragdepth" in RouteCBridge.downportShaderSource (gated `depthFixActive`). Read it.

OPEN QUESTION this task answers: does Apple's GLES-over-Metal actually HONOR gl_FragDepth here, and does it resolve
the z-fight? The earlier on-device `fragdepth` run only looked at the BIRDSEYE horizon (that view is dominated by a
DIFFERENT problem — sub-pixel ALIASING of thin far geometry, which gl_FragDepth does not address) and was a wash;
the WATER BAND (a clean z-fight case) was never checked.

================================================================================
STEP 1 — VALIDATE ON THE SIMULATOR GLES (off-device, fast, no StikDebug). This is the whole point.
================================================================================
Adapt the gpu-b1 harness (or write a sibling EAGL-only Simulator app) into a minimal, CONTROLLED z-fight repro:
1. EAGL ES3 context + an offscreen FBO: RGBA8 color + GL_DEPTH_COMPONENT32F depth (match the device scene FBO).
2. Render TWO large overlapping quads that are NEARLY coplanar at a LARGE view distance, using the EXACT plugin
   depth setup: the reverse-Z projection (clip.z = 2*near = 100 constant; clip.w = viewZ ~ 3000..8000; so
   NDC.z = 100/viewZ), glDepthFunc(GL_GREATER), glClearDepthf(0), NO glClipControl. Give quad A and quad B
   different solid colors, B at a tiny viewZ delta (e.g. 1-10 units) behind A. Draw A then B.
3. CONTROL (no gl_FragDepth): glReadPixels and measure — the two colors should Z-FIGHT (a moiré of A/B across the
   overlap), reproducing the device bug on Apple GLES. If it does NOT z-fight here, the Simulator precision differs
   from the device and you must say so (then the Simulator can only test "does gl_FragDepth break anything").
4. FIX (add the gl_FragDepth writes above to both shaders): glReadPixels again — the nearer quad (A) should now win
   CLEANLY across the whole overlap (no moiré). Quantify (count mixed/fighting pixels before vs after).
VERDICT: gl_FragDepth resolves the z-fight on Apple GLES (md5-gate the readback like the other gpu-b1 verdicts) =>
high confidence; proceed to Step 2. If it does NOT resolve it on the Simulator -> gl_FragDepth is not the answer on
this driver; go to the FALLBACK below and do NOT burn a device round on gl_FragDepth.
Simulator facts: iPhone 17 Pro Max sim UDID 2872C3DB-671E-41DC-A6C5-0C87E3591DDE; build via xcodegen + xcodebuild
(see gpu-b1/project.yml + gpu-b1-verdict.sh); pull the container with `xcrun simctl get_app_container <udid>
<bundle> data`. The B1 probe is the working template for EAGL-context + shader-compile + offscreen-render + readback.

================================================================================
STEP 2 — ONE device confirmation (only if Step 1 is green)
================================================================================
mode "fragdepth" is ALREADY wired + was last SET on the device (Documents/routec-water-mode.txt = "fragdepth").
- Device is currently OFF this machine's network — building/installing/pushing needs it reconnected (USB/same LAN).
- JAVA_HOME=/opt/homebrew/opt/openjdk@21 ; DEV=AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B ; bundle dev.audaro.helloshell.
- BRIDGE-ONLY rebuild: xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell -destination "id=$DEV"
  -derivedDataPath build/dd-gpu -allowProvisioningUpdates build ; then xcrun devicectl device install app
  --device "$DEV" build/dd-gpu/Build/Products/Debug-iphoneos/HelloShell.app  (the "No provider was found" line is
  non-fatal). Push a mode file with `xcrun devicectl device copy to --device "$DEV" --domain-type appDataContainer
  --domain-identifier dev.audaro.helloshell --source <local> --destination Documents/routec-water-mode.txt`.
- The user attaches StikDebug + taps "▶ Run GPU (Route C)", logs in via autologin, navigates to the Lumbridge
  river. ASK THE USER for a screenshot of the river band (no readback in present mode; screenshots/recordings only).
- VERDICT: river black band gone => ship `fragdepth` + the texture fix as the final build (combine: make
  depthFixActive true for the shipping fix too, not just the "fragdepth" A/B). Band still there => fallback.

================================================================================
FALLBACK if gl_FragDepth fails (candidate #2, partially wired)
================================================================================
Polygon offset for the depth-mask-off ALPHA pass: RouteCBridge.alphaOffsetUnits + "offsetalpha*" modes already
exist (bridge applies a reverse-Z polygon offset during alpha draws) — UNTESTED. It targets water/overlay z-fight
specifically (only works because the fighting surfaces are in DIFFERENT draws). Validate the same way (Simulator
first). Also possible: identify which scene draws carry water vs riverbed (SceneUploader / opaque-vs-alpha split in
GpuPlugin.drawZoneOpaque/drawZoneAlpha, Zone.renderOpaque/renderAlpha).

================================================================================
BRIDGE MODE MECHANISM (no rebuild needed to switch experiments)
================================================================================
Gated by the file Documents/routec-water-mode.txt (read once per launch by RouteCBridge.waterDiagMode). Modes:
baseline | stripes | solidtex | depthalways | fragdepth | fix | fix2x | offsetalpha[8|32|128|-32]. Flags:
waterFixActive (texture fix), depthFixActive (gl_FragDepth), superSampleActive (2x SS), alphaOffsetUnits (poly
offset). Trace probes land in Documents/routec-trace.log; downported shaders in Documents/routec-shaders/. ALL of
this gated scaffolding (probes, modes, the non-shipping experiments) MUST be STRIPPED before any merge.

================================================================================
CONSTRAINTS
================================================================================
- Do NOT modify the base gpu plugin SOURCE (runelite-client/.../plugins/gpu/*) or its .glsl — all fixes live in the
  bridge (RouteCBridge / routec_gl_jni / the glShaderSource-time downport) or boot/config. Route C = unmodified
  plugin source.
- Commit + push under Jordan Simpson <sayhijordy@gmail.com> ONLY — the repo must read as entirely the user's own
  work. No automated-tooling attribution of any kind anywhere (no authorship trailers, no machine-authored
  notices, no tool/model names) in commits, code comments, or committed docs. Scrub staged content before
  committing. Auto-commit + push at logical checkpoints.
- Be efficient: validate on the Simulator BEFORE asking the user for any device run. Be honest about uncertainty.

================================================================================
CURRENT STATE
================================================================================
- Committed + pushed on `gpu` (commit 309698fad and later): the texture fix + all the gated diagnostics + the
  WATER-SHIMMER-DIAGNOSIS.md writeup. The `offsetalpha*` polygon-offset fallback is wired but untested.
- The texture fix (Artifact A) is good and should ship; the only open item is Artifact B (depth z-fighting).
```
