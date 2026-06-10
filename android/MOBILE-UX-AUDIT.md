# Android Mobile-UX Audit

This audit inventories every desktop RuneLite client feature, perk, and plugin and grades how well it survives the move to a touch-first Android phone running real RuneLite on a second in-process HotSpot VM. It pairs the shared cross-port **feature matrix** (identical to the iOS audit, embedded verbatim for side-by-side comparison) with **Android-lensed findings** ranked by impact × effort, an Android **phased plan** keyed to the existing `android/PORT-PHASING.md` phase letters, **Android-vs-iOS divergence** notes, and **open questions/risks**. The posture is adversarial about feasibility: rosy "works generically" claims are downgraded where the code contradicts them, and structurally-blocked items are marked infeasible rather than aspirational. Every claim is grounded in `path:line` from the Android host (`android/app/src/main/java/dev/audaro/helloshell/*.kt`, `android/app/src/main/cpp/jvm_bootstrap.cpp`), the shared Java bridge layer (`runelite-client/src/main/java/net/runelite/client/headless/*`), or the unmodified plugin source.

---

> ### Hard constraints (Android) — read before planning
>
> - **Port phase ~A2 on paper, A-1→A8 + Track G AG1a on disk.** The "~A2" label is stale: `MainActivity.kt` already implements A1 (input), A2 (menu peek), A3 (login), A4 (plugin plane), A5 (panels + Tier-2), A6 (chrome), A7 (relocate), A8 (flushConfig-on-background). The real gap is **device verification** — *every* Android "works" is **emulator-GREEN only**, not device-confirmed (`PORT-PHASING.md:671`). Treat every touch claim (pinch, IME, haptics, surface-recreate, `dlopen`-from-`filesDir`) as unverified on real hardware.
> - **JIT is granted free by Android.** No StikDebug attach, no `patch-jre-txm.sh`, no brk-mirror, no per-launch ceremony (B5 #18). Unlike iOS, Android **can** have a true one-tap cold-start-to-play — this is the single biggest UX advantage over iOS.
> - **A native GLES path is planned but not live.** GLES/EGL exists only in the Track-G smoke harness (`librlawt.cpp`; `rc_AWT_swapBuffers` is a no-op stub). The shipping renderer is a **CPU pixel blit** to a `SurfaceView` (`jvm_bootstrap.cpp:703-746`, R↔B swizzle to `RGBA_8888`). 117HD is a **post-v1 fast-follow**, explicitly NOT a v1 gate (B5 #11, `PORT-PHASING.md` Q8). The GPU coin-flip (`glValidateProgram`) **CLEARED** on a real Mali-G715 (AG1b GREEN, B5 #14) — so HD is Android's strategic axis to beat iOS, but base-gpu live present (AG2) is still NEXT-not-done.
> - **Plugins stay UNMODIFIED.** Every fix must work without editing plugin Java (B5 #1). This forces native veneers + shared-bridge engine-widget relocation, never plugin forks. The Jagex-Mobile toplevel (group 601) is unbuildable on the desktop gamepack (B5 #2/#3).
> - **Shared Java core, additive-only.** Both ports embed the same shaded jar; the `net.runelite.client.headless.*` contract (34 FLAGs + 93 methods, `tools/shared-contract/baseline.txt`) may only grow, never change signatures (B5 #27). A pre-commit drift guard enforces this. **Any UX logic pushed into a bridge benefits both ports for free** — and where iOS already built the shared half, Android only owes the native (Compose/Views) half.
> - **Dual-VM marshalling tax (Android-only).** Android runs a *second* HotSpot via `JNI_CreateJavaVM`; the render thread is the sole HotSpot-attached caller and UI-plane calls marshal across ART↔HotSpot on a single-thread `rl-bridge` executor (`JvmLauncher.kt:207-215`) to avoid ANRs. iOS is single-VM/direct. This adds latency/ANR risk Android must manage that iOS does not.
> - **No clean shutdown flush.** `MobileUiBridge.flushConfig()` must be called on close/background or config silently doesn't persist (B5 #29) — already wired at A8 (`MainActivity.kt`).

---

## Feature inventory (shared matrix — verbatim, both ports)

Below are the master feature matrices, split into category sub-tables for readability. They are to be embedded verbatim in both per-port audit files.

---

### Legend

**Mobile suitability** (intrinsic portability of the feature's interaction model to touch): **Native-ready** = pure passive render / no input, or already touch-driven; works on touch with no rework. **Adaptable** = portable but needs a native substitute (long-press for right-click, native picker/keyboard, panel reflow, op-replay instead of widget edit). **Hard** = depends on a desktop affordance with no clean touch analogue (modifier-hold-during-drag, hover-cursor, mouse-wheel, free-form mouse drag) — works only via a deliberate gesture shim. **Infeasible** = structurally cannot port (OS cursor/tray/taskbar, separate desktop windows, or explicitly shelved/excluded).

**Current iOS / Android status** (grounded in B1/B2/B3 port code): **works** = wired and device-confirmed (iOS) / emulator-GREEN (Android). **partial** = core path works but a sub-behavior is stubbed/missing. **broken** = present but defective. **missing** = not implemented on this port. **desktop-only-assumption** = the desktop feature is not reproduced and is intentionally bypassed/replaced (e.g. Swing chrome the native shell supplants). **unverified** = corpus does not establish status. iOS = device-confirmed (iPhone 17 Pro Max); Android = emulator-GREEN only, device verification pending (per B3/B5).

---

#### Table 1 — Client-level chrome & host surface (A1, A4)

| Feature / Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Main window / JFrame host (ClientUI) | Client chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | No OS window; native root VC (ViewController.m) / Activity (MainActivity.kt) replaces it. |
| Resize modes fixed/resizable/stretched + auto-grow | Client chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | Both ports force Classic resizable (164); auto-grow meaningless on fixed screen (Android patches preferences.dat byte[3]). |
| Sidebar tabbed plugin rail + toggle | Client chrome | Adaptable | works (native dock) | works (Compose panel) | Right-edge Swing rail replaced by native plugin dock; hover/right-click tab affordances dropped. |
| On-canvas sidebar arrow (hover-reveal) | Client chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | Hover-to-reveal has no touch analogue; replaced by native ⚙/▣ buttons. |
| Toolbar / non-tab nav buttons | Client chrome | Adaptable | works | works | Toolbar nav buttons surfaced as native buttons; right-click popup unported. |
| NavigationButton registration seam | Integration seam | Adaptable | works | works | Forwarded to MobileUiBridge.registerNav (no Swing sidebar); icons/tooltips degraded. |
| PluginPanel (225px Swing column) | Panel host | Adaptable | works (rendered + reflow) | works (rendered + reflow) | 225px column rendered to buffer & blitted, or natively reflowed (2 panels only). |
| MultiplexingPluginPanel back-stack | Panel host | Adaptable | unverified | unverified | Native nav stacks exist but corpus doesn't confirm muxer drill-down parity. |
| Configuration panel (search/list/config) | Settings UI | Adaptable | works (native) | works (native) | Swing config replaced by native config editor; keybind/dimension/set read-only. |
| Per-plugin enable / pin-star / gear | Settings UI | Adaptable | partial | partial | Enable + config work; pin/star (pinnedPlugins) not surfaced on either native UI. |
| Plugin Hub install/browse | Settings UI | Adaptable | works | works | Native hub list + install; splash-abort bug fixed (c95cc079a) on both. |
| Config profiles (create/clone/import/reorder) | Settings UI | Hard | missing | missing | Drag-reorder + JFileChooser import/export have no native UI on either port. |
| Client hotkeys (sidebar/panel toggle Ctrl+F11/F12) | Input chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | No hardware function keys; replaced by native buttons. |
| Custom window chrome / title bar | Client chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | No title bar on mobile; N/A. |
| Login screen (engine-rendered) | Auth | Native-ready | works | works | Rides the canvas blit; both ports render it. App-level auth via native WebView. |
| Splash screen | Launch UX | Infeasible | desktop-only-assumption | desktop-only-assumption | runelite.nosplash gates it off on both (install path fix). |
| Screenshot/next-frame capture (DrawManager) | Capture | Native-ready | unverified | unverified | Produces a BufferedImage of the owned CPU buffer; consumer plugins' delivery unverified. |
| Theming / RuneScape fonts (ColorScheme/FontManager) | Styling | Native-ready | works | works | Fonts register under CACIO; native chat tabs bundle the TTFs (both ports). |
| Cursor management (custom + resize cursors) | Input chrome | Infeasible | desktop-only-assumption | desktop-only-assumption | No hardware cursor on touch. |

---

#### Table 2 — Notification delivery (A4)

| Feature / Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Per-notification config UI (NotificationPanel) | Notifications | Adaptable | partial | partial | Notification config type is read-only in both native config editors (B1/B3). |
| OS tray notification (TrayIcon) | Notifications | Infeasible | missing | missing | No system tray; needs UNUserNotificationCenter (iOS) / NotificationManager (Android). |
| Shell-out notify (notify-send/osascript/terminal-notifier) | Notifications | Infeasible | missing | missing | No subprocess in sandbox; unreplaced. |
| Window focus / taskbar / dock attention | Notifications | Infeasible | desktop-only-assumption | desktop-only-assumption | No JFrame/taskbar; gated off. |
| Toolkit.beep / custom .wav sound | Notifications | Adaptable | unverified | unverified | AWT beep meaningless; custom-wav path + native audio not established in corpus. |
| In-canvas screen-flash overlay (processFlash) | Notifications | Native-ready | unverified | unverified | Draws into the game canvas (no OS chrome) — survives the blit; not explicitly verified. |
| In-chat game-message notification | Notifications | Native-ready | works | works | Pure chat text; rides the relocated chat veneer on both ports. |

---

#### Table 3 — Overlay system behaviors (A2)

| Feature / Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Overlay pure-render/layout (snap-corner stacking, clip, safeRender) | Overlay engine | Native-ready | works | works | Overlays lay out and draw at default/saved positions with no pointer. |
| Drag-to-reposition overlays (Alt-hold + drag) | Overlay engine | Hard | missing | missing | Requires Alt-modifier-during-drag; no native reposition gesture on either port. |
| Resize overlays (hover-cursor edge + drag) | Overlay engine | Infeasible | missing | missing | Doubly hover-cursor-dependent; no touch analogue. |
| Snap-to-corner on drop | Overlay engine | Hard | missing | missing | Bound to the drag path above; not ported. |
| Drag-onto-target merge (infobox grouping) | Overlay engine | Hard | missing | missing | Depends on overlay drag; infobox merge/detach unreachable. |
| Right-click reset overlay | Overlay engine | Adaptable | missing | missing | Needs long-press substitute; not wired. |
| SHIFT+right-click overlay menus ("Configure") | Overlay engine | Hard | partial | partial | Shift modifier never fed (A3); engine menu reachable via press-and-peek but Shift-gated entries absent. |
| InfoBox subsystem (render + hover tooltip/menu) | Overlay engine | Adaptable | partial | partial | Infoboxes render; hover tooltip + hover-menu (detach/flip/delete) need long-press, unported. |
| Tooltip subsystem (mouse-anchored) | Overlay engine | Hard | partial | partial | No ambient cursor → only surfaces during press-and-peek (onMove). |
| WorldMap overlay (render + hover + menu) | Overlay engine | Adaptable | partial | partial | Markers render; hover tooltips degrade, "Focus on" is a menu entry needing long-press. |
| WidgetOverlay (engine-widget relocation) | Overlay engine | Adaptable | works (veneer) | works (veneer) | Reimplemented as HudVeneerBridge relocate (shared Java); re-pinned each frame. |
| WidgetItemOverlay (item highlights) | Overlay engine | Native-ready | works | works | Pure-pixel item draw; rides the blit. |
| Overlay components/ (panels, bars, text, etc.) | Overlay engine | Native-ready | works | works | All pure render(Graphics2D); fully headless-safe. |

---

#### Table 4 — Input / hotkey / right-click surfaces (A3)

| Feature / Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Tap → left-click (walk/interact) | Input | Native-ready | works | works | JavaBridge.onClick(1) with red/yellow ring + haptic; smart snap. |
| Long-press → engine right-click menu (press-and-peek) | Input | Adaptable | works | works | MenuPeekBridge + clamped-pointer drag-select; native big-row overlay drawn on Android, stubbed on iOS. |
| One-finger camera rotate | Input | Native-ready | works | works | onCameraDrag (inverted axes), direct yaw/pitch set. |
| Pinch → zoom (wheel notches) | Input | Adaptable | works | works | onWheel at pinch centroid; the one bridged source of mouse-wheel. |
| Chat scroll-by-drag → chat scroll layer | Input | Adaptable | works | missing | iOS chatScrollAccum present; Android has no equivalent (nativeTouch plumbed but unused) — B3 gap. |
| Soft keyboard text + enter + backspace | Input | Adaptable | works | works | Hidden capture field → onText/onKeyStroke; no arrow/function/modifier keys. |
| Configurable hotkeys (Keybind/HotkeyListener) | Input | Hard | missing | missing | No HW keys/modifiers synthesized; every plugin hotkey dead unless re-bridged. |
| HotkeyButton binding UI | Input | Infeasible | missing | missing | No keyboard to record a binding; keybind config read-only on both ports. |
| Shift-click menu swaps (engine isKeyPressed KC_SHIFT) | Input | Hard | missing | missing | Bridge never feeds isKeyPressed → all shift-gated swaps inert. |
| Ctrl/Alt modifier behaviors (zoom, anti-drag, remap) | Input | Hard | missing | missing | No modifier-key events synthesized. |
| Right-click context menus (BUTTON3 identity) | Input | Adaptable | works (via long-press) | works (via long-press) | BUTTON3 faked only by the deliberate long-press gesture. |
| Middle-click / extra mouse buttons | Input | Infeasible | missing | missing | No BUTTON2/≥4 on touch. |
| Mouse-drag (inventory anti-drag, ground-item box) | Input | Hard | unverified | unverified | Drag stream synthesized only for scripted gestures; plugin-level drag not generally driven. |
| Hover (enter/exit/move, dwell, tooltip) | Input | Hard | partial | partial | Only deliberate onMove gestures; no ambient hover. |
| Mouse-wheel (general) | Input | Hard | partial | partial | Only the bridged pinch produces a wheel event. |
| AWT focus-driven held-state reset | Input | Hard | unverified | unverified | AWT focus meaningless on mobile; held-state cleanup unreliable. |

---

#### Table 5a — Passive overlay / infobox plugins (A5a/A5b/A5c) — Native-ready group

These render through the overlay/infobox pipeline with no input surface; they ride the blit on both ports. Status is **works** (renders) on iOS (device-confirmed CPU path) and **works** (renders) on Android (emulator), except where an anchor/HUD-collision caveat is noted. Named in full:

| Plugin(s) | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Diary Requirements, Attack Styles, Combat Level | Interface overlay | Native-ready | works | works | Passive widget overlays; none. |
| Agility, Blast Furnace, Blast Mine, Cooking, Drift Net, Fishing, Herbiboar, Hunter, Mining, Motherlode Mine, Runecraft, Smelting, Tithe Farm, Woodcutting | Skilling overlay | Native-ready | works | works | Passive scene/minimap overlays + timers; minimap overlays must sit under the relocated HUD minimap. |
| Barbarian Assault, Barrows, Cannon, Corp, Nightmare Zone, Pest Control, Pyramid Plunder, Tears of Guthix, Wintertodt, Zalcano, MTA | Minigame/boss overlay | Native-ready | works | works | Passive overlays/timers; NMZ auto-relocates its overlay (reconcile with veneer). |
| Boosts, Boss Timers, Ammo, Item Charges, Kingdom of Miscellania, Poison, Slayer, Special Counter, Timers & Buffs | Info infobox | Native-ready | works | works | Infoboxes must render inside the relocated HUD infobox layer. |
| Chat Channels, Clue Scroll, Puzzle Solver, Implings, POH, Player Indicators, Opponent Information, Team, Regen Meter, Status Bars, Tile Indicators, Runepouch, Item Identification | Passive overlay | Native-ready | works | works | Pure render; Status Bars anchor + Implings/Player-Indicators minimap dots must reconcile with the veneer. |
| FPS Control | Performance overlay | Native-ready | works | works | Passive FPS display; FPS-limit relevant to battery. |
| XP Globes, XP Drop | XP overlay | Native-ready | works | works | Canvas globes / recolored XP-drop widgets; globe hover tooltips degrade on touch. |

---

#### Table 5b — Config-only / headless-logic plugins (A5a/A5b/A5c) — Native-ready group

No on-screen interaction; behavior is config-driven or background. Status is **works** where the corpus establishes the engine/config path holds; **unverified** where notification/network delivery isn't confirmed.

| Plugin(s) | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Animation Smoothing, Entity Hider, Roof Removal, Virtual Levels, Report Button | Visual/widget rewrite | Native-ready | works | works | Engine-hook/script rewrites, no input; verify render hooks under CACIO. |
| Chat Filter, Chat Notifications, Emojis, Timestamp | Chat transform | Native-ready | works | works | Passive text transforms; Chat Notifications delivery is a native-notify concern. |
| Daily Task Indicator, Default World, Logout Timer, Random Events | QoL/login | Native-ready | works | works | Config→engine calls / chat notices; none. |
| Low Detail (lowmemory) | Memory | Native-ready | works | works | Reduces engine memory/fill — mobile-positive; no input. |
| Metronome | Skilling audio | Native-ready | unverified | unverified | Audio-only; depends on the audio backend being wired (not established in corpus). |
| Idle Notifier | Notifications | Native-ready | partial | partial | Logic fine; notification delivery needs the native notify layer (missing, Table 2). |
| Crowdsourcing | Telemetry | Native-ready | works | works | Silent network send; privacy not a touch issue. |
| Examine | Info/pricing | Native-ready | works | works | Rides the existing Examine action; output is passive chat. |

---

#### Table 5c — Adaptable: right-click / menu-entry plugins (A5a/A5b/A5c)

Configuration gesture is right-click/menu-entry — reachable via the press-and-peek long-press, but Shift-gated variants are blocked (A3). Render/result is mobile-safe.

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Menu Entry Swapper | Menu/UI | Adaptable | partial | partial | Default swaps work (improve tap targeting); Shift-to-customize unreachable (no Shift). |
| Bank | Banking | Adaptable | partial | partial | Value overlay works; right-click bank swaps + pin-keyboard + search typing need long-press/keyboard. |
| Bank Tags | Banking org | Adaptable | unverified | unverified | Tag/untag is right-click; tag-tab drag-reflow is mouse-drag-bound. |
| Chat History | Chat | Adaptable | partial | partial | Retention passive; "Clear history" is a menu entry needing long-press. |
| Fairy Rings | Travel | Adaptable | unverified | unverified | Favourite/recent are menu entries; code search needs keyboard. |
| Friend List | Social | Native-ready | works | works | List info passive; minor menu hooks need long-press. |
| Friend Notes | Social | Adaptable | unverified | unverified | Add/edit-note is right-click + text dialog (long-press + keyboard). |
| Ground Items | Loot/QoL | Hard | partial | partial | Price overlay touch-friendly; hold-Alt-highlight + middle-click-hide are modifier/button-bound. |
| Ground Markers | Mapping | Hard | partial | partial | Render fine; mark = SHIFT-hold + right-click (no Shift) → unreachable. |
| HiScore | Lookup | Adaptable | unverified | unverified | Right-click "Lookup" needs long-press; panel typing/autocomplete needs keyboard + reflow. |
| Inventory Tags | Items | Adaptable | unverified | unverified | Tag-assign in right-click submenu (long-press); render fine. |
| NPC Indicators | Overlay | Hard | partial | partial | Highlight/respawn render fine; Shift+Tag unreachable (no Shift). |
| Object Markers | Overlay | Hard | partial | partial | Outline render fine; Shift+right-click-examine mark unreachable. |
| Spellbook | Magic | Adaptable | unverified | unverified | Drag-reorder via engine touch-drag may work; lock/unlock are menu entries (long-press). |
| Wiki | Integration | Adaptable | partial | partial | Orb + chatbox search work on touch; "Wiki"/"DPS" menu entries need long-press; LinkBrowser → native browser. |
| World Map (plugin) | Map overlay | Adaptable | partial | partial | Points render; hover tooltips degrade; depends on the world-map being openable on mobile. |

---

#### Table 5d — Adaptable: Swing-panel plugins needing reflow (A5a/A5b/A5c)

Rich desktop side panels; on the ports they fall through to the rendered-Swing-image surface (works generically) unless a native reflow exists. Per B1/B2/B3 only Quest Helper + World Switcher are natively reflowed.

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Quest Helper (hub) | Quest panel | Adaptable | works (native reflow) | works (native reflow) | Curated native screen via QuestHelperBridge; gated on plugin availability. |
| World Switcher (worldhopper) | Utility panel | Adaptable | works (native reflow) | works (native reflow) | Native world list + hop via WorldSwitcherBridge; hop hotkeys (Ctrl+Shift) dead. |
| Grand Exchange | Trading | Adaptable | partial | partial | In-game GE overlay passive; rich search panel falls to rendered image; typed search needs keyboard. |
| Loot Tracker | Loot panel | Adaptable | partial | partial | Data headless-safe; heavy Swing panel only via rendered-image fallback (buttons/popups/collapse). |
| XP Tracker | XP panel | Adaptable | partial | partial | Canvas infobox works; panel context menus + "Open Wise Old Man" only via rendered image / unported. |
| Time Tracking | Skilling panel | Adaptable | partial | partial | Multi-tab panel only via rendered image; native reflow absent. |
| Skill Calculator | Skilling panel | Adaptable | partial | partial | Text-entry + action grid only via rendered image; usability low without reflow. |
| Notes | Utility panel | Adaptable | partial | partial | Free-text JTextArea only via rendered image; needs native editor + keyboard binding. |
| Kourend Library | Utility panel | Adaptable | partial | partial | Book-location overlay works; Reset-button panel via rendered image. |
| Info Panel | Panel | Adaptable | partial | partial | Low-value build/version panel via rendered image; hyperlink → browser. |
| Party | Social panel | Hard | partial | partial | Panel via rendered image; ping = hotkey-hold + click (no modifier) → core gesture unported. |
| Screen Markers | UI/draw panel | Hard | missing | missing | Panel + free-form mouse-drag rectangle creation; no native equivalent — omit/reimplement. |
| HiScore panel (see also 5c) | Lookup panel | Adaptable | partial | partial | Name typing + autocomplete via rendered image / keyboard; results display fine. |

---

#### Table 5e — Adaptable: in-game-widget / chatbox-input & search plugins (A5b/A5c)

Tappable engine widgets that open the engine ChatboxTextInput — work on touch once the soft keyboard is bound (which both ports have).

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Music | Sound/UI | Adaptable | works | works | Search/filter/volume are tappable widgets; search uses chatbox input (soft keyboard wired). |
| Quest List | Utility | Adaptable | works | works | Tappable search button → chatbox input; depends on soft keyboard (present). |
| Chat Commands | Chat | Adaptable | partial | partial | Typed `!` commands work via keyboard; keybind-triggered lookups dead (no HW keys). |
| Inventory Viewer | Items | Adaptable | partial | partial | Hotkey toggle unreachable; usable as static overlay when default NOT_SET. |
| Inventory Grid | Items | Adaptable | unverified | unverified | Purely visual drag preview; depends on engine touch-drag working. |

---

#### Table 5f — Hover / cursor-dependent plugins (A3/A5a/A5b)

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Mouse Tooltips (mousehighlight) | Overlay | Hard | partial | partial | Hover-only; surfaces only under last tap — largely redundant with native press-and-peek reticle. |
| Item Prices | Items | Hard | partial | partial | Hover tooltip; inert without an open menu/peek. |
| Item Stats | Inventory | Hard | partial | partial | Same hover-only limitation as Item Prices. |
| Interact Highlight | Combat | Adaptable | partial | partial | Hover-color path fires only during peek; interact-on-click path works. |
| Custom Cursor | Cosmetic | Infeasible | missing | missing | No OS cursor on touch — meaningless; should be hidden/no-op. |

---

#### Table 5g — Hard / Infeasible / excluded plugins (A3/A5a/A5b/A5c)

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Anti Drag | Inventory/input | Hard | broken | broken | Default mode needs Shift-hold; no modifier feed → inert. |
| Camera | Camera/input | Hard | partial | partial | Zoom-limit useful; right/middle-click remaps + key-camera dead on touch. |
| Key Remapping | Input | Infeasible | missing | missing | Intercepts HW WASD/F-keys; nothing to remap on touch (BT keyboard only). |
| Login Screen | Utility | Adaptable | desktop-only-assumption | desktop-only-assumption | Engine login bypassed by native Jagex WebView; clipboard-paste hotkey dead. |
| Instance Map | Utility/overlay | Hard | unverified | unverified | Right-click open + scroll-zoom + click-pan have no native gestures; needs gesture mapping. |
| Interface Styles | UI/overlay | Hard | unverified | unverified | Rewrites fixed widget layout — collides with the HUD veneer; likely keep off. |
| Stretched Mode | Renderer | Infeasible | missing | missing | Rescales surface + remaps mouse coords — conflicts with port scaling/touch; exclude. |
| GPU | Renderer backend | Infeasible (iOS) / Hard (Android) | desktop-only-assumption (shelved) | partial (smoke-only) | iOS shelved (ES3.0 ceiling); Android GLES is recon/smoke-only (AG1b cleared), no live present. |
| Developer Tools | Debug | Infeasible | desktop-only-assumption | desktop-only-assumption | Spawns separate Swing frames; dev-only — exclude. |
| Account | RuneLite sync | Adaptable | unverified | unverified | Nav button tappable; RL/Google OAuth flow desktop-oriented, low value. |
| Discord | External integration | Infeasible | unverified | unverified | Discord RPC/IPC is a desktop-process integration; likely inert on-device. |
| DPS Counter | Combat/party | Native-ready | unverified | unverified | Display passive; depends on Party networking working on-device. |
| Twitch | Integration/chat | Adaptable | unverified | unverified | IRC logic headless-safe; needs native config form incl. secret OAuth field (read-only in editor). |
| XP Updater | Integration/background | Native-ready | unverified | unverified | Background WOM/CML/Temple post; username entry only. |
| RuneEnergy | — | N/A | missing | missing | Directory empty/untracked — plugin does not exist; exclude. |
| Raids, Screenshot | PvM / Utility | Adaptable | partial | partial | Overlay/triggers work; screenshot hotkey dead (use native button); LinkBrowser folder → native handling. |
| Skybox, Wintertodt config, XP Drop, Tile Indicators | Visual config | Adaptable | partial | partial | Color-picker config works on iOS/Android native editor (UIColorPicker / Compose); render passive. |
| Prayer | Combat | Adaptable | partial | partial | Overlays/flick high-value + mobile-safe; reorder = widget drag-and-drop needs engine touch-drag routing. |
| Configuration (config plugin) | Core settings | Adaptable | desktop-only-assumption | desktop-only-assumption | Hidden Swing config replaced natively (MobileUiBridge); the Swing panel itself not touch-first. |

> **Android corrections to the verbatim matrix (apply when reading the Android column).** The shared matrix above predates two verified code facts; the matrix text is kept verbatim for cross-port comparison, but these rows are *wrong for Android* and are corrected here:
> - **Table 5g "Skybox, Wintertodt config, XP Drop, Tile Indicators" — color-picker config is NOT "works" on Android.** `ConfigScreen.kt:55` ("set/color/unknown render read-only for now") and `:123` (`"color", "set", "unknown" -> OutlinedTextField ... read-only first cut`) render **color read-only** on Android. iOS has a real `UIColorPickerViewController`; Android does not. **Corrected Android status: partial (color read-only).** Adding a Compose color picker is in-scope native work, not "already done" (see Finding 14).
> - **Table 5e "Inventory Grid" / Table 5c "Spellbook" / Table 5g "Prayer" — engine touch-drag is NOT wired on Android.** `JvmLauncher.kt:66,190` plumb `nativeTouch`/`touch(...)`, but **no `.touch(` call exists in `MainActivity.kt`** — the drag stream is never emitted. So Prayer/Spellbook/Bank-tag reorder and Inventory-Grid drag preview are **unreachable on Android today**, not "may work" (see Finding 13).
> - **Table 5g "GPU" — Android "partial (smoke-only)" overstates it.** `rc_AWT_swapBuffers` is a no-op stub (`librlawt.cpp:271`); there is **no live GPU frame on screen**. Functionally **missing as a renderer**; AG1b cleared the shader viability gate, AG2 (live present) is NEXT-not-done.

---

## Findings — ranked by impact × effort (Android lens)

Ranking puts quick high-impact wins first, then larger high-impact reworks, then medium items, then accept-as-is. Each finding tags **[shared-core]** (additive Java bridge — benefits both ports; if iOS already built it, Android owes only the native half) or **[native-Android]** (Kotlin/Compose/Views host work). "**Behind iOS**" flags where Android lacks something iOS already ships and can reuse iOS's shared-core work. The Android lens emphasizes: device-verification debt, the two iOS-parity gaps (logout, chat-scroll), the dual-VM marshalling, and the GPU/117HD upside.

### Tier 1 — Quick high-impact wins (S/M effort, high impact)

#### 1. No persistent "Log out" on Android — token file leaks across accounts — **Behind iOS** — [native-Android]
- **Problem:** iOS has an explicit logout that deletes `tokens.json` + purges Jagex/runescape cookies (`ViewController.m:608-631`). Android only offers re-login via the `⎆` dev button (`MainActivity.kt:187-188`); the only "logout" is overwriting the token file by logging in again (B3 §7 parity matrix, "Android behind"). `LoginActivity.kt:73` already calls `CookieManager.getInstance().removeAllCookies(null)` on login, and `JagexLogin.kt:123` writes `tokens.json` to `filesDir` — both halves of a clean logout already exist; nothing deletes on demand.
- **Impact:** High for account hygiene. Per-platform-account split is a locked constraint (B5 #24); inability to cleanly drop a session risks cross-contamination + stale cookies.
- **Effort:** S — mirror iOS `onLogout`: delete `filesDir/tokens.json` + `CookieManager.removeAllCookies` for the Jagex/runescape domains, then surface a button. Logout must force a process restart on next login because `JX_*` is snapshotted pre-VM (B5 #25) — Android already restarts after login (`LoginActivity.kt:137-143`), so just route logout → restart.
- **Files/mechanisms:** native-Android only — `MainActivity.kt` (new persistent button alongside `⎆`), `LoginActivity.kt:73` (cookie purge pattern exists), `JagexLogin.kt:123` (token path). No shared-core change.
- **Blocking constraint:** none (B5 #24 makes it important, #25 means logout must restart — already handled).

#### 2. Chat scroll-by-drag missing on Android — **Behind iOS** — [native-Android] (shared side done)
- **Problem:** iOS converts a drag begun inside the relocated chat region into engine chat-scroll via `chatScrollAccum` (`ViewController.m:1980-1989`) → `HudVeneerBridge.scrollChat` → the chatbox scroll layer (`HudVeneerBridge.java:236, 749`). Android has **no equivalent**: `nativeTouch`/`onTouch` is plumbed (`JvmLauncher.kt:65-66`) but **never called** (no `.touch(` in `MainActivity.kt`; B3 §2). Reading scrollback above the fold is impossible on Android today.
- **Impact:** High — reading chat history is core mobile UX; the relocated chat is the primary social/feedback surface.
- **Effort:** S — the shared `HudVeneerBridge.scrollChat` already works (built for iOS). Android only needs the native gesture: detect a drag starting in the relocated chat rect and call `scrollChat(deltaPx)`. The wheel-would-zoom problem (transparent chat over viewport) is already solved by the scroll-layer approach in shared Java.
- **Files/mechanisms:** shared-core done (`HudVeneerBridge.scrollChat` `:236`, `applyChatScroll` `:749`); native-Android needed — a chat-rect drag detector in `MainActivity.kt` + a `JvmLauncher.scrollChat` extern (the bridge method is already in `baseline.txt`, so this is an additive JNI extern, not a contract change).
- **Blocking constraint:** none.

#### 3. Manual keyboard-summon button missing on Android — **Behind iOS** — [native-Android]
- **Problem:** iOS has a manual `⌨︎` toggle button + an input-accessory toolbar (`ViewController.m:287-296`); Android relies **purely on auto-raise** polling `isTextInputOpen()` (`MainActivity.kt:447-458`). With no manual summon, an Android user cannot open the keyboard to compose ordinary public chat / PM — only engine-driven text fields (search, enter-amount) raise it (B3 §5: "No iOS-style `⌨` keyboard button").
- **Impact:** Medium-High — public-chat composition is the highest-frequency text entry on a phone and is currently only reachable when the engine happens to open a field.
- **Effort:** S — add a discreet `⌨` toggle to the existing bottom-left dev `LinearLayout` (`MainActivity.kt:161-208`) that shows/dismisses the 1×1 `GameInputView` (`MainActivity.kt:200, 377-422`). The text-commit path already exists.
- **Files/mechanisms:** native-Android only — `MainActivity.kt` button + `GameInputView` show/hide.
- **Blocking constraint:** none.

#### 4. Input-queue overflow drops terminal UP events (stuck buttons/camera) — Android-specific reliability — [native-Android]
- **Problem:** Android's input queue "silently drops at 512 deep including terminal UP events" (B3 §8, `PORT-PHASING.md:61`). A burst of touch events can drop a release, leaving the engine with a stuck button/camera state. iOS has no such noted limit.
- **Impact:** Medium-High — a stuck camera or held button is a hard reliability failure, not cosmetic, and is exactly the kind of bug that only appears on a real device under load.
- **Effort:** S-M — coalesce move events and prioritize/never-drop terminal UP events in the native input path (`jvm_bootstrap.cpp` input dispatch). This is host C++, no shared-core change.
- **Files/mechanisms:** native-Android only — `jvm_bootstrap.cpp` input queue + `MainActivity.kt` touch handler.
- **Blocking constraint:** none; pure host robustness.

#### 5. Screenshot capture exists but has no native trigger/destination — [shared-core + native-Android]
- **Problem:** `ScreenshotPlugin` is hotkey-driven (`Keybind.NOT_SET`) and writes to a desktop `SCREENSHOT_DIR`, then opens a folder via `LinkBrowser.open` (A5c; A1 §19 `DrawManager`). On touch the hotkey is dead and folder-open is desktop-only; auto-triggers (level-up/death/kill) still fire but the image lands in an unreachable directory. `Hooks.java:639 processDrawComplete` runs on the CPU path (not behind the `isGpu()` return at `:562`), so the `BufferedImage` is reachable.
- **Impact:** Medium-High — screenshots are high-traffic; auto-capture works but is invisible/unreachable.
- **Effort:** S-M — `DrawManager` produces a `BufferedImage` of the owned CPU buffer (A1 §19). Add a SHARED additive bridge getter handing the latest screenshot PNG to native; Android saves to **MediaStore** and exposes a button. (Verify the screenshot-helper transform under CACIO — `getGraphicsConfiguration()`/`getCanvas()` — but the path stands.) iOS owes the Photos-save half; Android owes the MediaStore half — the shared getter is built once.
- **Files/mechanisms:** shared-core — new additive getter (`DrawManager.java:62-108`, `ScreenshotPlugin.java:193,216-229`) on `MobileUiBridge`/`JavaBridge` (additive per B5 #27); native-Android — MediaStore save + button.
- **Blocking constraint:** B5 #27 (additive-only); B5 #12 (single fused `int[]`) is fine — `DrawManager` wraps the composited CPU buffer.

#### 6. Pin/star (pinnedPlugins) not surfaced — plugin list has no fast access — [shared-core + native-Android]
- **Problem:** Desktop pins favourite plugins to the top via a star toggle persisted as `pinnedPlugins` CSV (A1 §10, `PluginListItem.java:113-124`). Neither native UI surfaces pin/star (Table 1, "partial"). With ~250 plugins, scrolling the Compose plugin list (`PluginPanel.kt:53-147`) to a frequently-toggled plugin is slow on a phone.
- **Impact:** Medium — pure QoL, but the mobile plugin list is longer-to-scroll than desktop and pinning is exactly the touch-friendly affordance.
- **Effort:** S-M — `pinnedPlugins` is a plain config key; add an additive read in `MobileUiBridge.listPlugins` + a `setPinned` write, then a Compose star button + sort-by-pinned.
- **Files/mechanisms:** shared-core — `MobileUiBridge.listPlugins` (`:773`) + new `setPinned`; native-Android — `PluginPanel.kt:53-147` star UI + sort.
- **Blocking constraint:** B5 #27 (additive-only).

#### 7. Default plugin set still desktop-tuned — Low Detail off, Custom Cursor on, etc. — [shared-core]
- **Problem:** Defaults assume desktop. Low Detail (`lowmemory`) is `enabledByDefault=false` (A5b) yet explicitly mobile-positive (reduces engine memory/fill, B5 #22-adjacent). Custom Cursor, Key Remapping, Stretched Mode, Anti Drag, Developer Tools, Interface Styles are meaningless/harmful on touch (A3, A5g) but remain user-toggleable to no effect or active harm.
- **Impact:** Medium-High — Low Detail on by default helps memory/heat on a phone; suppressing cursor/key/stretched/devtools/interface-styles prevents "I enabled it and nothing happened / it broke." On Android, Low Detail also directly reduces fill-rate, which matters for battery/thermal until the GPU path lands.
- **Effort:** S — a SHARED mobile default-profile override applied at boot (the gate plumbing exists: `-D` flags + `MobileUiBridge`), or a curated hidden-on-mobile list. Must be a config/default policy, not a plugin edit (B5 #1).
- **Files/mechanisms:** shared-core — boot-time default policy at `DeviceHeadlessBoot`/`MobileUiBridge`; targets `LowMemoryPlugin.java:67`, `CustomCursorPlugin`, `StretchedModePlugin`, `KeyRemappingPlugin`, `InterfaceStylesPlugin`.
- **Blocking constraint:** B5 #1 (config/default policy only, no plugin fork).

#### 8. Plugin list shows incompatible plugins as toggleable (dead toggles) — [shared-core + native-Android]
- **Problem:** iOS shows "✕ couldn't start (incompatible)" after a 4s grace for native-lib/GPU plugins that can't load (B1 §8, `ViewController.m:881-896`). Android's Compose `PluginPanel.kt` may not surface this. GPU, Discord, Developer Tools, Stretched Mode etc. remain listed + toggleable on Android → dead toggles. (On Android, GPU is *not* permanently shelved — but it's smoke-only today, so it's still a dead toggle until AG2.)
- **Impact:** Medium — user confusion when a toggle does nothing or errors.
- **Effort:** S — a SHARED "incompatible on this platform" flag (extend the existing grace/status detection) consumed by the Compose list to hide or disable known-incompatible plugins. iOS already has the native half; Android owes the Compose half.
- **Files/mechanisms:** shared-core — `MobileUiBridge.listPlugins` (`:773`) incompatible flag; native-Android — `PluginPanel.kt` status row.
- **Blocking constraint:** B5 #1 (list policy, not plugin fork).

### Tier 2 — High-impact, larger effort (M/L)

#### 9. Notification layer — Idle/Chat notifications fire into a void (no native delivery) — [shared-core + native-Android]
- **Problem:** Idle Notifier, Chat Notifications, Random Events route through `Notifier`, whose channels are tray (`TrayIcon`), shell-out (`notify-send`/`osascript`/`terminal-notifier`), and taskbar flash — none exist in a sandbox (A4 §6c, Table 2). Only in-canvas `processFlash` (A4 §6c) and in-chat game-message survive. Notification *logic* works; *delivery* is missing.
- **Impact:** High for idle/AFK skilling — the canonical reason to glance at a phone. **Android is the port where this actually pays off**: Android grants free JIT and has a normal app lifecycle, so a backgrounded JVM can in principle keep running and deliver `NotificationManager` notifications. (iOS's foreground-only render model + per-launch JIT make background delivery near-impossible there — so this finding's headline value is **Android-favoured**.) `AndroidManifest.xml:6` already declares `VIBRATE`; `NotificationManager` needs the `POST_NOTIFICATIONS` runtime permission (API 33+).
- **Effort:** M — `Notifier.sendNotification` (`Notifier.java:300-411`) is the single funnel; add a SHARED additive hook publishing `{title, body}` for native to consume, then wire `NotificationManager` + the permission prompt. iOS owes `UNUserNotificationCenter`; Android owes `NotificationManager` — shared publish-hook built once.
- **Files/mechanisms:** shared-core — additive notify-publish hook in `Notifier`/a bridge (`Notifier.java:157-229`, `:248-298` `processFlash` already canvas-safe); native-Android — `NotificationManager`, `POST_NOTIFICATIONS` prompt, tap-to-open deep-link.
- **Blocking constraint:** B5 #23 (sandbox: no subprocess/tray; replacement, not port). **Open dependency:** whether the second HotSpot VM survives Android backgrounding determines whether AFK delivery actually works — must be device-verified (see Finding 11 / OQ).

#### 10. Config editor — keybind/dimension/set/notification/color types read-only — [shared-core + native-Android]
- **Problem:** The Compose editor renders bool/enum/int/double/string writable but leaves **color, set, unknown read-only** (`ConfigScreen.kt:55, 123`) and keybind/dimension/notification unhandled. The shared schema (`MobileUiBridge.getPluginConfig`, `:825, 1160`) already emits range/choices/alpha/enum/Set choices — the gap is the Compose widgets, not the data. Note this is **worse than iOS**, which has a real color picker (`PluginConfigViewController.m:143-151`).
- **Impact:** Medium-High — color blocks a whole class of visual plugins (Skybox/Tile Indicators/Wintertodt/XP Drop, Table 5g); dimension (e.g. inventory-viewer size) and Set (multi-select highlight lists) block real configuration; notification-type is the per-plugin override (Table 2).
- **Effort:** M — Android needs a **Compose color picker** (the in-scope work the matrix wrongly assumed done), plus dimension (two numeric fields) and Set (multi-select). Notification needs a sub-screen mirroring `NotificationPanel` (A4 §6a) — and the MultiplexingPluginPanel drill-down to that sub-screen is **unverified/unbuilt** (Table 1), so the sub-screen is net-new, not "wire into existing drill-down." Keybind stays infeasible (no HW keys).
- **Files/mechanisms:** shared-core — schema already emits the needed fields; just an additive value-encoding convention for dimension/set on `setConfig` (`:957`); native-Android — `ConfigScreen.kt:58-143` (Compose color picker + dimension/set controls + a notification sub-screen).
- **Blocking constraint:** B5 #29 (`flushConfig` on close — wired); keybind bounded by B5 input (infeasible).

#### 11. Device verification of every emulator-GREEN touch claim — the real Android gate — [native-Android]
- **Problem:** **Every** Android "works" in the matrix is emulator-GREEN, not device-confirmed (B3 §0, B5; `PORT-PHASING.md:671`). Pinch, IME composition, haptics (`VibrationEffect`), surface-recreate on background/foreground, and `dlopen` of the JRE `.so` set from `filesDir` (B5 #21 — forbidden API-29+ from writable storage, works on emulator + one cloud device, must be confirmed on a hardened real device) are all unverified on real hardware. Perf/thermal/battery are entirely unmeasured (B3 §8).
- **Impact:** High (process risk) — every downstream Android finding compounds on unverified ground. A failed `dlopen` on a hardened device could force re-packaging the JRE under `jniLibs` (B5 #21).
- **Effort:** M — a real-hardware pass (the project has a Mali-G715 device per AG1b; cloud farm otherwise). Confirm each touch claim + measure perf/thermal/battery; spike the `dlopen`-from-`filesDir` path on a hardened device.
- **Files/mechanisms:** native-Android — `MainActivity.kt` (gestures/IME/haptics), `jvm_bootstrap.cpp` (surface-recreate `g_surfaceGen`, `dlopen`), `AssetInstaller.kt:8-17` (JRE unpack).
- **Blocking constraint:** B5 #21 (`dlopen`-from-`filesDir`) is the one that could force a packaging change; everything else is confirm-or-fix.

#### 12. Shift modifier is never fed — entire shift-click customization & swap surface is dead — [shared-core, GATED] — **likely-infeasible without engine API**
- **Problem:** No bridge feeds the engine's `client.isKeyPressed(KC_SHIFT)` path (verified: no `setKeyPressed`/`isKeyPressed`/`KC_SHIFT` in any bridge; A3 §0). `Client.java:1949` exposes only a **getter** for `isKeyPressed(int)` — there is **no public setter**. This kills: Menu Entry Swapper shift-to-customize (Table 5c), all shift-deposit/withdraw/shop-quantity swaps (~30 call sites), Ground/Object/NPC marking "Shift + right-click" (A5a/A5b), Anti Drag default mode, overlay SHIFT+right-click "Configure" (A2 §7, Table 3).
- **Impact:** High and broad *if feasible* — shift is the single most impactful missing input. But see feasibility.
- **Effort/feasibility:** **UNVERIFIED, likely-infeasible.** Feeding it requires either (a) synthesizing a real `VK_SHIFT` `KeyEvent` to the engine's *own canvas KeyListener* (A3 §0 — the engine shift state is fed by that listener, not `KeyManager`; whether a synthetic modifier routes into the `isKeyPressed` backing store is **unproven**), or (b) an engine-internal poke = forking the engine path (collides with B5 #1). **Gate this with a spike before any UI work.** If (a) fails, the shift feed is infeasible without forking, and the fallback is per-feature native injection only.
- **Files/mechanisms:** shared-core (if feasible) — a new additive `JavaBridge.setShiftHeld(bool)` targeting the engine canvas KeyListener path; consumers `MenuEntrySwapperPlugin.java:1889-1892`, `GroundMarkerPlugin.java:257`, `ObjectIndicatorsPlugin.java:265-267`, `NpcIndicatorsPlugin.java:281`; native-Android — a sticky "Shift" button (or a 3-finger gesture) wired into the long-press peek.
- **Blocking constraint:** B5 #1 (unmodified engine/plugins). **This gates Findings 18, 19, 20** — all inherit its unverified-infeasible risk; keep their shift-gated portions **missing/infeasible** until the spike clears.

#### 13. Engine touch-drag not wired → Prayer/Spellbook/Bank-tag reorder + Inventory-Grid unreachable — [shared-core + native-Android]
- **Problem:** Prayer reorder (`PrayerReorder.onWidgetDrag`), Spellbook reorder (A5c), Bank Tag tab reflow, and Inventory Grid's drag preview all use the engine's own widget drag (`getDraggedWidget()/getDraggedOnWidget()`). The Java seam exists — `JavaBridge.onTouch` synthesizes a correct PRESS→DRAGGED→RELEASE stream with button masks — but on Android **`nativeTouch` is never called** (`JvmLauncher.kt:66,190` plumbed; no `.touch(` in `MainActivity.kt`; B3 §2). So the drag stream is never emitted; reorder is **unreachable today**, not "may work."
- **Impact:** Medium — Prayer reorder and bank-tag organization are valuable; if the drag stream routes into the engine widget drag, this is moderate effort, but it is not free.
- **Effort:** M (both ports) — Android must (a) emit the `onTouch` drag stream from a native drag gesture over the relevant engine widgets, and (b) verify the stream drives the engine's canvas MouseListener widget-drag path. iOS only calls `onTouch` for camera/scroll, so neither shell currently routes a drag into an engine widget — the shared seam is proven for camera, unproven for widget-drag.
- **Files/mechanisms:** shared-core — `JavaBridge.onTouch` (`:1257`) already correct; native-Android — emit the stream from `MainActivity.kt` over engine widget rects; verify against `PrayerReorder.java:188-194`, `SpellbookPlugin.java:216-238`.
- **Blocking constraint:** none structural; needs wiring + device verification.

#### 14. Heavy Swing side panels fall to a degraded rendered image — tall panels OOM-blank — [shared-core + native-Android]
- **Problem:** Only Quest Helper + World Switcher are natively reflowed (B2 §2H, `QuestWorldScreens.kt`). Loot Tracker, XP Tracker, Time Tracking, Skill Calculator, Notes, Grand Exchange, HiScore, Party, Screen Markers fall through to the generic rendered-Swing-image surface (`PanelView.kt` → `mobilePanelImage` → `panelClick`; Table 5d). This is **not a safe baseline for tall panels**: a full-4× tall panel is ~38MB ARGB on a 256MB heap **shared with the running game → blank panel** (B2 §6, `MobileUiBridge.java:474-481`); the scale steps down under a memory budget. Even when it renders, it's a flat blit — context menus, popups, collapse arrows are barely hittable.
- **Impact:** High for high-traffic panels (Loot Tracker, XP Tracker, GE) — among the most-used plugins and the weakest mobile surface; tall ones can render blank.
- **Effort:** L per panel (×2 ports) — each needs a curated native reflow following the QuestHelper/WorldSwitcher pattern (a SHARED data-extraction bridge + a Compose screen). Generic Swing→Compose extraction is rejected (memory), so it's bespoke per panel. The shared data bridge is built once; Android owes the Compose screen, iOS the UIKit screen.
- **Files/mechanisms:** shared-core — per-panel data bridges following `QuestHelperBridge`/`WorldSwitcherBridge` (B2 §2H); fallback `MobileUiBridge.listPanels/openPanel/panelClick` (`:326, 390, 555`); targets `LootTrackerPanel.java:69`, `XpPanel.java:88-96`, `GrandExchangePanel`, `TimeTrackingPanel`; native-Android — `PanelView.kt`/`QuestWorldScreens.kt`-style Compose screens.
- **Blocking constraint:** B5 #27 (additive); B2 §6 OOM ceiling is *why* the rendered-image fallback degrades and the native reflow is necessary.

#### 15. Overlays can't be repositioned — no Alt-drag analogue — [shared-core + native-Android]
- **Problem:** Drag-to-reposition, snap-to-corner, resize, right-click-reset all require Alt-hold + mouse drag + hover-cursor (A2 §5-7, Table 3). None ported (Table 3 all "missing"). Overlays render only at default/saved positions (A2 §14). On a small screen the default corners frequently collide with the relocated HUD (the matrix repeatedly flags "must reconcile with the veneer").
- **Impact:** High — overlay placement is a signature RuneLite perk and the default corners fight the veneer on a phone.
- **Effort:** L (per-port-doubled) — a dedicated native "overlay edit mode" gesture (long-press an overlay → drag → drop on a native snap target), driving `overlay.setPreferredLocation/Position` + `overlayManager.saveOverlay` from a SHARED bridge. The pure layout/snap math is reusable (A2 §14). The edit-mode gesture + snap-target rendering must be built **twice** (Compose for Android, UIKit for iOS). **Resize stays infeasible** (doubly hover-cursor-dependent, A2 §6). Reset (right-click) needs the long-press substitute — feasible.
- **Files/mechanisms:** shared-core — additive bridge wrapping `OverlayManager.saveOverlay` (`OverlayManager.java:252-258`), snap math (`OverlayRenderer.java:792-837`), `Overlay` flags (`Overlay.java:71-93`); native-Android — edit-mode gesture + snap-target draw (a new overlay `View`).
- **Blocking constraint:** B5 #8 (viewport occludes 2D) doesn't apply — overlays draw above scene; resize specifically is infeasible.

#### 16. Minimap can't be enlarged — too small to use on a phone — [shared-core (data done) + native-Android, XL]
- **Problem:** `setMinimapScale` is a disabled stub (`HudVeneerBridge.java:687-743`); the engine hard-pins orbs/icons every frame (B5 #4) and there's no API to read the composited minimap bitmap (B5 #10) and you can't hide the live minimap without killing walk-to (B5 #6). The only lever is the global `renderH` dial (lower = bigger whole HUD), trading sharpness/FPS.
- **Impact:** High — the minimap is a primary navigation tool and is uncomfortably small; "make minimap bigger" is an obvious mobile expectation.
- **Effort:** **XL** (not L) — the only viable fix is a **native minimap reconstructed from data** (`HudDataBridge` already publishes plane/px/py/mapAngle/dots, B2 §2F) drawn over the **retained live engine minimap** (kept invisible underneath for walk-clicks, B5 #6). This is a from-scratch native minimap on **both** ports with a **soft terrain-fidelity tail** (B5 #10 — must reconstruct terrain/POI, no bitmap to scale). The "scaled blit" variant is impossible (can't read the bitmap). Mark the data-rebuild variant **unverified** — the data is published, but a usable reconstructed minimap from it is unproven.
- **Files/mechanisms:** shared-core (data done) — `HudDataBridge` minimap data (`:279-296`), `HudVeneerBridge.setMinimapScale` (disabled, `:687-743`, LIMITATION block `:714-721`); native-Android — a reconstructed minimap `View` over the live click-layer.
- **Blocking constraint:** B5 #4/#6/#10 collectively kill the cheap resize and force native reconstruction.

#### 17. Plugin install — no progress / can't cancel (splash removed) — [shared-core + native-Android]
- **Problem:** Hub install drove the desktop `SplashScreen` for progress (A4 §5, `ExternalPluginManager.refreshPlugins:182,263`), gated off on mobile (`runelite.nosplash`, recent `c95cc079a` fix). Native shows an optimistic Install/Remove flip but no download progress or failure surfacing beyond a status indicator.
- **Impact:** Medium — installing a ~1700-row hub plugin with no progress is confusing; failures are silent.
- **Effort:** M — `refreshPlugins` already stages progress; add a SHARED progress getter to replace the dead splash, surface it in Compose.
- **Files/mechanisms:** shared-core — `MobileUiBridge.hubInstall` (`:679`), `refreshPlugins` (`:136-396`), `SplashScreen.java:165-168` (gated off); native-Android — hub progress UI in the Compose hub list.
- **Blocking constraint:** B5 #2-adjacent (no splash window) → progress via bridge.

### Tier 3 — Medium impact

#### 18. Press-and-peek can't reach Shift-gated entries (overlay "Configure", marking) — [GATED on #12]
- **Problem:** Long-press opens the engine right-click menu (works; B3 §4 — Android draws a real native big-row `MenuOverlayView`), but entries the engine/overlay only inject *while Shift is held* never appear (A2 §7 overlay "Configure"; A5a/A5b marking submenus).
- **Impact:** Medium-High — "Configure this overlay" is unreachable; marking objects/NPCs/tiles unreachable.
- **Effort:** Blocked-pending Finding 12. If the shift feed clears, these appear in the existing native menu. Otherwise a SHARED "always-inject overlay menu entries on long-press" mode is the only fallback. **Infeasible if #12 fails.**
- **Files/mechanisms:** `OverlayRenderer.java:176-210` (shift-gated injection), `MenuPeekBridge` read surface; native-Android — surfacing in `MenuOverlayView.kt`.
- **Blocking constraint:** B5 #1 + the engine-shift caveat from #12.

#### 19. Menu Entry Swapper default swaps help; customization unreachable — [GATED on #12]
- **Problem:** Default swaps (improve tap targeting — mobile-positive) work transparently; shift-to-customize + shift-walk are dead (A3 §2, Table 5c).
- **Impact:** Medium — default swaps already help touch precision; loss is the customization layer.
- **Effort:** Folds into Finding 12 (shift feed). Without it, a native "customize swap" entry surfaced via the Compose config editor is the only path. **Infeasible-as-designed if #12 fails.**
- **Files/mechanisms:** `MenuEntrySwapperPlugin.java:1889-1892`.
- **Blocking constraint:** same as #12.

#### 20. Ground Items / Ground Markers Alt+Shift workflows unreachable — [GATED on #12 + native-Android]
- **Problem:** Ground Items hold-Alt-highlight + middle-click-hide (A3 §1, §5) and Ground Markers SHIFT-right-click mark (A5a) are modifier/button-bound. Price overlay + tile render are fine; the *config gestures* are dead.
- **Impact:** Medium — Ground Items price overlay (the high-value part) works; marking/hiding is the gap; Ground Markers is purely a marking plugin so it's more affected.
- **Effort:** M — Ground Markers folds into Finding 12 (shift); Ground Items needs an Alt analogue + middle-click analogue (no BUTTON2 on touch) → a native "hide this item" long-press entry instead.
- **Files/mechanisms:** `GroundItemMouseAdapter.java:39-82`, `GroundMarkerPlugin.java:257`; native-Android — long-press hide entry in `MenuOverlayView.kt`.
- **Blocking constraint:** B5 input (no Alt/middle-click/shift).

#### 21. Hover-only plugins inert on touch (Item Prices, Item Stats, Mouse Tooltips, World Map tooltips) — [native-Android]
- **Problem:** Item Prices, Item Stats, Mouse Tooltips, XP Globe tooltips, World Map tooltips read `client.getMouseCanvasPosition()` and surface only on hover (A2 §9, Table 5f). No ambient hover on touch; they fire only during the deliberate press-and-peek `onMove`. Mouse Tooltips is largely redundant with the native press-and-peek reticle.
- **Impact:** Medium — Item Prices/Stats are genuinely useful (price/EHP) but currently appear only mid-peek.
- **Effort:** M — surface the engine-computed tooltip for the finger point during the long-press peek (the `onMove` stream already drives it). Mostly native (read path is shared).
- **Files/mechanisms:** `MouseHighlightOverlay.java:92-176`, `ItemPricesPlugin.java:64,95`, `ItemStatOverlay.java:70,86`, `TooltipManager.java:33-52`; native-Android — extend `MenuOverlayView`/peek to render the tooltip.
- **Blocking constraint:** B5 (no hover) — only ever during an explicit gesture; Mouse Tooltips should likely be hidden as redundant.

#### 22. Infobox hover-menu (detach/flip/delete) and merge unreachable — [shared-core + native-Android]
- **Problem:** Infoboxes render (Table 3), but the hover tooltip + hover-menu (Detach/Flip/Delete, `InfoBoxManager.java:68-74`) and drag-onto merge need hover/drag (Table 3 "partial").
- **Impact:** Medium — infobox grouping/detach is a real organization feature as infoboxes pile up in the relocated HUD.
- **Effort:** M — long-press an infobox → native menu calling `InfoBoxManager` detach/flip/delete; merge needs drag-onto (harder, bound to overlay drag #15).
- **Files/mechanisms:** `InfoBoxOverlay.java:113-208`, `InfoBoxManager.java:68-146`; shared-core — additive infobox-action-by-point bridge; native-Android — long-press menu.
- **Blocking constraint:** B5 (no hover/drag); merge bound to #15.

#### 23. Minimap-overlay & status-bar plugins collide with the relocated HUD — [shared-core]
- **Problem:** Minimap dots (Implings, NPC/Player Indicators, POH), Status Bars anchor, and NMZ auto-relocate must "reconcile with the veneer" (Tables 5a). These anchor to engine widget positions the veneer moved; NMZ auto-moves its own overlay (`NightmareZonePlugin.java:128`), potentially fighting the veneer.
- **Impact:** Medium — visible misplacement (dots off the relocated minimap, status bars detached from inventory).
- **Effort:** M — the relocate setters re-pin every frame (B2 §2C); reconciliation means the veneer accounts for these overlays' anchor expectations, or exposes the relocated geometry. Shared (benefits both ports).
- **Files/mechanisms:** shared-core — `HudVeneerBridge.tick` (`:255`), `MobileChromeBridge` geometry (B2 §2G), `NightmareZonePlugin.java:128,167`, Status Bars `Viewport`/`BarRenderer`.
- **Blocking constraint:** B5 #4 (hard-pinned orbs), #8 (z-order); Interface Styles is the extreme case (Finding 7 default-off).

#### 24. FPS limiting / UI-scale for battery & thermal not surfaced — [shared-core (FPS) + native-Android (renderH)]
- **Problem:** FPS Control's limit is config-driven (A5a) and Android's `renderH` (the sharpness↔FPS↔UI-size dial, B3) is a hardcoded magic constant with no in-app tuning. On a phone, capping FPS materially extends battery / reduces heat — and thermal is entirely unmeasured on Android (B3 §8).
- **Impact:** Medium — battery/thermal is a real mobile concern, especially pre-GPU when the CPU rasterizer is the load.
- **Effort:** S (surface FPS Control config) / M (expose `renderH`/UI-scale as a native setting).
- **Files/mechanisms:** shared-core — `FpsPlugin` config; native-Android — the Android `renderH` equivalent + a Compose UI-scale slider.
- **Blocking constraint:** none.

#### 25. External links break (LinkBrowser → desktop browser) — [shared-core + native-Android]
- **Problem:** Wiki "Wiki"/"DPS" → `LinkBrowser.browse`, XP Tracker "Open Wise Old Man", Screenshot "open folder" → `LinkBrowser.open` (A5c). On mobile these need routing to a Chrome Custom Tab / browser intent; folder-open has no analogue.
- **Impact:** Medium — Wiki integration is high-traffic; orb+chatbox search works (Table 5c) but link-out is broken.
- **Effort:** M — intercept `LinkBrowser` at a SHARED seam and forward the URL to native (Custom Tab / `Intent.ACTION_VIEW`); drop folder-open or route to MediaStore.
- **Files/mechanisms:** shared-core — central `LinkBrowser` interception (must not edit each plugin, B5 #1); native-Android — Custom Tab.
- **Blocking constraint:** B5 #1 (intercept centrally).

#### 26. MultiplexingPluginPanel drill-down parity unverified — [native-Android]
- **Problem:** Desktop config drills list → config → sub-panel via a `CardLayout` back-stack (A1 §8). The Compose nav exists but corpus doesn't confirm the muxer drill-down (notification/font sub-panels) works through the Compose config editor (Table 1 "unverified").
- **Impact:** Medium — sub-panels (notification override, font) are reached via the muxer; if drill-down is incomplete they're unreachable even after Finding 10.
- **Effort:** S to verify, M to build the missing Compose sub-screens.
- **Files/mechanisms:** native-Android — `ConfigScreen.kt` nav stack; targets `MultiplexingPluginPanel.java:53-111`, `NotificationPanel`, `FontPanel`.
- **Blocking constraint:** none.

#### 27. Chatbox-input plugins depend on the soft keyboard being reliably bound — [native-Android]
- **Problem:** Music/Quest List/Wiki/Chat Commands/GE/Bank/Fairy Rings searches open the engine `ChatboxTextInput` and need the soft keyboard wired to the engine chatbox (Table 5e). Android has contextual auto-raise polling `isTextInputOpen` (B3 §2). "works" for Music/Quest List, "partial/unverified" for several.
- **Impact:** Medium — search is the entry to a lot of plugin functionality; if auto-raise misfires for any, that search is unreachable.
- **Effort:** S to verify each (mechanism exists); pairs with Finding 3 (manual summon for chat).
- **Files/mechanisms:** native-Android — `MainActivity.kt:447-458` auto-raise; verify against `MusicPlugin.java:124,379`, `QuestListPlugin.java:116`.
- **Blocking constraint:** none; keyboard has no arrow/function keys but text+enter+backspace suffices for search.

### Infeasible / accept-as-is (Android)

- **I1. Overlay/profile resize via hover-cursor edges — INFEASIBLE.** Resize is doubly hover-cursor-dependent (`OverlayRenderer.java:419-477`); touch has no hover and no cursor. Reposition (Finding 15) is feasible; resize is not. **(B5 no-hover/cursor.)**
- **I2. General configurable hotkeys / HotkeyButton binding UI — INFEASIBLE.** `Keybind` matches an AWT keycode + modifier mask; the bridge synthesizes no arbitrary VK keys/modifier masks (A3 §0). There is no way to *record* or *fire* an arbitrary keybind. Per-action native buttons are the only substitute; **strike the generic "named action registry"** — `HotkeyListener` actions aren't named/registered queryably, so it can't be auto-discovered (only hand-picked per plugin). **(B5 input.)**
- **I3. Custom Cursor / Mouse Tooltips / Interface Styles cursor behaviors — INFEASIBLE (hide them).** No OS cursor on touch; Mouse Tooltips redundant with the native peek reticle. Hide/no-op (Finding 7). **(B5 no-cursor.)**
- **I4. Middle-click / extra-mouse-button camera & menu (Camera remaps) — INFEASIBLE.** RMB→MMB / MMB→RMB remaps + `setCameraMouseButtonMask(BUTTON2|...)` need buttons touch lacks. Camera's zoom-limit half is useful and works; the button-remap half is permanently dead. **(B5 no BUTTON2/≥4.)**
- **I5. WASD/F-key camera & "Press Enter to Chat" (Key Remapping) — INFEASIBLE.** Intercepts hardware keys; nothing to intercept without a BT keyboard (out of scope, personal build). Keep off by default. **(B5 input.)**
- **I6. Anti Drag default mode — INFEASIBLE.** Default `onShiftOnly=true` needs Shift held; even if shift were fed (#12) it tames *mouse-drag* of inventory, and touch inventory-drag semantics differ. Mark broken/accept. **(B5 input.)**
- **I7. Stretched Mode — INFEASIBLE (exclude).** Rescales the desktop surface + registers mouse-coord-translating listeners (`StretchedModePlugin.java:54-85`), conflicting with the port's own scaling + touch pipeline. Must not ship. **(B5 input pipeline.)**
- **I8. GPU plugin live render — HARD, not v1.** Distinct from iOS (shelved/infeasible): Android's GLES path is real (Mali GLES 3.2 + compute + `EXT_clip_control`, AG0 GREEN) and the shader coin-flip CLEARED (AG1b). But there is **no live GPU frame on screen** — `rc_AWT_swapBuffers` is a no-op stub; AG2 (base-gpu present-to-Surface) is NEXT-not-done. Treat as **functionally missing today**, **post-v1 fast-follow** (B5 #11/#14). This is Android's strategic axis to beat iOS, not a v1 deliverable.
- **I9. System tray / taskbar / shell-out notifications — INFEASIBLE (replace, don't port).** `TrayIcon`, taskbar flash, `notify-send`/etc. have no sandbox analogue; the only port is a *replacement* `NotificationManager` layer (Finding 9). Canvas `processFlash` is the one channel that survives unchanged. **(B5 #23.)**
- **I10. Config profiles drag-reorder + JFileChooser import/export — partially INFEASIBLE.** Drag-reorder + `JFileChooser` have no direct analogue; reorder can be a reorderable Compose list and import/export via the Storage Access Framework (so it's adaptable-but-L), but the desktop interaction model itself doesn't port. Accept a reduced native profiles UI at best.
- **I11. Screen Markers free-form drag-rectangle creation — INFEASIBLE as-is.** Free-form drag-to-draw on top of camera/peek gestures has no clean touch home; low-value on a relocated-widget HUD. Omit or full native reimplementation only if ever justified. **(B5 input.)**
- **I12. Developer Tools, Discord RPC, Account OAuth — INFEASIBLE / inert.** Developer Tools spawns separate Swing frames (dev-only); Discord RPC/IPC is a desktop-process integration likely inert on-device; Account's RL/Google OAuth is desktop-oriented + low-value. Exclude Developer Tools; accept Discord/Account as inert. **(B5 #23.)**

---

## Phased plan (Android) — keyed to PORT-PHASING.md

Phases reference the existing `android/PORT-PHASING.md` letters where sensible. **[shared-core]** items are sequenced FIRST (both ports consume; additive-only per B5 #27); where iOS already built the shared half, the Android phase is the **native (Compose/Views) half only**. Each phase notes blocking constraints and flags where Android is **Behind iOS** (and can reuse iOS's shared-core bridge work). Respect B5 #28: do not run layout/veneer (A6/A7) and the GPU track (Track G) concurrently in the shared tree — they meet at the single publish seam.

### Phase AP-0 — Close iOS-parity gaps (S, no new constraints) — this is where Android is behind
Quick wins that bring Android level with the mature iOS shell; mostly the native half of work iOS already proved.
- **Persistent Log out button + cookie purge + restart** (Finding 1) — **[native-Android]**, **Behind iOS**. No blocking constraint.
- **Chat scroll-by-drag → `scrollChat`** (Finding 2) — **[native-Android]**, **Behind iOS** (shared `HudVeneerBridge.scrollChat` already done). No blocking constraint.
- **Manual `⌨` keyboard-summon button** (Finding 3) — **[native-Android]**, **Behind iOS**. No blocking constraint.
- **Input-queue overflow guard / coalesce + never-drop terminal UP** (Finding 4) — **[native-Android]**, Android-specific reliability. No blocking constraint.

### Phase AP-1 — Device verification (the real gate; PORT-PHASING §4.5 / B3 §0)
Must precede any AP-2+ investment so findings don't compound on unverified ground (Risk R1).
- **Real-hardware confirm** of every emulator-GREEN touch claim: pinch, IME composition, haptics, surface-recreate, `dlopen`-from-`filesDir` (Finding 11) — **[native-Android]**. Blocking constraint: B5 #21 (`dlopen` could force a `jniLibs` re-package).
- **Perf / thermal / battery on device** (Finding 24 context, B3 §8) — **[native-Android]**.

### Phase AP-2 — Bridge-amortized features (consume shared S0/S1; mirror iOS-P2)
Each consumes a shared bridge; Android builds the Compose half.
- **Screenshot → MediaStore + button** (Finding 5) — **[native-Android]** (shared getter in Shared-S0). Constraint B5 #27.
- **Notification delivery via `NotificationManager` + `POST_NOTIFICATIONS` prompt + tap-to-open** (Finding 9) — **[native-Android]** (shared publish-hook in Shared-S0). **Android-favoured** vs iOS. Constraint: B5 #23 + background-survival is device-gated (AP-1).
- **Pin/star UI + sort** (Finding 6) and **incompatible-plugin hide** (Finding 8) — **[native-Android]** (shared flags in Shared-S0). Constraint B5 #27.
- **Config editor: Compose color picker + dimension/set controls + notification sub-screen** (Finding 10) — **[native-Android]**, **Behind iOS** (iOS already has a color picker). Schema is shared/done. Constraint B5 #29 (flush wired).
- **Sticky-Shift button** (Finding 12 native half) — **[native-Android]** — **ONLY after the Shared-S0 shift-feed spike clears** (else skip; Risk R4).

### Phase AP-3 — GPU / 117HD track (POST-v1 fast-follow; isolated per B5 #28)
Android's strategic axis to beat iOS; the GPU coin-flip is cleared. Keep in a separate tree-discipline; never concurrent with A6/A7 layout work.
- **AG2** base-gpu present-to-Surface (live frame, color/depth correctness) → **AG2.5/AG3/AG4/AG5** → **117HD** on vanilla OSRS textures — **[native-Android]** + a new GPU publish seam at the shared render contract (B5 #12/#28). Blocking constraints: B5 #12 (fused-int[]-before-GPU-branch — needs a new publish hook), #26 (RLHD asset licensing is out-of-band, default to vanilla textures), #28 (publish-seam isolation).

### Phase AP-4 — Larger native reworks (mirror iOS-P3) + accept-as-is defaults
- **Native minimap reconstruction over the retained live click-layer** (Finding 16, XL) — **[native-Android]**. Constraint B5 #4/#6/#10; data published, reconstruction unverified.
- **Overlay edit-mode gesture + snap targets** (Finding 15) — **[native-Android]** (shared position/persist driver in Shared-S1). Resize stays infeasible (I1).
- **Per-panel native Compose reflows** for Loot Tracker / XP Tracker / GE / Time Tracking (Finding 14) — **[native-Android]** (shared per-panel data bridges in Shared-S1). Constraint B2 §6 OOM (why reflow is needed).
- **Keyboard occlusion reflow + numeric keypad for "enter amount"** (Gaps below) — **[native-Android]**.
- **Mobile default-profile** (Low Detail on; cursor/key/stretched/devtools/interface-styles off) — consume shared (Finding 7) — **[shared-core]** boot policy.
- **Hide** Custom Cursor / Mouse Tooltips / Interface Styles (I3, Finding 23 default-off) — **[native-Android]**.

### Shared-core track (additive Java — sequence FIRST; both ports consume)
Each is additive-only; after each, re-run desktop OpenJDK 21 headless smoke + `tools/shared-contract/check-contract.sh --update` + re-census both ports (B5 #27, Risk R2).

**Shared-S0 (unblocks the most):**
- **Engine shift-state feed** `JavaBridge.setShiftHeld` (Finding 12) — **gated on the D1 feasibility spike, do this spike FIRST** (no engine setter exists; synthetic `VK_SHIFT` → `isKeyPressed` unproven; infeasible if it fails). Unlocks Findings 18/19/20 + overlay "Configure".
- Screenshot PNG getter on `DrawManager`/bridge (Finding 5).
- `Notifier` notify-publish hook (Finding 9).
- `pinnedPlugins` read/`setPinned` write + incompatible-platform flag (Findings 6, 8).
- Mobile default-profile boot policy (Finding 7).
- `LinkBrowser` central interception → publish URL (Finding 25).

**Shared-S1:**
- Config schema dimension/set/notification value-encoding (Finding 10).
- Overlay position/persist driver + snap-math wrapper (Finding 15).
- Infobox-action-by-point bridge (Finding 22).
- Per-panel data bridges (Loot/XP/GE/Time, Finding 14).
- Veneer↔overlay anchor reconciliation (Finding 23).
- Hub install-progress getter (Finding 17).
- Engine touch-drag → widget-drag routing (Finding 13) — verify the existing `onTouch` stream drives the engine canvas widget-drag (both ports must emit it).

### Completeness gaps folded into the plan (under-covered desktop features / mobile-UX dimensions)
These were absent or thin in the matrix; address within the phases above:
- **Camera zoom-limit as config** (the mobile-positive half of an otherwise-dead Camera plugin — pinch already maps to wheel; expose `innerLimit`/`outerLimit`) and **draw-distance as a perceived-perf/battery lever on the CPU renderer** (verify scene-radius is reachable without the GPU plugin) — fold into Finding 24 (AP-4). **[shared-core config + native-Android]**
- **`warningOnExit` / exit-and-background protection** while logged in (swipe-to-kill / backgrounding) — `ClientUI.java:797-805` is the desktop guard; mobile needs a native confirm or a safe-suspend story (ties to OQ6). **[native-Android]**
- **Login→in-world handoff UX** — the gap between native-auth-complete and LOGGED_IN: F2P `changeWorld` (B5 #25), the post-WebView restart (B3 login flow), and what the user sees during the handoff. **[native-Android]**
- **Account-switch flow** — logout → cookie purge → re-login → restart is a multi-step forced-restart UX (ties to Finding 1 + B5 #25). **[native-Android]**
- **Orb tap-target sizing** (quick-prayer/spec/run orbs are tiny, hard-pinned at `MM_W=211`, same physics as the minimap, B5 #4) — audit against 48dp Android minimum. **[native-Android]**
- **Public-chat composition + GE/Bank numeric "enter amount"** (numeric keypad vs QWERTY) — high-frequency text entry distinct from search; fold into AP-4 keyboard reflow. **[native-Android]**
- **Report Abuse dropped** (both ports drop the Report chat tab) — ToS-relevant; confirm acceptance (OQ12). 
- **Bank-PIN keypad vs soft-keyboard collision** (`BankConfig.bankPinKeyboard`) — analyze the engine PIN pad vs auto-raised keyboard interaction. **[native-Android]**
- **Touch-target sizing pass** (inventory slots, spell/prayer icons, side-panel tab cells, dialogue rows) against the 48dp Android guideline. **[native-Android]**
- **Gesture vocabulary + long-press overload** — long-press is wanted by right-click menu, chat-filter menu, infobox menu, overlay-edit-mode, plugin-row config; needs by-region disambiguation (OQ3). Consider two-finger-tap / double-tap / edge-swipe / a 3-finger Shift-toggle (OQ4). **[native-Android]**
- **Thumb-reachability** — chat→top-left, minimap→top-right are the hardest one-handed zones on a large phone; no reachability map exists. **[native-Android]**
- **Keyboard occlusion** — specify `adjustResize` vs `adjustPan`; what the keyboard covers (chat/GE/bank results) and whether content reflows above it. **[native-Android]**
- **Portrait vs landscape** — Android is `sensorLandscape` (`AndroidManifest.xml`); official Jagex Mobile supports portrait (the natural one-handed orientation). Decide before reachability/layout rework (OQ2). **[native-Android]**
- **UI-scale / density** — the Android `renderH` equivalent is a hardcoded constant tuned on one device class; no DPI/UI-scale setting (OQ5). **[native-Android]**
- **Haptic vocabulary** — `VibrationEffect` families differ from iOS `UIFeedbackGenerator`; no parity policy for typing/toggle/error/notify/level-up. **[native-Android]**
- **Display cutout / notch** — `SHORT_EDGES` is set (B3 §3) but its interaction with relocated widgets is unverified; check cutout occlusion of game content. **[native-Android]**
- **Accessibility** — TalkBack (the game blit is opaque; the Compose chrome could be labeled), Dynamic Type/font scaling, color-blind click-rings, reduce-motion — entirely uncovered; acknowledge as out-of-scope or pick a minimal subset (OQ11). **[native-Android]**
- **Network/connectivity UX** — cell↔wifi handoff reconnect, the engine connection-lost screen on touch, data-usage, token refresh on a flaky connection (B3 token handling). **[native-Android]**
- **DrawManager every-frame listeners** — `FpsPlugin` and any `registerEveryFrameListener` consumer is a per-frame CPU-path overhead concern; triage. **[shared-core/audit]**

---

## Android-vs-iOS divergence notes

Consolidated "Android-behind / Android-ahead / different-by-construction" ledger. The cross-cutting note in the iOS audit lists only 4 items; this is the full picture for the Android side.

**Android behind iOS (close in AP-0/AP-2):**
- **Persistent Log out** — iOS deletes `tokens.json` + purges cookies (`ViewController.m:608-631`); Android only re-logs-in to overwrite (Finding 1).
- **Chat scroll-by-drag** — iOS `chatScrollAccum` (`ViewController.m:1980-1989`); Android has no equivalent, `nativeTouch` plumbed-but-unused (Finding 2).
- **Manual keyboard-summon `⌨`** — iOS has a toggle + accessory bar (`ViewController.m:287-296`); Android is auto-raise-only (B3 §5, Finding 3).
- **Color-picker config** — iOS `UIColorPickerViewController` (`PluginConfigViewController.m:143-151`); Android renders color **read-only** (`ConfigScreen.kt:55,123`) (Finding 10). These pickers also differ in capability (iOS native picker has eyedropper/grids; a Compose picker is custom).
- **Device verification** — iOS device-confirmed (iPhone 17 Pro Max); every Android "works" is **emulator-GREEN only** (B3 §0). Every Android touch claim is unverified on real hardware (Finding 11).

**Android ahead of iOS (the strategic reason Android exists):**
- **Native big-row right-click menu** — Android draws a real touch-friendly `MenuOverlayView` (34/40dp rows, body biased away from the holding hand, B3 §3); iOS uses the engine's own small menu (the native overlay is dead scaffolding, B1 §2). iOS owes this build; Android already has it.
- **Free JIT / one-tap cold start** — Android grants JIT natively (B5 #18); iOS requires a StikDebug attach **every launch** (B5 #16). **Android can have a true one-tap launch; iOS structurally cannot** — the defining UX difference between the ports.
- **117HD / native GLES** — Android targets real HD (GLES 3.2 + compute, AG0/AG1b GREEN); iOS is ES-3.0-only and GPU is shelved (B5 #11). Background-deliverable notifications (Finding 9) also favour Android's normal app lifecycle vs iOS's foreground-only render model.

**Different-by-construction (no parity gap, but UX-relevant):**
- **UI toolkit** — Android uses Compose/Views (`PluginPanel.kt`, `ConfigScreen.kt`, `PanelView.kt`, `QuestWorldScreens.kt`, `ChromeOverlayView.kt`, `MenuOverlayView.kt`); iOS uses UIKit. No code reuse across the boundary (B4) — every native screen is built twice, with different reflow ceilings.
- **Dual-VM marshalling** — Android runs a *second* HotSpot via JNI and marshals ART↔HotSpot on the `rl-bridge` thread (`JvmLauncher.kt:207-215`), adding latency/ANR risk iOS (single VM, direct calls) doesn't share.
- **Input-queue limit** — Android silently drops at 512 deep including terminal UP events (B3 §8); iOS has no noted limit (Finding 4).
- **`renderH` constant** — both hardcode a render-height dial, but tuned on different device classes; neither is user-adjustable (OQ5).

---

## Open questions / risks (for the human)

**Open questions (decide before the dependent work):**
- **OQ1 (BLOCKER — decide first). Is `client.isKeyPressed(KC_SHIFT)` settable without forking?** `Client.java:1949` is getter-only; the engine shift state is fed by the engine's *own* canvas KeyListener, not `KeyManager` (A3 §0). If a synthetic `VK_SHIFT` `KeyEvent` doesn't route into the `isKeyPressed` backing store, the shift feed is **infeasible** and Findings 12/18/19/20 + overlay "Configure" fall back to per-feature native injection. **Run the spike before any Sticky-Shift UI** (Risk R4). This single answer determines 4-5 findings.
- **OQ2. Portrait support — yes or no?** Android is `sensorLandscape`; official Jagex Mobile is portrait-capable (the natural one-handed orientation). The HUD veneer + `topLevelId==164` layout guard are landscape-shaped. Decide before reachability/layout rework.
- **OQ3. Long-press disambiguation.** Right-click menu, chat-filter menu, infobox menu, overlay-edit-mode (Finding 15), plugin-row config all want long-press. By-region routing (chat rect → filter; overlay rect → edit; world → menu) or an alternative gesture for some? Affects Findings 15, 18, 22.
- **OQ4. Expand the gesture vocabulary or keep it minimal?** Two-finger-tap (faster right-click), double-tap, edge-swipe (dock/chat), a 3-finger Shift-toggle (relevant to OQ1's fallback). Power vs accidental-trigger risk. Decide before Findings 12/15 native UX.
- **OQ5. Expose `renderH` / UI-scale as a user setting?** Hardcoded constant tuned on one device class; Android device spread is wide (small phones / tablets untested). Ship a UI-scale slider (and is draw-distance a separate perceived-perf lever)?
- **OQ6. Background behavior policy.** Keep the second HotSpot VM alive when backgrounded (battery/heat, but enables AFK notifications, Finding 9) vs suspend (logout/reconnect risk, B5 #25 forced restart)? This is *more consequential on Android than iOS* because Android is the port where background delivery is plausible.
- **OQ7. Per-panel native reflow scope.** Each rich panel (Loot/XP/GE/Time/Skill Calc/Notes/Party) is bespoke Compose. Decide the priority cut-list vs staying as the OOM-prone rendered image (B2 §6).
- **OQ8. Notification permission + tap-routing.** First-run `POST_NOTIFICATIONS` prompt timing, deep-link target on tap, and whether to invest in keeping the VM alive for background delivery (OQ6).
- **OQ9. Minimap: native reconstruction (XL, Finding 16) vs accept-small?** The only real fix is a from-scratch native minimap rebuilt from `HudDataBridge` over the retained live click-layer, with a terrain-fidelity tail (B5 #10). Justified, or accept the small minimap?
- **OQ10. Accessibility scope.** Confirm TalkBack/font-scaling/color-blind/reduce-motion is out-of-scope for the personal build, or pick a minimal subset (e.g. color-blind-safe click rings).
- **OQ11. Report Abuse unreachable.** The Report chat tab is dropped on both ports — confirm this ToS-relevant function being unreachable is accepted.
- **OQ12. GPU sequencing.** AG2 (live present) is the next GPU gate; confirm it stays a strictly post-v1 fast-follow isolated from A6/A7 layout work (B5 #28) and is not started concurrently in the shared tree.

**Risks:**
- **R1 (process — Android-specific). Every Android "works" is emulator-only.** Real-device verification (AP-1) must precede AP-2+ investment or findings compound on unverified ground. The `dlopen`-from-`filesDir` path (B5 #21) specifically could force a `jniLibs` re-package.
- **R2 (architecture). Shared-core changes are additive-only with no compile-time wall** (B5 #27, B4) — the contract drift-guard + dual-port re-census is mandatory after each Shared-S item or one port silently breaks.
- **R3 (sequencing). Layout/veneer (AP-4) and the GPU track (AP-3) meet at the single publish seam** (B5 #28) — must not run concurrently in the shared tree.
- **R4 (feasibility cascade). OQ1's answer can invalidate Sticky-Shift work across both ports** — spike before UI.
- **R5 (dual-VM responsiveness). The ART↔HotSpot marshalling on `rl-bridge` is an Android-only latency/ANR surface** — synchronous bridge reads (config/panel JSON) must stay off the UI thread; verify under real-device load.
- **R6 (device spread). Android hardware spread is far wider than iOS's single test device** — `renderH`/touch-target/reachability/thermal conclusions from one device class may not generalize (OQ5).
