# Rung 0 — desktop miniJVM vs OpenJDK speedup (RESULT)

**Date:** 2026-05-29. **Purpose:** the first rung of the real-JVM de-risking ladder
(`ios-spike/REALJVM-FEASIBILITY-SCOPE.md` §7) — quantify, on the same machine with no iOS involved, how much
faster a real JIT JVM is than miniJVM on the workload the iOS profiler proved dominates the OSRS software
renderer (`project_ios_perf_pivot`: `Float.floatToIntBits` dispatch ~27%, rasterizer `fq.*` ~13%, rest
distributed interpreter overhead).

**Verdict: strong GO.** Even against miniJVM's *absolute best* (sljit JIT on, all hot methods compiled),
OpenJDK is **12–109× faster**; against the interpreter regime that matches the current ~3 fps iOS baseline,
**45–184× faster**. A ~12× floor on the most JIT-friendly component alone means the 3 fps software renderer has
ample headroom to clear playable on a real JVM.

## How it was measured
- **Bench:** `Bench.java` (this dir) — three components mirroring the profile: (A) `Float.floatToIntBits` /
  `intBitsToFloat` / `floatToRawIntBits` dispatch, (B) an `int[]` gouraud-fill rasterizer inner loop, (C) mixed
  call/alloc/array/math. Each self-calibrates (doubles iters until a run crosses ~1s) so the slow VM stays
  bounded and throughput is read directly. A printed checksum + a real data-dependency chain defeat C2
  dead-code elimination.
- **Same bytecode both VMs:** compiled `javac --release 8` → major version 52, **zero `invokedynamic`** (no
  lambda / `StringConcatFactory` / condy), so miniJVM and OpenJDK execute byte-identical, simple bytecode.
- **Correctness cross-check:** the fixed warmup workload printed `WARMUP-DONE sink=891409065879486` on *every*
  run — OpenJDK, miniJVM-interpreter, and miniJVM-JIT alike — proving identical arithmetic (and that miniJVM's
  JIT doesn't corrupt results).
- **VMs:** OpenJDK `11.0.31` (Homebrew, 64-bit Server VM, default mixed mode / C2) vs the desktop `mini_jvm`
  CLI built from this repo's miniJVM C source (`binary/build_mac_linux.sh` compile line; arm64).
- **miniJVM JIT:** the desktop CLI entry doesn't wire the `MINIJVM_JIT` env (only the iOS app entry does), so
  the default run is interpreter-only. To get miniJVM's *best case* the iOS entry's enable block was
  temporarily mirrored into `minijvm/c/main.c` (built as a throwaway `mini_jvm_jit`, **reverted after** — tree
  clean), run with `MINIJVM_JIT=1 MINIJVM_JIT_ALL=1 MINIJVM_JIT_THRESHOLD=1`. The low threshold matters:
  miniJVM compiles on method-entry count (no on-stack replacement), so the default `threshold=5000` never
  compiles the bench's few-entry/huge-loop hot methods — only at threshold≈1 does its JIT actually engage on
  them.

## Throughput (ops/sec, steady-state best of two timed runs)

| Component | OpenJDK 11 (C2) | miniJVM interpreter | miniJVM + sljit JIT (best) |
|---|--:|--:|--:|
| floatbits | 198,081,509 | 4,399,941 | 16,538,059 |
| raster (px/s) | 1,703,498,381 | 9,277,098 | 79,671,270 |
| mixed | 61,052,267 | 433,453 | 559,588 |

## Ratios

| Component | OpenJDK ÷ miniJVM-interp | OpenJDK ÷ miniJVM-JIT-best | miniJVM JIT ÷ its own interp |
|---|--:|--:|--:|
| floatbits | **45.0×** | **12.0×** | 3.76× |
| raster | **183.6×** | **21.4×** | 8.59× |
| mixed | **140.9×** | **109×** | 1.29× |

## Reading it honestly
- **Operative comparison ≈ the interpreter column (45–184×).** The current iOS build runs interpreter-dominated
  (memory notes miniJVM's JIT was kept out of stable visual runs), so that's the regime behind the ~3 fps
  baseline.
- **Even the most charitable comparison clears the bar.** vs miniJVM's *best possible* (JIT, all hot methods
  compiled), the floor is 12× (floatbits) — and floatbits is exactly where miniJVM's JIT helps *least* (a
  native-method call it can't intrinsify), which is why OpenJDK's intrinsic dominates.
- **`mixed`'s 109× is partly escape-analysis:** OpenJDK's C2 scalar-replaces the per-iteration `new int[16]`
  (it doesn't escape), while miniJVM allocates for real. Representative of short-lived allocation in real code,
  but the cleaner compute-bound floors against miniJVM-JIT are floatbits (12×) and raster (21×).
- **Caveats (by design):** this is a *synthetic proxy*, not the gamepack render loop, and it measures *desktop*
  CPU. The transferable signal is the **ratio**, not absolute throughput — the real on-device FPS is Rung 3.
  iOS HotSpot-with-JIT is the same optimization class as desktop C2, so the multiplier should largely carry.

## Reproduce
```sh
cd ios-spike/dev-tools/jvmbench
/opt/homebrew/opt/openjdk@11/bin/javac --release 8 -d out Bench.java
# OpenJDK:
/opt/homebrew/opt/openjdk@11/bin/java -cp out Bench
# miniJVM interpreter (build the CLI first — see binary/build_mac_linux.sh; needs
#   -framework CoreFoundation -framework CoreGraphics -framework ImageIO on macOS):
ABS=../../vendor/miniJVM/binary
"$ABS/mac_arm64/mini_jvm" -bootclasspath "$(cd "$ABS/lib" && pwd)/minijvm_rt.jar" -cp "$(pwd)/out" Bench
```
miniJVM needs **absolute** classpath/bootclasspath paths (it resolves them oddly relative otherwise).
