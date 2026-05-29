# Phase 1 — device JIT probe + run procedure

The two-prong Phase-1 spike (see `../../REALJVM-DEV-PLAN.md` Phase 1 and `../../FINDINGS.md`
"Phase-1 audit verdict"). Goal: prove **HotSpot JITs on the iPhone 17 Pro Max, iOS 26.5, under StikDebug** —
*harsher and smaller than a full app target*. Oracle first, then our shell.

## Artifacts in this dir
| File | Role | Pass signal |
|---|---|---|
| `JitProbe.java` | Decisive JIT-vs-interpreter check | `VERDICT = JIT ACTIVE` (mixed mode + `compileMs>0`) |
| `Bench.java` | Rung-1 perf A/B (floatbits / raster / mixed) | throughput ≫ `-Xint`, ratios echo Rung-0 |
| `GlyphSmoke.java` | iOS native 2D/font rasterizer smoke | exit 0, `nonBlankPixels` ≫ 0, PNG legible |

Compile (Java-8 bytecode — no invokedynamic, runs on the device JDK 21 and on desktop):
```sh
javac --release 8 -d out JitProbe.java Bench.java GlyphSmoke.java
```

## Desktop ground truth (already run, for reference)
On desktop OpenJDK 21 (aarch64) the A/B is unambiguous — this is the shape to reproduce on device:
- `java -cp out JitProbe` → `OpenJDK 64-Bit Server VM` / **`mixed mode`** / `CompilationMXBean = HotSpot 64-Bit
  Tiered Compilers` / hotLoop ≈ **88.6M ops/s** / `compileMs=2` / **`VERDICT = JIT ACTIVE`**.
- `java -Xint -cp out JitProbe` → **`interpreted mode`** / `CompilationMXBean = <absent>` / hotLoop ≈ **3.8M
  ops/s** (~23× slower) / **`VERDICT = INTERPRETER ONLY`**. Identical checksum to the JIT run (same arithmetic).
- `java -cp out GlyphSmoke /tmp/glyph-smoke.png` → `nonBlankPixels=6701`, legible bold AA text + shapes.

## The probe (run this on device, three ways, read the verdict each time)
```sh
java -cp out JitProbe                                            # StikDebug attached -> expect JIT ACTIVE
java -Xint -cp out JitProbe                                      # negative control -> INTERPRETER ONLY
java -Xbatch -XX:+PrintCompilation -XX:CompileThreshold=100 -cp out JitProbe   # log-based cross-check
```
**Decisive signals (any one suffices; we want all three to agree):**
1. `java.vm.name == "OpenJDK 64-Bit Server VM"` → HotSpot, **not** a Zero port (Zero would silently cap us at
   interpreter speed — the trap in FINDINGS "Two different OpenJDK on iOS worlds").
2. `java.vm.info` contains **`mixed mode`** (JIT) vs **`interpreted mode`** (`-Xint`).
3. `CompilationMXBean.getTotalCompilationTime()` climbs **> 0** during the hot loop (C1/C2 threads ran).

Then `Bench` (the Rung-1 number) and `GlyphSmoke` (the iOS rasterizer smoke):
```sh
java -cp out Bench
java -cp out GlyphSmoke /path/on/device/out.png   # pull the PNG off and eyeball it
```

**Negative control — unattached (no StikDebug):** expect HotSpot to either crash on first codegen (AMFI kill on
RWX/JIT pages → SIGILL/EXC_BAD_ACCESS) or fall back; capture whatever happens. This is the control that proves
StikDebug is what flips JIT on.

---

## [NEEDS YOUR HANDS] device setup — do these interactively, in order

These are the steps I can't do from here. Each is a gate; stop and tell me the result before the next.

**App install sources (verified 2026-05-29) — install all 3 via the dev's AltStore, except the VPN (App Store):**
| App | Role | Install source |
|---|---|---|
| LocalDevVPN | loopback VPN StikDebug requires | **App Store** id `6755608044` (`com.jkcoxson.LocalDevVPN`, Coxson Engineering) — no sideload, no expiry |
| StikDebug 3.1.3 | JIT enabler (debugger-attach → `CS_DEBUGGED`) | AltStore source `https://stikdebug.xyz/index.json` (minOS 17.4) |
| Amethyst | the oracle (Minecraft on the same `jre21-ios-aarch64`) | **No AltStore source yet** (README: "available soon"; the wiki's `alt.crystall1ne.dev` is stale). Install the **CI build artifact IPA**: `gh run download <run> -R AngelAuraMC/Amethyst-iOS -n org.angelauramc.amethyst.slimmed-ios.ipa` (the slimmed `-ios.ipa`, not `.tipa`) → AirDrop → AltStore **+**. Minecraft needs a Microsoft account or **demo mode** (fine for the oracle). |

> **TXM reality:** SideStore's *built-in* JIT enabler is non-TXM-only on iOS 26, so it's out on the 17 Pro Max.
> StikDebug (debugger-attach via pairing file + LocalDevVPN loopback) is the path; Amethyst (on the supported
> list) is the oracle that proves it works on this TXM phone.

1. Install the 3 apps above. **StikDebug 3.1.3** + **LocalDevVPN** + **Amethyst** on the iPhone 17 Pro Max
   (iOS 26.5). TrollStore is N/A on 26.5. *(Confirm StikDebug version = 3.1.3.)*

2. **Oracle prong — prove JIT on a *supported* app first.** Install **Amethyst** (it's on SideStore's iOS-26
   JIT-supported list; an arbitrary custom app is *not*, and this is a TXM device — that's the open risk we're
   isolating). Attach StikDebug to Amethyst, launch, and confirm JIT engages.
   - **If Amethyst exposes JVM args / a JRE picker:** run `JitProbe` through it and read the verdict.
   - **Else:** the coarse signal is Minecraft running at playable speed (JIT) vs a crawl (interpreter).
   - **GATE A:** Amethyst shows JIT working on this exact phone. If NO → the platform is the blocker (not our
     code); we escalate (SideJITServer, pinned config) before building anything.

3. **Fetch the JRE + pin its checksum.** Source is the **Amethyst CDN** `jre21-ios-aarch64.zip` (referenced from
   Amethyst's `Makefile`, ~line 289) — **not** a durable Pojav GitHub release, so we pin the hash. After
   download: `shasum -a 256 jre21-ios-aarch64.zip` and save the value; unzip and confirm
   `lib/server/libjvm.dylib` is HotSpot (`strings ... | grep -E 'Tiered|C1|C2|Server VM'`), `release` says
   `JAVA_VERSION="21.0.8"` / `OS_ARCH="aarch64"`, and `vtool -show lib/server/libjvm.dylib` reports platform
   `IOS`. *(Tell me where you put the unzipped JRE and I'll wire the inventory + the launch path.)*

4. **StikDebug attach ritual (for our own shell, prong B — after GATE A is green).** Per-launch:
   launch app → app waits at a "Start JVM" gate → attach StikDebug → app proceeds (`dlopen(libjli)` →
   `JLI_Launch`). **JIT must be live *before* the JVM warms** — the shell must not start the VM until attach is
   confirmed. *(I'll build this shell once the oracle + JRE are in hand; I'll flag the device build/sign step
   when we get there.)*

> Once steps 1–3 are green, ping me with the results + the JRE path. I'll wire the custom-shell launch path and
> stage the on-device run of `JitProbe`/`Bench`/`GlyphSmoke`.
