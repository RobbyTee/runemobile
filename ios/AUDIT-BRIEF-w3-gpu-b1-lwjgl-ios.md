# Audit brief — GPU B1 part 2: LWJGL ↔ iOS EAGL GLES binding (the GL-stack bring-up)

> Self-contained brief for an independent reviewer. The shader half of B1 is **GREEN** (see
> `ios/dev-tools/gpu-b1/B1-RESULT.md`: RuneLite's real `gpu`-plugin shaders downport to GLSL ES 3.00 and
> compile + link + validate + render on a native GLES 3.0 driver — no ANGLE needed). This brief covers the
> **other half of B1**: getting the **Java GL binding the plugin uses (`org.lwjgl.opengl`) to drive iOS's EAGL
> GLES 3.0 context** from our in-process on-device JVM. Be adversarial: attack the cheapest-path assumption,
> name the single most-likely-fatal blocker, and the cheapest experiment that refutes each claim. We would rather
> kill the LWJGL route early than sink weeks into it.

## Context (what's already settled)

- **B0 (device):** native EAGL = **GLES 3.0 / GLSL ES 3.00, no compute**; offscreen FBO + `glReadPixels` works.
  Base `gpu` (GL 3.3 core, no compute) is reachable on native GLES; 117HD (needs 3.1 + compute) is not, without
  a GL→Metal layer. (`ios/app/evidence/diag/hello-shell-glrecon.log`.)
- **B1 part 1 (Simulator, GREEN):** the actual plugin shaders run on GLES 3.00 after a documented 5-class
  downport. So the **shaders are not the blocker.**
- **Process:** real RuneLite runs in-process on an on-device JIT OpenJDK 21 via `JNI_CreateJavaVM`; JIT serviced
  by StikDebug. Native libs load only if built for iOS arm64 (macOS dylibs fail `dlopen … incompatible platform`).

## The seam the plugin actually uses (verified in-tree)

`GpuPlugin.java` imports **desktop** LWJGL OpenGL, not GLES:

```
import org.lwjgl.opengl.GL;            // desktop GL loader  (GL.createCapabilities / GLCapabilities)
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;        // debug callback
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.BufferUtils; import org.lwjgl.system.{Callback,Configuration};
// + Shader.java: import static org.lwjgl.opengl.GL33C.*;
```

`GpuPlugin.startUp()` does: `AWTContext.ensureCapabilities` → context current → **`GL.createCapabilities()`**
→ `GLCapabilities` drives every `GLxxC.*` call. Two iOS-relevant facts:

1. **LWJGL ships no iOS binaries** (only `natives-{windows,linux,macos}-*`, `build.gradle.kts:80-94`). The plugin's
   GL calls are resolved at runtime by a **`FunctionProvider`** that LWJGL gets from the context (GLFW/WGL/GLX/EGL
   on the desktop platforms). iOS has **none of those** — it has **EAGL** (`EAGLContext`, no GLFW), which is a
   GLES context. So the binding question is: *can a desktop-`org.lwjgl.opengl` `GLCapabilities` be initialised
   against an EAGL GLES 3.0 context?*
2. **The plugin's desktop-only GL entry points** (census): `glMultiDrawArrays` ×4 (the scene draw), `glClipControl`
   (GL45), `glDebugMessageControl` (GL43). **None are in GLES 3.0 core** (`glMultiDrawArrays` has no ES core entry;
   `glClipControl` has no ES analogue — but ES default NDC-z is already `[-1,1]`, so a `NEGATIVE_ONE_TO_ONE` call
   is a no-op; debug output is optional). `glTexStorage3D` (GL42) **is** core ES 3.0. These are mostly a **B2**
   concern, but they shape the binding: whatever provides the GL symbols must either expose these or let us shim
   them.

## Prior art

PojavLauncher/Amethyst run **LWJGL3 OpenGL Minecraft on iOS** via a **GL→GLES→Metal** translation layer
(gl4es / ANGLE / "MobileGlues"-class) plus LWJGL iOS natives and a custom context — so "LWJGL OpenGL on iOS" is
solved in the abstract. Caveats: Minecraft renders to a **real GLFM/GLFW window** (we are **headless/offscreen**),
and its GL is older/simpler. Our memory note on Amethyst (`reference_amethyst_ios.md`) is explicit that Amethyst
is our reference for **JIT/sideload, NOT the renderer** — so the GL-binding specifics must be re-derived, not
assumed. (GPLv3: read for patterns, do not vendor.)

## The three candidate routes (the core decision)

