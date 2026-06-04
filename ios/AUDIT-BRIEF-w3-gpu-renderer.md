# Audit brief — GPU rendering on iOS (base `gpu` plugin, then 117HD/RLHD)

> Self-contained brief for an independent reviewer. Goal: pressure-test the feasibility and the de-risking
> ladder for bringing hardware (GPU) scene rendering to this on-device iOS RuneLite before any code is written.
> Be adversarial: flag wrong assumptions, the cheapest experiment that would refute each claim, and the single
> most-likely-fatal blocker. We would rather kill this early than sink weeks into a dead end.

## Why we want this

The app runs **real, unmodified RuneLite** on an on-device JIT **OpenJDK 21** (iPhone 17 Pro Max, iOS 26.5),
rendering through the **CACIO CTC** software toolkit — i.e. the 3D scene is **software-rasterized on the CPU**,
no GPU. Consequences:

- **HD ↔ FPS is a hard trade.** We expose a render-resolution dial (`renderH`, currently 720). Raising it sharpens
  the image but linearly increases CPU raster cost; 900 already cut FPS noticeably. There is no way to get both
  high resolution and high frame rate on a software rasterizer.
- A **GPU path would resolve that tension** (the GPU does the per-pixel work) and additionally unlock 117HD/RLHD's
  lighting/shadows/water/AA. This is the highest-ceiling visual improvement left for the project.

Target order: **(1) the base RuneLite `gpu` plugin** (simpler), then **(2) 117HD/RLHD** (much heavier). 117HD builds
on the same context/native infra as `gpu`, so `gpu` is the correct first milestone.

## Current architecture (context the reviewer needs)

- **Process / JIT:** single iOS app (`dev.audaro.helloshell`); HotSpot brought up in-process via
  `JNI_CreateJavaVM`; JIT enabled per-launch by attaching **StikDebug** (services HotSpot's code-cache mirror
  `brk`). A `§6.2` setup clears the task `EXC_BAD_ACCESS` Mach port so HotSpot's own null-check/safepoint faults
  work. The JIT-mirror/code-cache region is sensitive — disturbing it has caused crashes historically.
- **Toolkit / rendering:** non-headless AWT via **Caciocavallo CTC** + a fake `libawt_xawt` (reexports
  `libawt_headless` + stubs the absent headful `initIDs`). The gamepack draws into a CTC `BufferedImage`-backed
  `Canvas`; **there is no native window/surface** — CTC is windowless.
- **Display path:** `net.runelite.client.headless.JavaBridge.snapshot(mainBufferProvider)` is called at the
  post-overlay-composite hook in `Hooks.draw`; the native shell pulls `getFramePixels()` (a `0xAARRGGBB int[]`)
  + dynamic `getFrameWidth/Height` each `CADisplayLink` tick → `CGImage` → a `UIImageView` (ScaleAspectFit),
  skipping the rebuild when `frameSeq` is unchanged. So the whole display contract today is **"hand us an
  `int[]` framebuffer."**
- **Resolution:** set once at launch via `-Drunelite.headless.resize=WxH` → a gated `HeadlessResize` resizes the
  CTC `Component` on LOGGED_IN. The OSRS account must be in **resizable display mode**.
- **Native libs that DO load:** the patched `jre21` dylibs + our `libawt_xawt` (re-signed ad-hoc, plain
  `dlopen`). **Native libs that do NOT load:** anything compiled for macOS — they fail
  `dlopen … incompatible platform, have 'macOS' need 'iOS'`. This is the wall that blocks every native-lib
  plugin today (117HD `librlawt`, JNA `libjnidispatch`).

## What the GPU plugins actually require (verified in-tree)

`runelite-client/src/main/java/net/runelite/client/plugins/gpu/` (base `gpu` plugin) needs:

