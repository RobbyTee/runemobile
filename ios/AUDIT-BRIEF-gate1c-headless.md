# Audit brief — Gate 1c: headless RuneLite architecture (adversarial review)

**For:** an independent reviewer. **Goal:** try to **refute** the architecture we're about to invest weeks in
— booting **real RuneLite headless** (no Swing `ClientUI`), keeping `PluginManager` + overlays, and capturing
`mainBufferProvider.getImage()` to PNG on **desktop OpenJDK 21**. Default to skepticism; for any unproven
claim, name the **cheapest experiment** that settles it, and flag any cheaper/safer architecture we're missing.
This is the long pole (Claim 2 of the prior brief); a wrong cut here costs the most.

## Context (what's settled — don't re-litigate)
Goal: a self-contained native iOS app running real RuneLite on an on-device JIT OpenJDK 21, plugins unmodified,
live OSRS, Jagex-Mobile-style UI. **Already proven (this repo):**
- **Gate 1a/1b** (`dev-tools/cacio-smoke/`): RuneLite's Java2D overlay draw ops render to a buffer with **no
  window**, on plain headless OpenJDK 21 **and** under the live CACIO CTC toolkit; cacio-tta 1.18 runs on JDK 21.
  *Caveat (load-bearing):* desktop `BufferedImage` `Graphics2D` + `sun.font.CFontManager` are **toolkit-independent**
  — so the desktop test validates overlay-code portability + headless-forkability, **not** the iOS native font/2D
  rasterizer (that rides to Phase 3 / device). See `FINDINGS.md`.
- **Stock RuneLite on desktop OpenJDK 21** (`dev-tools/run-desktop-reference.sh`): builds (Gradle 8.8), the
  `injected-client:1.12.28-SNAPSHOT` gamepack resolves from `repo.runelite.net`, and it boots to the **live-OSRS
  login screen** with the full plugin set + GPU plugin + frame shown. JDK-21 flags documented.
- Perf premise (Rung 0), ToS/ban risk (accepted, throwaway account) — settled in `FINDINGS.md` / `REALJVM-DEV-PLAN.md`.

The repo is a fork of `runelite/runelite`; client at `runelite-client/src/main/java/net/runelite/client/`.

## The proposed architecture (what to attack)
Bypass the Swing `ClientUI` and drive RuneLite's existing render seam ourselves:
- **Boot** via the real Guice injector (`RuneLite.start`, `Guice.createInjector(new RuneLiteModule(...))`
  `RuneLite.java:261`), but replace `ClientUI` with a **headless `ClientUI`** that does no `JFrame`/tray/
  `GraphicsEnvironment` work and instead exposes the buffer.
- **Capture seam:** `Hooks.draw(MainBufferProvider, Graphics, x, y)` (`callback/Hooks.java:388`) already composites
  game + every overlay into `mainBufferProvider.getImage()` and only blits at `:477`. We read `getImage()` (or its
  `int[]` via `runelite-api` `BufferProvider.getPixels()`) → PNG. No window touched.
