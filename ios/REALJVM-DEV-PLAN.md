# Dev Plan — RuneLite as a native iOS app (on-device JVM, plugins, Jagex-Mobile-style UI)

**Status:** PLAN. Rung 0 (perf premise) ✅ proven (`dev-tools/jvmbench/RESULTS.md`). Companion to
`REALJVM-FEASIBILITY-SCOPE.md` (the why) and `HANDOFF-realJVM-feasibility.md`.
**Last updated:** 2026-05-29.

## The target (locked with the user 2026-05-29)
A **self-contained native iOS app** that:
- runs **real RuneLite** (not our gamepack harness) on an **on-device JIT JVM** — so the **plugin ecosystem works unmodified**,
- plays **live/official OSRS**,
- looks and feels like the **official Jagex OSRS Mobile app** (touch controls, mobile chrome), and
- needs **no host machine** (no streaming) and is sideloaded (AltStore + StikDebug), App-Store-independent.

## Target device & install path (confirmed 2026-05-29)
- **Dev device:** iPhone 17 Pro Max on **iOS 26.5** — overkill on performance (Rung 0 showed 45–184× headroom; horsepower is a non-issue).
- **TrollStore is NOT available** on iOS 26.5 (CoreTrust patched since iOS 17.0.1; TrollStore tops out at 16.6.1/17.0). So the install/JIT path is **AltStore (or SideStore) + StikDebug**, not TrollStore.
  - **JIT:** **StikDebug 3.1.3** (released 2026-05-18; iOS 17.4+, with iOS 26 fixes) attaches a debugger per-launch → sets `CS_DEBUGGED` → HotSpot JIT works. *Per-launch* activation (vs TrollStore's automatic) is the main UX cost. **iOS-26/TXM caveat (Phase-1 review):** StikDebug lists iOS 26.0+ as supported but with **"Limited App Availability"**; SideStore's JIT doc says iOS 26 "broke JIT again" and the supported set is **specific apps (Amethyst listed), not arbitrary custom apps**, with SideStore's own enabler limited to **non-TXM** devices on 26. The iPhone 17 Pro Max is **TXM** → whether JIT is granted to *our* app (vs a listed app) is the open Claim-1 risk. **Mitigation: oracle-first** — prove JIT on Amethyst on this phone before building our target (see Phase 1).
  - **Memory cap:** replaced TrollStore's cap-lift with the **`increased-memory-limit` entitlement**, which **AltStore 2.2+ supports on a free dev account** — so we keep heap headroom for RuneLite + plugins.
  - **App expiry:** free Apple ID = **7-day resign** (AltStore auto-refreshes); **$99/yr** paid dev account = yearly + smoother. 3-app limit on free tier.
- **iOS-version sensitivity (watch item):** iOS 26 "broke JIT again"; StikDebug's fix is recent/limited. **Validate JIT on 26.5 in Phase 1 before any big investment** (it's the Phase 1 gate).
- **Device/iOS floor for the finished app** (your device is far above it): the binding floor is the JIT method (**~iOS 17.4+** for StikDebug), and iOS 26 needs **iPhone 11 / A13 (2019)+**. As far back as iPhone XS (A12) is feasible if staying on iOS 17.4–18. Performance is not the constraint; the sideload/JIT method is.

