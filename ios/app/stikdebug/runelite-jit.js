// runelite-ios — StikDebug JIT script for the on-device HotSpot (jre21, iOS 26 TXM)
// ---------------------------------------------------------------------------------
// Assign this to HelloShell in StikDebug. It services HotSpot's code-cache memory
// requests so the JVM can generate executable code on a TXM device.
//
// Primary path — brk #0x69 (BreakGetJITMapping): our jre21's libjvm requests an RX
// code-cache region via `char* BreakGetJITMapping(size_t bytes)` == `brk #0x69; ret`
// (size in x0, RX address returned in x0). On a stock jre21 this is gated behind an
// in-sandbox TXM check that fails for an app; we patch libjvm (dev-tools/patch-jre-txm.sh)
// so the brk path is used. Here we allocate an RX region of `size`, wire it up via
// prepare_memory_region, and return its address — HotSpot then mirrors it RW to write
// code and executes the RX view. Stays attached (the code cache can grow).
//
// Safety nets (should not fire once libjvm is patched): a not-yet-executable page that
// faults on fetch is mapped once (deduped — never re-mapped, so no infinite loop); any
// other stop (a real HotSpot brk like #0x1 ShouldNotReachHere, or a re-fault) is
// forwarded to the VM so it handles/aborts instead of the debugger spinning.
//
// Uses only StikDebug builtins (get_pid, send_command, prepare_memory_region, log),
// the GDB remote protocol, and the AArch64 BRK encoding.

const pid = get_pid();
const PAGE = 0x4000n;                 // iOS 16 KB pages
const mapped = new Set();             // pages already prepared (dedup -> no fault loop)
let served = 0, faulted = 0, forwarded = 0;

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
log(`[rl-jit] attached; servicing HotSpot code-cache (brk #0x69 primary) …`);

while (true) {
    const stop = send_command(`c`);
    if (!stop || stop.length === 0) continue;
    const k = stop[0];
    if (k === "W" || k === "X") {
        log(`[rl-jit] target ended (${stop}); served ${served}, faults ${faulted}, fwd ${forwarded}`);
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

    // --- Primary: brk #0x69 = BreakGetJITMapping(size) ---------------------------
    if (imm === 0x69) {
        const size = reg(stop, "00");                          // size in x0
        send_command(`P20=${bigToLeHex(pc + 4n)};thread:${tid};`);   // step over the brk
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
        send_command(`P0=${bigToLeHex(addr)};thread:${tid};`);       // return RX addr in x0
        served++;
        log(`[rl-jit] #${served} brk#0x69 size=0x${size.toString(16)} -> RX 0x${addr.toString(16)} (prepare=${pr})`);
        continue;
    }

    // --- Safety net: instruction-fetch abort on a not-yet-mapped page ------------
    if (imm < 0) {
        const base = pc & ~(PAGE - 1n);
        const key = base.toString(16);
        if (!mapped.has(key)) {
            mapped.add(key);
            const pr = prepare_memory_region(Number(base), Number(PAGE));
            faulted++;
            log(`[rl-jit] fault@0x${pc.toString(16)} -> map RX 0x${key} (prepare=${pr})`);
            continue;                                          // retry (no signal)
        }
        // already mapped and still faulting here -> not a perms issue; forward.
    }

    // --- Anything else (real HotSpot brk e.g. #0x1, or a re-fault): forward -------
    // Let the VM's own handler process/abort instead of the debugger looping.
    forwarded++;
    log(`[rl-jit] forward pc=0x${pc.toString(16)} instr=${instr} brk=${imm} sig=${sig}`);
    send_command(`vCont;C${sig}:${tid}`);
}
