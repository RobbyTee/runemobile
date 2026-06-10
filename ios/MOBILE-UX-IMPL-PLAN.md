# iOS Mobile-UX — Implementation Plan & Tracker

> Execution tracker for the findings in `ios/MOBILE-UX-AUDIT.md`.

## Locked decisions (2026-06-08)
- **Orientation:** Landscape-only (current). No portrait work; build all layout/minimap/reach work for landscape.
- **Cadence:** Batched with device checkpoints. Shared-core lands on desktop OpenJDK 21 first (no device); native changes batch into one on-device StikDebug test pass per batch.
- **Gated/infeasible:** Spike-first, build-if-green. Run OQ1 shift + #15 touch-drag spikes before their dependents; skip cleanly if infeasible. I1–I12 = hide/accept, not build.

## Rate-limiters (why the batching)
1. Per-launch StikDebug attach — every functional test is on the physical device. Batch native changes between device passes.
2. Shared-core re-stage round-trips (`tools/stage-mobile-runtime.sh` → `tools/shared-contract/check-contract.sh` → both-port census). Land all shared-core in one desktop batch.

## Conventions per batch
- Shared-core: develop on desktop OpenJDK 21, re-stage, contract-check, headless smoke, re-census BOTH ports.
- Native: build with the mandatory xcodebuild flags, install, hand a per-batch on-device test script to the user.
- Auto-commit + push per batch under the user's name; scrub for AI/attribution refs first.

---

## Batch A — Spikes & scope lock (no build/device)
- [x] **OQ1 shift-feed spike — GREEN (2026-06-08).** ✅ `[shiftfeed] SHIFTFEED baseline=false downOnce=true downRepeat=true upAfterRelease=false method=single-press conclusion=FEASIBLE via single-press (clean-release)`. A single synthetic held `KEY_PRESSED VK_SHIFT` posted to the gamepack Canvas flips `client.isKeyPressed(KC_SHIFT)` true; release flips it back — clean sticky-shift semantics, NO engine fork. **Un-gates #9/#10/#16 + overlay "Configure" + all marking workflows.** Spike: `ShiftFeedSpike.java` + `Hooks.draw` block; reproduce via `ios/dev-tools/headless/run-shiftfeed.sh`.
  - DESIGN for Batch B/E: additive `JavaBridge.setShiftHeld(boolean)` — on true, post one `KEY_PRESSED VK_SHIFT` (re-post each frame as belt-and-suspenders is unnecessary but harmless); on false, post `KEY_RELEASED`. Native sticky-"Shift" toggle drives it. Keep press held only across the menu-build / long-press peek window.
- [ ] **#15 touch-drag spike** — does `JavaBridge.onTouch`'s PRESS→DRAG→RELEASE stream drive the engine's own widget drag (`getDraggedWidget`)? (verify-first; can fold into Batch B)
- [x] Scope decisions locked (above).

