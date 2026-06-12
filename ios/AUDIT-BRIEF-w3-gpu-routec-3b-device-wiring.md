# Audit brief — GPU Route C step 3b: on-device JNI wiring + the GPU publish seam (B2)

> Self-contained brief for an independent, adversarial reviewer. Everything **static** in Route C is GREEN
> (the unmodified base `gpu` plugin compiles against the local facade — step 1 & 3b; shaders downport + render on
> native GLES 3.00 — B1; the EAGL ES3 context + depth + reverse-Z + the 5 shims work on a real GLES 3.0 driver —
> step 2; the whole GL surface exists + the JNI bridge compiles for iOS arm64 — step 3a/3b). What remains is
> **JVM-coupled, device-only**: wire the bridge into the running on-device JVM so the real `gpu` plugin renders
> the scene on the GPU and we display it. This brief proposes the wiring and asks the reviewer to **attack it
> before we build** — name the single most-likely-fatal flaw and the cheapest experiment that refutes each claim.
> We would rather kill a wrong design here than sink a device round into it.

## 0. The four things that have to be wired (handoff)

1. Build the client jar with the **device facade** (`facade-native/`, GL/`AWTContext` methods turned `native`)
   swapped in for LWJGL + librlawt — an **iOS-only variant** that must **not** regress the desktop/Android build.
2. Compile the bridge into the shell: `app/RouteCBridge.m` + `jni/routec_gl_jni.m` (links `OpenGLES.framework`).
3. After `JNI_CreateJavaVM`, call `RouteC_registerGLNatives(env)` once (before the gpu plugin starts); stand up
   the EAGL context at the render resolution.
4. Enable the gpu plugin; it renders the real `DrawCallbacks` scene into the EAGL FBO; the shell `glReadPixels`
   → publishes to the existing live-blit display path. **The publish seam (B2) is the subtle part.**

## 1. Context verified in-tree (file:line, current `gpu` branch — all re-checked, no stale cites)

- **All LWJGL/rlawt use is confined to the 6-file `gpu` plugin package** (`GpuPlugin`, `Shader`, `TextureManager`,
  `Zone`, `VBO`, `VAO`). Nothing else in `runelite-client` imports `org.lwjgl` or `net.runelite.rlawt` (verified
  by grep over `runelite-client/src/main/java`). So swapping these out touches only the gpu plugin's classes.
- **The real-build gpu bytecode references only `GL33C`/`GL42C`/`GL43C`/`GL45C`** (199/1/2/1 method refs;
  GL constants are compile-time-inlined literals, no field refs) — i.e. exactly the facade's classes. javac
  records the *qualifying* class of a static import, not the (real-LWJGL) declaring class (`GL13C`…), so there
  are **no references to non-facade GL classes**. (Checked via `javap -c` on the staged shaded jar.)
- **The shaded jar** is `./gradlew :client:assemble` → `runelite-client/build/libs/client-1.12.28-SNAPSHOT-shaded.jar`
  → copied to `ios/app/jvm-resources/`. The `shadowJar` task (`runelite-client/build.gradle.kts:105-127`) just
  unions `sourceSets.main.output` + the flattened `runtimeClasspath` (which includes `org/lwjgl/**` from
  `lwjgl-core/opengl/opencl` and `net/runelite/rlawt/**` from `rl.awt`).
- **Device boot:** `JVMLauncher.m:879` `callMain(env, "DeviceHeadlessBoot", …)` →
  `ios/dev-tools/awtsmoke/DeviceHeadlessBoot.java` (in `probes.jar`) → `RuneLiteHeadless.main` → `RuneLite.main`.
  With `-Drunelite.hub=true` (set at `JVMLauncher.m:742`) `DeviceHeadlessBoot.java:36` drops `--safe-mode`, so
  **the gpu plugin is already enabled today** — it just fails at `startUp()` because librlawt/LWJGL are macOS
  dylibs that can't `dlopen` on iOS, and the engine falls back to the software (CTC) blit. Route C replaces
  exactly those native deps.
