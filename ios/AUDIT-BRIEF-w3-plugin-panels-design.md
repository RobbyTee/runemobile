# Review brief — W3 native plugin side-panels: design/layout options (2026-06-02)

Self-contained; a reviewer needs only this doc. Asking for an **adversarial design verdict**: given hard
constraints, what is the **best way to present RuneLite plugin "side-panels" on a native iOS client** so they
look good / feel native — and is our current "render the Swing panel to an image and blit it" approach the
right one, a dead end, or improvable? We are NOT asking how to make the current code crisper (already done);
we want the **right architecture/UX for plugin panels on mobile**.

## The project (context)
A self-contained **native iOS app** runs **real, unmodified RuneLite** (the Java OSRS client + its whole
plugin ecosystem) on an **on-device JIT OpenJDK 21**, styled like the official Jagex OSRS Mobile app. No
streaming, no host machine. The Java AWT/Swing stack runs **headless under CACIO** (a pure-Java AWT toolkit,
`com.github.caciocavallosilano`): there is **no real screen/window server** — Java2D/Swing render into
`BufferedImage`s. The game itself is already on screen: RuneLite composites each frame into a buffer, the
native shell pulls the pixels over reverse-JNI and blits them into a `UIImageView` (`CADisplayLink`), and
native UIKit touches are mapped back to AWT `MouseEvent`s posted into the game canvas. That game blit + touch
bridge is solid and shipping. Device: iPhone 17 Pro Max, iOS 26.5, landscape, full-screen.

There is a **Java-owned control-plane facade** `MobileUiBridge` (reverse-JNI, gated `-Drunelite.mobileui`)
with a load-bearing **two-lane thread model**: plugin lifecycle on the AWT/CACIO EDT, config writes on the
game client thread. On top of it we already built, and device-confirmed:
- a **native plugin list + enable/disable** panel,
- a **native typed config screen** (boolean→switch, int→stepper, enum→picker, color→iOS color picker, etc.),
  built by reading RuneLite's **config metadata** (`ConfigDescriptor`/`ConfigItemDescriptor` — a structured,
  typed schema) and rendering **real native UIKit controls**,
- a **native plugin-hub browse/install** screen.

So for *config*, we get genuinely native UIKit controls **because config has a typed schema we can reflect
over**. The thing this brief is about — plugin **panels** — has **no schema**.

## What a "plugin panel" is (the crux of the difficulty)
Many RuneLite plugins register a `NavigationButton` (`ClientToolbar.addNavigation`) carrying an optional
`PluginPanel`. On desktop these are the right-sidebar tools: **XP Tracker, Grand Exchange search, World
Switcher, Loot Tracker, Notes, Screen Markers, Skill Calculator, Quest Helper, Hiscore**, plus ~1700
plugin-hub plugins, each potentially with one. A `PluginPanel` is an **arbitrary `javax.swing.JPanel`
subclass** — the plugin builds its own Swing component tree (labels, buttons, combo boxes, custom-painted
components, search fields, dynamic lists) and mutates it at runtime (XP ticking, GE results loading). There
is **no declarative description** of a panel — it is imperative Swing code. `PluginPanel.PANEL_WIDTH = 225`
is hardcoded; panels assume that fixed narrow width and a vertical scroll.

**Implication:** unlike config, we cannot reflect a schema and rebuild panels as native controls — there is
nothing structured to read. Reimplementing each panel natively would mean rewriting arbitrary third-party
plugin UI by hand (1700+, ever-changing) and would break the project's "unmodified plugins" guarantee.

## What we built (current state — functional, device-confirmed)
An **embedded-Swing surface**, the same shape as the game blit:
- `HeadlessClientUI.addNavigation/removeNavigation` (no-ops before) now forward the registered
  `NavigationButton`s to `MobileUiBridge` (kept sorted like the desktop toolbar).
- `MobileUiBridge.listPanels()` → JSON `[{index,tooltip}]` of plugins that have a panel (excluding RuneLite's
  own "Configuration" sidebar, which is empty standalone).
- `MobileUiBridge.openPanel(index)`: on the EDT, add the panel's `getWrappedPanel()` (its `JScrollPane` +
  content) to a **showing off-screen `JWindow`** (`setVisible(true)` is required under CACIO so children lay
  out + paint), call `Activatable.onActivate()`, lay out at the **242 logical width and full content height**.
- A 500 ms daemon re-paints the active panel (so live updates show). `renderActiveOnEdt()` paints the wrapped
  tree via `printAll(g)` into a `TYPE_INT_ARGB` buffer **supersampled `RENDER_SCALE=4`** (Graphics2D scaled
  4×; layout stays 242-logical so the look is faithful, pixels are 4× denser → crisp). Published as an
  `int[]` + width/height + a frame sequence.
- Native (`PluginPanelsViewController`): a `▣ Panels` button opens a screen with a **horizontal toolbar** of
  buttons (one per panel, labeled by tooltip) and a **vertically-scrolling `UIScrollView`** holding a
  `UIImageView`. Each tick it polls the frame sequence and, when changed, pulls the buffer → `CGImage` →
  image view, **displayed at device resolution 1:1** (no upscale → pixel-crisp), at the buffer's natural
  size (~323 pt wide on a 3× screen), **centered** with the side margins blended into the app background.
