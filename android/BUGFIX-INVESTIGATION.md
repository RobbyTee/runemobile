# Android bug triage — root-cause investigation

Investigation of 16 user-reported Android bugs. Read-only code investigation; no fixes applied yet.
Findings below are file:line + cause + fix sketch + confidence. Several bugs share one root cause.

## Architecture note (important)
There are **two** plugin-UI surfaces on Android:
- **Rendered-bitmap path:** `MobileUiBridge.java` renders a Swing `PluginPanel` to a bitmap →
  `PanelView.kt` displays it. Used by *custom* PluginPanels (Notes, Skill Calculator, Screen Markers).
- **Native Compose path:** `ConfigScreen.kt` builds native Compose controls for standard config options.

This split explains why "panels too large" (bitmap path) and "keyboard won't open" (Compose path) are
different bugs with different fixes.

---

## Cluster A — Plugin panel scaling (bugs 13,14,15,16) — CONFIRMED single root cause
Notes "too large & washed out"; Skill Calculator + Screen Markers "very large, hard to navigate";
"fix the large plugin layout by default."

- **Cause:** `MobileUiBridge.java:810` `RENDER_SCALE = 4` supersamples every PluginPanel to a 968px-wide
  buffer ("4x of 242 ≈ 323pt at 3x"). `PanelView.kt:158` displays it with `ContentScale.FillWidth` and
  **no density compensation** — the oversized buffer is stretched to the viewport → oversize + blurry
  ("washed out"). Note `MobileUiBridge.java:829` `activeRenderScale` already steps the scale down for
  tall panels (memory budget), so the scale is not strictly fixed.
- **Fix sketch:** compensate for device density at display time in `PanelView.kt` (divide reported
  px dims by `LocalDensity.current.density` before sizing), OR make `RENDER_SCALE` density-aware from a
  native-reported DPI. Must verify crispness on device — naive scale-down can soften text.
- **Confidence:** High that it's one root cause. Medium on the exact fix (needs device A/B).

## Cluster C — Functional bugs (independent)

### Bug 1 — Keyboard doesn't open in plugin menu (Compose path)
- **Cause:** `ConfigScreen.kt:197+` uses Compose `OutlinedTextField`s but never forces the IME.
  `PanelView.kt:161-165` does it correctly: `fr.requestFocus(); delay(60); keyboard?.show()`.
- **Fix:** add `FocusRequester` + `LocalSoftwareKeyboardController.show()` to ConfigScreen text fields.
- **Confidence:** High.

### Bug 4 — Quick-hop "Next" does nothing
- **Cause:** Wired correctly (`QuestWorldScreens.kt:196` → `worldHopDirection(false)` → JNI →
  `WorldSwitcherBridge.hopDirection()`). Silent-fails if `hop(boolean)` is unresolved on the loaded
  WorldHopper plugin (`WorldSwitcherBridge.java:185-196`, logs then returns).
- **Fix:** needs **device logcat** to confirm the abort path; then resolve `hop(boolean)`/fall back to
  `hop(int)` with the next world id.
- **Confidence:** Medium — runtime diagnosis required before committing a fix.

### Bug 2 — Quest Helper "In progress" shows Agility/Woodcutting/Mining
- **Cause:** Not (necessarily) an external-plugin bug. Quest Helper's enum includes non-quest *skill
  helpers*; `QuestHelperBridge.java:193-195` reflects `getState`/`getQuestType`/`getDifficulty` per entry.
  The In-Progress list isn't filtering out non-quest types, so skill helpers leak in.
- **Fix:** filter by `getQuestType()` (already read at line 194) to exclude SKILL/GENERIC/etc. from the
  quest list, or bucket them separately.
- **Confidence:** Medium — confirm the exact questType enum values before filtering.

### Bug 12 — NPC dialogue portrait missing; match real OSRS look
- **Cause:** `DialogueView.kt` draws an **opaque** parchment over the whole dialogue box and never cuts
  out the head region, so the engine's 3D head (rendered behind on the SurfaceView) is occluded. Head
  bounds are already published by the bridge (`HudVeneerBridge.java:2323`, "head" bounds).
- **Fix:** clip/punch a transparent hole at the head bounds (`clipRect` DIFFERENCE or PorterDuff
  DST_OUT) so the 3D head shows through; align fonts/colors to OSRS.
- **Confidence:** Medium-High.

## Cluster D — Chat / HUD chrome

### Bug 8 — Chat tabs + toolbar tabs flash during the "Welcome to Gielinor" message
- **Cause:** `MainActivity.kt:updateChrome()` (~1700-1759) enables chrome the first frame `loggedIn`
  is true, before the first chat line is drawn → brief flash.