- **The gpu plugin's GL bring-up runs on the client thread:** `GpuPlugin.startUp()` (`GpuPlugin.java:275`) does
  `canvas = client.getCanvas()` (:291) → `new AWTContext(canvas)` (:300) → `configurePixelFormat` (:301) →
  **`awtContext.createGLContext()` (:304)**. The per-frame draw (`GpuPlugin.java:~1412`) ends with
  **`awtContext.swapBuffers()` (:1430)** then `drawManager.processDrawComplete(...)` (:1458). All GL calls —
  startUp shader compile, every draw, swapBuffers — are on the engine/client thread.
- **DPI is satisfied headless:** `clientUI` injects `HeadlessClientUI extends ClientUI`
  (`HeadlessClientUI.java:74`), which overrides `getGraphicsConfiguration()` (:156) → the headless frame's GC.
  Under CTC that transform is identity, so `glDpiAwareViewport` (`GpuPlugin.java:2068`) ≈ a plain `glViewport`.
- **The display path** (the live blit we must feed): `JavaBridge.snapshot(MainBufferProvider)`
  (`JavaBridge.java:207`) double-buffers the engine's composited CPU `int[]` (`0x00RRGGBB`) and bumps
  `frameSeq`. The native side (`JVMLauncher.m:~905` `latestFrameImage`) polls `getFrameSeq/Width/Height/Pixels`,
  `GetIntArrayRegion`s the `int[]`, builds a `CGImage` (`kCGImageAlphaNoneSkipFirst | byteOrder32Little`) →
  `UIImage` → shown each `CADisplayLink` tick. **In GPU mode the CPU snapshot at `Hooks.java:559` captures only
  the UI layer; `Hooks.draw` then returns early at `Hooks.java:562-566` (`if (client.isGpu()) return;`) — the
  composited GPU frame is finished *later*, by the plugin. That early-return is the B2 seam.**

## 2. Proposed design

### 2A. Classpath swap — jar surgery in an iOS-only staging script (no shared-build change)

A new script `ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh` post-processes the **normal** desktop shaded jar
into the iOS variant placed at `ios/app/jvm-resources/…-shaded.jar` (the exact name the app bundles). Desktop +
Android keep using `build/libs/…` untouched. Steps, md5-gated:

1. `javac` the facade-native + facade support classes (`facade-native/**` + `facade/{GLCapabilities,Callback,
   Configuration,BufferUtils,PointerBuffer,EXTTextureFilterAnisotropic,GLUtil,GL33C/42C/43C/45C}` — note
   facade-native already carries its own GL/AWTContext/GL.java) → `facade_out/`.
2. **Recompile the 15 delombok'd gpu sources** (`build/generated/sources/delombok/.../gpu/*.java`) against
   `facade_out` + (shaded-jar-minus-lwjgl-minus-rlawt) → `gpu_out/`. (Same recipe as `routec-facade-verdict.sh`,
   already GREEN.) **Recompiling — not reusing the real-build gpu bytecode — is deliberate**: it guarantees the
   bytecode resolves against facade-native classes I control, removing any inheritance/overload-resolution doubt.
3. Repackage: unzip the shaded jar; `rm -rf org/lwjgl net/runelite/rlawt net/runelite/client/plugins/gpu`;
   copy in `facade_out/**` + `gpu_out/**`; rezip. Result: exactly **one** provider of each `org.lwjgl.*` /
   `rlawt.AWTContext` / `gpu.*` class, all facade-backed.

Rationale for jar-surgery over a gradle iOS source-set/variant: purely additive under `ios/`, zero risk to the
shared build, deterministic + md5-gateable, matches the project convention. The facade covers exactly the gpu
plugin's referenced surface (proven step 1/3b), and the gpu package is the only LWJGL user, so stripping the
whole `org/lwjgl/**` + `rlawt/**` tree is safe (lazy class-loading: unreferenced stale classes never load).

