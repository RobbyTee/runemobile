# Audit result - W1 P3 6.2 JIT region corruption

Date: 2026-06-01

## Verdict summary

I do not consider the physical-page-aliasing diagnosis proven. The trusted artifacts prove
intermittent broad memory corruption while running HotSpot with the debugger-backed
`MirrorMappedCodeCache`, but they do not prove that the debugger JIT mapping shares physical
frames with the Java heap or metaspace.

My working diagnosis would be narrower: the failing mechanism is in the debugger/TXM-backed
RX/RW code-cache mirror or in its lifecycle. The most likely immediate failure is that bytes or
metadata written through the RW alias are not always observed correctly through the RX alias at
execution or code-cache metadata use time. The tier-1 GC crash is compatible with downstream
wrong-code or bad-root corruption; it is not direct evidence that heap pages physically alias the
code cache.

If forced to choose today, I would not invest first in heap-base pinning or "dedicated physical
page" work. I would first make the launch path match current Amethyst behavior exactly, then, if
the crash survives, instrument and fix the libjvm mirror/cache synchronization path.

## Evidence checked

Local trusted artifacts:

- `ios/app/evidence/jvm-stdio-6.2-isUnloading-w3shell-20260601.log`: one 64 MB JIT mapping,
  RX `0x1154b8000`, RW `0x1194b8000`, then a real RuneLite crash.
- `ios/app/evidence/hs_err-6.2-isUnloading-w3shell-20260601.log`: full-tiered crash in
  `IsUnloadingBehaviour::is_unloading(CompiledMethod*)`; CodeCache 64 MB, about 4.7 MB used,
  `full_count=0`; Java heap virtual range `[0x7f0000000, 0x800000000)`.
- `ios/app/evidence/hs_err-6.2-GCheapcorrupt-tier1-20260601.log`: tier-1-only crash in
  `G1ParScanThreadState::handle_evacuation_failure_par`; fault address
  `0x00000003aa659bf0`, outside the Java heap and outside the code-cache virtual ranges;
  CodeCache about 4.5 MB used, `full_count=0`.
- `ios/app/evidence/jvm-stdio-6.2-excmask-BOOTED-OK-20260531.log`: same style of single
  64 MB mapping booted RuneLite cleanly, but this is a clean boot rather than a long-run proof.
- `ios/app/stikdebug/runelite-jit.js`: manually handles `brk #0x69` by `_M{size},rx`,
  `prepare_memory_region(rx,size)`, returns RX, and stays attached.
- `ios/dev-tools/patch-jre-txm.sh`: forces the OpenJDK TXM/debugger mapping path by patching
  the libjvm gates.

Primary sources checked on 2026-06-01, for behavior only:

- `StephenDev0/StikDebug@848ddbbfa4745b0c4741391a4c73d232e9640dbb`,
  `StikDebug/Scripts/universal.js`: current `JIT26PrepareRegion` allocates `_M{size},rx`
  when `x0 == 0`, then calls `prepare_memory_region(addr,size)`. Current default legacy
  `brk #0x69` returns an error unless a launcher-provided legacy handler is installed.
- `AngelAuraMC/Amethyst-iOS@00678b07a192ef5c79f8c4a2e4cecf1d7406c8c5`,
  `Natives/JavaLauncher.m` and `Natives/resources/UniversalJIT26Extension.js`: Amethyst
  probes legacy JIT support, injects its Universal JIT extension, maps legacy `0x69` to
  `JIT26PrepareRegion(NULL,size)`, sets detach-after-first according to policy, and clears
  `EXC_MASK_BAD_ACCESS`.
- `AngelAuraMC/angelauramc-openjdk-build@a2a0d0194162fb7bcc79e8ee522fecd098cd92b9`,
  `patches/jre_21/ios/2_mirror_mapping.diff`: the JDK patch calls `BreakGetJITMapping`,
  `vm_remap`s the returned RX mapping to a RW alias, stores the RW base, returns RX to
  HotSpot, and translates between RX/RW with `mirror_w`/`mirror_x`.
- Same OpenJDK patch set, `patches/jre_21/ios/1_jdk21u_ios.diff`: AArch64 i-cache
  invalidation uses `sys_icache_invalidate`.

## Q1. Is physical-page aliasing the right diagnosis?

Verdict: not established; keep it as an open hypothesis, not the root-cause conclusion.

The evidence proves corruption associated with the JIT mirror:

- In the full-tiered run, the failing path walks code-cache/nmethod state and dies with a
  corrupt-looking compiled-method path while the CodeCache is only about 7 percent used.
