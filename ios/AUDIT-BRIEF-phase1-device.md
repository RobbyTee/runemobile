# Audit brief — Phase 1: embed OpenJDK + confirm JIT on the device (adversarial review)

**For:** an independent reviewer. **Goal:** try to **refute** the Phase-1 plan we're about to invest in —
embedding a **JIT-enabled HotSpot OpenJDK 21 on iOS 26.5** (iPhone 17 Pro Max), launching our Java on-device,
and **confirming the JIT actually engages**. This is the platform gate: if HotSpot silently runs the
interpreter (or won't load at all) on 26.5, the whole "real RuneLite on a real JVM" premise is at risk.
Default to skepticism; for any unproven claim name the **cheapest experiment** that settles it, and flag any
cheaper/safer path we're missing. Concrete corrections (versions, entitlements, file:line) welcome.

## Context (what's settled — don't re-litigate)
Target: a self-contained native iOS app running **real RuneLite** on an on-device JIT OpenJDK 21, plugins
unmodified, **live OSRS**, Jagex-Mobile-style UI; no host, sideloaded. Three workstreams (W1 platform / W2
headless RuneLite core / W3 native UI) — see `REALJVM-DEV-PLAN.md`, `REALJVM-FEASIBILITY-SCOPE.md`, `FINDINGS.md`.

**Already proven (this repo, desktop):**
- **Rung 0 perf premise** (`dev-tools/jvmbench/`, `RESULTS.md`): same Java-8 bytecode runs **45–184× faster on
  OpenJDK 11 than the old miniJVM interpreter**, 12–109× faster than miniJVM's best JIT. The faithful software
  renderer has massive headroom on a real JVM — *if* we get a real JIT on device.
- **Gate 1a/1b** (`dev-tools/cacio-smoke/`): RuneLite Java2D overlays render to a buffer headless on JDK 21,
  native + CACIO CTC. *Caveat:* desktop `BufferedImage`/`CFontManager` are toolkit-independent → does **not**
  settle the iOS native font/2D rasterizer (rides to Phase 3).
- **Gate 1c + 1c-ii — GREEN (W2 done on desktop):** real RuneLite boots headless (window-less `HeadlessClientUI`
  via a Guice override), reaches the live-OSRS login screen, and — with a synthesized Play Now click +
  `changeWorld` to an F2P world — logs in to **LOGGED_IN in-game** and composites a **real RuneLite plugin
  overlay** (FPS overlay) into the captured buffer, on native **and** CACIO/no-window. `nullGraphics=0`. See
  `FINDINGS.md` "Gate 1c-ii" + the appendix below. **So the W2 fork that Phase 3 will deploy already exists and works.**
- Install/JIT path decided: **TrollStore N/A on 26.5** (CoreTrust patched since 17.0.1) → **AltStore/SideStore +
  StikDebug** (per-launch debugger attach → `CS_DEBUGGED` → JIT) + `increased-memory-limit` entitlement.
- The "two OpenJDK-on-iOS worlds" trap (official **Zero, no JIT** vs **Pojav JIT HotSpot** — we want Pojav) and
  the simulator trap (needs an `*-ios-simulator`-triple JVM nobody ships → **device-first**) are in `FINDINGS.md`.

**Predecessor assets** (old miniJVM spike, `/Users/jordanmm/runelite`): a working `JLI_Launch` embedding
callsite (`mobile/c/glfmapp/main.c` `jvm_create`/`jvm_init`), and the `dev-tools/jvmbench/Bench.java` microbench.
**Amethyst-iOS** (`AngelAuraMC/Amethyst-iOS`, **GPLv3 — patterns only, do not copy**): `Natives/JavaLauncher.m`
(`JLI_Launch`), `Natives/dyld_bypass_validation.m` (load ad-hoc JRE dylibs), `Natives/external/ballpa1n/HostManager.c`
+ `Natives/external/mach/mach_excServer.c` (JIT enable), `awt_xawt/xawt_fake.m` (fake-xawt), `SurfaceViewController.m`.

## The proposed Phase-1 plan (what to attack)
1. New iOS app target. Bundle **Pojav's prebuilt `jre21-ios-aarch64`** in `Frameworks/`; at launch
   `dlopen(libjli.dylib)` → `dlsym(JLI_Launch)` → launch with a classpath + main class (mirror the miniJVM
   `jvm_create` callsite). Sign with AltStore (`get-task-allow` + `increased-memory-limit`), free Apple ID.
2. Run `Hello` + the **Rung-0 microbench** (`Bench.java`) on-device.
3. **Confirm JIT engages** with StikDebug attached: `-XX:+PrintCompilation` shows compilation, and runtime is
   ≫ `-Xint`. Document the per-launch attach ritual.
- **GATE:** HotSpot JITs on device (StikDebug attached, iOS 26.5). Runs in **parallel** to W2 (already done).

## Claims to refute / confirm

### Claim 1 — StikDebug enables HotSpot JIT on iOS **26.5** (the gate's whole point)
StikDebug attaches a debugger per-launch → kernel `CS_DEBUGGED` suppresses the AMFI kill on RWX/JIT pages, so
HotSpot can codegen. StikDebug "explicitly lists PojavLauncher as supported."
- **Refute:** iOS 26 "broke JIT again" and SideStore docs reportedly call 26 JIT support **limited**. Does the
  current StikDebug (3.1.3?) actually deliver a *stable* `CS_DEBUGGED` JIT on **26.5** specifically — or only on
  17.4–18.x, with 26.x partial/regressed? Is PojavLauncher confirmed JIT-working on 26.5 *today*, or is that
  claim stale? If JIT is flaky on 26.5, what's the fallback (pin a known-good iOS point release we can't
  downgrade to on this device; SideJITServer; accept interpreter)?
