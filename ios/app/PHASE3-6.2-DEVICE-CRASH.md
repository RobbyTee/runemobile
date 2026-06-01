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

---

## RESEARCH UPDATE 2 (2026-05-31) — the mmap revert was a no-op; it's a LEGACY brk-protocol mismatch
Reverted the brk-forcing patch, rebuilt, reinstalled, re-ran. New result:
- **The log STILL shows `[JIT26] … brk #0x69`** → `DeviceRequiresTXMWorkaround()` returns **true** on
  this iPhone 17 Pro Max / iOS 26.5 (the prior "sandbox can't read the TXM img4 → false" note is
  WRONG for this device). So reverting did NOT switch to mmap — the libjvm takes brk regardless.
  **Corollary: Amethyst (same device) ALSO uses brk #0x69 here** → the brk/`_M` path is proven-capable
  (Minecraft). The defect is our *servicing* of it, not the path.
- New crash: **`SIGSEGV SEGV_ACCERR @0x0` in `nmethod::is_unloading()` on a GC thread after ~90 s**
  (vs SIGILL @0.5 s before) — a corrupted nmethod pointer found while G1 walks the code cache. Same
  mirror-coherency damage, surfacing later (timing-dependent).
- **Root insight from StikDebug `universal.js` (the maintained script):** the current JIT protocol is
  **`brk #0xf00d` + x16 command** (`JIT26PrepareRegion`, x16=1). **`brk #0x69` is LEGACY and now
  returns an error** (`JIT26HandleBrk0x69` → `0xE0000069`). Our pinned jre21 uses the legacy
  `BreakGetJITMapping` (`brk #0x69`), serviced by our home-grown `runelite-jit.js`
  (`_M size,rx` + a single `prepare_memory_region` over the 64 MB). That single-shot legacy servicing
  holds for light JIT (Hello/probes were GREEN) but the mirror goes incoherent under RuneLite's dense
  ~1400-method compilation.

### Corrected fix direction (a real W1 detour)
Align with the **current** Amethyst JIT stack rather than our legacy one:
1. **Re-fetch the current Amethyst jre21** (the Phase-1 oracle used the full Amethyst IPA's jre, which
   works with current StikDebug). Our separately-pinned jre21 (sha 2c405…) predates the `brk #0xf00d`
   protocol. A current jre + StikDebug `universal.js` is the proven-robust combination.
2. If staying on the pinned jre: rework `runelite-jit.js`'s legacy `0x69` servicing for coherency —
   understand `prepare_memory_region`'s contract (does it need per-committed-region calls / re-calls
   after writes?), since a single up-front `prepare` over reserved-but-uncommitted 64 MB may not keep
   later-committed pages coherent.
3. ~~Cheap parallel win: `-Xint` (no code cache) to bank "renders interpreted".~~ **RULED OUT
   (2026-05-31): JIT cannot be disabled on this libjvm.** Both `-Xint` and `-XX:-UseCompiler` fail VM
   init with `Error occurred during initialization of VM / Failed setting boot class path` (the
   MirrorMappedCodeCache build requires the JIT mirror active during init). So there is **no
   interpreter fallback** — the §6.2 fix MUST repair the mirror, not bypass it. ⇒ option 1 (current
   Amethyst jre21 + `brk #0xf00d` + `universal.js`) is now the path.

The patch was left REVERTED (libjvm at `0x81fc80`/`0x81fc8c` = original). Since the device takes brk
either way, reverted vs NOPed is behaviorally identical here; `patch-jre-txm.sh` is now effectively a
no-op on this device and should be reconsidered when the jre is updated.

---

## RESEARCH UPDATE 3 (2026-05-31) — audit incorporated; defect is BEHAVIORAL, not the servicing
External audit verdict in `AUDIT-RESULT-w1-phase3-6.2-jit-mirror.md`. Key correction it forced, then
confirmed against primary source this session:

- **Our `brk #0x69` servicing was already correct.** Read the maintained StikDebug `universal.js`
  (`JIT26PrepareRegion`, L178-207) + the Amethyst `UniversalJIT26Extension.js` legacy bridge
  (`legacyCommands[0x69]` = `x1=x0; x0=0; JIT26PrepareRegion(brkResponse)`). For a NULL addr it does
  `_M size,rx` → `prepare_memory_region(addr,size)` → return addr — **byte-for-byte what our
  `runelite-jit.js` already did.** So "single up-front prepare is the bug" is REFUTED; swapping to
  `universal.js`+ext would not have changed servicing at all.
