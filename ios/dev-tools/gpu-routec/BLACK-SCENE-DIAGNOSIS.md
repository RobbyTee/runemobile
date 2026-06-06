# GPU Route C — black-3D-scene blocker: diagnosis

> ## ✅ RESOLVED 2026-06-05 — it was the undismissed post-login welcome screen (NOT a GPU/bridge gate)
>
> **Reproduced AND resolved on desktop, no device round.** A desktop harness (`ios/dev-tools/headless/
> run-gpu-handshake.sh`) boots real RuneLite under the same CACIO CTC as the device and arms `GpuHandshakeProbe`,
> which replays `GpuPlugin.startUp`'s engine handshake (`setGpuFlags(GPU|ZBUF)` + a **no-GL** counting
> `DrawCallbacks`) so `isGpu()==true` with **no GL context at all**. It reproduced the device signature exactly:
> `drawScene=0`, `viewport=0x0`, `topLevelIf=165`, `loadScene/swapScene=1`, `draw1` climbing, every per-frame
> zone callback `0` — **on desktop, with no iOS bridge, no EAGL, no GL surface.** That alone **falsifies the
> "offscreen-FBO-not-tied-to-canvas / iOS-bridge-specific" leading hypothesis below.**
>
> Then the real cause: the captured frame at that state is the **"Welcome to Gielinor / Click here to play"**
> screen — `LOGGED_IN` but *not in the world*. `HeadlessLogin` only clicks "Play Now" on the `LOGIN_SCREEN`,
> then stops; **nothing dismissed the post-login welcome screen.** Clicking the real `WelcomeScreen.PLAY` widget
> (`InterfaceID` 378) flips it instantly, in BOTH modes:
> ```
> BEFORE (welcome up):  viewport=0x0    topLevelIf=165  drawScene=0     preSceneDraw=0
> AFTER  (in world):    viewport=765x503 topLevelIf=164  drawScene≈50/s  preSceneDraw/drawZoneOpaque/drawZoneAlpha/
>                                                                        drawPass/postSceneDraw all firing ~50/s
> ```
> CPU mode then renders the full 3D world; GPU mode fires **every** gpu-plugin scene callback (the no-GL desktop
> handshake leaves the viewport black because the composite is a no-op — on the device's real EAGL bridge those
> same callbacks render the scene). Evidence: `evidence/desktop-welcome-screen-resolution-20260605.log`,
> `evidence/desktop-repro-cpu-inworld-20260605.png` (full world), `evidence/desktop-repro-gpu-inworld-
> blackviewport-20260605.png` (in-world HUD, callbacks firing, no-GL black viewport).
>
> **So `viewport=0x0` / `topLevelIf=165` was the welcome-screen state, NOT a red herring and NOT GPU-intrinsic.**
> The device's black-scene run reported that *identical* `viewport=0x0 / topLevelIf=165`, so the device was on the
> welcome screen too. The multi-round "GPU-mode-intrinsic render-gap / opaque-dispatch gate" conclusion in the
> rest of this doc was a **misdiagnosis** — it assumed (never tested) that the client had entered the world and
> that CPU mode rendered the scene here; both are false in this harness until the welcome screen is dismissed.
> The runtime stack (now captured) shows the scene render IS reached normally once in-world:
> `tf.run → tf.ahu → client.ia → client.jz → gc.ak(scene) → … → ev.dk → preSceneDraw/drawScene`.
>
> **FIX (shipped this branch):** `WelcomePlayProbe` (`runelite-client/.../headless/WelcomePlayProbe.java`, gated
> `-Drunelite.headless.welcomeplay=true`) clicks `WelcomeScreen.PLAY`'s real bounds until the welcome screen is
> gone; armed on the device autologin path in `JVMLauncher.m`. **Device round: rebuild + run GPU — the gpu plugin
> should now drive the real EAGL bridge to render the 3D world.** If the scene is *still* black on device once the
> callbacks fire, that is a NEW, downstream bridge-render issue — but the per-frame dispatch blocker is solved.
>
> ---
> *Everything below is the original (now-superseded) diagnosis, kept for the investigation trail.*

Branch `gpu`. The JNI GL bridge works end-to-end (registration, EAGL ES3 context, OpenGL33=true, shaders
compile/link, UI renders + composites correctly, readback/color/flip correct, C3 thread identity confirmed).
The 3D scene is black. This doc records the device-verified diagnosis so the work isn't re-tread.

## Verdict: it is a RENDER-GAP, not the bridge, not the viewport

Device evidence (iPhone 17 Pro Max, `-Drunelite.gpudiag=true`, `GpuSceneDiag` + `TracingDrawCallbacks`):

```
state=LOGGED_IN isGpu=true drawCallbacks=…GpuPlugin (AppClassLoader) resized=true topLevelIf=165 real=1304x600
callbacks[loadScene=1 loadSceneWV=1 swapScene=1 despawnWV=1 draw1=15247]   ← drawScene/preSceneDraw/drawZone*/postSceneDraw = 0
```