- **Cheapest experiment:** smallest possible "JIT-on" probe — an app that mmaps RWX + executes, or runs HotSpot
  with `-XX:+PrintCompilation` vs `-Xint`, with/without StikDebug attached. Name the exact signal that proves
  JIT (not interpreter) on 26.5.

### Claim 2 — a **JDK 21** JIT HotSpot build for iOS-arm64 actually exists and is loadable
We assume Pojav ships `jre21-ios-aarch64` (a JIT HotSpot, not the Zero port). The gamepack needs **11+**
(condy/invokedynamic/StringConcatFactory), so 8 is out; we picked 21.
- **Refute:** Does Pojav (or any maintained source) actually distribute a **JDK 21 iOS-arm64 JIT** artifact, or
  only jdk8/jdk17, or only Android-arm64 (not iOS)? Is the iOS build HotSpot-with-JIT or Zero-interpreter? If no
  21 build exists, is **JDK 17** the realistic target (still ≥11, satisfies the gamepack), or must we build the
  JVM ourselves (cost)? Name the concrete artifact + its provenance.

### Claim 3 — the embedding mechanics work on 26.5 under AltStore signing
`dlopen(libjli)` → `JLI_Launch` with libjvm/libjli in `Frameworks/`; Pojav uses `dyld_bypass_validation` to load
ad-hoc-signed JRE dylibs.
- **Refute:** On iOS 26.5, does dyld load the bundled (re-signed or ad-hoc) JRE dylibs under an AltStore free-cert
  app, or does library validation / the hardened runtime block it without the bypass — and does the bypass still
  work on 26.5? Does the **free Apple ID** path (7-day resign, 3-app limit) introduce signing/entitlement
  constraints that break `JLI_Launch` or the `increased-memory-limit` grant? What fails first at process launch?

### Claim 4 — JIT actually buys the Rung-0 win **on device** (not just desktop)
Rung 0 was proven on desktop (Mac). The on-device A/B (JIT vs `-Xint`, vs the old 3 fps miniJVM) is unmeasured.
- **Refute:** Any reason on-device JIT would *not* reproduce the desktop multiplier (thermal, ARM codegen
  quality, `CS_DEBUGGED` overhead, the per-launch attach adding latency/instability)? Is the microbench
  (`Bench.java`, Java-8 floatbits-heavy) still the right on-device proxy for RuneLite's renderer?

## Also worth a skeptical pass
- **Sequencing:** Phase 1 (JIT gate) is planned **parallel/first**; Phase 3 (AWT-on-iOS: Caciocavallo +
  fake-xawt) is called the *deepest* unknown. Given W2 is now fully proven on desktop, is the right next bet
  Phase 1 (cheap, settles the platform kill-risk) — or should a thin Phase-3 AWT spike run alongside, since
  that's where the real surprise risk lives?
