# Android ↔ iOS parity phasing

> Verified, phased plan for bringing the Android port to parity with the iOS app's mobile UX.
> Written 2026-06-12 against `android` @ `a310e583c8` and `origin/master` @ `53dcf37114`
> (merge-base `b333c671c2`). Every status below was verified from **code on the two branches**
> (commits cited), not from docs — the planned `IOS-PARITY-BLUEPRINT.md` reference was never
> committed on any branch, so this document re-derives its content directly from the master
> sources and is self-contained.
>
> Scope rule: each phase consumes **shared bridge methods that already exist in the merged
> jar** — zero shared-Java changes are expected anywhere in this plan. If implementing a phase
> ever seems to require a shared-Java edit, STOP and re-check: that is a plan bug, not a task.

Divergence at time of writing: master is **176 commits ahead** of android (all the iOS feature
work: world map, chat pill, engine-resolved touch, renderer-as-plugins, the gpu→master merge);
android is **29 commits ahead** (AP-0→AP-4 mobile-UX batches, legacy autologin, orbs fix,
1.12.28 API pin). All side branches (`chat-pill`, `touch-targeting`, `vanilla-default`, `gpu`)
are fully merged into master — there is **no unmerged in-flight branch work** to avoid.

---

## 1. Verified parity matrix

Status is the **android branch** state, verified from source (docs were cross-checked but code
wins). "Free after merge" = the shared-Java half arrives in the staged jar with Phase 0 and
needs no port work.

| Feature (iOS reference) | Android status | Evidence |
|---|---|---|
| **World map mobile** (floating window, touch pan/zoom, search) | **ABSENT** | Zero hits for `worldmap`/`WorldMap`/map-window under `android/app/src/main` (all spellings checked); no native screen in the Compose set; `WorldMapProbe.java` + `HudVeneerBridge.panWorldMap`/`getMapSnapshot` exist only on master (added `0e8e19cecf`, `74557217e7`) |
| **Chat channel pill** (collapse, fan-out, pull-down filter) | **PARTIAL** (predecessor only) | Android has the *pre-pill* veneer: enlarged tab row + long-press→filter via the native menu overlay (`ChatTabOverlayView.kt:181`, commit `fe04766ad8`) and chat scroll-by-drag (AP-0, `c71188a265`). The pill redesign (collapsed pill, 0.15 s fan-out, drag-to-switch, directional pull-down filter) is iOS-only (`bea94a90a0`…`69ed4cf9e6`) |
| **Renderer selection as plugins** (GPU/117HD in the plugin panel, apply-on-relaunch) | **ABSENT** | Android selects renderers via debug restart buttons (`◆`/`HD`, MainActivity.kt:261–267 marker-flag restarts). `RendererPanelStarter.java` is master-only (added `d2b46053cd` — it was **never deleted on android**, contrary to earlier notes); `MobileUiBridge.loadPluginNoStart` + `HUB_BLOCKED={"117hd"}` (`bb6939d7f1`) master-only |
| **Engine-resolved tap targeting** (settle gate → truth-classified click) | **PARTIAL** (free after merge) | Android's tap path is `GestureDetector → nativeClick → JavaBridge.onClick` (MainActivity.kt:571) against the *old* jar (still has `snapRadius()`; android JavaBridge untouched since merge-base). Master's state machine (`tapTick`, `parkPointer`, `ad41581f2f`/`a183c9782f`) runs from `Hooks.draw` gated on `-Drunelite.bridge=true`, which Android already sets (MainActivity.kt:1397) → **arrives free with the merged jar** |
| **Click ring + interact haptic** | **PARTIAL** | Haptic is DONE: `interactTapSeq` poll → `vibrateTick()` 18 ms one-shot (MainActivity.kt:777). The tap-feedback **ring visual** (yellow=walk / red=interact) is ABSENT — the only ring on Android is the menu-row highlight in `MenuOverlayView`, which is a different thing |
| **Tap-to-Play launch polish** (renderer pref, vanilla default, no stale flags) | **PARTIAL** | One-tap boot to LOGGED_IN exists (autologin, `JagexTokens.kt`); but no persisted renderer preference, no `-Drunelite.forceDisablePlugins=gpuplugin` / `-Drunelite.nosafemode` flag logic (`a289c8fd70`, `d6e37a5c7e`, `04b4e30375` are iOS-only) |
| **Plugin hub pin + 117HD collision block** | **PARTIAL** | Hub browse/install screen DONE (`PluginHubScreen.kt`, `62cf93468d`); the `HUB_BLOCKED={"117hd"}` manifest filter + install refusal is master-side `MobileUiBridge` (`bb6939d7f1`) → free after merge. Version pins (api.base + pluginhub at 1.12.28) coexist cleanly in the merged `runelite.properties` |
| **Overlay safe-insets + chat-avoid** (`setOverlaySafeInsets`, `getOverlayChatAvoid`) | **ABSENT** (free after merge, small native push) | No inset push anywhere in the Kotlin; methods are master-only (`78f73ccfa5`). Android runs edge-to-edge immersive (A7), so cutout-safe overlay corners matter |
| Chrome / HUD relocate trio (chat, side panel, minimap into safe area) | **DONE** | MainActivity.kt:956–1044 actively drives `relocateSidePanel`/`relocateChat`/`relocateMinimap`; `ChromeOverlayView` 2-col tab grid with `setStonesHidden`+`openTab` (A6/A7, pre-merge-base — an earlier audit pass mis-marked this "absent" from a stale doc table) |
| Press-and-peek native menu | **DONE** | `MenuOverlayView.kt` + `tryBuildNativeMenu`/`updateMenuHighlight`/`commitMenuSelection` (MainActivity.kt:1081–1166), iOS-proven bridge |
| Minimap orbs render fix | **DONE** (android authored it) | `HudVeneerBridge.ensureOrbsLoaded` (`3ab27df50e`); survives the merge (verified in the merge-tree result) |

