# iOS tap-to-interact — implementation plan

> Branch `touch-targeting` (off `master`). Base for this plan: `dbce4c38f`. All file:line refs
> are as of that commit and will drift once Phase 0 edits land — re-grep before trusting them.

## Goal
Make left-tap targeting ("tap an NPC/object → interact; tap ground → walk") as reliable as
desktop RuneLite / official OSRS mobile. Right now it is unreliable.

## How the engine resolves a left click (the model everything follows from)
- The menu is rebuilt **once per client tick (20ms)**, in this order: packets → interface tick →
  `ClientTick` → clientscripts → **menu build+sort** (`PostMenuSort`, only while no menu open) →
  **menu click detection** (`MenuOptionClicked`) → `PostClientTick`.
- A click is **never re-hit-tested**. The press is consumed at the click-detection step against
  the menu built earlier **in the same tick**. The click's coords matter only for side effects —
  notably the **Walk destination = the tile under the click point**.
- The **left-click default is the LAST element of `getMenuEntries()`** (same as
  `MenuOpened.getFirstEntry()`).
- **Entity picking is engine-side and renderer-independent** on the current client (upstream
  `b6a35c883` removed GpuPlugin's `checkClickbox`; the engine culls/picks clickboxes itself —
  see comment at `runelite-client/.../plugins/gpu/GpuPlugin.java:1198`). The engine's own hit
  test is AABB → per-triangle with a **5px slop (20px for "clickable" models)**. Bug 3 below is
  on-device proof the engine picks entities at a *synthetic* cursor position.
- `ClientThread.invokeLater` callbacks drain at `Hooks.tick()` = the **start** of a tick, *before*
  that tick's menu rebuild. Our synthetic AWT events dispatch on the Cacio EDT asynchronously to
  the tick pipeline. So every "one cycle later" hop in the current code is a **cross-thread race**,
  not a synchronization. Reading `getMenuEntries()` one `invokeLater` after a synthetic move reads
  the menu built **before** the move was folded → "WALK for everything" (our reverted attempt).
- Desktop is reliable because a physical cursor **parks** on the target for many ticks before the
  press; the menu has been rebuilt from the exact point dozens of times. Reliability comes from the
  parked position, not event timing.

## Root causes
- **Bug 1 (outline arms but walks).** (a) Every snap path clicks the **convex-hull centroid**
  (`JavaBridge.java:862-880`), which lands in concave gaps (tree canopy, archway) → engine picks
  nothing → tail = "Walk here". The outline is armed from a *separate* hull test at the *tap*
  point and never reconciled with the click outcome. (b) The EDT-vs-tick race posts the click a
  blind cycle after the move, often against a menu built for the previous position.
- **Bug 2 (bordered but walked to a far tile).** Same as 1(a) via walk coords: Walk dest = tile
  under the **click point** = under the centroid, which for a tall object's high screen centroid
  rays to a tile far behind it.
- **Bug 3 (stuck hover label on zoom).** `clearHover` posts `MOUSE_EXITED` at (−1,−1)
  (`JavaBridge.java:520-536`), but the engine does **not** clear its tracked mouse position on
  EXITED — the synthetic cursor stays parked, the engine keeps picking there each tick, and a zoom
  re-projects the object under that point → label reappears. Hover ends only by **moving** to a
  point that picks nothing, not by exiting.

