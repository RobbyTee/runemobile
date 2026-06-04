# Audit brief — phasing two large W3 efforts: native HUD veneers + GPU rendering

> Self-contained brief for an independent reviewer. Goal: pressure-test the **sequencing and phasing** of two
> large, independently-motivated W3 efforts that meet at exactly one code seam — and pressure-test the (not-yet-
> reviewed) plan for the **native HUD** effort specifically. Be adversarial: attack the sequencing logic, the
> de-risk gates, and the cheapest experiment that would refute each claim. We would rather find a wrong assumption
> here than discover it three phases deep.
>
> Companion brief (already reviewed): `ios/AUDIT-BRIEF-w3-gpu-renderer.md` (the GPU effort's internals + its own
> claim set A–F). This brief does **not** re-litigate the GPU internals — it focuses on (1) the native-HUD plan
> and (2) how the two efforts interleave without colliding.

## The two features

**Feature A — Native HUD veneers.** Today the minimap, orbs, compass, and chat are rendered by the desktop OSRS
gamepack, baked into the same frame the engine hands us, anchored to the canvas corners. To get the official
**OSRS-Mobile** layout (chat repositionable to the top, minimap/orbs where we want them, over a full-bleed
edge-to-edge scene), we render **our own** chat / minimap / orbs natively (UIKit/Metal), reading the live game
data through RuneLite's APIs, over an edge-to-edge 3D scene with the engine's baked-in 2D HUD suppressed. This is
the project's already-chosen "true immersion" direction (see the full-screen revert below). It is a substantial
sub-project — reproducing OSRS chat (tabs, colours, scrollback, input), a live minimap, the orbs — **not a layout
tweak**.

**Feature B — GPU scene rendering.** Replace the CPU software rasteriser (CACIO CTC) with hardware scene
rendering — the base RuneLite `gpu` plugin first (GL 3.3 core), then 117HD/RLHD (GL 4.3 compute). This resolves
the hard **sharpness↔FPS trade** the software rasteriser forces (`renderH` dial) and unlocks HD lighting. It is a
multi-week-to-months **research port with a hard go/no-go gate** and a real chance of stalling at recon. Full
detail + claims A–F in `ios/AUDIT-BRIEF-w3-gpu-renderer.md`; that brief's external review is already in hand.

These are **independently motivated** (A fixes layout/immersion; B fixes sharpness/FPS) and the user wants both.
The question this brief exists to answer: **do they collide, do they change how each other is built, and in what
order should they be done?**

## Why this needs careful sequencing (the one-paragraph version)

A and B touch the codebase in almost entirely **disjoint** places. They meet at exactly **one** seam: how the
finished frame is published to the native layer (`Hooks.draw` → `JavaBridge.snapshot` → native blit). At that one
seam they have **opposite needs** — A wants a *scene-only, HUD-suppressed* frame published; B changes *where the
scene physically lives* (GL framebuffer, not the CPU `int[]`). The collision is real but narrow, and it only
actually bites at the **late** stage of B (base `gpu` bring-up), not at B's cheap recon. Sequenced correctly they
never fight; run as two big parallel builds they corrupt each other's publish contract.

## Current architecture the reviewer needs (self-contained)

- **Process / display today.** Real unmodified RuneLite runs on an in-process on-device JIT **OpenJDK 21**
  (iPhone 17 Pro Max, iOS 26.5), drawing through **CACIO CTC** (software rasteriser, no GPU, windowless). The
  engine rasterises the 3D scene **and** the 2D HUD widgets into one shared `BufferProvider` `int[]`. RuneLite's
  `Hooks.draw` layers its overlays on top, then publishes that fused frame via
  `net.runelite.client.headless.JavaBridge.snapshot(mainBufferProvider)`; the native shell pulls
  `getFramePixels()` (a packed `[w,h,…0xAARRGGBB]` `int[]`) each `CADisplayLink` tick → `CGImage` → `UIImageView`,
  skipping the rebuild when `frameSeq` is unchanged. The entire display contract today is **"hand us one fused
  `int[]` framebuffer."**
- **Two existing bridges.** `JavaBridge` = the hot-path facade (frame blit + touch). `MobileUiBridge` = a
  Java-owned control-plane facade (plugin config, game-state reads), already thread-routed (EDT lane vs
  client-thread lane). Native chrome (edge-split tab rails, plugin dock, native right-click menu, smart
  touch-snap) is built on top and device-confirmed.
- **What the GPU plugin would change.** The base `gpu` plugin renders the 3D scene into its **own** offscreen
  framebuffer (`fboScene`), uploads the engine's 2D UI as a **separate** texture (`interfaceTexture`), and
  composites them only at the very end. The final GPU frame lives **in GL**, not in the `int[]` we publish today.
