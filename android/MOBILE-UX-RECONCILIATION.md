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
