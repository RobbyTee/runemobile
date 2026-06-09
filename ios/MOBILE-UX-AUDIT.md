# iOS Mobile-UX Audit — RuneLite on iPhone (CPU/CACIO Port)

This document audits the desktop-RuneLite feature surface against the iOS native shell (`ios/app/HelloShell/`, primarily `ViewController.m`) running real RuneLite + the unmodified plugin ecosystem on an on-device JVM, and ranks the gaps an engineer can execute from. It covers client chrome, the overlay system, input/right-click/hotkey surfaces, notifications, config UX, and the full ~130-plugin set. The shared feature inventory table is embedded verbatim for cross-port comparison; findings are then ranked by impact × effort through an **iOS lens** — the CPU/CACIO renderer, JIT-per-launch lifecycle, hard-pinned engine HUD, and the unmodified-plugin guarantee are the immovable bounds every fix must respect. Feasibility has been adversarially pressure-tested: rosy "works on both" claims are downgraded where the code contradicts them, and structurally impossible items are quarantined in an *Infeasible / accept-as-is* subsection rather than left as latent backlog.

> ### Hard constraints (iOS) — read before scoping anything
> - **CPU/CACIO renderer only.** iOS ships the software rasterizer; the GPU plugin and 117HD are **shelved and immovable** (ES-3.0 ceiling, EAGL z-fight, ANGLE wedges — B5 #11). The native blit contract is **one fused `int[]` framebuffer** taken before the GPU branch (`Hooks.java:559` then `if (client.isGpu()) return;` at `:562`, B5 #12). All UX is built on that single buffer; there is no scene-only buffer and no HD path.
> - **JIT is per-launch via StikDebug attach.** iPhone 17 Pro Max / iOS 26.5 TXM has no debugger-free JIT (`dynamic-codesigning` ungrantable, TrollStore N/A). Every cold start requires attaching StikDebug before `JNI_CreateJavaVM` (B5 #16). **There is no one-tap cold-start-to-play path and there cannot be** — only the post-attach screen can be polished.
> - **The engine hard-pins the minimap orbs/icons every frame.** Widget relocation is render-ignored and re-pinned (`HudVeneerBridge.java:715`, B5 #4). The minimap can't be hidden without killing walk-to (no walk-to-WorldPoint API, B5 #6), and there is no API to read the composited minimap bitmap (B5 #10). "Make the minimap bigger" cannot be solved by editing widgets.
> - **Plugins must stay unmodified.** Every fix must be a config/default policy, a native veneer, or an **additive** shared-bridge method — never a plugin edit (B5 #1, #27). The shared core is additive-only with no compile-time wall; the `tools/shared-contract` drift-guard is the only enforcement.
> - **Foreground-only lifecycle.** The JVM runs only while the app is foregrounded (jetsam assertion + `CADisplayLink`, B1 §1). There is no background execution → background notification / AFK-glance value is near-zero on iOS (B5 #16).
> - **Single VM, direct bridge.** iOS embeds one HotSpot via JNI; bridge calls are direct (no ART↔HotSpot marshalling). Device-confirmed on iPhone 17 Pro Max (vs Android emulator-GREEN only).

---

## Feature Inventory

> The tables below are the shared master matrix, embedded verbatim and carrying both iOS and Android status columns for cross-port comparison. Per the skeptic pass, several statuses are corrected in the **Findings** section (notably Android color config read-only [C1], GPU Android no-live-present [C3], rendered-image panels degraded/OOM-prone [C10], and all Shift-gated rows remaining missing/infeasible [C6]); the matrix text itself is reproduced unchanged for parity with the Android audit.

### Legend

**Mobile suitability** (intrinsic portability of the feature's interaction model to touch): **Native-ready** = pure passive render / no input, or already touch-driven; works on touch with no rework. **Adaptable** = portable but needs a native substitute (long-press for right-click, native picker/keyboard, panel reflow, op-replay instead of widget edit). **Hard** = depends on a desktop affordance with no clean touch analogue (modifier-hold-during-drag, hover-cursor, mouse-wheel, free-form mouse drag) — works only via a deliberate gesture shim. **Infeasible** = structurally cannot port (OS cursor/tray/taskbar, separate desktop windows, or explicitly shelved/excluded).

**Current iOS / Android status** (grounded in B1/B2/B3 port code): **works** = wired and device-confirmed (iOS) / emulator-GREEN (Android). **partial** = core path works but a sub-behavior is stubbed/missing. **broken** = present but defective. **missing** = not implemented on this port. **desktop-only-assumption** = the desktop feature is not reproduced and is intentionally bypassed/replaced (e.g. Swing chrome the native shell supplants). **unverified** = corpus does not establish status. iOS = device-confirmed (iPhone 17 Pro Max); Android = emulator-GREEN only, device verification pending (per B3/B5).

---

### Table 1 — Client-level chrome & host surface (A1, A4)

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

### Table 2 — Notification delivery (A4)

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

### Table 3 — Overlay system behaviors (A2)

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

### Table 4 — Input / hotkey / right-click surfaces (A3)

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

### Table 5a — Passive overlay / infobox plugins (A5a/A5b/A5c) — Native-ready group

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

### Table 5b — Config-only / headless-logic plugins (A5a/A5b/A5c) — Native-ready group

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

### Table 5c — Adaptable: right-click / menu-entry plugins (A5a/A5b/A5c)

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

### Table 5d — Adaptable: Swing-panel plugins needing reflow (A5a/A5b/A5c)

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

### Table 5e — Adaptable: in-game-widget / chatbox-input & search plugins (A5b/A5c)

Tappable engine widgets that open the engine ChatboxTextInput — work on touch once the soft keyboard is bound (which both ports have).

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Music | Sound/UI | Adaptable | works | works | Search/filter/volume are tappable widgets; search uses chatbox input (soft keyboard wired). |
| Quest List | Utility | Adaptable | works | works | Tappable search button → chatbox input; depends on soft keyboard (present). |
| Chat Commands | Chat | Adaptable | partial | partial | Typed `!` commands work via keyboard; keybind-triggered lookups dead (no HW keys). |
| Inventory Viewer | Items | Adaptable | partial | partial | Hotkey toggle unreachable; usable as static overlay when default NOT_SET. |
| Inventory Grid | Items | Adaptable | unverified | unverified | Purely visual drag preview; depends on engine touch-drag working. |

---

### Table 5f — Hover / cursor-dependent plugins (A3/A5a/A5b)

| Plugin | Category | Mobile suitability | iOS status | Android status | Gap (one line) |
|---|---|---|---|---|---|
| Mouse Tooltips (mousehighlight) | Overlay | Hard | partial | partial | Hover-only; surfaces only under last tap — largely redundant with native press-and-peek reticle. |
| Item Prices | Items | Hard | partial | partial | Hover tooltip; inert without an open menu/peek. |
| Item Stats | Inventory | Hard | partial | partial | Same hover-only limitation as Item Prices. |
| Interact Highlight | Combat | Adaptable | partial | partial | Hover-color path fires only during peek; interact-on-click path works. |
| Custom Cursor | Cosmetic | Infeasible | missing | missing | No OS cursor on touch — meaningless; should be hidden/no-op. |

---

### Table 5g — Hard / Infeasible / excluded plugins (A3/A5a/A5b/A5c)

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

---

## Findings — ranked by impact × effort (iOS lens)

Ranking: small/medium-effort high-impact wins first, then larger high-impact reworks, then medium/low. Each finding tags **[shared-core]** (additive Java bridge — benefits both ports, B5 #27) or **[native-iOS]** (UIKit/Obj-C in `ViewController.m` & friends), with the blocking B5 constraint called out. Feasibility corrections from the skeptic pass (C1–C11) are folded in inline.

### Tier 1 — Quick high-impact wins (S/M effort)

#### 1. iOS launch lands on the developer debug panel, not a "tap to play" screen — [native-iOS]
- **Problem.** The cold-start flow surfaces W1 bring-up scaffolding. The launch button literally reads **`"Run AWT activation (§6.0)"`** (verified `ViewController.m:385`); play/login/logout/canary controls live inside the `≡` debug panel; there is no onboarding/play front screen.
- **Impact.** High — first thing the only user sees every launch; the gap between a polished Jagex-Mobile shell and a debug console is jarring.
- **Effort.** S — relabel the button, gate the debug console behind a hidden affordance, present a minimal "Tap to play" once StikDebug attaches. The JIT poller already enables the button on attach (verified `ViewController.m:990-995`, `startJitPolling`).
- **Mechanism.** `ViewController.m:383-390` (button title), `:988-998` (`startJitPolling`), `:1489-1557` (`onLaunch`), debug panel `:369-374, 407-423`.
- **Blocking constraint.** B5 #16 — StikDebug attach is per-launch and **immovable**. You cannot remove "attach debugger first"; the button must still wait on `isDebuggerAttached`. Only the post-attach screen can be polished. (Defining iOS-vs-Android divergence — see Divergence §C7.)

#### 2. iOS HUD relocation is one-shot — won't re-relocate on safe-area change — [native-iOS]
- **Problem.** `updateHudLayout` is guarded by `engineChatRelocated`, set once and **never reset** (verified `ViewController.m:1565, 1572`). A safe-area inset change (rotation LandscapeLeft↔LandscapeRight, notch-side flip) leaves the HUD anchored to stale insets. The relocate *setters* are shared and re-applied every frame; the bug is purely the native one-shot guard.
- **Impact.** Medium-High — rare today (landscape-only + symmetric notch) but a visible breakage when hit; cheap insurance.
- **Effort.** S — reset `engineChatRelocated` on `viewSafeAreaInsetsDidChange` / trait change, or compare current insets to last-applied and re-run.
- **Mechanism.** `ViewController.m:1564-1608` (`updateHudLayout`), guard `:1565`.
- **Blocking constraint.** None.

#### 3. Screenshot capture exists but has no native trigger/destination — [shared-core] + [native-iOS]
- **Problem.** `ScreenshotPlugin` is hotkey-driven (`Keybind.NOT_SET`) and writes to a desktop dir, opening a folder via `LinkBrowser.open`. On touch the hotkey is dead and folder-open is desktop-only; auto-triggers (level-up/death/kill) still fire but the image lands in an unreachable directory.
- **Verified reachable (C-bound):** the CPU draw-complete path runs (`Hooks.java:639 processDrawComplete` is not behind the `isGpu()` early-return at `:562`), so `DrawManager` produces a `BufferedImage` of the owned CPU buffer. **Caveat:** the screenshot helper's `getGraphicsConfiguration()`/`getCanvas()` transform under CACIO needs one device verification.
- **Impact.** Medium-High — screenshots are high-traffic; auto-capture works but is invisible/unreachable.
- **Effort.** S-M — add an additive shared getter to hand the latest screenshot PNG to native; native saves to Photos (`UIImageWriteToSavedPhotosAlbum`) + exposes a button.
- **Mechanism.** `DrawManager.java:62-108`, `ScreenshotPlugin.java:193,216-229`; new additive `MobileUiBridge`/`JavaBridge` getter.
- **Blocking constraint.** B5 #27 (additive-only); B5 #12 (single fused `int[]`) — fine, `DrawManager` wraps the composited CPU buffer.

#### 4. Idle Notifier / Chat Notifications fire into a void — no native notification layer — [shared-core] + [native-iOS]
- **Problem.** Idle Notifier, Chat Notifications, Random Events route through `Notifier`, whose delivery channels are tray (`TrayIcon`), shell-out (`notify-send`/`osascript`/`terminal-notifier`), and taskbar flash — none exist in an app sandbox. Only in-canvas `processFlash` and the in-chat game-message survive. Notification *logic* works; *delivery* is missing.
- **Impact (corrected, C8).** **The headline AFK/idle-glance value on iOS is near-zero.** The foreground-only lifecycle (jetsam assertion, B1 §1) + JIT-per-launch (B5 #16) mean the JVM does not run in the background at all — notifications can only fire while foregrounded. In-app foreground notifications are still worth wiring; the canonical "glance at your phone while AFK-skilling" use case is **largely unrealizable on iOS as built.**
- **Effort.** M — `Notifier.sendNotification` (`Notifier.java:300-411`) is the single funnel; add an additive bridge hook publishing `{title, body}`, then wire `UNUserNotificationCenter` + permission prompt + tap-to-open.
- **Mechanism.** `Notifier.java:157-229` (notify), `:300-411` (OS dispatch to replace), `:248-298` (`processFlash` — already canvas-safe).
- **Blocking constraint.** B5 #23 (sandbox — no subprocess/tray); B5 #16 + B1 §1 (no background JVM) — the impact ceiling.

#### 5. Config editor: keybind / dimension / set / notification types are read-only — [shared-core] + [native-iOS]
- **Problem.** The native config editor renders boolean/int/double/string/enum/color but leaves keybind, dimension, Set-of-non-enum, and notification **read-only** (`PluginConfigViewController.m:157-162`; bridge maps these via `MobileUiBridge.getPluginConfig` at `:1160-1168`).
- **Impact.** Medium-High — dimension (e.g. inventory-viewer size) and Set (multi-select highlight lists) block real configuration; notification-type is the per-plugin override.
- **Effort.** M — dimension (two numeric fields) and Set (multi-select list) are straightforward native controls + an additive value-encoding. Notification needs a sub-screen mirroring `NotificationPanel`. **Keybind is the hard one (infeasible — see I2).**
- **Dependency caveat (C11).** The notification sub-screen is **unbuilt and reachability via the muxer drill-down is unverified** (Table 1 "unverified"; iOS editor renders notification read-only). Effort includes a **new native sub-screen**, not "wire into existing drill-down."
- **Mechanism.** Shared `MobileUiBridge.getPluginConfig`/`setConfig` (`:825, 957, 1160`); native `PluginConfigViewController.m:122-164`.
- **Blocking constraint.** B5 #29 (flushConfig on close — already wired); keybind editing bounded by B5 input (no HW keys → infeasible).

#### 6. Pin/star (pinnedPlugins) not surfaced — plugin list has no fast access — [shared-core] + [native-iOS]
- **Problem.** Desktop pins favorites to the top via a star toggle persisted as `pinnedPlugins` CSV (`PluginListItem.java:113-124`, `PluginListPanel.savePinnedPlugins:338`). Neither native UI surfaces pin/star (Table 1 "partial"). With ~250 plugins, scrolling to a frequently-toggled plugin is slow on a phone.
- **Impact.** Medium — pure QoL, but the mobile list is longer-to-scroll than desktop and pinning is the touch-friendly affordance.
- **Effort.** S-M — `pinnedPlugins` is a plain config key; additive read in `MobileUiBridge.listPlugins` + a `setPinned` write, then a native star + sort-by-pinned.
- **Mechanism.** Shared `MobileUiBridge.listPlugins` (`:773`); native plugin modal `ViewController.m:814-846`.
- **Blocking constraint.** B5 #27 (additive-only).

#### 7. Default plugin set still desktop-tuned — Low Detail off, Custom Cursor on, etc. — [shared-core]
- **Problem.** Defaults assume desktop. Low Detail (`lowmemory`) is `enabledByDefault=false` yet is explicitly mobile-positive (reduces engine memory/fill on the **memory-capped iOS path**, B5 #22). Custom Cursor, Key Remapping, Stretched Mode, Anti Drag, Developer Tools, Interface Styles are meaningless/harmful on touch but remain user-toggleable to no effect or active harm.
- **Impact.** Medium-High — Low Detail on by default directly helps the iOS heap; suppressing cursor/key/stretched/styles plugins prevents "I enabled it and nothing happened / it broke" confusion.
- **Effort.** S — a shared mobile default-profile override applied at boot (the gate plumbing exists: `-D` flags + `MobileUiBridge`), or a curated hidden-on-mobile list.
- **Mechanism.** `LowMemoryPlugin.java:67`, `CustomCursorPlugin.java:125`, `StretchedModePlugin.java:54-85`, `InterfaceStylesPlugin.java:140`; shared gate at `DeviceHeadlessBoot`/`MobileUiBridge`.
- **Blocking constraint.** B5 #1 (don't fork plugins — must be config/default policy); B5 #11 (iOS CPU-only) is *why* Low Detail matters more here.

#### 8. Native rendered right-click menu overlay is declared but never built (iOS) — [native-iOS]
- **Problem.** The class declares a full native OSRS-styled big-row menu (`menuContainer`, `menuRows`, sizing constants, `ViewController.m:216-218`) and comments describe it, but **only `teardownMenuViews` touches those views — nothing creates them** (B1 §2, "largest comment-vs-reality gap"). The shipped behavior is the *engine's own* rendered menu driven by clamped-pointer moves. Android *does* draw a native touch-friendly menu (`MenuOverlayView.kt`, 34/40dp rows).
- **Impact.** Medium-High — engine menu rows are sized for a 19/15px mouse target; on a phone they're small and finger-imprecise. Android already solved this; iOS lags.
- **Effort.** M — **net-new UIKit rendering** (C7: it is a fresh build, not a "port" — Kotlin Compose/Canvas does not cross the B4 boundary). Read `MenuPeekBridge.getSnapshot()` (already available), draw big native rows, commit via `selectMenuPoint`/`onClickPrecise`. Reference the Android approach for hand-bias + 44pt rows.
- **Mechanism.** Shared `MenuPeekBridge.getSnapshot`/`getTopFingerprint` (`:150, 160`); native scaffolding to flesh out `ViewController.m:1779-1928`, `:216-218`.
- **Blocking constraint.** B5 #8 (3D viewport occludes relocated 2D widgets) is *why* this must be a native UIKit layer above the blit — which is exactly what enables the fix.

---

### Tier 2 — High-impact, larger effort (M/L)

#### 9. Shift modifier is never fed — the entire shift-click customization & swap surface is dead — [shared-core] + [native-iOS] — ✅ SPIKE GREEN (2026-06-08): FEASIBLE, cleared to build
- **Problem.** No bridge feeds the engine's `client.isKeyPressed(KC_SHIFT)` path (re-verified: zero `isKeyPressed`/`setKeyPressed`/`KC_SHIFT`/`SHIFT_DOWN_MASK` in any `headless/` bridge; A3 §0). This kills Menu Entry Swapper shift-to-customize, all shift-deposit/withdraw/shop-quantity swaps (~30 call sites), Ground/Object/NPC marking ("Shift + right-click to mark"), Anti Drag default mode, and overlay SHIFT+right-click "Configure" menus.
- **Impact.** High and broad — shift is the single most impactful missing input; it gates marking workflows core to gameplay QoL.
- **Feasibility (corrected, C6 — the most consequential downgrade in this audit).** This was billed as the "highest-leverage shared win," but the engine API contradicts it. **`Client.isKeyPressed(int)` is a getter only — verified `Client.java:1949`, with no public setter.** The engine shift state is fed by the engine's *own* canvas KeyListener, not by `KeyManager`. Feeding it requires either (a) synthesizing a real `VK_SHIFT` `KeyEvent` to the engine's canvas KeyListener — **untested; the engine may not route a synthetic modifier into `isKeyPressed`'s backing state** — or (b) an engine-internal poke, which **forks the engine path and violates B5 #1**. **Status: unverified at best, infeasible at worst. Run the spike before committing any Sticky-Shift UI.**
- **Effort.** M for the bridge + a native sticky-"Shift" button *if and only if* a synthetic `VK_SHIFT` reaches the engine's `isKeyPressed` backing; otherwise infeasible-without-forking and the only fallback is per-feature native injection.
- **Mechanism.** Hypothetical additive `JavaBridge.setShiftHeld(bool)`; consumers `MenuEntrySwapperPlugin.java:1889-1892`, `GroundMarkerPlugin.java:257`, `ObjectIndicatorsPlugin.java:265-267`, `NpcIndicatorsPlugin.java:281`.
- **Blocking constraint.** B5 #1 (unmodified plugins/engine). **This cascades:** Findings #10, #15, #16 and overlay "Configure" all inherit this risk — they are *blocked pending #9's spike and infeasible if #9 fails.* The matrix rows assuming shift-gated entries become reachable keep their Shift-gated portions **missing/infeasible.**

#### 10. Press-and-peek can't reach Shift-gated menu entries (overlay "Configure", marking) — [shared-core] + [native-iOS] — ⚠ blocked-pending-#9
- **Problem.** Long-press opens the engine right-click menu (works), but entries the engine/overlay only inject *while Shift is held* never appear (overlay "Configure" `OverlayRenderer.java:176-210`; marking submenus). So the peek menu is missing exactly the configuration entries.
- **Impact.** Medium-High — "Configure this overlay" is the canonical desktop affordance and is unreachable; marking objects/NPCs/tiles is unreachable.
- **Effort.** Folds into #9 (feed shift during the peek) — **inherits #9's unverified/infeasible status (C6).** Alternative: a shared "always-inject overlay menu entries on long-press" mode (does not need shift, narrower scope).
- **Mechanism.** `OverlayRenderer.java:176-210` (shift-gated injection), `MenuPeekBridge` (read surface).
- **Blocking constraint.** B5 #1 + the engine-shift caveat from #9.

#### 11. Overlays can't be repositioned — no Alt-drag analogue — [shared-core] + [native-iOS]
- **Problem.** Drag-to-reposition, snap-to-corner, resize, and right-click-reset all require Alt-hold + mouse drag + hover-cursor. None ported (Table 3 all "missing"). Overlays render only at default/saved positions. On a small screen, default corners frequently collide with the relocated HUD (the matrix repeatedly flags "must reconcile with the veneer" for Status Bars, Implings, Player Indicators dots).
- **Impact.** High — overlay placement is a signature RuneLite perk and collisions are visible on a phone.
- **Effort (corrected, C5).** L, **per-port-doubled** — the native edit-mode gesture + snap-target rendering must be built in UIKit (iOS) *and* Compose/Views (Android); no code crosses the B4 boundary. The pure layout/snap math is reusable shared Java; drive `overlay.setPreferredLocation/Position` + `overlayManager.saveOverlay` from an additive bridge. **Reset (right-click) is feasible via long-press; resize is infeasible (I1).**
- **Mechanism.** Additive bridge wrapping `OverlayManager.saveOverlay` (`OverlayManager.java:252-258`), `OverlayRenderer` snap math (`:792-837`), `Overlay` flags (`movable/snappable/resizable`, `Overlay.java:71-93`).
- **Blocking constraint.** B5 #8 doesn't block (overlays draw above scene). Reposition for any overlay anchored to a relocated widget interacts with the per-frame re-pin (B5 #4) — see #18.

#### 12. Heavy Swing side panels fall to a zoomable rendered image — low usability — [shared-core] + [native-iOS]
- **Problem.** Only Quest Helper + World Switcher are natively reflowed. Loot Tracker, XP Tracker, Time Tracking, Skill Calculator, Notes, Grand Exchange, HiScore, Party, Screen Markers all fall through to the generic rendered-Swing-image surface (`MobileUiBridge.openPanel` → buffer → `panelClick`). The 225px column rendered at 4× into a pinch-zoom scrollview is functional but a poor touch experience.
- **Impact.** High for high-traffic panels (Loot Tracker, XP Tracker, GE) — among the most-used plugins and the weakest mobile surface.
- **Effort.** L **per panel**, **×2 ports** — each needs a bespoke native reflow following the QuestHelper/WorldSwitcher Bridge pattern (a shared data-extraction bridge + a native screen per platform). Generic Swing→UIKit extraction was explicitly rejected.
- **Rendered-image fallback is NOT a safe baseline (corrected, C10).** A full-4× tall panel is ~38MB ARGB on a 256MB heap *shared with the running game* → **blank panel** (`MobileUiBridge.java:474-481`); the scale steps down under a memory budget, so tall panels (Loot Tracker history, Time Tracking multi-tab) render **blank/degraded**. The fallback is also a flat blit — context menus, popups, collapse arrows are barely hittable. Mark Table 5d "works generically via rendered image" → **partial/degraded; tall panels OOM-blank.** This reinforces the case for native reflow.
- **Mechanism.** Shared pattern `QuestHelperBridge`/`WorldSwitcherBridge`; fallback `MobileUiBridge.listPanels/openPanel/panelClick` (`:326, 390, 555`). Targets: `LootTrackerPanel.java:69`, `XpPanel.java:88-96`, `GrandExchangePanel`, `TimeTrackingPanel`.
- **Blocking constraint.** B5 #27 (additive-only); the panel-buffer OOM ceiling (B2 §6).

#### 13. Plugin hotkeys are universally dead — no re-bind path — [native-iOS] (per-action only)
- **Problem.** Every `Keybind`/`HotkeyListener` is inert (no HW keys). This kills toggles with *no other trigger*: Inventory Viewer toggle, World Switcher prev/next hop (Ctrl+Shift), Raids/Screenshot hotkey, Party ping, Chat Commands keybind lookups, Wiki DPS hotkey. `HotkeyButton` can't record a binding (no keyboard).
- **Impact.** Medium-High — several plugins become unusable, not just degraded (Party ping has no fallback at all).
- **Effort & feasibility (corrected, C9).** **The generic "named-action registry" is infeasible** — `HotkeyListener` actions aren't named/registered anywhere queryable, so auto-discovery is impossible. Only **per-plugin native buttons** for the highest-value cases are feasible (M case-by-case; screenshot covered in #3, world hop already native). Bluetooth-keyboard users are out of scope (personal build).
- **Mechanism.** `HotkeyListener.java:46-103`, `Keybind.java:106-146`, `InventoryViewerPlugin.java:66`, `WorldHopperConfig.java:43-61`, `PartyConfig.java:81`.
- **Blocking constraint.** B5 input (no HW keys/modifiers) — a generic keybind system is infeasible (I2).

#### 14. Minimap can't be enlarged — too small to use on a phone — [shared-core] + [native-iOS] — ⚠ near-infeasible
- **Problem.** `setMinimapScale` is a **disabled stub** (verified `HudVeneerBridge.java:687-743` LIMITATION block; iOS `kMinimapScalePct=100` no-op, verified `ViewController.m:1588`). The engine hard-pins orbs/icons every frame (`setForcedPosition` ignored at render); the globe can only grow *into* the fixed orbs or desync the raster. The only lever is the global `renderH` dial (lower = bigger whole HUD), which trades sharpness/FPS.
- **Impact.** High — the minimap is a primary navigation tool and is uncomfortably small; "make minimap bigger" is an obvious mobile expectation.
- **Effort (corrected, C4).** **XL, not L.** No cheap "resize the widget" fix exists. The only viable path is a **native minimap reconstruction**: there is no API to read the composited minimap bitmap (B5 #10), so you cannot scale a bitmap — you must **redraw** a native minimap from `HudDataBridge` data (plane/px/py/mapAngle/dots already published) while keeping the **live engine minimap underneath invisibly for walk-clicks** (B5 #6 — minimap can't be hidden without killing walk-to), then re-do hit-testing — on **both** ports. Mark the data-rebuild variant **unverified** (a usable reconstructed minimap from published dots is unproven; carries a terrain/POI fidelity tail).
- **Mechanism.** Shared `HudVeneerBridge.setMinimapScale` (disabled, `:182, 687-743`), `HudDataBridge` minimap data (`:279-296`).
- **Blocking constraint.** B5 #4 (orbs hard-pinned), #6 (can't hide minimap without killing walk-to), #10 (no bitmap readback) — collectively force the XL native-reconstruction path.

#### 15. Prayer / Spellbook / Bank-tag reordering needs engine touch-drag routing — [shared-core] — ⚠ unverified-leaning-NO
- **Problem.** Prayer reorder (`PrayerReorder.onWidgetDrag`), Spellbook reorder, Bank Tag tab reflow all use the engine's own widget drag (`getDraggedWidget()/getDraggedOnWidget()`).
- **Feasibility (corrected, C2).** `JavaBridge.onTouch` *does* synthesize a full PRESS→DRAGGED→RELEASE stream with proper button masks (`:1257`), so it *could* drive the engine's own widget drag. **But the iOS shell only calls `onTouch` for camera/scroll gestures — it never routes a drag into an engine widget** (B1 §2). (On Android `onTouch` is plumbed but never called at all.) So engine-widget touch-drag is **not driven on either shell today.** This is a legitimate open verification, not a false claim — but it is **unverified-leaning-NO**, not "nearly free."
- **Impact.** Medium — Prayer reorder and bank-tag organization are valuable.
- **Effort.** M (not S) — verify on device whether routing the existing drag stream into the engine widget-drag path works; if so, wire the native shell to emit `onTouch` for in-widget drags. Benefits both ports once the shared seam is exercised.
- **Mechanism.** Shared `JavaBridge.onTouch` (`:1257`), `PrayerReorder.java:188-194`, `SpellbookPlugin.java:216-238`.
- **Blocking constraint.** None structural; needs device verification + native wiring on both shells.

#### 16. Menu Entry Swapper default swaps help, but customization is unreachable — ⚠ blocked-pending-#9
- **Problem.** Default swaps (improve tap targeting — genuinely mobile-positive) work transparently; the shift-to-customize submenu and shift-walk are dead.
- **Impact.** Medium — default swaps already help touch precision; the loss is the customization layer.
- **Effort.** Folds into #9 (shift feed) — **inherits #9's unverified/infeasible status.** Without #9, surface a native "customize swap" entry via the config editor instead of the in-game shift menu.
- **Mechanism.** `MenuEntrySwapperPlugin.java:1889-1892`.
- **Blocking constraint.** Same as #9.

#### 17. Plugin install can't be cancelled / progress invisible (splash removed) — [shared-core] + [native-iOS]
- **Problem.** Hub install drove the desktop `SplashScreen` for progress, gated off on mobile (`runelite.nosplash`; the `c95cc079a` fix). Native shows an optimistic Install/Remove flip but no download progress or failure surfacing beyond the row status.
- **Impact.** Medium — installing a 1700-row hub plugin with no progress is confusing; failures are silent until the row updates.
- **Effort.** M — `refreshPlugins` already stages progress; add an additive progress getter to replace the dead splash, surface it natively.
- **Mechanism.** Shared `MobileUiBridge.hubInstall` (`:679`), `ExternalPluginManager.refreshPlugins` (`:136-396`); native `PluginHubViewController.m:203-215`.
- **Blocking constraint.** B5 #2-adjacent (no desktop window/splash) → progress must come through a bridge.

---

### Tier 3 — Medium impact

#### 18. Minimap-overlay & status-bar plugins collide with the relocated HUD — [shared-core]
- **Problem.** Minimap dots (Implings, NPC/Player Indicators, POH), Status Bars anchor, and NMZ auto-relocate must "reconcile with the veneer." These anchor to engine widget positions the veneer moved; NMZ auto-moves its own overlay (`NightmareZonePlugin.java:128`), possibly fighting the veneer.
- **Impact.** Medium — visible misplacement (dots off the relocated minimap, status bars detached, NMZ overlay wrong place).
- **Effort.** M — the relocate setters re-pin every frame (B2 §2C); reconciliation means the veneer accounts for these overlays' anchor expectations or exposes the relocated geometry so overlays land correctly.
- **Mechanism.** `HudVeneerBridge.tick` (`:255`), Status Bars `Viewport`/`BarRenderer`, `NightmareZonePlugin.java:128,167`.
- **Blocking constraint.** B5 #4 (hard-pinned orbs), #8 (z-order); Interface Styles (#7) is the extreme case (keep off).

#### 19. Hover-only plugins are inert on touch (Item Prices, Item Stats, Mouse Tooltips) — [native-iOS]
- **Problem.** Item Prices, Item Stats, Mouse Tooltips, XP-globe and World-Map tooltips read `client.getMouseCanvasPosition()` and surface only on hover. No ambient hover on touch; they fire only during the deliberate press-and-peek `onMove`. Mouse Tooltips is "largely redundant with the native reticle."
- **Impact.** Medium — Item Prices/Stats are genuinely useful but appear only mid-peek.
- **Effort.** M — surface these during the long-press peek (the `onMove` stream already drives them) by also rendering the engine-computed tooltip at the finger point; or a "tap-and-hold an item → show stats" native affordance reading `TooltipManager`.
- **Mechanism.** `MouseHighlightOverlay.java:92-176`, `ItemPricesPlugin.java:64,95`, `ItemStatOverlay.java:70,86`, `TooltipManager.java:33-52`; shared `JavaBridge.onMove` (`:1095`).
- **Blocking constraint.** B5 (no hover) — can only appear during an explicit gesture; Mouse Tooltips should likely be hidden as redundant.

#### 20. Overlay infobox hover-menu (detach/flip/delete) and merge unreachable — [shared-core] + [native-iOS]
- **Problem.** Infoboxes render, but the hover tooltip + hover-menu (Detach/Flip/Delete, `InfoBoxManager.java:68-74`) and drag-onto merge need hover/drag. Users can't reorganize infoboxes.
- **Impact.** Medium — grouping/detach matters as infoboxes pile up in the relocated HUD layer.
- **Effort.** M — long-press an infobox → native menu calling existing `InfoBoxManager` detach/flip/delete; merge needs drag-onto (bound to #11, harder).
- **Mechanism.** `InfoBoxOverlay.java:113-208`, `InfoBoxManager.java:68-146`; additive infobox-action-by-point bridge.
- **Blocking constraint.** B5 (no hover/drag); merge bound to overlay drag (#11).

#### 21. Ground Items / Ground Markers Alt+Shift workflows unreachable — [shared-core] (shift) + [native-iOS] — ⚠ partly blocked-pending-#9
- **Problem.** Ground Items hold-Alt-highlight + middle-click-hide and Ground Markers SHIFT-right-click mark are modifier/button-bound. Price overlay and tile render fine; the *config gestures* are dead.
- **Impact.** Medium — Ground Items price overlay (the high-value part) works; marking/hiding is the gap. Ground Markers is purely a marking plugin → more affected.
- **Effort.** Ground Markers folds into #9 (shift, inherits its risk). Ground Items needs a native "hide this item" long-press entry (no Alt, no BUTTON2 on touch).
- **Mechanism.** `GroundItemMouseAdapter.java:39-82`, `GroundMarkerPlugin.java:257`.
- **Blocking constraint.** B5 input (no Alt/middle-click/shift).

#### 22. Color-picker works, but font / profiles config doesn't — [shared-core] + [native-iOS]
- **Problem.** Color config works on iOS (`UIColorPickerViewController`, `PluginConfigViewController.m:143-151`). But font-type config (`FontType`) and the entire Profiles tab (create/clone/import/export/reorder) have no native UI. Profiles uses drag-reorder + `JFileChooser`.
- **Impact.** Medium — font config is niche; profiles are valuable for power users but drag-reorder/file-chooser are hard on touch.
- **Effort.** M for font (additive bridge for font lists + native picker — note the **font sub-screen is unbuilt and muxer reachability unverified, C11**: effort includes a new native sub-screen). L for profiles (native list + reorder + iOS document picker for import/export — partially infeasible, I10).
- **Mechanism.** `ConfigPanel.createFont:714-733`, `FontPanel.java:215-260`, `ProfilePanel.java:91-118`.
- **Blocking constraint.** Profiles import/export needs the iOS document picker (no `JFileChooser`).

#### 23. MultiplexingPluginPanel drill-down parity unverified — [native-iOS]
- **Problem.** Desktop config drills list → config → sub-panel via a `CardLayout` back-stack. Native nav stacks exist but the muxer drill-down (notification sub-panel, font sub-panel) is unconfirmed (Table 1 "unverified"). If incomplete, those sub-panels are unreachable even after #5/#22.
- **Impact.** Medium — sub-panels (notification override, font) are reached via the muxer.
- **Effort.** S to verify, M to build the missing sub-screens.
- **Mechanism.** `MultiplexingPluginPanel.java:53-111`, `NotificationPanel.java`, `FontPanel.java`.
- **Blocking constraint.** None.

#### 24. Chatbox-input plugins depend on the soft keyboard being reliably bound — [shared-core] (verify)
- **Problem.** Music search, Quest List search, Wiki search, Chat Commands, GE search, Bank search, Fairy Rings code search all open the engine `ChatboxTextInput` and need the soft keyboard wired (contextual auto-raise polling `isTextInputOpen`, B1 §6). "works" for Music/Quest List, "partial/unverified" for several.
- **Impact.** Medium — search is the entry to a lot of plugin functionality; an auto-raise misfire makes that plugin's search unreachable.
- **Effort.** S to verify each — the mechanism already exists.
- **Mechanism.** Shared `JavaBridge.isTextInputOpen` (`:1197`), `MusicPlugin.java:124,379`, `QuestListPlugin.java:116`.
- **Blocking constraint.** None; keyboard has no arrow/function keys but text+enter+backspace suffices for search.

#### 25. Plugin "couldn't start (incompatible)" plugins still listed as toggleable — [shared-core] + [native-iOS]
- **Problem.** Native shows "✕ couldn't start (incompatible)" after a 4s grace for native-lib/GPU plugins that can't load on iOS (`ViewController.m:881-896`). But GPU, Discord, Developer Tools, Stretched Mode etc. remain in the list and toggleable → dead toggles.
- **Impact.** Medium — user confusion (toggling does nothing / errors). GPU specifically is shelved on iOS (B5 #11) but appears.
- **Effort.** S — a shared "incompatible on this platform" flag (extend the existing grace/status detection) to hide or disable known-incompatible plugins.
- **Mechanism.** Shared `MobileUiBridge.listPlugins` (`:773`); native status row `ViewController.m:881-896`.
- **Blocking constraint.** B5 #11 (iOS GPU shelved), #1 (list policy, not a plugin edit).

#### 26. FPS limiting for battery is available but not surfaced — [shared-core] (FPS Control) + [native-iOS] (renderH)
- **Problem.** FPS Control's limit is config-driven; the `renderH` dial trades FPS/sharpness/UI-size but is a hardcoded magic constant (`renderH=540`, `ViewController.m:1538-1542`) with no in-app tuning. On a phone, capping FPS materially extends battery/reduces heat.
- **Impact.** Medium — battery/thermal is a real mobile concern; a simple FPS cap toggle is high-value-per-effort.
- **Effort.** S to surface FPS Control's existing config; M to expose `renderH`/UI-size as a native setting.
- **Mechanism.** `FpsPlugin` config; `renderH=540` (`ViewController.m:1538-1542`).
- **Blocking constraint.** None.

#### 27. Wiki / XP Tracker / Screenshot external links break (LinkBrowser → desktop browser) — [shared-core] + [native-iOS]
- **Problem.** Wiki "Wiki"/"DPS" → `LinkBrowser.browse`, XP Tracker "Open Wise Old Man", Screenshot "open folder" → `LinkBrowser.open`. On mobile these need routing to `SFSafariViewController`/Safari; folder-open has no analogue.
- **Impact.** Medium — Wiki integration is high-traffic; orb+chatbox search work, but link-out is broken.
- **Effort.** M — intercept `LinkBrowser` at a shared seam and forward the URL to native; drop folder-open or route to Photos.
- **Mechanism.** `LinkBrowser` (used by Wiki, XP Tracker, Screenshot, Info Panel); shared interception point.
- **Blocking constraint.** B5 #1 (don't fork plugins) — must intercept `LinkBrowser` centrally, not edit each plugin.

---

### Infeasible / accept-as-is (desktop perks that cannot be made mobile-friendly under our constraints)

- **I1. Overlay/profile resize via hover-cursor edges — INFEASIBLE.** Resize is doubly hover-cursor-dependent: `mouseMoved` sets a global resize cursor that `mousePressed` reads (`OverlayRenderer.java:419-477`). Touch has no hover and no cursor. No gesture reconstructs "hover an edge to arm a resize handle." Accept overlays at default/saved size; reposition (#11) is feasible, resize is not. *(B5 — no hover/cursor.)*
- **I2. General configurable hotkeys / HotkeyButton binding UI — INFEASIBLE.** `Keybind` matches AWT keycode + modifier mask; `HotkeyButton` records a binding by capturing a physical key. With no hardware keyboard and no synthesized VK/modifier events, there is no way to *record* or *fire* an arbitrary keybind. Per-action native buttons (#13) are the only substitute; the generic registry is infeasible (C9). *(B5 input.)*
- **I3. Custom Cursor / Mouse Tooltips / Interface Styles cursor behaviors — INFEASIBLE (hide them).** No OS cursor on touch. Custom Cursor is meaningless; Mouse Tooltips is redundant with the press-and-peek reticle. No-op/hide, don't adapt. *(B5 — no cursor.)*
- **I4. Middle-click / extra-mouse-button camera & menu (Camera remaps) — INFEASIBLE.** RMB→MMB / MMB→RMB remaps and `setCameraMouseButtonMask(BUTTON2|...)` require buttons touch lacks. The zoom-limit half of Camera works; the button-remap half is permanently dead. *(B5 input.)*
- **I5. WASD/F-key camera & "Press Enter to Chat" (Key Remapping) — INFEASIBLE.** Intercepts hardware WASD/number/F-keys; nothing to intercept on touch. Out of scope; keep off by default. *(B5 input.)*
- **I6. Anti Drag default mode — INFEASIBLE in default config.** Default (`onShiftOnly=true`) needs Shift held; even if shift were fed (#9), it tames *mouse-drag* of inventory items and touch inventory-drag semantics differ entirely. Mark broken/accept-as-is. *(B5 input.)*
- **I7. Stretched Mode — INFEASIBLE (exclude).** Rescales the render surface + registers mouse-coord-translating listeners, conflicting with the iOS CPU renderer's own scaling (B5 #11) and the touch pipeline. Must not ship.
- **I8. GPU plugin on iOS — INFEASIBLE (shelved, immovable).** iOS is GLES-3.0-only with EAGL z-fight and ANGLE wedges; the CACIO CPU renderer is the pixel-accurate shipped path. 117HD is structurally impossible on iOS ES-3.0. Do not ship GPU on iOS. (Android is the HD home.) *(B5 #11, #13, #14, #15.)*
- **I9. System tray / taskbar / shell-out notifications — INFEASIBLE (replace, don't port).** `TrayIcon`, taskbar flash, `notify-send`/`osascript`/`terminal-notifier` have no sandbox analogue. The only path is a *replacement* native layer (#4), not a port. The canvas `processFlash` is the one channel that survives unchanged. *(B5 #23.)*
- **I10. Config profiles drag-reorder + JFileChooser import/export — partially INFEASIBLE.** Drag-reorder of profile cards and `JFileChooser` have no direct analogue. Reorder via a native reorderable list and import/export via the OS document picker make it adaptable-but-L; the desktop interaction model itself does not port. Accept a reduced native profiles UI at best.
- **I11. Screen Markers free-form drag-rectangle creation — INFEASIBLE as-is.** Free-form drag-to-draw on top of camera/peek gestures has no clean touch home and is low-value on a relocated-widget HUD. Omit or full native reimplementation only if ever justified.
- **I12. Developer Tools, Discord RPC, Account OAuth — INFEASIBLE / inert.** Developer Tools spawns separate Swing frames (dev-only); Discord RPC/IPC is a desktop-process integration likely inert; Account's RL/Google OAuth is desktop-oriented and low-value. Exclude Developer Tools; accept Discord/Account as inert. *(B5 #23.)*

---

## Phased plan (iOS)

Milestones grouped quick-wins → larger reworks. Each item tagged **[shared-core]** (additive bridge Java; re-run `tools/shared-contract/check-contract.sh --update` + desktop OpenJDK 21 headless smoke + re-census both ports after each) or **[native-iOS]** (UIKit). The shared-core items should land **first** so both ports amortize them. **Sequencing rule (B5 #28):** never run layout/veneer work and any GPU experiment concurrently in the shared tree — irrelevant on iOS (GPU shelled) but holds if the `gpu` branch is ever revisited.

### iOS-P0 — Parity & polish (S, no blocking constraint)
- **"Tap to play" front screen** gated on `isDebuggerAttached`; relabel/hide the debug panel (Finding #1) **[native-iOS]** — *bounded by B5 #16: the StikDebug attach step stays; only the post-attach screen is polished.*
- **Reset `engineChatRelocated`** on safe-area/trait change (Finding #2) **[native-iOS]**
- **Audit Dynamic-Island top-center occlusion** in landscape (gap; the engine top-center widgets/overlays sit where nothing is relocated) **[native-iOS]**
- Broad verification of chatbox-input auto-raise across Music/Quest List/Wiki/GE/Bank/Fairy Rings (Finding #24) **[shared-core, verify]**

### iOS-P1 — Native menu + targeting (M; Android already has it)
- **Build the native big-row menu overlay** from `MenuPeekBridge.getSnapshot()` (Finding #8) — net-new UIKit, 44pt rows, reference Android's hand-bias **[native-iOS]**
- **Resolve long-press overload by region** (world → menu; chat rect → filter; overlay rect → edit) — see OQ3 **[native-iOS]**

### iOS-P2 — Bridge-amortized features (consume shared-core below)
- **Screenshot → Photos** button (Finding #3 native half) **[native-iOS]**
- **Notification delivery** via `UNUserNotificationCenter` + permission prompt + tap-to-open (Finding #4) **[native-iOS]** — *foreground-only; AFK value near-zero (B5 #16).*
- **Pin/star UI + sort** (Finding #6 native); **incompatible-plugin hide** (Finding #25 native) **[native-iOS]**
- **Config editor: dimension/set/notification controls** incl. a new native notification sub-screen (Finding #5) **[native-iOS]**
- **Hub install progress UI** (Finding #17 native) **[native-iOS]**
- **`LinkBrowser` → SFSafariViewController** (Finding #27 native half) **[native-iOS]**

### iOS-P3 — Larger reworks
- **Native minimap reconstruction** over the retained live click-layer (Finding #14) **[native-iOS, XL]** — *blocked by B5 #4/#6/#10; from-scratch redraw, fidelity-risk flag.*
- **Overlay edit-mode** gesture + snap targets + reset-via-long-press (Finding #11 native) **[native-iOS, L]**
- **Per-panel native reflows** for Loot Tracker / XP Tracker / GE (Finding #12 native) **[native-iOS, per-panel L]**
- **Keyboard occlusion reflow** + numeric-keypad for amount/PIN entry **[native-iOS]**
- **Veneer↔overlay anchor reconciliation** (Finding #18) **[shared-core]**
- **Infobox long-press menu** (detach/flip/delete) (Finding #20) **[native-iOS]**

### iOS-P4 — Gated on the shift spike (do the spike FIRST — OQ1)
- **Shift-state feasibility spike** (Finding #9): can a synthetic `VK_SHIFT` KeyEvent reach the engine's `isKeyPressed` backing without forking? **This single answer gates Findings #9/#10/#16/#21-marking + overlay "Configure."** If NO → these stay infeasible; do not build the Sticky-Shift UI. **[shared-core spike]**
- If GREEN: additive `setShiftHeld` + native sticky-"Shift" button + peek integration **[shared-core] + [native-iOS]**

### iOS-P5 — Accept-as-is / hide
- **Mobile default-profile** (Low Detail on; cursor/key/stretched/styles/devtools off) (Finding #7) **[shared-core]**
- **Hide** Custom Cursor / Mouse Tooltips / Interface Styles (I3, #18) **[native-iOS]**

### SHARED-CORE prerequisites (land before the iOS-P2/P3 native halves)
- Screenshot PNG getter on `DrawManager`/bridge (Finding #3)
- `Notifier` notify-publish hook (Finding #4)
- `pinnedPlugins` read/`setPinned` write + incompatible-platform flag (Findings #6, #25)
- Config schema: dimension/set/notification value-encoding (Finding #5)
- Mobile default-profile boot policy (Finding #7)
- `LinkBrowser` central interception → publish URL (Finding #27)
- Hub install-progress getter (Finding #17)
- Overlay position/persist driver + snap-math wrapper (Finding #11)
- Infobox-action-by-point bridge (Finding #20)
- Per-panel data bridges (Loot/XP/GE/Time, Finding #12)
- Engine touch-drag→widget-drag routing — *verify-first* (Finding #15)

---

## iOS-vs-Android divergence

What iOS must do natively that Android also needs (shared shape, separate code), and where they genuinely differ. The Java seam is shared (B4); everything a user sees/touches is re-implemented per platform — UIKit/Obj-C on iOS, Compose/Views on Android — so every "native" item is **2× labour with zero code reuse across the boundary.**

- **C1 — JIT launch ceremony (the defining divergence, B5 #16 vs #18).** iOS requires a StikDebug attach **every launch**; Android grants JIT free. **Android can have a true one-tap cold-start-to-play; iOS structurally cannot.** Finding #1 (iOS play screen) is the best iOS can do — a polished *post-attach* screen. This is the single biggest UX gap between the ports and must be documented for the (single) user.
- **C2 — iOS-ahead: native menu, keyboard button, chat scroll.** iOS uses the engine's own small menu while Android draws a real native big-row `MenuOverlayView` → **iOS is behind** here (Finding #8). Conversely, iOS has a manual `⌨︎` keyboard-summon button + accessory bar and chat-scroll-by-drag (`chatScrollAccum`); **Android lacks both** (it relies purely on auto-raise and has no chat-scroll — `nativeTouch` plumbed but unused). Net: the ports each lead in different places; neither is uniformly ahead.
- **C3 — Logout: iOS-ahead.** iOS has an explicit Log out that deletes `tokens.json` + purges Jagex/runescape cookies (verified `ViewController.m:610`). Android only offers re-login (overwrites tokens). Both bounded by B5 #24 (per-platform accounts never shared) and #25 (JX_* snapshotted pre-VM → logout forces a process restart on next login).
- **C4 — Color config: iOS-ahead (C1 correction).** iOS uses a real `UIColorPickerViewController`; Android renders color **read-only** (`ConfigScreen.kt:55,123`). The matrix "works on both" for color is wrong — Android needs a Compose color picker as in-scope work.
- **C5 — Single-VM (iOS) vs dual-VM (Android).** iOS embeds one HotSpot; bridge calls are direct. Android runs a *second* in-process HotSpot via JNI and marshals strings/`int[]` across ART↔HotSpot on an `rl-bridge` thread → latency/ANR-risk UX implications iOS doesn't share. Any new bridge method must be additive on both, but Android pays the marshalling tax.
- **C6 — Engine touch-drag.** Neither shell currently routes a drag into an engine widget (Finding #15, C2): iOS calls `onTouch` only for camera/scroll; Android never calls `onTouch` at all. Prayer/Spellbook/Bank-tag reorder is unreachable on both until wired — but the *fix* is per-shell native.
- **C7 — Device verification.** iOS is device-confirmed (iPhone 17 Pro Max). Every Android "works" is **emulator-GREEN only** — pinch, IME, haptics, surface-recreate, `dlopen`-from-filesDir are all unverified on real hardware. iOS findings build on confirmed ground; Android findings build on emulator ground.
- **C8 — GPU/HD.** iOS GPU is shelved and immovable (ES-3.0 ceiling). Android is the designated HD home (native GLES3.1+compute; AG1b shader gate cleared on a real Mali-G715) — but has **no live GPU present path yet** (`rc_AWT_swapBuffers` is a no-op; matrix "partial (smoke-only)" overstates it → functionally missing as a renderer, C3). The HD divergence is the strategic reason Android exists.
- **Shared-by-need (both must build natively):** the play screen, native menu, screenshot save, notification layer, pin/star, dimension/set/notification config controls, panel reflows, minimap reconstruction, overlay edit-mode. Push the *logic* into additive shared bridges; build the *surface* twice.

---

## Open questions / risks (decide before implementation)

**OQ1 (BLOCKER — decide first). Is `client.isKeyPressed(KC_SHIFT)` settable without forking?** ✅ **RESOLVED GREEN (2026-06-08, `ShiftFeedSpike` on live engine):** a single synthetic held `KEY_PRESSED VK_SHIFT` posted to the gamepack Canvas (via the existing system-EventQueue path our text bridge uses) flips `isKeyPressed(KC_SHIFT)` true; `KEY_RELEASED` flips it back — clean sticky-shift, **no engine fork**. Findings #9/#10/#16/#21-marking + overlay "Configure" are **cleared to build** via an additive `JavaBridge.setShiftHeld(boolean)`. *(Original concern below, kept for record.)* Verified getter-only (`Client.java:1949`), no setter; the engine shift state is fed by the engine's own canvas KeyListener, not `KeyManager` — but that listener receives our posted KeyEvents, which is why the spike passed.

**OQ2. Portrait support — yes or no?** iOS is landscape-only (`Info.plist:34-40`); official Jagex Mobile supports portrait (the natural one-handed orientation). The entire HUD veneer + the `topLevelId==164` layout guard are landscape-shaped. Decide before any reachability/scale rework — it changes the HUD layout, thumb-reach map, and the orb/minimap sizing story.

**OQ3. Long-press is overloaded — how is it disambiguated?** Right-click menu, chat-filter menu, infobox menu, overlay-edit-mode (#11), and plugin-row config all want long-press. Needs by-region routing (chat rect → filter; overlay rect → edit; world → menu) or an alternative gesture for some. Affects #8, #11, #20.

**OQ4. Expand the gesture vocabulary, or keep it minimal?** Two-finger-tap as a faster right-click, double-tap, edge-swipe for dock/chat, a modifier *gesture* as a Shift alternative (relevant to OQ1's fallback). More power vs more accidental-trigger risk. Decide before #9/#11 native UX.

**OQ5. Expose `renderH` / UI-scale as a user setting?** Currently a magic `540` constant tuned for one device (iPhone 17 Pro Max). Smaller phones / iPad are untested. Decide whether to ship a UI-scale slider, and whether draw-distance is a separate perceived-perf lever (the CPU path clamps scene draw independently of the GPU plugin — worth verifying as a battery/fill lever).

**OQ6. Background behavior policy.** iOS keeps the app foregrounded via a jetsam assertion (battery cost, possible OS reclaim). There is no background JVM, so notifications are foreground-only and the AFK-glance use case is near-dead (Finding #4). Confirm keep-alive (battery/heat) vs accept that background = effectively suspended. Drives notification design.

**OQ7. Is "attach-then-tap-to-play" the accepted shipped UX?** Every iOS launch needs StikDebug attach — immovable (B5 #16). Confirm Finding #1's flow is the accepted ceiling and that the divergence from Android's one-tap launch is documented for the user.

**OQ8. Per-panel native reflow cut-list.** Each rich panel (Loot Tracker, XP Tracker, GE, Time Tracking, Skill Calculator, Notes, Party) is bespoke ×2 ports, and the rendered-image fallback is OOM-prone for tall panels (C10). Decide the priority cut — which panels justify native reflow vs. staying as the (degraded) zoomable image.

**OQ9. Notification permission + tap-routing.** First-run permission-prompt timing, deep-link target on tap, and whether to invest in any iOS background delivery (jetsam-constrained — likely no). Decide before building the native notification half.

**OQ10. Minimap: native reconstruction vs accept-small.** The only real fix is an XL native minimap rebuilt from `HudDataBridge` dots over the retained live click-layer, with a terrain-fidelity tail (B5 #4/#6/#10). Decide whether "make minimap bigger" justifies the XL spend, or accept the small minimap and lean on the `renderH` whole-HUD lever.

**OQ11. Accessibility scope.** No coverage exists for Dynamic Type, VoiceOver (the game is an opaque pixel blit — but native chrome/config could be labeled), color-blind-safe click rings, or reduce-motion. Confirm out-of-scope for the personal build, or pick a minimal subset.

**OQ12. Report Abuse unreachable.** The Report chat tab is dropped on both ports (`chatreport.png` bundled but unused, B1 §13). Confirm this ToS-relevant function being unreachable is accepted.

### Cross-cutting risks
- **R1 (feasibility cascade).** OQ1's answer can invalidate the Sticky-Shift work and four downstream findings — spike before any UI.
- **R2 (architecture).** Shared-core changes are additive-only with no compile-time wall (B5 #27); the `tools/shared-contract` drift-guard + dual-port re-census is mandatory after each shared item or one port silently breaks.
- **R3 (device spread).** All tuning is on one iOS device; renderH/touch-target/reachability conclusions may not generalize to smaller phones or iPad (OQ5).
- **R4 (sequencing).** Layout/veneer work and any revived GPU experiment meet at the single publish seam (B5 #28) — must not run concurrently in the shared tree.
- **R5 (rendered-image OOM).** The Swing-panel fallback blanks on tall panels at 4× on the game-shared heap (C10) — do not treat it as a safe baseline; it reinforces native-reflow priority.