- **The reverted full-screen work (load-bearing history).** Edge-to-edge full-screen was built and
  device-confirmed, then reverted (`319b4336d`, reverting `348dd55e4..75e5253cf`). It worked, but pushing the
  canvas to the physical screen edge put the gamepack's corner-anchored HUD (minimap top-right, chat bottom-left)
  under the notch / home-indicator / native rails — because **the engine HUD is drawn at canvas-relative corners
  and can't be relocated on the desktop gamepack** (the mobile `ToplevelOsm`/601 layout that *can* reposition the
  HUD is unbuildable here + plugin-breaking). The retreat (inset to safe-area) made full-screen ≈ windowed size,
  so the whole thing was reverted. **The runtime canvas-resize plumbing it built is sound and recoverable from
  git** (`HeadlessResize.applyResize`, soak-proven 200 cycles, no leak under `-Xmx256m`). Feature A is precisely
  the missing piece that makes edge-to-edge worth it: **suppress** the engine HUD instead of trying to keep it.

## The central finding: one shared seam, otherwise disjoint (with code evidence)

Verified by reading both render paths end-to-end. The reviewer can confirm each at the cited line.

1. **The native blit is hardwired to the CPU buffer and is taken *before* the GPU branch.** `Hooks.draw` calls
   `JavaBridge.snapshot(mainBufferProvider)` at `Hooks.java:498`, then `if (client.isGpu()) return;` at
   `Hooks.java:501`. Consequence: turning on the GPU plugin today would publish a **UI-only buffer with a hole
   where the scene should be** — base `gpu` bring-up *must* add a new GPU publish hook regardless of Feature A.

2. **The GPU plugin is the ONLY place in the codebase where the 3D scene physically exists separated from the
   HUD.** Scene → `fboScene` (`GpuPlugin.java:898` bind, `1011-1019` unbind); composite order in `draw()` is
   `blitSceneFbo()` (`GpuPlugin.java:1438`) **then** `drawUi()` (`GpuPlugin.java:1442`). Between those two lines
   the default framebuffer holds **scene-only, no HUD**. The 2D UI is the same `BufferProvider int[]` uploaded as
   `interfaceTexture` via PBO (`prepareInterfaceTexture`, `GpuPlugin.java:1365-1401`) and drawn as one quad
   (`drawUi`, `GpuPlugin.java:1481-1532`). In the **CPU** path there is no scene-only buffer at any point — scene
   and HUD are merged in one `int[]` before `Hooks.draw` runs.

3. **The only scene/UI separator the public API exposes in the CPU path is the viewport rectangle**
   (`Client.getViewportXOffset/YOffset/Width/Height`, `Client.java:332-353`) — geometric metadata, not a separate
   buffer. The iOS code already consumes it (`JavaBridge.inWorldViewport`, `JavaBridge.java:883-901`).

4. **Feature A's "suppress the engine HUD" lever exists but is untested here.** `client.setDraw2DMask(int)` /
   `DRAW_2D_NONE` (`Client.java:2093-2123`) is an engine flag that suppresses the entire 2D overlay pass while
   leaving the 3D scene. It is **currently unused anywhere in `runelite-client`** (grep-clean) → it is the
   cheapest lever but must be confirmed honoured by the injected gamepack. Fallback: per-widget `setHidden` on the
   HUD components (`Widget.setHidden`, `Widget.java:396-411`; the stock `minimap` plugin's exact recipe,
   `MinimapPlugin.java:160-184`, re-applying on `TOPLEVEL_REDRAW`).

5. **Feature A's data is fully available via RuneLite APIs, rendering-path-agnostic.**
   - Chat: `Client.getChatLineMap()` (`Client.java:945`), `getMessages()` (`952`); `MessageNode` carries
     type/name/value/timestamp; `ChatMessageType` enum splits tabs; colours from VarPlayer chat settings
     (`VarPlayer.java:191-215`) resolvable via `ChatMessageManager`.
   - Minimap (drawn from positions — there is **no** API to read the composited minimap bitmap):
     `getMapDots()` (`Client.java:1021`), `getMapScene()` (`1014`), `getMapIcons()` (`1037`), `getMapAngle()`
     (`695`), `getMinimapZoom()` (`2050`), plus actor world positions from the world view.
   - Orbs: `getBoostedSkillLevel/getRealSkillLevel(Skill.HITPOINTS/PRAYER)`, `getEnergy()` (`Client.java:547`),
     special via `VarPlayerID.SA_ENERGY (300)`, `isPrayerActive` (`918`) — working reference in
     `StatusBarsOverlay.java:113-221`.

**Net:** outside seam #1/#2, A is native UIKit/Metal + read-only data APIs + an engine-HUD-suppression call; B is
LWJGL-iOS + `librlawt` shim + GL→GLES/Metal down-port + offscreen FBO. They share the publish seam and nothing
else.

## How each feature changes the other

