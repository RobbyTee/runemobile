#!/usr/bin/env bash
# One-time, per-machine provisioning of the embedded iOS JRE at ios/dev-tools/jre (gitignored) — the one input no
# other staging script produces (docs/dev/setup.md, "The exception: the iOS JRE"). Automates JRE21-INVENTORY.md:
#   1. fetch the pinned Amethyst zip and check it against the inventory's SHA-256. Its original host
#      (crystall1ne.dev) now 404s and Amethyst's new host serves a newer rebuild whose libjvm no longer matches
#      patch-jre-txm.sh's offsets, so the zip is taken from the public `ios-jre-dep` release bundle, which carries
#      it verbatim. The checksum is the trust decision, not the host.
#   2. patch-jre-txm.sh (verifies the original bytes before NOPing them)
#   3. fake-xawt/build-libawt_xawt.sh (adds lib/libawt_xawt.dylib; without it AWT fails to load and nothing boots)
#
# Usage: ios/dev-tools/setup-ios-jre.sh [--force]    (--force re-provisions over an existing jre/)
set -euo pipefail

die() { printf '!! %s\n' "$*" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
JRE_DIR=ios/dev-tools/jre
BUNDLE_URL=https://github.com/audaro/runemobile/releases/download/ios-jre-dep/jre.tar.gz
BUNDLE_MEMBER=ios-jre/jre21-ios-aarch64.zip
JRE_SHA256=2c405fcc8c1cf95773617a93a2b090192191c871874cf1408f7a52500d8f0c71

[ "$(uname -s)" = "Darwin" ] || die "run this on a Mac (the libawt_xawt step needs Xcode's iphoneos SDK)"
if [ -f "$JRE_DIR/lib/server/libjvm.dylib" ] && [ "${1:-}" != --force ]; then
	echo "iOS JRE already at $JRE_DIR (pass --force to re-provision)"; exit 0
fi

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/bundle" "$W/unzip" "$W/jre"
echo "== fetch $BUNDLE_URL"
curl -fSL --retry 3 -o "$W/bundle.tar.gz" "$BUNDLE_URL"
tar -xzf "$W/bundle.tar.gz" -C "$W/bundle" "$BUNDLE_MEMBER"
ACTUAL="$(shasum -a 256 "$W/bundle/$BUNDLE_MEMBER" | awk '{print $1}')"
[ "$ACTUAL" = "$JRE_SHA256" ] || die "iOS JRE SHA-256 mismatch: expected $JRE_SHA256, got $ACTUAL"
echo "== SHA-256 OK"
unzip -q "$W/bundle/$BUNDLE_MEMBER" -d "$W/unzip"
TARBALL="$(find "$W/unzip" -name 'jre21-ios-arm64-*-release.tar.xz' -print -quit)"
[ -n "$TARBALL" ] || die "no jre21-ios-arm64-*-release.tar.xz inside the zip"
tar -xf "$TARBALL" -C "$W/jre"
LIBJVM="$(find "$W/jre" -path '*/lib/server/libjvm.dylib' -print -quit)"
[ -n "$LIBJVM" ] || die "no lib/server/libjvm.dylib in the extracted JRE"
JRE_ROOT="$(cd "$(dirname "$LIBJVM")/../.." && pwd)"
grep -q 'JAVA_VERSION="21' "$JRE_ROOT/release" || die "extracted JRE is not Java 21"

rm -rf "$JRE_DIR"
mv "$JRE_ROOT" "$JRE_DIR"
echo "== patch-jre-txm.sh"
ios/dev-tools/patch-jre-txm.sh "$JRE_DIR/lib/server/libjvm.dylib"
echo "== fake-xawt"
ios/dev-tools/fake-xawt/build-libawt_xawt.sh
[ -f "$JRE_DIR/lib/libawt_xawt.dylib" ] || die "lib/libawt_xawt.dylib was not installed"
echo "iOS JRE ready at $JRE_DIR. Back it up: docs/dev/setup.md treats it as local state you keep."
