# Audit brief — W3 hybrid native Jagex-Mobile chrome over the desktop root (2026-06-01)

Self-contained; a reviewer needs only this doc. Asking for an adversarial verdict on **the architecture and
sequencing for re-creating the official OSRS-Mobile chrome (split edge tabs / orbs / mobile chat) natively,
laid over the unmodified desktop in-game UI** — now that flipping to the game's own mobile toplevel has been
ruled out. The headline question:

> **How do we make the client *look and feel* like the Jagex-Mobile app — edge-split tabs, mobile orbs, mobile
> chat — while keeping unmodified RuneLite plugins (which target the desktop widget IDs) working, given the
> gamepack re-lays-out its widgets every frame and our control scheme already plays full-screen over the game?**

## Why this brief exists (the decision that led here)
We just ran a gated desktop spike (`ios/AUDIT-RESULT-mobile-layout-toplevel-osm.md`) on whether we could flip
the client into building the game's own mobile toplevel (interface group 601, `ToplevelOsm`, which lives in the
shared cache). **Verdict: RED.** Two independent reasons, device-pointless (settled on desktop):
1. **601 won't build on the desktop gamepack.** With a valid baseline (`getTopLevelInterfaceId()=164`,
   `ToplevelPreEoc` resizable), `runScript(907=TOPLEVEL_REDRAW, 601, {1,0})` neither threw nor had any effect —
   the toplevel stayed 164 and 0/6 of 601's components instantiated. Script 907 rebuilds the *engine-chosen*
   toplevel and ignores the group arg; 601's selection is gated on a native `clienttype` the desktop gamepack
   reports as desktop, with no API/varbit to change it.
2. **Even if it built, 601 breaks unmodified plugins** — switching the root nulls the desktop toplevel IDs that
   core overlay anchoring (`OverlayRenderer.getViewportLayer`, `WidgetOverlay`) and ~9 plugins hardcode.

So the official mobile layout is a **spec, not a live toplevel**: its 135-component definition in the cache is a
free, pixel-accurate reference for what to *draw natively*, but we build the chrome ourselves over the desktop
resizable root. This brief is that build.

## Where we are (all device-proven this session, md5-gated against verbatim devicectl artifacts)
The full stack **plays like a mobile client** on device (iPhone 17 Pro Max, iOS 26.5, TXM; JIT via per-launch
StikDebug attach): on-device JIT OpenJDK 21 → CACIO CTC → **real unmodified RuneLite** → live OSRS → **full-screen
landscape**, with a complete touch + keyboard control scheme (tap=click, long-press=drag-to-select right-click
menu, one-finger drag=camera, pinch=zoom, hidden-`UITextField` keyboard). Today the game renders the **desktop
resizable** layout stretched to the safe area — it does NOT look like the mobile app (tabs are the desktop
bottom-right cluster, not split to the screen edges). The native shell is a debug crutch: the game is the bottom
layer, all debug UI hides in a `≡`-toggled overlay panel, plus a floating `⌨` button (`ViewController.m`).

## The two bridges that already exist (the foundation this builds on)
- **`JavaBridge`** (gated `-Drunelite.bridge=true`) — hot path: `snapshot`/`getFramePixels` (blit) + intent-level
  input (`onClick`/`onMove`/`onTouch`/`onWheel`/`onCameraDrag`/`onText`/`onKeyStroke`). Main-thread JNI per tick.
- **`MobileUiBridge`** (gated `-Drunelite.mobileui=true`) — control plane, thread-routed: `listPlugins` /
  `getPluginConfig` / `setPluginEnabled` (EDT) / `setConfig` (client thread) + `tick(c)` snapshots
  **chat-input bounds** on the client thread. This is where new client-thread reads/writes (widget bounds,
  tab state, orb values) belong.

## The candidate architectures (the core thing to adjudicate)
The mobile chrome is tabs + orbs + minimap + split chat at the screen edges. Three ways to get there:

- **A — Native veneer over the visible desktop UI.** Leave the desktop in-game UI rendering as-is; overlay
  *native* UIKit tab buttons / orb widgets / chat at the screen edges; a native tap proxies to the real desktop
  widget (e.g. synthesize a click on the desktop tab-stone, read orb values over the bridge). Simplest, least
  fighting the engine — but visually it's the desktop UI **plus** a native veneer (double UI) unless we also hide
  the desktop UI.
