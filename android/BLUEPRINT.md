# Android Port — Blueprint to "117HD + all RuneLite plugins on Android"

> **What this is.** A forward-looking, followable execution plan whose **end state** is: real unmodified
> RuneLite + the full plugin ecosystem playable on Android, with the base `gpu` plugin **and 117HD**
> rendering through a native Android GLES context.
>
> **How it relates to the other docs.** This is the north-star *execution* plan. The companion docs stay
> authoritative for their scope:
> - [`PORT-PHASING.md`](PORT-PHASING.md) — the detailed per-phase tracker (A‑1…A8 + Track G), with GREEN
>   gates, decided open questions, and the iOS-parity feature inventory. **This blueprint does not replace
>   it; it sequences it toward the 117HD goal.**
> - [`../PORTS.md`](../PORTS.md) — the repo map (what is shared vs port-specific).
> - [`ROADMAP.md`](ROADMAP.md) — working standards / definition-of-done.
>
> **Branch model.** Android work lives on **`master`**. The iOS GPU/"Route C" work and the canonical
> 117HD plan (`ios/HD-PORT-BLUEPRINT.md`) live on the **`gpu` branch** — they are **prior art to mine, not
> code to merge.** Where this blueprint cites them, read them with `git show gpu:<path>`; their RLHD-internal
> specifics (renderer split, commit hashes) must be **re-audited against the current RLHD on Android**, not
> assumed.

---

## A. Where we are (done & emulator-verified)

The Android port is at the iOS "Phase‑4B" equivalent. Real unmodified RuneLite JIT-runs in a second,
in-process HotSpot (`JNI_CreateJavaVM` on a never-exiting pthread — **native JIT, no StikDebug/TXM/mirror
tax**), boots headless under CACIO CTC via `DeviceHeadlessBoot.main`, and blits the engine's CPU `int[]`
frame to a `SurfaceView` (`ANativeWindow`, `RGBA_8888`, R↔B swap).

| Phase | What | Gate evidence |
|---|---|---|
| **A‑1a** | Static bridge census (no device) | GREEN — `verdict=GREEN resolved=79 missing=0`, `census_md5=fd2c599160bfe1da1323709f02378286` (`dev-tools/bridge-census/`) |
| **A0** | Autologin → LOGGED_IN from pushed `tokens.json` | GREEN — `ANDROID_LOGGEDIN_GREEN md5=8aa7314f8d7b70116a5a3ad2e05b7d69` |
| **A1** | Touch input plumbing (read-surface) | GREEN — `ANDROID_A1_READSURFACE_GREEN md5=61b01f11fe972d7f6e338dc131ebc561` |
| **A2** | Press-and-peek native right-click menu | GREEN — `ANDROID_A2_MENUPEEK_GREEN md5=dc56e6b9e25cd46d2ef66ae7b7300152` |
| **Review‑2 hardening** | atomic `g_vm`, boot-fail `booted` reset, `g_surfaceGen` geometry-on-recreate, `MenuPeekBridge` JNI `ExceptionClear` | build-GREEN, commits `438621b2c` / `743f1d506` / `9b7d08361` |

**Load-bearing thesis (verified in-tree).** Every host-facing bridge already ships in the shaded jar both
ports embed: `runelite-client/src/main/java/net/runelite/client/headless/` holds **29 `.java` classes**, and
the 79-method static census resolves. So **A3–A8 is host/UI work with ~zero new *shared* Java** — set a `-D`
gate that already exists, write the Kotlin/native host + Android UI, call bridge methods that already exist.
The GPU track is the **only** place that needs a genuinely new native artifact (a per-ABI `librlawt`).

**Honest scope of that thesis.** "No new *shared* Java" ≠ "small." Each phase still adds real Android host
surface (new JNI externs + Kotlin/UI). The Java seam being done removes *engine-risk*, not *host/UI labour*.

> ⚠ **All GREEN above is arm64-emulator-only.** No real Android hardware is owned yet, and the emulator's GL
> is software (SwiftShader) → **not a valid proxy for the GPU track.** Two hard gates below (A0‑device, AG0)
> exist precisely to retire that.

---

## B. The two tracks, and the one seam where they collide

- **Track A — control plane / chrome / HUD (A3→A8).** Sequential. Ships **v1: a playable CPU-rendered
  client** with on-device login, native plugin config/hub, panels, and Jagex-Mobile chrome. The renderer
  stays the CACIO CPU rasterizer.
