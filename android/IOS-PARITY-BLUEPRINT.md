# iOS Parity Blueprint — the reference for the Android port

> What the iOS build *is* today, feature by feature, so the Android side never has to
> reverse-engineer it. Compiled from a full code survey (not just docs — docs lag the code in
> places, noted where they do) at **master `2386367e0`** (2026-06-11). Cross-checked against
> `ios/REALJVM-DEV-PLAN.md`, `ios/FINDINGS.md`, the per-feature plans under `ios/`, and
> `android/BLUEPRINT.md` / `android/PORT-PHASING.md`.
>
> How to read each feature entry: **Behavior** (what the user sees/does) → **Implementation**
> (which layer, key files/classes/bridge methods) → **Shared vs platform** (the reuse boundary)
> → **Gotchas** (hard-won, don't re-learn) → **Status** (with commits).

---

## (a) Branch map — what lives where

Surveyed with `git branch -a` + `git log master..<branch>` on 2026-06-11.

| Branch | Contents | Merge status |
|---|---|---|
| `master` (= `origin/master`) | The combined build: full iOS shell + shared headless core + 117HD GPU track + Android track through AG3-c. Tip `2386367e0`. | The integration trunk. |
| `vanilla-default` (checked out) | The current iOS working branch. **Identical to master tip** — it is master plus whatever is in flight in the working tree (clean at survey time; the last in-flight change, the fullscreen-map display transform, was committed as `2386367e0` to both branches and pushed). The name records the renderer default: `rendererPref` defaults to `cpu` (vanilla software renderer). | Tracks master 1:1. Nothing unique. |
| `gpu` (local + remote) | The historical iOS 117HD GPU track. | **Fully merged** (merge `f23bb9b56`, 2026-06-10). 0 commits ahead. |
| `touch-targeting` (local + remote) | Engine-resolved tap targeting. | **Fully merged** (ff to `ba762e9b4`, 2026-06-11). 0 ahead. |
| `chat-pill` (local + remote) | Chat channel pill. | **Fully merged** (ff to `9a8923a2c`, 2026-06-11). 0 ahead. |
| `origin/android` (remote only — no local branch) | The Android port's working branch. **29 commits NOT on master** (tip `a310e583c`): the mobile-UX AP-0/AP-2a/AP-2b/AP-4 batches (logout, chat scroll-by-drag, keyboard toggle, sticky shift, notifications/link routing, color/dimension/set config editors, pin/star, incompatible badges, screenshot-to-gallery, Plugin Hub screen, XP/Loot/GE reflows, overlay edit mode, infobox menu), the **minimap-orbs root-cause fix in shared `HudVeneerBridge` (+48 lines)**, **legacy username/password autologin in shared `HeadlessLogin` (+118 lines)**, a hub-version pin to 1.12.28, and **removal of 6 shared probe/starter classes** (`GpuHandshakeProbe`, `GpuSceneDiag`, `RendererPanelStarter`, `TracingDrawCallbacks`, `WelcomePlayProbe`, `WorldMapProbe`). | **UNMERGED.** Shared-contract baseline diverged: master 189 entries (39 FLAG + 150 METHOD) vs android 185 (40 FLAG + 145 METHOD). |

**Merge hazards to plan for when syncing android ← master next:**
- `HudVeneerBridge.java` changed on **both** sides since the branches diverged (master: world-map
  window/pan/snapshot + map-open HUD shoves; android: minimap-orbs fix). Expect a real 3-way merge,
  not a fast-forward. Use the proven throwaway-worktree merge flow.
- `origin/android` **deleted `RendererPanelStarter.java` and `WorldMapProbe.java`** — both are now
  load-bearing on master (renderer selection panel; world-map probe still referenced by the
  preshrink experiment). The merge must restore them.
- Run `tools/shared-contract/check-contract.sh` after the merge; reconcile baselines consciously.

---

## (b) Architecture overview — three layers and the bridge contract

```
┌────────────────────────────────────────────────────────────────────┐
│ Native shell (platform-specific)                                   │
│  iOS:  ios/app/HelloShell/*.m  (UIKit, ~7,900 lines ObjC)          │
│  And:  android/app/src/main/…  (Kotlin/Compose + cpp bootstrap)    │
│  Owns: gestures, native chrome/veneers, panels UI, login UI,       │
│        JVM lifecycle, frame present                                │
├────────────────────────────────────────────────────────────────────┤
│ Shared Java headless core (THE REUSE BOUNDARY — identical jar      │
│ on both platforms)                                                 │
│  runelite-client/src/main/java/net/runelite/client/headless/      │
│  Thick JNI facades: JavaBridge, MobileUiBridge, HudVeneerBridge,   │
│  MenuPeekBridge, MobileChromeBridge, HudDataBridge, panel bridges, │
│  HeadlessLogin/Resize, RLHDStarter/RouteCGpuStarter/               │
│  RendererPanelStarter, RuneLiteHeadless+HeadlessModule boot        │
├────────────────────────────────────────────────────────────────────┤
│ JVM + AWT backend (platform-specific runtime, same family)         │
│  iOS:  patched OpenJDK 21 (TXM JIT via StikDebug) + Cacio CTC +    │
│        fake-xawt; EAGL GL bridge (Route C + Zone natives)          │
│  And:  bionic arm64 OpenJDK 21 + same Cacio/fake-xawt; EGL GL      │
│        bridge                                                      │
└────────────────────────────────────────────────────────────────────┘
```

### The bridge contract (how the layers talk)

- **Native → Java:** static methods on the facade classes, called via cached `jmethodID`s from a
  daemon-attached native thread (iOS: `JVMLauncher.m` `ensure*()` resolvers; Android: the
  `rl-bridge` single-thread executor — Android pays an extra ART↔HotSpot marshalling hop that iOS
  does not).
- **Java → native:** **no callbacks.** Everything flows as **polled JSON snapshots** published to
  `volatile` fields on the client thread and read lock-free from the native render tick
  (`getSnapshot()` + a monotonic `seq` to skip re-parse). Notifications/links are bounded
  fire-and-forget queues drained per tick (`nextNotification()` / `nextLink()`).
- **Write lanes:** plugin lifecycle mutations queue to the **EDT**; config writes and game-state
  touches queue to the **ClientThread**. Acks via `getProcessedWriteId()`; change detection via
  `getMobileUiSeq()` / `pollMobileUiEvents(sinceSeq)`.
- **Every bridge is inert unless its `-D` gate is set** — the shared jar is safe to run desktop.

### Gate flags the iOS shell sets at JVM launch (JVMLauncher.m:746–950)

| Flag | Purpose |
|---|---|
| `-Drunelite.bridge=true` *(implied by boot path)* | JavaBridge frame blit + input |
| `-Drunelite.mobileui=true`, `-Drunelite.hub=true`, `-Drunelite.pluginhub.version=1.12.28` | plugin control plane + hub (version pinned: 1.12.29 was never released → 404) |
| `-Drunelite.mobilechrome=true` | chrome geometry snapshot (tab grid) |
| `-Drunelite.menupeek=true`, `-Drunelite.menupeek.maxEntries=14` | menu snapshot for the native long-press menu |
| `-Drunelite.huddata=true` | orbs/minimap/chat data snapshot |
| `-Drunelite.hudveneer=true` | chat/minimap/side/stones relocation + world map |
| `-Drunelite.headless=true`, `-Drunelite.headless.resize=WxH`, `-Drunelite.nosplash=true`, `-Drunelite.nosafemode=true` | headless boot; canvas sized to the device aspect (renderH=540 — see Minimap gotcha); no Swing splash (JFrame under Cacio aborted ExternalPluginManager) |
| `-Drunelite.headless.autologin=…` family | armed only if `tokens.json` exists (points, world, EULA, welcome-play) |
| `-Drunelite.rendererpanel=true` | load GPU + 117HD into the plugin list without starting |
| `-Drunelite.routecgpu=true` / `-Drunelite.routecrlhd=true` | start base GPU / 117HD per saved preference |
| `-Drunelite.gfxIos=true` | **THE platform gate.** iOS sets it; Android does not. RLHDStarter forks its config-forcing on it (iOS: GLES-safety forcing + seeded user-tunables; no marker: Android mobile-sane forcing). |
| `-Drunelite.forceDisablePlugins=gpuplugin` | when not in GPU mode, stop GpuPlugin auto-starting so the renderer choice actually applies (`d6e37a5c7`) |

Full flag inventory (39 FLAGs on master) lives in `tools/shared-contract/baseline.txt`.

### The contract guard

`tools/shared-contract/`: `extract-contract.py` scans `headless/*.java` for every public static
method signature and every `System.getProperty/getBoolean/getInteger` flag → sorted
`baseline.txt` (master: **189 entries = 39 FLAG + 150 METHOD**). A pre-commit hook runs
`check-contract.sh` whenever staged files touch `headless/` — removals/changes block the commit
unless `baseline.txt` is staged too (conscious acknowledgement). This is the wall that keeps one
port from silently breaking the other. **origin/android's baseline is 185 (40/145)** — reconcile
on merge.

---

## (c) Feature-by-feature blueprint

### C1. Boot, session, lifecycle

#### 1. JVM bootstrap (iOS-specific, but it shaped the app structure)
- **Behavior:** app cold-starts to the Tap-to-Play cover; JVM boots on demand on a worker thread;
  live blit shows the login screen immediately (never a black boot).
- **Implementation:** `ios/app/HelloShell/JVMLauncher.m` (1,650 lines). `dlopen(libjvm)` →
  **`JNI_CreateJavaVM`** (NOT `JLI_Launch` — libjli's apple_main re-invokes main →
  UIApplicationMain crash), JNI_VERSION_21, on an `NSThread` with a **16 MB stack** named
  `jvm-main`; entrypoint `DeviceHeadlessBoot.main`. Registers the Route C + Zone GL natives before
  boot. `gVM` published with C11 atomics; the main thread attaches as daemon once and never
  detaches.
- **iOS-only JIT plumbing (no Android analogue — Android gets JIT for free):** patched libjvm
  (`ios/dev-tools/patch-jre-txm.sh` NOPs both `get_debug_jit_mapping` gates), per-launch StikDebug
  attach servicing `brk #0x69`, `-XX:+MirrorMappedCodeCache`,
  `-XX:-UseCompressedClassPointers`, `-XX:+DisablePrimordialThreadGuardPages`,
  `-XX:ReservedCodeCacheSize=64m`, SIG_DFL reset before VM create.
- **Gotchas:**
  - **WebKit breaks JIT** (JVMLauncher.m:943–953): any WKWebView in the process puts
    JavaScriptCore on the BREAKPOINT Mach exception port; `brk #0x69` is never serviced →
    SIGTRAP in `CodeCache::initialize`. Hence the **restart-after-login** flow (Feature 3).
    Android equivalent hazard: none, but the lesson generalizes — keep heavyweight runtimes out of
    the game process.
  - A `UIBackgroundTaskIdentifier` assertion brackets the whole JVM run or jetsam kills the
    process in ~250 ms of UI idle.
  - JVM **suspends in background** (iOS lifecycle) — drives the notification design (Feature 22)
    and the explicit config flush (Feature 17). Android keeps the process alive longer but adopted
    the same explicit `flushConfig()`-on-background pattern (A8).
- **Status:** device-proven since 2026-05-29 (Phase 1 oracle + Prong B); stable.

#### 2. Tap-to-Play launch screen
- **Behavior:** dark OSRS-mobile cover — "RuneLite" in gold, "Old School RuneScape" subtitle, a
  Play button disabled until the JIT debugger attaches (status line amber → green), a login row
  showing the saved account. Triple-tap reveals the hidden dev/debug panel.
- **Implementation:** iOS shell only. `ViewController.m:342–482` (`buildLaunchScreen`,
  `refreshLaunchLogin`, fade-out on play); JIT poll via `+[JVMLauncher isDebuggerAttached]`
  (csops). Play reads `rendererPref` from NSUserDefaults (`cpu` default | `gpu` | `117hd`) and
  arms the matching `-D` flags.
- **Shared vs platform:** all native. The *renderer-preference contract* (which flags to arm) is
  the shared part — see Feature 8.
- **Android delta:** Android has no attach gate (no StikDebug) — its equivalent is straight
  launch + dev-bar; the *polish* (cover screen, hidden dev panel) is ported conceptually in A6+.
- **Status:** device-confirmed GREEN (`4411b70af`, 2026-06-09).

#### 3. Login / auth (Jagex OAuth + autologin)
- **Behavior:** "Log in with Jagex" → full-screen WKWebView OAuth (TOTP supported) → tokens
  captured → **"Restart app to play"** prompt (see WebKit gotcha) → next cold start autologins
  end-to-end: EULA accept (if fresh), Play Now, world hop, welcome-screen dismiss. Logout deletes
  tokens + purges WebKit cookies for jagex/runescape domains.
- **Implementation:**
  - Native: `JagexLoginViewController.m` (309 lines; JS injection captures the session POST);
    token persistence `ViewController.m:901–928` → `Documents/tokens.json`
    (`NSDataWritingFileProtectionComplete`); restart prompt `:976–994`; logout `:948–969`.
  - Shared: `HeadlessLogin.java` — two-stage state machine (EULA accept → Play Now) of
    synthesized AWT mouse events (`FOCUS_GAINED` first — the gamepack ignores input without
    focus), per-stage attempt budget + cooldown, plus `tryDismissWelcome` for the post-login news
    overlay. All tunable via the `runelite.headless.autologin.*` flags.
- **Shared vs platform:** the whole click-machine is shared; only token mint/storage is native.
  **origin/android added a legacy (non-Jagex) username/password stage to shared `HeadlessLogin`
  (+118 lines, `12e755769`) — not on master yet**; iOS gets it on the next merge.
- **Gotchas:** the autologin click points (`382,248` Play; `302,312` EULA) are coordinates in the
  765×503 boot canvas — resize happens after login. WebKit-resident flag (`webKitUsedThisSession`)
  must gate the play button, not just warn.
- **Status:** device-confirmed; stable for months of sessions.

#### 4. Lifecycle, safe area, edge-to-edge
- **Behavior:** game fills the entire screen (notch, rounded corners, home indicator); all HUD
  and native chrome are inset to the safe area; rotation/inset changes re-flow everything.
- **Implementation:** iOS shell: game view pinned to *view* edges (not safe area), AspectFit;
  render size derived from the full-screen aspect (renderH=540, renderW from aspect) so the blit
  has **no internal letterbox**. Safe-area insets (screen pt) are converted to **canvas px**
  (`× renderW/screenW`) and pushed into the engine once per layout via
  `HudVeneerBridge.setOverlaySafeInsets(l,t,r,b)` (plugin overlays snap clear of bezel + the
  relocated chat — `78f73ccfa`). `viewSafeAreaInsetsDidChange` resets the one-shot relocation
  guard (the fix for the relocate-only-once bug). On background: `MobileUiBridge.flushConfig()`.
- **Shared vs platform:** inset *math* native; inset *application* (overlay corner snapping,
  `getOverlayChatAvoid()`) shared.
- **Android delta:** has the A7 relocate trio (chat/side/minimap into the safe area) live;
  immersive fullscreen done in A6.
- **Status:** device-confirmed, merged.

### C2. Frame pipeline and renderers

#### 5. CPU frame blit (the default render loop)
- **Behavior:** live game at 60–120 Hz, smooth, whole-session.
- **Implementation:**
  - Shared: `Hooks.draw` → `JavaBridge.snapshot(MainBufferProvider)` double-buffers the
    composited ARGB frame; `getFramePixels()/getFrameSeq()` are lock-free volatile reads; seq
    skips rebuilds (engine ~50 fps under a 120 Hz display).
  - iOS: `CADisplayLink` (ProMotion range 60–120) → `[JVMLauncher latestFrame]` → int[] →
    CGImage cached per seq → `self.screen.image` (`ViewController.m:2534–2559`,
    `JVMLauncher.m:1048–1102`).
- **Shared vs platform:** buffer ownership + publication shared; present native (Android:
  SurfaceView + R↔B swizzle to RGBA_8888, seq-skip — already production-stable).
- **Status:** rock-solid on both platforms.

#### 6. GPU publish + direct present (Route C)
- **Behavior:** in GPU/117HD modes the scene renders on the GPU; iOS presents either by readback
  (glReadPixels → same blit path) or **direct EAGL present** (a `CAEAGLLayer` revealed once the
  world is warmed up — no per-frame readback).
- **Implementation:**
  - Shared: `JavaBridge.gpuFrameBackBuffer(w,h)` / `gpuPublish(w,h)` (readback path);
    `presentReady()` + `noteSceneDraw()/armSceneHold()` — a **6-draw scene warm-up gate** that
    holds the previous frame through load-in (kills the login sky-flash; counter re-arms on
    non-world GameState).
  - iOS: hidden CAEAGLLayer created at launch when zone-direct is armed
    (`ViewController.m:2305–2326`), `RouteCBridge.setPresentLayer`, revealed in the display tick
    once `sceneWarmedUp()` + first GPU frame.
- **Gotcha (the big one, fixed twice):** the JNI GL bridge must honor **LWJGL NIO buffer
  semantics — `remaining()`, not `capacity()`**. Using capacity replayed stale draw ranges →
  wedge/stripe moiré corruption. Fixed in the base-GPU bridge (`daabcddfc`) and the same latent
  pattern in the zone bridge `z_buffer_sub_data` (`88652191b`, device-confirmed `fb63e0290`).
  Android's bridge had the equivalent class of bug (glMapBuffer overloads `834dfcb0b`) — when
  writing any new GL JNI, audit position/remaining handling first.
- **Status:** device-confirmed, merged.

#### 7. 117HD on iOS (what shipped, vs what the docs say)
- **Heads-up:** `ios/HD-PORT-BLUEPRINT.md` describes an older MetalANGLE plan. **What shipped is
  the EAGL ES 3.0 path** — Zone renderer (RLHD rev with ZoneRenderer), a staged jar built by
  `dev-tools/gpu/stage-rlhd-zone-jar.sh` applying a **shader overlay** (downported GLSL: TBO→2D
  repack where needed, int→float casts, version downport, gl_FragDepth depth-write for the
  missing glClipControl), Route C GL facade + Zone-delta natives. B0 gate met on device
  (`15a2b8609`); B1 features device-confirmed: shadows DETAILED, normal mapping, dynamic lights,
  MSAA 4, tiled lighting; native curated graphics panel + config persistence (`34722d01f`,
  `27dc59fed`).
- **Shared platform fork:** `RLHDStarter.java` is shared and **forks on `-Drunelite.gfxIos`**:
  - iOS: forces 8 GLES-safety config values (uiScalingMode, shadowFiltering, windDisplacement,
    characterDisplacement, **asyncModelProcessing=false**, sceneScalingMode, …) and seeds
    user-tunable graphics once (sentinel keys `GFX_SEEDED_KEY`/`GFX_SHADOWRES_KEY` so user edits
    survive relaunch).
  - Android: forces mobile-sane quality (MSAA off, 2K shadows, draw 30, single-thread models).
- **Gotchas:**
  - **asyncModelProcessing must stay false on a single-context GL bridge** — RLHD's worker
    threads otherwise touch GL buffers concurrently → player-model flashing/NPC corruption.
    Identical constraint already hit and fixed on Android.
  - `HdPlugin` declares `conflicts="GPU"` — starting it auto-stops base GPU. Don't fight it.
  - The hub-delivered 117HD collides with the staged one — the hub pin blocks it
    (`bb6939d7f`).
- **Status:** device-confirmed + merged (`f23bb9b56`). Android's 117HD path (Track G, native
  GLES 3.1/3.2, no shader downport needed) reached AG3-c GREEN on real Mali (`86b95389d`).

