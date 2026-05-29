# FINDINGS — distilled lessons from the OSRS-on-iOS spike (carry forward)

**Purpose:** the durable, hard-won conclusions from ~27 phases of the iOS spike, distilled so the new
direction (native RuneLite-on-iOS via a real on-device JVM) doesn't re-tread dead ends. This is the *living*
summary; the full phase-by-phase history is archived (see end). **Last updated:** 2026-05-29.

---

## The current direction (one paragraph)
Build a **self-contained native iOS app** running **real RuneLite** on an **on-device JIT OpenJDK** (PojavLauncher/Amethyst-style, TrollStore), on **live OSRS**, with the **plugin ecosystem unmodified** and a UI **like the official Jagex OSRS Mobile app**. No streaming, no host. See `REALJVM-DEV-PLAN.md` (the build plan) and `REALJVM-FEASIBILITY-SCOPE.md` (the why). This **retires** miniJVM, the GPU-snapshot renderer, and our gamepack-injection harness.

## Dead ends — proven, do not re-tread
- **Port OSRS to C/native.** No source, obfuscated, ~weekly Jagex re-randomization → an unmaintainable moving target. Dead.
- **Port RuneLite to C.** RuneLite is a Java wrapper around the same un-portable gamepack. Pointless.
- **GraalVM native-image / AOT.** Needs all classes at build time; chokes on the gamepack's dynamic load + reflection + weekly updates. Doesn't fit.
- **GL→Metal for speed.** The GPU was already idle (~100k verts, few draw calls); we were JVM-bound, so Metal ≈ 0 speedup. (Only relevant later as GL-ES-deprecation future-proofing.)
- **GPU-snapshot scene reconstruction (the whole Phase-27 saga).** Reconstructing the scene from the engine's arrays and re-rasterizing on the GPU was fast (~18 fps) but **could not be made faithful cheaply**. Root cause, data-grounded: the per-frame `DrawCallbacks` emit only the player's local 8×8 zone, not the full scene; and key floors (e.g. the long-table room) come from the engine's **legacy `drawScenePaint` renderer**, which the GPU path doesn't use — so no scene-array/`renderOpaque` port reproduces them. **Shelved as the fallback**, not deleted, in case the JVM pivot fails.

## The pivotal finding: we were JVM-bound, not fill-bound
Profiling the engine's own software render (`MINIJVM_JIT_SAMPLE` on `GPU=0`) refuted the fill-rate hypothesis:
- The rasterizer (`fq.*`) was only **~13%** of CPU.
- The **#1 cost (~27%) was `Float.floatToIntBits`/`intBitsToFloat`/`floatToRawIntBits` dispatch** — a miniJVM deficiency (each a full native-method call + per-call alloc, not an inlined intrinsic).
- No single hot loop → porting the rasterizer to C buys ~+15% only.
⇒ The real bottleneck is **miniJVM execution speed**. The entire GPU-reconstruction effort existed only to work around a slow JVM. Fix the JVM and the engine's *own* faithful renderer runs fast — the renderer problem is solved by deletion.

## Rung-0 proof (desktop, 2026-05-29) — the pivot's perf premise is real
Same Java-8 bytecode on both VMs, same Mac (`dev-tools/jvmbench/`):
- OpenJDK 11 is **45–184× faster than miniJVM's interpreter** (the ~3 fps regime), and **12–109× faster even than miniJVM's best case** (its sljit JIT, all hot methods compiled).
- The 12× floor is floatbits — a native dispatch miniJVM's JIT can't intrinsify (matches the profiling).
⇒ The faithful software renderer has massive headroom on a real JVM. (Full numbers: `dev-tools/jvmbench/RESULTS.md`.) Also: desktop RuneLite already runs this exact gamepack smoothly — the perf risk is low; the risk is iOS **packaging**, not speed.