- **GPU scene IS uploaded** — `DrawCallbacks.loadScene` + `swapScene` fire once at login (geometry on the GPU).
- **Per-frame zone render is NEVER dispatched** — `preSceneDraw / drawZoneOpaque / drawZoneAlpha / drawPass /
  postSceneDraw / drawScene(DDDDDI)` fire **zero** times; only the composite `draw(int)` runs (~60 fps), so it
  blits an empty scene FBO → black 3D, correct UI.
- **GPU-mode-intrinsic** — the same scene renders fine in CPU/software mode (the predecessor and our own CPU runs).

### Ruled out (do not re-pursue)
- **The "viewport=0×0 gates the scene render" theory — FALSIFIED.** `getViewportWidth()==0` is *normal* for this
  resizable headless setup: the CPU run rendered the full scene with `viewport=0x0` for every reading. And in the
  injected `ev.dk`, the viewport getters are read only when `!isGpu`. Viewport is a red herring.
- Headless resize / resizable layout — booting GPU with no `headless.resize` (canvas stays fixed 765×503) is still
  black. `HeadlessResize` exonerated. Layout is healthy: `resized=true topLevelIf=165 real=1304x600 widgetRoots=2`.
- Registration / classloader / startUp — `isGpu()==true`, `getDrawCallbacks()` is the real `GpuPlugin` on the
  normal `AppClassLoader`, startUp succeeds (no "Error starting GPU plugin"), canvas `displayable=true`.
- Render threads — `GpuPlugin` doesn't set `RENDER_THREADS` even on desktop; `rl23` workers only process queued
  zone tasks and are downstream of the (un-called) scene render.

## Engine call-graph (from CFR-decompiled gamepack)

Per-frame scene render: `scene.ak(…) → ev.mm(…) → ev.ic(true,…) → DrawCallbacks.preSceneDraw … drawZoneOpaque …
postSceneDraw`. (A second, dead path `client.oy → ev.dk → ev.ic` exists but `oy` has no callers.) In `ev.dk`/
`ev.mm` the ZBUF branch is a clean `if ((client.ly & 0x10) != 0) { ic(true,…); gq(…); zd(null); getCallbacks().
drawScene(); }` — it **would** fire the zone callbacks if the method were called. `ev.dk` has no early return
before that branch, and our `Hooks.drawScene` counter (fired at the end of `ic`/`dk`/`mm`) is **0** ⇒ the scene
render method is simply **never invoked** per-frame in GPU mode (in CPU mode it is).

**Static analysis is exhausted:** the scene-render entry (`em.ak` / `ev.mm` / `ee.av`) is invoked through the
obfuscator's computed/opaque dispatch and has no statically-resolvable caller, even fully decompiled. All 19
`isGpu()` sites sit *inside* the scene-render methods, not on the decision to call them. So the gate that skips the
per-frame scene render in GPU mode can't be found by reading code.

## Leading hypothesis

On desktop, rlawt binds the GL context to the real AWT canvas and the engine drives the GPU scene render through
that canvas/surface pass; our Route C bridge uses an **offscreen FBO not tied to the canvas**, and our headless
loop only exercises the raster/`BufferProvider` path (which is why CPU works). So the GPU scene-render pass is
never triggered. Unconfirmed — see next step.

## Decisive next step: reproduce on DESKTOP headless

W2's whole premise is desktop-first (same JVM family). Run full RuneLite under our `HeadlessClientUI`/CACIO on
desktop OpenJDK with a tracing/dummy `DrawCallbacks` (à la the predecessor's `GpuProbeCallbacks`) + `setGpuFlags(GPU|
ZBUF)`, and log whether `preSceneDraw` fires:
- **If `preSceneDraw=0` on desktop too** → it's the HEADLESS/CACIO setup (not iOS/bridge). Reproducible on desktop
  → attach a real debugger (jdb/IntelliJ), break where the scene render is dispatched, read the gate directly. No
  device rounds.
- **If it renders on desktop-headless** → the gap is iOS/bridge-specific (the offscreen-FBO-not-on-canvas
  hypothesis) → focus on tying the bridge context to the canvas / driving the GPU scene pass.

## Diagnostics wired (gated `-Drunelite.gpudiag=true`, armed in `routecGpuMode`)
- `runelite-client/.../headless/GpuSceneDiag.java` — per-second state + drawScene/drawTick counters.
- `runelite-client/.../headless/TracingDrawCallbacks.java` — faithful delegating `DrawCallbacks` that counts every
  engine callback (safe: installed before the login scene-load so `rl4.yb==client.bt` swapScene dispatch holds).
- Two-finger long-press launch button = GPU fixed-mode (no `headless.resize`) A/B; ▶ Run GPU button = GPU + resize.
- CFR decompile recipe: `java -jar cfr.jar <injected-classes>.jar --outputdir <dir>`; strip the giant `@Named`
  obfuscator strings with `awk '{if(length($0)<1500)print}'`.
