# Audit result — desktop-parity audit (what iOS still needs to do everything desktop RuneLite does), 2026-06-12

Read-only feature-parity audit of the native iOS app on branch `audit-sec-p0` @ `5aac018f5`. No code changed,
nothing committed. **Lens:** does the iOS app do *everything* desktop RuneLite can do, as a touch-first
small-screen app? This is a different lens from `ios/AUDIT-RESULT-full-app-audit.md` (security/perf/UX/arch) and
from `ios/MOBILE-UX-AUDIT.md` (the earlier feature-inventory matrix, now partly stale — it predates the PiP /
background-keepalive, 117HD-on-iOS, selectable-renderers, and JVM-teardown work and still calls the GPU path
"shelved" and the lifecycle "foreground-only"). Where a parity gap overlaps an existing finding the IDs are
cross-referenced, not restated.

**Method.** A desktop capability checklist (right-bar/nav, plugin mgmt & config across every config type, overlay
management, notifications, screenshots, chat, world map/hopper, camera/zoom, menu-entry swapping, right-click,
login/session, window/display, keybinds, and the gameplay-plugin long tail) was enumerated, the iOS equivalent
located (or its absence confirmed), then **every** `file:line` cite was re-opened and the quote + gap claim
adversarially verified (line numbers corrected, "bridge built but never called" claims re-grepped, invented or
already-at-parity items dropped). Items that need the device to confirm a behaviour are tagged **DEVICE-VERIFY**.
A handful of claims kept at medium confidence are flagged inline.

**Buckets.** Every finding is exactly one of: **(A) IMPROVE** — an iOS feature that works but is worse than
desktop · **(B) MISSING** — a desktop capability with no iOS equivalent · **(C) ADAPT** — a desktop capability
that needs a touch re-presentation of the *same* capability · **⛔ N/A** — a desktop-window concept genuinely
irrelevant on iOS (listed with justification to prove coverage, never silently dropped). No finding invents a
feature desktop lacks.

---

## Executive summary

The unmodified-plugin engineering is strong: overlays render, the engine HUD is relocated natively, tap-to-play
and the right-click long-press menu work, login/OAuth is arguably nicer than desktop, and Quest Helper + World
Switcher are genuinely hand-native panels. The parity gaps cluster in **five themes**, in priority order:

1. **The right bar is the weakest surface and its panel content is the single biggest "doesn't feel like
   RuneLite" issue (P0).** Tapped panels are a non-native supersampled Swing *screenshot* driven by one
   `panelClick(x,y)` that dispatches only a left button — **no typing, no right-click, no dropdowns, no inner
   scroll/drag**. So Grand Exchange search, Notes editing, Hiscore/Skill-Calc numeric entry, and Loot-Tracker/GE
   right-click reset are all view-or-single-tap only. The launcher itself is a 38pt icon-only edge rail with no
   labels, no search, no scroll affordance, that fights the rounded corners. **[§3, RB-1, PANEL-1]**
2. **A whole class of client settings is structurally unreachable.** `listPlugins()` iterates only
   `pm.getPlugins()`, so the desktop "RuneLite" core-settings fake-plugin (overlay fonts, tooltip position,
   infobox size, notification defaults, wiki prices) and the "Chat Color" fake-plugin (`textrecolor`) **never
   appear** on iOS — and notification config collapses to a single on/off toggle. **[CORE-1, CHAT-1, NOTIF-3,
   NOTIF-2]**
