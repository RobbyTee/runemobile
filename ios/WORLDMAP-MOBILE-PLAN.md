# Plan — mobile-friendly world map

> Goal: the OSRS world map (opened from the globe next to the minimap) is fully usable by touch,
> matching the official OSRS-Mobile feel: finger-drag pans, pinch zooms, taps work everywhere on the
> map UI, search works with the soft keyboard, nothing is clipped by the curved screen or covered by
> our native chrome. No mouse-era constraints left.

## Why it's broken today

The shell's gesture routing is world-scene-shaped: 1-finger pan = **camera rotate**
(`onCameraPan` → `postCameraDx`, ViewController.m), pinch = wheel notches at the centroid, tap =
engine-resolved click with walk/interact ring semantics. With the world map open, a drag spins the
camera *behind* the map instead of panning it, taps get walk-ring colors that mean nothing on a map,
and the map's own edge UI (close X, bottom bar, search, key panel) sits at the raw canvas corners —
under the bezel/notch and under our relocated chat/minimap veneers.

## Engine facts (grounded in this repo)

- The map is engine interface **`InterfaceID.Worldmap`** (group 595). Useful components (all in
  `runelite-api/.../gameval/InterfaceID.java` `Worldmap`): `MAP_CONTAINER`/`MAP_DISPLAY` (the
  pannable view), `CLOSE`, `ZOOM_IN`/`ZOOM_OUT`, `SEARCH`, `KEY_TOGGLE`/`KEY_CONTAINER`,
  `OVERVIEW_CONTAINER` (the mini overview), `MAPLIST_*` (surface selector), `BOTTOM`, `TOOLTIP`.
- Desktop input model: **left-mouse drag pans** the map, **wheel zooms** (the engine routes a wheel
  whose cursor is over the map to map-zoom), left/right click opens menus on icons (RuneLite's
  `WorldMapOverlay` adds entries, e.g. focus-on). So everything is reachable through the mouse
  primitives we already synthesize.
- `client.getWorldMap()` exposes `getWorldMapPosition()` / `setWorldMapPositionTarget(...)` — useful
  for verifying pans headless and (if ever needed) for a native-side pan fallback.

## Shell primitives already in hand (all device-proven)

- **`postTouchType:x:y:button:`** → `JavaBridge.onTouch` — a press/move/release **drag stream with no
  phantom click**. This is exactly the map-pan primitive; it exists and is unused by the current
  camera path (camera went direct via `postCameraDx`).
- `postWheelX:y:rotation:` — pinch already emits wheel notches at the centroid (`onPinch`).
- `postClickX` (engine-resolved tap + settle) and `postClickPreciseX` (UI tap, no world-snap, no
  ring) — the `chatHitCanvas` routing pattern in `onTap` is the template for "this rect is UI".
- Long-press → engine right-click menu + native big-row overlay (`openEngineMenuAtGameX`).
- Contextual soft keyboard: `textInputOpen` polling already raises the keyboard when the engine
  opens a text field.
- Veneer toolbox for chrome: `setForcedPosition` relocation (chat/minimap pattern), proxy precise
  clicks, op-script replay, per-frame re-apply in `HudVeneerBridge.tick`.

## Phase 0 — recon + off-device harness (no device round-trips)

> **DONE 2026-06-11 — `WorldMapProbe` (`-Drunelite.worldmapprobe=true`, ticked in Hooks) +
> `ios/dev-tools/headless/run-worldmap-probe.sh`; verdict
> `WORLDMAP panned=false pannedApi=true zoomWheel=true zoomBtn=true searchInput=false closed=true
> reloc=RED`. PNGs in `out-worldmap/cap/`. Findings:**
>
> 1. **Synthetic left-drag does NOT pan the map** (two pacings tried: per-frame steps, and
>    hold-then-brisk 2-events/frame; `getWorldMapPosition` + PNG both static). The engine's drag-pan
>    isn't reachable through synthesized AWT drags.
> 2. **`WorldMap.setWorldMapPositionTarget()` PANS — the Phase-1 primitive.** Deterministic, and
>    better than a drag: the shell converts finger px → tiles via `getWorldMapZoom` and moves the
>    centre 1:1 with the finger. PNG-confirmed.
> 3. **Wheel zoom works** over the map (3.0 → 4.0 on one synthetic notch) — pinch stays as-is.
>    **Zoom buttons also work** (fallback unneeded but proven).
> 4. **The map search does NOT flip `VarClientInt.INPUT_TYPE`** — the soft-keyboard auto-raise won't
>    fire. Native must raise the keyboard itself on a tap in the search rect while the map is open
>    (typed chars go to the canvas; verify the field consumes them in Phase 1).
> 5. **Close works but is SLOW (~38 frames)** — the native close proxy should tolerate latency.
> 6. **Relocating the map window is RED**: `setForcedPosition` on `UNIVERSE` moves the *reported*
>    bounds but the render stays edge-to-edge (the tab-stone "getBounds lies" pattern,
>    pixel-verified). Phase 2 = native safe-area proxy buttons, NOT window relocation.
> 7. Layout recon: on the device-shaped canvas the reopened map is **fullscreen** (1173x540) — it
>    covers everything, so the native chrome must hide while the map is open. Edge UI lands exactly
>    in the curved corners: CLOSE top-right (26x23), SEARCH bottom-left, ZOOM bottom-right, key
>    panel top-left.

