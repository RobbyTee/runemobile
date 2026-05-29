# Scope — Real-JVM (OpenJDK + JIT-on-iOS) feasibility spike

**Written:** 2026-05-28. **Status:** SCOPE ONLY — awaiting user sign-off before any re-platforming.
**Companion to:** `HANDOFF-realJVM-feasibility.md` (the why), `project_ios_perf_pivot` (the finding that triggered this).
**Decision being scoped:** replace the slow custom `miniJVM` with a real JITing JVM (OpenJDK) on iOS, the
PojavLauncher/Amethyst way, so the OSRS engine's **own faithful software renderer** runs at playable FPS and
the entire GPU-reconstruction problem is solved by deletion.

---

## 0. TL;DR / recommendation

- **The performance payoff is near-certain, not speculative.** Our gamepack is `net.runelite:injected-client` —
  the *exact* artifact desktop RuneLite runs on a stock JVM every day at smooth FPS with the engine's own
  renderer. "Is the faithful renderer fast enough on a real JIT JVM?" is already answered YES on desktop. The
  open risk is **platform/packaging on iOS**, not perf.
- **The approach is real engineering, but every piece has a working reference.** It is essentially *"build a
  PojavLauncher-equivalent shell that loads the OSRS gamepack instead of Minecraft."* No novel research; it's
  integration of known-good parts (OpenJDK-iOS build, the sideload+JIT trick, an embedded AWT backend, a
  framebuffer+input bridge — all of which Pojav/Amethyst already ship for Minecraft).
- **Our Java is ~100% portable.** The login driver (`GamepackProbe.java`) and auth (`AuthRequesters.java`) use
  only standard reflection + `java.lang.reflect.Proxy` — zero miniJVM API calls. On a real JVM they get *easier*
  (the miniJVM-specific `invokedynamic`/`StringConcatFactory`/`CONSTANT_Dynamic` C patches simply **vanish** —
  HotSpot does all of that natively). See §4, §5.
- **Critical clarification (do not skip):** there are **two different "OpenJDK on iOS" worlds**. The *official*
  OpenJDK Mobile port is **Zero-interpreter-only on iOS-arm64 (NO JIT)** by design. The **PojavLauncher path is a
  JIT-enabled HotSpot** made to run via sideload + the `get-task-allow`/debugger `CS_DEBUGGED` trick. **We want
  the Pojav path.** Picking the wrong one silently caps you at interpreter speed. See §1.
- **Recommended path:** a 4-rung de-risking ladder (§7), each rung a cheap GO/NO-GO, hardest unknowns first.
  Rung 0 costs an afternoon and needs **no iOS at all**. Do **not** port anything until Rung 0 + Rung 1 pass.
- **One decision needed from you up front:** do you have a **TrollStore/sideload-capable iOS device** (and which
  iOS version)? That single fact picks device-first vs simulator-first for the JVM bring-up (§1.4, §8).

---

## 1. JIT-on-iOS enablement — the linchpin, and the trap

### 1.1 The trap: official OpenJDK-Mobile is Zero-only (no JIT) on iOS
The official OpenJDK **Mobile** project can build a static `libjvm` that runs on real iOS devices — **but on
iOS-arm64 it is restricted to the Zero interpreter**. Apple forbids apps from running runtime-generated assembly,
which rules out *both* HotSpot's template interpreter *and* its C1/C2 JIT compilers. Their forward plan is **Zero +
Project Leyden AOT** to sidestep the no-JIT rule. If we naively grabbed "OpenJDK for iOS," we'd get this — a real
JVM, but interpreter-only, defeating the whole point. [1][2]

### 1.2 The candidate: PojavLauncher/Amethyst — JIT-enabled HotSpot via the sideload trick
PojavLauncher (and its successor `AngelAuraMC/Amethyst-iOS`) run a **HotSpot build with the JIT compilers enabled**
and make it legal at runtime by **sideloading + attaching a debugger**: a binary signed with
`com.apple.security.get-task-allow` can have a debug server attached, which sets the kernel's **`CS_DEBUGGED`**
flag; that flag suppresses the code-signing enforcement (`CS_KILL`/`CS_HARD`) that would otherwise kill a process
the moment it writes executable JIT pages. With `CS_DEBUGGED` set, HotSpot's dual-mapped W^X JIT memory works and
you get *real* JIT — which is exactly why Minecraft is playable on iPhones through Pojav. The user's
**sideload-only stance makes this path available.** [3][4][5]

