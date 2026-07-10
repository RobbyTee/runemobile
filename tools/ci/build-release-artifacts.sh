#!/usr/bin/env bash
# build-release-artifacts.sh — build the shippable RuneMobile APK + IPA in one pass, ready to publish.
#
# This script IS the build (the CI workflow, .github/workflows/runemobile-release.yml, is a thin wrapper that
# calls it — same "script is the source of truth" convention as android/dev-tools/ci/release-gate.sh). It is
# fully runnable on a developer Mac, so the pipeline has real, reproducible evidence outside GitHub Actions.
#
# What it produces (into $DIST, default dist/ci):
#   RuneMobile-<version>.apk   release-signed APK (real 45:E3 keystore when configured, else debug key)
#   RuneMobile-<version>.ipa   Payload IPA (real dev provisioning profile; the outer seal breaks when the
#                              embedded JRE is re-signed — SideStore/AltStore re-sign the whole bundle on
#                              install, so that is expected and harmless)
#   runelite-jit.js            the StikDebug JIT launch template, copied verbatim
#   manifest.env              version / sha / apk fingerprint, sourced by publish-rolling-release.sh
#
# Secrets stay in $HOME, never in the repo or in GitHub secrets. Config is read from $CI_CONFIG_DIR
# (default ~/.runemobile-ci): keystore.properties, signing.env (DEVELOPMENT_TEAM), keychain.pw.
# See tools/ci/README.md for the one-time setup.
#
# Usage:
#   tools/ci/build-release-artifacts.sh                 # build both ports (needs macOS for iOS)
#   PLATFORMS=android tools/ci/build-release-artifacts.sh   # APK only (e.g. a Linux runner)
#   REUSE_JARS=1 tools/ci/build-release-artifacts.sh    # skip the heavy iOS jar rebuild if already staged
#   BUILD_NUMBER=42 GIT_SHA=abc1234 tools/ci/build-release-artifacts.sh   # stamp a rolling build id
#
# Env knobs (all optional):
#   PLATFORMS      space list of "android" "ios" (default: "android ios" on macOS, "android" elsewhere)
#   BUILD_NUMBER   monotonic build number (default: 0 for a local build; CI passes github.run_number)
#   GIT_SHA        short commit sha for the version-name suffix (default: `git rev-parse --short HEAD`)
#   REUSE_JARS     1 = reuse already-staged ios/app/jvm-resources jars instead of rebuilding them
#   DIST           output directory (default: <repo>/dist/ci)
#   CI_CONFIG_DIR  signing/config dir (default: ~/.runemobile-ci)
set -euo pipefail

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
cd "$ROOT"

DIST="${DIST:-$ROOT/dist/ci}"
CI_CONFIG_DIR="${CI_CONFIG_DIR:-$HOME/.runemobile-ci}"
BUILD_NUMBER="${BUILD_NUMBER:-0}"
GIT_SHA="${GIT_SHA:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
REUSE_JARS="${REUSE_JARS:-0}"

# Default platform set: iOS needs Xcode, so only attempt it on macOS.
if [ -z "${PLATFORMS:-}" ]; then
	if [ "$(uname -s)" = "Darwin" ]; then PLATFORMS="android ios"; else PLATFORMS="android"; fi
fi

