# Audit brief — W3 native plugin-config panel (2026-06-01)

Self-contained; a reviewer needs only this doc. Asking for an **adversarial verdict on the architecture and
sequencing** for a **native UIKit plugin-management + config panel** on iOS — the UI that replaces RuneLite's
desktop Swing config window (which doesn't exist on mobile). The headline question:

> **How do we let the user browse plugins, toggle them on/off, and edit their config from a native iOS panel —
> driving the unmodified RuneLite `PluginManager`/`ConfigManager` over reverse-JNI — without getting the JVM
> thread model wrong (the #1 risk) or desyncing the panel from the live config state?**

## Where we are (all device-proven this session)
The full stack plays like a mobile client on device (iPhone 17 Pro Max, iOS 26.5, TXM; per-launch StikDebug JIT):
on-device JIT OpenJDK 21 → CACIO CTC → **real unmodified RuneLite** → live OSRS → full-screen landscape, with a
complete touch + keyboard control scheme AND a native Jagex-Mobile-style **tab chrome** (authentic OSRS tab
sprites in two edge columns, precise-click switching, desktop stones covered). The native shell is
`ios/app/HelloShell/` (`ViewController.m` + `JVMLauncher.m`); the game renders to the bottom layer, native
chrome composites on top. What's **missing**: there is no way to browse/toggle/configure plugins from the UI.

## The backend that already exists (the foundation — and the crux of the review)
`MobileUiBridge` (gated `-Drunelite.mobileui=true`, device-proven "step 0" GREEN) is the Java-owned control-plane
facade the native panel calls over reverse-JNI. **Its entire reason to exist is THREAD ROUTING** — the #1 risk:
- **`onEdt` lane** — plugin lifecycle. `PluginManager.startPlugin`/`stopPlugin` `assert SwingUtilities.isEventDispatchThread()` (under iOS, the CACIO CTC event thread). MUST run on the EDT.
- **`onClientThread` lane** — config writes + any live-game-state read/write. `ConfigManager.setConfiguration` fires `ConfigChanged` **synchronously** via the single-threaded `EventBus`, so subscribers run on the calling thread; game-touching ones re-dispatch onto the `ClientThread` themselves.

If the native side called `PluginManager`/`ConfigManager` directly over JNI they'd run on the JNI-attached UIKit
thread — wrong thread, silent corruption (worse with assertions disabled). So the facade exposes coarse methods
and routes each onto the correct lane. **Device-proven (step 0):** the two lanes are empirically separate
(config write fires `ConfigChanged` on the client thread, NOT EDT; plugin toggle runs on EDT, becomes active,
registers its overlay headless), and config persists across JVM launches. Re-runnable self-test:
`MobileUiSmokeRunner` (armed by `-Drunelite.mobileui.smoke=true`).

### The JNI surface (all `static`, JSON in/out, fire-and-forget writes)
- `String listPlugins()` — array of `{className, name, description, enabled, active, hasConfig, developer, configGroup}`. `className` is the stable write handle.
- `String getPluginConfig(String group)` — `{group, items:[{key, name, description, section, position, secret, type, value, min?, max?, units?, choices?}]}`. Mirrors what the desktop `ConfigPanel` reflects.
- `String setPluginEnabled(String className, boolean enabled)` — EDT lane (`setPluginEnabled` then `startPlugin`/`stopPlugin`); returns `{"ok":true,"queued":"edt"}`. The follow-on `PluginChanged` is the confirmation.
- `String setConfig(String group, String key, String value)` — client-thread lane; value already serialized to its string form by the native control. The resulting `ConfigChanged` is the confirmation.
- `String resetConfig(String group, String key)` — client-thread; unset → default.
- Reads are **snapshots** (escaped JSON, parse once per panel open — never bound live at frame rate). Writes
  **queue and return immediately**; the emitted `ConfigChanged`/`PluginChanged` is the source of truth.

### Config item `type`s the bridge emits (the native control set to render)
`boolean, int (has min/max/units), double, string, enum (has choices[]), color, dimension, set (multi-select),
keybind, notification, unknown`. Anything unrecognised → `unknown` (render read-only).

## The native side it must integrate with
- Reverse-JNI pattern is established: `JVMLauncher.m` caches the bridge class + method ids on the JNI-attached
  main thread (`ensureBridge`), exposes `+ (NSString *)…` / `+ (void)…` wrappers; `ViewController.m` calls them.
  `MobileChromeBridge.getSnapshot` is the read-only precedent; `JavaBridge.onClickPrecise` the write precedent.
- A collapsible debug panel + a `≡` toggle already exist in `ViewController.m` (a dev crutch). The native chrome
  (tab rails) are subviews composited over the game image view; touch hit-testing must not leak chrome taps into
  the game (solved for the tabs; the config panel must do the same).
- `MobileUiBridge.init(...)` is wired from `runelite-client/.../ui/HeadlessClientUI.java`.

## Questions to adjudicate (verdict + reasoning each)
1. **JNI surface soundness / thread safety (the crux).** Is the two-lane facade the right boundary, and are
   there gaps? Specifically: (a) `listPlugins`/`getPluginConfig` are called from the native UI (JNI) thread and
   read "already-resolved metadata" — is that actually thread-safe vs the live client mutating plugins/config
   concurrently (e.g. a config value changing mid-read, a plugin list changing during iteration)? (b) `setConfig`
   fires `ConfigChanged` **synchronously** on the client thread — any reentrancy/ordering hazard if the user
   slams several writes, or if a subscriber itself writes config? (c) Should writes stay fire-and-forget, or does
   the panel need a completion signal?
2. **Refresh / state model.** Writes are fire-and-forget; the panel learns the result by re-reading the snapshot.
   What's the right refresh strategy — re-`getPluginConfig` after each write? Subscribe to `ConfigChanged`/
   `PluginChanged` and push to native? Poll? How do we keep the native control in sync without binding live at
   frame rate, and without races where the panel shows stale values?
3. **The config control set — scope + correctness.** Which `type`s are must-have for v1 vs deferrable to
   read-only? How should each map to a native control (boolean→switch, int→slider/stepper honoring min/max/units,
   enum→picker, color→native color picker, string→textfield, double→?, set→multi-select, keybind→?, dimension→?)?
   How is each serialized back to the `String` value `setConfig` expects (esp. color, enum, set, keybind)? Get
   this wrong and we write malformed values into real plugin config.
4. **Enabled vs active, and overlays.** `setPluginEnabled` is EDT and (un)registers overlays. The panel shows
   both `enabled` (user intent, persisted) and `active` (running). What's the correct UX + when does each change?
   Any risk that toggling a plugin from native breaks the overlay/anchor work already proven?
5. **Secret items + safety.** `secret` config items (passwords/tokens). How handled (mask, never log, no
   round-trip through native logs)? Any plugin whose config write could be destructive to guard?
6. **Where it lives + how it's invoked.** A native modal/sheet over the game (gear button in the chrome) vs a
   full-screen native view? How does it coexist with the live game (pause? overlay?) and the touch model (chrome
   taps must not fall through to the game)? Relationship to the existing `≡` debug panel — replace it or separate?
7. **Sequencing + biggest risk.** Cheapest-correct order to build this WITHOUT regressing the working controls
   or chrome — and the **single most likely thing to be wrong** that the first device step should settle
   (candidate: "does a typed config write from native — color/enum/int — actually land correctly in the live
   plugin and take effect, round-tripped through the bridge's serialization, on device?"). Is read-only
   plugin-list-first → toggle → typed-config the right ladder?

## Constraints / trust rules (unchanged)
- **Device-only** for the real verdict; desktop OpenJDK 21 + CACIO is the develop-first mirror (the
  `MobileUiSmokeRunner` self-test runs the SAME bytes on desktop and device).
- **Trust only verbatim device artifacts** (`jvm-stdio.log`/`hello-shell.log` via `devicectl`) + bash-computed
  md5-gated verdicts (documented false-GREEN history).
- **RuneLite plugins/config must keep working unmodified** — the panel drives the real `PluginManager`/
  `ConfigManager`; it must not corrupt config or fork the plugin ecosystem.
- The **two-lane thread model is load-bearing** — any change to it is the highest-risk thing in this brief.

## Pointers (all in-repo)
- Backend: `runelite-client/src/main/java/net/runelite/client/headless/MobileUiBridge.java` (the facade + JSON
  writer); `MobileUiSmokeRunner.java` (self-test); wiring `runelite-client/.../ui/HeadlessClientUI.java`.
- Native shell: `ios/app/HelloShell/{ViewController.m,JVMLauncher.m,JVMLauncher.h}`; reverse-JNI precedents
  `JavaBridge.java` (`onClickPrecise`) + `MobileChromeBridge.java` (`getSnapshot`).
- Config model: `runelite-client/.../config/{ConfigManager,ConfigDescriptor,ConfigItemDescriptor}.java`;
  desktop reference UI `runelite-client/.../ui/components/config/` (what we're replacing).
- Broader phase-6 brief (panel/polish slice, earlier): `ios/AUDIT-BRIEF-phase6-native-shell.md`.
- Prior briefs/verdicts (the rhythm): `ios/AUDIT-BRIEF-w3-hybrid-mobile-chrome.md`,
  `ios/AUDIT-RESULT-mobile-layout-toplevel-osm.md`.

**Bottom line for the reviewer:** the backend (`MobileUiBridge`, two-lane thread routing) is device-proven; the
risk is now (a) the native panel desyncing from live config state, (b) typed config writes round-tripping
incorrectly through the bridge's string serialization, and (c) any thread-safety gap in reads/writes that the
two-lane facade doesn't already cover. Pick the per-question architecture, name the one thing to de-risk first on
device, and order it so the working controls/chrome/overlays never regress.
