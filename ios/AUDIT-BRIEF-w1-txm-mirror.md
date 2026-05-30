# Audit brief — W1 shell: HotSpot code-cache won't execute on iOS-26 TXM (the mirror-mapping crux)

**For:** an independent reviewer with HotSpot / Pojav-Amethyst MirrorMappedCodeCache / iOS-26 TXM JIT
knowledge. **Goal:** tell us the **correct way to get our embedded HotSpot's code cache executable on a TXM
device under StikDebug**, and **what we're missing**. Default to skepticism; for each open question name the
**cheapest experiment** and **what breaks first**. We have deep ground truth (disasm, file\:line) below — please
validate the mechanism, don't just speculate.

## Context — what's already proven on device (don't re-litigate)
Our own minimal native iOS app (`HelloShell`, free-cert, AltStore-style dev sign) embeds Amethyst's
`jre21-ios-aarch64` HotSpot and `JLI_Launch`es a trivial `Hello` (Java-8 bytecode) on an **iPhone 17 Pro Max,
iOS 26.5 (TXM)** with **StikDebug 3.1.3** attached (per-launch, `CS_DEBUGGED`). Settled on device:
- **Claim A — re-sign avoids the dyld bypass: SUPPORTED.** We `codesign` every Mach-O in the bundled `jre/`
  with our dev cert at build time; a **plain `dlopen(libjli.dylib)` succeeds and `JLI_Launch` resolves** — no
  `dyld_bypass_validation` needed.
- **Claim D — entitlements: SUPPORTED.** The installed build carries `get-task-allow=true` +
  `com.apple.developer.kernel.increased-memory-limit=true`. iOS JIT comes from `get-task-allow` + StikDebug
  attach, not from entitlements.
- **Claim E — wait-for-JIT: SUPPORTED.** App polls `csops(CS_OPS_STATUS … CS_DEBUGGED)` and only launches the
  VM once debugged.
- The **Amethyst oracle** (same `jre21`) runs Minecraft fine on this exact phone under StikDebug — so the JRE +
  platform are capable; the gap is purely **our app-side / script-side JIT enablement**.

We deliberately did **not** vendor Amethyst's GPLv3 native glue; we reimplement clean-room and drive the JIT from
a **StikDebug script** plus a small **libjvm binary patch** (both ours). That choice is what this brief tests.

## JLI args we pass
`-XX:+UnlockExperimentalVMOptions -XX:+MirrorMappedCodeCache -XX:+DisablePrimordialThreadGuardPages
-XX:-UseCompressedClassPointers -XX:ReservedCodeCacheSize=64m -Djava.home=<jre> -Djava.library.path=<app>/Frameworks
-cp Hello.jar Hello`. `argv[0]=<jre>/bin/java`, `JAVA_HOME` set, SIG_DFL reset for SEGV/BUS/ILL/FPE/PIPE/TRAP
before launch. The flag was accepted (no "Unrecognized VM option"; the VM reaches code generation).

## Ground truth — the libjvm code-cache path (disasm of the bundled `lib/server/libjvm.dylib`)
- `os::pd_reserve_memory(size, executable)` (@ `0x8222a4`) calls **`get_debug_jit_mapping(size)`** (@ `0x81fc5c`)
  when reserving executable memory.
- `get_debug_jit_mapping` logic (verbatim from disasm):
  ```
  ldrb w8, [MirrorMappedCodeCache global @ 0xd1909a]; cmp w8,#1; b.ne 0x81fd8c   // (gate 1: the flag)
  mov x19, x0                                                                    // save size
  bl DeviceRequiresTXMWorkaround                                                 // (gate 2)
  cbz w0, 0x81fcc8                                                               // if !TXM -> mmap fallback
  printf("[JIT26] Requesting %zu MB for JIT mapping"); BreakGetJITMapping(x19)   // brk #0x69
  cbz x0, 0x81fcc8; printf("[JIT26] Got JIT mapping %p from debugger"); ...use it...
0x81fcc8:  mmap(0, size, PROT_READ|PROT_EXEC, MAP_PRIVATE|MAP_ANON, -1, 0)       // fallback (non-exec on TXM!)
0x81fd8c:  mov x0,#0; ret                                                        // gate-1-fail -> returns NULL
  ```