- **B — Hide desktop UI + fully native chrome.** Hide the desktop tabs/orbs/minimap/chat widgets
  (`Widget.setHidden(true)`, re-applied every frame since the gamepack re-lays-out) and draw 100% native chrome
  that proxies all interactions to the game (tab switch, panel open, orb state, chat). Cleanest look, but we now
  own re-creating every affordance and re-hiding every frame; the side **panels** (inventory/prayer/spell
  contents) are real game widgets we still need to show *somewhere*.
- **C — Reposition desktop widgets into the mobile split.** Use `setOriginalX/Y` + `revalidate` to push the
  desktop tab column / minimap / chat to the screen edges (mobile arrangement), restyle minimally, every frame.
  Keeps real widgets (plugins keep working, panels render themselves) but fights the engine's per-frame layout
  hardest and may look half-desktop.

Likely answer is a **mix** (e.g. reposition/keep the interactive side **panels**, but native-draw the tab
column + orbs + chat veneer). The reviewer should pick the architecture, per-element, and name the trap.

## Questions to adjudicate (verdict + reasoning each)
1. **Which architecture (A/B/C/mix), element by element** — tab column, side panels (inv/prayer/spell/etc.),
   minimap + compass, orbs (hp/prayer/run/spec/xp), world map, the split chat? For each: native-draw-and-proxy,
   hide, or reposition? The deciding constraints: (a) plugins target desktop widget IDs and many *draw into* or
   *anchor to* the desktop widgets — hiding/moving them must not break the plugin overlays we just got working;
   (b) the interactive panels (inventory items, spellbook, prayer) are real widgets with their own click
   handling — we almost certainly must keep those as real widgets, only relocated/reskinned.
2. **Tab switching.** A native "Inventory" tab tap must switch the game's active tab. Options: synthesize a click
   on the desktop tab-stone widget at its live canvas bounds via `JavaBridge.onClick` (read bounds over the
   bridge); `setVarcIntValue` on the panel/tab var (`VarClientID.TOPLEVEL_PANEL=171`?); `runScript(...)`. Which is
   robust across fixed/resizable and version updates? What's the source of truth for "which tab is active" to
   highlight the native tab?
