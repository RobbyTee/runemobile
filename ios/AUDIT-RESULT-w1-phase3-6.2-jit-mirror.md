# Audit result — W1 P3 §6.2 device JIT-mirror coherency (2026-05-31)

External adversarial review of `AUDIT-ASK-w1-phase3-6.2-jit-mirror.md`, plus our decision and the
primary-source checks that back it. The ask was: before re-pinning the JVM, adjudicate the crash
diagnosis and choose Option A (re-pin to the current Amethyst jre + `brk #0xf00d` + `universal.js`)
vs Option B (fix the legacy `brk #0x69` servicing) vs a cheaper interim.

## Verdict (verbatim)

> **The crash diagnosis is directionally right, but I would not phrase the defect as "single-shot
> prepare is definitely insufficient" yet.** Current Amethyst source still bridges legacy `brk #0x69`
> through `UniversalJIT26Extension.js`; the real delta is that your app is not reproducing the whole
> current Amethyst/StikDebug servicing stack.
>
> **Key correction.** Current StikDebug `universal.js` does make `brk #0x69` legacy and returns the
> magic/error value, but Amethyst then injects an extension that redefines `legacyCommands[0x69]` to
> call `JIT26PrepareRegion(NULL, size)`. So "current Amethyst = pure `brk #0xf00d` JVM" is not proven
> by source. Before re-pinning, disassemble the candidate `libjvm` and confirm whether HotSpot itself
> now emits `brk #0xf00d`, or whether Amethyst still relies on the legacy bridge.
>
> **Adjudication.**
> 1. *Diagnosis:* yes, this smells like JIT mapping/servicing, not AWT/RuneLite. `ReservedCodeCacheSize=64m`
>    is not too small when the SIGILL happens at 7% used. G1/code-cache walking is likely where corruption
>    becomes visible, not the root. But the claim "one up-front prepare is the bug" is weaker: Amethyst's
>    legacy extension also does one allocate/prepare for the requested region.
> 2. *Option A vs B:* prefer **Amethyst-stack parity**, but define it precisely: current `universal.js`,
>    app-side `brk #0xf00d` wrappers, clean-room extension injection for `0x69`, detach policy, and
>    exception-port handling. Re-pinning the JRE is justified only after proving the current CDN JRE
>    actually differs in the HotSpot JIT protocol. A cheaper first differential is: keep pinned JRE,
>    replace `runelite-jit.js` with the current Universal+extension protocol.
> 3. *Regression risk:* moderate but contained. Re-verify `JNI_CreateJavaVM` export,
>    `-XX:+MirrorMappedCodeCache`, `-XX:-UseCompressedClassPointers`, `java.desktop`, CACIO §6.0/§6.1 md5
>    gates, signing/re-signing, and §6.2 desktop/device logs. Also retire `patch-jre-txm.sh` unless its
>    offsets are re-proven on the new binary.
> 4. *Cheaper interim:* enlarging code cache is unlikely to help and may worsen the RX allocation.
>    Pre-faulting is mostly already what StikDebug's `prepare_memory_region` does: it writes one byte per
>    16 KB page. The best cheap test is **protocol parity without JRE swap**. Next best: `-Xbatch`,
>    `CICompilerCount=1`, or tier limiting to see if timing/density changes the failure mode, but those
>    are diagnostics, not the fix.
>
> **Recommended next move.** Do not blindly re-pin first. Run this sequence:
> 1. Disassemble current candidate `libjvm`: count `brk #0x69` vs `brk #0xf00d`.
> 2. Keep pinned JRE, switch to current StikDebug `universal.js` plus a clean-room Amethyst-style
>    `0x69 -> JIT26PrepareRegion(NULL,size)` extension.
> 3. Add Amethyst-style native calls before `JNI_CreateJavaVM`: probe `JIT26CreateRegionLegacy`, inject
>    extension, set detach-after-first-break, clear `EXC_MASK_BAD_ACCESS`.
> 4. If that still fails, then re-pin JRE and rerun §6.0, §6.1, §6.2 with md5-gated logs.
>
> Sources checked: StikDebug `universal.js` (L154–203), StikDebug `prepare_memory_region`
> (`JSDebugSupport.swift` L97–167), Amethyst `JavaLauncher.m` (L107–135), Amethyst
> `UniversalJIT26Extension.js` (L1–27).