1. **Map snapshot**: add a tiny worldmap section to an existing bridge snapshot (chrome or hud):
   `mapOpen` (`Worldmap.MAP_CONTAINER` non-null + not hidden) + canvas bounds of `MAP_CONTAINER`,
   `CLOSE`, `ZOOM_IN/OUT`, `SEARCH`, `BOTTOM`, `KEY_CONTAINER`. Mirrors `getChatSnapshot`.
2. **Headless input proof** (run-chat-veneer.sh pattern): autologin → synth-click the world-map orb →
   `onTouch` DOWN/MOVE/UP stream across `MAP_DISPLAY` → assert `getWorldMapPosition()` moved + frame
   captures show the pan. Same run: synthetic wheel over the map → assert zoom changed (worldmap zoom
   var/render), clicks on `ZOOM_IN`/`CLOSE` work, `SEARCH` opens a text field that flips
   `textInputOpen`.
3. **Layout recon**: steady-state frame dumps of the open map at our 1173x540 canvas — what the map
   window covers, where its edge UI lands vs the safe area, what our relocated chat/minimap/tab-grid
   overlap. Also test once whether `setForcedPosition`/`setOriginalWidth` on `Worldmap.WINDOW` sticks
   over N frames or is re-pinned like the tab stones (decides Phase 2 option A vs B).

Phase 0 answers every unknown below before any device work.

## Phase 1 — mode detection + input routing (the core)

> **DONE + DEVICE-CONFIRMED 2026-06-11 (`74557217e`, user: "everything feels good").** Built with the
> essential Phase-2 pieces: bridge `panWorldMap` (px → tiles via live zoom, sub-tile remainder
> carried) + `getMapSnapshot`; native gates (map pan / precise taps / no ring), chrome hide/restore,
> native safe-area ✕ proxying the engine CLOSE, search-tap keyboard raise. Pinch zoom unchanged.
> Remaining = Phase 3 polish only (momentum pan, double-tap zoom) — build on demand.

- Native polls `mapOpen` (~10 Hz with the other snapshots). While open:
  - **Pan**: a 1-finger drag that *begins inside* `MAP_CONTAINER` becomes a synthetic left drag —
    `postTouch DOWN` at the start point, incremental `MOVE`s (canvas px 1:1 with the finger), `UP` on
    lift. Begin-gate in `onCameraPan` exactly like the `chatScrolling` branch; camera never sees it.
  - **Pinch**: keep the existing wheel-notch pinch (Phase 0 verifies the engine maps it to map-zoom).
    Fallback if wheel doesn't reach the map: precise-click `ZOOM_IN`/`ZOOM_OUT` per accumulated notch.
  - **Tap**: keep `postClickX` (map icons/menus are engine-resolved), but suppress the walk/interact
    ring + haptic semantics while `mapOpen` (a map tap is neither a walk nor an interact).
  - **Long-press**: existing engine-menu path unchanged; verify the native big-row overlay anchors
    over the map (it should — it's view-space).
  - **Close/zoom/search taps**: already work as engine-resolved clicks; if any prove flaky, route
    their snapshot rects through the `postClickPreciseX` UI-rect path like `chatHitCanvas`.

## Phase 2 — chrome + layout coexistence

- **Native chrome**: while `mapOpen`, hide the overlapping veneers (chat pill + native tab rails +
  panel dock; the relocated minimap cluster if the map covers it) and restore on close — official
  mobile shows the map full-screen. One `mapOpen` edge-trigger in the existing ~10 Hz tick.
- **Curved bezel**: pick per Phase-0 recon:
  - **A (preferred if it sticks)**: relocate/resize the map window (`Worldmap.WINDOW` or `UNIVERSE`
    child) into the safe rect via the proven `setForcedPosition` veneer pattern.
  - **B (proven fallback)**: native safe-area buttons proxying the clipped controls — close, zoom,
    search — via precise clicks on their snapshot rects (the bigger-chat-tabs pattern).
- **Search keyboard**: Phase 0 tells us if `textInputOpen` already catches the map search field; if
  not, extend that poll to the search widget specifically.

## Phase 3 — mobile polish (optional, after device-green core)

- **Momentum pan**: on lift with velocity, continue a decaying `MOVE` stream before the `UP` (UIKit
  gives the velocity; cap duration ~0.4 s).
- **Double-tap = zoom in** at the tap point (wheel +N); two-finger tap = zoom out (official mobile
  convention).
- Map-key panel ergonomics (bigger touch targets via a native veneer) only if real use shows misses.

## Risks / unknowns (all retired by Phase 0)

1. Does a synthetic wheel over the map zoom the MAP (not the camera)? Fallback: zoom buttons.
2. Does `onTouch` drag actually pan the map headless? (Engine drag-pan is plain AWT drag — expected
   yes; `getWorldMapPosition()` is the oracle.)
3. Is the map window re-pinned per frame (tab-stone behavior) or relocatable (chat behavior)?
4. Does the map search field flip `textInputOpen`?
5. Overlap reality: exactly which veneers cover the open map at our canvas size.

## Build loop

- Java (snapshot + any veneer work): `./gradlew :client:delombok :client:shadowJar -q` →
  `ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh` → app build. Headless harness first
  (`ios/dev-tools/headless/`, run-chat-veneer.sh pattern) for everything in Phase 0/1 input; device
  (StikDebug, user-driven) only for gesture feel + final confirms.