#### 8. Renderer selection as plugins (CPU / GPU / 117HD)
- **Behavior:** the renderers appear as rows in the native plugin panel. Toggling one saves a
  **launch preference** and shows "fully quit + relaunch to apply" — renderers are NOT
  hot-swappable (the GL bridge + present layer are fixed at JVM start). The switch shows the
  saved preference, not live state. Default: **cpu** (vanilla — always works).
- **Implementation:**
  - iOS: `rendererPref` in NSUserDefaults (`ViewController.m:1278–1287`, special-cased rows
    `:1224–1239`); Tap-to-Play arms `routecgpu`/`routecrlhd` accordingly.
  - Shared: `RendererPanelStarter.java` (gated `-Drunelite.rendererpanel`) loads GPU + HdPlugin
    via `MobileUiBridge.loadPluginNoStart` so they exist in the list without starting;
    `RouteCGpuStarter`/`RLHDStarter` start the chosen one and poll `client.isGpu()` to confirm.
    `-Drunelite.forceDisablePlugins=gpuplugin` keeps GpuPlugin from auto-starting in CPU mode.
- **Android delta:** NOT ported. Android has a dev "HD button" (restart into routecrlhd) but no
  panel-based selection — **and origin/android deleted `RendererPanelStarter.java`**; restore it
  on merge, then port the native half (preference + relaunch prompt).
