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
the mirror coherency above. Activation (§6.0), headful Canvas (§6.1), and the full desktop RuneLite
render under UnsafePoke CTC are all proven — this is the one remaining device gap.

---

## RESEARCH UPDATE (2026-05-31, primary-source-verified) — i-cache theory REFUTED; real cause = our forced brk/_M mirror
Read the actual Pojav/AngelAuraMC OpenJDK patch (`…/patches/jre_21/ios/2_mirror_mapping.diff`) +
disassembled our own libjvm. Corrections to the diagnosis above:

- **The i-cache flush is NOT the bug.** In the patch, `ICache::invalidate_range` →
  `sys_icache_invalidate` is **unconditional** and the call sites pass **RX** addresses (the
  nmethod/blob `this` is constructed at RX via `mirror_x(...)`; writes route to the RW alias via
  `mirror_w(...)`). So the RX VA *is* flushed. My "RW-flushed-but-RX-stale" theory was wrong.
- **But the RX view really is incoherent:** the faulting entry word is `0xD5032069` — a system/MSR
  encoding HotSpot never emits as a C1 verified-entry first instruction → the correct nmethod bytes
  HotSpot wrote to RW did not land in RX's physical page. So the **RW↔RX mirror is not aliasing the
  same physical memory** for that page.
- **The divergence from Amethyst (the key finding):** `get_debug_jit_mapping(bytes)` is:
  ```
  if (MirrorMappedCodeCache) {
      if (DeviceRequiresTXMWorkaround()) buf_rx = BreakGetJITMapping(bytes);   // brk #0x69 (debugger _M)
      if (!buf_rx) buf_rx = mmap(NULL, bytes, PROT_READ|PROT_EXEC, MAP_PRIVATE|MAP_ANON, -1, 0);
  } else return NULL;
  vm_remap(... &buf_rw, buf_rx ...); vm_protect(buf_rw, RW);
  debug_jit_mapping_mirrored = buf_rw;  return buf_rx;   // RX = code cache, RW = write alias
  ```
  `DeviceRequiresTXMWorkaround()` returns **false for a sandboxed app** (can't read the TXM img4), so
  **Amethyst takes the `mmap(R|X)` + `vm_remap` path** (relying on StikDebug CS_DEBUGGED to make
  `mmap(R|X)` executable). Amethyst runs Minecraft's far-heavier JIT on this path with no SIGILL.
- **We binary-patched both gates to NOP (force `brk #0x69`)** → we use the **debugger-`_M` region +
  `vm_remap`** path instead. That mirror held for the low-volume probes but goes **incoherent under
  RuneLite's dense compilation** — i.e., `vm_remap` of a StikDebug `_M`/`prepare_memory_region` JIT
  region does not robustly share physical pages with the RW alias the way an `mmap`'d anonymous region
  does. (The prior session forced brk because it found `mmap(R|X)` "not executable" — that finding
  must be reconciled; likely the attach/JIT-enable state differed then.)

### Recommended experiment (next, needs a device run)
**Align with Amethyst's proven path:** revert the brk-forcing patch (restore `0x81fc80`=`61080054`
`b.ne`, `0x81fc8c`=`e0010034` `cbz w0`) so `get_debug_jit_mapping` takes the `mmap(R|X)`+`vm_remap`
path under our StikDebug attach (which sets CS_DEBUGGED via `vAttach`). The mmap fallback is intact in
our binary (verified at `0x81fcc8`). Then re-run RuneLite:
- If `mmap(R|X)` is now executable under our attach → coherent mirror → dense JIT should hold (Amethyst
  parity). If it instruction-aborts immediately (the old finding), the brk/_M path is required and the
  coherency fix must instead target the `_M`+`vm_remap` aliasing (investigate `prepare_memory_region`
  semantics / whether the whole 64 MB must be pre-faulted/committed before `vm_remap`).
- Keep a `runelite-jit.js` that still services any `brk #0x69` (harmless if unused) and does the
  standard JIT-enable attach. Bisect with `-Xint` first if we want to bank "renders interpreted on
  device" cheaply.

Net: this is a W1 JIT-mechanism choice (debugger-`_M` mirror vs Amethyst's `mmap`+`vm_remap` mirror),
not an i-cache flush bug. The cheapest path to parity is to stop forcing brk and use Amethyst's path.
