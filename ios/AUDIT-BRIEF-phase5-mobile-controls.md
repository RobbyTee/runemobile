# Audit brief — Phase 5 (W3): the real mobile control scheme + Jagex-Mobile UI (2026-06-01)

Self-contained; a reviewer needs only this doc. Asking for an adversarial review of **(a) the Phase-4
reverse-JNI bridge we just shipped — is the architecture sound to build on?** and **(b) a plan + sequencing
for the real mobile control scheme + native UI**, before we invest in the full input/UI layer.

## Where we are (all device-proven this session, md5-gated against verbatim devicectl artifacts)
The full stack now **plays interactively on device** (iPhone 17 Pro Max, iOS 26.5, TXM; JIT via per-launch
StikDebug attach): on-device JIT OpenJDK 21 → CACIO CTC toolkit → **real unmodified RuneLite** → live OSRS
→ logged into the game world → **live framebuffer blit on screen + real touch input** that moves the
character. No streaming, no host. The phased gate ladder up to here:
- **W1 platform** (§6.0/§6.1/§6.2) GREEN: real RuneLite JIT-runs on the on-device JVM under CACIO CTC,
  corruption-free, renders the live-OSRS login (two fixes: removed the `runelite-jit.js` map-on-fault net;
  added `NSMicrophoneUsageDescription`).
- **Phase 4 sub-goal A** GREEN: in-process two-stage login — `JX_*` launcher handoff (from a pushed
  `tokens.json`) + a synthesized **EULA-Accept click** (`loginIndex 12→10`) + the **Play Now click** drove
  `LOGIN_SCREEN → LOGGING_IN → LOADING → LOGGED_IN`, **no native bridge**. This proved the on-device CTC
  `EventQueue` accepts AWT events posted **off-window**.
- **Phase 4 sub-goal B** GREEN (the foundation this brief builds on):
  - **Live blit** — `JavaBridge.snapshot(mainBufferProvider)` at the post-overlay-composite hook publishes
    a complete-frame double buffer; native `JVMLauncher.latestFrame` pulls `getFramePixels()` (packed
    `0xAARRGGBB` `int[]`) each `CADisplayLink` tick → `CGImageCreate` → `UIImageView`. Ran 3840+ frames,
    765×503, smooth. (Replaced the §6.2 PNG-capture-to-disk display, which was choppy/disk-bound/capped.)
  - **Touch** — `ViewController` `touchesBegan/Moved/Ended` map ScaleAspectFit view coords → 765×503 game
    coords (letterbox-aware) → `JavaBridge.onTouch(DOWN/MOVE/UP, x, y, button)` → posts `FocusEvent` +
    `MouseEvent`(s) to the CTC system `EventQueue` with source `client.getCanvas()`. Confirmed: taps move
    the character / interact.

## The Phase-4 bridge as built (the thing to pressure-test for soundness)
- **`net.runelite.client.headless.JavaBridge`** (in the shaded client jar; gated `-Drunelite.bridge=true`):
  the single reverse-JNI facade. `register(client)` (top of `Hooks.draw`); `snapshot(MainBufferProvider)`
  (post-composite, double-buffered, volatile publish); `getFrameWidth/Height/getFramePixels`;
  `onTouch(type,x,y,button)` — DOWN = first-touch `FOCUS_GAINED`+`MOUSE_ENTERED`+`MOUSE_MOVED` then
  `MOUSE_PRESSED`; MOVE = `MOUSE_MOVED`/`MOUSE_DRAGGED`; UP = `MOUSE_RELEASED`+`MOUSE_CLICKED`; tracks
  pressed-state; button 1/3.
- **`JVMLauncher.m`**: caches `JavaVM*` after `JNI_CreateJavaVM` (before `callMain` blocks);
  `ensureBridge()` does `AttachCurrentThread` **on the main/UI thread** and caches the `JavaBridge` jclass
  (global ref) + method ids; `latestFrame` (build UIImage from the int[] via `CFData`→`CGImageCreate`,
  `kCGImageAlphaNoneSkipFirst|kCGBitmapByteOrder32Little` for the LE-arm64 `TYPE_INT_RGB` buffer);
  `postTouchType:x:y:button:`. The JVM runs on a worker thread (`jvm-main`); the blit + touch run on main.
- Process longevity from §6.2 W3 shell: foreground `beginBackgroundTask` + active `CADisplayLink`.

## Questions to adjudicate (verdict + reasoning each)
1. **Is the reverse-JNI bridge architecture sound to build the whole input/UI layer on?** Specifically:
   (a) **main-thread `AttachCurrentThread` that never detaches** — correct, or should the UI thread attach
   as a daemon / detach on teardown? Any risk at `DestroyJavaVM` (we null `gVM` first)? (b) Calling
   `getFramePixels` + `onTouch` from the main thread while the engine/AWT thread runs — the snapshot double
   buffer makes the blit tear-free, but is posting `MouseEvent`s from a non-EDT thread via
   `EventQueue.postEvent` fully safe under CACIO CTC (it is documented thread-safe; any CTC-specific
   caveat)? (c) `gVM` is a plain `static` written on the worker thread, read on main — does it need a
   barrier/atomic, or is the publish-once pattern fine? (d) The 765×503 `int[]` copied out per display tick
   (up to 120 Hz) then re-`CGImageCreate`d even when unchanged — wasteful; worth a frame-sequence guard, or
   negligible? Any better zero-copy path (e.g. a shared direct `ByteBuffer`, `CVPixelBuffer`, or a Metal
   texture) without over-engineering?
