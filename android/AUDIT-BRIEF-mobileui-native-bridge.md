# Audit brief — Android MobileUiBridge native bridge (A3.5 / A4 / A5)

> Self-contained brief for an **independent reviewer**. Goal: adversarially review the **native cross-VM JNI
> bridge** that wires RuneLite's plugin control plane + side-panels into the Android host (phases A3.5/A4/A5).
> The functional behaviour is already proven on the arm64 emulator (md5/screencap gates — see §4); those gates
> do **not** cover the failure modes this review targets: **JNI local-reference leaks, pending-exception
> hygiene, cross-VM array/string marshalling, and thread-safety/memory-ordering**. Find the bug that only shows
> up after hours of use, under load, or on a stricter device — not "does it work once." Prefer "here is the
> load-bearing thing that's wrong" over a broad endorsement. Verify every claim against the actual code.

## 0. Context the reviewer needs (the architecture)

This app's process is **ART** (Android's VM), but the game runs on a **second, in-process HotSpot VM** that the
native layer brings up with `JNI_CreateJavaVM` (`android/app/src/main/cpp/jvm_bootstrap.cpp`). The two VMs are
**separate JNI universes**: an ART thread is not attached to HotSpot, and a `jobject`/`jstring`/`jintArray` from
one VM is meaningless in the other.

The Kotlin host (`JvmLauncher`) declares `external fun native…` methods. When Kotlin calls one, the JVM hands
the C function an **ART** `JNIEnv*` (the `artEnv` parameter). To call the game's static bridge methods
(`net.runelite.client.headless.MobileUiBridge`, etc.) the C function must instead reach the **HotSpot** env:

- **`bridgeEnvAttach()`** (`jvm_bootstrap.cpp:241`) — `GetEnv`-or-`AttachCurrentThreadAsDaemon` on the HotSpot
  `JavaVM*` (`g_vm`) for the *current OS thread*. Daemon attach means **the thread is never detached**.
- **`publishBridge()`** (`:253`) resolves the bridge facade once, on the VM's boot thread: caches the `jclass`
  as a `NewGlobalRef` and each `GetStaticMethodID` into file-scope globals (`g_mobileUiCls`, `g_midListPlugins`,
  …). MobileUiBridge is resolved best-effort at `:313`–`:344` (a failure disables that surface, never the
  render path).
- **Strings/arrays cross the boundary as copies:** read a value from the HotSpot env (`GetStringUTFChars` via
  the `jstr()` helper at `:227`, or `GetIntArrayElements`), then re-create it in the ART env (`NewStringUTF` /
  `NewIntArray`+`SetIntArrayRegion`) to return.
- Kotlin callers go through **`JvmLauncher.onBridge { … }`** (`JvmLauncher.kt:161`) — a **single** dedicated
  daemon thread (`rl-bridge`, `:157`) so HotSpot only ever sees one ART-side caller and the **ART UI thread
  never attaches to HotSpot** (ANR risk). The Compose UI calls these off the main thread and pushes results
  back into Compose state.

Prior precedent worth matching: the existing `JavaBridge`/`MenuPeekBridge` wiring in the same file, and the
"Review-2" fixes (atomic `g_vm`; a missed `GetStaticMethodID` leaving a **pending exception** that tripped the
next call — see the `MenuPeekBridge` block). The bridges themselves (Java) are shared with iOS and out of scope.

## 1. What to review (exact targets)

| File | Focus |
|---|---|
| `android/app/src/main/cpp/jvm_bootstrap.cpp` | **(primary)** `bridgeEnvAttach` (:241), `jstr` (:227), the MobileUiBridge facade resolution (:313–:344), and the A4/A5 externs: `nativeListPlugins` (:786), `nativeSetPluginEnabled` (:814), `nativeGetPluginConfig` (:826), `nativeSetConfig` (:838), the `mobileUiStr`/`mobileUiInt` helpers (:862/:870), and the panel externs incl. `nativePanelPixels` (:906), `nativeOpenPanel` (:888), `nativePanelClick` (:897) |
| `android/app/src/main/java/dev/audaro/helloshell/JvmLauncher.kt` | the `rl-bridge` executor + `onBridge` (:157–:161); the `external fun` signatures vs the C signatures; `nativePanelPixels(): IntArray?` nullability |
| `android/app/src/main/java/dev/audaro/helloshell/MainActivity.kt` | `runA4SeamSelfTest` (:574) + `pollEnabled` (:674) — the on-bridge usage pattern; `ComposeView`/`setContent` host (:106–:128) |
| `PluginPanel.kt` / `ConfigScreen.kt` / `PanelView.kt` | **(secondary)** Compose state written from `onBridge`/`Dispatchers.IO`; the `getPanelPixels`→`Bitmap` path + tap→`panelClick` coord mapping in `PanelView.kt` |

