# World map — the HUD stops painting over it

**Status:** **DONE — device-verified 2026-10-20** (iPhone 17 Pro Max, iOS 27.2, Debug). Both halves
confirmed on the real client, user reporting "that fixed it" on each: the HUD cluster off the map, then the
"Walk here" label gone. Gates green (`:client` 729 tests, 0 failures).

**Commits:** `33aa7c3065` the container hide · `e9700f1586` restore only our own hides · `9728b6ee04` the
tripwire · `b2c109b251` the label is not a widget · `9973ffd6ef` label off client-wide · plus the
one-owner reconciliation below.

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
| `MOUSEOVER` | ~~the engine's hover-option text~~ — **wrong lever, removed**; see below |

Hiding, not moving, is the point: the draw recursion stops at a hidden container, so nothing under it can
paint itself at any position, hard-pinned or stale. This is the same ancestor-container hide that already
clears the tab-stone rows (the stones themselves are re-pinned; their grandparent's hidden state sticks).

- **Asserted from `preRender`**, the last `BeforeRender` subscriber — the pre-raster seam, so a layer the
  engine re-shows during the client tick gets *zero* painted frames, not one.
- **Unconditional** — no longer gated on an active relocate.
- **Restores only what it hid**, per layer, reading and writing each layer's OWN hidden flag. The shared
  `setHidden` helper guards on `Widget.isHidden()`, which counts a hidden *parent* too, so a layer the
  engine had already hidden would have been skipped on the way in and then force-SHOWN on the way out —
  `MOUSEOVER` is the live case, since the engine owns that flag frame to frame, and a hidden toplevel would
  have done it to all three at once. `mapHudHiddenPreEoc` pins the restore to the layout the hide went to.
- The existing shove stays, gated as before: it keeps the containers' *bounds* out of the way for the
  things that read bounds. The hide is what the render obeys.

Also: `Widget.isHidden()` walks the parent chain, so `buildOrbSnapshot` now publishes no orbs while the map
is open — the native orb tap pads stop sitting over the map's right edge for free.

Tests: five new cases in `HudVeneerBridgeTest` pin the hide, the restore, that a closed map never touches
the HUD, that a layer the engine had already hidden is never handed back shown, and that the hide is
re-asserted after the engine re-shows a layer.

## If it ever comes back

The hide holds today, so the build is silent. It is not silent if a layer ever paints over the map again —
`tickWorldMap` reads the three layers **post-raster** (tick runs in the post-render callback, after
`preRender` asserted the hide) and logs once per open:

```
[veneer] world map: a HUD layer painted over it — cluster=… side=… hover=…
```

That is the shape an upstream merge would leave if it started re-pinning these layers. Reading it:

- **any `SHOWN`** → the engine re-shows that layer inside the frame, the way it re-pins the tab stones.
  Next step is one level down: hide the `ORBS` layer and `COMPASSCLICK` directly, same seam.
- **all `hidden` but HUD visible anyway** → the hide stuck and something else is drawing it. Look at the
  RuneLite side: `WidgetOverlay` (`RESIZABLE_MINIMAP_WIDGET`, `CANVAS_TOP_RIGHT`) force-positions this
  container whenever its snap corner `isRepositioned()` or the overlay has a preferred location.

## "Walk here" — the `MOUSEOVER` hide was a no-op (resolved 2026-10-20)

It was a guess off the component's **name**, and the device said no: the label still painted over the open
map. That text is drawn by the client, not by a widget whose hidden flag could end it. The corroborating
tell was in RuneLite's own code all along — `SnapCorners` shifts every `TOP_LEFT` overlay down a hardcoded
`MOUSEOVERTEXT_OFFSET = 15` to clear this text, which is not what you do for a widget you can measure.

`Client.setMouseoverTextEnabled(boolean)` is the hook built for it (`runelite-api`, javadoc names the
top-left corner; implemented in the `injected-client` that ships inside our `client-shaded.jar`; no other
caller in the tree). `MOUSEOVER` is gone from the layer array and the tripwire now reports the flag, so it
can speak for the label it names.

**The label is now off client-wide, not just over the map** (`HOVER_TEXT`, revert with
`-Drunelite.hover.text=true`) — see the input-fidelity handoff for why.

**One owner, deliberately.** The map does *not* suppress the label separately, and nothing ever switches it
back on. A review flagged the first cut, which did both: the per-frame assert owned the flag AND the map
latched its own "we turned it off" to restore on close. In the default config that latch could never fire
(the assert leaves the flag already false, so the map's write is a no-op), but under the kill switch it
could overwrite a plugin's own `false` with `true` on close — the same confusion between *observed* and
*owned* state that the earlier widget-hide bug was. There is no fix for that while two owners share one
boolean, so the second owner is gone. Asking for the desktop label now asks for it over the map too, which
is what the switch means.

## Still open

- **Android** inherits the Java fix and is unchanged on the native side; not checked there.
- **No off-device repro exists** for this. `ios/dev-tools/tokens.json` is from 2026-06-17 and
  `WorldMapProbe.java` is gone, so reproducing it headless would need fresh tokens (interactive,
  user-only) and a probe to open the map. The five unit tests pin what the bridge writes; the tripwire
  above is what watches the half the tests cannot reach.
