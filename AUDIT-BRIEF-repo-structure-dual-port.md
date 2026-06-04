# Audit brief — repo structure for hosting iOS + Android in one repo

> Self-contained brief for an independent reviewer. The repo started as an iOS-only on-device-JVM
> RuneLite port and has just grown a **second port (Android)**. Question for the reviewer: **do we need to
> restructure the repo to host both ports cleanly, and if so, how far should we go?** Be adversarial — attack
> both the "leave it alone" position and the "restructure" proposal. We would rather over-think the layout
> once, now, while Android is two milestones old and cheap to move, than re-org after it has sprawled to
> match iOS. Recommend the *cheapest* change that prevents the two ports from rotting into divergence.

## 0. What this project is (context the reviewer needs)

A private fork of `runelite/runelite`. The goal: run **real, unmodified RuneLite + its plugin ecosystem** on
an **on-device JIT OpenJDK 21**, as a native mobile app, on live OSRS. No streaming, no host machine. There
are now **two native hosts** for the same game:

- **iOS** (`ios/`) — Obj-C/UIKit shell, JVM embedded via `JNI_CreateJavaVM`. iOS does **not** grant JIT
  freely: it needs a per-launch StikDebug debugger attach (`brk #0x69` mirror) + a patched libjvm. Mature:
  device-confirmed through on-device login, native HUD veneers, touch targeting (workstreams W1/W2/W3).
- **Android** (`android/`) — Kotlin/ART shell, same `JNI_CreateJavaVM` embed via a C++ JNI bootstrap.
  Android **grants JIT for free** (no debugger, no CoreTrust ceremony), so the bootstrap drops every
  iOS-JIT workaround. Young: Milestone A (JVM Hello + JIT) and B (headless RuneLite renders + touch drives
  the gamepack) are green on the arm64 emulator.

The strategic bet that drives the whole layout question: **the Java game/bridge layer is identical across
both ports and is reused verbatim.** Only the host (native shell + JVM bootstrap + frame blit + input) is
platform-specific.

## 1. Current layout (verified, not assumed)

```
/ (repo root = RuneLite fork; root Gradle build, rootProject "runelite")
├── runelite-client/  runelite-api/  cache/  …   ← upstream RuneLite tree (W2 edits live here)
│   └── src/main/java/net/runelite/client/headless/   ← THE SHARED CORE (see §2)
├── ios/
│   ├── app/            ← Obj-C app, xcodegen project.yml, StikDebug JIT scripts, JVMLauncher.m
│   ├── dev-tools/      ← jre (Mach-O), headless desktop harness, jvmbench, awtsmoke, cacio-smoke,
│   │                     fake-xawt, fetch-shield-tokens, package/unpack-local-state
│   └── *.md            ← ~30 docs: REALJVM-DEV-PLAN, FEASIBILITY-SCOPE, FINDINGS, ~20 AUDIT-* briefs
└── android/
    ├── app/            ← Kotlin/ART, cpp/jvm_bootstrap.cpp (JNI), assets/jvm (staged jars+JRE)
    ├── dev-tools/fake-xawt/   ← a SECOND fake-xawt (bionic variant)
    └── README.md       ← the only Android doc
```

**Build systems — three, unorchestrated:**
- **Root Gradle** builds the shared game jar: `./gradlew :client:shadowJar` →
  `runelite-client/build/libs/client-1.12.28-SNAPSHOT-shaded.jar` (~29 MB).
- **iOS:** xcodegen → xcodebuild. The shared jar is **manually copied** to `ios/app/jvm-resources/`
  (gitignored) and bundled by `project.yml`.
- **Android:** a **standalone** Gradle build (`rootProject "RuneliteAndroid"`, its own wrapper) — **not**
  `includeBuild` from root `settings.gradle.kts`. It expects the shared jar pre-staged into
  `android/app/src/main/assets/jvm/` (gitignored) alongside a split bionic JRE (jniLibs + assets).

## 2. What is genuinely shared vs port-specific (the load-bearing fact)

