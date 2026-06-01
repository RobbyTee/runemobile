# Audit ask — W1 P3 §6.2: intermittent memory corruption under on-device JIT (2026-06-01)

Self-contained; a reviewer needs only this doc. Asking for an adversarial review of a **root-cause
diagnosis** and a **fix direction** before we invest in either a StikDebug-protocol rewrite or a libjvm
re-pin. The bug: real RuneLite JIT-runs on an on-device OpenJDK 21, but **intermittently corrupts memory
(code cache OR Java heap) within ~90 s and crashes**. We believe the debugger-provided JIT code-cache
region does not exclusively own its physical pages and aliases JVM heap/metaspace. We want that
confirmed or refuted, and the cheapest reliable fix identified.

## Platform (all device-proven unless noted)
- **Device:** iPhone 17 Pro Max, iOS 26.5, **TXM** (Trusted Execution Monitor — RWX forbidden; JIT needs
  a debugger to map executable pages).
- **JVM:** Amethyst-CDN **jre21** (OpenJDK 21.0.8, HotSpot, tiered C1/C2, G1 GC, `MirrorMappedCodeCache`
  patch). Embedded in a native iOS app via `JNI_CreateJavaVM` (not JLI_Launch). sha-pinned; the current
  CDN build is byte-identical to ours.
- **JIT enablement:** **StikDebug** (per-launch debugger attach → `CS_DEBUGGED`) + a loopback VPN +
  an assigned JS script (below). Pairing via iloader (remote-pairing format).
- **VM flags:** `-XX:+UnlockExperimentalVMOptions -XX:+MirrorMappedCodeCache
  -XX:+DisablePrimordialThreadGuardPages -XX:-UseCompressedClassPointers -XX:ReservedCodeCacheSize=64m
  -Xmx256m` + CACIO JPMS `--add-exports/--add-opens`. (`UseCompressedClassPointers` off is a free-cert
  constraint; heap still uses compressed oops, zero-based, base `0x7f0000000`.)
- **GREEN already (device, md5-gated):** §6.0 CACIO CTC activation; §6.1 headful Canvas → live
  SunGraphics2D/CTC; the §6.2 capture pipeline is desktop-GREEN (renders the live-OSRS login screen to
  PNG, 765×503). Process longevity is solved app-side (background-task assertion + active CADisplayLink
  render loop): the process now survives minutes.

## How the on-device JIT mirror works (verified by disassembly + the Pojav/AngelAuraMC GPLv3 patch)
HotSpot's `MirrorMappedCodeCache` build, on a TXM device, gets executable memory via a debugger:

1. libjvm `get_debug_jit_mapping(bytes)`: if `MirrorMappedCodeCache && DeviceRequiresTXMWorkaround()`,
   it calls `BreakGetJITMapping(bytes)` == `brk #0x69` (size in x0) and expects an **RX** address back in
   x0. (On this device the TXM gate returns true; we also binary-NOP both gates to force the brk path —
   `patch-jre-txm.sh` — but the device takes brk regardless.) It then `vm_remap`s that RX region to a
   separate **RW** alias, sets `debug_jit_mapping_mirrored = RW`, and returns RX. HotSpot writes compiled
   code through the RW alias and executes the RX view (TXM forbids a single RWX mapping).
2. The debugger services `brk #0x69`. Our **`runelite-jit.js`** (assigned in StikDebug; md5
   `13d29fe25fc01a39da758b59559360ca`) does, per request:
   ```
   size = x0
   step PC over the brk
   rx   = send_command(`_M{size},rx`)            // StikDebug: allocate an RX region of `size`
   prepare_memory_region(rx, size)               // StikDebug TXM wire-up (writes 1 byte / 16KB page)
   set x0 = rx                                    // return the RX address to HotSpot
   ```
   This is byte-for-byte the maintained StikDebug `universal.js` `JIT26PrepareRegion(NULL, size)` path
   (confirmed against current StikDebug source). The script then **stays attached** and continuously
   services any further `brk #0x69` (HotSpot reserves the whole 64 MB up front in ONE call here — our
   libjvm emits `brk #0x69` exactly once, `brk #0xf00d` zero times).
