# Android port — roadmap & working standards

The Android app runs real RuneLite on a second, in-process HotSpot VM (see `README.md`). Current
state: **Milestone B** — headless RuneLite renders the OSRS login to the SurfaceView and on-screen
taps drive the gamepack on the arm64 emulator. This file tracks what's next and the bar each change
must clear.

## Working standards (definition of done)

A change is "done" only when its behavior is **proven on the arm64 emulator** — a successful build is
not proof.

- **Build:** `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug`
- **Install + run:** `adb install -r app/build/outputs/apk/debug/app-debug.apk` (adb at
  `~/Library/Android/sdk/platform-tools`), then **always `adb shell am force-stop dev.audaro.helloshell`
  and launch fresh** — never evaluate a stale running process (its pid / frame counter will mislead
  you). Confirm a new pid before trusting anything.
- **Observe:** logcat tags `jvm-boot` / `jvm-stdout`; drive with `adb shell input ...`; capture with
  `adb exec-out screencap -p`. Confirm the specific transition you intended, with a screenshot and/or a
  log line as the evidence.
- **Scope:** work lives under `android/`. The `net.runelite.client.headless.*` bridge is shared with the
  iOS host — change it **additively only** (never alter or remove existing signatures), and re-run the
  desktop OpenJDK 21 headless smoke after any bridge change to confirm nothing regressed.
- **Login:** out of scope by default — login needs account credentials, and running live OSRS on the
  emulator via a self-built client carries ban risk. Everything up to and including the login *screen*
  and login *UX plumbing* is fair game.
- **Commits:** author/committer Jordan Simpson <sayhijordy@gmail.com>; one logical, verified change per
  commit; never commit build artifacts (the generated `libawt_xawt.so`) or local notes.

## Backlog (ranked by value)

### 1. Re-verify the latest host hardening on a fresh boot
Commit `082422360` (atomic frame dims, abort-boot-on-bridge-publish-fail, overflow-safe blit, stdio fd
restore, JNI ref cleanup, SurfaceView-local touch + clamp) is build+install verified only. Force-stop,
launch fresh, confirm the login renders and a tap advances `loginIndex 0 -> 2`.
**Accept:** a screenshot of the rendered login plus a logcat line showing the loginIndex transition,
both from a freshly-booted pid.

### 2. Boot & lifecycle hardening
Surface destroy/recreate across background/foreground; robustness to GPU/buffer-format differences;
clean behavior on repeated launches.
**Accept:** background then foreground the app (and trigger a config change), and confirm the render
resumes with no crash or leak in logcat across several cycles.

### 3. Input parity with iOS (port the touch model)
iOS has long-press right-click menus, an on-screen keyboard, pinch-zoom, one-finger camera, and smart
tap-snap (see the iOS touch-targeting notes in memory). Wire these into the Android host:
- Long-press -> right-click menu (read `MenuPeekBridge` for the menu-peek seam iOS uses).
- Keyboard input for the login form (locate the bridge key entrypoint; iOS drives an on-screen
  keyboard — this may need an additive bridge method + JNI + Kotlin).
- Pinch-zoom and refinement of the camera/pan gesture.
**Accept:** each gesture produces the intended on-screen result, screenshotted, from a fresh boot.

### 4. Login UX plumbing
Port iOS's on-device login (OAuth web flow -> character select -> write `tokens.json` -> autologin; see
the iOS login notes + `JagexLoginViewController`). Mind the iOS same-session crash lesson (relaunch into
a clean process after the web flow). Completing an actual login needs credentials (see Login scope) —
build the flow; leave the credentialed run to a human.
**Accept:** the login UI appears and reaches the token-write step in a dry run, screenshotted.

### 5. Native UI chrome (port iOS Phase 6)
Jagex-Mobile-style tab rails, an always-on plugin dock, and HUD veneers (chat/minimap/orbs). iOS
composites real OSRS sprites and proxies clicks through the bridge (see the native-chrome + HUD notes;
`MobileChromeBridge`).
**Accept:** the chrome renders over the scene and a tab proxy-click switches tabs, screenshotted.

### 6. Plugin config + hub
iOS has native plugin toggle / typed-config / persist plus the plugin hub (pure-Java plugins work;
plugins bundling native libs can't load). Wire the Android equivalent.
**Accept:** toggle a pure-Java plugin and observe its effect on screen / in logcat.

### 7. Render-blit performance
The blit does a per-pixel R<->B swap (`TYPE_INT_RGB` -> `RGBA_8888`). Measure first; only optimize if
the numbers justify it (row/SIMD tricks; the seq-unchanged skip is already partly in place).
**Accept:** before/after FPS numbers from logcat instrumentation.

## Conventions
- Keep this file current: mark items done as they land, and add new items as they surface.
- Read `README.md` for the bring-up gotchas (allowNativeHeapPointerTagging, java.io.tmpdir, the
  `dev-tools/fake-xawt` backend, CACIO CTC activation order) before touching the bootstrap.
