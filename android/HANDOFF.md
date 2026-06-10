# Android mobile-UX — session handoff (2026-06-10)

> Paste the body of this file into a fresh session to continue. Local working note; not load-bearing for the build.

We're continuing the **Android RuneLite mobile-UX work** (runelite-ios repo, `android` branch). This is a native
Android app running real RuneLite on an embedded HotSpot JVM, blitting to a SurfaceView. Pick up mid-**Phase AP-2b**.

## READ FIRST, in order
1. `android/MOBILE-UX-RECONCILIATION.md` — current-reality addendum + per-session status ledger (read before the
   blueprint). The "Session 2026-06-10" sections track AP-0 and the AP-2a work.
2. `android/MOBILE-UX-AUDIT.md` — the blueprint: 27 findings ranked, `[shared-core]`/`[native-Android]` tags,
   phased plan.
3. The session's persistent project memory (`project_android_port_blueprint.md` in the per-project memory dir) —
   the bottom entries carry the AP-0/AP-2a/AP-2b session detail and gotchas. (MEMORY.md indexes everything.)

## TESTING POLICY (non-negotiable)
Emulator-first. Do ALL work on `adb -s emulator-5554`. The Nvidia Shield (`adb -s 192.168.0.142:5555`) is ONLY for
GPU/117HD render-path changes — the entire mobile-UX backlog is emulator work. If unsure, it doesn't need the Shield.

## THE KEY LEVERAGE
The iOS session already built the shared Java bridges (in the staged jar); Android owes only the native
(JNI + Compose/Views) halves. Most AP-2/AP-4 findings are "add a JNI extern + a Compose consumer." No shared-Java
change has been needed — contract is stable at **185 entries**.

## DONE this session (all emulator-verified, pushed to `origin/android`; latest commit `8117b62e5a`)
- **AP-0** (prior): logout, chat scroll-by-drag, manual `⌨` keyboard toggle.
- **AP-2a** (`f26da45cf5`): JNI layer + sticky-Shift `⇧` button (Finding 12, unblocks 18/19/20), notification
  delivery via NotificationManager (9), external links → `ACTION_VIEW` (25). Includes debug-only
  `publishNotification`/`publishLink` JNI + a `BuildConfig.DEBUG` long-press on `⇧` that publishes test items
  (deterministic path test).
- **AP-2b**: Finding 10 — config **color picker** (`6d1b8efe7b`) + **dimension/set** controls (`864e9da85e`);
  Finding 6 — **pin/star** (`edeae94e9f`); Finding 8 — **mark incompatible** plugins (`8117b62e5a`).

## NEXT — finish AP-2b (emulator work, native halves over already-staged shared bridges)
- **Finding 5 — screenshot → MediaStore + 📷 button.** Native already has the composited frame via
  `JavaBridge.getFramePixels()` — encode PNG, save to MediaStore, add a dev-bar button. No shared change.
- **Finding 7 — Low-Detail-on mobile default** (boot policy; mobile-positive for memory/heat).
- **Finding 17 — Plugin Hub browse/install screen.** NET-NEW on Android (it has no hub UI, only the
  enable/toggle list). Needs JNI for `hubManifest`/`hubInstall`/`hubUninstall` (+ `hubState` already staged) and
  a Compose screen mirroring iOS's `PluginHubViewController`. Bigger than the others.

Then **AP-4**: per-panel native reflows (14), overlay edit-mode (15), infobox long-press menu (22), minimap
reconstruction (16, XL), and the real-device verification pass (11 — the only item needing hardware not on the bench).

## CONVENTIONS
- `git fetch` first (the iOS session commits to master concurrently).
- Build: `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug`. If you touch shared
  Java, re-stage: `tools/stage-mobile-runtime.sh` + `android/dev-tools/gpu/stage-android-gpu-jar.sh`, then
  `tools/shared-contract/check-contract.sh`.
- Commit + push under the user's name only, **no tooling/attribution refs anywhere** (scrub staged diffs for
  stray attribution trailers/markers before committing). Auto-commit + push at checkpoints. Save emulator evidence
  frames under `android/dev-tools/ap2b-mobile-ux/`.

## EMULATOR GOTCHAS (learned this session)
- `adb input text "word"` **scrambles** chars even in native fields — clear fully (`keyevent 123` then ~20×
  `keyevent 67`) and type **char-by-char with `sleep 0.6`**.
- Compose **Dialogs** span only device-x `[540,1380]`; taps in the scrim dismiss them. `input tap` uses
  **1920×1080 device coords**; screenshots display at half-scale (×2 to convert). Switches/pointerInput nodes
  don't always expose bounds — read from uiautomator dump.
- A `force-stop` skips the `onStop` config flush; pin durability uses a poll-`getProcessedWriteId`-then-
  `flushConfig` pattern.
- The dev-bar (bottom-left) is now: `⚙ ⌨ ⇧ ▦ ⎆ ⏻ ◆ HD`. The emulator account is a tutorial account (autologin via
  bundled `assets/tokens.json`) on Tutorial Island; a couple of plugins (Account, Agility) are pinned from testing.

Start by reading the three docs, confirm the AP-2b plan, then continue with Finding 5 (screenshot) unless told otherwise.