### 2B. Bridge compiled in + registered (handoff steps 2–3)

- `project.yml`: add `RouteCBridge.m` + `routec_gl_jni.m` to the HelloShell sources; link `OpenGLES.framework`.
- `JVMLauncher.m`: immediately after `JNI_CreateJavaVM` succeeds (before `callMain`), call
  `RouteC_registerGLNatives(env)`. It `FindClass`es `org/lwjgl/opengl/GLALL`, `net/runelite/rlawt/AWTContext`,
  `org/lwjgl/opengl/GL` and `RegisterNatives` the three tables. These classes resolve from the swapped jar (2A).

### 2C. EAGL context on the client thread (correction to the handoff's literal "shell pre-creates")

EAGL contexts are **thread-affine** (`[EAGLContext setCurrentContext:]` binds the *calling* thread; GL calls use
the current context of their own thread). The plugin's GL calls are all on the **client thread**. Therefore the
context must be created + made current on the client thread — **not** pre-created by the shell on another thread.

**Design:** make `AWTContext.createGLContext()` (native `rc_AWT_createGLContext_0`, today a no-op) actually call
`[RouteCBridge awtCreateGLContextWidth:height:]`, sized from the live canvas. Since it runs on the client thread
(`GpuPlugin.java:304`), the context is current on the right thread for every subsequent call. The size is the
engine's canvas: add `JavaBridge.canvasWidth()/canvasHeight()` (reading `client.getCanvasWidth/Height()`) and
have the native shim query them (fallback to a default if 0). On canvas resize the FBO must be recreated — detect
a size change in `getFramebuffer`/`swapBuffers` and rebuild (rare on device; canvas is fixed at the resizeSpec).

### 2D. The GPU publish seam (B2)

Route the EAGL FBO into the **existing** display path so `latestFrameImage` is unchanged. In the native
`swapBuffers` shim (`rc_AWT_swapBuffers_0`, client thread, context current, FBO complete):

1. `glReadPixels(0,0,w,h, GL_BGRA, GL_UNSIGNED_BYTE, buf)` — on little-endian arm64 each 32-bit word is
   `0xAARRGGBB`; the display `CGImage` uses `kCGImageAlphaNoneSkipFirst` so the alpha byte is ignored → matches
   the CPU path's `0x00RRGGBB` exactly. **Vertical flip** (GL origin is bottom-left; the image is top-left) by
   copying rows in reverse.
2. Publish into `JavaBridge`'s frame double-buffer + bump `frameSeq`. To avoid a wasteful native→Java→native
   round-trip of a ~2.8 MB array each frame, add `JavaBridge.gpuFrameBackBuffer(w,h):int[]` (returns the next
   back buffer, allocating/resizing as needed) and `JavaBridge.gpuPublish(w,h)` (swap + `frameSeq++`); the shim
   pins the returned array (`GetPrimitiveArrayCritical`), writes the flipped pixels straight in, releases, then
   calls `gpuPublish`. `latestFrameImage` then displays it with **no code change**.

`client.isGpu()` is true once the plugin's `startUp` succeeds, so the CPU snapshot at `Hooks.java:559` still runs
(harmless — it publishes the UI-only buffer) and `Hooks.draw` returns at :566; the *authoritative* frame is the
one `swapBuffers` publishes a few ms later, with a higher `frameSeq`, so the display shows the GPU composite.

MSAA (deferred, non-blocking): if the plugin's AA is on it adds an MSAA FBO via `glRenderbufferStorageMultisample`
+ `glBlitFramebuffer` (both ES3 core, in the verified 81). The step-2 FBO is single-sample; add the resolve when
wiring AA. For first light, force AA off.

### 2E. Shaders

