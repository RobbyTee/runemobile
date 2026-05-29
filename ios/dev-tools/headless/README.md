# headless — Gate 1c: real RuneLite, no Swing window, capture the engine buffer

The Gate 1c long pole: boot **real RuneLite** (real gamepack, real `PluginManager`/overlay pipeline) with
the desktop Swing `ClientUI` **replaced** by a window-less one, and capture the engine's composited buffer
(`mainBufferProvider`) to PNG on **desktop OpenJDK 21**. This is the product premise — RuneLite headless —
de-risked on the same JVM family before any device work.

## The cut (after the Gate 1c adversarial audit)
The audit refuted "just override five hot-path methods" and identified the real kill risk (its **Claim D**):
*will the OSRS engine drive the `Hooks.draw` callback with a non-null `Graphics` when there is no real Swing
window / displayable canvas?* If not, nothing composites and `mainBufferProvider.getImage()` is never fed.

So the architecture is:
- **`net.runelite.client.ui.HeadlessClientUI`** — subclass of `ClientUI` (its constructor was relaxed
  `private → protected`). Replaces the full desktop chrome (ContainableFrame + sidebar + toolbar + tray +
  L&F + OSX adapters) with **one minimal AWT `Frame` that hosts the gamepack `Component` and is realized**,
  so the engine's `Canvas` acquires a peer and hands the engine a real `Graphics`. Focus / cursor / taskbar /
  navigation / sidebar-overlay are all no-ops.
- **`net.runelite.client.headless.HeadlessModule`** — Guice override (`Modules.override(RuneLiteModule)
  .with(..)`) rebinding `ClientUI → HeadlessClientUI`. Every `@Inject ClientUI` site gets the headless one.
- **`net.runelite.client.headless.RuneLiteHeadless`** — entrypoint; sets the override module then calls
  `RuneLite.main` verbatim (same boot, same gamepack load, same plugins) — the honest client, not a rewrite.
- **`net.runelite.client.headless.HeadlessCapture`** — gated instrumentation wired into two lines of
  `Hooks.draw`: counts every draw + null-Graphics arrivals, and dumps the buffer to PNG via the
  toolkit-independent `BufferProvider.getPixels()`. Inert unless `-Drunelite.headless.capture.dir` is set.

Upstream files touched (minimal, merge-friendly): `ui/ClientUI.java` (constructor visibility + two
`frame == null` guards on its private `@Subscribe` handlers), `callback/Hooks.java` (two gated capture
calls), `RuneLite.java` (an optional override-module seam). Everything else is new and isolated.

## Run
```sh
./run-headless.sh         # experiment 1: native toolkit (bring-up crutch; a tiny window appears)
./run-headless-cacio.sh   # experiment 2: CACIO CTC toolkit — NO OS window (the honest iOS proxy)
```
Both boot to the **live-OSRS login screen** with `--safe-mode` (GPU off), run `RUN_SECONDS` (default 45),
dump frames + a log to `out/` (gitignored), then print a summary. CACIO activation reuses the Gate 1b path
(`CacioExtension` via the JUnit console launcher; `../cacio-smoke/lib` jars). Requires OpenJDK 21 at
`/opt/homebrew/opt/openjdk@21` (override with `JAVA_HOME`).

## Result (2026-05-29) — GREEN (login-screen capture); Claim D refuted
- **Native toolkit:** `HeadlessClientUI` realizes its frame (`canvas 765×503 displayable=true`); engine
  drives `Hooks.draw` ~42 fps with **`nullGraphics=0`**; 20 PNGs captured — each the **real live-OSRS login
  screen** rendered by the engine into our buffer, with no `ClientUI`. No `HeadlessException`, no NPEs.
- **CACIO CTC:** identical, under `toolkit=CTCToolkit` / `graphicsenv=CTCGraphicsEnvironment` with **no OS
  window** — the canvas is realized to a CACIO offscreen peer. `nullGraphics=0`, 20 login-screen frames.
  This is the honest iOS-shaped proof: the engine renders with no native display.

⇒ The kill risk is settled: **a window-less `ClientUI` does drive the real engine's render + capture seam**,
on both native and the iOS-shaped CACIO virtual display.

## What this does NOT yet prove — overlay compositing needs a logged-in client
`Hooks.draw` → `OverlayRenderer.renderOverlayLayer` → `renderOverlays`, which **returns early unless
`client.getGameState() == LOGGED_IN`** (`OverlayRenderer.java:249`) for **every** layer (including
`ALWAYS_ON_TOP`). So no overlay — synthetic or real — composites at the login screen. The honest
overlay-compositing proof is therefore **1c-ii: live login + in-game capture** (needs the throwaway Jagex
account / `tokens.json`). A mocked-client 1c-i would have to bypass this gate, which just re-derives Gate 1a
(overlay draw-ops → buffer, already green) — matching the audit's Claim-C skepticism that 1c-i risks being
vacuous. Next step: wire live login on a throwaway account and capture an in-game frame with a real plugin
overlay composited.
