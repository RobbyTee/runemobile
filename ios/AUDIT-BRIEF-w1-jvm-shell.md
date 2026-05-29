# Audit brief — W1 minimal JVM shell on iOS 26.5 (the TXM-JIT crux)

**For:** an independent reviewer. **Goal:** try to **refute** the plan for our **own minimal native iOS app
that embeds the JIT-enabled HotSpot `jre21` and runs our Java on-device** — specifically the **iOS-26 TXM JIT
enablement**, which is the deepest remaining platform unknown. Default to skepticism; for each unproven claim
name the **cheapest experiment** that settles it, flag any simpler/safer path, and tell us **what breaks first
at launch**. Concrete corrections (entitlements, build-phase mechanics, HotSpot flags, file:line) welcome.

## Context — what's already settled (don't re-litigate)
- **Phase-1 oracle GREEN (on device).** On the real target — **iPhone 17 Pro Max, iOS 26.5 (a TXM device)** —
  **Amethyst's bundled `jre21` HotSpot ran Minecraft 1.21.4 fast & responsive with StikDebug 3.1.3 JIT
  attached.** So a JIT-enabled HotSpot demonstrably works on this phone. (Coarse signal: launcher+menus+launch;
  the demo *world* was blocked by an Amethyst UIKit bug, unrelated to the JVM.)
- **The JRE is in hand + verified** (`dev-tools/JRE21-INVENTORY.md`): `jre21-ios-aarch64` from Amethyst's CDN,
  checksum-pinned, HotSpot Tiered C1/C2 (not Zero), Mach-O `platform IOS`, `libjli.dylib` exports `_JLI_Launch`,
  dylibs are **ad-hoc/linker-signed** (no CMS signature). **We will deploy this exact JRE** (so we inherit its
  custom HotSpot patches, incl. the `-XX:+MirrorMappedCodeCache` flag — see below).
- **W2 is GREEN on desktop** (headless RuneLite boot→live-login→in-game→overlay). Our Java is proven; this brief
  is purely about the **device platform shell (W1)**.
- **Install/JIT path:** sideload via the user's **AltStore (free Apple ID** dev cert; 7-day resign; 3-app
  limit), JIT via **StikDebug 3.1.3** per-launch attach + **LocalDevVPN** loopback; pairing file via **iloader**
  (iOS-26 remote-pairing format). Xcode **26.5** on the Mac. TrollStore N/A on 26.5.
- **Amethyst (`AngelAuraMC/Amethyst-iOS`, GPLv3 — patterns only, we clean-room) is the reference.** Distilled
  launch sequence in the appendix.

## The proposed minimal-shell plan (attack this)
A new iOS app target whose **only** job is: prove *our* app can load the embedded `jre21` and `JLI_Launch` a
trivial `Hello` main on-device under our free-cert signing + StikDebug — then (trivially) swap the main class to
our `JitProbe`/`Bench`/`GlyphSmoke` (`dev-tools/jvmbench/`) for the definitive numeric JIT + rasterizer proof.

Proposed mechanics (mirroring Amethyst, minus Minecraft/AWT/LWJGL):
1. **Bundle the verified `jre21`** in the app. **Sign its dylibs with our dev cert at build time** (an Xcode
   codesign build phase) so dyld loads them under library validation — *hoping to avoid* Amethyst's 287-line
   runtime `dyld_bypass_validation` hook entirely.
2. **Wait-for-JIT gate:** app launches, shows a "Start JVM" control, blocks until StikDebug is attached / JIT is
   ready — *then* proceeds (do not warm HotSpot before JIT is live).
3. **`dlopen(libjli.dylib)` → `dlsym("JLI_Launch")`**, reset signal handlers (SIG_DFL for SEGV/BUS/ILL/FPE/PIPE),
   `JLI_Launch(argc, argv, …, "java","openjdk", FALSE,TRUE,FALSE,TRUE)`.
4. **JLI args for a plain Hello** (our hypothesized minimal set): `-XX:+MirrorMappedCodeCache` (iOS 26),
   `-XX:+UnlockExperimentalVMOptions -XX:+DisablePrimordialThreadGuardPages`, `-XX:-UseCompressedClassPointers`
   (free cert lacks extended-VA), `-Djava.library.path=<app>/Frameworks`, `-cp <Hello.jar>`, `Hello`. **No** AWT
   / Caciocavallo / LWJGL / `-XstartOnFirstThread` (no GL) — those are Minecraft-specific.
