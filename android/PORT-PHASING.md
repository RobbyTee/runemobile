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
> seam** — *almost no new **shared Java** is required*. Each phase below is mostly "turn on a `-D` gate that
> already exists + write the Android host/UI that calls bridge methods that already exist."
>
> **Honest scoping caveat (reviewer-corrected):** "almost no new Java" means no new *shared/`net.runelite`*
> Java — it does **not** mean the work is small. Each phase still adds a real, non-trivial **Android host
> surface**: new JNI externs in `jvm_bootstrap.cpp` + `JvmLauncher.kt` (today only 8 methods are bridged) and
> the Android UI itself. The Java seam being done removes the *engine-risk*, not the host/UI labour.

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
(313 LOC Kotlin, verified `wc -l`) versus iOS's **~5,000 LOC** across 6 view controllers + a 1,340-LOC `JVMLauncher.m`.
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
| Plugin Hub browse/install | Proven | `MobileUiBridge.{hubManifest,hubInstall,hubUninstall}` (gate `runelite.hub`, pin `runelite.pluginhub.version` — iOS pins `1.12.27`; our `1.12.28-SNAPSHOT` has no hub manifest → 404) | ❌ |
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
   iOS `devicectl`-pulled-log rule). No GREEN without the captured line. **No Android harness exists yet** —
   building the first md5-gate script is part of A-1/A0. **Caveat (reviewer):** raw `logcat` md5s are brittle
   (timestamps/pids/thread ids vary) → **md5 a normalized, grepped sentinel line**, not the raw stream; and
   screencap md5s need controlled orientation + a fixed crop (status bar/cutout stripped) to be stable.
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

Ten phases, **A-1 + A0–A8**. Each: **Goal · iOS parallel · Scope (features + exact bridges) · Android-specific
work · GREEN criteria · Depends on · Risk**. Ordering rationale in §6.

---

### Phase A-1 — Bridge/flag census harness (cheapest de-risk; added per review)
**Goal:** before building any UI, *prove from the running Android VM* that every bridge method and `-D` gate
the A0–A8 mainline depends on actually resolves and behaves — turning the plan's central "the Java is already
there" claim into a captured, md5-gated fact rather than a source-tree inference.
**iOS parallel:** the bridge-surface this mirrors is `JVMLauncher.h` (the proven host↔game contract).
**Scope:** a probe that, under each relevant `-D` flag set, JNI-resolves *every* method named in §1 (the iOS
contract surface) on its facade class and logs pass/fail per method; plus a flag-census that confirms each gate
(`runelite.mobileui` / `mobilechrome` / `menupeek` / `huddata` / `hudveneer` / `hub` / `headless.autologin.*`)
flips the bridge from inert to active. This also lays down the **first md5-gate script** (per §2.2) that every
later phase reuses.
**Android-specific work:** a small native or `JvmLauncher` probe entry + a bash harness that diffs the resolved
method set against the iOS `JVMLauncher.h` surface and md5s a normalized sentinel.
**GREEN:** md5-gated `ANDROID_BRIDGE_CENSUS_GREEN` — logcat lists every required bridge method as RESOLVED and
every gate as honored; any unresolved method is the *real, scoped* surprise the plan should know about up front.
**Depends on:** nothing (current state).
**Risk:** very low — it only reads. High value: it converts every downstream "no new Java needed" assumption
into evidence and front-loads any nasty surprise.

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
- **Fix `AndroidManifest.xml` orientation:** it is **portrait-locked** (`:19`, `screenOrientation="portrait"`);
  the game is landscape (iOS is landscape-only). Switch to **`sensor-landscape`** (reviewer-recommended; see §5 Q5).
