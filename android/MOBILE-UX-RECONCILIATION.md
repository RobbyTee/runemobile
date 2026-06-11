# Mobile-UX Audit — Reconciliation Note (read alongside `android/MOBILE-UX-AUDIT.md`)

> **Purpose:** `android/MOBILE-UX-AUDIT.md` was written **2026-06-08**, *before* the GPU/117HD/real-device
> work. Two of its load-bearing framings are now stale. Read this first so you plan from current reality
> (2026-06-10), not the snapshot. Nothing in the audit's UX findings/phasing is invalidated — only its
> GPU-status and device-verification assumptions changed (both for the better).

---

## ⚠️ TESTING POLICY — emulator-first, Shield only when unavoidable

**Default to the local emulator (`adb -s emulator-5554`) for ALL work unless using the Shield is absolutely
necessary.** The emulator runs locally on the mini, needs no device round-trip, and is authoritative for the
entire CPU-rendered client (Track A), all UI/Compose/Views work, plugin logic, the native bridges, input
plumbing logic, and class-load/wiring correctness. **Essentially the entire MOBILE-UX backlog is emulator
work** — AP-0, the config editor, plugin list, panels, notification *logic*, screenshot, links, overlays,
etc. are all CPU-client UX and verify on the emulator.

**Use the Shield (`adb -s 192.168.0.142:5555`) ONLY when the change genuinely requires it:**
- It touches the **GPU/117HD render path** (the emulator's SwiftShader physically cannot run Zone shaders).
- It needs **real-GPU or real-touch confirmation** the emulator can't give (final pinch/IME/haptic *feel*,
  perf/thermal/battery, a 117HD-active interaction).

If you're unsure whether a change needs the Shield, it almost certainly doesn't — ask first. Treat a Shield
run as the exception, scoped to a deliberate device-verification pass, not the per-iteration loop.

---

## What the Shield / GPU sessions CLEARED or CHANGED vs the 06-08 audit

1. **GPU track is essentially DONE — `I8` / Phase `AP-3` are obsolete.** The audit says *"no live GPU frame
   on screen, `rc_AWT_swapBuffers` is a no-op stub, AG2 is NEXT-not-done, post-v1 fast-follow, functionally
   missing today."* **Not true anymore.** AG2 (base-gpu present), AG3, **117HD (RLHD Zone)**, and a **direct
   EGL window-surface present** are all done and **playable on a real Nvidia Shield**. `rc_AWT_swapBuffers`
   is a full present path, not a stub. The GPU mountain is climbed — which is exactly why pivoting to this UX
   backlog is the right next move.

