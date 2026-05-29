# Review request — running a JVM inside an iOS app (feasibility / architecture)

**For:** an independent software-architecture reviewer. **Context:** a personal, technical iOS side-project
on my own iPhone and my own account — I'm building a native iOS app that **embeds and runs OpenJDK (a Java
virtual machine) inside the app** so it can run an existing open-source Java desktop program (the RuneLite
game client, BSD-2-licensed) on the phone, with a native touch UI on top. No host machine, installed via the
standard developer **sideloading** tools (AltStore / SideStore) on iOS 26.5 (iPhone 17 Pro Max).

**Goal:** try to **refute** my Phase-1 plan below. Default to skepticism; for any unproven assumption, name the
**cheapest experiment** that settles it, and flag any simpler/safer approach I'm missing. Concrete corrections
(library versions, build artifacts, file references) welcome.

## What's already working (don't re-litigate)
- **Performance premise:** the same Java bytecode runs ~45–184× faster on a desktop HotSpot JVM than on the
  toy interpreter I previously used — so a real **optimizing JIT** JVM has ample headroom for the client's
  software renderer. The open question is whether that optimizing JIT is available *on iOS*, not whether it's
  fast enough.
- **The Java side is fully proven on desktop (developed against desktop OpenJDK 21):** the client boots
  "headless" (I replaced its Swing desktop window with a window-less shell via a dependency-injection
  override), logs into the live game, and the plugin/overlay rendering pipeline composites a real plugin
  overlay into an offscreen image buffer I own — verified both on the native AWT toolkit and under the
  **Caciocavallo** virtual-display toolkit (an offscreen AWT backend) with no OS window. So the application
  code that Phase 3 will move onto the phone already runs end-to-end.

## Background facts I'm treating as settled (challenge if wrong)
- **Two flavors of "OpenJDK on iOS":** the official OpenJDK mobile port runs **interpreter-only** on iOS (it
  ships without the optimizing JIT compiler), whereas the **PojavLauncher** ecosystem distributes a build with
  the **optimizing JIT compiler enabled**. iOS only allows a runtime to use executable (JIT) memory when the
  app runs under the **standard developer debugger-attached workflow** — the same mechanism emulators like UTM
  and PojavLauncher already use, provided here by the **StikDebug** tool, attached per app launch. Without that
  attach, the JVM silently falls back to the interpreter.
- **Simulator is not a valid target:** the iOS Simulator would need a JVM compiled for the
  `*-ios-simulator` triple, which nobody distributes → **device-first**.
- **The JVM must be 11+** (the game client's bytecode uses `invokedynamic` / `condy` /
  `StringConcatFactory`); I picked **21**.

## The Phase-1 plan (attack this)
1. New iOS app target. Bundle the **PojavLauncher prebuilt `jre21-ios-aarch64`** JVM (its `.dylib`s + class
   library) inside the app's `Frameworks/`. At launch, `dlopen(libjli.dylib)` → `dlsym("JLI_Launch")` → start
   the JVM with a classpath + main class (I have a working equivalent embedding callsite from a previous
   prototype). Sign + install with AltStore on a free Apple developer ID, with the **extended-memory-limit**
   entitlement so the heap is large enough.
2. Run a `Hello` program plus a small CPU **microbenchmark** on the device.
3. **Verify the optimizing JIT is actually active** (vs interpreter) with the debugger attached: compare
   `-XX:+PrintCompilation` output and runtime against `-Xint` (interpreter-only). Document the per-launch
   attach steps.
- **Gate:** the optimizing JIT is confirmed active on iOS 26.5. (Runs in parallel to the already-done Java work.)

## Claims to refute / confirm

### Claim 1 — the optimizing JIT is actually usable on iOS **26.5** specifically
The StikDebug per-launch debugger attach is what lets the JVM use JIT memory. iOS 26 reportedly changed
behavior here, and one tracker calls 26.x support "limited."
- **Refute:** does the current StikDebug deliver a *stable* optimizing-JIT JVM on **26.5** today, or only on
  iOS 17.4–18.x with 26.x partial/regressed? Is PojavLauncher confirmed JIT-working on 26.5 right now, or is
  that claim stale? If JIT is unreliable on 26.5, what's the fallback (a remote-debugger helper like
  SideJITServer; accept interpreter speed; etc.)?
- **Cheapest experiment:** the smallest "is-JIT-active" probe — run HotSpot with `-XX:+PrintCompilation` vs
  `-Xint`, with and without the debugger attached. Name the exact signal that proves the JIT is compiling.