3. App-side, just before `JNI_CreateJavaVM`, we clear the task's `EXC_MASK_BAD_ACCESS` Mach exception
   port (`task_set_exception_ports(…, EXC_MASK_BAD_ACCESS, MACH_PORT_NULL, …)`) — Amethyst parity, so
   HotSpot's implicit null-checks/safepoint SIGSEGVs reach its own handler instead of the debugger.
   This measurably **reduced** the crash rate (boots now often succeed) but did not eliminate it.

## The crash — TWO faces, ONE root (this is the crux of the diagnosis)
Real RuneLite boots, the engine starts, then at **t ≈ 90 s** of sustained tiered compilation it SIGSEGVs.
Across runs on the SAME build the fault location MOVES, which is the key evidence:

- **Variant A (full tiered):** `SIGSEGV SEGV_ACCERR` in `IsUnloadingBehaviour::is_unloading(CompiledMethod*)`
  ← `nmethod::is_unloading()`, main thread in VM, walking the code cache. Faulting nmethod is a
  freshly-compiled tier-3 C1 method. CodeCache 7 % full, `full_count=0` (no sweeping). I.e. a **corrupted
  code-cache pointer**. (Earlier variant: `SIGILL ILL_ILLTRP` at `entry_point+0` of a just-compiled
  nmethod on first execution — the entry word `0xD5032069` is a system/MSR encoding HotSpot never emits
  as a C1 prologue → the bytes written to RW did not land coherently in RX.)
- **Variant B (after `-XX:TieredStopAtLevel=1 -XX:CICompilerCount=1`, confirmed applied):** `SIGSEGV
  SEGV_ACCERR si_addr=0x00000003aa659bf0` in **`GC Thread#0`**:
  `G1ParScanThreadState::handle_evacuation_failure_par ← copy_to_survivor_space ← G1ParCopyClosure::do_oop_work
  ← evacuate_roots`. The fault address `0x3aa659bf0` is a **wild pointer OUTSIDE the Java heap**
  (`[0x7f0000000, 0x800000000)`). **The crash reporter then SIGSEGV'd 3× itself** while printing register
  info ("error occurred during error reporting" ×3) → memory is **broadly** corrupt, not one structure.
  I.e. a **corrupted Java heap object** that GC followed into the weeds.

**Tier-1 did not fix the bug — it relocated the corruption from code to heap.** That, plus the
reporter-crashing-itself, is why we conclude a single root: **stray writes landing in the wrong physical
memory**, scribbling on whatever happens to be there (an nmethod, or a heap oop).

## Our root-cause diagnosis (what we want adjudicated)
**The debugger-provided JIT region (`_M`/`prepare_memory_region`, then libjvm `vm_remap` to the RW
alias) does not exclusively own its physical backing pages — they alias memory the JVM also uses
(heap / metaspace / other code).** HotSpot writes a compiled method through the RW mirror; the same
physical page is also a live heap object, so one write corrupts the other. Under ~90 s of compilation a
collision becomes probable, and *something* gets smashed — code or heap. This matches the original §6.2
audit's open hypothesis ("`vm_remap` of a StikDebug `_M` region does not robustly share/own physical
pages the way an `mmap`'d anonymous region does"). The EXC_MASK fix lowering (not removing) the rate is
consistent with a probabilistic physical-collision, not a deterministic protocol error.

Observed addresses (one run): debugger gave **RX = 0x1154b8000, RW = 0x1194b8000** (a 64 MB span);
heap base **0x7f0000000**; wild GC fault **0x3aa659bf0**. The wild address is neither in the heap nor in
the RW/RX band → consistent with a *smashed* compressed-oop/markword (a corrupted object header
decompressed to garbage), i.e. downstream corruption, not a literal base-range overlap. So the collision,
if real, is at the **physical-page** level (two virtual mappings → one physical frame), not a virtual
base overlap — which is exactly what we cannot see from addresses alone and need the review to pressure.