**Shared core — one copy, correctly already outside both port dirs:**
- `net.runelite.client.headless.*` (26 classes) in the upstream `runelite-client` tree: `JavaBridge` (hot
  path — frame blit + touch), `MobileUiBridge` (control plane), `MenuPeek*`, `Hud*`, `Mobile*`, plus the
  W3 probes (`ChatRelocateProbe`, `EdgeToEdgeProbe`, `MinimapWalkProbe`, …).
- The shaded `client-*-shaded.jar` + `probes.jar`. **Both ports load the identical bytes.** Android README:
  *"The Java side … is identical to iOS and is reused verbatim. Only the host … is Android-specific."*

**Port-specific, genuinely cannot merge:**
- The **JRE binaries**: iOS ships Mach-O; Android needs bionic arm64 (PojavLauncher's jre21). Same OpenJDK
  *version*, different ABIs — not byte-shareable.
- **`fake-xawt`**: both ports stub the headful X11 AWT lib, but the C differs (iOS Mach-O dylib vs Android
  `.so` with `DT_NEEDED libawt_headless.so`). Shared lineage, divergent targets.
- The native shells (Obj-C vs Kotlin+C++), the JVM bootstrap (StikDebug JIT path vs none), packaging.

## 3. The problems I think the current layout has (the claims to attack)

**P1 — No top-level framing of the dual-port architecture.** A reader cannot see the shared-core
relationship without reading prose buried in `android/README.md`. iOS has ~30 root docs; Android has one;
there is **no repo-root doc** that says "two hosts, one shared Java core, here's the seam." New cross-cutting
work has no obvious home.

**P2 — The desktop-first dev harness is mis-homed under `ios/`.** `ios/dev-tools/headless/run-*.sh` compile
the shared bridge against the shaded jar on **desktop OpenJDK** — this is the W2 "develop on desktop first"
loop and is **platform-neutral**, but it lives under `ios/`. Android can't naturally reuse it, so it will
either grow a parallel copy or silently depend on the iOS tree.

**P3 — Shared-jar staging is manual and duplicated per port.** No single task builds `:client:shadowJar`
and stages it (+ probes) into both ports. iOS does `cp … ios/app/jvm-resources/`; Android expects it
pre-placed in `assets/jvm/`. Drift between "the jar each port last bundled" is invisible and untracked
(both staged copies are gitignored).

**P4 — `fake-xawt` is duplicated and already diverging.** Two directories, two `xawt_fake.c`. A fix to the
stub set (e.g. a new `initIDs` the engine starts calling) must be hand-mirrored.

**P5 — No boundary convention for shared vs port-specific bridge classes.** Everything in `headless.*` is in
one flat package consumed by both ports. A change made for one port's host (e.g. an Android Surface pixel
format, or an iOS-only HUD veneer) lands in the shared tree and can silently affect the other. There is no
naming/module signal for "shared" vs "iOS-experiment-only" vs "android-only."

**P6 — Android build is detached from root.** It is a standalone Gradle root, not wired via `includeBuild`,
so it can't express a dependency on `:client:shadowJar` and can't be built from the repo-root build.

## 4. What I think is actually fine (resist the urge to change)

- **The shared core is already in the right place** — in the upstream `runelite-client` tree, built once,
  outside both port dirs. This is the single most important structural decision and it is **correct**. Do
  not move it under a new `shared/` module just for symmetry.
- **Two sibling port dirs at the repo root (`ios/`, `android/`)** is a sound, legible top-level shape.
  Resist collapsing them into a clever monorepo module graph.
- **Three build systems is inherent**, not a smell: xcodegen, Gradle/AGP, and the RuneLite root build are
  each the right tool. Merging them into one meta-build is over-engineering with little payoff.
- **Separate JRE binaries** are unavoidable; do not chase a "shared JRE."

## 5. Proposed change set (my recommendation — pressure-test the scope)

I lean **light-touch**: documentation + a thin shared-tooling seam, **not** a module re-architecture.

1. **Add a repo-root `PORTS.md` (or `ARCHITECTURE.md`)** that states the dual-host model, names the shared
   core (`headless.*` + shaded jar + probes), draws the one seam (`Hooks.draw → JavaBridge.snapshot →
   native blit`), and says where new work goes. Fixes P1. Cheapest highest-value item.