- **Track G — GPU / 117HD (AG0→AG5).** Runs **in parallel**, post-v1. End state = base `gpu`, then 117HD, on
  a native EGL/GLES context. **This is the user's headline goal** and the one axis where Android can
  genuinely beat iOS (native compute, free JIT, EGL-on-Surface).

**They meet at exactly one seam:** the frame-publish path in `jvm_bootstrap.cpp`
(`JavaBridge.getFramePixels()[I` → `ANativeWindow_lock`, lines 251/434). Track A's **A7** (edge-to-edge HUD)
and Track G's **AG2** (GL-to-Surface present) both rewrite that seam. **Sequence them so one render-seam
owner lands first** — do not let both restructure it blind. (Recommendation below: defer A7 from v1; let AG2
own the seam restructure.)

**Pin both tracks to one client-jar version *before* starting A3.** Source is `1.12.29-SNAPSHOT`, but the
staged Android asset jar and the `MainActivity.kt:451` classpath literal are still `1.12.28`. Restage to
1.12.29 and re-run the census first, so the census baseline, the GPU facade work, and the data audit all sit
on the same rev. (See **Step 1** in §G and the doc corrections that accompany this blueprint.)

---

## C. Track A — control plane → playable v1

### A‑1b — Runtime gate-flip census *(cheap; do now; closes the thesis)*
- **Goal:** prove each `-D` gate actually flips its bridge **inert→active at runtime** under a booted
  `DeviceHeadlessBoot` — not merely that the method resolves statically (A‑1a). Until A‑1b is GREEN, the
  "almost no new Java" claim rests on *static resolution alone*.
- **Tasks:** boot under each gate (`runelite.menupeek/huddata/mobilechrome/mobileui/hudveneer/headless.autologin`,
  plus `runelite.hub` + `runelite.pluginhub.version`); md5-gate an `isEnabled()==true` + a first
  `getSnapshot()`/`pollMobileUiEvents` readout from normalized `adb logcat`.
- **GREEN:** runtime activation log for all gates on the emulator.
- **Depends on:** restaged 1.12.29 jar + re-run static census.
- **Risk:** low (statically resolved), but if a gate doesn't activate cleanly under the headless boot, *that*
  bridge needs Java work after all — front-load the surprise.

### A0‑device — Real-device baseline gate *(HARD GATE — blocks declaring any A-phase truly done)*
- **Goal:** retire the single biggest device unknown: `dlopen` of `libjvm.so` from writable `filesDir` is
  nominally forbidden for API‑29+ targets (`PORT-PHASING.md` Q11), proven only on the emulator (the SELinux
  `avc: granted { execute }` is emulator-only).
- **Tasks:** run A0/A1/A2 on a **cloud device** (Samsung Remote Test Lab / Firebase Test Lab free tier first;
  AWS Device Farm / BrowserStack if needed). Confirm `filesDir` dlopen + the `RGBA_8888` SurfaceView blit on
  a real GPU/driver. Keep the fallback ready (ship the JRE `.so` set under a native-lib dir) in case a
  hardened device rejects W^X from app storage.
- **GREEN:** A0 LOGGED_IN + A1 read-surface + A2 menu reproduced on real hardware; md5 baselines re-captured
  (normalized line; fixed crop/orientation).
- **Risk:** if `filesDir` dlopen is rejected on hardened hardware, the JRE-staging model needs restructuring
  before anything ships. **This is the device-side go/no-go for the whole Android port.**

### A2‑device — Slide-to-row commit + the input paths adb can't drive
- **Goal:** close the input gaps the emulator can't exercise.
- **Tasks:** manual/device confirm (1) long-press → drag-to-non-cancel-row → release commits the right action
  (Attack/Follow) via the `selectMenuPoint` row math (currently only the **Cancel** path fired on the
  emulator — the 19px-header/15px-row geometry is **unverified**); (2) pinch-zoom `onWheel` via
  `ScaleGestureDetector`; (3) IME text into an in-game field (`onText`/`onKeyStroke`/`isTextInputOpen`).
  Also fix the input-queue drop policy: the 512-deep deque silently drops events **including possible
  UP/terminal events** — coalesce moves, prioritize releases.
- **GREEN:** a non-cancel menu row commits the correct engine action; pinch zooms; typing enters a field —
  all on real hardware.