The plugin's `Shader.compile` path hits our `glShaderSource` native. Downport the GLSL ES at `glShaderSource`
time by reusing `gpu-b1/app/B1Downport` (the 5 mechanical edits: `#version 300 es`, precision, drop
`noperspective`, strip `f` suffixes, int→float/ivec→vec casts) inside `rc_GL_glShaderSource_0` before
`glShaderSource`. (B1 proved these compile + link + render on GLES 3.00.)

## 3. The cheapest-first L1 kill-test (built first, ships this round)

Before the full plugin wiring, a self-contained device probe that fails fast iff the bridge can't satisfy the
plugin's hard gate. `RouteCL1Probe.main` (in a tiny jar = facade-native + the probe), booted via a
`-Drunelite.routec.l1=true` switch in the shell instead of `DeviceHeadlessBoot`:

```
AWTContext ctx = new AWTContext(null); ctx.createGLContext();   // EAGL ES3 current on THIS thread
GLCapabilities caps = GL.createCapabilities();                  // facade native
assert caps.OpenGL33;                                           // the plugin's gate (GpuPlugin.java:317)
int prog = GL33C.glCreateProgram(); assert prog != 0;           // a real core call returns non-zero
GL33C.glClearDepth(0.0); GL45C.glClipControl(GL45C.GL_LOWER_LEFT, GL45C.GL_ZERO_TO_ONE);  // shims don't throw
// print "ROUTEC_L1 OpenGL33=… createProgram=… glError=… VERDICT=PASS/FAIL" to jvm-stdio.log
```

If `OpenGL33` is false or a core call faults, Route C's premise is dead → stop before the full build. md5-gate the
verdict against the `devicectl`-pulled `jvm-stdio.log`. This shares the exact registration + EAGL-on-client-thread
wiring the full path needs, so a green L1 de-risks 2B + 2C directly.

## 4. Claims to validate / refute (attack these)

- **C1 — recompiling the gpu package against facade-native produces a runnable jar.** *Refute:* a class/member
  the real-build referenced isn't in facade-native (overload picked differently), or a stripped `org/lwjgl/**`
  class is loaded at runtime by something outside the gpu package.
- **C2 — `GL.createCapabilities()` through the facade yields `OpenGL33==true` on a live device EAGL ES3 context**
  (it's a hand-built `GLCapabilities`, not LWJGL's probe, so this should be trivially true — but L1 confirms the
  whole native-registration + FindClass + field-set chain on device). *Refute:* `RegisterNatives` fails (name/
  descriptor mismatch), or `GLCapabilities` field IDs don't resolve.
- **C3 — the EAGL context made current in `createGLContext` on the client thread stays valid for every later GL
  call on that thread** (startUp + all draws + swapBuffers are the same thread). *Refute:* RuneLite runs the gpu
  plugin's draw on a *different* thread than `startUp` (then the context isn't current there → all GL calls fail).
  **This is the claim I most want killed cheaply.** Cheapest check: log `pthread_self()`/Thread.currentThread in
  `createGLContext` vs the first `swapBuffers`.
- **C4 — `swapBuffers` `glReadPixels`(GL_BGRA, flipped) → the JavaBridge int[] → `latestFrameImage` shows the GPU
  composite**, and the higher-`frameSeq` GPU frame wins over the same-frame CPU UI-only snapshot. *Refute:* a
  same-`frameSeq` race makes the display flip between the UI-only CPU frame and the GPU frame (tearing/flashing),
  or `getFramebuffer(false)` isn't the FBO that holds the final composite at `swapBuffers` time.
- **C5 — `getFramebuffer(false)` returning our single offscreen FBO is the right "default framebuffer"** the
  plugin composites the scene+UI into before `swapBuffers`. *Refute:* the plugin renders the final image to FBO 0
  (the real window) via a path that bypasses `getFramebuffer`, so our FBO never receives the composite.
- **C6 — the FBO sized to `client.getCanvasWidth/Height()` matches the plugin's `glViewport`/interface-texture
  size** (`prepareInterfaceTexture` uses the same canvas dims, `GpuPlugin.java:1357`). *Refute:* a DPI scale > 1
  makes `glDpiAwareViewport` exceed the FBO (clipping), i.e. the headless GC transform isn't identity.

