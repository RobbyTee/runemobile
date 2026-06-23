# Phase 0 — Cartography: Android Architecture Map

> Authoritative architecture map for the **Android-only deep audit**. Synthesized from seven subsystem
> readers + one adversarial bridge verifier. Every claim carries `file:line` evidence from the readers; no
> facts are invented here. This map seeds Phase 1 (slimming/dead-code) — it **collects** removal candidates,
> it does **not** decide removal.

## Table of contents
- [1. What this app is](#1-what-this-app-is)
  - [1.1 Scope legend](#11-scope-legend)
- [2. Top-level architecture diagram](#2-top-level-architecture-diagram)
- [3. Subsystem sections](#3-subsystem-sections)
  - [3.1 Kotlin shell, lifecycle & per-frame loop](#31-kotlin-shell-lifecycle--per-frame-loop)
  - [3.2 Native UI: Compose screen graph + classic Views](#32-native-ui-compose-screen-graph--classic-views)
  - [3.3 Native layer: jvm_bootstrap.cpp + librlawt.cpp](#33-native-layer-jvm_bootstrapcpp--librlawtcpp)
  - [3.4 Shared headless bridges: inventory & classification](#34-shared-headless-bridges-inventory--classification)
  - [3.5 Build graph, dependencies & APK composition](#35-build-graph-dependencies--apk-composition)
  - [3.6 Login, auth & account-sync data path](#36-login-auth--account-sync-data-path)
  - [3.7 Renderer selection & GPU/117HD bring-up](#37-renderer-selection--gpu117hd-bring-up)
- [4. Adversarial bridge verification](#4-adversarial-bridge-verification)
- [5. Phase-1 candidate flags (consolidated)](#5-phase-1-candidate-flags-consolidated)

---

## 1. What this app is

`dev.audaro.helloshell` is a **native Android app that runs real, unmodified RuneLite headless on a second,
in-process HotSpot JVM** and presents it as an OSRS-Mobile-styled touch client. The ART process `dlopen`s an
**embedded arm64 OpenJDK 21** (`libjvm.so`) and calls `JNI_CreateJavaVM` to stand up a game VM that boots via
`DeviceHeadlessBoot.main` → `RuneLiteHeadless.main` → `RuneLite.main` with a `HeadlessModule` swapping the
Swing `ClientUI` for `HeadlessClientUI`. The PluginManager, overlays and config all run as on desktop. A
Kotlin/Compose shell hosts the live frame in a `SurfaceView`, injects touch/keys as AWT events through a
hot-path reverse-JNI facade (`JavaBridge`), and reads per-frame snapshots (chrome geometry, chat, menus,
tracker JSON) that a dedicated native render thread republishes lock-free. Three mutually-exclusive renderers
(CPU software, base-GPU, 117HD) are chosen **at process start** and drawn either by the CPU blit path or by
`librlawt.so` (native EGL/GLES3) presenting directly to the Surface. Two independent logins exist: a bespoke
on-device **Jagex** OAuth flow (Custom Tab → clipboard → `tokens.json` → `JX_*` env) and a stock
**RuneLite-account** (`runelite.net`) OAuth for config/plugin sync (loopback redirect, auto-completes).

### 1.1 Scope legend

| Tag | Meaning |
|---|---|
| **Android-only** | Lives under `android/` (Kotlin shell, `jvm_bootstrap.cpp`, `librlawt.cpp`, Gradle/CMake/assets, login Kotlin). Changing it cannot affect iOS. |
| **Shared / iOS-impacting** | Lives under `runelite-client/src/main/java/net/runelite/client/headless/` (the bridges + seam + starters) or other upstream-tree paths. Both ports class-load these; edits ripple to iOS. The shared/iOS-impacting marker matters most for the bridge table (§3.4) and the renderer starters (§3.7). |
| **Renderer-gated** | Shared code only reachable when a specific `-D` renderer flag is set (`routecgpu`/`routecrlhd`). |
| **PROBE / TEST / SPIKE** | Compiled into the shared jar and referenced by `HeadlessSeams`, but no production caller fires it on Android (flag never set). Dormant, not unreferenced. |

---

## 2. Top-level architecture diagram

```
                          ANDROID PROCESS (single OS process; ART + embedded HotSpot share it)
 ┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐
 │  ART side (Kotlin/Compose)                                                                              │
 │  ┌───────────────────────────────────────────────┐                                                     │
 │  │ MainActivity (FrameLayout z-stack)             │   Z0 SurfaceView (live frame)                       │
 │  │  • Choreographer doFrame (UI thread)           │   Z1 TapRingView   Z2 ChromeOverlayView             │
 │  │    reads *Pub/atomics, posts onBridge{}        │   Z3 ChatPillView  Z4 DialogueView                  │
 │  │  • classic View HUD veneers (per-frame push)   │   Z5 GameInputView Z6 MenuOverlayView               │
 │  │  • 1× ComposeView = PluginPanel (data screens) │   Z7 OverlayEditView Z8 ReconnectingOverlayView     │
 │  └───────────────────────────────────────────────┘   Z9 launcherHandle Z10 devBar Z11 hotkeyStrip      │
 │        │ Kotlin external fun (one-way DOWN; UI thread NEVER attaches HotSpot)   Z12 pluginPanel(top)    │
 │        ▼                                                                                                │
 │  ╔══════════════════════════ JNI / native (C++) ══════════════════════════════════════════════════╗   │
 │  ║ libhelloshell.so (jvm_bootstrap.cpp) — links log/android/dl, NOT libjvm                          ║   │
 │  ║   • dlopen embedded libjvm.so + preloadJreLibs → JNI_CreateJavaVM (HotSpot, one-per-process)     ║   │
 │  ║   • nativeSetEnv (JX_* before VM) · nativeSet{Surface,StdioRedirect} · input enqueue → g_inQueue ║   │
 │  ║   • render thread (renderLoop ~60Hz): drainInput→bridge, poll read-surfaces→atomics,             ║   │
 │  ║     republish 6 JSON snapshots (seq-gated, mutex strings), CPU blit (R↔B swizzle→RGBA_8888)      ║   │
 │  ║   • rl-bridge thread (the ONLY ART-side HotSpot-attached caller) runs control-plane calls        ║   │
 │  ║   ── per-frame SNAPSHOT (Java→native, read each tick): chrome geom, chat, orb, dialogue, menu,   ║   │
 │  ║      world-map, tracker JSON, framebuffer ── COMMAND (native→Java): input, config, hud-veneer    ║   │
 │  ║ librlawt.so (librlawt.cpp) — links log/EGL/GLESv3; dlopen'd ONLY in routec/smoke/gpu/rlhd modes  ║   │
 │  ║   • RouteC_registerGLNatives (GLALL/AWT/GL/MEM) · EGL ES3 pbuffer+FBO · GLSL330→ES downport      ║   │
 │  ║   • reverse-Z clip-control self-test · direct present (eglSwapBuffers→SurfaceView, no readback)  ║   │
 │  ╚══════════════════════════════════════════════════════════════════════════════════════════════════╝ │
 │        │ reverse-JNI (FindClass + NewGlobalRef of 15 bridge facades at boot)                            │
 │        ▼                                                                                                │
 │  ┌───────────────────────────── EMBEDDED HotSpot JVM (real RuneLite headless) ─────────────────────┐   │
 │  │ DeviceHeadlessBoot.main → RuneLiteHeadless.main → RuneLite.main (HeadlessModule: HeadlessClientUI)│  │
 │  │   PluginManager + overlays + ConfigManager (stock) │ Hooks.draw → HeadlessSeams.draw (dispatcher) │  │
 │  │   SHARED BRIDGES (net/runelite/client/headless/): JavaBridge, MobileUiBridge(+8 trackers),        │  │
 │  │   HudVeneerBridge, MobileChromeBridge, HudDataBridge, MenuPeekBridge, HeadlessLogin/Resize/Capture │  │
 │  │   RENDERER STARTERS: RouteCGpuStarter | RLHDStarter | RendererPanelStarter                        │  │
 │  └──────────────────────────────────────────────────────────────────────────────────────────────────┘ │
 └──────────────────────────────────────────────────────────────────────────────────────────────────────┘

 BOOT / ASSET FLOW (first launch, multi-second only on a runtime.tag change):
   build-time staging → assets/jvm/{jre image+33 .so, client-*-{shaded,gpu,rlhd}.jar, probes.jar, hello.jar}
        │  (uncompressed assets; JRE .so NEVER in jniLibs — the "jniLibs trap")
        ▼
   AssetInstaller.ensureInstalled: want="v5-<runtime.tag>"; if marker≠want → wipe filesDir/jvm + recursive copy
        ▼
   JAVA_HOME=filesDir/jvm/jre · classpath=[client-*-gpu.jar : client-*-rlhd.jar : probes.jar] · mainClass=DeviceHeadlessBoot
        ▼
   JagexTokens/LegacyCreds.loadAndArm → setenv JX_*/RL_LEGACY_* (BEFORE JNI_CreateJavaVM) → startJvm

 RENDERER SELECT (resolved ONCE at boot; not hot-swappable):
   intent extra | marker file | Game Loop(TEST_LOOP→117HD) | TV-leanback→117HD | persisted "renderer"/"mode" (default cpu)
        → bootJvm(gpu,rlhd) → -Drunelite.routecgpu/routecrlhd + force{Enable,Disable}Plugins → starter loads plugin in-world
```

---

## 3. Subsystem sections

### 3.1 Kotlin shell, lifecycle & per-frame loop
*(Android-only)*

#### Activity graph

| Activity | File | exported | launchMode | configChanges | orientation | Intent filters |
|---|---|---|---|---|---|---|
| `MainActivity` | `MainActivity.kt:66` | `true` (manifest:33) | default (standard) | `orientation\|screenSize\|keyboardHidden` (manifest:34) | `sensorLandscape` (manifest:35) | (a) `MAIN`+`LAUNCHER`+`LEANBACK_LAUNCHER` (manifest:36-42); (b) `com.google.intent.action.TEST_LOOP`+`DEFAULT`, mime `application/javascript` — Firebase Game Loop (manifest:45-49) |
| `LoginActivity` | `LoginActivity.kt:40` | `false` (manifest:56) | default | `orientation\|screenSize\|keyboardHidden` (manifest:58) | `sensorLandscape` (manifest:59) | none (started explicitly) |

- **No `android:launchMode`** on either activity. Renderer/login/logout "restarts" don't use launch mode — they `finishAffinity()` + `Runtime.getRuntime().exit(0)` after re-launching via `getLaunchIntentForPackage` with `FLAG_ACTIVITY_NEW_TASK|CLEAR_TASK` (e.g. `MainActivity.kt:936-940`, `restartIntoGpu` 1038-1044, `restartIntoRlhd` 1049-1059, `LoginActivity.restartIntoCleanBoot` 223-231). The hard process kill exists because the embedded HotSpot VM is **one-per-process** and snapshots env/`JX_*` at `JNI_CreateJavaVM` (`JvmLauncher.kt:49-53`).
- **LEANBACK path:** same `MainActivity` is the TV launcher target; at boot `packageManager.hasSystemFeature(FEATURE_LEANBACK)`/`android.hardware.type.television` forces 117HD unless a `cpu.mode` marker exists (`MainActivity.kt:882-889`). `uses-feature leanback`/`touchscreen` both `required=false` (manifest:18-19).
- **TEST_LOOP path:** `intent?.action == "com.google.intent.action.TEST_LOOP"` → `gameLoop=true` → forces `routecrlhd` (117HD) for Firebase Game Loop's Zone shader compile (`MainActivity.kt:870`, 885-888).

#### Boot sequence (ordered, `onCreate` `MainActivity.kt:318`)

```
onCreate (318)
 ├─ getSystemService(VIBRATOR) (321)
 ├─ if BuildConfig.DEBUG: register SET_HD_CONFIG + SET_RECONNECT_OVERLAY receivers (323-369)  [DEBUG/probe scaffolding]
 ├─ setupNotifications() (373→1720): 2 NotificationChannels + request POST_NOTIFICATIONS (API33+)
 ├─ construct Views: SurfaceView, GameInputView, MenuOverlayView, TapRingView, OverlayEditView,
 │   ChromeOverlayView, ChatPillView, DialogueView, ReconnectingOverlayView, ComposeView(pluginPanel) (377-471)
 ├─ build HUD buttons (devButton/launcherHandle/dev modifiers) + FrameLayout root z-order (475-672)
 ├─ setContentView(root) (673)
 ├─ refreshHotkeyStrip() + register PREFS_HOTKEYS listener (675-680)
 ├─ onBackPressedDispatcher.addCallback (685-713): overlay-edit / map-close / dialogue / panel / passthrough
 ├─ layoutInDisplayCutoutMode = SHORT_EDGES (718-721); setSoftInputMode(ADJUST_NOTHING) (725); enterImmersive() (726)
 ├─ surfaceView.holder.addCallback{} (727-737): surfaceCreated/Changed→JvmLauncher.setSurface(holder.surface);
 │                                              surfaceDestroyed→JvmLauncher.setSurface(null)
 ├─ GestureDetector + ScaleGestureDetector + surfaceView.setOnTouchListener (739-795)
 ├─ startInputPolling() (797→1627): post the Choreographer per-frame callback
 ├─ EARLY-RETURN PROBE GATES (boot off the normal path):
 │     • netprobe.mode marker → thread "netprobe" NetProbe.run; return (807-811)
 │     • gpuSmoke extra → bootGpuSmoke; return (812-818)
 │     • rlhdprobe extra/marker → bootRlhdProbe; return (823-830)
 ├─ resolve renderer mode (835-913):
 │     • auto-gpu-armed crash cookie check → revert to CPU on a prior never-presented boot (844-856)
 │     • first-run probeGpuCaps() (858-861) if no persisted mode
 │     • compute gpuMode / rlhdMode from intent extras + marker files + TV + persisted prefRenderer (863-889)
 │     • LOGIN GATE: if no tokens & no legacy creds & not census/rlhd → startActivity(LoginActivity); finish(); return (893-901)
 ├─ booted.compareAndSet(false,true) (902): arm auto-gpu watchdog if pref gpu/117hd (907-911),
 │     then thread "jvm-boot" { bootJvm(app, census, gpu, rlhd) } (912)
 └─ startBridgeDrain() (916→1747): thread "rl-drain" daemon
```

**`bootJvm` (`MainActivity.kt:2564`):** `AssetInstaller.ensureInstalled` (2565) → locate/copy `client-*-gpu.jar` + `client-*-rlhd.jar` + `probes.jar` (2573-2619) → `ensureResizableDisplayMode(preferences.dat)` patches byte[3]=2 (2636, 2818) → `JagexTokens.loadAndArm`/`LegacyCreds.loadAndArm` set `JX_*` env **before** VM create (2641-2644) → assemble ~40 VM opts incl. JPMS `--add-exports/--add-opens`, `-Xmx512m`, `-Drunelite.bridge=true`, `-Drunelite.mobileui/.mobilechrome/.hudveneer/.menupeek/.rendererpanel`, force-enable/disable renderer plugins (2649-2700), `-Drunelite.headless.resize=${renderW}x540` (2706-2719, sets `canvasW/canvasH`), autologin opts (2726-2735), gpu/rlhd frame-dump+fpsprobe (2752-2780) → `setStdioRedirect(BuildConfig.DEBUG)` (2784) → **`JvmLauncher.startJvm(... mainClass="DeviceHeadlessBoot" ...)`** (2787-2794). On `rc!=0` clears `booted` (2795-2802). Census also runs `runA4SeamSelfTest`/`runA6SeamSelfTest` (2804-2807).

**First frame / autologin:** the VM boot thread runs `DeviceHeadlessBoot.main` (native `jvm_bootstrap.cpp:1369`); the native render thread (started in `startJvm`, `jvm_bootstrap.cpp:1316`) polls `JavaBridge` and blits frames. Autologin is **entirely engine-side** (`HeadlessLogin`, armed by `JX_*` env + `JagexTokens.autologinOpts`) — the shell only sets env pre-boot, it does not drive login clicks.

#### JNI surface (Kotlin ↔ native) — one-way DOWN

Every `external fun` in `JvmLauncher` (`JvmLauncher.kt:40-311`) maps to a `Java_dev_audaro_helloshell_JvmLauncher_native*` in `jvm_bootstrap.cpp` (entry points `1384-2481`). The native layer caches **HotSpot** `JavaBridge`/facade static method IDs as global refs at boot (`jvm_bootstrap.cpp:496-917`) and only ever calls **into the embedded game VM** (`CallStaticVoidMethod(g_bridgeCls,...)`). **There are NO native→Kotlin upcalls** — `artEnv` is used solely for string/array marshalling (`GetStringUTFChars`, `GetIntArrayElements`); zero `artEnv->Call`/`artEnv->FindClass` targeting a `dev.audaro.helloshell` class. Loaded once via `System.loadLibrary("helloshell")` (`JvmLauncher.kt:25-27`).

Representative mapping (full set ~130 symbols):

| Kotlin symbol | Native symbol | Direction | Purpose / threading |
|---|---|---|---|
| `startJvm` | `…_startJvm` (1384) | Kotlin→native | Create HotSpot VM on a never-exiting native thread; starts render thread; returns at `JNI_OK` |
| `nativeSetEnv` | `…_nativeSetEnv` (1436) | Kotlin→native | setenv BEFORE startJvm (JX_* handoff) |
| `nativeSetStdioRedirect` | `…_nativeSetStdioRedirect` (1431) | Kotlin→native | Pipe VM stdout/err→logcat (DEBUG only) |
| `nativeSetSurface` | `…_nativeSetSurface` (1447) | Kotlin(UI)→native | `ANativeWindow_fromSurface`; bumps `g_surfaceGen` |
| `nativeClick/Touch/Camera/Wheel/Move/ClickPrecise/KeyStroke/Text/SelectMenuPoint/…` | (1498-1572) | Kotlin(UI)→native **enqueue** | Pushed onto `g_inQueue`, drained by render thread (UI never attaches HotSpot) |
| `nativeKeepAlive/SetShiftHeld/SetCtrlHeld/SetAltHeld/MiddleClick/Keybind/InvokeMenuEntry` | (1516-1572) | Kotlin(UI)→native enqueue | Ordered input events |
| `nativeFrameWidth/Height` | (1609-1611) | Kotlin→native read | Atomics `g_lastFrameW/H` — lock-free, no attach |
| `nativeInteractTapSeq/TapKindSeq/LastTapKind/LastWidgetBounds/IsTextInputOpen` | (1587-1604) | Kotlin(UI)→native read | Atomics published by render thread each tick |
| `nativeChromeSnapshotPub/ChatSnapshotPub/OrbSnapshotPub/MapSnapshotPub/DialogueJson/MenuSnapshot` | (2372-2464, 1576) | Kotlin(UI)→native read | Mutex-guarded string copies — **UI-safe, no HotSpot attach** |
| `nativeListPlugins/SetConfig/FlushConfig/OpenPanel/PanelPixels/Quest*/World*/Xp*/Overlay*/Account*/Hub*/Next{Notification,Link}/FramePixels …` | (1624-2481) | rl-bridge thread→native | **Must run off UI thread** via `onBridge` — daemon-attach caller to HotSpot |
| `nativeGpuFramesPresented` | (1615) | watchdog thread→native | librlawt `RouteC_framesPresented` via dlsym; auto-GPU commit signal |

`JvmLauncher` also exposes Kotlin aliases (`JvmLauncher.kt:314-463`) and two dispatch helpers: `onBridge{}` (single-thread `rl-bridge` executor, `351-355`) and `onBridgeTouch{}` (same + `nativeKeepAlive`, `364`).

#### Per-frame data path (two distinct loops)

**(a) Native render loop** (`jvm_bootstrap.cpp:984` `renderLoop`, started at `:1203`) — dedicated daemon-attached native thread, `usleep(16000)`≈60 Hz (1198). Each tick: drains input queue (`drainInput` 997, 939-978), polls `JavaBridge` read-surface into atomics (textInputOpen, interactTapSeq, tap-kind/bounds 1000-1017), republishes menu/chrome/chat/orb/dialogue/map snapshots **only on seq-advance** into mutex strings (1019-1138), then — unless `g_directPresent` (GPU/117HD owns the Surface via EGL `eglSwapBuffers` 1143) — checks `JavaBridge.getFrameSeq` (1153) and on a new seq pulls `getFramePixels()` (`int[]` TYPE_INT_RGB `0x00RRGGBB` 1162), `ANativeWindow_setBuffersGeometry(w,h,RGBA_8888)` on geometry change (1168-1170), `ANativeWindow_lock` → per-pixel **R↔B swizzle to RGBA_8888** + force opaque (1178-1186) → `ANativeWindow_unlockAndPost` (1189). Seq-check makes an idle login screen "nearly free."

**(b) Kotlin Choreographer poll** (`MainActivity.kt:1627` `startInputPolling` → `doFrame` 1629-1713) — runs on the **UI thread**, re-posts every frame (1712). Per tick, all lock-free, no HotSpot attach:
- IME visibility via `surfaceView.rootWindowInsets.isVisible(ime())`, reconciles keyboard raise/dismiss against `manualKeyboard`/`mapSearchKeyboard`/`isTextInputOpen()` (1632-1672).
- `interactTapSeq()` → haptic on advance (1673-1678).
- Tap-feedback: `tapKindSeq()`/`lastTapKind()` within `RING_GRACE_MS=250` → `tapRing.spawn` SCENE/WALK (1684-1705).
- `tryBuildNativeMenu()` while building (1706-1708).
- **`updateChrome()` (1709→1850)**, **`applyOverlayInsets()` (1710→2056)**, **`updateWorldMap()` (1711→2077)**.

`updateChrome` reads `chromeSnapshotPub()`, `JSONObject`-parses it (**allocates per frame** — 1853), derives login-transition/reconnect/grid state, caches `chatRectCanvas`/`invRectCanvas`/`reorderRectsCanvas` (allocates IntArrays/lists 1891-1915), fires change-gated bridge writes (`applyStonesHidden`/`applySidePanel`/`applyChatAtTop`/`applyMinimap` via `onBridge`, each on delta 1982-2213). `updateWorldMap` short-circuits when the snapshot string is unchanged (2079) — otherwise parses + allocates rects (2081-2092). **Per-frame UI-thread allocations:** `JSONObject(chromeSnapshotPub)` every tick (no string-equality short-circuit, unlike the map path) + cached rect IntArrays — flag for Phase 1.

#### Surface / blit / PiP-background

- **`SurfaceView`** (not TextureView) — `MainActivity.kt:377`, MATCH_PARENT first in z-stack (639). Holder callback hands `holder.surface`→native (727-737).
- **Blit:** CPU path = `ANativeWindow_lock`/`unlockAndPost` with R↔B swizzle (`jvm_bootstrap.cpp:1172-1189`); no Android `Bitmap`/`ByteBuffer` for the live frame. The only Kotlin `Bitmap` use is the **screenshot** path: `framePixels()` → `Bitmap.createBitmap(ARGB_8888)` → MediaStore PNG (`saveScreenshot` 1105-1133, `writePngToGallery` 1138) — CPU-path only (no frame under live GPU/117HD, 1104).
- **Fullscreen world map:** SurfaceView non-uniformly **scaled+translated** to stretch the map window edge-to-edge (`applyMapFullscreen` 2131-2149; reset 2152-2164). Touches stay in untransformed local space so `toGameX/Y` is unaffected.
- **PiP + background keepalive:** **NOT present in MainActivity** — no `enterPictureInPictureMode`, `onPictureInPictureModeChanged`, or silent-audio keepalive (those exist only on iOS per MEMORY). The engine stays alive purely because the HotSpot VM runs on its own never-exiting native thread; `onStop` only flushes config (2436-2444). Idle-logout countered by `nativeKeepAlive` on touches (`onBridgeTouch`, `JvmLauncher.kt:364`).

#### Renderer selection (from Kotlin)

Resolved **once at boot** (jar set + librlawt registration fixed at VM start). Precedence (`MainActivity.kt:863-887`): intent extras (`routecgpu`/`routecrlhd`) OR marker files (`gpu.mode`/`rlhd.mode`/`rlhd.once`) OR Game Loop OR TV-leanback OR persisted `rendererPref()` (SharedPreferences `"renderer"/"mode"`, default `cpu` — 921-922). First-run with no pref runs `probeGpuCaps()` (EGL pbuffer ES-tier/MSAA/compute probe, 952-1010). A crash-cookie `auto-gpu-armed` + `startAutoGpuWatchdog` (1019-1034) reverts a never-presenting promoted boot to CPU. Flags become `bootJvm(gpu=…, rlhd=…)` (912). PluginPanel exposes `rendererPref()`/`onRendererSelect→setRendererPref` (517-518), persists + offers immediate restart (924-944).

#### Threading model

| Thread | Created at | Touches |
|---|---|---|
| **ART UI** (main) | system | All Views, gesture detectors, Choreographer `doFrame`, lock-free atomic/`*Pub` reads, change-gated `onBridge{}` submissions; **never attaches HotSpot** |
| **`jvm-boot`** | `thread(name="jvm-boot")` 912/815/827 | `bootJvm`/`bootGpuSmoke`/`bootRlhdProbe`; calls `startJvm` then runs `DeviceHeadlessBoot.main` for the session |
| **native render thread** | `startRenderThreadOnce` (`jvm_bootstrap.cpp:1206`) | Daemon-attached to HotSpot; drains input, polls bridge, publishes snapshots, blits to `ANativeWindow` (CPU path) |
| **GPU draw thread** (routecgpu/rlhd) | inside librlawt | Owns the Surface via EGL `eglSwapBuffers` when `g_directPresent`; CPU present stands down (`jvm_bootstrap.cpp:1143`) |
| **`rl-bridge`** | single-thread executor (`JvmLauncher.kt:351-352`) | The only ART-side thread that daemon-attaches HotSpot; serializes all `onBridge`/`onBridgeTouch` plugin-plane calls |
| **`rl-drain`** | `startBridgeDrain` daemon (1750) | ~3 Hz: `nextNotification`→NotificationManager, `nextLink`→Custom Tab (attaches HotSpot) |
| **`auto-gpu-watchdog`** | daemon (1020) | Polls `nativeGpuFramesPresented`, deletes crash cookie |
| **`overlay-poll`** | daemon (1590), overlay-edit mode only | `onBridge{overlaysJson()}.get()` ~2.5 Hz → `overlayEditView.setBoxes` on UI thread |
| **`netprobe`** | `thread(name="netprobe")` 809 | NetProbe diagnostic (no VM boot) |
| **`jagex-login`** | LoginActivity (`LoginActivity.kt:183`) | mintSession/fetchAccounts |

**Invariant (load-bearing):** the ART UI thread must never attach to HotSpot (ANR risk) — it only reads atomics/`*Pub` strings and posts work to `rl-bridge`; the render thread is the only HotSpot-attached caller for the frame/read-surface (`jvm_bootstrap.cpp:13-21`, `JvmLauncher.kt:347-364`).

#### Dead/debug/probe scaffolding (flag for Phase 1)
- **DEBUG BroadcastReceivers:** `SET_HD_CONFIG` (`MainActivity.kt:310,324-354`), `SET_RECONNECT_OVERLAY` (316,355-368) — `RECEIVER_EXPORTED` only in DEBUG; `reconnectTestOverride` (186) test-only force.
- **Probe boot gates that early-return from `onCreate`:** `NetProbe` (807-811 + `NetProbe.kt`), `bootGpuSmoke`/`RouteCSmoke` (812-818, 2461), `bootRlhdProbe`/`RLHDProbe` (823-830, 2502), `worldmapprobe.mode` (2748-2751), census mode + `runA4SeamSelfTest`/`runA6SeamSelfTest` (2851-3037) + `pollEnabled` (3040), `fpsprobe` (2776).
- **DEBUG-only HUD controls:** `▦` chrome A/B raw-stones toggle (555-559), `◆` GPU restart (615-616), `HD` restart (619-620), `⌃`/`⌥` Ctrl/Alt latches, `⇧` long-press test-notification (588-595) — all `BuildConfig.DEBUG`-gated in devBar (632).
- **Marker-file boot triggers** (`gpu.mode`/`rlhd.mode`/`rlhd.once`/`cpu.mode`/`rlhd_prelogin.mode`/`netprobe.mode`/`rlhdprobe.mode`/`worldmapprobe.mode`) scattered across 807-887, 2725, 2748 — a filesystem side-channel sprawl worth consolidating.
- `chromeUiLogged`/`chatTabsLogged`/`chromeButton` one-shot green-log latches (162, 194, 1927, 1970).

Paths under `android/app/src/main/`: `java/dev/audaro/helloshell/{MainActivity,JvmLauncher,AssetInstaller,NetProbe,LoginActivity,ReconnectingOverlayView,TapRingView,ChromeOverlayView}.kt`, `cpp/jvm_bootstrap.cpp`, `AndroidManifest.xml`.

---

### 3.2 Native UI: Compose screen graph + classic Views
*(Android-only)*

All native-UI Kotlin lives in `android/app/src/main/java/dev/audaro/helloshell/`. The shell is deliberately **hybrid**: data/config screens are Jetpack **Compose**; live in-game HUD veneers that composite per-frame over the `SurfaceView` are classic **`android.view.View`** subclasses (`PluginPanel.kt:82-87`, `MainActivity.kt:465-466`).

#### Hosting model

There is exactly **one** `ComposeView` — the plugin launcher (`MainActivity.kt:471`, `visibility=GONE`, `elevation=200f`). Content is set lazily on open via `pluginPanel.setContent { PluginPanel(...) }` (508-533), disposed on close (`disposeComposition()`, 514). **Every Compose screen is a child composable inside this single `PluginPanel` composition** — no `NavHost`; navigation is hand-rolled `mutableStateOf` flags + a `when` block (`PluginPanel.kt:204-313`). All classic Views are added to a root `FrameLayout` in strict Z-order (`MainActivity.kt:637-672`); `setContentView(root)` is the `FrameLayout` (673), no Compose tree.

| Z (bottom→top) | View | addView site |
|---|---|---|
| 0 | `SurfaceView` (live game frame) | `MainActivity.kt:639` |
| 1 | `TapRingView` (tap feedback rings) | `MainActivity.kt:640` |
| 2 | `ChromeOverlayView` (tab grid) | `MainActivity.kt:641` |
| 3 | `ChatPillView` (chat-tab row) | `MainActivity.kt:642` |
| 4 | `DialogueView` (NPC parchment) | `MainActivity.kt:643` |
| 5 | `GameInputView` (1×1 IME host) | `MainActivity.kt:644` |
| 6 | `MenuOverlayView` (right-click menu) | `MainActivity.kt:645` |
| 7 | `OverlayEditView` (overlay layout editor, GONE) | `MainActivity.kt:646` |
| 8 | `ReconnectingOverlayView` (reconnect scrim) | `MainActivity.kt:647` |
| 9 | `launcherHandle` Button ("≡ Plugins") | `MainActivity.kt:650` |
| 10 | `devBar` LinearLayout (⌨/⇧/…) | `MainActivity.kt:655` |
| 11 | `hotkeyStripView` (user hotkey buttons) | `MainActivity.kt:662` |
| 12 | `pluginPanel` ComposeView (sheet, top) | `MainActivity.kt:671` |

#### Compose screens (all hosted inside the one `PluginPanel` ComposeView)

`PluginPanel` (`PluginPanel.kt:94`) is the root; its landing is a labelled **tile grid** (`LauncherGrid`, `:457`) in a PEEK/EXPAND bottom sheet (`SheetScaffold`, `:370`). Sub-screens dispatched by the `when` at `:204-313`; entry flips a state flag, dismiss is `onBack`/`onClose`; system BACK → `BackHandler` (`:197`) → `popSub()` (`:179-196`).

| Compose screen | File:line (def) | Triggered by | Dismiss | Re-skins / replaces |
|---|---|---|---|---|
| `PluginPanel` (launcher) | `PluginPanel.kt:94` | `launcherHandle` Button `openLauncher()` `MainActivity.kt:551`→537-552; `Account` tile launches separate `LoginActivity` (`MainActivity.kt:521`) | `onClose` GONE+dispose `MainActivity.kt:511-516` | Right side-panel / toolbar; renderer picker |
| `LauncherGrid` | `PluginPanel.kt:457` | no sub-screen active `:293` | — | Tile dock |
| `PluginListBody` (All plugins) | `PluginPanel.kt:627` | "All plugins" tile / search (`inList` `:224`) | "‹ Grid" `:228` | Desktop plugin list |
| `ConfigScreen` | `ConfigScreen.kt:87` | row/tile `onOpenConfig` sets `configFor` `PluginPanel.kt:290,297` | "‹ Back" `ConfigScreen.kt:124` | **Plugin config editor** |
| `PluginHubScreen` | `PluginHubScreen.kt:67` | "Hub" / "Plugin Hub" tile `PluginPanel.kt:236,304,542` | "Close" `:153` | **Plugin Hub** browse/install |
| `PanelView` | `PanelView.kt:78` | rendered-image panel tile `PluginPanel.kt:299,504` | "‹ Panels" `:122` | Generic Swing plugin panels |
| `QuestHelperScreen` | `QuestWorldScreens.kt:64` | Quest tile `PluginPanel.kt:300,496` | "‹ Back" `:96` | Quest Helper panel |
| `WorldSwitcherScreen` | `QuestWorldScreens.kt:162` | World tile `PluginPanel.kt:300,495` | "‹ Back" `:192` | World Switcher panel |
| `XpTrackerScreen` | `XpTrackerScreen.kt:64` | XP tile `PluginPanel.kt:301,491` | "‹ Back" `:88` | XP Tracker panel |
| `LootTrackerScreen` | `PanelReflowScreens.kt:63` | Loot tile `PluginPanel.kt:301,492` | "‹ Back" `:86` | Loot Tracker panel |
| `GrandExchangeScreen` | `PanelReflowScreens.kt:182` | GE tile `PluginPanel.kt:301,493` | "‹ Back" `:207` | Grand Exchange panel |
| `TimeTrackingScreen` | `PanelReflowScreens.kt:428` | Time tile `PluginPanel.kt:302,494` | "‹ Back" `:450` | Time Tracking panel |
| `InfoBoxScreen` | `InfoBoxScreen.kt:53` | Infoboxes tile `PluginPanel.kt:303,498` | "‹ Back" `:75` | Infobox hover-menu |
| `ConfigProfilesScreen` | `PanelReflowScreens.kt:330` | "Profiles" tile (`profilesAvail`) `PluginPanel.kt:305,544` | "‹ Back" `:351` | Config profiles dropdown |
| `HotkeysScreen` | `PanelReflowScreens.kt:560` | "Hotkeys" tile `PluginPanel.kt:306,545` | "‹ Back" `:577` | (net-new; no desktop analogue) |
| `RuneLiteAccountRow` | `PluginPanel.kt:322` | inline in launcher when `!inList` `:249` | n/a (row) | RuneLite-account login (config sync) |

#### Classic `android.view.View` veneers

| View class | File:line | Owns/skins | Driven by (per-frame) | Dismiss/BACK |
|---|---|---|---|---|
| `ChromeOverlayView` | `ChromeOverlayView.kt:30` | **2-col×7 tab grid** (bottom-right; engine stones hidden) | `setGridEnabled`/`setActiveTab` from `updateChrome` `MainActivity.kt:1886-1887` | n/a |
| `ChatPillView` | `ChatPillView.kt:29` | **Chat-at-top tab row** (7 opaque tabs over relocated chat) | `chatTabOverlay.update(chatSnap,…)` `MainActivity.kt:1947` | n/a |
| `DialogueView` | `DialogueView.kt:25` | **NPC dialogue parchment** | `dialogueOverlay.update(chatSnap, dlgJson,…)` `MainActivity.kt:1952` | HW BACK `dismiss()` `DialogueView.kt:76` |
| `MenuOverlayView` | `MenuOverlayView.kt:19` | **Right-click menu** (press-and-peek + tap submenus) | host `show()`/`setHighlight()` `MainActivity.kt:2294-2364` | release/tap-outside `hitTest` `:146` |
| `OverlayEditView` | `OverlayEditView.kt:27` | **Overlay-layout editor** (drag overlays) | `setBoxes(list)` host poll; GONE unless editing | "Done" `:136` / BACK `MainActivity.kt:686-687` |
| `ReconnectingOverlayView` | `ReconnectingOverlayView.kt:21` | **Reconnect scrim** | `setReconnecting(on)` from `updateChrome` flag | self |
| `TapRingView` | `TapRingView.kt:17` | Tap-feedback rings (red=interact/yellow=walk) | `spawn(x,y,color)` `MainActivity.kt:1691-1695` | self-expires (450 ms) |

**World map** is *not* a native screen: the tile calls `JvmLauncher.openWorldMap()` (op-replay) then closes the launcher (`PluginPanel.kt:308`); engine renders fullscreen; shell tracks `mapOpen` to gate gestures/HUD (`MainActivity.kt:88, 2077-2104`). **Minimap + orbs** are engine-rendered (relocated, not re-skinned) — `applyMinimap(o)` only repositions the raster (`MainActivity.kt:1926`); no native minimap View.

#### Data sources & state flow

All Compose screens read through `JvmLauncher` → shared headless bridges (`runelite-client/src/main/java/net/runelite/client/headless/`). Bridge calls run on the **single daemon thread `rl-bridge`** (`JvmLauncher.kt:351-352`); `onBridge{}` (reads, 355) and `onBridgeTouch{}` (writes + `nativeKeepAlive()`, 364) both `submit` and return a `Future`. State flow is **poll-based** for live screens, **snapshot-once** for static schemas.

| Screen | Bridge read (JvmLauncher fn) | Cadence | Write path |
|---|---|---|---|
| `PluginPanel` | `listPlugins()`+`listPanels()`+`*Available()` `PluginPanel.kt:145-163` | once (`reload()`) | `setPluginEnabled`/`setPinned` `:258,277` |
| `ConfigScreen` | `getPluginConfig(group)` `ConfigScreen.kt:101` | once per `reloadKey` | `setConfig`+debounced `flushConfig` `:167-168` |
| `PluginHubScreen` | `hubManifest()`→`hubState()` poll `PluginHubScreen.kt:84,129` | ~1 Hz until settle | `hubInstall`/`hubUninstall` `:120` |
| `PanelView` | `panelWidth/Height/FrameSeq/Pixels` `PanelView.kt:107` | 400 ms (seq-gated) | `panelClick`/`panelText`/`panelKey` `:144,184,189` |
| `QuestHelperScreen` | `questListJson()`+`questActiveJson()` `QuestWorldScreens.kt:79` | 700 ms | `questStart`/`questStop` `:101,123` |
| `WorldSwitcherScreen` | `worldListJson()` `QuestWorldScreens.kt:176` | 1500 ms | `worldHop`/`worldHopDirection`/`worldSetFavourite` |
| `XpTrackerScreen` | `xpJson()` `XpTrackerScreen.kt:76` | 1500 ms | read-only |
| `LootTrackerScreen` | `lootJson()` `PanelReflowScreens.kt:75` | 1500 ms | `lootResetAll` `:118` |
| `GrandExchangeScreen` | `geJson()` `PanelReflowScreens.kt:195` | 1500 ms | read-only |
| `TimeTrackingScreen` | `timeTrackingJson()` `PanelReflowScreens.kt:439` | 1000 ms | read-only |
| `InfoBoxScreen` | `infoBoxesJson()` `InfoBoxScreen.kt:64` | 1000 ms | `infoBoxInvoke` `:88` |
| `ConfigProfilesScreen` | `profilesJson()` `PanelReflowScreens.kt:338` | 1200 ms | `switchProfile` `:366` |
| `HotkeysScreen` | `boundKeybindsJson()` `PanelReflowScreens.kt:568` | once | writes `SharedPreferences` (not bridge) `:597` |
| `RuneLiteAccountRow` | `accountSessionJson()` `PluginPanel.kt:327` | 2000 ms | `accountLogin`/`accountLogout` |

Most live screens **bracket** polling with a `DisposableEffect` calling `…SetUiOpen(true/false)` so the engine only rebuilds the snapshot while visible (e.g. `XpTrackerScreen.kt:70-73`, `InfoBoxScreen.kt:58-61`, `QuestWorldScreens.kt:72-75,170-173`). Classic Views are driven by `updateChrome()` reading `chatSnapshotPub()`/`dialogueJsonPub()` (`MainActivity.kt:1946-1952`) — **pushed snapshots** synchronously on the UI thread.

#### The plugin-config editor (render-by-type)

`ConfigScreen.kt:87` parses one plugin's schema (`getPluginConfig` → JSON `items[]`) into `ConfigItem` (`:66-79`) and renders one control per `type`. Dispatch split: bool/enum/font/notification inline in the header (`ConfigItemControl`, `:186-199`); text-ish types full-width below (`:201-224`):

| `type` | Control | File:line |
|---|---|---|
| `boolean` | `Switch` | `ConfigScreen.kt:187-188` |
| `enum` | `EnumDropdown` | `:189-190, 511` |
| `font` | `FontDropdown` (writes engine constant) | `:191-192, 231` |
| `notification` | inline `Switch` + `NotificationCustomizeControl` dialog | `:196-197, 212, 375-485` |
| `int` | `NumericConfigControl` → `BoundedIntSlider` if `@Range` span ≤1000 else clamped field | `:202, 253-314` |
| `double` | `NumericConfigControl` (clamped field) | `:203` |
| `string` | `OutlinedTextField` (+`PasswordVisualTransformation` if `secret`) | `:204-208` |
| `color` | `ColorConfigControl` → `ColorPickerDialog` | `:209, 542, 663` |
| `dimension` | two W×H fields → `"WxH"` | `:210, 575` |
| `set` | summary row + checklist dialog → JSON array | `:211, 597` |
| `keybind` | **read-only** "edit on desktop" (no HW key capture) | `:215-218` |
| `unknown` | read-only field | `:219-222` |

Writes optimistic-then-acked: each change calls `setConfig` on the bridge thread + a debounced 1 s `flushConfig` (`:165-168`, crash-safety FIX-8 `:93-110`). Color writes engine **decimal ARGB** (`Color.getRGB()`), not hex (`:524-538, 563`). Reset polls `processedWriteId()` before re-pulling (`:143-151`).

#### Visual styling (OSRS-Mobile emulation)

There is **no Compose theme** — no `Theme.kt`/`Color.kt`, no `themes.xml`, no `darkColorScheme`, and `PluginPanel` is *not* wrapped in `MaterialTheme {}`. Colors are hardcoded `Color(0x…)` literals (dark `0xF2121212`/`0xFF141414`, teal `0xFF80CBC4`, amber `0xFFFFD54F`, error red `0xFFE57373`). The two `MaterialTheme.typography.titleLarge` refs (`PluginPanel.kt:234`, `PluginHubScreen.kt:152`) resolve against the **default** M3 theme. So Compose reads as a generic dark settings UI, **not** an OSRS skin. The OSRS-Mobile look is emulated **only in classic Views** bundling real sprites/fonts from `assets/tabassets/`: `ChromeOverlayView` (`stone-active/inactive.png`+`icon-0..13.png`, `:66-101`), `ChatPillView` (`chatbtn*.png`+`runescape.ttf`, `:97-101`, fill `0xFF423C33`), `DialogueView` (parchment palette `:30-35`), `MenuOverlayView`/`ReconnectingOverlayView` (OSRS menu colors + iOS pixel constants `:47, 61-63`).

#### Flags for Phase 1 (from this reader)
- **`PanelList`/`NativeRow` removed but referenced in comments** (`PanelView.kt:69-71`) — confirm no dangling refs.
- **`devBar` dev cluster partially always-on** — `ctrl/alt/chrome/gpu/hdButton` only under DEBUG (`MainActivity.kt:632`) but `kbButton`+`shiftButton` always shown (628). `gpuButton`/`hdButton` restart paths (`restartIntoGpu`/`restartIntoRlhd`, 616,620) are debug-only footguns overlapping the renderer-as-plugins picker.
- **`HotkeysScreen` writes only SharedPreferences** (`PanelReflowScreens.kt:597`), bypassing the bridge; HUD strip rebuild depends on a `SharedPreferences` listener (`MainActivity.kt:676-680`) — verify `PREFS_HOTKEYS`/`PREF_HOTKEYS_ACTIVE` contract.
- **`popSub()` order-coupling** — `popSub` (`PluginPanel.kt:179-196`) and the render `when` (`:204-313`) hand-kept in the same order ("only ever one sub-screen at a time today" `:177-178`); latent mismatch risk.
- **`RuneLiteAccountRow` polls every 2 s forever** while the launcher landing is shown (`PluginPanel.kt:325-330`) with no `DisposableEffect` bracket.
- **`GrandExchangeScreen`/`XpTrackerScreen`/`TimeTrackingScreen` read-only** — confirm intended parity, not missing feature.

---

### 3.3 Native layer: jvm_bootstrap.cpp + librlawt.cpp
*(Android-only)*

#### Two-VM model
The Android process is ART's. `jvm_bootstrap.cpp` (`libhelloshell.so`) `dlopen`s the *embedded* OpenJDK's `libjvm.so` and calls `JNI_CreateJavaVM` to stand up a **second, in-process HotSpot VM** running RuneLite (`jvm_bootstrap.cpp:1-15`). **No `JNI_OnLoad`** in either `.so` — natives bound by JNI name-mangling (`Java_dev_audaro_helloshell_JvmLauncher_*`, resolved lazily by HotSpot) or by explicit `RegisterNatives` (librlawt's GL facade). `System.loadLibrary("helloshell")` is the only `loadLibrary` (`JvmLauncher.kt:26`); `librlawt.so` is `dlopen`'d by soname at boot, never loaded by Kotlin.

#### CMake / build
| Target | Source | Links | Notes |
|---|---|---|---|
| `helloshell` (`libhelloshell.so`) | `jvm_bootstrap.cpp` | `log`, `android`, `dl` | Does **not** link `libjvm` — resolved via `dlopen` at runtime (`CMakeLists.txt:4-11`) |
| `rlawt` (`librlawt.so`) | `librlawt.cpp` | `log`, `EGL`, `GLESv3` | Android native EGL + GLES v3 driver — "no ANGLE, no gl4es" (`CMakeLists.txt:13-19`) |

**ABI: `arm64-v8a` only** (`app/build.gradle.kts:21`; `librlawt.cpp:3`). The GLES↔jlong handle casts assume a 64-bit pointer ABI (`librlawt.cpp:739-741`).

#### jvm_bootstrap.cpp — boot
- Boot on a never-exiting pthread `jvmThreadMain` (`:1218`), spawned + detached by `startJvm` (`:1410-1416`), which blocks on a condvar latch (`g_bootCv`) until `g_bootDone` (`:1419-1421`) so Kotlin gets a real rc.
- `dlopen(libjvmPath, RTLD_NOW|RTLD_GLOBAL)` (`:1229`) → `dlsym("JNI_CreateJavaVM")` (`:1231`). `libjvmPath` = `…/lib/server/libjvm.so` under the installed JRE (`MainActivity.kt:2463/2504/2566`); `javaHome` from `AssetInstaller.ensureInstalled` (`:2462`).
- **JNI version `JNI_VERSION_21` = `0x00150000`** (locally `#define`d `:34-36`; init args `:1249`; every `GetEnv` `:447`).
- `JavaVMInitArgs.ignoreUnrecognized = JNI_FALSE` (`:1252`) — an unknown `-D`/`-XX` flag is **fatal** (no silent drop).
- First 3 VM options always prepended in C++ (`:1237-1239`): `-Djava.class.path=<cp>`, `-Djava.library.path=<libPath>`, `-Djava.home=<home>`; the rest are Kotlin `extraOpts` verbatim.
- On success `g_vm.store(vm, release)` (`:1259`, `std::atomic<JavaVM*>` `:49`); worker parks forever (`while(true) pause();` `:1379`) so HotSpot's "main" thread never dies (no `DestroyJavaVM`).
- **JRE .so preload (DT_NEEDED workaround):** `preloadJreLibs`→`preloadOneDir` (`:387-423`) multi-pass `dlopen`s **every** `lib/*.so` (skipping `libjvm.so`) before VM create, because HotSpot loads JRE libs by absolute path but bionic resolves their `DT_NEEDED` siblings (libnio→libnet, libawt→libfontmanager) by soname against a namespace excluding the JRE lib dir.
- **stdio pump (DEBUG-gated):** `redirectStdioToLogcat` (`:347-381`) `pipe()`+`dup2`s fd 1/2 into a detached `pumpStdio` thread (`:329`) emitting logcat tag `jvm-stdout`. Gated by `g_stdioRedirect` (default `true` `:327`), set from `BuildConfig.DEBUG` via `nativeSetStdioRedirect` (`:1431`) — a release build must not leak engine/account/network chatter (`MainActivity.kt:2784`).

#### Boot mode branch (driven by `-D` flags scanned from extraOpts, `:1267-1272`)
| Flag | Mode | Behaviour |
|---|---|---|
| `-Drunelite.rlhdprobe=true` | `rlhdProbe` | CACIO activate + hand to `RLHDProbe.main`; no bridge/render/librlawt (`:1274-1284`) |
| `-Drunelite.routec.smoke=true` | `routecSmoke` | dlopen librlawt + `RouteC_registerGLNatives` only; no CACIO/engine/render (`:1285-1296`) |
| (default) | full boot | CACIO CTC → `publishBridge` → `startRenderThreadOnce` (`:1297-1316`) |
| `-Drunelite.routecgpu=true`/`-Drunelite.routecrlhd=true` | GPU/117HD | full boot **+** dlopen librlawt + RegisterNatives + arm direct EGL present (`:1326-1348`) |

CACIO installed by `callStaticVoidNoArg("UnsafePokeActivation","activate")` (`:1306`, also `:1279`) **before** any `java.awt.*` touch — `JavaBridge.<clinit>` builds a `java.awt.Color` which would drag `Toolkit.<clinit>`→`dlopen libawt_xawt.so` and die. This is the only **UnsafePoke/no-JIT-workaround** path — the iOS `MirrorMappedCodeCache`/`brk #0x69` machinery is entirely dropped (Android grants JIT free, `:5-6`).

#### RegisterNatives bindings (jvm_bootstrap side)
jvm_bootstrap registers **nothing** itself — the ~110 `Java_…JvmLauncher_native*` externs are name-mangled JNI, resolved on demand. The only explicit `RegisterNatives` is in librlawt. The jvm_bootstrap natives grouped by `JvmLauncher` method:

| Native C symbol (suffix on `…JvmLauncher_`) | Java method | Purpose | line |
|---|---|---|---|
| `startJvm` | `startJvm` | create the VM (entry) | 1383 |
| `nativeSetStdioRedirect` | `setStdioRedirect` | DEBUG stdio→logcat | 1431 |
| `nativeSetEnv` | `setEnv` | `setenv` before VM (JX_* tokens) | 1436 |
| `nativeSetSurface` | `setSurface` | `ANativeWindow_fromSurface`, bump `g_surfaceGen` | 1447 |
| `nativeClick`/`Touch`/`Camera`/`ClearHover` | core input enqueue | 1498–1511 |
| `nativeKeepAlive` | keepAlive | idle-logout reset | 1516 |
| `nativeSetShiftHeld`/`SetCtrlHeld`/`SetAltHeld`/`MiddleClick` | sticky modifiers + BUTTON2 | 1521–1534 |
| `nativeWheel`/`ClickPrecise`/`Move`/`KeyStroke`/`Keybind`/`Text` | A1 input-parity | 1539–1562 |
| `nativeSelectMenuPoint`/`InvokeMenuEntry`/`MenuSnapshot`/`MenuPublishSeq` | A2 press-and-peek menu | 1567–1583 |
| `nativeIsTextInputOpen`/`InteractTapSeq`/`TapKindSeq`/`LastTapKind`/`LastWidgetBounds` | A1 read-surface (atomics) | 1587–1606 |
| `nativeFrameWidth`/`FrameHeight` | live canvas dims | 1609–1611 |
| `nativeGpuFramesPresented` | auto-GPU watchdog (dlsym'd `RouteC_framesPresented`) | 1615 |
| `nativeListPlugins`/`MobileUiSeq`/`PollMobileUiEvents`/`SetPluginEnabled`/`SetPinned` | A4 plugin control | 1624–1681 |
| `nativeGetPluginConfig`/`SetConfig`/`ProcessedWriteId`/`FlushConfig`/`ResetPluginConfig`/`NotificationDefaults` | config R/W + flush | 1683–1763 |
| `nativeBoundKeybindsJson`/`FireHotkey` | user hotkeys | 1767–1782 |
| `nativeNextNotification`/`NextLink`/`HubState`/`HubManifest`/`HubInstall`/`HubUninstall`/`PublishNotification`/`PublishLink` | AP-2a drains + hub | 1787–1858 |
| `nativeListPanels`/`OpenPanel`/`ClosePanel`/`Panel{Width,Height,FrameSeq,Pixels,Click}` + text | A5 Tier-1 panels | 1740–1971 |
| `nativeFramePixels` | screenshot pull | 1978 |
| `nativeQuest*`/`World*` | Tier-2 Quest/World reflow | 2108–2180 |
| `nativeXp*`/`Loot*`/`Ge*`/`TimeTracking*` | Finding-14 data panels | 2184–2258 |
| `nativeProfiles*`/`SwitchProfile` | config profiles | 2262–2276 |
| `nativeOverlay*` | Finding-15 overlay edit | 2280–2308 |
| `nativeAccount*` | RuneLite-account OAuth | 2311–2330 |
| `nativeInfoBox*` | Finding-22 infobox menu | 2333–2360 |
| `nativeChromeSnapshot`/`ChromeSnapshotPub` | A6 chrome read | 2364–2375 |
| `nativeSetStonesHidden`/`OpenTab`/`OpenWorldMap`/`Relocate{SidePanel,Chat,Minimap}`/`SetOverlayInsets`/`SetChatCollapsed`/`SwitchChatTab`/`PanWorldMap`/`ScrollMapPanel`/`SetChatTabRowHeight`/`SetChatAreaWidth`/`ScrollChat` + `MapSnapshotPub`/`ChatSnapshotPub`/`OrbSnapshotPub`/`DialogueJson` | A6 HUD-veneer write + render-published reads | 2381–2484 |

**Bridge facades cached in `publishBridge` (`:517-919`, once on VM main thread).** Each `FindClass`+`NewGlobalRef`+`midOpt` (OPTIONAL `GetStaticMethodID` clearing a pending `NoSuchMethodError` `:469-473`). `JavaBridge` is all-or-nothing fatal (`:543-549`); every other facade best-effort with a release-store readiness latch (`g_*Ready` `std::atomic<bool>` release/acquire so the rl-bridge thread never reads a half-published facade `:110-150,456-465`): `MenuPeekBridge`, `MobileUiBridge` (+`g_mobileUiReady`/`g_panelReady`), `QuestHelperBridge`, `WorldSwitcherBridge`, `XpTracker/LootTracker/GrandExchange/TimeTrackingBridge` (generic `registerDataPanel` `:495-513`), `OverlayBridge`, `RuneLiteAccountBridge`, `InfoBoxBridge`, `ConfigProfileBridge`, `MobileChromeBridge`, `HudVeneerBridge`.

#### Thread model (native, 4+ threads)
| Thread | Spawned at | Role |
|---|---|---|
| HotSpot "main" (`jvmThreadMain`) | `startJvm` pthread_create, detached (`:1410`) | creates VM, runs `DeviceHeadlessBoot.main`, parks forever (`:1379`) |
| render thread (`renderLoop`) | `startRenderThreadOnce` (`:1203`) | **only** HotSpot-attached caller of the bridge on the hot path; drains input, polls read-surfaces, blits |
| stdio pump (`pumpStdio`) | `redirectStdioToLogcat` detached (`:371`) | fd1/2→logcat (DEBUG) |
| rl-bridge (ART executor) | — | daemon-attaches via `bridgeEnvAttach` (`:443-451`) for control calls; never the ART UI thread (ANR risk) |

ART UI thread only ever **enqueues** input (`enqueue` `:1471`); never attaches HotSpot.

#### Input injection
ART UI → `nativeClick/Touch/…` → `enqueue(InputEvent)` onto `g_inQueue` (deque under `g_inLock`, cap 512, `:307-308,1485`). `enqueue` **coalesces** a high-frequency position stream (camera deltas SUM; move/touch-move keep latest) and under cap evicts the oldest *coalescible* event to protect terminal events (down/up/click/key/menu-commit never dropped `:1465-1495`). `InputKind` has 17 kinds incl. `IN_SHIFT/CTRL/ALT` (ordered ahead of menu-open click) + `IN_KEEPALIVE` (`:283-304`). Render thread's `drainInput` (`:941`) swaps the queue, dispatches each via `CallStaticVoidMethod`, clears any exception per-event.

#### Frame/snapshot pump (`renderLoop`, `:984-1201`)
Daemon-attaches, ~60 Hz (`usleep(16000)`). Each tick: `drainInput`; poll A1 read-surfaces into atomics; republish 6 JSON snapshots (menu-peek, chrome, chat, orb, dialogue, world-map) into `std::string`+mutex pairs, each **seq-gated** via `snapSeqAdvanced` (`:484-491`) so an unchanged frame skips `GetStringUTFChars`. Seq getters are OPTIONAL (older jar → always copy). Then the surface blit (CPU path).

#### Surface blit (CPU present, `:1145-1198`)
Acquires `g_window` under `g_winLock`; on `g_surfaceGen` change re-applies `ANativeWindow_setBuffersGeometry(w,h,WINDOW_FORMAT_RGBA_8888)` (`:1169`). Reads `JavaBridge.getFrameSeq()`; only on a new seq pulls `getFramePixels()` (`[I`, `0x00RRGGBB`), `ANativeWindow_lock`, copies row-by-row **swizzling R↔B into `0xFFRRGGBB`** (`:1184`). Dims sanity-capped `≤16384` (`:1159`), published to `g_lastFrameW/H`. **In GPU direct-present mode** (`g_directPresent` `:1143`) the render thread does the snapshot republishes then `usleep`s, skipping the Surface — librlawt owns it (single-producer BufferQueue).

#### librlawt present seam (callbacks handed over)
`rcPresentAcquireWindow`/`rcPresentSurfaceGen`/`rcPresentReleaseWindow`/`rcPresentSetFrameDims` (`:216-242`) passed via `RouteC_setPresentCallbacks` (dlsym'd `:1337-1340`), arming `g_directPresent` and resolving `g_routecFramesPresented`. jvm_bootstrap keeps `libandroid` (`ANativeWindow_*`); librlawt keeps EGL/GLES.

#### Process-env handoff
`nativeSetEnv` (`:1436`) `setenv()`s before VM create so the gamepack's `System.getenv` JX_* handoff is snapshotted at HotSpot startup (`:1424-1442`).

#### librlawt.cpp (GLES/EGL backing)
A per-ABI native drop-in for the **unmodified base `gpu` plugin's `net.runelite.rlawt.AWTContext` + `org.lwjgl.opengl.*` facade**, ported from the iOS Route-C JNI GL bridge (EAGL→EGL, iOS platform tax dropped) (`librlawt.cpp:1-15`). `GpuPlugin.java`/`AWTContext` stay byte-for-byte unmodified.

- **EGL setup:** `rcEglPrepareSurface` (`:275-294`): `eglGetDisplay(EGL_DEFAULT_DISPLAY)`+`eglInitialize`; config `EGL_OPENGL_ES3_BIT`, `EGL_PBUFFER_BIT|EGL_WINDOW_BIT`, RGBA8+DEPTH 24 (`:283-287`); bring-up pbuffer. `rcEglCreateContextCurrent` (`:296-307`): `eglBindAPI(EGL_OPENGL_ES_API)`, highest ES 3.x (3.2→3.1→3.0 `:300-302`); thread-affine context (`rc_make_current` `:198-202`). Offscreen FBO: RGBA8 color RBO + DEPTH24 RBO as "framebuffer 0" (`rcBuildFbo` `:311-329`; in-place `rcResizeFbo` `:334-351`). Direct present: `rcEnsureWindowSurface` (`:940-961`) creates/recreates `eglCreateWindowSurface` on surface-gen change.
- **`RouteC_registerGLNatives(env)` (`:1279-1296`)** — `RegisterNatives` on **3 required** + 1 optional:

| Java class | table | count basis | line |
|---|---|---|---|
| `org/lwjgl/opengl/GLALL` | `kGLALL` | ~130 entries | 1106–1255 |
| `net/runelite/rlawt/AWTContext` | `kAWT` | 8 | 1262–1271 |
| `org/lwjgl/opengl/GL` | `kGL` | 2 | 1272–1275 |
| `org/lwjgl/system/MemoryUtil` (optional) | `kMEM` | 4 | 1256–1261 |

- **GL routing / key shims:** `glClearDepth(D)`→`glClearDepthf` (`:545`); `glDrawBuffer`→`glDrawBuffers(1,…)` (`:709`); `glMultiDrawArrays`→N `glDrawArrays` (`:629-635`). BGRA→RGBA swizzle on UI/texture upload (`:660-672,781-786`). `glMapBuffer` family: `GL_WRITE_ONLY` maps **without invalidate** to preserve partially-rewritten dynamic buffers (`:584-628`); `GL_ARB_map_buffer_range` reported **TRUE** so RLHD takes the range path (`:1094-1098`). `GLCapabilities` (`rc_make_caps` `:1075-1101`): `OpenGL33=TRUE` (REQUIRED or ZoneRenderer aborts); `OpenGL31/40/43/45=FALSE`, `ARB_buffer_storage/copy_buffer/shader_image_load_store=FALSE`. **GLSL 330→GLES downport** (`rc_downport` `:239-273`): strips license/`#version`/`#line`/`noperspective`/float-`f`, prepends `#version <esVer>`+precision, 9 scene-vertex int→float casts (`:220-231`), ES 3.1 `#extension` only at `"310 es"`; target from live `GL_VERSION` (`:205-213`). **Reverse-Z clip control** (`rcSetupClipControl` `:430-450`): `glClipControlEXT(LOWER_LEFT, ZERO_TO_ONE)` via `eglGetProcAddress`, then `rcClipControlSelfTest` (`:387-426`) draws a fragment + reads back to **prove** [0,1] engaged; fails safe to `[-1,1]`. 117HD never calls `glClipControl` itself. **swapBuffers** (`:966-1072`): direct path blits offscreen FBO→window default fb via `glBlitFramebuffer`+`eglSwapBuffers`, increments `sFramesPresented`. Legacy fallback (callbacks absent — stale .so) = `glFinish`+`glReadPixels`+per-pixel swizzle CPU path (`:1037-1041`). Compute/indirect/storage entries registered for class-graph binding but gated OFF on Zone (`:767-786`).

#### Two-.so load order
1. ART loads `libhelloshell.so` via `System.loadLibrary("helloshell")` (`JvmLauncher.kt:26`).
2. `startJvm`→`jvmThreadMain` `dlopen`s the **embedded** `libjvm.so` by absolute path (`:1229`), after `preloadJreLibs` `dlopen`s every other JRE `lib/*.so` (`:1234`).
3. After `JNI_CreateJavaVM`, **only in routec/smoke/gpu/rlhd modes**, jvm_bootstrap `dlopen("librlawt.so")` by soname (`:1287,1328`), dlsym's `RouteC_registerGLNatives` (`:1291,1332`) + `RouteC_setPresentCallbacks`/`framesPresented` (`:1338,1342`). librlawt never loaded in a plain CPU boot.

#### Every `-D`/JVM flag passed (verbatim, for later phases)
**C++-prepended (always, `:1237-1239`):** `-Djava.class.path=<clientJar[:rlhdJar]:probes.jar>` · `-Djava.library.path=<javaHome/lib>` · `-Djava.home=<javaHome>`. Classpath `MainActivity.kt:2618-2620`; `mainClass="DeviceHeadlessBoot"` (`:2792`).
**Production boot extraOpts (`MainActivity.kt:2649-2683`):** `-Djava.awt.headless=false` · `--add-exports=java.desktop/java.awt=ALL-UNNAMED` (+ `java.awt.peer`,`sun.awt.image`,`sun.java2d`,`java.awt.dnd.peer`,`sun.awt`,`sun.awt.event`,`sun.awt.datatransfer`) · `--add-exports=java.base/sun.security.action=ALL-UNNAMED` · `--add-opens=java.base/java.util` · `--add-opens=java.desktop/java.awt` · `--add-opens=java.desktop/sun.java2d` · `--add-opens=java.base/java.lang.reflect` · `-Xmx512m` · `-Duser.home=<filesDir>` · `-Djava.io.tmpdir=<filesDir/tmp>` · `-Drunelite.headless.capture.dir=<captureDir>` · `-Drunelite.bridge=true` · `-Drunelite.headless.capture.interval=10` · `-Drunelite.headless.capture.preLoginMax=40` · `-Dheadless.runSeconds=86400` · `-Dheadless.captureTargetFrames=999999` · `-Drunelite.menupeek=true` · `-Drunelite.menupeek.maxEntries=14` · `-Drunelite.mobileui=true` · `-Drunelite.mobilechrome=true` · `-Drunelite.hudveneer=true` · `-Drunelite.nosafemode=true` · `-Drunelite.rendererpanel=true`.
**Conditionally appended:** `-Drunelite.forceDisablePlugins=<other renderer keys>` (always `:2697`); `-Drunelite.forceEnablePlugins=<gpuplugin|hdplugin>` (if a renderer chosen `:2699`); `-Drunelite.headless.resize=<W>x540` (renderH hardcoded 540 `:2714-2717`); autologin set (`JagexTokens.autologinOpts` `:146-152`); census `-Drunelite.gatecensus=true`+`-Drunelite.huddata=true` (`:2740-2741`); worldmap dev `-Drunelite.worldmapprobe=true` (`:2749`); GPU `-Drunelite.routecgpu=true`+`gpuframedump`+`.dir` (`:2760-2762`); 117HD `-Drunelite.routecrlhd=true`+`gpuframedump`+`.dir`+`-Drunelite.fpsprobe=true` (`:2772-2777`). Smoke: `["-Djava.io.tmpdir=$tmpDir","-Drunelite.routec.smoke=true"]` (`:2481`). rlhdprobe adds `-Xmx512m`,`-Duser.home`,`-Djava.io.tmpdir`,`-Drunelite.rlhdprobe=true` (`:2531-2548`).
**Notable absences (vs iOS):** **no `-XX:+MirrorMappedCodeCache`, `-XX:-UseCompressedClassPointers`, `ReservedCodeCacheSize`, `DisablePrimordialThreadGuardPages`** — explicitly dropped (`:2646-2648`). No `-Xms`; only `-Xmx512m`. No GC selector. Because `ignoreUnrecognized=JNI_FALSE`, any stale flag is a hard VM-create failure.

#### Debug/probe/dead-code paths (flag for Phase 1)
| Item | Trigger | Location |
|---|---|---|
| GPU frame-time probe (`[gpuprobe]`, EXT_disjoint_timer_query, 8-query pool) | `setprop debug.routec.gputime 1` | `librlawt.cpp:142-175, 880-923` |
| Draw-call probe (`[drawcount]`) | `setprop debug.routec.drawcount 1` | `:117-124, 561-562, 629-635, 770-771, 988-1007` |
| Phase-4 caps probe (`[gpuprobe-caps]`) | **unconditional** at `createGLContext` | `:480-521, 828` |
| `eglSwapInterval` override | `setprop debug.routec.swapinterval {0,1,2}` | `:127-140` |
| clip-control force-off | `setprop debug.routec.clipcontrol 0` | `:433-435` |
| Shader source dump (`SHDR[..]`) | `touch files/rlhd_shaderdump` | `:649-656` |
| Legacy CPU-readback present | dead unless `RouteC_setPresentCallbacks` absent (stale .so) | `:1034-1071` |
| `glMultiDrawArraysIndirect`/`glBufferStorage_*` no-op forwarders | registered but never invoked (caps FALSE) | `:772-774` |
| `EXT_buffer_storage` persistent-map self-test writes (`0xC0FFEE`/`0x0BEEF`) | unconditional in caps probe | `:498-516` |
| GPU draw-call counters as plain (non-atomic) ints | safe only because all forwarders run on the single GL thread | `:122-124` |
| `rcProbePhase4Caps` cost at every context create | not gated — runs on every GPU/117HD boot | `:828` |

Note: production extraOpts unconditionally carry capture/probe props (`-Drunelite.headless.capture.*`, `-Dheadless.runSeconds=86400`, `-Dheadless.captureTargetFrames=999999`, `-Drunelite.gpuframedump=true` in GPU/RLHD) — diagnostic scaffolding still wired into the shipping launch path.

---

### 3.4 Shared headless bridges: inventory & classification
*(Shared / iOS-impacting — all 51 files live in `runelite-client/src/main/java/net/runelite/client/headless/`)*

**Wiring model.** Android's JVM entrypoint is `DeviceHeadlessBoot.main` (`MainActivity.kt:2792`; class at `ios/dev-tools/awtsmoke/DeviceHeadlessBoot.java`, NOT under `headless/`). It calls `RuneLiteHeadless.main` verbatim (`DeviceHeadlessBoot.java:47`), which installs `HeadlessModule` (Swing `ClientUI`→`HeadlessClientUI`), arms `MobileUiSmokeRunner` (dormant — needs `-Drunelite.mobileui.smoke=true`, never set), runs `RuneLite.main` (`RuneLiteHeadless.java:48-52`). Three reach-points then activate bridges:
1. **Per-frame seam** — `Hooks.draw`/`postComposite`/`drawScene` → `HeadlessSeams.draw/postComposite/drawScene` (`Hooks.java:430,453,554`). `HeadlessSeams` is a flag-gated dispatcher: each probe/bridge runs inside `guard(...)` only when its `isEnabled()` (`Boolean.getBoolean(...)`) is true. Most probes have flags Android never sets → callable-but-dormant.
2. **Native reverse-JNI** — `jvm_bootstrap.cpp` `FindClass`es 15 bridge classes after VM create (lines 518–871); `librlawt.cpp:185` resolves `JavaBridge`. Kotlin `JvmLauncher.kt` declares the `external fun` consumers (lines 120–284).
3. **MobileUiBridge fan-out** — `MobileUiBridge.installNativeBridges` `init()`s + `tick()`s the eight tracker/edit bridges (`MobileUiBridge.java:299-306, 2485-2492`), riding `-Drunelite.mobileui=true` (always set).

Production flags actually set by Android: `runelite.bridge`, `runelite.menupeek(.maxEntries)`, `runelite.mobileui`, `runelite.mobilechrome`, `runelite.hudveneer`, `runelite.nosafemode`, `runelite.rendererpanel`, `runelite.headless.capture.*`, `headless.runSeconds/captureTargetFrames`; conditionally `runelite.headless.resize`, `runelite.headless.autologin.legacy`, `runelite.huddata`+`runelite.gatecensus` (census only), `runelite.worldmapprobe` (marker only), `runelite.routecgpu`/`routecrlhd`+`gpuframedump`/`fpsprobe` (renderer only).

> **Verification-corrected table.** Per the adversarial verifier (§4): **zero verdicts overturned**. Rows that the verifier specifically re-examined and **confirmed** are tagged `[VERIFIED]`. No row's status was changed.

| File | LOC | Purpose (1 line) | Status | Evidence | In contract? |
|---|---|---|---|---|---|
| JavaBridge.java | 2291 | Hot-path reverse-JNI: framebuffer blit + all touch/key input | **LIVE-SHARED** | `librlawt.cpp:185` FindClass; 27 native refs; `JvmLauncher.kt:120,152`; gated `runelite.bridge` | Yes |
| MobileUiBridge.java | 2873 | Thick control-plane: plugin list/toggle/config + fans out & ticks 8 tracker/edit bridges (`:299-306,2485-2492`) | **LIVE-SHARED** | `jvm_bootstrap.cpp:590`; 15 native refs; `JvmLauncher.kt:152-157`; flag `runelite.mobileui` | Yes |
| HudVeneerBridge.java | 2594 | A6 native-chrome **write** (hide stones/openTab/relocate) + before-render listener; lock-free seq-diff | **LIVE-SHARED** | `jvm_bootstrap.cpp:871`; 16 native refs; 5 android src; flag `runelite.hudveneer`; ticked `HeadlessSeams.java:216` | Yes |
| MobileChromeBridge.java | 553 | A6 read-only chrome **geometry** snapshot (tab-stone/viewport bounds); lock-free latch | **LIVE-SHARED** | `jvm_bootstrap.cpp:846`; 8 native refs; `JvmLauncher.kt:264,268`; flag `runelite.mobilechrome`; ticked `HeadlessSeams.java:204` | Yes |
| HudDataBridge.java | 497 | A2 read-only HUD **content** snapshot (chat lines/orb values); lock-free | **LIVE-SHARED (dormant in normal play)** | Only in-jar ref `HeadlessSeams.java:210`; flag `runelite.huddata` set **census-mode only** | Yes |
| MenuPeekBridge.java | 339 | Per-tick menu-peek snapshot (left-click default at pointer) for touch targeting | **LIVE-SHARED** | `jvm_bootstrap.cpp:567` (9 refs); flag `runelite.menupeek`; ticked `HeadlessSeams.java:305` | Yes |
| QuestHelperBridge.java | 613 | Native Quest Helper reflow snapshot/commands | **LIVE-SHARED** | `jvm_bootstrap.cpp:664` (8 refs); `MobileUiBridge.java:299` | Yes |
| WorldSwitcherBridge.java | 612 | Native World Switcher snapshot (WorldService reads) + hop | **LIVE-SHARED** | `jvm_bootstrap.cpp:694` (8 refs); `MobileUiBridge.java:300` | Yes |
| XpTrackerBridge.java | 389 | Native XP Tracker JSON snapshot | **LIVE-SHARED** | `jvm_bootstrap.cpp:724` registerDataPanel; `JvmLauncher.kt:215-217`; `MobileUiBridge.java:302` | Yes |
| LootTrackerBridge.java | 502 | Native Loot Tracker JSON snapshot + resetAll | **LIVE-SHARED** | `jvm_bootstrap.cpp:725`; `JvmLauncher.kt:218-223`; `MobileUiBridge.java:301` | Yes |
| GrandExchangeBridge.java | 667 | Native GE offers/history snapshot + item search | **LIVE-SHARED** | `jvm_bootstrap.cpp:731`; `JvmLauncher.kt:224-226`; `MobileUiBridge.java:303,637-689` | Yes |
| TimeTrackingBridge.java | 697 | Native Time-Tracking (farming/timers) JSON snapshot | **LIVE-SHARED** | `jvm_bootstrap.cpp:732`; `JvmLauncher.kt:229-231`; `MobileUiBridge.java:304` | Yes |
| OverlayBridge.java | 490 | Native overlay edit-mode (reposition/snap/reset) snapshot+commands | **LIVE-SHARED** | `jvm_bootstrap.cpp:736` (7 refs); `JvmLauncher.kt:235-242`; `MobileUiBridge.java:305` | Yes |
| InfoBoxBridge.java | 378 | Native infobox snapshot + long-press menu invoke | **LIVE-SHARED** | `jvm_bootstrap.cpp:793` (7 refs); `JvmLauncher.kt:257-260`; `MobileUiBridge.java:306` | Yes |
| ConfigProfileBridge.java | 230 | Native config-profile list/switch (mirrors desktop ProfilePanel) | **LIVE-SHARED** | `jvm_bootstrap.cpp:821` (7 refs) | Yes |
| RuneLiteAccountBridge.java | 153 | runelite.net OAuth config-sync session (start/stop/read) — distinct from Jagex login | **LIVE-SHARED** | `jvm_bootstrap.cpp:767` (5 refs); `JvmLauncher.kt:245-248` | Yes |
| HeadlessLogin.java | 718 | Auto-login state machine (EULA→Play-Now click); legacy + Jagex stages | **LIVE-SHARED** | `MainActivity.kt:2639,2642` arms it; reached via `RuneLite`/seam; 2 android src refs | Yes |
| HeadlessResize.java | 158 | Resizes CACIO CTC canvas to `WxH` after login | **LIVE-SHARED** | flag `runelite.headless.resize` set when render size differs (`MainActivity.kt:2717`); 1 android src ref | Yes |
| HeadlessCapture.java | 253 | Frame-dump/observe mainBufferProvider to capture.dir | **LIVE-SHARED** | `DeviceHeadlessBoot.java:72-76`; `HeadlessSeams.java:103,376`; flag `runelite.headless.capture.dir` set | No |
| HeadlessSeams.java | 409 | Per-frame dispatcher: routes `Hooks.draw` to every gated bridge/probe via `guard()` | **LIVE-SHARED** | `Hooks.java:430,453,554`; references ~30 probes | No |
| BridgeFaults.java | 81 | Rate-limited fault sink wrapping every seam tick | **LIVE-SHARED** | 17 in-jar refs; used by `HeadlessSeams.guard` | No |
| RLHDStarter.java | 254 | Starts/force-enables the 117HD plugin once in-world | **LIVE-ANDROID (renderer-gated)** | `jvm_bootstrap.cpp` 1 ref; flag `runelite.routecrlhd` (117HD only); `HeadlessSeams.java:176,350` | No |
| RouteCGpuStarter.java | 167 | Starts/force-enables the base GPU plugin once in-world | **LIVE-ANDROID (renderer-gated)** | flag `runelite.routecgpu` (GPU only); `jvm_bootstrap.cpp:1269`; `HeadlessSeams.java:168,343` | No |
| RendererPanelStarter.java | 78 | Force-enables the chosen renderer plugin via the panel selection | **LIVE-ANDROID** | flag `runelite.rendererpanel` **always set**; `HeadlessSeams.java:358`; 2 android src refs | No |
| TapItemOutlineOverlay.java | 69 | Flashes engine item-outline on a tapped inventory/bank item | **LIVE-SHARED** | armed by `JavaBridge.java` on item tap; client-thread overlay | No |
| TapWidgetOutlineOverlay.java | 54 | Flashes sprite-outline on a tapped sprite widget | **LIVE-SHARED** | armed by `JavaBridge.java` on widget tap | No |
| RuneLiteHeadless.java | 54 | Device/desktop entry: installs `HeadlessModule`, arms smoke, runs `RuneLite.main` | **LIVE-SHARED** | `DeviceHeadlessBoot.java:47` | No |
| MobileUiSmokeRunner.java | 1118 | Facade self-test daemon; armed but **inert** unless `-Drunelite.mobileui.smoke=true` | **PROBE/TEST** (armed, dormant) `[VERIFIED]` | only `RuneLiteHeadless.java:51` `arm()`; no production flag | No |
| WorldMapProbe.java | 1406 | One-shot world-map recon oracle (orb-open + pan/zoom/search, logs+PNGs) | **PROBE** (dev-marker-gated) `[VERIFIED]` | flag `runelite.worldmapprobe` only if `worldmapprobe.mode` marker exists (`MainActivity.kt:2749`); `HeadlessSeams.java:189` | No |
| WorldMapOrbsFix.java | 514 | Productized fix for accounts with "Data orbs" OFF | **PROBE/SPIKE** (productized but disarmed) | flag `runelite.orbsfix` — **never set**; `HeadlessSeams.java:195` | No |
| MenuPeek.java | 829 | Desktop CACIO de-risk of the menu-peek primitive | **PROBE** `[VERIFIED]` | flag `runelite.menupeek.probe` (distinct from live `.menupeek` — never set); `HeadlessSeams.java:319` | No |
| HudIsolationTest.java | 601 | A0 desktop scene-isolation/route-back lever de-risk | **PROBE/TEST** | flag never set; `HeadlessSeams.java:255`; refs `MobileChromeHideTest` | No |
| MobileChromeHideTest.java | 256 | Desktop test: does `setHidden`/`setVarcInt` hide tab stones? | **PROBE/TEST** | `HeadlessSeams.java:249`; ref by `HudIsolationTest` | No |
| EdgeToEdgeProbe.java | 306 | A1 desktop: prove HUD-suppressed edge-to-edge wide-aspect scene | **PROBE** | flag `runelite.e2e`(unset); `HeadlessSeams.java:261` | No |
| ChatRelocateProbe.java | 266 | A3 desktop: relocate engine chatbox to top & still render? | **PROBE** | flag `runelite.chatreloc`(unset); `HeadlessSeams.java:273` | No |
| MinimapRelocateProbe.java | 464 | A4 desktop: relocate minimap & keep click-to-walk? | **PROBE** | `HeadlessSeams.java:279` | No |
| MinimapWalkProbe.java | 338 | Desktop: route-back by synth-clicking the live engine minimap? | **PROBE** | `HeadlessSeams.java:267`; ref by `MinimapRelocateProbe` | No |
| MobileToplevelProbe.java | 226 | Spike: can desktop gamepack build mobile toplevel group 601? | **SPIKE** | `HeadlessSeams.java:184` | No |
| MobileChromeProbe.java | 374 | Desktop: what switches active tab on chrome tap (synth-click vs varc)? | **PROBE** | `HeadlessSeams.java:222` | No |
| MobileChromeReposProbe.java | 313 | Desktop: reposition real widgets into the OSRS-Mobile split-edge layout | **PROBE** | `HeadlessSeams.java:228` | No |
| MobileChromePanelProbe.java | 198 | Desktop: can the docked side panel be moved at all? | **PROBE** | `HeadlessSeams.java:243` | No |
| MobileChromeSpriteDump.java | 233 | One-shot desktop: dump tab-stone sprite PNGs | **SPIKE** | `HeadlessSeams.java:236` | No |
| StoneHideProbe.java | 353 | A4 desktop: push tab stones off-screen | **PROBE** | `HeadlessSeams.java:293`; ref by `TabScriptProbe` | No |
| StoneSuppressProbe.java | 498 | A4 desktop: find a lever to remove the stone row | **PROBE** | `HeadlessSeams.java:299` | No |
| TabScriptProbe.java | 353 | A4 desktop: open a tab by replaying its op-script | **PROBE** | `HeadlessSeams.java:287` | No |
| ShiftFeedSpike.java | 273 | "Sticky Shift" feasibility spike (synthetic held VK_SHIFT) | **SPIKE** `[VERIFIED]` | flag `runelite.shiftfeed`(unset); `HeadlessSeams.java:326`; 1 android src ref | No |
| WelcomePlayProbe.java | 206 | GPU Route C: dismiss post-login welcome so 3D renders | **PROBE** | `HeadlessSeams.java:141` | No |
| GpuHandshakeProbe.java | 147 | GPU Route C black-scene reproduction, DESKTOP-only (no GL) | **PROBE** | `HeadlessSeams.java:152`; ref by `TracingDrawCallbacks` | No |
| GpuSceneDiag.java | 187 | GPU Route C read-only scene-render diagnostic | **PROBE** | `HeadlessSeams.java:161,393`; installs `TracingDrawCallbacks` | No |
| TracingDrawCallbacks.java | 280 | Counting/delegating `DrawCallbacks` wrapper for the GPU diag | **PROBE (helper)** | installed by `GpuSceneDiag.java`; ref by `GpuHandshakeProbe` | No |

#### Summary (51 files)
- **LIVE-SHARED: 22** — bridges + seam infrastructure runtime-reachable on both ports. Includes the renderer starters' shared base and `Tap*OutlineOverlay`.
- **LIVE-ANDROID (renderer-gated): 3** — `RendererPanelStarter` (always armed), `RLHDStarter` (117HD only), `RouteCGpuStarter` (GPU only).
- **PROBE / TEST / SPIKE: 25** — desktop CACIO de-risk + GPU-diag probes, gated on flags Android never sets. All still *compiled* into the shared jar and *referenced by `HeadlessSeams`* → dormant, not unreferenced.
- **UNCLEAR: 0.**

**Caveat for Phase-1 slimming:** "PROBE" = *no production caller fires it*, NOT *unreferenced*. Every probe has ≥1 in-jar reference (almost all from `HeadlessSeams`). Slimming requires deleting both the file and its `HeadlessSeams` `if (X.isEnabled())` block (and sibling cross-refs: `TracingDrawCallbacks`↔`GpuSceneDiag`↔`GpuHandshakeProbe`; `MinimapWalkProbe`↔`MinimapRelocateProbe`; `MobileChromeHideTest`↔`HudIsolationTest`; `StoneHideProbe`↔`TabScriptProbe`; `WorldMapOrbsFix`←`WorldMapProbe`).

#### Per-frame snapshot / command surface (lock-free)
- **Snapshot-publishers (Java→native, read each display tick):** `MobileChromeBridge` (chrome geometry — tab-stone/viewport bounds, `getSnapshot`), `HudDataBridge` (HUD content — chat lines/orb values; census-mode only), `MenuPeekBridge` (left-click default at pointer), the eight `MobileUiBridge`-ticked tracker/edit bridges (XP/Loot/GE/Time/Overlay/InfoBox/QuestHelper/WorldSwitcher JSON, refreshed `MobileUiBridge.java:2485-2492`). `JavaBridge` publishes the live framebuffer.
- **Command-receivers (native→Java):** `HudVeneerBridge` (chrome **write** — `openTab`/`relocate*`/hide-stones, mids `jvm_bootstrap.cpp:879-882`), `JavaBridge` (input — `onClick`/`onClickPrecise`/`onText`/`onKeybind`/`onTouch`/`onCameraDrag`), `MobileUiBridge` (control-plane commands + GE/account/overlay delegators).

---

### 3.5 Build graph, dependencies & APK composition
*(Android-only; consumes shared RuneLite as pre-built jars)*

#### Gradle module graph
Two-module Gradle project rooted at `android/` (`rootProject.name = "RuneliteAndroid"`, `settings.gradle.kts:17`); a *separate* build from the upstream RuneLite multi-project — consumes RuneLite only as staged jars, never as a project dependency.

| Module | Path | applicationId | namespace | Role |
|---|---|---|---|---|
| `:app` | `android/app/` | `dev.audaro.helloshell` | `dev.audaro.helloshell` | The real client shell (Compose UI + JNI host + embedded JVM) |
| `:recon` | `android/recon/` | `dev.audaro.glesrecon` | `dev.audaro.glesrecon` | Track-G AG0 standalone GLES-capability probe app; **no embedded JVM, no NDK, no facade** (`recon/build.gradle.kts:1-4`); for Firebase Test Lab Robo |

**No dependency edge** `:app`↔`:recon` (`settings.gradle.kts:18-19`). `:recon` ships only `ReconActivity.kt` + 12 GLSL recon shaders under `recon/src/main/assets/gpu-shaders/`, producing a **2.4 MB** debug APK.

#### Plugins, SDK levels, signing, buildTypes
Root plugins (`build.gradle.kts:1-6`, `apply false`): `com.android.application` **8.6.0** (AGP), `org.jetbrains.kotlin.android` **2.0.20**, `org.jetbrains.kotlin.plugin.compose` **2.0.20**.

| Setting | Value | Source |
|---|---|---|
| `compileSdk` | 35 | `app/build.gradle.kts:9` |
| `minSdk` | 28 (Android 9) | `:13` |
| `targetSdk` | 35 | `:14` |
| `versionCode / versionName` | 1 / "0.1" | `:15-16` |
| `sourceCompatibility / jvmTarget` | Java 17 | `:44-50` |
| `abiFilters` | **arm64-v8a only** | `:18-22` |
| Gradle JVM heap | `-Xmx2048m` | `gradle.properties:1` |
| R8 nonTransitiveRClass | enabled | `gradle.properties:4` |

**Signing (Phase-1 note):** the **release buildType is signed with the `debug` key** — `signingConfig = signingConfigs.getByName("debug")` (`app/build.gradle.kts:66`); rationale "personal/technical sideload (never shipped)" (`:63-65`); no dedicated release keystore.
**buildTypes (`:52-68`):** `debug` `isMinifyEnabled=false`; `release` **`isMinifyEnabled=false` and `isShrinkResources=false`** (`:61-62`) — R8/minify off **on purpose** because the engine + `net.runelite.client.headless.*` bridges, `DeviceHeadlessBoot`, `UnsafePokeActivation` resolve **by name via JNI `FindClass`** and RuneLite reflects internally; shrinking would break the engine (sound given the FindClass-by-name architecture; 51 bridge classes confirmed in the shared `headless/`). `buildFeatures`: `compose=true`, `buildConfig=true` (`:72-75`; `BuildConfig.DEBUG` gates dev-only logging).

#### Dependencies (`:app`) — all AndroidX/Compose/Kotlin (no RuneLite/LWJGL/JVM via Gradle)
| group:artifact:version | Role | Weight |
|---|---|---|
| `androidx.core:core-ktx:1.13.1` | Core KTX | small |
| `androidx.appcompat:appcompat:1.7.0` | AppCompat base | small/med |
| `androidx.browser:browser:1.8.0` | outbound links + A3 on-device login (Custom Tab) | small |
| `androidx.compose:compose-bom:2024.09.00` | Version-align Compose | n/a (BOM) |
| `androidx.compose.ui:ui` (BOM) | Compose runtime/UI | med (DEX) |
| `androidx.compose.material3:material3` (BOM) | Material3 widgets | med (DEX) |
| `androidx.activity:activity-compose:1.9.2` | Compose↔Activity glue | small |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1` | Coroutines | small/med |

Native footprint from deps: only `libandroidx.graphics.path.so` (~10 KB, transitive Compose) in `lib/arm64-v8a/`. DEX (`classes.dex` 13.3 MB + `classes2.dex` 5.6 MB ≈ **19 MB**) is overwhelmingly Compose+AndroidX.

#### APK composition (release ~116 MB measured)
**Measured on-disk:** release = **116 MB** (`app-release.apk`, 116,155,287 B), debug = **184 MB**. **Critical caveat: the on-disk APKs are STALE/inconsistent with current boot code** (see Phase-1 flags). Release breakdown (uncompressed; JRE/jars stored uncompressed via `noCompress`):

| Component | Uncompressed size | Compressible? | Why it exists |
|---|---|---|---|
| `assets/jvm/jre/lib/modules` | **110.8 MB** | No | OpenJDK 21 module image (`jimage`) — the class library; dominates the APK |
| `assets/jvm/client-…-shaded.jar` | 30.6 MB | No | Pristine RuneLite + deps (`:client:shadowJar`) — CPU client + surgery source |
| `assets/jvm/jre/lib/server/libjvm.so` | 18.5 MB | No | HotSpot; dlopen'd by absolute path |
| `classes.dex` + `classes2.dex` | 13.3 + 5.6 MB | (DEX) | Kotlin/Compose/AndroidX shell |
| JRE native libs (`libfontmanager.so` 2.2 MB, `libfreetype.so` 0.68 MB, `libawt.so` 0.59 MB, `liblcms`, `libmlib_image`, `libjimage`, `libjdwp`, … 33 `.so` total) | ~5 MB aggregate | No | bionic JRE native layer (incl. hand-built `libawt_xawt.so`) |
| `assets/jvm/jre/lib/fonts/*.ttf` (Lucida ×~6) | ~2 MB | No | JRE bundled fonts |
| `lib/arm64-v8a/libhelloshell.so` | 0.42 MB (release) | No | JNI host |
| `resources.arsc` + Compose/AndroidX resources | ~0.6 MB | n/a | App resources |
| `assets/jvm/probes.jar` | 0.14 MB | No | Headless entrypoints + **CACIO folded in** + W1 probes (no separate `cacio/` dir; `.gitignore:17 cacio/` is vestigial) |
| `assets/jvm/jre/lib/jrt-fs.jar` | 0.11 MB | No | JRT filesystem provider |
| `assets/jvm/hello.jar` | 1.5 KB | No | Milestone-A smoke; the **only** jvm-asset jar tracked in git (`.gitignore:22-23`) |

**The three client jars (all built by jar surgery from the one pristine shaded jar):**

| Jar | Size | Contents | Built by | Loaded when |
|---|---|---|---|---|
| `client-…-shaded.jar` | 28–29 MB | Pristine RuneLite + deps incl. real `org.lwjgl.*`/`net.runelite.rlawt` | `:client:shadowJar` | Surgery source + desktop-dev; *no longer a boot target* (`MainActivity.kt:2582-2583`) |
| `client-…-gpu.jar` | 28 MB | Shaded jar with `org/lwjgl/*`,`net/runelite/rlawt`,`…/plugins/gpu/*.class` **stripped** + a pure-Java facade (LWJGL/AWTContext→`native`, backed by `librlawt` RegisterNatives); unmodified gpu plugin recompiled against the facade; shaders+`regions.txt` kept | `android/dev-tools/gpu/stage-android-gpu-jar.sh` | **Every** boot (`useGpuJar=true`, `:2584-2585`) |
| `client-…-rlhd.jar` | 8.7 MB | Vendored 117HD hub plugin (`rs117.**`) compiled against [facade + lwjgl-stripped client] + shaders/JSON/textures + GLSL-ES overlay; carries **no** lwjgl/rlawt | `android/dev-tools/gpu/stage-rlhd-zone-jar.sh` | Rides **every** classpath (`:2589-2591`); hard-required only on rlhd boot |

#### Asset / JVM-unpack lifecycle
**Staging (build):** (1) `tools/stage-android-jre.sh` downloads the SHA-pinned bionic arm64 OpenJDK 21 (`8d41ec…`), verifies it's bionic aarch64, stages the whole JRE incl. every `.so` into `assets/jvm/jre` (assets ONLY), builds `libawt_xawt.so` via NDK, and enforces the **"jniLibs trap"** (deletes any JRE `.so` from `jniLibs/arm64-v8a`, leaving only `libhelloshell.so`; a JRE `.so` in jniLibs makes libawt's `realpath()` fail → VM abort). (2) `tools/stage-mobile-runtime.sh` builds the shaded jar + `probes.jar`, copies both, writes `assets/jvm/runtime.tag` = first 12 hex of `sha256(shaded + probes + gpu-variant jars)`; records gitignored `tools/last-stage.manifest` (version `1.12.29-SNAPSHOT`, content-tag `7e2e029f7381`). (3) `stage-android-gpu-jar.sh` + `stage-rlhd-zone-jar.sh` stage the variant jars **before** step 2 so their SHAs fold into `runtime.tag`.

Everything under `assets/jvm/` ships uncompressed (`noCompress += listOf("jar","so","dat","bin")`, `app/build.gradle.kts:40-42`). JRE `.so`s gitignored (`.gitignore:11-18`); only `hello.jar` tracked.

**Unpack (first launch) — `AssetInstaller.ensureInstalled(ctx)`:** `want="v5-<bundledTag>"` (`AssetInstaller.kt:26,30-32,44-49`); read `.installed-tag`; if `have!=want` → `root.deleteRecursively()` (wipe stale tree) + `copyAssetDir` + write marker (`:34-39,51-65`); return `filesDir/jvm/jre` as JAVA_HOME (`:40`). Re-unpack triggered by (a) jar update (recomputed `runtime.tag`) or (b) hand-bumped `BASE`; the recursive copy of 132 MB JRE + ~67 MB jars is the ~30 s stall; unchanged installs skip it. **Secondary jar-copy fallback (boot time):** because `ensureInstalled` only re-unpacks on a tag change, `bootJvm` (`MainActivity.kt:2586-2601`) + AG1a/probe paths (`:2468-2472,2509-2517`) lazily copy a needed jar **only if the device file is absent** — the `runtime.tag` SHA fold-in prevents a stale variant serving forever. **Boot classpath** (`:2603-2620`): `[client-…-gpu.jar : client-…-rlhd.jar : probes.jar]`; `java.library.path = jre/lib`; `useGpuJar=true` unconditionally (`:2584`).

#### Native build (CMake → 2 `.so`s)
`cpp/CMakeLists.txt` (cmake 3.22.1, `cppFlags=-std=c++17`): `libhelloshell.so` (`jvm_bootstrap.cpp` 2,484 LOC; links log/android/dl; resolves `JNI_CreateJavaVM` by dlopen of the JRE's `libjvm.so` at runtime); `librlawt.so` (`librlawt.cpp` 1,296 LOC; links log/EGL/GLESv3; AG1a Android EGL/GLES backing; no ANGLE/gl4es; dlopen'd by helloshell). **`extractNativeLibs="true"`** (`AndroidManifest.xml:28`) — required because the JRE dlopens its `.so`s from filesDir by absolute path. Only `libhelloshell.so`+`librlawt.so` (+ ~10 KB Compose `libandroidx.graphics.path.so`) in `lib/arm64-v8a/`; the JRE's 33 `.so`s travel as assets (the trap).

#### Bloat / dead artifacts (Phase-1 flags from this reader)
1. **STALE/inconsistent on-disk APKs — highest priority.** Boot code globs `-gpu.jar` and requires `librlawt.so`, but `app-release.apk` contains **NEITHER the gpu jar, the rlhd jar, NOR `librlawt.so`** — only `shaded.jar + hello.jar + probes.jar` + `libhelloshell.so`. The **debug APK** *does* contain gpu.jar + rlhd.jar + gpu-smoke.jar + librlawt.so. The two checked-in outputs were built at different times; **the release APK as built would fail to boot** (no gpu jar to glob, no librlawt to dlopen). Build-reproducibility/staleness defect (explains the ~116 vs ~111 MB delta).
2. **`gpu-smoke.jar` (28 KB)** — dev-only AG1a smoke (`MainActivity.kt:2456-2487`); ships in debug APK, no production caller.
3. **`hello.jar` (1.5 KB)** — Milestone-A smoke; only tracked jvm jar; in every APK.
4. **`shaded.jar` shipped alongside `gpu.jar` (≈30 MB duplicate)** — once `useGpuJar=true` is unconditional, the pristine shaded jar is a non-boot artifact; large duplicate-class overlap across `shaded`/`gpu`; Phase-1 dedup/exclusion candidate.
5. **`recon` module** builds a second installable APK (`dev.audaro.glesrecon`, 2.4 MB) — dev/test probe; flag whether it should be in the default `assemble`.

---

### 3.6 Login, auth & account-sync data path
*(Jagex/legacy Kotlin = Android-only; RuneLite-account bridge + HeadlessLogin = shared)*

Two **independent** logins: (1) **Jagex game login** → `tokens.json` → `JX_*` env → gamepack auth (bespoke on-device); (2) **RuneLite-account login** (`runelite.net`, config/plugin sync ONLY — NOT Jagex) → stock `SessionManager` OAuth, bridged to the shell.

#### A. Boot-time login gate
`MainActivity.onCreate` runs a **login-first boot gate** before the JVM boots (because `JX_*` is snapshotted at `JNI_CreateJavaVM`):

| Step | Location | Behavior |
|---|---|---|
| Gate check | `MainActivity.kt:893-901` | If not census/rlhd AND `!JagexTokens.hasTokens()` AND `!LegacyCreds.hasCreds()` → `startActivity(LoginActivity)`, `finish()`, `return` |
| Env arm (Jagex) | `:2641` | `JagexTokens.loadAndArm(app)` sets `JX_*` **before** `startJvm` |
| Env arm (legacy) | `:2644` | `!autologinArmed && LegacyCreds.loadAndArm(app)` — Jagex wins when both exist |
| Autologin opts | `:2725-2735` | Adds `JagexTokens.autologinOpts` (+ `-Drunelite.headless.autologin.legacy=true` for legacy) |
| NetProbe diversion | `:807-809` | If `netprobe.mode` marker → `NetProbe.run(app)` only, no JVM boot |

`autologinOpts` (`JagexTokens.kt:145-153`) hard-codes click coords: EULA `302,312`, Play Now `382,248`, world `301`/`oldschool1.runescape.com`.

#### B. Jagex OAuth flow (Custom Tab → Cloudflare → localhost → clipboard)
Embedded WebView is Cloudflare-walled at Jagex auth; the consent client accepts **only** `redirect_uri=http://localhost` (loopback-port + custom-scheme rejected) → result carried back **by hand via clipboard/paste** (`LoginActivity.kt:23-39`).

| # | Step | file:line |
|---|---|---|
| 1 | base64url nonce + state (24 random bytes, `SecureRandom`) | `LoginActivity.kt:44-45`→`JagexLogin.randomToken()` `:45-49` |
| 2 | Build Stage-A OIDC-hybrid consent URL (`response_type=id_token code`, `redirect_uri=http://localhost`, `scope=openid offline`, **`prompt=login`**) | `LoginActivity.kt:82-85,120-135`→`JagexLogin.consentUrl()` `:51-63`; const `:33-36` |
| 3 | Open in **Chrome Custom Tab** `SHARE_STATE_ON` (clears Cloudflare + human-check PAT); fallback `ACTION_VIEW` | `LoginActivity.kt:126-134` |
| 4 | Chrome lands on `http://localhost/#id_token=…` ("refused to connect" — expected) | `:32-34` |
| 5 | User copies link; on return `onWindowFocusChanged(true)`→`maybeAutoImport()` auto-reads clipboard (gated on `id_token=` + `localhost` shape) | `:137-156` |
| 6 | (Or paste into `pasteField` → "Import login link") | `:94-100` |
| 7 | `tryImport`: split after `#`, extract `id_token` via `JagexLogin.valueFrom` | `:160-171` (`JagexLogin.kt:66-76`) |
| 8 | **`clearLink()`** wipes paste field + system clipboard (`clearPrimaryClip`) | `:175-178` |
| 9 | `handleRedirect`: `mintSession(idToken)` POST `auth.runescape.com/game-session/v1/sessions` → `sessionId` | `:180-189` (`JagexLogin.kt:79-85`) |
| 10 | `fetchAccounts(sessionId)` GET `…/game-session/v2/accounts?fetchMembership=true` Bearer | `:190` (`JagexLogin.kt:88-92`) |
| 11 | `presentPicker` — auto-select single, else `AlertDialog` | `:199-212` |
| 12 | `finishWith`→`writeTokens(...)` writes `tokens.json` to `filesDir` | `:214-219` (`JagexLogin.kt:107-137`) |
| 13 | `restartIntoCleanBoot`: `NEW_TASK|CLEAR_TASK`, `finishAffinity()`, `Runtime.exit(0)` | `:223-231` |

Mint/REST (9–10) use the **launcher UA** (`JagexLogin.LAUNCHER_UA`, spoofed Mac `JagexLauncher/2.4.4`, `:40-42`) because the game-session API is UA-sensitive; not bot-blocked (only browser auth is). Plain `HttpURLConnection` 15s/20s timeouts (`:147-173`). Re-entrancy guards `launched`/`importing`/`done`/`lastTried` (`:46-49`).

#### C. tokens.json — storage, precedence, env
**Written** (`JagexLogin.writeTokens` `:107-137`) to `File(ctx.filesDir,"tokens.json")` (app-private). Stage-A fields only: `session_id`,`accounts`,`chosen_account_id`,`chosen_display_name`; access/refresh/id_token **empty** (gamepack mints its own). Also calls `JagexTokens.clearLoggedOut(ctx)`.
**Read precedence** (`JagexTokens.readSource` `:30-55`): (1) `filesDir/tokens.json`; (2) `getExternalFilesDir(null)/tokens.json` **DEBUG only** (`adb push` dev flow `:30-36`); (3) **bundled `assets/tokens.json`** lowest priority, **suppressed** if `logged_out.marker` exists (`:41,47-49`). Note: `assets/tokens.json` exists in the working tree (`android/app/src/main/assets/tokens.json`, 1091 B, `-rw-------`, gitignored `.gitignore:20`) — a real bundled session for sideload bundles.
**Env mapping** (`loadAndArm` `:96-138`): `session_id`→`JX_SESSION_ID`, `chosen_account_id`→`JX_CHARACTER_ID`, `chosen_display_name`→`JX_DISPLAY_NAME`, `access_token`→`JX_ACCESS_TOKEN`(if present), `refresh_token`→`JX_REFRESH_TOKEN`(if present). `hasTokens` (`:63-71`) is the gate predicate. **Logout** (`deleteAll` `:77-93`) deletes internal+external `tokens.json` AND `legacy_login.json`, writes `logged_out.marker`; triggered from `doLogout` (`MainActivity.kt:1075-1098`) which also flushes config, purges `CookieManager`, restarts.

#### D. Legacy (non-Jagex) path — `LegacyCreds.kt`
No OAuth/browser — a username/password JSON the engine types into the classic login form.

| Aspect | Legacy | Jagex |
|---|---|---|
| Source | `legacy_login.json` `{username,password}` | `tokens.json` |
| Precedence | filesDir; external only DEBUG (`:26-32`); **no asset fallback** | filesDir → external(DEBUG) → asset |
| Env | `RL_LEGACY_USER`,`RL_LEGACY_PASS` (`:53-55`) | `JX_*` |
| Provenance | pushed via `set-legacy-login.sh` (`:16`) | minted on-device by LoginActivity |
| Precedence | only if Jagex didn't (`MainActivity.kt:2644`) | wins |

`HeadlessLogin` reads `RL_LEGACY_USER`/`PASS` from `System.getenv` (`HeadlessLogin.java:90-91`) — from **env not `-D`** to keep them out of logs. With `legacy=true`, `tickLegacy` (`:502-565`) enters the welcome panel (loginIndex 0), `client.setUsername`/`setPassword` + double-Enter (loginIndex 2).

#### E. HeadlessLogin — click/credential state machine (shared bridge)
`HeadlessLogin.tick(client,state,loginIndex)` (`:288-493`) once per `Hooks.draw`, inert unless `-Drunelite.headless.autologin=true`. Does NOT authenticate (the `JX_*` handoff auto-authenticates and parks at `LOGIN_SCREEN`); synthesizes the clicks: Stage-1 EULA Accept `EULA_X,EULA_Y` (loginIndex 12, `:380-402`); Stage-2 Play Now `CLICK_X,CLICK_Y` (loginIndex 10, `:438-492`) after `selectWorld`/`selectHopWorld` (F2P to avoid members bounce, `:624-668`); Welcome dismiss (`tryDismissWelcome` `:581-622`); FIX-9 reconnect on `CONNECTION_LOST` → "Try again" (loginIndex 9, `:294-309,414-436`); Bug-4 hop-via-relog `requestWorldHop`/`cancelWorldHop` from `WorldSwitcherBridge` (`:239-280`). Clicks = AWT `MouseEvent`s posted to the gamepack `Canvas` via system `EventQueue`, preceded by synthetic `FOCUS_GAINED` (`synthesizeClick` `:697-717`); `synthesizeClickScaled` scales fixed-765×503 coords to live canvas (`:688-695`).

#### F. RuneLite-account sync (runelite.net OAuth — config/plugin sync, NOT Jagex)
Syncs profiles+settings+installed hub/external plugins; does not touch the game session. Wired through `RuneLiteAccountBridge.java`, a static JNI facade over stock `SessionManager`.

```
PluginPanel.RuneLiteAccountRow (PluginPanel.kt:323-361)
  ├─ poll: accountSessionJson() ─JNI─► nativeAccountSession (jvm_bootstrap.cpp:2326-2330)
  │                                  └─► RuneLiteAccountBridge.getSessionJson (:98-119) → {available,loggedIn,username}
  ├─ "Log in":  accountLogin()  ─JNI─► nativeAccountLogin  (:2316-2320) └─► startLogin (:62-77) └─► SessionManager.login() (:182-211)
  └─ "Log out": accountLogout() ─JNI─► nativeAccountLogout (:2321-2325) └─► logout (:80-95 / SessionManager.java:213-217)
```
Facade published at boot (`jvm_bootstrap.cpp:767-789`); Kotlin externs `JvmLauncher.kt:245-248,433-436`; `init(sessionManager)` from stock `RuneLite.java:332`; row hides when `available=false` (`PluginPanel.kt:332`).
**The loopback redirect (auto-completes, no paste):** `login()` (`SessionManager.java:182-211`) starts a local `HttpServer` on `localhost:0` with `/oauth` (`:219-221`); `accountClient.login(port)` (`:201`,`AccountClient.java:63`) embeds the loopback port; `LinkBrowser.browse(oauthUrl)` (`:210`) → on Android funnels through `MobileUiBridge.publishLink` (`:259-274`), drained by `startBridgeDrain` (`MainActivity.kt:1747-1769`) → Chrome Custom Tab (`:1823-1838`); after auth the browser redirects to `http://localhost:<port>/oauth?username=…&sessionId=…` which hits the app's own loopback server → opens `AccountSession`, `saveSession()`, 302-redirects (`SessionManager.java:222-256`). Per memory: works through the Custom Tab with **no cleartext config** (only `INTERNET` declared `AndroidManifest.xml:4`; no `res/xml/` network-security-config, no `usesCleartextTraffic`).

#### G. NetProbe — diagnostic only (`NetProbe.kt`)
Gated by a pushed `netprobe.mode` marker (`MainActivity.kt:807-809`); runs instead of JVM boot. Answers "does a home-minted Jagex session authenticate from THIS device's IP?": logs egress IP (`api.ipify.org` `:26-30`), control `google.com/generate_204` (`:33`), reads `session_id` from pushed `tokens.json` (`:99-107`), makes the **exact** gamepack call GET `…/game-session/v2/accounts` Bearer (`:40-46`), raw TCP `oldschool1.runescape.com:43594` (`:51,87-97`). Plain `"RuneLite"` UA (`:64`); logs `NETPROBE …` no secrets.

#### H. Security-relevant notes (flags for Phase 5)
| # | Surface | Location | Note |
|---|---|---|---|
| S1 | id_token in clipboard | `LoginActivity.kt:147-156` | Redirect (carrying `id_token`) read from clipboard; mitigated by `clearLink()` (`:175-178`) but user copied it to history first |
| S2 | session_id at rest plaintext | `JagexLogin.kt:127` | `tokens.json` plaintext JSON (no Keystore), app-private dir |
| S3 | Bundled session in APK | `assets/tokens.json` | Real `session_id` ships inside the APK for sideload (gitignored but in tree/artifact) |
| S4 | Legacy password at rest plaintext | `LegacyCreds.kt:34-44` | `legacy_login.json` plaintext password |
| S5 | PII/session prefix in logs (DEBUG) | `JagexTokens.kt:127-133`, `JagexLogin.kt:129-131`, `LegacyCreds.kt:56-57` | DEBUG logs displayName/characterId/6-char session prefix/`user.take(2)`; release minimal (`:134-136`); password never logged — confirm split holds |
| S6 | session_id as Bearer over HttpURLConnection | `JagexLogin.kt:147-173`, `NetProbe.kt:59-75` | TLS but launcher-UA spoof + no cert pinning |
| S7 | session_id as process env | `JagexTokens.kt:119` | `JX_*` in `/proc/self/environ` (legacy deliberately uses env over `-D`) |
| S8 | RuneLite-account session on disk | `SessionManager.saveSession` (`:237`) | runelite.net UUID saved by stock code (separate from Jagex secrets) |
| S9 | Loopback OAuth server | `SessionManager.java:219-221` | `localhost:0` during account login window; mitigated by random port + short lifetime + `stopServer` (`:253-256`) |

---

### 3.7 Renderer selection & GPU/117HD bring-up
*(Selection/probe/auto-GPU = Android-only; starters = shared renderer-gated; librlawt = Android-only)*

Three **mutually exclusive** renderers, chosen **at process start** (JVM is one-per-process; classpath jar set + `librlawt` registration fixed at `JNI_CreateJavaVM`). UI → SharedPreferences → boot flags → `-D` props → shared starters that load/start the renderer plugin in-world.

| Renderer | What it is | Plugin class | Jar set | Native GL | Started by |
|---|---|---|---|---|---|
| **CPU** (default) | Vanilla software renderer; no GL plugin | none (`chosenKey=null`) | `gpu.jar` + `rlhd.jar` (facade jar, renderer plugins loadable-but-stopped) | none | nothing |
| **base-GPU** | Stock `gpu` plugin (zone renderer) over Android EGL/GLES3 | `net.runelite.client.plugins.gpu.GpuPlugin` | same facade jar | `librlawt.so` (EGL) | `RouteCGpuStarter` |
| **117HD** | `rs117.hd.HdPlugin` Zone renderer (shadows/normal maps/lighting) | `rs117.hd.HdPlugin` (`conflicts="GPU"`) | facade jar + `rlhd.jar` | `librlawt.so` (same bridge) | `RLHDStarter` |

Nuance (`MainActivity.kt:2579-2584,2618`): **every** boot uses the facade-backed `-gpu.jar` and appends `-rlhd.jar`. CPU mode = facade jar with **no routec flags** + `gpuplugin` force-disabled. The only real switch is the routec `-D` flags + force-enable/disable lists.

#### Select & persist
- **Persistence:** SharedPreferences file `"renderer"`, key `"mode"` ∈ `{cpu,gpu,117hd}`, default `cpu` (`rendererPref()` `:921-922`).
- **Selection writes** via `setRendererPref(mode)` (`:924-930`): writes `mode`, clears `autoDecided`/`autoGpuFailed`, deletes `auto-gpu-armed`, AlertDialog offers immediate **restart** (apply-on-next-launch `:931-939`).
- **Panel surfacing:** `RendererPanelStarter` (`:44-77`) loads **both** `GpuPlugin`+`HdPlugin` into the panel **without starting** (`MobileUiBridge.loadPluginNoStart`), gated `-Drunelite.rendererpanel=true` (always set).
- **Other entry points:** intent extras `routecgpu`/`routecrlhd`; markers `gpu.mode`/`rlhd.mode`/`rlhd.once`/`cpu.mode`; Game Loop (`TEST_LOOP`→117HD); TV/leanback→117HD (`:864-889`). Dev "GPU"/"HD" buttons restart with extra + one-shot marker (`restartIntoGpu` `:1038`; HD restart `:1047-1055`).

#### Resolution → boot → flags
```
onCreate (:844-913): auto-gpu-armed cookie check (:846-856) · first-run probeGpuCaps() seed (:857-861)
  prefRenderer=rendererPref() (:863); gpuMode=extra||gpu.mode||pref=="gpu" (:864-865)
  rlhdMode=extra||rlhd.mode||gameLoop||rlhd.once||TV||pref=="117hd" (:885-887)
  arm crash-cookie+watchdog iff pref∈{gpu,117hd} (:907-911)
  bootJvm(gpu=gpuMode&&!rlhdMode, rlhd=rlhdMode) (:912)
    └─ opts (:2648-2780): -Drunelite.nosafemode + .rendererpanel (:2681-2682);
        chosenKey=gpu?"gpuplugin":rlhd?"hdplugin":null (:2695);
        -Drunelite.forceDisablePlugins=<OTHER renderer key> (:2697);
        -Drunelite.forceEnablePlugins=<chosenKey> (:2698-2700);
        gpu: -Drunelite.routecgpu + gpuframedump (:2752-2764);
        rlhd: -Drunelite.routecrlhd + gpuframedump + fpsprobe (:2766-2779)
    └─ JvmLauncher.startJvm → native JNI_CreateJavaVM
```
**force-disable/force-enable** (`:2684-2700`) is load-bearing: `HdPlugin.conflicts="GPU"` means a RuneLite-account *synced* profile enabling the wrong renderer could stop the running one mid-session and destroy its GL context — force-disabling the non-chosen key wins over synced config.

#### The starters (shared, gated by `-D`, driven from `HeadlessSeams`)
| Starter | Flag | Seam | Behavior |
|---|---|---|---|
| `RouteCGpuStarter` | `routecgpu` | `HeadlessSeams.java:168-174`, post `:343-345` | Forces `gpu.uiScalingMode=LINEAR` first (avoids `scale/*.glsl` int↔float ShaderException, `:92-101`), waits for LOGGED_IN/LOGIN_SCREEN, forces canvas valid (EDT `validate()`), `loadAndStartPlugin(GpuPlugin)`; retries ≤30× until `client.isGpu()` (`:154-165`) |
| `RLHDStarter` | `routecrlhd` | `HeadlessSeams.java:176-182`, post `:350-352` | Same for `HdPlugin` (not auto-discovered — `rs117.hd.*` outside `loadCorePlugins` `:36-43`); applies per-platform 117HD config (below), loads via `loadAndStartPlugin` once (`:243`), ≤40 attempts (`:51`) |
| `RendererPanelStarter` | `rendererpanel` | `HeadlessSeams.java:358-360` | Loads both renderers no-start into the panel, once |

**117HD config seeding** (`RLHDStarter.tick` `:109-211`), Android vs iOS forked by `-Drunelite.gfxIos`:
- Android **safety-forced every launch** (not user-tunable): `uiScalingMode=LINEAR` (also client-thread-readiness probe `:120-122`), `asyncModelProcessing=false` (single thread-affine EGL context), `tiledLighting=false`.
- Android **user-tunable seed-once** (gated on `iosGfxSeeded` sentinel `GFX_SEEDED_KEY` `:55`, persisted): `MSAA_4`, `sceneResolutionScale=85`, `aniso=16`, `drawDistance=30`, `shadowResolution=RES_1024`, **POM/wind/characterDisplacement OFF** (`:141-143`). Kept HD: normalMapping, dynamicLights=SOME, shadowMode=DETAILED, shadowFiltering=SMOOTH.
- Note: `ANDROID-GPU-117HD-AUDIT.md` §4#1/§7 (cites `:111`) flags POM-on as the biggest win — the audit **predates the seed change**; live code at `:141` already sets `parallaxOcclusionMappingToggle=false` → treat that finding as resolved.

#### librlawt EGL path (cross-ref §3.3 for full detail)
- Source `cpp/librlawt.cpp` (1296 lines); per-ABI replacement for `AWTContext`+`org.lwjgl.opengl.*` facade (EAGL→EGL); `add_library(rlawt SHARED librlawt.cpp)` links EGL+GLESv3.
- Registration: `jvm_bootstrap.cpp` on a `routecgpu`/`routecrlhd` boot `dlopen("librlawt.so")` then `RouteC_registerGLNatives(env)` **before** the engine runs (`:1287-1334`); tables at `librlawt.cpp:1284-1293`.
- Context: offscreen pbuffer ES3 + RGBA8+DEPTH24 FBO (`:302-305`,`:284`); downport target from `GL_VERSION` (`:81-83`).
- Reverse-Z (Phase 1): `EXT_clip_control` via `eglGetProcAddress`, `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` enabled + **self-tested** at context creation (`sClipReverseZ` `:43-60`).
- Direct present: `RouteC_setPresentCallbacks` (`jvm_bootstrap.cpp:1335-1345`); presents straight to the SurfaceView via EGL window surface (`eglCreateWindowSurface` `:955`, `eglSwapBuffers` `:1027`) — no glReadPixels.

#### Auto-GPU caps-probe + crash-cookie fallback (Phase 3, device-verified per memory)
- **First-run caps probe** `probeGpuCaps()` (`:952-1010`): throwaway EGL ES3 pbuffer, reads ES tier, `GL_MAX_SAMPLES`, `GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS`, renderer string; returns `"117hd"` (esTier≥3.1 && compute>0), `"gpu"` (esTier≥3.0 && maxSamples≥1), else `"cpu"` (`:993-995`); seeds `mode`+`autoDecided` once.
- **Crash-cookie:** before a pref-driven GPU/117HD boot, write `filesDir/auto-gpu-armed` + `startAutoGpuWatchdog()` (`:907-911`). Next `onCreate`, cookie still present (prior boot crashed/blacked/hung before presenting) → revert `mode` to `cpu` (permanent for AUTO via `autoGpuFailed`, one-shot for user pick) (`:846-856`). Dev/CI triggers deliberately **not** cookie-protected (`:904-906`).
- **Watchdog** (`:1019-1034`): polls `nativeGpuFramesPresented()`; ≥3 presented frames within 240s → delete cookie (commit); else CPU next launch. Native counter `sFramesPresented` incremented per `eglSwapBuffers` (`librlawt.cpp:98-101`/`RouteC_framesPresented` `:935`), surfaced via `JvmLauncher.kt:141` (JNI `jvm_bootstrap.cpp:1615`).
- Auto-GPU promotion **explicitly gated** on Phase 1 (depth correct) + Phase 2 (config persists) (`ANDROID-GPU-117HD-BLUEPRINT.md:82,156`).

#### `:recon` module (`dev.audaro.glesrecon`) — separate APK, NOT in the main app
Track-G AG0 standalone GLES recon (no embedded JVM/NDK/facade). `ReconActivity` (`recon/src/main/java/dev/audaro/glesrecon/ReconActivity.kt:24-31`) brings up a headless EGL ES3 context and logs the device's real GLES tier + extensions (`ANDROID_AG0 … VERDICT`): ES tier, compute, `EXT_clip_control`, `EXT_texture_format_BGRA8888`, `GL_MAX_SAMPLES`, RGBA16UI/depth renderability, FBO+readback round-trip (`:15-23`). For Firebase Test Lab Robo (can't pass intent extras). Emulator (ANGLE→SwiftShader) is invalid; needs a real device. **Confirmed separate:** own `applicationId`, own `MAIN/LAUNCHER`, `include(":recon")`, **no `project(":recon")` dependency in `:app`** → installs alongside, never inside the main APK.

#### Dead / scaffolding GPU probes & diag (Phase-1 flags from this reader)
| Item | Gate flag | Armed on Android today? | Status |
|---|---|---|---|
| `GpuHandshakeProbe` | `runelite.gpuhandshake` | **No** | DESKTOP-ONLY reproduction; "must NEVER be armed on device" (`GpuHandshakeProbe.java:71-73`). Dead scaffolding |
| `GpuSceneDiag` | `runelite.gpudiag` (+`.stack`) | **No** | Read-only black-scene localizer; bring-up complete → scaffolding |
| `gpuframedump` | `runelite.gpuframedump` | **Yes**, every GPU + 117HD boot (`MainActivity.kt:2761,2773`) | Frame-dump-to-external diagnostic armed unconditionally — should be DEBUG/CI-gated, not shipped on release GPU/117HD |
| `fpsprobe` | `runelite.fpsprobe` | **Yes**, every 117HD boot (`:2777`) | FIX-13 once/sec logcat fps probe; candidate to gate behind `BuildConfig.DEBUG` |
| `rlhdprobe` path (`bootRlhdProbe`) | intent `rlhdprobe` / `rlhdprobe.mode` | Marker-gated only | Headless 117HD class-load probe (`:823-829`, impl `~:2495`) — dev-only |
| Phase-4 EXT probes in librlawt (`rcProbePhase4Caps`, `EXT_buffer_storage`/`EXT_multi_draw_indirect`) | always (context creation) | Yes | Phase 4 **CLOSED**; probe only *gates* unimplemented/no-op'd levers (`librlawt.cpp:62-72`) — levers are dead code |

#### Cited perf conclusions (reference only)
- 117HD **not GPU-bound at 50 fps cap** (~25% headroom); POM-off no win *capped* but ~+10–11 fps *uncapped* — measure uncapped (`ANDROID-GPU-117HD-AUDIT.md` §1/§4#1).
- Shipped Phase-2 set: `MSAA_4`+`aniso 16` (tiler-cheap), `sceneResolutionScale=85` only budget-breaker; config persists via stock `ConfigManager` timer/shutdown, not per-edit.
- Phase-3 Task C (auto-GPU caps-probe + crash-cookie) **DONE + device-verified** on Mali-G710; remaining Tasks A (`getFramebuffer` micro-opt) + B (overlay seq-gate) ≈ 0 fps (GPU-bound).

---

## 4. Adversarial bridge verification

The verifier independently grepped all four surfaces — `android/app/src/main/cpp/*.cpp` (FindClass / GetStaticMethodID literals), `android/app/src/main/java` (Kotlin), the `headless/` cross-references, and `tools/shared-contract/baseline.txt` — plus checked for dynamic class loading (`Class.forName`, variable-fed `FindClass`), reflection, Guice/`HeadlessModule` wiring, and `main()` entrypoints.

### (a) Verdicts OVERTURNED — **None.**
Every "PROBE/TEST/SPIKE" verdict survives refutation. No probe/spike/test class has a single runtime reference in native code, Kotlin, the contract, or any non-comment cross-file path. **The classification table in §3.4 is therefore unchanged; no rows were corrected.** Rows the verifier specifically re-examined are tagged `[VERIFIED]` in the table.

References that *looked* like they might overturn a verdict, all non-runtime on inspection:
- `WorldMapProbe` (`MainActivity.kt:2749`) — real code, already (correctly) classified dev-marker-gated; gate `worldmapprobe.mode` (`:2748`) is a pushed marker, default-absent. Other hit (`HudVeneerBridge.java:1172`) is a `{@code WorldMapProbe.synthClick}` Javadoc.
- `ShiftFeedSpike` (`MainActivity.kt:577`, `JavaBridge.java:2157`) — both comments ("proven by ShiftFeedSpike"). Flag `runelite.shiftfeed` NOT set.
- `ChatRelocateProbe`/`MinimapRelocateProbe`/`StoneSuppressProbe`/`TabScriptProbe` in `HudVeneerBridge.java` (lines 54,92,116,252,786,1142) — all Javadoc/inline comments, no runtime call.
- `MenuPeek` (probe) vs `MenuPeekBridge` (live): all 14 "external" hits were the `MenuPeekBridge` substring or Javadoc; whole-word `\bMenuPeek\b` (probe) appears **zero** times in native/Kotlin/contract. The probe gates on `runelite.menupeek.probe` (`MenuPeek.java:78`) — a distinct flag from the live `runelite.menupeek` — and it is NOT set.

### (b) Probe/dead verdicts CONFIRMED (negative evidence)
- **Native:** grep of all 23 probe class names across `jvm_bootstrap.cpp`+`librlawt.cpp` → **0 FindClass/method-id literals**. The only three variable-fed `FindClass` sites are safe: `registerDataPanel` (`:496`) fed only the four live data-panel bridges (lines 724,725,731,732); `callStaticVoidNoArg` (`:924`) fed only `"UnsafePokeActivation"` (`:1279,1306`); the entrypoint `FindClass` (`:1356`) loads only `a->mainClass` (`DeviceHeadlessBoot`).
- **Kotlin:** 0 probe-class references in `android/app/src/main/java` (the two apparent hits are the marker-gated arm + a comment).
- **Contract:** `baseline.txt` names exactly the 18 live bridges + `HeadlessLogin`/`HeadlessResize`; **zero** probe/spike/test classes.
- **Flags:** every probe-arming flag confirmed absent from `MainActivity.kt`: `runelite.mobileui.smoke`, `runelite.orbsfix`, `runelite.shiftfeed`, `runelite.e2e`, `runelite.chatreloc`, `runelite.menupeek.probe`. (The extra flags Android does set, `runelite.rlhdprobe`/`runelite.routec.smoke`, are read by **no** file under `runelite-client/src/main/java/` — they target renderer plugins outside `headless/`.)
- **No alternate reach-path:** no probe declares `static void main`, none is Guice/`HeadlessModule`-bound, and `Class.forName`/reflection inside `headless/` all target plugin classes (`GpuPlugin`, `Clock`, `BirdHouseData`, `LootRecord`, dynamic plugin names) — never a probe.

### Verification notes
The probes are **dormant, not unreferenced**. Each is referenced by `HeadlessSeams.java` (the per-frame `if (X.isEnabled()) guard(...)` dispatcher) and several by sibling probes. The "PROBE" verdict (no production caller fires it) is correct and safe; a naive file-only deletion would break compilation of `HeadlessSeams` and the sibling cross-refs, but would NOT break any JNI-by-name or reflection path because none exists. Phase-1 slimming must delete both the file and its `HeadlessSeams` `isEnabled()` block (+ sibling cross-refs).

---

## 5. Phase-1 candidate flags (consolidated)

> Everything any reader flagged as possibly dead / bloat / dev-only. **Collected, not decided.** Pointers for Phase 1.

**Shared bridges (probe/test/spike — dormant on Android; flag never set; still referenced by `HeadlessSeams` + siblings):**
- `MobileUiSmokeRunner.java` (armed every boot but inert without `-Drunelite.mobileui.smoke`).
- `WorldMapProbe.java` (dev-marker-gated `worldmapprobe.mode`, default-absent).
- `WorldMapOrbsFix.java` (productized but disarmed — `runelite.orbsfix` never set).
- `MenuPeek.java` (`runelite.menupeek.probe` never set — distinct from live `MenuPeekBridge`).
- `HudIsolationTest.java`, `MobileChromeHideTest.java`, `EdgeToEdgeProbe.java`, `ChatRelocateProbe.java`, `MinimapRelocateProbe.java`, `MinimapWalkProbe.java`, `MobileToplevelProbe.java`, `MobileChromeProbe.java`, `MobileChromeReposProbe.java`, `MobileChromePanelProbe.java`, `MobileChromeSpriteDump.java`, `StoneHideProbe.java`, `StoneSuppressProbe.java`, `TabScriptProbe.java`, `ShiftFeedSpike.java` — all desktop CACIO de-risk experiments, flags unset.
- `WelcomePlayProbe.java`, `GpuHandshakeProbe.java`, `GpuSceneDiag.java`, `TracingDrawCallbacks.java` — GPU Route-C diag probes; bring-up complete. (`GpuHandshakeProbe` "must NEVER be armed on device".)
- Slimming caveat: each requires deleting the file **and** its `HeadlessSeams` `isEnabled()` block **and** sibling cross-refs (`TracingDrawCallbacks`↔`GpuSceneDiag`↔`GpuHandshakeProbe`; `MinimapWalkProbe`↔`MinimapRelocateProbe`; `MobileChromeHideTest`↔`HudIsolationTest`; `StoneHideProbe`↔`TabScriptProbe`; `WorldMapOrbsFix`←`WorldMapProbe`). PROBE = no production caller, NOT unreferenced.
- `HudDataBridge.java` — LIVE-SHARED but **dormant in normal play** (snapshot published only in census mode, `runelite.huddata` census-only); confirm whether the native veneers ever consume it outside census.

**Dev/smoke jars shipping in assets:**
- `gpu-smoke.jar` (28 KB) — dev-only AG1a smoke path (`MainActivity.kt:2456-2487`); in debug APK, no production caller.
- `hello.jar` (1.5 KB) — Milestone-A smoke; only tracked jvm jar; in every APK.
- `client-…-shaded.jar` (≈30 MB) — non-boot artifact once `useGpuJar=true` is unconditional; large duplicate-class overlap with `gpu.jar`; Phase-1 dedup/exclusion candidate (keep build-side only).

**Build / APK staleness & bloat:**
- **STALE/inconsistent on-disk APKs (highest priority):** `app-release.apk` lacks the gpu jar, rlhd jar, AND `librlawt.so` that current boot hard-requires → as built it would fail to boot; debug APK has them. Build-reproducibility defect, not just bloat.
- Release buildType **signed with the `debug` key** (`app/build.gradle.kts:66`) — documented sideload choice; flag for review.
- `recon` module builds a second installable APK (`dev.audaro.glesrecon`, 2.4 MB) — dev/test probe; flag whether it belongs in default `assemble`.
- Vestigial `.gitignore:17 cacio/` entry (CACIO actually folded into `probes.jar`, no separate dir).

**Dead/dev-only Kotlin paths (shell):**
- DEBUG `BroadcastReceiver`s `SET_HD_CONFIG`/`SET_RECONNECT_OVERLAY` + `reconnectTestOverride` (`MainActivity.kt:310-368,186`).
- Probe boot gates that early-return from `onCreate`: `NetProbe` (807-811), `bootGpuSmoke`/`RouteCSmoke` (812-818,2461), `bootRlhdProbe`/`RLHDProbe` (823-830,2502), `worldmapprobe.mode` (2748-2751), census + `runA4/A6SeamSelfTest` (2851-3037) + `pollEnabled` (3040), `fpsprobe` (2776).
- devBar partially always-on: `gpuButton`/`hdButton` restart paths (`restartIntoGpu`/`restartIntoRlhd`, `MainActivity.kt:616,620`) are debug-only footguns overlapping the renderer-as-plugins picker; `ctrl/alt/chrome/gpu/hdButton` DEBUG-gated (632) but `kb/shiftButton` always shown (628).
- Marker-file boot-trigger sprawl (`gpu.mode`/`rlhd.mode`/`rlhd.once`/`cpu.mode`/`rlhd_prelogin.mode`/`netprobe.mode`/`rlhdprobe.mode`/`worldmapprobe.mode`) — consolidate (`MainActivity.kt:807-887,2725,2748`).
- One-shot green-log latches `chromeUiLogged`/`chatTabsLogged`/`chromeButton` (`162,194,1927,1970`).
- Per-frame UI-thread allocations: `JSONObject(chromeSnapshotPub)` every tick (no string-equality short-circuit, unlike the map path) + cached rect IntArrays (`updateChrome` `MainActivity.kt:1853,1891-1915`).
- Native UI: `PanelList`/`NativeRow` removed but still referenced in comments (`PanelView.kt:69-71`); `HotkeysScreen` writes only SharedPreferences bypassing the bridge (`PanelReflowScreens.kt:597`) — verify `PREFS_HOTKEYS` contract; `popSub()` order-coupling risk (`PluginPanel.kt:177-196,204-313`); `RuneLiteAccountRow` polls every 2 s forever with no `DisposableEffect` (`PluginPanel.kt:325-330`); `GrandExchange`/`XpTracker`/`TimeTracking` screens read-only — confirm intended.

**Dead/dev-only native paths:**
- librlawt `setprop`-gated diagnostics: `[gpuprobe]` gputime, `[drawcount]`, `eglSwapInterval` override, clip-control force-off, shader source dump (`librlawt.cpp:117-175,433-435,649-656,880-923`).
- librlawt always-on caps probe `rcProbePhase4Caps` at every context create (`:480-521,828`) — Phase 4 CLOSED; only gates unimplemented/no-op'd levers (`glMultiDrawArraysIndirect`/`glBufferStorage_*` no-op forwarders `:772-774`; `EXT_buffer_storage` self-test writes `:498-516`).
- Legacy CPU-readback present path — dead unless `RouteC_setPresentCallbacks` absent (stale .so) (`:1034-1071`).
- GPU draw-call counters as plain (non-atomic) ints — safe only on the single GL thread (`:122-124`).

**Renderer/diagnostic flags armed on production launches (candidate to gate behind DEBUG/CI):**
- `-Drunelite.headless.capture.*`, `-Dheadless.runSeconds=86400`, `-Dheadless.captureTargetFrames=999999` — unconditionally on every boot.
- `-Drunelite.gpuframedump=true` — every GPU + 117HD boot (`MainActivity.kt:2761,2773`).
- `-Drunelite.fpsprobe=true` — every 117HD boot (`:2777`).
- `rlhdprobe` path (`bootRlhdProbe`) — dev-only, marker-gated.

**Renderer starters (renderer-gated, shared — not dead, scope-flagged):**
- `RLHDStarter` (117HD only), `RouteCGpuStarter` (GPU only), `RendererPanelStarter` (always armed) — LIVE-ANDROID; editing them ripples to the shared jar (iOS shares `RLHDStarter`).

**recon module status:** standalone GLES-recon APK `dev.audaro.glesrecon` (Track-G AG0); no edge to `:app`; for Firebase Test Lab Robo; emulator-invalid (ANGLE→SwiftShader). Flag: keep in default `assemble`?
