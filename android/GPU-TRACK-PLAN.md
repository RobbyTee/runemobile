> Grounded, adversarially-reviewed engineering plan for the Android GPU / 117HD track (Track G).
> Produced 2026-06-08 by a multi-reader research + critique pass; every load-bearing claim was
> verified against on-disk source (file:line refs throughout). Where this disagrees with
> `BLUEPRINT.md` §D or `PORT-PHASING.md` §3G (e.g. the stale blit-seam line citations, the
> A7-vs-AG2 "contention", the "jar prerequisite"), THIS doc is correct. Honest headline: base GPU
> rendering is *plausible, not probable* (~35–40% to a real frame); the whole track gates on AG1.
>
> **STATUS — AG1a GREEN on the emulator (2026-06-08, commit `5bfe36c45`).** The facade-bind EGL smoke
> passes: `ANDROID_AG1A OpenGL33=true createProgram=1 fbo=1 glErr*=0` on a live EGL 1.4 / ES 3.1 context,
> headless. The librlawt EGL replacement (`app/src/main/cpp/librlawt.cpp`), the ported Java facade, and the
> host smoke launch all work — see `dev-tools/gpu/`. This closes the *easy ~75%* (AG1a). **It is NOT
> AG1-GREEN** — the emulator is ANGLE→SwiftShader; the coin-flip AG1b (real shaders + `glValidateProgram`
> on a real Adreno/Mali driver, under the real HotSpot render thread + CACIO canvas) is device-only and
> still open.
>
> **STATUS — AG0 GREEN on a REAL device (2026-06-08, Firebase Test Lab, Pixel 8 / Mali-G715).** Best-case:
> `ANDROID_AG0 esTier=32 compute=true(512) clipControl=true bgra8888=true maxSamples=4 rgba16uiArrayRenderable=true
> depth32f=true → VERDICT=PASS`. GLES **3.2 + compute + EXT_clip_control + 32F depth + RGBA16UI array** are all
> present — so base `gpu` AND the *clean* 117HD compute path are both supported, and the iOS ES-3.0/GEQUAL
> workarounds are unnecessary here. Evidence: `dev-tools/firebase/evidence/ag0-pixel8-20260608.log`. Also GREEN
> on real HW: **A0-device/Q11** (the embedded JVM `dlopen`s libjvm from `filesDir`; SELinux grants execute).
>
> **STATUS — AG1b GREEN on the REAL Mali driver (2026-06-08, Pixel 8 / Mali-G715, GLES 3.2). THE PROJECT-WIDE
> GPU COIN-FLIP CLEARED.** `ANDROID_AG1B esVer=320 es sceneVert=true sceneFrag=true uiVert=true uiFrag=true
> sceneLink+Validate=true uiLink+Validate=true → VERDICT=PASS`: all four real base-`gpu` shaders compile, both
> programs link, and **`glValidateProgram` passes** on real mobile silicon — the exact step `Shader.java` treats
> as fatal, the one the iOS proof never ran on a real GLES driver. The downport that got there is the proven iOS
> "5 B1 edits" ported to Kotlin (drop pre-`#version` + re-version + precision; strip `noperspective`; strip float
> `f` suffix; 9 explicit int→float casts), each rediscovered live by iterating real compiles. Harness:
> `:recon` app (it runs AG0 then AG1b in one launch); gate `fb-gate.sh recon`; evidence
> `dev-tools/firebase/evidence/ag1b-pixel8-20260608.log`. NOTE: this validates the SHADER path (the load-bearing
> unknown). The AG1a facade/EGL `OpenGL33` path is separately GREEN on the emulator; the CACIO-canvas +
> HotSpot-render-thread sub-risks (run the unmodified `GpuPlugin.startUp` end-to-end) remain for AG2.
>
> **STATUS — AG2 wiring COMPLETE + emulator END-TO-END GREEN (2026-06-08, commits `c08490a7e`/`0a681a488`/
> `8ffc44dfa`/`0eba3bc26`).** The unmodified base `gpu` plugin renders through our facade + native EGL bridge,
> on the embedded HotSpot VM, on the live-OSRS client. Proven on the emulator (ANGLE→SwiftShader, ES 3.1):
> `isGpu=true`, `createGLContext` brings up the EGL ctx + canvas-sized FBO through `librlawt`, scene+UI shaders
> compile (the 320/310/300-es downport now lives in the `glShaderSource` shim), `swapBuffers`→`glReadPixels`→
> `gpuPublish`→the existing SurfaceView blit, and the dumped frame is a correct OSRS render — **right-side-up
> (vertical flip OK) with correct colours (BGRA + readback R↔B swizzle OK)**. The pieces:
> - **P1** `JavaBridge.gpuFrameBackBuffer/gpuPublish/canvasWidth/Height` + Hooks `!isGpu` CPU-snapshot gate.
> - **P2** `librlawt` AG2 shims: highest-ES context (3.2→3.0), `glShaderSource` downport, `swapBuffers` readback
>   publish, per-frame `getFramebuffer` FBO resize.
> - **P3** `dev-tools/gpu/stage-android-gpu-jar.sh` jar surgery (strip lwjgl/rlawt/gpu classes, facade-back,
>   recompile the plugin) → `client-<ver>-gpu.jar`.
> - **P4** `-Drunelite.routecgpu=true` full boot (registers the bridge after VM-create), `DeviceHeadlessBoot`
>   drops `--safe-mode` (which disables the gpu plugin — the one `loadInSafeMode=false` plugin), `RouteCGpuStarter`
>   force-enables the plugin in-world and forces `uiScalingMode=LINEAR` (the default HYBRID/xBR scale shaders use
>   `sourceDimensions` int↔float math strict GLSL-ES rejects; LINEAR is scale-free and is what AG1b validated),
>   `MainActivity` GPU launch path + dev `◆` button + `--ez routecgpu true`.
>
> **STATUS — AG2-P5 DEVICE PASS on a real Pixel 8 / Mali-G715 (2026-06-08, Firebase Test Lab, commit
> `18a4f0686`).** `VERDICT=AG2_DEVICE_PASS`: the unmodified base `gpu` plugin is the active renderer on real Mali
> (`isGpu=true` ×6), `createGLContext` brings up the EGL ctx + FBO through `librlawt` (`es=320 es`, OpenGL ES 3.2,
> `Using device: Mali-G715`), and the pulled frame is a correct OSRS render (right-side-up, correct colour) on real
> silicon. Evidence: `dev-tools/gpu/evidence/ag2-p5-pixel8-mali-{loginscreen.png,20260608.log}`. Harness:
> `fb-gate.sh gpu` (Robo can't pass `--ez routecgpu`, so it pushes a `gpu.mode` marker via `--other-files` +
> `--directories-to-pull` the frames). **Two findings:** (1) **Mali is strict, NOT lenient** — the default
> HYBRID/xBR UI scale shader (`sourceDimensions` int↔float) throws a transient `ShaderException` on the
> `enabledByDefault` auto-start before `RouteCGpuStarter` forces LINEAR; it recovers (isGpu→true) and self-heals
> once LINEAR persists. (2) The render proven here is the **OSRS LOGIN screen** — the Jagex session token had
> expired (login failed), so the relaxed `LOGIN_SCREEN` gate is what salvaged the real-Mali frame.
>
> **FIREBASE IS LOGIN-SCREEN-ONLY (settled 2026-06-08).** OSRS login FAILS on Firebase Test Lab even with a
> freshly-minted token ("Failed to login. Please try again." — evidence `ag2-firebase-login-blocked-freshtoken-
> 20260608.png`). The token is NOT the problem (it reuses across the user's Mac mini + MacBook with no remint —
> both log in from the home IP); Firebase runs in a Google datacenter, so the login originates from an unexpected
> IP/region and Jagex's OSRS login-location check rejects it (and/or the device's game-server egress is
> restricted). There is no Firebase knob to fix this (you can pick device model/API but not datacenter region).
> **So every auth-gated OSRS gate (in-world render, soak, login flows) must run on an OWNED physical device on a
> normal network — Firebase stays valid only for pre-login gates (AG0/AG1b recon, the base-gpu LOGIN-screen render).**
>
> **STATUS — IN-WORLD 3D SCENE renders via the GPU pipeline (2026-06-08, emulator, evidence
> `ag2-inworld-3dscene-gpu-emulator-20260608.png`).** The emulator logs in fine (it runs on the home network, the
> network the Jagex `session_id` was minted from — see the Firebase diagnosis below), so booting GPU mode there
> reaches LOGGED_IN and the unmodified base `gpu` plugin renders the **full live OSRS 3D world** through our
> facade + EGL bridge: terrain/trees/player models with correct **depth-sort (reverse-Z works)**, correct colour,
> right-side-up, native A6/A7 chrome composited on top. `isGpu=true`, `createGLContext` via librlawt. SwiftShader
> is non-authoritative for PERF, but the shader + render MECHANICS are already real-Mali-proven (AG1b validate +
> AG2 device render), so this functionally closes the 3D `drawScene` path (geometry/depth/flip).
>
> **WHY FIREBASE CAN'T LOG IN — PROVEN by a direct network probe (`NetProbe.kt`, run `fb-gate.sh netprobe`).** At
> login the gamepack hits `auth.runescape.com/game-session/*` (mint a play/OTL token with Bearer=session, then the
> game world). From the Firebase device (egress IP `108.177.18.240`, Google datacenter; IPv6 `2001:4860:…`) that
> API returns **HTTP 403 with a `<title>Security | Jagex</title>` page** — a WAF / anti-bot block on the datacenter
> IP, returned BEFORE the session is even evaluated. Control (`google/generate_204`→204) and `account.jagex.com`
> (→200) work, so it is not general egress — it is Jagex's security layer specifically blocking the game-session
> API from cloud/datacenter IPs. From the home network the SAME probe returns **HTTP 200 + the account list**.
> Evidence: `dev-tools/gpu/evidence/ag2-firebase-netprobe-waf403-20260608.log`. **This kills the "mint the token on
> the Firebase device" idea: minting uses the SAME game-session API, so it is 403-blocked identically — the block
> is on the IP/network, not the token/session. There is NO token trick** (a residential-proxy/SOCKS tunnel for ALL
> Jagex + the 43594 game socket would be the only path, and is over-engineering). Auth-gated OSRS gates run on a
> RESIDENTIAL network: the emulator (home mini) or a physical Android device on home wifi, adb-driven.
>
> **NEXT: in-world 3D on real MALI (perf-authoritative)** — needs a physical Android device on the home network
> (the emulator already proves the render functionally; AG1b/AG2 prove the Mali mechanics). Then AG2.5 (RLHD
> loaders + OpenCL facade — does NOT need login, Firebase-testable) → AG3 (117HD).
>
> **STATUS — AG2.5-a AUDIT DONE (2026-06-08).** RLHD vendored at `dev-tools/gpu/rlhd-src/` (rev `7dded59`, targets
> RuneLite `1.12.+` ✓; gitignored). Findings:
> - **Renderer split:** `ZoneRenderer` (DEFAULT, `legacyRenderer()`=false) gates on **`OpenGL33` only** — pure
>   graphics pipeline, NO compute/OpenCL. `LegacyRenderer` (opt-in) gates `OpenGL43` (GL compute) or `OpenGL31`
>   (OpenCL, forced only on `HdPlugin.APPLE`). **Ship Zone; LegacyRenderer is out.**
> - **OpenCL decision = STUB (option a), CONFIRMED REQUIRED.** Even on the Zone path the class-load graph reaches
>   `org.lwjgl.opencl`: `HdPlugin → UBOCompute (uses CL_MEM_READ_ONLY) → UniformBuffer → SharedGLBuffer (field
>   `OpenCLManager.context`) → OpenCLManager`, and `ZoneRenderer → UniformBuffer` too. BUT every `cl*` CALL is
>   guarded by `OpenCLManager.context != 0` (always 0 in Zone — `OpenCLManager.startUp`/`CL.create()` only run in
>   Legacy/Apple), and `OpenCLManager.<clinit>` only does `Configuration.OPENCL_EXPLICIT_INIT.set(true)` (org.lwjgl.
>   **system**, already in our facade — just add the field). So the stub only needs the SYMBOLS to exist for
>   class-load/verify; methods are never invoked (can throw). Surface ≈ 60 `CL_*` constants (any value) + ~25 `cl*`
>   methods + classes `CL, CL10, CL10GL, CL12, CLCapabilities, CLContextCallback, CLImageFormat, APPLEGLSharing,
>   KHRGLSharing`. (AG3 jar-surgery recompiles these RLHD files against the stub, same pattern as base-gpu.)
> - **GL call-delta (Zone vs base-gpu ~81):** adds `glTexBuffer` (TBO, GLES3.2 core), `glDrawArraysIndirect`/
>   `glDrawElementsIndirect` (GLES3.1), `glTexStorage3D` (have it), `glFramebufferTexture`/`glBindImageTexture`
>   (GLES3.1/3.2, tiled-lighting, OPTIONAL w/ fallback), `glVertexAttribDivisor`, fence/sync, `glGetProgramBinary`
>   (optional cache). **All map to GLES 3.1/3.2 core** (AG0 = 3.2) — no compute needed for Zone. `glDispatchCompute`/
>   `glMemoryBarrier` are Legacy-only (out).
> - **Shaders:** Zone graphics shaders are `#version 330` (SAME downport as base-gpu, already proven on Mali); use
>   `isamplerBuffer` TBO + `sampler2DArray`/`usampler2DArray` + 8 `std140` UBOs — all GLES 3.2 core. The 2 compute
>   shaders (`#version 420/430`) are Legacy-only. 47 .glsl, ~4 Zone programs (UI/Scene/Shadow/TiledLighting).
> - **Loaders** (Texture/Material/WaterType/Gameval/Area/GroundMaterial/TileOverride/ModelOverride/Light/Environment
>   managers): all bundled-classpath JSON/texture FILE-I/O, NO network, NO headless-unsafe (no Frame/ClientUI). Low
>   risk under our headless boot.
>
> **STATUS — AG2.5-b GREEN on the emulator (2026-06-08).** The OpenCL stub + 117HD Zone facade delta are built and
> the UNMODIFIED RLHD source (304 classes) compiles AND class-loads against our pure-Java `org.lwjgl` facade.
> - **Build (host, deterministic):** `dev-tools/gpu/stage-rlhd-zone-jar.sh` compiles RLHD against [facade-src :
>   **lwjgl-stripped** client jar] — the facade is the SOLE LWJGL provider, so a missing GL/CL/system symbol is a
>   `javac` error, not a runtime `NoClassDefFound`. lombok runs as an annotation processor (no separate delombok).
>   `MEGAVERDICT=ANDROID_RLHD_STAGE_GREEN`: 304 classes, 0 lwjgl leak, 47 glsl, 9 loader json, ZoneRenderer +
>   OpenCLManager present → `client-<ver>-rlhd.jar` (gitignored). The facade ships in the base-gpu
>   `client-<ver>-gpu.jar`, so the gpu jar MUST be re-staged from the same facade-src (it is shared).
> - **Facade delta:** `org/lwjgl/opencl/*` symbols-only stub (CL/CL10/CL10GL/CL12/CLCapabilities/CLContextCallback/
>   CLImageFormat/APPLEGLSharing/KHRGLSharing + ~60 CL_* + ~25 cl*, all throwing — Zone never creates a CL context);
>   `Configuration.OPENCL_EXPLICIT_INIT` (the only thing OpenCLManager.<clinit> touches); GLALL grows by the Zone GL
>   surface (glTexBuffer/indirect/fence-sync/glVertexAttribDivisor/glFramebufferTexture(Layer)/glBindImageTexture/
>   program-binary, GLES 3.1/3.2 core) + GL40/41C/43/44/ARBTextureStorage/ARBShaderImageLoadStore shims;
>   GLCapabilities reports ARB buffer-storage/image-load-store/indirect FALSE so RLHD takes the GLES-core fallbacks;
>   functional MemoryStack/MemoryUtil/PointerBuffer/Platform/Version (MemoryUtil raw-pointer ops are native → librlawt,
>   render path only). The whole facade is ADDITIVE — the base gpu plugin still recompiles GREEN against it.
> - **Runtime gate (device JVM, emulator-authoritative — it's class-load/verify/JSON, NO GL execution):**
>   `dev-tools/gpu/check-rlhdprobe.sh` boots `RLHDProbe` (mode `--ez rlhdprobe`, host does CACIO activation then a
>   headless class-load test — no engine/bridge/render/login) on [gpu jar + rlhd jar]. `MEGAVERDICT=ANDROID_RLHD_PROBE_GREEN`:
>   **linkSweep linked=304/304 failures=0** (every rs117.* class load+links+verifies against the facade — no
>   NoClassDefFound/VerifyError), **openclGraph init=true OPENCL_EXPLICIT_INIT=true** (the OpenCL class-load graph runs
>   its <clinit>), **loaderJson parsed=9/9** (the bundled loader data is present + well-formed on-device). Evidence
>   `dev-tools/gpu/evidence/ag25b-rlhdprobe-emulator-20260608.log`.
>
> **NEXT (AG3 — Zone bring-up):** wire RLHD into the runtime plugin lifecycle and run `HdPlugin.startUp` end-to-end so
> the loaders' typed Gson data-load + the renderer/VAO/UBO/shader init actually execute against a live EGL/GLES context
> (this is where the librlawt GL-delta forwarders + MemoryUtil native backing get exercised; the AG2.5-b probe proves
> class-load/data-presence, NOT the full GL startUp). Then the Zone shaders (330, same downport family as base-gpu) +
> first HD frame.

# Android GPU Rendering + 117HD: Final Plan (post-review)

This is the funded engineering plan to move Android RuneLite off the CACIO software rasterizer onto the GPU (base `gpu` plugin) and, best-effort, onto 117HD. All work lands on the **`android` branch**. The reusable GPU prior art is on the **`gpu` branch** under `ios/dev-tools/gpu-routec/`. Where the seven research maps disagreed with disk, the on-disk fact wins and is flagged.

---

## Corrections from adversarial review (what changed vs the draft, and why)

Eight findings were verified against disk this session and the draft was wrong on each:

1. **A7 does NOT contend with the AG2 seam — and is already merged.** Verified: commit `76d12169e` ("A7 minimap+orbs safe-area relocation") is at branch HEAD with A8 on top of it; its `jvm_bootstrap.cpp` diff adds only `nativeRelocateMinimap` HUD-relocation JNI (lines confirmed) and touches **zero** of `ANativeWindow_lock`/`setBuffersGeometry`/the swizzle blit. A7 relocates the HUD *inside the engine framebuffer*; the blit seam is a different layer. **§6 is rewritten: there is no A7-vs-AG2 collision; AG2/AG5 own the seam outright and are unblocked. All "defer A7" / "before A6 ships" language is deleted** (A6/A7/A8 are all landed).

2. **The "1.12.29 jar + MainActivity:451" prerequisite is fabricated.** Verified: `MainActivity.kt:775` resolves the jar by glob (`startsWith("client-") && endsWith("-shaded.jar")`), version-agnostic; there is no line-451 jar literal (file is 1139 lines), and `client-1.12.29-SNAPSHOT-shaded.jar` is already staged. **Demoted to a non-blocking dev-tools cleanup** (the ~20 stale `run-*.sh` naming 1.12.28).

3. **AG2 "no new gate needed" is false on the android branch.** Verified: `Hooks.java:559` runs `JavaBridge.snapshot(...)` (when `isEnabled()`) **before** the `if (client.isGpu()) return;` at `:562` — the gate skips only the later Swing stretch/paint, not the snapshot, so GPU mode would still publish a CPU frame and fight `frameSeq`. And `gpuPublish`/`gpuFrameBackBuffer` **do not exist on android** (`grep -c` = 0; they live only on the `gpu` branch). **AG2 now explicitly includes porting the gpu-branch Hooks gate change AND adding the two bridge methods.**

4. **The AG1 risk was mis-stated as an LWJGL-introspection problem.** Verified: the architecture *strips real LWJGL*; `GL.createCapabilities()` is a `native` stub and the iOS JNI hardcodes `OpenGL33=JNI_TRUE` — there is no LWJGL introspection or Pojav/GLFW path at runtime. **The AG1 risk is re-stated as (a) real-shader compile/link/`glValidateProgram` on a real Adreno/Mali driver, (b) `eglMakeCurrent` on the HotSpot-attached render thread, (c) CACIO-canvas `getTreeLock()`/`isValid()`/`getWidth()` behavior, (d) facade `Configuration`/`Callback` static-init not loading real liblwjgl.** AG1 is split into AG1a (facade-bind, iOS-proven, ~75%) and AG1b (real shaders + one frame, proven nowhere, ~50%).

5. **iOS L1-PASS was over-credited.** Verified: `RouteCL1Probe.java` calls `createCapabilities` + `GL33C.glCreateProgram()` (empty) + glGetError + the 5 shims — **never** `glShaderSource/glCompileShader/glLinkProgram/glValidateProgram`. The load-bearing AG1 step is unproven on *any* mobile GLES driver, iOS included. Odds are recompounded accordingly: **a real base-gpu frame ≈ 0.75 × 0.50 ≈ ~35–40%, not "probably yes ~60%."**

6. **The BGRA fix was mis-specified.** Verified in `routec_gl_jni.m`: the working recipe is, in the `glTexImage2D`/`glTexSubImage2D` shims, rewrite `GL_BGRA→GL_RGBA` and `GL_UNSIGNED_INT_8_8_8_8_REV→GL_UNSIGNED_BYTE`, plus per-texture `glTexParameteri(GL_TEXTURE_SWIZZLE_R, GL_BLUE)` / `SWIZZLE_B, GL_RED` on the interface texture. **The "swizzle in fragui.glsl" and "reuse the CPU int[] blit swizzle" suggestions are dropped as wrong** (different layers).

7. **The EGL surface-teardown ordering is a latent crash, not a soak item.** Verified: `nativeSetSurface` (jvm_bootstrap.cpp:925-937) calls `ANativeWindow_release(old)` synchronously on the ART UI thread, then bumps `g_surfaceGen`; the render thread that owns the EGL window surface is never told to tear it down first. **Promoted to a first-class AG2-Phase-2/AG5 design item (handshake), and AG5 is no longer "mostly verification."**

8. **Dropped dependencies surfaced:** `api(libs.lwjgl.opencl)` at `build.gradle.kts:82` is a hard *compile* dep with no facade in the port list (a class-init `NoClassDefFound` risk for RLHD Legacy); `abiFilters += "arm64-v8a"` only (single-ABI hard lock — "per-ABI librlawt" is misleading). The "A19 Pro: ~30-40 FPS, throttle to 20-25 after 10 min" perf numbers in the maps have **no committed evidence file** on the gpu branch and are removed as fabricated precision.

Also corrected: the BLUEPRINT's seam citation (lines 251/434) is **stale**; the real seam is `jvm_bootstrap.cpp:718-739` (geometry+lock+swizzle+post) and `:935` (surfaceGen). AG1↔AG0 are decoupled (AG1 needs only *some* live GLES≥3.0 context; AG0 gates the AG3 downport-skip).

---

## 1. What GPU rendering actually buys

Today's path is CPU-bound: engine → CACIO software raster → `int[]` → `JavaBridge.snapshot()` (`JavaBridge.java:212-249`) → native `renderLoop()` BGRA↔RGBA swizzle blit (`jvm_bootstrap.cpp:724-737`). Every pixel of the 3D scene is rasterized on the CPU by the embedded HotSpot — the bottleneck that killed the iOS miniJVM and motivated the real-JVM pivot.

**Base `gpu` plugin alone (the right first target — ~80% of the "real client look"):**

- **3D scene rasterization moves to the GPU.** The engine stops CPU-rasterizing triangles; `GpuPlugin` uploads geometry + textures and the GPU draws (`vert.glsl`/`frag.glsl`, `glDrawArrays`/`glMultiDrawArrays`). The single biggest CPU unload.
- **Higher, more stable FPS** — removes the software-raster fill cost that scales with screen area. On a ~1200×2670 panel the CPU fill cost is brutal; the GPU eats it.
- **Draw distance** — `client.setExpandedMapLoading(config.expandedMapLoadingZones())` (`GpuPlugin.java:366`) is a base-gpu feature, not a 117HD one.
- **Anti-aliasing (MSAA)** — renderbuffer + `glRenderbufferStorageMultisample` + `glBlitFramebuffer` resolve.
- **Sharper UI / texture filtering** — `fragui.glsl` scaling modes (bilinear/Mitchell/Catmull-Rom/xBR/hybrid) replace the nearest-neighbour CPU blit; anisotropic when `GL_EXT_texture_filter_anisotropic` present (`TextureManager.java:109`).
- **Resolves the HD↔FPS tension by itself** — native-resolution sharpness *and* high FPS, no CPU trade-off.

**What 117HD adds (the last ~20%, riskiest):** real-time lighting, dynamic/tiled lights, shadows, water, PBR materials/normal maps, ground blending, atmospherics. Multi-pass (shadow depth + scene + optional tiled-lighting + MSAA resolve + UI); mobile perf is **unproven on any platform**.

**Honest framing (the decided strategy):** promise *"GPU rendering: sharper + faster."* Do **not** promise *"enable 117HD → HD."* Base `gpu` is the legitimate deliverable; 117HD is best-effort on top.

---

## 2. The Android advantage

Android is a materially better GPU target than iOS. The iOS Route-C work fought constraints that do not exist here:

| iOS constraint | Android reality | iOS workaround that DISAPPEARS |
|---|---|---|
| EAGL/MetalANGLE = **ES 3.0 only**, no compute | Native **GLES 3.1/3.2 + compute + SSBO** on modern arm64 (M4, M5) | TBO→2D-array repack, draw-indirect stripping, compute→CPU fallback, SSBO→UBO loop emulation, image-store removal (M5: 7 of 14 iOS blockers vanish) |
| No `glClipControl` → reverse-Z via `GL_GEQUAL` + `clearDepthf(0)` projection hack | **`EXT_clip_control`** available natively (probe in AG0) | the reverse-Z GEQUAL hack |
| JIT requires StikDebug per-launch, libjvm NOPs, `brk #0x69` mirror, Mach exception-port surgery, `increased-memory-limit`, resign churn | **JIT is free** (ART hosts HotSpot, no debugger attach) | `patch-jre-txm.sh`, `MirrorMappedCodeCache`, exception-port code, `resign-jre.sh`, the WebKit restart dance |
| Offscreen FBO + readback is the *only* present path | **EGL-on-`ANativeWindow`** is the natural production path (`eglCreateWindowSurface` on the SurfaceView; M6 §3) | the forced readback becomes a *bring-up* convenience, not a permanent tax |
| ANGLE/Metal platform extensions (`eglGetPlatformDisplayEXT`, `EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE`) | Standard `eglGetDisplay(EGL_DEFAULT_DISPLAY)` | all ANGLE platform-selector code (M2 §7) |

**The cost Android adds:** there is **no `natives-android` LWJGL classifier** (verified: `runelite-client/build.gradle.kts:93-94` loops over linux/macos/windows only). So real LWJGL is *physically stripped* and replaced by a pure-Java facade + a native EGL `.so` (`librlawt`). That is the iOS Route-C pattern.

**Catches that do NOT disappear:**
- **GLSL strictness is *stricter*, not looser,** on GLSL 3.1x/4.x (explicit int→float casts, `vec2 *= ivec2` rejected) — M5 blockers #12-14 stay.
- **`glValidateProgram` is fatal** (`Shader.java:100-105` throws on `GL_VALIDATE_STATUS==GL_FALSE`). A real Adreno/Mali driver may reject what SwiftShader and lenient EAGL accept. **This is unproven on every mobile driver, iOS L1 included** (the iOS probe never compiled a real shader — verified).
- **`eglMakeCurrent` on the HotSpot-attached render thread** is the genuine EGL risk Android adds (EAGL's `setCurrentContext` has no display/surface and cannot exercise it; M4 rates this 60%).

---

## 3. The sequenced plan: AG0 → AG5

Reuse legend: **[REUSE-iOS]** = port the `gpu`-branch Route-C Java facade nearly verbatim · **[NEW-EGL]** = new Android-native code (`librlawt`) · **[REUSE-W2]** = shared headless Java already on `android`.

### AG0 — Real-device GLES tier recon · HARD GATE
- **Goal:** ground truth on real arm64: GLES version, compute, `EXT_clip_control`, `GL_MAX_SAMPLES`, `EXT_texture_format_BGRA8888`, `DEPTH_COMPONENT24/32F` renderability, `RGBA16UI` 2D-array color-renderability, offscreen FBO + `glReadPixels`.
- **Work [NEW-EGL, throwaway-small]:** standalone recon APK (Kotlin → EGL ctx → `glGetString`/`glGetIntegerv` → logcat). Ship via Firebase Test Lab (`android/dev-tools/firebase/fb-gate.sh recon`). **The recon APK is not yet built — it is part of the §7 deliverable.**
- **GREEN gate:** md5-gated device logcat confirming **ES ≥3.1 + compute + clip_control**.
- **Deps:** none — runs immediately, **truly in parallel with AG1** (decoupled: AG1 needs only *some* live GLES≥3.0 context for plumbing; AG0's caps verdict gates the AG3 downport-skip, not AG1).
- **Emulator vs device:** **DEVICE ONLY.** The emulator advertises `ro.opengles.version=0x30001` (ES 3.0) but runs ES 3.1 via ANGLE→SwiftShader (M7) — non-authoritative for caps, extensions, and every perf number.
- **Odds:** ~90%.

### AG1 — Native EGL context → `OpenGL33==true`, headless · HARD GATE, HIGHEST RISK
Split into two sub-gates so the headline odds stop double-counting the iOS green:

**AG1a — facade bind + empty program + `createCapabilities`, headless (~75%, iOS-proven on device).**
- **Goal:** `GL.createCapabilities()` returns `OpenGL33==true` through the facade on a live EGL/GLES context, headless; `glCreateProgram()` returns non-zero; the 5 shims don't throw. (This is exactly what iOS L1 PASSED — `routec-l1-device-verdict-20260605.log` — and no more.)
- **Work — facade (Java) [REUSE-iOS]:** port verbatim into the Android client source set (all are platform-neutral pure Java — confirmed 14 files, GLALL has 189 native decls):
  - `org/lwjgl/opengl/{GL, GL33C, GL42C, GL43C, GL45C, GLALL, GLCapabilities, GLUtil, EXTTextureFilterAnisotropic}.java`
  - `org/lwjgl/{BufferUtils, PointerBuffer}.java`, `org/lwjgl/system/{Callback, Configuration}.java`
  - `net/runelite/rlawt/AWTContext.java`
  - **`GpuPlugin.java` and the real `AWTContext` stay byte-for-byte unmodified.** The facade satisfies the imports; `GLCapabilities` is populated by JNI (`OpenGL33=true`, `OpenGL45=false`, `glTexStorage3D≠0`, `glDebugMessageControl=0`, probe anisotropic).
  - **Explicitly assert the facade `Configuration` is a self-contained stub** — `SHARED_LIBRARY_EXTRACT_DIRECTORY.set(...)` (called at `GpuPlugin.java:310`, before `createCapabilities` at `:312`) must be a no-op that never delegates to real liblwjgl; `Callback` likewise. This static-init is a cheaper, more likely AG1 crash than the GL binding and is named in the §7 smoke checklist.
- **Work — EGL native (`librlawt`) [NEW-EGL]:** new `android/app/src/main/cpp/librlawt.cpp` + new CMake target (current CMake builds only `helloshell`, links `log android dl`, no EGL — verified):
  1. `eglGetDisplay(EGL_DEFAULT_DISPLAY)` → `eglInitialize` → `eglChooseConfig(EGL_OPENGL_ES3_BIT, EGL_PBUFFER_BIT, depth 24)` → `eglCreateContext(CLIENT_VERSION 3)` → `eglMakeCurrent` (pbuffer or `EGL_KHR_surfaceless_context`).
  2. `RegisterNatives` the ~98-entry table (M2 §5): ~80 GL forwards, 5 non-ES-core shims, 8 `AWTContext` methods, `GL.createCapabilities`. **Adapt the iOS `routec_gl_jni.m` table** — swap `RouteCBridge` ObjC for C functions.
  3. The 5 shims (M1/M2/M6): `glClearDepth(double)`→`glClearDepthf`; `glMapBuffer`→`glMapBufferRange(...,GL_MAP_WRITE_BIT|GL_MAP_INVALIDATE_BUFFER_BIT)`; `glReadBuffer`→hardcode `GL_BACK`/`GL_COLOR_ATTACHMENT0`; `glClipControl`→native `EXT_clip_control` if AG0-confirmed (else GEQUAL fallback); `glMultiDrawArrays`→native on ES 3.1.
- **GREEN gate (AG1a):** md5-gated device logcat: `librlawt` "EGL context live" + `OpenGL33=true` + `createProgram≠0` + shims clean, headless under the **real DeviceHeadlessBoot/JNI_CreateJavaVM render thread** (not a plain ART activity).

**AG1b — real shaders compile/link/VALIDATE + one frame (~50%, proven nowhere).**
- **Goal:** the unmodified `GpuPlugin.startUp()` runs past the `OpenGL33` gate at `:317` AND compiles+links+**validates** the real base `vert/frag/vertui/fragui.glsl` (after the runtime 300-es downport) on the real device driver, producing one frame (even black) with no `glValidateProgram` throw.
- **Two named sub-risks promoted from prose to first-class failure modes:**
  - **(i) Real-driver `glValidateProgram`.** Add a **pre-AG1b micro-gate**: in `librlawt`, compile+link+validate the four base shaders standalone on the real device before claiming AG1b green. A validate throw on Adreno/Mali is an AG1b RED, not a footnote. SwiftShader and EAGL are not proxies; iOS L1 never ran this step.
  - **(ii) CACIO headless Canvas coupling.** `GpuPlugin.startUp()` does `client.getCanvas()` → `synchronized(canvas.getTreeLock())` → `canvas.isValid()`/`validate()` → `canvas.setIgnoreRepaint(true)` (verified `:291-306`) and feeds canvas dims into context/FBO sizing. The `new AWTContext(null)` smoke never exercises these. **Sub-gate:** run the *unmodified* `startUp()` against the real headless CACIO canvas and verify (a) `getTreeLock()` does not deadlock the bridge thread, (b) `getWidth()/getHeight()` return live game dims (not 0 → otherwise the context is stuck at the 512×512 probe size forever), (c) `setIgnoreRepaint()` is benign. If the peerless canvas reports 0×0 pre-layout, add a headless validate/resize before `GpuPlugin` starts.
- **Deps:** AG1a green; any live GLES≥3.0 context. **[REUSE-W2]** the entire headless boot (`DeviceHeadlessBoot → RuneLiteHeadless → HeadlessClientUI`, CACIO CTC, `UnsafePokeActivation`, `JNI_CreateJavaVM`) unchanged.
- **Emulator vs device:** facade compiles + EGL ctx + AG1a smoke on **emulator** (free iteration); **AG1b is GREEN only on device** (BrowserStack — intent-extra control + iterative debug). SwiftShader passes too leniently to trust; emulator-green ≠ AG1-green.
- **Odds:** AG1a ~75%, AG1b ~50% → **AG1 overall ~50% (the project-wide coin-flip).**

### AG2 — Base `gpu` plugin renders the real world
- **Goal:** the unmodified base `gpu` plugin renders the live OSRS scene.
- **Work [NEW-EGL + REUSE-iOS]:**
  - Wire the full GL surface (M1/M6 census: 100 facade calls, 81 distinct ES symbols, only ~5 non-core). On Android they forward straight to `GLES31/GLES32` — no downport for what iOS dropped.
  - **Base-gpu runtime shader downport (explicit AG2 work item, not "free").** The base shaders ship a license comment **before** `#version 330` (`head -1` = `/*`), which the iOS downporter (`RouteCBridge.m`) had to detect, strip, and replace with `#version 300 es` + precision qualifiers, **inside the `glShaderSource` shim, for every base shader.** It was tuned for EAGL/ANGLE, not Adreno/Mali. On a real ES 3.1+ device, **prefer targeting `#version 310 es`** and re-test precision/cast strictness rather than assuming the EAGL-tuned 300-es path ports clean. Re-validate against the real driver (carries its own AG2 risk, shared with AG1b's validate risk).
  - **BGRA→RGBA (corrected, port `routec_gl_jni.m` verbatim — do not re-derive):** in the `librlawt` `glTexImage2D`/`glTexSubImage2D` shims, rewrite external format `GL_BGRA→GL_RGBA` and type `GL_UNSIGNED_INT_8_8_8_8_REV→GL_UNSIGNED_BYTE`, and install per-texture `glTexParameteri(GL_TEXTURE_SWIZZLE_R, GL_BLUE)` / `(GL_TEXTURE_SWIZZLE_B, GL_RED)` on the interface texture (preserving packing). `EXT_texture_format_BGRA8888` (AG0 probe) only legalizes BGRA as an *external* upload format and does not remove the swizzle need. The fragui.glsl-swizzle and CPU-int[]-blit-reuse ideas are dropped (different mechanisms).
  - **Reverse-Z:** native `EXT_clip_control(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` if AG0-green (vs the iOS GEQUAL hack).
  - **Frame-publish gate (corrected — a gate change IS required):** the gpu branch moved/gated `JavaBridge.snapshot()` so GPU mode is the sole `frameSeq` publisher; on android `snapshot()` still runs at `Hooks.java:559` *before* the `isGpu()` return at `:562`. **Port the gpu-branch Hooks change** (relocate/condition the snapshot below the `isGpu` early-return) **AND add `gpuFrameBackBuffer(II)[I` + `gpuPublish(II)V` to android's `JavaBridge`** (both are 0 hits on android today — they exist only on the gpu branch). They reuse the same `bufA`/`bufB` pool and bump `frameSeq`.
  - **Render path (staged):** Phase 1 = `glReadPixels` → existing `bufA`/`bufB` `int[]` pool → existing SurfaceView blit (isolates "did GL come up" from "is the present path right"). The GPU EGL thread renders to an **offscreen FBO and reads back; it must NOT call `ANativeWindow_lock`** — only the CPU blit touches the window (avoids the dual-owner race, M6 §3).
- **GREEN gate:** md5-gated device evidence of `gpu.startUp` + a composited frame of the real world; colours correct (BGRA fix), depth + vertical flip correct.
- **Deps:** AG1 (AG1a + AG1b).
- **Emulator vs device:** plumbing/shaders/API-correctness on emulator; **pixel correctness + reverse-Z + BGRA + real-driver validate only valid on device.**
- **Odds:** ~60% *conditional on AG1*. Note the honest compound below.

### AG2.5 — Headless loaders re-proof + asset-licensing + OpenCL facade decision (blocks AG3)
- **Goal:** prove RLHD's loader systems (gameval/override/area/tile/texture managers) initialize under our headless client on-device; **resolve OpenCL class-load**; settle asset licensing (the *engineering* parts only — the licensing *call* is moved out, see §5).
- **Work [REUSE-W2 + NEW-EGL]:**
  - Run RLHD's loaders under the headless boot.
  - **OpenCL (newly surfaced):** `api(libs.lwjgl.opencl)` (`build.gradle.kts:82`) is a hard *compile* dep with no facade in the port list — RLHD's Legacy gate is `OpenGL31 with OpenCL fallback`. **Pre-AG3 hard check (parallel to the GL census):** either (a) ship a stubbed `org/lwjgl/opencl/{CL, CLCapabilities}` facade returning "unavailable" so class-loading never `NoClassDefFound`s, OR (b) confirm via the vendored-RLHD re-audit that the Zone default path never references `org.lwjgl.opencl` at class-init and explicitly scope **out** Legacy/OpenCL. Default to (a) — cheap insurance.
- **GREEN gate:** loaders init without error on device; OpenCL class-load resolved; licensing decision (or the vanilla-textures default) recorded.
- **Deps:** AG2.
- **Odds:** highest *non-GL* 117HD risk.

### AG3 — 117HD bring-up
- **Goal:** RLHD's default (Zone) renderer running on Android.
- **Work [NEW-EGL + REUSE-iOS]:** vendor RLHD from the plugin hub; **re-pin + re-audit the Zone-vs-Legacy split against the actual vendored RLHD revision** (the iOS split is version-specific to one commit — see §5 OPEN). Expand the GL facade by the RLHD call-delta; wire std140 UBOs, `sampler2DArray`/`isamplerBuffer` TBOs (native on ES 3.1 — no iOS repack), MSAA color RBO + `DEPTH_COMPONENT32F`, `glBlitFramebuffer` resolve, `glDrawBuffers`. Downport shaders `#version 420/430` → `#version 310 es`/`320 es` (looser than iOS's hard `300 es` cap).
- **GREEN gate:** recognizable HD scene + **staged perf/thermal microbench** (static → alpha-heavy → +shadows → +tiled/dynamic lights → 10-min sustained soak) *before* any playability claim. **There are no committed mobile perf numbers yet** — the staged bench produces the first ones; do not cite a figure until measured.
- **Deps:** AG2, AG2.5.
- **Emulator vs device:** **device only** — emulator perf is fiction; compute semantics (barriers/atomics) differ on SwiftShader.
- **Odds:** full ~20%, partial ~60%.

### AG4 — 117HD feature layers (best-effort frontier)
- **Goal:** materials, lighting, shadows, water, dynamic/tiled lights, atmosphere.
- **Android upside:** dynamic/tiled lights via compute + SSBO + image-store — RLHD's fast path iOS could not take.
- **GREEN gate:** per-feature visual parity at acceptable sustained FPS. Not a hard gate. Risk: thermal throttling.
- **Deps:** AG3.

### AG5 — GPU stability + production present
- **Goal:** GPU path production-ready: retire the `glReadPixels` round-trip → direct `eglCreateWindowSurface` on the `ANativeWindow` + `eglSwapBuffers`.
- **Work [NEW-EGL] — now real design, not "mostly verification":**
  - **Surface-teardown handshake (CRITICAL — the moment a window surface lands).** Verified bug: `nativeSetSurface` (jvm_bootstrap.cpp:925-937) calls `ANativeWindow_release(old)` synchronously on the ART UI thread, then bumps `g_surfaceGen` — the render thread that owns the EGL window surface is never told to tear it down first. An EGL window surface holds a ref to that `ANativeWindow`; releasing it underneath is a use-after-free/driver crash. Phase-1 (offscreen FBO + readback) hides this; it surfaces exactly when "production present" lands. **Design the handshake now:** on surface change the UI thread *signals* the render thread; the render thread does `eglMakeCurrent(NO_SURFACE)` + `eglDestroySurface` and *acknowledges*; **only then** may `nativeSetSurface` release the old `ANativeWindow`. Also handle `EGL_CONTEXT_LOST`/`EGL_BAD_NATIVE_WINDOW` from `eglSwapBuffers` explicitly. iOS never exercised Android's Surface-destroy-on-background lifecycle (it keeps the `CAEAGLLayer`), so there is no prior art to lean on.
  - **Memory argument for Phase 2:** the direct-EGL present removes the `bufA`/`bufB` `int[]` pair from the Java heap (~12.8 MB each at ~1200×2670 → ~25.6 MB inside `-Xmx512m`). See §8 memory note.
- **GREEN gate:** GPU soak, no context-loss crash across background/foreground churn; the teardown handshake holds.
- **Deps:** AG4. **Odds:** medium (real design + soak, not pure verification).

---

## 4. The two hard go/no-gos + STOP rule

**AG0 — real-device GLES recon.** *Proves:* the target is ES ≥3.1 + compute + clip_control, so the Android downport-skip and 117HD compute path are real. *Kills:* if a target device is ES 3.0/compute-less, base `gpu` **still needs the `glMultiDrawArrays`→loop shim and the 300-es downport** (corrected — base gpu uses `glMultiDrawArrays`, which is ES 3.1+, and 330 shaders; "only needs OpenGL33" is the facade's hardcoded lie, not real ES-3.0-core sufficiency), and the 117HD clean path is off → fall back to iOS downports. *Device-only* (SwiftShader caps non-authoritative). Free (Firebase, 5 runs/day). ~90%.

**AG1 — `OpenGL33==true` via facade + real shaders on real EGL, headless.** *Proves:* (AG1a) the stripped facade binds a desktop-GL face to a real GLES context with no GLFW window, under our HotSpot-attached render thread; (AG1b) the real base shaders compile/link/**validate** on a real Adreno/Mali driver and `GpuPlugin.startUp()` runs past `:317`, against the real CACIO canvas, producing one frame. *Kills:* if `eglMakeCurrent` fails on the attached thread, or `glValidateProgram` rejects the base shaders on a real driver, or the CACIO canvas deadlocks/reports 0×0 — the GPU architecture is invalidated. **The whole GPU track gates here.** Note: AG1a is iOS-proven and ~75%; AG1b is proven on no mobile driver and ~50%.

**STOP rule:** **Red AG1 → STOP. Track A (A0–A8, all merged and emulator-GREEN, INCLUDING A7) is the complete, shippable v1 product.** Do not sink effort into AG2/AG3 on a broken facade. This is an accepted, fine outcome — base CPU rendering with native chrome is v1 regardless; Track G is a fast-follow upside with no remaining Track-A sequencing dependency.

---

## 5. 117HD specifically

**What it needs beyond base `gpu` (M5 §1, verified against current RLHD master):** `isamplerBuffer` TBOs (`Zone.java:831`, `scene_vert.glsl:43`), std140 UBOs (`glBindBufferRange`/`glUniformBlockBinding`), `sampler2DArray`+`isampler2DArray`, MSAA RBO + `GL_MAX_SAMPLES`, `DEPTH_COMPONENT32F` reverse-Z depth, `glClipControlEXT`, `glBlitFramebuffer` MSAA resolve, `#version 420/430` shaders, `glDrawBuffers`. Conditional: image-store tiled lighting (fallback per-layer FBO), dynamic lights, shadows.

**What Android compute restores (7 of 14 iOS blockers vanish):** TBO (#1), draw-indirect (#2), layered `glFramebufferTexture` (#3), `CLAMP_TO_BORDER` (#6), geometry shaders (#8), compute+SSBO (#9), image-store (#10). **What remains on all platforms:** `RGBA16UI` renderability probe (#4), shader header downport (#7, but to `310/320 es`), and GLSL type-strictness/explicit-cast edits (#12-14).

**Zone-vs-Legacy:** iOS was forced to Zone (default, CPU-sort, `OpenGL33` gate, zero compute) because EAGL had no compute. Android *has* compute, so the choice genuinely re-opens. Current RLHD master: Zone gates `OpenGL33` (`ZoneRenderer.java:167`), Legacy gates `OpenGL43` (or `OpenGL31` + OpenCL). Default to Zone (proven on metal, JIT makes CPU-sort cheap); Legacy is speculative — compute works in principle but is **never proven on mobile** (thermal unknown), and its OpenCL fallback needs the §AG2.5 facade decision.

**Asset licensing — moved out of the technical ladder.** RLHD's `scene/textures/000_licenses.txt` mixes Jagex-derived ("Fan Content Policy", e.g. `LEAVES_SIDE_ALT`), CC0/community (AmbientCG, 3dtextures, pexels), and project-granted ("licensed to 117HD and any future forks"). The Jagex-derived-in-an-app-vs-plugin question is **not an engineering gate the team can md5-green** and can block AG3 indefinitely with no technical remedy. **Decision: make HD lighting/shadows on vanilla OSRS textures the DEFAULT 117HD plan** (no vendored assets, no legal gate, ships faster, still reads as "HD"). Bundling RLHD's asset pack is an *upgrade* contingent on a licensing call resolved out-of-band — see OPEN.

**Honest odds:** full 117HD **~20%**, partial **~60%**. Best-effort; never promise full HD.

---

## 6. Render-seam ownership (rewritten against HEAD)

**There is no A7-vs-AG2 contention.** Verified: A7 (`76d12169e`) is merged at HEAD with A8 on top; its `jvm_bootstrap.cpp` change adds only HUD-relocation JNI (`nativeRelocateMinimap`) and touches **zero** of the blit seam. A7 relocates the engine HUD *inside the engine framebuffer* (the same root-anchored relocate the chat and side-panel veneers use); the host-side present path is a different layer. The two never collide.

**Ownership:**
1. **AG2/AG5 own the seam restructure outright, unblocked.** AG2 Phase 1 *reuses* the existing CPU blit unchanged (`glReadPixels` into `bufA`/`bufB` from an offscreen FBO; the GPU EGL thread does **not** call `ANativeWindow_lock`). AG5 Phase 2 does the one clean restructure to direct EGL-on-`ANativeWindow`, with the surface-teardown handshake (§AG5).
2. **No "defer A7" / "before A6 ships" sequencing remains** — A6/A7/A8 are landed. Track A is HEAD-as-built and emulator-GREEN; the GPU track is a clean fast-follow.
3. **Correct seam lines:** `jvm_bootstrap.cpp:718-739` (geometry + lock + swizzle + post) and `:935` (`g_surfaceGen`). The BLUEPRINT's cited `251/434` are **stale** (251 = `pending.push_back`, 434 = a FindClass error log) — superseded by these.

---

## 7. The immediate first deliverable (do-now spec)

**Build the AG1a facade-bind smoke harness (emulator) + the AG0 recon APK (real device), in parallel.** Be honest about scope: the emulator smoke de-risks the *easy ~75%* (EGL + facade link + RegisterNatives + facade static-init); it does **not** close the coin-flip — only the AG1b run (real shaders + real driver + the real DeviceHeadlessBoot/HotSpot render thread, on device) does. **Emulator-GREEN ≠ AG1-GREEN; emulator-RED kills it instantly and cheaply.**

### Artifacts to create (on the `android` branch)

**1. Port the Route-C Java facade** (verbatim — pure Java, 14 files confirmed on the gpu branch) into `runelite-client/src/main/java/`:
- `org/lwjgl/opengl/{GL, GL33C, GL42C, GL43C, GL45C, GLALL (189 native decls), GLCapabilities (5-field struct), GLUtil, EXTTextureFilterAnisotropic}.java`
- `org/lwjgl/{BufferUtils, PointerBuffer}.java`; `org/lwjgl/system/{Callback, Configuration}.java` — **`Configuration` must be a self-contained stub whose `SHARED_LIBRARY_EXTRACT_DIRECTORY` is a no-op Option that swallows `.set()`; `Callback` likewise** (no real liblwjgl load).
- `net/runelite/rlawt/AWTContext.java`
- Pull with `git show gpu:ios/dev-tools/gpu-routec/facade-native/<path>`.

**2. `android/app/src/main/cpp/librlawt.cpp`** [NEW] — EGL context (M4 §1 recipe) + the ~98-entry `RegisterNatives` table (M2 §5) + the 5 shims (M2/M6) + the BGRA shim (port `routec_gl_jni.m:663-724` verbatim: `GL_BGRA→GL_RGBA`, `_8_8_8_8_REV→GL_UNSIGNED_BYTE`, per-texture `SWIZZLE_R=GL_BLUE`/`SWIZZLE_B=GL_RED`). Adapt the iOS `routec_gl_jni.m` structure (swap `RouteCBridge` ObjC → C).

**3. `android/app/src/main/cpp/CMakeLists.txt`** [EDIT] — add a second target linking EGL + GLESv3 (current file builds only `helloshell`, links `log android dl`, no EGL — verified):
```cmake
add_library(rlawt SHARED librlawt.cpp)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
target_link_libraries(rlawt ${log-lib} ${egl-lib} ${gles-lib})
```
The `.so` is **arm64-v8a only** (matching the single staged bionic JRE; `abiFilters += "arm64-v8a"` is the only ABI). Multi-ABI is gated on sourcing additional-ABI bionic JREs and is out of scope for v1 — drop the "per-ABI" framing.

**4. AG1a smoke activity** (emulator) — loads `rlawt`, calls `new AWTContext(null)` → `createGLContext()` → `GL.createCapabilities()`, logs `OpenGL33` + `glGetString(GL_VERSION)` + one `glClearColor`, and `GL33C.glCreateProgram()` returns non-zero, with no `UnsatisfiedLinkError`. md5-gate the logcat line. **Named failure modes to assert in the smoke checklist:** (a) no real-liblwjgl load via `Configuration`/`Callback` static-init; (b) EGL `eglMakeCurrent` succeeds; (c) `createProgram≠0`. **This proves facade link + EGL + RegisterNatives completeness — the 75% part. It does NOT run under JNI_CreateJavaVM's HotSpot render thread, does NOT compile a real shader, and does NOT touch the CACIO canvas — so it does not close AG1.**

**5. AG0 recon APK** (real device, parallel) — standalone Kotlin activity → EGL ctx → `glGetString`/`glGetIntegerv` for ES version, compute, `EXT_clip_control`, `GL_MAX_SAMPLES`, BGRA, depth/RGBA16UI renderability → logcat. Run via `android/dev-tools/firebase/fb-gate.sh recon` (Firebase Spark, 5 free runs/day). Settles GPU-truth independently of AG1.

**What GREEN looks like on the emulator (AG1a):** logcat shows `librlawt: EGL context live`, `OpenGL33=true`, `GL_VERSION: OpenGL ES 3.x`, `createProgram=<nonzero>`, no `UnsatisfiedLinkError`, no real-liblwjgl extract — md5 matches the committed baseline. **This is necessary, not sufficient.**

**What AG1-GREEN actually requires (device, AG1b):** the facade running inside the real `DeviceHeadlessBoot`/`JNI_CreateJavaVM` render thread; `GpuPlugin.startUp()` past `:317` against the real CACIO canvas (no deadlock, non-zero dims); the four base shaders compiling/linking/**validating** on a real Adreno/Mali driver; one frame composited. Run on BrowserStack (intent-extra + iterative debug). SwiftShader passes too leniently to trust.

**Non-blocking cleanup (not a gate):** the staged asset jar is already 1.12.29 and `MainActivity.kt` globs the client jar (`:775`) — there is **no** jar-name reconciliation to do. The only residual is the ~20 stale `ios/dev-tools/headless/run-*.sh` still naming 1.12.28; fix opportunistically. (The AG0 recon APK doesn't even load the client jar.)

---

## 8. Effort + risk summary

| Phase | Effort | Odds | Emulator / Device | Reuse | Blocks |
|---|---|---|---|---|---|
| **Cleanup: stale run-*.sh → 1.12.29** (non-blocking; jar already staged, MainActivity globs) | XS | n/a | either | — | nothing |
| **AG0** GLES recon | S | ~90% | **device** (Firebase, free) | [NEW-EGL] tiny | AG3 downport-skip |
| **AG1a** facade bind + empty program | M | ~75% | emu smoke → **device** | [REUSE-iOS] facade + [NEW-EGL] librlawt | AG1b |
| **AG1b** real shaders validate + 1 frame + CACIO canvas | **L** | **~50%** | **device** GREEN (BrowserStack) | [REUSE-iOS] + [NEW-EGL] | **entire GPU track** |
| **AG2** base `gpu` renders world | L | ~60%* | emu plumbing → **device** pixels | [NEW-EGL] GL wire + [REUSE-iOS] bridge + Hooks gate port | "real client look" |
| **AG2.5** RLHD loaders + OpenCL facade + licensing call | M | med | **device** | [REUSE-W2] + stub OpenCL | AG3 |
| **AG3** 117HD bring-up | **XL** | full ~20% / partial ~60% | **device only** | [NEW-EGL] facade-delta + [REUSE-iOS] downport | HD claim |
| **AG4** HD feature layers | XL | best-effort | **device** | [NEW-EGL] | — |
| **AG5** direct EGL present + teardown handshake + soak | M-L | medium (real design) | **device** | [NEW-EGL] seam + handshake | production GPU |

\* AG2's ~60% is *conditional on AG1* and shares AG1b's single unproven failure mode (real-driver shader validate). **Honest compound for "a real base-gpu frame renders on a real device" ≈ AG1a 0.75 × AG1b 0.50 × AG2-given-AG1 ≈ ~35–40%, NOT "probably yes."** The headline is: base GPU rendering is *plausible* (~35–40% to a real frame), not *probable*; the ~60% figure embeds the never-executed shader path and must not be read standalone.

**Memory budget (AG2/AG3, newly accounted):** the readback `int[]` pair (`bufA`/`bufB`) is on the **Java heap** — ~12.8 MB each at ~1200×2670 (~25.6 MB for the pair) competing inside `-Xmx512m` (`MainActivity.kt:822`). Off-heap GL driver memory (texture-array atlas, the BGRA interface texture sized `canvasW*canvasH*4`, FBOs/renderbuffers at panel res, ×8 MSAA RBO, the `width*height*4` PBO) is **not** in the 512 MB cap and is unbudgeted today; AG3's MSAA×8 + `DEPTH_COMPONENT32F` shadow + tiled-light FBOs multiply it. **The direct-EGL present (AG5) removes the `int[]` pair from the Java heap — that is a memory argument, not just a perf one, for prioritizing Phase 2.** Decide whether 512 MB needs raising once GPU textures are resident.

**Reuse split overall:** the Java facade + bridge methods + shader-downport recipe + GL census are **[REUSE-iOS]** (knowledge transfers; re-verify per RLHD revision and per real driver). The EGL context, RegisterNatives marshalling, BGRA shim, and present path are **[NEW-EGL]** (`librlawt.cpp` — Android replaces EAGL with `eglGetDisplay(EGL_DEFAULT_DISPLAY)`, no ANGLE selectors). The entire headless Java boot is **[REUSE-W2]**, unchanged.

**Expected outcome (honest):** base GPU rendering — *plausible, not probable* (~35–40% to a real frame; if it lands: sharper + faster, draw distance, AA, resolves HD↔FPS). Full 117HD — probably no (~20%). Partial 117HD on **vanilla textures** (HD lighting/shadows, the default plan) — plausible (~60%). The whole thing gates on AG1; red AG1 → the CPU shell (A0–A8, already shipped) is the product, and that is fine.

---

## OPEN QUESTIONS (close before funding the dependent phase)

- **[CRITICAL · close before AG1b green] Real-driver `glValidateProgram` on the base shaders.** Unproven on every mobile driver (iOS L1 never compiled a real shader; SwiftShader/EAGL are lenient). *Close by:* the pre-AG1b micro-gate — compile+link+validate the four base `vert/frag/vertui/fragui.glsl` (310-es downport) standalone in `librlawt` on a real Adreno/Mali device via BrowserStack; a validate throw is AG1b RED.
- **[CRITICAL · close before AG1b green] `eglMakeCurrent` on the HotSpot-attached render thread.** EAGL never exercised display/surface affinity (M4 rates this 60%). *Close by:* bring up EGL + `eglMakeCurrent` on the actual `jvm_bootstrap` render thread (not a fresh pthread) inside `DeviceHeadlessBoot`, on device, before declaring the facade path green.
- **[MAJOR · close before AG1b green] CACIO headless Canvas coupling.** `GpuPlugin.startUp()` calls `getCanvas()/getTreeLock()/isValid()/setIgnoreRepaint()` and sizes the context from canvas dims; the `new AWTContext(null)` smoke never tests them. *Close by:* run the unmodified `startUp()` against the real headless canvas; verify no tree-lock deadlock, non-zero dims (else context stuck at 512×512), benign `setIgnoreRepaint`; add a headless validate/resize if 0×0 pre-layout.
- **[MAJOR · close before AG3] OpenCL class-load.** `api(libs.lwjgl.opencl)` is a compile dep with no facade; RLHD Legacy gates on `OpenGL31 + OpenCL`. *Close by:* ship a stubbed `org/lwjgl/opencl/{CL,CLCapabilities}` returning unavailable, OR confirm Zone never touches `org.lwjgl.opencl` at class-init and scope out Legacy — run parallel to the GL census.
- **[MAJOR · close before AG3] RLHD revision re-pin + Zone/Legacy re-audit.** The iOS split is version-specific; RLHD is an external hub plugin that moves. *Close by:* clone the pinned RLHD revision you vendor and grep `OpenGL33`/`OpenGL43`/`GL_COMPUTE_SHADER` gates before wiring AG3.
- **[MAJOR · resolve out-of-band, do NOT schedule as an engineering gate] Asset-licensing call.** Bundling Jagex-derived RLHD assets in an *app* (vs a plugin) cannot be md5-greened and can stall AG3 indefinitely. *Resolved-by-default:* ship HD lighting/shadows on **vanilla textures** as the AG3 plan; vendoring RLHD's pack is an upgrade contingent on a separate legal call, not a blocker.
- **[MAJOR · close before AG5 Phase 2] Surface-teardown ordering.** `nativeSetSurface` releases the `ANativeWindow` before the render thread tears down the EGL window surface (use-after-free). *Close by:* implement the UI-thread→render-thread handshake (signal → `eglMakeCurrent(NO_SURFACE)`+`eglDestroySurface` → ack → release) and `EGL_CONTEXT_LOST`/`EGL_BAD_NATIVE_WINDOW` handling on `eglSwapBuffers`, before any window-surface present lands.
- **[MAJOR · close before relying on AG0/AG1 device runs] Firebase first-run unknowns.** Default device "shiba"/Pixel 8 must be confirmed arm64 + GLES 3.1+; `--other-files` push timing and the logcat results-bucket path are untested live. *Close by:* one Firebase `recon` run that asserts device ABI/ES tier and verifies the logcat pull path.
- **[MINOR · monitor] `-Xmx512m` vs resident GPU textures.** Off-heap GL allocations are unbudgeted. *Close by:* measure native + Java heap at AG2/AG3 on device; decide whether to raise the cap or prioritize the AG5 Phase-2 present (which frees the `int[]` pair).

**Load-bearing references (verified on disk this session):** `Hooks.java:559` (snapshot) before `:562` (`isGpu` return) · `JavaBridge.java` android has **0** `gpuPublish`/`gpuFrameBackBuffer` · `MainActivity.kt:775` globs `client-*-shaded.jar` (no `:451` literal) · `GpuPlugin.java:291-312` (`getCanvas`/`getTreeLock`/`isValid`/`setIgnoreRepaint`/`Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set` → `createCapabilities`), `:317` OpenGL33 gate · `build.gradle.kts:82` `api(libs.lwjgl.opencl)`, `:93-94` no `natives-android` · `android/app/build.gradle.kts:21` `abiFilters += "arm64-v8a"` (single ABI) · `jvm_bootstrap.cpp:724-737` swizzle, `:925-937` `ANativeWindow_release(old)` before `g_surfaceGen` bump · `CMakeLists.txt` target `helloshell`, links `log android dl`, no EGL · commit `76d12169e` (A7, merged at HEAD, zero seam touch) · `gpu:.../RouteCL1Probe.java` (createCapabilities + glCreateProgram only — no shader compile/validate) · `gpu:.../routec_gl_jni.m:663-724` (BGRA swizzle recipe) · `gpu:.../facade-native/` (14 files, GLALL = 189 native decls).
