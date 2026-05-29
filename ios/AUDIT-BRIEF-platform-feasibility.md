# Audit brief — platform feasibility & gate sequencing (adversarial review)

**For:** an independent reviewer. **Goal:** try to **refute** the load-bearing platform assumptions in
`ios/REALJVM-DEV-PLAN.md` *before* we invest weeks. Default to skepticism; if a claim is unproven, say so and
say what cheap test would settle it. **This is NOT a review of the strategy** (pivot off miniJVM to a real JVM —
already proven: `ios/dev-tools/jvmbench/RESULTS.md`, and desktop RuneLite is a live existence proof). It is a
review of **HOW**, specifically the three things that are uncertain and expensive-if-wrong.

## Context (what's settled — don't re-litigate)
Goal: a **self-contained native iOS app** running **real RuneLite** on an **on-device JIT OpenJDK 21**, plugins
unmodified, live OSRS, Jagex-Mobile-style UI. Settled: miniJVM is the bottleneck (45–184× slower than OpenJDK,
measured); C-port/GraalVM are dead ends; the ToS/ban risk is accepted (personal build, throwaway account). Read
`ios/REALJVM-DEV-PLAN.md`, `ios/FINDINGS.md` for full background. The repo is a fork of `runelite/runelite`; the
client is at `runelite-client/src/main/java/net/runelite/client/`.

---

## Claim 1 (HIGHEST RISK) — AWT-on-iOS via Caciocavallo actually satisfies what OSRS + RuneLite overlays need
**The plan asserts:** Caciocavallo (CACIO) + a fake-xawt shim gives a working headless AWT on iOS, so the
engine's `Canvas`/`BufferProvider` and RuneLite's `Graphics2D` overlay pipeline work without a real display —
"like Amethyst does for Minecraft."

**Why this is the soft spot:** Minecraft renders through **LWJGL/GLFW (OpenGL)**, *not* the `java.awt.Canvas` /
`Graphics2D` path. So Amethyst's precedent proves CACIO boots an AWT-ish JVM — it does **not** prove CACIO
renders a full `Graphics2D` scene + the OSRS applet's Canvas correctly. That transfer is assumed, not shown.

**Evidence the seam exists (encouraging):** RuneLite composites the game **and every plugin overlay** into one
offscreen `BufferedImage` and only blits the *final* image to the window:
- `callback/Hooks.java:168` — `lastGraphics = (Graphics2D) mainBufferProvider.getImage().getGraphics()` (overlays draw here).
- `callback/Hooks.java:388–402` — `getGraphics(mainBufferProvider)` → `renderOverlayLayer(...)` / `paintOverlays(...)` onto that Graphics2D.
- `callback/Hooks.java:412` — `Image image = mainBufferProvider.getImage()` (the composited frame).
- `callback/Hooks.java:477` — `graphics.drawImage(finalImage, 0, 0, client.getCanvas())` — *only here* does anything touch the window.
⇒ Capturing `mainBufferProvider.getImage()` (or its int[] via `BufferProvider.getPixels()`) yields the full
composited frame, overlays included, **with no window**. The `runelite-api` `BufferProvider` exposes the pixel buffer.

**Prior art that partially de-risks this:** the existing miniJVM spike (predecessor repo `~/runelite`) already
drove the gamepack to render into `BufferProvider` in software mode and blitted the int[] — i.e. the gamepack
does **not** require a native-peer Canvas to render. (A prior exploration that called the gamepack canvas a
"BLOCKER" did not know this.)

**Refute these specifically:**
1. Does CACIO on iOS actually provide a `BufferedImage.getGraphics()` `Graphics2D` that correctly executes the
   overlay drawing ops RuneLite uses (text/fonts via `FontMetrics`, `drawImage`, alpha composite, clips,
   `Polygon` fills)? Fonts are the classic CACIO gap — name what breaks.
2. Does the OSRS gamepack's own software rasterizer need anything from the Canvas beyond an offscreen surface
   (a native peer, `GraphicsConfiguration`, display refresh)? The spike suggests no — confirm or break it.
3. Is `java.awt.headless=true` compatible with CACIO here, or does CACIO require headless=false + its own
   toolkit? Which exact toolkit/backend, and does a build of it exist for iOS arm64 (Pojav/Amethyst), or must we build it?
**Cheapest settling test:** run the W2 headless prototype (Claim 2) on **desktop** under CACIO with
`-Djava.awt.headless` + the CACIO toolkit (no real display), capture `getImage()`, confirm overlays render. Zero iOS needed.

## Claim 2 (HIGH RISK) — RuneLite can run "headless" (no Swing `ClientUI`) without forking deep into its internals
**The plan asserts:** bypass the Swing `ClientUI`, keep `PluginManager` + overlays, render to a buffer.

**Evidence it's DEEPER than the plan implies (this is the correction the audit should weigh):**
- `ui/ClientUI.java:322` `init()` is on the **critical path**, uses `SwingUtilities.invokeAndWait()`, builds a `JFrame`, sidebar, and a **system tray icon** (`SystemTray.getSystemTray()` ~`:1374`). `RuneLite.java:341/364` call `clientUI.init()/show()`.
- The gamepack object is cast to an AWT `Component` and added to a `JPanel`/`JFrame` (`ui/ClientPanel.java:49`, `ui/ClientUI.java:208`).
- Headless-hostile surface is broad: ~**115** `SwingUtilities.invoke*` calls; `GraphicsEnvironment`/`Toolkit.getDefaultToolkit().createCustomCursor()` (`ClientUI.java` ~`:755`,`:958`); `SystemTray`/`Taskbar` in ~13 files; **plugins** start *after* `clientUI.show()` and ~141 plugin files import `javax.swing`.
- `PluginManager` itself is Guice-injected and UI-agnostic at load (`plugins/PluginManager.java:102`), but plugin start is pushed onto the **EDT** (`:127`).