### 1.3 How JIT gets turned on, by install method (device)
| Method | How JIT is enabled | Notes |
|---|---|---|
| **TrollStore** | Automatic — Pojav consumes TrollStore's extended entitlements, self-enables JIT, **and lifts the memory cap** | Best case; needs a TrollStore-supported iOS version. Relevant to our GC churn (§8 of handoff). [3][4] |
| **Jailbreak** | Automatic (detects jailbreak) | Not assumed. [4] |
| **AltStore / SideStore / SideJITServer / Jitterbug / StikDebug** | Attach a debug server over the network/on-device → sets `CS_DEBUGGED` | Works on current iOS (incl. 17.4+ via StikDebug); no memory-cap lift; must re-enable per launch. [3][6][7] |

Apple has tightened this over time (the old iOS≤13 `ptrace(PT_TRACE_ME)` self-trace was patched in iOS 14; the
current path needs `get-task-allow` + a real debugger attach). **It works today via the tools above but it's a
community-maintained, iOS-version-sensitive surface** — fine for a personal sideloaded build, fragile for "ship to
others." [5][8]

### 1.4 The Simulator: permissive for JIT, but needs a simulator-ABI build
The **iOS Simulator runs under macOS security rules, not device AMFI** — so JIT works there permissively (no
`get-task-allow`/debugger dance; macOS allows JIT for ad-hoc-signed / non-hardened-runtime processes, optionally
via the `com.apple.security.cs.allow-jit` entitlement). [9][10] **But** a simulator app can only load Mach-O built
for the `arm64-apple-ios*-simulator` platform; it **cannot** load a device-platform `libjvm`. Pojav/Amethyst ship
**device** JREs only. So **simulator-first means building a JIT-enabled HotSpot from source for the simulator
triple** — nobody ships that, and it front-loads the single hardest unknown.

**Implication for path choice (see §7/§8):**
- **Device-first** reuses Pojav's *prebuilt JIT JRE* → fastest path to a real signal, but needs a
  sideload-capable device.
- **Simulator-first** keeps our established loop (the user drives the simulator) and dodges entitlements, but
  requires the from-source simulator build of HotSpot **before any signal at all**.

---

## 2. OpenJDK packaging for iOS/arm64

- **Which JDK version:** the gamepack uses `invokedynamic` + `makeConcatWithConstants` (JDK 9+) +
  `CONSTANT_Dynamic` (JDK 11+) bytecode (these are exactly the three features miniJVM had to hand-patch — see §4).
  RuneLite targets Java 11. ⇒ **target OpenJDK 17 or 21.** The Pojav ecosystem ships 17 and 21 runtimes (it runs MC
  up to 1.21, which needs Java 21); Amethyst lists **Azul Zulu** as its JDK source. **Not** Pojav's old `jdk8u`
  fork — JDK 8 can't even load condy class files. [11][12]
- **Packaging:** the OpenJDK Mobile repo builds a **static `libjvm`** for embedding in an iOS app binary; you boot
  it in-process via the **JNI Invocation API** (`JNI_CreateJavaVM` / the `libjli` launcher entry), then drive
  classes through JNI — structurally the same place our `main.c` calls `jvm_create()` + `jvm_init()` today
  (`mobile/c/glfmapp/main.c:115-122`). [1]