- **`BreakGetJITMapping(size_t bytes)`** (@ `0x81fb80`) == `brk #0x69 ; ret` — size in x0, returns RX addr in x0.
- **`DeviceRequiresTXMWorkaround()`** (@ `0x81fb8c`) returns true iff iOS≥19 **and** it can `access()` the file
  `/private/preboot/<uuid>/usr/standalone/firmware/FUD/Ap,TrustedExecutionMonitor.img4`. **From inside the app
  sandbox `opendir("/private/preboot")` fails → it returns 0.**
- Other strings present: `[JIT26] Failed to allocate RX region`, `[JIT26] Failed to remap RX region %d`,
  `[JIT26] mapping at RW=%p, RX=%p`. So libjvm's MirrorMapped path expects to set up a **RW mirror** of the RX
  region. (We have NOT traced exactly how/where it obtains the RW VA.)
- The `MirrorMappedCodeCache` global @ `0xd1909a` has **5 read sites, no obvious direct writer** — it's the
  product flag `MirrorMappedCodeCache` (string present in the flag table).

## What we did, and the on-device results (chronological)
1. **Map-on-fault script (no patch).** StikDebug script catches instruction-abort faults and calls
   `prepare_memory_region(faultPage, 16KB)`. Result: reached a JIT-active verdict briefly but then **looped on
   `brk #0x1` (HotSpot `ShouldNotReachHere`)** ~4000×/10s → crash. Also: a page we'd `prepare`'d (`prepare=OK`)
   **re-faulted at the same pc** → strong evidence **`prepare_memory_region` only makes *debugger-`_M`-allocated*
   regions executable, not HotSpot's own `mmap`'d pages**. `brk #0x69` **never fired** in this run.
2. **Patch #1 — NOP only the TXM gate** (`cbz w0` @ `0x81fc8c`). Still **no `brk #0x69`**; same faults. → the
   **flag gate (`b.ne` @ `0x81fc80`) diverts first** (the `MirrorMappedCodeCache` global reads ≠1 here, so
   `get_debug_jit_mapping` returns NULL at `0x81fd8c` → caller uses its own `mmap` → non-exec on TXM → fault).
3. **Patch #2 — NOP both gates** so `get_debug_jit_mapping` *unconditionally* calls `BreakGetJITMapping`. App
   still **crashes on Start JVM**. We could NOT confirm whether `brk #0x69` now fires: `jvm-stdio.log` came back
   **empty** (libjvm `printf` to a pipe is fully buffered; lost on the hard crash). We've since switched capture
   to unbuffered direct-to-file and will retry to get the `[JIT26]` trace.

Our StikDebug script (`runelite-jit.js`, ours, clean-room) handles `brk #0x69`: read size from x0, `_M <size>,rx`
(StikDebug allocate RX), `prepare_memory_region(addr,size)`, write addr back to x0, step over the brk. It stays
attached. Non-0x69 brks and re-faults are forwarded to the VM (deduped — no infinite loop).

## The open questions (please answer with mechanism, not guesses)

### Q1 — Is "force `brk #0x69` + a debugger script" even sufficient, or is app-side native JIT26 setup MANDATORY?
Amethyst's app, **before `JLI_Launch`**, calls (clean-room summary): `JIT26CreateRegionLegacy(getpagesize())`
(expects magic `0x690000E0`), `JIT26SendJITScript(UniversalJIT26Extension.js)`,
`JIT26SetDetachAfterFirstBr(...)`, and **`task_set_exception_ports(mach_task_self(), EXC_MASK_BAD_ACCESS, 0,
EXCEPTION_DEFAULT, …)`**. We do **none** of this — we bet a StikDebug-side script alone suffices.
- Does HotSpot's MirrorMapped path **require** the app to have cleared `EXC_MASK_BAD_ACCESS` so HotSpot's *own*
  fault handler (not the attached debugger) drives the remap? I.e., is the `[JIT26]` remap fault-driven through
  a libjvm mach/signal handler that never runs because debugserver swallows `EXC_BAD_ACCESS`?