**Android-ahead (do NOT redo; iOS may later want these):** Compose color picker
(`6d1b8efe7b`), dimension+set config controls (`864e9da85e`), XP/Loot/GE native reflows
(`c8c8efd306`, `31eaad75b1`), overlay edit mode (`4f0b7764a0`), infobox menu (`9e7525c039`),
screenshot-to-gallery (`b79639ff36`), pin/star (`edeae94e9f`), incompatible marking
(`8117b62e5a`), sticky-Shift + notifications + link-out (`f26da45cf5`), logout + manual
keyboard (`fe04766ad8`), legacy autologin (`12e7557699`), hub screen (`62cf93468d`).

---

## 2. Phase 0 — merge master → android (prerequisite for everything)

The merge is **far cheaper than previously flagged**. `git merge-tree --write-tree
origin/android origin/master` completes with **zero conflicts** (result tree
`eee7c96afd8c…`, verified 2026-06-12). The three previously-flagged hazards resolve as:

1. `HudVeneerBridge.java` modified on both sides (master: 7 world-map/inset commits; android:
   orbs fix) — hunks don't overlap; the merged file keeps **both** `ensureOrbsLoaded` and
   `panWorldMap`/`getMapSnapshot`/`setOverlaySafeInsets` (verified in the merge-tree result).
2. `RendererPanelStarter.java` / `WorldMapProbe.java`: **never deleted on android** — they are
   new files added on master after the merge-base (`d2b46053cd`, `0e8e19cecf`); the merge just
   adds them. No restore step needed.
3. `tools/shared-contract/baseline.txt` (189 vs 185): auto-merges to **190** = master's 189 +
   android's `FLAG runelite.headless.autologin.legacy`. The real method delta master brings:
   `JavaBridge#armSceneHold/noteSceneDraw/presentReady` (GPU warm-up gate),
   `MobileUiBridge#flushConfigQueued/getConfig(String,String)`.

### Real Phase-0 work (the merge itself is the easy part)

- **(a) Fix the `snapRadius` crash before first boot.** Master **deleted**
  `JavaBridge.snapRadius()` (touch Phase 0, `ad41581f2f`); Android's render loop polls it
  every tick — `g_midSnapRadius = midOpt(...)` will come back null post-merge, and
  `env->CallStaticIntMethod(g_bridgeCls, g_midSnapRadius)` at `jvm_bootstrap.cpp:790` is
  **unguarded** → undefined behavior/abort on the first frame. Remove the poll and
  `g_snapRadius`/`nativeSnapRadius` entirely (Android ships SNAP_RADIUS=0 = parity; the
  machinery no longer exists Java-side). Same sweep: confirm every `midOpt`-resolved method id
  is null-checked before call.