2. **AP-1 device-verification's scariest item is RETIRED.** The audit flags **`dlopen`-from-`filesDir`
   (B5 #21)** as the one risk that could force re-packaging the JRE under `jniLibs`. It **works on the
   Shield** — the embedded JVM comes up on real hardware. So the existential AP-1 risk (R1) is cleared.
   *Still genuinely unverified on a real phone:* touch-specific Track-A claims (pinch, IME composition,
   haptics, surface-recreate) and perf/thermal/battery — but these are emulator-testable for *logic*, and
   only need a device for final *feel/measurement*, not as a blocking gate.

3. **New durable constraint discovered: the GL bridge is a SINGLE thread-affine context.** Any RLHD/engine
   **multithreaded-GL feature must stay OFF** (we forced RLHD's `asyncModelProcessing=false` to fix
   player-model flashing — concurrent buffer writes from engine render-threads corrupt on a one-context
   bridge). Relevant if any future UX/render change touches threading or the draw path. First suspect for any
   intermittent rendering corruption.

## What is UNCHANGED (the audit's UX work stands)

- All 27 findings, the `[shared-core]`/`[native-Android]` tagging, the "Behind iOS / reuse iOS's shared half"
  leverage, the `I1–I12` infeasible list, and the open questions are all still valid.
- **OQ1 (the shift-feed spike) is still the single blocker** that decides ~5 findings (12/18/19/20 + overlay
  "Configure"). Run it early; it's cheap and its yes/no gates a whole branch.

## Session 2026-06-10 — OQ1 + AP-0 status (emulator-verified)

- **OQ1 is RESOLVED — no spike needed.** The shift-feed was proven FEASIBLE on the live engine on 2026-06-08
  (`d5e54948de`: a synthetic held `VK_SHIFT` posted to the gamepack Canvas flips `isKeyPressed(KC_SHIFT)`,
  release clears it). The shared `JavaBridge.setShiftHeld(boolean)` already shipped (`7c9cf46825`, in
  `baseline.txt`) and iOS has its native sticky-Shift button (`5a3abe83b3`). Findings 12/18/19/20 + overlay
  "Configure" are UNBLOCKED; Android owes only the native sticky-Shift toggle (AP-2).
- **Finding 4 (input-queue overflow guard) was already done in A8** (`jvm_bootstrap.cpp` coalesce + never-drop
  terminal events at the 512 cap) — the audit predates it.
- **AP-0 Findings 1/2/3 implemented + emulator-verified:**
  - **1 Logout** (`⏻` + confirm): flushConfig → delete tokens.json (internal + external) → write a
    `logged_out.marker` that suppresses the bundled-asset fallback (sideload bundles, undeletable) until the
    next successful login clears it → purge WebView cookies → process restart → login-first boot gate.
    Verified end-to-end incl. asset suppression + pushed-tokens re-arm.
  - **2 Chat scroll-by-drag**: drag starting inside the live chat rect (chrome-snapshot bounds, tracks the
    relocate) → accumulated canvas-px deltas → new `nativeScrollChat` JNI extern → shared
    `HudVeneerBridge.scrollChat`. Gesture + JNI seam verified; **visible scroll not yet observed because the
    emulator account is on Tutorial Island, where the chatbox is in dialog/instruction mode (no scrollable
    message layer — `applyChatScroll` correctly clamps to a no-op)**. The engine-side path is the
    iOS-device-proven shared code. Re-verify visually once the account is off the tutorial.
  - **3 Manual `⌨` keyboard toggle**: forces the soft keyboard up for public-chat composition; the auto-raise
    poll now compares against the ACTUAL IME visibility (`ime()` insets, API 30+), so manual mode is sticky
    against system dismissals and stale-state wedges are impossible. Verified incl. re-raise-after-BACK.
- **Note:** the staged Android runtime jars predated the iOS shared-core merge (no `scrollChat` in
  `HudVeneerBridge`) — re-staged via `tools/stage-mobile-runtime.sh --android-only` +
  `stage-android-gpu-jar.sh` (contract UNCHANGED, 184 entries). If a HudVeneer facade method is added to the
  all-or-nothing JNI set, the staged jar must carry it or the whole chrome write surface disables.

## Recommended first steps for the next session

1. **Sync the branch first.** The blueprint + the iOS-side shared-core (which the audit says to *reuse*) live
   on `master`; the `android` branch doesn't have them yet. Merge `origin/master` **into** `android` so you
   have the blueprint and iOS's shared halves in your working tree. (Coordinate shared-core changes with the
   live iOS session — every Shared-S item needs the contract guard + dual-port re-census, per Risk R2.)
2. **Phase AP-0** — four small, zero-blocking-constraint, native-Android-only wins (iOS already proved the
   shared halves). Start with **Finding 4 (input-queue overflow guard)** — it's a real reliability bug
   (dropped touch-UP = stuck camera/button), not polish. Then Logout, chat scroll-by-drag, manual `⌨` button.
3. **Run the OQ1 shift spike** (separately, early) before planning any shift-dependent UX.
4. All of the above is **emulator work** — see the testing policy above.

## Session 2026-06-10 (later): chat scroll-by-drag VISUALLY VERIFIED (emulator, mainland account)

The Tutorial-Island blocker is gone: the emulator now runs a **legacy (non-Jagex) mainland account** via the
new username/password autologin (`HeadlessLogin` legacy stage + `LegacyCreds` + `set-legacy-login.sh`; see
that commit). The tutorial account's tokens.json is parked on-device as `tokens.json.tutorial` (a plain
tokens.json wins over legacy creds by design).

