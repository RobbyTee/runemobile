# A6 — native Jagex-Mobile chrome (seam)

Phase A6 brings the OSRS-Mobile chrome: the **native 2-column tab grid** at the bottom-right with the engine's
own tab stones hidden, the open side panel pulled flush-left of the grid, and (next) the engine **chat
relocated to the top**. Like A4/A5, it rides shared bridges already in the jar — no new shared Java:

- **`MobileChromeBridge`** (gate `-Drunelite.mobilechrome=true`) — read-only `getSnapshot()` → per-frame
  escaped JSON of the tab-stone / side-panel / minimap / orb / chat / viewport **canvas bounds**, the active
  tab, and `topLevelId` (the layout guard). The native grid reads this each display tick for the active-tab
  highlight + to know when to engage (only the Classic/PRE_EOC layout, toplevel 164).
- **`HudVeneerBridge`** (gate `-Drunelite.hudveneer=true`) — the chrome **write** surface:
  `setStonesHidden(true)` hides all 14 engine tab stones (the native grid replaces them), `openTab(i)` opens a
  tab by replaying its stone op script (works while hidden — no widget moved or covered), and
  `relocateSidePanel(x,y)` pulls the open inventory/skills/etc. panel flush-left of the grid.
  `relocateChat(x,y,transparent)` (chat-at-top) is wired in a later increment.

The native grid mirrors the proven iOS shell (`ViewController.updateChrome`): all 14 tabs in a 2-col × 7-row
grid at the bottom-right via `TAB_GRID_ORDER = {3,1,4,2,5,13,6,7,0,12,8,9,10,11}` (grid slot → engine stone
index), each cell a bundled OSRS stone sprite (`tabassets/stone-active|inactive.png`) composited with the tab
glyph (`tabassets/icon-0..13.png`). The same sprites the iOS app uses, so the two shells match pixel-for-pixel.

## Seam gate (this dir)

`check-a6seam.sh` proves the **host reaches the chrome read surface across the VM boundary** and that the
surface **resolves real geometry** in the current Android headless layout.

```
cd android && JAVA_HOME=… ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.audaro.helloshell/.MainActivity --ez census true
android/dev-tools/a6-chrome/check-a6seam.sh      # greps the host self-test sentinel
```

`runA6SeamSelfTest` (census mode, MainActivity) reads `JvmLauncher.chromeSnapshot()` on the rl-bridge thread
and polls the **live** snapshot for ~40s after login, because the bridge's own one-shot `[chrome] CHROME_READ`
verdict fires ~60 LOGGED_IN frames in — **before the in-world scene/toplevel builds** (it logs
`topLevel=165`=TOPLEVEL_DISPLAY, everything absent). Polling the live snapshot instead catches the resolved
state.

## Baseline GREEN (emulator `rl_arm64`, 2026-06-08)

```
ANDROID_A6_SEAM_GREEN chrome read resolved: rootKind=PRE_EOC resized=true topLevel=164
  activeTab=-1 viewport=true tabsNonNull=14/14 sidePanel=false minimap=true orbs=true chat=true
```

**Key finding:** with the gamepack forced into RESIZABLE display mode (A7, `preferences.dat` byte[3]=2) the
chrome read surface resolves in the **mobile Classic/PRE_EOC layout (toplevel 164)** at the full screen canvas
(1280×720) — all 14 tab stones, the viewport, minimap/orbs/chat. `topLevelId == 164` is the native grid's
engage condition (the iOS LAYOUT GUARD): the `TAB_GRID_ORDER` stone indices map to real PRE_EOC stones, so the
grid only takes over there. (Earlier, before A7, the seam resolved FIXED 765×503 / toplevel 548 — the
cover-and-proxy approach used then; the 2-col grid replaced it once resizable unblocked the mobile layout.)

## UI gate (`check-a6ui.sh`) — native 2-col tab grid

`ChromeOverlayView` is a full-screen interactive View stacked over the SurfaceView (below the menu overlay). A
Choreographer tick reads the **render-thread-published** snapshot (`JvmLauncher.chromeSnapshotPub()` — lock-free,
no HotSpot attach) for `loggedIn` / `topLevelId` / `activeTab`. In the supported Classic/PRE_EOC layout
(toplevel 164) it hides the engine stones (`HudVeneerBridge.setStonesHidden(true)`, off the UI thread via the
rl-bridge executor) and draws all 14 tabs as a 2-col × 7-row grid at the bottom-right (cells auto-fit the bottom
~56% of the safe height so they never collide with the minimap). A tap that **starts on a cell** is consumed and
opens that tab (`HudVeneerBridge.openTab(i)`); a tap elsewhere falls through to the game. The open side panel is
relocated flush-left of the grid (`relocateSidePanel`). In any other layout the grid **disengages** —
`setStonesHidden(false)` + the side panel is released — so the real engine HUD renders (the iOS LAYOUT GUARD).
`-Drunelite.mobilechrome` + `-Drunelite.hudveneer` are now **always-on**; a `▦` button toggles the native grid
(A/B vs the raw engine stones).

```
adb shell am start -n dev.audaro.helloshell/.MainActivity   # autologin in-world; grid draws once toplevel 164 loads
android/dev-tools/a6-chrome/check-a6ui.sh
```

### Baseline GREEN (emulator `rl_arm64`, 2026-06-08)

```
ANDROID_A6_UI_GREEN native 2-col tab grid drawn (engine stones hidden) topLevel=164 activeTab=-1 view=1920x1080
a6: native tab 3 tap -> openTab                                                # a cell tap opened the inventory tab
```

Verified end-to-end on the emulator: the bottom-right renders the native 2-col grid (composited OSRS stones +
glyphs) with the engine's own stones hidden — the top-right shows only the minimap + orbs cluster, no leftover
stone row (`evidence-grid-idle.png`). A synthesized tap on the inventory cell fired `openTab(3)`: the inventory
panel opened **flush-left of the grid** and the inventory cell highlighted **red** (active), the rest grey
(`evidence-grid-inventory.png`). So the grid is correctly positioned, interactive, and the engine + plugins stay
authoritative (the tab switch is the stone's own op script — no widget moved or covered).

