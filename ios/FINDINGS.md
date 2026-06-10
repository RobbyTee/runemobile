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
- **Install a dev build straight to a remote iPhone over Tailscale/VPN (no machine on the phone's LAN).** iOS 26.5.1 interface-gates lockdownd: it answers a lockdown `QueryType` on the Wi‑Fi/LAN interface but **resets the identical request arriving over a VPN interface** (raw probe: LAN IP → 297B plist reply; tailnet IP → `Connection reset`). The iOS 17.4+ dev tunnel bootstraps through that same gated lockdownd, so `devicectl`/`pymobiledevice3` cannot reach a roaming phone — the installer must originate on the phone's own LAN or loopback. On-device sideload (SideStore/AltStore, loopback to localhost lockdownd) is the only no-second-machine path. Detail: `dev-tools/DEPLOY-REMOTE.md`.

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

## Desktop CACIO overlay gate (Gate 1a/1b) — GREEN, with a load-bearing caveat (2026-05-29)
The platform-feasibility audit's cheapest test, run in `dev-tools/cacio-smoke/`: render a representative
slice of RuneLite's Java2D overlay surface (RuneScape TTFs via `registerFont`; `TextComponent` shadow/
outline/`<col>` tags via `FontMetrics`; `OverlayUtil` `Polygon` draw+alpha-fill + `BasicStroke`; `fillOval`;
`AffineTransform`; `drawImage` blit; `setClip`; `AlphaComposite`) to a PNG with **no window**.
- **Result:** renders correctly on plain headless OpenJDK 21 **and** under the live CACIO CTC toolkit
  (`CTCToolkit`/`CTCGraphicsEnvironment`). cacio-tta **1.18 runs on JDK 21** (built for 17). Output is
  **pixel-identical** between the two (`maxChannelDelta=0`).