- **Chat is in normal message mode**: tab row (All/Game/Public/…), input line, scrollbar, real scrollback —
  not the tutorial's dialog/instruction mode.
- **Scroll-by-drag verified end-to-end**: drag inside the live chat rect fires
  `MainActivity: ap0: chat scroll begin game=(266,66)` and the **visible lines move** — with the scrollback
  overflowed (>8 lines), a downward drag brought "Welcome to Old School RuneScape." / "This is a free
  world…" back into view (~3 lines of travel for a 120-view-px drag), and the position **holds after
  release** (no snap-back). Frames: `dev-tools/ap0-mobile-ux/evidence-chat-scroll-{before,during,after}.png`.
- Drag direction is natural touch scrolling: drag down → older lines scroll into view.
- Incidental findings while building scrollback for the test (not regressions, logged for later):
  - `adb shell input text` arrives but the per-char round-trip reorders characters (scrambled chat input);
    hardware `KEYCODE_ENTER` (`input keyevent 66`) never reaches the engine, so a pending chat line can't be
    submitted from adb. IME (soft-keyboard) input is unaffected — this is an adb-injection quirk, but worth
    a look if scripted typing is ever needed (e.g. world-switcher search automation).
  - Long-presses with x ≳ 1660 inside the open side panel don't reach the menu-peek path (taps do) — likely
    the chrome overlay's grid hit-test region. Examines on the panel's left half work fine.

## Session 2026-06-10 (AP-2a): JNI layer + thin native consumers — emulator-verified

The shared Java bridges from the iOS merge (MobileUiBridge.publishNotification/nextNotification, publishLink/
nextLink, hubState; JavaBridge.setShiftHeld) had ZERO Android JNI externs + ZERO native consumers — Android
owed the native half across the whole AP-2/AP-4 backlog. AP-2a built the JNI layer + the three true
thin-consumer wins (all consume already-merged shared methods; NO shared-Java change, contract UNCHANGED at
185). Dev-bar gained a `⇧` latch button.

- **Finding 12 — sticky-Shift ⇧ latch — VISUALLY VERIFIED.** `JvmLauncher.setShiftHeld` → queued `IN_SHIFT`
  (ordered ahead of the menu-open right-click) → `JavaBridge.setShiftHeld` (synthetic held VK_SHIFT, the
  iOS-device-proven shared path). Latch ON, long-press a tile → the menu gains **"Mark Tile"** (a
  Ground-Markers shift-gated entry, on by default); latch OFF → it's gone (4 entries → 3). Frames:
  `dev-tools/ap2a-mobile-ux/evidence-shift-{held-mark-tile,released-no-mark}.png`. **Unblocks Findings 18/19/20.**
- **Finding 9 — notification delivery — VERIFIED.** A daemon (`rl-drain`, ~3Hz, OFF the UI thread) drains
  `nextNotification()` → `NotificationManager` on a `runelite_game` channel; `POST_NOTIFICATIONS` requested on
  API 33+. The shared publish-before-focus-gate fix (Notifier.java) means any `notifier.notify()` reaches the
  queue regardless of (always-true headless) focus. Proven deterministically: a real `NotificationRecord`
  posts + renders in the shade ("RuneLite / Notification delivery test"). Frame:
  `evidence-notification-shade.png`.