## 5. What breaks first (my predictions, ranked)

1. **C3 / threading** — if the gpu draw runs off the startUp thread, the whole "context on the client thread"
   design needs a rework (make the context current at the top of each draw, or pin the plugin to one thread).
2. **C4 / publish race** — CPU-UI vs GPU frame alternating on `frameSeq`; may need to suppress the CPU snapshot
   when `isGpu` (skip `JavaBridge.snapshot` if GPU is live) so only the GPU frame publishes.
3. **C5/C6 / FBO-as-default-framebuffer + size** — subtle but step-2 already proved the FBO is complete and
   reverse-Z works; the risk is the plugin's multi-FBO bookkeeping, not the EAGL FBO itself.
4. **A long-tail `TODO(device)` marshalling** (`glBufferData` size by capacity×4 vs `remaining()`,
   `glMapBuffer` size) producing a wrong buffer size → corrupt geometry, not a crash. Refine per handoff step 5
   (compute address/size in the Java facade, pass primitives).

## 6. Key files

- Bridge: `ios/dev-tools/gpu-routec/app/RouteCBridge.{h,m}`, `jni/routec_gl_jni.m`, `gen/routec_jni_gen.py`,
  `facade-native/**`, `facade/**`, `used-descriptors.txt`, `bridge-manifest.md`, `README.md`.
- Shell: `ios/app/HelloShell/JVMLauncher.m` (VM create + `callMain`; `latestFrameImage` ~:905), `project.yml`.
- Java seams: `JavaBridge.java` (:207 snapshot, :248-275 getters), `Hooks.java` (:557-566 the GPU seam),
  `GpuPlugin.java` (:275 startUp, :304 createGLContext, :1430 swapBuffers, :766/:1028/:1460 getFramebuffer),
  `HeadlessClientUI.java` (:156 getGraphicsConfiguration), `DeviceHeadlessBoot.java` (:36 safe-mode).
- Shader downport: `ios/dev-tools/gpu-b1/app/B1Downport.{h,m}`.

---

## Review outcome (folded in 2026-06-05) — design GREEN with two mandatory B2 corrections

External adversarial review (all citations re-verified against the current tree). **Section 1 facts: VERIFIED**
(two cosmetic slips: the native symbol is `+latestFrame`, not `latestFrameImage`; GPU `draw` does not "end" at
`swapBuffers` :1430 — it continues to `processDrawComplete` :1458 and **rebinds `getFramebuffer(false)` at :1460**,
which is load-bearing for C5 because it leaves our FBO bound at the top of the next `draw`). Verdicts:

- **C5 VERIFIED (decisive).** The plugin renders the scene into a *separate* `fboScene`, then `draw(int)`
  composites into the default FB = `getFramebuffer(false)`: clear (:1418) → `blitSceneFbo` (:1044-1046, fboScene→
  default) → `drawUi` (:1426) → `swapBuffers` (:1430). **Every `glBindFramebuffer` in the plugin targets only
  `fboScene` or `getFramebuffer(false)` — there is no bind to literal FBO 0.** So a `glReadPixels` of our single
  offscreen FBO after `swapBuffers` yields the complete composite. RuneLite's own `screenshot()` (:1523-1544) does
  exactly this readback (with `GL_RGBA`), confirming the seam.
- **C6 VERIFIED (bytecode-proven).** CTC's `CTCGraphicsConfiguration.getDefaultTransform()` returns the identity
  `AffineTransform` (scale 1.0), so `glDpiAwareViewport` collapses to a plain `glViewport` at the canvas size =
  the FBO size. *Required follow-through:* the bridge hard-codes the FBO size at create (`RouteCBridge.m:17-45`),
  so a runtime canvas resize (`GpuPlugin.java:369`) must trigger FBO recreation (the 2C size-change path).