2. **Coordinate model for the full scheme.** We hardcode 765×503 (the OSRS fixed-mode canvas) and
   letterbox-map. To **fill a modern phone screen** we likely want RuneLite's **resizable mode** (the canvas
   resizes to an arbitrary WxH) and/or a higher render resolution for retina. What's the least-risky path:
   drive resizable mode (post a `ComponentEvent`/resize the Canvas peer headless — does CTC support a
   resize?), render larger, and map touches to the live WxH? Or keep fixed 765×503 and just scale the blit
   (simpler, but small/letterboxed)? Pitfalls with the gamepack's own viewport vs the RL canvas.
3. **Tap vs right-click menu.** OSRS desktop: left-click = default action, right-click = full menu. On
   mobile the convention is **tap = default action, long-press = open the menu** (then tap an entry). Best
   way to implement on this stack: a long-press gesture → `onTouch` with `button=3` (right-click) to open
   the menu, then route the next tap to the menu entry? Any gamepack timing/▸focus subtlety (the menu is
   drawn by the gamepack into the same canvas, so the follow-up tap is just another left-click at the entry
   coords — correct?). How to distinguish a tap-to-walk from a drag.
4. **Camera + zoom.** Desktop rotates the camera with **middle-mouse-drag** or **arrow keys**, and zooms
   with the **scroll wheel**. Mobile wants **one-finger drag on empty ground = walk-to**, but camera needs a
   gesture too (two-finger drag to rotate? edge drag? a dedicated control?). And **pinch = zoom** → synth a
   `MouseWheelEvent`. Which RL/gamepack input does each map to, and what's the cleanest gesture assignment
   that doesn't collide with tap-to-walk? Is `MouseWheelEvent` accepted by the CTC EventQueue the same way
   `MouseEvent` is?
5. **Keyboard.** Chat, search, login fallback need text. Plan: a hidden `UITextField` first-responder →
   `KeyEvent`s (`KEY_PRESSED`/`KEY_TYPED`/`KEY_RELEASED`) posted to the canvas via the same bridge. Gotchas
   with `KeyEvent` keycodes vs typed chars, and IME/autocorrect on iOS. Worth it now or defer?
6. **Native UI shell (the Jagex-Mobile look).** Currently a debug console + buttons + the image view. The
   product wants a clean full-screen game view with native chrome (on-screen buttons, plugin config,
   overlays). Sequencing: is it right to **harden the control scheme on the current debug shell first**,
   then build the UIKit chrome — or build the UI scaffold now so controls land in their final home? Anything
   that forces UI-first (e.g. SwiftUI/UIKit lifecycle interactions with the JVM thread / background-task /
   `CADisplayLink`)?
7. **Sequencing + biggest risk.** Given A+B are GREEN, what is the cheapest-correct order for Phase 5
   (controls → camera/zoom → keyboard → UI chrome), and what is the **single most likely thing to break or
   to be wrong in the bridge** that we should de-risk first before building on it?

## Constraints / trust rules (unchanged)
- **Device-only** for the real verdict; desktop OpenJDK 21 is the develop-first mirror (W2 rule) — Java/logic
  is validated on desktop first, the device validates the device-specific delta.
- **Trust only verbatim device artifacts** (`jvm-stdio.log`/`hello-shell.log`/`hs_err`/crash `.ips` pulled
  via `devicectl`) + bash-computed md5-gated verdicts. This project has a documented false-GREEN history from
  fabricated shell output.
- **GPLv3 sources (Amethyst/Pojav/StikDebug) are patterns-only**, never vendored.
- **Throwaway Jagex account only**; `tokens.json` is a secret that stays out of git and the shared build
  (pushed to the app container via `devicectl` at test time).
- Self-built client on live OSRS is **ban-risk / not shippable to others** — personal technical build
  (accepted by the user).

## Pointers (for the reviewer, all in-repo)
- Bridge facade: `runelite-client/src/main/java/net/runelite/client/headless/JavaBridge.java`.
- Hooks wiring: `runelite-client/src/main/java/net/runelite/client/callback/Hooks.java` (`register` top of
  `draw`; `snapshot` after `clientUi.paintOverlays`).
- Native bridge: `ios/app/HelloShell/JVMLauncher.m` (`gVM`/`ensureBridge`/`latestFrame`/`postTouch`) +
  `JVMLauncher.h`; touch + blit on the UI side: `ios/app/HelloShell/ViewController.m`
  (`onDisplayTick`, `mapTouch:`, `touchesBegan/Moved/Ended`).
- Login automation (sub-goal A): `runelite-client/.../headless/HeadlessLogin.java`;
  capture/observe instrumentation: `.../headless/HeadlessCapture.java`.
- Canvas seam: `runelite-api/.../Client.java` (`Canvas getCanvas()`).
- Device entry: `ios/dev-tools/awtsmoke/DeviceHeadlessBoot.java`; VM opts + `JX_`/autologin/bridge props in
  `JVMLauncher.m`.
- Evidence (verbatim, md5-noted in commits): `ios/app/evidence/jvm-stdio-phase4-subgoalA-LOGGED_IN-20260601.log`,
  `login-frames-phase4-subgoalA-20260601/`, `hello-shell-phase4-subgoalB-blit-touch-20260601.log`,
  `jvm-stdio-phase4-subgoalB-20260601.log`.
- Prior briefs/verdicts (the rhythm): `ios/AUDIT-BRIEF-phase4-interactive-login.md`,
  `ios/AUDIT-BRIEF-w1-phase3-awt.md`, `ios/app/PHASE3-6.2-DEVICE-CRASH.md`.