- **Finding 25 — external links — VERIFIED.** The same daemon drains `nextLink()` → `Intent.ACTION_VIEW`
  (system browser; a plain intent, no androidx.browser dep). A published wiki URL launches Chrome. Frame:
  `evidence-link-chrome.png` (the unconfigured-AVD Chrome first-run is an emulator artifact; the page loads on
  a set-up browser — the ACTION_VIEW path is what's proven).
- **Deterministic-test infra (debug-only):** added `JvmLauncher.publishNotification/publishLink` JNI (host-
  origin publish into the shared queues — also genuinely useful for native-raised alerts later). A
  `BuildConfig.DEBUG`-only LONG-PRESS on the ⇧ button publishes one of each, exercising queue→drain→delivery
  without needing a flaky in-game trigger. Real engine notifications/links ride the identical path.

### Scope correction discovered this session
- **Finding 17 (hub install progress) is NOT a thin consumer on Android.** Android has **no Plugin Hub
  browse/install screen at all** — only the plugin enable/toggle list (`PluginPanel.kt`). The shared
  `hubManifest`/`hubInstall`/`hubUninstall`/`hubState` bridge exists and iOS has a full
  `PluginHubViewController`, but Android never built the equivalent (the matrix's "Android hub works" row was
  carried over from iOS). So #17 needs the whole hub screen built first — moved OUT of AP-2a to its own slot.
  The `nativeHubState` JNI extern is staged + ready for when that screen lands.

## Session 2026-06-10 (AP-2b cont.): screenshot (5) + Low-Detail default (7) — emulator-verified

- **Finding 5 — screenshot → gallery — VERIFIED.** New `📷` dev-bar button → `saveScreenshot()` pulls the
  composited CPU frame via a new `JvmLauncher.nativeFramePixels()` JNI extern (mirrors `nativePanelPixels`,
  reads `JavaBridge.getFramePixels` off the rl-bridge thread) → builds a Bitmap → PNG into MediaStore
  `Pictures/RuneLite`. **Format gotcha:** `getFramePixels` is `0x00RRGGBB` (alpha UNSET) — OR `0xFF000000`
  for an opaque PNG; channel order already matches Android's ARGB int so **NO R↔B swizzle** here (unlike the
  SurfaceView blit at `jvm_bootstrap.cpp:790`, which swizzles for RGBA_8888). Width from `nativeFrameWidth()`,
  `h = len/w` guarded by `len % w == 0` (bails on a torn read across a resize). API 29+ scoped insert
  (RELATIVE_PATH + IS_PENDING, no permission); pre-Q legacy path covered by `WRITE_EXTERNAL_STORAGE`
  maxSdk=28. NO shared-Java change; contract UNCHANGED. Verified: tap → 1280×720 opaque PNG, correct colours,
  lands in `Pictures/RuneLite/` + gallery (pulled + eyeballed). Note: CPU path only — under live GPU/117HD
  present the frame isn't published to `getFramePixels`. Commit `b79639ff36`. Evidence:
  `dev-tools/ap2b-mobile-ux/evidence-screenshot-{devbar,saved-mediastore}.png`.
- **Finding 7 — Low-Detail-on mobile default — VERIFIED, NO CODE NEEDED.** The shared half already shipped in
  the staged jar via the iOS merge: `MobileUiBridge.applyMobileDefaults()` (`:1169`) runs once on the first
  `tick()` (`:1349`) and calls `enableByDefaultIfUnset("LowMemoryPlugin", true)` — enables Low Detail only when
  the user hasn't chosen (config key unset), so a re-seed never overrides an explicit setting. This is a
  pure `[shared-core]` finding (no native half), so Android gets it for free. Verified by stripping
  `runelite.lowmemoryplugin` from the profile, rebooting → log `[mobileui] default-profile: LowMemoryPlugin
  -> true` fires on first tick, key re-applied + persisted to `=true` after a background flush. The
  touch-hostile plugins (cursor/key-remap/stretched/interface-styles/anti-drag) are already stock-OFF, so
  nothing to flip — keeping them out of reach is Finding 8's native "hide incompatible" concern (done).