### A3 — On-device Jagex login *(first net-new Android host code)*
- **Goal:** in-app login, replacing the pushed-`tokens.json` crutch.
- **Tasks:** `WebView` OAuth (launcher User-Agent for Cloudflare) → session mint → account picker → write
  `tokens.json`. **Must be a login-FIRST boot gate** (or force a clean process restart after login/account
  switch): `JX_*` env is snapshotted at `JNI_CreateJavaVM`, so a post-boot token write is missed — same
  lesson as the iOS "relaunch into a clean process" fix. UI: **Compose** for this data-driven flow (classic
  Views are reserved for the live per-frame overlays — `PORT-PHASING.md` §4.2 hybrid decision).
- **Secret hygiene (do here, before any credentialed run):** current token handling reads **external** app
  storage and pipes `System.out/err` (with session/display prefixes) to logcat — tighten to internal-only,
  redact, and filter in release builds.
- **GREEN:** cold start, no pushed token → web login → LOGGED_IN, on a real device.
- **Depends on:** A0‑device. Independent of A1/A2 — can run in parallel.
- **Risk:** medium — Cloudflare/UA sensitivity + WebView redirect interception; both solved on iOS as a
  reference (`ios/dev-tools/fetch-shield-tokens.sh`, `JagexLoginViewController.m`).

### A3.5 — Bridge request/response thread *(architecture prerequisite — build before A4)*
- **Goal:** replace the fire-and-forget, render-thread-sole-caller model with a synchronous request/response
  path. A4/A5's reads (`MobileUiBridge.listPlugins/getPluginConfig`) are **synchronous JSON** and cannot be
  fire-and-forget.
- **Tasks:** a dedicated HotSpot bridge thread **or** a render-thread request/response queue. The ART UI
  thread must **never** attach to the embedded VM.
- **GREEN:** a synchronous `listPlugins()` round-trips JSON to the ART side under load with no ANR and no
  illegal attach.
- **Risk:** wrong threading → ANR or illegal ART-UI-thread attach. **Design debt that must land before A4.**

### A4 — Plugin control plane *(this is "all plugins" arriving)*
- **Goal:** enable/disable plugins + typed per-plugin config + Plugin Hub, from native UI.
- **Tasks:** gates `runelite.mobileui` + `runelite.hub`; `MobileUiBridge.{listPlugins,setPluginEnabled,
  getPluginConfig,setConfig,resetConfig,getProcessedWriteId,hubManifest,hubInstall,hubUninstall}`; call
  `flushConfig()` on `onPause`/screen-close (Android has no RuneLite shutdown flush — toggles/edits silently
  don't persist otherwise). **Pin `-Drunelite.pluginhub.version` to a *published* manifest** (iOS pins
  `1.12.27`; the repo `1.12.29-SNAPSHOT` has no hub manifest → 404). Compose for the list/config/hub screens.
- **GREEN:** toggle a plugin (starts/stops in logcat), write+reset a config value (round-trips, survives
  relaunch via flush), hub browse returns a manifest (not 404), install→appear→uninstall.
- **Depends on:** A3.5.
- **Note:** `MobileUiSmokeRunner` ships in-jar (arm via `-Drunelite.mobileui.smoke`) to validate the seam
  before building UI.

### A5 — Plugin side-panels (rendered fallback + curated native)
- **Goal:** the two-tier panel system.
- **Tasks:** Tier‑1 generic rendered-image fallback (`MobileUiBridge.{listPanels,openPanel,getPanel*,
  panelClick}` → zoom/pan `Bitmap`); Tier‑2 curated native **Quest Helper** (`QuestHelperBridge.*`) +
  **World Switcher** (`WorldSwitcherBridge.*`), falling back to Tier‑1 when `isAvailable()` is false. Generic
  Swing→UIKit/Compose auto-extraction is **rejected** (settled on iOS — do not attempt).
- **GREEN:** Quest Helper + World Switcher render natively with live data; an arbitrary third plugin's panel
  shows via the image fallback.
- **Depends on:** A4, A3.5.

### A6 — Native Jagex-Mobile chrome (tab rails + chat-at-top)
- **Goal:** the OSRS-Mobile look.
- **Tasks:** gates `runelite.mobilechrome` + `runelite.hudveneer`; cover-and-proxy native tab stones over the
  live stones (`MobileChromeBridge.getSnapshot` → draw + `JavaBridge.onClickPrecise`); `HudVeneerBridge.
  relocateChat` for chat-at-top. **Classic Views/`Canvas`** (latency/z-order sensitive). **Do NOT use**
  `mobilechrome.repos` engine-side repositioning — tried and REVERTED on iOS.