log()  { printf '\n\033[1;35m>> %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# ── Version scheme ────────────────────────────────────────────────────────────────────────────────────
# Semver base comes from android/app/build.gradle.kts (single source of truth). Rolling CI builds append a
# -master.<N> suffix and use a versionCode base of 1_000_000 + N so every merge-built APK is strictly newer
# than the last AND stays above the semver-derived range (which maxes at 99*10000+99*100+99 = 999999).
SEMVER="$(sed -nE 's/^[[:space:]]*val verMajor = ([0-9]+).*/\1/p;' android/app/build.gradle.kts | head -1)"
_mn="$(sed -nE 's/^[[:space:]]*val verMinor = ([0-9]+).*/\1/p;' android/app/build.gradle.kts | head -1)"
_pt="$(sed -nE 's/^[[:space:]]*val verPatch = ([0-9]+).*/\1/p;' android/app/build.gradle.kts | head -1)"
SEMVER="${SEMVER}.${_mn}.${_pt}"
[ "$SEMVER" != ".." ] || die "could not parse semver from android/app/build.gradle.kts"

if [ "$BUILD_NUMBER" -gt 0 ] 2>/dev/null; then
	VERSION="${SEMVER}-master.${BUILD_NUMBER}"
	VERSION_CODE=$(( 1000000 + BUILD_NUMBER ))
	VERSION_NAME_SUFFIX="-master.${BUILD_NUMBER}+${GIT_SHA}"
else
	VERSION="$SEMVER"
	VERSION_CODE=""              # empty → gradle uses the plain semver code
	VERSION_NAME_SUFFIX=""
fi
JARVER="$(sed -n 's/^project\.build\.version=//p' gradle.properties)"; [ -n "$JARVER" ] || die "no project.build.version"

log "RuneMobile build — version=$VERSION  code=${VERSION_CODE:-semver}  sha=$GIT_SHA  jars=$JARVER  platforms=[$PLATFORMS]"
rm -rf "$DIST"; mkdir -p "$DIST"

# ── Config (secrets stay in $HOME) ────────────────────────────────────────────────────────────────────
# android/keystore.properties selects the release signing key (absent → gradle falls back to the debug key,
# so a keyless run still builds an installable-but-differently-signed APK). Copy it in from the CI config
# dir if the working tree doesn't already carry it (a fresh runner checkout won't have the gitignored file).
if [ ! -f android/keystore.properties ] && [ -f "$CI_CONFIG_DIR/keystore.properties" ]; then
	cp "$CI_CONFIG_DIR/keystore.properties" android/keystore.properties
	log "staged android/keystore.properties from $CI_CONFIG_DIR"
fi

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
[ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME has no java: $JAVA_HOME (set JAVA_HOME to an OpenJDK 21)"
export JAVA_HOME
GRADLEW=(./gradlew --no-daemon --console=plain)

wants() { case " $PLATFORMS " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

# ── Android ───────────────────────────────────────────────────────────────────────────────────────────
if wants android; then
	log "Android :app:assembleRelease (self-stages JRE + jars + native)"
	GRADLE_ARGS=()
	[ -n "$VERSION_CODE" ] && GRADLE_ARGS+=("-PversionCodeOverride=$VERSION_CODE")
	[ -n "$VERSION_NAME_SUFFIX" ] && GRADLE_ARGS+=("-PversionNameSuffix=$VERSION_NAME_SUFFIX")
	( cd android && "${GRADLEW[@]}" :app:assembleRelease "${GRADLE_ARGS[@]}" ) || die "assembleRelease failed"
	APK="android/app/build/outputs/apk/release/app-release.apk"
	[ -f "$APK" ] || die "no app-release.apk emitted"
	cp "$APK" "$DIST/RuneMobile-${VERSION}.apk"
	log "→ $DIST/RuneMobile-${VERSION}.apk ($(du -h "$APK" | cut -f1))"
fi

# ── iOS (macOS only) ──────────────────────────────────────────────────────────────────────────────────
APK_FP=""
if wants ios; then
	[ "$(uname -s)" = "Darwin" ] || die "iOS build requested but not on macOS"
	have xcodegen  || die "xcodegen not found (brew install xcodegen)"
	have xcodebuild || die "xcodebuild not found (install Xcode)"
	[ -f "$CI_CONFIG_DIR/signing.env" ] && . "$CI_CONFIG_DIR/signing.env" || { [ -f ios/dev-tools/signing.env ] && . ios/dev-tools/signing.env; }
	[ -n "${DEVELOPMENT_TEAM:-}" ] || die "DEVELOPMENT_TEAM unset — add it to $CI_CONFIG_DIR/signing.env"

	APPDIR="ios/app"
	# 1) Stage the 4 embedded jars (shaded client + rlhd + probes + routec-l1). Reuse if asked and present.
	NEED_JARS=1
	if [ "$REUSE_JARS" = 1 ]; then
		NEED_JARS=0
		for j in "client-${JARVER}-shaded.jar" "client-${JARVER}-rlhd.jar" probes.jar routec-l1.jar; do
			[ -f "$APPDIR/jvm-resources/$j" ] || NEED_JARS=1
		done
	fi
	if [ "$NEED_JARS" = 1 ]; then
		log "Stage iOS jars (shadowJar + gpu-routec + probes + l1)"
		"${GRADLEW[@]}" :client:shadowJar || die "shadowJar failed"
		( cd ios/dev-tools/gpu-routec && JAVA_HOME="$JAVA_HOME" ./stage-ios-gpu-jar.sh ) || die "stage-ios-gpu-jar failed"
		( cd "$APPDIR" && JAVA_HOME="$JAVA_HOME" ./build-probes.sh ) || die "build-probes failed"
		( cd ios/dev-tools/gpu-routec/l1 && JAVA_HOME="$JAVA_HOME" ./build-l1-probe.sh ) || die "build-l1-probe failed"
	else
		log "Reusing already-staged iOS jars"
	fi
	for j in "client-${JARVER}-shaded.jar" "client-${JARVER}-rlhd.jar" probes.jar routec-l1.jar; do
		[ -f "$APPDIR/jvm-resources/$j" ] || die "staged jar missing: $j"
	done

	# 2) Unlock the dedicated build keychain so codesign works from a non-interactive (service/tmux) session.
	#    (The login keychain may be locked or out of the search list under a launchd runner.)
	if [ -f "$CI_CONFIG_DIR/keychain.pw" ] && [ -f "$HOME/Library/Keychains/build.keychain-db" ]; then
		security unlock-keychain -p "$(cat "$CI_CONFIG_DIR/keychain.pw")" "$HOME/Library/Keychains/build.keychain-db"
		security list-keychains -d user -s "$HOME/Library/Keychains/build.keychain-db" "$HOME/Library/Keychains/login.keychain-db" >/dev/null
		log "build.keychain unlocked + in search list"
	fi

	# 3) Generate the xcodeproj and build a signed device .app (no device needed).
	log "xcodegen generate"
	( cd "$APPDIR" && xcodegen generate >/dev/null ) || die "xcodegen failed"
	DD="$APPDIR/build/dd-ci"
	log "xcodebuild RuneMobile (Release, generic/iOS, signed)"
	xcodebuild -project "$APPDIR/RuneMobile.xcodeproj" -scheme RuneMobile -configuration Release \
		-destination 'generic/platform=iOS' -derivedDataPath "$DD" -allowProvisioningUpdates \
		MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="${BUILD_NUMBER:-1}" build || die "xcodebuild failed"
	APP="$DD/Build/Products/Release-iphoneos/RuneMobile.app"
	[ -d "$APP" ] || die "no .app produced at $APP"

	# 4) Package Payload/*.app -> IPA and stamp the version (Info.plist hardcodes it, so xcodebuild's
	#    MARKETING_VERSION is ignored — SideStore re-signs on install, so touching the bundle is harmless).
	log "package IPA"
	WORK="$DIST/ipa-work"; rm -rf "$WORK"; mkdir -p "$WORK/Payload"
	cp -R "$APP" "$WORK/Payload/"
	/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" -c "Set :CFBundleVersion ${BUILD_NUMBER:-1}" \
		"$WORK/Payload/RuneMobile.app/Info.plist" >/dev/null
	( cd "$WORK" && zip -qry "$DIST/RuneMobile-${VERSION}.ipa" Payload )
	rm -rf "$WORK"
	log "→ $DIST/RuneMobile-${VERSION}.ipa ($(du -h "$DIST/RuneMobile-${VERSION}.ipa" | cut -f1))"
fi

# ── The StikDebug JIT template rides along with every iOS release ──────────────────────────────────────
cp ios/app/stikdebug/runelite-jit.js "$DIST/runelite-jit.js"

# ── Fingerprint the APK for the release notes / signing verification ──────────────────────────────────
if wants android; then
	APKSIGNER="$(command -v apksigner || true)"
	if [ -z "$APKSIGNER" ]; then
		for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
			[ -n "$d" ] || continue
			c="$(ls "$d"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"; [ -n "$c" ] && APKSIGNER="$c" && break
		done
	fi
	[ -n "$APKSIGNER" ] && APK_FP="$("$APKSIGNER" verify --print-certs "$DIST/RuneMobile-${VERSION}.apk" 2>/dev/null | sed -nE 's/.*SHA-256 digest: ([0-9a-f]+).*/\1/p' | head -1)"
fi

# ── Manifest for the publish step ─────────────────────────────────────────────────────────────────────
{
	echo "VERSION=$VERSION"
	echo "BUILD_NUMBER=$BUILD_NUMBER"
	echo "GIT_SHA=$GIT_SHA"
	echo "APK_FINGERPRINT=$APK_FP"
	echo "DIST=$DIST"
} > "$DIST/manifest.env"

log "DONE — artifacts in $DIST:"
ls -la "$DIST" | sed 's/^/    /'
