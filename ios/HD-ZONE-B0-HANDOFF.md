# Handoff → Phase B0 (iOS 117HD ZoneRenderer bring-up)

> Branch `gpu`. Self-contained kickoff for a fresh session. Project memory auto-loads, so this is just the focused
> "where we are + what's next." Everything below is pushed to `origin/gpu`.

---

## ★★★ B0 GATE FULLY MET (2026-06-10) — 117HD RENDERS THE LIVE OSRS SCENE *AND* TEXTURED PLAYERS/NPCs ON THE iPHONE.

**Read this first; it supersedes the older sections below.** Over one long device session the full ZoneRenderer
pipeline was brought up on the iPhone 17 Pro Max (A19 Pro). The **static HD world renders correctly** (device
screenshots): textured rolling terrain, water, the Lumbridge-area stone bridge, HD trees, dirt paths, rocks,
fences, plants — full 117HD materials + lighting. The fixes, in order (all on `gpu`, pushed; each a separate
commit, see `git log`):

1. **Material UBO → texture** (`b8d44f817`) — UBOMaterials 57 KB > Apple's 16 KB cap → RGBA32I `materialStore`
   isampler2D (`gpu-zone/ios-overrides/uniforms/materials.glsl` + `java-overrides/.../MaterialManager.java` +
   Scene/ShadowShaderProgram). 2. **Shadow int/float** (`30fbe3cd4`) — `ios-overrides/shadow_frag.glsl` for the
   FAST/DETAILED+TRANSPARENCY branches the Z0 probe (SHADOW_MODE=0) never tested. 3. **Heap OOM** (`610eb4da8`) —
   `-Xmx256m→1024m` + gate HeadlessCapture off in GPU mode. 4. **Buffer map failures** (`c373ab475` +
   `ce2b6c91b`) — orphan-on-write (`GL_MAP_INVALIDATE_BUFFER_BIT`) + host the textureFaces TBO on
   `GL_COPY_WRITE_BUFFER` (GL_TEXTURE_BUFFER is ES 3.2). 5. **TBO repack SIGSEGV** (`2e9c67c6c`) — upload exactly
   n texels, not the full w×h rect. 6. **Black world / MSAA** (`814b63d11`) — RLHDStarter forces
   `antiAliasingMode=DISABLED`; the 8× MSAA `glBlitFramebuffer` resolve fails on Apple GLES and falls back to a
   black clear. 7. **★ Byte order** (`0862006a9`) — THE big one: `z_glMapBuffer`/`z_glMapBufferRange` returned JNI
   `NewDirectByteBuffer` (BIG-endian) but the GPU reads native little-endian → every vertex/face int+float
   byte-swapped (garbage geometry + zero HSL). Added `z_native_order` (the base bridge had it; zone omitted it).
   8. **BGRA texture array** (`ab4dc9f64`) — RLHD uploads the material texture array as `GL_BGRA +
   GL_UNSIGNED_INT_8_8_8_8_REV` (invalid on iOS GLES 3.0 → black textures); `z_glTexSubImage3D` now swaps R↔B and
   uploads `GL_RGBA + GL_UNSIGNED_BYTE`. This made the STATIC SCENE render in full HD.

**ANIMATED MODELS — FIXED + ★ CONFIRMED ON DEVICE (`fbbe40945`).** A packed Lumbridge bridge now renders every
player + NPC as a fully textured HD model (device screenshot) — the static scene AND dynamic entities are correct.
The player + NPCs previously rendered as BLACK silhouettes (geometry correct, per-face colour zero) while the
static scene was perfectly textured. Lead (d)
above was right: it's the per-face `tbo` (VAO::TBO `GLTextureBuffer`) repack, NOT persistent mapping. The earlier
`7fe1563c3` (force `z_glMapBufferRange`→NULL for `GL_MAP_PERSISTENT_BIT`) was **inert**: on iOS `SUPPORTS_STORAGE_
BUFFERS=false` (`GL_ARB_buffer_storage` false; `rc_make_caps` only sets `OpenGL33`), so `isStorageBuffer()` is false
for every buffer → `ensureCapacity`'s persistent block (`GLBuffer.java:451-489`) is **never entered** and
`GL_ARB_map_buffer_range` is false so the writer maps via `z_glMapBuffer` (range fn unused on this path). Both the
dynamic VBO and TBO go through the plain `glBufferData` + `glMapBuffer` path — same as the static scene (hence the
silhouettes upload).