- **Where the build comes from:** device-first → reuse the prebuilt Pojav/Amethyst/Zulu **device** JRE (no
  from-source build to *start*). Simulator-first → build the mobile-OpenJDK (or Pojav's fork) for the simulator
  triple from source, **with the JIT compilers left in** (the official mobile config disables them on iOS — we'd
  re-enable, accepting we then rely on macOS-rules JIT in the simulator and `CS_DEBUGGED` JIT on device). This
  build-config delta is the chief packaging unknown to de-risk.
- **License:** OpenJDK is GPLv2+CE (fine to ship). Amethyst/Pojav are **GPLv3** — read for patterns, license-review
  before copying code (already flagged in `reference_amethyst_ios`). The CACIO/LWJGL/GL4ES bits carry their own
  licenses (GPLv2 / MIT / BSD).

---

## 3. Booting the gamepack — minimum scaffolding to a login screen

What the gamepack needs from any JVM: load `injected-client.jar` + `runelite-api.jar` on the classpath,
instantiate the `client` applet, give it an AWT `Canvas`/applet-stub surface, and feed it OAuth tokens. Our
classpath is already enumerated (`main.c:92-112`): `minijvm_rt` (→ **drop**, replaced by OpenJDK's class library),
`glfm_gui`, `xgui`, `slf4j-api`, `slf4j-simple`, `runelite-api`, `guava`, `json` (org.json — needed by the login
handshake), `injected-client`.

**Can we reuse RuneLite's own Java loader instead of our custom injection? Largely yes** — it's plain Java and runs
on OpenJDK. Today `GamepackProbe.java` does the applet bring-up by hand via reflection (`Class.forName("client")`,
field injection into obfuscated slots `client.cr`/`client.xw`/`tf.zi`, then `init()`/`start()`). That code is
JVM-agnostic and ports as-is; on a real JVM we additionally have the *option* to lean on more of RuneLite's actual
client bootstrap (it expects a real `java.applet`/`java.awt` stack — see §4). **Desktop RuneLite already does
exactly this boot on a stock JVM**, so the path is proven; the iOS-specific work is only the AWT surface (§4) and
keeping our token plumbing (§5).

---

## 4. Surface + input bridge

**The OSRS software renderer needs a `java.awt` surface + a pixel buffer; it does not need OpenGL.** It draws into
a `BufferProvider` int[] that we already read and blit (the `client.adb(true)` → `getBufferProvider()` path in
`jni_glfm.c`). So the iOS bridge is: **embedded AWT backend → engine renders into the int[] → our existing GLFM
blit puts it on screen.**

- **AWT on iOS** is the one genuinely new component, and it has a known answer: **Caciocavallo (CACIO)** — the
  OpenJDK project for pluggable, headless/embedded AWT backends — which **Amethyst already bundles** for precisely
  this ("run an AWT/Swing-ish app on a JVM with no native windowing"). We provide a CACIO backend whose framebuffer
  is the int[] we blit. [12]
- **We keep what already works:** the GLFM blit/compositing and the **input mapping is already standard** — touch →
  `Component.deliverEvent(MouseEvent)`, keys → `Component.deliverKey(...)` with VK translation (`jni_glfm.c`). None
  of it is miniJVM-coupled. On real AWT we may even feed events through the standard `java.awt.EventQueue` instead
  of our hand-rolled dispatch.
- **What we do *not* need from Pojav:** its heavy GL stack (LWJGL3 + LWJGLX + GL4ES + MetalANGLE + MoltenVK + Mesa)
  exists to satisfy *Minecraft's* OpenGL. The OSRS **software** path needs none of it. (If we later want RuneLite's
  *GPU* plugin on iOS for extra FPS headroom, that stack becomes relevant — but that's post-payoff, and the whole
  point of the pivot is that the software renderer is fast enough once the JVM is fast.)

---

## 5. Re-doing our login/auth work — gets *easier*

`AuthRequesters.java` builds `java.lang.reflect.Proxy` instances against the gamepack's classloader implementing
the OAuth requester interfaces; `GamepackProbe.java` injects tokens and drives the applet lifecycle. **Both are
pure standard Java** — and several things that are *hard* under miniJVM become free under HotSpot:

- The **three "non-negotiable" miniJVM C patches disappear**: `CONSTANT_Dynamic` CP parsing, the `invokedynamic`
  bootstrap + the custom `MethodInfo`-unwrap of the CallSite, and `StringConcatFactory.makeConcatWithConstants`
  are all **native in HotSpot**. That's not a port — it's deletion. (`interpreter.c` ~300-402 today.)
- Real reflection / `Proxy` / JNI / (optional) `java.lang.instrument` agents all behave to spec, so any miniJVM
  quirks we've worked around go away.
- **Token plumbing carries over unchanged:** `tokens.json` (Jagex OAuth refresh/access + otl), `fetch-shield-tokens.sh`,
  the live-refresh + live-mint OTL logic — all JVM-agnostic. (`project_ios_auth_tokens`.)

**Estimate:** the login/auth port is **small** (days, mostly re-wiring entry points and the AWT surface), and is
the *low* risk in this whole effort.

---

## 6. The payoff measurement (the decision gate)

Once it boots, measure FPS of the engine's own (`GPU=0`-equivalent) software renderer on the iOS OpenJDK:
- **On the Simulator:** correctness + a perf sanity check (simulator FPS ≠ device FPS — it runs on the Mac's CPU,
  so treat it as an upper bound / functional check, not the real number).
- **On a device with JIT on:** the real number. **GO if playable** (target ≳ the ~18 fps the GPU hack reached, at
  full fidelity). Given desktop RuneLite's smoothness and that modern A-series CPUs running JIT-HotSpot are in the
  same league, **GO is the expected outcome.**
- **Graceful-degradation insight:** even **without** JIT (Zero/interpreter HotSpot), HotSpot has *real intrinsics*
  for the exact ops that sank miniJVM (`Float.floatToIntBits` et al. were ~27% of our CPU as a per-call native
  dispatch — see `project_ios_perf_pivot`). So even the no-JIT tier should be **N× miniJVM**. The approach
  degrades gracefully rather than failing binary.

---

## 7. The de-risking ladder (do these in order; each is a GO/NO-GO)

**Rung 0 — Quantify the speedup on desktop. (≈ an afternoon, NO iOS.) — ✅ DONE 2026-05-29: strong GO.**
Built the desktop `mini_jvm` CLI from this repo's miniJVM C source and ran a representative microbench (the
profiler's dominant ops, same Java-8 bytecode, no `invokedynamic`) under it vs OpenJDK 11 on the same Mac.
**Result: OpenJDK is 45–184× faster than miniJVM's interpreter** (the regime behind the current ~3 fps), and
**12–109× faster even than miniJVM's *best case* with its sljit JIT fully engaged** — the floatbits floor is
12× because that's a native-dispatch miniJVM's JIT can't intrinsify. Massive headroom; the pivot's perf premise
is confirmed cheaply. Full method + numbers: `dev-tools/jvmbench/RESULTS.md` (bench: `dev-tools/jvmbench/Bench.java`).

**Rung 1 — A JIT-enabled HotSpot runs Java on the target + a microbench. (days)**
Pick the path from §1.4/§8. Get `libjvm` to execute trivial Java **and confirm JIT actually engages** (not a
silent Zero fallback — check `-XX:+PrintCompilation` / tier logs), then run the Float-bitops microbench.
Deliverable: "HotSpot-on-target is **N×** miniJVM, JIT confirmed on." **This is the core platform gate.**

**Rung 2 — Boot the gamepack to a login screen on that JVM. (1–2 weeks)**
Port the (portable) `GamepackProbe`/`AuthRequesters`, drop `minijvm_rt`, add the CACIO AWT backend, keep
`tokens.json` plumbing. Deliverable: the OSRS login screen rendered through OpenJDK.

**Rung 3 — Wire framebuffer blit + input; measure the renderer. (days) — THE DECISION GATE.**
Connect the engine's int[] to our GLFM blit and the input mapping; get in-game; measure software-renderer FPS on a
JIT device (§6). **GO ⇒ the renderer problem is deleted** (fast *and* perfectly faithful, gamepack unmodified,
auto-updating). NO-GO ⇒ fall back to the shelved GPU-snapshot path (still on disk, behind flags).

---

## 8. Effort / risk — honest estimate

| Component | Risk | Why | Reuse |
|---|---|---|---|
| Perf payoff (is real JVM fast enough) | **Low** | Desktop RuneLite already runs this exact gamepack smoothly | — |
| Login/auth/gamepack boot Java | **Low** | Pure reflection/Proxy; miniJVM C patches *delete* on HotSpot | `GamepackProbe`, `AuthRequesters`, `tokens.json` |
| Surface + input bridge | **Low–Med** | AWT-on-iOS via CACIO is known; our blit+input already JVM-agnostic | GLFM blit, input map; Amethyst's CACIO integration |
| JIT enablement | **Med** | Mechanism proven (Pojav), but iOS-version-sensitive, community-maintained | TrollStore/StikDebug/Pojav |
| **OpenJDK-iOS packaging** | **Med–High** | Device: reuse prebuilt (low). **Simulator: from-source JIT-enabled build is the hardest single unknown** | Pojav device JRE / mobile-OpenJDK build scripts |

**Overall:** a **multi-week** integration effort (rough order: Rung 1 ≈ days–1wk, Rung 2 ≈ 1–2wk, Rung 3 ≈ days),
**front-loaded so the expensive parts are gated by cheap signals.** No single component is research-grade; the cost
is integration + the OpenJDK build config. The dominant risk is **packaging**, and it's almost entirely retired by
choosing **device-first with Pojav's prebuilt JIT JRE** for Rung 1.

**Smallest de-risking experiment to run first:** **Rung 0** (desktop ratio, ~an afternoon, no iOS), immediately
followed by **Rung 1** on whichever target §8's decision selects.

---

## 9. Decisions needed before Rung 1 (for sign-off)

1. **Do you have a TrollStore/sideload-capable iOS device, and which iOS version?** This picks the bring-up path:
   - **Device-first (recommended if yes):** reuse Pojav's prebuilt JIT JRE → fastest real signal; TrollStore also
     lifts the memory cap. Simulator stays the loop for iterating the *Java/harness* once a JVM exists.
   - **Simulator-first (if no device):** keeps the current workflow but requires the from-source simulator HotSpot
     build before any signal — slower start, higher first-step risk.
2. **OK to proceed Rung 0 → Rung 1 now**, holding Rung 2/3 (the actual port) for a second sign-off after the
   platform gate passes?
3. **GPLv3 boundary:** confirm we treat Amethyst/Pojav as *read-for-patterns* only (no code copy) until a license
   review — consistent with `reference_amethyst_ios`.

---

## Sources
- [1] OpenJDK Mobile — iOS platform implementation: https://openjdk.org/projects/mobile/ios.html and project page https://openjdk.org/projects/mobile/
- [2] "Running Java on iOS: Gluon Introduces OpenJDK Mobile Resources…" (InfoQ, 2025-11): https://www.infoq.com/news/2025/11/java-on-ios/
- [3] PojavLauncher iOS — "iOS and JIT" FAQ: https://pojavlauncher.app/wiki/faq/ios/JIT.html
- [4] Amethyst-iOS (AngelAuraMC) + install/JIT/TrollStore: https://github.com/AngelAuraMC/Amethyst-iOS , https://wiki.angelauramc.dev/wiki/getting_started/INSTALL.html
- [5] Saagar Jha, "Jailed Just-in-Time Compilation on iOS" (CS_DEBUGGED / ptrace history): https://saagarjha.com/blog/2020/02/23/jailed-just-in-time-compilation-on-ios/
- [6] StikDebug (on-device JIT enabler, iOS 17.4+): https://github.com/StephenDev0/StikDebug
- [7] SideStore — Enabling JIT: https://docs.sidestore.io/docs/advanced/jit
- [8] iOS Debugging/JIT guides index: https://spidy123222.github.io/iOS-Debugging-JIT-Guides/
- [9] Apple — `com.apple.security.cs.allow-jit` entitlement & `MAP_JIT`: https://developer.apple.com/documentation/BundleResources/Entitlements/com.apple.security.cs.allow-jit
- [10] macOS JIT memory / AMFI (simulator runs under macOS rules): https://kyleavery.com/posts/macos-jit-memory/ , https://book.hacktricks.wiki/en/macos-hardening/macos-security-and-privilege-escalation/macos-security-protections/macos-amfi-applemobilefileintegrity.html
- [11] PojavLauncher OpenJDK build repos (multiarch; JRE 8/17/21 ecosystem): https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases
- [12] Amethyst third-party components (Azul Zulu JDK; Caciocavallo; LWJGL3/LWJGLX; GL4ES/MetalANGLE/MoltenVK/Mesa): https://github.com/AngelAuraMC/Amethyst-iOS