**The plan's mitigation (does it hold?):** we don't need the window — only `mainBufferProvider.getImage()`
(Claim 1's seam). So a **minimal headless `ClientUI` stub** + a real (CACIO) EDT could let the game + overlays
render while `JFrame`/tray/`GraphicsEnvironment` are stubbed or no-ops. Core plugins that only draw overlays
(the ones that matter on mobile) may work; Swing-config-heavy plugins break (acceptable — W3 replaces config UI).

**Refute these specifically:**
1. Is a "stub `ClientUI` + capture `getImage()`" genuinely a few seams, or do core render/input paths transit
   `ClientUI` (e.g. `getCanvasOffset()` `ui/ClientUI.java:997`, focus, resize, `GraphicsConfiguration` at `Hooks.java:416/487`) such that you must reimplement much of it anyway?
2. Can the EDT be a real CACIO EDT (not a mock)? If overlays/plugins assume a live EDT, mocking it is fragile —
   is "real CACIO toolkit + headless-stub ClientUI" the honest minimum?
3. How many **core** (not optional) plugins/overlays touch Swing/`Toolkit`/tray at *start* and would crash a headless boot before first frame?
**Cheapest settling test:** same desktop CACIO prototype — boot RuneLite with a stub ClientUI, load 2–3 overlay
plugins, render N frames to PNG. If that works on desktop with no display, the iOS port is "swap the JVM + surface."

## Claim 3 (MEDIUM) — gate sequencing: is Phase 1 (JIT on device) the right *first* gate?
**The plan orders:** Phase 0 → Phase 1 (embed JVM + confirm JIT on iOS 26.5) → Phase 2 (headless RuneLite on
desktop, in parallel) → Phase 3 (merge on device).

**The challenge:** Claims 1 & 2 are the **deepest, least-tested** risks, and **both are testable entirely on
desktop with zero iOS** (the CACIO headless prototype). JIT-on-iOS (Phase 1) is real but *better-precedented*
(StikDebug lists PojavLauncher as working). So arguably the **very first** thing should be the desktop CACIO +
headless-RuneLite + overlay-capture proof — it validates the product premise (plugins render to a buffer)
before any device/sideload/JIT setup, and it's the long pole.

**Refute or confirm:** Is there a reason JIT-on-device must be proven first (e.g. it could invalidate everything
cheaply in an hour)? Or should the plan be reordered to **"desktop CACIO headless-RuneLite proof"** as Phase 1,
with JIT-on-device as a parallel cheap check? What's the single cheapest experiment that most reduces total risk?

---

## Also worth a skeptical pass (lower priority)
- **OpenJDK 21 + JIT on iOS 26.5 specifically** — iOS 26 had JIT regressions; StikDebug fix is recent. Is per-launch StikDebug JIT reliable enough for multi-hour sessions, or does it drop?
- **Memory** — does the `increased-memory-limit` entitlement (AltStore 2.2+) give enough heap for RuneLite + plugins, given no TrollStore cap-lift on iOS 26.5?
- **Input** — mapping UIKit touch → AWT `MouseEvent`/`KeyEvent` into a CACIO EventQueue: any showstopper vs the spike's existing input bridge?
- **Maintenance** — does "real RuneLite self-updates the gamepack" actually hold for an unofficial fork, or does RuneLite gate updates on its own launcher/version checks we'd have to satisfy?

## What a useful audit output looks like
For each claim: **REFUTED / SUPPORTED / UNPROVEN**, the evidence, and — if unproven — the **single cheapest
experiment** that would settle it. Plus a recommended **gate ordering** (what to test first to kill the project
cheapest if it's doomed). Concrete file:line corrections to this brief are welcome.

## Key references (all in this repo unless noted)
- Plan/scope/findings: `ios/REALJVM-DEV-PLAN.md`, `ios/REALJVM-FEASIBILITY-SCOPE.md`, `ios/FINDINGS.md`.
- Perf proof: `ios/dev-tools/jvmbench/` (`Bench.java`, `RESULTS.md`).
- RuneLite seams: `runelite-client/src/main/java/net/runelite/client/callback/Hooks.java` (draw/overlay/blit),
  `ui/ClientUI.java`, `ui/ClientPanel.java`, `rs/ClientLoader.java` + `rs/RSAppletStub.java` (gamepack load),
  `ui/overlay/OverlayRenderer.java`, `plugins/PluginManager.java`; `runelite-api/.../BufferProvider.java`.
- AWT-on-iOS prior art (external, GPLv3 — patterns only): `AngelAuraMC/Amethyst-iOS` (Caciocavallo + `Natives/awt_xawt/xawt_fake.m`).
- miniJVM spike (gamepack-renders-to-BufferProvider prior art): predecessor repo `~/runelite` (`ios-spike/`, fallback).