## The oracle that says this is fixable
**Amethyst runs Minecraft (far heavier, sustained JIT) on this exact device + the byte-identical jre21
under the same StikDebug for many minutes with no such corruption.** So a debugger-mapped code cache CAN
be made non-colliding here. The difference must be in *how the region is reserved/owned*. Amethyst's
native path: `JIT26CreateRegionLegacy(getpagesize())` probe → inject `UniversalJIT26Extension.js` (which
makes legacy `brk #0x69` call `JIT26PrepareRegion(NULL, size)`) → `JIT26SetDetachAfterFirstBr(true)` →
clear `EXC_MASK_BAD_ACCESS`. Note Amethyst **detaches after the first brk** (we stay attached); we earlier
tried detach-after-first and it broke VM init on our libjvm (it calls `BreakGetJITMapping` repeatedly
during init), so naive detach is out for us.

## Questions to adjudicate (verdict + reasoning each)
1. **Is the diagnosis correct** — physical-page aliasing between the debugger JIT region and the JVM
   heap/metaspace? Or is there a **simpler/more-likely cause** consistent with BOTH a corrupt-nmethod and
   a corrupt-heap fault at ~90 s with the reporter self-faulting? Candidates to weigh: (a) the RW↔RX
   `vm_remap` is fine but **i-cache/d-cache incoherence** still corrupts on first execution (we believe
   the patch's `ICache::invalidate_range` is unconditional + RX-targeted — is that enough on Apple
   Silicon?); (b) `prepare_memory_region` only touches 1 byte/16 KB page so **later-committed pages in the
   64 MB reservation are never prepared** and fault/alias; (c) a GC-moves-code interaction with a stale
   mirror; (d) `-XX:-UseCompressedClassPointers` / heap-base choice creating a real overlap; (e) the
   continuously-attached debugger corrupting state vs Amethyst's detach.
2. **Cheapest reliable fix.** Rank: (i) make `runelite-jit.js` reserve **dedicated, fully-committed,
   non-shared** physical pages for the JIT region (how, within StikDebug's `_M`/`prepare_memory_region`
   primitives?); (ii) **pre-reserve the JIT region before the JVM allocates its heap**, or pin heap base
   away from the mirror band; (iii) per-committed-region `prepare_memory_region` calls instead of one
   up-front; (iv) match Amethyst's exact `JIT26PrepareRegion` semantics/ordering; (v) something else. What
   is the minimum that makes Amethyst's region non-colliding that we're missing?
3. **What single on-device experiment best discriminates** the diagnosis from the alternatives in Q1
   (e.g. dump the kernel page mapping of the RX region vs heap; force a GC immediately after the first
   compile; log every `prepare_memory_region` extent vs every JVM `mmap`)? We can add native logging and
   pull verbatim `hs_err`/`jvm-stdio` via `devicectl`.
4. **Is `-XX:-UseCompressedClassPointers` or `ReservedCodeCacheSize=64m` implicated** in the page layout
   such that a smaller/larger/relocated code cache would avoid the collision?

## Constraints / trust rules
- **Primary sources only** for StikDebug/Amethyst/Pojav internals (current `universal.js`,
  `UniversalJIT26Extension.js`, `JavaLauncher.m`, the Pojav `jre_21/ios/2_mirror_mapping.diff`); GPLv3 →
  patterns only, never vendored.
- **Trust only verbatim device artifacts** (`hs_err`, `jvm-stdio.log` pulled via `devicectl`) and
  bash-computed md5-gated verdicts. This area previously produced false-GREENs from fabricated/misattributed
  logs — every claim here is from a saved `hs_err`/`jvm-stdio` (in `ios/app/evidence/`).
- Full history + evidence: `ios/app/PHASE3-6.2-DEVICE-CRASH.md` (RESEARCH UPDATES 1–14) and
  `ios/app/evidence/` (the `is_unloading` and `GCheapcorrupt-tier1` hs_err logs, the clean-boot stdio).
