#!/usr/bin/env bash
# GPU Route C — compile-check the device JNI GL bridge for iOS arm64 (DEVICE-ONLY runtime; this is the static
# gate before a device build). Compiles RouteCBridge.m + the generated routec_gl_jni.m against the iOS SDK +
# the bundled jni.h. GREEN = both produce object files with zero `error:` diagnostics. OpenGLES deprecation
# warnings (deprecated-since-iOS-12, works on iOS 26) are silenced via GLES_SILENCE_DEPRECATION.
set -euo pipefail
cd "$(dirname "$0")"

SDK=$(xcrun --sdk iphoneos --show-sdk-path)
JNI_INC=../../app/HelloShell/jni
OUT=$(mktemp -d)
CFLAGS=(-arch arm64 -isysroot "$SDK" -fobjc-arc -DGLES_SILENCE_DEPRECATION=1 -Wall
        -Wno-deprecated-declarations -I app -I jni -I "$JNI_INC")

rc=0
for src in app/RouteCBridge.m jni/routec_gl_jni.m; do
	obj="$OUT/$(basename "${src%.m}").o"
	log="$OUT/$(basename "${src%.m}").log"
	if clang "${CFLAGS[@]}" -c "$src" -o "$obj" 2>"$log"; then
		ERRS=$(grep -c 'error:' "$log" || true)
		SZ=$(stat -f%z "$obj" 2>/dev/null || echo 0)
		echo "OK   $src   (obj ${SZ}B, ${ERRS} errors$( [ "$(grep -c 'warning:' "$log" || true)" -gt 0 ] && echo ", $(grep -c 'warning:' "$log") warnings" ))"
		[ "$ERRS" = 0 ] && [ "$SZ" -gt 0 ] || rc=1
	else
		echo "FAIL $src:"; grep -E 'error:' "$log" | head -30; rc=1
	fi
done

if [ "$rc" = 0 ]; then echo "MEGAVERDICT = ROUTEC_BRIDGE_COMPILES_ARM64"; else echo "MEGAVERDICT = ROUTEC_BRIDGE_COMPILE_FAIL" >&2; fi
rm -rf "$OUT"
exit $rc
