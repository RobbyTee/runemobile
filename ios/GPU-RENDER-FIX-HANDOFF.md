# Handoff — fix the base GPU render bug (iOS)

> Branch `vanilla-default` (off `master`). Picks up after the "renderers as plugins" model landed
> (device-confirmed) — base GPU rendering is the **one remaining item**. All file:line refs drift; re-grep.

## Goal
The base **GPU plugin** renders the scene with a visible bug. **117HD and CPU render correctly.**
Make base GPU render correctly too. This bug predates the 117HD work (was never solved before 117HD);
the bet is it's now far more tractable because base GPU and 117HD share the same base GL bridge, so the
hard shared problems are already fixed and 117HD is a working A/B reference on the same device GL.

## The symptom (from a device screenshot, 2026-06-11)
- A large **red/pink gradient** fills the **upper-left** of the viewport, with a clean **diagonal
  boundary that exactly follows the edge of the loaded scene** (the green terrain stops along that
  diagonal; the red is everything drawn *beyond* the scene tiles). The red is darker toward the
  top-left corner and fades toward the boundary (a gradient, not a flat fill).
- **Everything else renders correctly:** terrain (green), river (blue), buildings (brown/tile roofs),
  trees, NPCs + HP bars + hitsplats, the stone paths, the bridge. The whole native HUD is correct
  (minimap with proper terrain colours, orbs, inventory icons, chat tab "Public / On", buttons). 32 FPS.
- User's words: "bands on water and geometric shape colors" — primary artifact is the red region;
  watch for subtler water banding too.

### Leading hypothesis
This is **not** geometry or scene-colour corruption — the scene + HUD are right. It's the **sky / fog
/ out-of-scene fill colour rendering RED**. OSRS's sky/fog is light blue/grey; a light-blue colour with
the **R and B channels swapped** reads as red. So suspect a **colour-channel-order (BGRA↔RGBA) or
byte-order issue on the fog/sky/clear colour specifically** — a single colour value/uniform, not the
textures (which look right) and not the geometry. Start there.

## Where to look (ranked)
1. **The fog / sky / clear colour path in the base GPU plugin + bridge.** `GpuPlugin` computes the
   fog colour (and fogDepth) and the glClear colour from the scene/skybox colour, and uploads it as a
   shader uniform (`fogColor` vec4) + `glClearColor`. Trace that one colour from the engine int →
   plugin extraction → uniform/clear. If the int's bytes or the channel order are wrong on iOS, blue→red.
   `runelite-client/.../plugins/gpu/GpuPlugin.java` (grep `fogColor`, `glClearColor`, `skyboxColor`,
   `getSkyboxColor`, `Fog`). The shader: `runelite-client/.../plugins/gpu/*.glsl` (fog blend).
2. **Diff the base bridge against the zone bridge's colour/texture fixes.** The 117HD/zone bridge got a
   specific `BGRA + UNSIGNED_INT_8_8_8_8_REV → GL_BGRA/GL_RGBA + UNSIGNED_BYTE` fix
   (`ios/dev-tools/gpu-zone/jni/routec_zone_jni.m`: `z_glTexSubImage2D_J`, `z_glTexSubImage3D`). The
   base bridge (`ios/dev-tools/gpu-routec/jni/routec_gl_jni.m`) handles BGRA *differently* — a
   per-texture **R↔B swizzle** (`rc_GL_glTexImage2D_0` ~line 662-688, `rc_GL_glTexSubImage2D_0` ~738).
   That swizzle is the prime divergence; if a colour comes through a path the swizzle doesn't cover (a
   uniform, a renderbuffer, the clear colour) it won't be corrected. Compare what 117HD does right.
3. **Water banding** (secondary): depth precision / the water blend in the gpu shaders.

## What's already ruled out / known-good
- **Byte order on MAPPED buffers** — the base bridge already applies native order
  (`rc_native_order` → `rc_GL_glMapBuffer_0`/`rc_GL_glMapBufferRange_0`, routec_gl_jni.m ~589-604). But
  base GPU uploads most geometry via `glBufferData` from `GpuIntBuffer`/`GpuFloatBuffer` (NIO buffers,
  nativeOrder on both platforms), not maps — so geometry positions/HSL look correct (they do).