### Claim 2 — a **JDK 21** JIT build for iOS-arm64 actually exists and loads
I assume PojavLauncher ships `jre21-ios-aarch64` as a JIT-capable HotSpot (not the interpreter-only port).
- **Refute:** does PojavLauncher (or any maintained source) actually distribute a **JDK 21, iOS-arm64,
  JIT-capable** JVM — or only jdk8/jdk17, or only Android-arm64 (not iOS)? Is the iOS build HotSpot-with-JIT or
  interpreter-only? If there's no 21 build, is **JDK 17** the realistic target (still ≥11), or must I compile
  the JVM myself? Name the concrete artifact and where it comes from.

### Claim 3 — the embedding mechanics work on 26.5 under AltStore signing
`dlopen(libjli)` → `JLI_Launch` with the JVM `.dylib`s in `Frameworks/`.
- **Refute:** on iOS 26.5, does the dynamic loader load the bundled (app-signed) JVM libraries inside an
  AltStore free-developer-ID app, or does the loader's signature checking reject them? Does the **free Apple
  ID** path (7-day re-sign, 3-app limit) impose signing/entitlement constraints that break `JLI_Launch` or the
  extended-memory grant? What fails first at process launch?

### Claim 4 — JIT delivers the expected speedup **on device** (not just desktop)
The speedup was measured on a Mac; the on-device A/B (JIT vs `-Xint`) is unmeasured.
- **Refute:** any reason on-device JIT wouldn't reproduce the desktop multiplier (thermal, ARM codegen
  quality, debugger-attach overhead, attach latency/instability)? Is a floating-point-heavy Java-8
  microbenchmark still the right proxy for the client's renderer?

## Also worth a skeptical pass
- **Sequencing:** Phase 1 (the JIT check) is cheap and runs in parallel; **Phase 3** (porting the AWT/Swing
  display backend — Caciocavallo + a stub windowing shim — onto the iOS JVM) is the deepest unknown. Given the
  Java side is already proven on desktop, should a thin Phase-3 spike run alongside Phase 1, since that's where
  the real surprise risk lives?
- **iOS 2D/font rendering:** my desktop proof used a desktop Caciocavallo backend whose `BufferedImage`/font
  rasterizer is platform-independent — so it did **not** exercise the **iOS** JVM's native 2D/font rasterizer.
  The iOS build uses a different Caciocavallo fork whose arm64 2D/font code is unproven. Is Phase 1 a good place
  to *also* smoke-test "can the iOS JVM rasterize a `BufferedImage` glyph at all," to retire that risk earlier?
- **Memory:** does the extended-memory-limit entitlement (free dev account) lift the per-app memory cap enough
  for the client + plugins, or only partially?
- **Launch ordering:** must the debugger be attached *before* `JLI_Launch` (i.e. before the JVM warms up)?

## What a useful review looks like
For each claim: **REFUTED / SUPPORTED / UNPROVEN**, the evidence (versions / URLs / file references), and — if
unproven — the **single cheapest experiment** that settles it. Plus: a recommended JVM artifact (PojavLauncher
21 vs 17 vs self-build) with provenance; a go/no-go on the JIT-on-26.5 question with the exact detection probe;
and a sequencing recommendation (pure Phase-1 vs Phase-1 + a thin Phase-3/rasterizer spike). Tell me what
breaks first at launch.

## Appendix — poke holes in the "already working" Java-side proof
- **Overlay pipeline:** I demonstrated overlay compositing with the client's built-in FPS-counter overlay (a
  static, screen-corner overlay rendered through the normal overlay renderer, which only runs once logged in).
  It's the *simplest* overlay. Does that fully prove the pipeline, or do **world-anchored overlays** (entity
  highlights, using world→screen projection) exercise materially more that I haven't shown?
- **Headless input for testing:** to drive the logged-out client to the logged-in state in an automated
  headless test, I post a synthetic AWT `MouseEvent` to the client's canvas (standard UI test-automation). The
  on-phone path will instead translate touch events into AWT events via the iOS AWT backend. Is "the engine
  accepts a programmatic AWT event" the right thing to have proven, and is the touch-translation correctly
  scoped to a later phase?
- **Stability:** the test stayed logged in for thousands of frames but logged one benign network warning from
  the client's own optional telemetry ping (unrelated to the game connection). Any reason to think the session
  is actually unhealthy in a longer soak?
