// runelite-ios — StikDebug JIT script for the on-device HotSpot (jre21, iOS 26 TXM)
// ---------------------------------------------------------------------------------
// Assign this to HelloShell in StikDebug. It services HotSpot's code-cache memory
// requests so the JVM can generate executable code on a TXM device.
//
// Protocol — brk #0x69 (BreakGetJITMapping): our pinned jre21's libjvm requests an
// RX code-cache region via `char* BreakGetJITMapping(size_t bytes)` == `brk #0x69; ret`
// (size in x0, RX address returned in x0). Disassembly of our libjvm confirms it emits
// brk #0x69 exactly ONCE (zero brk #0xf00d): HotSpot reserves the whole
// ReservedCodeCacheSize up front in a single get_debug_jit_mapping() call, then all
// nmethods are written into that one already-prepared RX region + its RW mirror.
//
// Servicing is identical to the maintained StikDebug `JIT26PrepareRegion(NULL, size)`
// (universal.js): allocate an RX region of `size` (`_M …,rx`), wire it for TXM execution
// (prepare_memory_region), return its address in x0. So the per-brk servicing was never
// the §6.2 defect — the divergence from the proven Amethyst stack was BEHAVIORAL:
//
//   1. DETACH AFTER THE FIRST RESERVATION. Amethyst sets JIT26SetDetachAfterFirstBr so
//      the debugger detaches once the single code-cache region is prepared; the dense
//      compilation phase then runs with NO debugger attached. Our old script stayed
//      attached across all ~1400 compiles — a persistently-attached debugger is the
//      prime suspect for the RW↔RX mirror going incoherent under dense JIT (the SIGILL
//      at a fresh nmethod's first execution / SIGSEGV in nmethod::is_unloading).
//   2. NO map-on-fault net. Our old script "mapped on fault" any instruction-abort —
//      an anti-pattern absent upstream that can paper over / corrupt mirror state.
//      Removed. Stray faults are forwarded to the VM (and, once HelloShell clears
//      EXC_MASK_BAD_ACCESS, go straight to the default handler for a clean crash).
//
// DETACH_AFTER_FIRST is the fix-under-test. If the device log instead shows a second
// brk #0x69 (a fault right after "detached"), this libjvm reserves the cache in more
// than one call — flip DETACH_AFTER_FIRST=false and pursue the app-side parity
// (EXC_MASK_BAD_ACCESS clear in HelloShell) instead.
//
// Uses only StikDebug builtins (get_pid, send_command, prepare_memory_region, log),
// the GDB remote protocol, and the AArch64 BRK encoding.

const DETACH_AFTER_FIRST = true;      // Amethyst parity: detach once the cache is reserved
const pid = get_pid();
let served = 0, forwarded = 0;

// 16-hex-char little-endian register value -> BigInt
function leHexToBig(hex) {
    let n = 0n;
    for (let i = hex.length - 2; i >= 0; i -= 2) n = (n << 8n) | BigInt(parseInt(hex.substr(i, 2), 16));
    return n;
}
// BigInt -> 8-byte little-endian hex (GDB 'P' register write)
function bigToLeHex(n) {
    let s = "";
    for (let i = 0; i < 8; i++) { s += (Number(n & 0xFFn)).toString(16).padStart(2, "0"); n >>= 8n; }
    return s;
}
// pull register `num` (GDB hex index: "00"=x0, "20"=pc) from a T stop-reply
function reg(stop, num) {
    const m = new RegExp(num + ":([0-9a-f]{16});").exec(stop);
    return m ? leHexToBig(m[1]) : null;
}
// little-endian 4-byte instruction hex -> BRK #imm16, or -1 if not a BRK
function brkImm(instrHex) {
    if (!instrHex || instrHex.length < 8) return -1;
    const w = parseInt(instrHex.substr(0, 8).match(/../g).reverse().join(""), 16);
    if (((w & 0xFFE0001F) >>> 0) !== 0xD4200000) return -1;
    return (w >> 5) & 0xFFFF;
}

log(`[rl-jit] attaching to pid ${pid}`);
send_command(`vAttach;${pid.toString(16)}`);
log(`[rl-jit] attached; servicing HotSpot code-cache (brk #0x69, detachAfterFirst=${DETACH_AFTER_FIRST}) …`);

let detached = false;
while (!detached) {
    const stop = send_command(`c`);
    if (!stop || stop.length === 0) continue;
    const k = stop[0];
    if (k === "W" || k === "X") {
        log(`[rl-jit] target ended (${stop}); served ${served}, fwd ${forwarded}`);
        break;
    }
    if (k !== "T") { log(`[rl-jit] unexpected stop: ${stop}`); continue; }

    const tidM = /thread:([0-9a-f]+);/.exec(stop);
    const pc = reg(stop, "20");
    if (!tidM || pc === null) { log(`[rl-jit] missing tid/pc; continue`); continue; }
    const tid = tidM[1];
    const sigM = /^T([0-9a-f]{2})/.exec(stop);
    const sig = sigM ? sigM[1] : "0b";

    const instr = send_command(`m${pc.toString(16)},4`);
    const imm = brkImm(instr);

    // --- brk #0x69 = BreakGetJITMapping(size): allocate + prepare one RX region -------
    if (imm === 0x69) {
        const size = reg(stop, "00");                                  // size in x0
        send_command(`P20=${bigToLeHex(pc + 4n)};thread:${tid};`);     // step over the brk
        if (size === null || size === 0n) {
            send_command(`P0=${bigToLeHex(0n)};thread:${tid};`);
            continue;
        }
        const rx = send_command(`_M${size.toString(16)},rx`);
        if (!rx || rx.length === 0 || rx[0] === "E") {
            log(`[rl-jit] _M alloc FAILED size=0x${size.toString(16)}: ${rx}`);
            send_command(`P0=${bigToLeHex(0n)};thread:${tid};`);
            continue;
        }
        const addr = BigInt(`0x${rx}`);
        const pr = prepare_memory_region(Number(addr), Number(size));
        send_command(`P0=${bigToLeHex(addr)};thread:${tid};`);         // return RX addr in x0
        served++;
        log(`[rl-jit] #${served} brk#0x69 size=0x${size.toString(16)} -> RX 0x${addr.toString(16)} (prepare=${pr})`);

        if (DETACH_AFTER_FIRST) {
            // Amethyst parity: the cache region is prepared; let the dense-compile phase
            // run with the debugger detached. The RX region stays executable post-detach.
            const d = send_command(`D`);
            log(`[rl-jit] detached after first reservation (D -> ${d}); dense JIT now runs un-attached`);
            detached = true;
        }
        continue;
    }

    // --- Anything else (a real HotSpot brk e.g. #0x1, or an unexpected fault): forward.
    // No map-on-fault net (removed): let the VM's own handler process/abort.
    forwarded++;
    log(`[rl-jit] forward pc=0x${pc.toString(16)} instr=${instr} brk=${imm} sig=${sig}`);
    send_command(`vCont;C${sig}:${tid}`);
}
