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
  - **JIT:** **StikDebug 3.1.2** (iOS 17.4+, with iOS 26 fixes) attaches a debugger per-launch → sets `CS_DEBUGGED` → HotSpot JIT works. StikDebug **explicitly lists PojavLauncher as supported** — a JIT JVM is already proven on this exact stack. *Per-launch* activation (vs TrollStore's automatic) is the main UX cost.
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
- Set up **AltStore (or SideStore) + StikDebug** on the iPhone 17 Pro Max (iOS 26.5); verify you can build+sign+install a trivial iOS app and **attach StikDebug to enable JIT** on it (smallest possible "JIT-on" proof). TrollStore is N/A on 26.5.
- Obtain Pojav's prebuilt **`jre21-ios-aarch64`** (JDK 21 — RuneLite needs 11+, 21 is current & forward-safe).
  Unzip; inventory `libjli.dylib`, `libjvm.dylib`, `lib/`, `modules`/`jrt-fs`, `Frameworks/` layout.
- Stand up the **desktop inner loop**: run **stock RuneLite on desktop OpenJDK 21**, logged into live OSRS, as
  the reference + dev harness. (We already have desktop OpenJDK 11; add 21.)
**Refs:** Pojav `android-openjdk-build-multiarch` / iOS JRE releases; `Natives/LauncherPrefManageJREViewController.m`; StikDebug (iOS 17.4+ JIT).
**Deliverable:** device runs a hello app with JIT enabled via StikDebug; desktop runs stock RuneLite in-game. **GATE:** both green.
**Risk:** iOS 26.5 JIT regressions → confirm StikDebug 3.1.2 works on 26.5 (Pojav is listed supported); else pin to a known-good point release.

### Phase 1 — Embed OpenJDK on device, run Java, **confirm JIT** (= Rung 1, the platform gate)
**Goal:** prove a JIT-enabled HotSpot runs our code on the device.
**Steps:**
- New iOS app target; bundle the JRE in `Frameworks/`; `dlopen(libjli)` → `dlsym(JLI_Launch)` → launch with a
  classpath + main class. (Mirror our existing `jvm_create`/`jvm_init` callsite in `mobile/c/glfmapp/main.c`.)
- Run `Hello` + the **Rung-0 microbench** (`dev-tools/jvmbench/Bench.java`) on-device.
- **Confirm JIT actually engages** (StikDebug attached): check `-XX:+PrintCompilation`, and compare to
  `-Xint`. JIT requires the StikDebug debugger to be attached at launch (per-launch); document the launch ritual.
**Refs (Amethyst):** `Natives/JavaLauncher.m` (JLI_Launch), `Natives/dyld_bypass_validation.m` (load ad-hoc
JRE dylibs), `Natives/external/ballpa1n/HostManager.c` + `Natives/external/mach/mach_excServer.c` (JIT enable),
`JavaApp/src/launcher/.../PojavLauncher.java` (Java-side launch glue).
**Deliverable:** on-device microbench numbers; JIT confirmed N× miniJVM. **GATE:** **HotSpot JITs on device (StikDebug attached, iOS 26.5).**
**Risk:** iOS 26.5 JIT regression or silent interpreter fallback → still likely ≫ miniJVM (Rung 0), but flag it; this gate de-risks the whole bleeding-edge-iOS concern early.

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

## Open review item (pre-build)
`ios/AUDIT-BRIEF-platform-feasibility.md` is an adversarial review brief for the three uncertain platform
assumptions: **(1)** AWT-on-iOS via Caciocavallo actually rendering OSRS + RuneLite overlays, **(2)** RuneLite
running headless without a deep fork, **(3)** gate sequencing. Early evidence (in the brief): RuneLite composites
game + overlays into one offscreen `BufferedImage` (`callback/Hooks.java:168/412/477`), so there **is** a clean
capture seam — but `ClientUI`/Swing/EDT coupling is **deeper than first assumed** (~115 `SwingUtilities.invoke*`,
system tray, `GraphicsEnvironment`). **Likely resequencing to validate before committing:** make the *first* gate
a **desktop CACIO + headless-RuneLite + overlay-capture proof** (zero iOS) — it tests the product premise cheapest.
Resolve the brief before/alongside Phase 0.

## Definition of done
Self-contained sideloaded iOS app: launch → Jagex login → in-game on **live OSRS**, **touch controls + Jagex-Mobile-style UI**, **RuneLite plugins** toggle/config natively with overlays composited, **playable FPS**, stable for hours, surviving a weekly game update — **no host, no desktop, no streaming.**

## Immediate next steps (Phase 0 → 1, in parallel with starting Phase 2 on desktop)
1. Set up AltStore/SideStore + StikDebug on the iPhone 17 Pro Max (iOS 26.5); build+install a hello iOS app and confirm StikDebug enables JIT on it.
2. Fetch `jre21-ios-aarch64`; inventory its layout.
3. Stand up stock RuneLite on desktop OpenJDK 21 (baseline + W2 dev harness).
4. Begin Phase 1 (embed libjli → JLI_Launch → run `Bench` on device, confirm JIT) **and** Phase 2 (headless RuneLite fork on desktop) together.