- **Status:** device-confirmed + merged (`d2b46053c`, `d6e37a5c7`, `eb67d624f`).

### C3. Input

#### 9. Engine-resolved tap targeting (THE input core)
- **Behavior:** single tap = walk/interact, resolved exactly like official mobile: the engine's
  own pick at the **literal tap point** decides (no aim-assist; ships zero forgiveness =
  parity). Feels immediate; never misfires on a stale hover.
- **Implementation (almost entirely shared — Android reuses it as-is):**
  `JavaBridge.onClick(x,y,1)` enters a client-thread state machine
  (`JavaBridge.java:195–700`): **IDLE → AWAIT_ENGINE → CLICKED**.
  - AWAIT_ENGINE: post the synthetic move, wait until the engine's cursor matches the tap point
    AND the left-click tail fingerprint is stable for `STABLE_NEEDED=2` consecutive frames
    (`MAX_SETTLE=12` fail-open cap — never drop a tap; desktop settles ≤2; the cap covers a
    lagging device EDT). Then post the click.
  - CLICKED: wait for the **`MenuOptionClicked` truth event** (fires for every left click incl.
    walk); classify WALK vs interaction; on interaction bump `getInteractTapSeq()` (native
    haptic cue) and arm the model outline (`drawSelection`); then **park the cursor at (−1,−1)**.
  - A second tap during CLICKED queues as a follow-up; a tap during AWAIT_ENGINE replaces the
    pending one.
  - `onClickPrecise(x,y,b)` skips resolution for UI chrome (chat tabs, map window, menu rows).
  - Tunables: `runelite.touch.settleMax/settleStable/clickTimeout/selFlashMs`,
    `runelite.touch.hideClickCross` (vanilla click-X sprites blanked by default).
  - iOS native half is thin: tap recognizer → `mapPoint:toGameX:toGameY:` (AspectFit math) →
    `postClickX:y:button:` (`ViewController.m:2703–2745`).
