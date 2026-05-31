# Audit ask — W1 P3 §6.2 device JIT-mirror coherency (2026-05-31)

Self-contained; a reviewer needs only this doc. The §6.2 goal (run the real RuneLite headless client
on the on-device JIT JVM and capture its buffer) is **GREEN on desktop** but **crashes on device**
inside HotSpot's mirror-mapped JIT code cache under dense compilation. We've diagnosed it thoroughly;
this asks for an adversarial review of the diagnosis and the proposed fix **before re-pinning the JVM
build** (a foundational change). Evidence: `ios/app/PHASE3-6.2-DEVICE-CRASH.md` + the committed logs.

## Trust anchors (verified this session — treat as given)
- **§6.0** (CACIO CTC activation via `UnsafePokeActivation`, no agent) and **§6.1** (headful
  `java.awt.Canvas` → live `SunGraphics2D` off a `CTCGraphicsConfiguration`, Java2D + fonts render)
  are **GREEN on device**, md5-gated.
- **§6.2 desktop GREEN:** `DeviceHeadlessBoot` = `UnsafePokeActivation.activate()` then
  `RuneLiteHeadless.main("--safe-mode")` (no byte-buddy) boots the real client under CACIO CTC off the
  29 MB shaded jar and captures live-OSRS login-screen PNGs.
- **Device JVM:** Amethyst-CDN **jre21** (HotSpot, `MirrorMappedCodeCache` patch), embedded via
  `JNI_CreateJavaVM`, JIT enabled by **StikDebug** attach. `-XX:+MirrorMappedCodeCache
  -XX:ReservedCodeCacheSize=64m`, plus the §6.0 JPMS flags. Code cache is a debugger-provided **RX**
  region that HotSpot `vm_remap`s to a separate **RW** alias for writing (iOS TXM forbids RWX).

## The crash (device, real RuneLite boot)
- Run 1: `SIGILL (ILL_ILLTRP)` at `entry_point+0` of a **just-compiled** C1 nmethod (`VM.isSystemDomainLoader`)
  on its **first execution**, at t≈0.5 s. Code cache **7 % full, `full_count=0`** (no sweeping/reuse).
- Run 2 (after an unrelated patch revert): `SIGSEGV (SEGV_ACCERR @0x0)` in `nmethod::is_unloading()`
  on a **GC thread** walking the code cache, at t≈90 s.
- Both = the **RX (executable) view of freshly-written nmethods is incoherent** with the RW write
  alias under dense compilation. The crash entry word (`0xD5032069`) is not anything HotSpot emits as a
  C1 prologue → RW writes aren't reaching the RX physical pages for some nmethods.
- The light-JIT probes (Bench, §6.1) JIT-compiled and ran fine — only RuneLite's ~1400 dense,
  single-shot compiles expose it.

## What we verified against primary source (so don't re-derive)
- **i-cache is NOT the bug.** In the Pojav/AngelAuraMC patch (`patches/jre_21/ios/2_mirror_mapping.diff`,
  mirrored at `github.com/Baconium/iOS-java-25-JVM`; GPLv3, patterns only), `ICache::invalidate_range`
  → `sys_icache_invalidate` is **unconditional and targets RX** (nmethod `this` is `mirror_x(...)`=RX;
  writes go via `mirror_w(...)`=RW). So a missing/mis-targeted i-cache flush is ruled out.