- **(b) Re-baseline the shared contract deliberately.** Master's committed baseline is **stale
  against master's own code**: it still lists `METHOD JavaBridge#snapRadius()` +
  `FLAG runelite.touch.snapRadius` (deleted from code) and lacks `tapTick`/`parkPointer` and
  the `runelite.touch.settleMax`/`settleStable` flags. After merging, run
  `tools/shared-contract/check-contract.sh` — expect exit 2 (the snapRadius "removal" is the
  pre-existing drift surfacing, not a merge mistake) — review the diff, then `--update` and
  commit the re-baseline with this rationale.
- **(c) Restage the runtime jar** (`tools/stage-mobile-runtime.sh`) so the device jar picks up
  the new headless classes (tap state machine, world-map veneer, RendererPanelStarter,
  WorldMapProbe, HUB_BLOCKED, config methods) and the merged `runelite.properties` pins
  (api.base **and** pluginhub at 1.12.28 — both verified present in the merge result).
- **(d) Note for awareness:** the merge also brings the whole iOS GPU/117HD direct-present
  stack under `ios/` — irrelevant to the Android build, no action.

**Exit gate (emulator):** fresh boot → LOGGED_IN; chrome grid + chat/side/minimap relocates
live; orbs render top-right; plugin panel + config round-trip work; no JNI crash in a 10-min
session; `check-contract.sh` exits 0 after the re-baseline commit.

---

## 3. Phases (dependency order)

### Phase 1 — engine-resolved tap targeting + click ring

The state machine is **all shared Java and already wired**: `Hooks.draw` calls
`JavaBridge.tapTick(client)` behind `-Drunelite.bridge=true` (Android sets it,
MainActivity.kt:1397); tap ingress is the existing `onClick` path; `parkPointer` is invoked
Java-side after classification. Native scope is feedback only:

- Click-ring visual keyed off the truth classification: walk → yellow ring, interact
  (`interactTapSeq` advance) → red ring. Poll `getInteractTapSeq()` with a **grace window of
  ~0.25 s** after the tap — the engine truth arrives ~80–160 ms later through the settle
  pipeline (iOS started at 0.10 s and had to widen, `a183c9782f`). Keep the existing 18 ms
  haptic tick as-is.
- Tunables if needed: `-Drunelite.touch.settleMax` (default 12 frames, fail-open) and
  `-Drunelite.touch.settleStable` (default 2).

iOS gotchas to honor (all root-caused on iOS, `f78e237784`):
- **Cursor parking is move-to-(−1,−1), not exit.** The engine does NOT clear its tracked
  position on `MOUSE_EXITED` — hover is ended by *moving* to an unpickable point. The shared
  `parkPointer` does this; native must not post extra EXITED events of its own after taps.
- **No centroid/snap logic native-side, ever** — concave-gap and tall-object misclicks were
  the whole reason the snap design was deleted. The engine resolves the target; native just
  reports the finger.
- **Don't drive the engine cursor while a menu is open until the menu rect is known**, then
  clamp inside it (iOS fix `f82debabe7`, native-side). Android's current menu highlight is
  purely native (no cursor drive — safe today), but this applies the moment any flow streams
  finger moves into an open engine menu (the Phase-4 pull-down filter does).

**Exit gate (emulator):** tap an NPC → red ring + haptic + interaction occurs; tap open
ground → yellow ring + walk; tap a tree canopy *gap* → no misclick-walk-to-wrong-tile
regression; zoom immediately after a tap → no stuck hover label.

### Phase 2 — renderer selection as plugins + Tap-to-Play launch flow

Shared side is free post-merge: `RendererPanelStarter` (gate `-Drunelite.rendererpanel=true`;
one-shot in-world `loadPluginNoStart` of GpuPlugin + HdPlugin so both appear in the panel),
`MobileUiBridge.loadPluginNoStart` (idempotent), `HUB_BLOCKED` hub filter. Native scope:

- Persisted renderer preference (`cpu|gpu|117hd`, default **cpu** — vanilla parity with iOS
  `a289c8fd70`), written from the plugin-panel toggle with a "Restart to apply" notice; no
  live hot-swap.
- Launch flags per mode, mirroring iOS (`d6e37a5c7e`, `04b4e30375`): always
  `-Drunelite.nosafemode` + `-Drunelite.rendererpanel=true`; in CPU and 117HD modes add
  `-Drunelite.forceDisablePlugins=gpuplugin` (GpuPlugin is `enabledByDefault` and will
  auto-start with safe-mode off otherwise — this force-disable must also **win over persisted
  config**, which is exactly what the shared `04b4e30375` change does). Clear all pending
  route/marker flags on Play so stale dev-button taps can't leak in.
- Replace the debug `◆`/`HD` restart buttons as the user-facing path (keep behind the debug
  build if useful); the existing routec/routecrlhd boot markers become the *implementation*
  the preference selects.
- Optional polish from the same batch: gate the first blit on `JavaBridge.presentReady()`
  (with `armSceneHold` at boot) to kill the boot flash — the three methods are in the merged
  contract.

**Exit gate (emulator):** GPU + 117HD both visible in the plugin panel as plugins; toggling
persists across relaunch and boots the selected renderer; CPU default unaffected; 117HD does
NOT appear in the hub screen and a forced install attempt is refused. (Zone/117HD *rendering*
cannot be gated on the emulator — SwiftShader lacks TBO/geometry support; gate on
selection/persistence only, real-Mali confirm stays on the Track-G path.)

### Phase 3 — overlay safe-insets + chat-avoid

Shared side free post-merge (`78f73ccfa5`): `OverlayRenderer` already insets every snap corner
and drops TOP_LEFT below the relocated chat. Native scope is one push:

- On layout/insets change, convert the display-cutout insets to **canvas px** and call
  `HudVeneerBridge.setOverlaySafeInsets(l, t, r, b)`. That's it — `getOverlayChatAvoid()` is
  consumed Java-side by the renderer, not by native.

**Exit gate (emulator with a simulated cutout):** TOP_LEFT-snapped plugin overlay (e.g. Quest
Helper guidance) renders clear of the cutout AND below the relocated chat, both collapsed and
expanded; desktop behavior provably unchanged (insets 0 ⇒ no-op).

### Phase 4 — chat channel pill

Replaces the enlarged-tab-row veneer as the user-facing chat control (keep `scrollChat`
drag-scroll as-is). All bridge methods exist today on both branches (`getChatSnapshot`,
`switchChatTab`, `setChatCollapsed`, `scrollChat`); this phase is pure native gesture work,
porting the iOS design (`bea94a90a0` + fixes):

- At rest: one collapsed pill (one tab wide; iOS uses 150 canvas px) showing the active
  channel. Quick tap = toggle chat collapse via `setChatCollapsed`.
- Hold **0.15 s** → 7 channel chips fan out across the chat width (display-only, original
  32 px tab height — iOS oversized them first and walked it back, `5a70ef21ea`); drag
  sideways + lift = `switchChatTab(index)`; switching auto-expands a collapsed chat.
- **Pull-down filter:** dragging DOWN past a filterable chip's bottom edge opens that
  channel's engine filter menu at the finger; drag to a row + lift applies, drag back up
  cancels. Filterable indices are **2..6 of the 7-tab array** (Public, Private, Channel,
  Clan, Trade); All & Game have no filter (no ▾ hint, no menu). The trigger is
  **directional, not dwell** — hesitation must not misfire. Reveal the ▾ hint as the finger
  nears the chip's bottom edge.

iOS gotchas to honor:
- **Collapse is shove-off-canvas, not hide** (`CHAT_COLLAPSE_OFFSCREEN_DY=3000` in
  `HudVeneerBridge`) — the chatbox stays fully alive; reopen is instant and the selected
  channel persists. Never call `setChatHidden` for collapse.
- **Pull-down filter with the chat collapsed** (`69ed4cf9e6`): the filter button is off-canvas
  while collapsed, so the menu can't open — auto-expand at the pull-down threshold and gate
  the menu open on the button being back on-canvas (one engine-tick rebuild latency).