- **The caveat (correction to the audit):** on desktop, `BufferedImage` `Graphics2D` is `SunGraphics2D`
  over `BufferedImageGraphicsConfig` and the glyph rasterizer is `sun.font.CFontManager` — **both chosen by
  platform, independent of the AWT toolkit.** CACIO's CTC swaps the *windowing/peer* layer, not the
  BufferedImage rasterizer or font scaler (hence the byte-identical output). So the desktop test **settles
  Claim 2** (RuneLite overlays render to a buffer headless; overlay code is toolkit-portable) and proves
  cacio runs on JDK 21 + survives a swapped GraphicsEnvironment — but it **does NOT settle the iOS half of
  Claim 1** (does the *iOS JRE's* native font/2D rasterizer render these ops). Those natives only run on
  arm64-iOS → that risk rides to Phase 3 (device). Bounded: overlay code is plain Java2D, and the gamepack's
  software rasterizer writes int[] directly (no font/2D).
- **Activation gotcha:** bare `-Dawt.toolkit=...CTCToolkit` **silently falls back** to the native toolkit.
  CACIO installs before AWT init only via `@ExtendWith(CacioExtension.class)` (byte-buddy self-attach →
  needs `-Djdk.attach.allowAttachSelf=true` on JDK 21, plus the `--add-exports/--add-opens` set).
- **W1 P3 §6.0 activation — DESKTOP PRE-CHECK PARTIAL (2026-05-29, `dev-tools/awtsmoke/`).** Verbatim from
  `run-activation-desktop.sh all` (OpenJDK 21.0.5 aarch64, exit 0): (a) classpath+props+JPMS → **FAILED**;
  (b) `-Xbootclasspath/a`+props → **FAILED** (so `-Dawt.toolkit`/`-Djava.awt.graphicsenv` are NOT honored on
  JDK 21); (c) **byte-buddy agent** (`CacioExtension.injectCTCGraphicsEnvironment()` + `allowAttachSelf`) →
  **CTC ACTIVE, toolkit AND GE both CTC** — *even though its JVMTI redefine THROWS* (`class redefinition
  failed: change the schema`), so the load-bearing part is the `Instrumentation` install + CacioExtension's
  static-field/bootstrap injection, NOT the redefine; (d) my naive pure-reflection field-poke → **FAILED**
  (there is NO `GraphicsEnvironment.localEnv` field — the GE singleton is in the lazy holder
  `java.awt.GraphicsEnvironment$LocalGE`; poking it threw `ExceptionInInitializerError`). **DEVICE §6.0 is
  OPEN, not solved.** The only working desktop recipe (the agent) needs the **attach API / attach-self**,
  which a stripped mobile JRE may omit — UNTESTED on the iOS `jre21`. Candidate device paths (none proven):
  (1) attach-self works on iOS → agent ports; (2) a CORRECT no-instrumentation field-poke that seeds the real
  `LocalGE` holder; (3) Amethyst's `CTCPreloadClassLoader`. Probe the JRE's attach capability before building.
  **(Process note — FOUR TIMES this session I wrote a GREEN/ACTIVE verdict BEFORE reading the output; all
  wrong: all-FAILED → "agent GE-only/FAILED" → "field-poke is the recipe/ACTIVE" → reality: agent ACTIVE,
  field-poke FAILED. Hard rule, now enforced in the awtsmoke README: no verdict without the matching captured
  output line.)** Probes: `AwtActivation.java` (assertion), `AwtActivationAgent.java` (working agent path),
  `FieldPokeActivation.java` (the failed field-poke, kept as the negative record). See
  `AUDIT-BRIEF-w1-phase3-awt.md` §6/§6b.
- **iOS CACIO is the SAME artifact lineage (correction, 2026-05-29 P3 review):** earlier notes here said the
  iOS/arm64 backend was the **PojavLauncherTeam/caciocavallo** fork — **WRONG.** That fork is the old
  `net.java.openjdk.cacio` 1.10 / Java-8 tree (only `cacio-shared`+`cacio-androidnw`), unfit for a condy/JDK21
  gamepack. **Amethyst's Java-21 path uses the same `com.github.caciocavallosilano` `cacio-shared`/`cacio-tta`
  (cacio17, source/target 17) we already run on desktop** (`dev-tools/cacio-smoke/lib`). The desktop↔device
  delta is **activation**, not artifact: desktop installs CTC via the JUnit `CacioExtension` (byte-buddy
  self-attach); **device uses `CTCPreloadClassLoader` + `-Xbootclasspath/a` + fake `libawt_xawt`** ahead of
  AWT init. CTC's screen buffer is a `BufferedImage(TYPE_INT_ARGB)` → routes through the same Java2D/font
  stack `GlyphSmoke` proved (so the 2D/font *rasterizer* is settled; the open risk is the activation path).
  See `AUDIT-BRIEF-w1-phase3-awt.md` (Claims A/B, reviewed).

## Stock RuneLite on desktop OpenJDK 21 — UP (Phase 0 step 3 done, 2026-05-29)
Our fork's `runelite-client` builds and runs on OpenJDK 21 (`/opt/homebrew/opt/openjdk@21`), against
**live OSRS**. Reference harness: `dev-tools/run-desktop-reference.sh`.
- **Build:** `./gradlew :client:assemble` (Gradle 8.8) → `runelite-client/build/libs/client-1.12.28-SNAPSHOT-shaded.jar`
  (29 MB). `runtimeOnly net.runelite:injected-client:1.12.28-SNAPSHOT` **resolves from `repo.runelite.net`**
  (the SNAPSHOT injected gamepack hooks are published — no need to pin to a release).
- **Run:** boots → loads the live gamepack (`ClientLoader - injected-client 26527618741.147`) → full plugin
  set starts → `GpuPlugin - Using device: Apple M1` → `Showing frame ... title=RuneLite` → `LOGIN_SCREEN` +
  `Cache is ready` → `Client initialization took ~4.8s`.
- **Canonical JDK-21 launch flags (match the official launcher's macOS args):**
  `--add-exports java.desktop/com.apple.eawt=ALL-UNNAMED` (else `ClientUI.init` dies with `IllegalAccessError`
  on `OSXFullScreenAdapter` → `com.apple.eawt.FullScreenAdapter`) and
  `--add-opens java.base/java.lang.reflect=ALL-UNNAMED` (for `ReflectUtil` annotation-cache invalidation).
- **Benign noise (ignore):** `ExternalPluginManager 404` (no external-plugin manifest for a -SNAPSHOT version);
  `ReflectUtil ... NoSuchFieldException: root` (RuneLite's own best-effort cache-bust, self-handled at DEBUG);
  `[LWJGL] Unsupported JNI version` (lwjgl on newer JDK — irrelevant to us; headless uses the software renderer).
- **Note:** the `com.apple.eawt` flag is moot for our **headless** path — Gate 1c replaces `ClientUI`, so
  `OSXFullScreenAdapter` never runs. It only matters for this desktop *GUI* reference.

## Gate 1c — headless RuneLite + login-screen capture — GREEN; kill-risk refuted (2026-05-29)
Real RuneLite boots with the Swing `ClientUI` replaced by a window-less one and renders the live-OSRS login
screen into a buffer we own, on desktop OpenJDK 21 — on the native toolkit **and** under the CACIO CTC
virtual display (no OS window). Harness: `dev-tools/headless/` (`run-headless.sh`, `run-headless-cacio.sh`).
- **The architecture (post-audit cut):** `HeadlessClientUI extends ClientUI` (constructor relaxed
  `private→protected`) swaps the whole desktop chrome for **one minimal AWT `Frame` that hosts the gamepack
  `Component` and is realized**, so the engine's `Canvas` gets a peer + a real `Graphics`. Bound via
  `Modules.override(RuneLiteModule).with(HeadlessModule)` (rebinds `ClientUI→HeadlessClientUI`); entrypoint
  `RuneLiteHeadless` calls `RuneLite.main` verbatim (honest client, not a rewrite). Upstream diff is tiny:
  `ClientUI` ctor visibility + two `frame==null` guards on its private `@Subscribe` handlers, two gated
  capture calls in `Hooks.draw`, an optional override-module seam in `RuneLite.main`. Rest is isolated.
- **The settled kill risk (audit Claim D):** *does the engine drive `Hooks.draw` with a non-null `Graphics`
  with no real window?* **Yes.** Both toolkits: ~42 fps of draws, **`nullGraphics=0`**, 20 PNGs each — every
  one the real login screen (`765×503`, captured via toolkit-independent `BufferProvider.getPixels()`). No
  `HeadlessException`, no NPEs. CACIO run confirmed `toolkit=CTCToolkit`/`CTCGraphicsEnvironment`, canvas
  realized to an offscreen peer — the honest iOS-shaped proof (engine renders with no native display).
  - *Why a Frame at all:* a truly frameless `ClientUI` leaves the `Canvas` unrealized → `canvas.getGraphics()`
    is null → `Hooks.draw` early-returns at its `graphics==null` guard → nothing renders. CACIO lets the frame
    be realized **offscreen** (no window), which is exactly what the device path needs. This front-loaded the
    audit's "minimal CACIO-backed AWT peer host" fallback because the static read predicted it.
- **What this does NOT prove yet — overlay compositing needs LOGGED_IN.** `Hooks.draw` →
  `OverlayRenderer.renderOverlayLayer` → `renderOverlays` returns early unless
  `client.getGameState()==LOGGED_IN` (`OverlayRenderer.java:249`), for **every** layer incl. `ALWAYS_ON_TOP`.
  So no overlay composites at the login screen. The honest overlay proof is **1c-ii (live login + in-game
  capture)** — needs the throwaway Jagex account / `tokens.json`. A mocked-client 1c-i would have to bypass
  this gate, which just re-derives Gate 1a (overlay draw-ops→buffer, already green) — confirming the audit's
  Claim-C skepticism that 1c-i risks being vacuous. So 1c-i is **skipped**; next is 1c-ii.

## Gate 1c-ii — headless live login + in-game overlay capture — GREEN (2026-05-29)
Real RuneLite, headless, logs into **live OSRS** on the throwaway account and composites a **real RuneLite
plugin overlay** (the FPS overlay) into the buffer we own — on the **native toolkit AND under CACIO CTC
with no OS window**. This closes Claim 2's long pole. Harness: `dev-tools/headless/run-headless-login.sh`
(native) / `run-headless-login-cacio.sh` (no window); auth via `dev-tools/fetch-shield-tokens.sh`.

- **The JX_* handoff auto-AUTHENTICATES but does NOT auto-LOGIN.** `tokens.json` → JX_* env vars
  (`session_id → JX_SESSION_ID`, `chosen_account_id → JX_CHARACTER_ID`, `displayName → JX_DISPLAY_NAME`;
  `access_token`/`refresh_token` empty is fine — those only feed long-session refresh, the gamepack mints
  its own play token from session+character, so Stage A of `fetch-shield-tokens.sh` suffices). The gamepack
  reads the handoff (`launcherDisplayName` set, `loginIndex=10`) and renders the **authenticated welcome
  screen** ("Welcome to RuneScape / Play Now / <name> / World N"), then **sits at `LOGIN_SCREEN`** — it
  never advances on its own.
- **Crossing `LOGIN_SCREEN → LOGGED_IN` needs synthesized input (the Phase-4 fork, settled minimally).**
  `net.runelite.client.headless.HeadlessLogin` (gated by `-Drunelite.headless.autologin`) posts a
  focus-gain + enter/move/press/release/click `MouseEvent` sequence at the Play Now button into the gamepack
  `Canvas` via the toolkit `EventQueue`. **The window-less engine accepts it:** one synthesized click drove
  `LOGIN_SCREEN → LOGGING_IN → LOADING → LOGGED_IN`. This is the same UIKit-touch → AWT-`MouseEvent` path the
  device needs, so the Phase-4 input kill-risk ("does a headless engine accept synthesized events at all")
  is answered YES on desktop CACIO. `client.setGameState(LOGGED_IN)` is NOT a shortcut (doesn't drive the
  network login); stock RuneLite has no auto-login API.
- **F2P world gotcha:** a throwaway **free** account is bounced ("You need a members' account to use this
  world") if the remembered world is members. `HeadlessLogin` calls `client.changeWorld()` to an F2P world
  (default 301 / `oldschool1.runescape.com`; world W's host is `oldschool{W-300}.runescape.com`) **before**
  the click. With that, login completes.
- **In-game render + overlay compositing both confirmed.** Once `LOGGED_IN` the engine keeps driving
  `Hooks.draw` with non-null Graphics (`nullGraphics=0`, stable for thousands of draws), and
  `OverlayRenderer` — which gated everything on `LOGGED_IN` (`OverlayRenderer.java:249`) — now composites:
  the RuneLite **FPS overlay** (OSRS has no native FPS counter) appears top-right in the captured PNG on
  **both** the native toolkit and CACIO (no window). The captured account is fresh, so the scene is Tutorial
  Island — irrelevant to the proof.
- **1c-i still skipped (vacuous), as predicted.** The honest overlay proof needed a logged-in client, which
  we now have.
- **Honest gap (Phase-1 review): FPS proves the *core* compositor, not *world-anchored* overlays.** The FPS
  overlay is static/screen-positioned (`layer ABOVE_WIDGETS`, drawn via `OverlayRenderer.renderOverlays` whose
  `:249` early-return is gated on `LOGGED_IN`). It proves the compositor + the LOGGED_IN overlay path, but it
  does **not** exercise **world→screen projection** (NPC/ground-item highlight, tile indicators) — the actual
  product value. Whether that's a real gap or already covered by the scene rendering correctly is **untested**.
  **Cheapest add-on (deferred, not blocking Phase 1):** enable one world-anchored overlay (tile indicators or
  NPC/ground-item highlight) and capture one in-game frame. Tracked as a W2 follow-up.

## Platform findings (OpenJDK-on-iOS) — the traps
- **Two different "OpenJDK on iOS" worlds.** The *official* OpenJDK Mobile port is **Zero-interpreter-only (NO JIT)** on iOS-arm64 by design (Apple bans runtime codegen). The **PojavLauncher path is JIT-enabled HotSpot**, made legal via sideload + `com.apple.security.get-task-allow` + debugger-attach → kernel `CS_DEBUGGED` (suppresses the signing kill on JIT pages). **We want the Pojav path.** Picking the official port silently caps you at interpreter speed.
- **iOS Simulator runs under macOS rules, not device AMFI** → JIT works there permissively, but a simulator app needs a `*-ios-simulator`-triple build of the JVM (nobody ships one). **Device-first** (Pojav's prebuilt JIT JRE + TrollStore) avoids this and is the chosen path.
- **JIT enablement on device:** TrollStore auto-enables + lifts the memory cap; AltStore/SideStore/SideJITServer/StikDebug attach a debugger per-launch. iOS-version-sensitive, community-maintained. **iOS-26/TXM caveat (Phase-1 review, 2026-05-29):** StikDebug 3.1.3 (released 2026-05-18) lists iOS 26.0+ as supported but with **"Limited App Availability"** + "app updates required"; SideStore's JIT doc (as of 2026-05-03) is more skeptical — iOS 26 "broke JIT again", the supported-app set is **specific apps (Amethyst is listed), not arbitrary custom apps**, and SideStore's *own* enabler only works on iOS 26 for **older non-TXM devices**. The iPhone 17 Pro Max is a **TXM** device, so whether StikDebug enables JIT for *our custom app* (vs a known-good app already on the supported list) is the **open Claim-1 risk** → **oracle-first** sequencing (prove JIT on a supported app — Amethyst — on this exact phone *before* investing in our own app target). Refs: [StikDebug README/3.1.3](https://github.com/StephenDev0/StikDebug), [SideStore JIT doc](https://github.com/SideStore/SideStore-Docs/blob/main/docs/advanced/jit.mdx).
- **Target OpenJDK 21** (gamepack uses condy/invokedynamic/StringConcatFactory = JDK 11+; Pojav's old jdk8u can't load it). **Artifact + provenance corrected (Phase-1 review):** the durable *current* source is **Amethyst's CDN**, not a Pojav GitHub release — Amethyst's `Makefile` downloads `jre21-ios-aarch64.zip` ([Makefile L289-291](https://github.com/AngelAuraMC/Amethyst-iOS/blob/main/Makefile#L289-L291)). The reviewer downloaded + inspected it: contains `jre21-ios-arm64-20250915-release.tar.xz`, `lib/server/libjvm.dylib`, `lib/libjli.dylib`, `bin/java`; `release` says `JAVA_VERSION="21.0.8"`, `OS_ARCH="aarch64"`, `OS_NAME="Darwin"`; `vtool` reports platform **IOS** (min 12.0, SDK 26.0); `strings libjvm.dylib` shows **`OpenJDK 64-Bit Server VM`, `HotSpot 64-Bit Tiered Compilers`, `C1`, `C2`** — i.e. **JIT HotSpot, NOT Zero**. → **Use JRE 21 first, pin its checksum locally** (CDN URL is not a durable release). **JRE 17 is the fallback, not JDK 8.** **Fetched + verified locally 2026-05-29** (`dev-tools/JRE21-INVENTORY.md`): SHA-256 `2c405fcc8c1cf95773617a93a2b090192191c871874cf1408f7a52500d8f0c71`; `vtool` `platform IOS minos 12.0 sdk 26.0`; `libjli.dylib` exports `_JLI_Launch`; `libjvm.dylib` is **ad-hoc/linker-signed** (no CMS signature) → confirms *why* loading it under an app-signed process needs the `dyld_bypass_validation` step.
- **Embedding (correction — the `dlopen(libjli)` story is incomplete on iOS 26):** Amethyst does **iOS-26/TXM JIT setup *before* the library-validation bypass**, *then* loads `libjli` and calls `JLI_Launch` — it is not a bare `dlopen`. Order: JIT-enable (`JIT26*` paths) → `dyld_bypass_validation` (load ad-hoc-signed JRE dylibs) → `dlopen(libjli)` → `JLI_Launch`. **What likely breaks first at launch:** either our custom app is **not in StikDebug's iOS-26 supported set** (no JIT granted), or `dlopen(libjli)` **fails on code-signing / library-validation before Java even starts**. **Safer architecture (adopt for Phase 1):** native shell launches → **waits for debugger attach / JIT-ready** → *then* `dlopen`/`JLI_Launch` (don't warm the JVM before JIT is live). **AWT-on-iOS** via **Caciocavallo + a fake-xawt shim** (Amethyst bundles both). Amethyst reference files (GPLv3, patterns only): `Natives/JavaLauncher.m` (`JIT26*`/`dlopen`/`JLI_Launch`), `Natives/dyld_bypass_validation.m` (iOS-26/TXM paths), `awt_xawt/xawt_fake.m`, `external/ballpa1n/HostManager.c` (+ `mach_excServer.c`), `SurfaceViewController.m`.
- **The three miniJVM C patches DELETE on HotSpot** — CONSTANT_Dynamic, invokedynamic-bootstrap/MethodInfo-unwrap, StringConcatFactory are all native in HotSpot. Not a port; a deletion.

## Phase-1 audit verdict — incorporated (2026-05-29)
`AUDIT-BRIEF-phase1-device.md` was run past an external reviewer. **Verdict: Phase 1 is the right next gate,
but make the plan harsher and smaller — do not start with a full new app target.** Per-claim:
- **Claim 1 (StikDebug enables JIT on 26.5): UNPROVEN.** Evidence is mixed (see the iOS-26/TXM caveat above).
  Resolve **oracle-first**.
- **Claim 2 (JDK 21 iOS-arm64 JIT HotSpot exists): SUPPORTED — now verified locally.** Fetched the **Amethyst
  CDN** `jre21-ios-aarch64.zip`, checksum-pinned, confirmed HotSpot Tiered C1/C2 (not Zero), `platform IOS`,
  `_JLI_Launch` exported (`dev-tools/JRE21-INVENTORY.md`). 17/8 are fallbacks on the same CDN. We do **not** need
  to build the JVM ourselves.
- **Claim 3 (embedding works under AltStore signing): UNPROVEN** — the bare `dlopen(libjli)` story skips the
  iOS-26/TXM JIT-setup-before-library-validation-bypass step; what breaks first is supported-set exclusion or a
  library-validation failure at `dlopen`. (Details in the embedding bullet above.)
- **Claim 4 (JIT buys the Rung-0 win on device): UNPROVEN but likely** — no reason to expect interpreter-like
  perf if C1/C2 really run; debug-attach overhead shouldn't erase a 10–100× gap. Thermal / code-cache /
  iOS-26 JIT scripts unmeasured. `Bench.java` remains the right first proxy (ties to the prior miniJVM
  bottleneck, `dev-tools/jvmbench/RESULTS.md`); **add one short Java2D `BufferedImage` glyph smoke while on
  device** (retires part of the Phase-3 iOS-rasterizer caveat early).

**The go/no-go probe (exact signal).** Smallest "JIT-on" check, attach-vs-no-attach:
`-Xbatch -XX:+PrintCompilation -XX:CompileThreshold=100`. **Pass:** compilation log lines for the bench methods
**and** runtime ≫ `-Xint`. **Programmatic decisive signal** (no log parsing): `java.vm.name` =
`OpenJDK 64-Bit Server VM` (HotSpot, not Zero) **and** `java.vm.info` reports **`mixed mode`** (JIT) vs
**`interpreted mode`** (`-Xint`), **and** `ManagementFactory.getCompilationMXBean().getTotalCompilationTime()`
climbs above 0 during a hot loop. (`JitProbe.java` in `dev-tools/jvmbench/` emits all three.)

**Sequencing — two-prong spike, plus a thin Phase-3 rasterizer smoke alongside:**
1. **Oracle prong:** install **Amethyst** (on SideStore's iOS-26 supported list), attach StikDebug on the
   iPhone 17 Pro Max, and confirm JIT engages — proving "this exact phone + 26.5 + StikDebug + JRE 21 + HotSpot
   compiler threads active" *independently of our app's signing*. This retires the Claim-1 platform kill-risk
   the cheapest way before we build anything.
2. **Custom-shell prong:** minimal native shell, same JRE, **wait-for-JIT** → `dlopen(libjli)` → `JLI_Launch`,
   run `Hello`, `Bench`, and the `BufferedImage` glyph smoke. This is where the Claim-3 signing/embedding risk
   actually lands.
The **thin Phase-3 rasterizer smoke** (`BufferedImage` glyph → PNG on the iOS JRE) rides prong 2 — cheap, and
retires the desktop-CACIO rasterizer caveat earlier than a full Phase 3.

## Phase 1 — Prong A (oracle) GREEN on device (2026-05-29) — the platform kill-risk is RETIRED
Ran the Amethyst oracle on the real target: **iPhone 17 Pro Max, iOS 26.5 (TXM)**. Result: with **StikDebug
3.1.3 JIT attached** (Connect-by-App → Amethyst), the **full Amethyst build's bundled `jre21` HotSpot ran
Minecraft 1.21.4 fast and responsive.** The interpreter cannot do that (it's a slideshow / minutes to the
menu), so **Claim 1 — StikDebug enables HotSpot JIT on iOS 26.5 TXM — is settled GREEN on device.**
- **What this proves (the big one):** a JIT-enabled HotSpot **runs on this exact phone/OS** — the entire
  "can a real JIT JVM even work on bleeding-edge iOS 26.5" premise is no longer a kill-risk. And it's the
  **same `jre21-ios-aarch64` HotSpot we verified locally** (`dev-tools/JRE21-INVENTORY.md`) and will deploy.
- **Claim 3 substantially de-risked too:** Amethyst is an **AltStore free-cert** (`get-task-allow`) app loading
  **ad-hoc-signed** JRE dylibs via `dyld_bypass_validation` → `JLI_Launch`, and it works on this device. So the
  embedding mechanics + signing path are proven viable here; our own shell just has to replicate them.
- **Signal strength (honest):** "fast and responsive" is a **coarse** read (launcher + Minecraft menus/launch
  with JIT attached), not a numeric A/B. The demo *world* didn't fully generate — blocked by an **Amethyst iOS
  UI bug** (`SIGABRT` / `-[NSObject doesNotRecognizeSelector:]` on the UIKit main thread, in the Multiplayer
  "server list" nav — **not** a JVM/JIT/memory fault; irrelevant to our build). The **definitive numeric proof**
  (`java.vm.info` = `mixed mode` + `CompilationMXBean` climbing + `Bench` A/B vs `-Xint`) is **Prong B** with our
  own shell + `JitProbe`/`Bench`/`GlyphSmoke`.
- **Device-setup facts that worked (carry forward):** install apps via the dev's AltStore (free cert);
  **LocalDevVPN** (App Store) is the loopback VPN; **pairing file via iloader** (the iOS-26 remote-pairing
  format — pymobiledevice3's classic lockdown record is rejected with "missing field public key"); JIT is
  **per-launch** (re-attach StikDebug each cold start). Use the **full** Amethyst IPA (the slimmed one bundles
  no JRE); pick a **Java-21 MC version** (1.20.5–1.21.5; "latest" now needs Java 25).

## W1 minimal JVM shell — the TXM-JIT crux (Prong B; under review before build)
Studied Amethyst's `Natives/JavaLauncher.m launchJVM()` (GPLv3, patterns only). **The `JLI_Launch` glue is the
easy part; the deep unknown is the iOS-26 TXM JIT enablement.** Distilled so we don't re-clone the ephemeral ref:
- **HotSpot needs executable memory to boot** (it generates its template interpreter at VM init) → **even
  `Hello` can't start without working JIT** on a TXM device. So the shell's hard problem is platform JIT, not
  the launch call.
- **Two TXM JIT modes in Amethyst:** (1) **always-attached** — StikDebug stays attached,
  `task_set_exception_ports(… EXC_MASK_ALL & ~EXC_MASK_BREAKPOINT …)` + dyld-bypass; (2) **JS-script** —
  StikDebug *executes* a shipped `UniversalJIT26.js` (user "Assigns Script" per-app) + native
  `JIT26CreateRegion…`/`DeviceHasJITFlags` glue, detach-after-first-breakpoint. **Open question (the crux):
  does our app need the JS-script machinery, or does always-attached + `-XX:+MirrorMappedCodeCache` suffice?**
- **`-XX:+MirrorMappedCodeCache`** is the iOS-26 code-cache flag (likely an Amethyst/Pojav HotSpot *patch* — we
  inherit it by using their exact `jre21`). **`-XX:-UseCompressedClassPointers`** needed on **free cert** (no
  `extended-virtual-addressing` entitlement). Plus `-XX:+UnlockExperimentalVMOptions
  -XX:+DisablePrimordialThreadGuardPages`; reset SIG_DFL for SEGV/BUS/ILL/FPE/PIPE before `JLI_Launch`.
- **Dylib loading:** Amethyst uses a 287-line runtime `dyld_bypass_validation` to load ad-hoc dylibs. **Our
  hypothesis:** code-sign the bundled `jre21` dylibs with our dev cert at **build time** → avoid the bypass.
  Unverified (Claim A in the brief).
- **iOS JIT is via `get-task-allow` + StikDebug `CS_DEBUGGED`, NOT entitlements.** The macOS hardened-runtime
  keys (`com.apple.security.cs.allow-jit`) the predecessor app carried are likely **no-ops on iOS device**.
- **This architecture is under adversarial review before we build it:** `AUDIT-BRIEF-w1-jvm-shell.md` (Claims
  A–E: re-sign vs bypass; always-attached vs JS-script; minimal JLI args; free-cert entitlements; wait-for-JIT
  ordering). Matches the established brief→review→build rhythm.

### W1 shell — built + run on device (2026-05-29): Claim A SUPPORTED, Claim B fully REFUTED
Built the minimal Hello shell (`ios/app/`, xcodegen; commits f9fcbfd5c + ___clear_cache→`sys_icache_invalidate`
fix 1274d5678) and ran it on the iPhone 17 Pro Max (iOS 26.5, TXM) with StikDebug attached (`CS_DEBUGGED` set)
AND an iOS-26 JIT script assigned to our app. Results from `Documents/hello-shell.log`:
- **Claim A — re-sign avoids the dyld bypass: SUPPORTED (proven on device).** With the build-phase re-sign of
  every Mach-O in the embedded `jre/` (`scripts/resign-jre.sh`, signed *Apple Development: jordansimps@me.com*,
  team 5NZ6CPA994), a **plain `dlopen(libjli.dylib)` succeeded and `JLI_Launch` resolved** — no
  `dyld_bypass_validation` needed. iOS library validation accepts the re-signed JRE dylibs. (Device build also
  carries `get-task-allow=true` + `increased-memory-limit=true`, confirming Claim D.)
- **Claim B — assigned StikDebug script alone is enough: FULLY REFUTED.** Even with the universal script assigned
  to HelloShell, the app crashes the instant `JLI_Launch` starts VM init (last log line: "calling JLI_Launch").
  **Cause (clean-room mechanism study of Amethyst, 2026-05-29):** the assigned `UniversalJIT26.js` is only the
  *framework* (debugger main loop + GDB-packet parsing + StikDebug's `prepare_memory_region` stdlib). The per-app
  handler for HotSpot's `brk #0x69` code-cache request is **injected at runtime by the APP's native glue**, which
  our shell never did. So HotSpot's first code-cache mapping request hit an unhandled breakpoint → crash.
- **The required app-side TXM JIT26 glue (runs BEFORE `JLI_Launch`), distilled:**
  1. `brk #0x69` handshake → expect magic `0x690000E0` in x0 (StikDebug-present check).
  2. Inject the **extension script** via `mov x16,#2; brk #0xf00d` (x0=script ptr, x1=len) — installs
     `legacyCommands[0x69]` → `JIT26PrepareRegion` → StikDebug `prepare_memory_region(addr,len)`.
  3. Set detach-after-first-break via `mov x16,#3; brk #0xf00d` (x0=flag).
  4. `task_set_exception_ports(mach_task_self(), EXC_MASK_BAD_ACCESS, 0, EXCEPTION_DEFAULT, ...)`.
  5. then `-XX:+MirrorMappedCodeCache` JVM launch — HotSpot writes RW / executes RX mirror; unmapped mirror → it
     emits `brk #0x69`, StikDebug's injected handler maps the RX region and writes the addr back to x0.
  Protocol constants: break codes `0x69` (region request) + `0xf00d` (x16-dispatched command bus); magic
  `0x690000E0`; x16 cmds 0=detach 1=prepareRegion 2=newBreakpoints 3=setDetachAfterBr 4=prepareForPatching.
  `prepare_memory_region` is a **StikDebug stdlib** fn (version-coupled; StikDebug 3.1.x).
### W1 shell — GREEN on device (2026-05-29): HotSpot JIT runs our Java to main on our own app
After the map-on-fault fix to `runelite-jit.js`, **HelloShell reached `Hello.main` with `VERDICT = JIT ACTIVE`
on the iPhone 17 Pro Max (iOS 26.5, TXM)** — our own free-cert app, embedded jre21, StikDebug-attached. The
~10s-then-terminate is the expected JVM exit (`Hello.main` returns → `JLI_Launch`/`DestroyJavaVM` → `exit()`
kills the process); real RuneLite never returns from main so it won't self-terminate.
- **The working mechanism (decisive):** on this jre21, libjvm's `brk #0x69` (`BreakGetJITMapping`) path is gated
  in `os::pd_reserve_memory(size, exec)` → `get_debug_jit_mapping` behind `MirrorMappedCodeCache==1 &&
  DeviceRequiresTXMWorkaround()`. `DeviceRequiresTXMWorkaround()` detects TXM by reading
  `/private/preboot/.../Ap,TrustedExecutionMonitor.img4`, which the **app sandbox can't open** → returns 0 →
  libjvm falls back to `mmap(PROT_READ|PROT_EXEC)`, **non-executable on TXM** → HotSpot jumps in →
  instruction-abort. So `brk #0x69` never fires; the JIT enablement is **map-on-fault**: StikDebug's script
  catches each instruction-abort, `prepare_memory_region(faultPage,16KB)`, and continues (NO signal re-deliver).
  Our first script wrongly forwarded the fault as a signal → killed the VM; the fix made it the on-demand RX
  mapper. (Kept the `brk #0x69` handler too, in case the gate is ever satisfied.)
- **Map-on-fault was the WRONG mechanism (dead-end), replaced by patched brk #0x69.** The first "green" run
  used a map-on-fault script (make each faulting code page RX). It reached a JIT-active verdict but then
  **looped on `brk #0x1` (HotSpot `ShouldNotReachHere`)** every ~10s until the script's fault cap → crash.
  Hitting `ShouldNotReachHere` means map-on-fault doesn't reliably set up the RW↔RX **mirror** — it makes pages
  executable but the mirror content/control-flow is wrong. The fix is to use HotSpot's **designed** path:
  `os::pd_reserve_memory(exec)` → `get_debug_jit_mapping` → `BreakGetJITMapping` (`brk #0x69`), which asks the
  debugger for ONE properly-mirrored RX region. That path is gated behind `MirrorMappedCodeCache==1 &&
  DeviceRequiresTXMWorkaround()`; the latter reads `/private/preboot` (sandbox-blocked) → returns 0 → fallback.
  **`dev-tools/patch-jre-txm.sh` NOPs the `DeviceRequiresTXMWorkaround` gate (`cbz w0` @ file 0x81fc8c → nop),
  leaving the MirrorMapped flag check intact**, so the brk path engages on TXM. `runelite-jit.js` rewritten:
  brk #0x69 = allocate RX (`_M size,rx`) + `prepare_memory_region` + return addr (primary); fetch-abort faults
  deduped-and-mapped once (no loop); any real HotSpot brk (`#0x1`) or re-fault forwarded to the VM. App also
  passes `-XX:ReservedCodeCacheSize=64m` to bound the reservation. **Patch is REQUIRED per-machine** after
  fetching the JRE (see JRE21-INVENTORY).
- **W1 platform crux essentially retired on our own app** (free-cert sign + re-signed ad-hoc jre21 dylibs, no
  dyld bypass + `get-task-allow`/`increased-memory-limit` + StikDebug `CS_DEBUGGED` + patched-libjvm brk #0x69 +
  JIT script). Pending the clean re-run to confirm `Hello.main` completes + clean exit, then: swap `Hello` →
  `JitProbe`/`Bench`/`GlyphSmoke` for definitive JIT numbers + iOS rasterizer smoke, then W1 Phase 3 (AWT).

### W1 shell — DEFINITIVELY GREEN, clean end-to-end (2026-05-29). PRONG B COMPLETE.
HelloShell (our own free-cert app) booted a **JIT-compiling HotSpot** on the iPhone 17 Pro Max (iOS 26.5, TXM),
ran `Hello.main`, and shut down cleanly. From `Documents/jvm-stdio.log`:
`[JIT26] Requesting 64 MB` → `[JIT26] Got JIT mapping 0x1138b8000 from debugger` → `[JIT26] mapping at
RW=0x1178b8000, RX=0x1138b8000` → `java.vm.info = mixed mode` → `compileMs 10 -> 13` (C1/C2 ran) →
`VERDICT = JIT ACTIVE` → `main() returned cleanly` → `[jvm] DestroyJavaVM done`. **No crash.**
- **Two fixes made it work, in order:**
  1. **Embed via `JNI_CreateJavaVM`, NOT `JLI_Launch`.** The real blocker (found via a symbolicated `crash.log`
     from `NSSetUncaughtExceptionHandler`) was NOT the JIT: libjli's `apple_main` (the launcher's first-thread
     dance, spawned at libjli `0xd69c`) `dlsym`s and re-invokes our executable `main()` →
     `UIApplicationMain` → "There can only be one UIApplication instance". `HACK_IGNORE_START_ON_FIRST_THREAD`
     did NOT prevent it on our path (Amethyst pairs it with `-XstartOnFirstThread`). Bypassing the launcher
     entirely (dlopen `libjvm` → `JNI_CreateJavaVM` on our worker thread → `FindClass`/`CallStaticVoidMethod`)
     removes the whole problem class. Vendored `jni.h` + a minimal arm64-Darwin `jni_md.h`.
  2. **Patch libjvm to force the brk #0x69 path** (`dev-tools/patch-jre-txm.sh` NOPs BOTH gates in
     `get_debug_jit_mapping` @ `0x81fc80` + `0x81fc8c`). Confirmed working: `[JIT26] Requesting/Got mapping` +
     the RW≠RX mirror prove HotSpot took the debugger path and `vm_remap`'d a RW mirror of the StikDebug `_M`
     RX region. **map-on-fault is fully abandoned** (the `_M`/brk path gives a real mirror; faults didn't).
- **The would-be reviewer questions are answered by this run:** (Q1) debugger-script-only IS sufficient — no
  app-side `JIT26CreateRegion`/`task_set_exception_ports` glue needed, once the gate is patched and we embed via
  JNI; (Q2) the mirror is correct (RW=0x1178b8000 / RX=0x1138b8000, HotSpot makes the RW view itself); (Q3) our
  `brk #0x69` handler (`_M size,rx` → `prepare_memory_region` → return addr) is right. `AUDIT-BRIEF-w1-txm-mirror.md`
  is therefore retired (no external review needed).
- **NEXT:** swap `Hello` → `JitProbe`/`Bench`/`GlyphSmoke` (`dev-tools/jvmbench/`) for the definitive on-device
  JIT throughput numbers (Rung 1) + the iOS native 2D/font rasterizer smoke; then W1 Phase 3 (AWT backend:
  Caciocavallo + fake-xawt) toward running real RuneLite headless-core on device.

### Rung-1 numbers + rasterizer smoke ON DEVICE (2026-05-29). PHASE 1 COMPLETE.
Ran `JitProbe`+`Bench`+`GlyphSmoke` (one VM via `JNI_CreateJavaVM`, `probes.jar`) on the iPhone 17 Pro Max
(iOS 26.5 TXM) under StikDebug. Same `[JIT26]` mirror trace; all three ran; clean.
- **JIT works; warmed throughput is excellent (Bench):** `raster ≈ 2.57–2.64 G ops/s`, `floatbits ≈ 263–267 M
  ops/s`, `mixed ≈ 128–130 M ops/s`. Real JIT-compiled performance on-device.
- **GlyphSmoke: the iOS headless Java2D/font rasterizer WORKS** — wrote a legible 320×160 PNG (6290 non-blank
  px), `font=SansSerif.bold stringWidth("RuneLite iOS")=180`, AA text + circle + rotated rect rendered correctly
  with `-Djava.awt.headless=true` (libfontmanager/libfreetype/libawt_headless). Major Phase-3 de-risk — that's
  the `Graphics2D` path RuneLite overlays draw through.
- **Nuance — cold/OSR-under-debugger is slow:** `JitProbe`'s single 200 M-iter OSR loop measured only ~2.16 M
  ops/s (92.6 s, `compileMs delta=4`) → OSR barely engaged during that cold loop under the debugger; it ran
  ~interpreted. Verdict still `JIT ACTIVE`. Bench (which warms via repeated *method* calls → standard tiered
  compilation) shows the true fast steady state. **Implication for the real app:** expect warmup lag, then fast
  steady state — RuneLite's game loop calls methods repeatedly (like Bench), so standard compilation applies.
  (If warmup proves painful later, options: `-XX:-TieredCompilation`/`-XX:TieredStopAtLevel`, tune
  CompileThreshold, or AOT/CDS — deferred.)
- **PHASE 1 (W1 platform: on-device JIT JVM) is DONE.** Next: W1 Phase 3 — the AWT backend (Caciocavallo +
  fake-xawt) so RuneLite's `Canvas`/`BufferProvider` init on-device; that's the next adversarial-brief→review
  candidate (AWT-on-iOS is the next genuine unknown). Recommend a fresh session for it.

### W1 shell — earlier device run (2026-05-29): the licensing/mechanism notes that led here
- **Licensing fork (resolved):** the brk stubs + protocol are interface (clean-room-able), but the JS scripts
  (`UniversalJIT26.js` framework + `…Extension.js` handler) are Amethyst's GPLv3 IP. Need to either reimplement
  the extension clean-room (small; the 0x69 handler) and rely on a base script StikDebug/community provides, or
  reimplement both. **Open Q being investigated:** does StikDebug 3.1.3 ship a built-in universal JIT script (so
  we write only our small extension), or is the whole framework an Amethyst artifact we must rewrite?

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