- **Fix the stale `AssetInstaller.kt:9-14` doc-comment** (it still claims the abandoned "jniLibs split" model —
  but `MainActivity.kt:119` dlopens libjvm from staged `filesDir/jvm/jre/lib/server`, contradicting the
  assets-only reality. A trap for future contributors; see §5 Q11 on whether that dlopen path is even
  real-device-safe).
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
hubUninstall}` (hub, with `-Drunelite.pluginhub.version=1.12.27` — SNAPSHOT 404s); `flushConfig()` on close/background. **Bridge owns its own EDT/client-thread lanes** — the
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

## 3G. Track G — GPU rendering (base `gpu` first; 117HD best-effort on top)

**This is a parallel high-risk track, the Android counterpart to iOS "Track B."** It exists because of a
product desire for HD visuals — but **"117HD" is three different decisions wearing one name, and conflating
them is the trap** (over-invest, or over-promise):
1. **Do we want HD *visuals* / a real-client look?** — the actual emotional ask.
2. **Do we want the base `gpu` plugin** (GPU rasterization: sharper, higher FPS, AA, draw distance)?
3. **Do we want the *117HD plugin specifically*** (compute-shader lighting/shadows/water on top of `gpu`)?

**Strategic reframe (decided): the target is "GPU rendering, with 117HD best-effort," not "117HD works."**
Base `gpu` is the real prize and the legitimate first target — it is ~80% of the "looks like a real client"
win and the foundation 117HD requires anyway. 117HD's shadows/water/post are the last 20% *and* the riskiest
20%. **Do not promise the user "enable 117HD → HD"** — that may be unkeepable. Promise "GPU rendering: sharper
+ faster"; frame full 117HD as best-effort that may land partially.

**Honest odds (my estimate, to set expectations):** AG0 green ~90% · AG1 green ~50% (the real coin-flip) ·
base `gpu` ships if AG1 green ~60% · *full* 117HD ~20% (partial ~60%). **Expected outcome: base GPU rendering
probably yes, full 117HD probably no, partial 117HD plausible.** This is exactly *why* Q8 keeps it off the v1
gate.

**Why this is a track, not a toggle.** Both ports render the 3D scene on the **CPU** (CACIO software
rasterizer) and hand the host an `int[]` framebuffer (`JavaBridge.snapshot`/`getFramePixels`). 117HD — and the
base RuneLite `gpu` plugin it builds on — **replace that rasterizer with a GPU pipeline**: `org.lwjgl.opengl.
GL33/GL45` (verified: 38 `glGet*` calls in `runelite-client/.../plugins/gpu/`), the `net.runelite.rlawt.
AWTContext` native lib (`librlawt`, binds a GL context to the AWT Canvas peer), desktop GLSL, and — for 117HD —
a **compute-shader** pipeline (GL 4.3 / GL ES 3.1 compute) plus heavy lighting/shadow/water shaders. The LWJGL
natives in our build ship **desktop-only** (`build.gradle.kts:93` `classifier("natives-$platform")`). So
enabling 117HD with no GPU track = it appears in the hub (A5) and **fails to start** (no GL context) — exactly
how the iOS plugin panel marks GPU plugins "couldn't start (incompatible)". Making it *work* requires porting
RuneLite's GPU stack. Target order (117HD builds on the same context/native infra): **base `gpu` first**, then
117HD.

**Why Android is a much better GPU target than iOS** (this is the strategic point — the iOS GPU brief
`ios/AUDIT-BRIEF-w3-gpu-renderer.md` blockers B1/B2/B3 + Claim D largely dissolve here):
- **B1 (native LWJGL):** iOS needs an immature iOS-arm64 LWJGL rebuild. **Android: the PojavLauncher/Amethyst
  lineage our bionic JRE already comes from ships a bionic-arm64 LWJGL + GL-translation stack
  (gl4es / holy-gl4es / LTW / ANGLE) built precisely to run LWJGL desktop-GL Minecraft on Android.** The
  hardest binary blocker is largely pre-solved in our own ecosystem. **⚠ Reviewer caveat (do not over-claim):**
  there is **no bionic/Android LWJGL classifier in *this repo* today** — `build.gradle.kts:84-95` ships
  `natives-{linux,linux-arm64,macos,macos-arm64,windows*}` only, **no `natives-android`**. The Pojav stack must
  be *imported and proven*, not assumed present. And that stack targets a **GLFW/GLFM-windowed** Minecraft, not
  a **headless AWT** engine — whether its translation path even initializes with no GLFW window is itself an
  AG1 open question, not a given.
- **B2 (no desktop GL):** iOS has only GLES(deprecated)+Metal → needs ANGLE→Metal. **Android exposes native
  GLES 3.1+ (with compute) and Vulkan** → 117HD's compute pipeline is *directly reachable*; the same
  translation libs handle desktop-GL→GLES.
- **B3 (headless/no window):** iOS needs an EAGL/Metal-backed FBO. **Android: a `SurfaceView` natively hosts an
  EGL/GLES context** — render the engine's GL straight onto the Surface (the "bigger win" restructure the iOS
  brief describes is the *natural* Android path), or `glReadPixels`→`int[]` for a first cut.
- **iOS Claim D (GL driver vs the JIT mirror / exception-port setup):** a **non-issue** on Android — free JIT,
  no code-cache mirror, no Mach-port surgery.

**What kills AG2/AG3 first (reviewer-sharpened ordering of the risk):**
- **AG2 most likely dies on `AWTContext`/LWJGL/`OpenGL33`-on-bionic before any shader work.** `GpuPlugin`
  hard-requires `AWTContext.loadNatives()` + `new AWTContext(client.getCanvas())` (`GpuPlugin.java:288,299`),
  then `GL.createCapabilities()` and a hard `if (!glCapabilities.OpenGL33) return false` (`GpuPlugin.java:311,316`).
  GLES 3.1 + compute does **not** automatically satisfy a desktop-`OpenGL33`/`OpenGL45` capability probe — the
  translation layer (gl4es/ANGLE) must convincingly *report* those caps to LWJGL, or `gpu` refuses to start.
- **AG3 most likely dies on compute + desktop-GL semantics + external plugin loading** (117HD's compute
  pipeline, `glClipControl` Z-range, UBO layout, texture-array `glTexStorage3D`).

**Shared blockers that remain (same as iOS):**
- `librlawt`/`AWTContext` binds GL to the AWT Canvas's native peer; the headless CTC has none → must be
  **replaced by an offscreen/Surface EGL-context shim**, *without touching the unmodified plugin Java* (iOS
  Claim F). This is the one real native/Java-adjacent piece — and it is **load-bearing**, since `GpuPlugin`
  constructs `AWTContext` directly from the canvas peer (`new AWTContext(client.getCanvas())`, can't be avoided
  without editing the plugin). **Resolution (the constraint IS satisfiable):** `AWTContext` is only the JNI face
  of `librlawt.so`. **Reimplement `librlawt.so` for Android** — same JNI contract, but it ignores the (peerless)
  canvas handle and creates an EGL context against a pbuffer / the `SurfaceView` Surface. `AWTContext.java` and
  `GpuPlugin.java` stay byte-for-byte unmodified. So the "no plugin Java change" rule holds — **the work is a
  native drop-in, not a Java fork.** (Same shaded jar both ports embed; only the native `.so` differs by ABI.)
- **Desktop-GL→GLES semantic down-port** (UBO layout, `glClipControl` Z-range, `glMultiDrawElements`,
  texture-array `glTexStorage3D`, compute) — via the translation layer or a shader port; some 117HD features
  may not survive cleanly.

**Sub-phases (cheapest-correct first, strict go/no-go gates — mirrors the iOS B0→B3 ladder):**

- **AG0 — GLES recon (a day, HARD GATE — run NOW, standalone).** Do this *immediately*, decoupled from the
  A-track and even from A1 — it's information-cheap and decision-rich. On the **real target device** (not
  swiftshader, which under-reports), query `glGetString(GL_VERSION/RENDERER/SHADING_LANGUAGE_VERSION)` +
  `GL_MAX_COMPUTE_*`. Confirms base `gpu` (≥ GLES 3.0 / 3.3-core-equiv) and/or 117HD (≥ GLES 3.1 + compute) are
  even reachable. md5-gated tier readout. (Expected green — any modern arm64 device is GLES 3.1+ compute.)
- **AG1 — THE go/no-go for the entire track. Define it ruthlessly; make it fail FAST (weeks).** From the
  on-process HotSpot, load the PojavLauncher-lineage bionic LWJGL, create an EGL context (pbuffer or on the
  SurfaceView Surface) with **no GLFW window**, and — the crux — get **`GL.createCapabilities()` to report
  `OpenGL33 == true`**, then compile a trivial shader, draw a triangle → read back / present. **This single
  result gates everything downstream.** The likely death is here, not in shaders: a real GLES context reports
  *GLES*, not desktop GL 3.3, so the translation layer (gl4es/ANGLE) must present a desktop-GL face that LWJGL
  binds **headlessly** — exactly the thing Pojav does only *through* GLFW. Design AG1 to die cheaply at that
  binding step. **Green → base `gpu` is genuinely reachable and worth funding AG2. Red → STOP before AG2.**
- **AG2 — base `gpu` plugin bring-up (weeks–months; THE render-path decision).** Replace `librlawt` with the
  EGL shim; down-port `gpu`'s GL/GLSL to the available GLES tier (or run it through gl4es/ANGLE); route output.
  **Render-path decision (DECIDED — staged, see §5 Q9):** use `glReadPixels`→`JavaBridge` int[] through the
  existing SurfaceView blit for *bring-up* (isolates "did GL come up" from restructuring the render seam / A7),
  then move to **direct EGL-to-Surface for production** once the pipeline is proven (readback's GPU→CPU→GPU
  round-trip throttles FPS). **Resolves the HD↔FPS tension on its own** and is the legitimate high-value first
  GPU target. Milestone: the live scene renders on GPU, FPS + sharpness both up.
- **AG3 — 117HD/RLHD (largest; partial success the realistic target, not full).** The compute pipeline + heavy
  shaders on the proven base. GLES 3.1 compute makes it more reachable than on iOS, but the desktop-GL→GLES
  down-port (UBO layout, `glClipControl` Z-range, SSBOs, the lighting/shadow/water/post passes) is large and
  some features genuinely won't survive. **Honest target: HD lighting/textures plausibly; shadows/water/post
  best-effort.** Only attempt after AG2 ships and there's appetite for the long tail. Don't let "enable 117HD"
  become a promise of *full* 117HD.

**Dependencies & intersections:**
- **Plugin enable-ability:** needs **A4/A5** (plugin hub) so 117HD appears and can be toggled.
- **Render path:** AG2's "glReadPixels vs direct-to-Surface" choice **changes the core blit** the rest of the
  app is built on — it intersects **A7** (edge-to-edge) and the **2D-UI compositing** (the `gpu` plugin's
  `drawUi` uploads the 2D framebuffer as a GL texture; the more of the HUD that A7 makes native, the less must
  be composited). Track G and A7 **meet at the same render seam** (`JavaBridge.snapshot` / the blit) — exactly
  as iOS Track A and Track B do. Coordinate them.

**Risk: HIGHEST in the project** (multi-week-to-months, real chance of only partial 117HD success). Run AG0+AG1
as a hard go/no-go before committing to AG2. **But** the base `gpu` plugin is a legitimate, high-value,
*Android-favoured* first target that on its own transforms the visuals — and unlike iOS, the foundation
(bionic LWJGL + GLES compute + EGL-on-Surface + free JIT) is already largely under us.

**Calendar guidance (pull the recon forward, even though the track is post-v1).** Track G does not *gate*
anything (Q8), but **GPU-on-Android is the one axis where this port can genuinely beat iOS** — iOS Track B is
stuck on an iOS-arm64 LWJGL rebuild + ANGLE→Metal, while Android has native GLES compute + the Pojav lineage.
The information that tells you whether that upside is real (AG0 now; AG1 as a funded spike once the playable
shell A0–A2 exists) is *cheap relative to the upside*. So: don't sequence AG0/AG1 dead-last just because the
track is a fast-follow — buy the cheap, high-leverage information early; commit the expensive AG2/AG3 build only
on a green AG1. If AG1 is red, the honest answer is "117HD/GPU is not happening on this stack" and the CPU shell
*is* the product — which is fine, because v1 never depended on it.

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

### 4.2 Android UI architecture — **DECIDED: hybrid**
iOS is UIKit view controllers compositing over a `UIImageView`. Android must composite native chrome/menus/
panels over a live `SurfaceView`. **Decision (maintainer + reviewer): a hybrid split, not one paradigm.**
- **Classic Views / `Canvas` for the live, per-frame overlays** drawn over the `SurfaceView` — A2 press-and-peek
  menu, A6 chrome tab-rails, A7 HUD veneers. These track engine geometry *every frame* and are z-order- and
  latency-sensitive; Compose recomposition-per-frame is wasteful and the SurfaceView/Compose interop z-ordering
  has known sharp edges on exactly this path.
- **Jetpack Compose for the data-driven screens** — A4 plugin list/config/hub, A5 panels + Quest Helper / World
  Switcher. List/form/picker UI is Compose's sweet spot and these aren't on the frame-critical path.
Settle the View-side overlay scaffolding before A2 (the first overlay).

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

1. **UI framework: Compose vs Views/Fragments?** (§4.2) — **DECIDED: hybrid.** Classic Views/`Canvas` for the
   live per-frame overlays (A2 menu, A6 chrome, A7 HUD); Jetpack Compose for the data-driven screens (A4
   config/hub, A5 panels, quest/world). See §4.2.
2. **Phase ordering:** is A3 (on-device login) correctly placed *after* A1/A2, or should it precede them so the
   app is self-contained sooner? (It's independent of A1/A2; pushed `tokens.json` unblocks all emulator dev.)
   **Reviewer recommendation:** move A3 to right after A0 (or parallel with A1) — self-contained login is
   foundational, not polish. *(Sequencing recommendation; final order is the maintainer's call.)*
3. **A7 scope realism:** the native HUD veneer is unfinished on iOS. Should Android attempt it at all in v1, or
   ship parity-minus-veneers (keep the engine HUD in-frame, as Android does today) and treat A7 as post-v1?
   **Reviewer recommendation:** defer A7 from v1 — even iOS hasn't finished native HUD veneers. *(Plan already
   leans this way; reviewer concurs.)*
4. **Real-device target:** which Android device/OS is the v1 target? (Affects A0's GPU/blit confirm, A7's notch
   handling, and whether `armeabi-v7a`/`x86_64` ABIs are ever needed.) **Reviewer recommendation:** one modern
   arm64 device first (Pixel 8/9-class, GLES 3.1+), then one midrange sanity device; **do not** support
   `armeabi-v7a`/`x86_64` for v1.
5. **Landscape vs portrait:** confirm the app should be landscape-locked like iOS (the manifest is currently
   portrait — A0 fixes this, but confirm the product intent). **Reviewer recommendation + plan decision:** lock
   **`sensor-landscape`** (folded into A0).
6. **Parallelism:** A4 (plugin control) and A3 (login) are independent of the input phases — worth running in
   parallel if effort allows? Or keep strictly serial for the brief→review→GREEN cadence?
7. **Any Java changes at all?** This plan asserts ~zero new *shared* Java *for the A0–A8 mainline*. Is there a
   phase the reviewer believes needs a Java-side change (e.g. an Android-specific bridge method) that we've
   missed? (Track G is the explicit exception — `librlawt` replacement + the render-path shim are real
   native/Java work.) **Reviewer answer:** expect no shared-Java changes for A1–A6 — but expect *plenty* of
   Android JNI/Kotlin host surface (today only 8 bridge methods are wired). A-1's census is the cheap way to
   confirm this empirically before committing UI effort. **A7 + Track G** are the likely places a shared/
   Java-adjacent change does surface.
8. **Is a working 117HD a v1 requirement (gating) or post-core-parity?** (§3G) — **DECIDED: fast-follow.** v1
   ships the *playable CPU shell* (A0–A8); 117HD appears-but-incompatible (as on iOS). Track G is a post-v1
   workstream; run AG0+AG1 as a cheap parallel go/no-go but **do not block release on it**.
9. **AG2 render path: `glReadPixels`→int[] (keep the current blit) vs render-GL-directly-to-the-SurfaceView?**
   **DECIDED: staged.** `glReadPixels`→int[] through the existing SurfaceView blit for AG2 *bring-up* (isolates
   "did the GL pipeline come up" from restructuring the render seam / A7), then move to **direct EGL-to-Surface
   for production** once the pipeline is proven (readback's GPU→CPU→GPU round-trip throttles FPS).
10. **GPU stack: lean on the PojavLauncher/Amethyst GL-translation libs (gl4es/LTW/ANGLE + bionic LWJGL) the way
   our JRE's lineage already does, or hand-port `gpu` to native GLES?** And confirm the AG0 target-device GLES
   tier (compute support) before committing. **Reviewer caution:** treat the Pojav GL-translation path as a
   *probe, not a plan* — it was built for a **GLFW-windowed** Minecraft, not a **headless AWT** engine. If AG1
   can't make it expose desktop `OpenGL33` caps to LWJGL **headlessly**, stop before AG2.
11. **Native-lib dlopen path (reviewer-missed risk).** The native libs are currently dlopen'd from writable
   `filesDir` (`MainActivity.kt:119` libjvm; `jvm_bootstrap.cpp` preloads `lib/*.so` from `java.library.path`).
   Android nominally **forbids dlopen of libraries from writable app storage for API 29+ targets** — this works
   on the emulator but is **untested on a hardened real device** and may need a packaging change (e.g. shipping
   the JRE `.so` set under the APK's `jniLibs`/native-lib dir, or a lower `targetSdk`). Settle in A0/A8.

## 6. Sequencing rationale & quick reference

**Why this order:** A-1 first (a read-only bridge/flag census harness — front-loads any "the Java isn't
actually there" surprise and lays the first md5-gate; added per review). A0 next (cheapest real work — solidify
+ reach in-game + real-device gate, mostly host plumbing).
A1 next (highest play-value-per-effort: full input is pure host wiring against proven bridges → the app
becomes *playable*). A2 builds on A1's input for the signature menu UX. A3 (login) is independent and can slot
anywhere after A0 — placed here so core play is proven first. A4→A5 (plugin control → panels) are the bulk of
the "native shell" and unlock RuneLite's actual value-add. A6 (chrome) is the cosmetic Jagex-Mobile identity.
A7 (HUD veneers/edge-to-edge) is last among features because it's the only phase that's *unfinished on iOS* —
highest risk, lowest confidence. A8 stabilizes/ships.

| Phase | Deliverable | Primary gate(s) | New bridges wired | iOS parallel | Risk |
|---|---|---|---|---|---|
| **A-1** | Bridge/flag census + first md5-gate | all (probe) | — (resolve-only census) | `JVMLauncher.h` surface | Very low |
| **A0** | In-game (LOGGED_IN) + real device | `autologin.*` (+ JX_* env) | — (JavaBridge already wired) | P1 + P4-A | Low |
| **A1** | Playable: pinch/keyboard/precise/hover/haptic | `bridge` (existing) | `JavaBridge` input methods | P5 | Low |
| **A2** | Press-and-peek native menu | `menupeek` | `MenuPeekBridge` + `selectMenuPoint` | P5 | Med |
| **A3** | On-device Jagex login | — (login UI) | (writes tokens → A0 path) | login spike | Med |
| **A4** | Plugin list/config/hub | `mobileui`, `hub` | `MobileUiBridge` | P6 config | Low-Med |
| **A5** | Plugin panels (rendered + curated) | `mobileui` | `+QuestHelper/WorldSwitcher` | P6 panels | Med |
| **A6** | Native tab-rails + chat-at-top | `mobilechrome`, `hudveneer` | `MobileChromeBridge`,`HudVeneerBridge` | P6 chrome | Med |
| **A7** | Native HUD veneers + edge-to-edge | `huddata` (+`resize`) | `HudDataBridge` | W3 Track A | **High** |
| **A8** | Soak + signed APK + install UX | — | — | P7–P8 | Low-Med |
| **AG0** | GLES tier recon (hard gate; **run NOW**) | — | — (recon) | Track B step 0 | **High** |
| **AG1** | EGL ctx + bionic LWJGL → `OpenGL33` (**THE go/no-go**) | — | LWJGL/EGL shim | Track B step 1 | **High** |
| **AG2** | Base `gpu` plugin (GPU scene; fixes HD↔FPS) | (gpu plugin enabled) | native `librlawt` reimpl + render path | Track B step 2 | **High** |
| **AG3** | 117HD/RLHD (compute shaders; **partial HD best-effort**) | (117HD enabled via hub) | GPU stack | Track B step 3 | **Highest** |

**Track G runs as a parallel high-risk track** (Android counterpart to iOS Track A/B): the A0–A8 mainline
delivers the *playable CPU-rendered shell*; Track G adds GPU rendering and is what makes 117HD actually work.
**DECIDED (Q8): Track G is a post-v1 fast-follow, not a release gate** — v1 ships the CPU shell with 117HD
appears-but-incompatible. AG0+AG1 run as a cheap parallel go/no-go before AG2.

**Definition of done (Android):** a self-contained sideloaded/installed Android app — launch → on-device Jagex
login → in-game on live OSRS, full touch + Jagex-Mobile-style UI, RuneLite plugins toggle/config natively with
overlays composited, playable FPS, stable for hours — **no host, no desktop, no streaming, and (unlike iOS) no
per-launch JIT-attach ceremony.** *117HD/GPU rendering (Track G) is a separate, Android-favoured workstream and
is **explicitly NOT in the v1 definition of done** (decided, Q8) — it is a post-v1 fast-follow.*

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