## 2. Claims / risks to pressure-test (highest value first)

1. **JNI local-reference leak on the never-detaching `rl-bridge` thread.** Every extern that calls
   `CallStaticObjectMethod` gets a HotSpot **local ref** (and `setConfig`/`setPluginEnabled`/`openPanel` also
   create input strings with `NewStringUTF`). The thread daemon-attaches once and never detaches, so the local
   frame is effectively long-lived — **un-freed locals accumulate for the process lifetime**. Audit *every*
   path (including the early-return/exception paths and the `mobileUiStr` helper) for a `DeleteLocalRef` on
   every HotSpot ref created. The `getPanelPixels` poll (`PanelView.kt`, ~2–3 Hz) and the per-frame-ish reads
   make any leak here unbounded. **Is a `PushLocalFrame`/`PopLocalFrame` (or explicit deletes on all paths) in
   order?**
2. **Pending-exception hygiene.** After each `CallStatic*`, is `ExceptionCheck` + `ExceptionClear` guaranteed on
   *all* paths before the function returns? A leaked pending HotSpot exception trips the *next* bridge call
   (the exact Review-2 `MenuPeekBridge` bug). Check `nativeSetConfig` (3 input strings), `nativePanelPixels`
   (array path), and the `mobileUiStr`/`mobileUiInt` helpers.
3. **Cross-VM `int[]` marshalling** (`nativePanelPixels`, :906). `GetIntArrayLength`/`GetIntArrayElements` on the
   HotSpot array, `NewIntArray`+`SetIntArrayRegion` on ART, `ReleaseIntArrayElements(..., JNI_ABORT)`. Verify:
   null/zero-length handling, that `n` is read from the HotSpot array (not assumed), OOM behaviour if `NewIntArray`
   fails, and that the panel buffer (supersampled, up to ~3 M px ≈ 12 MB) being copied every poll is acceptable
   (any way to avoid the double copy / cap the rate?).
4. **Memory-ordering / publication of the cached globals.** `g_mobileUiCls` + the `g_mid*` method ids are plain
   (non-atomic) globals written once on the VM boot thread (`publishBridge`) and later read on the `rl-bridge`
   thread. Is there a guaranteed happens-before between publish and first use, or is this a data race in
   principle (even if benign in practice because publish completes at boot before any UI)? `g_vm` is atomic;
   these are not — intentional or oversight?
5. **Best-effort resolution leaving a half-initialized facade.** The MobileUiBridge block resolves core
   (list/toggle/config) all-or-nothing but the **panel** + **config** method ids are resolved as sub-best-effort
   (a null disables only that sub-feature). Confirm: each extern null-checks the *specific* method id it uses
   (not just `g_mobileUiCls`), so a partially-resolved facade can't call a null `jmethodID`. Any global left
   dangling on the failure path (e.g., a `NewGlobalRef` not freed when a sub-resolution fails)?
6. **Threading / ANR.** `onBridge` is a single-thread executor; the Compose UI does
   `withContext(Dispatchers.IO){ onBridge{…}.get() }`. Is `.get()` ever reachable on the main thread? Can the
   single bridge thread deadlock (e.g., a bridge call that blocks on the EDT/client thread while the UI awaits
   it)? `setPluginEnabled`/`setConfig` queue onto the engine EDT (fire-and-forget) — confirm no host-side wait
   can hang on a stalled EDT.
7. **Compose-state writes from a background context** (`PluginPanel`/`ConfigScreen`/`PanelView`). State is
   assigned after `withContext(Dispatchers.IO)`; confirm assignments resume on a Compose-safe dispatcher, and
   that `DisposableEffect{ onDispose{ closePanel() } }` + the polling `LaunchedEffect` can't leak the panel
   (repaint daemon left running) on rapid open/close/rotation.
8. **`PanelView` tap mapping** — `panelClick` buffer coords use `(off + scroll) * pw / viewW` (fit-width,
   uniform scale). Verify the supersample factor is already folded into `pw` (the Java side divides by it), so
   no double-scaling, and that vertical scroll offset is correct.