**Route A — desktop-`org.lwjgl.opengl` + custom EAGL FunctionProvider.** Build LWJGL **core** (`liblwjgl`) for iOS
arm64; supply a `FunctionProvider` that `dlsym`s GL symbols from `OpenGLES.framework` (or a translation layer's
`libGLESv2`); make an `EAGLContext` current; call `GL.createCapabilities()`. The overlapping GLES-3.0 symbol set
resolves; desktop-only symbols (`glMultiDrawArrays`, `glClipControl`, debug) resolve to NULL → must be guarded/shimmed.
*Keeps the plugin's imports unmodified.* Risk: `GL.createCapabilities()` may hard-require symbols/extension strings
a GLES driver doesn't present; calling convention / `Configuration` assumptions.

**Route B — port the plugin to `org.lwjgl.opengles` (GLES30).** LWJGL has a separate GLES module whose
`FunctionProvider` is EGL-based. iOS uses **EAGL, not EGL**, so even this needs an EAGL shim — and it **changes the
unmodified plugin** (`GL33C` → `GLES30`), breaking the project's "unmodified RuneLite" guarantee unless done as a
maintained iOS plugin variant. Upside: GLES bindings match the driver's actual surface (fewer NULL symbols).

**Route C — skip LWJGL entirely: a thin hand-written JNI GL bridge.** The plugin makes a **bounded** set of GL
calls (~40 distinct entry points). Expose exactly those as a small iOS-arm64 JNI lib over `OpenGLES.framework`,
behind a **shim `org.lwjgl.opengl.GL33C`/`GL`/`GLCapabilities` facade** (so the plugin still compiles unmodified)
or behind a maintained variant. This sidesteps the entire LWJGL native port (no `liblwjgl`/dyncall/libffi build)
and lets us shim `glMultiDrawArrays`→loop, `glClipControl`→no-op, debug→no-op at the bridge. Risk: re-implementing
enough of LWJGL's buffer/`MemoryUtil` surface; long tail of entry points.

The reviewer's highest-value call: **is the LWJGL native port (Route A/B) worth it, or is a thin JNI bridge
(Route C) the cheaper-correct path given the plugin's bounded GL surface and our already-proven shader downport?**

## Claims to validate / refute

- **L1 — `org.lwjgl.opengl.GL.createCapabilities()` can initialise against an EAGL GLES 3.0 context** via a custom
  FunctionProvider (Route A). *Cheapest refutation:* on the **Simulator**, from a tiny JVM (or even a JNI smoke),
  feed LWJGL a FunctionProvider that `dlsym`s from `OpenGLES.framework`, make an EAGLContext current, call
  `createCapabilities()`, assert `caps.OpenGL30 == true` and a core call (`glCreateProgram`) returns non-zero.
- **L2 — `liblwjgl` (core) builds for iOS arm64** and loads via our re-sign + plain-`dlopen` path (Routes A/B).
  *Refute:* LWJGL core's dyncall/libffi/JNI layer has no iOS arm64 target or assumes a desktop layout.
- **L3 — a thin JNI GL bridge (Route C) covers the plugin's whole GL surface** behind an unmodified-plugin facade,
  for less work than the LWJGL native port. *Refute:* enumerate the plugin's GL/`MemoryUtil`/`BufferUtils` surface
  and show the facade is as large as porting LWJGL.
- **L4 — the EAGL offscreen context replaces `AWTContext`/`librlawt` cleanly** (B3 from the GPU brief): the plugin
  reaches `AWTContext` only for context create / makeCurrent / swap, all of which have offscreen EAGL analogues
  (no on-screen swap needed — we `glReadPixels` into the `int[]` blit). *Refute:* `AWTContext` exposes
  window-peer specifics with no offscreen analogue. (This is a B2 seam but the binding choice depends on it.)
- **L5 — the desktop-only calls are shimmable, not fatal:** `glMultiDrawArrays`→N×`glDrawArrays`,
  `glClipControl(NEGATIVE_ONE_TO_ONE)`→no-op (GLES default), debug output→no-op. *Refute:* the plugin depends on
  `glMultiDrawArrays` semantics (per-draw `first/count` arrays) in a way a loop changes (state between draws), or
  `glClipControl` is called with `ZERO_TO_ONE`.

## Cheapest-correct de-risk ladder (proposed)

0. **(done) Shader gate** — GREEN.
1. **L1 Simulator smoke (small, decisive for Route A):** custom FunctionProvider over `OpenGLES.framework` →
   `GL.createCapabilities()` against an EAGLContext → assert `OpenGL30` + a live core call. If this is clean, the
   desktop-GL-binding route is alive and the rest is symbol-shimming. If it can't init, Route A is likely dead →
   Route C.
2. **L3 surface census:** enumerate every distinct `GLxxC.*` / `MemoryUtil` / `BufferUtils` call in the plugin →
   size Route C's facade precisely. (Mostly mechanical from the source.)
