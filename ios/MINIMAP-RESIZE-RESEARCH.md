# Minimap resize — research / handoff (iOS CPU client, W3 Feature-A)

Goal: make the top-right minimap bigger. This doc is the research for a fresh session to implement it.
Same project rules apply (CPU renderer; resizable Classic layout PRE_EOC / topLevel 164; do NOT lower renderH —
that globally scales everything, already rejected for the chat).

## Current state (baseline)
The minimap+orbs cluster is the **live engine HUD**, RELOCATED (not redrawn) to the top-right:
- `HudVeneerBridge` minimap block in `tick()` force-positions `MAP_CONTAINER` each frame (`relocateMinimap`,
  driven from `ViewController.updateHudLayout` → `relocateEngineMinimapX:y:`, cluster width `MM_W = 211`).
- It stays click-to-walk + orb-clickable because it IS the engine minimap (MinimapRelocateProbe proved this).

## CONFIRMED mechanism (verified off-device this session)
**Resizing the minimap DRAW-AREA widget makes the engine render a bigger globe**, and click-to-walk follows for
free:
- The draw-area widget is `InterfaceID.ToplevelPreEoc.MINIMAP` (0x00a4_001e) — i.e.
  `WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA`. (OSRS_STRETCH variant: `ToplevelOsrsStretch.MINIMAP`.)
- Setting it to ABSOLUTE width/height (`setWidthMode/HeightMode(ABSOLUTE)` + `setOriginalWidth/Height` +
  `revalidate`) → the engine draws a LARGER circular globe (terrain + dots scale up).
  - Verified: 152×152 → 258×258 at 170%; the globe rendered bigger (desktop capture).
  - Off-device probe used: `HudVeneerBridge.minimapResizeProbe` gated by `-Drunelite.hudveneer.test=true` +
    `-Drunelite.hudveneer.test.minimapScalePct=NNN`. Iterate via
    `ios/dev-tools/headless/run-chat-veneer.sh` with `EXTRA_JVM_ARGS="-Drunelite.hudveneer.test.minimapScalePct=170"`,
    then crop the top-right of `out-chat-veneer/cap/frame-LOGGED_IN-*.png`.
- **Click-to-walk needs NO change**: `Perspective.localToMinimap` computes the minimap centre from
  `minimapDrawWidget.getCanvasLocation() + getWidth()/2, getHeight()/2` and places dots at
  `worldDelta * (getMinimapZoom()/LOCAL_TILE_SIZE)`. A bigger draw widget → correct centre → accurate walk clicks.
- **Zoom vs size**: `Client.getMinimapZoom()` = pixels-per-tile (default 4) = ZOOM (content density), INDEPENDENT
  of widget size. A bigger widget at the same zoom shows MORE map area (bigger window). To show the SAME area but
  scaled up, also raise `setMinimapZoom(4 * scale)` (e.g. 170% → ~6.8 px/tile). Decide which feel is wanted.

## The CATCH (also verified — see the 170% capture)
The globe scales, but the surrounding minimap **chrome are separate fixed-size/position widgets that do NOT scale
or move with the globe**, so they misalign over the enlarged globe:
- Frame/border ring: `MAP_MINIMAP_GRAPHIC6` (0x00a4_001d) / `MAP_MINIMAP_GRAPHIC9` (0x00a4_0020) + the resizable
  map-surround sprite — stays at the old radius.
- The compass.
- The ORBS cluster: `ToplevelPreEoc.ORBS` (0x00a4_0021) — XP, hp, prayer, run, spec, quest icon — stays at the
  original positions (now overlapping the bigger globe's lower-left).
- Corner icons: world map, wiki, store/close.
These are children of `MAP_CONTAINER` but with fixed sizes + positions; growing `MINIMAP` only grows the globe.

## Approaches (recommended → fallback)
**A. Engine-widget resize (recommended — crisp, click-to-walk free).** Resize `MINIMAP` (globe) AND reposition the
chrome to hug the bigger globe: `setForcedPosition` the ORBS cluster / compass / corner icons around the new
radius (same live-widget relocate the rest of the HUD uses), and HIDE or replace the frame ring (a frameless
circle reads clean, or swap a bigger surround sprite). Orbs stay their own size but sit correctly — matches the
official-mobile feel. Globe is the real engine render (crisp); walk + orb clicks keep working (live widgets).
Effort is in the per-child repositioning; iterate it on the off-device probe above.

**B. Native scaled blit (fallback — uniform but blurry).** Crop the whole `MAP_CONTAINER` region from the blitted
frame and draw it scaled in a UIImageView over the original (everything scales uniformly, no misalignment), then
map touches back to engine canvas coords for walk (`localToMinimap` inverse) + each orb. Uniform scale, but the
result is an upscaled small render (soft), and you re-implement all the hit-testing. Only if A's chrome
repositioning proves too fiddly.

## Key files / IDs / APIs
- `runelite-client/.../headless/HudVeneerBridge.java`: `relocateMinimap` + the minimap block in `tick()`;
  `minimapResizeProbe` (off-device test scaffold — extend it to also reposition the orbs/frame for iteration).
- `ios/app/HelloShell/ViewController.m`: `updateHudLayout` (`relocateEngineMinimapX:y:`, `MM_W`); add a native
  `setEngineMinimapScale:`-style facade + `JVMLauncher` reverse-JNI if driving size from native (mirror the chat
  `setChatTabRowHeight`/`scrollChat` facades).
- IDs: `ToplevelPreEoc.{MAP_CONTAINER, MAP_MINIMAP, MINIMAP, ORBS, MAP_MINIMAP_GRAPHIC6, MAP_MINIMAP_GRAPHIC9}`;
  `WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA`.
- APIs: `Widget.setOriginalWidth/Height`, `setWidthMode/HeightMode(WidgetSizeMode.ABSOLUTE)`,
  `setForcedPosition`, `revalidate`; `Perspective.localToMinimap`; `Client.getMinimapZoom/setMinimapZoom`.
- Off-device iteration: `ios/dev-tools/headless/run-chat-veneer.sh` (CACIO software renderer = same as device) +
  `-Drunelite.hudveneer.test.minimapScalePct`.

## Open questions for the new session
1. Does the circular CLIP scale with the widget, or is a fixed-radius mask left behind at large scales? (The 170%
   capture looked clipped to the bigger circle, but confirm at the target scale.)
2. Exact orb/compass/corner-icon target positions for the chosen scale (tune on the off-device probe).
3. Frame: hide it (frameless) vs. a scaled/replacement surround sprite — pick the look.
4. Keep map area (raise minimapZoom) vs. show more area (leave zoom)? — user preference.