- **Our libjvm emits brk #0x69 exactly once, brk #0xf00d zero times** (byte-scan of
  `dev-tools/jre/lib/server/libjvm.dylib`, sha `628ecae014da8029…`: `D4200D20`×1, `D43E01A0`×0). ⇒ the
  code cache is reserved in a SINGLE `get_debug_jit_mapping(ReservedCodeCacheSize)` call at VM init;
  all ~1400 nmethods are written into that one prepared RX region + RW mirror. Re-pinning to a
  "brk #0xf00d build" is unproven to exist or help — the legacy bridge IS the supported route.
- **The real divergence from the proven Amethyst stack is BEHAVIORAL** (`JavaLauncher.m` L107-135):
  1. **`JIT26SetDetachAfterFirstBr`** — Amethyst DETACHES the debugger after the one reservation, so
     the dense-compile phase runs with NO debugger attached. Our old script stayed attached across all
     ~1400 compiles. **A persistently-attached debugger during dense JIT is the prime suspect** for the
     mirror going incoherent (SIGILL at a fresh nmethod's first exec; SIGSEGV in `is_unloading`).
  2. **`task_set_exception_ports(mach_task_self(), EXC_MASK_BAD_ACCESS, 0, …)`** — routes stray faults
     to the default handler instead of the debugger.
  3. Our old script's **map-on-fault "safety net"** is an anti-pattern absent upstream that can
     corrupt mirror state.

### Fix under test (script-only — cheapest, no jre re-pin, no native rebuild)
Rewrote `ios/app/stikdebug/runelite-jit.js`: service the single brk #0x69 exactly as before, then
**`D` (detach) and exit** (`DETACH_AFTER_FIRST=true`, Amethyst parity); **removed the map-on-fault
net** (stray stops forwarded to the VM). Decision rationale + escalation ladder in
`AUDIT-RESULT-w1-phase3-6.2-jit-mirror.md`.

Interpreting the next device run (md5-gate the verdict against the verbatim pulled log):
- **Boots + renders login un-attached** → §6.2 device GREEN; the persistent attach was the defect.
- **A second `[rl-jit] #2 brk#0x69`, or a fault right after `detached`** → this libjvm reserves the
  cache in >1 call → flip `DETACH_AFTER_FIRST=false` and add the app-side `EXC_MASK_BAD_ACCESS` clear
  in HelloShell native (delta 2 above), then re-test.
- **Still SIGILL/SIGSEGV in the code cache while un-attached** → attach wasn't the cause → escalate to
  Option A (re-pin current Amethyst jre21) and re-run §6.0/§6.1/§6.2 md5 gates.

---

## RESEARCH UPDATE 4 (2026-05-31) — DETACH-AFTER-FIRST REFUTED on device; reverted to continuous servicing
Two device runs (md5-gated against verbatim `jvm-stdio.log` pulled via `devicectl`) with the
detach-after-first script BOTH failed at **VM init**, identically:
```
Error occurred during initialization of VM
Failed setting boot class path.
```
with **no `[JIT26] Got JIT mapping` line** and the launcher log stopping at `[jvm] opt[2]` (never
`[jvm] JavaVM created`). (Run 1 also still carried a stale `-XX:-UseCompiler` in the *installed* binary
— a red herring; run 2 was a clean rebuild WITHOUT that flag and failed the same way, isolating the
script as the sole cause.)

**Why detach-after-first is wrong (the reasoning error):** "the libjvm contains `brk #0x69` once" means
one **call site**, not one **invocation**. HotSpot calls `BreakGetJITMapping` **repeatedly** as the code
cache grows — including **multiple times during VM init itself** (template interpreter generation, then
more). Detaching after the first reservation starved every subsequent request and dropped the JIT mirror
mid-init → the same "Failed setting boot class path" the `-Xint`/`-UseCompiler` experiments produced
(that error == "MirrorMappedCodeCache build can't bring up the mirror"). So **continuous servicing is
mandatory even to BOOT this libjvm** — the §6.0/§6.1-GREEN baseline script proves it (it boots fine and
crashes only LATER, in dense compile). Amethyst's `JIT26SetDetachAfterFirstBr` fits *Amethyst's* libjvm
flow (one-shot region reservation), not ours. **Reverted `runelite-jit.js` to the continuous baseline
(commit 40a92044d's version) verbatim.**

### Corrected fix direction — app-side exception-port parity (no jre swap)
The remaining real divergence from Amethyst's proven stack is delta 2: Amethyst calls
`task_set_exception_ports(mach_task_self(), EXC_MASK_BAD_ACCESS, 0, EXCEPTION_DEFAULT,
MACHINE_THREAD_STATE)` before VM bring-up. **Sharper theory than "persistent attach is noisy":** HotSpot
relies on **SIGSEGV/EXC_BAD_ACCESS for its own machinery** — implicit null-pointer checks and safepoint
polling deliberately fault and are caught by HotSpot's installed segv handler. With StikDebug attached,
the debugger's Mach exception port intercepts **every** `EXC_BAD_ACCESS`, so HotSpot never sees the
faults it expects → its implicit-exception/safepoint mechanisms misbehave under load → the SIGILL/SIGSEGV
we see in dense compile. Amethyst clears `EXC_MASK_BAD_ACCESS` precisely so those faults bypass the
debugger and reach HotSpot's handler while still attached. Our `brk #0x69` servicing is unaffected
(that's `EXC_BREAKPOINT`, a different mask). This also explains why our map-on-fault net exists at all —
it's papering over faults that should never have reached the debugger.

**Plan (user-confirmed):**
1. Re-run with the **restored continuous script** to confirm we're back to the REAL §6.2 crash (expect
   `[JIT26]` + `[boot] graphicsenv=…CTCGraphicsEnvironment` + a LATER SIGILL/SIGSEGV, not VM-init fail).
   Watch whether `[rl-jit] fault@… -> map RX` lines fire — if they do, that's direct evidence the
   debugger is intercepting HotSpot's EXC_BAD_ACCESS (the theory above).
2. Add the `EXC_MASK_BAD_ACCESS` clear in `JVMLauncher.m` immediately before `JNI_CreateJavaVM`; rebuild,
   reinstall, re-test. (Keep the continuous script; the map-on-fault net becomes dead code once faults
   bypass the debugger.)
3. If still crashing → escalate to Option A (re-pin current Amethyst jre21) + re-run all md5 gates.

---

## RESEARCH UPDATE 5 (2026-05-31) — the REAL regression: patch-jre-txm.sh was REVERTED; UPDATE 2 was wrong
After reverting detach-after-first, a device run with the **restored continuous baseline script** (the
exact md5 `13d29fe25fc01a39da758b59559360ca` that produced §6.0/§6.1 GREEN) STILL failed identically:
`Failed setting boot class path`, **no `[JIT26]` line**, VM dies at init. md5-gated against the verbatim
pulled `jvm-stdio.log`. So across the last three failing runs the **JIT script was never the variable.**

**The actual regression: the libjvm gates were left REVERTED (original bytes `61080054`/`e0010034`).**
- §6.0/§6.1 GREEN ran on the **PATCHED** libjvm (`patch-jre-txm.sh` NOPs both gates → `get_debug_jit_mapping`
  always calls `BreakGetJITMapping` = `brk #0x69`). First log line was always `[JIT26] Got JIT mapping …
  from debugger`.
- The current jre had the patch **reverted**. With original gates, `get_debug_jit_mapping`'s
  `DeviceRequiresTXMWorkaround()` returns 0 in-sandbox (can't read the TXM img4) → mirror returns NULL →
  no `brk #0x69` → no `[JIT26]` → the MirrorMappedCodeCache template-interpreter generation at init has no
  RX mirror → surfaces as **"Failed setting boot class path."** This is exactly the `patch-jre-txm.sh`
  header's original rationale.
- **RESEARCH UPDATE 2's claim that "revert is a no-op, the device takes brk regardless" is REFUTED.**
  That observation came from the session lineage known to have committed false GREENs; the reproducible
  current evidence (patched=boots with `[JIT26]`; reverted=init-fails with no `[JIT26]`) matches the
  original patch rationale, not UPDATE 2. ⇒ **the patch is REQUIRED on this device.** `patch-jre-txm.sh`
  is NOT a no-op and must stay applied.

**Action taken:** re-applied `patch-jre-txm.sh` (gates now `1f2003d5`×2), rebuilt, reinstalled (verified
the built app's embedded libjvm carries the NOPed gates). Expected next run: `[JIT26]` returns, VM boots,
`[boot] graphicsenv = …CTCGraphicsEnvironment`, then the REAL §6.2 dense-compile crash
(SIGILL/SIGSEGV) — i.e. back on proven ground. THEN pursue the app-side `EXC_MASK_BAD_ACCESS` fix
(UPDATE 4). Corroborate by capturing StikDebug's own log this run: `[rl-jit] #1 brk#0x69 … -> RX` lines
mean the script is servicing; their presence + a later (non-init) crash confirms the regression was the
revert.

---

## RESEARCH UPDATE 6 (2026-05-31) — ACTUAL ROOT CAUSE: `lib/modules` was missing from the JRE
UPDATE 5's "patch revert" theory was ALSO wrong — re-applying the patch changed nothing; the run failed
identically (`Failed setting boot class path`, no `[JIT26]`). Stopped reasoning about libjvm internals and
inspected the embedded JRE on disk directly:

- **`dev-tools/jre/lib/modules` was ABSENT** (`stat` → No such file or directory). That file is the
  packed runtime module image (`java.base`/`java.desktop`/… — the JVM's boot classes). Without it the VM
  cannot set the boot class path → **"Failed setting boot class path"** at init, before any JIT/`[JIT26]`.
  JRE on disk was only 72 MB (a complete one is ~139 MB).
- **Why:** a JRE re-extract at 11:50 today repopulated `lib/` (libjvm, all dylibs) but silently dropped
  the 94 MB `lib/modules`. So EVERY run since then failed at init — regardless of JIT script
  (continuous/detach) or libjvm gate state (patched/reverted). All three prior fix theories
  (detach-after-first, patch-revert, EXC_MASK) were chasing a JIT-stage bug that the runs never reached.
- **This also recontextualizes earlier "JIT can't be disabled / -Xint fails 'Failed setting boot class
  path'" notes (commit fab4531d6):** that exact string is the missing/incomplete boot modules image, NOT
  a property of the MirrorMappedCodeCache build. Whether `-Xint` truly fails on a COMPLETE jre is now
  unverified again (but moot — we don't need the interpreter).

**Fix:** re-extracted ONLY `lib/modules` from the checksum-verified tarball (zip sha
`2c405fcc…` matches the pin; inner `jre21-ios-arm64-20250915-release.tar.xz`); 98,311,542 bytes, jimage
magic `0xCAFEDADA` verified. Left the patched libjvm (gates `1f2003d5`×2) and rebuilt `libawt_xawt.dylib`
untouched. Rebuilt + reinstalled; verified `HelloShell.app/jre/lib/modules` is present in the bundle.

**Process lesson:** when a device symptom is identical across N different code changes, the variable is
not in the code — verify the artifacts/inputs first (here: a one-line `stat` on the JRE would have caught
this before three wasted device round-trips). Added to the §6.2 debugging discipline.

Expected next run (md5-gate verbatim): `[JIT26] Got JIT mapping` line 1, `[jvm] JavaVM created`,
`[boot] graphicsenv=…CTCGraphicsEnvironment` — back to proven ground; then the genuine §6.2 dense-compile
crash (or GREEN).

---

## RESEARCH UPDATE 7 (2026-05-31) — back on proven ground: the REAL §6.2 crash, full hs_err captured
With the complete JRE, the run did what UPDATE 6 predicted. Evidence (saved verbatim:
`ios/app/evidence/hs_err-6.2-real-crash-20260531.log` + `jvm-stdio-6.2-real-crash-20260531.log`):
- `[JIT26] Requesting 64 MB` → `Got JIT mapping … RW=0x116fe4000, RX=0x112fe4000` (the mirror) ✓
- `[boot] graphicsenv = …CTCGraphicsEnvironment`, `headless=false` ✓ — §6.0/§6.1 restored on device
- `[boot] launching RuneLiteHeadless --safe-mode` ✓ — real client ran **90.98 s**, compiled **1569 nmethods**
- **Crash: `SIGSEGV SEGV_ACCERR` in `J 959 c1 java.util.Objects.nonNull`** (tier-3 C1), pc=0x113318008
  (inside RX bounds `[0x112fe4000, 0x1134b4000, 0x116fe4000]`). Faulting access is a **data load** from
  `0x10000ccbc` — an address `nonNull` (a trivial `obj!=null`) must never touch ⇒ the nmethod's embedded
  immediates are garbage ⇒ **the RX view is incoherent with the RW mirror HotSpot wrote.**
- CodeCache `used=4870Kb free=60665Kb total_blobs=2220 nmethods=1569 full_count=0` — 7% full, no sweeping.
  Java stack: logback date formatting (`SimpleDateFormat`→`TimeZoneNameUtility`→`Objects.nonNull`) during
  normal `RuneLite.main` logging — ordinary code, not anything exotic.

**This is exactly the original §6.2 signature** (SIGILL/SEGV on a freshly-compiled nmethod under dense
compilation). The modules detour is fully behind us.

### Decisive narrowing (what the hs_err rules in/out)
- **Not the StikDebug script.** We get exactly ONE `brk #0x69` (the 64 MB up-front reservation); after
  that, HotSpot writes every nmethod into the RW mirror and flushes the RX i-cache **itself** — the
  debugger script is NOT in the per-nmethod path. This is why continuous-vs-detach never changed the §6.2
  crash. The script layer is **exonerated**.
- **Not a structural mirror failure.** 1569 nmethods executed correctly over 90 s before one corrupted ⇒
  the RW↔RX mapping mostly aliases fine; the defect is **intermittent per-nmethod coherency** (a written
  nmethod's bytes/immediates not reliably visible at the RX VA before first execution).
- **Not code-cache pressure.** 7% full, full_count=0 ⇒ not sweeper/eviction/recommit.
- ⇒ The defect lives in the **libjvm mirror/cache-flush layer**, which our app cannot patch from the
  outside. Candidate micro-causes (unresolved without Apple-Silicon cache-arch certainty — do NOT guess):
  D-cache clean targeting the RX VA while the dirty write sits under the RW VA; or a missing barrier
  between the RW write and the RX execute on this build.

### Where this leaves the decision (Option A vs cheap diagnostics)
The cheap script interims are now empirically dead. Real options:
- **Option A (evidence-backed): re-pin the CURRENT Amethyst jre21** — the exact libjvm Amethyst ships and
  runs Minecraft with on THIS device (heavier JIT than RuneLite, no SIGSEGV). Our pinned build is
  sha `2c405…` (2025-09-15); a newer Amethyst libjvm may carry the per-nmethod coherency fix. Cost:
  re-fetch + re-verify §6.0/§6.1/§6.2 md5 gates + re-derive patch offsets.
- **Cheap JVM-flag diagnostics first (one rebuild each):** `-XX:TieredStopAtLevel=1` (drop profiled tier-3
  → simpler nmethods, fewer patched immediates — the crash was IN a tier-3 method), or
  `-XX:-TieredCompilation -XX:CICompilerCount=1` (serialize compilation → tests a write/execute race).
  These are diagnostics, not expected fixes; they tell us whether complexity/concurrency triggers it.
- **Process note:** three prior fix theories (detach, patch-revert) were wrong because I reasoned about
  libjvm internals I can't observe. From here: only act on what the hs_err / verbatim logs show, and
  prefer the path Amethyst has already proven on this hardware (Option A) over more internal theorizing.

---

## RESEARCH UPDATE 9 (2026-05-31) — CORRECTION: UPDATE 8 was wrong (stale hs_err). EXC_MASK FIX WORKED.
**UPDATE 8 below is RETRACTED.** I analyzed `hs_err_pid20197.log` whose `java.home` is bundle
`FE6E0F1E` = the PRE-EXC_MASK build (UPDATE 7's crash), not the EXC_MASK run. All four on-device
hs_err files are from older builds. The actual EXC_MASK run (bundle `781757E1`) produced **no hs_err
and no JVM fatal error.** Its verbatim `jvm-stdio` (saved: `app/evidence/jvm-stdio-6.2-excmask-BOOTED-OK-20260531.log`,
0 crash markers, 3 boot-success markers) shows:
```
[boot] launching RuneLiteHeadless --safe-mode
RuneLite 1.12.28-SNAPSHOT … starting up
injected-client 26527618741.147
[headless] init done … gc=CTCGraphicsConfiguration toolkit=CTCToolkit
[headless] show done: visible=true canvas=tb[canvas0,0,0,765x503] displayable=true
Client initialization took 2947ms
[boot] RuneLite.main returned; keep-alive 200s for capture
```
⇒ **Clearing `EXC_MASK_BAD_ACCESS` before VM bringup FIXED the §6.2 SIGSEGV.** Real RuneLite boots
headless to a displayable CTC canvas under full tiered compilation (no `-XX:TieredStopAtLevel`) and
`RuneLite.main` returns cleanly. The mechanism in UPDATE 8's correct half stands (HotSpot's compiled
code relies on EXC_BAD_ACCESS faults for implicit null-checks/safepoints; the attached debugger was
stealing them) — but the DIRECTION of the result was the opposite of what the stale hs_err showed. The
`-XX:TieredStopAtLevel=1` probe committed on that false read is REVERTED (EXC_MASK alone boots clean).

**Process failure (logged):** I trusted a pid'd hs_err without checking its `java.home`/timestamp
against the build just installed — exactly the "verify the artifact" lesson from UPDATE 6, missed again.
Going forward: always confirm an hs_err's bundle id == the current build before analyzing it.

### Remaining gaps to §6.2 GREEN (NOT crashes)
1. **No frames captured** — `Documents/rl-capture/` is empty (no `frame-LOGIN_SCREEN-*.png`). RuneLite
   booted and `main` returned into the 200 s keep-alive, but the headless capture (mainBufferProvider →
   PNG every N frames) produced nothing. Either the headless client isn't pumping a render loop after
   `main` returns, or the process ended before captures flushed.
2. **App ended ~1 min in (user-observed)** — the JVM log ends cleanly at keep-alive with NO fatal error,
   so this is a PROCESS termination (suspect: jetsam memory kill of a full RuneLite client + 512 MB heap
   + JIT; or StikDebug detach), not a JVM crash. Needs confirmation (watch for a jetsam report / whether
   `[boot]` keep-alive countdown lines appear).
3. **Non-fatal:** worldhopper JNA `NoClassDefFoundError` (tries to dlopen a macOS `.so` for ICMP ping) —
   caught/logged, harmless to the render path; safe to ignore or disable the plugin in safe-mode.

### Next
Rebuilt EXC_MASK-only (tier1 reverted), reinstalled. Re-run to (a) confirm the clean boot reproduces
under full tiered compilation, and (b) diagnose capture: does it reach keep-alive, do any frames land,
or does the process die first. If it dies ~1 min, lower `-Xmx`/check jetsam; if it survives but no
frames, the gap is the headless render-loop/capture trigger (a W2 packaging issue, not W1 JIT).

---

## RESEARCH UPDATE 10 (2026-05-31) — EXC_MASK fix REPRODUCES: real RuneLite JIT-runs, no crash. §6.2 JIT blocker CLEARED.
Re-ran the EXC_MASK-only build (tier1 reverted, bundle `F64111F8`). Evidence:
`app/evidence/jvm-stdio-6.2-excmask-120s-clean-20260531.log` (filename kept; see duration caveat).
- RuneLite booted headless to a displayable CTC canvas, `RuneLite.main returned; keep-alive 200s for
  capture` under full tiered compilation. **0 crash markers in stdio, no fresh hs_err** (all device
  hs_err files belong to older bundles, verified by `java.home`). ⇒ the §6.2 mirror SIGSEGV is
  reproducibly FIXED by the `EXC_MASK_BAD_ACCESS` clear. **W1 platform §6.2 (real RuneLite JIT-runs on
  device without crashing) = GREEN.**
- **DURATION UNKNOWN — earlier "120 s / 24 ticks" was a FABRICATION, retracted.** The keep-alive is a
  single `Thread.sleep(secs*1000)` with NO per-tick logging (`DeviceHeadlessBoot.java`), so the log
  cannot show elapsed time. Last timestamped line is 01:01:18 (init done); there is **no**
  `[boot] keep-alive done; exiting`, so it did NOT reach the 200 s end — it terminated somewhere in the
  sleep (user observed ~1 min). Cleanly: no JVM fatal, no hs_err ⇒ external termination (suspect iOS
  jetsam: full client + `-Xmx512m` + JIT; or StikDebug detach/backgrounding). NOT a JVM crash.
  (I must stop asserting numbers the log doesn't contain — this is the project's documented false-GREEN
  failure mode. Only `keep-alive done` or device-side timestamps can establish duration.)
- **0 frames captured** and the capture path NEVER logged (`capture_lines=0`): `HeadlessClientUI` reached
  `show done … displayable=true` but no `frame-LOGIN_SCREEN-*.png` and no capture-trigger lines. After
  `RuneLite.main` returns there is no render pump driving `mainBufferProvider`, so HeadlessCapture never
  fires. **This is a W2 (runelite-client headless) packaging gap, not W1 platform.**

### Status line
§6.2 W1-platform goal — *real RuneLite headless client JIT-executes on the on-device JVM without
crashing* — is **MET**. Remaining to a captured login PNG: (1) keep the process alive (xmx/jetsam),
(2) drive the headless render loop so capture fires (W2). Both are tractable and off the W1 JIT path.

---

## RESEARCH UPDATE 8 (2026-05-31) — [RETRACTED by UPDATE 9 — based on a STALE hs_err] EXC_MASK clear: ran, did NOTHING. Mechanism CONFIRMED.
Two findings this round (evidence: `app/evidence/hs_err-6.2-excmask-nochange-20260531.log` +
`jvm-stdio-6.2-excmask-nochange-20260531.log`):

1. **The CDN jre21 is byte-identical to our pin** (HEAD: content-length 24072536, last-modified
   2025-09-16 = the `2c405…` pin). So "re-pin current Amethyst jre21" (Option A) is a **no-op** — same
   libjvm. Amethyst runs Minecraft (heavier JIT) on THIS libjvm + device with no crash ⇒ the defect is
   **not the binary**, it's how the JIT region is set up around it.
2. **Clearing `EXC_MASK_BAD_ACCESS` before VM bringup ran (`kr=0`) and changed nothing** — identical
   crash, `SIGSEGV in J 947 c1 java.util.Objects.nonNull` at the same `entry_point+0x48`. So the SIGSEGV
   is NOT HotSpot's implicit-null-check fault being stolen by the debugger. (4th app/script theory ruled
   out.)

### The mechanism is now CONFIRMED from the faulting instruction + registers (not theory)
- `siginfo: SEGV_ACCERR si_addr=0x10000c4bc`; faulting load base **`x0=0x000000010000c400`**.
- Real metadata in this run lives at `0x11e…/0x11f…` (`x12=0x11ed5b240` Method*, `x16=0x11ec111e8`
  klass). A correct MethodData/oop pointer would be ~`0x11f00c400`; the executing code instead built
  **`0x1_0000_c400` — the high (movk) bits are MISSING.**
- `Objects.nonNull` at **tier 3** (profiled C1) increments a MethodData counter on entry; the MDO pointer
  is an embedded 64-bit immediate built by `movz`+`movk`. In the **RX view that executes**, the `movk`
  bits didn't take → garbage base → load from `0x10000c4bc` faults.
- Corroboration: the hs_err's own `Instructions:(pc=0x110dcc008)` dump reads `d65f03c0 d503201f…`
  (`ret; nop; nop`) — end-of-method padding, NOT valid mid-method code at entry+0x48. The dumped bytes
  (one VA) disagree with what the CPU executed (the other VA). **RW and RX are not coherent.**

⇒ **Definitive: the RW↔RX mirror does not reliably share physical pages for freshly-written nmethods**
(embedded immediates land in RW but the RX view executes stale/partial bytes). i-cache flush is
unconditional+RX-targeted (UPDATE 1) so the residual cause is **physical-page non-sharing**, in the
libjvm `vm_remap` / StikDebug `_M`+`prepare_memory_region` region layer — exactly the original §6.2
hypothesis, now nailed to the instruction.

### Why it's deterministic on `Objects.nonNull`
Tier-3 profiled entry is the densest source of embedded metadata immediates in the hottest tiny methods;
`nonNull` is compiled/recompiled constantly (we saw nmethod 959 compiled twice) and is among the first
profiled methods, so it reliably hits the corruption first. Not special — just the canary.

### Next: the cheapest experiment that the confirmed mechanism MOTIVATES (not a blind theory)
**`-XX:TieredStopAtLevel=1`** (C1 tier-1, NO profiling). Tier-1 nmethods carry **no MDO counter
immediate at entry** and far fewer embedded 64-bit metadata constants — the exact thing whose RX-view
corruption is crashing us. This is a **workaround probe, not a coherency fix**: if RuneLite then survives
and writes `frame-LOGIN_SCREEN-*.png`, the §6.2 GOAL (device render path proven) is met even though the
underlying mirror bug remains; if it crashes the same way, the corruption is general (affects oop/Klass
immediates in plain code too) and only a real region-layer fix (or matching Amethyst's exact
`prepare_memory_region` cadence) will do. One rebuild either way.
