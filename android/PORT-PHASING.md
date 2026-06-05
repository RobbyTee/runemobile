# Android Port — Phasing Plan

> The Android counterpart to `ios/REALJVM-DEV-PLAN.md`. This is a **followable roadmap** to bring the
> Android port (`android/`) from its current state (real RuneLite rendering the OSRS login on the emulator)
> to **parity with the mature iOS shell** (`ios/app/HelloShell/`). It is written to be self-contained for an
> external reviewer: §0–§1 establish where Android is and what the parity target is; §2 states the
> phasing principles carried over from iOS; §3 is the phase-by-phase plan; §4–§6 are cross-cutting workstreams,
> open questions, and a quick-reference sequencing table.
>
> **The single load-bearing fact for everything below:** the entire Java game layer — headless boot, the
> render/input seam, and *every* host-facing facade (`net.runelite.client.headless.*`) — is **already
> compiled into the same shaded jar both ports embed**. iOS proved and uses ~50 bridge methods; Android wires
> 8. So reaching parity is **a Kotlin/native-host + Android-UI effort against an existing, desktop-and-iOS-proven
> seam** — *almost no new Java is required*. Each phase below is mostly "turn on a `-D` gate that already
> exists + write the Android host/UI that calls bridge methods that already exist."

---

## 0. Where Android stands today (the baseline)

**Done and verified (emulator, 2026-06-04):** real unmodified RuneLite JIT-runs in a second in-process
HotSpot (`JNI_CreateJavaVM`), boots headless under CACIO CTC via `DeviceHeadlessBoot.main`, renders the live
OSRS login screen (correct colours) to a `SurfaceView` via `ANativeWindow`, and a tap drives the gamepack
(`loginIndex` advances). See `android/README.md`, `android/app/src/main/cpp/jvm_bootstrap.cpp`,
`android/app/src/main/java/dev/audaro/helloshell/{MainActivity,JvmLauncher,AssetInstaller}.kt`.

**Android is at the iOS "Phase 4-B (live blit + first real touch)" equivalent** — a raw game surface with
three gestures, no Jagex-Mobile shell.

### 0.1 What Android already has (do NOT re-litigate — it's proven)
| Layer | Status on Android |
|---|---|
| Embedded bionic arm64 OpenJDK 21 + free JIT | ✅ (no StikDebug/patching needed — see 0.3) |
| `JNI_CreateJavaVM` host, VM kept alive for process | ✅ `jvm_bootstrap.cpp` |
| CACIO JPMS opts + `UnsafePokeActivation` (eager, pre-bridge) | ✅ `MainActivity.kt:142-166`, `jvm_bootstrap.cpp:411` |
| fake-xawt `libawt_xawt.so` | ✅ `android/dev-tools/fake-xawt/` |
| `DeviceHeadlessBoot.main` → `RuneLiteHeadless` → `HeadlessClientUI` | ✅ shared jar |
| Frame blit: `getFrameSeq/Width/Height/Pixels` → SurfaceView (R↔B swap, seq-skip) | ✅ `jvm_bootstrap.cpp:279` |
| Core input: `onClick`, `onTouch`, `onCameraDrag`, `clearHover` | ✅ `jvm_bootstrap.cpp:215-222` |
| JRE staging + shared-jar staging scripts | ✅ `tools/stage-android-jre.sh`, `tools/stage-mobile-runtime.sh` |

### 0.2 The gap (this whole document)
Android calls **8 of ~50** bridge methods iOS uses. Its UI is **one `SurfaceView` + a `GestureDetector`**
(~370 LOC Kotlin) versus iOS's **~5,000 LOC** across 6 view controllers + a 1,340-LOC `JVMLauncher.m`.
**All the Java bridges that close this gap already ship in the embedded jar** (`MobileUiBridge`,
`MenuPeekBridge`, `MobileChromeBridge`, `HudDataBridge`, `HudVeneerBridge`, `QuestHelperBridge`,
`WorldSwitcherBridge`, plus the unused `JavaBridge` input methods). Each is gated behind a `-D` flag Android
does not yet set.

### 0.3 What Android SKIPS entirely (iOS-only platform tax — for the reviewer's context)
The hardest, most expensive cluster of iOS work has **no Android equivalent and is dropped wholesale** —
Android grants app JIT natively:
StikDebug per-launch attach + `runelite-jit.js` · `patch-jre-txm.sh` libjvm NOPs · `MirrorMappedCodeCache` /
`brk #0x69` mirror · Mach exception-port surgery · `increased-memory-limit` entitlement / `.entitlements` ·
`resign-jre.sh` Team-ID re-sign · the WebKit-breaks-JIT force-restart dance · jetsam-survival scaffolding ·
provisioning/`devicectl`. **This is why the Android port is fundamentally cheaper than iOS from here.**