- **GREEN:** rails track the engine stones and switch tabs; chat renders at the top, still live — on a real
  device.
- **Depends on:** A1 (`onClickPrecise`).

### A7 — Native HUD veneers + edge-to-edge *(highest-risk feature phase — recommend DEFER from v1)*
- **Goal:** native minimap/orbs veneer over an edge-to-edge scene.
- **Tasks:** gate `runelite.huddata` + `-Drunelite.headless.resize=WxH`; respect Android
  `WindowInsets`/`displayCutout` **from the start** (iOS edge-to-edge was built, device-confirmed, then
  **reverted** over notch occlusion); make `toGameX/Y` inset/letterbox-aware (today a pure view→frame
  stretch); minimap/orbs are **cover-in-place + proxy-click** (no single lever both hides the HUD and keeps
  it clickable — inherited iOS open question; only chat is fully native).
- **Decision:** **plan + reviewer lean to deferring A7 from v1.** Unfinished even on iOS. Treat as research.
- **Seam note:** A7 rewrites the same seam as AG2 — settle render-seam ownership before A6 ships.

### A8 — Stability, soak, packaging
- **Goal:** a stable, installable, self-contained app.
- **Tasks:** multi-hour soak (tune memory under `-Xmx512m` for real OSRS + plugins); background/foreground
  surface churn (already partly handled by process-wide `booted` + never-destroyed VM + `g_surfaceGen`);
  `flushConfig` on background; signed APK + first-run UX. Decide multi-ABI (today **arm64-v8a only**, matched
  to the bionic arm64 JRE; another ABI needs a matching JRE build — likely stay arm64-only).
- **GREEN:** N-hour in-game soak on a real device, stable memory + FPS; clean install→login→play.

> **v1 = A0‑device + A2‑device + A3 + A4 + A5 + A6 + A8** (A7 deferred). CPU rendering. 117HD
> "appears-but-incompatible" (as on iOS) until Track G lands.

---

## D. Track G — GPU / 117HD (parallel; the headline goal)

### D.0 — Why Android is the *favoured* GPU target (leverage it everywhere)
The iOS port is structurally capped: native EAGL = **ES 3.0 only, no compute**; even MetalANGLE-over-Metal
caps at ES 3.0. That forced an apparatus iOS needed and **Android can skip:**

| iOS workaround | Why iOS needed it | Android |
|---|---|---|
| Reverse-Z *without* `glClipControl` (projection hack + `GL_GEQUAL` + `clearDepthf(0)`) | EAGL has no `glClipControl` | **Use `EXT_clip_control` natively** if the device advertises it (confirm in AG0); the GEQUAL hack is the fallback only |
| MetalANGLE vendored from the Amethyst IPA | iOS has no usable GL driver | **Real native GLES driver — no ANGLE** |
| LWJGL `org.lwjgl.opengl` facade + JNI-GL "Route C" bridge | iOS has no LWJGL-iOS and no desktop GL | Still need a thin facade — **LWJGL has no `natives-android` classifier** (verified: `runelite-client/build.gradle.kts` ships `natives-{linux,linux-arm64,macos,macos-arm64,windows*}` only) — but it binds to **`android.opengl.GLES31/GLES32`** directly, not a marshalling translation layer |
| GLSL 330→ES 3.00 downport, TBO→`usampler2D` repack, draw-indirect stripping, image-store removal | ES 3.0 lacks TBO / draw-indirect / compute | **GLES 3.1/3.2 restores TBO, draw-indirect, compute, SSBO, image-store** → the heaviest downport items **disappear** |

**Verified in-tree (master), the load-bearing GL facts the GPU track rides on:**
- The base `gpu` plugin **hard-gates only on `OpenGL33`** — `if (!glCapabilities.OpenGL33) return false`
  (`runelite-client/.../plugins/gpu/GpuPlugin.java:317`).
- `glClipControl` is **optional** — only called when `glCapabilities.OpenGL45` (`GpuPlugin.java:356,358`).
  Reverse-Z is therefore achievable *without* it (the iOS proof, and the GEQUAL fallback).
- The plugin builds its context via `AWTContext.loadNatives()` + `new AWTContext(client.getCanvas())`
  (`GpuPlugin.java:289,300`) — the `librlawt` dependency; this is the one piece that needs an Android native
  drop-in (see AG1).