- **Gotchas (each cost a debugging session):**
  - **Park by MOVE to (−1,−1), not MOUSE_EXITED** — the engine never clears the picked position
    on exit; only moving to an empty point ends hover. This fixed the stuck-label-on-pinch-zoom
    bug. EXITED is sent too, but only as AWT hygiene.
  - Dead ends already removed (don't reintroduce): centroid-click snap and pre-click hull tests —
    both armed feedback at *resolution* time, not *click truth* time; centroids land in concave
    gaps (tree canopies) and the walk destination ends up under the centroid, not the finger.
  - Phase 2 "forgiveness" (re-aim toward a nearby entity hull when the settled tail is WALK)
    exists behind `SNAP_RADIUS` but ships **0** — official mobile has none.
- **Status:** device-confirmed 15/15 taps, merged (`ba762e9b4`, 2026-06-11). Android consumes the
  same machine via `onClick` (emulator-green; real-device feel TBD).

#### 10. Click ring + haptic feedback (native feedback layer)
- **Behavior:** every tap spawns an expanding/fading ring — **red** if the engine consumed an
  interaction, **yellow** for plain walk; light haptic on interactions.
- **Implementation:** iOS native (`ViewController.m:2749–2773`): CAShapeLayer ring, scale
  0.5→2.2 / fade over 0.45 s. **Deferred one display tick**: the ring colour is decided by
  polling `getInteractTapSeq()` (bump = red; 0.25 s grace = yellow) — showing it instantly would
  misclassify, because engine resolution takes ~80–160 ms.
- **Shared vs platform:** `getInteractTapSeq()` is the shared half (optional method —
  null-checked so older jars work). Ring/haptic are native.
- **Android delta:** not ported (verify — Android may show no tap feedback today). Small,
  self-contained port.
- **Status:** device-confirmed, merged.

#### 11. Long-press menu (press-and-peek, native big rows)
- **Behavior:** hold 0.4 s → engine right-click menu opens at the finger; a native opaque
  OSRS-styled overlay (header "Choose Option", 32 pt rows) covers the engine's tiny menu; drag
  highlights rows (haptic tick); release on a row commits, off-rows cancels. Box opens away from
  the pressing thumb (hand bias), clamped to the safe area.
- **Implementation:**
  - Shared: `MenuPeekBridge` — read-only per-frame snapshot (`getSnapshot()`: menu rect +
    entries in display order, tags stripped, capped `menupeek.maxEntries`; `getTopFingerprint()`
    + `getPublishSeq()` for release-revalidation on moving targets). Commit via
    `JavaBridge.selectMenuPoint(x,y)` (clears the parked hover after select; falls back to
    `onClickPrecise` on older jars).
  - iOS: `ViewController.m:2949–3068` (gesture), `:2809–2890` (overlay build — polls the
    snapshot up to 0.6 s before falling back to the engine-clamp path), `:2910–2947` (drag
    highlight). OSRS menu geometry constants: header 19 px, rows 15 px (`:283–289`).
- **Gotchas:**
  - **Clamp the engine cursor inside the menu rect during the drag** — the engine auto-closes
    the menu the instant the cursor leaves; the native overlay maps finger → row → posts
    `onMove` at the row centre *clamped into the rect*.
  - Don't drive the engine cursor before the menu rect is known (`f82debabe`) — early moves with
    a stale rect close the menu.
  - Native menu drag must NOT also pan the camera (`menuOpen` short-circuits the pan handler).
  - The commit path is index-based via row geometry, not label matching.
- **Status:** device-confirmed, merged. Android has its own native menu overlay (A2,
  `MenuOverlayView`) on the same bridge — parity already achieved.

#### 12. Pinch zoom
- **Behavior:** two-finger pinch zooms the world, centred on the pinch.
- **Implementation:** iOS `ViewController.m:3188–3216`: **pinch is wheel events** — accumulate
  `(scale delta) × 12 notches/unit` and post whole notches via `JavaBridge.onWheel(x,y,n)` at the
  pinch centroid (scale-up → negative rotation = zoom in). `clearHover()` on begin AND end.
- **Gotchas:** the begin/end hover clears are mandatory — the (−1,−1) park fix surfaced as a
  pinch-zoom bug (stale hover label re-appearing). Wheel (not a camera API) is also what the
  world map open consumes for map zoom — one primitive, two behaviors, free.
- **Status:** device-confirmed, merged. Android: ScaleGestureDetector → same wheel accumulation
  (emulator-green).

#### 13. One-finger camera drag (and its two context hijacks)
- **Behavior:** one-finger drag rotates the camera ("grab the world" — axes inverted). The SAME
  gesture is context-routed: inside the open world-map window it pans the map; inside the open
  chat box it scrolls chat.
- **Implementation:** iOS `ViewController.m:3093–3170`: pan recognizer (1 touch), begin decides
  the mode (map window hit → `mapPanning`; chat box hit → `chatScrolling`; else camera). Camera:
  `JavaBridge.onCameraDrag(-dx,-dy)`. Map: sub-px accumulators → whole canvas px →
  `HudVeneerBridge.panWorldMap(dx,dy)`. Chat: notch accumulation → `HudVeneerBridge.scrollChat`.
- **Gotchas:**
  - `clearHover()` before a camera pan (scenery lights up under a stale cursor).
  - Sub-pixel accumulators matter: slow drags otherwise quantize to zero.
  - `onTouch` (the raw DOWN/MOVE/UP stream) deliberately **never synthesizes a click on UP** —
    drag is a primitive, not a click.
- **Status:** device-confirmed, merged. Android has camera drag + chat scroll (AP-0); map-pan
  routing not yet (no world-map feature there).

#### 14. Keyboard + sticky Shift
- **Behavior:** ⌨ button toggles the soft keyboard; the keyboard ALSO auto-raises when the
  engine opens a text field (bank search, PM, quantity dialogs) and auto-dismisses when it
  closes. ⇧ button latches Shift (amber when on) — un-gates Shift+long-press "Mark"/"Configure"
  entries exactly like desktop Shift.
- **Implementation:** iOS: hidden off-screen UITextField holding a zero-width sentinel; the
  delegate intercepts every edit and forwards without mutating the field
  (`ViewController.m:599–623`, `3329–3348`): insertions → `JavaBridge.onText(string)`,
  deletes → `onKeyStroke(8)`, return → `onKeyStroke(10)` (and keeps the keyboard up).
  Auto-raise: 10 Hz edge-triggered poll of `JavaBridge.isTextInputOpen()` (`:2605–2618`).
  Sticky shift → `JavaBridge.setShiftHeld(bool)` (shared; a single held synthetic
  `KEY_PRESSED VK_SHIFT` flips `client.isKeyPressed(KC_SHIFT)`).
- **Gotchas:** the world-map search field does **not** flip the engine's INPUT_TYPE var — the
  generic auto-raise never fires for it; the native side must raise the keyboard itself on
  search-rect taps (see Feature 21). Edge-trigger the auto-raise or you fight the user's manual
  toggle.
- **Status:** device-confirmed (shift latch GREEN 2026-06-08), merged. Android: keyboard toggle +
  sticky shift done (AP-0/AP-2a).

### C4. HUD and chrome

#### 15. Chat relocate + chat-at-top (the veneer foundation)
- **Behavior:** the live engine chatbox sits at the **top-left** of the safe area in transparent
  OSRS-mobile style (text over scene). It remains the real engine HUD — scrollback, input line,
  colours, filters all work.
- **Implementation:** shared `HudVeneerBridge.relocateChat(x, y, transparent)` +
  `setChatTabRowHeight/setChatAreaWidth/setChatHidden`. Applied **every frame** on the client
  thread; transparent mode hides the background sprites + sets the transparency varbit. iOS
  computes the canvas-px position from safe-area insets one-shot per layout
  (`ViewController.m:2393–2441`).
- **Gotchas (the two veneer laws — apply to every relocation feature):**
  1. **Force positions every frame** — TOPLEVEL_REDRAW rebuilds the toplevel and re-pins
     everything; one-shot moves silently revert.
  2. **Collapse = shove off-screen (y+3000), never `setHidden(true)`** — hiding cancels the
     message-list rebuild and tears down content; a shoved widget stays alive and revalidated, so
     restore is instant. This trick is reused by the world map's HUD handling and the side panel.
- **Status:** device-confirmed, merged. Android: chat-at-top live (A6/A7).

#### 16. Native chat tabs + the channel pill
- **Behavior:** the engine's 23 px tab row is replaced visually by enlarged native tabs
  (RuneScape-font, two-line: name + filter state). Normally collapsed to a **single channel
  pill**: tap = collapse/expand the chat; **hold ~0.15 s** = 7 chips fan out (spring animation);
  **drag sideways** = highlight + release-to-switch channel; **pull DOWN past 26 pt** on a
  filterable chip = the engine's filter menu drops at the finger (drag into a row, lift applies;
  pull back up cancels); a ▾ hint appears as the finger nears the bottom edge.