**Root cause:** iOS can't put the textureFaces buffer on a real `GL_TEXTURE_BUFFER` (ES 3.2), so `GLTextureBuffer`
(iOS override) backs it on `GL_COPY_WRITE_BUFFER` and the bridge *copies* the bytes into a 2D RGB32I texture at
`glTexBuffer` time — a snapshot, not a desktop aliasing view, so it must be re-run AFTER the data is written.
`GLTextureBuffer.unmap()` does that (`super.unmap()` + `attachTextureBuffer()`); the **static** scene gets it
because `Zone.unmap()` calls `tboF.unmap()`. But the **dynamic** models stream per-face data through
`GLMappedBufferIntWriter`, whose `flush()` unmapped via `mappedBuffer.unmap()` (the `GLMappedBuffer` directly) —
bypassing the `GLTextureBuffer` override → the 2D colour texture kept the empty `initialize()` snapshot → every
model sampled zero colour → black.

**Fix:** new `java-overrides/.../GLMappedBufferIntWriter.java` — `flush()` unmaps through `buffer.unmap()` (the
owning `GLBuffer`) instead of `mappedBuffer.unmap()`, so the `GLTextureBuffer` override re-runs the repack with the
filled buffer (`buffer == mappedBuffer.getOwner()`, `buffer.mappedBuffer == this.mappedBuffer` → same
`GLMappedBuffer`, plus the repack; for the plain VBO/EBO writers there's no `unmap` override so it's identical to
before). Inert on desktop. `DynamicModelVAO.unmap()` runs on the render/GL thread (`ZoneRenderer` PASS_ALPHA), so
the repack lands on the EAGL context thread like the static path. Staged GREEN (jar md5 `ec35374e`, class md5
`ff2d464a`); app built + installed; **CONFIRMED rendering textured players/NPCs on device.**