## Batch B — Shared-core Java bridge (desktop OpenJDK 21; one re-stage at end)
- [x] #3  ~~Screenshot PNG getter~~ — RECLASSIFIED native-only (Batch C): native already has the composited frame via `JavaBridge.getFramePixels()`, so the manual screenshot is pure native (encode pixels → Photos); no shared-core getter needed. Auto-capture rerouting = native reads the sandbox screenshot dir.
- [x] #4  `Notifier` publish hook ({title,body}) → `MobileUiBridge.publishNotification`/`nextNotification` (bounded queue, gated). Funnel in `Notifier.notify` after the focus-gate. Native drains + raises OS notification.
- [x] #6 `pinnedPlugins` read (`pinned` in listPlugins) + `setPinned(name,bool)` write — shared desktop CSV (RuneLite group), client-thread RMW. #25 incompatible flag = NOT shared (listPlugins already exposes enabled+active; native infers incompatible = enabled&&!active after grace, as iOS does) → native-Android (Batch C).
- [x] #5  Config dimension/set already round-trip shared-side (getPluginConfig emits type+value+choices; setConfig writes the string; ConfigManager deserializes by type: Dimension="WxH", Set=gson json-array). Added self-describing `encoding` hint so native sends the right format. Native controls = Batch C. Notification structured editing deferred to the native sub-screen (value already emitted).
- [x] #7  Mobile default-profile — COLLAPSED to one flip: Low Detail ON (only-if-unset, applied once on first tick via the proven setPluginEnabled path). The rest (cursor/key/stretched/styles/antidrag) are ALL already `enabledByDefault=false` (audit's "Custom Cursor on" was wrong) → nothing to flip; keeping them out of reach = native hide (Batch C).
- [x] #27 `LinkBrowser.browse/open` intercept → `MobileUiBridge.publishLink`/`nextLink` (bounded, gated); native opens SFSafariViewController. `open()` no-ops on mobile (no file manager / Swing dialog).
- [x] #17 Hub install-progress: `hubState()` ({refreshing,seq}) + `ExternalPluginsChanged` listener latch in MobileUiBridge (install/remove download+load is async; native shows spinner until the event bumps seq, then re-fetches hubManifest).
- [x] #11 `OverlayBridge` — getOverlaysJson + setOverlayPreferredLocation/Position + resetOverlay + snapPositionForPoint (5 real corners); drives OverlayManager.saveOverlay/resetOverlay on the client thread; lazy injector resolve; resize reported but not driven (deferred). Native edit-mode = Batch C.
- [x] #20 `InfoBoxBridge` — getInfoBoxesJson + invokeInfoBoxMenu(index,option); INDEX-based (per-box bounds wiped each frame, so native renders the menu at the long-press point); invokes by posting `InfoBoxMenuClicked` (exact desktop path). Native long-press menu = Batch C.
- [x] #12 Per-panel data bridges — all 4: `LootTrackerBridge` (getLootJson), `XpTrackerBridge` (getXpJson), `GrandExchangeBridge` (getGeJson), `TimeTrackingBridge` (getTimeTrackingJson). Each = QuestHelperBridge pattern (init/isAvailable/setUiOpen/get*Json/tick), wired into MobileUiBridge.init+tick, added to the shared-contract file list. Mostly reflection (most panel/record/snapshot types are package-private even though the plugins are core). Build-green; data shapes documented in each file. Native reflow screens = Batch C.
- [ ] #15 Engine touch-drag→widget-drag routing — NO new bridge code (existing `JavaBridge.onTouch` streams PRESS→DRAG→RELEASE); needs a desktop spike + native wiring. Deferred.
- [x] #9  `JavaBridge.setShiftHeld(boolean)` — transition-gated held VK_SHIFT (mirrors the proven spike). Cleared by Batch A.
- [x] Batch-B closeout: mobileui live-login smoke **GREEN** (md5-gated; facade wired, default-profile applies on EDT, LOGGED_IN, zero new-bridge exceptions); re-staged both ports (content-tag 5396887b185f); contract baseline current (184 entries). #15 touch-drag still deferred (own spike).

