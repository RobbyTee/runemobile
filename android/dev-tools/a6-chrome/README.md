# A6 — native Jagex-Mobile chrome (seam)

Phase A6 brings the OSRS-Mobile chrome: native edge **tab-rails** drawn over the live tab stones
(cover-and-proxy) + the engine **chat relocated to the top**. Like A4/A5, it rides shared bridges already in
the jar — no new shared Java:

- **`MobileChromeBridge`** (gate `-Drunelite.mobilechrome=true`) — read-only `getSnapshot()` → per-frame
  escaped JSON of the tab-stone / side-panel / minimap / orb / chat / viewport **canvas bounds** + active tab.
  The native rails read this each display tick to position + highlight themselves; a tab tap proxies
  `JavaBridge.onClickPrecise(centre, 1)` onto the live stone (no widget is moved or hidden).
- **`HudVeneerBridge`** (gate `-Drunelite.hudveneer=true`) — `relocateChat(x,y,transparent)` moves the engine's
  own chat to the top (tabs/scrollback/colours/input stay live). (Wired in the chrome-UI increment.)

## Seam gate (this dir)

`check-a6seam.sh` proves the **host reaches the chrome read surface across the VM boundary** and that the
surface **resolves real geometry** in the current Android headless layout.

```
cd android && JAVA_HOME=… ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.audaro.helloshell/.MainActivity --ez census true
android/dev-tools/a6-chrome/check-a6seam.sh      # greps the host self-test sentinel
```

`runA6SeamSelfTest` (census mode, MainActivity) reads `JvmLauncher.chromeSnapshot()` on the rl-bridge thread
and polls the **live** snapshot for ~40s after login, because the bridge's own one-shot `[chrome] CHROME_READ`
verdict fires ~60 LOGGED_IN frames in — **before the in-world scene/toplevel builds** (it logs
`topLevel=165`=TOPLEVEL_DISPLAY, everything absent). Polling the live snapshot instead catches the resolved
state.

## Baseline GREEN (emulator `rl_arm64`, 2026-06-08)

```
ANDROID_A6_SEAM_GREEN fixed-mode chrome read resolved: rootKind=FIXED resized=false topLevel=548
  activeTab=3 viewport=true tabsNonNull=14/14 sidePanel=true minimap=false orbs=true chat=true
```

**Key finding:** the chrome read surface resolves **fully in FIXED (765×503) mode** once the world loads — all
14 tab stones, the viewport, orbs/chat/sidePanel — so **A6 cover-and-proxy is viable in fixed mode; the mobile
resizable layout is a separate, deferrable concern.** (`-Drunelite.headless.resize` on Android resizes the AWT
component but the gamepack canvas stays 765×503 and the viewport collapses — that resizable-canvas gap is a
shared/headless-Java item parked for the edge-to-edge work, NOT a blocker for the cover-and-proxy rails.)
`minimap=false` is expected: the bridge returns null for the fixed-root minimap (the orbs cluster resolved).