- In the tier-1 run, the JVM dies in GC root/object processing with a wild address outside the
  Java heap.
- The error reporter self-faults in the tier-1 run, which supports broad corruption rather than
  a single benign null-check or one expected HotSpot trap.
- The crashes appear after sustained compilation/runtime activity, not at the initial mapping.

That is enough to indict the on-device JIT/mirror path. It is not enough to prove that RX/RW code
cache pages physically alias heap or metaspace pages. The hs_err files show virtual separation:
the Java heap is at `0x7f0000000..0x800000000`, while the CodeCache is in the `0x11...` region.
The tier-1 fault address `0x3aa659bf0` is not a heap address and not a code-cache address; it is
best read as a corrupted pointer. A corrupted pointer can be produced by stale or wrong compiled
code just as easily as by a direct heap-page collision.

Physical aliasing would require evidence that two live VM objects, the debugger-provided JIT
mapping and the JVM heap/metaspace, share the same physical pages. None of the current artifacts
dump Mach VM object identity or PFNs. Without that, "the same physical page is a live heap
object" is an overclaim.

Candidate causes:

- i-cache/d-cache or RX/RW mirror coherency: most likely. The patch does have an unconditional
  `sys_icache_invalidate` path and the mirror patch tries to keep HotSpot-visible addresses in
  the RX view. That weakens the simple "forgot to invalidate i-cache" theory. It does not fully
  rule out a D-cache clean / I-cache invalidate / barrier problem across distinct RW and RX
  virtual aliases, nor a call path that still flushes the wrong alias. This candidate explains
  both corrupt nmethods and downstream heap/GC corruption.
- Partial `prepare_memory_region` coverage: weaker. The local script and current StikDebug
  `JIT26PrepareRegion(NULL,size)` both prepare the full requested 64 MB. The crash happens with
  only about 4.5 to 4.7 MB of CodeCache used. If the bug exists here, it would have to be that
  later HotSpot commit/protect operations change physical backing after the up-front prepare.
  That is worth instrumenting, but it is not the best first explanation.
- GC moving code or stale mirror caused by GC: unlikely. HotSpot's G1 heap movement does not move
  nmethods/code-cache pages, and both failing runs show low CodeCache usage with `full_count=0`.
- `-XX:-UseCompressedClassPointers` or heap-base virtual overlap: refuted as a direct cause by
  the logs. Compressed class pointers are disabled, so there is no compressed class space to
  overlap. The Java heap and CodeCache virtual ranges are far apart.
- Continuous attach vs Amethyst detach: plausible as an operational difference, not proven as
  the corruption mechanism. The current named logs show one 64 MB JIT allocation request, so the
  earlier "detach starves later brks" conclusion should not be treated as trusted unless a fresh
  log reproduces repeated `brk #0x69` calls after the `lib/modules` contamination was fixed.
  Staying attached plus the local non-BRK "prepare current PC page and retry" safety net is a
  real divergence from Amethyst and should be removed during parity testing.

## Q2. Cheapest reliable fix

Verdict: exact Amethyst parity is the cheapest credible first fix. If that does not hold, the
reliable fix moves into libjvm mirror/cache synchronization, not StikDebug JS page allocation.

Ranking:

1. Match Amethyst's current JIT protocol and exception lifecycle exactly. Use the maintained
   Universal script plus a launcher extension/pattern equivalent to Amethyst's legacy `0x69`
   bridge; perform the legacy probe; set detach-after-first after the first HotSpot code-cache
   reservation if the fresh logs still show exactly one reservation; keep `EXC_MASK_BAD_ACCESS`
   out of StikDebug; and remove the local "prepare page at stopped PC" safety net. This is the
   cheapest path because it changes lifecycle and ordering without changing HotSpot internals.
2. If parity still corrupts, fix or validate the libjvm mirror/cache layer. Add verification
   around `get_debug_jit_mapping`, `vm_remap`, code-cache commit, and `ICache::invalidate_range`.
   The likely repair would be an explicit correct D-cache clean / I-cache invalidate / barrier
   sequence for the RW-written range and RX-executed range, plus assertions that all HotSpot
   code writes happen through RW and all execute/metadata addresses are RX.
3. Add per-commit or per-used-page `prepare_memory_region` logging/preparation as a diagnostic
   or defensive experiment. Do not assume it is the fix unless logging shows that HotSpot changes
   backing/protection after the original 64 MB prepare.