## What we verified against primary source (this session)

- **Our pinned libjvm uses the legacy protocol only.** Byte-scan of
  `dev-tools/jre/lib/server/libjvm.dylib` (sha256 `628ecae014da8029…`): **`brk #0x69` × 1**
  (`D4200D20`), **`brk #0xf00d` × 0** (`D43E01A0`). HotSpot here requests code-cache via the single
  `BreakGetJITMapping` thunk. ⇒ re-pinning is *not* required to get a working protocol; the legacy
  bridge is what the current Amethyst stack itself uses.
- **Amethyst's current `0x69` bridge** (`UniversalJIT26Extension.js`): its `legacyCommands[0x69]`
  handler moves size into `x1`, sets `x0 = 0` (NULL), and calls `JIT26PrepareRegion(brkResponse)` —
  i.e. **`JIT26PrepareRegion(NULL, size)`** — with an optional `JIT26Detach()` when
  detach-after-first-break is set. This is the maintained primitive; **our home-grown `_M …,rx` +
  one-shot `prepare_memory_region` is the approximation that loses coherency under dense compilation.**

## Decision

**Reject blind Option A (re-pin jre). Adopt the audit's refined Option B: protocol parity on the
pinned jre.**

- Keep the **pinned jre21**. Update the device's **StikDebug to the current build** (whose
  `universal.js` exposes the `JIT26PrepareRegion` primitive and the `brk #0xf00d` dispatch).
- Replace our `runelite-jit.js` with a **clean-room** script matching the current protocol:
  dispatch `brk #0xf00d` + x16 commands, and service the legacy `brk #0x69` via
  `JIT26PrepareRegion(NULL, size)` (+ detach-after-first-break), instead of `_M`+single-prepare.
- The only on-device change is the StikDebug app update — **no JVM swap**, so every md5-gated GREEN
  (§6.0/§6.1, the `JNI_CreateJavaVM` embed, signing, JPMS flags) is preserved.

**Interim micro-tweaks rejected:** code cache was 7% full at the SIGILL, so `ReservedCodeCacheSize`
is not the constraint; pre-faulting is already what `prepare_memory_region` does. `-Xbatch` /
`CICompilerCount=1` retained only as diagnostics to shift compile timing/density.

**Option A (re-pin current Amethyst jre21) = escalation only** if protocol parity on the pinned jre
still crashes (which would prove the delta is in the binary, not the servicing). On escalation,
re-verify: `JNI_CreateJavaVM` export, `+MirrorMappedCodeCache`, `-UseCompressedClassPointers`,
`java.desktop`, §6.0/§6.1/§6.2 md5 gates, signing, and re-prove `patch-jre-txm.sh` offsets on the new
binary (else retire it).

## Implementation plan (device-tested, md5-gated)

1. **Sources read** (primary, not memory): `universal.js`, `UniversalJIT26Extension.js`,
   `JavaLauncher.m` JIT section, `JSDebugSupport.swift` `prepare_memory_region`. GPLv3 → patterns only,
   clean-room.
2. **Rewrite `ios/app/stikdebug/runelite-jit.js`** to the current protocol: `brk #0xf00d` x16 dispatch
   + legacy `0x69 → JIT26PrepareRegion(NULL,size)` + detach-after-first-break, keeping our `vAttach`.
   If `JIT26PrepareRegion` is absent in the device's StikDebug, the StikDebug update supplies it.
3. **Build + sign + install** (team 5NZ6CPA994). User: connect LocalDevVPN (SideStore VPN off),
   attach the updated StikDebug with the new script, tap launch.
4. **Pull `Documents/jvm-stdio.log` + `hs_err` + `rl-capture/`** via `devicectl`; adjudicate with a
   **bash-computed, md5-gated** verdict (this area produced false GREENs before — trust only verbatim
   device logs).
5. If still crashing → escalate to Option A with the re-verify list above.

## Process caveat
This libjvm/JIT area is where a prior session committed false GREENs from fabricated shell output.
Trust only verbatim `devicectl`-pulled `jvm-stdio.log`/`hs_err` and bash-computed md5-gated verdicts.
GPLv3 sources are reference-only (patterns, not vendored code).
