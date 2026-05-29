// runelite-ios — StikDebug JIT script for the on-device HotSpot (jre21, iOS 26 TXM)
// ---------------------------------------------------------------------------------
// Assign this script to the HelloShell app in StikDebug. It services HotSpot's
// code-cache memory requests so the JVM can generate executable code on a TXM
// device (iPhone 17 Pro Max / iOS 26.5), where the app cannot map RX memory itself.
//
// Why this is needed: our jre21's libjvm contains a single thunk
//     char* BreakGetJITMapping(size_t bytes)  ==  `brk #0x69 ; ret`
// HotSpot calls it with the requested size in x0 and expects the address of a
// freshly-mapped RX region back in x0. StikDebug's stock `universal.js` only
// returns a stub (0xE0000069) for brk #0x69, which HotSpot then tries to execute
// from -> crash. This script provides a real handler.
//
// It uses only StikDebug's documented builtins (get_pid, send_command,
// prepare_memory_region, log) plus the GDB remote protocol and the AArch64 BRK
// encoding. Stays attached and services every request (the code cache grows over
// the run), so it does NOT detach after the first break.

const pid = get_pid();

// 16-hex-char little-endian register value -> BigInt
function leHexToBig(hex) {
    let n = 0n;
    for (let i = hex.length - 2; i >= 0; i -= 2) {
        n = (n << 8n) | BigInt(parseInt(hex.substr(i, 2), 16));
    }
    return n;
}

// BigInt -> 8-byte little-endian hex (for GDB 'P' register writes)
function bigToLeHex(n) {
    let s = "";
    for (let i = 0; i < 8; i++) {
        s += (Number(n & 0xFFn)).toString(16).padStart(2, "0");
        n >>= 8n;
    }
    return s;
}

// pull register `num` (GDB hex index, e.g. "00"=x0, "20"=pc) out of a T stop-reply
function reg(stop, num) {
    const m = new RegExp(num + ":([0-9a-f]{16});").exec(stop);
    return m ? leHexToBig(m[1]) : null;
}

// little-endian 4-byte instruction hex -> BRK immediate (#imm16), or -1 if not a BRK
function brkImm(instrHex) {
    if (!instrHex || instrHex.length < 8) return -1;
    const w = parseInt(instrHex.substr(0, 8).match(/../g).reverse().join(""), 16);
    // BRK: 1101 0100 001 imm16 00000  (mask 0xFFE0001F == 0xD4200000)
    if ((w & 0xFFE0001F) >>> 0 !== 0xD4200000) return -1;
    return (w >> 5) & 0xFFFF;
}

log(`[rl-jit] attaching to pid ${pid}`);
send_command(`vAttach;${pid.toString(16)}`);
log(`[rl-jit] attached; servicing HotSpot code-cache (brk #0x69 + map-on-fault) …`);

let served = 0;
let faulted = 0;
const FAULT_CAP = 4000;   // generous: the interpreter+stubs touch many 16 KB pages
while (true) {
    const stop = send_command(`c`);                 // continue; returns on next stop
    if (!stop || stop.length === 0) { continue; }
    const k = stop[0];
    if (k === "W" || k === "X") {                    // process exited / terminated
        log(`[rl-jit] target ended (${stop}); served ${served} JIT request(s).`);
        break;
    }
    if (k !== "T") { log(`[rl-jit] unexpected stop reply: ${stop}`); continue; }

    const tidM = /thread:([0-9a-f]+);/.exec(stop);
    const pc = reg(stop, "20");
    if (!tidM || pc === null) { log(`[rl-jit] missing tid/pc; continue`); continue; }
    const tid = tidM[1];

    const instr = send_command(`m${pc.toString(16)},4`);
    const imm = brkImm(instr);

    if (imm !== 0x69) {
        // Not a brk. On this jre21, libjvm's brk #0x69 path is gated behind an
        // in-sandbox TXM check (DeviceRequiresTXMWorkaround reads /private/preboot,
        // which the app can't), so it falls back to mmap(PROT_READ|PROT_EXEC) — a
        // page that is NOT actually executable on TXM. HotSpot jumps into it and we
        // get an instruction-abort here. Make that page executable and retry rather
        // than forwarding the (fatal) signal.
        const PAGE = 0x4000n;                        // iOS 16 KB pages
        const base = pc & ~(PAGE - 1n);
        const pr = prepare_memory_region(Number(base), Number(PAGE));
        faulted++;
        log(`[rl-jit] fault@0x${pc.toString(16)} instr=${instr} -> map RX page 0x${base.toString(16)} (prepare=${pr})`);
        if (faulted > FAULT_CAP) {
            log(`[rl-jit] fault cap (${FAULT_CAP}) hit — giving up to avoid a loop`);
            break;
        }
        continue;                                    // loop re-issues `c` (no signal) -> retry instruction
    }

    const size = reg(stop, "00");                    // BreakGetJITMapping(size_t bytes): size in x0

    // Step over the `brk` so the trailing `ret` returns into HotSpot.
    send_command(`P20=${bigToLeHex(pc + 4n)};thread:${tid};`);

    if (size === null || size === 0n) {
        log(`[rl-jit] brk #0x69 with zero/missing size; returning NULL`);
        send_command(`P0=${bigToLeHex(0n)};thread:${tid};`);
        continue;
    }

    // Allocate a fresh RX region of `size` and wire it up for TXM execution.
    const rx = send_command(`_M${size.toString(16)},rx`);
    if (!rx || rx.length === 0 || rx[0] === "E") {
        log(`[rl-jit] _M alloc FAILED for size 0x${size.toString(16)}: ${rx}`);
        send_command(`P0=${bigToLeHex(0n)};thread:${tid};`);
        continue;
    }
    const addr = BigInt(`0x${rx}`);
    const pr = prepare_memory_region(Number(addr), Number(size));

    // Return the RX region address in x0.
    send_command(`P0=${bigToLeHex(addr)};thread:${tid};`);
    served++;
    log(`[rl-jit] #${served} brk #0x69 size=0x${size.toString(16)} -> RX 0x${addr.toString(16)} (prepare=${pr})`);
}