### Two corrections this plan bakes in (vs. the old spike framing)
1. **Run real RuneLite, not the gamepack + `GamepackProbe` injection harness.** On a real JVM, RuneLite boots
   itself, loads the gamepack itself, and runs the **whole plugin system for free**. We *delete* the injection
   harness (and the shelved GPU-snapshot renderer) — not port them. RuneLite is **BSD-2-Clause**, so forking it
   is license-clean. (Pojav/Amethyst native code is GPLv3 → read for patterns, don't copy.)
2. **"Mobile design" is a whole workstream the spike never scoped.** RuneLite's UI is **desktop Swing**. Getting
   "like Jagex Mobile" means running RuneLite's plugin/overlay **core headless** and building a **native
   iOS UI** on top — independent of the JVM work and arguably the bigger lift.

### ⚠️ Risk you've accepted (stated plainly, once)
A self-built iOS client is an **unapproved third-party client** on live OSRS (Jagex's approved list is
desktop-only: official, RuneLite, HDOS, OSBuddy). Using it **risks account bans** (2-week → permanent), it
**cannot be shipped to others** (Jagex takedowns + Apple won't carry it + users get banned), and login
fingerprints may be detectable. **Use a throwaway Jagex account; never a main.** This plan is therefore a
**personal / technical-achievement** build. (Full risk register at the end.)

---

## Architecture — three workstreams

```
  ┌─────────────────────────── Native iOS app (Swift/UIKit) ───────────────────────────┐
  │  W3: Mobile UI shell — Jagex-Mobile-style chrome, touch controls, native plugin     │
  │      config panels, on-screen buttons, tabs, chat. Composites the game + overlays.  │
  └───────────────▲───────────────────────────────────────────────────▲────────────────┘
                  │ JNI / C bridge (framebuffer out, input + plugin-state in/out)
  ┌───────────────┴───────────────────────────────────────────────────┴────────────────┐
  │  W2: RuneLite core, HEADLESS — real RuneLite client + PluginManager + overlay        │
  │      rendering, Swing ClientUI bypassed; engine renders to an int[] we own.          │
  └───────────────▲──────────────────────────────────────────────────────────────────────┘
                  │ runs on
  ┌───────────────┴──────────────────────────────────────────────────────────────────────┐
  │  W1: Platform — OpenJDK 21 (JIT-enabled HotSpot) embedded on iOS, JIT via StikDebug,   │
  │      AWT backend (Caciocavallo + fake-xawt) so the engine's Canvas/BufferProvider work.│
  └────────────────────────────────────────────────────────────────────────────────────────┘
```

- **W1 = "can it run"** — the spike's focus. Hardest unknowns, mostly de-risked in principle.
- **W2 = "real RuneLite + plugins, headless"** — a RuneLite fork; develop on **desktop OpenJDK first**.
- **W3 = "the mobile app"** — the biggest *product* effort; native iOS, JVM-independent.

### The accelerator: a desktop-OpenJDK inner loop
Desktop OpenJDK and the iOS device JIT-HotSpot are the **same JVM family**. So **W2 (all the RuneLite-headless +
plugin + auth Java) is developed and debugged on desktop OpenJDK first** — full debugger, instant iterate, no
device cycle — then deployed to the device JVM. Only W1 (embedding, JIT, AWT-on-iOS) and W3 (native UI) are
device-only. This removes the device round-trip from the bulk of the Java work.

---

## Phases (each: Goal · Steps · Reuse/refs · Deliverable · GATE · Risk)

> Reference files: **ours** in `vendor/miniJVM/...`; **Amethyst** patterns (GPLv3, read-only) in the
> `AngelAuraMC/Amethyst-iOS` repo; **RuneLite** in this outer repo's `runelite-client/`.

### Phase 0 — Foundations & artifacts
**Goal:** toolchain, device, JRE, and the desktop baseline all green before real work.
**Steps:**
- Set up **SideStore (or AltStore) + StikDebug 3.1.3** on the iPhone 17 Pro Max (iOS 26.5). TrollStore is N/A on 26.5.
- **Oracle-first JIT proof (the cheapest Claim-1 settle):** install **Amethyst** (on SideStore's iOS-26 supported list), attach StikDebug, and confirm JIT engages on *this exact phone* — before building any custom target. (Custom-app JIT on a TXM device is the open risk; an app already on the supported list isolates the platform question from our signing.)
- Obtain the prebuilt **`jre21-ios-aarch64`** (JDK 21 — RuneLite needs 11+, 21 is current & forward-safe). ✅ **DONE 2026-05-29** — fetched from Amethyst's CDN (`https://crystall1ne.dev/cdn/amethyst-ios/jre21-ios-aarch64.zip`), checksum-pinned, unzipped + verified HotSpot Tiered C1/C2 (not Zero), `platform IOS`, `_JLI_Launch` exported. Staged at `dev-tools/jre/` (gitignored); full record in `dev-tools/JRE21-INVENTORY.md`. **JRE 17 is the fallback, not JDK 8.**
- Stand up the **desktop inner loop**: run **stock RuneLite on desktop OpenJDK 21**, logged into live OSRS, as
  the reference + dev harness. ✅ already done (see FINDINGS "Stock RuneLite on desktop OpenJDK 21").
**Refs:** Amethyst `Makefile` (JRE CDN URL), `Natives/LauncherPrefManageJREViewController.m`; StikDebug 3.1.3 (iOS 17.4+ JIT); SideStore JIT doc (supported-app set).
**Deliverable:** Amethyst runs with JIT enabled via StikDebug on the phone (oracle GREEN); JRE 21 fetched + checksum-pinned + inventoried; desktop runs stock RuneLite in-game. **GATE:** oracle JIT green + JRE in hand.
**Risk:** iOS 26.5 / TXM JIT limited to the supported app set → if the oracle is green but our custom app is denied JIT, that's the Phase-1 Claim-3 finding (escalate: SideJITServer, or pin a known-good config).

### Phase 1 — **Confirm JIT on device** as a two-prong spike (= Rung 1, the platform gate)
**Goal:** prove a JIT-enabled HotSpot runs our code on the device — *harsher and smaller than a full app target*
(Phase-1 review). Two prongs, oracle first.

**Prong A — oracle (cheapest Claim-1 settle, no custom app):** with Amethyst (supported app) + StikDebug
attached on the iPhone 17 Pro Max, confirm JIT engages. The decisive signal is the JIT probe below; if
Amethyst exposes JVM args, run `JitProbe`/`Bench`; otherwise its own playable-vs-crawl behavior is the coarse
signal. **This proves the platform (phone + 26.5 + StikDebug + JRE 21 + C1/C2) before we build anything.**

**Prong B — custom shell (where the Claim-3 signing/embedding risk lands):** minimal native app target; bundle
the checksum-pinned JRE in `Frameworks/`. **Adopt the safer launch architecture:** the shell launches and
**waits for debugger-attach / JIT-ready** (a "Start JVM" gate), *then* runs the iOS-26/TXM JIT setup → dyld
bypass → `dlopen(libjli)` → `dlsym(JLI_Launch)` → launch with classpath + main class. **Do NOT warm the JVM
before JIT is live.** Run, in order: `Hello` → **`JitProbe`** → **Rung-0 `Bench`** → **`GlyphSmoke`** (the thin
Phase-3 rasterizer smoke; see below).
- **Callsite reference (corrected):** the predecessor spike's `ios-spike/vendor/miniJVM/mobile/c/glfmapp/main.c`
  is a **miniJVM** embedding (`jvm.h`/`jvm_init_mem_alloc` + bootclasspath/classpath assembly from C under
  glfm) — **not** a HotSpot `JLI_Launch` callsite (miniJVM is its own VM). Reuse it only as the *pattern* for
  "assemble classpath + boot a JVM from C on iOS"; the actual `JLI_Launch` glue comes from **Amethyst's
  `JavaLauncher.m`**, not our predecessor.

**The JIT probe (exact signal — `dev-tools/jvmbench/`):**
- Smallest log-based check: `-Xbatch -XX:+PrintCompilation -XX:CompileThreshold=100` → **pass** = compilation
  lines for the bench methods **and** runtime ≫ `-Xint`.
- Programmatic, no log parsing (`JitProbe.java`): `java.vm.name` = `OpenJDK 64-Bit Server VM` (HotSpot, not
  Zero) **and** `java.vm.info` = **`mixed mode`** (JIT) vs **`interpreted mode`** (`-Xint`) **and**
  `getCompilationMXBean().getTotalCompilationTime()` climbs > 0 during a hot loop. Run once attached, once with
  `-Xint`, once unattached — the A/B is the proof.

**Thin Phase-3 rasterizer smoke (rides Prong B, retires the CACIO caveat early):** `GlyphSmoke.java` draws text
+ a shape into a `BufferedImage` and writes a PNG — exercising the **iOS JRE's native 2D/font rasterizer**,
which the desktop CACIO gate could not (FINDINGS "Desktop CACIO overlay gate" caveat).

**Refs (Amethyst, GPLv3 — patterns only):** `Natives/JavaLauncher.m` (`JIT26*`/`dlopen`/`JLI_Launch` order),
`Natives/dyld_bypass_validation.m` (iOS-26/TXM paths), `Natives/external/ballpa1n/HostManager.c` +
`Natives/external/mach/mach_excServer.c` (JIT enable), `JavaApp/.../PojavLauncher.java` (Java-side launch glue).
**Deliverable:** Prong A oracle green; on-device `JitProbe` + `Bench` numbers (JIT confirmed N× `-Xint`/miniJVM);
`GlyphSmoke` PNG proving the iOS rasterizer works. **GATE:** **HotSpot JITs on device (StikDebug attached, iOS 26.5).**
**Risk:** custom-app JIT denied on TXM even though the oracle is green (Claim-3) → escalate (SideJITServer, config);
or silent interpreter fallback → still likely ≫ miniJVM (Rung 0), but flag it. This gate de-risks the whole
bleeding-edge-iOS concern early.

### Phase 2 — Real RuneLite **headless** on desktop OpenJDK (the core fork) ‖ runs parallel to Phase 1
**Goal:** real RuneLite + plugins + live login, rendering to an offscreen buffer, **no Swing window** — on desktop.
**Steps:**
- Fork `runelite-client`; bypass the Swing `ClientUI` launcher. Boot the Guice injector, `ClientLoader` (loads
  the gamepack), `PluginManager`, and the overlay pipeline **headless**; host the engine's `Applet`/`Canvas` in a
  surface we control and capture its `BufferProvider` int[].
- Keep **overlay rendering** (`OverlayRenderer`/`DrawManager`, Java2D onto the game buffer) — prove ≥1 plugin
  loads and its overlay composites.
- Wire **live Jagex-account auth** (reuse our `AuthRequesters` Proxy + `tokens.json` patterns; on real RuneLite
  the official Jagex session/OTL flow applies). `fetch-shield-tokens.sh` still mints tokens.
**Refs (ours):** `phase3/AuthRequesters.java`, `tokens.json` plumbing. **RuneLite:** `RuneLite.java`,
`ClientLoader`, `ClientUI`, `PluginManager`, `OverlayRenderer`, `DrawManager`.
**Deliverable:** headless RuneLite on desktop, logged into live OSRS, plugin overlay rendering to a buffer.
**GATE:** **plugins + overlays work headless** (the whole product premise).
**Risk:** RuneLite assumes a real Swing window in more places than expected → triage which hooks need a headless shim.

### Phase 3 — Bring headless RuneLite up on the **device** JVM (the AWT-on-iOS crux)
**Goal:** real RuneLite in-game on the device, rendering to a buffer.
**Steps:**
- Put RuneLite + deps + gamepack on the device JVM classpath (drop `minijvm_rt`; use OpenJDK's class library).
- Provide the **AWT backend** so the engine's `Canvas`/`BufferProvider` + RuneLite's headless needs initialize:
  **Caciocavallo** + the **fake-xawt** shim pattern. This is the deepest platform unknown — spike it early.
- Recompile our Java (the W2 fork) for the device; reach login → in-game; capture the framebuffer on device.
**Refs (Amethyst):** `Natives/awt_xawt/xawt_fake.m`, `empty.m`; Caciocavallo (CACIO) integration.
**Deliverable:** real RuneLite in-game on device, framebuffer produced. **GATE:** **in-game on device via OpenJDK.**
**Risk #4:** OSRS needs more AWT (fonts, image decode, EDT) than Minecraft did → may need extra CACIO surface area.

### Phase 4 — Present + touch input (make it interactive)
**Goal:** play core OSRS by touch on the device.
**Steps:**
- Blit the int[] framebuffer to screen — simplest path is a **Metal texture upload per frame** (765×503×4B is
  trivial; no GL needed for a software renderer). Reuse `egl_bridge.m`/`gl_bridge.m` only if GL is preferred.
- Map **UIKit touches → AWT `MouseEvent`** into the EventQueue, keys → `KeyEvent`; add a first-cut **mobile
  control scheme** (tap-to-walk, long-press → context menu, pinch-zoom, drag-look).
**Refs (Amethyst):** `Natives/SurfaceViewController.m`, `Natives/GameSurfaceView.m`, `Natives/customcontrols/*`.
**Deliverable:** playable OSRS by touch on device. **GATE:** **core gameplay works by touch.**

### Phase 5 — Measure the payoff (= Rung 3, the renderer decision gate)
**Goal:** confirm the engine's **own** software renderer is playable on device with JIT.
**Steps:** measure FPS in-game (JIT on); A/B vs the old 3 fps (miniJVM) / 18 fps (GPU hack).
**Deliverable:** on-device FPS. **GATE:** **playable at full fidelity (target ≥ ~25–30 fps for a good mobile feel).**
**If NO-GO:** fallbacks — RuneLite's **GPU plugin** on iOS via the GL bridge (the engine still drives it
faithfully, unlike our shelved snapshot hack), or revisit. (Rung 0 makes GO the expected outcome.)

### Phase 6 — Native mobile UI shell, Jagex-Mobile-style (W3 — the big product layer)
**Goal:** the app *looks and feels* like the official OSRS Mobile app, with plugins surfaced natively.
**Sub-phases (iterative):**
- **6a Control scheme:** the Jagex-Mobile-style on-screen buttons, tap targets, gestures; tighten Phase-4's cut.
- **6b Chrome:** native minimap/compass, the bottom **tab bar** (inventory/skills/etc.), chat, settings — SwiftUI/UIKit over the game surface.
- **6c Plugin management, native:** list/toggle/configure RuneLite plugins from a **native** panel (bridge to
  `PluginManager` + each plugin's config descriptor over JNI), replacing RuneLite's Swing config UI.
- **6d Overlay composition + polish:** ensure plugin overlays composite correctly over the game; visual polish to match Jagex Mobile.
**Refs:** the official OSRS Mobile app as the design target; RuneLite `PluginManager`/`ConfigManager` for the data model.
**Deliverable:** the app resembles Jagex Mobile + has working plugin config. **Largest, most iterative phase.**
**Risk #2:** this is product/UX work, not platform — budget accordingly; it's independent of W1.

### Phase 7 — Stability, memory, updates
**Goal:** stable multi-hour play; survive weekly game updates.
**Steps:** validate the **`increased-memory-limit` entitlement** (via AltStore 2.2+) gives RuneLite + plugins enough heap (≫ miniJVM); GC tuning; re-evaluate the
old `sig:11` SIGSEGV on the new runtime; soak test. **Weekly updates:** real RuneLite **updates the gamepack
itself** — a major maintenance win over our pinned-revision harness (no more hand-coded obfuscated names).
**Deliverable:** stable long sessions; survives a Jagex update. **GATE:** multi-hour soak passes.

### Phase 8 — Packaging & install flow
**Goal:** a clean sideload install for you.
**Steps:** AltStore/SideStore IPA install (sign with `increased-memory-limit` + `get-task-allow`); JIT-enable UX (StikDebug per-launch attach; consider SideJITServer for auto); 7-day resign cadence (or $99/yr cert);
first-run setup (token mint). **Deliverable:** installable IPA. (No App Store — by design.)

---

## Sequencing & parallelism
- **Phase 1 (device JVM)** and **Phase 2 (desktop headless RuneLite)** run **in parallel** — different machines, different skills.
- **Phase 3** merges them (needs 1 + 2). **Phases 4–5** follow on device.
- **Phase 6 (native UI)** can start its design/scaffolding during 2–3 and integrate after 4.
- **7–8** are hardening/packaging.
- Critical path to "playable on device": **0 → 1 → 3 → 4 → 5**. Critical path to "feels like Jagex Mobile": **+6**.

## Rough effort (order-of-magnitude, not commitments)
- Phase 0: days · Phase 1: ~1 wk · Phase 2: 2–4 wk (the RuneLite headless fork) · Phase 3: 1–3 wk (AWT crux) ·
  Phase 4: 1–2 wk · Phase 5: days · **Phase 6: many weeks** (the mobile app) · Phase 7: ongoing · Phase 8: days.
- **Reality:** a polished result is a **multi-month, three-workstream** effort. The plan front-loads cheap GO/NO-GO
  gates (1, 3, 5) so you can stop early if a platform assumption breaks, and delivers "playable on device" well
  before the big UI investment.

## Risk register
1. **ToS / account ban (live OSRS, unapproved client) — accepted.** Throwaway account only; not shippable to others.
2. **Phase 6 (mobile UI) is the largest effort** and is product, not platform.
3. **JIT enablement is iOS-version-sensitive** (community-maintained; Apple tightens periodically). On iOS 26.5 specifically: no TrollStore → StikDebug per-launch attach; iOS 26 has had JIT regressions, so pin a known-good StikDebug + validate at Phase 1. Per-launch JIT + 7-day resign are ongoing UX costs.
4. **AWT/Swing-headless completeness on iOS** (Phase 3) — the deepest platform unknown after JIT.
5. **Maintenance:** RuneLite + plugins + gamepack move weekly — mitigated because **real RuneLite self-updates the gamepack** (vs our pinned hand-coded harness).
6. **Apple/distribution:** sideload-only forever; no App Store.

## Audit outcome & resequencing (RESOLVED 2026-05-29)
`ios/AUDIT-BRIEF-platform-feasibility.md` was reviewed adversarially. Verdicts: **Claim 1** (AWT-on-iOS
renders overlays) UNPROVEN; **Claim 2** (RuneLite headless) UNPROVEN and *deeper than "a few seams"* — the
honest minimum is a **real CACIO EDT + a purpose-built `ClientUI` replacement**, not a casual mock; **Claim 3**
(sequencing) REFUTED-as-sequenced → the first product-killing gate must be the **desktop CACIO + headless
RuneLite + overlay-capture** proof, with device JIT run **in parallel**, not first.

**Corrections baked in:**
- **Use `java.awt.headless=false` + a CACIO toolkit, NOT `java.awt.headless=true`.** Java headless mode selects
  the headless toolkit and throws for display-dependent APIs; CACIO needs its own toolkit active (`CTCToolkit`/
  `CTCGraphicsEnvironment` on desktop), installed *before* AWT init. This is a "virtual display" architecture.
- Coupling numbers (this checkout): ~**104** `SwingUtilities.invoke*` across **47** files; ~**86** plugin files
  import Swing. **Plugins start *before* `clientUI.show()`** (correcting the brief's "after").
- StikDebug latest is **3.1.3** (not 3.1.2); iOS 26 JIT support is **limited** per SideStore docs — keep Phase 1
  as the parallel JIT gate.

**Corrected gate order (cheapest-kills-first):**
1. **Gate 1a/1b — desktop CACIO overlay smoke ✅ DONE (GREEN).** `dev-tools/cacio-smoke/`: RuneLite's overlay
   draw ops render to a buffer with no window, on plain headless **and** under the live CACIO CTC toolkit; cacio
   runs on JDK 21. **Caveat:** desktop `BufferedImage`/`CFontManager` are toolkit-independent, so this settles
   **Claim 2** + overlay-code portability, **not** the iOS native font/2D rasterizer (Claim 1's iOS half → rides
   to Phase 3). See `FINDINGS.md` "Desktop CACIO overlay gate".
- **Prereq — stock RuneLite on desktop OpenJDK 21 ✅ DONE (Phase 0 step 3).** Builds + runs to the live-OSRS
   login screen with full plugins + GPU plugin. Reference harness `dev-tools/run-desktop-reference.sh`;
   injected-client SNAPSHOT resolves; JDK-21 flags documented. See `FINDINGS.md` "Stock RuneLite on desktop OpenJDK 21".
2. **Gate 1c — desktop headless RuneLite.** Split per the 1c audit (kill-risk-first):
   - **1c login-screen capture ✅ DONE (GREEN, 2026-05-29).** `dev-tools/headless/`: real RuneLite boots with
     a window-less `HeadlessClientUI` (replaces the Swing `ClientUI` via a Guice override) and renders the
     **live-OSRS login screen** into `mainBufferProvider` → PNG, on the **native toolkit** *and* under **CACIO
     CTC with no OS window**. Settles the audit's kill risk (**Claim D**): the engine drives `Hooks.draw` with
     a non-null `Graphics` headless (`nullGraphics=0`, ~42 fps). See `FINDINGS.md` "Gate 1c".
   - **1c-i (mocked-client overlay smoke) — SKIPPED.** `OverlayRenderer` gates all layers on `LOGGED_IN`
     (`OverlayRenderer.java:249`), so a no-gamepack mock can't drive the real overlay path — it would just
     re-derive Gate 1a. (Confirms the audit's Claim-C "vacuous" risk.)
   - **1c-ii — live login + in-game capture ✅ DONE (GREEN, 2026-05-29).** Headless RuneLite logs into live
     OSRS on the throwaway account and composites a **real RuneLite plugin overlay** (the FPS overlay) into
     the captured buffer — on the **native toolkit AND CACIO CTC with no OS window**. Harness
     `dev-tools/headless/run-headless-login.sh`(+`-cacio`); auth `dev-tools/fetch-shield-tokens.sh`.
     **Key findings:** (a) the `JX_*` launcher handoff auto-*authenticates* (`loginIndex=10`, welcome screen)
     but does NOT auto-*login* — it parks at `LOGIN_SCREEN`; (b) crossing to `LOGGED_IN` needs a **synthesized
     Play Now click** (`HeadlessLogin` posts a focus+mouse sequence into the gamepack canvas via the toolkit
     `EventQueue`) — the window-less engine accepts it (Phase-4 input kill-risk settled minimally, same
     UIKit→AWT path the device needs); (c) a free account must `changeWorld` to an F2P world first
     (default 301). Closes Claim 2's long pole. = Phase 2. **The minimal click is the only Phase-4 input
     built — the full mobile control scheme stays later product work.**
3. **Device HotSpot/JIT microbench** (`-XX:+PrintCompilation` vs `-Xint`) — **parallel** to 1c. = Phase 1.
4. Merge: same headless RuneLite fork on the device JVM. = Phase 3.
5. Soak: memory, JIT persistence, weekly gamepack update. = Phase 7.

## Definition of done
Self-contained sideloaded iOS app: launch → Jagex login → in-game on **live OSRS**, **touch controls + Jagex-Mobile-style UI**, **RuneLite plugins** toggle/config natively with overlays composited, **playable FPS**, stable for hours, surviving a weekly game update — **no host, no desktop, no streaming.**

## Immediate next steps (Phase 1, two-prong; W2/Phase 2 already GREEN on desktop)
1. **[NEEDS HANDS]** Set up SideStore/AltStore + **StikDebug 3.1.3** on the iPhone 17 Pro Max (iOS 26.5).
2. **[NEEDS HANDS] Oracle prong:** install **Amethyst** (supported app) + attach StikDebug → confirm JIT engages on this phone (settles Claim 1 the cheapest way, isolated from our signing).
3. ✅ **DONE** — `jre21-ios-aarch64.zip` fetched from the Amethyst CDN, checksum-pinned + verified (HotSpot Tiered C1/C2, `platform IOS`, `_JLI_Launch` exported). See `dev-tools/JRE21-INVENTORY.md`.
4. ✅ **READY** — probe artifacts built + desktop-verified in `dev-tools/jvmbench/`: `JitProbe.java` (decisive JIT-vs-interpreter signal), `Bench.java` (Rung-1 A/B), `GlyphSmoke.java` (iOS rasterizer smoke), `PHASE1-PROBE.md` (run procedure). Run once a device launch path exists.
5. **Custom-shell prong:** minimal native target (**Free Apple ID** cert → `get-task-allow` + `increased-memory-limit`, 7-day resign, 3-app limit), wait-for-JIT → `dlopen(libjli)` → `JLI_Launch` → run `Hello`/`JitProbe`/`Bench`/`GlyphSmoke`. (Build after the oracle is green, so we don't invest in the target before the platform is proven.)
6. **W2 follow-up (deferred, non-blocking):** capture one world-anchored overlay frame (tile indicator / NPC highlight) to close the FPS-only overlay gap (FINDINGS Gate 1c-ii "Honest gap").