- **C2 VERIFIED** (modulo device): `GLCapabilities` is hand-built with `OpenGL33=JNI_TRUE`; the only risk is the
  `RegisterNatives`/field-ID chain, which L1 confirms on device.
- **C1 leans VERIFIED** for compile; residual is runtime reflection/service-loading of a stripped `org/lwjgl/**`
  class. *Harden 2A:* after rezip, assert `jar tf | grep -c '^org/lwjgl/'` equals exactly the facade class count
  (no survivors) and grep `META-INF/services` for any provider pointing at a removed class.

- **C3 (threading) — SAME THREAD by RuneLite's engine contract, but NOT statically closeable in this repo, and
  the brief over-claimed.** Correction: `GpuPlugin.startUp()` runs on the **EDT** (`PluginManager` asserts EDT at
  :405/:429); it **defers the GL bring-up to the client thread via `clientThread.invoke(...)` (`GpuPlugin.java:282`)**.
  `DrawCallbacks.draw(int)` (which calls `swapBuffers` :1430) also runs on the client thread. So `createGLContext`
  and the draws ARE the same (client) thread — *provided* the gamepack invokes `DrawCallbacks` on the client
  thread, which is empirically true (desktop works) but lives in the obfuscated gamepack (absent here). **The L1
  probe runs on its own `main` thread and therefore does NOT prove full-path thread affinity** — the brief's
  "green L1 de-risks 2C directly" is too strong on exactly this point. **MITIGATION (adopt from the start): make
  the EAGL context current per-frame, unconditionally** — `[EAGLContext setCurrentContext:sCtx]` at the top of
  `getFramebuffer`/each draw is nearly free and removes C3 as a risk entirely, regardless of which thread `draw`
  runs on. Also: on the first full-path run, log `pthread_self()` in `createGLContext` vs the first `swapBuffers`
  and md5-gate that they're equal.

- **C4 (publish seam) — REFUTED twice; both fixes are mandatory for first light:**
  1. **`GL_BGRA` is NOT a valid `glReadPixels` format on iOS GLES 3.0.** ES3 core readback is `GL_RGBA`/
     `GL_UNSIGNED_BYTE` (the plugin's own `screenshot()` reads `GL_RGBA` :1544). A raw `GL_RGBA` readback on
     little-endian gives the word `0xAABBGGRR` (R and B swapped vs the display's `0x00RRGGBB`), so the scene would
     render **blue/red-swapped**. **Fix: `glReadPixels(GL_RGBA)` + swizzle R↔B during the existing reverse-row
     (vertical-flip) copy** — one pass, since we already touch every row for the flip.
  2. **The flash race is real; "higher frameSeq wins" only holds amortized.** Within a frame the CPU UI-only
     snapshot publishes (seq N) then the GPU composite publishes (seq N+1); a `CADisplayLink` tick landing between
     them shows the UI-only frame (transparent scene) for one tick → flicker. **Fix (promote §5.2 to primary):
     suppress `JavaBridge.snapshot` when `client.isGpu()`** so the GPU shim is the *sole* `frameSeq` publisher.
     In GPU mode the CPU `mainBufferProvider` never holds the scene, so the CPU snapshot is always the wrong thing
     to display; single-publisher also closes the 4-non-atomic-JNI-read tear window in `+latestFrame`.
     Concretely: gate `Hooks.java:557` behind `&& !client.isGpu()`.

**Net design changes for the full wiring (2C/2D/2A):** (a) per-frame `setCurrentContext` unconditionally;
(b) FBO recreation on canvas-size change; (c) readback `GL_RGBA` + R↔B swizzle in the flip; (d) suppress the CPU
`JavaBridge.snapshot` when `isGpu()` (single publisher) — gate `Hooks.java:557`; (e) post-jar-surgery assert no
`org/lwjgl/**` survivors. **L1 is unaffected** (it tests the EAGL+register+caps+core-call chain on one thread and
is the correct cheapest gate before any of the above is built).
</content>
