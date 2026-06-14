# Android Desktop-Parity Audit — RuneLite on Android (CPU/SurfaceView port)

> Supersedes `android/MOBILE-UX-AUDIT.md` (2026-06-10).

## Contents

- [Feature inventory](#feature-inventory-shared-matrix--ios-columns-verbatim-android-column-re-derived-from-code)
- [Findings](#findings--ranked-by-impact--effort-android-lens)
- [Right-bar redesign](#4-right-bar-redesign)
- [Infeasible](#5-infeasible--accept-as-is)
- [Phased plan](#6-phased-plan)
- [Divergence](#7-android-vs-ios-divergence)
- [Open questions](#8-open-questions--risks-decisions-for-the-human-before-implementation)

This document is the code-grounded desktop-parity audit for the **native Android port** of RuneLite —
a second OpenJDK HotSpot VM brought up via `JNI_CreateJavaVM` inside the Android process, running the
unmodified RuneLite client + plugin ecosystem headless, rasterizing a CPU framebuffer that the native
layer blits into a `SurfaceView` (`android/app/src/main/cpp/jvm_bootstrap.cpp`). Every finding traces to
a capability that **demonstrably exists in desktop RuneLite** — the stock Swing client
(`runelite-client/src/main/java/net/runelite/client/ui/*`) or an official / Plugin-Hub plugin
(`.../plugins/*`) — and is sorted into exactly one of three buckets: **improve-degraded** (works on
Android but degraded/half-wired), **restore-missing** (desktop feature absent on Android), or
**rehome-touch** (a desktop interaction with no touch analogue, re-homed to a mobile equivalent).
It mirrors the structure of `ios/MOBILE-UX-AUDIT.md` (the iOS sibling audit) and **supersedes**
`android/MOBILE-UX-AUDIT.md` (dated 2026-06-10), which predates the five shipped parity phases and the
later config work; all Android-column claims in that older doc are treated here as hypotheses and
re-verified against current code on the `android` branch (every factual claim below carries a `path:line`
anchor — no anchor, no claim).

## Hard constraints (immovable)

- **Plugins stay UNMODIFIED.** A fix is only legal as a config policy, a native veneer (Compose / Views),
  or an additive shared-bridge method — never a plugin fork.
- **Shared core is additive-only.** `tools/shared-contract/baseline.txt` may only grow; any logic placed in
  a shared bridge under `runelite-client/.../headless/*` also benefits iOS.
- **CPU pixel-blit is the shipping renderer.** 117HD / GPU is post-v1 and is NOT a v1 parity gate — treat
  the GPU renderer as missing-as-a-renderer, but do not block parity findings on it.
- **Dual-VM marshalling tax.** UI-plane calls marshal ART↔HotSpot on the single `rl-bridge` thread
  (`JvmLauncher.kt`); any new bridge call inherits that latency and ANR risk.
- **JIT is free on Android.** One-tap cold-start-to-play is possible — do not carry the iOS
  StikDebug-attach-per-launch assumption into Android findings.
- **`flushConfig()` must fire on close/background** or config never persists; every finding that mutates
  config must verify the flush is wired.
- **Emulator-GREEN ≠ device-confirmed.** Pinch, IME composition, haptics, surface-recreate, and
  `dlopen`-from-`filesDir` are UNVERIFIED on real Android hardware and must be flagged as such.
- **The engine hard-pins minimap orbs every frame and exposes no API to read the composited minimap
  bitmap** — "make the minimap bigger" cannot be solved by relocating widgets.

## Reconciliation — what changed vs the 2026-06-10 audit

The 2026-06-10 `android/MOBILE-UX-AUDIT.md` + `android/MOBILE-UX-RECONCILIATION.md` were written against a
pre-parity-merge tree; `android/PARITY-PHASING.md` (2026-06-12) then planned and landed a master→android
merge plus five parity phases. The `android` branch HEAD now carries all six commits — Phase 0 merge
(`fb34c63b56`), Phase 0 fix/re-baseline (`d3e34be70a`, `a26f696eb3`), Phase 1 (`31380ae3cb`),
Phase 2 (`679e0f6a22`), Phase 3 (`bd5293308f`), Phase 4 (`a722d5b079`), Phase 5 (`6e2dc1ade6`) — so every
"missing / partial / read-only" claim in the older doc is re-checked here against current code. The five
phases moved a large block of the old backlog from hypothesis to shipped, while exposing several items that
are now shipped-but-partial or shipped-but-broken. The code-verified deltas (all anchors confirmed against
the `android` branch on 2026-06-14; where a cited line drifts by a few lines from current HEAD the
substantive claim was re-confirmed):

**Now SHIPPED and present (do not re-litigate as new findings):**

- **engine-stones-hidden** — `MainActivity.applyStonesHidden` + `HudVeneerBridge.setStonesHidden`/`hideRowOf`
  (`MainActivity.kt:1110-1114`; `HudVeneerBridge.java:200-203,684-703`).
- **native 2-col tab grid** — `ChromeOverlayView` draws 14 cells, `openTab` replays an op-script
  (`ChromeOverlayView.kt:146-195`; `HudVeneerBridge.java:724-748`); `TAB_GRID_ORDER` covers 0..13 and
  `openTabNow` handles both roots (`ChromeOverlayView.kt:62`; `HudVeneerBridge.java:705-748`).
- **side-panel relocate** — `applySidePanel`/`releaseSidePanel` → `relocateSidePanel`
  (`MainActivity.kt:1119-1139`; `HudVeneerBridge.java:191`).
- **chat / minimap relocate trio** — `applyChatAtTop`/`applyMinimap` → `relocateChat`/`relocateMinimap`
  (`MainActivity.kt:1144-1165,1241-1263`; `HudVeneerBridge.java:158,174`), re-forced every frame via
  `HudVeneerBridge.tick` (`MainActivity.kt:1074-1086`; `HudVeneerBridge.java:340-475`).
- **fullscreen immersive** — `enterImmersive` + cutout `SHORT_EDGES`, re-asserted on focus
  (`MainActivity.kt:367-371,864-877`).
- **overlay safe-insets** — `applyOverlayInsets` → `setOverlayInsets` (`MainActivity.kt:1174-1191`;
  `HudVeneerBridge.java:266`); `OverlayRenderer.positionSnapcorners` insets every corner and avoids the
  relocated chat (`OverlayRenderer.java:806-848`); chat-avoid via `getOverlayInsets`/`getOverlayChatAvoid`
  (`HudVeneerBridge.java:275,284`).
- **flushConfig on close/background** — `onStop` flush at `MainActivity.kt:1393-1400` + `doLogout` flush at
  `MainActivity.kt:584-585`, both via `JvmLauncher.flushConfig` → `MobileUiBridge.flushConfig`
  (`MobileUiBridge.java:357`); per-action flush in `OverlayEditScreen.kt:96-97` / `InfoBoxScreen.kt:88`.
- **native press-and-peek menu** — `tryBuildNativeMenu`/`updateMenuHighlight`/`commitMenuSelection`
  (`MainActivity.kt:1313,1350,1360`; `MenuOverlayView.kt:89-100`).
- **chat scroll-by-drag** (refutes the 06-10 "missing") — drag inside the live chat rect → accumulated
  canvas-px deltas → `scrollChat` (`MainActivity.kt:730-771`; `HudVeneerBridge.java:237`,
  `applyChatScroll` `1019-1039`).
- **keyboard toggle + auto-raise** — manual `⌨` + IME-visibility-aware auto-raise
  (`MainActivity.kt:294-301,880-916`).
- **right-click (BUTTON3) menus** — `MainActivity.kt:711` → `JavaBridge.java:592/608`.
- **pinch → mouse-wheel** — `ScaleListener.onScale` → `JvmLauncher.wheel` (`MainActivity.kt:788-801`;
  `JavaBridge.java:1232`); pinch-over-map multitouch device-unverified.
- **camera drag** — `JvmLauncher.camera` (`MainActivity.kt:774`; `JavaBridge.java:1253`).
- **notification delivery to the system shade** — `Notifier.java:180` → `MobileUiBridge.java:201` →
  `MainActivity.kt:972-1026` (`NotificationManager.notify` at `1021`); channel + runtime permission
  (`MainActivity.kt:946-958`; `AndroidManifest.xml:9`); tap-to-reopen single-top PendingIntent
  (`MainActivity.kt:1003-1018`); in-canvas screen-flash for free via the engine (`Notifier.java:255-305`
  drawn by `Hooks.java:610`); in-chat game-message channel for free (`Notifier.java:215-227`).
- **link routing to browser** — `LinkBrowser.java:58-62` → `MobileUiBridge.java:225` →
  `MainActivity.kt:977-1040`.
- **config editors** — color (real HSV+alpha picker, `ConfigScreen.kt:144` + picker `187-374`), dimension
  (guarded W×H, `ConfigScreen.kt:145,220-239`), set (multi-select checklist, `ConfigScreen.kt:146,243-294`).
- **pin/star** (shared `pinnedPlugins` CSV, persisted+flushed) — `PluginPanel.kt:201-218`;
  `MobileUiBridge.java:977-999`.
- **incompatible badge** (enabled && !active after grace) — `PluginPanel.kt:184,259`.
- **Plugin Hub install/browse** — `PluginHubScreen.kt:65-178`; `MobileUiBridge.java:720,782,799`.
- **per-plugin enable/gear** — `PluginPanel.kt:177-188,251-256`.
- **screenshot-to-gallery** — CPU frame via `nativeFramePixels` → MediaStore (added `b79639ff36`).
- **XP Tracker / Loot Tracker / Quest Helper / World Switcher native reflows** — `XpTrackerScreen.kt:64`
  (`jvm_bootstrap.cpp:597`); `LootTrackerScreen` `PanelReflowScreens.kt:59` (`jvm_bootstrap.cpp:598`);
  `QuestHelperScreen` `QuestWorldScreens.kt:63` (`jvm_bootstrap.cpp:540-566`); `WorldSwitcherScreen`
  `QuestWorldScreens.kt:161` (`jvm_bootstrap.cpp:570-593`); all routed in `PluginPanel.kt:87-90`.
- **overlay edit mode (snap/reset)** — `OverlayEditScreen.kt:96-97` + `OverlayBridge.java:158-218`;
  right-click reset is *better-than-desktop accessibility* (desktop needs Alt-held management mode +
  right-click, `OverlayRenderer.java:393-399`; Android offers an always-visible Reset, `OverlayEditScreen.kt:128`).
- **infobox menu** (Detach/Flip/Delete + plugin entries, posts the real `InfoBoxMenuClicked`) —
  `InfoBoxBridge.java:205-214`; `InfoBoxScreen.kt:106-117`.
- **chat channel pill** (collapse / fan-out / drag-switch / pull-down filter) — `ChatPillView.kt:33-389`;
  `MainActivity.kt:213-251`; the pull-down filter drives the **real engine filter menu** via press-and-peek
  (full desktop filter parity, not a re-impl) — `ChatPillView.kt:340-351`; `MainActivity.kt:228-250,1301-1310`.
  Collapse is shove-off-canvas, not hide, so history survives (`HudVeneerBridge.java:344`).
- **world-map floating window** (pan / precise taps / search IME / engine-X close / HUD shove) —
  `HudVeneerBridge.tickWorldMap` + `getMapSnapshot` (`HudVeneerBridge.java:519,526,542`) consumed by
  `MainActivity.updateWorldMap` (`MainActivity.kt:937,1195-1221`); touch pan via `panWorldMap`
  (`MainActivity.kt:723-755`; `HudVeneerBridge.java:519`, `setWorldMapPositionTarget` `630`); precise taps
  via `clickPrecise` (`MainActivity.kt:699`); HUD shove + restore (`HudVeneerBridge.java:588-609`).
- **minimap orbs** rendered via the `ensureOrbsLoaded` workaround (headless boot never opens interface 160)
  — `HudVeneerBridge.java:321-327,821-848`; minimap relocate keeps orbs as live click-targets
  (`HudVeneerBridge.java:437-447`).

**Now shipped but PARTIAL / BROKEN (carried forward into the audit body as findings):**

- **overlay-edit move** is corner-snap-only; free pixel-drag is unbridged
  (`jvm_bootstrap.cpp:612-614`) and the editor is a list, not an in-place handle (`OverlayEditScreen.kt:80`).
- **SHIFT+right-click overlay "Configure"** is broken/missing — sticky-shift feeds the world menu, not the
  overlay hover (`MainActivity.kt:308-314`), and `OverlayBridge` exposes no overlay menu entries (no
  `getMenuEntries` in `OverlayBridge.java`).
- **sticky-shift feed** is present (`isKeyPressed(SHIFT)` flip via `MainActivity.kt:308-313` →
  `JavaBridge.java:1382`) but its proof basis is the live engine + iOS device, **not an Android device** —
  UNVERIFIED on Android hardware.
- **engine touch-drag** (Prayer/Spellbook/Bank-tag/Inventory reorder) is plumbed-but-unwired:
  `JavaBridge.onTouch` exists (`JavaBridge.java:1415`) and `JvmLauncher.touch` wraps `nativeTouch`
  (`JvmLauncher.kt:248`, extern `jvm_bootstrap.cpp:1242`), but **no Kotlin call site emits the DOWN/MOVE/UP
  stream** (camera-drag uses the separate `JvmLauncher.camera` path) — absent on both shells per the iOS audit.
- **in-app Custom Tab for links** is partial — opens an external browser via `ACTION_VIEW`
  (`MainActivity.kt:1035`), not the Custom Tab the bridge doc describes (`MobileUiBridge.java:223`).
- **Grand Exchange reflow** is partial — `GrandExchangeScreen` (`PanelReflowScreens.kt:149`) shows offers but
  DROPS the trade history the bridge emits (`GrandExchangeBridge.java:183` vs `parseGe`
  `PanelReflowScreens.kt:220`).
- **Loot Tracker reflow** is read-only (no reset) — `PanelReflowScreens.kt:59`.
- **search/filter** is name-only; desktop matches name+description+tags (`PluginPanel.kt:158` vs
  `PluginListItem.java:88-90`).
- **rendered-image fallback tall-panel clip** — `PANEL_MAX_H=2500` cap truncates tall content
  (`MobileUiBridge.java:459,562`); the OOM-blank is fixed via a scale-step-down keeping the buffer under 3M px
  (`MobileUiBridge.java:566-571`), so the older "blank" claim no longer holds.
- **Report (8th) chat tab** is published by the bridge but not exposed by the pill (capped at `TAB_COUNT=7`) —
  `ChatPillView.kt:36` vs `HudVeneerBridge.java:70,1073`.
- **orbs `ensureOrbsLoaded`** is a workaround, broken for world-map-orb-open on legacy (`HudVeneerBridge.java:321-327`).
- **minimap enlarge** exists in code but is DISABLED by default and structurally capped by the orb hard-pin
  constraint (`HudVeneerBridge.java:957-1013`).
- **world-map search IME raise** is partial — IME raises on a search-rect tap (`MainActivity.kt:695-698,887`)
  but typed-text-reaches-engine is UNVERIFIED (`PARITY-PHASING.md` Finding 2).
- **world-map pinch zoom** rides the wheel-notch path (`MainActivity.kt:788-799`); pinch-over-map multitouch
  is device-unverified.
- **legacy-account world-map open** is broken — `WorldMapProbe` reproduces it (`WorldMapProbe.java:472`); no
  productized fix in Android code (the Jagex-tokens account opens it fine).
- **chrome A/B toggle** (raw stones vs grid) is present but dev-only — `chromeButton ▦` in the devBar
  (`MainActivity.kt:281-285`).
- **native grid layout-guard** (disengage outside `PRE_EOC`) is narrow — gated `topLevel==164` only;
  `OSRS_STRETCH`/`FIXED` fall back to raw stones (`MainActivity.kt:1057-1086`).

**Still MISSING (carried forward as restore-missing findings):**

- **keybind recorder** — type reaches the panel but no control is rendered (blank row);
  `ConfigScreen.kt` switch has no keybind case (`132-152`), bridge emits the type
  (`MobileUiBridge.java:1624-1626`).
- **per-notification config editor** — no `notification` case in `ConfigScreen.kt` (`else->{}` at `151`);
  bridge emits the type, panel drops it (`MobileUiBridge.java:1627-1628`).
- **global RuneLite notification-defaults panel** — no `emitFakePlugin` on branch `android`'s
  `MobileUiBridge.listPlugins`.
- **notification sound** — `javax.sound.sampled` `AudioPlayer` has no Android backend (`AudioPlayer.java:33-41`);
  the 2-arg payload carries no sound (`MobileUiBridge.java:211`).
- **config profiles UX** — no native UI, no bridge methods.
- **per-plugin Reset button** — no reset UI in `ConfigScreen.kt`; bridge has only single-key `resetConfig`
  (`MobileUiBridge.java:1389`).
- **Time Tracking native reflow** — shared `TimeTrackingBridge.java` is complete + wired
  (`MobileUiBridge.java:266,1438`) but no native/UI half exists; it falls to the rendered image.

**Process notes from the parity sessions worth carrying:** the GL bridge is a single thread-affine context
(any multithreaded-GL feature must stay OFF — `MOBILE-UX-RECONCILIATION.md` line 45); the shared contract
re-baselined to 199 entries after the snapRadius removal (`PARITY-PHASING.md` Phase-0 GREEN); and the
minimap magnifier was built then **removed at the user's request** (`MOBILE-UX-RECONCILIATION.md`
lines 322-334), so Finding 16 is declined, not open.

## Feature inventory (shared matrix — iOS columns verbatim, Android column re-derived from code)

### Legend

- **Mobile suitability** — how well the desktop capability maps onto a touch device:
  - **Native-ready** — pure render or config-driven; rides the CPU pixel-blit / native veneer with no input rework.
  - **Adaptable** — works on touch once re-homed (hover→long-press, drag→gesture, keybind→native button) or given a native reflow.
  - **Hard** — depends on a desktop-only affordance (free pixel-drag, hover-cursor edge, HW modifier) that has only a partial/awkward touch analogue.
  - **Infeasible** — structurally impossible on touch (HW cursor, OS window chrome, function keys, subprocess) — no native equivalent; goes to the Infeasible bucket with its blocking constraint.
- **status** — `works` / `partial` / `broken` / `missing` / `desktop-only-assumption` (the feature presumes a desktop affordance that does not exist on mobile) / `unverified` (no corpus evidence either way).
- **Android** — **emulator-GREEN only unless noted.** Real-device behavior for pinch, IME, haptics, surface-recreate, and `dlopen`-from-`filesDir` is UNVERIFIED on hardware. `(re-homed)` = the desktop gesture is replaced by a native veneer/gesture. `(emulator)` = render confirmed only in the emulator. `(shift latch)` = reachable via the sticky-Shift latch feeding a held `VK_SHIFT`.

#### Table 1 — Client-level chrome & host surface (A1, A4)

| Feature / Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Main window / JFrame host (ClientUI) | Client chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | No OS window; native root Activity (`MainActivity.kt`) replaces it. |
| Resize modes fixed/resizable/stretched + auto-grow | Client chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | Forced Classic resizable; auto-grow meaningless on a fixed screen. |
| Sidebar tabbed plugin rail + toggle | Client chrome | Adaptable | works (native dock) | works (Compose panel) | Swing rail replaced by the native plugin panel + `chromeOverlay` rail (`ChromeOverlayView.kt`); hover/right-click tab affordances dropped. |
| On-canvas sidebar arrow (hover-reveal) | Client chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | Hover-to-reveal has no touch analogue; replaced by native buttons. |
| Toolbar / non-tab nav buttons | Client chrome | Adaptable | works | works | Nav buttons surfaced as native buttons; right-click popup unported. |
| NavigationButton registration seam | Integration seam | Adaptable | works | works | Forwarded to MobileUiBridge.registerNav (no Swing sidebar); icons/tooltips degraded. |
| PluginPanel (225px Swing column) | Panel host | Adaptable | works (rendered + reflow) | works (rendered + reflow) | Rendered to buffer & blitted, or natively reflowed (`PanelReflowScreens.kt`, curated panels only). |
| MultiplexingPluginPanel back-stack | Panel host | Adaptable | unverified | unverified | Native nav stacks exist but corpus doesn't confirm muxer drill-down parity. |
| Configuration panel (search/list/config) | Settings UI | Adaptable | works (native) | works (native) | Native config editor (`ConfigScreen.kt`); bool/enum/int/double/string/color/dimension/set all editable, keybind/unknown read-only. |
| Per-plugin enable / pin-star / gear | Settings UI | Adaptable | partial | works | Enable + config + pin-star all wired on Android (`PluginPanel.kt:201-217` setPinned + persist + star sort); iOS still lacks pin/star. |
| Plugin Hub install/browse | Settings UI | Adaptable | works | works | Native hub list + install (`PluginHubScreen.kt`); splash-abort bug fixed on both. |
| Config profiles (create/clone/import/reorder) | Settings UI | Hard | missing | missing | Drag-reorder + JFileChooser import/export have no native UI on either port. |
| Client hotkeys (sidebar/panel toggle Ctrl+F11/F12) | Input chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | No HW function keys; replaced by native dev/chrome buttons. |
| Custom window chrome / title bar | Client chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | No title bar on mobile; N/A. |
| Login screen (engine-rendered) | Auth | Native-ready | works | works | Rides the canvas blit; app-level auth via native flow (`JagexLogin.kt`/`LoginActivity.kt`). |
| Splash screen | Launch UX | Infeasible | desktop-only-assumption | desktop-only-assumption | runelite.nosplash gates it off on both. |
| Screenshot/next-frame capture (DrawManager) | Capture | Native-ready | unverified | works | Android wires screenshot-to-gallery; consumer-plugin DrawManager delivery still unverified. |
| Theming / RuneScape fonts (ColorScheme/FontManager) | Styling | Native-ready | works | works | Fonts register under CACIO; native chat tabs bundle the TTFs. |
| Cursor management (custom + resize cursors) | Input chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | No hardware cursor on touch. |

---

#### Table 2 — Notification delivery (A4)

| Feature / Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Per-notification config UI (NotificationPanel) | Notifications | Adaptable | partial | partial | The serialized Notification config type is still read-only in the native editor (`ConfigScreen.kt:147` → `unknown`), but the alert itself fires. |
| OS tray notification (TrayIcon) | Notifications | Infeasible | missing | works | Android raises real OS notifications: Notifier funnels every fired alert into `MobileUiBridge.publishNotification` (`Notifier.java:180`), drained to `NotificationManager` (`MainActivity.kt:946-1024`). iOS still missing. |
| Shell-out notify (notify-send/osascript/terminal-notifier) | Notifications | Infeasible | missing | works (re-homed) | No subprocess, but the same fired-notification queue is delivered via `NotificationManager` instead — the shell-out is superseded, not absent (`MainActivity.kt:991-1024`). |
| Window focus / taskbar / dock attention | Notifications | Infeasible | desktop-only-assumption | desktop-only-assumption | No JFrame/taskbar; gated off (`Notifier.java:182` focus gate bypassed for mobile). |
| Toolkit.beep / custom .wav sound | Notifications | Adaptable | unverified | unverified | AWT beep meaningless; custom-wav + native audio not established in corpus. |
| In-canvas screen-flash overlay (processFlash) | Notifications | Native-ready | unverified | unverified | Draws into the game canvas (no OS chrome) — survives the blit; not explicitly verified. |
| In-chat game-message notification | Notifications | Native-ready | works | works | Pure chat text; rides the relocated chat pill veneer (`ChatPillView.kt`). |

---

#### Table 3 — Overlay system behaviors (A2)

| Feature / Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Overlay pure-render/layout (snap-corner stacking, clip, safeRender) | Overlay engine | Native-ready | works | works | Overlays lay out and draw at default/saved positions with no pointer; safe-insets pushed (`MainActivity.kt:1226-1236`). |
| Drag-to-reposition overlays (Alt-hold + drag) | Overlay engine | Hard | missing | partial (re-homed) | No free pixel-drag, but a native snap-to-corner edit UI (`OverlayEditScreen.kt:116-124`) re-homes the move; `setOverlayPreferredLocation` exists for a future free-drag. iOS still missing. |
| Resize overlays (hover-cursor edge + drag) | Overlay engine | Infeasible | missing | missing | Doubly hover-cursor-dependent; no touch analogue. |
| Snap-to-corner on drop | Overlay engine | Hard | missing | works (re-homed) | Native corner buttons call `OverlayBridge.setOverlayPreferredPosition` (`OverlayEditScreen.kt:96`, OverlayBridge:158); iOS not ported. |
| Drag-onto-target merge (infobox grouping) | Overlay engine | Hard | missing | missing | Depends on overlay drag; infobox merge/detach-by-drag unreachable. |
| Right-click reset overlay | Overlay engine | Adaptable | missing | works (re-homed) | Native "Reset" button → `OverlayBridge.resetOverlay` (`OverlayEditScreen.kt:97`/:128, OverlayBridge:193). iOS not wired. |
| SHIFT+right-click overlay menus ("Configure") | Overlay engine | Hard | partial | works | Sticky-Shift latch (`MainActivity.kt:308-313` → `JavaBridge.setShiftHeld`:1382) holds VK_SHIFT so the press-and-peek menu now surfaces shift-gated "Configure" entries. |
| InfoBox subsystem (render + hover tooltip/menu) | Overlay engine | Adaptable | partial | works (re-homed) | Infoboxes render; hover-menu (Detach/Flip/Delete) re-homed to a native list firing `invokeInfoBoxMenu` (`InfoBoxScreen.kt:45-50`). iOS still partial. |
| Tooltip subsystem (mouse-anchored) | Overlay engine | Hard | partial | partial | No ambient cursor; surfaces only during the deliberate press/move (`MainActivity.kt:404-406` pre-hover `move`). |
| WorldMap overlay (render + hover + menu) | Overlay engine | Adaptable | partial | works | Now a dedicated floating window (`MainActivity.kt:1195-1221`): finger-pan, search-IME, zoom/close taps; "Focus on" reachable via the generic long-press peek. Markers render; ambient hover tooltips still degrade. |
| WidgetOverlay (engine-widget relocation) | Overlay engine | Adaptable | works (veneer) | works (veneer) | HudVeneerBridge relocate (shared Java); re-pinned each frame; A7 minimap/chat/panel relocate trio live (`MainActivity.kt:1238-1254`). |
| WidgetItemOverlay (item highlights) | Overlay engine | Native-ready | works | works | Pure-pixel item draw; rides the blit. |
| Overlay components/ (panels, bars, text, etc.) | Overlay engine | Native-ready | works | works | All pure render(Graphics2D); fully headless-safe. |

---

#### Table 4 — Input / hotkey / right-click surfaces (A3)

| Feature / Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Tap → left-click (walk/interact) | Input | Native-ready | works | works | `JvmLauncher.click(gx,gy,1)` with red/yellow ring + interact-haptic on engine truth (`MainActivity.kt:701-708`, `TapRingView.kt`). |
| Long-press → engine right-click menu (press-and-peek) | Input | Adaptable | works | works | `onLongPress → beginMenuPeek` (`MainActivity.kt:711-713,1281-1294`) + native big-row overlay (`MenuOverlayView.kt`); drag-select to commit. |
| One-finger camera rotate | Input | Native-ready | works | works | onScroll → camera drag when not in chat/map/menu (`MainActivity.kt:741-744`). |
| Pinch → zoom (wheel notches) | Input | Adaptable | works | works | ScaleListener → onWheel at pinch centroid; the one bridged source of mouse-wheel. |
| Chat scroll-by-drag → chat scroll layer | Input | Adaptable | works | works | One-finger drag starting in the live chat box scrolls via `HudVeneerBridge.scrollChat` (`MainActivity.kt:723-769`, JvmLauncher:346) — NOT a wheel. Now at parity with iOS. |
| Soft keyboard text + enter + backspace | Input | Adaptable | works | works | Hidden capture field → onText/onKeyStroke (`jvm_bootstrap.cpp:437`); no arrow/function/modifier keys. |
| Configurable hotkeys (Keybind/HotkeyListener) | Input | Hard | missing | missing | No general VK keybind synthesized (only onKeyStroke + setShiftHeld in the JNI); every plugin hotkey dead unless re-bridged. |
| HotkeyButton binding UI | Input | Infeasible | missing | missing | No keyboard to record a binding; keybind config read-only (`ConfigScreen.kt:151` else→no control). |
| Shift-click menu swaps (engine isKeyPressed KC_SHIFT) | Input | Hard | missing | works (re-homed) | Sticky-Shift latch feeds a held VK_SHIFT (`MainActivity.kt:308-313`, `JavaBridge.setShiftHeld`); shift-gated swaps now reachable. iOS still missing. |
| Ctrl/Alt modifier behaviors (zoom, anti-drag, remap) | Input | Hard | missing | missing | Only Shift is synthesized; no Ctrl/Alt modifier events. |
| Right-click context menus (BUTTON3 identity) | Input | Adaptable | works (via long-press) | works (via long-press) | BUTTON3 faked by the long-press gesture (`JvmLauncher.click(...,3)`). |
| Middle-click / extra mouse buttons | Input | Infeasible | missing | missing | No BUTTON2/≥4 on touch. |
| Mouse-drag (inventory anti-drag, ground-item box) | Input | Hard | unverified | unverified | Drag stream (onTouch DOWN/MOVE/UP) synthesized only for scripted gestures; plugin-level drag not generally driven. |
| Hover (enter/exit/move, dwell, tooltip) | Input | Hard | partial | partial | Only deliberate pre-hover/move (`MainActivity.kt:404-406`); no ambient hover. |
| Mouse-wheel (general) | Input | Hard | partial | partial | Only the bridged pinch produces a wheel event. |
| AWT focus-driven held-state reset | Input | Hard | unverified | unverified | AWT focus meaningless on mobile; held-state cleanup unverified on device. |

---

#### Table 5a — Passive overlay / infobox plugins (A5a/A5b/A5c) — Native-ready group

These render through the overlay/infobox pipeline with no input surface; they ride the blit on both ports. iOS status is device-confirmed CPU path; Android status is emulator-GREEN (real-device render UNVERIFIED). Named in full:

| Plugin(s) | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Diary Requirements, Attack Styles, Combat Level | Interface overlay | Native-ready | works | works (emulator) | Passive widget overlays; none. |
| Agility, Blast Furnace, Blast Mine, Cooking, Drift Net, Fishing, Herbiboar, Hunter, Mining, Motherlode Mine, Runecraft, Smelting, Tithe Farm, Woodcutting | Skilling overlay | Native-ready | works | works (emulator) | Passive scene/minimap overlays + timers; minimap overlays must sit under the relocated HUD minimap (relocate trio shipped). |
| Barbarian Assault, Barrows, Cannon, Corp, Nightmare Zone, Pest Control, Pyramid Plunder, Tears of Guthix, Wintertodt, Zalcano, MTA | Minigame/boss overlay | Native-ready | works | works (emulator) | Passive overlays/timers; NMZ auto-relocates its overlay (reconcile with veneer). |
| Boosts, Boss Timers, Ammo, Item Charges, Kingdom of Miscellania, Poison, Slayer, Special Counter, Timers & Buffs | Info infobox | Native-ready | works | works (emulator) | Infoboxes render inside the relocated HUD infobox layer (overlay safe-insets + relocate trio shipped). |
| Chat Channels, Clue Scroll, Puzzle Solver, Implings, POH, Player Indicators, Opponent Information, Team, Regen Meter, Status Bars, Tile Indicators, Runepouch, Item Identification | Passive overlay | Native-ready | works | works (emulator) | Pure render; Status Bars anchor + Implings/Player-Indicators minimap dots reconcile with the veneer. |
| FPS Control | Performance overlay | Native-ready | works | works (emulator) | Passive FPS display; FPS-limit relevant to battery. |
| XP Globes, XP Drop | XP overlay | Native-ready | works | works (emulator) | Canvas globes / recolored XP-drop widgets; globe hover tooltips degrade on touch. |

---

#### Table 5b — Config-only / headless-logic plugins (A5a/A5b/A5c) — Native-ready group

No on-screen interaction; behavior is config-driven or background.

| Plugin(s) | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Animation Smoothing, Entity Hider, Roof Removal, Virtual Levels, Report Button | Visual/widget rewrite | Native-ready | works | works (emulator) | Engine-hook/script rewrites, no input. |
| Chat Filter, Chat Notifications, Emojis, Timestamp | Chat transform | Native-ready | works | works | Passive transforms; **Chat Notifications now has a real native delivery path on Android** (drain → `NotificationManager.notify`, MainActivity.kt:1021). |
| Daily Task Indicator, Default World, Logout Timer, Random Events | QoL/login | Native-ready | works | works (emulator) | Config→engine calls / chat notices; none. |
| Low Detail (lowmemory) | Memory | Native-ready | works | works (emulator) | Reduces engine memory/fill — mobile-positive; no input. |
| Metronome | Skilling audio | Native-ready | unverified | unverified | Audio-only; audio backend not established in corpus on Android. |
| Idle Notifier | Notifications | Native-ready | partial | **works** | **CHANGED:** Android now ships the native notify layer (channel MainActivity.kt:192; drain MainActivity.kt:966-980; deliver MainActivity.kt:1021) — idle alerts surface in the system shade. iOS still partial. |
| Crowdsourcing | Telemetry | Native-ready | works | works (emulator) | Silent network send; privacy not a touch issue. |
| Examine | Info/pricing | Native-ready | works | works (emulator) | Rides the existing Examine action; output is passive chat. |

---

#### Table 5c — Adaptable: right-click / menu-entry plugins (A5a/A5b/A5c)

Configuration gesture is right-click/menu-entry — reachable via the press-and-peek long-press (MainActivity.kt:711-713, beginMenuPeek :1281). **Shift-gated variants are now reachable on Android via the sticky-Shift latch (⇧ button MainActivity.kt:308-313 → `setShiftHeld` JvmLauncher.kt:251 → JavaBridge.java:1382): latch ON → long-press surfaces the engine's shift entries → tap commits.** This is the divergence from the iOS verbatim "no Shift" gaps below.

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Menu Entry Swapper | Menu/UI | Adaptable | partial | **partial+ (shift now reachable)** | **CHANGED:** Default swaps work; Shift-to-customize is now reachable via the sticky-Shift latch (long-press while ⇧ held). iOS still has no Shift. |
| Bank | Banking | Adaptable | partial | partial | Value overlay works; right-click bank swaps via long-press; pin-keyboard + search typing via the soft keyboard (IME bound, MainActivity.kt:197). |
| Bank Tags | Banking org | Adaptable | unverified | unverified | Tag/untag is right-click (long-press OK); tag-tab drag-reflow is mouse-drag-bound and engine touch-drag is NOT wired (no `JvmLauncher.touch(` call). |
| Chat History | Chat | Adaptable | partial | partial | Retention passive; "Clear history" is a menu entry via long-press. |
| Fairy Rings | Travel | Adaptable | unverified | unverified | Favourite/recent are menu entries (long-press); code search needs keyboard (present). |
| Friend List | Social | Native-ready | works | works (emulator) | List info passive; minor menu hooks via long-press. |
| Friend Notes | Social | Adaptable | unverified | unverified | Add/edit-note is right-click + text dialog (long-press + keyboard, both present). |
| Ground Items | Loot/QoL | Hard | partial | **partial+ (shift now reachable; Alt still absent)** | **CHANGED:** Price overlay touch-friendly; SHIFT-hold-highlight now reachable via the latch, but hold-Alt-highlight + middle-click-hide remain modifier/button-bound (no Alt/middle-click feed). |
| Ground Markers | Mapping | Hard | partial | **partial+ (shift now reachable)** | **CHANGED:** Render fine; mark = SHIFT-hold + right-click is now reachable via the sticky-Shift latch + long-press. iOS still has no Shift. |
| HiScore | Lookup | Adaptable | unverified | unverified | Right-click "Lookup" via long-press; panel typing/autocomplete via keyboard + rendered-image fallback. |
| Inventory Tags | Items | Adaptable | unverified | unverified | Tag-assign in right-click submenu (long-press); render fine. |
| NPC Indicators | Overlay | Hard | partial | **partial+ (shift now reachable)** | **CHANGED:** Highlight/respawn render fine; Shift+Tag is now reachable via the sticky-Shift latch + long-press. |
| Object Markers | Overlay | Hard | partial | **partial+ (shift now reachable)** | **CHANGED:** Outline render fine; Shift+right-click-examine mark is now reachable via the sticky-Shift latch + long-press. |
| Spellbook | Magic | Adaptable | unverified | **broken (drag unwired)** | **CHANGED:** Lock/unlock menu entries reachable via long-press, but drag-reorder is unreachable — engine touch-drag is NOT wired (one-finger scroll goes to `JvmLauncher.camera`, MainActivity.kt:774; no `.touch(` call exists). iOS leaves it "may work"; Android refuted. |
| Wiki | Integration | Adaptable | partial | partial | Orb + chatbox search work on touch; "Wiki"/"DPS" menu entries via long-press; LinkBrowser now routes to the native browser (ACTION_VIEW, MainActivity.kt:1035). |
| World Map (plugin) | Map overlay | Adaptable | partial | partial | Points render; floating world-map window shipped (open/pan/precise-tap/IME search, MainActivity.kt:1195+) — but the OPEN action is account-gated (legacy account cannot open the map; Jagex account OK). |

---

#### Table 5d — Adaptable: Swing-panel plugins needing reflow (A5a/A5b/A5c)

Rich desktop side panels; on the ports they fall through to the rendered-Swing-image surface unless a native reflow exists. The rendered-image fallback is NOT safe for tall panels — it steps the supersample scale down under a memory budget and tall panels OOM-blank (MobileUiBridge.java:465, :563-565). Only Quest Helper + World Switcher are natively reflowed (plus native XP/Loot/GE reflow screens exist as partial assists).

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Quest Helper (hub) | Quest panel | Adaptable | works (native reflow) | works (native reflow) | Curated native screen via QuestHelperBridge; gated on plugin availability. |
| World Switcher (worldhopper) | Utility panel | Adaptable | works (native reflow) | works (native reflow) | Native world list + hop via WorldSwitcherBridge; hop hotkeys (Ctrl+Shift) dead. |
| Grand Exchange | Trading | Adaptable | partial | partial | In-game GE overlay passive; rich search panel falls to rendered image; typed search needs keyboard (present). |
| Loot Tracker | Loot panel | Adaptable | partial | partial | Data headless-safe; tall history panel OOM-blanks via rendered-image fallback (MobileUiBridge.java:563-565). |
| XP Tracker | XP panel | Adaptable | partial | partial | Canvas infobox works; native XpTrackerScreen reflow present; "Open Wise Old Man" → native browser. |
| Time Tracking | Skilling panel | Adaptable | partial | partial | Multi-tab tall panel only via rendered image (OOM-blank risk); no native reflow. |
| Skill Calculator | Skilling panel | Adaptable | partial | partial | Text-entry + action grid only via rendered image; usability low without reflow. |
| Notes | Utility panel | Adaptable | partial | partial | Free-text JTextArea only via rendered image; needs native editor. |
| Kourend Library | Utility panel | Adaptable | partial | partial | Book-location overlay works; Reset-button panel via rendered image. |
| Info Panel | Panel | Adaptable | partial | partial | Low-value build/version panel via rendered image; hyperlink → native browser (MainActivity.kt:1035). |
| Party | Social panel | Hard | partial | partial | Panel via rendered image; ping = hotkey-hold + click → no native modifier+click gesture. |
| Screen Markers | UI/draw panel | Hard | missing | missing | Panel + free-form mouse-drag rectangle creation; no native equivalent + engine touch-drag unwired. |
| HiScore panel (see also 5c) | Lookup panel | Adaptable | partial | partial | Name typing + autocomplete via rendered image / keyboard; results display fine. |

---

#### Table 5e — Adaptable: in-game-widget / chatbox-input & search plugins (A5b/A5c)

Tappable engine widgets that open the engine ChatboxTextInput — work on touch once the soft keyboard is bound (Android binds it: GameInputView MainActivity.kt:197 + auto-raise poll + manual ⌨ toggle MainActivity.kt:297).

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Music | Sound/UI | Adaptable | works | works | Search/filter/volume are tappable widgets; search uses chatbox input (soft keyboard wired). |
| Quest List | Utility | Adaptable | works | works | Tappable search button → chatbox input; soft keyboard present. |
| Chat Commands | Chat | Adaptable | partial | partial | Typed `!` commands work via keyboard; keybind-triggered lookups dead (no HW keys). |
| Inventory Viewer | Items | Adaptable | partial | partial | Hotkey toggle unreachable; usable as static overlay when default NOT_SET. |
| Inventory Grid | Items | Adaptable | unverified | **broken (drag unwired)** | **CHANGED:** Visual drag preview depends on engine touch-drag, which is NOT wired on Android (no `.touch(` call; one-finger scroll → `JvmLauncher.camera` MainActivity.kt:774). iOS "unverified"; Android refuted. |

---

#### Table 5f — Hover / cursor-dependent plugins (A3/A5a/A5b)

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Mouse Tooltips (mousehighlight) | Overlay | Hard | partial | partial | Hover-only; surfaces only under press-and-peek — largely redundant with the native reticle. |
| Item Prices | Items | Hard | partial | partial | Hover tooltip; inert without an open menu/peek (no ambient hover, MOBILE-UX-AUDIT.md:110). |
| Item Stats | Inventory | Hard | partial | partial | Same hover-only limitation as Item Prices. |
| Interact Highlight | Combat | Adaptable | partial | partial | Hover-color path fires only during peek; interact-on-click path works. |
| Custom Cursor | Cosmetic | Infeasible | missing | missing | No OS cursor on touch — meaningless; should be hidden/no-op. |

---

#### Table 5g — Hard / Infeasible / excluded plugins (A3/A5a/A5b/A5c)

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Anti Drag | Inventory/input | Hard | broken | **partial (shift latch)** | **CHANGED:** Default mode needs Shift-hold, now feedable via the sticky-Shift latch (setShiftHeld, JavaBridge.java:1382) — usable while ⇧ latched, though the hold-during-drag UX is awkward on touch. iOS still broken. |
| Camera | Camera/input | Hard | partial | partial | Zoom-limit useful; right/middle-click remaps + key-camera dead on touch. |
| Key Remapping | Input | Infeasible | missing | missing | Intercepts HW WASD/F-keys; nothing to remap on touch (BT keyboard only). |
| Login Screen | Utility | Adaptable | desktop-only-assumption | desktop-only-assumption | Engine login bypassed by the native Jagex login (JagexLogin.kt); clipboard-paste hotkey dead. |
| Instance Map | Utility/overlay | Hard | unverified | unverified | Right-click open + scroll-zoom + click-pan have no native gestures; needs gesture mapping. |
| Interface Styles | UI/overlay | Hard | unverified | unverified | Rewrites fixed widget layout — collides with the HUD veneer; likely keep off. |
| Stretched Mode | Renderer | Infeasible | missing | missing | Rescales surface + remaps mouse coords — conflicts with port scaling/touch; exclude. |
| GPU | Renderer backend | Infeasible (iOS) / Hard (Android) | desktop-only-assumption (shelved) | **wired, device-unverified, POST-v1** | **CHANGED:** `rc_AWT_swapBuffers` is no longer a no-op stub — it has a real direct-EGL-window-surface present (librlawt.cpp:606-623, armed via RouteC_setPresentCallbacks) selected by the persisted renderer pref (PluginPanel.kt:60-170 → routecgpu, MainActivity.kt:459). NOT a v1 gate; emulator can't run it (Zone needs real Mali); live present UNVERIFIED on device. Functionally still "missing as the shipping renderer," but the prior "no-op stub" claim is stale. |
| Developer Tools | Debug | Infeasible | desktop-only-assumption | desktop-only-assumption | Spawns separate Swing frames; dev-only — exclude. |
| Account | RuneLite sync | Adaptable | unverified | unverified | Nav button tappable; RL/Google OAuth flow desktop-oriented, low value. |
| Discord | External integration | Infeasible | unverified | unverified | Discord RPC/IPC is a desktop-process integration; likely inert on-device. |
| DPS Counter | Combat/party | Native-ready | unverified | unverified | Display passive; depends on Party networking working on-device. |
| Twitch | Integration/chat | Adaptable | unverified | unverified | IRC logic headless-safe; native config form incl. secret OAuth field now writable (string/secret OutlinedTextField ConfigScreen.kt:139). |
| XP Updater | Integration/background | Native-ready | unverified | unverified | Background WOM/CML/Temple post; username entry only. |
| RuneEnergy | — | N/A | missing | missing | Directory empty/untracked — plugin does not exist; exclude. |
| Raids, Screenshot | PvM / Utility | Adaptable | partial | **works (Screenshot) / partial (Raids)** | **CHANGED:** Screenshot now has a native trigger + destination on Android (📷 button MainActivity.kt:337 → saveScreenshot → MediaStore Pictures/RuneLite MainActivity.kt:642-651). Raids overlay/triggers work; LinkBrowser → native browser. |
| Skybox, Wintertodt config, XP Drop, Tile Indicators | Visual config | Adaptable | partial | partial | **CHANGED (prior correction now STALE):** color config is NO LONGER read-only on Android — native Compose color picker shipped (ColorConfigControl ConfigScreen.kt:187 + ColorPickerDialog :309); dimension (:220) and set (:243) editors also writable. Render passive. |
| Prayer | Combat | Adaptable | partial | **partial (reorder still unwired)** | Overlays/flick high-value + mobile-safe; reorder = widget drag-and-drop, engine touch-drag still NOT wired (no `.touch(` call; one-finger scroll → camera MainActivity.kt:774). |
| Configuration (config plugin) | Core settings | Adaptable | desktop-only-assumption | desktop-only-assumption | Hidden Swing config replaced natively (ConfigScreen.kt / MobileUiBridge); the Swing panel itself not touch-first. |

---

### Android-status changes vs the prior matrix

- Table 1 'Per-plugin enable / pin-star / gear' Android: partial -> works. Pin/star is now surfaced & persisted: PluginPanel.kt:201-217 (onTogglePin -> JvmLauncher.setPinned + flushConfig + star-sort), star control PluginPanel.kt:244; bridge MobileUiBridge#setPinned at MobileUiBridge.java:999, contract baseline.txt:161. (Prior doc claimed pin/star 'not surfaced on either native UI'.)
- Table 1 'Configuration panel' gap re-derived: color/dimension/set are now EDITABLE not read-only. ConfigScreen.kt:144 color->ColorConfigControl (native picker), :145 dimension->DimensionConfigControl (WxH), :146 set->SetConfigControl (multi-select); only keybind/unknown stay read-only (:147,:151). The header comment ConfigScreen.kt:76 saying set/color read-only is STALE vs the live when-block. (Prior doc: 'keybind/dimension/set read-only'.) Status stayed 'works' but the gap claim changed.
- Table 1 'Screenshot/next-frame capture' Android: unverified -> works (screenshot-to-gallery is shipped per the already-shipped list); consumer-plugin DrawManager delivery still unverified.
- Table 2 'OS tray notification (TrayIcon)' Android: missing -> works. Real OS notifications now delivered: Notifier.java:180 funnels every fired notification to MobileUiBridge.publishNotification (MobileUiBridge.java:201), drained to NotificationManager at MainActivity.kt:946-1024 (channel + POST_NOTIFICATIONS + deliverNotification). iOS still missing.
- Table 2 'Shell-out notify' Android: missing -> works (re-homed). Same fired-notification queue is delivered via NotificationManager (MainActivity.kt:991-1024) rather than a subprocess; the desktop shell-out path is superseded.
- Table 3 'Drag-to-reposition overlays' Android: missing -> partial (re-homed). Native snap-to-corner edit UI OverlayEditScreen.kt:116-124 calls OverlayBridge.setOverlayPreferredPosition; OverlayBridge.setOverlayPreferredLocation (OverlayBridge.java:125) exists for future free-drag.
- Table 3 'Snap-to-corner on drop' Android: missing -> works (re-homed). OverlayEditScreen.kt:96 onSnap -> JvmLauncher.overlaySnap -> OverlayBridge.setOverlayPreferredPosition (OverlayBridge.java:158); contract has setOverlayPreferredLocation baseline.txt:169.
- Table 3 'Right-click reset overlay' Android: missing -> works (re-homed). OverlayEditScreen.kt:97/:128 onReset -> JvmLauncher.overlayReset -> OverlayBridge.resetOverlay (OverlayBridge.java:193).
- Table 3 'SHIFT+right-click overlay menus (Configure)' Android: partial -> works. Sticky-Shift latch MainActivity.kt:308-313 -> JavaBridge.setShiftHeld (JavaBridge.java:1382 posts held VK_SHIFT, contract baseline.txt:111); press-and-peek then surfaces shift-gated Configure entries.
- Table 3 'InfoBox subsystem (render + hover tooltip/menu)' Android: partial -> works (re-homed). Hover-menu Detach/Flip/Delete re-homed to InfoBoxScreen.kt list firing InfoBoxBridge.invokeInfoBoxMenu (contract baseline.txt:79).
- Table 3 'WorldMap overlay (render + hover + menu)' Android: partial -> works. Dedicated floating window MainActivity.kt:1195-1221 (finger-pan via panWorldMap MainActivity.kt:755, search-IME MainActivity.kt:695-696, zoom/close taps MainActivity.kt:1204-1207); 'Focus on' reachable via the generic long-press peek (onLongPress MainActivity.kt:711). Ambient hover tooltips still degrade.
- Table 4 'Chat scroll-by-drag -> chat scroll layer' Android: missing -> works. One-finger drag starting in the live chat box scrolls via HudVeneerBridge.scrollChat: MainActivity.kt:723-769, JvmLauncher.kt:346 scrollChat, native nativeScrollChat jvm_bootstrap.cpp:1949, contract baseline.txt:67. Now at parity with iOS (prior doc B3 gap: 'nativeTouch plumbed but unused').
- Table 4 'Shift-click menu swaps (engine isKeyPressed KC_SHIFT)' Android: missing -> works (re-homed). Sticky-Shift latch feeds a held VK_SHIFT (MainActivity.kt:308-313, JavaBridge.setShiftHeld JavaBridge.java:1382, JNI g_midSetShiftHeld jvm_bootstrap.cpp:450); shift-gated swaps now reachable. iOS still missing.
- Table 5g Skybox/Wintertodt config/XP Drop/Tile Indicators: prior Android correction 'partial (color read-only)' is now STALE — color config is write-capable. Native color picker shipped: ConfigScreen.kt:187 (ColorConfigControl) + ConfigScreen.kt:309 (ColorPickerDialog); dimension editor ConfigScreen.kt:220; set editor ConfigScreen.kt:243. Android status stays 'partial' for the same reason as iOS (render passive), NOT read-only.
- Table 5g GPU: prior Android correction 'missing as renderer (rc_AWT_swapBuffers no-op stub librlawt.cpp:271)' is now STALE — swapBuffers has a real direct-EGL-window-surface present at librlawt.cpp:606-623 (eglSwapBuffers :623), armed via RouteC_setPresentCallbacks (librlawt.cpp:567) and selected by the persisted renderer pref (PluginPanel.kt:60-170, MainActivity.kt:459). New Android status: wired but device-unverified and POST-v1 (emulator cannot run Zone; needs real Mali).
- Table 5g Anti Drag: prior 'broken' → 'partial (shift latch)'. Sticky-Shift latch feeds VK_SHIFT-hold so the default Shift-hold mode is reachable: ⇧ button MainActivity.kt:308-313 → JvmLauncher.setShiftHeld JvmLauncher.kt:251 → JavaBridge.setShiftHeld JavaBridge.java:1382.
- Table 5g Raids,Screenshot: prior 'partial (screenshot hotkey dead)' → 'works (Screenshot)'. Native 📷 button MainActivity.kt:337 → saveScreenshot writes PNG to MediaStore Pictures/RuneLite (MainActivity.kt:642-651).
- Table 5b Idle Notifier: prior 'partial (no native notify layer)' → 'works'. Native notification layer shipped: channel setupNotifications MainActivity.kt:192, drain MainActivity.kt:966-980, deliver NotificationManager.notify MainActivity.kt:1021.
- Table 5b Chat Notifications row: Android delivery now wired (NotificationManager.notify MainActivity.kt:1021) — annotated as genuinely delivered, not merely passive text. Status remains 'works' but for a stronger reason than iOS.
- Table 5c Menu Entry Swapper: shift-to-customize now reachable via sticky-Shift latch (MainActivity.kt:308-313, JavaBridge.java:1382) + press-and-peek long-press (MainActivity.kt:711, beginMenuPeek :1281). iOS verbatim still says 'no Shift'.
- Table 5c Ground Markers: SHIFT-hold + right-click mark now reachable via the sticky-Shift latch + long-press (MainActivity.kt:312 setShiftHeld; beginMenuPeek :1281; commit selectMenuPoint commitMenuSelection). Was 'unreachable (no Shift)' in iOS verbatim.
- Table 5c NPC Indicators: Shift+Tag now reachable via the sticky-Shift latch + long-press (MainActivity.kt:312; :1281).
- Table 5c Object Markers: Shift+right-click-examine mark now reachable via the sticky-Shift latch + long-press (MainActivity.kt:312; :1281).
- Table 5c Ground Items: SHIFT-hold-highlight now reachable via the latch (MainActivity.kt:312); Alt-highlight + middle-click-hide still unreachable (no Alt/middle-click feed). Net 'partial+'.
- Table 5c Spellbook: prior iOS 'unverified (drag may work)' refuted for Android → 'broken (drag unwired)'. Engine touch-drag is NOT wired: no JvmLauncher.touch( call anywhere; one-finger game-surface scroll routes to JvmLauncher.camera (MainActivity.kt:774). Lock/unlock menu entries still reachable via long-press.
- Table 5e Inventory Grid: prior 'unverified' refuted → 'broken (drag unwired)'. Drag preview depends on engine touch-drag, which is absent (no .touch( call; MainActivity.kt:774 routes scroll to camera). This confirms prior Android correction #2 still holds against current code.
- Table 5g Prayer: reorder still unreachable — engine touch-drag unwired (no .touch( call; MainActivity.kt:774). Confirms prior correction #2; overlays/flick still partial/works.
- Table 5g Twitch: secret OAuth config field is now WRITABLE on Android (string/secret OutlinedTextField with PasswordVisualTransformation, ConfigScreen.kt:139-142), not read-only; status remains 'unverified' overall (network path unconfirmed).

## Findings — ranked by impact × effort (Android lens)

Each finding is tagged **[shared-core]** or **[native-android]** to say where the fix actually lands. **[shared-core]** means the fix needs (or already has) a method in the additive-only shared bridge under `runelite-client/.../headless/` — because that code is also consumed by iOS, any shared work here benefits both ports, and `tools/shared-contract/baseline.txt` may only grow (never shrink, never fork a plugin). **[native-android]** means the fix is purely host-side: Compose/Views veneer, JNI wiring in `jvm_bootstrap.cpp`, or input/lifecycle policy in the Kotlin shell — no shared change. Orthogonally, each finding carries an **iOS call** describing leverage from the four iOS parity branches (`origin/parity-b1-config`, `-b2-rightbar`, `-b4-overlays`, `-b5-hopper`): **free-after-merge** = iOS already built the shared half AND a native analogue, so Android owes only its own native veneer once the shared delta merges; **shared-work-owed** = the shared half still has to be written (on either port) and will land in the contract; **native-only** = no shared component, each platform writes its own. Buckets are **improve-degraded** (works but clumsy/half-wired), **restore-missing** (desktop feature absent), **rehome-touch** (desktop interaction re-homed to a mobile gesture/control). Every factual claim is anchored to `path:line`.

### Tier 1

#### Keybind config items render as blank, uneditable rows (no control, not even the current value)
- **Bucket:** rehome-touch
- **Problem:** The shared bridge correctly tags Keybind/ModifierlessKeybind config items as type `keybind` (MobileUiBridge.java:1619-1626), but ConfigScreen.kt's `when(item.type)` at :132-152 has no `keybind` case and falls into `else->{}`, while the outer inline `when()` at :125-129 handles only boolean/enum. The `unknown` read-only fallback at :147-150 is never reached. So a plugin's hotkey setting shows only its name+description with NO control and NOT EVEN the current binding string — worse than iOS, which labels keybind rows `(not editable yet)`.
- **Impact:** Every keybind-bearing plugin config (~10-13 plugin Config classes; KeyRemappingConfig alone has 39 keybind fields) shows an inert blank row — the user cannot see the current binding, clear it, or change it. Desktop shows a clickable HotkeyButton.
- **Effort:** S
- **Mechanism (path:line):** Add a `keybind` branch to ConfigScreen.kt:132 rendering the current `Keybind.toString()` read-only with a `Clear` button writing NOT_SET via the existing setConfig string round-trip (ConfigScreen.kt:106). This read-only+clear v1 needs no plugin or shared fork. A full capture editor (re-homed HotkeyButton.java:42-77) would need an additive shared setConfig path for the Keybind string; read-only display is free now.
- **Blocking constraint:** Plugins stay unmodified — native veneer + existing setConfig string round-trip; read-only+clear needs no shared change, a capturing editor is additive-shared-owed.
- **Tag:** [native-android] — iOS: native-only (origin/parity-b1-config ios/app/HelloShell/PluginConfigViewController.m:805 — keybind falls into the read-only `(not editable yet)` branch; iOS built no native keybind editor either, so Android owes its own native control)
- **Current status:** partial  **Desktop target:** HotkeyButton config control — runelite-client/.../plugins/config/HotkeyButton.java:42-77

#### Notification config items render as blank rows (no enable toggle, no sub-editor) and the global RuneLite defaults group is unreachable
- **Bucket:** restore-missing
- **Problem:** The shared bridge tags Notification config items as type `notification` (MobileUiBridge.java:1627-1628) but ConfigScreen.kt's `when(item.type)` at :132-152 has no `notification` case — these rows render label-only with no control, so per-plugin notification settings (enable on/off, sound, flash, colour, volume, game-message) are invisible and uneditable. Compounding it, the global `RuneLite` config-only fake-plugin that holds the notification DEFAULTS all non-overridden notifications inherit (Notifier.defaultNotification, Notifier.java:133) is never injected into listPlugins on the android branch (no emitFakePlugin; MobileUiBridge.java:984). So both the per-item editor and the global defaults are absent — notifications fire with hardcoded RuneLiteConfig defaults.
- **Impact:** Dozens of plugins exposing a per-notification toggle (Idle Notifier, Chat Notifications, Boss Timers, Slayer) are uncustomisable, AND the user cannot change the default sound/volume/flash/colour/send-when-focused. Notifications already route to the OS via publishNotification, so the inability to configure them is a visible parity gap with effectively zero notification configurability from the UI.
- **Effort:** M
- **Mechanism (path:line):** Two additive pieces, both already written on iOS. (1) Per-item: add a `notification` branch to ConfigScreen.kt:132 — an enable Switch parsed from the Notification value plus a tap-to-open sub-editor mirroring NotificationPanel.java:275-425 (sound/volume/flash/colour/game-message), composing fields back into the serialized config string via setConfig. (2) Global: merge origin/parity-b1-config's emitFakePlugin(RuneLite, RuneLiteConfig.GROUP_NAME) + RUNELITE_HIDDEN_KEYS (drops window/keybind-only keys) so the defaults group appears and renders through existing enum/int/color controls. Hide window-only fields (tray/request-focus/timeout) with no mobile analogue.
- **Blocking constraint:** Shared core additive-only + plugins unmodified — per-item editor is native veneer over the existing setConfig string; the global group reuses the additive iOS emitFakePlugin (only grows listPlugins output).
- **Tag:** [shared-core] — iOS: free-after-merge (origin/parity-b1-config: MobileUiBridge adds notificationDefaults()+emitFakePlugin(RuneLiteConfig.GROUP_NAME)+RUNELITE_HIDDEN_KEYS; ios PluginConfigViewController.m:793-824 builds the enable-switch + editNotification sub-editor (git diff master..origin/parity-b1-config -- runelite-client/.../MobileUiBridge.java))
- **Current status:** missing  **Desktop target:** createNotification enable + NotificationPanel sub-editor — ConfigPanel.java:677-712, NotificationPanel.java:275-425; global defaults Notifier.java:133-143

#### FontType config items render as read-only opaque serialized strings instead of a 3-way preset picker
- **Bucket:** improve-degraded
- **Problem:** FontType uses a custom serializer (not an enum), so the bridge's typeName() has no case for it and returns `unknown` (MobileUiBridge.java:1582-1631), which ConfigScreen renders as a read-only text field (ConfigScreen.kt:147-150) showing a raw serialized blob. Desktop renders a font preset picker (createFont -> FontPanel, ConfigPanel.java:397,714-733) with REGULAR/BOLD/SMALL.
- **Impact:** Any plugin with a font-size/style setting (the RuneLite presentation group + several overlay plugins) is uneditable on Android and shows an opaque value, where desktop offers a clean 3-way preset dropdown.
- **Effort:** S
- **Mechanism (path:line):** Port origin/parity-b1-config's typeName FontType->`font` case + choiceValues emission (REGULAR/BOLD/SMALL) in getPluginConfig, then add a `font` dropdown branch in ConfigScreen reusing the existing EnumDropdown (ConfigScreen.kt:127-128) mapping choices<->choiceValues.
- **Blocking constraint:** Shared core additive-only — adding the `font` type + choiceValues only grows the bridge; native picker is veneer.
- **Tag:** [shared-core] — iOS: free-after-merge (origin/parity-b1-config: MobileUiBridge typeName adds case `FontType`->`font` + getPluginConfig emits REGULAR/BOLD/SMALL; ios PluginConfigViewController.m:773-820 builds editFont preset picker)
- **Current status:** partial  **Desktop target:** createFont preset picker (FontPanel) — ConfigPanel.java:397,714-733

#### Native config/plugin panel has no chrome-grade entry point — only a dim dev-cluster gear button
- **Bucket:** improve-degraded
- **Problem:** Desktop opens the plugin side panel via a persistent on-canvas sidebar arrow (ClientUI.paintOverlays draws sidebarOpenIcon on hover, :1024-1054) plus title-toolbar nav and Ctrl+F12. On Android the ONLY way into the native plugin list / config / Plugin Hub is the `⚙` button buried in the bottom-left devBar at alpha 0.7 next to throwaway dev buttons (GPU/HD restart, sign-in, logout, screenshot), so it reads as a debug control, not the primary settings affordance.
- **Impact:** The whole native config ecosystem — plugin toggles, per-plugin editors, pin/star, Plugin Hub, renderer select (the bulk of shipped parity work) — is gated behind a control that looks disposable and visually conflates with restart-the-process dev buttons. New users won't find it.
- **Effort:** S
- **Mechanism (path:line):** Promote panel access out of devBar into proper chrome: a dedicated full-opacity settings button near the tab grid (mirroring the Jagex-mobile gear) or an on-canvas arrow analogous to ClientUI.paintOverlays. The panel-open path already exists; only the launcher home + styling change (MainActivity.kt:264-278,340-345).
- **Blocking constraint:** Native-veneer-only — Compose/Views placement + styling, no engine widget move, no shared-core or plugin edit.
- **Tag:** [native-android] — iOS: native-only (n/a (iOS has its own UIKit chrome; this is Android Compose/Views placement))
- **Current status:** partial  **Desktop target:** Persistent sidebar-open arrow + toolbar settings — ClientUI.java:1024-1054

#### Dev affordance cluster (devBar) ships in release builds — GPU/HD restart, logout, sign-in always visible
- **Bucket:** improve-degraded
- **Problem:** The 9-button devBar (⚙, ⌨, ⇧, ▦ chrome A/B, ⎆ sign-in, ⏻ logout, ◆ GPU restart, HD restart, 📷 screenshot) is added in onCreate unconditionally (MainActivity.kt:279-345); only the shift long-press notification-test and stdio redirect are BuildConfig.DEBUG-gated. The ▦ chrome-toggle and GPU/HD process-restart buttons are developer tooling that does not belong in a shipping HUD.
- **Impact:** A shipped client shows cryptic single-glyph dev buttons (some hard-restart the process into experimental GPU/117HD renderers = footguns) overlapping the bottom-left game area, adding visual clutter the desktop never shows.
- **Effort:** S
- **Mechanism (path:line):** Gate the dev-only subset behind BuildConfig.DEBUG (the ▦ A/B toggle, ◆ GPU, HD, probe buttons), keep only genuine user controls (settings gear, keyboard, sign-in/out, screenshot) in release; renderer selection moves to the already-shipped native panel's renderer toggle rather than a HUD button. Pure host change (MainActivity.kt:279-345).
- **Blocking constraint:** Native-only host UI policy.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** partial  **Desktop target:** Desktop ships no in-canvas dev buttons; renderer choice lives in plugin config — n/a host policy

#### Outbound links open the external system browser (task-switch away) instead of an in-app Custom Tab
- **Bucket:** improve-degraded
- **Problem:** openLink fires Intent.ACTION_VIEW with FLAG_ACTIVITY_NEW_TASK (MainActivity.kt:1035), handing the URL to a separate browser app and task-switching the user AWAY from the game. The shared bridge explicitly documents the opposite — `opens it in-app (SFSafariViewController / Custom Tabs)` (MobileUiBridge.java:222-223) — so this is a degraded implementation of an already-shipped feature.
- **Impact:** Tapping any Wiki / HiScore / GE-price / plugin `open page` link yanks the user out of RuneLite into a separate browser and out of the game task; returning requires the recents switcher. Custom Tabs would keep the user in-app with a back-to-game affordance.
- **Effort:** S
- **Mechanism (path:line):** Replace the raw ACTION_VIEW at MainActivity.kt:1035 with androidx.browser CustomTabsIntent (CustomTabsIntent.Builder().build().launchUrl(this, uri)), keeping the http/https guard at :1030. No shared/plugin change — the URL already arrives via the shared nextLink channel.
- **Blocking constraint:** Native-shell change only — swap the Intent for CustomTabsIntent + add the androidx.browser dependency; plugins unmodified, shared link channel already additive.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** partial  **Desktop target:** Desktop#browse opens a browser beside the game — MobileUiBridge.java:222 documents in-app Custom Tabs

#### Grand Exchange native reflow drops the trade-history tab the bridge already emits
- **Bucket:** improve-degraded
- **Problem:** GrandExchangeBridge reflects and emits a `history` array each refresh (GrandExchangeBridge.java:183), and the Android header even documents the schema {offers,history:[{buy,itemId,qty,price,time}]} (PanelReflowScreens.kt:142-143). But parseGe only reads `offers` (PanelReflowScreens.kt:220) and GrandExchangeScreen renders offers only (:149-188) — the history is discarded on the Kotlin side.
- **Impact:** The GE native screen looks complete but silently omits half the desktop panel's value (recent buys/sells and prices). The data is already crossing the bridge each refresh.
- **Effort:** S
- **Mechanism (path:line):** Parse o.optJSONArray('history') in parseGe (PanelReflowScreens.kt:216-236) into a GeHistory data class and render a collapsible `Recent trades` section under the offers list (:180-186). Pure Android-side change; JSON already produced by GrandExchangeBridge.java:183.
- **Blocking constraint:** None structural — shared data already present, additive-only respected (no bridge change).
- **Tag:** [native-android] — iOS: native-only (GrandExchangeBridge.java present on parity branches (history emit shared); native history view is per-platform)
- **Current status:** partial  **Desktop target:** GE panel offers + trade-history list — GrandExchangeBridge.java:183 emits both

#### Native tab grid only engages in PRE_EOC (164); OSRS_STRETCH falls back to raw engine stones
- **Bucket:** improve-degraded
- **Problem:** updateChrome gates the native grid on topLevel == 164 (PRE_EOC) ONLY (MainActivity.kt:1057). The shared layer already supports OSRS_STRETCH (161): HudVeneerBridge.tick passes the !isResized guard in stretch (line 305), stoneIds(preEoc=false) returns the stretch stones (:713-718), and setStonesHidden hides whichever root's row. So an OSRS_STRETCH player gets the un-hidden raw engine stone row instead of the native mobile grid despite the shared layer supporting it.
- **Impact:** A player in OSRS_STRETCH resizable mode loses the entire native mobile chrome (grid, hidden stones, all relocates release) and reverts to a desktop-shaped HUD — a jarring, silent, layout-dependent experience. (FIXED mode is NOT free: the apply path hard-returns on !isResized at HudVeneerBridge.java:305 and stoneIds has no FIXED branch — that would need additional shared work, so scope this to stretch.)
- **Effort:** M
- **Mechanism (path:line):** Widen the gate from topLevel==164 to rootKind in {PRE_EOC, OSRS_STRETCH} (rootKind is in the snapshot; HudVeneerBridge.openTabNow + stoneIds already branch). Verify the TAB_GRID_ORDER index mapping holds for stretch stones on a device, then enable (MainActivity.kt:1057; HudVeneerBridge.java:705-720; MobileChromeBridge.java:261-275).
- **Blocking constraint:** Plugins-unmodified + reads-only chrome — openTab stays an op-script replay; gate widening is host config policy but needs device confirm that stretch stones replay correctly.
- **Tag:** [native-android] — iOS: free-after-merge (MobileChromeBridge + HudVeneerBridge stretch/preEoc branches are shared on master (MobileChromeBridge.java:79-92; HudVeneerBridge.java:705-720))
- **Current status:** partial  **Desktop target:** Resizable stretched HUD (OSRS_STRETCH layout) — MobileChromeBridge.java:261-275 resolves it

#### Plugin search filters by name only (desktop matches name + description + tags)
- **Bucket:** improve-degraded
- **Problem:** The plugin-list filter is it.name.contains(filter, ignoreCase) (PluginPanel.kt:158) — display name only. Desktop builds keywords from name + description + @PluginDescriptor tags (PluginListItem.java:88-90) and ranks with a fuzzy comparator (PluginSearch.java:41-67). The Hub screen already matches description (PluginHubScreen.kt:161-164), so the inconsistency is itself confusing.
- **Impact:** Discovery is materially worse: a user who doesn't know a plugin's exact display name can't find it by what it does (e.g. `xp`, `drops`, `overlay`).
- **Effort:** S
- **Mechanism (path:line):** Carry description in PluginRow + parsePlugins (listPlugins already emits description, MobileUiBridge.java:964) and broaden the filter to match name OR description, mirroring the Hub filter (PluginHubScreen.kt:161-164). Tags would need an additive bridge field; fuzzy ranking is optional polish.
- **Blocking constraint:** None structural — listPlugins already emits description; native-only for description-scope, additive bridge field only if tags added.
- **Tag:** [native-android] — iOS: shared-work-owed (n/a — search scope is native; the bridge already provides description, only tags would need the additive shared field)
- **Current status:** partial  **Desktop target:** Search over name+description+tags — PluginListItem.java:88-90, PluginSearch.java:41-67

#### Config-item edits are not flushed on config-screen exit (only on app background)
- **Bucket:** improve-degraded
- **Problem:** ConfigScreen writes each edit fire-and-forget via setConfig (ConfigScreen.kt:104-107) with no flushConfig on the Back path; persistence relies entirely on the global onStop flush (MainActivity.kt:1393-1400). The pin path flushes after its write (PluginPanel.kt:208-214) and the Hub flushes on settle/close (PluginHubScreen.kt:99-102), but config-item edits do not.
- **Impact:** If the app is force-killed or crashes after editing settings but before backgrounding, those edits are lost — config writes are queued to the client thread but not persisted until a flush. The asymmetry (pins/hub persist immediately, config edits don't) is a subtle data-loss risk.
- **Effort:** S
- **Mechanism (path:line):** Flush on ConfigScreen's onBack (or debounce-flush after writes), mirroring the Hub close() pattern (PluginHubScreen.kt:99-102): JvmLauncher.onBridge { JvmLauncher.flushConfig() } before onBack.
- **Blocking constraint:** flushConfig() must fire on close/background — global onStop covers normal use, but per-screen flush closes the force-kill gap; native-only, no shared change.
- **Tag:** [native-android] — iOS: native-only (n/a — flush-on-exit is a native lifecycle concern)
- **Current status:** partial  **Desktop target:** Desktop ConfigManager persists synchronously on edit — flush gap is mobile-specific

#### int/double config writes per-keystroke with no range clamp (corruption + out-of-range)
- **Bucket:** improve-degraded
- **Problem:** The int/double OutlinedTextField calls onChange on every keystroke (ConfigScreen.kt:134) and onChange immediately setConfig's (:106) — no commit-guard (unlike DimensionConfigControl which commits only when both fields parse, :224-227) and no @Range clamp (ConfigItem has no min/max; parseConfig never reads the min/max the bridge emits at MobileUiBridge.java:1088-1092). So a transient `` or lone `-` is written to ConfigManager and out-of-range values stick. Desktop uses a JSpinner clamped to @Range (ConfigPanel.java:461-492).
- **Impact:** Each character fires a dual-VM bridge marshal; mid-edit partial values are deserialized (zero/throw) and final values can exceed the plugin's @Range, producing visibly wrong behaviour the desktop spinner prevents.
- **Effort:** S
- **Mechanism (path:line):** Parse min/max into ConfigItem (extend the data class + parseConfig) and switch to a local text state that commits onChange only when value parses non-null and within [min,max] — the DimensionConfigControl.commit() guard pattern (ConfigScreen.kt:224-227). Optionally a slider for small bounded spans (iOS does <=1000).
- **Blocking constraint:** Dual-VM marshalling tax — per-keystroke setConfig is the concrete cost; commit-on-valid also reduces bridge traffic. Native-only, no shared change.
- **Tag:** [native-android] — iOS: native-only (origin/parity-b1-config ios PluginConfigViewController.m: int editing uses editBoundedInt (slider) for span<=1000 else a number-pad editor committing on Done, not per-keystroke)
- **Current status:** partial  **Desktop target:** JSpinner SpinnerNumberModel clamped to @Range — ConfigPanel.java:461-492

#### Chat 'Report' tab (8th engine tab) unreachable via the pill
- **Bucket:** restore-missing
- **Problem:** The desktop chatbox has 8 tabs (All, Game, Public, Private, Channel, Clan, Trade, Report). The shared bridge publishes all 8 (CHAT_TAB_IDS includes REPORTABUSE at index 7, HudVeneerBridge.java:65-70; buildChatSnapshot emits all, :1073-1082), but ChatPillView hard-caps at TAB_COUNT=7 (ChatPillView.kt:36) and hard-codes only the first 7 NAMES (:39), so Report never appears in the fan.
- **Impact:** The abuse-report chat tab — a real desktop tab — has no touch path. Minor (rarely used) but a genuine desktop feature absent on Android.
- **Effort:** S
- **Mechanism (path:line):** Bump ChatPillView.TAB_COUNT to 8, add `Report` to NAMES and a FILTERABLE entry (false); the existing fan/switch/op-replay path (switchChatTabNow handles index 7, HudVeneerBridge.java:753-763) carries it for free. Pure native-veneer change. Cosmetic concern: 8 chips = narrower touch targets.
- **Blocking constraint:** None structural — the shared snapshot already publishes the tab; the cap is a native ChatPillView constant.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** missing  **Desktop target:** Chatbox Report tab — InterfaceID.REPORTABUSE, the 8th engine chat tab; HudVeneerBridge.java:65

#### Overlay snap targets limited to 5 corners; ABOVE_CHATBOX_RIGHT / CANVAS_TOP_RIGHT not offered
- **Bucket:** improve-degraded
- **Problem:** Desktop snap corners include ABOVE_CHATBOX_RIGHT and CANVAS_TOP_RIGHT in resizable mode (OverlayRenderer.positionSnapcorners:840-844). Android's editor offers only TOP_LEFT/TOP_CENTER/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT (OverlayEditScreen.kt:45-48); the two extra positions appear only as read-only labels (:138). The bridge already accepts any OverlayPosition by name (OverlayBridge.java:158-189).
- **Impact:** Overlays that default to or want `above chat` / `canvas top-right` can't be snapped there on Android, only reset — minor but a real desktop position lost.
- **Effort:** S
- **Mechanism (path:line):** Add the two OverlayPosition names to SNAP_CORNERS (OverlayEditScreen.kt:45) gated on resized mode; setOverlayPreferredPosition already parses any position string (OverlayBridge.java:158,429-438). Pure native config, no shared change.
- **Blocking constraint:** Plugins unmodified — UI-only list change; the bridge already accepts the position strings.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** partial  **Desktop target:** ABOVE_CHATBOX_RIGHT + CANVAS_TOP_RIGHT snap corners — OverlayRenderer.java:840-844

### Tier 2

#### Legacy account cannot open the world map (ORBS_DISABLED varbit) — the orb is the only open path, fix exists only as a probe diagnostic
- **Bucket:** improve-degraded
- **Problem:** The only way to open the world map on Android is tapping the engine world-map orb. On a legacy account the open silently fails: ORBS_DISABLED varbit 4084=1 (`Data orbs` off server-side) means the server never opened the Orbs interface (160), the headless boot fakes it open client-side via ensureOrbsLoaded, so the orb renders and its op is consumed every frame but the if_button targets an interface node the server never opened and is silently discarded. MAP_CONTAINER never appears. Jagex-token accounts open fine on the same build. The shared WorldMapProbe reproduces and localizes it.
- **Impact:** On a legacy account (exactly the autologin account the Android port uses for most testing) the world map is 100% unreachable — the whole shipped Phase-5 floating map window is inert; map testing must switch to the tokens account. Even on a Jagex account, opening depends on hitting one ~25px orb with no enlarged touch target.
- **Effort:** M
- **Mechanism (path:line):** Two parts. (1) Persist the data-orbs fix: WorldMapProbe FIX MODE (WorldMapProbe.java:175-184,203-354) already proves the recipe — open the REAL server-backed settings window, find the `Data orbs` row, click it, verify ORBS_DISABLED flips to 0 (server-persisted). Productize as a one-shot config policy: on first LOGGED_IN, if safeVarbitInt(ORBS_DISABLED)!=0 (WorldMapProbe.java:858-868), auto-flip it through the same settings-UI script path lifted into an additive HudVeneerBridge method (or a one-tap `Enable map orb` prompt). (2) Add a deterministic native map-open affordance that replays the orb's op-script the way switchChatTabNow/openTabNow replay hidden-widget ops (HudVeneerBridge.java:753-773), so open doesn't depend on a server-opened interface node.
- **Blocking constraint:** Plugins/engine stay unmodified — the toggle MUST be flipped through the engine's server-opened settings UI (the probe's proven path; a client-side varbit write is not server-persisted), and the open replay is an additive shared-bridge op-replay method.
- **Tag:** [shared-core] — iOS: shared-work-owed (WorldMapProbe.java is shared (runelite-client/.../headless; present on origin/parity-b2-rightbar + origin/parity-b5-hopper, +839 in the diff); a HudVeneerBridge orbs-enable + orb-open method benefits iOS identically; no iOS branch has shipped the fix)
- **Current status:** broken  **Desktop target:** Clicking the minimap world-map orb (Orbs.ORB_WORLDMAP) opens the world map — WorldMapProbe.java:358

#### Engine widget touch-drag (Prayer/Spellbook/Bank-tag/Inventory reorder, ground-item box) — fully plumbed but no gesture ever drives it
- **Bucket:** rehome-touch
- **Problem:** The desktop drag-to-reorder surface (PrayerReorder, Spellbook reorder, bank-tag drag, inventory drag, ground-item drag-box) relies on the engine's MOUSE_PRESSED->DRAGGED->RELEASED stream feeding getDraggedWidget/getDraggedOnWidget. The shared onTouch() bridge synthesizes exactly that stream (JavaBridge.java:1415-1465), wired through JNI (nativeTouch -> IN_TOUCH, jvm_bootstrap.cpp:1242) with a public JvmLauncher.touch() wrapper (JvmLauncher.kt:248). But there is ZERO gesture call site of touch()/nativeTouch in any Kotlin handler — every one-finger drag is consumed by camera-rotate / chat-scroll / map-pan, and long-press goes to the right-click menu. The entire drag-to-reorder family is unreachable.
- **Impact:** Players cannot reorder prayers/spells, drag bank-tag items, rearrange inventory, or draw a ground-item selection box — all standard desktop interactions backed by stock plugins and the engine.
- **Effort:** L
- **Mechanism (path:line):** Add an explicit drag-reorder gesture mode in MainActivity.kt that, when a one-finger drag STARTS over an inventory/prayer/spell/bank widget (resolved via a small read-surface hit-test, analogous to chatRectCanvas at MainActivity.kt:730), routes DOWN/MOVE/UP through JvmLauncher.touch(0/1/2,...) instead of camera/chat. onTouch already exists; the hit-test rects need a shared additive read-surface method to locate the widget panes (benefits iOS — onTouch is driven on neither shell today).
- **Blocking constraint:** Needs a shared additive read-surface to locate the engine widget panes so a drag inside them routes to onTouch not camera; pure native gesture wiring cannot distinguish a reorder-drag from a camera-drag — baseline.txt grows by the rect-query method.
- **Tag:** [native-android] — iOS: shared-work-owed (origin/parity-b2-rightbar:ios/MOBILE-UX-AUDIT.md:356 — onTouch synthesizes the full PRESS->DRAG->RELEASE stream but iOS only calls it for camera/scroll, Android never; `driven on neither shell today`)
- **Current status:** missing  **Desktop target:** Engine drag (getDraggedWidget/getDraggedOnWidget) + PrayerReorder/Spellbook reorder — Client.java:473/484

#### No in-game hotkey/keybind TRIGGER surface — hotkey-gated plugin actions are unreachable
- **Bucket:** rehome-touch
- **Problem:** Desktop fires plugin actions through HotkeyListener.keyPressed/keyReleased matching a Keybind (HotkeyListener.java:75-103) — ground-items hotkey-show, anti-drag hold, custom plugin keybinds. On Android the only key dispatch is text-editing keys from the soft keyboard (onKeyStroke, MainActivity.kt:845-851) plus the shift-only modifier (JavaBridge.java:1382); there is NO native button or gesture that fires a configurable hotkey into KeyManager. (Two specific gated features ARE separately re-homed by bypassing the keybind: screenshot via the camera button, world-switch via the native world panel — so status is partial, not missing.)
- **Impact:** The bulk of hotkey-gated plugins (ground-items ALT toggle, anti-drag hold, raids, party, inventory-viewer toggle, world-hopper prev/next chords) cannot be triggered on touch.
- **Effort:** M
- **Mechanism (path:line):** Re-home as native buttons: a per-active-keybind button (or a `hotkeys` tray) calling a general additive setKeyHeld(vk,held) bridge + reading each plugin's Keybind from the existing config snapshot. onKeyStroke already posts a single VK (JavaBridge.java:1338) and setShiftHeld proves the held-modifier pattern (:1382). No plugin fork; the bridge method is additive (benefits iOS).
- **Blocking constraint:** Additive shared bridge required to (a) enumerate active plugin keybinds and (b) post an arbitrary held VK+modifier into the Canvas listener; baseline.txt grows. Pure native cannot synthesize the modifier+key chord setShiftHeld does for shift alone.
- **Tag:** [shared-core] — iOS: shared-work-owed (n/a)
- **Current status:** partial  **Desktop target:** HotkeyListener + Keybind.matches + KeyManager dispatch — HotkeyListener.java:75, GroundItemHotkeyListener.java

#### Time Tracking native reflow absent — shared bridge built but never wired to Android
- **Bucket:** restore-missing
- **Problem:** The Time Tracking side panel (farming patch timers, contract, bird houses, custom timers/stopwatches) has a COMPLETE shared bridge — TimeTrackingBridge isAvailable/setUiOpen/getTimeTrackingJson/tick (:105,:111,:120,:129) emitting a rich snapshot (rebuild() at TimeTrackingBridge.java:145-180), already init()'d and tick()'d by MobileUiBridge (:266,:1438). But there is NO native half: no nativeTime* in JvmLauncher.kt, no registration in jvm_bootstrap.cpp (0 TimeTracking refs, confirmed), no Compose screen, no NativeRow in PanelView.kt — so it falls to the rendered-image fallback (PanelView.kt:143).
- **Impact:** A high-traffic skilling panel (when will my herbs/trees/birdhouses be ready) renders on mobile only as a flat, click-only, multi-tab Swing image — tab switching is a guessed tap and countdowns aren't first-class. The data for a clean native screen already exists engine-side and is wasted.
- **Effort:** M
- **Mechanism (path:line):** Mirror the XP/Loot/GE pattern exactly. (1) Add nativeTimeAvailable/SetUiOpen/Json externs+wrappers in JvmLauncher.kt. (2) registerDataPanel(env,'.../TimeTrackingBridge','getTimeTrackingJson',g_timePanel) in jvm_bootstrap.cpp (the uniform avail/setUiOpen/getJson helper at :396 fits the exact signature). (3) Add a TimeTrackingScreen Composable parsing farming/contract/birdhouses/timers/stopwatches. (4) Wire timeAvail + NativeRow('Time Tracking') in PanelView.kt:98-106 and routing in PluginPanel.kt:89-93. No shared change beyond what exists.
- **Blocking constraint:** Additive-only shared core — the shared TimeTrackingBridge is already present and additive; this is purely the native (JNI+Compose) half, no plugin fork, no baseline growth.
- **Tag:** [native-android] — iOS: free-after-merge (TimeTrackingBridge.java present on all iOS parity branches (verified via git ls-tree); the shared half is merged — Android owes only the native screen)
- **Current status:** missing  **Desktop target:** Time Tracking side panel (farming/birdhouse/timers) — TimeTrackingBridge.java:105 reflects it

#### Overlay free pixel-drag is unbridged on Android (corner-snap only); iOS already built the native drag editor
- **Bucket:** improve-degraded
- **Problem:** Desktop repositions overlays by Alt+drag to any free pixel (OverlayRenderer.java:595-605 setPreferredLocation). Android's OverlayEditScreen offers only the five snap corners + reset (OverlayEditScreen.kt:45-48); the shared setOverlayPreferredLocation + snapPositionForPoint (OverlayBridge.java:125-152,234-260) are already MERGED on the android branch but dormant — no native JNI maps them (jvm_bootstrap.cpp bridges only setOverlayPreferredPosition at :613, confirmed; setOverlayPreferredLocation absent in cpp). Overlays can only land in 5 quantized spots.
- **Impact:** Overlays the player wants nudged a few px off the minimap/chat (the common case on a cramped phone) can only jump to a whole corner, often overlapping other HUD. The most natural touch gesture (drag the box) does nothing.
- **Effort:** M
- **Mechanism (path:line):** Shared half is DONE on android (OverlayBridge.java:125-152,234-260). Owe only a native JNI nativeOverlaySetLocation (jvm_bootstrap.cpp, mirror the snap entry at :613) + an in-place draggable handle view. iOS already wrote exactly this (OverlayEditView.m: UIPanGestureRecognizer per handle -> overlaySetLocation; 700ms re-pin grace), so the native pattern is a port.
- **Blocking constraint:** Plugins stay unmodified — native veneer + the already-additive bridge method; no plugin or new shared logic.
- **Tag:** [native-android] — iOS: shared-work-owed (origin/parity-b4-overlays a96c87c97e ios/app/HelloShell/OverlayEditView.m:178-183 (drag->overlaySetLocation); shared setOverlayPreferredLocation at OverlayBridge.java:125 (already merged on android))
- **Current status:** partial  **Desktop target:** Alt+drag free reposition — OverlayRenderer.java:595-605

#### Overlay editor is a separate full-screen list, not an in-place editor over the live game
- **Bucket:** improve-degraded
- **Problem:** OverlayEditScreen is a Surface(fillMaxSize) list of overlay names with corner buttons (OverlayEditScreen.kt:80-104) shown via PluginPanel routing (PluginPanel.kt:92), so it covers the game canvas. The player can't see where an overlay is or watch it move while choosing a corner; desktop edits in place with live bounds + snap-corner highlights (OverlayRenderer.java:258-364). Names are lightly-prettified class names (OverlayEditScreen.kt:144-147) that won't match what's on screen.
- **Impact:** Editing is blind: pick TOP_LEFT from a list, back out, look, find a collision, go back in. Combined with the corner-only limit, repositioning is a guessing loop.
- **Effort:** M
- **Mechanism (path:line):** The bridge already returns per-overlay bounds in canvas px (OverlayBridge.java:316-318); the iOS OverlayEditView maps those bounds canvas->screen at blit scale and floats a labelled handle over each live overlay (OverlayEditView.m:150-183). Android owes the equivalent Compose/View floated over the SurfaceView (chrome overlay plumbing already exists, ChromeOverlayView.kt). No shared change.
- **Blocking constraint:** Dual-VM marshalling tax — keep reading the cached volatile snapshot (OverlayBridge.getOverlaysJson is lock-free, :116-119); no per-frame bridge calls.
- **Tag:** [native-android] — iOS: free-after-merge (origin/parity-b4-overlays ios/app/HelloShell/OverlayEditView.m:150-183 (bounds->screen handles over live game); same bounds JSON OverlayBridge.java:316)
- **Current status:** partial  **Desktop target:** in-place edit with live bounds + snap highlights — OverlayRenderer.java:258-364

#### No per-plugin Reset-to-defaults button
- **Bucket:** restore-missing
- **Problem:** Desktop ConfigPanel has a `Reset` button that, after a confirm dialog, restores a plugin's whole config to defaults (setDefaultConfiguration + plugin.resetConfiguration, ConfigPanel.java:423-444). Android's ConfigScreen has no reset of any kind. The shared bridge only has single-key resetConfig (MobileUiBridge.java:1389); the whole-plugin resetPluginConfig the iOS branch added does not exist on android.
- **Impact:** A user who messes up a plugin's settings (especially the now-editable color/dimension/set fields) has no one-tap revert — they must manually fix each field, and some fields (keybind/notification) aren't even editable to revert.
- **Effort:** M
- **Mechanism (path:line):** Port origin/parity-b1-config's additive resetPluginConfig(group) (wraps setDefaultConfiguration on the client thread) and add a confirm-dialog `Reset to defaults` button at the bottom of ConfigScreen mirroring ConfigPanel.java:423-444.
- **Blocking constraint:** Shared core additive-only — resetPluginConfig is a new additive bridge method; confirm UI is native veneer.
- **Tag:** [shared-core] — iOS: free-after-merge (origin/parity-b1-config MobileUiBridge adds public static String resetPluginConfig(String group); ios PluginConfigViewController.m renders a `resetPlugin` row + confirmResetPlugin)
- **Current status:** missing  **Desktop target:** Per-plugin Reset (setDefaultConfiguration + resetConfiguration) — ConfigPanel.java:423-444

#### Rendered-image fallback panels with text fields (HiScore, Notes, Skill Calculator) cannot be typed into
- **Bucket:** restore-missing
- **Problem:** The rendered-image fallback supports ONLY taps: PanelView.kt:184-192 wires detectTapGestures->panelClick, and MobileUiBridge.panelClick (:645-677) synthesizes MOUSE_PRESSED/RELEASED/CLICKED only — no KeyEvent/text dispatch anywhere (PanelView.kt has no IME). All three text-driven panels reach panelNavs() (only `Configuration` excluded, :398-411) so they appear in the list but their text input is dead: HiScore (player-name lookup), Notes (free-text JTextArea), Skill Calculator (level/xp entry).
- **Impact:** High for HiScore (can't type a player name = the whole panel is useless) and Notes (can't edit); Medium for Skill Calculator. `Present in the list but the primary interaction is impossible` — worse than missing because it looks available.
- **Effort:** L
- **Mechanism (path:line):** Either (1) add a shared panelText(String)/panelKey path to MobileUiBridge that focuses the deepest text component at a tapped point (SwingUtilities.getDeepestComponentAt already used at :663) and dispatches KeyEvents, plus an IME field in PanelView.kt; OR (2) build native reflows for the highest-value two (HiScore name field + skill grid over a HiScore data bridge; Notes native editor writing the notes config). Native reflow is cleaner per the settled two-tier design.
- **Blocking constraint:** Plugins unmodified + additive-only — can't fork NotesPanel/HiscorePanel; fix is a shared text-dispatch bridge method or a data-bridge-backed native screen. Touch-keyboard IME on real hardware is also UNVERIFIED.
- **Tag:** [native-android] — iOS: native-only (n/a — no iOS branch builds a HiScore/Notes/SkillCalculator data bridge or panel-text dispatch; unbuilt on both ports)
- **Current status:** broken  **Desktop target:** Keyboard-entry panels (HiScore name, Notes, Skill Calculator) — HiscorePanel.java, NotesPanel.java, UICalculatorInputArea.java

#### Rendered-image fallback truncates panels taller than 2500px and goes blurry (no supersample) when tall
- **Bucket:** improve-degraded
- **Problem:** The generic rendered-Swing-image fallback (the only path for Notes, HiScore, Skill Calculator, Time Tracking, Party, Kourend Library, Info, Screen Markers) hard-caps content at PANEL_MAX_H=2500 logical px (MobileUiBridge.java:459,:562) and printAll paints only that top window (:577) — anything below 2500px is silently lost. To fit the 3M-px budget the supersample scale steps down toward 1 for tall panels (:566-571) and the native side upscales scale-1 pixels via ContentScale.FillWidth (PanelView.kt:195) = visibly blurry. (The prior `tall panel OOM-blanks` claim is NO LONGER true — the scale-step-down keeps the buffer under MAX_BUFFER_PX.)
- **Impact:** Medium-High for panels on this path: content past ~2500px is unreachable (the buffer doesn't contain it) and the readable area is blurry on tall panels — the residual quality floor for every panel without a native reflow.
- **Effort:** M
- **Mechanism (path:line):** Shared-core-additive. (a) Window the render: track a scrollTop in MobileUiBridge.renderActiveOnEdt and translate the Graphics2D so the painted 2500px window follows the native scroll position (PanelView.kt scroll.value), letting taller panels be reached; OR (b) raise PANEL_MAX_H relying on the scale-step-down (more blur). Best long-term fix is more native reflows (e.g. Time Tracking) so fewer panels hit this path.
- **Blocking constraint:** Dual-VM marshalling + shared heap — the buffer lives on the game-shared heap (MobileUiBridge.java:464-466 budget), so the height cap can't simply be removed; windowed-render is the additive-safe path.
- **Tag:** [shared-core] — iOS: shared-work-owed (MobileUiBridge is shared with iOS; iOS consumes the same openPanel/panelPixels fallback so a windowed-render fix benefits both — no iOS branch shows it built)
- **Current status:** partial  **Desktop target:** Arbitrarily scrollable side panels — MobileUiBridge.java:459 caps at 2500px

#### Minimap orbs render at native (small) size with no enlarged touch veneer
- **Bucket:** improve-degraded
- **Problem:** The relocated MAP_CONTAINER cluster carries the orbs (HP/prayer/run/spec/world-map/wiki/store) as live tappable children, but they render at the engine's fixed ~25px size with NO native enlargement — unlike the chat tabs (enlarged ChatPillView) and the tab stones (native grid). There is zero orb veneer in the Kotlin layer.
- **Impact:** On a phone the orbs are small, dense, adjacent touch targets (spec, prayer flick, run toggle, compass-reset) next to the minimap walk surface — a mis-tap walks the player or hits the wrong orb. Desktop solves this with a precise mouse.
- **Effort:** M
- **Mechanism (path:line):** Mirror the chat-pill pattern: publish each orb's live canvas bounds in a per-frame snapshot (the dump scaffold HudVeneerBridge.dumpMinimapTree:871-883 already enumerates ORB_HEALTH/PRAYER/RUNENERGY/SPECENERGY/STORE/WORLDMAP/WIKI), then draw enlarged native buttons over them proxying a precise click to the live orb centre (same as ChatPillView.engineButtonCenter -> JvmLauncher.click at canvas px, ChatPillView.kt:166-170). Orbs stay live engine widgets; only the touch target grows.
- **Blocking constraint:** Engine hard-pins the orbs every frame (setForcedPosition ignored at render, HudVeneerBridge.java:984-988) — the fix must be a native veneer drawing OVER them + proxy-clicking, never a widget reposition. Requires a shared orb-bounds snapshot (additive, like getChatSnapshot:229).
- **Tag:** [native-android] — iOS: shared-work-owed (The orb-bounds snapshot would be a shared HudVeneerBridge addition (like getChatSnapshot:229) reused by iOS; the native draw is per-platform)
- **Current status:** partial  **Desktop target:** Minimap orb cluster (HP/prayer/run/spec/compass/world-map) — HudVeneerBridge.java:871-878

#### No config-profiles UX (create/clone/rename/import/export/switch)
- **Bucket:** restore-missing
- **Problem:** Desktop ProfilePanel offers a full profiles manager — create, clone, rename, delete, import/export via JFileChooser, switch active profile, toggle sync — backed by ProfileManager + ConfigManager.switchProfile/renameProfile. Android has NONE: no profiles screen (no Kotlin reference) and no profile bridge methods in MobileUiBridge.java. The app silently runs the single default profile.
- **Impact:** Users with multiple accounts/setups (a common RuneLite use case — a profile per character/activity) cannot create or switch profiles on Android, nor import a desktop-exported profile or export the phone's config.
- **Effort:** L
- **Mechanism (path:line):** New additive shared bridge methods wrapping ProfileManager/ConfigManager: listProfiles, switchProfile(id), create/clone/rename/delete, and import/export (export to filesDir / share sheet; import via SAF document picker instead of JFileChooser). Then a native Compose profiles screen. switchProfile must run on the client thread and re-fetch snapshots (ProfilePanel.java:159-454; ConfigManager.switchProfile:160). Benefits iOS.
- **Blocking constraint:** Shared core additive-only — baseline.txt may only grow; switchProfile's marshalling tax (re-reads all config) must stay on the rl-bridge thread to avoid ANR.
- **Tag:** [shared-core] — iOS: shared-work-owed (n/a — no profiles bridge on any parity branch; neither port has built it)
- **Current status:** missing  **Desktop target:** ProfilePanel create/clone/rename/import/export/switch — ProfilePanel.java:159-454, ConfigManager.switchProfile:160

#### Notification sound (NATIVE beep / CUSTOM .wav) is silently dropped — no Android audio backend and the payload carries no sound type
- **Bucket:** restore-missing
- **Problem:** Desktop plays a sound on notification: NATIVE -> Toolkit.beep(), CUSTOM -> AudioPlayer.play of a user .wav (Notifier.java:205-213). On Android: (1) the shared 2-arg publishNotification packs only title+body (MobileUiBridge.java:211) so no sound type crosses the bridge; (2) AudioPlayer is built on javax.sound.sampled (AudioPlayer.java:33-41) with no impl in the embedded Android JRE; (3) native deliverNotification builds the NotificationCompat with the default channel sound, never honoring the per-notification sound (MainActivity.kt:1012-1019).
- **Impact:** Audible alerts (the primary way idle/level-up notifications get attention when the screen is off) do not play; custom sounds and per-notification volume are absent. Only the OS channel default is audible.
- **Effort:** M
- **Mechanism (path:line):** Carry the sound across the bridge (iOS already did: parity-b1-config's publishNotification(title,body,soundType,volume) appends sound+volume — additive). Natively map NATIVE->channel default sound and CUSTOM->per-notification setSound(Uri) on the Builder at MainActivity.kt:1012; volume maps to channel importance or a sound attribute. Config-policy + native veneer; the unmodified Notifier already emits the sound type.
- **Blocking constraint:** Shared core additive-only (4-arg overload) + the unmodified AudioPlayer/javax.sound path cannot work on Android — the sound MUST be re-homed to the native NotificationManager channel, not played in-VM.
- **Tag:** [shared-core] — iOS: shared-work-owed (origin/parity-b1-config adds publishNotification(title,body,soundType,volume); the native shell still owes the Android channel-sound mapping)
- **Current status:** missing  **Desktop target:** Notifier sound branch (Toolkit.beep / AudioPlayer custom .wav) — Notifier.java:205-213,472-497

### Tier 3

#### Overlay 'Configure' (SHIFT+right-click) menu has no Android path
- **Bucket:** restore-missing
- **Problem:** Desktop adds an overlay's own menu entries (Configure, plugin-specific) to the right-click menu while hovering the overlay with SHIFT held (OverlayRenderer.onClientTick:188-210). Android's OverlayBridge exposes NO overlay menu entries (no getMenuEntries/OverlayMenuClicked in OverlayBridge.java), and the sticky-Shift latch (MainActivity.kt:308-314) feeds the WORLD press-and-peek menu, not overlay hover — so the overlay Configure menu is entirely absent.
- **Impact:** Overlays that put settings/actions behind the shift-hover menu (a common RuneLite idiom) are unreachable on Android.
- **Effort:** M
- **Mechanism (path:line):** Additive shared bridge: OverlayBridge.getOverlayMenu(name) (read overlay.getMenuEntries() like InfoBoxBridge.java:226-241) + invokeOverlayMenu(name,option) firing the callback (mirror OverlayRenderer.java:205-209, InfoBoxBridge.doInvoke:182-218). Surface as buttons in the overlay editor row. Benefits iOS. No plugin fork.
- **Blocking constraint:** Shared core additive-only — new OverlayBridge methods only grow the surface.
- **Tag:** [shared-core] — iOS: native-only (n/a — iOS b4 (OverlayEditView.m) shipped move/snap/reset only; no overlay-menu path on either branch)
- **Current status:** missing  **Desktop target:** SHIFT-hover overlay `Configure`/plugin menu entries — OverlayRenderer.java:188-210

#### Overlay resize (hover-cursor edge drag) is missing on Android
- **Bucket:** restore-missing
- **Problem:** Desktop resizes resizable overlays by hovering an edge and dragging (OverlayRenderer.java:436-594 setPreferredSize). OverlayBridge emits an overlay's resizable flag (:322) but offers no setPreferredSize method, and OverlayEditScreen never reads resizable or offers any resize control.
- **Impact:** Players cannot size resizable overlays (screen-markers, certain panels); they are stuck at default dimensions, which on a phone can cover the scene or be too small to read.
- **Effort:** M
- **Mechanism (path:line):** Additive shared setOverlayPreferredSize(name,w,h) calling overlay.setPreferredSize + saveOverlay (mirror setOverlayPreferredLocation, OverlayBridge.java:125-152). Native: a corner resize handle on the in-place editor gated on the resizable flag already in the JSON. Benefits iOS.
- **Blocking constraint:** Shared core additive-only — new setOverlayPreferredSize grows the contract.
- **Tag:** [shared-core] — iOS: native-only (n/a — iOS b4 OverlayEditView.m did not ship resize either)
- **Current status:** missing  **Desktop target:** hover-cursor edge resize — OverlayRenderer.java:436-594

#### Infobox drag-to-merge / drag-onto-target merge is missing (only menu Detach/Flip/Delete exist)
- **Bucket:** restore-missing
- **Problem:** Desktop lets you Alt+drag one overlay onto another drag-target (OverlayRenderer.java:349-354,632-640 calls dragTargetOverlay.onDrag) — the mechanism infoboxes use to merge groups. Android's infobox UI is index-based with menu buttons only (InfoBoxScreen.kt:106-117); the bridge exposes no per-infobox bounds and no onDrag/merge call (InfoBoxBridge.java:50-59). (Group Delete = merge-into-default IS reachable via the Delete entry, InfoBoxManager.java:136-145, so the merge OUTCOME is partially reachable.)
- **Impact:** Players who organize infoboxes by dragging groups together cannot do so; only the coarse Detach/Flip/Delete menu actions are available.
- **Effort:** L
- **Mechanism (path:line):** Additive shared InfoBoxBridge.dragInfoBoxOnto(srcIndex,dstIndex) posting the same splitInfobox/mergeInfoBoxes path, OR expose onDrag for infobox overlays. Harder because merge is layer-keyed and the index snapshot loses layer identity — the bridge must also carry layer. Native: long-press-drag one infobox row onto another.
- **Blocking constraint:** Engine wipes infobox component bounds every frame (InfoBoxBridge.java:50-57) — no off-thread hit-test; must stay index/layer-keyed, not pixel-drag on the canvas.
- **Tag:** [shared-core] — iOS: native-only (n/a — no iOS branch shipped infobox drag-merge)
- **Current status:** missing  **Desktop target:** drag-onto-target overlay/infobox merge — OverlayRenderer.java:349-354,632-640

#### Overlay/widget hover tooltip subsystem has no touch trigger
- **Bucket:** rehome-touch
- **Problem:** Desktop overlays push help text into TooltipManager during overlay.onMouseOver (OverlayRenderer.java:366-370) and TooltipOverlay renders it under the cursor (TooltipOverlay.java:71-85). On touch there is no persistent hover, so hover-fed tooltips never display. Only infobox tooltip TEXT is rehomed (into the infobox list label, InfoBoxScreen.kt:102); generic overlay/widget tooltips have no veneer.
- **Impact:** Contextual hover help (an overlay explaining a counter, a hover tooltip on a screen marker) is silently lost; the player gets less information than on desktop.
- **Effort:** L
- **Mechanism (path:line):** Press-and-peek already pre-hovers the engine pointer to the finger (MainActivity.kt:404), which can populate TooltipManager for the held point. A rehome reads TooltipManager.getTooltips() via a small additive bridge and shows the current tooltip in a native bubble at the finger during a long-press. Most overlay tooltips are plugin-driven on mouseover, so coverage is best-effort.
- **Blocking constraint:** No hover on touch (inherent) — and tooltips are transient/per-frame, so any bridge must snapshot on the client thread, not read off-thread.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** missing  **Desktop target:** hover tooltip subsystem — OverlayRenderer.java:366-370 + TooltipOverlay.java:71-85

#### World-map POI tooltips have no touch equivalent
- **Bucket:** rehome-touch
- **Problem:** Desktop world-map POI tooltips (agility shortcuts, fairy rings, teleports, fishing spots) are engine-rendered on mouse HOVER (WorldMapPlugin.java:332-577 .tooltip(...) on dozens of point types). On Android there is no sustained hover: a finger DOWN fires one static JvmLauncher.move() pre-hover (MainActivity.kt:404-407) with no dwell, and a press inside the map is immediately claimed as a precise tap or a pan, so a tooltip never gets the hover it needs.
- **Impact:** All worldmap-plugin tooltip metadata (marker identity, fairy-ring code, agility level, teleport name) is invisible on Android — meaningful information loss in the most plugin-enriched part of the map.
- **Effort:** M
- **Mechanism (path:line):** Re-home hover to a slow press / press-and-hold-still inside the map (distinct from long-press which opens the menu) driving a sustained JvmLauncher.move() to the finger each frame so the engine renders the tooltip under the finger. The engine composites the tooltip into the frame so no readback is needed; only feed a steady hover point while the finger is down-and-still inside mapRectCanvas. Native-only.
- **Blocking constraint:** Touch has no persistent hover and the engine renders the tooltip only while the cursor dwells over the icon — a single DOWN-move pre-hover can't trigger it; a sustained hover-feed gesture must be added.
- **Tag:** [native-android] — iOS: native-only (n/a — pure native gesture handling per platform)
- **Current status:** missing  **Desktop target:** world-map POI hover tooltips — WorldMapPlugin.java:332-577

#### Hover is a single one-frame MOUSE_MOVED on touch-down — no dwell, hover-only plugin cues stay inert
- **Bucket:** rehome-touch
- **Problem:** Desktop hover (enter/exit/move/dwell + tooltips, ground-item hover highlight, NPC/object outlines) needs a sustained cursor position. On Android, move() fires once on ACTION_DOWN of a fresh single-finger touch (MainActivity.kt:404-406), then parkPointer() moves the cursor to (-1,-1)+EXITED after the tap (JavaBridge.java:541). No dwell timer, no sustained hover.
- **Impact:** Hover-only feedback — tooltips, hover-highlight on ground items/NPCs/objects, any MOUSE_MOVED-dwell plugin — never appears or flashes for one frame.
- **Effort:** M
- **Mechanism (path:line):** Re-home hover to an explicit gesture: a long-press-and-hold-without-menu (or a dedicated `inspect` mode) streaming JvmLauncher.move(gx,gy) while the finger is down (move()->onMove->MOUSE_MOVED, JavaBridge.java:1213), parking only on lift. move()/onMove exist; native gesture wiring plus deciding hover-vs-menu. No shared change for basic sustained hover.
- **Blocking constraint:** Gesture collision — long-press is already the right-click menu (MainActivity.kt:711); a sustained-hover mode must be a distinct gesture/mode toggle so it doesn't cannibalize menu-peek.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** partial  **Desktop target:** Engine hover (MOUSE_MOVED dwell) + tooltip/hover-highlight plugins — JavaBridge.onMove JavaBridge.java:1213

#### No sidebar-toggle / panel-column collapse — desktop Ctrl+F11 hide-the-panel-column has no mobile re-home
- **Bucket:** restore-missing
- **Problem:** Desktop ClientUI.toggleSidebar (Ctrl+F11 + on-canvas arrow) hides/shows the entire side-panel column and persists it (clientSidebarClosed). On Android the side panel is relocated flush-left of the tab grid (applySidePanel) but there is no affordance to collapse/dismiss it once a plugin panel is open — grep finds no sidebarToggle/collapseSidePanel in the host. The native plugin panel is a separate Compose overlay; the desktop notion of toggling the live engine side-panel column is absent.
- **Impact:** On a small screen an open engine side panel (equipment/inventory tab content) permanently occupies the bottom-right next to the grid with no quick hide; the desktop one-key viewport reclaim is lost.
- **Effort:** M
- **Mechanism (path:line):** Re-home toggleSidebar to a native button/gesture driving the existing relocate/release path: a collapse toggle that calls releaseSidePanel (push off-canvas) and re-applySidePanel, mirroring the chat pill's collapse model. The shoving primitive exists (relocateSidePanel can take an off-canvas X like the chat at y=3000); a native toggle + a small shared off-canvas-side parameter may be needed (MainActivity.kt:1119-1139; HudVeneerBridge.java:191).
- **Blocking constraint:** Engine hard-pins the side container each frame — collapse must be a relocate (shove off-canvas) like the chat pill, not a widget mutation; any new shared helper must be additive-only.
- **Tag:** [native-android] — iOS: shared-work-owed (HudVeneerBridge.relocateSidePanel is shared (HudVeneerBridge.java:191); a collapse variant would benefit iOS too)
- **Current status:** missing  **Desktop target:** Ctrl+F11 toggle side-panel column — ClientUI.java:1076-1127

#### No Ctrl/Alt/middle-click modifier surface — Ctrl-click and middle-click interactions unreachable
- **Bucket:** restore-missing
- **Problem:** Desktop has modifier-gated behaviors beyond shift: Ctrl-click, Alt, middle-click (BUTTON2). The bridge supports only BUTTON1 (tap) and BUTTON3 (long-press menu): onClick/onTouch map button 3->BUTTON3 else BUTTON1, no BUTTON2 (JavaBridge.java:1425), and the only modifier is setShiftHeld (:1382). No setCtrlHeld/setAltHeld, no middle-click gesture.
- **Impact:** Any Ctrl/Alt-modified click or middle-click interaction can't be performed on Android. (Camera middle-drag is separately handled via onCameraDrag at JavaBridge.java:1248, so the common middle-mouse-camera case is covered.)
- **Effort:** M
- **Mechanism (path:line):** Generalize the sticky-modifier pattern: additive setCtrlHeld/setAltHeld mirroring setShiftHeld (JavaBridge.java:1382) surfaced as native latch buttons next to the Shift latch (MainActivity.kt:308). Middle-click needs a button-2 gesture routing onClick(.,.,2) + a BUTTON2 case in onClick/onTouch (absent, :1425). Bridge additions benefit iOS.
- **Blocking constraint:** Additive shared bridge required (setCtrlHeld/setAltHeld + a BUTTON2 path); baseline grows. Lower priority — Ctrl/Alt/middle-click interactions are far rarer in OSRS than shift.
- **Tag:** [shared-core] — iOS: shared-work-owed (n/a)
- **Current status:** missing  **Desktop target:** Ctrl/Alt modifier behaviors + BUTTON2 middle-click — setShiftHeld is the existing shift-only analogue JavaBridge.java:1382

#### Loot Tracker native reflow is view-only — desktop reset/clear actions absent
- **Bucket:** improve-degraded
- **Problem:** The Loot Tracker native screen (LootTrackerScreen, PanelReflowScreens.kt:59-115) is purely display: it lists sources/items/values and never offers the desktop panel's per-source actions. LootTrackerBridge exposes only isAvailable/setUiOpen/getLootJson/tick (:97,:103,:112,:121) — no clear, reset, or collapse. The desktop side panel lets you reset/clear a tracked source and collapse records.
- **Impact:** The most-used loot panel is read-only on mobile; you can see loot but can't reset a session count or clear records. Not blocking (data is the main value) but a parity gap.
- **Effort:** M
- **Mechanism (path:line):** Add an additive LootTrackerBridge.reset(sourceName)/clearAll() routing onto the client thread (bridge already holds PluginManager+ClientThread, LootTrackerBridge.java:90) and calling the plugin's reset reflectively (plugin unmodified), then nativeLootReset in JvmLauncher.kt + a swipe/long-press on LootSourceItem (PanelReflowScreens.kt:100). Confirm the plugin exposes a resettable API first.
- **Blocking constraint:** Additive-only + unmodified plugin — reset must be a new shared bridge method reaching the plugin reflectively, not a plugin edit.
- **Tag:** [shared-core] — iOS: shared-work-owed (LootTrackerBridge present on parity branches but only the read path; the action half is unbuilt on both ports)
- **Current status:** partial  **Desktop target:** Loot Tracker reset/clear per source — LootTrackerBridge.java:97 (read only)

#### Screen Markers panel reaches the fallback but its core action (drag-to-draw a rectangle) is unreachable
- **Bucket:** rehome-touch
- **Problem:** ScreenMarkerPluginPanel.java reaches panelNavs() and renders via the click-only rendered-image fallback (only `Configuration` excluded, MobileUiBridge.java:398-411). The plugin's defining interaction is free-form mouse-drag-to-draw a rectangle over the viewport, which the fallback's tap-only panelClick (:645, no drag) cannot perform and which has no touch home that doesn't collide with camera/press-and-peek gestures bound to the viewport.
- **Impact:** Low-Medium — niche QoL panel; on mobile the panel is visible but you cannot create a new marker (the drag-rectangle step is impossible).
- **Effort:** L
- **Mechanism (path:line):** If ever justified: a native marker-creation flow (tap two corners, or a draggable resize handle) feeding an additive ScreenMarkerBridge.create(x,y,w,h) on the client thread — full native reimplementation, not a fallback tweak. Lower priority; reasonable to defer.
- **Blocking constraint:** Touch gesture collision + unmodified plugin — free-form drag-on-viewport has no clean mapping over existing camera/peek gestures; can't fork the plugin.
- **Tag:** [native-android] — iOS: native-only (n/a — no Screen Marker bridge on any parity branch; unbuilt on both ports)
- **Current status:** broken  **Desktop target:** Screen Markers drag-to-draw rectangle — ScreenMarkerPluginPanel.java

#### Orb taps arm the walk-ring optimistically (600ms) before the interact truth resolves
- **Bucket:** improve-degraded
- **Problem:** A tap on an orb goes through the generic tap path (onSingleTapUp), arming ringPending and posting a click; the ring resolves to INTERACT (red) once interactTapSeq bumps, else falls back to WALK (yellow) after RING_GRACE_MS=600ms. Orb ops DO bump interactTapSeq (JavaBridge:824) so the ring eventually shows red — but only the open world-map WINDOW suppresses the ring up front (onSingleTapUp:692-700). Orbs/compass/spec taps get a click ring drawn at the finger.
- **Impact:** Tapping the minimap orb area flashes a tap ring over the HUD (then resolves red on a beat's delay on slow frames). Cosmetic, but on a slow EDT a real interact can mis-flash, and a ring over the orbs is visual noise desktop never shows.
- **Effort:** S
- **Mechanism (path:line):** Extend the world-map-window suppression at onSingleTapUp:692 to also suppress the walk-ring when the tap lands inside the relocated MAP_CONTAINER cluster bounds — that rect is ALREADY published (MobileChromeBridge.java:191-192) and read natively (MainActivity.kt:1249), so just cache it for the tap-time hit test like chatRectCanvas (MainActivity.kt:1063-1066). Native-only.
- **Blocking constraint:** None — purely a native gate using a bounds rect already published and read.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** partial  **Desktop target:** Orb clicks give no walk-feedback ring (HUD interactions, not world walks) — JavaBridge.java:822

#### No input accessory bar over the soft keyboard (iOS has one) — special keys awkward while typing
- **Bucket:** improve-degraded
- **Problem:** Text input works (auto-raise on isTextInputOpen + manual KB toggle, committed text via onText, special keys via onKeyStroke from GameInputView — MainActivity.kt:294-301,845-851), but there is no input-accessory bar (no accessory ref in MainActivity). iOS ships a manual keyboard button + accessory bar (origin/parity-b2-rightbar:ios/MOBILE-UX-AUDIT.md:527). On Android special-key access depends entirely on the system IME; the engine's Tab/Esc/function keys have no dedicated touch surface during typing.
- **Impact:** While a game text field is open the user can't easily send Esc/Tab/Enter or any engine key the chosen IME omits. Clumsy-but-works (ENTER/BACKSPACE/arrows are mapped at MainActivity.kt:845-851), not a hard block.
- **Effort:** S
- **Mechanism (path:line):** Add a small native accessory row (Compose/View) shown while keyboardShown is true (MainActivity.kt:898) with Esc/Tab/Enter buttons calling JvmLauncher.keyStroke(VK_*) — keyStroke routes to onKeyStroke (JavaBridge.java:1338). Needs two added VK constants (ESCAPE/TAB not yet declared at MainActivity.kt:163-168). Pure native veneer.
- **Blocking constraint:** None structural — purely native veneer; lowest-priority polish.
- **Tag:** [native-android] — iOS: native-only (iOS ships a manual keyboard button + accessory bar (origin/parity-b2-rightbar:ios/MOBILE-UX-AUDIT.md:527); shared onKeyStroke exists for both, Android owes the full native row)
- **Current status:** partial  **Desktop target:** Physical keyboard special keys (Esc/Tab/Enter) — JavaBridge.onKeyStroke JavaBridge.java:1338

#### Idle/Chat notification flash & request-focus paths are partly inert (focus + request-focus are no-ops)
- **Bucket:** improve-degraded
- **Problem:** The screen-flash channel works (processFlash renders into the blitted CPU framebuffer, Notifier.java:255-305 via Hooks.java:610) and the shade notification is wired. But the surrounding logic assumes a desktop window: FLASH_UNTIL_CANCELLED only cancels when clientUI.isFocused() (Notifier.java:282), and HeadlessClientUI.isFocused() is always true (HeadlessClientUI.java:206-224), so the until-cancelled branch behaves differently; the requestFocus/TASKBAR/FORCE branch (Notifier.java:187-198) calls no-op headless methods. Stock defaults are DISABLED flash + OFF request-focus, so this surfaces only on explicit opt-in.
- **Impact:** For notifications configured FLASH_UNTIL_CANCELLED the focus-gated cancel can't behave like desktop, and the `request window focus / flash taskbar` cue has no mobile equivalent beyond the shade notification. The 2-second flash variants work; until-cancelled + focus-attention are degraded.
- **Effort:** M
- **Mechanism (path:line):** Config policy + native veneer: clamp the mobile-profile default FlashNotification to a finite variant (FLASH_TWO_SECONDS) so the until-cancelled focus dependency never engages (no applyMobileDefaults clamp exists today, MobileUiBridge.java:1183), and treat request-focus modes as satisfied by the delivered system notification. For a stronger cue, re-home TASKBAR/FORCE to a heads-up notification + optional VIBRATE (permission already declared, AndroidManifest.xml:6).
- **Blocking constraint:** Notifier is shared/unmodified and HeadlessClientUI.isFocused() is structurally always-true on a fullscreen app — the focus-gated cancel can't mimic a desktop window; re-home to a finite flash + shade/vibrate via config policy.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** partial  **Desktop target:** FlashNotification until-cancelled + RequestFocusType taskbar/force — RuneLiteConfig.java:219/285, Notifier.java:187-198,282

#### Sticky-Shift latch unverified on Android hardware + no per-frame re-assert on focus loss
- **Bucket:** improve-degraded
- **Problem:** The sticky-Shift toggle is wired correctly (MainActivity.kt:308-313 -> setShiftHeld -> JavaBridge.java:1382 posts one held VK_SHIFT, ordered ahead of the menu-open click). But its proof basis is ShiftFeedSpike + an `iOS-device-proven` comment (MainActivity.kt:306) — NOT an Android device. setShiftHeld posts ONE held press with no per-frame re-assert, and JavaBridge's own doc warns `if a long hold is ever observed to clear — e.g. on a focus change — a per-frame re-assert is the fix` (JavaBridge.java:1378-1379). On Android the soft keyboard/IME raise-dismiss churns Canvas focus frequently (MainActivity.kt:880-916) — exactly the flagged risk.
- **Impact:** If the held VK_SHIFT clears on an Android focus change (IME show/hide, dialog), shift-gated entries (Menu Entry Swapper shift-customize, shift deposit/withdraw, Shift+right-click Mark, overlay Configure) silently stop swapping with the latch still showing ON — a confusing dead toggle. UNVERIFIED on real hardware.
- **Effort:** S
- **Mechanism (path:line):** Re-assert the held VK_SHIFT from the per-frame register/drain when shiftHeld is latched (the doc's own named fix, JavaBridge.java:1378-1379); the transition-gate in setShiftHeld (:1384) guards double-post so a re-assert needs a re-post path. Verify on an Android device first.
- **Blocking constraint:** Emulator-GREEN != device-confirmed — the focus-churn (IME raise/dismiss clearing modifier state) only manifests on real hardware with a real IME.
- **Tag:** [shared-core] — iOS: free-after-merge (setShiftHeld is shared (JavaBridge.java:1382); the comment marks it iOS-device-proven so iOS already exercises it)
- **Current status:** unverified  **Desktop target:** client.isKeyPressed(KC_SHIFT) shift-click swaps + Shift+right-click Mark — JavaBridge.setShiftHeld JavaBridge.java:1382

#### World-map search: typed text reaching the engine filter is unverified
- **Bucket:** improve-degraded
- **Problem:** The search path is wired structurally (tap the engine SEARCH rect -> mapSearchKeyboard latch -> manual IME raise -> committed text via onText) but it has never been confirmed that typed text actually reaches the engine's world-map search filter. The map search does not flip VarClientInt.INPUT_TYPE (probe-proven, WorldMapProbe.java:628-654), so the keyboard is raised heuristically off a rect-hit latch not engine focus; on the emulator the IME occluded the field and the map didn't jump on Enter.
- **Impact:** Search is the primary way to jump the map to a named place; if text doesn't land the feature is decorative — the user can raise a keyboard but can't navigate by name, and there's no native search box fallback.
- **Effort:** M
- **Mechanism (path:line):** Verify + harden the IME->engine path (GameInputView.commitText -> JvmLauncher.text() -> onText, MainActivity.kt:830-836; latch read in startInputPolling at :887). Gaps: (1) ensure the engine SEARCH widget holds keyboard focus when latched (probe found INPUT_TYPE does NOT flip, so a synthetic focus or a routed click is needed before text); (2) add a keyboard-resize layout so the IME doesn't occlude the field; (3) consider a small native search overlay forwarding to onText. Native-veneer / additive-bridge.
- **Blocking constraint:** The map search does not raise the engine input type (INPUT_TYPE stays 0, probe-proven) — the keyboard can't be driven by engine focus state, so it must be latched natively and text routed blind, requiring end-to-end verification.
- **Tag:** [native-android] — iOS: shared-work-owed (iOS Phase-1 (74557217e7) added the same search-keyboard path; if iOS verified text-to-engine, the verification + any focus-bridge fix is shared)
- **Current status:** unverified  **Desktop target:** World-map search box jump-to-place — shared onText; WorldMapProbe.java:628-654

#### World-map 'Focus on' / right-click actions reachable but unverified on the map
- **Bucket:** rehome-touch
- **Problem:** Desktop exposes world-map context actions (`Focus on`, marker/teleport options) via right-click. On Android long-press always routes to beginMenuPeek -> JvmLauncher.click(gx,gy,3) at the finger (MainActivity.kt:711-713,1281-1295) with NO special-case for the open map, so the engine's world-map right-click menu SHOULD surface through the native menu-peek overlay. But this is not gated on mapOpen, the menu-peek was tuned for the live scene (settle gate, hand-side), and there's no evidence it was exercised against an open map.
- **Impact:** If it works, parity is free; if the menu-peek heuristics misbehave over the map (different menu geometry, or the precise-tap union swallows the press), `Focus on` and per-POI actions are silently unreachable.
- **Effort:** S
- **Mechanism (path:line):** Verify long-press over an open world-map POI produces the engine menu through the existing overlay (beginMenuPeek, MainActivity.kt:1281-1295) and that onSingleTapUp's union-rect gate (:692-701) doesn't pre-empt the long-press. If the settle/hand-side logic misfires over the map, add a mapOpen branch reusing JvmLauncher.click(...,3) with map-appropriate anchoring. Native-only; the engine builds the menu.
- **Blocking constraint:** Right-click is the only surface for `Focus on`; re-homed to long-press but unverified against the world-map interface (emulator-GREEN covered only key-panel toggle + walk-ring suppression).
- **Tag:** [native-android] — iOS: native-only (n/a — native gesture/menu handling per platform)
- **Current status:** unverified  **Desktop target:** World-map right-click `Focus on` / per-POI actions — engine-built menu via long-press peek

#### Pinch-to-zoom over the open map is device-unverified (emulator-only wheel path)
- **Bucket:** improve-degraded
- **Problem:** Map zoom is the engine wheel-notch path: pinch -> ScaleListener.onScale -> JvmLauncher.wheel(gx,gy,+/-1) at the centroid (MainActivity.kt:788-799), plus engine ZOOM_IN/ZOOM_OUT buttons via precise taps. The probe confirmed the wheel/button oracles headlessly (WorldMapProbe.java:579-626) but multitouch pinch has never run on real hardware — PARITY-PHASING flags `multitouch needs the device wall.`
- **Impact:** If pinch is janky or the centroid maps wrong on a real digitizer, users fall back to the small engine zoom buttons (precise-tap only) — the clumsier desktop-button experience rather than natural pinch.
- **Effort:** S
- **Mechanism (path:line):** Device-verify pinch-zoom-over-map; the wheel path is proven (WorldMapProbe.java:579-626) and the zoom buttons are in the precise-tap union fallback. Likely zero code change — verification + PINCH_STEP tuning on real hardware.
- **Blocking constraint:** Emulator-GREEN != device-confirmed — pinch is multitouch + the centroid->wheel conversion is untested on a real digitizer.
- **Tag:** [native-android] — iOS: native-only (n/a — multitouch is per-platform native input)
- **Current status:** partial  **Desktop target:** Mouse-wheel map zoom — WorldMapProbe.java:579-626 (proven headlessly)

#### Chrome relocate trio + grid layout are emulator-GREEN only — pinch/IME/haptics/cutout-inset/surface-recreate unconfirmed on hardware
- **Bucket:** improve-degraded
- **Problem:** The native-chrome layout pipeline depends on device-real values that are unverified: ChromeOverlayView.safeInsets/computeLayout sizes the grid against displayCutout+systemBars insets; applyChatAtTop/applyMinimap/applyOverlayInsets convert view-px insets to canvas-px against frameW/viewW. On the full-bleed emulator insets are 0, so notch clearance, the 0.56 grid fit, and the relocate math are untested against a real landscape cutout. SurfaceView surface-recreate, pinch-to-wheel, IME auto-raise, and haptics are likewise emulator-only.
- **Impact:** On a real phone the tab grid, relocated chat/minimap, side panel, and overlay snap corners could collide with the notch/rounded corners or gesture nav bar; surface-recreate could blank the frame — none of which the emulator surfaces.
- **Effort:** M
- **Mechanism (path:line):** Device pass: run on a notched landscape device, confirm safeInsets reports nonzero and the grid/chat/minimap/overlay-insets clear the cutout (the math at ChromeOverlayView.kt:120-144 and MainActivity.kt:1144-1191), and confirm SurfaceHolder.Callback recreate resumes the live frame. No code change presumed; verification owed before parity is real.
- **Blocking constraint:** Emulator-GREEN != device-confirmed — the inset->canvas conversions are only exercised with zero insets today.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** unverified  **Desktop target:** Desktop HUD never clips to a cutout — the safe-inset math is mobile-specific

#### Notification + link delivery is emulator-GREEN only — POST_NOTIFICATIONS grant, channel sound, PendingIntent reopen device-UNVERIFIED
- **Bucket:** improve-degraded
- **Problem:** The delivery path has an end-to-end self-test (MainActivity.kt:316-320) and logs `notification delivered`/`link opened` (:1022,1036), but all of it runs on the emulator. The runtime POST_NOTIFICATIONS prompt (:956), the suppress-if-not-granted branch (:995-1001), the single-top PendingIntent reopen while the JIT VM is already running (:1003-1018), and ACTION_VIEW dispatch (:1035) are UNVERIFIED on real hardware.
- **Impact:** On a real device the permission could be denied (notifications silently dropped), the PendingIntent reopen could mis-handle the live second-VM process, or no browser exists for ACTION_VIEW — none surfaced by the emulator. Until a device run confirms grant->deliver->tap-reopen and link-open, delivery isn't device-confirmed.
- **Effort:** S
- **Mechanism (path:line):** Verification task (no product code): on the physical device, grant/deny POST_NOTIFICATIONS and confirm suppress vs delivery (MainActivity.kt:999 vs 1022), tap a delivered notification to confirm single-top reopen (:1003-1018), tap a wiki link to confirm browser launch (:1035). Capture logcat `ap2a: notification delivered` / `ap2a: link opened`.
- **Blocking constraint:** Emulator-GREEN != device-confirmed — the runtime permission flow and PendingIntent reopen can only be validated on real hardware.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** unverified  **Desktop target:** Desktop tray notification + Desktop#browse — the Android delivery analogue, MainActivity.kt:1003

#### Overlay/infobox edit screens are emulator-only; gestures + persistence unverified on device
- **Bucket:** improve-degraded
- **Problem:** The overlay snap/reset and infobox menu invoke marshal ART->HotSpot on the single rl-bridge thread (OverlayEditScreen.kt:96-97, InfoBoxScreen.kt:88 via JvmLauncher.onBridge) and rely on flushConfig persisting on background (MainActivity.kt:1393-1400). Per the audit these are emulator-GREEN only; surface-recreate, IME-free panels, and the bridge-thread invoke under a busy client tick carry ANR risk on real hardware.
- **Impact:** An overlay reposition or infobox Detach could be slow/dropped or fail to persist on a real device after backgrounding; needs a device soak.
- **Effort:** S
- **Mechanism (path:line):** Device verification only (no code): confirm snap/reset move the overlay on the next blit, the move survives kill/relaunch (flushConfig on onStop fired), and the bridge invoke doesn't block the UI thread.
- **Blocking constraint:** Emulator-GREEN != device-confirmed + dual-VM marshalling on one rl-bridge thread inherits ANR risk.
- **Tag:** [native-android] — iOS: native-only (n/a)
- **Current status:** unverified  **Desktop target:** Overlay/infobox config persists via ConfigManager — OverlayBridge.java:143, InfoBoxManager.java:122-146

#### No fallback way to open the world map when the relocated minimap orb cluster is off-screen / chrome hides it
- **Bucket:** restore-missing
- **Problem:** The only opener is the engine world-map orb, which lives in the MAP_CONTAINER cluster. While a map is open the shared tick shoves that cluster off-screen (HudVeneerBridge.java:598-609) and the native chrome is hidden (MainActivity.kt:1208-1213); after close the cluster is re-forced home, but there is no dedicated native `open world map` affordance independent of the relocated orb's pixel position. Desktop also offers a keybind quick-open; on touch the keybind is gone.
- **Impact:** If the minimap relocate parks the orb cluster partly off the safe area (a real notch-device risk, device-unverified), or a future chrome state hides the orb, the map becomes unopenable with no fallback.
- **Effort:** S
- **Mechanism (path:line):** Add a native open-map affordance (a button in the 2-col tab grid / floating control) calling the engine orb op directly, decoupling open from the relocated orb's pixel position — analogous to the existing native openTab. The engine op already exists (the orb's onOp listener, WorldMapProbe.java:1069-1083). Confirm on device that the post-close orb cluster lands fully on-canvas. (Overlaps with the legacy-map-open finding's deterministic native open path.)
- **Blocking constraint:** The keybind opener doesn't translate to touch and the orb's on-canvas reachability after the relocate trio is device-unverified (notch/safe-area).
- **Tag:** [native-android] — iOS: native-only (n/a — native affordance per platform)
- **Current status:** partial  **Desktop target:** World-map opener (orb + keybind) — HudVeneerBridge.java:598-609, WorldMapProbe.java:1069-1083

## 4. Right-bar redesign

### 4.1 Current presentation inventory

The "right bar" is not one rail — it is **three distinct surfaces** wired into the root `FrameLayout` (z-ordered in `MainActivity.kt:346-361`):

| # | Surface | What it is | Anchor | Key anchors |
|---|---------|-----------|--------|-------------|
| A | Native 2×7 tab **GRID** | The 14 engine HUD stones, re-skinned native; engine stones hidden via `HudVeneerBridge.setStonesHidden`, tap replays the stone op-script via `openTab` | bottom-right | `ChromeOverlayView.kt:30-44` (build `:66-76`, draw `:146-166`, tap `:168-195`); slot→tab map `intArrayOf(3,1,4,2,5,13,6,7,0,12,8,9,10,11)` `:62` |
| B | Relocated engine **SIDE PANEL** | The engine's own Swing side panel, parked flush-left of the grid, top-aligned | flush-left of grid | `relocateSidePanel` `MainActivity.kt:1116-1131` (released at `:1134-1138`); geometry from `gridLeftPx/gridTopPx` `ChromeOverlayView.kt:115-116` |
| C | Gear **⚙ gateway** | One 0.7-alpha glyph in a 9-glyph bottom-left dev cluster (`⚙ ⌨ ⇧ ▦ ⎆ ⏻ ◆ HD 📷`); SOLE entry to the entire RuneLite plugin-panel ecosystem | bottom-left dev cluster | `devBar` `MainActivity.kt:340-345`; `devButton` factory `:257-263`; open path `:264-278` (gear→`pluginPanel.visibility=VISIBLE`, gear set GONE `:277`); `pluginPanel` added `:354` |

Behind surface (C), every screen is a **full-screen opaque Compose Surface** that covers the live game:

- **Plugins screen** `PluginPanel.kt:138` — `Surface(0xF2121212, fillMaxSize)`; header `Plugins (n/total)` + `Hub | Panels | Close` `:138-149`; filter `:150-154`; `LazyColumn` of pin/name/badge/Switch rows `:159-262`; renderer rows special-cased `:160-181`.
- **Panels sub-screen** (`PanelList`) `PanelView.kt:63-124` — two taps deep (gear→Plugins→Panels); curated `NativeRow` set `:98-108`, then generic rendered-image rows `:109-120`.
- **Rendered-image viewer** `PanelView.kt:142-200` — opens the real Swing panel off-screen under CACIO, polls a supersampled ARGB bitmap every 400 ms (`:168`), fit-to-width scroll, blind tap→buffer mapping `:184-192`. Shared render: `MobileUiBridge.openPanel/renderActiveOnEdt` `:480-585`.
- **Config editor** `ConfigScreen.kt:80-102` — full-screen, `‹ Back`-only.

Grid sizing: `cell = min(dp(46), (height-insTop-insBottom)*0.56f/7)`, margin `dp(6)` `ChromeOverlayView.kt:134-144`. Grid only engages at `loggedIn && topLevel==164` `MainActivity.kt:1057-1059` / `setGridEnabled :106-108`; A/B raw-stones toggle `railsShown :110` (`MainActivity.kt:281-285`). Rendered panels truncate over `PANEL_MAX_H=2500` logical px (`MobileUiBridge.java:459`, hard cap `:562`) and step supersample 4×→1× under `MAX_BUFFER_PX=3,000,000` (`:463-467, :566-571`). `listPanels` = every NavigationButton with non-null PluginPanel minus `Configuration` (`MobileUiBridge.java:398-432`), desktop toolbar order (`navButtonsSorted :388-394`).

### 4.2 Diagnosis (anchored)

1. **Discoverability** — the entire plugin-panel ecosystem hides behind one 0.7-alpha gear in a debug cluster (`MainActivity.kt:264-278, :340-345`). Nothing signals it is the plugin sidebar; desktop's parity target is an always-visible labelled rail (`ClientToolbarPanel.java:63-101`, `NavigationButton.java:39-73`).
2. **Depth** — panels are 2–3 taps deep and modal-stacked: gear→Plugins→`Panels`→`PanelList`→row (`PluginPanel.kt:99-117`, `PanelView.kt:63-124`). Desktop is one click on the rail.
3. **Full-screen takeover** — every step is opaque `fillMaxSize` (`PanelView.kt:171`, `PluginPanel.kt:138`); you cannot watch the game while reading a panel. Desktop's ~225 px slide-out (`PluginPanel.java:38`) leaves the game visible.
4. **Grid touch targets** — cells capped at 46 dp then reduced by `*0.56/7` (`ChromeOverlayView.kt:134-137`) → below Android's 48 dp minimum on tall phones; 14 unlabelled glyphs in a dense 2×7 block at the extreme bottom-right (`:146-166`).
5. **One-handed reach** — the side-panel relocate parks content flush-left of the bottom-right grid, **top-aligned** (`MainActivity.kt:1116-1131`, top-align verified `:1128`) — the hardest zone for a right thumb. The gateway is bottom-LEFT (`:355-360`).
6. **Rendered-image degradation** — generic panels are a 400 ms polled screenshot (`PanelView.kt:153-170`) that truncates over 2500 px (`MobileUiBridge.java:459,562`), blurs at 1× when tall (`:566-571`), and offers only blind tap-to-buffer (`:184-192`) — no hover, no tooltips, no IME.
7. **Close/dismiss ambiguity** — mixed exits (`Close` `PluginPanel.kt:148`, `‹ Back`/`‹ Panels` `PanelView.kt:174`, `ConfigScreen.kt:94`, engine map rect `MainActivity.kt:1205`); **no Android system-back is wired** for these Compose screens (confirmed: only `onStop` overridden `MainActivity.kt:1393`). Deep users back out step by step.
8. **Trap risk** — the gear is set GONE on open (`MainActivity.kt:277`) and is the only re-show trigger; if a panel fails to render its `Close`, the user is stranded.
9. **Toolbar-only buttons have no home** — onClick/popup-only NavigationButtons (Account `AccountPlugin.java:80-87`, Discord `DiscordPlugin.java:123-125`, Screenshot `ScreenshotPlugin.java:217-220`) are scattered across dev-cluster glyphs (`MainActivity.kt:287-337`) or absent (Discord/Info); no unified rail concept.

### 4.3 Parity confirmation — every desktop NavigationButton / PluginPanel reachable

| Desktop item | Desktop anchor | Reachable today | Reachable after redesign |
|---|---|---|---|
| Configuration (top-level search) | `ConfigPlugin.java:93-96` | partial — excluded from `listPanels` (`MobileUiBridge.java:403`); Plugins list is the substitute | yes — Plugins-list tile (substitute preserved) |
| Grand Exchange | `GrandExchangePlugin.java:269-272` | yes (native reflow) `PanelReflowScreens.kt:148-188`, `PanelView.kt:102` | yes — native reflow as panel-sheet content |
| XP Tracker | `XpTrackerPlugin.java:147-150` | yes (native reflow) `XpTrackerScreen.kt`, `PanelView.kt:100` | yes |
| Loot Tracker | `LootTrackerPlugin.java:595-598` | yes (native reflow) `PanelReflowScreens.kt:58-97`, `PanelView.kt:101` | yes |
| World Switcher | `WorldHopperPlugin.java:208-211` | yes (native) `QuestWorldScreens.kt`, `PanelView.kt:103` | yes |
| Quest Helper (Hub) | `MobileUiBridge.java:416` | yes (native) `QuestWorldScreens.kt`, `PanelView.kt:104` | yes |
| Notes | `NotesPlugin.java:70-73` | partial — rendered image, blind tap, >2500 px truncates | improved — IME via `panelText` (see §4.4 cost note); **truncation still open** |
| Time Tracking | `TimeTrackingPlugin.java:148-150` | partial — rendered image, tall lists truncate/blur | improved render room (EXPAND detent); **truncation still open** |
| Hiscore | `HiscorePlugin.java:109-112` | partial — rendered, no IME for name field | improved — IME via `panelText` |
| Skill Calculator | `SkillCalculatorPlugin.java:65-68` | partial — rendered, blind number entry | improved — IME via `panelText` |
| Party | `PartyPlugin.java:191-194` | partial — rendered image | unchanged (rendered, watchable in sheet) |
| Info | `InfoPlugin.java:56-59` | partial — rendered image | unchanged (rendered, watchable in sheet) |
| Kourend Library | `KourendLibraryPlugin.java:144-147` | partial — rendered, long lists truncate | unchanged; **truncation still open** |
| Screen Markers | `ScreenMarkerPlugin.java:135-138` | partial — rendered, create/drag does not translate | unchanged (interaction limit persists) |
| Developer Tools | `DevToolsPlugin.java:294-297` | no — incompatible badge `PluginPanel.kt:259` | no — stays badged (does not start headless) |
| Take screenshot | `ScreenshotPlugin.java:217-220` | yes (rehome) `MainActivity.kt:336-337` | yes — **fixed native tile** (not via `listPanels`) |
| Account sign in/out | `AccountPlugin.java:80-87` | yes (rehome) `MainActivity.kt:287-293` | yes — **fixed native tile** |
| Join Discord | `DiscordPlugin.java:123-125` | **no** — no Android home | yes — **fixed native tile** wired to `publishLink` |
| Overlay layout/edit | `OverlayRenderer.java` | yes (native) `OverlayEditScreen.kt`, `PanelView.kt:105` | yes |
| Infoboxes | `InfoBoxManager.java` | yes (native) `InfoBoxScreen.kt`, `PanelView.kt:106` | yes |
| Plugin Hub | `ConfigPlugin.java` (hub entry) | yes (native) `PluginHubScreen.kt`, `PluginPanel.kt:146` | yes — launcher header `Hub` button |
| Persistent right-edge RAIL itself | `ClientToolbarPanel.java:63-101` + `NavigationButton.java:39-73` + `PluginPanel.java:38` | **no** — collapsed into one hidden gear | yes — **launcher handle + grid restores the rail concept** |

> **Caveat carried into design:** Discord/Screenshot/Account are onClick-only navs. `panelNavs()` filters `getPanel()!=null` (`MobileUiBridge.java:403`), so they are **never** in `listPanels` data. Implement them as **fixed native tiles** wired to existing `JvmLauncher` calls (`saveScreenshot`, `publishLink`, login) — do not expect them through the panel list.

### 4.4 The redesign — bottom-sheet launcher (winner), with grafted picks

**Concept:** retire surface (C) — the hidden gear and its full-screen modal stack — and replace it with a single bottom-edge **handle** that opens a full-width **launcher** bottom sheet (a labelled grid of every plugin panel + curated native screen + config + renderers). Tapping a tile slides up a **panel sheet** with two detents (PEEK / EXPAND) so the live game stays visible. Surface (A) the engine 2×7 tab GRID and surface (B) the side-panel relocate are **separate concerns** and stay; (A) gets the grafted touch-target floor and an opt-in dock-flip.

#### Layout sketches

**Launcher CLOSED** — only new persistent affordance is the bottom-center handle:
```
+--------------------------------------------------+
| [chat pill TL]                       [minimap TR]|   relocate trio UNCHANGED
|                                                  |
|                 LIVE GAME FRAME                  |
|                                       +----+     |
|              engine SIDE PANEL ----->  |3  1|    |   engine 2x7 GRID (A)
|              flush-left of grid (B)    |4  2|    |   UNTOUCHED, bottom-right
|                                        |5 13|    |   (>=48dp cells, grafted)
| [⌨⇧▦◆HD] devBar BL (gear REMOVED)    +----+     |
|             _______________(=)______________      |   NEW: full-width 28dp HANDLE,
+--------------------------------------------------+      bottom-center ABOVE devBar
                                                          drag-up or tap = open launcher
```

**Launcher OPEN** — sheet at EXPAND; game dimmed but visible at top:
```
+--------------------------------------------------+
|              live game (dim scrim)               |   game NOT fully covered
|==================(=)=============================|   grab handle
| Plugins (n/total)        [search field......]    |
| -- Renderers --  [CPU*] [GPU] [117HD]            |   renderer pref radios (:160-181)
| -- Pinned ★ --   [XP Trk][Loot][Grand Ex]        |   2-3 col LABELLED tiles:
| -- Panels --     [World][Quest][Notes]           |   icon28 + text + ★ + ✕incompat
|                  [Hiscre][Time][Party]           |
|                  [Hub][Config][Overlay]          |
|                  [Infobox][Discord*][Screenshot*]|   * fixed native tiles
+--------------------------------------------------+      (publishLink / saveScreenshot)
```

**Panel OPEN at PEEK** — game watchable above the peek line (the desktop-slideout win):
```
+--------------------------------------------------+
|        live game (watchable) + minimap + chat    |
|==================(=)=============================|   handle: up=expand, down=close
| ‹ XP Tracker        ★    ⊞grid    ✕close         |   unified header
|--------------------------------------------------|
| Skill  Start  Curr  /hr   ttl                    |   NATIVE reflow OR rendered image
+--------------------------------------------------+      ~45% height
```

**SWITCHING (panel→panel)** — the header `⊞grid` re-opens the launcher without fully closing; one tap jumps panels (vs today's back-out-then-redrill). `‹` also returns to the launcher grid.

**LANDSCAPE (grafted from edge-rail):** the panel sheet anchors to the **RIGHT edge** at `~min(86%w, 420dp)`, not the bottom — a bottom sheet on a short-wide window steals too much vertical room; right-anchor preserves the ~225 px slide-out shape and keeps more game visible.

#### Gesture model (open / close / switch / dismiss)

- **OPEN:** drag-up OR tap the full-width bottom **handle** (28 dp, bottom-center; z-above the engine grid and devBar, below `menuOverlay` `MainActivity.kt:353`). This is the only new persistent affordance and the fix for diagnosis #1.
- **SWITCH (launcher→panel):** tap a labelled tile → launcher collapses, panel sheet animates to PEEK. Tile order mirrors `navButtonsSorted` (`MobileUiBridge.java:388-394`) so muscle memory matches desktop.
- **SWITCH (panel→panel):** header `⊞grid` re-opens the launcher without closing; `‹` returns to grid.
- **PEEK ↔ EXPAND:** drag the grab handle. Two detents (`BottomSheetBehavior` Views or `ModalBottomSheet` with two `SheetValue`s). PEEK ~45% keeps the game watchable (parity with `PluginPanel.java:38`); EXPAND ~88% for tall content.
- **DISMISS — one unified model** replacing today's mixed exits: (a) drag the panel sheet below the peek threshold; (b) header `✕close`; (c) tap the dim scrim; (d) **Android system BACK** — wire `onBackPressedDispatcher` (absent today, `MainActivity.kt:1393`) to collapse panel→launcher→game one level per press.
- **GRAFT — keep the handle visible (dimmed), never `View.GONE`, behind the sheet** (vs the gear's GONE-on-open `MainActivity.kt:277`) so a deep screen that fails to render its own back affordance always leaves a guaranteed "return to game" (fixes diagnosis #8). Pair with system-back.
- **GRAFT — A/B raw-stones escape hatch** (`railsShown` `ChromeOverlayView.kt:110`) stays intact as a diagnostic fallback.
- **flushConfig:** on sheet dismiss, also fire `flushConfig()` — today it fires only on `onStop` (`MainActivity.kt:1397`) and logout (`:584`), so a config edit closed without backgrounding currently relies on `onStop`. The dismiss-flush is an additive correctness win.

#### HUD / chat / minimap coexistence + edge-to-edge insets

- **Bottom anchor structurally avoids the HUD:** chat pill top-left (`MainActivity.kt:1144`) and minimap top-right (`applyMinimap`, `:1241-1255`) need **zero relocate-trio changes**. PEEK at ~45% guarantees the top quarter stays clear — required because the engine hard-pins minimap orbs every frame and there is no API to read the composited minimap bitmap, so the sheet must never cover them.
- **Engine grid (A):** the bottom-center full-width handle does **not** fight the bottom-RIGHT 2-col grid for a corner the way a right-edge rail would. The only overlap is the PEEK sheet rising over the grid — an edge case scoped to `topLevel==164` tab-open; the grid re-renders untouched from `chromeSnapshotPub` (`MainActivity.kt:1050-1059`) on dismiss.
- **Insets:** the sheet consumes bottom + horizontal safe insets via `WindowInsets`, reusing the exact pattern in `ChromeOverlayView.safeInsets()` (`:120-129`) and `MainActivity.applyOverlayInsets()` (`:1174-1191`): the handle sits **above** the nav-bar/gesture inset; tiles/content pad by left+right insets so a landscape cutout never clips a tile; the header `✕` and `LazyColumn` first/last rows clear the cutout and gesture pill.
- **World map:** when `mapOpen` the chrome grid is already `GONE` (`MainActivity.kt:1209-1212`); extend that same gate (one line) to hide the launcher handle and force-close any open sheet, since the map owns its own fullscreen surface. No launcher tile opens the map — it stays on the engine minimap orb (`mapSnapshotPub`, `:1196-1220`), unchanged.
- **GRAFT — engine-grid dock-flip (A only):** mirror `gx0` in `ChromeOverlayView.computeLayout` (`:140`) and `sideX` in `applySidePanel` (`MainActivity.kt:1127`) behind a `SharedPreferences` `dockSide` flag. **Bind it to an explicit settings toggle, NOT a long-press on a cell** — long-press collides with the engine's own press-and-peek menu gesture. This gives left-handed/large-phone reach for the grid that the launcher (plugin surface only) does not address.

#### Dual-VM panel-data cost note

Every tile open and panel poll marshals ART↔HotSpot on the **single** rl-bridge thread (`JvmLauncher.kt:270`). Mitigations:

- **Boot prefetch:** fetch the launcher list (`listPlugins`/`listPanels`) once at app boot to mask first-open marshalling latency; keep the `LaunchedEffect` off the UI thread as `PluginPanel.kt:123-128` already does.
- **GRAFT — coalesce/cancel on panel→panel switch:** before issuing the new `openPanel`, cancel the in-flight 400 ms `PanelView` poll (`PanelView.kt:168`) so a switch never queues behind a stale poll on the one rl-bridge thread.
- Per-tile open still pays one round-trip (`listPanels`/`openPanel`/`panelPixels`); the sheet adds no caching by itself.

**Free-after-merge (verified) — the single largest de-degradation:** cherry-pick the **additive** parity-b2 (`60c4e522e`) `MobileUiBridge` methods `panelText`/`panelKey`/`panelLongPress`/`panelWheel`/`panelMove`/`isPanelTextActive`/`getPanelText` — confirmed **absent on master** and absent from Android `JvmLauncher` (only `nativePanelClick` `:154`). This unblocks IME text-entry + right-click into rendered panels (Notes/Hiscore/Skill-Calc/GE-search) for the first time. It grows `baseline.txt` additively and benefits iOS too.

**Carried-open caveat (NOT fixed by any launcher shape):** the `PANEL_MAX_H=2500` hard-truncation + supersample step-down (`MobileUiBridge.java:459,562-571`) — tall Notes/Time-Tracking/Kourend panels still clip/blur. The EXPAND detent only adds header room, not buffer capacity. Track as a **separate shared-core finding**.

#### Migration path from the current ChromeOverlayView grid

The grid is **not** migrated — it is a separate surface and is **left intact** (only the grafted Phase-0 touch-floor + opt-in dock-flip touch it). The launcher replaces only surface (C).

- **GRAFT Phase 0 (do FIRST, independent of the launcher):** bump the grid touch floor — `cap=dp(46)→dp(48)` and `cell=min(48dp, availH/7)` at `ChromeOverlayView.kt:134-137`; all geometry derives from `cell` so no other call site changes. Highest-confidence, lowest-risk hit-target fix in the audit; land it immediately. On very tall phones where `availH/7 < 48dp`, fall back to a compact single-column/overflow mode rather than growing past the `*0.56` clamp into the minimap.
- **Phase 1 (additive bridge merge):** cherry-pick the parity-b2 `MobileUiBridge` delta (above) + add JNI wrappers in `jvm_bootstrap.cpp` and `external fun nativePanelText/nativePanelKey/nativePanelLongPress` + fun wrappers in `JvmLauncher.kt` (today stops at `nativePanelClick :154`). Additive-only; `baseline.txt` grows.
- **Phase 2 (native scaffold):** add a `LauncherHandle` View at the root `FrameLayout` (`MainActivity.kt:346-361`) bottom-center; on tap present a `ModalBottomSheet`. Launcher content = the **existing** `PluginPanel.kt` list reflowed into a labelled grid — reuse `parsePlugins` (`PluginPanel.kt:265`), `parsePanels` (`PanelView.kt:202`), renderer special-casing (`PluginPanel.kt:160-181`), and the `NativeRow` set (`PanelView.kt:98-108`). Add the **three fixed native tiles** (Discord→`publishLink`, Screenshot→`saveScreenshot`, Account→login) per the §4.3 caveat. No new bridge calls beyond Phase 1.
- **Phase 3 (panel-as-sheet):** host the existing screens (`XpTrackerScreen.kt`, `PanelReflowScreens.kt`, `QuestWorldScreens.kt`, `ConfigScreen.kt`, `PanelView.kt`) **inside** the panel sheet's content slot with PEEK/EXPAND detents — change `PanelView.kt:171` and `PluginPanel.kt:138` from `Surface(fillMaxSize)` to the sheet slot. The 400 ms poll (`PanelView.kt:168`) and `panelClick` mapping (`:184-192`) are unchanged; wire the new `panelText`/`panelKey` to a hidden Compose `BasicTextField` that mirrors into the focused Swing field.
- **Phase 4 (cutover):** retire the ⚙ open path (`MainActivity.kt:264-278`) into the handle (keep handle dimmed-not-GONE per graft); wire `onBackPressedDispatcher` for the sheet chain; add `flushConfig` on dismiss; gate the handle on `!mapOpen` (`:83/:1209`); apply the landscape right-anchor and the coalesce/cancel. **Delete the `PanelList` intermediate screen** (`PanelView.kt:63-124`) — its rows are now launcher tiles. Keep the `▦/▤` A/B toggle.

Each phase is independently shippable and emulator-verifiable. No plugin is forked.

#### Files / bridges each change touches

- `android/.../helloshell/MainActivity.kt` — add `LauncherHandle` to root FrameLayout (`:346-361`); retire ⚙ open path (`:264-278`); delete screenshot/login/logout dev glyphs into native tiles (`:287-337`); wire `onBackPressedDispatcher` (absent, `:1393`); gate handle on `mapOpen` (`:83/:1209-1212`); `flushConfig` on dismiss; engine dock-flip mirror of `sideX` (`:1127`).
- `android/.../helloshell/ChromeOverlayView.kt` — **grafts only:** Phase-0 cell floor `dp(48)` (`:134-137`); dock-flip mirror of `gx0` in `computeLayout` (`:140`). Grid logic otherwise untouched.
- `android/.../helloshell/PluginPanel.kt` — reflow list into the labelled-grid launcher sheet; reuse `parsePlugins` (`:265`) + renderer special-casing (`:160-181`); replace `fillMaxSize` Surface (`:138`) with sheet content slot.
- `android/.../helloshell/PanelView.kt` — delete `PanelList` (`:63-124`); host `PanelView` (`:142`) in the panel sheet with PEEK/EXPAND instead of `Surface(fillMaxSize)` (`:171`); add hidden text field mirroring `panelText` for IME; coalesce/cancel the poll on switch.
- `android/.../helloshell/ConfigScreen.kt` — host inside the sheet back-stack; breadcrumb header instead of `fillMaxSize` (`:80-102`).
- `android/.../helloshell/PanelReflowScreens.kt`, `XpTrackerScreen.kt`, `QuestWorldScreens.kt` — host native reflows inside the panel-sheet slot.
- `android/.../helloshell/JvmLauncher.kt` — add `external fun` + wrappers `nativePanelText/nativePanelKey/nativePanelLongPress` (today ends at `nativePanelClick :154`); keep all async via `onBridge` on the single rl-bridge thread (`:270`); add boot prefetch.
- `android/.../cpp/jvm_bootstrap.cpp` — JNI wrappers for `panelText`/`panelKey`/`panelLongPress`.
- `runelite-client/.../headless/MobileUiBridge.java` — **additive merge only** from parity-b2 (`60c4e522e`): `panelText`/`panelKey`/`panelLongPress`/`panelWheel`/`panelMove`/`isPanelTextActive`/`getPanelText`. Master has only `panelClick` (`:645`) + `openPanel` (`:480`) + `closePanel` (`:520`) + `listPanels` (`:416`). Benefits iOS.
- `tools/shared-contract/baseline.txt` — grows by the merged additive methods (additive-only gate; `extract-contract.py` + `check-contract.sh`).
- **Explicitly UNCHANGED:** the engine-stone tab GRID logic in `ChromeOverlayView.kt` beyond the two grafts; the relocate-trio (chat/minimap); the world-map floating window.

**Device-unverified (emulator-green ≠ device):** `BottomSheet` drag detents, IME-into-Swing mirroring (`panelText`), detent-snap haptics (`vibrateTick` `MainActivity.kt:251`), system-back-with-IME-up, surface-recreate/background while a sheet is open, and the landscape right-anchor flip all need real-hardware confirmation. **No new features added** — every target already exists in desktop RuneLite or an installed plugin.

---

> Code-grounded against branch `android`. Phase letters from `android/PARITY-PHASING.md`
> (Phases 0–5, all emulator-GREEN 2026-06-12) and `android/MOBILE-UX-AUDIT.md` (Phases
> AP-0..AP-4 + Shared-core track). Every factual claim is anchored `path:line`. Prior-doc
> Android-column claims were treated as hypotheses and re-verified against current code;
> corrections noted inline.

---

## 5. Infeasible / accept-as-is

Desktop perks that cannot be made touch-friendly under our hard constraints. Each row names
the **blocking constraint**. These draw from the matrix Infeasible rows
(`MOBILE-UX-AUDIT.md:452-465`) plus rehome items with no viable substitute. None of these
should consume implementation budget except as "hide / no-op by default" policy.

| # | Desktop capability | Blocking constraint | Disposition |
|---|---|---|---|
| **I1** | **Overlay / profile resize via hover-cursor edge-drag** (`OverlayRenderer.java:419-477`) | No hover, no cursor on touch — resize is doubly hover-cursor-bound. (Reposition IS feasible; resize is not.) | Accept-as-is. Reposition handled in §6 AP-4; resize dropped. |
| **I2** | **Configurable hotkeys / `HotkeyButton` binding UI** (`Keybind` = AWT keycode + modifier mask) | The bridge synthesizes no arbitrary VK keys / modifier masks; there is no path to *record* or *fire* an arbitrary keybind, and `HotkeyListener` actions aren't named/registered queryably so they can't be auto-discovered. | Generic registry struck. Per-action native buttons are the only substitute (see §6 "hotkey trigger surface", which is the hand-picked subset, not a generic registry). |
| **I3** | **Custom Cursor / Mouse Tooltips / Interface-Styles cursor behaviours** | No OS cursor on touch; Mouse Tooltips redundant with the native peek reticle (`MenuOverlayView.kt`). | Hide / no-op by default (AP-4 mobile default-profile). |
| **I4** | **Middle-click / extra-mouse-button camera & menu remaps** (Camera plugin `setCameraMouseButtonMask(BUTTON2…)`) | Touch lacks BUTTON2 / buttons ≥4. The zoom-limit *half* of Camera is useful and kept; the button-remap half is permanently dead. | Button-remap half: accept-dead. Zoom-limit half → §6 AP-4 config. |
| **I5** | **WASD / F-key camera & "Press Enter to Chat" (Key Remapping)** | Intercepts hardware keys; nothing to intercept without a BT keyboard (out of scope, personal build). | Keep off by default. |
| **I6** | **Anti-Drag default mode** (`onShiftOnly=true` needs Shift held) | Even if the shift feed lands (§6 shared-S0), it tames *mouse-drag* of inventory; touch inventory-drag semantics differ. | Mark broken / accept. |
| **I7** | **Stretched Mode** (`StretchedModePlugin.java:54-85` rescales the surface + installs mouse-coord-translating listeners) | Conflicts with the port's own scaling + the touch coordinate pipeline. | Must NOT ship. |
| **I8** | **Notifier screen-flash + request-focus + taskbar-flash** (`Notifier.java:133-202`) | These are desktop `ClientUI` *window* operations — there is no host AWT window on Android to flash or focus. (Confirmed inert: §6 finding "focus + request-focus are no-ops".) | Sound + content survive (→ §6); flash/focus accept-dead. |
| **I9** | **System tray / taskbar / shell-out notifications** (`TrayIcon`, `notify-send`) | No sandbox analogue. The only port is a *replacement* `NotificationManager` layer (a different mechanism, §6 AP-2). Canvas `processFlash` is the one channel that survives unchanged. | Replace, don't port. |
| **I10** | **Config-profiles drag-reorder + `JFileChooser` import/export** | Drag-reorder + `JFileChooser` have no direct analogue. Reorder → reorderable Compose list; import/export → Storage Access Framework (adaptable-but-L); the desktop interaction model itself does not port. | Reduced native profiles UX at best (§6 AP-4 restore-missing). |
| **I11** | **Screen Markers free-form drag-to-draw rectangle** | Free-form drag-to-draw layered on top of camera/peek gestures has no clean touch home; low value on a relocated-widget HUD. | Omit, or full native reimplementation only if ever justified (§6 lists the panel reaches its fallback but the core action is unreachable). |
| **I12** | **Developer Tools (spawns separate Swing frames), Discord RPC/IPC, Account RL/Google OAuth** | Separate Swing frames have no host window; Discord IPC is a desktop-process integration inert on-device; Account OAuth is desktop-oriented + low value. | Exclude Dev Tools; accept Discord/Account as inert. |
| **I13** | **GPU plugin live render / 117HD on screen** | CPU pixel-blit is the shipping renderer; treat GPU as **missing-as-a-renderer**, not a v1 gate. Android's GLES path is real (Mali GLES 3.2, AG0/AG1b GREEN) but there is no live GPU frame on a *device-confirmed* basis yet. | Post-v1 fast-follow only (§6 AP-3). Not a parity blocker. |
| **I14** | **Overlay hover tooltips / world-map POI tooltips / overlay-widget hover dwell cues** | Hover is a single one-frame `MOUSE_MOVED` on touch-down — no dwell. Hover-only plugin cues stay inert (refute any "works generically" claim). | Rehome items with **no clean substitute** (a dwell-on-press hint is possible but low-value); accept thin. Tracked as rehome in §6 but flagged low-priority/accept-thin. |

> **Adversarial note:** several "rehome" findings in the consolidated set (hover tooltip
> subsystem, world-map POI tooltips, single-frame hover) have **no viable substitute** under
> the no-hover constraint and effectively belong here as accept-thin. They are kept in §6 at
> Tier-3 with the honest caveat that the "fix" is a degraded approximation, not parity.

---

## 6. Phased plan

Keyed to the existing Android phase letters. **Shared-core (additive Java) work lands FIRST**
— it benefits both ports (logic in a shared bridge is consumed by iOS too). Where iOS already
authored the shared half on a `parity-bN` branch, the Android job is **cherry-pick the shared
additive method, then build the native veneer**; where the shared method is already on
`origin/android`, the work is **native-only**.

> **Constraint reminder applied throughout:** plugins stay unmodified (fixes = config policy /
> native veneer / additive shared-bridge method); `tools/shared-contract/baseline.txt` may only
> GROW; new bridge calls inherit the dual-VM ART↔HotSpot marshalling tax on the single
> `rl-bridge` thread (`JvmLauncher.kt:207-215`) and the ANR risk that brings; `flushConfig()`
> must fire on close/background or config doesn't persist.

### Shared-core track (land first)

Additive-only `runelite-client/.../headless/*.java`. After **each**, re-run desktop OpenJDK 21
headless smoke + `tools/shared-contract/check-contract.sh --update` + re-census both ports.
Several of these are **already written on an iOS `parity-bN` branch** and are pure additive —
**cherry-pick rather than re-author**.

- **SC-1 — Notifications: sound type + volume + per-notifier defaults.**
  Cherry-pick `parity-b1-config` (`5f89b1583f`): the 4-arg
  `MobileUiBridge.publishNotification(title,body,String soundType,int volume)` overload +
  `notificationDefaults()`. NOT on `origin/android` — current android wires only the 2-arg
  `publishNotification(title,body)` to the system shade (verified: zero panel-input/4-arg hits;
  routing at MainActivity.kt:1035 area, JvmLauncher). Maps to desktop `Notifier.java:133-202`
  SoundType/volume. **Blocking constraint:** screen-flash + request-focus are desktop-window-only
  (I8) — only sound + content are reachable. Consolidated findings: *"Notification sound … is
  silently dropped"* (M, shared), *"Notification config items render as blank rows … global
  RuneLite defaults group unreachable"* (M, shared).

- **SC-2 — Config reach: CORE client settings + Chat-Color groups + reset.**
  Cherry-pick `parity-b1-config` (`5f89b1583f`): `emitFakePlugin`/`fakeConfigClass`
  (surfaces the CORE `RuneLite` + `Chat Color`/textrecolor groups as pseudo-plugins),
  `isMobileHiddenItem`, `resetPluginConfig(group)`. None exist on `origin/android`. The
  per-plugin config *data path* already exists on android (`getPluginConfig`/`setConfig`).
  Consolidated: *"global RuneLite defaults group unreachable"*, *"No per-plugin
  Reset-to-defaults button"* (M, shared, free-after-merge).

- **SC-3 — Config schema: set/EnumSet value encoding + FontType preset enumeration.**
  The android `SetConfigControl` exists (ConfigScreen.kt:146,243) and `"set"` is no longer
  read-only — but FontType still falls through to the opaque `"unknown"` read-only field
  (ConfigScreen.kt:148-150). The shared half (typed schema for FontType as a 3-way preset)
  rides `parity-b1-config`. Consolidated: *"FontType config items render as read-only opaque
  serialized strings"* (S, shared, free-after-merge), *"set/EnumSet read-only"* (rolled into
  SC-2's editor reach).

- **SC-4 — Right-bar / panel-content input.**
  Cherry-pick `parity-b2-rightbar` (`60c4e522e8`): `isPanelTextActive()`, `getPanelText()`,
  `panelText(String)`, `panelKey(int vk)`, `panelLongPress(x,y)`, `panelButton(x,y,button)`,
  `panelWheel(x,y,rotation)`, `panelMove(x,y)`, `textComponentFor(Component)`. **Verified
  absent on android** — the host calls ONLY `panelClick` (PanelView.kt:190); grep for
  panelText/panelKey/panelLongPress/panelButton/panelWheel/panelMove/isPanelTextActive returns
  ZERO. So every Swing `PluginPanel` with a `JTextField` (HiScore/Notes/Skill-Calc search/name
  entry), a scrollable `JList`/`JScrollPane`, or right-click/hover is half-wired: tap works,
  text/scroll/secondary-action do not. Maps to desktop "full mouse+keyboard inside every
  `PluginPanel`". Consolidated: *"Rendered-image fallback panels with text fields … cannot be
  typed into"* (L), *"Engine widget touch-drag … no gesture drives it"* (L).

- **SC-5 — World Switcher favourites + quick-hop.**
  Cherry-pick `parity-b5-hopper` (`c07e492a34`): `WorldSwitcherBridge.setFavourite(worldId,bool)`,
  `isFavourite(worldId,fav1,fav2)`, `hopDirection(boolean previous)`, `configManager()`. **Net-new,
  NOT on android** (android has only init/isAvailable/getWorldListJson/hop/tick/rebuild/resolveHop;
  native list at `QuestWorldScreens.kt:156-161`). Maps to desktop World Switcher plugin ★ +
  next/prev-world hotkeys. (Sort/region-filter are native-only atop the existing
  `getWorldListJson`.)

- **SC-6 — Engine shift-state feed (D1 spike FIRST).**
  `JavaBridge.setShiftHeld` — sticky-shift latch is already native-shipped on android
  (MainActivity.kt shift path, BuildConfig.DEBUG-gated long-press at MainActivity.kt:318), and
  the shared `JavaBridge.setShiftHeld` exists. **OQ1 BLOCKER** (`MOBILE-UX-AUDIT.md:576`):
  `Client.java:1949` is getter-only; whether a synthetic `VK_SHIFT` routes into the
  `isKeyPressed` backing store is unproven. Run the spike before any further shift work.
  Unlocks shift-gated menu entries + overlay "Configure". Consolidated: *"Sticky-Shift latch
  unverified on hardware + no per-frame re-assert on focus loss"* (S, shared, free-after-merge).

- **SC-7 — Robustness refactor (free-after-merge, no new cap).**
  `parity-b5-hopper` JAVA-1/JAVA-3 (`82a6cb3b43`): camera-pan on the *client* thread (not the
  JNI thread), bridge-tick log-once, `BridgeFaults.java` + `HeadlessSeams.java` scaffolding
  (+81/+348). NOT on android. Adds no user-facing parity but **de-risks the dual-VM
  marshalling/ANR constraint** — land opportunistically with the next merge.

- **SC-8 — Image-fallback panel ceiling.**
  The rendered-image fallback (PanelView.kt:139-208) truncates tall panels and goes blurry
  (no supersample). A shared cap/tile-or-supersample policy lands once for both ports.
  Consolidated: *"Rendered-image fallback truncates panels taller than 2500px and goes blurry"*
  (M, shared). **Adversarial:** this is the OOM-blank-when-tall failure mode — do not treat the
  rendered-image path as "works generically".

- **SC-9 — World-map legacy-account open path.**
  Currently exists only as the probe diagnostic (`WorldMapProbe.java`, open-failure dumps added
  `d0f27a2ec6`). The legacy account cannot open the map (ORBS_DISABLED varbit); the orb is the
  only open path. A shared open-by-script path is owed. Consolidated: *"Legacy account cannot
  open the world map"* (M, shared).

- **SC-10 — Other owed shared bridges** (from `MOBILE-UX-AUDIT.md:516-523`): screenshot PNG
  getter (already shipped — verify), `pinnedPlugins`/incompatible flag (shipped — verify),
  infobox-action-by-point, hub install-progress getter, plugin search over name+description+tags,
  config-profiles, Ctrl/Alt/middle-click modifier surface, sidebar-toggle, hotkey trigger
  surface. Sequence by which finding consumes them (below).

**Device-verification debt is its own gate** between the shared track and AP-2+ (see AP-1).

### Phase AP-0 — Close iOS-parity gaps (native-only quick wins)

Mostly shipped already (`MOBILE-UX-AUDIT.md:473-479`); the remaining native-only S items:

- Native config/plugin-panel **chrome-grade entry point** (currently only the dim dev-cluster
  `⚙` gear, MainActivity.kt:264). Consolidated S, native-android.
- **Gate the devBar out of release builds** — verified the bar itself is always-on
  (MainActivity.kt:340-355 add the `devBar` unconditionally; only the shift long-press is
  `BuildConfig.DEBUG`-gated at MainActivity.kt:318). GPU/HD restart (`◆`/`HD`,
  MainActivity.kt:327-331), logout, sign-in ship in release. Consolidated S, native-android.
- **Outbound links → in-app Custom Tab** instead of the external browser — verified
  MainActivity.kt:1035 `startActivity(ACTION_VIEW … FLAG_ACTIVITY_NEW_TASK)` task-switches away.
  Consolidated S, native-android.
- **Config flush on config-screen exit** — verified `flushConfig()` fires on `onStop()`
  (MainActivity.kt:1397) and logout (MainActivity.kt:584) but NOT on `ConfigScreen` `onBack`
  (ConfigScreen.kt:118 `onBack` calls no flush). Edits persist only on background. Consolidated S.
- **int/double range clamp + don't write per-keystroke** — verified the int/double field writes
  every `onValueChange` with only `toIntOrNull` parsing (ConfigScreen.kt:131-138, helper
  `toIntOrNull` at :180), no min/max clamp → corruption + out-of-range. Consolidated S.
- **Keybind config items render as blank, uneditable rows** — no control at all. Rehome to a
  native per-action button where the action is hand-pickable (NOT a generic registry, per I2).
  Consolidated S, native-android, rehome-touch.
- **GE reflow: restore the trade-history tab** — verified the bridge emits `history`
  (PanelReflowScreens.kt:142-143 comment documents `history:[…]`) but the native screen renders
  only `offers` (PanelReflowScreens.kt:150,181). Pure native add. Consolidated S, native-android.
- **Native tab grid: support OSRS_STRETCH** — verified the grid engages only at `topLevelId==164`
  (MainActivity.kt:1057 `supported = loggedIn && topLevel == 164`); other top-levels fall back
  to raw engine stones. Consolidated M, native-android, free-after-merge.
- **Plugin search over name+description+tags** (desktop matches all three; android is name-only).
  Consolidated S, native-android, shared-work-owed.
- **Orb-tap walk-ring optimistic 600ms arm before interact truth** — tighten/defer the ring.
  Consolidated S, native-android. (Note PARITY-PHASING.md:154-158 already widened RING_GRACE_MS
  to 600ms for the settle gate — reconcile so the optimistic walk-arm doesn't double-fire.)
- **Input-accessory bar over the soft keyboard** (iOS has one) — special keys awkward. S.

### Phase AP-1 — Device verification (the real gate)

Must precede AP-2+ investment (`MOBILE-UX-AUDIT.md:480-483`, Risk R1). **Emulator-GREEN ≠
device-confirmed.** Treat every one of these as UNVERIFIED on real hardware until proven:

- pinch-to-zoom (over the map: emulator-only wheel path), IME composition (world-map search
  text-to-engine is *unverified even on emulator* — PARITY-PHASING.md:328-332 Finding 2),
  haptics, surface-recreate, `dlopen`-from-`filesDir` (could force a `jniLibs` re-package).
- Chrome relocate trio + grid layout (cutout-inset, surface-recreate) — emulator-GREEN only.
- Notification + link delivery — POST_NOTIFICATIONS grant, channel sound, PendingIntent reopen.
- Overlay/infobox edit screens — gestures + persistence.
- Sticky-Shift multi-second latch.
- Perf / thermal / battery on device.

All consolidated S "emulator-GREEN only" findings collapse into this gate.

### Phase AP-2 — Bridge-amortized features (consume the shared track)

Each builds the Compose half over a shared method that landed in the shared track:

- **Notification delivery** (SC-1): map soundType/volume onto an Android `NotificationChannel`
  sound/importance; tap-to-open via PendingIntent. **Android-favoured** vs iOS (normal app
  lifecycle). Constraint: background survival is device-gated (AP-1, OQ6).
- **Config editor reach** (SC-2/SC-3): surface the new fake CORE + Chat-Color groups; add an
  EnumSet multi-select control; FontType 3-way preset picker; per-group Reset button. The
  native HSV+alpha `ColorPickerDialog` already exists (ConfigScreen.kt:204,309) — **the stale
  ConfigScreen.kt:76 "set/color/unknown render read-only" comment is wrong for color; color
  editing is SHIPPED.** Update that comment.
- **Pin/star + incompatible-hide** — verify shipped (`MOBILE-UX-AUDIT.md:489`).
- **Screenshot → MediaStore** — verify shipped.
- **Sticky-Shift button** — ONLY after SC-6's D1 spike clears (Risk R4).

### Phase AP-3 — GPU / 117HD track (POST-v1 fast-follow; isolated)

`MOBILE-UX-AUDIT.md:493-495`. Android's strategic axis (real Mali GLES 3.2 + compute). **NOT a
v1 parity gate** (I13). AG2 base-gpu present-to-Surface → AG2.5/AG3/AG4/AG5 → 117HD on vanilla
textures, on a separate tree-discipline; never concurrent with the layout/veneer work (Risk R3,
single publish seam). New GPU publish hook at the shared render contract.

### Phase AP-4 — Larger native reworks (mirror iOS-P3)

`MOBILE-UX-AUDIT.md:497-503`. Native veneer over shared drivers from the shared track:

- **Overlay edit-mode in-place over the live game** (currently a separate full-screen list,
  OverlayEditScreen.kt) + **free pixel-drag** (currently corner-snap only; the shared
  `OverlayBridge.setOverlayPreferredLocation(name,x,y)` already exists on android —
  OverlayEditScreen.kt:58 flags free-drag as "a follow-up", so this is **native-only**) +
  **extra snap targets** (ABOVE_CHATBOX_RIGHT / CANVAS_TOP_RIGHT; currently 5 corners,
  OverlayEditScreen.kt:55-59). Resize stays infeasible (I1). Overlay "Configure"
  (SHIFT+right-click) menu rides SC-6. Consolidated M items.
- **Per-panel native reflows**: Loot Tracker reset/clear actions (currently view-only), **Time
  Tracking** native reflow (shared bridge built, never wired — free-after-merge), GE/XP at
  data-parity already. Constraint B2 §6 OOM (why reflow beats the image fallback).
- **Engine widget touch-drag routing** (SC-4): Prayer/Spellbook/Bank-tag/Inventory reorder,
  ground-item box — plumbed but no gesture drives it. L.
- **Sidebar-toggle / panel-column collapse** (desktop Ctrl+F11) — re-home to a native button.
- **Ctrl/Alt/middle-click modifier surface** — shared modifier feed + native modifier buttons.
- **Hotkey trigger surface** — hand-picked per-plugin native buttons (NOT a generic registry, I2).
- **Minimap-orb enlarged touch veneer** — orbs render at native small size; **NOTE the hard
  constraint**: the engine hard-pins minimap orbs every frame and there is no API to read the
  composited minimap bitmap, so "make minimap bigger" cannot be solved by moving widgets — only
  a from-scratch native reconstruction (XL, OQ9) or an enlarged transparent touch-target veneer
  over the pinned orbs is possible.
- **Config-profiles UX** (reduced; I10), **Rendered-image-fallback text fields** (SC-4),
  **keyboard occlusion reflow + numeric "enter amount" keypad**, **mobile default-profile**
  (Low Detail on; cursor/key/stretched/devtools/interface-styles off — hides I3/I4/I5/I7).
- **Chat "Report" tab (8th engine tab)** unreachable via the pill (restore-missing, S) — confirm
  ToS acceptance (OQ11) before investing.
- **Rehome-thin / accept-thin** (per I14): overlay+widget hover tooltips, world-map POI
  tooltips, single-frame-hover dwell, Screen Markers drag-to-draw, world-map "Focus on" /
  right-click verify, pinch-over-map device verify. Low priority; the "fix" is a degraded
  approximation, not parity.

### Device-verification gate (recap)

AP-1 is a hard gate before AP-2+. Independently, AP-3 (GPU) and AP-4 (layout) must not run
concurrently in the shared tree (Risk R3). Every "GREEN 2026-06-12" claim in PARITY-PHASING.md
is emulator-only.

---

## 7. Android-vs-iOS divergence

### Where Android LEADS iOS

- **Native press-and-peek big-row menu** — `MenuOverlayView.kt` + `tryBuildNativeMenu`/
  `updateMenuHighlight`/`commitMenuSelection` (MainActivity.kt:1081-1166). iOS uses the engine's
  own small menu and its native overlay is dead scaffolding; **iOS owes this build, Android has
  it** (`MOBILE-UX-AUDIT.md:561`).
- **Free JIT / one-tap cold start** — Android grants JIT natively (do NOT carry iOS StikDebug
  per-launch-attach assumptions over); iOS structurally cannot have a true one-tap launch
  (`MOBILE-UX-AUDIT.md:562`). The defining UX difference.
- **GPU / 117HD further along** — real Mali GLES 3.2 + compute, AG0/AG1b/AG2/AG3-c GREEN on real
  silicon; iOS is ES-3.0-only and GPU was shelved (`MOBILE-UX-AUDIT.md:563`). Still post-v1 (I13).
- **Native ColorPickerDialog already shipped** (ConfigScreen.kt:204,309) — refutes the prior-doc
  "Android color read-only" claim (`MOBILE-UX-AUDIT.md:557` is stale; the ConfigScreen.kt:76
  comment is stale too). Android is at color-edit parity now.

### Where Android LAGS iOS (and free-after-merge opportunities)

Verified against the xref. Calls below reflect what is actually on `origin/android` vs the iOS
`parity-bN` branches.

| Area | iOS shipped on | android call | What android owes |
|---|---|---|---|
| **Config editors** (CORE RuneLite + Chat-Color reach, EnumSet, group reset) | `parity-b1-config` `5f89b1583f` | **shared-work-owed** | `emitFakePlugin`/`fakeConfigClass`/`resetPluginConfig`/`isMobileHiddenItem` are **NOT on android** (last shared merge `fb34c63b56` predates `5f89b1583f`) → cherry-pick the additive shared half + native veneer (fake groups, EnumSet control). Color editing already shipped (refutes stale comment). |
| **Right-bar / panel input** (IME, long-press, wheel, button into the Swing panel) | `parity-b2-rightbar` `60c4e522e8` | **shared-work-owed** | Android forwards `panelClick` ONLY (PanelView.kt:190); `panelText`/`panelKey`/`panelLongPress`/`panelButton`/`panelWheel`/`panelMove`/`isPanelTextActive`/`getPanelText` all **absent** (grep = 0). Cherry-pick shared methods + native IME field + drag→wheel + long-press→BUTTON3. |
| **Overlay free pixel-drag** | `parity-b4-overlays` (ZERO shared delta over b2; iOS-native only) | **native-only** | `OverlayBridge.setOverlayPreferredLocation(name,x,y)` is **already on android**; corner-snap + reset already ship (OverlayEditScreen.kt:55-59). Build the Compose draggable-handle veneer; no shared work. |
| **World Switcher hopper** (★ favourites, quick-hop prev/next) | `parity-b5-hopper` `c07e492a34` | **shared-work-owed** | `setFavourite`/`isFavourite`/`hopDirection` are **net-new, NOT on android**. Cherry-pick + native star/quick-hop buttons. Sort/region-filter are native-only atop existing `getWorldListJson`. |
| **Notifications** (sound type + volume) | `parity-b1-config` `5f89b1583f` | **shared-work-owed** | Android wires only 2-arg `publishNotification` (MainActivity.kt:1035 area). 4-arg overload + `notificationDefaults()` net-new → cherry-pick + native channel sound/importance. Flash/focus infeasible (I8). |
| **World map** (floating window: pan, precise taps, search IME) | iOS Phases 0-1 — **already merged into android via `fb34c63b56`** | **free-after-merge (SHIPPED)** | Verify-present, not a new finding: MainActivity.kt:79-92,723-755,937 `mapPanning`/`panWorldMap`/`updateWorldMap`; `WorldMapProbe.java` carried on android. OPEN BUGS: legacy account cannot open (`d0f27a2ec6`); search-to-engine unverified. |
| **Chat** (channel pill, scroll-by-drag, keyboard toggle, sticky-shift) | shared seams present on android; pill native-landed `a722d5b079` | **free-after-merge (SHIPPED)** | Verify-present. `ChatPillView.kt` is the native pill; `isChatInputVisible`/`getChatInputBounds` on android; `ShiftFeedSpike` identical to b5. DEVICE CAVEAT: IME/keyboard-toggle emulator-green only. |
| **Input** (two-finger camera pan, tap-targeting / press-and-peek) | shared tap machine + MenuPeek merged via `fb34c63b56` | **free-after-merge (SHIPPED core)** | Verify-present. b5 JAVA-3 camera-pan-on-client-thread + `BridgeFaults`/`HeadlessSeams` (SC-7) are robustness-only, free-after-merge, no new cap. |
| **GE / Loot / XP / InfoBox / Quest Helper / Time Tracking reflows** | all curated data bridges byte-identical android↔b5 | **free-after-merge (SHIPPED data)** | Verify-present at data-parity. Native screens consume them. Residual native gaps (GE history tab, Loot reset, Time Tracking native screen) are native-only adds in AP-4. |

**Free-after-merge opportunities** (shared half already on android — owe only native): overlay
free-drag, overlay in-place editor, tab-grid OSRS_STRETCH, FontType preset, per-group reset,
Time Tracking reflow. **Cherry-pick-then-native** (shared half written on a `parity-bN` branch
but NOT on android): config reach (b1), panel input (b2), hopper favourites (b5), 4-arg
notifications (b1).

---

## 8. Open questions / risks (decisions for the human before implementation)

- **OQ-A (BLOCKER, decide first). Engine shift-state feed feasibility (SC-6 / OQ1,
  `MOBILE-UX-AUDIT.md:576`).** `Client.java:1949` `isKeyPressed` is getter-only; the engine
  shift state is fed by its own canvas `KeyListener`, not `KeyManager`. If a synthetic
  `VK_SHIFT` does not route into the backing store, the shift feed is **infeasible** and
  sticky-shift / shift-gated entries / overlay "Configure" fall back to per-feature native
  injection. **Run the spike before any sticky-shift UI** (Risk R4). One answer gates 4-5 items.

- **OQ-B. Device-verification debt (AP-1, Risk R1).** Every Android "works" is emulator-GREEN
  only. pinch, IME composition, haptics, surface-recreate, `dlopen`-from-`filesDir`,
  notification grant/channel-sound/PendingIntent, overlay-edit gestures, sticky-shift latch are
  ALL unverified on real hardware. `dlopen`-from-`filesDir` specifically could force a `jniLibs`
  re-package. This gate must clear before AP-2+ investment.

- **OQ-C. Dual-VM ANR risk (Risk R5).** New bridge calls marshal ART↔HotSpot on the single
  `rl-bridge` thread (`JvmLauncher.kt:207-215`). Synchronous bridge reads (config/panel JSON)
  must stay off the UI thread — config reads are already `Dispatchers.IO` (ConfigScreen.kt:86)
  — verify under real-device load. SC-7's client-thread camera-pan fix de-risks this.

- **OQ-D. Minimap-orb hard constraint (OQ9).** The engine hard-pins minimap orbs every frame and
  there is **no API to read the composited minimap bitmap** — "make minimap bigger" cannot be
  solved by moving widgets. Decide: accept-small, an enlarged transparent touch-target veneer
  over the pinned orbs, or a from-scratch native reconstruction (XL) over the retained live
  click-layer.

- **OQ-E. World-map legacy-account open failure (SC-9).** The legacy mainland account cannot open
  the map (ORBS_DISABLED varbit); the Jagex-tokens account opens it on the same build. The orb
  is the only open path; the fix exists only as a probe diagnostic (`d0f27a2ec6`,
  `WorldMapProbe.java`). Decide whether to invest a shared open-by-script path or accept
  tokens-account-only map testing. Also provide a **fallback open path** when the relocated
  minimap orb cluster is off-screen / chrome hides it.

- **OQ-F. World-map search text-to-engine (unverified).** Typed search text reaching the engine
  filter is unverified even on emulator (IME occludes the field; map did not jump on Enter,
  PARITY-PHASING.md:328-332). Follow up with the keyboard-resize layout or on device.

- **OQ-G. GPU sequencing (OQ12 / Risk R3).** Confirm AG2 (live present) stays a strictly post-v1
  fast-follow isolated from the AP-4 layout work — they meet at the single publish seam and must
  not run concurrently in the shared tree. GPU is missing-as-a-renderer (I13), not a v1 gate.

- **OQ-H. Release-build hygiene.** The devBar (GPU/HD restart, logout, sign-in) ships in release
  (MainActivity.kt:340-355, unconditional). Decide the release affordance set before any signed
  build.

- **OQ-I. Report Abuse unreachable (OQ11).** The Report chat tab (8th engine tab) is dropped on
  both ports — confirm this ToS-relevant function being unreachable is accepted, or invest a
  native path.

- **OQ-J. Shared-contract drift guard (Risk R2).** Shared-core changes are additive-only with no
  compile-time wall; `baseline.txt` may only grow. The drift-guard
  (`tools/shared-contract/check-contract.sh`) + dual-port re-census is mandatory after EACH
  shared-S item, or one port silently breaks.

- **OQ-K. Device spread (Risk R6).** `renderH`/touch-target/reachability/thermal conclusions are
  from one emulator/device class; Android hardware spread is far wider than iOS's single device.
  Decide a UI-scale/density story (OQ5) and portrait support (OQ2) before reachability rework.