- **GATE:** our own app boots HotSpot and runs `Hello` on-device (settles Claim 3 for *our* app, not just Amethyst).

## Claims to refute / confirm

### Claim A — we can avoid the runtime dyld bypass by build-time re-signing the JRE dylibs
We assume that if Xcode signs the bundled `jre21` `.dylib`s with our dev cert (codesign build phase / embedded
frameworks), dyld loads them normally and Amethyst's `dyld_bypass_validation` is unnecessary.
- **Refute:** does iOS library validation still reject them (team-id pinning, the JRE dylibs `dlopen` each other
  via `@rpath`, `libjli`→`libjvm`→`lib/*.dylib` chains, ad-hoc→re-signed mismatch)? Does the free-cert profile
  forbid loading >N embedded dylibs, or dylibs not declared as linked frameworks? **What fails first** —
  re-sign step, app launch, or the first inter-dylib `dlopen`? Is the bypass actually mandatory on 26.5?
- **Cheapest experiment:** name it (e.g., build a stub app that just `dlopen`s the re-signed `libjvm.dylib` and
  reports `dlerror()`).

### Claim B — TXM JIT: does "always-attached StikDebug + `-XX:+MirrorMappedCodeCache`" suffice, or is the JS script required? (THE CRUX)
HotSpot generates its **template interpreter** into executable memory at VM init, so **even `Hello` can't boot
without working JIT memory** on this TXM device. Amethyst has two paths (appendix): (1) **always-attached** —
StikDebug stays attached, `task_set_exception_ports` lets it catch breakpoints, `init_bypassDyldLibValidation`,
run with `-XX:+MirrorMappedCodeCache`; (2) **JS-script** — StikDebug *executes* a shipped
`UniversalJIT26.js`/`Amethyst-MeloNX.js` that the user "Assigns" to the app, plus native `JIT26CreateRegion…`
glue, detaching after the first breakpoint.
- **Refute:** Can our app get HotSpot to boot on TXM 26.5 with **just** always-attached StikDebug +
  `-XX:+MirrorMappedCodeCache` (the simple path), or is the **JS-script + `JIT26*` native machinery mandatory**
  on TXM? If mandatory: must our app **ship its own JIT script** that the user assigns in StikDebug (per-app),
  and what exactly must that script do (mirror-map RWX regions? set BPs?) that the debugger-attach alone
  doesn't? Is `-XX:+MirrorMappedCodeCache` a **custom patch in Amethyst's jre21** (we inherit it) or stock — and
  does it even apply without their region setup? **What breaks first** if we try the simple path — HotSpot
  aborts generating the template interpreter (SIGILL/EXC_BAD_ACCESS on RWX), or silently?
- **Cheapest experiment:** name the smallest on-device test that distinguishes "simple path works" from
  "JS-script required" (e.g., the Hello shell with always-attached StikDebug → does HotSpot reach `main`?).

### Claim C — the minimal JLI arg set is right for a plain Hello
- **Refute:** is anything in the proposed arg set wrong/insufficient for a **no-AWT, no-GL** Hello on this JRE?
  Is `-XX:-UseCompressedClassPointers` actually needed on free cert (no extended-VA entitlement), or does it
  hurt? Are `DisablePrimordialThreadGuardPages` / experimental opts needed at all for Hello? Does omitting
  `-XstartOnFirstThread` + `HACK_IGNORE_START_ON_FIRST_THREAD` cause a thread/abort problem when there's no GL
  main thread? Any iOS-26-specific flag we're missing for the code cache?

### Claim D — free-Apple-ID entitlements: what actually grants iOS device JIT?
We believe iOS device JIT comes from **`get-task-allow` + StikDebug's debugger-attach → `CS_DEBUGGED`**, NOT from
entitlements; and that the macOS hardened-runtime keys (`com.apple.security.cs.allow-jit`,
`allow-unsigned-executable-memory`) the *predecessor* app carried are **no-ops on iOS device**. Memory headroom
comes from `com.apple.developer.kernel.increased-memory-limit` (AltStore 2.2+ injects on free cert).
- **Refute:** is that correct? What is the **exact** entitlement set our `.entitlements` should declare for a
  free-cert AltStore iOS app to (a) be debuggable for StikDebug JIT, (b) get memory headroom? Does AltStore
  inject `increased-memory-limit` automatically or must we declare it? Any entitlement that, if wrongly
  declared, makes the free-cert sign **fail**?