1. **`net.runelite.rlawt.AWTContext`** (`librlawt`) — a small native lib that creates a GL context bound to the
   **AWT Canvas's native window peer** (CGL/NSOpenGL on macOS, WGL/GLX elsewhere) and handles
   swap/makeCurrent. `GpuPlugin.java:79` imports it.
2. **LWJGL** — `org.lwjgl.opengl.*`. Build pulls `lwjgl.core` + `lwjgl.opengl` + `lwjgl.opencl` and the
   **`natives-<platform>`** classifiers (macOS binaries in our build): `build.gradle.kts:80-94`.
3. **Desktop OpenGL 3.3 core + select 4.x:** imports include `GL33C.*`, `GL42C.glTexStorage3D`,
   `GL43C` debug output, `GL45C.glClipControl`. No compute shaders in the base plugin.
4. **117HD/RLHD additionally** (external hub plugin, https://github.com/117HD/RLHD): a **compute-shader**
   pipeline (model sorting/processing) → **GL 4.3 / GL ES 3.1 compute**, plus heavier GLSL (lighting, shadow
   maps, water, post). Historically constrained on macOS because Apple's desktop GL caps at 4.1 (no compute).

## The three stacked blockers

- **B1 — native binaries.** `librlawt` + LWJGL natives are macOS dylibs → won't `dlopen` on iOS arm64. Must be
  **rebuilt for iOS arm64** (LWJGL) and **replaced** (`librlawt` — see B3).
- **B2 — no desktop OpenGL on iOS.** iOS offers only **OpenGL ES** (deprecated but present) and **Metal**. The
  plugins call desktop GL 3.3–4.5 with desktop GLSL. Either **down-port** the GL calls + shaders to **GL ES 3.1**
  (which has compute, so 117HD's pipeline is theoretically reachable) or run them through a **GL→Metal
  translation layer** (ANGLE-style, GL ES front-end). Some 117HD features may not survive a clean down-port.
- **B3 — headless, no window.** `librlawt` attaches a context to a Canvas's native peer; CTC has none. We need an
  **offscreen** context (EAGL/GL ES or a Metal-backed FBO) and either `glReadPixels` back into the existing
  `int[]` blit, or present the GPU surface directly to the screen (which would bypass software raster entirely —
  a larger restructure but the bigger win).

## Prior art

**PojavLauncher / Amethyst** (our documented reference stack; Amethyst is the iOS Minecraft launcher we already
use to validate JIT) **already run LWJGL + OpenGL Minecraft on iOS** via a GL→GLES→Metal translation layer
(gl4es / ANGLE / "MobileGlues"-class). So "LWJGL OpenGL Java on iOS" is a solved problem in the abstract. Caveats
the reviewer should weigh: Minecraft renders to a **real GLFM/GLFW window** (we are headless), and its GL is
**older/simpler** than 117HD's GL 4.3 compute pipeline (the base `gpu` plugin's GL 3.3 core is closer to what
that translation layer already handles).

## Claims to validate / refute

- **Claim A — the base `gpu` plugin is a meaningfully easier first target than 117HD.** (GL 3.3 core, no compute,
  smaller shaders vs. 117HD's 4.3 compute + heavy shaders.) If A is false, there's no cheap stepping stone.
- **Claim B — an offscreen context feeding the existing `int[]` blit is viable** (render to FBO →
  `glReadPixels` → `getFramePixels`), so we do NOT have to restructure the whole display path to present a
  GPU surface directly for a first cut. Refuting datum: `glReadPixels` round-trip cost dominates / FBO offscreen
  unsupported in the chosen GL layer.
- **Claim C — the PojavLauncher/Amethyst iOS GL layer exposes a GL tier the plugin can target** (≥ GL ES 3.0 for
  `gpu`, ≥ GL ES 3.1 + compute for 117HD). Refuting datum: it only exposes what Minecraft needs and caps below
  3.3-core-equivalent / has no compute.
- **Claim D — the JIT/StikDebug setup tolerates a GPU driver + LWJGL JNI in-process** (JNI→GL is ordinary native
  calls; the GL driver lives outside the JIT mirror). Refuting datum: the GL/Metal driver allocates executable
  memory or installs fault handlers that collide with the `§6.2` mirror/exception-port setup.
- **Claim E — LWJGL can be rebuilt for iOS arm64** and loaded via our re-sign + plain-`dlopen` path (same as the
  jre dylibs / `libawt_xawt`). Refuting datum: LWJGL's iOS support is absent/immature, or its JNI registration
  assumes a desktop layout.
- **Claim F — `AWTContext`/`librlawt` can be replaced by an iOS offscreen-context shim** without touching the
  unmodified plugin Java. Refuting datum: the plugin reaches through `AWTContext` for window-peer specifics that
  have no offscreen analogue.

## Proposed de-risk ladder (cheapest-correct first)

0. **Reconnaissance (no build):** confirm the GL tier Amethyst's translation layer exposes on THIS device
   (`glGetString(GL_VERSION/RENDERER/SHADING_LANGUAGE_VERSION)`, `GL_MAX_COMPUTE_*`). Settles Claim C and tells
   us whether `gpu` (3.3) and/or 117HD (4.3 compute) are even reachable.
1. **Offscreen-context probe (small):** from our on-device JVM, load an iOS-built LWJGL, create an **offscreen**
   GL ES / ANGLE context with **no window**, `glGetString`, compile one trivial vertex+fragment shader, render a
   triangle to an FBO, `glReadPixels` it back, assert non-blank. Settles B3 + Claims B, D, E minimally.
2. **Base `gpu` plugin bring-up (large):** iOS `librlawt` shim → offscreen context; down-port `gpu`'s GL/GLSL to
   the available GL ES tier; route its output into the `int[]` blit. Milestone: the live scene renders via GPU,
   FPS + sharpness both up. Settles Claims A, B, F end-to-end.
3. **117HD/RLHD (largest):** the compute pipeline + heavy shaders on top of the proven base. Expect partial
   feature coverage; identify which 117HD features the GL ES/Metal tier can't support.

## What breaks first (predictions to confirm)

- Step 0 shows the GL layer caps below GL 3.3-core-equivalent / no compute → 117HD is out, `gpu` maybe out.
- Step 1: no windowless/offscreen context in the GL layer; or `glReadPixels` is prohibitively slow at 765×503+.
- Step 2: the down-port of desktop-GL semantics (UBO layout, `glClipControl` Z range, `glMultiDrawElements`,
  texture-array `glTexStorage3D`) to GL ES diverges enough to mis-render; or `AWTContext` assumptions leak.
- Step 3: 117HD compute shaders unsupported / too slow; macOS-style 4.1 fallbacks don't map to iOS.

## Scope / expectations

This is a **multi-week-to-months research port, not a feature toggle**, with a real chance of only **partial**
success on 117HD specifically. The base `gpu` plugin is the legitimate, high-value first target and on its own
would resolve the HD↔FPS trade. Sequence this **after** the full-screen-mode work; the two are independent large
efforts. Recommend running step 0 + step 1 as a strict go/no-go gate before committing to step 2.

## Key files / pointers

- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/` — base plugin; `GpuPlugin.java`
  (`AWTContext`, LWJGL GL33C/42/43/45), `Shader.java`, `VBO.java`, `VAO.java`, `TextureManager.java`.
- `runelite-client/build.gradle.kts:80-94` — LWJGL deps + `natives-<platform>`.
- `net.runelite.client.headless.JavaBridge` — the `int[]` blit contract (`snapshot`/`getFramePixels`/`frameSeq`).
- iOS shell: `ios/app/HelloShell/JVMLauncher.m` (VM bringup, `§6.2` mirror/exception-port setup, dylib loading),
  `ViewController.m` (`CADisplayLink` blit, `renderH`).
- Reference: Amethyst-iOS (GPLv3, patterns only) iOS LWJGL + GL translation stack.