- **`get_debug_jit_mapping(bytes)`** (verified by disassembling our libjvm):
  `if(MirrorMappedCodeCache){ if(DeviceRequiresTXMWorkaround()) buf_rx=BreakGetJITMapping(bytes); /*brk #0x69*/
  if(!buf_rx) buf_rx=mmap(R|X,ANON); } vm_remap(&buf_rw,buf_rx); debug_jit_mapping_mirrored=buf_rw; return buf_rx;`
  On this iPhone 17 Pro Max / iOS 26.5, **`DeviceRequiresTXMWorkaround()` returns TRUE** (device log
  shows `[JIT26] … brk #0x69` even with our gate-NOP patch reverted). ⇒ the device takes the brk path
  regardless, and **Amethyst (same device) also uses `brk #0x69`** → the brk/`_M` path is proven-capable
  (the Phase-1 oracle ran Minecraft's heavier JIT on it). **So the path is fine; our SERVICING is the
  defect.**
- **Protocol mismatch (the lead):** StikDebug's maintained `universal.js` shows the **current** JIT
  protocol is **`brk #0xf00d` + an x16 command** (`JIT26PrepareRegion`); **`brk #0x69` is LEGACY and
  now returns an error** (`0xE0000069`). Our pinned jre21 uses the legacy `BreakGetJITMapping`
  (`brk #0x69`), serviced by our **home-grown** `ios/app/stikdebug/runelite-jit.js` — a single
  `_M size,rx` + one `prepare_memory_region` over the reserved 64 MB. That single-shot legacy servicing
  holds for light JIT but the mirror goes incoherent under dense compilation.
- **No interpreter escape hatch:** JIT cannot be disabled on this libjvm — both `-Xint` and
  `-XX:-UseCompiler` fail VM init with `Failed setting boot class path` (the MirrorMappedCodeCache
  build requires the JIT mirror active at init). So §6.2 MUST repair the mirror, not bypass it.

## The proposed fix (what we want adjudicated)
**Option A (recommended): align with the current Amethyst JIT stack.** Re-pin the **current** Amethyst
jre21 (the `brk #0xf00d` build the Phase-1 oracle's full-Amethyst IPA used; our pinned sha `2c405…`
predates it) and drive it with StikDebug's bundled **`universal.js`** instead of our binary patch
(`patch-jre-txm.sh`) + custom `runelite-jit.js`.
**Option B: keep the pinned jre, fix the legacy `brk #0x69` servicing** in `runelite-jit.js` for
coherency.

### Adjudicate (verdict + reasoning each)
1. Is the diagnosis correct — is this our **legacy single-shot `_M`+`prepare_memory_region` servicing**
   failing to keep the RW↔RX mirror coherent under dense compilation? Or is there a **simpler cause**
   we've missed (e.g. `ReservedCodeCacheSize=64m` too small for the mirror; the 64 MB reserved region
   committed incrementally so later pages aren't shared by `vm_remap`; a G1 + code-cache interaction;
   `prepare_memory_region` needing per-committed-region calls rather than one up-front call)?
2. **Option A vs B.** Is re-pinning to the current Amethyst jre (`brk #0xf00d` + `universal.js`) the
   right call, given Amethyst runs heavier JIT on this exact device? What does the current protocol's
   `JIT26PrepareRegion` do that the legacy `brk #0x69` path doesn't (note its `x0!=0` branch
   "prepare THIS region" — does HotSpot call it per-committed-region, which would explain why a single
   up-front `prepare` is insufficient)? Minimum we must reproduce.
3. **Regression risk of re-pinning the jre.** Could the current Amethyst jre21 break what's already
   GREEN — the §6.0/§6.1 activation, the `JNI_CreateJavaVM` embedding (vs `JLI_Launch`), the
   `increased-memory-limit`/signing, or the `-XX:-UseCompressedClassPointers` free-cert constraint?
   What must we re-verify?
4. **Cheaper interim?** Before a jre re-pin: would simply **enlarging `ReservedCodeCacheSize`** (so the
   mirror maps a region the dense compile never grows/recommits past), pre-faulting/committing the full
   region before `vm_remap`, or a `prepare_memory_region`-per-region tweak in `runelite-jit.js` plausibly
   fix coherency on the *legacy* path? I.e., is there a low-risk fix that avoids swapping the JVM?

## Process caveat (important)
This libjvm/JIT-mirror area is exactly where a prior session committed **false GREENs** (fabricated/
duplicated shell output mistaken for success). Trust only: the **verbatim device `jvm-stdio.log` /
`hs_err` pulled via `devicectl`**, and **bash-computed, md5-gated** verdicts. The desktop §6.2 GREEN and
the device crash signatures (SIGILL nmethod-first-exec; SIGSEGV in `is_unloading`) are the trustworthy
anchors. GPLv3 sources (Pojav patch, `universal.js`) are reference-only — patterns, not vendored code.