**B0 is DONE.** The full 117HD pipeline renders correct static *and* animated geometry on the iPhone 17 Pro Max.
Remaining work is the deferred non-blocking cleanup below + the B1+ feature phases (shadows / tiled lighting /
dynamic lights / POM, each turned on in `RLHDStarter`'s minimal config will surface its own shader cast sites).

**Cleanup still owed (deferred, non-blocking):** the JavaBridge continuous frame-dump diagnostic was reverted (tree
clean) but the gpu-zone shaders + diagnostics around it are fine; `ShaderOverlay` (a dev debug overlay) fails its
`smoothstep(int,...)` compile — caught/non-fatal, not on the render path; the `gl_FragDepth` reverse-Z precision
port may be wanted later if distance z-fighting shows (not observed yet). Device UDID
`AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B`; build loop unchanged (see "Build/run loop" in project memory).

---

## Where we are (one paragraph)
The GPU track is on iOS native **EAGL GLES 3.0** (decision locked: EAGL + `gl_FragDepth`, not ANGLE). The base
RuneLite `gpu` plugin already renders the live OSRS 3D world on the iPhone via the Route C JNI-GL bridge. The goal is
**117HD (RLHD)** on iOS. **Phase Z0 (shader conformance) is DONE and GREEN on the real device** (iPhone 17 Pro Max,
A19 Pro): all 6 RLHD zone shaders (scene/shadow/ui × vert/frag) compile to GLSL ES 3.00 and all 3 programs link —
verdict `ALL_ZONE_PROGRAMS_LINK_ES300`, sealed in `ios/dev-tools/gpu-zone/`. So the entire zone shader surface is
proven portable. **Next is Phase B0: actually bring up the RLHD ZoneRenderer.**

## Progress this session (host-only; all pushed to `origin/gpu`)
- **B0.1 GREEN** (`stage-ios-zone-jar.sh`, commit `c76cb1c7b`): unmodified RLHD `@7dded59` (full src vendored +
  gitignored, see `rlhd-src/PROVENANCE.md`) compiles against `[gpu-zone/facade/ : lwjgl-stripped client jar]` →
  `client-1.12.29-SNAPSHOT-rlhd.jar` (304 classes, 0 lwjgl leak, 47 shaders, 9 json; md5
  `04e0b29fa4761cca3386d8eb6ca457d3`). `facade/` (35 classes, seeded from Android's validated RLHD facade-src) is
  the RLHD-complete superset + the spec for the native delta.
- **B0.3 facade-delta scan** (`scan-facade-delta.sh` → `facade-delta.md`/`.txt`, `used-descriptors-rlhd.txt`,
  commit `2fd63d395`): 209 RLHD facade calls − base 100 = 117 delta, categorized → genuine native surface ≈ **31
  GLES-core forwarders + 3 off-heap + the glTexBuffer→2D repack + 4 shims**; the rest is facade pure-Java or safe
  B0 stub.
- **B0.4 conformance analysis** (`B0-conformance-notes.md`, commit `725e85442`): source-proved that unmodified
  RLHD takes the ES-3.0-core path by **caps gating** — zone is default; `supportsGpu` only needs `OpenGL33`
  (already true); OpenCL/compute never run on the zone path; **indirect draw is caps-gated OFF** (→ auto-fallback
  to direct `glMultiDrawArrays`, Route C shim covers) leaving only the unconditional `GL_DRAW_INDIRECT_BUFFER`
  alloc to remap in the facade; storage-buffers off (`glBufferData` path); `glDrawBuffer`→plural via facade shim.
  **No RLHD source patches needed.**

- **B0.2/B0.3 bridge PRE-WRITTEN + arm64-green** (`jni/routec_zone_jni.m` + `zone-bridge-compile-check.sh`, commit
  `efcf5f428`): the RLHD facade-delta native forwarders, registered ON TOP of the base bridge (untouched) via
  `RouteC_registerZoneNatives`. **53 entries, exact match to `facade-delta.txt`** — 50 GL on GLALL (31 GLES-core
  forwarders + `glDrawBuffer`→plural #11 + `glTexBuffer`→2D RGB32I repack #1 + 17 caps-gated-off no-op stubs) + 3
  off-heap on MemoryUtil. `ZONE_BRIDGE_COMPILES_ARM64` (0 err/0 warn) vs jni.h + OpenGLES(EAGL) + base
  RouteCBridge.h. Mirrors base step 3b (device-only at runtime). See `jni/README.md` for the device wiring +
  TODO(device) list.

### B0.5 DEVICE ROUND — RLHD boots + scene shaders compile on device; one blocker left (2026-06-09)

Wired + ran the full zone boot on the iPhone 17 Pro Max. **Base-gpu render re-confirm GREEN** (stripped build still
renders the live 3D world). Then booted RuneLite with RLHD (`▶ 117HD (Zone)` button → `routecZoneMode`):
`RouteC_registerZoneNatives rc=0`, RLHD loads from the staged jar (`RLHDStarter` + `loadAndStartPlugin`),
`HdPlugin.startUp` runs through full GPU detection (`Indirect draw: false`, `Storage buffers: false` — the
caps-gated GLES-core path, as B0.4 predicted), and **`scene_vert`+`scene_frag` COMPILE to ES 3.00** via the runtime
downport. Fixes landed this round (each one peeled off by reading the device ShaderException):
- **Wiring** (`1226374f6`): `RLHDStarter.java` (+Hooks tick) + `MobileUiBridge.loadAndStartPlugin` +
  `JVMLauncher.routecZoneMode` + `routec_zone_jni.m` compiled in + `stage-ios-gpu-jar.sh FACADE_SRC` superset.
- **Reliable trigger + hub fix** (`765d0fe41`): dedicated one-tap `▶ 117HD (Zone)` button (3-finger gesture was
  unreliable → fell through to L1); zone mode drops `-Drunelite.hub` so the plugin HUB doesn't auto-load its OWN
  cached 117hd (URLClassLoader without our facade → `ClassNotFoundException org.lwjgl.system.Platform` + collision).
- **Shader downport** (`3974015c1`): ported the full Z0 transform into the runtime `RouteCBridge downportShaderSource`
  (full sampler-precision set, the 65 #13 casts, textureFaces TBO→2D); ios-overrides shipped as a jar overlay
  (`rs117/hd/utils/{color_utils,water,fog}.glsl`).
- **Z0 minimal config** (`2bff79a02`): `RLHDStarter` forces dynamicLights=NONE/normalMapping=false/POM=false/
  shadowMode=OFF/wind+char-disp=false (else POM etc. shader branches bring un-ported cast sites).
- **f-suffix bug** (`7772eb6b6`): runtime regex ate `.flowMap`→`.lowMap`; added the `(?![A-Za-z0-9_])` boundary.

**THE UBO BLOCKER — RESOLVED (2026-06-09, option (a) texture-backed material store):** link failed
`Uniform interface block UBOMaterials too large (57120 exceeds max 16384)` — Apple's GLES-on-Metal caps a uniform
block at **16 KB**, and the real material set is **595 materials × 96 B std140 = 57120 B** (the earlier "~476 ×
~120 B" framing divided the same total differently). Audit confirmed UBOMaterials is the ONLY over-cap block under
the Z0 config (next-largest: UBOWorldViews 10240 B; UBOLights would be 32000 B but only if tiled lighting is ever
enabled — future B1+ flag). Fix shipped on `gpu`:
- **Shader:** `gpu-zone/ios-overrides/uniforms/materials.glsl` — drops the std140 block + `#include
  MATERIAL_GETTER`, declares `uniform highp isampler2D materialStore` (unit 6, after TEXTURED_FACES=5) and
  rebuilds `Material getMaterial(int i)` from 6 RGBA32I `texelFetch`es decoding the exact UniformBuffer std140
  bytes (8 ints @0–28, 4 float bits @32–47, flowMapStrength@48, flowMapDuration@56, scrollDuration@64,
  textureScale@80; 96-B stride). Every consumer funnels through `getMaterial`/`MAT_*`, so ONE file converts the
  scene program + both shadow programs (shadow_vert calls getMaterial unconditionally — shadow programs compile
  even at shadowMode=OFF). Verified a fixed point of the runtime downport rules (no regex collision).
- **Java:** NEW `gpu-zone/java-overrides/` overlay (stage-time copy over pristine rlhd-src, same pattern as
  ios-overrides; mechanism added to `stage-ios-zone-jar.sh` step 2b): `MaterialManager` uploads the UBO's
  serialized ByteBuffer verbatim into the RGBA32I texture after every `uboMaterials.update()` (reflection on
  `UniformBuffer.data`; upload via the already-wired `glTexImage2D(...,long)` + `MemoryUtil.memAddress0`, NEAREST
  filters — integer textures are incomplete under linear; PBO defensively unbound; active unit restored to GAME);
  `SceneShaderProgram`/`ShadowShaderProgram` wire the `materialStore` sampler. **Zero facade/JNI/bridge changes.**
  The dead Java-side UBO keeps uploading harmlessly (ShaderProgram skips absent blocks by name).
- **Gates (all GREEN):** `check-std140.sh` (NEW) proves the GLSL decode map matches the real UniformBuffer layout
  engine on host (`STD140_DECODE_MAP_MATCHES`). The Z0 probe now assembles at the REAL `MATERIAL_COUNT 595` (Z0's
  placeholder 1 is why it never caught this): **negative control** (override held out) REPRODUCES the exact link
  failure on device (`evidence/gpu-zone-b05-materialstore-NEGCONTROL.log`), fix run links **3/3 →
  `ALL_ZONE_PROGRAMS_LINK_ES300`**, sealed `evidence/gpu-zone-b05-materialstore-DEVICE-GREEN.log` (md5
  `373b9b4a7d633e91c9fe4a8e89ae1011`, now the `gpu-zone-z0-verdict.sh` default). Staging GREEN (class md5
  `d4dc966c00af9b0d983f25eecce62f73`), glslang 6/6 clean, both bridge arm64 compile checks GREEN, HelloShell
  built + installed on device with the new jar.
- **DEVICE ROUND 2 (2026-06-09→10) — material fix HELD; shadow gap found + fixed.** Ran on the iPhone:
  `RLHDStarter` loaded our staged `rs117.hd.HdPlugin` (count:1), and **the scene program LINKED** — no more
  `UBOMaterials too large`; the material-store fix works on the real RuneLite boot (`SceneShaderProgram` resolved
  its uniforms, the `unused shadowMap` warning = a clean link). `startUp` then advanced to the **next** program,
  `fastShadowProgram`, which failed to COMPILE: `ShaderException 0:78 '-' int/float, 0:79 '*' float/int` at
  `ZoneRenderer.initializeShaders:236`. = `shadow_frag.glsl` 78-79, the `#if SHADOW_TRANSPARENCY` depth-packing
  block. The zone renderer compiles BOTH `fastShadowProgram` (SHADOW_MODE_FAST) and `detailedShadowProgram`
  (DETAILED) unconditionally with `SHADOW_TRANSPARENCY=config` (default **true**), but the Z0 probe only ever
  assembled shadow at `SHADOW_MODE=0`/transparency-off (scene+ui must see SHADOW_MODE=0), so these branches were a
  never-gated false-green the device exposed once scene stopped failing first. **Fixed (`30fbe3cd4`):**
  `ios-overrides/shadow_frag.glsl` — hand-ported ES3 copy with every int-in-float-context explicit across all
  config paths (DETAILED `!= -1.0`/`clamp(...,0.0,...)`; TRANSPARENCY `1.0 - opacity`,
  `float(SHADOW_ALPHA_MAX/SHADOW_DEPTH_MAX)`, `float(<int pack>) / float(SHADOW_COMBINED_MAX)`). One file fixes
  runtime + probe. `ZoneShaderAssemble` now resolves top-level targets from ios-overrides first; new
  `check-shadow-configs.sh` gates all four {FAST,DETAILED}×{transparency on,off} combos via glslang
  (`ALL_SHADOW_CONFIGS_CLEAN`). App rebuilt + reinstalled. (Diagnosis note: `routec-shaders/*` dumps + `client.log`
  are CUMULATIVE across launches — a stale pre-fix `UBOMaterials` scene_frag dump and the hub's pristine external
  117hd copy were red herrings; the 16:05 session had only the shadow error, no UBO error.)
- **NEXT (needs the user, ~1 tap):** StikDebug attach + `▶ 117HD (Zone)` → scene + both shadow programs + ui should
  now link; watch for (1) anything AFTER `initializeShaders` in `startUp` (uncharted — likely the next blocker
  class), (2) once it activates, first-frame render correctness (material colors/water — the texture-upload/sampling
  path is the only material piece the link gate can't prove). Pull `Documents/.runelite/logs/client.log` (read the
  LAST session only — it's cumulative) + `Documents/routec-shaders/*.out.glsl` if it stops again.

## Read first
- `ios/HD-ZONE-B0-SCOPE.md` — the step-by-step B0 plan (this is the spec to execute).
- `ios/dev-tools/gpu-zone/README.md` — what Z0 produced (the assembler, ios-overrides, the device runbook).
- `ios/HD-PORT-BLUEPRINT.md` §5 (ES-3.0 blocker list) + §6 (phasing).
- `ios/dev-tools/gpu-tbo2d/README.md` — the proven `textureFaces` TBO→2D RGB32I repack (blocker #1).
- `ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh` — the base-gpu staging script to clone for B0.1.

## What to do (B0, in order)
1. **Cheap first check (device):** confirm the freshly-stripped base Route C build still renders the scene
   (hardwired `gl_FragDepth` = the config that ran clean in the EAGL A/B; unverified since the device-kit strip).
   This needs StikDebug (it's the full JVM app). One tap.
2. **B0.1 — stage the RLHD jar:** author `ios/dev-tools/gpu-zone/stage-ios-zone-jar.sh` (analogue of
   `stage-ios-gpu-jar.sh`): vendor/compile RLHD's `ZoneRenderer` (+ GL-touching classes) against the facade, ship the
   RLHD shader resources, re-zip. Note: B0 needs the RLHD **Java** too (Z0 only vendored shaders) — pull from RLHD
   `@7dded59` (see `gpu-zone/rlhd-src/PROVENANCE.md`).
3. **B0.2 — facade TBO upload:** replace `glTexBuffer(GL_RGB32I)` with a 2D `glTexImage2D(GL_RGB32I, W, ceil(N/W))`
   of the same bytes (gpu-tbo2d proved this byte-exact), bound as `isampler2D`. Pair it with the shader-side TBO→2D
   rewrite (already implemented in the Z0 assembler's downport — rules 1/2/3 for `textureFaces`).
4. **B0.3 — facade-delta scan (Z0.4):** static-scan RLHD for GL calls not in the facade (`GLALL.java` /
   `bridge-manifest.md`); implement the delta (known adds: `glDrawBuffers`, `glFramebufferTextureLayer`, conditional
   `GL_DRAW_INDIRECT_BUFFER`).
5. **B0.4 — Java conformance fixes:** make draw-indirect bind conditional (#2), `glDrawBuffer`→`glDrawBuffers` (#11),
   facade `OpenGL33=true` so `supportsGpu` passes (#14), minimal UBO + stub material/water plumbing.
6. **B0.5 — wire + boot:** register the zone bridge after `JNI_CreateJavaVM`, boot RuneLite with RLHD as the
   renderer. **Gate B0:** the ZoneRenderer renders the scene (even flat-shaded) on device.

## Gotchas
- **Z0 used a MINIMAL flat-scene config** (shadows/tiled-lighting/dynamic-lights/displacement/POM OFF, in
  `ZoneShaderAssemble.java buildConfig()`). Turning features on in B1+ surfaces their shaders' own #13 cast sites —
  the assembler config + CAST map grow per phase. The override mechanism (`ios-overrides/`) + the glslang fast-filter
  (`glslang-check.sh`, works for shadow/ui, NOT scene) are there to reuse.
- **Device UDID:** `xcrun devicectl list devices` (current iPhone = `AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B`).
- The `gpu-zone` Z0 probe needs **no StikDebug** (pure EAGL). The full RuneLite app (HelloShell / Route C) **does**
  (JIT). `devicectl install` shows a non-fatal "No provider was found" line — ignore it.
- Commit/push under the user's name, no AI attribution (repo convention).

## Suggested opener for the new session
> "Continue the iOS GPU track on branch `gpu`. Z0 is done (green on device). Start Phase B0 per
> `ios/HD-ZONE-B0-HANDOFF.md` + `ios/HD-ZONE-B0-SCOPE.md`."
