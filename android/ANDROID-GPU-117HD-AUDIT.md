# Android GPU / 117HD Audit

## 1. Executive summary

- **The accelerated path is built, device-proven, and largely idle.** Base GPU and 117HD both render end-to-end on the real Pixel-8/Mali-G715 (AG0–AG2 all green), yet the shipping default renderer is the **CPU software rasteriser** — GPU/117HD only engage when a user manually flips a persisted pref and accepts a full process restart (`MainActivity.kt:794`). No first-run capability gate exists. The single biggest *utilization* win is auto-seeding GPU on confirmed-capable hardware.
- **The frame-present path is already near-optimal** and is the audit's best news: direct EGL window-surface present is armed by default, and the expensive `glFinish + glReadPixels + double CPU swizzle` readback is now dead fallback code (`librlawt.cpp:606`, `jvm_bootstrap.cpp:1263`). The wins here are small, cheap cleanups (NEAREST 1:1 blit, cached surface dims, dropped per-frame JNI probes), not a rewrite.
- **The largest *speed* win that respects the unmodified-plugin rule is config-only:** Android's 117HD preset never disables **parallax-occlusion mapping / wind / character displacement** — all three run at the aggressive desktop default (`RLHDStarter.java:111`), and POM is a per-fragment ray-march invoked up to 3× per material. iOS already turns these off. Estimated **+8–15 fps** in fill-bound open world for an `S`-effort, fully reversible config change.
- **Android quality is artificially capped vs iOS by a missing seed-once mechanism.** The Android branch re-forces the mobile preset on *every* launch (`RLHDStarter.java:109–147`) and `return`s before the iOS `GFX_SEEDED_KEY` seed-and-persist block. Users see editable graphics controls that silently reset on restart. MSAA (device supports 4×), 2K shadows, scene-scale, and tiled lighting are all locked. Fixing the seed gap is the single enabler for every quality lever below.
- **Profiling cannot currently find the next Pareto point.** Tuning is driven by one smoothed `client.getFPS()` value/sec (`Hooks.java:291`) and the automated live-A/B MCP knob is a **silent no-op** (sends `--ei` extras the receiver never reads — `harness_server.py:401` vs `MainActivity.kt:294`). Fix the knob and add frame-time percentiles before chasing further fps.

---

## 2. Current state — what actually ships and renders today

- **Default renderer: CPU software rasteriser.** `rendererPref()` returns `getString("mode","cpu") ?: "cpu"` (`MainActivity.kt:794`). The same facade jar set loads every boot, but with no trigger the embedded VM boots without `-Drunelite.routecgpu` or `-Drunelite.routecrlhd`, so the engine uses its vanilla CPU rasteriser. GPU engages only via intent extra / `gpu.mode` marker / `prefRenderer=="gpu"` (`MainActivity.kt:745`); 117HD via intent / `rlhd.mode` / Game Loop / TV / `prefRenderer=="117hd"` (`MainActivity.kt:766`).
- **Renderer architecture (Route C):** the *unmodified* base RuneLite `gpu` plugin runs against a pure-Java LWJGL facade; `librlawt.cpp` provides an Android EGL/GLES3.2 context, an offscreen RGBA8+DEPTH24 FBO, a GL-call RegisterNatives forwarding table, and a GLSL 330/430→GLSL-ES downport shim. 117HD's `rs117/hd` plugin (vendored at `ios/dev-tools/gpu-zone/rlhd-src`, staged via `stage-rlhd-zone-jar.sh`) is the higher-quality path; default renderer inside 117HD is **ZoneRenderer** (`HdPlugin.java:559`, CPU alpha-sort, no compute), not the opt-in compute LegacyRenderer.
- **Present path: direct EGL, zero CPU copy.** `jvm_bootstrap.cpp:1263` dlsyms `RouteC_setPresentCallbacks`; when found it installs the 4 window-borrow callbacks and sets `g_directPresent=true`. `swapBuffers` (`librlawt.cpp:606`) then GPU-blits the offscreen FBO into the window default framebuffer and `eglSwapBuffers` — no CPU touch of pixels. The legacy `glFinish + glReadPixels + 2× per-pixel swizzle` path (`librlawt.cpp:633`, second pass at `jvm_bootstrap.cpp:1105`) only runs against a *stale* librlawt missing the symbol. **The worst present cost is already avoided on any current build.**
- **Device baseline:** Pixel-8 Mali-G715 (AG0 recon) reports GLES 3.2 + compute(512) + `EXT_clip_control` + 32F depth + RGBA16UI + `maxSamples=4`. Base gpu and 117HD render in-world; FIX-13 perf preset (sceneScale 85 + aniso 8) measured ≈ +35% fps. GPU/117HD presets measured ≈ 30–33 fps; the CPU baseline is what the median user actually gets today.
- **117HD Android preset (`RLHDStarter.java:112–147`)** force-applies 8 keys every launch: `uiScalingMode=LINEAR`, `tiledLighting=false`, `antiAliasingMode=DISABLED`, `shadowResolution=RES_1024`, `sceneResolutionScale=85`, `anisotropicFilteringLevel=8`, `asyncModelProcessing=false`, `drawDistance=30`. Everything else inherits the desktop default — so the HD look (RES_256 textures, DETAILED textured shadows, SMOOTH PCF, normal mapping, ground blending, water/caustics, ground fog) is **correctly retained** (`HdPluginConfig.java:727/400/569/738`).