- Does `BreakGetJITMapping`'s success depend on `JIT26CreateRegionLegacy` having first established a region
  (the `0x690000E0` magic) that our script never sets up?
- **Cheapest experiment** to decide "script-only viable" vs "must replicate the native glue"?

### Q2 — Mirror semantics: does forcing `BreakGetJITMapping` actually yield a correct RW↔RX mirror?
`get_debug_jit_mapping` returns an RX address; libjvm logs `mapping at RW=%p, RX=%p`. **Where does the RW view
come from** — does `BreakGetJITMapping`/`prepare_memory_region` (debugger side) set up *both* the RX and a RW
mirror of the same physical pages, or does libjvm `vm_remap` the returned RX region to a RW VA itself? If the
latter, does that `vm_remap` work on a StikDebug-`_M`'d region on TXM? **And is the `MirrorMappedCodeCache`
global @ `0xd1909a` the real enable for the RW-mirror *write* path** (so by NOPing the gate while the global is
0, HotSpot takes the brk path but then writes to a non-mirrored RX region → SIGBUS)? If so, forcing the brk path
is wrong; we must get the global set to 1 instead.

### Q3 — `prepare_memory_region` contract + the right script behavior
What exactly does StikDebug's `prepare_memory_region(addr, len)` do on TXM (the `_M…,rx` GDB alloc + JIT-page
marking)? For our `jre21`'s `BreakGetJITMapping` (size in x0, fresh RX expected), is our handler correct
(`_M size,rx` → `prepare_memory_region` → return addr), or should we instead let libjvm pass an address (the RW
region) and only `prepare` it? Our evidence says `prepare_memory_region` is a **no-op on non-`_M` pages** (the
map-on-fault re-faults) — is that right, and does it constrain how the region must be obtained?

## Also worth a skeptical pass
- **Is the binary patch the right tool at all**, or should we instead make the `MirrorMappedCodeCache` global
  read 1 (so gate 1 passes naturally) — and is there a flag/env that does that, or is the global set only via a
  code path we're not hitting? Is there a HotSpot flag that disables MirrorMapped and uses a single
  W^X-toggled region that would work better with a debugger that can flip page perms on demand?
- **`ReservedCodeCacheSize=64m`**: `BreakGetJITMapping`/`_M` would then allocate a 64 MB RX region in one shot —
  is that sane on a free-cert app (`increased-memory-limit`), or should it be much smaller / segmented?
- **What breaks first** in the "both-gates-NOPed + our script" path — `_M` alloc fails, `prepare_memory_region`
  no-ops, RW-mirror `vm_remap` fails, or first execute SIGBUSes?

## What a useful output looks like
A go/no-go on **"debugger-script-only" vs "must replicate Amethyst's app-side native JIT26 glue
(`JIT26CreateRegion*` + `task_set_exception_ports(EXC_MASK_BAD_ACCESS,0)`)"**, the **correct script `brk #0x69`
handler semantics**, whether the **binary patch is right or we should set the flag global instead**, and **what
breaks first**. File\:line / Mach-API specifics welcome.

## Appendix — repo pointers
- App + launcher: `ios/app/HelloShell/{JVMLauncher.m,ViewController.m}` (CS_DEBUGGED gate, JLI args, stdio capture).
- StikDebug script (ours): `ios/app/stikdebug/runelite-jit.js`.
- libjvm patch (ours): `ios/dev-tools/patch-jre-txm.sh` (NOPs both gates @ `0x81fc80`, `0x81fc8c`).
- Full history + disasm notes: `ios/FINDINGS.md` ("W1 shell — GREEN on device" / the TXM sections).
- Amethyst ref (GPLv3, patterns only): `AngelAuraMC/Amethyst-iOS` `Natives/{JavaLauncher.m,utils.m}`,
  `Natives/resources/UniversalJIT26*.js`. StikDebug: `StephenDev0/StikDebug` `Scripts/universal.js`,
  `JSSupport/RunJSView.swift`, `Support/ScriptStore.swift`.