- The engine filter menu is an *open engine menu*: apply the Phase-1 rule (hold cursor moves
  until the menu rect is known, then clamp inside it).
- iOS also hit a platform-specific infinite-recursion crash in a hover setter
  (`36e3e7f987`, ObjC property/method name collision) — nothing to port, just avoid
  shadowing names in the Kotlin state.

**Exit gate (emulator):** switch channels by pill drag; pull-down applies a Public filter with
the chat open AND collapsed; quick tap collapses/expands with channel preserved;
scroll-by-drag unaffected.

### Phase 5 — world map mobile (floating-window behavior)

Port the **device-confirmed Phase-1 floating-window** behavior (`74557217e7`/`7376456a3f`,
window seeding from `6bde0eae53`/`924b8169c9`) — NOT the fullscreen display transform (see
§4). Shared side free post-merge: `HudVeneerBridge.panWorldMap(dxPx, dyPx)` (converts px →
tiles via the live zoom and moves the map centre — the supported panning primitive),
`getMapSnapshot()` (map-open state + widget rects), `WorldMapProbe` (dev re-verification,
`-Drunelite.worldmapprobe=true`). Native scope:

- Detect map open via `getMapSnapshot()`; while open: hide native chrome/floating buttons,
  hide-or-relocate the chat, suppress the normal walk/interact tap handling inside the map
  rect.
- **Pan = finger drag → `panWorldMap` deltas.** Do NOT synthesize left-drag streams into the
  map — proven dead end (Phase-0 probe `0e8e19cecf`: synthetic drags do not pan the world
  map). Pinch → zoom via wheel notches at the pinch centroid (`onWheel`), with the engine's
  zoom buttons as the fallback path.
- Taps inside the map go through engine-resolved clicks (POI/teleport rows work natively);
  long-press → the existing native menu overlay anchored over the map.
- **Close = the engine's own X**, clicked precisely on its snapshot rect — iOS deliberately
  dropped the native close button (`6bde0eae53`). Do not move the engine window
  (`setForcedPosition` relocation is RED — reported bounds move but the render doesn't).
- **Search:** tapping the search field does NOT flip the engine's input type — native must
  hit-test the search rect itself and raise the IME manually, then route text via
  `onText`/`onKeyStroke`.

**Exit gate (emulator):** open the map from the world-map orb; drag-pan follows the finger
1:1; pinch zooms about the centroid; search raises the keyboard and filters; tap a search
result; close via the engine X; chrome + chat restore.

### Phase ordering rationale

1→2→3 are independent of each other but all tiny next to 4/5 — front-load them for visible
wins per session. 4 before 5 because the pull-down filter exercises the
engine-menu-with-cursor pattern Phase 5's long-press path reuses. Every phase is
emulator-gateable; the standing **real-device verification debt** (all Android claims are
emulator-GREEN only) is orthogonal and unchanged by this plan.

---

## 4. Explicitly EXCLUDED (do not port)

- **World-map fullscreen display transform** (`2386367e01` + polish `53dcf37114`): committed
  on master but **not device-confirmed** on iOS. Port the Phase-1 floating-window behavior
  (which IS device-confirmed, `7376456a3f`); revisit the transform only after iOS confirms it.
- **Minimap enlargement / magnifier**: dead end on both ports. Android already shipped and
  reverted it (Finding 16, `eb61029d2e` → revert `e8fd6d85b7`); iOS's `setMinimapScale`
  exists but ships disabled (`scalePct ≤ 100` = no-op). Leave it.
- **Touch forgiveness SNAP_RADIUS**: ships 0 = parity; master deleted the machinery entirely.
  Android's side of this is *removal* (the Phase-0 crash fix), not a port.
- **iOS GPU/117HD internals** (direct-present, EAGL bridge, shader downports, graphics
  settings panel under `ios/`): Android has its own Track G (AG-phases) for this; nothing
  here is Android-portable.
- **Anything not on `origin/master`**: all side branches are fully merged as of 2026-06-12;
  if new in-flight branches appear, they are out of scope until merged + device-confirmed.