## 3. Lower priority / explicitly out of scope
- **Out of scope:** the shared Java bridges (`net.runelite.client.headless.*`) — proven on desktop+iOS; the
  blueprint/phasing docs; the BrowserStack/Firebase harnesses under `dev-tools/{browserstack,firebase}/`
  (unverified scaffolds, review after their first live run); Compose visual styling.
- **Lower priority:** the Compose control selection in `ConfigScreen.kt` (type→widget) and list/filter logic —
  correctness-check the data flow, not the aesthetics.

## 4. How to build + verify (ground truth)
```
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# seam self-tests (census mode) + the per-phase md5 gates:
adb shell am start -n dev.audaro.helloshell/.MainActivity --ez census true
android/dev-tools/a4-pluginplane/check-a4seam.sh     # list + toggle + config seam
android/dev-tools/a5-panels/check-a5.sh              # Tier-1 panel image seam
```
Current GREEN baselines (emulator `rl_arm64`): `ANDROID_A4_SEAM_GREEN listPluginsLen≈32k`,
`ANDROID_A4_TOGGLE_GREEN`, `ANDROID_A4_CONFIG_GREEN`, `ANDROID_A5_SEAM_GREEN … 968x404 px=391072`,
`ANDROID_A4_UI_GREEN`, `ANDROID_A4_CONFIG_UI_GREEN`, `ANDROID_A5_UI_GREEN`. The emulator GL is software
(SwiftShader); these prove logic, not perf/driver behaviour.

## 5. Output
For each item in §2: **CONFIRMED bug / NOT A BUG / UNCERTAIN**, with the file:line, a one-line root cause, and
the cheapest fix or the cheapest experiment that would settle it. Then any leak/race/edge case §2 missed. Rank
by severity (crash/leak/race > correctness > style). The native bridge (`jvm_bootstrap.cpp`) is the priority —
spend the budget there.

---

## 6. Review outcome (folded in 2026-06-08)

The review returned **four CONFIRMED issues** (none caught by the emulator gates — all latent under
load / OOM / on a stricter device) plus a missed edge case. All fixed in `jvm_bootstrap.cpp`; the A4/A5
emulator gates re-ran **GREEN with no regression** (`check-a4seam.sh` + `check-a5.sh`, exit=0; boot log now
shows `MobileUiBridge facade published (panels=on)`). Verdicts:

| # | Item | Verdict | Fix landed |
|---|---|---|---|
| 4 | Global publication / memory-ordering | **CONFIRMED** | `g_mobileUiReady`/`g_panelReady` release-store at end of `publishBridge`; every A4/A5 extern acquire-gates (`mobileUiReady()`/`panelReady()`) before reading the plain method-id globals — gives the happens-before so a UI tap before boot-complete can't read a half-published facade. |
| 2 | Pending-exception hygiene | **CONFIRMED** | `jstr()` clears a `GetStringUTFChars` OOM; `nativePanelPixels` clears a failed `GetIntArrayElements`/`NewIntArray`; the render-thread menu-snapshot read clears its OOM; all optional method-id lookups go through `midOpt()` (per-lookup clear). |
| 5 | Partial facade (panels) | **CONFIRMED** | Tier-1 panels are now all-or-nothing — `g_panelReady` true only when *every* panel method (incl. `closePanel`) resolves, so the viewer never opens a panel it can't close (no more silent dispose no-op → leaked engine-side panel). |
| — | `NewGlobalRef` unchecked before `GetStaticMethodID` | **CONFIRMED (missed edge)** | Null-checked after `NewGlobalRef` on both the MobileUi and MenuPeek facades before resolving any method. |
| 1 | Local-ref leak | NOT A BUG | Locals already deleted on all paths; left as-is. |
| 6 | Threading / ANR | NOT A BUG | `.get()` only off the UI thread (Dispatchers.IO); writes are fire-and-forget. |
| 8 | Tap mapping | NOT A BUG | Java divides by `activeRenderScale`; no double-scale. |
| 3 | `int[]` marshalling perf | UNCERTAIN (deferred) | OOM paths hardened (see #2). The ~12 MB-per-poll copy is bring-up-only; emulator gives no perf signal — revisit with a real-device soak / a seq-gated fetch when AG/perf work starts. |
| 7 | Compose state / dispose | UNCERTAIN (deferred) | State writes resume safely post-`withContext`; close is submitted not awaited. Revisit under a rapid open/back/rotation stress on a real device. |

Commit: `cf459bd53` (`android` branch).