- **Fix:** gate `setGridEnabled`/`applyChatTabs` until the first chat line arrives (a clean
  engine-side "first message shown" flag is preferable to JSON string-sniffing).
- **Confidence:** Medium.

### Bug 11 — Chatbox text fully white
- **Cause:** `CHAT_DEFAULT_COLOR` (`HudVeneerBridge.java:1791`) is *already* white. Real issue: runs
  carry per-`<col=>` colors and a hardcoded red (`:2017 newColor = 0xff0000`). To force fully white we
  must override run colors, not the default constant. (NB: changing the default constant alone is a dead end.)
- **Fix:** force all emitted runs to white (skip `<col>` overrides) for chat lines — likely behind a flag.
- **Confidence:** High on cause; design choice on scope (all chat vs. just game messages).

## Cluster E — Camera hole / notch (bug 3)
- **Cause:** `ChromeOverlayView.kt` already applies safe-area insets (`safeInsets()`, ~132-143) but
  `ChatPillView.kt` does **not** — it uses raw canvas→view scaling, so the chat tab row can sit under
  the punch-hole. Broader UI may also need cutout handling.
- **Fix:** add the same `safeInsets()` logic to `ChatPillView` (and audit other overlays); apply top
  inset to row Y.
- **Confidence:** Medium — confirm this is the only element overlapping the cutout.

## Clusters F + G — Bottom-left toolbar & hotkeys (bugs 5,6,7,9,10)
All in `MainActivity.kt`. Note several are already `BuildConfig.DEBUG`-gated — if the user sees them,
they're on a debug build (confirm whether removal should be debug-too or release-only concern).

- **5 Tools too small:** `devButton()` factory `:428` — 10dp padding, `minWidth/minHeight=0`. Bump
  padding ~16-20dp + min touch target 44-48dp.
- **7 Remove GPU/HD buttons:** `:541-557` (`gpuButton`/`hdButton`), already DEBUG-gated at `:557`.
  Renderer selection lives in the plugin panel — remove these.
- **6 Shift no active indication:** `:505-511` toggles alpha 0.7↔1.0 only. Add background/border/tint
  for a clear latched state.
- **9 Remove other hotkeys (keep Shift):** `⌨` (`:493`), `⌃` (`:525`), `⌥` (`:533`), user hotkeys
  (`refreshHotkeyStrip` `:1417`). Decide which to drop (note ⌥ drives Ground Items hold-to-highlight).
- **10 Remove "hide custom chat/toolbar tabs" toggle:** chrome button `:482-486` + `applyChatTabs`
  `:1754-1795`, DEBUG-gated. Remove the toggle concept if unwanted.
- **Confidence:** High (these are localized UI edits / removals).

---

## Review-pass reconciliation
An independent review raised three challenges; reconciled against the code:
- **"White chat & panel scaling share a root in MobileUiBridge" — REJECTED.** Verified separate paths:
  panels = `MobileUiBridge`→Compose `Image` (`PanelView.kt:158`); chat = `HudVeneerBridge`→`ChatPillView`
  (native canvas, per-`<col>` run colors). No shared code; one cannot fix the other.
- **"Check GL_NEAREST/GL_LINEAR sampler" — wrong layer, kernel ACCEPTED.** No GL sampler in the panel
  path (only `librlawt.cpp`, the game scene). Panels are an ARGB Bitmap shown via
  `Image(ContentScale.FillWidth)`, which bilinear-filters on rescale → that is the "washed out" blur.
  **Refines the Cluster A fix:** render the panel at the density-correct size so Compose scales ~1:1
  (no rescale → no blur) and set `Image` `filterQuality` deliberately — don't merely lower RENDER_SCALE.
- **"Quest Helper filter via sealed class" — infeasible, robustness ACCEPTED.** questType crosses a
  classloader boundary by reflection (returns a String enum name); no compile-time enum available.
  Instead match an explicit allow/deny set and **log unknown types** so new variants fail loud.

## Revised plan / open questions
1. **Cluster A** is the top-ROI single fix (4 bugs) but needs an on-device A/B to confirm crispness.
2. **Clusters F+G** are quick, low-risk UI edits — good momentum batch; need a decision on which
   hotkeys/toggles to keep vs. remove.
3. **Bug 4 (quick-hop)** and **Bug 2 (quest helper)** need runtime/enum confirmation before fixing.
4. Several F/G items are DEBUG-only today — confirm scope of "remove."
