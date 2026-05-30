#!/usr/bin/env bash
# Build the fake libawt_xawt.dylib (arm64 iphoneos) and drop it into the embedded jre.
# See awtxawt_stub.c for why. Usage: ./build-libawt_xawt.sh
set -euo pipefail
cd "$(dirname "$0")"

OUT=libawt_xawt.dylib
JRE_LIB=../jre/lib

xcrun --sdk iphoneos clang -arch arm64 -mios-version-min=17.0 -dynamiclib \
  -install_name @rpath/libawt_xawt.dylib -o "$OUT" awtxawt_stub.c

echo "built:"; file "$OUT"
nm -gU "$OUT" | grep -i onload || true

if [ -d "$JRE_LIB" ]; then
  cp "$OUT" "$JRE_LIB/$OUT"
  echo "installed -> $JRE_LIB/$OUT (the Xcode resign-jre phase will sign it)"
else
  echo "NOTE: $JRE_LIB not found — copy $OUT into the jre's lib/ manually."
fi