- **EDT:** use a **real** dispatch thread (the prior audit's correction), supplied by the AWT environment.
- **AWT environment (desktop):** candidates — (a) plain `headless=true`; (b) `headless=false` + native toolkit
  (we have a display); (c) `headless=false` + CACIO CTC (the iOS-shaped virtual display). Which is the honest
  minimum for *desktop* 1c is an open decision (see Claim 2).

### Decomposition (the plan we want challenged)
- **1c-i (no gamepack, no login):** prove the **overlay pipeline** runs headless — `OverlayManager` +
  `OverlayRenderer.renderOverlayLayer(g2d, layer)` drawing 2–3 real overlay plugins onto a `BufferedImage` we own,
  with a headless `ClientUI` + real EDT, **against a mocked/stub `Client`**. No throwaway account, no network.
- **1c-ii (real client):** layer in real gamepack load (`ClientLoader`) + **live Jagex login**, capture a real
  in-game composited frame headless.

## Claims to refute / confirm

### Claim A — a headless `ClientUI` is a small, well-bounded surface
`ClientUI` is a **concrete, non-final class** injected by type (`RuneLite.java:142`, decl `ui/ClientUI.java:132`),
so it can be subclassed or rebound. The hot path touches only: `paintOverlays(Graphics2D)` (`:1012`),
`getGraphicsConfiguration()` (`:1044`), `getCanvasOffset()` (`:992`), `requestFocus()` (`:866`), plus lifecycle
`init()` (`:322`) / `show()` (`:627`). `Hooks.draw` calls `clientUi.paintOverlays` + `clientUi.getGraphicsConfiguration`
(`callback/Hooks.java:402/416`).
- **Refute:** Is overriding/replacing just these enough, or do other call sites (`Notifier` focus/taskbar
  `Notifier.java:175`, `OverlayRenderer` cursor `:405`, resize/focus events, `getCanvasOffset` consumers) force us
  to reimplement materially more of `ClientUI`? Enumerate the *core* (non-optional) `clientUi.*` callers reachable
  before/at first frame.
- **Swap mechanism:** subclass-and-override `ClientUI` vs extract an interface vs a Guice rebind to `HeadlessClientUI`
  — which is least invasive given the concrete-type `@Inject` and `injector.injectMembers(client)` (`RuneLite.java:297`)?

### Claim B — the AWT environment for *desktop* 1c
Plugin start needs a **real** dispatch thread: `PluginManager.startPlugins` uses `SwingUtilities.invokeAndWait`
(`plugins/PluginManager.java:233`) and `startPlugin`/`stopPlugin` assert `isEventDispatchThread()` (`:405/:469`).
- **Refute:** Can we run desktop 1c under **plain `headless=true`** (BufferedImage rendering works — Gate 1a proved
  it; a headless EDT exists) — or does booting RuneLite hit a `HeadlessException` first (e.g. the gamepack `Canvas`,
  `createCustomCursor`, `GraphicsConfiguration`, `VolatileImage` at `Hooks.java:430`)? If headless=true breaks, is the
  honest desktop minimum the **native toolkit** (we have a display; simplest) or **CACIO CTC** (proves the no-window
  path that mirrors iOS)? Pick one and justify — and say whether 1c *must* use CACIO to be a valid iOS proxy, given
  the Gate-1a finding that BufferedImage/2D are toolkit-independent anyway.

### Claim C — 1c-i (mocked Client) is a meaningful isolation, not a vacuous test
We intend to exercise `OverlayManager` + `OverlayRenderer` against a **mocked `Client`** with no gamepack.
- **Refute:** Does `OverlayRenderer.renderOverlayLayer` (and the 2–3 chosen overlay plugins) transit so much live
  `Client`/`Widget`/scene state that a no-gamepack render is either impossible to set up or proves nothing about the
  real path? Or is there a clean subset of overlays (e.g. an always-on-top info overlay) that renders against a
  trivially-mocked `Client`? If 1c-i is vacuous, say so and propose the minimal real-state harness instead.

### Claim D — 1c-ii: real gamepack + live login work headless
Stock boot reaches `LOGIN_SCREEN` *with* a `JFrame` (verified). The gamepack is an `Applet` loaded via
`ClientLoader` (`rs/ClientLoader.java`) + `RSAppletStub`; it renders in software to `BufferProvider`.
- **Refute:** Does the gamepack `Applet`/`Canvas` require a real native peer / displayable `Canvas` to initialize and
  pump frames, or does an offscreen surface suffice (the miniJVM spike suggests software render needs no native peer)?
  Does login (Jagex OAuth / OTL) touch any Swing UI that a headless build removes? Name what breaks first.

## Also worth a skeptical pass
- **Render drive:** with no window, who calls `Hooks.draw`? Does the gamepack engine thread invoke the draw
  callback on its own once initialized, or is it gated on AWT paint events we'd have to synthesize?
- **`-ea`:** the EDT `assert`s only fire with assertions enabled — are we relying on them, or are they incidental?
- **Fork hygiene:** keep changes under a headless entrypoint / minimal `ClientUI` swap so `git merge upstream/master`
  stays clean (all our work isolated). Is subclassing or a parallel `main` the lower-merge-conflict path?

## What a useful audit output looks like
For each claim: **REFUTED / SUPPORTED / UNPROVEN**, the evidence (file:line), and — if unproven — the **single
cheapest experiment** that settles it. Plus: a recommended **desktop AWT environment** for 1c (headless=true vs
native vs CACIO) with rationale; a **1c-i/1c-ii go/no-go** (is the isolation worth it?); and the **least-invasive
`ClientUI` swap** mechanism. Concrete file:line corrections welcome.

## Key references (all in this repo unless noted)
- Boot/seams: `RuneLite.java` (`:261` injector, `:297` injectMembers, `:341/364` clientUI init/show),
  `RuneLiteModule.java:152` (`provideClient` → `clientLoader.get()`), `callback/Hooks.java` (`:388` draw, `:477` blit),
  `ui/ClientUI.java` (`:132` decl, `:322` init, `:1012` paintOverlays, `:1044` getGraphicsConfiguration),
  `plugins/PluginManager.java` (`:233` invokeAndWait, `:405/469` EDT asserts),
  `ui/overlay/OverlayRenderer.java`, `ui/overlay/OverlayManager.java`, `rs/ClientLoader.java` + `rs/RSAppletStub.java`,
  `runelite-api/.../BufferProvider.java`.
- Proven groundwork: `dev-tools/cacio-smoke/` (Gate 1a/1b), `dev-tools/run-desktop-reference.sh` (stock RL on 21),
  `FINDINGS.md`, `REALJVM-DEV-PLAN.md` (gate order), `AUDIT-BRIEF-platform-feasibility.md` (the prior verdict).