- **The GPU verdict changes Feature A's "publish" half only.**
  - *CPU path* (today, no GPU): suppress the 2D HUD (`setDraw2DMask(DRAW_2D_NONE)` or per-widget `setHidden`),
    render the scene edge-to-edge in resizable mode (viewport fills the canvas), publish the existing packed
    `int[]`. The resize plumbing is recoverable from git.
  - *GPU path* (if B lands): export `fboScene` (already scene-only) to the native blit and **skip `drawUi`**.
  - This is a **thin** seam (where the scene-only frame comes from), small relative to A's whole surface.
- **Feature A lightens — but does NOT eliminate — a future Feature B's UI compositing.** `interfaceTexture` is
  not just the engine HUD: `Hooks.draw` renders RuneLite plugin overlays + client-UI + our selection drawing into
  the buffer *before* the GPU return (`Hooks.java:470`), and `prepareInterfaceTexture` uploads that whole buffer
  (`GpuPlugin.java:1377`) for `drawUi` to composite (`:1442`). Suppressing the engine HUD (A's `setDraw2DMask`)
  leaves an **overlay-only** buffer, so B2 still needs `drawUi`/`interfaceTexture` — just carrying a lighter,
  overlay-only texture, not the full HUD. **B2 is therefore an overlay-only composite path, not a pure scene-FBO
  publish.** (See revised claim S3.)
- **They are complementary, not antagonistic.** Native HUD (crisp UIKit text) over a GPU scene (crisp 3D) is the
  *full* sharpness win — A fixes HUD crispness, B fixes scene crispness. The only antagonism is the working-tree /
  publish-seam contention if both big builds run at once.

## The phasing

Two tracks. **Track A is the mainline deliverable; Track B's recon runs cheaply alongside; Track B's big build is
strictly gated and never concurrent with Track A's seam work.**

### Track A — Native HUD veneers

> W2 rule throughout: de-risk on desktop OpenJDK 21 + CACIO first, then one device run. Device verdicts md5-gated
> against verbatim devicectl-pulled logs (the project's discipline against fabricated GREENs).

**A0 — Scene-isolation de-risk (desktop; cheap; GATES the whole native-HUD approach).**
- Goal: prove a clean **scene-only, HUD-suppressed, viewport-filled** render is obtainable **AND that route-back
  still works** under whichever suppression mode we pick.
- Work: in the headless desktop harness, after LOGGED_IN call `setDraw2DMask(DRAW_2D_NONE)` on the client thread;
  in resizable mode assert `getViewport*` ≈ full canvas and the baked HUD (minimap/chat/orbs/tabs) is gone;
  capture PNG. Also smoke the **fallback**: per-widget `setHidden` on `MAP_MINIMAP`/`ORBS`/`CHAT_CONTAINER`/
  `STONE0..13` (MinimapPlugin recipe, `MinimapPlugin.java:169`, re-applied on `TOPLEVEL_REDRAW`).
- **Route-back interaction test (added per review — visual-HUD-free is NOT sufficient):** after EACH suppression
  mode, precise-click the chat-tab / orb / minimap **original centres** and assert the game state changes (tab
  switch / run toggle / quick-prayer / spec / walk-to). This catches the **suppression↔route-back conflict**:
  **`setHidden` widgets are not clickable** — the project already learned this (`MobileChromeHideTest.java:42`:
  hidden stones aren't clickable, fell back to varc probing) — so the A3/A5 "click the engine widget" route-back
  would be **dead** under the `setHidden` fallback. `setDraw2DMask` (rendering-only suppression, widgets stay
  live) is therefore the **strongly preferred** lever precisely because it preserves click-through; the
  `setHidden` fallback additionally requires per-action alternative invoke paths (varc/clientscript), expanding
  scope.
- Exit: a scene-only HUD-free PNG **+ all route-back interactions confirmed** + a recorded verdict on which lever
  works. **RED here reopens Feature A's whole premise** — if neither lever yields a clean scene-only frame *with
  working route-back*, native HUD over edge-to-edge is not reachable on the desktop gamepack.
- **A0 RESULT — DONE, md5-gated GREEN (`HUD_ISO_GREEN_AS_EXPECTED`), desktop OpenJDK 21 + CACIO, live OSRS,
  2026-06-04.** Probe `HudIsolationTest` (`-Drunelite.hudisolation=true`), harness `run-hud-isolation.sh` +
  `hud-isolation-verdict.sh`; evidence `ios/app/evidence/a0-hud-iso-{baseline,mask,hidden,transparent}-20260604.png`.
  Three levers measured for BOTH removal (judged by `isHidden` + the PNGs — NOT a pixel-diff; the live minimap
  fools that, which produced and then corrected a false positive) and route-back (does a synth-click at the run
  orb flip `OPTION_RUN`):
  - `setDraw2DMask(DRAW_2D_NONE)` → does **NOT** remove the interface HUD (governs world-space 2D extras;
    minimap region delta 2%, HUD intact). **Refutes claim A-1's first option** — the brief's "preferred lever"
    is the wrong tool.
  - `setHidden(true)` → **clean removal** (minimap/chat/orbs gone, region delta 99%) but the orb is then **not
    clickable** (`OPTION_RUN` doesn't flip) — reconfirms the suppression↔route-back conflict.
  - `setOpacity(255)` (transparent) → **stays clickable** (orb flips; "Toggle Run" tooltip live) but does **NOT**
    remove the HUD (only rectangle fills go; the minimap/orbs/text still render).
  - **=> resolver=none.** No single lever both removes the HUD and stays clickable. A0's primary goal — *a clean
    scene-only HUD-free frame IS obtainable* (via `setHidden`, PNG-confirmed) — is GREEN, but click-through
    route-back is dead under the clean remover.
  - **Plan consequence:** native-HUD route-back must **decouple** removal from invocation — *cover-in-place* for
    elements kept in position (engine widget stays live + clickable under the native draw), or *hide +
    direct-invoke* for repositioned ones. Headline win: **native chat (chat-at-top) needs no problematic
    route-back** — input already works via `onText`; tabs/scrollback are native operations over `getMessages()`.
    The genuinely hard case is **minimap-walk** (walk-to-arbitrary-world-point with the engine minimap hidden) —
    **now SETTLED, see A4 ROUTE-BACK RESULT: cover-in-place (keep the engine minimap live + covered, proxy the
    walk-click); it cannot be hidden.**

**A1 — Edge-to-edge scene-only publish (the shared seam; desktop soak → device).**
- Goal: get the HUD-free scene to the native layer, full-bleed, without re-introducing the revert's clipping.
- Work: restore the runtime-resize plumbing (`HeadlessResize.applyResize` + `JavaBridge.requestResize`, from git
  `348dd55e4..75e5253cf`); apply A0's suppression; render edge-to-edge (full bounds, ignore safe-area); publish
  via the existing packed `[w,h,pixels]` snapshot. Native: pin the game view to full bounds.
- De-risk: re-run `ResizeSoakProbe` with suppression active (the 200-cycle soak already passed without it).
- Exit: device shows a full-bleed, HUD-free scene with **nothing** bleeding under the corners/notch (the exact
  thing the reverted full-screen could not do). md5-gated.
- **A1 RESULT (desktop half) — DONE, md5-gated GREEN (`EDGE2EDGE_GREEN_AS_EXPECTED`), 2026-06-04.** Probe
  `EdgeToEdgeProbe` (`-Drunelite.edge2edge=true`), harness `run-edge2edge.sh` + `edge2edge-verdict.sh`; evidence
  `ios/app/evidence/a1-edge2edge-scene-1560x720-20260604.png`. At the device wide full-bleed aspect **1560×720
  (~19.5:9)** with the full engine 2D HUD `setHidden` (20 targets: minimap/mapContainer/orbs/chat/sidePanels/
  sideContainer + 14 tab stones): **the resizable 3D viewport fills the wide canvas edge-to-edge** (viewport
  `1560×720@0,0` == canvas — settles the new unknown: no letterbox at 19.5:9) and all 20 targets hide; the PNG
  shows a clean wide scene with the major HUD gone. **Completeness note (the brief's predicted "incomplete
  suppression"):** a few PERIPHERAL widgets outside the 20-target set remain (top-right logout/world-map/store
  buttons + a bottom-right icon row) — enumerate + add to the hide set in A6. The runtime-resize TOGGLE (reverted
  with full-screen) was NOT needed — edge-to-edge is the default, so the launch-time resize sets the aspect once.
  **Device half pending** (native ViewController pins full-bleed + the atomic `[w,h,px]` snapshot, recoverable
  from git `348dd55e4..75e5253cf`).

**A2 — HUD data bridge (read-only; desktop → device).**
- Goal: publish chat / minimap / orb data to native each frame.
- Work: extend `MobileUiBridge` (client-thread, control-plane) to snapshot per frame — chat (per-tab MessageNode
  lists with type/name/value/timestamp + resolved colour), minimap (local player world pos, map angle, zoom,
  actor world positions, the dot sprite set), orbs (HP/prayer cur+max, run energy, spec %, run/quick-prayer/poison
  /stamina state). Publish as escaped-JSON snapshots + seq (mirrors the existing `chromeSnapshot`/
  `menuPeekSnapshot` pattern); native reads via reverse-JNI.
- Exit: native logs faithful chat/minimap/orb snapshots that track live game state on device. md5-gated.
- **A2 RESULT (desktop half) — DONE, md5-gated GREEN (`HUD_DATA_GREEN_AS_EXPECTED`), 2026-06-04.** `HudDataBridge`
  (`-Drunelite.huddata=true`, ticked read-only in `Hooks.draw`), harness `run-hud-data.sh` + `hud-data-verdict.sh`;
  evidence `ios/app/evidence/a2-hud-data-trail-20260604.log`. Publishes a per-frame escaped-JSON snapshot
  (`getSnapshot()`/`getSeq()`, native reads via reverse-JNI) carrying **chat** (per-line type/name/value/ts — e.g.
  `{"type":"WELCOME","value":"Welcome to Old School RuneScape."}`, raw `<col>` tags preserved for A3), **orbs**
  (hp 12/12, prayer 1/1, energy, spec, run-enabled, poison, stamina), **minimap** (local player world pos
  3214,3222 plane 0, compass angle, nearby-actor dots as tile offsets — 10 players + 21 npcs, 24 dots). md5-gate
  confirmed **faithful** (login welcome line present, orb values plausible, local player resolved) + **live**
  (fingerprint folds in dot positions + chat size → moved over ~2.4s). Read-only, **rendering-path-agnostic**
  (confirms S2 — reads `Client`/world-view APIs, not the framebuffer). **Device confirm pending** (user-driven).
- Goal: expose chat/minimap **fidelity cost** before committing to polished A3/A4 (S2 is path-agnostic but **not
  effort-agnostic** — chat formatting is tied to script callbacks, `ChatMessageManager.java:99`, not a clean
  "call this helper" API; the minimap is sprites + positions, not a composited bitmap).
- Work: throwaway native chat list (plain rows, resolved colour) + native minimap (player dot + a few NPC dots +
  compass), fed by A2. No tabs/scrollback/input/terrain yet — just enough to measure how far the raw data is from
  faithful. **Run the A-ALT kill-test here too** (relocate `CHAT_CONTAINER` to the top, see if it's occluded).
- Exit: a go/no-go read on native-reproduction effort + the A-ALT kill-test verdict, *before* any A3 polish.

**A3 — Native chat veneer (the single biggest piece; native + device-iterated).**
- Goal: a native OSRS chat box, **repositionable to the top**, with tabs / colours / scrollback / input.
- Work: UIKit/Metal chat view fed by A2 — tabs (All/Game/Public/Private/Clan/Trade), per-message colour,
  scrollback, the input line; **chat-tap → keyboard** using the real chat-input model (closes the long-deferred
  chat-tap→keyboard item, now native). Route back (**A0-settled**): the engine chat is **hidden** (`setHidden`),
  so tabs + scrollback are **native operations over `getMessages()`** (no engine round-trip) and text goes via the
  existing `onText` path — chat needs **no** click-through route-back, the easy case.
- Exit: user-confirmed native chat at the top — readable, scrollable, typeable, tab-switchable.

**A4 — Native minimap veneer (native + device).**
- Goal: a live native minimap drawn from positions, placed where we want.
- Work: render terrain (`getMapScene`), dots (`getMapDots` sprites at actor world→minimap positions), icons
  (`getMapIcons`), compass (`getMapAngle`); **minimap tap → walk** (world point → walk action). Consider
  `setMinimapTileDrawer` only if a finer hook is needed.
- **ROUTE-BACK RESULT — DONE, md5-gated GREEN (`MINIMAPWALK_GREEN_AS_EXPECTED`), 2026-06-04.** Probe
  `MinimapWalkProbe` (`-Drunelite.minimapwalk=true`), harness `run-minimap-walk.sh` + `minimap-walk-verdict.sh`;
  evidence `ios/app/evidence/minimap-walk-trail-20260604.log`. The one hard route-back A0 surfaced is settled:
  clicking the **live** engine minimap 12px off-centre issued a walk (`getLocalDestinationLocation()` → non-null,
  `dest=(6208,7232)`); the **same pixel with the minimap `setHidden` did NOT walk**. So a native minimap routes
  walks by **cover-in-place** — keep the engine minimap LIVE + covered by the native draw, translate native-minimap
  taps → engine-minimap pixels (centre + scale + `mapAngle`) → synth-click. The minimap CANNOT be hidden (kills the
  walk), so it differs from chat (fully native + freely repositionable): the native minimap can move visually, but
  the live engine minimap stays at top-right (invisibly covered) to receive the proxied walk-clicks. No
  walk-to-WorldPoint API exists, so this is the route.
- Exit: user-confirmed minimap that tracks the player and is tappable to walk.

**A5 — Native orbs veneer (native + device).**
- Goal: native HP / prayer / run / spec orbs with actions.
- Work: draw the four orbs from A2 (+ poison/venom/disease/stamina/quick-prayer state colouring per
  `StatusBarsOverlay`'s model). Route back (**A0-settled — hidden orbs aren't clickable**): either keep the engine
  orbs in position and **cover-in-place** (native orbs drawn over the still-live engine orbs → tap = click-through,
  parity-safe), or **hide + direct-invoke** per action (run/quick-prayer/spec via clientscript/varc). Cover-in-place
  is preferred where the orbs stay near their original spot.
- Exit: user-confirmed orbs, tappable.

**A6 — Input + chrome integration (device).**
- Goal: the native HUD coexists with the existing native chrome (tab rails, plugin dock, right-click menu, smart
  snap) under edge-to-edge.
- Work: reconcile hit-testing (native HUD regions vs the game viewport vs rails/dock); ensure touch-snap/menu key
  off the now-full-canvas viewport rect; relayout the existing chrome for full-bleed.
- Exit: all existing controls + the new HUD work together on device.

**A7 — Polish + parity pass.**
- Chat long tail (jagex tags, clan/emoji icons, filters, word-wrap), minimap fidelity, orb edge cases; confirm
  the native HUD does not regress blit cadence; optional `renderH`/DPI bump now that the scene is edge-to-edge.

### Track B — GPU rendering (gated; see `ios/AUDIT-BRIEF-w3-gpu-renderer.md` for internals)

**B0 — GL tier recon (device; ~a day; HARD GATE).** `glGetString(GL_VERSION/RENDERER/SHADING_LANGUAGE_VERSION)`,
`GL_MAX_COMPUTE_*` through the device's GL layer. Settles whether base `gpu` (≥ GL ES 3.3-equiv) and/or 117HD
(≥ 3.1 + compute) are even reachable. **Non-colliding** — a native probe; touches none of the publish seam.

**B1 — LWJGL-iOS offscreen probe (potentially weeks, not days).** iOS-built LWJGL → windowless GL ES/ANGLE
context; per the companion review's harsher gate, compile **RuneLite's actual `vert.glsl`/`frag.glsl`** and assert
`GL.createCapabilities().OpenGL33` (not just a generic GLES triangle); render triangle → FBO → `glReadPixels`
non-blank. Settles B/D/E. **Non-colliding** — isolated probe, no `Hooks.draw`/`JavaBridge` changes.

**B2 — Base `gpu` bring-up (gated; weeks-to-months; THE shared seam).** iOS `librlawt` shim → offscreen context;
down-port GL/GLSL to the available tier; **export `fboScene` to the native blit**. If Track A is done, **skip GPU
UI compositing** (the native HUD replaces it). This is the only Track-B phase that touches Track A's publish seam.

**B3 — 117HD/RLHD (largest; partial success expected).** Compute pipeline + heavy shaders on the proven base.

### Interleaving + isolation rules

1. **B0 runs immediately** (a day, gates everything; can precede or parallel A0). Its verdict is the single thing
   that changes A1's eventual design (CPU publish vs GPU `fboScene`), so knowing it early is cheap insurance.
2. **A0→A7 is the mainline.** It is CPU-viable today and delivers the wanted immersion outcome regardless of B.
3. **B1 may run in parallel with Track A** — it is an isolated probe. Keep it on a dedicated `gpu` **git
   worktree/branch** (the GPU work needs the gitignored local state: symlink `ios/dev-tools/jre`, copy
   `ios/app/jvm-resources/` + `tokens.json`; see `ios/dev-tools/unpack-local-state.sh`).
4. **B2 is strictly gated** behind *green B0 + B1* **and** *A1's publish seam settled/merged*. **Never run B2
   concurrently with A1 in the shared tree** — that is the one combination that corrupts the publish contract.
   When B2 starts, A's native HUD lets it skip UI compositing.

## Claims to validate / refute (the adversarial surface)

**Sequencing claims (the core of this brief):**
- **S1 (REFINED per review) — A and B share one mutable *publish* seam** (`Hooks.draw`→`JavaBridge`) **plus a
  shared frame-geometry contract.** A1 changes canvas size (`HeadlessResize.java:97`) and native touch-mapping
  keys off the blitted image size (`ViewController.m:1306`); B2 sizes/blits FBOs to the canvas (`GpuPlugin.java:898`).
  So both also touch canvas-size / frame-size / viewport / native-view-bounds / point-round-trip. *Gate:* log the
  full frame-geometry contract (canvas, frame, viewport, native bounds, point round-trip) in **both** CPU and GPU
  modes and prove it matches. *Refute:* find a *third* shared mutable point beyond publish + geometry.
- **S2 — Building A on the CPU path wastes no work if B later lands;** only the thin scene-publish seam re-targets
  `fboScene`; A2–A5 (data bridge + native rendering) are path-agnostic. *Refute:* show a hard dependency from
  A2–A5 on the CPU `int[]` specifically (not just on `Client`/world-view APIs).
- **S3 (REVISED per review — was false as written) — A native HUD lets B2 skip *engine-HUD* compositing only, NOT
  all UI compositing.** `interfaceTexture` also carries RuneLite plugin overlays + post-scene Java drawing
  (selection outlines), which `Hooks.draw` renders before the GPU return (`Hooks.java:470`). So B2 keeps
  `drawUi`/`interfaceTexture` as an **overlay-only** texture path (engine HUD masked off via `setDraw2DMask`), not
  a pure scene-FBO publish. *Confirm:* enable a visible RuneLite overlay, skip `drawUi`, prove it disappears —
  demonstrating the overlay path must remain.

**Native-HUD claims (Feature A, not previously reviewed):**
- **A-1 — [SETTLED by A0, 2026-06-04] `setDraw2DMask` does NOT suppress the interface HUD** (it governs
  world-space 2D extras — overhead text/hitsplats). **`setHidden` is the only clean remover** (PNG-confirmed) and
  it makes the widget **un-clickable**, so route-back can't be click-through under it. **`setOpacity(255)`** stays
  clickable but does **not** remove the HUD. No single lever does both → route-back must decouple (cover-in-place
  vs hide + direct-invoke). See **A0 RESULT** above. The `setHidden` re-apply on `TOPLEVEL_REDRAW` (MinimapPlugin
  recipe) still applies.
- **A-2 — RuneLite data APIs are sufficient to reproduce OSRS chat/minimap/orbs faithfully.** *Refute:* identify
  the worst long-tail item — chat jagex-tag/icon/emoji rendering + word-wrap, or minimap-from-positions missing
  terrain/POI nuance — that makes "native reproduction" a multi-week tail rather than a phase.
- **A-3 — Edge-to-edge + HUD suppression does not break the existing native chrome / touch-snap / right-click
  menu,** which currently assume the windowed canvas + engine-HUD geometry. *Refute:* name a control that
  hard-codes engine-HUD pixel positions and would mis-target once the HUD is gone and the canvas is full-bleed.
- **A-4 — Native chat input + tab switch route back parity-safe** (engine widgets/clientscripts, no `menuAction`).
  *Refute:* show tab-switch or chat-send has no parity-safe synth path on the desktop gamepack.

**The cheaper alternative the reviewer should explicitly weigh (open question, not yet decided):**
- **A-ALT (review verdict: likely DEAD, one kill-test left) — Relocate the engine widgets instead of reproducing
  them natively.** `setForcedPosition` / `setOriginalX/Y` + `revalidate()` (stock `interfacestyles` plugin
  repositions tab stones/containers). If the engine chat/minimap/orb widgets could be **moved** (chat→top) rather
  than **redrawn**, Feature A would collapse (~5× cheaper). **Review surfaced strong evidence against it:** the
  launcher already tried repositioned stones over the world and **reverted them because the 3D viewport occluded
  them** (`JVMLauncher.m:679`) — engine z-order draws the scene *over* relocated 2D widgets, fatal for "chat at the
  top over a full-bleed scene" even if `setForcedPosition` holds. This is exactly why **native veneers are immune**
  — they draw in a UIKit layer *above* the blitted scene, outside the engine's z-order. Kept as a single kill-test
  inside the A2.5 spike before any A3 commitment: move `CHAT_CONTAINER` to the top, force `TOPLEVEL_REDRAW`,
  capture, verify it is **visible above the 3D viewport AND live for input**. If it renders behind the scene
  (expected), A-ALT is dead and native chat is confirmed.

## What breaks first (predictions to confirm)

- **A0:** the injected gamepack ignores `setDraw2DMask` (unused flag) → fall to `setHidden`, which not only adds
  `TOPLEVEL_REDRAW` re-application complexity but **kills click-through route-back** (hidden widgets aren't
  clickable, `MobileChromeHideTest.java:42`) → A3/A5 then need varc/clientscript invoke paths.
- **A1:** suppression is *incomplete* — a single un-hidden widget re-introduces the exact revert clipping under
  the notch. The exit gate ("nothing under the corners") must be visually strict.
- **A1↔B2 geometry:** canvas/frame/viewport/native-bounds drift between CPU and GPU modes → touches mis-map
  (`ViewController.m:1306` keys off the blitted image size). The frame-geometry-contract gate (S1) must pass in
  both modes before B2 lands.
- **A2:** chat colour resolution wants `ChatMessageManager`, which may not be cleanly reusable from the
  client-thread snapshot context; per-frame snapshot cost on the client thread.
- **A3:** chat alone is a multi-week sub-project (tabs/colours/scrollback/input/word-wrap/icons) — the single
  largest risk to A's timeline. (See A-ALT: relocation would sidestep this entirely.)
- **B0:** GL layer caps below 3.3-core-equivalent / no offscreen FBO → base `gpu` out, 117HD out.
- **B2:** the desktop-GL-over-iOS ABI cliff (the companion review's single most-likely-fatal blocker): the iOS GL
  layer won't present a desktop-OpenGL-3.3 / LWJGL `org.lwjgl.opengl` environment the unmodified plugin accepts.

## Scope / expectations / what we are NOT doing

- **NOT** rebuilding the mobile gamepack `ToplevelOsm`/601 layout (unbuildable on the desktop gamepack +
  plugin-breaking — the whole reason we go native veneer).
- **NOT** treating 117HD as a gate for anything — it is the last, partial-success track.
- **NOT** running Track A and Track B2 as concurrent big builds in the shared tree.
- Minimap is **drawn from positions**, not a readback of the engine's composited minimap (no API for that — this
  is the intended design, same as the official mobile client).
- Feature A is multi-week (chat dominates). Feature B is multi-week-to-months with a real chance of partial /
  no success — gate it hard at B0/B1 before committing to B2.

## Recommended order (the proposal under review)

1. **B0 (GL recon)** — a day; hard gate; settles whether a GPU scene/UI seam will ever exist.
2. **A0→A7 (native HUD)** as the mainline deliverable, on the CPU path (or relocation per A-ALT if the reviewer
   judges it viable).
3. **B1** in parallel with Track A *iff B0 is green* (isolated `gpu` worktree).
4. **B2** strictly after green B0+B1 **and** A1 merged; never concurrent with A1.

The reviewer's highest-value calls: (a) is recon-first + A-as-deliverable right, or should A wait out the full
GPU gate to avoid re-targeting A1's seam once? (b) is A-ALT (relocate, don't redraw) being too quickly dismissed?
(c) is S2 ("no wasted work") actually true, or is there hidden CPU-buffer coupling in A2–A5?

## Review outcome (folded into this revision)

External review verdict: **the sequencing is right** — run **B0 first**, make **A0→A7 the mainline**, and do not
block the CPU-viable deliverable on B1/B2. Three revisions were called out and are all reflected above:
1. **S3 weakened** — a native HUD skips *engine-HUD* compositing only; B2 still needs an **overlay-only**
   `drawUi`/`interfaceTexture` path for RuneLite plugin overlays + post-scene selection drawing
   (`Hooks.java:470`), not a pure scene-FBO publish.
2. **A0 gains route-back interaction tests** — visual HUD-free is insufficient; each suppression mode must prove
   chat-tab/orb/minimap click-through still drives game state. `setDraw2DMask` is strongly preferred over
   `setHidden` because hidden widgets aren't clickable (`MobileChromeHideTest.java:42`), which would break A3/A5
   route-back.
3. **A-ALT demoted to a single kill-test** run inside the A2.5 spike before any A3 commitment — the 3D viewport
   occludes relocated 2D widgets (`JVMLauncher.m:679`), which is likely fatal; native veneers are immune (they
   draw above the blit).

Also adopted: **S1 refined** to "publish seam + frame-geometry contract" with a both-modes geometry gate;
**A2.5 fidelity spike** added to expose chat/minimap reproduction cost early. B2 still waits on A1, and when it
starts it is an **overlay-only composite path**. S2 ("no wasted work") stands — A2–A5 read `Client`/world-view
APIs, not the CPU buffer; the only path-specific piece is A1's publish seam.

## Key files / pointers

- Publish seam: `runelite-client/src/main/java/net/runelite/client/callback/Hooks.java:498` (snapshot) / `:501`
  (GPU early-return); `net.runelite.client.headless.JavaBridge` (snapshot `207-244`, getters `248-275`, viewport
  gate `883-901`).
- GPU scene/UI seam: `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java` — `fboScene`
  `898`/`1011-1019`, `blitSceneFbo` `1021-1042`, **`draw()` composite order `1403-1479` (esp. `1436-1442`)**,
  `prepareInterfaceTexture` `1365-1401`, `drawUi` `1481-1532`.
- HUD suppression: `runelite-api/src/main/java/net/runelite/api/Client.java:2093-2123` (`setDraw2DMask`);
  `runelite-api/src/main/java/net/runelite/api/widgets/Widget.java:396-411` (`setHidden`), `553-611`
  (`setOriginalX/Y`/relocate); `net/runelite/client/plugins/minimap/MinimapPlugin.java:160-184` (hide recipe,
  `:169`); `net/runelite/client/plugins/interfacestyles/WidgetOffset.java` (relocate prior art).
- Suppression↔route-back evidence: `runelite-client/src/main/java/net/runelite/client/headless/MobileChromeHideTest.java:42`
  (hidden stones aren't clickable → varc-probe fallback); `ios/app/HelloShell/JVMLauncher.m:679` (relocated stones
  occluded by the 3D viewport → A-ALT evidence).
- Frame-geometry contract (S1 gate): `runelite-client/src/main/java/net/runelite/client/headless/HeadlessResize.java:97`
  (canvas size), `ios/app/HelloShell/ViewController.m:1306` (touch→game mapping off image size), `GpuPlugin.java:898`
  (FBO sizing).
- HUD data: `Client.java` chat `945`/`952`, minimap `1021`/`1014`/`1037`/`695`/`2050`, orbs `547`/`918`;
  `net/runelite/client/plugins/statusbars/StatusBarsOverlay.java:113-221` (orb reads reference);
  `net/runelite/client/chat/ChatMessageManager.java` (chat colour resolution).
- Resize plumbing (recoverable): git `348dd55e4..75e5253cf`; `HeadlessResize.applyResize`, `ResizeSoakProbe`.
- Native shell: `ios/app/HelloShell/JVMLauncher.m` (VM bringup, §6.2 mirror/exception-port setup, dylib load),
  `ios/app/HelloShell/ViewController.m` (CADisplayLink blit, `renderH`, chrome).
- Companion brief (GPU internals, already reviewed): `ios/AUDIT-BRIEF-w3-gpu-renderer.md`.
- Worktree local-state restore for the `gpu` branch: `ios/dev-tools/unpack-local-state.sh`.