## Official OSRS mobile (the target behavior)
Same engine. Tap = execute the **top menu entry at the tap point**; tap-hold = open the menu. No
3D aim-assist / tap radius (clickboxes "function as on PC"). No hover text. Feedback ("Entity
Highlights" silhouette + haptic) is drawn **after** the action, from what was actually hit. The
loop: *park the position at the tap → let the engine resolve it through the normal per-tick menu
pipeline → execute the top entry → confirm visually from the truth.*

## Chosen approach — Engine-resolved tap (drive the engine, sync on observables, feedback from the consumed click)
Move the synthetic cursor to the **literal tap point** → wait until the engine has demonstrably
resolved it (cursor-position match **and** stable menu fingerprint) → post a real click at the tap
point → classify what actually fired from `MenuOptionClicked` → arm the outline/haptic from that →
park the cursor at a neutral point. "Outline shown ⇔ interaction happened" then holds *by
construction*. Every edge case (overlap, multi-tile, tall models, moving NPCs, zoomed out) inherits
desktop behavior because the engine makes every decision.

**Rejected:** direct `client.menuAction(...)` dispatch — the single overload at
`runelite-api/.../Client.java:2066` carries no `worldViewId` and never runs `MenuEntry.onClick`
consumers, so plugin-created/swapped entries (Menu Entry Swapper, NPC-indicator Tag) break. Keep
only as a documented fallback. Pure geometric self-resolution is strictly dominated (our hull test
can't match the engine's slop-radius triangle test — that mismatch *is* the current bug).

## Reuse: the settle primitive already exists
`runelite-client/.../headless/MenuPeek.java` is desktop-green (md5-gated) and already does exactly
the settle-on-observable we need: post move → end tick → re-read the `getMenuEntries()` tail every
frame, fingerprint it (`option|target|type|id|p0|p1|itemId`), and accept when stable for
`STABLE_NEEDED=2` consecutive reads, capped at `MAX_SETTLE=12` frames. Model the new gate on it.

## Implementation (against JavaBridge.java)

### Phase 0 — remove the wrong machinery
- Left taps always click the **literal tap point**. Delete the centroid click-point selection and
  the snap scan from the click path (`resolveSnapTarget` / `hullCentroid` / `SMART_SNAP` branch in
  `startLeftTap`, ~`JavaBridge.java:637-686`). Keep `armEntityOutline` / `drawSelection` (the
  render half stays — it's now fed from the consumed click, not the pre-click hull test).
- Remove the arm-at-resolution outline + `interactTapSeq++` from `startLeftTap`; both move to
  click confirmation.

### Phase 1 — the tap state machine (`IDLE → AWAIT_ENGINE → CLICKED → IDLE`, client-thread only)
1. **Tap ingress** (`onClick` BUTTON1, in-game): `ct.invoke` → if `client.isMenuOpen()`, route to
   the existing menu-select path; else record `pendingTap(x,y, frame=0, stable=0, fp=null)`,
   `postMove(canvas,x,y)`, state `AWAIT_ENGINE`.
2. **Per-frame settle gate** — add `JavaBridge.tapTick(client)` called once per frame from
   `Hooks.draw`, beside `MenuPeekBridge.tick` (~`Hooks.java:560-566`, client thread, menu settled).
   In `AWAIT_ENGINE`:
   - Read `client.getMouseCanvasPosition()` and the tail of `client.getMenu().getMenuEntries()`;
     fingerprint the tail (MenuPeek `:452-493`).
   - **Gate: position == tap AND fingerprint identical for 2 consecutive frames.** Position match
     proves the EDT folded the move; stability proves the engine rebuilt from it.
   - Pass → `postClickButtons(canvas,x,y,BUTTON1)` at the **tap point**, state `CLICKED`.
   - Timeout (12 frames) → click anyway (fail-open, never drop a tap), log `settle-timeout`.
3. **Truth-based confirmation** — register on the EventBus once (lazy pattern:
   `RuneLite.getInjector().getInstance(EventBus.class)`, see
   `headless/InfoBoxBridge.java:110-125`; `@Subscribe` needs an instance listener object):
   - **`onMenuOptionClicked`** (fires for every left click incl. walk/Cancel) when state==`CLICKED`,
     classify `entry.getType()`:
     - `WALK` → yellow ring, no `interactTapSeq`. `CANCEL` → nothing.
     - else (`NPC_*`/`GAME_OBJECT_*`/`GROUND_ITEM_*`/`PLAYER_*`/widget/`RUNELITE*`) →
       `interactTapSeq++` (red ring + haptic) and **arm the outline from the entry**:
       `entry.getActor()` for NPC/player; objects via `param0/param1` scene coords + identifier
       using the `plugins/interacthighlight/InteractHighlightPlugin.java:260` `findTileObject`
       pattern (`client.getWorldView(entry.getWorldViewId()).getScene().getExtendedTiles()`, +40
       offset for the top-level view, match `getGameObjects()/getWallObject()/...` by id). Skip
       outline for widget ops.
     - then `ct.invokeLater(JavaBridge::parkPointer)`, state `IDLE`.
   - **`onMenuOpened`** when state==`CLICKED`: the click force-opened the engine menu (deprioritized
     top entry) → hand off to the existing native menu flow (`menuBuilding`/`menuPeekSnapshot`
     polling the long-press uses), state `IDLE`, no park (menu needs the cursor inside it).
   - `CLICKED` timeout (~10 frames, neither event) → park + `IDLE` + diag (shouldn't happen).
4. **`parkPointer` replaces `clearHover` semantics** (post-tap, post-menu-select, camera/pinch
   begin): post `MOUSE_MOVED` to **(−1,−1)** *then* `MOUSE_EXITED`. Moving — not exiting — ends
   hover. This is the bug-3 fix (also kills the camera-drag stuck-label variant). Fallback if the
   engine clamps negatives: park at `(canvasW+50, canvasH+50)`.
5. **Re-entrancy:** a new tap during `AWAIT_ENGINE` replaces the pending tap (re-postMove, reset
   counters). During `CLICKED`, queue exactly one follow-up and start it on the
   `MenuOptionClicked`/timeout transition.
6. **Native** (`ios/app/HelloShell/ViewController.m`): extend the ring/haptic grace 0.10s → ~0.25s
   (truth now arrives ~80-110ms post-tap). Optional latency polish: arm the outline at settle-time
   from the predicted tail, reconcile/cancel at `MenuOptionClicked` on mismatch.
7. **Diagnostics** (keep `[touchdiag]`, extend): per tap log settle-frames, position-match latency,
   predicted-tail vs consumed-entry (the ⇔ check), and a park-verification read one frame after
   `parkPointer` (expect Cancel-only tail). Keep `diagWalkDest`. The right-tap diag read at
   `JavaBridge.java:610-623` has the same tick-start staleness — move it to a frame read or ignore.

### Phase 2 (optional, after device-green) — tap forgiveness
If the settled tail is WALK and `SNAP_RADIUS>0`, re-aim through the *same* gate at 2-3 candidate
points nudged toward the nearest entity hull (~2 frames each). Mobile ships zero forgiveness, so
default stays radius 0 = parity; this is strictly additive.

**Non-issues by design:** MenuEntrySwapper (we never reconstruct entries — the engine consumes
whatever MES swapped at the build step; our `Hooks.draw` reads are post-swap). Moving NPCs (cursor
parked, engine re-resolves each tick). UI/widget taps (same flow; tail is a widget op).

## Device-verify checklist (things not statically provable)
1. **(−1,−1) `MOUSE_MOVED` neutralizes picking** (no Walk entry, no mouseover, no hover). Confirm:
   tap a tree → interact → zoom in/out; label must never reappear; park-verify diag shows
   Cancel-only tail. Fallback: park off-canvas `(canvasW+50,canvasH+50)`.
2. **Settle latency on iOS** (frames to position-match + stable fp). Desktop ≤2; device EDT may
   lag. The `[touchdiag]` settle counters answer it in one session → tune `MAX_SETTLE`/native grace.
3. **`MenuOpened` fires on a force-open left click** (deprioritized top entries are rare). Cheap
   desktop check via the MenuPeek runner.
4. **`getMouseCanvasPosition()` reflects synthetic moves 1:1 on device** (backs the gate's position
   check). The settle diag verifies it on the first run; if it never matches, the fp-stability half
   still gates and the diag says which.

## Verified ground truth (do not re-derive)
- Coordinates are exact: engine canvas = viewport = **1173×540**, offset 0,0, DPI 1.0, taps map 1:1.
  NOT a coordinate bug.
- Client/scene reads must be on the client thread (`ClientThread.invoke`).
- `getMenuEntries()` read one `invokeLater` after a synthetic move is structurally one rebuild
  behind (the reverted verification failure). Sync on the MenuPeek settle observable instead.
- The engine picks entities at synthetic cursor positions on this device (bug 3 is the proof).

## Build & test loop (from a checkout of this branch with build deps present)
```
# 1. client jar (JavaBridge etc.)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :client:delombok :client:shadowJar -q
# 2. stage the iOS jars (gpu jar carries JavaBridge; zone jar carries rlhd/HdPlugin)
( cd ios/dev-tools/gpu-routec && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./stage-ios-gpu-jar.sh )
( cd ios/dev-tools/gpu-zone   && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./stage-ios-zone-jar.sh )
# 3. app
( cd ios/app && xcodegen generate )
xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell \
  -destination "generic/platform=iOS" -derivedDataPath build/dd-gpu -allowProvisioningUpdates build
xcrun devicectl device install app --device <UDID> \
  build/dd-gpu/Build/Products/Debug-iphoneos/HelloShell.app
```
Bridge-only (`routec_zone_jni.m`) changes skip steps 1-2 (jars unchanged). The `[touchdiag]` logs
go to `Documents/jvm-stdio.log`; pull with
`xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer
--domain-identifier dev.audaro.helloshell --source Documents/jvm-stdio.log --destination /tmp/...`.
The app must be **fully quit** before pulling (the log is held open while running). Device UDID:
`AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B` (iPhone 17 Pro Max). The user drives the StikDebug launch.

## Current code state on this branch (what the new session starts from)
- `routec_zone_jni.m` — **GPU UI-composite fix** (`z_glTexSubImage2D_J`, BGRA→UNSIGNED_BYTE). This
  made the engine UI/overlay layer composite at all (HUD, hit splats, HP bars, markers, outlines).
  Keep — it is independent of targeting and should merge to `master` once verified.
- `JavaBridge.java` — vanilla click-cross hidden (`hideClickCross`); WIP centroid/model-hull
  targeting (to be **replaced** by the state machine above); `[touchdiag]`/`diagWalkDest` logging.
- `ViewController.m` — native right-click menu teardown deferred until the engine menu closes
  (`beginMenuClose`) so the engine menu doesn't peek through. Keep.
- Diagnostics are gated `-Drunelite.touch.diag=true` (set in `JVMLauncher.m`). Strip before the
  final merge to `master`.
