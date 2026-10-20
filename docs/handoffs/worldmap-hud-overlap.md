# World map — the HUD stops painting over it

**Status:** implemented, gates green (`:client` 726 tests, 0 failures), **NOT yet verified on device.**
The device check is one map open; the build logs the answer either way (see *Reading the verdict*).

## The report

Opening the world map on device (iPhone 17 Pro Max): the map fills the screen, but the minimap cluster's
compass, XP button and HP/prayer/run/spec orbs paint on top of it down the right edge, and the engine's
hover-option text ("Walk here") sits at the top-left. The minimap **globe** is absent — only the orbs and
compass are drawn.

## Why the existing fix wasn't enough

`tickWorldMap` already shoved the relocated containers off-screen
(`shoveOffscreen(MAP_CONTAINER, …)` → `setForcedPosition(x, 3000)`), and that is real: the globe is gone
from the screenshot because the engine rasterises it at the draw widget's live bounds, which followed the
shove. The orbs and compass did not follow. That matches what `applyMinimapScale` already documents about
this cluster — *"the engine hard-pins every minimap orb/icon to a fixed position each frame
(setForcedPosition updates the getter but is ignored at render — readback-confirmed)"* — and the orbs have
a second reason of their own: they are interface group 160, which `ensureOrbsLoaded` opens **into** the
ORBS layer, so they carry their own laid-out position rather than reading the container's each frame.

So moving the container was never going to clear the orbs. The globe/no-orbs split in the screenshot is
exactly the signature of that.

Two further gaps the same change closes:

- the shove was gated on `minimapX >= 0` / `sideX >= 0` — an **active relocate**. iOS never calls
  `relocateSidePanel`, and Android releases both when the rails are toggled off, so in those states
  nothing moved the HUD out of the map's way at all.
- `tick()` runs in the **post-render** callback, so even a working position change lands a frame late.

## What changed

`HudVeneerBridge.setMapHudHidden(client, preEoc, hidden)` — while the world map is open, **hide** the three
toplevel layers that would paint over it:

| layer | what it is |
|---|---|
| `MAP_CONTAINER` | the minimap + orbs + compass cluster |
| `SIDE_CONTAINER` | the inventory / equipment / spellbook panel |
| `MOUSEOVER` | the engine's hover-option text ("Walk here") |

Hiding, not moving, is the point: the draw recursion stops at a hidden container, so nothing under it can
paint itself at any position, hard-pinned or stale. This is the same ancestor-container hide that already
clears the tab-stone rows (the stones themselves are re-pinned; their grandparent's hidden state sticks).

- **Asserted from `preRender`**, the last `BeforeRender` subscriber — the pre-raster seam, so a layer the
  engine re-shows during the client tick gets *zero* painted frames, not one.
- **Unconditional** — no longer gated on an active relocate.
- **Restores only what it hid** (`mapHudHidden`), so a side panel the engine itself closed is never forced
  back on, and `mapHudHiddenPreEoc` pins the restore to the layout the hide went to.
- The existing shove stays, gated as before: it keeps the containers' *bounds* out of the way for the
  things that read bounds. The hide is what the render obeys.

Also: `Widget.isHidden()` walks the parent chain, so `buildOrbSnapshot` now publishes no orbs while the map
is open — the native orb tap pads stop sitting over the map's right edge for free.

Tests: four new cases in `HudVeneerBridgeTest` pin the hide, the restore, that a closed map never touches
the HUD, and that the hide is re-asserted after the engine re-shows a layer.

## Reading the verdict (what to do on device)

Open the map once and read the log for:

```
[veneer] map open — HUD shown after raster? cluster=… side=… hover=…
```

Three lines per open, read **after** the frame rasterised:

- **all `hidden` and the map is clean** → done.
- **all `hidden` but HUD still visible** → the hide stuck and something else is drawing it. Look at the
  RuneLite side: `WidgetOverlay` (`RESIZABLE_MINIMAP_WIDGET`, `CANVAS_TOP_RIGHT`) force-positions this
  container whenever its snap corner `isRepositioned()` or the overlay has a preferred location.
- **any `SHOWN`** → the engine re-shows that layer inside the frame, like it re-pins the stones. Next step
  is one level down: hide the `ORBS` layer and `COMPASSCLICK` directly, same seam.
- **`hover=absent`** → `MOUSEOVER` is not where "Walk here" is drawn and that third hide is a no-op; drop
  it and suppress the label another way.

## Unverified

- Everything above the log line is **inspection, not measurement**. Headless in-game verification is
  blocked: `ios/dev-tools/tokens.json` is from 2026-06-17 and `WorldMapProbe.java` no longer exists, so
  reproducing this off-device needs fresh tokens (interactive, user-only) and a probe to open the map.
- The `MOUSEOVER` hide is the remedy the input-fidelity handoff prescribed for the lingering label
  (*"the fix is to stop drawing the label, not to move the cursor somewhere the finger never was"*), but
  the component has not been confirmed as the one the engine draws it into.
- Android is unchanged on the native side and inherits the Java fix; not checked there either.

## Next step

Build + deploy (`/deploy ios`), open the map, read the line above.