## Platform findings (OpenJDK-on-iOS) — the traps
- **Two different "OpenJDK on iOS" worlds.** The *official* OpenJDK Mobile port is **Zero-interpreter-only (NO JIT)** on iOS-arm64 by design (Apple bans runtime codegen). The **PojavLauncher path is JIT-enabled HotSpot**, made legal via sideload + `com.apple.security.get-task-allow` + debugger-attach → kernel `CS_DEBUGGED` (suppresses the signing kill on JIT pages). **We want the Pojav path.** Picking the official port silently caps you at interpreter speed.
- **iOS Simulator runs under macOS rules, not device AMFI** → JIT works there permissively, but a simulator app needs a `*-ios-simulator`-triple build of the JVM (nobody ships one). **Device-first** (Pojav's prebuilt JIT JRE + TrollStore) avoids this and is the chosen path.
- **JIT enablement on device:** TrollStore auto-enables + lifts the memory cap; AltStore/SideStore/SideJITServer/StikDebug attach a debugger per-launch. iOS-version-sensitive, community-maintained.
- **Target OpenJDK 17/21** (gamepack uses condy/invokedynamic/StringConcatFactory = JDK 11+; Pojav's old jdk8u can't load it). Artifact: `jre21-ios-aarch64`.
- **Embedding:** `dlopen(libjli)` → `dlsym(JLI_Launch)`; libjvm/libjli in the app's `Frameworks/`. **AWT-on-iOS** via **Caciocavallo + a fake-xawt shim** (Amethyst bundles both). Amethyst reference files (GPLv3, patterns only): `Natives/JavaLauncher.m`, `awt_xawt/xawt_fake.m`, `external/ballpa1n/HostManager.c` (+ `mach_excServer.c`), `dyld_bypass_validation.m`, `SurfaceViewController.m`.
- **The three miniJVM C patches DELETE on HotSpot** — CONSTANT_Dynamic, invokedynamic-bootstrap/MethodInfo-unwrap, StringConcatFactory are all native in HotSpot. Not a port; a deletion.

## Product / legal reality (decisive for scope)
- The **official OSRS Mobile app** already provides the game + touch + mobile UI. The *only* gap we're filling is **RuneLite plugins**, which require running real RuneLite (⇒ a JVM).
- **Jagex's approved-client list is desktop-only** (official, RuneLite, HDOS, OSBuddy). A self-built iOS client is an **unapproved third-party client on live OSRS → ban risk** (2-week → permanent), **not shippable to others** (Jagex takedown + Apple won't carry + users banned). The build is therefore **personal / technical-achievement; throwaway account only.**
- Shippable mobile-RuneLite legitimately exists only on **private servers** (e.g. Alora). Streaming approved desktop RuneLite is the only ToS-safe way to get plugins on a phone — explicitly rejected in favor of the on-device native app.

## Architecture decision (the new direction)
Run **real RuneLite** (not the gamepack + `GamepackProbe` harness) on the device JVM — plugins come free, and RuneLite **self-updates the gamepack** (kills the weekly hand-coded-obfuscated-name treadmill). Three workstreams: **W1** platform (OpenJDK+JIT+AWT on iOS), **W2** RuneLite headless core (fork, bypass Swing `ClientUI`, keep PluginManager+overlays — dev on desktop OpenJDK first, same JVM family), **W3** native mobile UI (Swift/UIKit, the big product lift). RuneLite is **BSD-2** (fork freely).

## Reusable infrastructure (carries forward)
- **Auth:** Jagex OAuth `tokens.json` + `dev-tools/fetch-shield-tokens.sh` (interactive PKCE) + the `AuthRequesters` Proxy patterns (pure standard Java; portable). Throwaway account; consider VPN.
- **Desktop inner loop:** develop all W2 Java against desktop OpenJDK (= ≈ RuneLite) before deploying to device.
- **Bench:** `dev-tools/jvmbench/` (the Rung-0 harness; re-runnable on device for Rung 1).
- **Two-machine / build conventions, desktop ground-truth tooling:** see the archived infra docs (update for the OpenJDK/device world as W1 lands).

## Where the full history lives
The ~27 superseded phase docs (the GPU/floor-saga, Phase 24–27 plans/audits) are retained as the archive in this repo (`ios-spike/`, being moved to `ios-spike/archive/`), and the **miniJVM nested repo** (`vendor/miniJVM`) is kept as the **documented fallback**. Nothing deleted.
