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
- [ ] #5  Config schema: dimension/set/notification value-encoding
- [x] #7  Mobile default-profile — COLLAPSED to one flip: Low Detail ON (only-if-unset, applied once on first tick via the proven setPluginEnabled path). The rest (cursor/key/stretched/styles/antidrag) are ALL already `enabledByDefault=false` (audit's "Custom Cursor on" was wrong) → nothing to flip; keeping them out of reach = native hide (Batch C).
- [x] #27 `LinkBrowser.browse/open` intercept → `MobileUiBridge.publishLink`/`nextLink` (bounded, gated); native opens SFSafariViewController. `open()` no-ops on mobile (no file manager / Swing dialog).
- [x] #17 Hub install-progress: `hubState()` ({refreshing,seq}) + `ExternalPluginsChanged` listener latch in MobileUiBridge (install/remove download+load is async; native shows spinner until the event bumps seq, then re-fetches hubManifest).
- [ ] #11 Overlay position/persist driver + snap-math wrapper (`OverlayManager.saveOverlay`)
- [ ] #20 Infobox-action-by-point bridge
- [x] #12 Per-panel data bridges — all 4: `LootTrackerBridge` (getLootJson), `XpTrackerBridge` (getXpJson), `GrandExchangeBridge` (getGeJson), `TimeTrackingBridge` (getTimeTrackingJson). Each = QuestHelperBridge pattern (init/isAvailable/setUiOpen/get*Json/tick), wired into MobileUiBridge.init+tick, added to the shared-contract file list. Mostly reflection (most panel/record/snapshot types are package-private even though the plugins are core). Build-green; data shapes documented in each file. Native reflow screens = Batch C.
- [ ] #15 Engine touch-drag→widget-drag routing — NO new bridge code (existing `JavaBridge.onTouch` streams PRESS→DRAG→RELEASE); needs a desktop spike + native wiring. Deferred.
- [x] #9  `JavaBridge.setShiftHeld(boolean)` — transition-gated held VK_SHIFT (mirrors the proven spike). Cleared by Batch A.
- [ ] Re-stage + contract check + desktop headless smoke + dual-port census + commit

## Batch C — Native UIKit consuming Batch B (one device test pass)
P0 polish:
- [ ] #1  "Tap to play" front screen gated on `isDebuggerAttached`; hide debug panel
- [ ] #2  Reset `engineChatRelocated` on safe-area/trait change
- [ ] Dynamic-Island top-center occlusion audit (landscape)
- [ ] #24 Verify chatbox-input auto-raise across Music/Quest List/Wiki/GE/Bank/Fairy Rings
P1 menu + targeting:
- [ ] #8  Native big-row menu overlay from `MenuPeekBridge.getSnapshot()` (44pt rows, hand-bias)
- [ ] #3 long-press region routing (world→menu; chat→filter; overlay→edit) — OQ3
P2 bridge-amortized:
- [ ] #3  Screenshot → Photos button
- [ ] #4  Notifications via `UNUserNotificationCenter` (foreground-only; AFK value near-zero)
- [ ] #6  Pin/star UI + sort; #25 incompatible-plugin hide
- [ ] #5  Config dimension/set/notification controls + native notification sub-screen
- [ ] #17 Hub install progress UI
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