**AP-2b status: Findings 10, 6, 8, 5, 7 all DONE.** Only remaining AP-2b item: **Finding 17 — net-new
Plugin Hub browse/install screen** (JNI for hubManifest/hubInstall/hubUninstall + a Compose screen mirroring
iOS's PluginHubViewController; hubState already staged). Then AP-4.

## Session 2026-06-10 (AP-2b finish): Plugin Hub screen (17) — emulator-verified; AP-2b COMPLETE

- **Finding 17 — Plugin Hub browse/install — VERIFIED (the net-new AP-2b item).** Android had only the
  enable/toggle list; this adds a Compose `PluginHubScreen` mirroring iOS's `PluginHubViewController` over the
  shared `MobileUiBridge` hub bridge. Reached via a new **"Hub"** button on the plugin panel header. Flow:
  poll `hubManifest()` (~1 Hz) until the one-time async catalog download settles → list every hub plugin
  (name/author/version/description) with an Install/Remove button → on tap, optimistic flip + in-flight
  spinner, call `hubInstall`/`hubUninstall`, poll `hubState()` ({refreshing,seq}) until refreshing=false or a
  25 s deadline, then **reconcile the true installed flags from a fresh `hubManifest()`** (catches a silent
  failure). New JNI externs `nativeHubManifest`/`nativeHubInstall`/`nativeHubUninstall` (nativeHubState was
  already staged AP-2a). NO shared-Java change; contract UNCHANGED (185). Verified: manifest **1737 plugins**;
  install "1 Tick Flick" → spinner → reconcile → "Remove" + engine config `runelite.externalPlugins=one-tick-flick`;
  Remove → empty. Commit `62cf93468d`. Evidence: `dev-tools/ap2b-mobile-ux/evidence-hub-{catalog,installed-remove}.png`.
- **Hub-version pin (shared resource).** The manifest first 404'd: `runelite.pluginhub.version` was
  `${project.version}` = our local **1.12.29**, which was **never released to RuneLite's hub** (upstream's
  latest tag is `runelite-parent-1.12.28`; the hub publishes `1.12.28_full.js` but not `1.12.29`). Pinned
  `runelite.pluginhub.version=1.12.28` in `runelite-client/.../runelite.properties` (decoupled from
  project.version). 1.12.28-built plugins load fine in our 1.12.28+merge client. **This is a shared resource
  → both ports embed it; iOS's hub 404s identically at 1.12.29 (a regression the version bump introduced
  after iOS's hub was last verified 2026-06-02). The iOS port must re-stage its runtime jar
  (`tools/stage-mobile-runtime.sh`) to pick up the pin.** Contract UNCHANGED; only the Android jars were
  re-staged this session (CPU `stage-mobile-runtime --android-only` + GPU `stage-android-gpu-jar.sh`).
- **Related latent issue spotted (NOT hub, out of scope):** `runelite.api.base=https://api.runelite.net/
  runelite-${project.version}` (runelite.properties:18) also uses the non-existent 1.12.29 → item-price / GE /
  session API calls likely 404 too. Flagged for a separate fix (same pin-or-revert decision).

**AP-2b COMPLETE — Findings 10, 6, 8, 5, 7, 17 all done + emulator-verified.** Next: **AP-4** — per-panel
native reflows (14), overlay edit-mode (15), infobox long-press menu (22), minimap reconstruction (16, XL),
and the real-device verification pass (11 — the only item needing hardware not on the bench). Dev-bar now:
⚙ ⌨ ⇧ ▦ ⎆ ⏻ ◆ HD 📷.

## Session 2026-06-10 (AP-4 start): per-panel native reflows (Finding 14) — XP / Loot / GE

Started AP-4 with Finding 14 (per-panel native Compose reflows, replacing the OOM-prone rendered-Swing-image
fallback). All shared data bridges (XpTracker/LootTracker/GrandExchange/TimeTracking) were already merged from
iOS; Android owed the native half. Added a **generic read-only data-panel registrar** in `jvm_bootstrap.cpp`
(`registerDataPanel` + a `DataPanel` struct: isAvailable/setUiOpen/<jsonGetter>, release-latched like
Quest/World) so each new panel is a 3-line JNI add + one Compose screen.

- **XP Tracker** (`c8c8efd306`): `XpTrackerScreen` — live Overall row + per-skill rows (level delta, xp/hr,
  time-to-level, actions-left, progress bar). Verified: facade resolves (xpNative=true), getXpJson round-trips,
  the live **Overall row renders real formatted data** through the same `xpRowOf`→`fmt`→`XpRowItem` path a skill
  row uses. A non-zero per-skill row pends a live XP gain (idle test account; not initiating blind combat on a
  live account — death/item-loss risk).