---

## 3. Utilization gaps — capability left on the table

| Gap | What's idle | Why it matters | Fix surface |
|---|---|---|---|
| **GPU/117HD never auto-engage** *(unverified)* | The accelerated, device-green paths sit unused for any non-opt-in user; median user runs CPU. | Largest reach win — moves the typical user off the software rasteriser. | First-run capability gate (reuse AG0 probe) seeds `rendererPref="gpu"` once; CPU stays fallback. Launch-time pref write (`MainActivity.kt:797`), respects jar-fixed-at-JVM-start. (`M`) |
| **Seed-once mechanism is iOS-only** | `GFX_SEEDED_KEY` seed-and-persist exists but lives inside the `gfxIos` branch (`RLHDStarter.java:172`); Android `return`s at :147 before reaching it. | Every quality lever below is locked — panel edits silently revert on restart; the panel is dishonest. | Split Android forces into safety-critical (re-apply every launch: LINEAR, async off, tiled off until ES shaders ported) vs seeded-once tunables. Reuse the iOS code path. (`M`) — **enabler for all quality findings** |
| **MSAA never offered** | Device `maxSamples=4`; multisample RBO + resolve FBO already wired (`HdPlugin.java:1333`); facade forwards `glRenderbufferStorageMultisample` (`librlawt.cpp:367`) and resolve blit (`:268`). Android forces DISABLED; iOS seeds MSAA_4. | Zero edge AA on Android — the most visible quality gap vs iOS. | See §5. (`S` to enable, `M` to seed properly) |
| **2K shadows / tiled lighting locked** | Shadows pinned at RES_1024 (desktop default 8192); tiledLighting forced off caps Android at **50** simultaneous lights vs iOS's 1000. | Soft/blocky shadows; dropped lights in dense scenes (Wintertodt, Mage Arena, GE). | See §5. |
| **`SUPPORTS_INDIRECT_DRAW` forced off** *(unverified)* | `OpenGL40/43=FALSE` hardcoded (`librlawt.cpp:673`) **and** plugin gate is `config.indirectDraw().get(NVIDIA && !APPLE)` → false on Mali; `rc_glMultiDrawArraysIndirect` is a **no-op** (`librlawt.cpp:498`). | Zone draws fall to per-range `MultiDrawArrays`/`DrawArrays` — one JNI hop per draw range × visible zones/frame. | See §4. |
| **`GL_ARB_buffer_storage` forced off** *(unverified)* | Persistent-mapped buffers disabled; uploads re-run `glBufferData`+`SubData` every frame. `rc_glBufferStorage` is a no-op (`librlawt.cpp:499`). | Per-frame full-buffer re-upload + JNI marshalling on model-heavy scenes. | See §4. |
| **Compute path dark *(by design)*** *(unverified)* | Device has compute(512); ZoneRenderer never dispatches. Dispatch/barrier forwarders are real (`librlawt.cpp:494`). | **Not a gap to close** — Zone CPU alpha-sort is the supported path; porting it to compute would rewrite the unmodifiable plugin (XL). Document and move on. | — |

---

## 4. Efficiency & speed findings — ranked by impact