### Claim E — wait-for-JIT ordering + detecting "JIT ready"
StikDebug attaches per-launch; JIT must be live **before** HotSpot init.
- **Refute:** how does the shell reliably know StikDebug has attached / JIT is ready before it calls
  `JLI_Launch`? Poll `getppid()`/`sysctl KERN_PROC` for `P_TRACED`? A user-driven "Start" button after they
  attach? Does Amethyst's always-attached mode need the app to *wait*, or does StikDebug attach at process
  spawn? What's the robust, race-free gate?

## Also worth a skeptical pass
- **Is the minimal shell even the right next step, or should we first run `JitProbe`/`Bench` *through Amethyst***
  (which has working TXM JIT) for the numeric proof — i.e., is decoupling "definitive JIT numbers" from "our own
  shell" cheaper? (Caveat: that needs modifying/rebuilding Amethyst to launch our main class.)
- **Clean-room / licensing:** we read Amethyst (GPLv3) for patterns and reimplement; our shell ships under our
  own terms alongside BSD-2 RuneLite. Any contamination risk in reimplementing the dyld-bypass / `JIT26` approach
  from reading their source? Where's the line?
- **Build/sign flow:** Xcode 26.5 → `xcodebuild` → IPA → AltStore install (free cert). Right flow? Does the
  JRE-dylib codesign build phase interact badly with AltStore's re-sign-on-install (double-sign / strip)?
- **`MirrorMappedCodeCache` provenance:** confirm whether it's an Amethyst/Pojav HotSpot patch (so it only exists
  *because* we use their JRE) and what it presupposes about the JIT region setup.

## What a useful audit output looks like
Per claim: **REFUTED / SUPPORTED / UNPROVEN** + evidence (file:line / entitlement names / HotSpot flag docs) +
the single cheapest experiment. Plus: a **go/no-go on the simple path** (always-attached + MirrorMappedCodeCache,
re-signed dylibs, no bypass) vs the verdict that we **must** replicate the JS-script TXM machinery; the **correct
free-cert `.entitlements`**; and **what breaks first at launch**.

---

## Appendix — Amethyst launch sequence (distilled, GPLv3 — patterns only)
From `Natives/JavaLauncher.m` `launchJVM()`:
- **TXM detection:** `DeviceHasJITFlags(JIT_FLAG_FORCE_MIRRORED | JIT_FLAG_HAS_TXM)` → `requiresTXMWorkaround`.
- **TXM path (not always-attached):** `JIT26CreateRegionLegacy(getpagesize())` (expects magic `0x690000E0`),
  `JIT26SendJITScript(<UniversalJIT26Extension.js>)`, `JIT26SetDetachAfterFirstBr(...)`,
  `task_set_exception_ports(…, EXC_MASK_BAD_ACCESS, …)`. Ships `resources/UniversalJIT26.js` (+`…Extension.js`);
  user "Assigns Script" in StikDebug.
- **Always-attached path** (`debug.debug_always_attached_jit`): `task_set_exception_ports(…, EXC_MASK_ALL &
  ~EXC_MASK_BREAKPOINT, …)` + `init_bypassDyldLibValidation()`.
- **Dylib load:** `init_bypassDyldLibValidation()` (`Natives/dyld_bypass_validation.m`, 287 lines: patches
  `libsystem_kernel` `mmap`/`fcntl`, `vm_protect`, signature checks) → `dlopen(<javaHome>/lib/libjli.dylib,
  RTLD_GLOBAL)` → `dlsym("JLI_Launch")`.
- **iOS-26 flag:** `if (@available(iOS 26.0,*)) margv[]="-XX:+MirrorMappedCodeCache";`
- **Free-cert flag:** `if(!entitlement("com.apple.developer.kernel.extended-virtual-addressing"))
  margv[]="-XX:-UseCompressedClassPointers";`
- **Other:** `-XX:+UnlockExperimentalVMOptions -XX:+DisablePrimordialThreadGuardPages`; signal handlers reset to
  `SIG_DFL` before launch; mem via `increased-memory-limit`; `-XstartOnFirstThread` + env
  `HACK_IGNORE_START_ON_FIRST_THREAD=1` (Minecraft/GL — likely N/A for Hello).
- **Files to read:** `Natives/JavaLauncher.m` (launch), `Natives/dyld_bypass_validation.m` (bypass),
  `Natives/utils.m`+`utils.h` (`DeviceHasJITFlags`/`JIT26*`), `Natives/main_hook.m`,
  `Natives/resources/UniversalJIT26.js`(+`Extension`). Clone: `git clone --depth 1
  https://github.com/AngelAuraMC/Amethyst-iOS` (read-only; do not vendor).