- The scene geometry, scene textures, gouraud colours, and the entire HUD all render correctly — so the
  bug is narrow (the sky/fog/out-of-scene colour), not the whole pipeline.

## Diagnostic toolkit (proven on the 117HD grind — see [project_ios_gpu_track] memory)
- **Forced-colour discriminators**: temporarily force the fog/clear/sky colour to a known value in the
  shader or the bridge to confirm which value is the red one and where it's set.
- **A/B against 117HD**: 117HD renders the same world correctly on the same bridge — diff the colour/fog
  handling between `GpuPlugin` (+ base bridge) and `HdPlugin` (+ zone bridge).
- **Re-arm the GPU diagnostics** (they were stripped for shipping): in `JVMLauncher.m` the
  `routecGpuMode` block, re-add `-Drunelite.gpudiag=true` (GpuSceneDiag → client.log) and optionally
  `-Drunelite.gpuframedump=true` (PNGs to `Documents/routec-frames/` — strip after; it grows fast).
- GL errors are invisible on device → reason from spec + symptom; the **Simulator EAGL shader probe**
  (used throughout the 117HD work) pre-validates shader edits off-device.

## Reproduce / select base GPU
The renderer is chosen in the **plugins panel** now (the launch buttons are gone): open ⚙ → toggle
**GPU** on → "Restart to apply" → fully quit + relaunch. This persists via `NSUserDefaults rendererPref
= "gpu"` (read in `onPlayTapped`, ViewController.m). For a faster dev loop you can temporarily default
`rendererPref` to `gpu` in `onPlayTapped`, or set the NSUserDefault directly. Base GPU boots via
`pendingRouteCGpu` → `routecGpuMode` → `RouteCGpuStarter` (force-enables + starts GpuPlugin; GPU is
force-disabled in every other mode via `-Drunelite.forceDisablePlugins=gpuplugin`, omitted in GPU mode).
Present path for base GPU = **readback** (`glReadPixels` → int[] → native blit), NOT the CAEAGL direct
present (that's 117HD only) — see the present block in `ViewController.m` (the `pendingRouteCZoneDirect`
condition).

## Build & test loop
```
# Bridge-only change (routec_gl_jni.m): app rebuild only — jars unchanged
xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell \
  -destination "generic/platform=iOS" -derivedDataPath build/dd-gpu -allowProvisioningUpdates build
xcrun devicectl device install app --device AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B \
  build/dd-gpu/Build/Products/Debug-iphoneos/HelloShell.app

# Java change (GpuPlugin / shaders): rebuild + restage the gpu jar first
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :client:delombok :client:shadowJar -q
( cd ios/dev-tools/gpu-routec && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./stage-ios-gpu-jar.sh )
# (then the app build + install above)
```
- Device UDID `AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B` (iPhone 17 Pro Max). **User drives the StikDebug
  launch + plays; Claude builds + installs + pulls logs.** Unlock the phone when StikDebug attaches.
- Logs: `client.log` (Java/GpuSceneDiag) + `jvm-stdio.log` at
  `Documents/.runelite/logs/client.log` / `Documents/jvm-stdio.log`; pull with
  `xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer
  --domain-identifier dev.audaro.helloshell --source <path> --destination /tmp/...` (app must be fully
  quit; the log is cumulative + truncates per JVM-launch → read the LAST session).

## Context the new session needs
- "Renderers as plugins" is DONE + device-confirmed (`project_ios_renderers_as_plugins` memory, branch
  tip ~`04b4e3037`): GPU + 117HD selectable in the panel, apply-on-next-launch + restart message,
  default CPU, no launch buttons. Don't regress it.
- The full 117HD fix catalog (byte order, BGRA, MSAA, TBO repack, dynamic models, etc.) is in the
  `project_ios_gpu_track` memory — that's the reference for "what iOS-GLES gotchas look like."
- Commit convention: under the user's name only, NO AI/Claude attribution anywhere; auto-commit + push
  at checkpoints; scrub staged content first.
- NOT merged to master. Merge decision (base GPU fixed first, or merge with GPU as a known-buggy opt-in)
  is the user's.
```