3. **L2 (only if Route A/B chosen):** build `liblwjgl` core for iOS arm64; load it in our shell; run the L1 smoke
   in-process on the **device**.
4. **Pick the route**, then B1 part-2 milestone: from the on-device JVM, init the binding, compile the **already-
   proven** shaders through it, hand-draw a triangle to an offscreen FBO, `glReadPixels` non-blank. That closes B1
   ("real gpu shaders compile + scene-shaped offscreen render via the chosen Java GL binding on iOS").

## What breaks first (predictions)

- L1: `GL.createCapabilities()` rejects the GLES driver (missing desktop extension strings / version probe), or the
  FunctionProvider can't resolve enough symbols → Route A dead, fall to Route C.
- L2: `liblwjgl` core won't cross-compile for iOS (dyncall/libffi) → both LWJGL routes get expensive → Route C wins.
- L4: `AWTContext` is reached for more than create/current/swap → the offscreen shim leaks into plugin Java.
- L5: `glMultiDrawArrays`→loop changes a per-draw assumption (unlikely; the plugin uses it as a batched draw).

## Constraints / setup

- **Simulator first** (this Mac's Metal-backed GLES, the B1 proxy); final confirm on the **device** (primary
  machine). Flag Simulator-specific GL quirks.
- Dedicated **`gpu` branch/worktree**; the GPU work needs the gitignored local state (jre symlink, `jvm-resources`,
  `tokens.json`) per `ios/dev-tools/unpack-local-state.sh` only once it touches the JVM (the L1 smoke can run
  Simulator-only without it; the device step needs it).
- Reference Amethyst (GPLv3, **patterns only, do not vendor**): `git clone --depth 1
  https://github.com/AngelAuraMC/Amethyst-iOS` — for LWJGL iOS natives + GL context patterns.

## Key files / pointers

- Plugin GL seam: `runelite-client/.../plugins/gpu/GpuPlugin.java` (imports, `GL.createCapabilities`,
  `AWTContext`), `Shader.java` (`org.lwjgl.opengl.GL33C.*`, `glShaderSource`/compile path).
- LWJGL deps + natives: `runelite-client/build.gradle.kts:80-94`.
- Shader gate (done): `ios/dev-tools/gpu-b1/` (`B1-RESULT.md`, `app/B1Downport.m`, `app/B1Probe.m`,
  `gpu-b1-verdict.sh`, `evidence/`).
- The `int[]` publish contract B2 routes into: `net.runelite.client.headless.JavaBridge`
  (`snapshot`/`getFramePixels`/`frameSeq`); GPU early-return `Hooks.java:501`.
- iOS shell (device step): `ios/app/HelloShell/JVMLauncher.m` (VM bringup, dylib load), `GLRecon.m` (EAGL recon).

---

## Review outcome (folded in 2026-06-05) — Route A RED, go Route C

External review verdict: **Route A leans RED**; the fatal blocker is not shaders but
**`GL.createCapabilities()` producing a `GLCapabilities` the unmodified plugin accepts as `OpenGL33`**. All
citations verified against current source. Corrections, with the wrong original text struck:

1. **L1 pass bar was too weak (was `OpenGL30`).** The plugin gates on **`if (!glCapabilities.OpenGL33)`**
   (`GpuPlugin.java:317`) immediately after `GL.createCapabilities()` (`:312`) and **aborts** otherwise. LWJGL's
   `OpenGL33` flag requires the context to report **≥ 3.3 AND** all desktop 3.3 core functions present. A native
   **GLES 3.0** context reports `GL_MAJOR_VERSION=3, GL_MINOR_VERSION=0` → `OpenGL33 = false` → plugin aborts.
   Forcing it true needs *both* lying about the version *and* shimming every desktop-only function — i.e. **Route C
   plus a fragile compatibility lie**, strictly worse than Route C. ⇒ **Route A red.** Revised L1 (if run at all):
   assert **`GL.createCapabilities().OpenGL33`** (not `OpenGL30`), capture LWJGL missing-function reports, then
   execute the plugin's real startup calls (`glClearDepth`, `glMapBuffer`, `glMultiDrawArrays`,
   `glClipControl(GL_ZERO_TO_ONE)`). This is the formal kill-test, best run **on the device** (the Simulator can't
   load our iOS-triple JVM, and LWJGL needs a JVM + a real GLES context together).

2. **~~`glClipControl(NEGATIVE_ONE_TO_ONE)` → no-op~~ — WRONG.** The plugin calls
   **`glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`** (`GpuPlugin.java:358`) with **reverse-Z** state:
   `glDepthFunc(GL_GREATER)` (`:973`), `glClearDepth(0d)` (`:987`/`:993`). GLES NDC-z is fixed `[-1,1]` with no
   `glClipControl` analogue, so reverse-Z `[0,1]` is **a real depth/projection port + render test**, not a no-op
   (**L5 partially false**). This is a Route-C design item, not a free shim.