2. **Hoist the platform-neutral dev harness** out of `ios/dev-tools/` into a neutral `tooling/` (or
   `dev-tools/` at root): the desktop-first `headless/run-*.sh`, `jvmbench`, the shaded-jar build/stage
   step. Leave genuinely iOS-only bits (StikDebug scripts, `patch-jre-txm.sh`, Mach-O JRE) under `ios/`.
   Fixes P2/P3. **Highest-risk item** — lots of relative paths (`../../runelite-client/build/libs/…`) to
   re-thread; may not be worth the churn. *Reviewer: is the cheaper move to leave them and just symlink /
   document them as shared?*
3. **One staging script/task** — `stage-shared.sh` (or a Gradle task) that builds `:client:shadowJar` +
   `probes.jar` and copies both into each port's staging dir. Fixes P3 drift.
4. **Unify `fake-xawt`** into one dir with two build targets (or keep two but add a shared `xawt_fake.c` +
   per-platform build script). Fixes P4. Low value if the stubs rarely change.
5. **A bridge-class boundary convention** — e.g. keep truly shared classes as-is, and mark/segregate
   port-experiment-only probes (a package suffix or an `@SharedBridge` vs `@PortExperiment` marker, or
   simply a doc list). Fixes P5. *Reviewer: is a convention enough, or does this want real module
   separation?*
6. **(Optional) `includeBuild("android")` from root** so the android build can depend on `:client:shadowJar`
   instead of expecting a hand-staged jar. Fixes P6. *Reviewer: does mixing AGP into the RuneLite root
   build create more coupling than it removes?* I suspect **leave android standalone** and let the staging
   script (item 3) be the seam — but I want this challenged.

## 6. Explicit questions for the reviewer

1. **Scope check.** Is the §5 light-touch set right, or is the project already at the size where a real
   restructure (a `shared/` Gradle module for the bridge, a top-level umbrella build) pays for itself?
   What's the *cheapest experiment* that would prove which side is right?
2. **The harness hoist (item 2) is the one with real churn risk.** Worth it, or is "leave under `ios/`,
   document as shared" the honest call until Android actually needs those scripts?
3. **Bridge coupling (P5).** Is a naming/doc convention enough to stop one port's host needs from silently
   regressing the other, or does the shared bridge need a compile-time boundary (separate module / API
   surface) before the two hosts diverge further?
4. **Android-to-root wiring (item 6).** `includeBuild` vs standalone-with-staging-script — which keeps the
   two ports more independently buildable as they grow?
5. **What am I missing?** Any structural trap that bites at *port #3*-scale (e.g. a desktop reference host,
   or a shared GPU-renderer effort that both ports will want) that we should pre-empt in the layout now?
6. **Anti-over-engineering pass.** Which of my six proposals would you *delete* as motion without payoff?

## 7. Appendix — facts to verify independently

- Shared bridge dir: `runelite-client/src/main/java/net/runelite/client/headless/` (26 `.java`).
- Shared jar build: `runelite-client/build.gradle.kts:105` (`shadowJar` task) → `…/build/libs/
  client-1.12.28-SNAPSHOT-shaded.jar`.
- iOS staging: `ios/SETUP-NEW-MAC.md:63` (`cp … ios/app/jvm-resources/`); bundled via `ios/app/project.yml`.
- Android staging + JRE split: `android/README.md` ("Why the JRE is split"), `android/app/build.gradle.kts`,
  `android/app/src/main/java/dev/audaro/helloshell/{AssetInstaller,MainActivity}.kt`.
- Android build is standalone: `android/settings.gradle.kts` (`rootProject "RuneliteAndroid"`, no
  `includeBuild`); root `settings.gradle.kts` does not reference `android`.
- Duplicated stub: `ios/dev-tools/fake-xawt/xawt_fake.c` vs `android/dev-tools/fake-xawt/xawt_fake.c`
  (differ).
- Local-only / gitignored seams: both ports' staged jars + JREs are gitignored; the local project notes and
  `ios/SETUP-NEW-MAC.md` are in `.git/info/exclude`.
