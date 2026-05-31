# W1 P3 §6.2 — device crash: JIT mirror i-cache coherency (2026-05-31)

First on-device boot of the **real RuneLite headless client** (`DeviceHeadlessBoot` = UnsafePoke CTC
activate() + `RuneLiteHeadless.main --safe-mode`, shaded client jar bundled) reached CTC activation
and started the client, then **SIGILL'd during boot**. This is a **W1 platform** issue (the
mirror-mapped JIT code cache), NOT a §6.2 packaging issue — activation + the render path are
desktop-proven and §6.0/§6.1 are device-GREEN.

## Evidence (pulled `jvm-stdio.log` + `hs_err_pid19277.log`, local link)
```
[boot] graphicsenv = ...ctc.CTCGraphicsEnvironment      ← CTC active (as §6.0)
[boot] launching RuneLiteHeadless --safe-mode
SIGILL (si_code=2 ILL_ILLTRP) at pc=entry_point+0 of nmethod 1422
  J 1422 c1 jdk.internal.misc.VM.isSystemDomainLoader (17 bytes)   ← C1, compiled at t=0.506
CodeCache: size=65536Kb used=4403Kb max_used=4407Kb free=61132Kb nmethods=1413 full_count=0
```

## Diagnosis
- The code cache is **7% full with NO sweeping** (`full_count=0`) → not churn/reuse.
- The faulting method was **just compiled and crashed on its FIRST execution at entry+0**. The
  in-memory bytes at the entry are a valid A64 prologue, yet the CPU trapped `ILL_ILLTRP`.
- ⇒ the **executable (RX) view of a freshly-written nmethod is incoherent**: HotSpot writes the
  nmethod to the RW mirror and invalidates the i-cache for the **RW** VA, but the CPU fetches from
  the **RX** alias, whose i-cache line (pulled in by an adjacent nmethod — 1417/1421 sit a few KB
  away in the same region) is stale. First execution → stale/garbage instruction → SIGILL.
- **Why probes passed, RuneLite fails:** the probes (Bench/§6.1) compile few, far-apart methods and
  re-run the *same* hot ones (lines fetched correctly once, then reused). RuneLite boot compiles
  ~1400 methods densely and runs each once → high chance of executing an RX line that a neighbor
  already polluted. Pure volume/density.

## Why this is FIXABLE (the key cross-check)
Amethyst runs **Minecraft** on this same device + jre21 (Phase-1 oracle GREEN) with *far* heavier JIT
than RuneLite boot. So a mirror-mapped code cache CAN sustain dense compilation here. ⇒ our
replication of Amethyst's iOS-26 JIT setup is **missing an i-cache-coherency step** that Amethyst's
native path (`JavaLauncher.m` / `utils.m` JIT26 + its libjvm handling) performs. The mechanism here:
`runelite-jit.js` services `brk #0x69` → `_M size,rx` + `prepare_memory_region`, returns the RX addr;
HotSpot `vm_remap`s the RW alias. The gap is i-cache invalidation on the **RX** VA after each nmethod
write.

## Candidate fixes / experiments (cheapest first)
1. **Confirm + isolate with `-Xint`** (pure interpreter, no code cache): if RuneLite boots + renders
   the login screen interpreted, it proves the device render path end-to-end AND pins the crash to the
   JIT code-cache mirror. (Caveat: interpreted boot may be very slow.)
2. **`-Xbatch` / `-XX:-BackgroundCompilation`** (synchronous compile): probes whether it's a
   cross-thread write→execute race on the RX alias; possible cheap workaround if so.
3. **Real fix — i-cache on the RX alias.** Ensure the RX VA's i-cache is invalidated after nmethod
   writes. Options to investigate: does the patched libjvm's `ICache::invalidate_range` /
   `MirrorMappedCodeCache` flush the RX mapping? Did `patch-jre-txm.sh` (NOPing the TXM gates) route
   us onto a path that skips Amethyst's RX i-cache step? Compare to Amethyst's native JIT setup
   (GPLv3 — patterns only). May need a `prepare_memory_region`/remap tweak in `runelite-jit.js` or a
   second libjvm patch.

## Status
§6.2 device build is installed (shaded jar + DeviceHeadlessBoot, commit 907e49588). The blocker is
the mirror i-cache coherency above. Activation (§6.0), headful Canvas (§6.1), and the full
desktop RuneLite render under UnsafePoke CTC are all proven — this is the one remaining device gap.