- Touch: a tap on the image maps view-pt → buffer-px → (Java) ÷RENDER_SCALE → 242-logical coords;
  `MobileUiBridge.panelClick(x,y)` finds the deepest component at that point and dispatches
  PRESSED/RELEASED/CLICKED `MouseEvent`s on the EDT (drives buttons/checkboxes/combo arrows).

This **works on device**: the toolbar populates, panels render and switch, taps dispatch, live repaint runs.
Files: `runelite-client/src/main/java/net/runelite/client/headless/MobileUiBridge.java` (panel section),
`runelite-client/.../ui/HeadlessClientUI.java`, `ios/app/HelloShell/PluginPanelsViewController.{h,m}`,
`ios/app/HelloShell/JVMLauncher.{h,m}` (the `mobile*Panel*` wrappers).

## The problem we want solved
It **looks like a screenshot of a narrow desktop Swing panel embedded in a box**, not like a native mobile
UI. Even pixel-crisp and centered, the user's verdict is "stretched and ugly … doesn't look native." Reasons:
- It is the desktop dark Swing theme + desktop/RuneScape fonts + the fixed 225-px narrow layout, sitting as a
  ~323 pt column in a wide landscape screen with empty side margins (a "floating box").
- It is a **flat raster** — no native scrolling feel beyond the outer `UIScrollView`, no native momentum on
  inner lists, no native text selection, no native controls; combo boxes/search fields are images you tap.
- Widening it blurs (upscale) or, if we relayout wider, the content stays pinned to its 225 preferred width
  (JScrollPane won't stretch the view past preferred) → empty gaps.

## What we want from the review
A verdict + reasoning on the **best path for plugin panels on this stack**, weighing these candidate
directions (and any we missed). Be opinionated and rank them; call out which are dead ends.

1. **Keep the rendered-Swing surface, but make it *present* as native chrome.** e.g. a proper native title
   bar (plugin name + icon), full-height column, a native segmented/scrolling tab strip, native momentum
   scrolling of the rendered image, portrait-style sizing, theming the surround to match the Jagex-Mobile
   look. Is good *presentation* around a rendered panel enough? What specifically would make it read native?
2. **Relayout the Swing panel to a mobile-appropriate width.** Can we make panels lay out wider than 225
   (override `getScrollableTracksViewportWidth`, force the view width, or set a wider `PANEL_WIDTH`) without
   breaking plugins — so content fills a phone column instead of sitting in a 225 box? Risk: many panels
   assume 225 and use fixed-width components; how badly does this break in practice, and is it worth it?
3. **Semantic extraction / hybrid.** Is there any robust way to walk a `PluginPanel`'s Swing component tree
   and map common components (`JLabel`, `JButton`, `JComboBox`, `JTextField`, `JCheckBox`, the RuneLite
   custom widgets) to **native UIKit controls** generically — at least for the common cases — falling back to
   the raster for anything unrecognized? Is that a maintainable architecture or a tar pit?
4. **Native reimplementation of the few high-value panels** (XP Tracker, GE search, World Switcher, Notes),
   driven by the plugins' *data* (events/APIs) rather than their Swing UI, with the rendered surface as the
   universal fallback for everything else. Where's the line — which panels have clean enough data APIs to be
   worth a hand-built native UI?
5. **Input/scroll fidelity on the rendered surface.** Today only tap is forwarded. Should we forward drag
   (inner scroll/sliders), wheel, text entry (route the iOS keyboard into a focused Swing field), long-press
   → context menus? Or does the full-height-native-scroll model (we render the whole panel tall, native
   scrolls the image) make inner Swing scrolling moot — and what breaks then (e.g. a panel with its own
   internal scroll regions, drag-reorder lists)?
6. **Higher-fidelity rendering.** Font hinting/anti-aliasing settings, rendering at true device DPI,
   per-component dirty repaint instead of whole-panel every 500 ms (perf/memory: a tall panel at 4× is a
   multi-MB buffer rebuilt twice a second). Worth it, or polishing a fundamentally non-native thing?
7. **Reframe the goal.** Is "make plugin panels look native" the wrong target? Should plugin panels be a
   deliberately "advanced/desktop-tools" surface that looks like RuneLite (familiar to RuneLite users) rather
   than iOS — i.e. accept the rendered look but make it intentional and polished? What would the official
   Jagex Mobile app analogue be?

## Constraints / non-negotiables
- **Plugins stay unmodified** — no per-plugin forks; whatever we do must work for arbitrary current/future
  plugins (the ~1700-plugin hub), with at most a special-cased native path for a small hand-picked few.
- **Headless CACIO** — no real screen; Swing renders to buffers. The two-lane EDT/client-thread model and the
  reverse-JNI `MobileUiBridge` facade are load-bearing; don't propose breaking them.
- **Device-only is the real verdict**; desktop OpenJDK 21 + CACIO is the develop-first mirror (a probe in
  `MobileUiSmokeRunner` already renders a real panel to a PNG headless on both).
- Performance/memory matter (we've fought iOS jetsam before); a 4× full-panel raster at 2 Hz is already a
  cost worth questioning.

## Bottom line for the reviewer
We have a **working but unsatisfying** embedded-Swing panel surface. The hard constraint is that plugin
panels are **arbitrary unmodified Swing components with no schema**, unlike config (which we made truly native
via its typed metadata). Tell us the **best architecture + UX** for plugin panels on this iOS/CACIO stack:
rank the directions above (or propose better), name the **single highest-value change**, and flag anything
that's a dead end so the next session doesn't chase it.
