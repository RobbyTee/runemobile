// runelite-ios — StikDebug JIT script for the on-device HotSpot (jre21, iOS 26 TXM)
// ---------------------------------------------------------------------------------
// Assign this to HelloShell in StikDebug. It services HotSpot's one code-cache
// reservation so the JVM can generate executable code on a TXM device, and then
// stays attached (idle, non-intercepting) so the StikDebug session/process is not
// reaped.
//
// Why this shape (UPDATE 17 experiment — isolate the corruption fix):
//   The detach-after-first parity script (UPDATE 16) ran RuneLite with NO memory
//   corruption (cleanest boot yet) but the process was terminated ~5-10 s after
//   `main` returned — and the device JetsamEvents prove it was NOT a memory kill,
//   so the most likely cause is the StikDebug session being torn down (we sent `D`
//   and exited the script). That parity script changed TWO things at once: it
//   (a) detached after the first brk AND (b) removed the old map-on-fault net.
//   Either could be why corruption stopped. This variant keeps change (b) — no
//   fault net — but does NOT detach: it stays attached and idle after the single
//   reservation, keeping the session open so the process lives (the continuous
//   baseline lived ~1 min). If RuneLite now boots WITHOUT corruption AND survives,
//   the fault net was the culprit and detaching was an unnecessary longevity cost.
//
// Lifecycle:
//   1. vAttach (grants CS_DEBUGGED → JIT permission).
//   2. Service the single brk #0x69 (BreakGetJITMapping): our jre21's libjvm
//      reserves the whole ReservedCodeCacheSize (64 MB) in ONE call at VM init —
//      `char* BreakGetJITMapping(size_t bytes)` == `brk #0x69; ret` (size in x0, RX
//      address returned in x0). Verified against the trusted device logs: exactly
//      one "[JIT26] Requesting 64 MB" per boot, used ~7% and never grown.
//      We allocate an RX region of `size`, wire it up via prepare_memory_region, and
//      return its address — HotSpot mirrors it RW to write code and executes RX.
//      (libjvm is patched, dev-tools/patch-jre-txm.sh, so the brk path is taken.)
//   3. Stay attached and keep continuing. With EXC_MASK_BAD_ACCESS cleared app-side,
//      HotSpot's implicit faults bypass the debugger entirely, so after the one brk
//      we receive no stops during normal compilation — we hold the session open
//      without intercepting. Any real stop (a HotSpot brk, or a fault from genuine
//      corruption) is FORWARDED to the VM's own handler (no map-on-fault net), so it
//      produces an hs_err instead of being silently absorbed.
//
// Uses only StikDebug builtins (get_pid, send_command, prepare_memory_region, log),
// the GDB remote protocol, and the AArch64 BRK encoding.

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
log(`[rl-jit] attached; servicing the single brk #0x69, then idle-attached (no fault net) …`);

while (true) {
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

    // --- brk #0x69 = BreakGetJITMapping(size): the one 64 MB reservation ----------
    if (imm === 0x69) {
        const size = reg(stop, "00");                          // size in x0
        send_command(`P20=${bigToLeHex(pc + 4n)};thread:${tid};`);   // step over the brk
        if (size === null || size === 0n) {
            log(`[rl-jit] brk#0x69 with size=0 (anomalous) — returning 0`);
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
        // Stay attached (idle). Do NOT detach — keeps the StikDebug session open so
        // the process is not reaped. We never re-intercept; subsequent stops forward.
        continue;
    }

    // --- Anything else (a real HotSpot brk, or a fault) ---------------------------
    // Forward to the VM's own handler — no map-on-fault absorption.
    forwarded++;
    log(`[rl-jit] forward pc=0x${pc.toString(16)} instr=${instr} brk=${imm} sig=${sig}`);
    send_command(`vCont;C${sig}:${tid}`);
}