## Batch C — Native UIKit consuming Batch B (one device test pass)
- [x] **C1 JNI foundation** — `JVMLauncher` wrappers: postShiftHeld, mobileNextNotification, mobileNextLink, mobileSetPinned, mobileHubState (optional-resolved + null-guarded). Compiles generic/iOS.
P0 polish:
- [x] #1  "Tap to play" front screen gated on `isDebuggerAttached`; hide debug panel — **✅ DEVICE-CONFIRMED GREEN 2026-06-09 (iPhone 17 Pro Max): cover at cold start, Play un-dims on StikDebug attach → green "Ready to play", tap → game launches.** Full-screen opaque Jagex-Mobile cover (`buildLaunchScreen` in ViewController.m) sits above game/HUD/dock/debug-panel: "RuneLite" gold title + "Old School RuneScape" subtitle + big **Tap to Play** button (disabled/dimmed until `isDebuggerAttached`→CS_DEBUGGED, enabled by `startJitPolling`) + gated status line ("Attach StikDebug to play…" amber → "Ready to play" green) + login row mirroring tokens.json (`refreshLaunchLogin`, synced via `refreshLoginButton`). Debug panel now `hidden=YES` by default (≡ toggle still reveals it). Hidden dev affordance: **triple-tap the cover** → `onRevealDebug` drops to the W1 debug panel (canary/AWT-activation) without launching. Tap-to-Play → fade cover → existing `onLaunch`; if WebKit resident this session, defers to the restart prompt and keeps the cover up. Native-only (no shared-core); build-green generic/iOS, installed iPhone 17 Pro Max.
- [x] #2  Reset `engineChatRelocated` in `viewSafeAreaInsetsDidChange` (re-anchor on rotation/notch-flip)
- [x] #9 consumer: sticky-Shift HUD button (amber-latched) → `postShiftHeld`. *(The marquee de-risked feature.)* **✅ DEVICE-CONFIRMED GREEN 2026-06-08 (iPhone 17 Pro Max): latch on → Shift+long-press shows "Mark" entries → mark works → un-latch hides them. Multi-second latch holds with NO per-frame re-assert. Full native→bridge→engine path validated; remaining C1 consumers de-risked (shared JNI pattern).**
- [ ] Dynamic-Island top-center occlusion audit (landscape)
- [ ] #24 Verify chatbox-input auto-raise across Music/Quest List/Wiki/GE/Bank/Fairy Rings
P1 menu + targeting:
- [x] #8  Native big-row menu overlay from `MenuPeekBridge.getSnapshot()` — **BUILT + installed on device 2026-06-09 (device verify pending).** Long-press still opens the engine's own right-click menu (so onClick/worldViewId stay honoured); once it lands in the snapshot (`tryBuildNativeMenu`, polled in onDisplayTick, 0.6s timeout→clamp fallback) we draw an opaque OSRS-styled overlay (`buildNativeMenuWithEntries:`) that COVERS the engine's tiny menu: "Choose Option" header + one **32pt** row per entry (display order, row 0 = left-click default), option white + target OSRS-yellow. Box sized to fully cover the engine rect, **hand-biased** (right-thumb→opens leftward), clamped to safe area. Drag highlights the row under the finger (`updateNativeMenuHighlight:` — white-tint + per-row haptic) and parks the hidden engine cursor on the matching engine row (`menuY+19+k*15+7`, fixed gamepack geom) so the menu stays open; release commits via the proven `selectMenuPoint` click on that engine row (off-rows→title-strip cancel). Reuses the entire existing commit path; `updateMenuHighlightForPoint` dispatches to native when the container exists, else the old engine-clamp fallback. Note: snapshot caps at 10 entries (MAX_ENTRIES); targets uncoloured (tags stripped by the bridge). Native-only. Build-green generic/iOS, installed iPhone 17 Pro Max.
- [ ] #3 long-press region routing (world→menu; chat→filter; overlay→edit) — OQ3
P2 bridge-amortized — **✅ DEVICE-CONFIRMED GREEN 2026-06-08** (notification config toggle + delivery verified on device; sticky-Shift earlier):
- [x] #3  Screenshot → Photos button (📷, saves live frame; NSPhotoLibraryAddUsageDescription added)
- [x] #4  Notifications → `UNUserNotificationCenter`. **Device fix:** publish BEFORE the desktop focus gate (HeadlessClientUI.isFocused()==true was dropping all notifications) + UNUserNotificationCenterDelegate so banners show foregrounded.
- [x] #27 Links → `SFSafariViewController` (drains nextLink ~2Hz, http(s) only)
- [x] #6  Pin/star: pinned-first + ★ + long-press **centered alert** (Pin/Unpin/Configure) → setPinned. (Action-sheet bounced on the 60fps landscape HUD; centered alert fixed it.)
- [x] #5 (partial) notification config items render an **enabled toggle** (flip per-item enabled) instead of raw JSON; compound types no longer dump blobs. Dimension/Set native controls still TODO.