3. **Route A FunctionProvider needs wrapper symbols, not raw `dlsym(OpenGLES.framework)`.** Apple's OpenGLES SDK
   exports `glClearDepthf` (float), **not** desktop `glClearDepth` (double); and lacks `glClipControl`,
   `glMultiDrawArrays`, `glDebugMessageControl`. Aliasing `glClearDepth`→`glClearDepthf` is an ABI/signature
   mismatch — every desktop-only symbol needs a typed wrapper, which is Route C work anyway.

4. **~~Route C is "~40 calls"~~ — UNDERCOUNT.** Verified census: **86 distinct `gl*` methods** in the plugin
   package (one, `glDpiAwareViewport`, is a RuneLite helper → **85 GL calls**) + a tiny buffer surface
   (`BufferUtils.createIntBuffer` ×3, direct `ByteBuffer`/`IntBuffer`/`FloatBuffer`, `Configuration`,
   `GLUtil.setupDebugMessageCallback`). Still bounded and **still cheaper than an iOS LWJGL core port + a desktop-GL
   lie.** Only **5 of the 85 are not GLES 3.0 core**; the other ~80 map 1:1:

   | desktop-only call | GLES 3.0 disposition |
   | --- | --- |
   | `glClearDepth` (double) | → `glClearDepthf` (float) — signature shim |
   | `glMapBuffer` | → `glMapBufferRange` (full range) — ES 3.0 is range-map only (plugin already uses `glMapBufferRange`/`glFlushMappedBufferRange`/`glUnmapBuffer`) |
   | `glMultiDrawArrays` | → loop of `glDrawArrays` (per-draw `first[]/count[]`; verify no inter-draw state assumption) |
   | `glDebugMessageControl` + `GLUtil.setupDebugMessageCallback` | → no-op (KHR_debug optional) |
   | `glClipControl(GL_ZERO_TO_ONE)` + reverse-Z | **render decision** (item 2) — port depth/projection to ES `[-1,1]`, render-test |

5. **L4 — `AWTContext`/`ClientUI` surface is more than create/current/swap** (verified): `getFramebuffer(false)`
   (×4: `:766/:870/:1028/:1460`, the default-FBO the offscreen shim must supply), `getBufferMode` (`:1543`,
   `glReadBuffer` target), `setSwapInterval` (`:542`), `swapBuffers` (`:1430`), and **`clientUI.getGraphicsConfiguration()`**
   DPI transforms (×5: `:728/:1037/:1493/:1535/:2070`, feeding `glDpiAwareViewport`). The iOS offscreen shim must
   answer all of these (framebuffer 0 = our FBO; swap = no-op or `glReadPixels`→blit; DPI = our render scale).

### Decision

**Kill Route A. Proceed Route C** — a thin iOS-arm64 JNI GL bridge over the 85-call surface behind **local facade
classes** standing in for the plugin's `org.lwjgl.opengl` imports (so "unmodified plugin" = unmodified *plugin
source*, satisfied by our `org.lwjgl.opengl.GL33C`/`GL`/`GLCapabilities`/`AWTContext` facades on the iOS classpath).
This avoids the LWJGL core port (no `liblwjgl`/dyncall/libffi-on-iOS), avoids the desktop-GL version lie, and lets
us own the 5 shims + the reverse-Z decision at the bridge. **L2 (build `liblwjgl` for iOS) is dropped** unless a
revised on-device L1 unexpectedly shows `OpenGL33` satisfiable.

### Revised next experiments (Route C)

1. **Facade-compile smoke (desktop, cheap):** create local `org.lwjgl.opengl.GL33C`/`GL`/`GLCapabilities` +
   `net.runelite.rlawt.AWTContext` facade stubs on an iOS-only source set; confirm `GpuPlugin`/`Shader` compile
   unmodified against them (sizes the facade, finds any missed symbol).
2. **iOS JNI GL bridge (device):** implement the 85 calls over `OpenGLES.framework` (the 5 shims as above), an
   offscreen `AWTContext` analogue (EAGL ES3 context + our FBO as "framebuffer 0"), feed the **already-proven**
   downported shaders through `Shader.compile`, render the plugin's real scene pass → `glReadPixels` → the existing
   `int[]` blit. Resolve the reverse-Z/depth decision here with a render test.
3. **Formal Route-A kill-test (optional, device):** the revised L1 asserting `OpenGL33` — only if we want the
   empirical refutation on record before committing to Route C.

External reference (review): Khronos OpenGL ES Registry (https://registry.khronos.org/OpenGL/index_es.php).