- **Loot Tracker + Grand Exchange** (`31eaad75b1`): `LootTrackerScreen` (per-source kills/value + item list) +
  `GrandExchangeScreen` (active offers: buy/sell, qty/total, price, progress). Verified: both resolve
  (loot=true ge=true), round-trip + parse, empty states render ("No loot tracked this session." / "No active
  Grand Exchange offers." — idle account has no kills/offers).

Pattern (mirrors Quest/World Tier-2): JNI `nativeXxxAvailable/SetUiOpen/Json` → Compose screen with
`DisposableEffect{ setUiOpen(true/false) }` bracketing + a ~1.5 s poll → a NativeRow in `PanelList` gated on
`xxxAvailable()`. No shared-Java change; contract UNCHANGED (185).

**Finding 14 status: 3 of 4 panels done (XP/Loot/GE). Time Tracking deferred** (most complex — tabs + farming
contract + bird houses + timers + stopwatches multi-section JSON; least-used; hardest to verify on an idle
account). Populated states for all three pend live game data but the wiring/parse/render/empty paths are
verified and the data is iOS-proven. **Remaining AP-4: Finding 14 Time Tracking panel; Finding 15 overlay
edit-mode (fully emulator-verifiable — overlays always present; but L + OQ3 long-press disambiguation); Finding
22 infobox long-press menu; Finding 16 minimap reconstruction (XL); Finding 11 real-device pass (hardware).**

## Session 2026-06-10 (AP-4 cont.): overlay edit mode — snap-to-corner (Finding 15) — FULLY VERIFIED

- **Finding 15 — overlay edit (snap-to-corner first cut) — VERIFIED end-to-end.** The desktop affordance
  (Alt-hold + free mouse drag + hover-cursor) has no clean touch analogue; on a phone the signature need is
  moving an overlay off the relocated HUD, which the five engine snap corners cover. New `OverlayEditScreen`
  (reached from the panel list, gated on `OverlayBridge.isAvailable`) lists every movable overlay with
  TL/TC/TR/BL/BR snap buttons + Reset, calling `setOverlayPreferredPosition` / `resetOverlay`; the engine
  re-renders at the new corner on the next frame (visible through the CPU blit) and persists. Dedicated
  OverlayBridge JNI registration (5 method-ids, release-latched like Quest) + a new `facadeStrArgs`
  (String[,String]→String) C++ helper. No shared-Java change; contract UNCHANGED (185).
  - **This is the first AP-4 item verified with a real state change (not empty-state):** facade resolves
    (overlay=true); the list showed 3 movable widget overlays (EXPERIENCE_TRACKER_WIDGET, RESIZABLE_MINIMAP_WIDGET,
    RESIZABLE_VIEWPORT_BOTTOM_LINE_CHATBOX_PARENT); snapping EXPERIENCE_TRACKER_WIDGET Top-right→Top-left
    updated the live position (engine accepted + persisted, re-poll reflected it), and Reset returned it to
    Top-right (default). HUD restored to default after the test. Evidence:
    `dev-tools/ap4-mobile-ux/evidence-overlay-{edit-list,snapped-topleft,reset-default}.png`.
  - **Note:** the listed overlays here are the engine's resizable WIDGET overlays; plugin overlays appear too
    when their plugin is active. Snapping the minimap/viewport widgets can interact with the HUD veneer
    relocate — left to the user's discretion (the bridge call is safe either way).
  - **Follow-up:** free pixel-drag (setOverlayPreferredLocation + an on-canvas edit gesture + snap preview via
    snapPositionForPoint) is deferred — it needs touch-dispatch integration in MainActivity; the snap-corner
    cut delivers the core "move it off the HUD" value safely.

**AP-4 progress: Finding 14 (3/4 panels: XP/Loot/GE), Finding 15 (overlay snap) DONE. Remaining: Finding 14
Time Tracking panel; Finding 15 free-drag follow-up; Finding 22 infobox long-press menu; Finding 16 minimap
reconstruction (XL); Finding 11 real-device pass (hardware).**

## Session 2026-06-10 (AP-4 cont.): infobox menu (Finding 22) — FULLY VERIFIED

- **Finding 22 — infobox menu (Detach/Flip/Delete) — VERIFIED end-to-end.** Desktop surfaces these via an
  infobox hover-menu; touch has no hover, AND the `InfoBoxBridge` snapshot carries **no bounds** (so a
  long-press can't hit-test a specific infobox) — the bridge is **index-based, designed for a list UI**, not a
  long-press (this corrects the audit's "long-press an infobox" framing for the actual bridge shape). New
  `InfoBoxScreen` (reached from the panel list, gated on `InfoBoxBridge.isAvailable`) lists every live infobox
  (tooltip + text value) with a button per menu option, firing `invokeInfoBoxMenu(index, option)` — which posts
  the same `InfoBoxMenuClicked` the desktop right-click does. Dedicated InfoBoxBridge JNI registration + an
  `(int,String)->String` invoke extern. No shared-Java change; contract UNCHANGED (185).
  - **Verified with a real state change:** facade resolves (infobox=true); the screen listed **26 live
    infoboxes** (the account had active stat boosts — Attack/Defence/Strength/Hitpoints boost, etc.) with
    Detach/Flip; invoking **Detach** on "Attack boost" processed engine-side — the ~1 Hz re-poll showed that box
    **gained a "Delete" option** (a detached infobox is deletable) while the others still showed only
    Detach/Flip. Evidence: `dev-tools/ap4-mobile-ux/evidence-infobox-{list,detached-delete}.png`. (The
    detached boost infobox is transient/cosmetic and self-heals when the boost wears off.)
  - **InfoBoxBridge JSON:** `{available,infoboxes:[{index,name,text,tooltip,menu:["Detach",..]}]}` — index-based;
    `invokeInfoBoxMenu` resolves by current list index + matches the OverlayMenuEntry by option text.

**AP-4 progress: Finding 14 (3/4 panels), Finding 15 (overlay snap), Finding 22 (infobox menu) DONE — all three
reach native screens from the panel list. Remaining: Finding 14 Time Tracking panel; Finding 15 free-drag;
Finding 16 minimap reconstruction (XL); Finding 11 real-device pass (hardware).** Native screens now in the
panel list: XP Tracker, Loot Tracker, Grand Exchange, World Switcher, Quest Helper, Overlay Layout, Infoboxes.

## Session 2026-06-10 (AP-4 cont.): minimap magnifier (Finding 16, scaled-blit MVP) — FULLY VERIFIED

- **Finding 16 — minimap magnifier — VERIFIED end-to-end.** The engine minimap is uncomfortably small on a
  phone. A new **🗺 dev-bar toggle** shows an enlarged minimap: a **scaled-blit of the live minimap region
  cropped from the composited frame**, with tap-to-walk mapped back to the real (relocated) minimap (which
  stays live underneath). **This gives REAL terrain, not a dot-radar** — and corrects the audit: B5 #10 said
  "no API to read the composited minimap bitmap → scaled blit impossible → must reconstruct from data (XL)".
  That predated `getFramePixels` (added for Finding 5), which exposes the whole composited CPU frame, so the
  minimap region is readable and the scaled-blit (higher fidelity, far simpler than terrain reconstruction)
  is viable.
  - **Efficient crop:** new `nativeMinimapCrop(rx,ry,rw,rh)` reads the full frame **once in HotSpot** (pinned,
    no copy) and returns only the small `[rw*rh]` crop to ART — **no per-frame 3.7 MB marshal**. Pulled ~6 Hz
    off the UI thread (one in flight at a time), gated on the magnifier toggle. The minimap rect comes from the
    chrome snapshot's `minimap` field (tracks the relocate). Tap → `(frac × rect)` canvas point inside the real
    minimap → `clickPrecise` (walk). No shared-Java change; contract UNCHANGED (185).
  - **Verified:** toggle on → large minimap with real terrain (river/paths/trees + compass + white player dot +
    yellow NPC dots); tapping it logged `f16: magnifier walk -> canvas (1198,65)` (inside the real minimap rect)
    and **the player walked there** (scene + minimap shifted, walk path drawn). Evidence:
    `dev-tools/ap4-mobile-ux/evidence-minimap-{magnifier,walk}.png`.
  - **Follow-ups:** circular clip (currently a square crop), an always-on enlarged-minimap mode (vs the toggle),
    and true terrain reconstruction (only needed if a non-CPU/GPU path ever lacks `getFramePixels`).

**AP-4 progress: Findings 14 (3/4 panels), 15 (overlay snap), 22 (infobox menu), 16 (minimap magnifier) DONE.
Remaining: Finding 14 Time Tracking panel; Finding 15 free pixel-drag; Finding 11 real-device pass (the only
item needing hardware not on the bench).** Dev-bar now: ⚙ ⌨ ⇧ ▦ ⎆ ⏻ ◆ HD 📷 🗺.

## Session 2026-06-10 (later): minimap magnifier (Finding 16) REMOVED per user request

The minimap magnifier built earlier this session (commit `eb61029d2e`) was **removed at the user's request** —
deemed unnecessary. Reverted in `e8fd6d85b7` (the `nativeMinimapCrop` JNI extern, the `JvmLauncher.minimapCrop`
alias, and all the MainActivity native UI: the 🗺 toggle, the magnifier `ImageView`, `refreshMagnifier` /
`onMagnifierTouch`, the `minimapRect` capture in `applyMinimap`, and the evidence frames). The dev-bar is back to
⚙ ⌨ ⇧ ▦ ⎆ ⏻ ◆ HD 📷. Contract was never touched (UNCHANGED at 185). **Finding 16 is therefore OPEN/declined**
(the small minimap stands; OQ9 answered "accept-small" in effect). The technical finding still holds for the
record: a scaled-blit minimap IS viable via `getFramePixels` (correcting the audit's B5 #10 "impossible"), should
it ever be wanted again.

**AP-4 status after removal: Findings 14 (3/4 panels), 15 (overlay snap), 22 (infobox menu) DONE. Finding 16
declined. Remaining: Finding 14 Time Tracking panel; Finding 15 free pixel-drag; Finding 11 real-device pass.**

## Session 2026-06-10 (later): minimap ORBS missing — ROOT-CAUSED + FIXED

User noticed the HP/prayer/run/special orbs (+ XP/world-map/wiki/store icons) weren't rendering around the
minimap. **Root cause (not the magnifier — that was reverted, and not the relocate/veneer/resize):** the
minimap-orb cluster's **ORBS layer is built but empty** — the engine never opens the Orbs interface (group 160)
INTO that layer in our headless boot. Proven with the existing `HudVeneerBridge.dumpMinimapTree` (enabled via
`-Drunelite.hudveneer.test=true -D…minimapScalePct=1`): `MAP_CONTAINER` + `ORBS` layer present and un-hidden,
but every `InterfaceID.Orbs.*` child (`ORB_HEALTH`/`ORB_PRAYER`/`ORB_RUNENERGY`/`ORB_SPECENERGY`/…) resolved
**null**. Confirmed independent of the A7 resize (still null with resize disabled). The orb *data* was always
fine (HudDataBridge reads it from varplayers); only the orb *interface* never rendered.

**Fix (`3ab27df50e`, shared):** `HudVeneerBridge.tick` now calls `ensureOrbsLoaded` — if the ORBS layer exists
but interface 160 isn't loaded, it `client.openInterface(ORBS_LAYER, InterfaceID.ORBS, NON_MODAL)` once (as the
engine would). The interface's own onload binds the orbs to the live varplayers, so they render + update like a
normal client. Guarded: no-op once present, a 50-tick cooldown so a failed open isn't hammered, re-opens if a
toplevel rebuild drops them. Private helper → **contract UNCHANGED (185)**. Emulator-verified: orbs render
top-right with live values (HP 35 / prayer 13 / run 100 / spec 100 + XP/world-map/wiki/store). Evidence:
`dev-tools/ap4-mobile-ux/evidence-orbs-before-after.png`. **SHARED change — the iOS port must re-stage its
runtime jar to pick it up (iOS very likely had the same missing-orbs gap; `ensureOrbsLoaded` is a safe no-op if
its orbs are already present).**