~~KNOWN ISSUE: minimap orbs + wiki missing~~ **RESOLVED 2026-06-09 — NOT our code.** It was account #2's saved in-game display state; swapping to account #1 restored the orbs immediately. Proven by elimination: desktop loaded the orbs with byte-identical current code at the device's exact resolution (mobileui + Low Detail on); the pre-session build on device still had no orbs; disabling every veneer op (relocate / hideStones / side-panel / openTab) changed nothing. All orb-hunt diagnostics + A/B gates reverted; HudVeneerBridge restored to working state. **Lesson: when desktop-with-identical-code works but one device doesn't, suspect account/server state and run the swap-account test EARLY (~2 min vs. a dozen device build cycles).**
- [x] #5  Config dimension/set controls — **BUILT + installed on device 2026-06-09 (device verify pending).** `PluginConfigViewController.m`: **dimension** rows (encoding=WxH) now show the value + open a two-number-field editor (`editDimension:`) writing back `"WxH"`; **set** rows (encoding=json-array) show the selected element names + open a full-screen multi-select checklist (`MUISetChooser`, a new private VC) writing back the gson json-array of element names in enum order (`editSet:`/`setValuesFrom:`). Both added to the reset-swipe editable set. Notification toggle (enabled) already shipped C1; full notification sub-screen intentionally deferred (on mobile the banner is delivered regardless of desktop sound/flash fields, so `enabled` is the only meaningful control). Native-only. Build-green, installed iPhone 17 Pro Max. **⚠ TEST TARGET: Inventory Viewer has NO dimension/set config (just a keybind + 2 booleans) — the audit's "inventory-viewer size" example was wrong. Verify Set via World Hopper → "Region filter" / "World type filter" (Set<Enum>). No CORE plugin declares a `Dimension` config item (audited 2026-06-09) — the dimension editor only appears for certain hub plugins; can't be verified with a core plugin.**
- [x] #17 Hub install progress UI — **BUILT + installed on device 2026-06-09 (device verify pending).** `PluginHubViewController.m`: tapping Install/Remove marks the row in-flight (button→"…", disabled) + shows a global spinner with "Installing/Removing X…", then polls `MobileUiBridge.hubState` ({refreshing,seq}) at 0.4s (`startHubProgressPolling`/`pollHubProgress`); since `hubInstall` sets refreshing=true synchronously, a later false = settled → `reconcileInstalledState` re-reads the manifest and trusts its installed flags (correcting the optimistic flip if a download/load **failed silently**), clears the in-flight rows, stops the spinner. Replaces the dead desktop SplashScreen progress path. Native-only. Build-green, installed iPhone 17 Pro Max. **FIX 2026-06-09 (device report: spinner stuck on "Installing…" forever): `hubRefreshing` only clears via the `ExternalPluginsChanged` event, so a hung/failed install OR a nil `mobileHubState` left the spinner hanging. Added a deadline anchor (`progressStartedAt`) + `settleHubProgress`: readable refreshing waits up to 25s, an unreadable state (nil hubState) settles in 2.5s; both then reconcile against the manifest (whose `installed` = `getInstalledExternalPlugins()`, updated synchronously by `install()`), so it never hangs and always lands on the true state. Re-anchored on every tap.** Open question: whether hub install actually completes on iOS (downloads+loads an external jar) is a SEPARATE pre-existing concern — the honest reconcile now surfaces it (button reverts to Install if the manifest shows it didn't take) instead of the old optimistic lie.
- [ ] #27 `LinkBrowser` → SFSafariViewController
P5 hide/accept:
- [ ] #7  Apply mobile default-profile (native trigger)
- [ ] I3/#18 Hide Custom Cursor / Mouse Tooltips / Interface Styles
- [ ] Build + install + device test pass + commit

## Batch D — Larger reworks (each own effort + device pass)
- [ ] #14 Native minimap reconstruction (XL) over retained invisible click-layer
- [ ] #11 Overlay edit-mode gesture + snap targets + reset-via-long-press
- [ ] #12 Per-panel native reflows: Loot Tracker / XP Tracker / GE
- [ ] Keyboard-occlusion reflow + numeric keypad for amount/PIN
- [ ] #18 Veneer↔overlay anchor reconciliation
- [ ] #20 Infobox long-press menu (detach/flip/delete)

## Batch E — Shift-gated (GREEN — Batch A spike passed; cleared to build)
- [ ] #9  Additive `setShiftHeld` + native sticky-"Shift" button
- [ ] #10 Peek integration (shift-gated overlay "Configure" + marking reachable)
- [ ] #16 (shift-dependent swaps)

## Accept-as-is / hide (I1–I12) — handled as no-op/suppress, folded into P5/native
I1 overlay resize · I2 generic hotkeys/HotkeyButton · I3 custom cursor/tooltips/interface-styles · I4 middle/extra-button camera · I5 WASD/F-key remap · I6 Anti Drag default · I7 Stretched Mode (exclude) · I8 GPU on iOS (shelved) · I9 tray/taskbar/shell notifications · I10 profile drag-reorder/JFileChooser · I11 Screen Markers free-form draw · I12 Developer Tools/Discord/Account

## Deferred open questions (default unless revisited)
OQ2 portrait = NO (locked). OQ5 UI-scale slider — default: ship a scale slider in Batch C polish. OQ3 long-press routing — resolved in Batch C P1. OQ6 background policy — default: foreground-only, no background JVM. OQ11 accessibility — out of scope. OQ12 Report Abuse — accept unreachable unless raised.