> **Lead note (present path):** the frame-present/readback path *survived* audit as **already optimal**. The expensive `glReadPixels` readback is dead fallback code on current builds (confirmed). The present-path items below are therefore small, cheap, low-risk cleanups — not the headline. The headline speed win (#1) is a config change, not a present-path change.

### 1. POM / wind / character displacement left ON — the single biggest untapped fill+vertex win  `S` · high confidence
- **What:** Android `!gfxIos` branch (`RLHDStarter.java:111–148`) sets 8 keys but **never touches** `parallaxOcclusionMapping`, `windDisplacement`, `characterDisplacement`, or `atmosphericLighting`. All default `true` (`HdPluginConfig.java:581/763/775`). The iOS branch disables all three (`RLHDStarter.java:160–161/182`).
- **Why slow:** POM is a per-fragment ray-march invoked up to 3× per blended material; level-3 self-shadow is a 9× nested ray-march (`scene_frag.glsl:194`) — pathological on a mobile tiler. The open-world scene is fill-bound; POM fights exactly the bottleneck FIX-13 attacks. Wind/char displacement add per-vertex ALU.
- **Fix (config-only, reversible):** add `MobileUiBridge.setConfig("hd","parallaxOcclusionMappingToggle","false")`, `windDisplacement=false`, `characterDisplacement=false` to the Android branch — mirror iOS. No plugin/facade/AWTContext change.
- **Expected delta:** POM-off alone ≈ +10–25% in textured open world; estimate **+8–15 fps** additive with sceneScale=85; roughly flat at the model-bound GE.

### 2. `glMapBufferRange` ignores the reuse buffer — fresh direct ByteBuffer + uncached reflection every map, every frame  `S` · high  *(unverified)*
- **What:** `rc_glMapBufferRange` (`librlawt.cpp:355`) discards its 5th arg (the prior ByteBuffer) and always returns `rc_native_order(NewDirectByteBuffer(...))`. Base gpu `VBO.map()` (`VBO.java:73`) and RLHD `GLMappedBuffer.map()` (`GLMappedBuffer.java:86`) both pass the prior buffer expecting reuse → a new buffer (+ asInt/asFloat views in RLHD, rebuilt because `byteView != buf` is always true) per VAO per frame. `rc_native_order` (`librlawt.cpp:231`) does 2× FindClass + 2× GetMethodID + 2 reflective calls, fully uncached, per map.
- **Why slow:** RLHD maps `FRAMES_IN_FLIGHT × VAO_COUNT` dynamic VAOs + UBO + alpha-EBO/frame; base gpu maps each pooled VAO/frame. Steady per-frame heap allocation + reflection on the per-launch HotSpot draw thread → GC pauses surface as frame hitches.
- **Fix (facade-only):** honor the reuse contract like `rc_mapReuse` (`librlawt.cpp:333`) — when `GetDirectBufferAddress(old)==p && capacity==a2`, return `old`. Cache the `ByteOrder`/`ByteBuffer` jclass (NewGlobalRef) + 2 jmethodIDs once at `RouteC_registerGLNatives`.
- **Expected delta:** removes several direct-buffer allocations + 4 reflective lookups/frame; **smoother frame pacing / fewer stalls** rather than raw mean fps.

### 3. Indirect draw forced off → per-zone JNI `glDrawArrays` loops  `L` · medium  *(unverified)*
- **What:** `OpenGL40/43=FALSE` (`librlawt.cpp:673`) and plugin gate `SUPPORTS_INDIRECT_DRAW = config.indirectDraw().get(NVIDIA && !APPLE)` (`HdPlugin.java:577`) both resolve false on Mali; the multi-draw-indirect forwarder is a no-op (`librlawt.cpp:498`). Zone draws fall to `MultiDrawArrays`/per-range `DrawArrays` (`DynamicModelVAO.java:286/295`).
- **Why slow:** on the JNI-per-call facade, one Java→C border crossing per draw range × all visible zones/frame — the worst-case pattern, exactly the regime fill-rate levers don't help (GE crowd, dense areas).
- **Fix (facade + starter):** (1) implement `rc_glMultiDrawArraysIndirect` (loop `glDrawArraysIndirect`, already forwarded at `:496`, or the GLES3.2-core path if `EXT_multi_draw_indirect` probes present at context creation like the texture_buffer probe at `:535`); set `OpenGL43=true` **only** if the forwarder is real; (2) force the hd `indirectDraw` config key true in the Android `RLHDStarter` branch. Plugin/AWTContext untouched.
- **Expected delta:** single-digit to ~15% on draw-call-bound scenes, larger as zone count grows. *Verify the forwarder before flipping caps.*

### 4. `getFramebuffer` per-frame JNI resize probe + full FBO teardown on resize  `M` · high (confirmed)
- **What:** `rc_AWT_getFramebuffer` (`librlawt.cpp:553`) runs early each draw: `rc_make_current` + two `CallStaticIntMethod` upcalls (canvasWidth/Height) + `ExceptionCheck`, purely to detect a resize that only happens on rotation/keyboard. On change, `rcBuildFbo` (`:211`) `glDelete`s both RBOs + the FBO and `glGen`s them fresh instead of `glRenderbufferStorage`-resizing in place.
- **Fix (facade-only):** read cached dims from a native dirty flag bumped on surface change (cleaner than `g_lastFrameW/H`, which is published *after* swapBuffers — see verifier note on ordering); resize RBOs in place, `glDelete` only on context teardown.
- **Expected delta:** removes 2 JNI upcalls + ExceptionCheck/frame on the hottest path (taxed on the embedded HotSpot); eliminates GL-object churn on rotation. Small steady-state, but pure overhead removal, correctness unchanged.

### 5. Overlay-snapshot republish runs at 60Hz in direct-present mode  `M` · low (needs-nuance)
- **What:** the CPU renderLoop wakes ~60×/s and runs ~6–7 JNI `CallStaticObjectMethod` + `GetStringUTFChars` + mutex copies (menu/chrome/chat/orb/dialogue/map) *before* the `if (g_directPresent) { usleep(16000); continue; }` early-out (`jvm_bootstrap.cpp:1069`). Menu-peek is already seq-gated (`:950`); chrome/chat/orb/dialogue/map are **not** (`:976/995/1013/1031/1050`).
- **Nuance (honor verifier):** these snapshots are **not dead work** — they feed the native HUD overlays, which are live even in direct mode. The waste is the *cadence*, not the existence.
- **Fix (native + JavaBridge, no plugin):** seq-gate the remaining snapshots (replicate the menu-peek pattern at `:955`) so an unchanged HUD is nearly free; optionally drive republish from the engine client-tick (~50ms) instead of 60Hz. Keep tap/keyboard atoms (`interactTapSeq`, `textInputOpen`) at full rate.
- **Caution:** throttling risks stale overlay bounds (e.g. lagging chat-tab); seq-gating (not blind throttling) avoids this.
- **Expected delta:** ~1–3 fps in CPU-contended scenes (e.g. GE crowd where fill levers don't help); battery/heat win. **Severity corrected to low.**

### 6. Base-gpu uniform forwarders copy the pinned Java array every call  `S` · high  *(unverified)*
- **What:** base gpu `VAO.draw` re-issues `glUniformMatrix4fv` + `glUniform4i` + `glBindVertexArray` per range with no state dedup (`VAO.java:155`); `rc_glUniformMatrix4fv` (`librlawt.cpp:411`) pins the whole Java `float[]` via `GetFloatArrayElements`/`ReleaseFloatArrayElements(JNI_ABORT)` (an ART copy) every call. RLHD already dedups via `GLState.apply()` (`RenderState.java:30`).
- **Fix (facade-only):** switch the `*fv` forwarders to `GetPrimitiveArrayCritical`/`ReleasePrimitiveArrayCritical` (no-copy on ART), matching the `_B` direct-buffer fast paths. The redundant-uniform resend is structural to the unmodified plugin — note it, leave it; route efficiency-seekers to 117HD (which dedups).
- **Expected delta:** removes a `float[16]`/`float[]` copy per uniform call across the base-gpu frame (hundreds of ranges in a busy scene); modest draw-thread CPU saving, most in geometry-heavy/GE scenes.

### 7. Persistent-mapped buffers off → per-frame buffer re-spec  `L` · medium  *(unverified)*
- **What:** `GL_ARB_buffer_storage=FALSE` (`librlawt.cpp:675`); the persistent path (`glBufferStorage(PERSISTENT)` + `glMapBufferRange(PERSISTENT|UNSYNCHRONIZED)`, `GLBuffer.java:467`) is gated off; the orphan `glBufferData`+`glBufferSubData` fallback (`:492`) runs. Forwarders are no-ops (`librlawt.cpp:499`).
- **Fix (facade-only):** implement `rc_glBufferStorage` via `glBufferStorageEXT` (lazy-resolve like `glClipControlEXT` at `:421`), probe `EXT_buffer_storage` at context creation, set the cap true only when present; otherwise keep the fallback.
- **Expected delta:** eliminates per-frame full-buffer re-upload + JNI traffic on bandwidth/upload-bound scenes. Moderate fps. **Needs device validation — `EXT_buffer_storage` on Mali-G715 unconfirmed in AG0.**

### 8. `eglSwapInterval` never called — no vsync/fps-cap control  `S` · high  *(unverified)*
- **What:** `rc_AWT_setSwapInterval` is `return a0;` (`librlawt.cpp:564`) — the plugin's request is discarded; `eglSwapInterval` is never invoked. Present runs at EGL default (interval 1 = display refresh); the renderLoop's `usleep(16000)` is bypassed in direct mode, so pacing is uncontrolled. On a 120Hz panel the engine free-runs to 120.
- **Fix (native-only):** call `eglSwapInterval(sDpy, N)` in `rcEnsureWindowSurface`; wire `rc_AWT_setSwapInterval` to store+apply. Default N=1; expose `-Drunelite.routec.swapInterval` (0=uncapped for benchmarking, 2=30fps thermal floor).
- **Expected delta:** capping a thermally-throttling session to 30 can *raise* sustained fps (audit notes throttle in 1–2 min); uncapped is required for honest GPU-time benchmarking. Steady-state neutral, large win on sustained/thermal + measurement fidelity.

### Present-path micro-cleanups (all `S`, low severity, confirmed) — bundle as one PR
- **NEAREST 1:1 present blit + cached dims** (`librlawt.cpp:622/615`): present blit uses `GL_LINEAR` on an equal-size copy and re-queries window dims via 2 `eglQuerySurface`/frame. Cache `sw/sh` in `rcEnsureWindowSurface`; use `GL_NEAREST` when `w==sw && h==sh`, fall back to `GL_LINEAR` only on a genuine size mismatch (resize race). Pixel-exact, removes 2 driver calls + filtered-resolve cost. *(severity corrected to low)*
- **Per-frame `eglMakeCurrent` to window surface** (`librlawt.cpp:613`, needs-nuance): track `sWinSurfCurrent` and skip the `eglMakeCurrent` when already current; clear on surface recreate. **Do not** simply drop it — some Mali drivers require it before `eglSwapBuffers`. Device-verify. `M`.
- **`gpuframedump` armed in release** (`MainActivity.kt:2428/2440`): 8 full-canvas PNG encodes on the client thread during in-world warmup, *not* DEBUG-gated (contrast `setStdioRedirect(BuildConfig.DEBUG)` at `:2451`). Gate behind `BuildConfig.DEBUG`. Removes a startup stutter + IO with zero diagnostic value in release. `S`.

### Confirmed "do NOT build" (avoid wasted effort)
- **No AHardwareBuffer/EGLImage zero-copy path** (confirmed): current direct blit-to-window-default-fb + `eglSwapBuffers` is already at the zero-copy floor; a HardwareBuffer path cannot remove the one remaining GPU→GPU blit (the plugin requires an FBO target per the unmodified AWTContext) and adds sync complexity. Leave as-is.
- **RLHD MultiDrawArrays fallback is JNI-optimal** (`DynamicModelVAO.java:295`, `librlawt.cpp:359`): N sub-draws collapse to one JNI hop, N-expanded C-side; ranges pre-coalesced; repack is stack-backed (no alloc). Do **not** push expansion back to Java. Keep `OpenGL43=FALSE` so the no-op indirect path is never selected (until finding #3 lands).
- **Compute / base-gpu CPU sort:** both blocked by the unmodified-plugin rule. Route efficiency-seekers to 117HD ZoneRenderer.

---

## 5. Graphics quality findings — ranked

> All depend on the **seed-once gap fix** (§3) to actually persist. Without it, every value below silently resets each launch.

| # | Finding | Current | Recommended | Tradeoff |
|---|---|---|---|---|
| 1 | **MSAA disabled** (`RLHDStarter.java:119`; iOS seeds MSAA_4 at `:180`) | DISABLED | Request **MSAA_4** (engine clamps to `GL_MAX_SAMPLES=4`, `HdPlugin.java:1297`); seed-once tunable | The single most visible gap vs iOS. 4× MSAA on a tiler ≈ **−8% to −15%** fill-bound, near-free geometry-bound. Resolve blit (`HdPlugin.java:1356`) is forwarded and runs on the direct-EGL path. `S` |
| 2 | **sceneResolutionScale=85** softens every frame (`RLHDStarter.java:127`; iOS=100) | 85 (≈0.72× pixels, bilinear upblur) | Keep 85 default but make it **seeded-tunable**; A/B 90 as ship default; re-sweep 75–80 once POM is off (§4 #1) | 90 recovers most sharpness for ~+10–12% pixels; 100 fully sharp at ~+38%. **Do not** change `sceneScalingMode` off LINEAR (pulls in unported `scale/*.glsl`). `S` |
| 3 | **Shadow map 1K** (`RLHDStarter.java:123`; desktop default 8192) | RES_1024 | Expose **RES_2048**; consider shipping 2K on this tier | 2K ≈ doubles shadow-edge sharpness for measured **~−14%** fps; shadows are geometry/bandwidth-bound not fill-bound; 2K depth = one ~16MB texture, well under cap. Filtering already SMOOTH 3×3 PCF, compounds cleanly. `S` |
| 4 | **Tiled lighting off** caps lights at **50** (`RLHDStarter.java:114`; iOS=true→1000) | tiledLighting=false (DynamicLights.SOME=50) | (a) bump dynamicLights SOME→**MANY (100)** now; (b) proper: port `tiled_lighting_*.glsl` into the Android overlay, validate via `glsl_es_validate`, flip true | MANY: fewer popped lights in dense scenes for ~−5–10% where many lights visible. Full port restores 1000-light ceiling but is `L` + ES-shader port + device-validation risk (shaders not in curated overlay, rely on regex downport). `L` |
| 5 | **Base-gpu reverse-Z (clipControl) gated off** (`GpuPlugin.java:356` only use of OpenGL45; caps false at `librlawt.cpp:664`) | OpenGL45=false → standard [-1,1] depth | Set **OpenGL45=true**; forwarder already handles EXT-absent gracefully | Verified OpenGL45 is referenced exactly once (no DSA paths unlocked). Reduces z-fighting/depth banding on distant geometry at one extra startup call. Pure quality win. **See §6 — this is part of a deeper correctness issue.** `S` |
| 6 | **Aniso 8** (`RLHDStarter.java:131`; desktop 16) | 8 | Keep 8 default; expose 16 as tunable | Pure sampler param. 8→16 is a small crispness gain on oblique ground for real tiler bandwidth cost. Lowest-priority lever — expose, don't change default. `S` |
| — | **HD look correctly retained** | RES_256 textures, DETAILED textured shadows, normal mapping, ground blend, water/caustics, fog all ON | No change | The override set is narrow and well-chosen. **Forward note:** POM/wind/char-displacement are left desktop-true (§4 #1) — a quality positive, but disable them for *perf*; verify POM renders correctly under the regex downport before assuming. |

---

## 6. Correctness / risk notes

1. **Reverse-Z `glClipControl(ZERO_TO_ONE)` is never enabled on Android — HIGH risk** *(unverified)*. Both base gpu **and** 117HD render with a reverse-Z projection (clip depth in [0,2], `GL_GREATER`/`GEQUAL`, `clearDepth 0` — `Mat4.java:97`, `GpuPlugin.java:973`, `ZoneRenderer.java:785`), but `glClipControl` is never invoked: base gpu gates it behind `OpenGL45=false`, and a grep of all of `rlhd-src` finds **zero** `glClipControl` calls. Under the GLES default depth convention the projection's near plane lands outside the [-1,1] clip volume → near geometry clipped + usable depth collapses to [0.5,1], maximizing z-fighting. **iOS hit and fixed exactly this** (`HD-PHASE-A1-HANDOFF.md:37`: far-plane depth 0.508→0.0167, ~15× precision). **Fix:** call `glClipControlEXT(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` in `rc_AWT_createGLContext` after `rc_set_es_target()` (one EGL call; EXT is device-present per AG0), exactly like iOS's native bridge — plugin/AWTContext stay byte-for-byte. Verify with a `glClearDepth(1)`/readback A/B. `S`. *Validate the depth-bug claim on device before treating as shipped.*

2. **`rc_glClipControl` forwarder is dead code with a misleading comment — MEDIUM (confirmed)**. The forwarder (`librlawt.cpp:420`) resolves `glClipControlEXT` and works, but its only caller is `OpenGL45`-gated (false), so it is never invoked. The source comment (`librlawt.cpp:39`) claims a "GEQUAL projection fallback handles reverse-Z" — **this is wrong**: `GL_GEQUAL` only flips the compare direction, it does not remap the [-1,1] vs [0,1] clip convention. Fold into fix #1 (call it directly from `createGLContext`) and correct the comment so no future engineer trusts a non-existent fallback. `S`.

3. **Direct-present Y orientation is unverified — LOW (confirmed)**. `librlawt.cpp:620` *assumes by comment* that GL bottom-left maps upright; the legacy readback path explicitly Y-flips (`:642`), proving the concern is real. Standard EGL almost certainly maps correctly, but a wrong assumption presents the scene upside-down **silently**. Run the screenshot harness once on the Pixel target; if inverted, swap the dst Y extents in the single `glBlitFramebuffer` (free). `S`.

4. **Stale-`librlawt.so` regression is silent — LOW (confirmed)**. If a build ships a librlawt without `RouteC_setPresentCallbacks`, present silently falls to the 2×-CPU-per-frame readback path with no log signal. Add a one-time `WARN` when the readback path is selected (`librlawt.cpp:570/633`), and confirm the "direct EGL present armed" boot line appears in a real in-world run. No hot-path change. `S`.

5. **BGRA swizzle is persistent per-texture state, never reset — LOW (confirmed)**. `rc_glTexImage2D`/`rc_glTexSubImage3D_buf` (`librlawt.cpp:391/511`) set `GL_TEXTURE_SWIZZLE_R/B` permanently on the bound texture. Correct *only* if every later upload to that id is also BGRA — true today (UI texture + RLHD array). If texture reuse patterns change upstream, a lingering swizzle silently inverts red/blue. Assert the invariant + add a debug check, or reset to IDENTITY on any non-BGRA upload. `S`.

6. **ES context selection + `#extension` emission are CORRECT — no change** (confirmed). Context picks ES 3.2 first (`librlawt.cpp:199`), downport targets `320 es` (`:109`), and io_blocks/texture_buffer/geometry `#extension` lines are correctly suppressed on 320 es (core) and only added on a 3.1 fallback (`:162`). Reported as an explicit negative so this path isn't "fixed" needlessly.

---

## 7. Prioritized roadmap (top ~12)

Ranked by impact × confidence ÷ effort, respecting the unmodified-plugin design. "(unv)" = unverified (verifier rate-limited) — weight lower until confirmed.

| # | Tier | Action | Impact | Effort | File(s) |
|---|---|---|---|---|---|
| 1 | **Now** | Disable POM / wind / character displacement in Android branch (mirror iOS) | **+8–15 fps** open world | S | `RLHDStarter.java:111` |
| 2 | **Now** | Fix the seed-once gap: split Android forces into safety-critical (every-launch) vs seeded tunables; reuse iOS `GFX_SEEDED_KEY` path | Unlocks all quality levers; honest panel | M | `RLHDStarter.java:109–198` |
| 3 | **Now** | Enable reverse-Z `glClipControl(ZERO_TO_ONE)` in `createGLContext` + fix the misleading "GEQUAL fallback" comment (unv) | Removes near-clip + dominant z-fighting on both renderers | S | `librlawt.cpp:420/664/createGLContext`, `librlawt.cpp:39` |
| 4 | **Now** | Fix `glMapBufferRange` reuse + cache `rc_native_order` jclass/methodIDs (unv) | Smoother pacing, less GC | S | `librlawt.cpp:355/231` |
| 5 | **Now** | Fix the MCP A/B knob (`--es` or add `--ei` readers) + add frame-time percentiles & thermal to the fps probe | Unblocks all further tuning | M | `harness_server.py:401`, `MainActivity.kt:294`, `Hooks.java:291` |
| 6 | **Now** | Enable MSAA_4 (seeded) on Android | Removes the most visible quality gap vs iOS | S | `RLHDStarter.java:119` |
| 7 | **Next** | Present-path cleanup bundle: NEAREST 1:1 blit + cached dims; gate `gpuframedump` to DEBUG; one-time WARN on readback fallback | Small fps + startup smoothness + regression safety | S | `librlawt.cpp:615/622/570`, `MainActivity.kt:2428/2440` |
| 8 | **Next** | `eglSwapInterval` control + boot prop (uncapped for bench, 30-cap for thermal) (unv) | Sustained-fps/thermal + honest benchmarking | S | `librlawt.cpp:564`, `rcEnsureWindowSurface` |
| 9 | **Next** | Expose 2K shadows + dynamicLights MANY(100) as seeded tunables; A/B sceneScale 80 + ship 90 default | Quality ceiling without default regression | S | `RLHDStarter.java:114/123/127` |
| 10 | **Next** | `getFramebuffer` dirty-flag (no per-frame JNI probe) + in-place RBO resize | Per-frame overhead off hottest path | M | `librlawt.cpp:553/211` |
| 11 | **Next** | Seq-gate all overlay-snapshot republishes (replicate menu-peek pattern) | ~1–3 fps CPU-contended; battery | M | `jvm_bootstrap.cpp:976/995/1013/1031/1050` |
| 12 | **Later** | First-run capability gate → auto-seed `rendererPref="gpu"` on confirmed-capable GPU (unv) | Moves median user off CPU rasteriser | M | `MainActivity.kt:794/797` |
| 13 | **Later** | Implement `rc_glMultiDrawArraysIndirect` + probe, flip `OpenGL43`/`indirectDraw` (unv) | Single-digit–15% draw-bound | L | `librlawt.cpp:498/535`, `RLHDStarter` |
| 14 | **Later** | Implement `rc_glBufferStorage` + `EXT_buffer_storage` probe (unv) | Moderate, upload-bound — *needs device validation* | L | `librlawt.cpp:499/421` |
| 15 | **Later** | Port `tiled_lighting_*.glsl` to Android overlay, validate, flip tiledLighting=true | Restores 1000-light ceiling (iOS parity) | L | overlay + `RLHDStarter.java:114` |

> Items #4–#6, #8, #10 are facade/native/starter-local and never touch the plugin or AWTContext. `mediump` precision special-casing for trivial UI shaders (`librlawt.cpp:149`) is deferred (low confidence, M effort, banding risk) — only attempt with an `image_diff` A/B.
>
> **§9 (second-opinion adjustments) overrides this table where they conflict:** #3 is now gated (not blind-enable), #9 (2K shadows) is deferred to *Later*, #12 (auto-seed GPU) moves up to *Next*, plus a new recompile-stutter item to verify.

---

## 8. Open questions / profiling gaps — measure on-device

1. **Working A/B + per-pass timing first (blocking).** The MCP `android_set_hd_config` knob is a no-op (`--ei` extras ignored at `MainActivity.kt:294`); the only signal is one smoothed `client.getFPS()`/sec (`Hooks.java:291`) over ~20 samples with an O(n²) awk sort, no percentiles, no GPU/per-pass timing, no thermal. **Cannot distinguish a 2–3 fps tuning delta from noise.** Fix the knob, add frame-time min/avg/95p + thermal, add `GL_TIME_ELAPSED` timer queries around shadow vs scene passes.
2. **Re-sweep sceneScale *after* POM-off.** POM (still on) is fighting the scale lever; with POM off, re-sweep 75/80/85 via the fixed knob — 80 likely holds visual quality with extra headroom. Also align the debug "new" preset to the shipped `drawDistance=30` (it uses 25, `MainActivity.kt:295`) so A/Bs stop over-crediting the draw-distance cut.
3. **Shadow cost is already bounded by drawDistance — don't chase 90 tiles.** `ShadowCasterVolume.build()` clamps to the scene far plane (`ShadowCasterVolume.java:53`), which is set from drawDistance; at mobile drawDistance=30 the caster volume is ~30 tiles, not 90 (`ZoneRenderer.java:412`). Measure 2K-shadow cost at this bounded footprint.
4. **Confirm `EXT_buffer_storage` and `EXT_multi_draw_indirect` presence on Mali-G715** at context creation — both gate `L`-effort efficiency wins (#13, #14) and were not in the AG0 recon. Probe before implementing.
5. **Device-verify direct-present is live + upright.** Confirm the "direct EGL present armed" boot line in a real in-world run and screenshot for Y orientation (§6 #3/#4).
6. **Quantify MSAA_4, 2K shadows, and POM-off independently** under the new frame-time probe to build the real Pareto frontier rather than estimating; measure sustained (post-thermal) fps with `eglSwapInterval` caps, since the audit notes throttling within 1–2 minutes.

---

## 9. Second-opinion adjustments (independent review)

An independent reviewer rated this audit trustworthy and concurred with the headline calls (POM-off as the top win, the seed-gap as the enabler, killing the readback path). The deltas below were accepted after reconciling against source; refuted points are recorded so they aren't re-raised.

**Accepted — changes to the plan:**
- **Roadmap #3 (reverse-Z `glClipControl`) — upgrade from "enable + A/B" to "enable behind a RUNTIME gate."** A driver that advertises `EXT_clip_control` but bugs out yields a broken depth buffer that is invisible in static screenshots but ruins gameplay. There is no validation today (`librlawt.cpp:420-422` resolves the EXT but nothing checks it; `OpenGL45=FALSE` at `:664`). Required shape: enable → `glClearDepth(1)` + center-pixel readback in `createGLContext` → **auto-revert to standard depth if the result is wrong.** Still `S`, but no longer "just flip it." This is the single biggest risk in following the audit blind.
- **Quality #3 / Roadmap #9 (2K shadows) — DEFER (Later/hold), not Next.** 2K shadow maps eat shared-L2 bandwidth on a Mali tiler; raise shadow-res only *after* the fps floor is solid (POM-off + present cleanups). Keep RES_1024 until then.
- **Roadmap #12 (auto-seed GPU) — move Later → Next.** A capable device defaulting to the CPU rasteriser is a UX failure, not just a code gap. Caveat retained: it must sit behind a *solid* capability gate so GPU is never auto-enabled on a device where it crashes (CPU stays the safe fallback).
- **Present-path cleanup (`eglMakeCurrent`) — sharpen the caution:** skip the call only when the **native surface pointer** is unchanged, never on a mere bool flag (some Mali/Adreno drivers draw garbage / crash if it's skipped across a surface change).

**Accepted — NEW finding to verify:**
- **Shader-recompile-on-toggle stutter (unverified — not in the original audit).** 117HD recompiles GL programs on certain setting changes; if that runs on the render thread it stutters — and the seed/forced-config writes (`RLHDStarter.java`) may trigger it. Not confirmed in the vendored copy on a quick grep. **Action:** confirm whether `HdPlugin` recompiles on the keys we force/seed, and ensure any recompile happens once at startup, not per setting-flip.

**Reviewed and refuted (no change — recorded to avoid re-raising):**
- *"BGRA texture uploads hit a driver software-swizzle"* — already handled: `librlawt.cpp:389-391` and `:508-511` rewrite `GL_BGRA→GL_RGBA` and set `TEXTURE_SWIZZLE_R/B` (a free sampler-state swizzle, not a per-upload CPU swizzle); Correctness #5 already documents it.
- *"`glMapBufferRange` (#4) ranked too high vs POM"* — POM is already #1, above #4; and #4 is already characterized as a frame-pacing/GC win, not raw fps.
- *"Missing GPU fence → present stall"* — within a single GL context execution is in-order, so the present blit already observes completed FBO writes; only cross-frame FBO-reuse pacing is relevant, which overlaps Roadmap #8 (`eglSwapInterval`).