3. **Orbs + minimap (live game-state).** Orbs are their own interface groups (`Orbs`/`OrbsNomap`), minimap is a
   live-rendered engine surface. If native-drawn, the native side must poll values every frame over the bridge
   (HP/prayer/run/spec %, run-toggle state, spec %, world-map). Is per-frame polling of ~6 values fine, or keep
   them as repositioned real widgets (minimap especially — it's an engine raster, painful to re-host natively)?
   Note minimap interactivity (tap-to-walk on minimap, compass click, run-orb toggle, spec toggle).
4. **The per-frame re-layout fight.** The gamepack re-runs its toplevel layout every frame, so any hide/move we
   apply must be re-applied each `Hooks.draw` (like `MobileUiBridge.tick` already does for chat bounds). Is that
   sustainable and flicker-free, or does it cause visible thrash / fight overlay anchoring
   (`OverlayRenderer.getViewportLayer` re-reads the desktop root each frame)? Is there a cleaner hook than
   per-frame re-apply (e.g. react to `ScriptPostFired` TOPLEVEL_REDRAW/RESIZE_CUSTOMISE, like `MinimapPlugin`)?
5. **Touch-region coexistence + the snap bug.** Native chrome occupies screen regions; taps there must be
   captured by UIKit and **not** fall through to the game. Conversely the game view keeps the world region.
   **Known bug to fix here:** `JavaBridge.onClick`/`resolveSnap` snaps *every* in-game left tap to the nearest
   world entity with **no world-viewport exclusion** — under edge-tab chrome a near-edge world tap could snap
   wrong, and a tab tap that leaks through would mis-route. Plan: native UIKit hit-testing owns chrome regions;
   the Java snap is gated on the world-viewport bounds (read the viewport widget bounds over the bridge). Sound?
6. **Where the chrome is drawn.** Native UIKit views composited over the game image view (crisp,
   DPI-independent, standard controls, gesture-native) vs drawn into the Java buffer (consistent with the engine
   but blurry at our upscale and re-implements widgets). Native UIKit seems right — confirm, and note what MUST
   stay in-engine (the interactive panels, the minimap raster).
7. **Sequencing + biggest risk.** Cheapest-correct order to build this WITHOUT regressing the working controls
   (tap/menu/camera/pinch/keyboard) or the working plugin overlays — and the **single most likely thing to be
   wrong** that the first desktop probe should settle (candidate: "can we hide/reposition the desktop tab/chat
   widgets under our headless CACIO build, every frame, without breaking plugin overlay anchoring or the
   gamepack's own input?"). Relationship to the still-open phase-6 items (native plugin-config panel, chat-tap,
   auto-resizable display mode, DPI) — does the chrome come before or after those?

## What we know about the seams (for the spike)
- **Widget toolkit (Path-2):** `Widget.setHidden(boolean)`, `setOriginalX/Y(int)` + `revalidate()`, `getBounds()`,
  `getCanvasLocation()`, `isHidden()` — hide/move/measure real widgets. `Client.getWidget(int)`,
  `getTopLevelInterfaceId()`, `getVarcIntValue`/`setVarcIntValue`, `runScript(Object...)` (client thread only),
  `isResized()`.
- Desktop tab buttons = 14 `STONE0..13` per toplevel group (`InterfaceID.Toplevel/ToplevelOsrsStretch/
  ToplevelPreEoc`); side panel = `SIDE3`/`SIDE_CONTAINER`/`SIDE_PANELS`; minimap = `MAP_*`/`ORBS`; chat =
  `CHAT_CONTAINER` + `Chatbox.*`.
- The mobile **spec** to copy: `InterfaceID.ToplevelOsm` (601) component names — `SIDE_RIGHT_TABS`,
  `SIDE_LEFT_CHAT`, `ORBS`, `MINIMAP`, `COMPASSCLICK`, `CHAT_TOGGLE`, `HOTKEYS`, `WIFI`, `BATTERY`, corner
  anchors — for *layout reference only* (not built live).
- `MobileUiBridge.tick(c)` already snapshots chat-input bounds on the client thread — the pattern for any new
  per-frame widget-bounds/state reads the native chrome needs.
- Control scheme already proven over the full-screen game (`JavaBridge` + `ViewController` gesture recognizers);
  native chrome must not regress it.

## Constraints / trust rules (unchanged)
- **Device-only** for the real verdict; desktop OpenJDK 21 + CACIO is the develop-first mirror (W2 rule).
- **Trust only verbatim device artifacts** (`jvm-stdio.log`/`hello-shell.log`/`hs_err`/crash `.ips` via
  `devicectl`) + bash-computed md5-gated verdicts (documented false-GREEN history).
- **RuneLite plugins must keep working** is first-class — hiding/moving widgets must not break overlays.
- **GPLv3 sources (Amethyst/Pojav/StikDebug) patterns-only**; **throwaway Jagex account only**; self-built
  client on live OSRS is ban-risk / not shippable — personal technical build.

## Pointers (all in-repo)
- Verdict that led here: `ios/AUDIT-RESULT-mobile-layout-toplevel-osm.md`; gated probe
  `runelite-client/.../headless/MobileToplevelProbe.java`; harness `ios/dev-tools/headless/run-osm-probe.sh`.
- Bridges: `runelite-client/.../headless/JavaBridge.java`, `MobileUiBridge.java`; wiring
  `runelite-client/.../callback/Hooks.java` (`draw`); window-less host `.../ui/HeadlessClientUI.java`;
  resize `HeadlessResize.java`; chat-bounds pattern in `MobileUiBridge.tick`.
- Overlay anchoring to protect: `runelite-client/.../ui/overlay/OverlayRenderer.java:776`
  (`getViewportLayer`), `WidgetOverlay.java:43`.
- Widget/Client API: `runelite-api/.../widgets/Widget.java`, `runelite-api/.../Client.java`,
  `runelite-api/.../gameval/{InterfaceID,VarClientID,VarbitID,ScriptID}.java`.
- Native shell scaffold: `ios/app/HelloShell/{ViewController.m,JVMLauncher.m,JVMLauncher.h}`; `Info.plist`.
- **Broader phase-6 brief (still open items — plugin-config panel, chat-tap, auto-resizable, DPI, bridge
  soundness):** `ios/AUDIT-BRIEF-phase6-native-shell.md` (this brief is the *chrome-layout* slice; that one is
  the panel/polish slice — adjudicate how they sequence).
- Prior briefs/verdicts (the rhythm): `ios/AUDIT-BRIEF-phase5-mobile-controls.md`,
  `ios/AUDIT-BRIEF-w1-phase3-awt.md`.

**Bottom line for the reviewer:** the mobile *look* must now be hand-built natively over the desktop root (the
game's own mobile toplevel is unreachable + plugin-breaking). The whole risk is the seam between native chrome
and the live, self-re-laying-out gamepack UI that plugins depend on. Pick the per-element architecture, name the
one thing to de-risk first on desktop, and order it so the working controls and plugin overlays never regress.