- **Implementation:** **iOS-native-only feature** (zero Java changes — the existing veneer
  bridge ops suffice): `ViewController.m:1509–1986`. Chips painted from
  `HudVeneerBridge.getChatSnapshot()` (selected/new/labelColor/filterColor per tab, 10 Hz);
  switching replays the engine tab op-script via `switchChatTab(idx)`; the filter menu reuses
  the long-press menu path (`openEngineMenuAtGameX:` at the engine button's centre). Bundled
  RuneScape TTFs registered at startup.
- **Gotchas:**
  - The launch crash that shipped once: a `setHoverChip` self-recursion — keep chip-hover state
    transitions non-reentrant.
  - Optimistic tab highlight with a 0.25 s revert cache — the engine sprite lags one redraw.
  - Pull-down (a *direction*) beat a dwell-timer design (a *wait*) — accidental activation ~0;
    it can't fire during horizontal drag-select.
  - The engine buttons stay alive under the native row (proxy-precise-clicks + filter menus need
    their real centres); only their *sprites* are hidden, via an ancestor widget.
  - Pull-down filter works with the chat collapsed too (`69ed4cf9e`) — the shove trick keeps the
    buttons functional off-screen.
- **Status:** device-confirmed, merged (`9a8923a2c`). **Android: NOT ported** (its chat tab
  veneer is the enlarged row + long-press filter — no pill/fan/pull-down).

#### 17. Chat scroll-by-drag
- **Behavior:** drag inside the open chat scrolls the message history.
- **Implementation:** shared `HudVeneerBridge.scrollChat(delta)` — drives the
  `UPDATE_SCROLLBAR` script directly. iOS routes the pan gesture (Feature 13).
- **Gotcha:** **not** a wheel event — wheel over the transparent chat (which overlays the
  viewport) would zoom the camera.
- **Status:** device-confirmed, merged. Android: done (AP-0, `nativeScrollChat`).

#### 18. 2-column tab grid + stone covers + side panel
- **Behavior:** the 14 desktop tab stones are replaced by a native 2×7 grid of OSRS-styled
  stone buttons pinned bottom-right in the safe area; active tab red/gold. Tapping switches the
  engine tab. The engine side panel (inventory/equipment/…) relocates to sit flush-left of the
  grid. A ▦ toggle instantly hides/shows all native chrome (A/B fallback to stock).
- **Implementation:**
  - Shared: `MobileChromeBridge.getSnapshot()` (chrome geometry; guarded on `topLevelId==164` —
    Classic resizable layout only), `HudVeneerBridge.setStonesHidden(bool)` (hides the stone
    *grandparent containers* — re-applied every frame; engine re-pins stones but not ancestors),
    `openTab(idx)` (**replays the stone's op-script** — works while hidden, no proxy click to a
    screen position), `relocateSidePanel(x,y)`.
  - iOS: `ViewController.m:1451–2094` — bundled stone/icon PNGs composited nearest-neighbour;
    grid layout fills the bottom ~56% of the safe area; side-panel X computed from the grid's
    left edge in canvas px.
- **Gotchas:** op-script replay (not coordinate proxy-clicks) is what makes hidden-stone tab
  switching reliable; layout-guard everything on toplevel 164 and fall back to stock chrome
  otherwise.
- **Status:** device-confirmed, merged. Android: done (A6 — redesigned to this exact grid).

#### 19. Minimap + orbs relocate (and the scale dead-end)
- **Behavior:** the minimap+orbs cluster sits top-right in the safe area; minimap walk-clicks
  and orb taps all work (the cluster is the live engine widget).
- **Implementation:** shared `HudVeneerBridge.relocateMinimap(x,y)` — one `setForcedPosition` on
  MAP_CONTAINER per frame moves the whole cluster. iOS pins it via canvas-px math (MM_W = 211).
- **Gotchas:**
  - **Minimap enlargement is a dead end as a widget edit:** `setMinimapScale(pct)` exists but
    no-ops at 100 — the engine hard-pins orb positions at render time (even forced positions are
    overridden), so a bigger globe overlaps the orbs. The real path (deferred) is a native
    scaled blit of the cluster. Android tried a scaled-blit MVP and **reverted it** (Finding 16)
    — agreed dead end for now.
  - Instead, the whole HUD was made bigger by reducing renderH to 540 (≈ +11% minimap); below
    ~480 the tab grid and minimap collide.
  - **Orbs need the Orbs interface opened** — the root cause Android found (`3ab27df50`,
    shared fix on origin/android, +48 lines in HudVeneerBridge): orbs render empty without it.
    iOS inherits the fix on merge.
- **Status:** device-confirmed, merged (relocate); scale disabled by design.

#### 20. Native HUD data veneers (orbs/minimap dots/chat lines)
- **Behavior:** native widgets can render HP/prayer/energy/spec orbs, minimap actor dots,
  compass angle, and recent chat lines without touching engine pixels.
- **Implementation:** shared `HudDataBridge.getSnapshot()` (+`getSeq()`); gated
  `-Drunelite.huddata`. iOS currently uses it selectively (the engine cluster does the visual
  work); Android same.
- **Status:** merged; available to both ports as the basis for any future fully-native HUD.

#### 21. World map, mobile (the newest feature — partially in flight)
- **Behavior (current master):** opening the world map (orb tap) yields an
  official-OSRS-Mobile-style **fullscreen map**: the engine's (size-capped) map window is scaled
  by a display transform to fill the screen width, centred, with black bands above/below; all
  native chrome and the minimap hide while it's open. One-finger drag pans the map 1:1;
  pinch zooms (wheel); taps inside are precise (no walk ring); tapping the search field raises
  the native keyboard; the engine's own ✕ closes (precise tap).
- **Implementation:**
  - Shared (`HudVeneerBridge`, ~lines 425–620 + `panWorldMap`): seeds the window rect varcs
    (WORLDMAP_X/Y/MINUSWIDTH/MINUSHEIGHT) every tick so the open lands at inset 2 px;
    `getMapSnapshot()` publishes `{open, window, close, search, zoomIn, zoomOut}` rects;
    `panWorldMap(dx,dy)` converts finger px → tiles via live zoom (carrying sub-tile remainders)
    and applies `WorldMap.setWorldMapPositionTarget` — **the** pan primitive. While open: chat
    hidden, minimap cluster + side panel shoved off-screen (the collapse trick), restored on
    close.
  - iOS (`ViewController.m:2452–2528` + the gesture routing in Features 9/13):
    `updateWorldMap` polls the snapshot ~10 Hz; `applyMapZoom:` applies the scale transform +
    black-band views. **Display-only:** gesture locations are read in the view's *bounds* space,
    which UIKit maps back through the transform — hit-testing stays on the engine's true
    geometry; the bands swallow stray touches.
- **Gotchas (Phase-0 probe answers — don't re-test):**
  - **Synthetic left-drags do NOT pan the map** (two timings tried). `setWorldMapPositionTarget`
    is the only working pan primitive.
  - Wheel zoom works (one notch = a zoom step); zoom buttons are the fallback.
  - The search field does **not** flip INPUT_TYPE → native must raise the keyboard itself.
  - **Window relocation is RED:** `setForcedPosition` on the map window moves *reported* bounds
    but the render stays put (the "getBounds lies" pattern, same as tab stones). Hence
    seed-the-varcs + display transform instead.
  - **The engine hard-caps the window at canvas minus a 215×165 reserve** (side column + chat
    strip). Hiding those widgets does NOT shrink the reserve, and (probe `2386367e0`) neither
    does zeroing their layout sizes pre-open. True engine-side fullscreen is impossible — the
    native display transform is the answer.
  - Walk ring suppressed for in-map taps (precise click path).
- **Status:** Phase 1 (pan/zoom/tap/search/chrome-hide) **device-confirmed** (`74557217e`,
  `7376456a3`, 2026-06-11). The fullscreen display-transform iteration (`367d17b65` →
  `6bde0eae5` → `924b8169c` → **`2386367e0`**) is **committed but not yet device-confirmed** —
  treat the transform approach as in-flight until confirmed. Optional Phase 3 (momentum pan,
  double-tap zoom) unscheduled. **Android: NOT ported** (the shared half arrives with the next
  master merge; the native half is gestures + transform + chrome-hide).

### C5. Plugins, panels, system surfaces

#### 22. Plugin management panel
- **Behavior:** ⚙ opens a full-screen card listing all plugins (pinned first, then A–Z): icon/
  star, name, status line (running / enabling… / disabled / **couldn't start** after a 4 s
  grace / renderer-preference), toggle. Tap row = toggle; long-press = Pin/Unpin/Configure.
  Renderer rows behave per Feature 8. Buttons: ＋ Hub, ▣ Panels.
- **Implementation:** iOS `ViewController.m:996–1366` over shared `MobileUiBridge`:
  `listPlugins()`, `setPluginEnabled()` (EDT lane), `setPinned()` (shared `pinnedPlugins` CSV —
  persists into the normal RuneLite config), `getPluginConfig()`. Change detection via
  `getMobileUiSeq()`/`pollMobileUiEvents()`.
- **Gotchas:** the enabled-but-never-active grace timer is what surfaces "couldn't start"
  (plugins with desktop-native deps); custom row gestures, not table selection (selection state
  fights the toggles). Explicit `flushConfig()` on panel close + app background — neither
  platform runs RuneLite's shutdown hook.
- **Status:** device-confirmed, merged. Android: full parity incl. pin/star + incompatible
  badges (A4 + AP-2b).

#### 23. Plugin config editors
- **Behavior:** modal sheet per plugin: switches, sliders (int/double + units), text inputs,
  enum choice rows, color, keybind-excluded, per-item reset; hidden/secret items omitted. For
  117HD only the iOS-ported settings are whitelisted.
- **Implementation:** iOS `PluginConfigViewController.m` (527 lines) over shared
  `MobileUiBridge.getPluginConfig(group)` — the descriptor carries `type` + `encoding`
  (Dimension = "WxH", Set = JSON array) so native renders typed controls — plus
  `setConfig/resetConfig/getProcessedWriteId`.
- **Android delta:** Android is *ahead* here: native Compose **color picker**, dimension
  two-number editor, and Set checklist shipped in AP-2b; iOS's color editing landed earlier via
  UIColorPickerViewController. Parity is mutual minus styling.
- **Status:** device-confirmed, merged.

#### 24. Plugin Hub
- **Behavior:** modal browser of the hub manifest (~1,700 plugins): icon, name, description,
  install/uninstall with progress spinner that never hangs.
- **Implementation:** iOS `PluginHubViewController.m` over shared `hubManifest()` (async:
  status=loading→ok/error), `hubInstall/hubUninstall` (fire-and-forget + flush), `hubState()`
  (poll `{refreshing, seq}`; seq bump → re-fetch manifest and reconcile).
- **Gotchas:** pin `runelite.pluginhub.version=1.12.28` (the `-SNAPSHOT` 404s); block the
  hub-delivered 117HD (collides with the staged Zone build) — `bb6939d7f`; deadline-anchor the
  spinner + handle nil hubState so installs can't hang the UI.
- **Status:** device-confirmed, merged. Android: done (AP-2b, including the same version pin
  independently rediscovered — `a310e583c`).

#### 25. Plugin panel dock + two-tier panels
- **Behavior:** a docked icon rail on the right edge opens plugin side-panels in-place next to
  the game (or as a modal drawer — A/B switchable). Two tiers:
  - **Tier 1 (generic):** the actual Swing `PluginPanel` rendered to an image, pinch-zoomable,
    tap-through (`panelClick` maps buffer px → Swing coords). Works for everything; degraded for
    tall panels.
  - **Tier 2 (curated native):** hand-built native screens fed by per-plugin data bridges —
    **Quest Helper** (quest list/active steps/items) and **World Switcher** (world list + hop)
    on iOS.
- **Implementation:** iOS `PluginPanelsViewController.m` (1,112 lines; PassthroughView so the
  game stays playable behind the rail). Shared: `listPanels/openPanel/closePanel/
  getPanelPixels/getPanelFrameSeq/panelClick` (4× supersampled render, adaptive scale within a
  3 M px budget, 15 fps repaint daemon) + `QuestHelperBridge` (pure reflection — survives
  hub-plugin absence) and `WorldSwitcherBridge`.
- **Gotchas:**
  - **Generic Swing→UIKit extraction was REJECTED** (audit `AUDIT-BRIEF-w3-plugin-panels-design`)
    — not portable, Swing internals too platform-coupled. The Bridge pattern
    (init/isAvailable/tick/getJson + native screen) is the design.
  - The rendered-image fallback is NOT a safe baseline: a full-4× tall panel ≈ 38 MB ARGB on a
    heap shared with the game → blank panel (OOM) for Loot-Tracker-history-sized content. Treat
    Tier 1 as "degraded", and keep promoting heavy panels to Tier 2.
- **Status:** device-confirmed, merged. Android: Tier 1 + Quest/World native (A5) **plus** XP
  Tracker, Loot Tracker, Grand Exchange native reflows (AP-4 — Android is ahead; the bridges
  `XpTrackerBridge`/`LootTrackerBridge`/`GrandExchangeBridge`/`TimeTrackingBridge` are already
  shared on master, so iOS can build the native screens whenever).

#### 26. Notifications + external links
- **Behavior:** RuneLite notifications (idle, chat keywords, …) appear as OS banners even while
  the app is foregrounded; plugin "open link" requests open an in-app browser sheet.
- **Implementation:** shared `MobileUiBridge.publishNotification/nextNotification` (bounded
  FIFO, `"title\x01body"` encoding) and `publishLink/nextLink`; the Notifier publish hook fires
  **before** the desktop focus gate (HeadlessClientUI.isFocused()==true was eating everything).
  iOS drains both queues per display tick (`ViewController.m:3253–3313`) →
  UNUserNotificationCenter (with `willPresentNotification` so banners show foregrounded) and
  SFSafariViewController (https/http validated; one at a time).
- **Honest constraint:** the iOS JVM suspends in background, so notifications only fire while
  the game is on-screen — the AFK-glance use case is structurally limited on iOS. Android's
  process model makes its NotificationManager delivery (AP-2a) more useful.
- **Status:** device-confirmed, merged.

#### 27. Screenshot
- **Behavior:** 📷 saves the live frame to Photos.
- **Implementation:** iOS-native trivially (`ViewController.m:3315–3327`,
  `UIImageWriteToSavedPhotosAlbum` of the cached blit). Android: MediaStore insert (AP-2b).
- **Status:** both done.

---

## (d) Android delta — what's ported, what isn't, suggested order

Cross-checked against `android/BLUEPRINT.md`, `android/PORT-PHASING.md`, and the
`origin/android` log. Android baseline: Track A software-complete through **A8**
(emulator-GREEN 2026-06-08, android `77570d649`/master `27dc032c5`); Track G GREEN through
**AG3-c** (117HD starts on real Mali-G715 via Firebase Game Loop, `5847c07f8`/merge
`86b95389d`). Everything Android is **emulator-green, not device-confirmed** (except the GPU
gates on Shield/Pixel) — real-device soak + signed install remain the standing wall.

### Already at parity (no port work)
Touch core (tap/settle machine, long-press menu, pinch, camera drag, keyboard, sticky shift) ·
chat-at-top + enlarged tabs · 2-col tab grid + side-panel relocate · A7 safe-area HUD trio ·
plugin panel (+pin/star/incompatible badges) · config editors (color/dimension/set — ahead of
iOS on color UX) · Plugin Hub · two-tier panels (ahead: XP/Loot/GE reflows) · notifications +
links · screenshot · logout (ahead — iOS logout exists but Android's token+restart flow is
cleaner) · chat scroll-by-drag · legacy autologin (ahead; shared half pending merge to master).

### NOT yet on Android (the porting backlog, in suggested order)

1. **Merge master → android first** (mechanical prerequisite). Brings the world-map shared half
   (`panWorldMap`, `getMapSnapshot`, map-open HUD shoves), the zone-bridge `remaining()` fix,
   hub restore/pin, and the latest `HudVeneerBridge`. Resolve: HudVeneerBridge 3-way (orbs fix ×
   world-map changes), restore `RendererPanelStarter.java` + `WorldMapProbe.java`, reconcile
   `baseline.txt` (189 vs 185).
2. **World map mobile** (native half only): route the pan gesture into `panWorldMap` when the
   drag starts inside the window rect; precise taps in-window; search-tap → IME; hide chrome on
   open; pinch → wheel already works. Hold the fullscreen display-transform piece until iOS
   device-confirms `2386367e0`; the Phase-1 floating-window behavior is the stable target today.
3. **Renderer selection as plugins**: restore the shared starter, add the saved launch
   preference + "restart to apply" rows (Android already restarts into routecrlhd for its dev HD
   button — productize that path). Mind `forceDisablePlugins=gpuplugin` for CPU mode.
4. **Chat channel pill** (pure native, zero Java): collapse-to-pill, hold-fan, drag-select,
   pull-down-filter. Port the interaction constants (0.15 s hold, 26 pt pull, ▾ hint) and the
   non-reentrant hover-state lesson.
5. **Click ring + interact haptic** (small): poll `getInteractTapSeq()`, defer feedback one
   frame, red/yellow classification.
6. **Tap-to-Play-grade launch polish** (optional; Android has no attach gate so this is purely
   cosmetic).

### Android-side work iOS should pull back (reverse delta)
- Minimap-orbs root-cause fix (shared, `3ab27df50`) — iOS inherits on merge.
- Legacy username/password autologin (shared, `12e755769`).
- Native reflows for XP/Loot/GE exist as shared bridges already; iOS lacks the native *screens*.
- Overlay edit-mode (drag/snap-to-corner) and InfoBox long-press menu (AP-4) — iOS Batch-D
  items; Android's bridge usage is the reference.

---

## (e) Open / in-flight — not yet stable enough to port

| Item | State |
|---|---|
| **World-map fullscreen display transform** (`applyMapZoom` + black bands + minimap shove, `2386367e0`) | Committed to master **minutes before this survey**; NOT device-confirmed. The transform claims display-only hit-testing (gestures read in bounds space) — verify on device before porting. Until then, Phase-1 floating-window behavior (`74557217e`) is the stable reference. |
| World-map window relocate | RED — dead end ("getBounds lies"); do not attempt. |
| World-map reserve shrink | Probed and dead (`2386367e0` commit message): neither hiding chat/side nor zeroing their layout sizes lifts the 215×165 cap. |
| World-map Phase 3 (momentum pan, double-tap zoom) | Optional, unscheduled. |
| Touch Phase 2 forgiveness (`SNAP_RADIUS>0`) | Implemented but ships 0 (= official-mobile parity). Don't enable without a UX reason. |
| Minimap enlargement | Disabled (engine pins orbs); native scaled blit is the only viable path; Android's MVP attempt reverted. Deferred on both ports. |
| 117HD deferred items | ShaderOverlay noise cleanup, POM port (last portable feature). |
| `origin/android` 29 commits | Unmerged to master; see branch map for the merge hazards. |
| Android real-device wall | Soak + signed release APK + physical install→login→play on the user's hardware — pending. |
| In-world 117HD scenery screenshot on Android | Blocked by WAF (datacenter IPs can't log in); needs real Mali on a residential network. |

---

*Verification provenance: branch states from `git log master..<branch>` per branch; feature
behavior from direct reads of `ViewController.m`, `JVMLauncher.m/h`, the four panel/login VCs,
and every non-probe class in `runelite-client/.../headless/`; statuses from commit messages +
the per-feature plans. Where `ios/*.md` docs disagree with the code (HD-PORT-BLUEPRINT's ANGLE
plan, REALJVM-DEV-PLAN's phase table), the code won.*