4. Do not prioritize heap-base pinning or pre-reserving the JIT region before heap allocation.
   The trusted failures do not show virtual overlap, and physical aliasing cannot be fixed
   reliably by moving virtual bases. It may perturb allocation order and change crash rate, but
   that would be a probabilistic workaround.
5. Do not spend first effort on "dedicated, fully committed, non-shared physical pages" inside
   `runelite-jit.js`. The JS primitives visible here are `_M` and `prepare_memory_region`; they
   do not expose a way to request exclusive PFNs or inspect the kernel VM object. The local script
   already matches the important allocation/prepare semantics of `JIT26PrepareRegion(NULL,size)`.

The minimum missing piece from the proven Amethyst stack is probably not a different `_M`
allocation primitive. The local allocation path is already substantially the same. The missing
piece is exact lifecycle parity: current Universal script plus extension, probe/order, detach
policy, exception masks, and no ad-hoc fault recovery.

## Q3. Best single on-device discriminator

Verdict: dump Mach VM region identity for RX, RW, heap, metaspace, and GC side mappings in the
same process, immediately after JIT mapping setup and again after heap/metaspace initialization.

Implement a native diagnostic that logs, for each relevant range:

- start/end address and current protection/max protection;
- `mach_vm_region_recurse` nesting and submap data;
- `VM_REGION_EXTENDED_INFO` fields available on-device, especially share mode, user tag,
  resident page counts, ref counts, and object identity fields if exposed;
- the exact RX/RW translation pair returned by `get_debug_jit_mapping` and `vm_remap`;
- heap, metaspace, card table, mark bitmap, and polling-page ranges from HotSpot.

Expected outcomes:

- RX and RW do not identify as the same VM object: the mirror itself is broken; investigate
  `vm_remap` and cache synchronization.
- RX/RW identify as the same VM object, and heap/metaspace identify as different objects:
  physical-page aliasing with heap/metaspace is strongly refuted; focus on cache coherency,
  stale RX/RW translations, or attach lifecycle.
- Heap or metaspace identifies as the same object/backing as RX/RW: the physical-page-aliasing
  diagnosis is confirmed and the fix must move below the current JS primitive level.
- Object identity is not available: fall back in the same build to page-indexed canaries written
  through RW and read through RX plus scans of reserved heap/native buffers. That is weaker than
  object/PFN identity but still directly tests collision rather than inferring it from crash
  location.

This experiment discriminates the proposed physical collision from cache/coherency and attach
lifecycle better than forcing a GC, changing tier level, or logging only every mmap. The latter
can reproduce symptoms, but they cannot prove or refute page aliasing.

## Q4. Are `-XX:-UseCompressedClassPointers` or `ReservedCodeCacheSize=64m` implicated?

Verdict: neither is implicated as the direct cause in the trusted logs.

`-XX:-UseCompressedClassPointers` is not a page-collision signal here. It removes compressed
class space from the layout, and the logs still show compressed oops with zero-based heap
addressing. Amethyst also uses this flag in jailed/no-extended-VA conditions. It can change
metaspace layout, but there is no artifact showing a metaspace/code-cache virtual overlap or a
class-space collision.

`ReservedCodeCacheSize=64m` controls the size of the debugger allocation and the RX/RW remap, so
it can affect probability if the underlying bug is proportional to prepared/remapped pages. It is
not evidence of CodeCache pressure: both crashes occur with about 4.5 to 4.7 MB used and
`full_count=0`. A smaller code cache might reduce exposure and a larger one might increase it,
but neither is a reliable fix for a mirror/coherency bug. Treat code-cache size as a diagnostic
knob only.

## Recommended next step

Run two fresh device builds:

1. Amethyst-parity lifecycle build: current Universal script plus legacy bridge behavior,
   detach-after-first enabled after confirming one 64 MB HotSpot reservation, `EXC_MASK_BAD_ACCESS`
   cleared, and the local non-BRK safety net removed.
2. Instrumented mapping build: Mach VM region identity dump for RX/RW/heap/metaspace/card/mark
   ranges, with the same RuneLite workload and the same hs_err/jvm-stdio capture discipline.

If build 1 survives a long RuneLite run, the root cause was likely lifecycle/exception handling.
If build 1 still corrupts and build 2 shows RX/RW separate from heap/metaspace, prioritize the
libjvm mirror/cache synchronization fix. If build 2 shows shared backing between JIT and
heap/metaspace, the current physical-page-aliasing hypothesis is confirmed and the fix cannot be
implemented reliably with only `_M` plus `prepare_memory_region` from JS.
