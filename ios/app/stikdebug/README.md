# StikDebug JIT script — `runelite-jit.js`

The piece that makes the W1 shell actually boot HotSpot on the iPhone 17 Pro Max (iOS 26.5, TXM).

## Why it exists
On a TXM device the app cannot map executable (RX) memory itself; StikDebug (the attached debugger)
must do it on the JVM's behalf. Our jre21's libjvm requests code-cache memory via a single thunk:

```
char* BreakGetJITMapping(size_t bytes)   ==   brk #0x69 ; ret
```

HotSpot calls it with the requested **size in x0** and expects the address of a freshly-mapped **RX
region back in x0**. StikDebug's stock `universal.js` only returns a stub (`0xE0000069`) for `brk #0x69`,
which HotSpot then tries to execute → crash (this is the crash we hit on the first device run).
`runelite-jit.js` provides a real handler: allocate an RX region of `size` (`_M …,rx`), wire it up for
TXM execution (`prepare_memory_region`), and return its address in x0. This servicing is byte-for-byte
the maintained StikDebug `JIT26PrepareRegion(NULL, size)` (universal.js) that the Amethyst stack uses.

It uses only StikDebug's documented builtins (`get_pid`, `send_command`, `prepare_memory_region`, `log`),
the GDB remote protocol, and the AArch64 BRK encoding — no app-side native glue required.

### Detach after the first reservation (Amethyst parity — `DETACH_AFTER_FIRST`)
Disassembly of our pinned libjvm shows it emits `brk #0x69` exactly once (zero `brk #0xf00d`): HotSpot
reserves the whole `ReservedCodeCacheSize` up front in a single `get_debug_jit_mapping()` during
`JNI_CreateJavaVM` (before the compiler threads start). All ~1400 nmethods are then written into that
one already-prepared RX region + its RW mirror. So once that single region is serviced, the debugger is
no longer needed — and the proven Amethyst stack sets `JIT26SetDetachAfterFirstBr` to **detach** at that
point, so the dense-compile phase runs with **no debugger attached**.

Our earlier script instead stayed attached across all ~1400 compiles and ALSO mapped-on-fault any
instruction-abort. Both are divergences from the proven path; the persistent attach is the prime suspect
for the §6.2 mirror going incoherent under dense JIT (see `../PHASE3-6.2-DEVICE-CRASH.md`). The script now
detaches after the first `brk #0x69` (`DETACH_AFTER_FIRST=true`) and has **no** map-on-fault net (stray
stops are forwarded to the VM). If a device run shows a *second* `[rl-jit] #2 brk#0x69` or a fault right
after `detached`, this libjvm reserves the cache in more than one call → set `DETACH_AFTER_FIRST=false`
and add the app-side `EXC_MASK_BAD_ACCESS` clear in `JVMLauncher.m`.

## Install + assign (on the iPhone)
1. Get `runelite-jit.js` onto the phone and into StikDebug's scripts:
   - AirDrop the file to the iPhone, or
   - **Files → On My iPhone → StikDebug → scripts/** and drop it there, or use StikDebug's in-app
     "add/import script" button.
2. In **StikDebug**, open the app list, select **HelloShell**, and **assign** `runelite-jit.js` as its
   script. (StikDebug stores the per-app mapping; on iOS-26 TXM it runs the assigned script on attach.)
3. Make sure **LocalDevVPN** is connected.

## Run loop
1. Launch **HelloShell** → it waits at "JIT: waiting…".
2. **Attach StikDebug** to HelloShell (with `runelite-jit.js` assigned). `CS_DEBUGGED` flips green; the
   script attaches and sits in its continue-loop waiting for `brk #0x69`.
3. Tap **Start JVM**. Watch HelloShell's console and StikDebug's log:
   - StikDebug log should show one `[rl-jit] #1 brk#0x69 size=… -> RX 0x…` line, then
     `[rl-jit] detached after first reservation` — the JVM then runs the dense-compile phase un-attached.
   - HelloShell should reach `=== HelloShell: HotSpot reached main() ===` and print
     `VERDICT = JIT ACTIVE`.

## If it still crashes / hangs
Capture both logs. Useful signals:
- **No `[rl-jit] attached` line** → the script wasn't assigned/run; re-check the assignment.
- **`_M alloc FAILED`** → RX allocation rejected (try a smaller `-XX:ReservedCodeCacheSize` in the app's
  JLI args, e.g. 64m).
- **`[rl-jit] non-0x69 stop …`** → HotSpot hit a different fault; paste the pc/instr line.
- **Reaches main but `VERDICT = INTERPRETER ONLY`** → JIT mapping worked for the interpreter but C1/C2
  didn't engage; we look at compiler threads next.