**Reusable iOS prior art (knowledge, not code — on the `gpu` branch; re-verify, don't assume):**
- `git show gpu:ios/HD-PORT-BLUEPRINT.md` — the canonical 117HD plan: the line-by-line RLHD audit, a 14-item
  ES‑3.0 blocker checklist (Android skips the items 3.1/3.2 restore), the base-gpu GL census (~85 calls, only
  ~5 non-ES-core), the GLSL 330→ES downport recipe, and the data/asset **licensing** analysis (backend-
  agnostic — applies to Android unchanged), and the staged perf/thermal microbenchmark methodology.
- **The RLHD renderer-split claim** (the iOS audit found the *default* `ZoneRenderer` path needs no
  compute/SSBO, with compute living only in an unused `LegacyRenderer`) is **version-specific to the RLHD
  commit iOS audited.** RLHD is an external hub plugin (**not vendored in this repo**) and moves — **re-pin
  and re-audit the renderer split against the RLHD revision you actually vendor on Android.** Because Android
  *has* compute, the perf-optimal choice (Zone CPU-sort vs Legacy compute-sort) is genuinely open here and
  may differ from the iOS choice (which was forced by no-compute).

### AG0 — GLES tier recon on a REAL device *(HARD GATE — run NOW; cheap; emulator is invalid)*
- **Goal:** settle the actual GLES tier + extensions on target hardware.
- **Tasks:** on a cloud/owned device query `GL_VERSION`, ES 3.1/3.2 presence,
  `GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS` (must **not** error), `EXT_clip_control`,
  `EXT_texture_format_BGRA8888`, `GL_MAX_SAMPLES`, RGBA16UI 2D-array color-renderability,
  `DEPTH_COMPONENT24/32F`, offscreen FBO + `glReadPixels`.
- **GREEN:** a committed device verdict log confirming ES ≥3.1 + compute + clip_control on the chosen device.
- **HARD dependency:** if `GL_MAX_COMPUTE_*` errors / ES < 3.1 on the target device, base `gpu` can still run
  (it only needs `OpenGL33`-equivalent) but the *clean* 117HD compute path is off — escalate device selection
  as a blocker before funding AG3's compute route.
- **Risk:** low (~90%). Any modern arm64 device is GLES 3.1+ with compute.

### AG1 — Native EGL context → `OpenGL33==true`, headless *(THE project-wide go/no-go; ~50%)*
- **Goal:** get `GL.createCapabilities()` to report **`OpenGL33==true`** through the facade, **headlessly**
  (no GLFW window), on a real device — so `GpuPlugin` does not abort at `GpuPlugin.java:317`.
- **Tasks:** build the per-ABI **`librlawt` replacement** — same JNI contract as `net.runelite.rlawt.
  AWTContext`, but it **ignores the peerless CACIO canvas** and creates an **EGL context against an offscreen
  FBO/pbuffer** (then the SurfaceView Surface). Provide local `org.lwjgl.opengl.{GL33C,GL,GLCapabilities}`
  facade classes that present a desktop-GL `OpenGL33` face to the unmodified plugin. **`GpuPlugin.java` and
  `AWTContext.java` stay byte-for-byte unmodified** — the "no plugin Java change" rule holds; the work is a
  native + facade drop-in.
- **GREEN:** md5-gated device log — `GL.createCapabilities()` → `OpenGL33==true` on a live EGL/GLES context,
  headless, `GpuPlugin.startUp()` past line 317.
- **Depends on:** AG0 GREEN. Prior art: the iOS Route‑C facade pattern (`git show gpu:` the `gpu-routec`
  tree). iOS proved the *approach* (its raw-GLES "Route A" failed; the forced-`OpenGL33` facade passed) — but
  it must be **re-proven on Android EGL**, and **no `natives-android` LWJGL exists in-repo today**, so the
  binding stack is **built and proven, not assumed.**
- **Risk:** **HIGHEST in the project.** A real GLES context reports *GLES*, not desktop GL 3.3 — the facade
  must convincingly present a desktop-GL face that LWJGL binds headlessly. **Green → fund AG2. Red → STOP;
  the CPU shell (Track A) is the product, which is fine — v1 never depended on Track G.**

### AG2 — Base `gpu` plugin renders the real world
- **Goal:** the unmodified base `gpu` plugin renders the live OSRS scene on Android GLES.
- **Tasks:** wire the ~85-call GL surface; apply the ~5 non-ES-core shims (`glClearDepth`→`glClearDepthf`,
  `glMapBuffer`→`glMapBufferRange`, etc.) + the GLSL 330→ES downport; **swizzle the `GL_BGRA` UI-texture
  upload to RGBA** (silently corrupts colours on GLES — doesn't error); use `EXT_clip_control` reverse-Z
  natively (vs the iOS GEQUAL hack). **Render path (`PORT-PHASING.md` Q9 — staged):** start with
  `glReadPixels`→`int[]`→existing SurfaceView blit (isolates "did GL come up" from restructuring the seam),
  then move to **direct EGL-to-Surface** for production (the readback round-trip throttles FPS).
- **GREEN:** md5-gated device evidence of `gpu.startUp` + a composited frame of the real world; colours
  correct (verify the BGRA fix), depth/flip correct.
- **Depends on:** AG1.
- **Risk:** `glValidateProgram` is treated as fatal (`Shader.java`) — a strict ES driver may fail where
  desktop passed; coplanar z-fighting may differ with reverse-Z precision. **Base `gpu` ships ~60%
  (conditional on AG1) — and on its own resolves the HD↔FPS tension and ~80% of the "looks like a real
  client" win.**

### AG2.5 — Headless data/loader re-proof + asset licensing *(blocks AG3; highest *non-GL* 117HD risk)*
- **Goal:** prove RLHD's loader systems run under **our** headless RuneLite client on Android, and settle the
  asset-licensing question — neither is answered by any GL recon.
- **Tasks:** re-prove RLHD's gameval/override/area/tile/texture managers initialize under the headless client
  on device; resolve the **asset LICENSING gate** before vendoring RLHD scene/textures (Jagex-derived /
  fan-content / project-specific grants — see the licensing analysis in `git show gpu:ios/HD-PORT-BLUEPRINT.md`).
- **GREEN:** loaders init without error on device; licensing decision recorded.
- **Risk:** the iOS blueprint flags this as the **highest 117HD risk**, and it transfers unchanged. If
  licensing fails, the fallback is **HD lighting/shadows on vanilla textures** (no asset vendoring).

### AG3 — 117HD bring-up
- **Goal:** RLHD's default renderer running on Android.
- **Tasks:** **vendor 117HD from the plugin hub** (not in this repo today); **re-decide Zone vs Legacy** given
  Android compute (re-audit — don't inherit the iOS choice); expand the GL facade by the RLHD call-delta
  (static scan of every `org.lwjgl.opengl` call vs the base census); wire std140 UBOs, `sampler2DArray`, MSAA
  color RBO + `DEPTH_COMPONENT32F`, `glBlitFramebuffer` resolve. On Android the heaviest iOS downport items
  (TBO repack, draw-indirect stripping) are **avoidable** — 3.1/3.2 restore those objects/entry points.
- **GREEN:** a recognizable HD scene renders on device; a **staged perf/thermal microbenchmark** (static →
  alpha-heavy → +shadows → +tiled/dynamic lights, sustained 10-min) **before** any playability claim.
- **Depends on:** AG2, AG2.5.
- **Risk:** **perf is unproven for RLHD's multi-pass renderer on *any* platform** (the iOS single-pass base
  numbers don't transfer). **Honest odds (`PORT-PHASING.md` §3G): full 117HD ~20%, partial ~60%.** Promise
  "GPU rendering: sharper + faster" (AG2); frame full 117HD as best-effort.

### AG4 — 117HD feature layers (best-effort frontier)
- **Goal:** the RLHD feature ladder — materials, lighting, shadows, water, dynamic/tiled lights, atmosphere.
- **Android upside:** dynamic/tiled lights is exactly where Android **compute + SSBO** can take RLHD's *fast*
  path the iOS port could not — re-audit which features benefit.
- **GREEN:** per-feature visual parity vs desktop RLHD at acceptable sustained FPS.
- **Risk:** thermal throttling on mobile. Best-effort, not a hard gate.

### AG5 — GPU stability + production present
- **Goal:** GPU path production-ready.
- **Tasks:** direct EGL-to-Surface present (retire the `glReadPixels` round-trip); handle EGL context loss on
  background/foreground; soak.
- **GREEN:** GPU soak with no context-loss crash across background/foreground churn.

---

## E. Reuse vs Android-specific (one glance)

- **Reused identically (shared W1/W2):** the shaded RuneLite client jar + the entire
  `net.runelite.client.headless.*` bridge (29 classes); `DeviceHeadlessBoot.main → RuneLiteHeadless →
  HeadlessClientUI`; CACIO CTC (cacio17, same lineage as desktop); `UnsafePokeActivation.activate()` before
  AWT init; the `JNI_CreateJavaVM` in-process embedding; the CPU `int[]` BufferProvider path; the bridge JSON
  contract (escaped JSON + `seq`/`writeId` poll); `tokens.json` (same file/key mapping as iOS).
- **Reused iOS GPU prior art (knowledge, gpu branch):** the RLHD audit, the ES-blocker checklist, the
  base-gpu shim census, the GLSL 330→ES recipe, the `librlawt`-replacement *pattern*, the data/licensing
  analysis, the perf microbenchmark methodology.
- **Android-specific (cannot share):** the **bionic-arm64 JRE21** (vs iOS Mach-O — same OpenJDK, different
  ABI — see [`dev-tools/JRE21-ANDROID-INVENTORY.md`](dev-tools/JRE21-ANDROID-INVENTORY.md));
  `dev-tools/fake-xawt/` (`.so` with `DT_NEEDED libawt_headless.so`); the Kotlin/ART + C++ host
  (`jvm_bootstrap.cpp`); **filesDir-only JRE staging** (mirroring `.so` into `jniLibs` SIGABRTs in
  `Toolkit.<clinit>`); `-Djava.io.tmpdir=filesDir/tmp`; `allowNativeHeapPointerTagging=false`; the per-ABI
  `librlawt` EGL replacement.
- **Dropped wholesale (the iOS platform tax Android skips):** StikDebug per-launch attach,
  `patch-jre-txm.sh` libjvm NOPs, `MirrorMappedCodeCache`/`brk #0x69`, Mach exception-port surgery,
  `increased-memory-limit` entitlement, `resign-jre.sh`, the WebKit-breaks-JIT restart dance, and the `-XX`
  JIT-workaround opts. **This is why Android is cheaper from W1 onward — JIT is free.**

---

## F. Cross-cutting risks (biggest first)

1. **AG1 (~50%)** — headless `OpenGL33==true` through a facade on native EGL; no Android LWJGL classifier
   exists yet, the stack must be built + proven. **The whole GPU track gates here.**
2. **A0‑device / Q11** — `filesDir` dlopen of `libjvm.so` unproven on hardened hardware; could force a
   JRE-staging restructure. **The whole port's device viability gates here.**
3. **AG2.5 data/loader + LICENSING** — RLHD loaders unproven under our headless client; asset licensing is a
   hard gate. Backend-agnostic; transfers from iOS unanswered.
4. **A7 edge-to-edge HUD** — unfinished even on iOS (reverted over notch occlusion). Recommend deferring v1.
5. **A3.5 bridge threading** — wrong threading → ANR or illegal ART-UI-thread attach. Must land before A4.
6. **Version/asset drift** — staged jar is 1.12.28 while source is 1.12.29; the census md5 is jar-version-
   sensitive. Restage + reconcile before A3.
7. **Perf (AG3+)** — RLHD's multi-pass renderer perf is unproven on any platform; mobile thermal throttling.
8. **bionic JRE provenance** — SHA-pinned in `tools/stage-android-jre.sh` but sourced from a community build;
   now inventoried in [`dev-tools/JRE21-ANDROID-INVENTORY.md`](dev-tools/JRE21-ANDROID-INVENTORY.md).
9. **Shared-bridge convention wall** — `net.runelite.client.headless.*` is shared by *convention only* (no
   compile-time wall); an Android-specific change can silently break iOS. **Change it additively only**, and
   re-run the desktop OpenJDK‑21 headless smoke after any bridge touch.

---

## G. Suggested execution order

> **Decisions locked (2026-06-07):** ① run **AG0/AG1 early, in parallel** with Track A — cheap, and they
> de-risk the project's biggest unknown. ② **Real-device tooling is built + cost-tiered** (harnesses in
> `android/dev-tools/{firebase,browserstack}/`, both reusing the emulator md5 gates on the fetched logcat —
> "real device" is just a different target for the identical GREEN check). A real device = a **real GPU
> driver** (valid for Track G; the local `rl_arm64` SwiftShader GL is **not**) and faithfully tests the
> API-29 `filesDir` dlopen (Q11). **Default = Firebase Test Lab Spark — FREE, 5 runs/day, no expiry**
> (`fb-gate.sh`, gcloud Robo) for **A0-device** (login→LOGGED_IN) and **AG0** (GLES recon). **BrowserStack
> App Automate** (`bs-gate.sh`, Appium; 100-min free trial, then ~$199–249/mo) is the paid fallback for what
> Robo can't drive — the **A-1b census** (needs the `--ez census true` intent extra; already GREEN on the
> emulator) and iterative AG1 work. **Near-term cost ≈ $0**; the only real spend is a one-time **~$150 owned
> arm64 device**, still **deferred to a green AG1** (cloud round-trips are too slow/metered for the
> months-long AG2/AG3 iteration). ③ AG0 should be a **minimal standalone GL-recon APK** — query
> `glGetString` + `GL_MAX_COMPUTE_*` only, no login / no full RuneLite boot — so one free Firebase run settles it.
>
> ④ **Emulator-first for ALL development.** The local `rl_arm64` emulator is ANGLE→Vulkan(SwiftShader)
> advertising **GLES 3.1 + compute** (verified `ro.opengles.version`=0x30001), so it is a real (if slow) GL
> implementation: **build and debug *everything* on it first** — the entire Track A playable shell
> (A‑1b/A3/A3.5/A4/A5/A6, and A7 with an AVD-simulated cutout), **and** the GPU-track *plumbing* (AG1 facade +
> EGL shim → `OpenGL33`, AG2 base-gpu rendering, even AG3 compute bring-up to functional correctness). Reserve
> **BrowserStack** for only what real hardware can answer: the **AG0 tier truth** (the emulator's caps are
> SwiftShader's, not the device's), **real-driver confirmation** of what was built, the single **A0-device /
> Q11** `filesDir`-dlopen confirm, and — the decisive one — **performance** (the emulator gives *zero* FPS
> signal, and FPS is the entire point of the GPU track). So: emulator de-risks *functional feasibility* cheaply;
> a real device decides *viability*. Net effect — the GPU real-device gate becomes a **confirmation + measure**
> pass, not a from-scratch bring-up, which slashes BrowserStack usage.

1. **Restage 1.12.29 + reconcile the hardcoded jar name + re-run the census** (`tools/stage-mobile-runtime.sh`;
   fix `MainActivity.kt:451` to glob `client-*-shaded.jar`; record the new `census_md5`). Unblocks everything.
2. **A‑1b** (cheap confidence) ∥ **AG0 real-device recon** (cheap; settles the GPU advantage).
3. **A0‑device** (Track-A device go/no-go) ∥ **AG1** (Track-G go/no-go) — same cloud-device session.
4. If **A0‑device GREEN** → **A2‑device → A3 ∥ A4-prep, A3.5 → A4 → A5 → A6 → A8** (v1; A7 deferred).
5. If **AG1 GREEN** → **AG2 → AG2.5 → AG3 → AG4 → AG5** in parallel with Track A.
6. Buy a ~$150 owned arm64 device **once AG1 is GREEN** (cloud round-trips are too slow/metered for the
   months-long GPU iteration; deferred until the information justifies the spend).

**Definition of done (the user's goal):** a self-contained, installed Android app — on-device Jagex login →
in-game on live OSRS, full touch + Jagex-Mobile UI, **all RuneLite plugins toggle/config natively**, and
(Track G) **base `gpu` + 117HD rendering on the device GPU** — no host, no desktop, no streaming, no
per-launch JIT ceremony. Track G's full‑117HD tier is **best-effort** (honest odds above); base GPU rendering
is the high-confidence, Android-favoured prize.

---

### Appendix — key files
- **Android host:** `app/src/main/cpp/jvm_bootstrap.cpp`, `app/src/main/java/dev/audaro/helloshell/*.kt`,
  `app/build.gradle.kts`, `dev-tools/fake-xawt/`.
- **Shared seam:** `runelite-client/src/main/java/net/runelite/client/headless/*` (29 classes),
  `runelite-client/src/main/java/net/runelite/client/callback/Hooks.java`.
- **GPU plugin (target):** `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java`
  (`OpenGL33` gate @ :317, optional `glClipControl` @ :356, `AWTContext` @ :289/300).
- **Staging:** `tools/stage-android-jre.sh`, `tools/stage-mobile-runtime.sh`.
- **iOS prior art (gpu branch):** `git show gpu:ios/HD-PORT-BLUEPRINT.md`, `git show gpu:ios/dev-tools/gpu-routec/`.