- **What the iOS CACIO must actually do:** the desktop proof used `CaciocavalloSilano:cacio-tta`; the device
  needs the **PojavLauncherTeam/caciocavallo** fork (GPLv3) + fake-xawt, and that fork's **2D/font natives on
  arm64-iOS are unproven** (the Gate-1a caveat). Is Phase 1 the right place to *also* smoke-test that the iOS
  JRE can render a `BufferedImage` glyph at all (cheap, on-device), to retire the rasterizer risk earlier?
- **Memory:** does `increased-memory-limit` (AltStore 2.2+, free dev account) actually lift the cap enough for
  RuneLite + plugins, or only partially? What heap did desktop need as a reference?
- **Entitlement/expiry UX:** 7-day resign + per-launch StikDebug attach — any ordering gotcha (must JIT be
  attached *before* `JLI_Launch`, i.e. before the JVM warms)?

## What a useful audit output looks like
For each claim: **REFUTED / SUPPORTED / UNPROVEN**, the evidence (URLs/versions/file:line), and — if unproven —
the **single cheapest experiment** that settles it. Plus: a recommended **JRE artifact** (Pojav 21 vs 17 vs
self-build) with provenance; a **go/no-go on StikDebug+26.5** with the exact JIT-detection probe; and a
**sequencing recommendation** (pure Phase-1 vs Phase-1 + a thin Phase-3/rasterizer spike). Tell us what breaks
first at launch.

---

## Appendix — Gate 1c-ii evidence to adversarially validate (try to poke holes)
1c-ii is claimed GREEN on **empirical artifacts** (state-transition logs + captured PNGs, native + CACIO). Before
we lean on it, attack the proof itself:
- **Overlay-pipeline claim:** we proved compositing with the **FPS overlay** (`FpsOverlay extends Overlay`,
  layer `ABOVE_WIDGETS`, rendered via `OverlayRenderer.renderOverlays` — the method whose `:249` early-return is
  gated on `LOGGED_IN`). It's the *simplest* overlay (static, screen-positioned). Does this fully prove the
  pipeline, or do **entity-anchored overlays** (NPC/ground-item highlights — the actual product value, using
  world→screen projection) exercise materially more that we haven't shown? Is that a real gap or already covered
  by the scene rendering correctly?
- **Input-injection scope:** we proved *the window-less engine accepts a synthesized AWT `MouseEvent`* (posted to
  the toolkit `EventQueue`, source = gamepack `Canvas`) → one click drove `LOGIN_SCREEN → LOGGED_IN`. The iOS
  path delivers events differently (UIKit touch → AWT via CACIO/fake-xawt). Is "engine accepts synthesized AWT
  events" the right thing to have proven, and is the device event-delivery plumbing correctly scoped to Phase 3/4?
- **Stability:** the run stayed `LOGGED_IN` for thousands of draws but logged a benign `java.io.IOException:
  Unsuccessful ping` (RuneLite's own telemetry/session ping to api.runelite.net, not the OSRS connection). Any
  reason to think the *game* session is actually unhealthy / would drop in a longer soak?
- **iOS faithfulness:** does the desktop CACIO 1c-ii result risk *over*-claiming, given the iOS native font/2D
  rasterizer is still unproven (Phase 3)? Is the FINDINGS framing honest about what's NOT settled?
- **Method/account risk:** the synthesized-click + `changeWorld` login pattern on a throwaway free account — any
  added ban-fingerprint risk worth noting beyond the already-accepted unapproved-client risk?

**1c-ii references (this repo):** `runelite-client/src/main/java/net/runelite/client/headless/` (`HeadlessLogin.java`,
`HeadlessCapture.java`, `RuneLiteHeadless.java`, `HeadlessModule.java`), `ui/HeadlessClientUI.java`,
`callback/Hooks.java` (`:381` draw seam), `ui/overlay/OverlayRenderer.java:249` (the LOGGED_IN gate),
`plugins/fps/FpsOverlay.java`; harness `ios/dev-tools/headless/run-headless-login.sh`(+`-cacio.sh`),
auth `ios/dev-tools/fetch-shield-tokens.sh`.
