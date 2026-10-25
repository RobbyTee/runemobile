#!/usr/bin/env bash
# One-shot local setup for building RuneMobile in Xcode on your own Mac (fork workflow).
#
# Does what .github/workflows/ios-fork-build.yml does on GitHub's Mac, but leaves you with an Xcode project you can
# Run straight to a plugged-in iPhone, signed with your own Apple Developer team:
#   1. checks/installs the toolchain (Xcode CLT, Homebrew xcodegen + openjdk@21)
#   2. writes ios/dev-tools/signing.env (DEVELOPMENT_TEAM, gitignored) from $1 or a prompt
#   3. provisions ios/dev-tools/jre: the pinned Amethyst zip (same SHA-256 pin + source as CI), patched by
#      patch-jre-txm.sh — skipped if already present
#   4. builds + stages the 4 embedded jars (same sequence as tools/ci/build-release-artifacts.sh)
#   5. xcodegen generate, then opens RuneMobile.xcodeproj
#
# Usage (from anywhere in the repo):  ios/dev-tools/setup-local-mac.sh [TEAM_ID]
# Re-run any time; pass --jars-only to just rebuild/re-stage the jars + regenerate the project after a code change.
set -euo pipefail

log()  { printf '\n\033[1;35m>> %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

JARS_ONLY=0
TEAM=""
for a in "$@"; do
	case "$a" in
		--jars-only) JARS_ONLY=1 ;;
		*) TEAM="$a" ;;
	esac
done

JRE_BUNDLE_URL=https://github.com/audaro/runemobile/releases/download/ios-jre-dep/jre.tar.gz
JRE_BUNDLE_MEMBER=ios-jre/jre21-ios-aarch64.zip
JRE_SHA256=2c405fcc8c1cf95773617a93a2b090192191c871874cf1408f7a52500d8f0c71
JRE_DIR=ios/dev-tools/jre

# ── 1) toolchain ─────────────────────────────────────────────────────────────────────────────────────
[ "$(uname -s)" = "Darwin" ] || die "run this on your Mac"
have xcodebuild || die "xcodebuild not found — install Xcode, open it once, then: sudo xcode-select -s /Applications/Xcode.app"
have brew || die "Homebrew not found — install it from https://brew.sh"
have xcodegen || { log "brew install xcodegen"; brew install xcodegen; }
[ -d "$(brew --prefix)/opt/openjdk@21" ] || { log "brew install openjdk@21"; brew install openjdk@21; }
JAVA_HOME="${JAVA_HOME:-$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
[ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME has no java: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21' || die "JAVA_HOME is not a Java 21: $JAVA_HOME"
export JAVA_HOME

if [ "$JARS_ONLY" = 0 ]; then
	# ── 2) signing team ──────────────────────────────────────────────────────────────────────────────
	if [ -z "$TEAM" ] && [ -f ios/dev-tools/signing.env ]; then
		. ios/dev-tools/signing.env; TEAM="${DEVELOPMENT_TEAM:-}"
	fi
	if [ -z "$TEAM" ]; then
		echo "Your Team ID is the 10-character code at https://developer.apple.com/account → Membership details."
		read -r -p "Apple Developer Team ID: " TEAM
	fi
	[[ "$TEAM" =~ ^[A-Z0-9]{10}$ ]] || die "Team ID should be 10 letters/digits, got: '$TEAM'"
	printf 'export DEVELOPMENT_TEAM=%s\n' "$TEAM" > ios/dev-tools/signing.env
	log "signing.env → DEVELOPMENT_TEAM=$TEAM"

	# ── 3) iOS JRE ───────────────────────────────────────────────────────────────────────────────────
	if [ -f "$JRE_DIR/lib/server/libjvm.dylib" ]; then
		log "iOS JRE already at $JRE_DIR (delete it to re-provision)"
	else
		log "Fetch, verify and patch the iOS JRE"
		W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
		mkdir -p "$W/bundle" "$W/unzip" "$W/jre"
		curl -fSL --retry 3 -o "$W/bundle.tar.gz" "$JRE_BUNDLE_URL"
		tar -xzf "$W/bundle.tar.gz" -C "$W/bundle" "$JRE_BUNDLE_MEMBER"
		ACTUAL="$(shasum -a 256 "$W/bundle/$JRE_BUNDLE_MEMBER" | awk '{print $1}')"
		[ "$ACTUAL" = "$JRE_SHA256" ] || die "iOS JRE SHA-256 mismatch: expected $JRE_SHA256, got $ACTUAL"
		echo "iOS JRE SHA-256 OK"
		unzip -q "$W/bundle/$JRE_BUNDLE_MEMBER" -d "$W/unzip"
		TARBALL="$(find "$W/unzip" -name 'jre21-ios-arm64-*-release.tar.xz' -print -quit)"
		[ -n "$TARBALL" ] || die "no jre21-ios-arm64-*-release.tar.xz inside the zip"
		tar -xf "$TARBALL" -C "$W/jre"
		LIBJVM="$(find "$W/jre" -path '*/lib/server/libjvm.dylib' -print -quit)"
		[ -n "$LIBJVM" ] || die "no lib/server/libjvm.dylib in the extracted JRE"
		JRE_ROOT="$(cd "$(dirname "$LIBJVM")/../.." && pwd)"
		grep -q 'JAVA_VERSION="21' "$JRE_ROOT/release" || die "extracted JRE is not Java 21"
		ios/dev-tools/patch-jre-txm.sh "$JRE_ROOT/lib/server/libjvm.dylib"
		rm -rf "$JRE_DIR"
		mv "$JRE_ROOT" "$JRE_DIR"
	fi
fi

# ── 4) jars ──────────────────────────────────────────────────────────────────────────────────────────
log "Build + stage the iOS jars (shadowJar, gpu-routec, gpu-zone, probes, l1) — first run takes a while"
APPDIR=ios/app
rm -f "$APPDIR"/jvm-resources/*.jar
./gradlew --console=plain :client:shadowJar
( cd ios/dev-tools/gpu-routec && ./stage-ios-gpu-jar.sh )
( cd ios/dev-tools/gpu-zone && ./stage-ios-zone-jar.sh )
( cd "$APPDIR" && ./build-probes.sh )
( cd ios/dev-tools/gpu-routec/l1 && ./build-l1-probe.sh )
for j in client-shaded.jar client-rlhd.jar probes.jar routec-l1.jar; do
	[ -f "$APPDIR/jvm-resources/$j" ] || die "staged jar missing: $j"
done

# ── 5) Xcode project ─────────────────────────────────────────────────────────────────────────────────
[ -f ios/dev-tools/signing.env ] && . ios/dev-tools/signing.env
log "xcodegen generate"
( cd "$APPDIR" && xcodegen generate )
log "Done. Opening RuneMobile.xcodeproj — pick your iPhone as the run destination and press Run."
open "$APPDIR/RuneMobile.xcodeproj"