3. **Several fully-built Java bridges have no native consumer.** `OverlayBridge` (487 lines: move / snap / resize-
   setter-missing / reset / `snapPositionForPoint`) and the `InfoBox` long-press menu are wired and *ticking*
   but called by zero ObjC — so overlays can't be moved/snapped/reset on touch at all, and `GrandExchange /
   XpTracker / LootTracker / TimeTracking` bridges exist yet their panels still degrade to the screenshot
   fallback. The hardest half (the touch-friendly API) is already shipped and dormant. **[OVL-2..5, NAV-1, NAV-2]**
4. **World Hopper is a raw world dump.** No favourites, no ping, no quick-hop (the plugin's headline keybind
   feature), no sort, partial filters — strictly worse than the in-game world list for picking a world. **[WH-1..5]**
5. **Input idioms with no touch home.** Right-click *submenus* (8 plugins use them, incl. menu-entry-swapper's
   "Swap left-click ▸") are unreachable; plugin action-hotkeys (Ground Items hide, quick-hop, inventory toggle)
   can't be fired; in-game **item drag** (inventory rearrange, bank-tag assignment — supported by the official
   Jagex app) has no gesture; screen-marker box-drag can't be created. **[MENU-1, KEY-1, DRAG-1, PLUG-1]**

Net: the floor ("real RuneLite runs and renders") is met; the gap is the **reach and editability** of its
configuration, the **management** of what renders (overlays), the **richness** of the panels, and a few
**touch-input idioms** that were never re-housed. Most fixes are re-housing existing capability for touch (A/C),
and a surprising number are *native-UI-only* because the Java side is already built.

### Parity scorecard

Legend: ✅ parity · 🟡 partial (A, IMPROVE) · ❌ missing (B) · 🔧 needs touch adaptation (C) · ⛔ N/A on iOS

| Capability | Status | Refs |
|---|---|---|
| **Right bar / plugin navigation** | | |
| Nav buttons present, priority ordering | ✅ | NAV-8 |
| Nav icons labelled / tooltips | 🔧 | NAV-3 |
| Nav-button right-click popups | 🔧 | NAV-4 |
| Action-only navs (Discord, RuneLite account) | ❌ | NAV-5 |
| Panel **content** interaction (type / right-click / dropdown / inner-scroll / drag) | 🟡 **P0** | RB-1, PANEL-1, PANEL-2 |
| Native (reflowed) panels — Quest Helper, World Switcher | ✅ | — |
| Native panels — Grand Exchange, XP, Loot, Time Tracking | 🟡 | NAV-1 |
| **Plugin management & config** | | |
| Plugin list, enable/disable | ✅ | — |
| Per-plugin config: boolean / color / enum-set | ✅ | — |
| int (bounded), double, string editing | 🟡 | CFG-7, CFG-9 |
| FontType picker | ❌ | CFG-2 |
| Keybind rebinding | 🔧 | CFG-8 |
| Notification config (sound/flash/focus/…) | 🟡 | NOTIF-2, NOTIF-1 |
| Plugin-list search / category tags | ❌ | CFG-5 |
| Collapsible sections + `closedByDefault` | 🟡 | CFG-6 |
| Whole-plugin Reset | 🟡 | CFG-10 |
| Favourites / pin | ✅ | — |
| Plugin Hub install | ✅ | — |
| Config profiles | ❌ | CFG-3 |
| Cloud config sync | ❌ | CFG-4 |
| RuneLite **core** settings group + Chat Color | ❌ | CORE-1, CHAT-1 |
| **Overlays** | | |
| Overlays render (snap-frame mobile-aware) | ✅ | OVL-1 |
| Reposition (drag) | 🔧 | OVL-2 |
| Snap-to-corner | 🔧 | OVL-3 |
| Resize | 🔧 | OVL-4 (no Java setter yet) |
| "Configure" / reset menu | 🟡 | OVL-5 |
| Layers / priority | ✅ | OVL-6 |
| InfoBox long-press menu (Detach/Flip/Delete) | ❌ | NAV-2 |
| **Notifications** | | |
| Banner delivery | ✅ | — |
| Sound type / volume / OFF honored | 🟡 | NOTIF-1 |
| In-canvas screen flash | ✅ | NOTIF-NA-1 |
| Global notification defaults | ❌ | NOTIF-3 |
| Window flash / taskbar / force-focus | ⛔ | NOTIF-NA-1 |
| **Screenshots** | | |
| Manual capture | 🟡 (omits veneers) | SHOT-3 |
| Auto-on-event → Photos | ❌ | SHOT-2 |
| Capture hotkey/gesture | 🔧 | SHOT-4 |
| Copy to clipboard | ❌ | SHOT-5 |
| **Chat** | | |
| Render / tabs / input / channel pill | ✅ | — |
| Chat Color recolor config | ❌ | CHAT-1 |
| Precise click on chat **message** content | 🟡 | CHAT-2 |
| Report tab | 🟡 (deliberate) | CHAT-3 |
| Chat Filter / Chat Commands plugins | ✅ | — |
| **World map / hopper / camera** | | |
| Map pan / pinch-zoom / search | ✅ | — |
| Map close (native control) | 🟡 | WM-1 |
| Map zoom +/- buttons | 🔧 | WM-2 |
| Map icon tooltips | ❌ | WM-3 |
| Double-tap / momentum pan | 🟡 | WM-4 |
| World list (basic) | ✅ | — |
| Favourite worlds | ❌ | WH-1 |
| Ping column / sort | ❌ | WH-2 |
| Quick-hop next/prev | ❌ | WH-3 |
| List sort | 🟡 | WH-4 |
| Region / world-type filter | 🟡 | WH-5 |
| Camera rotate (drag) + zoom (pinch) | ✅ | CAM-1 |
| Camera config discoverability | 🟡 | CAM-1 |
| Mouse-binding camera config | ⛔ | CAM-NA-1 |
| **Menu / right-click / login** | | |
| Tap-to-play left-click resolve | ✅ | — |
| Long-press right-click (flat menu) | ✅ | — |
| Right-click **submenus** | ❌ | MENU-1 |
| Long menu scroll (>14 entries) | 🟡 | MENU-2 |
| Shift-gated entries (discoverability) | 🟡 | MENU-3 |
| Login / OAuth / character picker | ✅ | LOGIN-3 |
| Account / character switch (reuse session) | 🟡 | LOGIN-1 |
| In-app logout (real session) | 🟡 | LOGIN-2 |
| Session / world status chip | ❌ | LOGIN-4 |
| **Window / display / keybinds / long-tail** | | |
| Interface scale / game size control | 🔧 | DISP-1 |
| Plugin action-hotkeys | 🔧 | KEY-1 |
| KeyRemapping (WASD / F-keys) | ✅ native equiv | KEY-2 |
| Ctrl / Alt modifier latch | 🔧 | KEY-3 |
| In-game **item drag** | 🔧 | DRAG-1 |
| Screen-marker drag-to-create | 🔧 | PLUG-1 |
| DevTools floating JFrame | ❌ (likely wontfix) | PLUG-2 |
| Resize modes / fullscreen / chrome / tray / opacity / always-on-top | ⛔ | WIN-NA-1..4 |
| Sidebar/panel toggle keys, block-extra-mouse-buttons | ⛔ | KEY-NA-1/2 |

---

## Findings

> Severity: **P0** blocks "feels like real RuneLite" · **P1** major · **P2** polish. Effort: S/M/L. Bucket:
> A improve / B missing / C adapt. Cites verified against `audit-sec-p0` @ `5aac018f5`.

### Area 1 — Right bar / plugin navigation
*(The right-bar **redesign** is its own dedicated section, §3. These are the contents-parity facts.)*

**NAV-1 (A · P1 · L) — Native bridges for Grand Exchange / XP Tracker / Loot Tracker / Time Tracking exist but
aren't wired to a native screen; these panels degrade to the Swing screenshot.**
- Desktop: real interactive panels — `xptracker/XpTrackerPlugin.java:147` `.tooltip("XP Tracker")` `.panel(xpPanel)`;
  `grandexchange/GrandExchangePlugin.java:269`/`:272`; `loottracker/LootTrackerPlugin.java:595`/`:598`;
  `timetracking/TimeTrackingPlugin.java:148`/`:150`.
- iOS: bridges are init'd + ticking (`MobileUiBridge.java:263-266`, `:1435-1438`) and each exposes `isAvailable()`
  + `get*Json()` (`GrandExchangeBridge.java:88`/`:103`, `XpTrackerBridge.java:87`/`:102`,
  `LootTrackerBridge.java:97`/`:112`, `TimeTrackingBridge.java:105`/`:120`) — but `PluginPanelsViewController.m:491-507`
  routes **only** "Quest Helper" (`:493`) and "World Switcher" (`:500`) to native VCs; no GE/XP/Loot/Time VC or
  JVMLauncher JNI accessor exists (grep: only doc comments). A tap opens the screenshot, not a native screen.
- Why: these are the most-used panel plugins (XP/hr, GE offer tracking, drop logs, farm/bird-house timers). The
  Java half ships; the panels look "done" architecturally but degrade to a non-typable, hard-to-read zoomable image.
- Fix: mirror the QuestHelper/WorldSwitcher pattern — 4 JVMLauncher JNI accessors over the existing bridge methods
  + 4 lightweight native VCs (table/segmented like WorldSwitcher), routed from the tip-dispatch at `:491-507`. Note
  `GrandExchangeBridge` is currently **read-only** (offer-slot data; no item search / price-set / place-offer), so a
  native GE *creation* screen still needs the raw engine GE interface via taps. *[completeness: GE-create]*

**NAV-2 (B · P2 · M) — InfoBox long-press menu bridge is built and ticking but no native code calls it.**
- Desktop: `InfoBoxManager.java:68-70` `Detach`/`Flip`/`Delete`, dispatched `:125-136`; bridge reproduces it,
  `InfoBoxBridge.java:147` emits `"menu":["Detach",…]`.
- iOS: init'd (`MobileUiBridge.java:268`) + ticked (`:1440`), exposes `isAvailable()`/`getInfoBoxesJson()`/
  `invokeInfoBoxMenu(int,String)` (`:129`/`:150`/`:164`) — grep of the shell for `infobox|getInfoBoxes|invokeInfoBoxMenu`
  returns **zero** hits.
- Why: infoboxes are core (timers/counters); their long-press menu doesn't exist on device.
- Fix: wire JVMLauncher JNI to the bridge, trigger from a long-press on an infobox hit-region reusing the existing
  MenuPeek plumbing (`JVMLauncher.m:220 ensureMenuPeek`).

**NAV-3 (C · P1 · S) — The rail shows no label/tooltip; the plugin name appears only after you open the panel.**
- Desktop: `NavigationButton.java:50-53` tooltip; `ClientUI.java:254` `sidebar.insertTab(null, icon, …, navBtn.getTooltip(), …)`.
- iOS: `PluginPanelsViewController.m makeIconButton (:220-238)` sets only an image (or a single-letter fallback `:232`);
  the tooltip is used only for the *open* panel's title (`:522`). No `accessibilityLabel`, no long-press (grep: zero).
- Why: at 38pt several icons (XP Tracker, Time Tracking, Loot Tracker, Notes, Party) are ambiguous; desktop always
  answers "what is this?" via hover. On touch you blind-tap to discover.
- Fix: set `accessibilityLabel = tooltip` (free VoiceOver) + a long-press name bubble, or a caption under each icon.
  *(Subsumed by the §3 redesign, which puts a label on every tile.)*

**NAV-4 (C · P2 · S) — Nav-button right-click popups are entirely unported.**
- Desktop: `screenshot/ScreenshotPlugin.java:220-226` `.popup(…"Open screenshot folder…")`; `ClientUI.java:474-483`
  builds a `JPopupMenu` from `navBtn.getPopup()` on BUTTON3.
- iOS: `NavigationButton.getPopup()` is never read — `listPanels()` (`MobileUiBridge.java:416-432`) emits only
  `{index,tooltip,iconPng}`; no long-press on rail icons.
- Why/Fix: minor; re-house popup entries as a long-press menu on the tile. The Screenshot "open folder" entry is
  itself N/A on the iOS sandbox — surface saved shots via the native screenshot flow instead.

**NAV-5 (C/B · P2 · S) — Button-only navs (RuneLite account sign-in/out, Join Discord, Take screenshot) are silently
dropped.**
- Desktop: `account/AccountPlugin.java:78-88` (sign in/out, no panel), `discord/DiscordPlugin.java:122-125`,
  `screenshot/ScreenshotPlugin.java:217-219` — `ClientUI.java:240-243` routes panel-less navs to real toolbar buttons.
- iOS: `MobileUiBridge.panelNavs() (:403)` keeps only `getPanel() != null` navs, so all three are excluded.
  Screenshot **has** a native equivalent (`ViewController.m:577-585` 📷). Discord *would* route via
  `MobileUiBridge.publishLink (:225)` → SFSafariViewController, **but grep confirms the shell never calls publishLink**
  — no Discord entry point exists. RuneLite-account sign-in (cloud-config session) has none.
- Why/Fix: the panel==null filter makes this decision implicitly. Decide per-button: Discord → an action in the
  plugin-management card calling `publishLink`; RuneLite account → a settings action or formally mark N/A; screenshot
  is covered. *(The §3 redesign re-houses these as grid tiles via a new `getActions()` bridge.)*

**NAV-6 (A · P2 · S · medium) — Developer Tools can surface in the rail with a dev-only Swing panel that can't work
on iOS.**
- Desktop: `devtools/DevToolsPlugin.java:294-297` registers the nav (priority 1) only when `developerPlugin = true (:94)`.
- iOS: `panelNavs() (:398-409)` does **not** filter on `developerPlugin` (the flag is available — `listPlugins`
  emits it at `:968`), so if DevTools is loaded it appears near the top of the rail opening a panel that spawns
  separate frames on desktop. Whether it's in the shipped loaded set is unverified (no dev-mode marker in JVMLauncher.m).
- Fix: exclude `developerPlugin` navs from `panelNavs()`, matching desktop's dev-mode gate. **DEVICE-VERIFY** the loaded set.

**NAV-7 (A · P2 · M) — Configuration master panel is correctly rail-excluded and natively replaced; verify the
replacement reaches its full surface.**
- Desktop: `config/ConfigPlugin.java:93-96` the always-first priority-0 master panel (search + pins + per-plugin config).
- iOS: dropped from the rail (`panelNavs() :403`) and replaced by the native ⚙ plugin-management card
  (`ViewController.m:824`/`:829`/`:1149`) + shared `pinnedPlugins` CSV (`MobileUiBridge.setPinned :999`). The native
  card currently has **no search** over ~250 rows (see CFG-5).
- Why/Fix: exclusion is correct; the parity ledger entry is that the replacement must reach search + pin + per-plugin
  config. Pin is done; search is the residual gap (CFG-5).

**NAV-8 (✅ parity · S) — Rail ordering mirrors desktop priority exactly; only soft gap is no visual grouping.**
- Both sort by `priority` then `tooltip` (`MobileUiBridge.navButtonsSorted() :388-394` == `NavigationButton.COMPARATOR
  :41-42`); every priority value re-grepped and matches (Configuration 0, DevTools 1, XP Tracker 2, World Switcher/GE 3,
  Time Tracking 4, Hiscore/Loot/Screen Markers 5, Skill Calc/Kourend 6, Notes 7, Party 9, Info 10).
- Fix: none (recorded so the redesign doesn't "fix" a non-bug). Optional: subtle separators between priority bands.

**NAV-9 (⛔ N/A · S) — Sidebar collapse/expand toggle, deselect-to-game-focus, selected-tab back-stack.**
- Desktop `ClientUI.java:498-503`/`:419-459`/`:271-281`/`:606-611` exist because the Swing sidebar is a heavyweight
  panel that overlaps the game. The iOS always-on **passthrough** dock (`PluginPanelsViewController.m:23-25`, `:70`)
  makes collapse/focus-handoff/back-stack moot (tap the active icon closes just that panel, `:508-511`). No action.

### Area 2 — Plugin management & config

**CORE-1 (B · P1 · M) — The RuneLite "core settings" group and the "Chat Color" fake-plugin are structurally
unreachable.** *(Root cause shared by CHAT-1 and NOTIF-3.)*
- Desktop registers two **fake plugins**: `config/ConfigPlugin.java:77-81` "RuneLite" / "RuneLite client settings"
  (the `runelite` group: overlay fonts, `tooltipPosition`, `infoBoxSize`, notification defaults, wiki prices, …) and
  `:82-85` "Chat Color" (`ChatColorConfig`, group `textrecolor`). Neither is a `Plugin`.
- iOS: `MobileUiBridge.listPlugins() (:929-973)` and `descriptorForGroup() (:1498-1527)` iterate **only**
  `pm.getPlugins()` — fake-plugins have no `PluginDescriptor`, so their groups never enumerate. The bridge's only
  `runelite`-group touch is the `pinnedPlugins` CSV. No native entry point reaches either group.
- Why: these hold RuneLite's global presentation + notification defaults (the base every non-overridden notification
  reads) and chat recolor — foundational "where are the client settings" gaps.
- Fix: inject the two fake-plugins as synthetic config-only rows (no enable toggle, no PluginPanel) into
  `listPlugins()`/`descriptorForGroup()` via `configManager.getConfig(...)`/`getConfigDescriptor(...)`; the existing
  `PluginConfigViewController` (color picker already works) renders them with zero new UI. Mobile-filter the window-
  chrome subset (opacity/tray/always-on-top/keybinds) — those are the WIN-NA items.

**CFG-2 (B · P2 · S) — FontType config is read-only (no font picker).**
- Desktop: `ConfigPanel.java:397-400` opens a FontPanel; `FontType.java:42` is a **class** (REGULAR/BOLD/SMALL,
  custom `FontTypeSerializer :78`), not an enum.
- iOS: `MobileUiBridge.typeName()` falls to `"unknown"` (isEnum false `:1607`, no case `:1622-1631`) →
  `PluginConfigViewController.m:295-301` renders `(not editable yet)`.
- Fix: add a `font` type case emitting the 3 named constants + their serialized JSON; native shows a 3-way action
  sheet writing back the serialized object. (Cannot reuse the enum editor — it round-trips a constant *name*, but
  FontType serializes as a JSON object.) ⚠️ *The draft's "reuse enum editor" sketch was corrected during verification.*

**CFG-3 (B · P1 · L) — No config profiles (create/switch/clone/rename/delete/import-export).**
- Desktop: `TopLevelConfigPanel.java:85` "Profiles" tab; `ProfilePanel.java` create/rename/clone/export/import;
  `ConfigManager.java:160 switchProfile(...)`.
- iOS: grep finds no config-profile surface; `MobileUiBridge` has no profile method (the only `profile`-labelled code
  is the unrelated LowMemory default seed `:1181-1184`).
- Why/Fix: profiles are how desktop users keep main-vs-alt / PVM-vs-skilling loadouts. Add a bridge surface
  (`listProfiles`/`switchProfile`/create/rename/delete on the client write lane) + a small native profiles screen +
  a `ProfileChanged` event kind so panels re-pull. L because switching reloads plugin/config state. *Single-account
  throwaway use makes this lower urgency — see batch plan (deferred).*

**CFG-4 (B · P2 · L · medium) — No cloud config sync.**
- Desktop: `ConfigClient.java:108`/`:134`, `ConfigManager.syncRemote (:697)`, `ProfilePanel.java:518` per-profile
  sync toggle.
- iOS: grep finds no sync surface; persistence is local (`MobileUiBridge.flushConfig → cm.sendConfig() :364`).
- Why/Fix: gated on CFG-3 (sync is a per-profile flag) + a RuneLite-account session the shell doesn't expose. Defer
  as a fast-follow on CFG-3. *(Medium: ConfigClient reachability in the headless build is unverified.)*

**CFG-5 (B · P1 · M) — The plugin list has no search/filter (desktop has a search box + category-tag suggestions).**
- Desktop: `config/PluginListPanel.java:134` `searchBar = new IconTextField()` (`:135` SEARCH icon), `:159`
  category-tag suggestions (`CATEGORY_TAGS :77-86`), `:262` `PluginSearch.search(...)`.
- iOS: `ViewController.m buildPluginPanel (1048-1147)` builds the table with **no UISearchBar**; `refreshPluginList
  (1236-1270)` just sorts pinned-first then by name. (The Plugin Hub and Quest Helper *do* have search bars; the core
  plugin list does not.)
- Why/Fix: ~130 plugins; scrolling an unfiltered list on a phone is a daily friction point. Add a `UISearchBar`
  (clone `PluginHubViewController.m:51` + `applyFilter :147`) filtering by name/description client-side; optionally add
  `tags` to `listPlugins` JSON for category-tag parity. *[completeness: the category-tag chips are part of this fix.]*

**CFG-6 (A · P2 · M) — Config sections are always expanded; no collapse, `closedByDefault` ignored.**
- Desktop: `ConfigPanel.java:283` honors `closedByDefault` + `toggleSection (:257-265)`; used by ChatFilter /
  MenuEntrySwapper / LootTracker.
- iOS: `PluginConfigViewController.m` renders static grouped headers (`:234-237`), no collapse; the bridge emits only
  the section *name* (`MobileUiBridge.java:1063`), not `closedByDefault`.
- Fix: tappable headers (track per-section collapsed BOOL → 0 rows when collapsed); add `closedByDefault` + section
  description to the bridge JSON. (Ordering is already correct — `ConfigManager :1052-1069`.)

**CFG-7 (A · P2 · S) — int config with `@Range` is a free number-pad, not a bounded stepper/slider.**
- Desktop: `ConfigPanel.java:461-491` `SpinnerNumberModel(value,min,max,1)` + Units suffix.
- iOS: `PluginConfigViewController.m editText (395-420)` opens a plain NumberPad (`:401`), clamps *after* entry
  (`:410-411`), shows no range hint/stepper; Units only in the read-only detail (`:271-272`).
- Fix: when min/max present + small span, show a `UISlider`/`UIStepper` seeded from value with the range + Units in the
  title (bridge already emits min/max `:1088-1091` + units `:1093-1096`). Native-only.

**CFG-8 (C · P2 · M) — Keybind/ModifierlessKeybind config is read-only (no on-screen rebind).**
- Desktop: `ConfigPanel.java:389-392` → `createKeybind (:657-675)` HotkeyButton.
- iOS: `typeName` maps to `"keybind"` (`:1624-1626`) but `PluginConfigViewController.m` has no keybind branch → `(not
  editable yet)` (`:295-301`); not tappable, not resettable (`:327-328` excludes keybind).
- Why/Fix: keybinds still fire with a Bluetooth keyboard and other plugins read them. Add a touch editor: Clear/None +
  a curated key/modifier picker + live capture when a hardware keyboard is connected (UIKey). At minimum Clear-to-None.

**CFG-9 (A · P2 · S) — Multi-line / list-style String config is unusable.** *(completeness critic)*
- Desktop renders String config as a multi-line wrapping `JTextArea` (`ConfigPanel.createTextField`, `setLineWrap(true)`);
  iOS edits String via a single-line `UITextField` in a `UIAlertController` (`PluginConfigViewController.m editText`),
  so newline-delimited lists (ChatFilter Filtered words/regex/names) can't be edited.
- Fix: use a multi-line `UITextView` editor for String items whose value contains newlines (or whose descriptor hints
  a list). Refines CFG-7 / PANEL-1.

**CFG-10 (A · P2 · S) — No whole-plugin Reset.** *(completeness critic)*
- Desktop's ConfigPanel Reset calls `setDefaultConfiguration(config,true)` **and** `plugin.resetConfiguration()` (clears
  non-config state: screen-marker boxes, ground-marker tiles, GE history).
- iOS only has per-key reset (swipe → `mobileResetConfigGroup`; `MobileUiBridge.resetConfig` requires a non-null key),
  so drawn markers / marked tiles can never be cleared on iOS.
- Fix: add a group-wide reset bridge calling both `setDefaultConfiguration(group)` and `plugin.resetConfiguration()`,
  surfaced as a "Reset plugin" row in the config editor.

### Area 3 — Overlays & overlay management

**OVL-1 (✅ parity · S) — Plugin overlays render on iOS, with a mobile-aware snap frame.**
- The 5 overlay passes paint onto the canvas the shell blits (`Hooks.java:394/514/530/552/568` →
  `JVMLauncher.m:1123 getFramePixels`); snap corners are inset by the native safe area + chat-avoid
  (`OverlayRenderer.java:806`/`:811` ← `HudVeneerBridge.getOverlayInsets :276`/`getOverlayChatAvoid :285`). This is the
  baseline that makes the management gaps below worth fixing. No work.

**OVL-2 (C · P1 · L) — No touch way to reposition an overlay; the Java bridge exists with zero native consumer.**
- Desktop: Alt-hold + drag → `OverlayRenderer.java:143/595/603-604`, persisted via `OverlayManager.saveOverlayLocation
  (:360-376)`.
- iOS: `OverlayBridge.setOverlayPreferredLocation(name,x,y) (:125-152)` is fully implemented + ticked
  (`MobileUiBridge.java:267`/`:1439`), **but** grep of `ios/app/HelloShell/` for `OverlayBridge` / all its method names
  returns nothing — no FindClass, no JNI, no edit-mode VC. `setUiOpen` stays false so the snapshot is never built.
- Why: repositioning overlays off the thumb / off the bezel matters *more* on a phone; an entire 487-line bridge is
  dormant. **This is the headline management gap.**
- Fix: build the native "Move overlays" edit-mode the bridge was designed for — `setUiOpen(true)`, read
  `getOverlaysJson()`, draw drag handles over each movable overlay, finger-drag → `setOverlayPreferredLocation` on
  release. Only the JVMLauncher FindClass+methodIDs and a UIKit edit overlay are missing.

**OVL-3 (C · P1 · M) — No touch snap-to-corner; Java snap helpers built and unused.**
- Desktop: `OverlayRenderer.java:258-270` draws snap corners while dragging, `:643-663` snaps on release →
  `setPreferredPosition`.
- iOS: `OverlayBridge.setOverlayPreferredPosition (:158-190)` + `snapPositionForPoint(x,y,w,h) (:234-260)` (a 2×3 grid
  mapper with a comment "so native edit mode can show a snap preview while the finger drags") both exist with **zero**
  ObjC callers.
- Fix: in the same edit-mode (OVL-2), call `snapPositionForPoint` to highlight the nearest of 5 corners; on release →
  `setOverlayPreferredPosition(name,"TOP_LEFT")`. All Java + persistence done.

**OVL-4 (C · P2 · L) — No overlay resize, and no Java setter for it.**
- Desktop: `OverlayRenderer.java:446-475` resize cursors, `:509-588` resize + `minimumSize` clamp; persisted via
  `OverlayManager.saveOverlaySize (:378-394)`.
- iOS: `OverlayBridge.java:322` emits `"resizable"` in the snapshot but there is **no `setOverlayPreferredSize`**
  anywhere (the only `setPreferredSize` is `HeadlessResize.java:121`, a window component). Even with an edit UI, resize
  can't be driven.
- Fix: add `setOverlayPreferredSize(name,w,h)` to OverlayBridge (clamp to `getMinimumSize`, `setPreferredSize`,
  `saveOverlay` on the client thread) **and** corner grab handles in edit mode. Both bridge method + UI missing.

**OVL-5 (A · P2 · M) — The "Configure"/reset overlay menu is reachable but buried and undiscoverable.**
- Desktop: shift adds per-overlay menu entries (`OverlayRenderer.java:188`/`:205`); right-click in managing mode resets
  (`:393-399 resetOverlay`).
- iOS: reachable *indirectly* — latch sticky-Shift (`ViewController.m:561-571`) **then** long-press exactly on the
  overlay → `openEngineMenuAtGameX` injects the engine menu, and the shift-added entries snapshot into the native menu.
  Wholly undiscoverable. Separately, `OverlayBridge.resetOverlay(name) (:193)` is a direct reset that's unused (no ObjC
  caller).
- Fix: in the edit-mode screen (OVL-2), a per-overlay **Reset** button → `resetOverlay(name)` (already built) + list
  the overlay's `getMenuEntries()` as tappable rows so "Configure" is one tap, not a hidden ⇧+long-press combo.

**OVL-6 (✅ parity · S) — Overlay layers & priority are automatic on both sides.**
- `OverlayManager.OVERLAY_COMPARATOR (:66)` + `rebuildOverlayLayers (:274)` (incl. UNDER_WIDGETS→ABOVE_WIDGETS
  promotion `:285-288`) are unmodified shared core; there is no desktop hand-ordering UI to port. No work.

### Area 4 — Notifications & screenshots

**NOTIF-1 (A · P1 · S) — Per-notification sound type/volume/custom-sound is ignored; every banner uses the default
iOS sound.**
- Desktop: `Notifier.java:205-213` switches NATIVE/CUSTOM/OFF; `:472-491` plays a named sound at a volume-derived
  gain; `NotificationSound.java:34-36`.
- iOS: `MobileUiBridge.publishNotification(title,body) (:201-211)` enqueues only `title␟body` — sound/volume fields
  never forwarded; `ViewController.m:3723` hardcodes `c.sound = [UNNotificationSound defaultSound]` for every banner
  (so OFF still beeps, CUSTOM is lost).
- Fix: carry the sound enum + volume hint in the queue; native maps NATIVE/CUSTOM→a bundled `UNNotificationSound`,
  OFF→no sound. Custom user `.wav` has no sandbox source — collapse CUSTOM→the app sound; honor OFF.

**NOTIF-2 (A · P1 · M) — Per-notification config exposes only the enabled toggle.** *(= the config-area duplicate
"CFG-1"; merged.)*
- Desktop: `Notification.java:54-64` (sound/soundName/volume/timeout/gameMessage/flash/flashColor/requestFocus/
  sendWhenFocused); ConfigPanel renders a full sub-editor (`NotificationPanel.java:291-395`).
- iOS: `PluginConfigViewController.m:284-294` is a single UISwitch on `enabled` (`:287` comment "Sound/flash/etc. are a
  future sub-screen"); `onNotificationSwitch (:363-378)` flips only `enabled` and re-serializes — but the bridge
  already round-trips the **whole** Notification JSON (`MobileUiBridge.java:1081`), so only the native editor is missing.
- Why/Fix: a pushed sub-screen reached by tapping the row — enabled + flash mode (enum) + flash color (picker) +
  game-message toggle + sound (map to NOTIF-1). `requestFocus`/`sendWhenFocused`/`timeout` are window concepts → hide.
  Data already round-trips; native-editor-only. Depends on NOTIF-1/NOTIF-3 for the control vocabulary.

**NOTIF-3 (B · P1 · M) — Global notification defaults (RuneLite core) are completely unreachable.** *(Shares CORE-1's
root cause.)*
- Desktop: `RuneLiteConfig.java:53` `notificationSettings` section (sound/volume/timeout/flash/flashColor/focus/
  game-message), surfaced via the "RuneLite" fake-plugin (`ConfigPlugin.java:77-81`). `Notifier.defaultNotification()`
  bases every non-overridden notification on these.
- iOS: never enumerated (CORE-1). The user can't change the default notification sound/flash at all.
- Fix: CORE-1 (inject the fake-plugins), mobile-filtered to the applicable fields (sound/flash/volume/game-message).

**SHOT-2 (B · P1 · M) — Auto-screenshots (level-up, pet, boss kill, clue, collection-log…) never reach Photos.**
- Desktop: `ScreenshotPlugin.java` fires `takeScreenshot(...)` for ~20 events (`:461 SD_PETS`, `:472 SD_BOSS_KILLS`,
  `:528 SD_COLLECTION_LOG`, `:548 SD_LEVELS`) → `ImageCapture.saveScreenshot (:203-253)` writes to `SCREENSHOT_DIR`
  + posts `ScreenshotTaken`.
- iOS: `SCREENSHOT_DIR` is under `~/.runelite` = the app **sandbox**, unreachable from Photos (`RuneLite.java:107/110`);
  grep finds no `ScreenshotTaken` listener and no native screenshot drain. The only Photos path is the manual button
  (`ViewController.m:3765-3768`); the shell even concedes it (`:575-576`).
- Why/Fix: subscribe to `ScreenshotTaken` in a headless bridge, push the PNG/int[] onto a native-drained queue;
  native writes to Photos via `PHPhotoLibrary` (same auth the manual button uses), optionally behind an "auto-save"
  toggle. *Distinct from UX-3 (manual button stale GPU frame).*

**SHOT-3 (A · P2 · M) — Manual screenshot captures only the engine frame; native HUD veneers are missing.**
- Desktop: `ScreenshotConfig.includeFrame` default true; `ImageCapture.addClientFrame (:113-157, :136 clientUi.paint)`
  composites the whole client chrome.
- iOS: `ViewController.m:3766 onScreenshot` uses `latestFrame` = `getFramePixels` (engine canvas **only**). The native
  chat/minimap/orbs/tabs are separate UIKit views over the game (`buildChatTabs :1626-1675`), absent from that buffer.
- Why/Fix: render the visible hierarchy (`UIGraphicsImageRenderer` over `self.screen`) or composite `latestFrame` +
  the veneer layers so chat/minimap/orbs are included. (Auto-screenshots are engine-only by nature — document the
  asymmetry.) Interacts with UX-3.

**SHOT-4 (C · P2 · S) — No configurable capture hotkey/gesture; the desktop hotkey is dead.**
- Desktop: `ScreenshotConfig.java:87-96 hotkey` Keybind → `ScreenshotPlugin.java:193-212` HotkeyListener.
- iOS: only the on-screen 📷 button (`ViewController.m:577-585`); the Keybind item is read-only (CFG-8).
- Fix: treat the camera button as the hotkey's touch equivalent (mark the Keybind N/A) + optionally a long-press /
  3-finger gesture. Mostly an affordance decision.

**SHOT-5 (B · P2 · S) — Copy-screenshot-to-clipboard isn't wired.**
- Desktop: `ScreenshotConfig.copyToClipboard`; `ImageCapture.java:237 clipboard.setContents(...)`.
- iOS: `onScreenshot` only `UIImageWriteToSavedPhotosAlbum`; grep finds **zero** `UIPasteboard` use anywhere.
- Fix: when `copyToClipboard` is on, also set `UIPasteboard.generalPasteboard.image`. Trivial; pairs with SHOT-2/3.

**NOTIF-NA-1 (⛔ N/A · S) — Window flash / taskbar bounce / force-focus.**
- `Notifier.java:187-198` REQUEST/TASKBAR/FORCE → `HeadlessClientUI.java:212-232` no-ops ("no window to focus or
  flash"). The **in-canvas** screen flash is a *separate* engine effect that DOES still work on iOS (`Hooks.java:401
  notifier.processFlash`), so only the window-bounce variants are N/A. Optional: a `UINotificationFeedbackGenerator`
  haptic as a loose stand-in.

### Area 5 — Chat

**CHAT-1 (B · P1 · M) — Chat Color (`textrecolor`) recolor config is unreachable.** *(Shares CORE-1's root cause.)*
- Desktop: `ConfigPlugin.java:82-85` registers "Chat Color" as a fake plugin; `ChatColorConfig.java:29
  @ConfigGroup("textrecolor")` is core Config, not a Plugin.
- iOS: `listPlugins`/`descriptorForGroup` scan only `pm.getPlugins() (:938/:1506)`, so `textrecolor` never appears;
  the config editor only opens from the plugin-list "Configure" path. Colors still **render** (engine reads saved
  config) but can never be **changed** on-device.
- Fix: CORE-1's fake-plugin allowlist; the existing `PluginConfigViewController` color picker renders it with no new
  UI. (Chat Filter + Chat Commands are real `@PluginDescriptor` plugins and configure fine.)

**CHAT-2 (C · P1 · M) — Tapping a chat *message* line walk-rings instead of a precise click; clickable chat content is
degraded.**
- Desktop: the chatbox is a widget plane — a left-click continues dialogue / clicks names / follows links. The
  relocated iOS chat **is** the real engine chat (`HudVeneerBridge.java:45-46`).
- iOS: only the **tab row** gets a precise UI click (`ViewController.m:2551 chatHitCanvas`; `onTap :3144-3149
  postClickPrecise`). A tap on a message line falls through to `postClickX (:3153-3158)` = world-snap + walk-ring. The
  142px message area (`kChatContentCanvasH=142 :293`) is in `chatScrollCanvas` but that's used only for finger-scroll,
  never a precise tap (grep: exactly two precise-tap sites — world-map `:3137` + tab-row `:3147`).
- Why/Fix: "click to continue", clicking a player name, or a chat link can mis-resolve to the world behind the
  transparent chat. Extend the precise-UI rect to the whole open chatbox: when expanded and the tap lands in
  `chatScrollCanvas`, route through `postClickPrecise` (no ring, no world-snap) like the tab row. Right-click already
  works via long-press; only the left-click default needs this. **DEVICE-VERIFY**.

**CHAT-3 (A · P2 · S · medium) — The Report Abuse chat tab is dropped from the native pill (7 tabs, no Report).**
- Desktop: the bridge knows all 8 tabs incl. Report (`HudVeneerBridge.java:66-71 CHAT_TAB_IDS/NAMES … REPORTABUSE`;
  `switchChatTab` documented "0..7 … + Report" `:243-245`).
- iOS: the veneer hard-codes 7 tabs and **deliberately** drops Report (`ViewController.m:291 kChatTabCount=7`, `:298`
  7-name array, `:287` comment "NO Report — the official app doesn't show it"). With engine stones hidden
  (`:2111`), there's no on-screen tab path to Report.
- Why/Fix: this is an intentional parity-with-the-official-app choice, so a chat chip is the wrong place. Confirm the
  engine's player right-click "Report" reaches the long-press menu and **document** the tab omission as deliberate,
  or add Report to a side-grid overflow. *(Medium: that the right-click Report reaches the menu is an inference —
  engine menu content is data-driven. DEVICE-VERIFY.)*

### Area 6 — World map · world hopper · camera/zoom

**WH-1 (B · P1 · M) — World Switcher has no favourites (no star, no favourite-sort, no add/remove).**
- Desktop: `WorldSwitcherPanel.java:208-213` floats favourites to the top; `WorldHopperPlugin.java:361-373`
  add/removeFromFavorites + favourite varbits `:378-382`.
- iOS: `WorldSwitcherBridge.java:195-214` JSON has no `favourite` field; `PluginPanelsViewController.m:1067-1099` shows
  no star/swipe-to-favourite (the World Switcher VC lives *inside* PluginPanelsViewController.m).
- Why/Fix: favourites keep your 2-4 go-to worlds one tap away; without them every hop is scroll-and-hunt. Add
  `favourite` to the bridge JSON + `addFavourite/removeFavourite` (reflecting `setFavoriteConfig`/`clearFavoriteConfig`);
  native: a tappable star + a pinned Favourites section. Purely additive.

**WH-2 (B · P1 · M) — World Switcher shows no ping (no ping column, no ping sort).**
- Desktop: `WorldSwitcherPanel.java:145-161 updatePing` + sortable ping header (`:337-338`, `:349`);
  `WorldHopperPlugin.java:963-968 ping(...)`.
- iOS: bridge JSON has no `ping` (grep `ping` in `WorldSwitcherBridge.java` = 0).
- Why/Fix: ping is half the reason to use the switcher. **Load-bearing caveat (verified):** `pingInitialWorlds (:841)`
  and `pingNextWorld (:865)` both early-return on `!config.showSidebar()`, and there's no Swing sidebar on iOS — so
  pings are never collected even if exposed. The fix must drive the ping executor (force `showSidebar`/ping on, or kick
  from `setUiOpen`) **then** add a ping label + low-ping-first sort. ICMP `Ping.java` likely falls back to TCP RTT —
  **DEVICE-VERIFY**.

**WH-3 (C · P1 · S) — Quick-hop next/previous (the signature one-action hop) has no touch path.**
- Desktop: `WorldHopperConfig.java:48-62 previousKey/nextKey` (Ctrl+Shift+Left/Right) → `WorldHopperPlugin.java:173-188`
  HotkeyListeners → `hop(boolean) (:531-669)`.
- iOS: grep finds no quick-hop path; `WorldSwitcherBridge.java:243` reflects only `hop(int.class)`, never
  `hop(boolean)`.
- Why/Fix: keybind-driven quick-hop *is* the plugin to most players (skilling/PK rotations). Reflect `hop(boolean)` +
  add two on-screen "hop ◀ / ▶" buttons (panel header or a tiny optional HUD pair). Effort S — the engine logic exists.

**WH-4 (A · P2 · S) — World list can't be ordered (no sort by World / Players / Activity / Ping).**
- Desktop: `WorldSwitcherPanel.java:301-326 orderBy(...)` with sortable headers.
- iOS: `PluginPanelsViewController.m:1024-1044 applyFilter` only filters; bridge emits raw `WorldResult` order
  (`WorldSwitcherBridge.java:187`).
- Fix: a small segmented/menu sort control toggling asc/desc; sort `self.filtered` client-side (players/activity/id
  already in JSON; no bridge change for the first three).

**WH-5 (A · P2 · M) — World filters incomplete (no region US/UK/AUS/GER or world-type filter).**
- Desktop: `WorldHopperConfig.java:130-150 regionFilter + worldTypeFilter`; `RegionFilterMode.java:34-44`. The region
  filter also constrains quick-hop (`WorldHopperPlugin.java:567`/`:600`).
- iOS: the only filter is `@[@"All",@"Members",@"Free",@"PVP"]` (`PluginPanelsViewController.m:971`); `location` is
  display-only, `types` is in JSON but unused by `applyFilter (:1029-1033)`.
- Fix: region chips (US/UK/AUS/GER) + optional world-type chips, filtered client-side; persist as the worldhopper
  config keys so they also scope any quick-hop (WH-3).

**WM-1 (A · P2 · S) — World map close is slow/indirect (tap-through to the engine X, no native close).**
- Desktop: `WorldMapProbe.java:466-480` confirms the engine close takes >40 frames; CLOSE widget at `:452-456`.
- iOS: a tap in the map window is forwarded to the engine X (`ViewController.m:3128-3139`). `mapCloseCanvas` is set
  (`:2586`) but **never read** (referenced exactly twice; declaration `:197` + assignment) despite a comment claiming a
  native ✕ proxy (`:2560-2561`) — close is pure tap-through.
- Fix: draw a native ✕ at `mapCloseCanvas` sized for touch and outside the bezel curve; forward the precise click to
  CLOSE (or, better, a bridge close running the close script directly to kill the multi-frame lag).

**WM-2 (C · P2 · S) — No touch zoom +/- buttons on the world map (pinch only).**
- iOS: `HudVeneerBridge.java:706-707` publishes `zoomIn`/`zoomOut` rects, but `updateWorldMap (:2567-2608)` reads only
  close/search/window/key/maplist (`:2576-2580`) — never zoom; zoom is pinch-only (`onPinch→postWheel :3636-3663`).
- Fix: render native +/- buttons mapped to the published rects, or explicitly mark the engine buttons decorative.
  Low effort — rects already exposed.

**WM-3 (C · P1 · L · medium) — World-map icon tooltips never appear on touch (the worldmap plugin's hover text is
dead).**
- Desktop: `WorldMapConfig.java` hover tooltips — fairy-ring codes (`:37`), agility level req (`:59`), quest status
  (`:191`), rare tree type (`:213`), transport (`:235`).
- iOS: a map tap is a precise *click* (`:3137`); long-press opens the right-click menu (`:3364`), not a tooltip; the
  `postMove` hover primitive exists but is used only for menu-row highlight (`:3356/:3360/:3449`), never over the map.
- Why/Fix: reading those tooltips is the whole point of the worldmap plugin; on iOS the icons are mute. Adapt
  hover→long-press/dwell: while the map is open, a long-press parks a synthetic `postMove` at that map coord so the
  plugin draws the tooltip. Needs a map-mode branch in `onLongPress`. **DEVICE-VERIFY** the engine redraws on a parked
  hover (medium).

**WM-4 (A · P2 · M) — World map lacks double-tap-to-zoom and momentum pan.**
- iOS: pan is 1:1 (`ViewController.m:3592-3610`), END just clears `mapPanning (:3562-3567)` — no decelerate; grep
  `doubleTap|momentum|decelerat` = 0. (Already memory-noted as optional Phase 3.)
- Fix: velocity-tracked pan + a double-tap recognizer posting zoom-in wheel notches centred on the tap. Native-only.

**CAM-1 (A · P2 · M · medium) — Camera zoom-limit/speed/invert config is reachable but undiscoverable; no two-finger
rotate.**
- Desktop: `CameraConfig.java:52/67/77-78/115/126/209-227` (expand inner/outer zoom limit, relax pitch, zoom/camera
  speed, invert yaw/pitch).
- iOS: touch rotate works (`JavaBridge.onCameraDrag :1284-1309` → `setCameraYawTarget`/`setCameraPitchTarget`), pinch
  zooms; the int/bool config items are editable (`PluginConfigViewController.m:269-273`). But the popular "expand zoom
  limit" is buried in the generic config editor, and rotation shares the same 1-finger gesture as walk-disambiguation.
- Fix: surface the key Camera items (expand zoom limits, invert, speed) more prominently, or default the Camera plugin
  on so pinch can zoom out fully; optionally a two-finger twist→yaw. Config already flows.

**CAM-NA-1 (⛔ N/A · S) — Mouse-binding camera config (right-click moves camera / middle-button menu / ctrl-zoom).**
- `CameraConfig.java:148-149/197-198/88-108` are mouse+Ctrl input concepts; their *purpose* (rotate / open menu /
  ctrl-zoom) is already served by drag-rotate, long-press-menu, pinch-zoom. Leave inert.

### Area 7 — Menu-entry swapping · right-click · login/session

**MENU-1 (C · P1 · L) — Right-click submenus (Swap left/shift-click, ground-item & NPC tag flyouts) are unreachable in
the native menu.**
- Desktop: `MenuEntrySwapperPlugin.java:562 createSubMenu()` + `api/MenuEntry.java:169 getSubMenu()`; **8 plugins** use
  submenus (menuentryswapper, npchighlight `:425`, grounditems `:530`, groundmarkers `:304`, objectindicators `:321`,
  inventorytags `:187`, interfacestyles `:174`, devtools `:644`).
- iOS: `MenuPeekBridge.appendEntry (:173-186)` serializes only flat fields — `getSubMenu()` is **never** read;
  `ViewController.m buildNativeMenuWithEntries (:3243)` builds one flat row per entry (`box.userInteractionEnabled=NO
  :3273`). Committing a parent row opens an invisible engine flyout the native overlay has no rows for.
- Why: menu-entry-swapper's headline feature is per-item/object/NPC "Swap left-click ▸ <action>"; ground-items
  "Hide"/"Highlight ▸ color", NPC "Tag ▸ color" are core. With no submenu navigation the capability is effectively
  dead on touch. **Biggest right-click parity gap.**
- Fix: extend `appendEntry` to recurse one level (serialize `getSubMenu().getMenuEntries()` as `sub:[...]`); in the
  native menu a row with children shows ▸, landing the drag expands an inline second column (or slides to a child
  sheet), and commit drives `postMenuSelect` at the submenu-row geometry.

**MENU-2 (A · P2 · M) — The native right-click menu truncates at 14 entries with no scroll.**
- iOS: `JVMLauncher.m:840 -Drunelite.menupeek.maxEntries=14`; `MenuPeekBridge :127 Math.min(MAX_ENTRIES,count)`. The
  native box is fixed-height (`ViewController.m:3255`) and never reads `menuScrollable` (published at
  `MenuPeekBridge.java:118`). Entries beyond 14 silently vanish.
- Fix: make the box a `UIScrollView` when count > visible rows; raise/remove `maxEntries`; honor `isMenuScrollable`.
  Matters for ground-item piles / crowded scenes.

**MENU-3 (C · P2 · S) — Shift-gated config entries depend on the sticky-Shift latch being discovered + combined with
long-press.**
- Desktop: `MenuEntrySwapperPlugin.java:537` swap-config entries only appear with Shift held.
- iOS: a 44×36pt sticky `⇧` button (`ViewController.m:564`, next to the keyboard `:854`) → `postShiftHeld →
  setShiftHeld (JavaBridge.java:1413)` flips `isKeyPressed(KC_SHIFT)`; the user must latch Shift **then** long-press,
  with no in-menu hint.
- Fix: a "Customize" affordance in the native menu header that auto-applies the Shift latch for the rebuild, so
  swap-config is reachable without manually arming Shift. (The latch itself is device-proven.)

**LOGIN-1 (A · P1 · M · medium) — No in-app character/account switch that reuses stored accounts; every switch is a
full re-OAuth + restart.**
- iOS: the accounts array **is** persisted (`ViewController.m:960` in tokens.json) and the native picker exists
  (`JagexLoginViewController.m:209 presentPickerForAccounts`) — but its **only** caller is the post-OAuth flow
  (`:202`); no ViewController path re-presents stored accounts against the same session. The login button (`:475`) →
  fresh WKWebView OAuth (`:60 nonPersistentDataStore`) → forced restart.
- Why/Fix: a multi-character player must redo the whole web login + restart just to swap. Add a "Switch character"
  action reading tokens.json `accounts`, re-presenting the picker, rewriting `chosen_account_id`, re-arming autologin —
  only forcing OAuth when the session is actually expired. *(Medium: session-validity reuse is the open question.)*

**LOGIN-2 (A · P2 · S) — The "Log out" button only disarms next-launch autologin; it doesn't end the live session.**
- iOS: `onLogout (:998)` only deletes tokens.json + purges cookies + logs "autologin disarmed" — it does **not** touch
  the running JVM; it lives in the W1 debug panel (`:786`). The real in-game logout *is* reachable via `openEngineTab(13)`
  (`TAB_GRID_ORDER :2119`).
- Why/Fix: the labelled button is misleading. Either rename to "Forget account / disable autologin", or have it also
  drive the in-game logout (open tab 13 + tap the logout widget), and surface it outside the debug panel.

**LOGIN-3 (A · P2 · L · medium) — Strong login parity, but the web-login forces a full app restart before play.**
- iOS: `JagexLoginViewController.m` implements the full OAuth consent + session mint + native picker (arguably nicer
  than desktop), but `promptRestartToPlay (:1025)` forces a restart (`exit(0) :1038`) because WKWebView leaves WebKit
  resident and breaks the StikDebug JIT-mirror (`:1020-1024`). Subsequent cold launches with saved tokens are fine.
- Why/Fix: a first login or any account switch forces quit-and-reopen — jarring vs desktop. Platform-constrained
  (WebKit vs JIT); mitigate by pairing with LOGIN-1 (skip OAuth on character switch) and making the restart one-tap.
  *(Medium: the unavoidability is a documented W1 platform constraint, not re-testable from source.)*

**LOGIN-4 (B · P2 · S · medium) — No persistent in-session account/world status chip in the native chrome.**
- iOS: the only account display is the pre-launch login-button title (`ViewController.m:475`); once in-game the
  current character/world is visible only via the engine's own blitted UI. No native session/world veneer (grep).
- Fix: a tiny session chip (name + world) fed by HudVeneerBridge/WorldSwitcherBridge, tappable to open World Switcher.
  Low priority (the world is visible in the blit).

**MENU-NA-1 (⛔ N/A → already re-housed · S) — Desktop hover-tooltip menu preview.**
- Already re-housed: the engine-resolved tap settles the left-click entry before clicking
  (`JavaBridge.java:625 startLeftTap`) + `MenuPeekBridge.getTopFingerprint() (:160)` streams the top entry for a
  reticle. Touch has no persistent cursor → a literal hover-tooltip is irrelevant. No gap.

### Area 8 — Window/display · long-tail plugins · keybinds

**PANEL-1 (A · P1 · M) — Generic plugin panels accept taps but can't receive TEXT; any panel with a text field is
half-dead.** *(The §3 P0; listed here as the long-tail systemic gap.)*
- Desktop panels are full Swing with live text fields (`PluginListPanel.java:99/:135/:139`).
- iOS: `MobileUiBridge.panelClick (:645)` dispatches only `MouseEvent` PRESSED/RELEASED/CLICKED (BUTTON1, `:671-673`);
  grep for `JTextField|requestFocus|panelText|KEY_TYPED` → none. `PluginPanelsViewController.m onPanelTap (:580)` maps
  the tap to buffer px and calls `mobilePanelClickX:y:` only.
- Why: Notes, Screen Markers rename, Kourend Library / Hiscore / GE search all need typed input — you tap the field
  and nothing happens. *(Curated native bridges — Quest Helper, World Switcher — do their own UISearchBar and are fine.)*
- Fix: on `panelClick`, if `getDeepestComponentAt` resolves to a `JTextComponent`, raise the soft keyboard and route
  characters through a new `MobileUiBridge.panelText(String)/panelKey(int)` dispatching `KEY_TYPED/KEY_PRESSED` to the
  focused field (mirror `JavaBridge.onText`).

**PANEL-2 (C · P2 · M · medium) — No hover state in panels; tooltips and hover-highlights never fire.**
- iOS: `panelClick (:645)` never dispatches `MOUSE_ENTERED/MOUSE_MOVED` (grep → none), so Swing hover state in the
  rendered panel is never entered. (In-*world* tooltips render into the canvas and DO show — this is panel-side only.)
- Fix: on long-press inside a panel, dispatch a synthetic `MOUSE_MOVED/ENTERED` + re-raster so a tooltip/highlight
  appears (consistent with the long-press=right-click convention). *(Medium: which specific panels degrade is
  plugin-dependent.)*

**PLUG-1 (C · P2 · M · medium) — Screen-marker / "drag a box on screen" plugins have no touch creation path.**
- Desktop: `screenmarkers/ScreenMarkerMouseListener.java:54/:71/:84/:94/:102` — mouse-drag-on-canvas creates the rect.
- iOS: a one-finger drag on the game maps to camera-drag/tap-to-walk (`JVMLauncher.m:171 onCameraDrag`), not a Swing
  MouseListener drag; no mode feeds press/drag/release into the creation overlay. The Screen Markers panel's "Add"
  button can't be reached for typing either (PANEL-1).
- Fix: on the panel's create button, enter a native "draw marker" mode — capture a two-point drag over the blit,
  convert to a canvas-rect, call the plugin's `startCreation`/`completeSelection` via a small `ScreenMarkerBridge`.

**PLUG-2 (B · P2 · L — likely wontfix) — DevTools opens a real top-level Swing JFrame, the one plugin UI that can't
exist under the iOS shell.**
- Desktop: `devtools/DevToolsFrame.java:35 extends JFrame` (`:58 setVisible(true)`) — the *only* `JFrame` in the whole
  plugin tree.
- iOS: the shell replaces ClientUI and renders only the canvas + PluginPanel images; a JFrame has no native host.
- Fix: treat as effectively N/A for v1 (developer plugin); hide JFrame-based dev panels from the iOS list. If ever
  needed, expose inspector data via a bridge and re-present natively.

**KEY-1 (C · P1 · M) — Plugin action-hotkeys (Ground Items hide, Inventory Viewer toggle, quick-hop, Bank search)
have no touch trigger.**
- Desktop: **8 plugins** register a HotkeyListener (bank, devtools, grounditems, inventoryviewer, party, raids,
  screenshot, worldhopper) — e.g. `InventoryViewerPlugin.java:76→:81 overlay.toggle()`; 11 plugins carry Keybind
  config items.
- iOS: the shell only sends ENTER/BACKSPACE for chat (`ViewController.m:3669/:3786`) + committed text; no UI fires an
  arbitrary configured Keybind, and keybind config is read-only (`PluginConfigViewController.m:296`).
- Why/Fix: a real player toggles ground-item hiding / hops worlds dozens of times an hour. Add an optional,
  user-curated on-screen action-button strip that fires a chosen plugin's configured Keybind via
  `JavaBridge.onKeyStroke(vk+mods)` — let the user "pin" a hotkey to a button. Re-houses the exact desktop capability.
  *(Verification corrected the draft's inflated "21 plugins" to 8 HotkeyListener plugins.)*

**KEY-2 (C · P2 · M → already native-satisfied) — Key Remapping (WASD camera + F-key→number remaps) is inert on iOS.**
- Desktop: `KeyRemappingConfig.java:73/85/97/109` WASD; F-key remap section `:114-119`.
- iOS: no hardware keyboard; camera is the native drag (`JVMLauncher.m:171`) + pinch-zoom, and tab switching is the
  engine's mobile tab stones — so the plugin's purpose is already served by native gestures, but the plugin itself does
  nothing.
- Fix: mark camera-pan + F-key-tab "handled by native equivalent"; optionally hide KeyRemapping from the iOS list.

**KEY-3 (C · P2 · S) — Ctrl (and Alt) modifier is unreachable; only Shift has a sticky button.**
- Desktop: e.g. `antidrag/AntiDragConfig.java:58 disableOnCtrl`; `dragHotkey` default Alt (`RuneLiteConfig.java:480`).
- iOS: only a sticky Shift exists (`ViewController.m:37/:3691 → setShiftHeld`); no Ctrl/Alt latch (Alt-drag overlay is
  covered by OVL-2's edit mode instead).
- Fix: add an optional Ctrl latch next to Shift (reuse the `setShiftHeld` pattern with `setCtrlHeld`). Few plugins
  need Ctrl → P2.

**DISP-1 (A · P2 · M) — Interface scale / game size is a hardcoded constant; desktop's adjustable game-size has no iOS
control.**
- Desktop: `RuneLiteConfig.java:65/:69 gameSize()` — user-adjustable.
- iOS: `ViewController.m:2372 int renderH = 540;` (comment "tune here") → `renderW = renderH*aspect` fed to
  `HeadlessResize` (`JVMLauncher.m:898`). One dev-tuned constant; no user control.
- Why/Fix: on a phone, HUD/UI scale is the single most impactful display setting. Expose a 2-3 step "Interface size"
  (Small/Default/Large → renderH ~600/540/480) re-launching/resizing via the existing `HeadlessResize` path; only a UI
  + persisted preference is missing. (Fixed-vs-resizable mode is correctly resolved as always-resizable-fullscreen.)

**DRAG-1 (C · P1 · M) — In-game item drag (inventory rearrange, drag-to-bank-tag) has no touch path.** *(completeness
critic)*
- Desktop **and the official Jagex Mobile app** support drag-to-rearrange inventory + drag-item-onto-bank-tag-tab
  (engine `WidgetDrag`; `banktags TabInterface.java:68` imports it).
- iOS: `ViewController.m:528` maps a one-finger drag to camera-rotate only; grep for `MOUSE_DRAGGED/itemDrag/widgetDrag`
  → nothing. No synthesized engine drag.
- Why/Fix: inventory rearrange + bank-tag assignment are everyday actions; entirely unreachable. Add a touch gesture
  (long-press an item, then drag) that synthesizes engine `MOUSE_DRAGGED` between item slots. Distinct from PLUG-1
  (overlay box-drag) and OVL-2 (overlay reposition). **DEVICE-VERIFY.**

### Deliberately excluded — N/A on iOS (coverage proof)

These desktop capabilities are window-manager / hardware-input concepts with no analogue on a full-screen touch app;
listed so the audit proves they were reviewed, not dropped.

| Capability | Desktop cite | Why N/A |
|---|---|---|
| Custom window chrome / lock window size / remember position / contain-in-screen / auto-resize-type | `RuneLiteConfig.java:125/:93/:105/:117/:81` | No OS window/title-bar; canvas sized by HeadlessResize. **[WIN-NA-1]** |
| Window opacity / always-on-top | `RuneLiteConfig.java:146/:158` | No overlapping windows; app is opaque fullscreen (PiP is the closest "always-on-top" analogue, already built). **[WIN-NA-2]** |
| Tray icon / minimize-to-tray / tray notifications / request-focus | `RuneLiteConfig.java:188/:207/:219` | No system tray; UNUserNotification banners replace it; "request focus" meaningless fullscreen. **[WIN-NA-3]** |
| Exit warning / display-name-in-title-bar | `RuneLiteConfig.java:170/:182` | No title bar; "exit" is the home gesture (teardown is ARCH-1, a different lens). **[WIN-NA-4]** |
| Sidebar toggle key / panel toggle key (Ctrl+F11/F12) | `RuneLiteConfig.java:433/:445` | The native rail/launcher is toggled by direct touch. **[KEY-NA-1]** |
| Block extra mouse buttons (4+) | `RuneLiteConfig.java:456` | Touch synthesizes only BUTTON1; no extra buttons to block. **[KEY-NA-2]** |
| Mouse-binding camera config (right-click moves camera / middle-button menu / ctrl-zoom) | `CameraConfig.java:148-149/197-198/88-108` | Mouse/Ctrl input concepts; purposes served by drag-rotate / long-press-menu / pinch-zoom. **[CAM-NA-1]** |
| Window flash / taskbar bounce / force-focus | `Notifier.java:187-198` | No OS window to flash; in-canvas screen flash IS preserved. **[NOTIF-NA-1]** |
| Resize modes (fixed/resizable/stretched), splash screen, OS cursor management | `ClientUI`/`SplashScreen` | iOS forces resizable fullscreen; no cursor; splash gated off. *(Per MOBILE-UX-AUDIT Table 1.)* |
| DevTools floating JFrame | `DevToolsFrame.java:35` | No native host; dev-only, likely wontfix. **[PLUG-2]** |

---

## §3 — Right-bar redesign (dedicated)

> The desktop "right bar" (`ClientToolbar`'s vertical `NavigationButton` strip opening `PluginPanel`s) is presented
> on iOS by `PluginPanelsViewController.m` as a ~38pt right-edge icon rail + a panel rendered as a fixed-width
> supersampled Swing **screenshot** over a scrim (plus legacy drawer / centered fallbacks). This is the surface the
> user is least happy with. The goal is a cleaner, more user-friendly presentation of the **same** navigation feature.

### 3.1 — Critique of the current presentation (concrete, cited)

**Parity of contents** — all 14 panel-bearing navs surface in the correct desktop order; the gaps are *labels*,
*popups*, *action-only navs*, and *panel content fidelity*:

| Desktop nav | Prio | Surfaces on iOS? | iOS surface |
|---|---|---|---|
| Configuration | 0 | No (deliberate) | Native config/plugin-list card |
| Developer Tools | 1 | Yes (should be gated) | Rendered screenshot |
| XP Tracker | 2 | Yes | Rendered screenshot |
| World Switcher | 3 | Yes (**native**) | Native UITableView |
| Grand Exchange | 3 | Yes | Rendered screenshot (search **not** typable) |
| Time Tracking | 4 | Yes | Rendered screenshot |
| Screen Markers / Hiscore / Loot Tracker | 5 | Yes | Rendered screenshot (Hiscore username not typable; Loot right-click reset lost) |
| Skill Calculator / Kourend Library | 6 | Yes | Rendered screenshot (Skill-Calc inputs not typable) |
| Notes | 7 | Yes | Rendered screenshot (**not editable**) |
| Party / Info | 9 / 10 | Yes | Rendered screenshot |
| Quest Helper (external) | — | Yes (**native**) | Native UITableView |
| Take screenshot / Join Discord / Sign in-out of RuneLite | (action) | **No** | 📷 covers screenshot; Discord + account have no surface |

Concrete problems (`file:line`):
- **[P0] Panel content is a non-native screenshot with one-button input.** The only input is `panelClick(x,y)`
  dispatching a single `MouseEvent.BUTTON1` press/release/click (`MobileUiBridge.java:670-673`); `JVMLauncher.m:356`
  confirms no other panel-input method exists. So **no typing, no right-click, no JComboBox dropdown** (its popup
  renders to a heavyweight window `printAll` never captures), **no inner scroll/drag**. GE search, Notes editing,
  Hiscore/Skill-Calc entry, and Loot-Tracker/GE right-click reset are view-or-single-tap only.
- **[P1] Rail discoverability/crowding.** `kRailWidth = 38.0` (`:7`), `kRailButtonH = 40.0` (`:11`),
  `showsVerticalScrollIndicator = NO` (`:114`), 22+22pt insets to dodge the rounded corners (`:14-15`). In landscape
  the clear band fits ~8-9 icons; with ~14 panels the rail **silently** scrolls with no affordance — off-screen panels
  are undiscoverable. No bottom strip is actually built (only the right rail).
- **[P1] Full-height supersample → OOM-to-blank on the densest panels.** The panel is rendered at full preferred
  content height, supersampled (`RENDER_SCALE=4`) but stepped down toward 1× to stay under `MAX_BUFFER_PX`
  (`MobileUiBridge.java:566-571`; `:565` comment: "at full 4x is ~38 MB … that OOMs and the panel renders blank"). So
  the *largest/most-text-dense* panels render at the *lowest* resolution. Live repaint is a 500ms daemon poll, so
  updates lag.
- **[P2] Right-edge asymmetry + corner collision.** Icons sit 2pt off the screen edge (`:162`) right of the engine tab
  stones, fighting the rounded-corner curve (mitigated only by the insets that steal capacity), all at the far right
  away from the left thumb in landscape, with no relocate option (unlike the HUD veneers). Desktop tabs are 16px in a
  managed JTabbedPane (`ClientUI.java:252`).
- **[P2] Tooltips never render** (no hover; NAV-3) — every icon is unlabelled until the panel opens.
- **[P2] Three presentation code paths** for the same surface (docked dock + drawer `drawerMode=YES` + legacy centered
  `drawerMode=NO`, `ViewController.m:1178`/`:1187`) — maintenance/UX drift.

### 3.2 — Ranked redesign options

Three independent mobile-native redesigns were generated and judged. All keep the game playable, reuse the existing
`listPanels` icons + the two-tier native/rendered fork, and use the already-shipped `pinnedPlugins` favourites.

- **#1 — "More" FAB → bottom-sheet launcher grid.** One ~52pt ⊞ FAB (joins the existing ⚙/▦/≡ corner stack) raises a
  `UISheetPresentationController` (.medium→.large) with a sticky search field + "＋ Hub", a ★ Favourites row, and a
  2-col (portrait) / 4-5-col (landscape) grid of **labelled** icon tiles (`iconPng` above `tooltip`). Long-press a tile
  = pin/unpin; tap = open. **Strongest discovery surface** (labels + search + favourites), scales past the rail's
  scroll-clip, mirrors the Jagex "more options" grid. *Weakness:* a bottom sheet overlaps the bottom-anchored chat/
  minimap veneers.
- **#2 — Categorized sectioned bottom-sheet.** Same sheet idiom, a grouped searchable list (★ Favourites / Combat /
  Skilling / Utility / Info). Reads cleanly but categorization is a liability: desktop `tags()` are free-form and don't
  fold 1:1 into 4 buckets, so it needs a curated native lookup + a catch-all — for only ~17 panel plugins, sections add
  maintenance for little gain over #1's flat favourites+search grid.
- **#3 — Right-edge swipe-in "Cabinet" + compact labelled tab dock.** A `UIScreenEdgePanGestureRecognizer` pulls in a
  labelled tab dock; the selected panel slides in as a **right-anchored ~52% sheet** (game visible on the left). Its
  **panel geometry is the best of the three** (right-anchored, game-visible, a child-VC not a modal so JVM/PiP/
  notifications keep ticking). *Weakness as an entry mechanism:* a bare edge-swipe is undiscoverable, fights the
  generic single-finger camera-pan, and the proposal itself concedes it needs a persistent ❯ handle + coach-mark —
  re-introducing the edge chrome the redesign is trying to remove.

### 3.3 — Recommendation: **"Plugins FAB → labelled grid sheet → right-anchored panel"** (graft #1's launcher onto
#3's panel geometry)

- **Launcher (from #1):** a ⊞ FAB in the existing corner chip stack → a bottom-sheet **grid** of labelled, searchable
  tiles with a ★ Favourites row (reuse the plugins-modal's pinned-sort + star-toggle code: `ViewController.m:1247`/
  `:1303`/`:1429` already call `mobileSetPinned`).
- **Panel presentation (from #3):** tapping a tile opens the panel as a **right-anchored sheet** `width
  MIN(440, screenW*0.52)` (the exact geometry WorldSwitcherViewController already ships), implemented as a **child VC**
  of ViewController (not a UIKit modal) so the game blit / HUD veneers / PiP / notification drain keep running behind
  it, and the live scene stays visible/playable on the left. Dismiss = swipe-right / scrim / ✕ → existing
  `mobileClosePanel`+`mobileFlushConfig`.
- **Why this over a pure bottom sheet:** a right sheet doesn't collide with the bottom-anchored chat+minimap veneers,
  keeps the left thumb zone free, and reuses proven shipping geometry. Why not #3 as the entry: a bare edge-swipe is
  undiscoverable and fights camera-pan; a labelled grid behind one obvious FAB is the clearer Jagex-Mobile idiom.

### 3.4 — Panel content: native vs rendered (the P0)

The shell move alone does **not** fix the panel-content P0 — it must be paired with new bridge input methods, because
`panelClick(II)` can't express typing/right-click/dropdown/inner-scroll:

- **New bridge inputs:** `panelText(String)` routed to the focused Swing text component (with a hidden
  `becomeFirstResponder` UITextField raising the iOS keyboard, like the world-map search already does);
  `panelLongPress(x,y)` → a BUTTON3 dispatch for right-click context actions; `panelWheel(x,y,dy)` for inner
  JScrollPanes/sliders.
- **Tier-1 (hand-native — the quality bar):** Quest Helper + World Switcher today; next, the panels whose **core action
  is type / right-click / inner-drag** and so are most broken under tap-only — **Notes** (a `UITextView` bound to the
  note string; editing is 100% broken in the raster), **Grand Exchange** (item search keyboard + right-click reset; the
  most-used + most-broken — note GE create is currently read-only, NAV-1/completeness), **Hiscore** (username field),
  **Skill Calculator** (level inputs). Each has a bridge-shaped data surface like the existing per-plugin bridges.
- **Tier-2 (rendered fallback — keep as the universal net so no panel ever 404s):** Dev Tools, XP Tracker, Time
  Tracking, Screen Markers, Loot Tracker, Kourend Library, Party, Info — pure read-outs; now legible at full sheet
  width, and the `panelType/panelLongPress/panelWheel` additions stop them being view-only (Loot Tracker's right-click
  reset comes back via `panelLongPress`). Raise `MAX_BUFFER_PX` modestly (the panel no longer fights a 334pt column)
  and segment tall live lists rather than rendering one 2500px image.
- **Re-house the 3 dropped action navs here** (NAV-5): add a `getActions()` bridge (onClick/popup-bearing navs) so the
  grid shows "Discord" + "RuneLite account" tiles; the Screenshot popup ("Open screenshot folder…") maps to a tile
  long-press.

### 3.5 — Migration (incremental, each step compiles + runs; rail stays as a net until step 3)

0. **Java, additive (ships first):** `listPanels()` also emits `name`/`className`/`pinned` (already in `listPlugins`,
   `MobileUiBridge.java:962-969`); add `getActions()` for the dropped Discord/Account/Screenshot navs. No behaviour
   change — the rail ignores the new fields.
1. Generalize the WorldSwitcher right-anchored child-VC geometry into a reusable panel **shell** that can host Tier-2
   rendered panels; wire dismiss to the existing close/flush.
2. Add the ⊞ FAB → `UISheetPresentationController` grid (reuse the plugins-modal pinned-sort + star code), routing tile
   taps through the **same** `onSelectorButton` fork (`PluginPanelsViewController.m:487-514`).
3. Flip the default: `rail.hidden = YES`, FAB is the entry (a one-line, reversible guard). **DEVICE-VERIFY** here.
4. Delete the dead surfaces — `drawerMode` path, legacy long-press path, `setupDrawer/setupLegacy/buildRail` —
   collapsing 3+ code paths into one.
5. (Parallel) the P0 input work: `panelType/panelLongPress/panelWheel` + the hidden keyboard field, then promote
   Notes/GE/Hiscore/Skill-Calc to Tier-1 one at a time.
6. Surface the 3 dropped action buttons as grid tiles via `getActions()`, screenshot popup as a tile long-press.

---

## Proposed batch / sequencing plan

Each batch is branch-sized (off `master`, our normal loop: plan → approve → implement → compile-check → scrub →
commit → deploy-to-phone). Ordered by value × independence; the right-bar batch can be pulled forward if you want to
tackle your top annoyance first (it's independent of Batch 1).

| # | Batch | Findings | Depends on | Notes |
|---|---|---|---|---|
| **1** | **Config reachability & editability** | CORE-1, CHAT-1, NOTIF-2, NOTIF-1, NOTIF-3, CFG-2, CFG-5, CFG-6, CFG-7, CFG-9, CFG-10 | — | Mostly additive bridge + native editor. CORE-1 (inject the RuneLite + Chat Color fake-plugins) unblocks CHAT-1 & NOTIF-3. High value, low risk. |
| **2** | **Right-bar redesign + panel-input P0** | §3 (RB-1), NAV-3, NAV-4, NAV-5, NAV-6, PANEL-1, PANEL-2 | step-0 bridge add | The centerpiece. FAB→grid launcher + right-anchored shell + `panelText/panelLongPress/panelWheel`. Delete legacy modes. |
| **3** | **Native panels for high-traffic plugins** | NAV-1 (GE/XP/Loot/Time native VCs), + promote Notes/Hiscore/Skill-Calc | Batch 2 shell | Mirror QuestHelper/WorldSwitcher. GE *create* still needs raw engine GE. |
| **4** | **Overlay edit mode** | OVL-2, OVL-3, OVL-4, OVL-5, NAV-2 (infobox), KEY-3 (Alt) | — | The dormant `OverlayBridge` → native "Move/Snap/Reset overlays" screen. OVL-4 needs a new `setOverlayPreferredSize` bridge method. Self-contained. |
| **5** | **World Hopper + map polish** | WH-1, WH-2, WH-3, WH-4, WH-5, WM-1, WM-2, WM-3, WM-4 | — | WH = additive bridge JSON + native sort/filter/star/hop-buttons (WH-2 must also drive the ping executor past the `showSidebar` gate). |
| **6** | **Right-click & touch-input idioms** | MENU-1, MENU-2, MENU-3, KEY-1, DRAG-1, PLUG-1 | — | Submenus (MENU-1) + action-hotkey strip + in-game item drag + screen-marker drag-create. Highest "feels native" payoff after the right bar. |
| **7** | **Notifications & screenshots** | (NOTIF-1/2/3 if not in B1), SHOT-2, SHOT-3, SHOT-4, SHOT-5 | B1 for notif | Auto-screenshot→Photos drain, manual capture incl. veneers, clipboard. |
| **8** | **Login / session / chat / display polish** | LOGIN-1, LOGIN-2, LOGIN-4, CHAT-2, CHAT-3, CAM-1, DISP-1 | — | Account/character switch reusing stored session (LOGIN-1), precise chat-message click (CHAT-2), interface-size control (DISP-1). |
| **9** | **Profiles & cloud sync (deferred)** | CFG-3, CFG-4 | — | Power-user; low urgency under single-account throwaway use. CFG-4 gated on CFG-3 + a RuneLite-account session. |

**Suggested order:** 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → (9 deferred). Batches 1, 4, 5, 6 are independent and can be
re-ordered freely; 2 needs the step-0 bridge add; 3 needs Batch 2's shell.

---

## Confidence & uncertainty ledger

- **High confidence (re-read both sides, gap re-grepped):** all NAV, CFG, OVL, NOTIF/SHOT, CHAT-1/2, WH-1..5,
  WM-1/2/4, MENU-1/2/3, LOGIN-1/2, DISP-1, KEY-1/3, PANEL-1, DRAG-1, the §3 critique, and every WIN-NA/KEY-NA item.
- **Medium / DEVICE-VERIFY:** NAV-6 (whether DevTools is in the shipped loaded set), CHAT-3 (whether right-click
  "Report" reaches the menu — engine menu is data-driven), WM-3 (whether the engine redraws a map tooltip on a parked
  hover), CAM-1 (discoverability claim), LOGIN-1 (session-validity reuse), LOGIN-3/LOGIN-4 (platform-restart
  unavoidability / session chip), PANEL-2 & PLUG-1 (which specific panels degrade), CFG-4 (ConfigClient reachability in
  the headless build).
- **Corrected during verification (worth knowing before implementing):** CFG-2 — FontType is a **class with a custom
  serializer, not an enum**, so it cannot reuse the enum editor (must map the 3 named constants to their serialized
  values). KEY-1 — **8** HotkeyListener plugins, not 21. WM-1 — `mapCloseCanvas` is captured but **never read** (the
  "native ✕ proxy" comment is unimplemented intent). WH-2 — pings are gated behind `!config.showSidebar()` and never
  collected, so the fix is not a pure bridge-JSON addition.