---

## 1. iOS feature inventory (the parity target)

Master inventory of the iOS shell, with the **exact shared bridge each feature rides on** and **Android's
current status**. Maturity reflects iOS. "Bridge" names the static facade in
`runelite-client/src/main/java/net/runelite/client/headless/` (all already in Android's jar).

### 1.1 Render & core input — *mostly done on Android*
| Feature | iOS maturity | Bridge / method | Android |
|---|---|---|---|
| Live frame blit (int[]→surface, seq-skip) | Proven | `JavaBridge.getFrame{Seq,Width,Height,Pixels}` | ✅ HAVE |
| Tap-to-walk/interact | Proven | `JavaBridge.onClick(x,y,1)` | ✅ HAVE |
| Long-press → engine right-click (button 3) | Proven | `JavaBridge.onClick(x,y,3)` | ✅ HAVE (raw; no native menu) |
| One-finger camera rotate | Proven | `JavaBridge.onCameraDrag` + `clearHover` | ✅ HAVE |
| Drag stream primitive (no synth click) | Proven | `JavaBridge.onTouch(type,…)` | ✅ HAVE |
| Touch-forgiveness / snap-to-entity + selection outline | Proven (Java-side) | runs inside `JavaBridge.onClick` + `drawSelection` (`Hooks.draw`) | ✅ FREE once `onClick` used |

### 1.2 Full touch control scheme — *absent on Android*
| Feature | iOS | Bridge / method | Android |
|---|---|---|---|
| Pinch-to-zoom | Proven | `JavaBridge.onWheel(x,y,rotation)` | ❌ ABSENT |
| Precise UI-proxy tap (no world-snap) | Proven | `JavaBridge.onClickPrecise(x,y,1)` | ❌ |
| Hover/menu-track move | Proven | `JavaBridge.onMove(x,y)` | ❌ |
| Soft keyboard text entry | Proven | `JavaBridge.onText(String)` | ❌ |
| Special keys (backspace/enter/…) | Proven | `JavaBridge.onKeyStroke(vk)` | ❌ |
| Contextual keyboard auto-raise | Proven | `JavaBridge.isTextInputOpen()` | ❌ |
| Interact haptic cue | Proven | `JavaBridge.getInteractTapSeq()` | ❌ |
| Finger snap radius | Proven | `JavaBridge.snapRadius()` | ❌ |

### 1.3 Press-and-peek native right-click menu — *absent on Android*
| Feature | iOS | Bridge / method | Android |
|---|---|---|---|
| Native OSRS-styled right-click menu (slide-to-highlight, release-to-commit) | Proven | read `MenuPeekBridge.{getSnapshot,getTopFingerprint,getPublishSeq}`; commit `JavaBridge.selectMenuPoint(x,y)` | ❌ (gate `runelite.menupeek`) |

### 1.4 On-device Jagex login + autologin — *absent on Android*
| Feature | iOS | Mechanism | Android |
|---|---|---|---|
| `tokens.json` → `JX_*` env → autologin to LOGGED_IN | Proven | `setenv` before `JNI_CreateJavaVM` + `-Drunelite.headless.autologin.*` (`HeadlessLogin`) | ❌ (no JX_* / autologin flags set) |
| On-device OAuth login UI (account picker) | Proven (WKWebView) | WebView consent → `id_token` → `URLSession` session/accounts → write `tokens.json` | ❌ (no login UI; iOS `JagexLoginViewController` has no Android twin) |

### 1.5 Plugin control plane — *absent on Android*
| Feature | iOS | Bridge / method | Android |
|---|---|---|---|
| Plugin list + enable/disable | Proven | `MobileUiBridge.{listPlugins,setPluginEnabled,getMobileUiSeq,pollMobileUiEvents}` | ❌ (gate `runelite.mobileui`) |
| Native typed per-plugin config | Proven | `MobileUiBridge.{getPluginConfig,setConfig,resetConfig,getProcessedWriteId}` | ❌ |
| Plugin Hub browse/install | Proven | `MobileUiBridge.{hubManifest,hubInstall,hubUninstall}` (gate `runelite.hub`, pin `pluginhub.version`) | ❌ |
| Explicit config flush (no clean shutdown) | Proven | `MobileUiBridge.flushConfig()` on close/background | ❌ |

### 1.6 Plugin side-panels — *absent on Android*
| Feature | iOS | Bridge / method | Android |
|---|---|---|---|
| Tier-1 generic rendered Swing panel (zoom/pan) | Proven | `MobileUiBridge.{listPanels,openPanel,closePanel,getPanel{Width,Height,FrameSeq,Pixels},panelClick}` | ❌ |
| Tier-2 native Quest Helper | Proven | `QuestHelperBridge.{isAvailable,setUiOpen,getQuestListJson,getQuestActiveJson,startQuest,stopQuest}` | ❌ |
| Tier-2 native World Switcher | Proven | `WorldSwitcherBridge.{isAvailable,setUiOpen,getWorldListJson,hop}` | ❌ |

### 1.7 Native Jagex-Mobile chrome & HUD veneers — *absent on Android (partial even on iOS)*
| Feature | iOS | Bridge / method | Android |
|---|---|---|---|
| Native edge tab-rails (cover-and-proxy) | Proven | read `MobileChromeBridge.getSnapshot`; proxy `JavaBridge.onClickPrecise` | ❌ (gate `runelite.mobilechrome`) |
| Chat-at-top (engine chat relocation) | Working | `HudVeneerBridge.{relocateChat,setChatHidden}` | ❌ (gate `runelite.hudveneer`) |
| Native minimap/orbs HUD veneer | **Spike/seam (data only) even on iOS** | read `HudDataBridge.{getSnapshot,getSeq}` (gate `runelite.huddata`) | ❌ |
| Full-screen landscape (canvas resize) | Proven on iOS; **reverted** then in-progress (notch) | `-Drunelite.headless.resize=WxH` (`HeadlessResize`) | ❌ |

### 1.8 Debug/lifecycle (iOS-specific, mostly skip)
Debug HUD + log console (port a minimal logcat-backed one if useful), config-flush-on-background (port — see
1.5), JIT-attach gating / jetsam / WebKit-restart (**skip — iOS-only**).

---

## 2. Phasing principles (carried from the iOS port)

The iOS port's discipline is itself load-bearing (it has a documented "false-GREEN" history). Carry these:

1. **The Java seam is already proven on desktop+iOS — Android phases are host/UI work.** Most phases require
   **zero Java changes**: set the `-D` gate, write the Kotlin/native host + Android UI, call existing bridge
   methods. When a phase *does* need Java, call it out explicitly (rare).
2. **md5-gated GREEN, never eyeballed.** Each phase defines a pass/fail sentinel computed in bash and
   md5-compared against **verbatim `adb logcat` / `adb exec-out screencap`** output (the Android analog of the
   iOS `devicectl`-pulled-log rule). No GREEN without the captured line.
3. **Emulator-first, then one real-device confirm.** Android's `rl_arm64` emulator is the desktop-inner-loop
   analog (fast, debuggable). Promote to a real Android device at A0 and re-confirm the platform there once;
   thereafter most phases are emulator-verified with a device spot-check at milestones.
4. **One brief → adversarial review → build → GREEN per phase**, mirroring `ios/AUDIT-BRIEF-*.md`.
   This doc is the umbrella; each phase warrants its own short brief if non-trivial.
5. **iOS UIKit shell is the spec, not a redesign.** Match the proven UX (tap/long-press/camera/pinch, the
   press-and-peek menu, two-tier panels, cover-and-proxy chrome). Don't re-invent interaction models that
   iOS already settled — port them. Deviate only where Android platform idioms demand it (see §4.2).
6. **Reuse the bridge JSON contract verbatim.** Every read-surface bridge returns escaped JSON + a `seq`/
   `writeId` poll model — parse into Kotlin value types exactly as Swift does. No Java-side changes.

---

## 3. The phases

Nine phases, **A0–A8**. Each: **Goal · iOS parallel · Scope (features + exact bridges) · Android-specific work
· GREEN criteria · Depends on · Risk**. Ordering rationale in §6.

---

### Phase A0 — Baseline hardening + real-device gate + autologin-to-LOGGED_IN
**Goal:** make today's emulator result solid, reach **LOGGED_IN** (not just the login screen) from a pushed
`tokens.json`, and confirm the whole platform on a **real Android device** once.
**iOS parallel:** Phase 1 (device JIT confirm) + Phase 4-A (reach LOGGED_IN via pushed tokens + synth clicks).
**Scope / bridges:** no new UI. Wire `tokens.json` → `JX_*` env (`setenv` before `JNI_CreateJavaVM`) +
`-Drunelite.headless.autologin{,.eula,.point,.world,…}` (the exact flag set iOS sets in `JVMLauncher.m:776`;
`HeadlessLogin` is already in the jar). The engine then auto-clicks EULA/Play-Now to LOGGED_IN.
**Android-specific work:**
- Add JX_* env + autologin opts in `jvm_bootstrap.cpp`/`MainActivity.kt` (iOS `loadJagexTokens` is the
  template; `setenv` semantics identical on bionic).
- **Fix `AndroidManifest.xml` orientation:** it is **portrait-locked** (`:15-24`); the game is landscape (iOS
  is landscape-only). Decide + switch to landscape (or sensor-landscape).
- **Fix the stale `AssetInstaller.kt:9-13` doc-comment** (it still claims the abandoned "jniLibs split" model,
  contradicting the assets-only reality — a trap for future contributors).
- Stand up a real Android device (USB debugging) + an `installDebug`/run wrapper; re-confirm boot→LOGGED_IN.
- Optional: a minimal logcat-backed on-screen status line (the iOS debug-HUD analog) for field debugging.
**GREEN:** `adb logcat` shows `gameState` reach `LOGGED_IN` from a pushed `tokens.json` with no manual taps,
**on a real device**; md5-gated sentinel (e.g. `ANDROID_LOGGEDIN_GREEN`). Screencap shows the in-game scene.
**Depends on:** nothing (current state).
**Risk:** low. Real-device GPU/driver differences in the SurfaceView blit are the only unknown; emulator uses
swiftshader.

---

### Phase A1 — Full touch control scheme (input parity) → *playable*
**Goal:** Android plays interactively like iOS Phase 5 — pinch-zoom, keyboard/text, precise taps, hover.
**iOS parallel:** Phase 5 (mobile controls) minus the native menu (that's A2).
**Scope / bridges (all `JavaBridge`, all already in jar — pure host wiring):** `onWheel` (pinch→zoom),
`onClickPrecise` (no-snap), `onMove` (hover), `onText` + `onKeyStroke` + `isTextInputOpen` (keyboard),
`getInteractTapSeq` (haptic), `snapRadius`.
**Android-specific work:**
- Extend the native input enqueue/drain (`jvm_bootstrap.cpp` `InputEvent` + `drainInput`) and `JvmLauncher.kt`
  externs with the new method ids — the existing UI-enqueue→render-thread-drain model extends directly.
- `ScaleGestureDetector` → `onWheel` notches about the pinch centroid; a `GestureDetector` long-press already
  exists. Two-finger vs one-finger disambiguation (camera vs pinch) like iOS.
- A hidden `EditText` (or `InputConnection`) → `onText`/`onKeyStroke`; poll `isTextInputOpen()` each frame to
  auto-raise/dismiss the soft keyboard (bank/GE/PM search).
- `VibrationEffect` (HapticFeedback) on `getInteractTapSeq()` advance; red/yellow tap ring drawn natively.
**GREEN:** on-device md5-gated `ANDROID_CONTROLS_GREEN` — logcat confirms wheel/text/keystroke events reach
the engine (e.g. a typed search opens the bank search; pinch changes zoom varp); screencap evidence.
**Depends on:** A0.
**Risk:** low — every bridge method is iOS-proven; this is mechanical host wiring + standard Android input.

---

### Phase A2 — Press-and-peek native right-click menu
**Goal:** the iOS press-and-peek menu — long-press opens the engine menu, native OSRS-styled overlay, slide to
highlight (haptic per row), release to commit; release-off cancels.
**iOS parallel:** Phase 5 long-press menu (the most sophisticated input feature).
**Scope / bridges:** gate `-Drunelite.menupeek=true` (+ `menupeek.maxEntries=14`). Read
`MenuPeekBridge.getSnapshot()` (`{menuOpen,menuX/Y/W/H,count,entries[…]}` in display order) +
`getTopFingerprint()` (release-revalidation) + `getPublishSeq()`. Commit via `JavaBridge.selectMenuPoint(x,y)`
(clicks the computed engine row → honours plugin `onClick`/Menu-Entry-Swapper; clears parked hover).
**Android-specific work:** the first **custom native overlay drawn over the live SurfaceView** — a menu View
positioned at the finger, body opening away from the holding hand, row hit-testing on OSRS 19px-header/15px-row
geometry, haptic tick per row change. Port the iOS state machine (`onLongPress:`/`tryBuildNativeMenu`/
`presentNativeMenu`/`commitMenuSelection`) to Android gestures + a Compose/View overlay.
**GREEN:** on-device — long-press an NPC, the native menu lists the engine entries, release on "Attack"
attacks (logcat shows the menu action); md5-gated `ANDROID_MENUPEEK_GREEN`.
**Depends on:** A1 (uses `onMove`/precise click).
**Risk:** medium — first overlay-over-SurfaceView compositing; the Java targeting is already proven, the risk
is purely Android overlay/gesture timing.

---

### Phase A3 — On-device Jagex login (self-contained app)
**Goal:** log in on the phone with no desktop — the iOS `JagexLoginViewController` flow on Android.
**iOS parallel:** the login spike + Phase 4-A token plumbing.
**Scope / mechanism:** `android.webkit.WebView` runs the Stage-A OAuth consent flow (ephemeral cookie store);
intercept the `http://localhost#id_token=…` redirect in a `WebViewClient`; mint the session with OkHttp/
`HttpURLConnection` (`/game-session/v1/sessions` → `sessionId`, `/v2/accounts` → character list, **with the
launcher User-Agent** for Cloudflare); account-picker dialog; write `Documents/tokens.json`. Then A0's JX_*
plumbing arms autologin. The OAuth *logic* (endpoints/PKCE/sequence) is portable and proven —
`ios/dev-tools/fetch-shield-tokens.sh` + `JagexLoginViewController.m` are direct templates.
**Android-specific work:** net-new — WebView + HTTP client + picker dialog + token persistence. **None of the
iOS WebKit-breaks-JIT force-restart dance is needed** (Android has no JIT/WebView conflict — drop it entirely).
**GREEN:** on-device — fresh login (no pushed tokens) reaches LOGGED_IN; `tokens.json` written; account switch
works. md5-gated on the session-mint HTTP 200 + the LOGGED_IN transition.
**Depends on:** A0 (JX_* plumbing). Independent of A1/A2 — could run in parallel.
**Risk:** medium — Cloudflare UA sensitivity + WebView redirect interception are the fiddly parts; both solved
on iOS as a reference.

---

### Phase A4 — Plugin control plane (enable/disable, native config, hub)
**Goal:** native plugin management + typed per-plugin config + Plugin Hub — iOS Phase 6 config.
**iOS parallel:** Phase 6 native plugin-config panel.
**Scope / bridges:** gate `-Drunelite.mobileui=true` (+ `runelite.hub=true`, `pluginhub.version` pin).
`MobileUiBridge.{listPlugins,setPluginEnabled,getMobileUiSeq,pollMobileUiEvents}` (enable/disable);
`{getPluginConfig,setConfig,resetConfig,getProcessedWriteId}` (typed config); `{hubManifest,hubInstall,
hubUninstall}` (hub); `flushConfig()` on close/background. **Bridge owns its own EDT/client-thread lanes** — the
host calls the static methods from any thread; no Android threading care needed beyond not blocking the UI.
**Android-specific work:** real Android UI screens — a plugin list (switches + status), a typed config screen
(bool→Switch, int/double→numeric field+clamp, enum→picker, color→color-picker, string→text), a hub
browse/install list. Parse the bridges' escaped JSON into Kotlin types. **Wire `flushConfig()` to
`onPause`/screen-close** (Android has no RuneLite shutdown flush either — same discipline as iOS).
**GREEN:** toggle a plugin (it starts/stops in logcat), write+reset a config value (round-trips, survives app
restart via flush), hub install→appear→uninstall. md5-gated `ANDROID_MOBILEUI_GREEN`.
**Depends on:** A0. Independent of A1–A3.
**Risk:** low-medium — large UI surface but the bridge is iOS-proven and self-test-covered (`MobileUiSmokeRunner`
exists in-jar and can be armed via `-Drunelite.mobileui.smoke` to validate the seam before building UI).

---

### Phase A5 — Plugin side-panels (rendered fallback + curated native)
**Goal:** the two-tier panel system — generic rendered-image fallback for any panel, curated native screens for
Quest Helper + World Switcher.
**iOS parallel:** Phase 6 plugin-panels (design "SETTLED": rendered-image fallback + a few curated natives;
generic Swing→UIKit auto-extraction was explicitly **rejected** — do not attempt it on Android either).
**Scope / bridges:** Tier-1 `MobileUiBridge.{listPanels,openPanel,closePanel,getPanel{Width,Height,FrameSeq,
Pixels},panelClick}` (blit the headless-rendered Swing panel into a zoom/pan view; map taps back).
Tier-2 `QuestHelperBridge.*` + `WorldSwitcherBridge.*` (native data-driven screens; fall back to Tier-1 when
`isAvailable()` is false).
**Android-specific work:** a panel host (dock/drawer), a zoomable image view (`getPanelPixels` → Bitmap,
pinch-zoom, tap→`panelClick`), and two native screens (Quest Helper segmented browse/current; World Switcher
filterable list+hop) parsing the curated JSON. Call `setUiOpen(true/false)` so the bridges only tick while open.
**GREEN:** open a rendered panel (e.g. XP tracker) and interact; open native Quest Helper, start a quest;
native World Switcher, hop worlds. md5-gated per panel.
**Depends on:** A4 (`MobileUiBridge` wired) for Tier-1; A4's gate also enables panels.
**Risk:** medium — Tier-1 panel rendering shares the frame-blit pattern; the curated screens are straight
JSON→UI.

---

### Phase A6 — Native Jagex-Mobile chrome (tab rails + chat-at-top)
**Goal:** the OSRS-Mobile look — native edge tab-rails over the live stones (cover-and-proxy) and the engine
chat relocated to the top.
**iOS parallel:** Phase 6 hybrid chrome (the "flip to mobile toplevel 601" path is **RED** — don't attempt;
cover-and-proxy is the proven primitive).
**Scope / bridges:** gate `-Drunelite.mobilechrome=true` — read `MobileChromeBridge.getSnapshot()`
(`{activeTab,tabs[14],sidePanel,minimap,orbs,chat,viewport}`); draw native tab stones over the live ones; a tab
tap proxies `JavaBridge.onClickPrecise(centre,1)` onto the live stone (no widget is moved/hidden). Gate
`-Drunelite.hudveneer=true` — `HudVeneerBridge.relocateChat(x,y,transparent)` moves the **engine's own** chat to
the top (tabs/scrollback/colours/input stay live), re-applied each frame.
**Android-specific work:** composite native tab-stone Views over the SurfaceView (port the iOS
`tabassets/` sprites + `buildTabAssets`/`updateChrome`/`layoutColumn` cover-and-proxy logic), a `▦` A/B toggle
to raw chrome, and the one-shot chat relocation on login. **Note `mobilechrome.repos` (engine-side stone
repositioning) was tried and REVERTED on iOS — do not use it.**
**GREEN:** native edge rails track the engine stones and switch tabs; chat renders at the top, still live.
md5-gated `ANDROID_CHROME_GREEN`.
**Depends on:** A1 (`onClickPrecise`).
**Risk:** medium — per-frame overlay layout tracking the snapshot; the seam is iOS-proven.

---

### Phase A7 — Native HUD veneers + edge-to-edge landscape
**Goal:** the current iOS *frontier* — suppress the engine HUD, draw native minimap/orbs/chat over an
edge-to-edge scene. **Lowest-confidence phase (partial even on iOS).**
**iOS parallel:** W3 Track A (native HUD veneers) — A0–A7 there; chat veneer in progress, minimap/orbs settled
as "cover-in-place" (the minimap *cannot* be hidden without killing walk-to).
**Scope / bridges:** `HudDataBridge.{getSnapshot,getSeq}` (`{orbs,minimap:{plane,px,py,mapAngle,players,npcs,
dots},chat}` — rendering-path-agnostic, reads `Client` not the framebuffer). Edge-to-edge via
`-Drunelite.headless.resize=WxH` (`HeadlessResize`) + selectively hiding HUD widgets (the iOS A0 finding: no
single lever both removes the HUD and keeps it clickable → **minimap/orbs are cover-in-place + proxy-click**,
only chat is fully native).
**Android-specific work:** native minimap/orb/chat rendering (Canvas/Compose) fed by `HudDataBridge`; canvas
resize to the device landscape safe-area; **respect the notch/cutout** (the iOS edge-to-edge work was reverted
because the engine HUD fell under the notch — use Android `WindowInsets`/`displayCutout` from the start).
**GREEN:** native orbs/minimap track live game state; scene fills the safe area; walk-via-(covered)-minimap
works. md5-gated, staged like the iOS `HUD_DATA_GREEN`/`EDGE2EDGE_GREEN`/`MINIMAPWALK_GREEN`.
**Depends on:** A6 (chrome), A2 (proxy-click for cover-in-place minimap).
**Risk:** **high** — this is unfinished on iOS; expect to inherit its open questions (HUD-suppression levers,
notch handling). Treat as research, not mechanical.

---

### Phase A8 — Stability, lifecycle, packaging & install
**Goal:** a stable, installable, self-contained Android app — iOS Phases 7–8.
**iOS parallel:** Phase 7 (stability/memory/updates) + Phase 8 (packaging/install).
**Scope:** multi-hour soak (the VM is already kept alive for the process — verify no leak/GC pathology under
`-Xmx512m`); Activity-recreation / app-background survival (already partly handled via the process-wide
`booted` flag + VM-never-destroyed); `flushConfig` on background (from A4); surviving weekly Jagex updates
(mitigated free by RuneLite self-updating the gamepack); a clean signed APK + first-run UX (JRE staged via
`AssetInstaller`; no JIT-attach UX needed — Android's huge simplification); optional multi-ABI
(`armeabi-v7a`/`x86_64`) if a non-arm64 target ever matters (today: arm64 only).
**GREEN:** N-hour in-game soak with stable memory + FPS on a real device; clean install→login→play from a
fresh APK. md5-gated soak summary.
**Depends on:** the feature phases it stabilizes.
**Risk:** low-medium — mostly verification + packaging.

---

## 4. Cross-cutting Android workstreams

### 4.1 The host↔bridge wiring pattern (repeated every phase)
For each new facade: (1) set its `-D` gate in `MainActivity.kt`'s opts; (2) in `jvm_bootstrap.cpp` (or a new
`JvmLauncher.kt` JNI extern) cache the `FindClass` + `GetStaticMethodID`s once; (3) call the static methods —
reads return escaped JSON (parse to Kotlin), writes are fire-and-forget with a `writeId` ack. **Thread safety:**
the thick `MobileUiBridge` dispatches its own EDT (plugin lifecycle) and client-thread (config) lanes
internally, so the host may call from any thread; the hot `JavaBridge` input methods post to the toolkit
EventQueue and are already called from Android's render-thread drain. Mirror the iOS `JVMLauncher.h` method
surface — it *is* the portable contract.

### 4.2 Android UI architecture — **decision needed (see §5)**
iOS is UIKit view controllers compositing over a `UIImageView`. Android must composite native chrome/menus/
panels over a live `SurfaceView`. Candidates: **(a) Jetpack Compose** over an `AndroidView(SurfaceView)`
(modern, fast to build overlays/panels, good for the data-driven screens) vs **(b) classic Views/Fragments**
(closer 1:1 with the UIKit structure). Recommendation leans Compose for the panels/config/menus with the
SurfaceView as an interop layer — but this is the single biggest Android-specific architectural choice and
should be settled before A2 (the first overlay). **Flagged for the reviewer.**

### 4.3 Threading, coordinate mapping, render scaling
- Input: keep the UI-thread-enqueue → render-thread-drain model (`jvm_bootstrap.cpp`); the ART UI thread must
  never attach to HotSpot. New input methods extend `drainInput`.
- Coords: the existing `toGameX/Y` (view-px→game-px via `frameWidth/Height`) generalizes; overlays need the
  inverse (game-rect→view-rect) like iOS `viewRectForGameX:y:w:h:` for chrome/menu placement.
- Render scaling: iOS tunes `renderH` (FPS↔sharpness↔UI-size). Android sets the same via
  `-Drunelite.headless.resize` — pick a device-appropriate default in A0/A7.

### 4.4 Config-flush discipline
Like iOS, Android never runs RuneLite's shutdown/periodic flush — **must** call `MobileUiBridge.flushConfig()`
on screen-close and `onPause`/`onStop`, or plugin toggles + config edits silently don't persist (A4).

---

## 5. Open questions / risks for the reviewer

1. **UI framework: Compose vs Views/Fragments?** (§4.2) — the biggest architectural fork; affects every UI
   phase. Is Compose-over-SurfaceView the right call, or does the overlay-heavy, per-frame-tracked chrome favour
   a custom View hierarchy closer to the UIKit structure?
2. **Phase ordering:** is A3 (on-device login) correctly placed *after* A1/A2, or should it precede them so the
   app is self-contained sooner? (It's independent of A1/A2; pushed `tokens.json` unblocks all emulator dev.)
3. **A7 scope realism:** the native HUD veneer is unfinished on iOS. Should Android attempt it at all in v1, or
   ship parity-minus-veneers (keep the engine HUD in-frame, as Android does today) and treat A7 as post-v1?
4. **Real-device target:** which Android device/OS is the v1 target? (Affects A0's GPU/blit confirm, A7's notch
   handling, and whether `armeabi-v7a`/`x86_64` ABIs are ever needed.)
5. **Landscape vs portrait:** confirm the app should be landscape-locked like iOS (the manifest is currently
   portrait — A0 fixes this, but confirm the product intent).
6. **Parallelism:** A4 (plugin control) and A3 (login) are independent of the input phases — worth running in
   parallel if effort allows? Or keep strictly serial for the brief→review→GREEN cadence?
7. **Any Java changes at all?** This plan asserts ~zero new Java. Is there a phase the reviewer believes needs
   a Java-side change (e.g. an Android-specific bridge method) that we've missed?

---

## 6. Sequencing rationale & quick reference

**Why this order:** A0 first (cheapest — solidify + reach in-game + real-device gate, mostly host plumbing).
A1 next (highest play-value-per-effort: full input is pure host wiring against proven bridges → the app
becomes *playable*). A2 builds on A1's input for the signature menu UX. A3 (login) is independent and can slot
anywhere after A0 — placed here so core play is proven first. A4→A5 (plugin control → panels) are the bulk of
the "native shell" and unlock RuneLite's actual value-add. A6 (chrome) is the cosmetic Jagex-Mobile identity.
A7 (HUD veneers/edge-to-edge) is last among features because it's the only phase that's *unfinished on iOS* —
highest risk, lowest confidence. A8 stabilizes/ships.

| Phase | Deliverable | Primary gate(s) | New bridges wired | iOS parallel | Risk |
|---|---|---|---|---|---|
| **A0** | In-game (LOGGED_IN) + real device | `autologin.*` (+ JX_* env) | — (JavaBridge already wired) | P1 + P4-A | Low |
| **A1** | Playable: pinch/keyboard/precise/hover/haptic | `bridge` (existing) | `JavaBridge` input methods | P5 | Low |
| **A2** | Press-and-peek native menu | `menupeek` | `MenuPeekBridge` + `selectMenuPoint` | P5 | Med |
| **A3** | On-device Jagex login | — (login UI) | (writes tokens → A0 path) | login spike | Med |
| **A4** | Plugin list/config/hub | `mobileui`, `hub` | `MobileUiBridge` | P6 config | Low-Med |
| **A5** | Plugin panels (rendered + curated) | `mobileui` | `+QuestHelper/WorldSwitcher` | P6 panels | Med |
| **A6** | Native tab-rails + chat-at-top | `mobilechrome`, `hudveneer` | `MobileChromeBridge`,`HudVeneerBridge` | P6 chrome | Med |
| **A7** | Native HUD veneers + edge-to-edge | `huddata` (+`resize`) | `HudDataBridge` | W3 Track A | **High** |
| **A8** | Soak + signed APK + install UX | — | — | P7–P8 | Low-Med |

**Definition of done (Android):** a self-contained sideloaded/installed Android app — launch → on-device Jagex
login → in-game on live OSRS, full touch + Jagex-Mobile-style UI, RuneLite plugins toggle/config natively with
overlays composited, playable FPS, stable for hours — **no host, no desktop, no streaming, and (unlike iOS) no
per-launch JIT-attach ceremony.**

---

### Appendix — companion source references
- iOS plan/why/findings: `ios/REALJVM-DEV-PLAN.md`, `ios/REALJVM-FEASIBILITY-SCOPE.md`, `ios/FINDINGS.md`.
- iOS shell (the UX spec): `ios/app/HelloShell/{ViewController,JVMLauncher,PluginPanelsViewController,
  PluginConfigViewController,PluginHubViewController,JagexLoginViewController}.m` + `JVMLauncher.h` (the host↔game contract).
- Shared bridges (already in Android's jar): `runelite-client/src/main/java/net/runelite/client/headless/*`,
  `runelite-client/src/main/java/net/runelite/client/callback/Hooks.java`, `ios/dev-tools/awtsmoke/{DeviceHeadlessBoot,UnsafePokeActivation}.java`.
- Android baseline: `android/README.md`, `android/app/src/main/cpp/jvm_bootstrap.cpp`,
  `android/app/src/main/java/dev/audaro/helloshell/*.kt`, `android/dev-tools/fake-xawt/`.
- Staging: `tools/stage-android-jre.sh`, `tools/stage-mobile-runtime.sh`, `PORTS.md`.
