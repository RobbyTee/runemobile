#!/bin/bash
# Patch the bundled jre21 libjvm so HotSpot uses the debugger brk #0x69 code-cache
# path on our (sandboxed) iOS app.
#
# Why: libjvm's get_debug_jit_mapping() only calls BreakGetJITMapping (brk #0x69)
# when  MirrorMappedCodeCache==1 && DeviceRequiresTXMWorkaround().
# DeviceRequiresTXMWorkaround() detects TXM by reading
# /private/preboot/.../Ap,TrustedExecutionMonitor.img4 — which the iOS app sandbox
# cannot open — so it returns 0, and libjvm falls back to mmap(PROT_READ|PROT_EXEC),
# which is NOT executable on a TXM device -> instruction-abort on generated code.
#
# Fix: NOP the `cbz w0, <mmap-fallback>` that branches on DeviceRequiresTXMWorkaround
# (at file offset 0x81fc8c). We deliberately LEAVE the MirrorMappedCodeCache flag
# check (0x81fc80) intact, so the brk path only engages when mirror mode is actually
# on (our -XX:+MirrorMappedCodeCache). After this, on a real TXM device HotSpot asks
# the attached StikDebug script (runelite-jit.js, brk #0x69 handler) for one properly
# mirrored RX region instead of faulting page-by-page.
#
# Idempotent + verified. Run after fetching/extracting the JRE (see JRE21-INVENTORY.md).
# The app's resign-jre.sh re-signs the patched dylib at build time.
set -euo pipefail

LIBJVM="${1:-$(dirname "$0")/jre/lib/server/libjvm.dylib}"
OFF=$((0x81fc8c))
ORIG="e0 01 00 34"   # cbz w0, 0x81fcc8   (little-endian 0x340001e0)
NOP="1f 20 03 d5"    # nop                (little-endian 0xd503201f)

if [ ! -f "$LIBJVM" ]; then echo "patch-jre-txm: not found: $LIBJVM" >&2; exit 1; fi

cur=$(xxd -s "$OFF" -l 4 -p "$LIBJVM")
if [ "$cur" = "1f2003d5" ]; then
    echo "patch-jre-txm: already patched (nop at 0x$(printf %x $OFF)) — skipping"
    exit 0
fi
if [ "$cur" != "e0010034" ]; then
    echo "patch-jre-txm: UNEXPECTED bytes at 0x$(printf %x $OFF): $cur (expected e0010034)." >&2
    echo "  The JRE build may differ from the pinned one — re-derive the offset before patching." >&2
    exit 1
fi

# Write the 4 NOP bytes at OFF.
printf '\x1f\x20\x03\xd5' | dd of="$LIBJVM" bs=1 seek="$OFF" count=4 conv=notrunc status=none
echo "patch-jre-txm: NOPed DeviceRequiresTXMWorkaround gate at 0x$(printf %x $OFF) in $LIBJVM"
echo "patch-jre-txm: verify -> $(xxd -s "$OFF" -l 4 -p "$LIBJVM")"
