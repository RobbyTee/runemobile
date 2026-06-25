#!/usr/bin/env bash
# Android release gate — the single, runnable "a real gate exists and passes" check (GATE.md row 6).
#
# This script IS the gate. CI (.github/workflows/android-release-gate.yml) is a thin wrapper that calls it,
# but the gate is fully reproducible on a developer machine — so it has real, observable evidence even while
# GitHub Actions are disabled at the repo level (this fork ships an unapproved live-OSRS client and is never
# published to a marketplace, so CI is dormant-but-ready, not the source of truth). Findings covered:
#   PROD-QA-01          — build a signed :app:assembleRelease, lint it, run the shared-contract check (+ a
#                         device-smoke stage that is opt-in: it needs a real device / Firebase Test Lab pool).
#   PROD-RELEASE-ENG-07 — the cross-port contract check is no longer a bypassable local-only pre-commit hook;
#                         it is a required, non-bypassable stage of this gate (fail on a removed/changed entry).
#
# Usage:
#   release-gate.sh                     # contract + assembleRelease + lintRelease (the runnable gate)
#   release-gate.sh --contract-only     # fast pre-flight: just the cross-port contract diff
#   release-gate.sh --with-device-smoke # also run the Firebase Test Lab boot-to-first-frame smoke (needs gcloud+pool)
#   release-gate.sh --skip-build        # contract + lint only (skip the heavy assembleRelease)
#
# Exit: 0 = gate PASS, non-zero = a stage failed (the failing stage is named).
set -euo pipefail

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
ANDROID="$ROOT/android"
CONTRACT="$ROOT/tools/shared-contract/check-contract.sh"

run_build=1
run_lint=1
run_device_smoke=0
contract_only=0

for arg in "$@"; do
	case "$arg" in
		--contract-only)     contract_only=1 ;;
		--skip-build)        run_build=0 ;;
		--with-device-smoke) run_device_smoke=1 ;;
		-h|--help)           sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
		*) echo "release-gate: unknown arg '$arg' (see --help)" >&2; exit 64 ;;
	esac
done

hr() { printf '────────────────────────────────────────────────────────────────────────\n'; }
stage() { hr; printf '▶ %s\n' "$1"; hr; }
fail() { echo; echo "✋ GATE FAIL — stage: $1"; exit "${2:-1}"; }

# ── Stage 1: cross-port contract (PROD-RELEASE-ENG-07) ────────────────────────────────────────────────
# Required, non-bypassable. exit 0 = unchanged, 1 = additive-only (OK in CI — the other port simply won't
# call the new entry yet), 2 = a removed/renamed/re-signatured entry that can break the OTHER port.
stage "Contract gate (shared iOS/Android bridge + -D flags)"
set +e
contract_report="$("$CONTRACT" 2>&1)"; contract_rc=$?
set -e
echo "$contract_report"
case "$contract_rc" in
	0) echo "  → contract UNCHANGED." ;;
	1) echo "  → contract ADDITIVE-only (new entry, safe for the other port) — allowed." ;;
	2) fail "contract (a removed/changed entry risks breaking the other port — verify both ports, then refresh baseline.txt)" 2 ;;
	*) fail "contract (check-contract.sh errored, rc=$contract_rc)" "$contract_rc" ;;
esac

if [ "$contract_only" -eq 1 ]; then
	echo; echo "✅ contract-only pre-flight PASS."; exit 0
fi

# Gradle is invoked from android/. CI (and most dev shells) already export JAVA_HOME — honour it. Only when it is
# unset do we probe known JDK-21 locations, and only export one that actually exists on disk; if none match (e.g. a
# Linux distro path we don't list), leave JAVA_HOME unset so the gradlew wrapper / PATH `java` resolves it — never
# hard-code a macOS-only path that would point a Linux/Windows run at a non-existent home.
if [ -z "${JAVA_HOME:-}" ]; then
	for cand in \
		/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
		/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
		/usr/lib/jvm/temurin-21-jdk-amd64 \
		/usr/lib/jvm/java-21-openjdk-amd64 \
		/usr/lib/jvm/java-21-openjdk
	do
		if [ -x "$cand/bin/java" ]; then export JAVA_HOME="$cand"; break; fi
	done
fi
GRADLEW=(./gradlew --no-daemon --console=plain)

# ── Stage 2: assembleRelease (PROD-QA-01) ─────────────────────────────────────────────────────────────
# Self-stages the gitignored boot assets via preBuild, then the in-build guards run: verifyNoReleaseSecret
# (no tokens.json session secret in the release artifact) + verifyReleaseBootAssets (JRE + gpu/rlhd jars
# present — an un-bootable APK fails here). A keyless clone falls back to the debug key, so this builds in CI.
if [ "$run_build" -eq 1 ]; then
	stage "assembleRelease (+ verifyNoReleaseSecret / verifyReleaseBootAssets guards)"
	( cd "$ANDROID" && "${GRADLEW[@]}" :app:assembleRelease ) || fail "assembleRelease"
	apk="$ANDROID/app/build/outputs/apk/release/app-release.apk"
	[ -f "$apk" ] || fail "assembleRelease (no app-release.apk emitted)"
	echo "  → built $(cd "$ROOT" && realpath --relative-to="$ROOT" "$apk" 2>/dev/null || echo "$apk") ($(du -h "$apk" | cut -f1))"
fi

# ── Stage 3: lintRelease (PROD-QA-01) ─────────────────────────────────────────────────────────────────
# Android lint aborts on an error severity by default; this is the fail-on-error lint the gate spec asks for.
if [ "$run_lint" -eq 1 ]; then
	stage "lintRelease (fail on error severity)"
	( cd "$ANDROID" && "${GRADLEW[@]}" :app:lintRelease ) || fail "lintRelease"
	echo "  → lint clean (report: android/app/build/reports/lint-results-release.html)"
fi

# ── Stage 4: device smoke (PROD-QA-01, opt-in) ────────────────────────────────────────────────────────
# Boot-to-first-frame on a real GPU. There is no device in plain CI, so this is opt-in and runs the existing
# Firebase Test Lab harness (needs gcloud auth + a configured project). On the operator's machine it is the
# Pixel-7a in-world boot recorded in GATE.md row 2; in a device-pool CI it would be Firebase Robo + :recon.
if [ "$run_device_smoke" -eq 1 ]; then
	stage "device smoke (Firebase Test Lab — boot to first frame)"
	fb="$ANDROID/dev-tools/firebase/fb-gate.sh"
	[ -x "$fb" ] || fail "device-smoke (android/dev-tools/firebase/fb-gate.sh not found/executable)"
	"$fb" || fail "device-smoke"
	echo "  → device smoke PASS."
fi

echo
hr
echo "✅ GATE PASS — Android release gate green."
echo "   contract: rc=$contract_rc  |  build: $([ "$run_build" -eq 1 ] && echo assembleRelease || echo skipped)  |  lint: $([ "$run_lint" -eq 1 ] && echo lintRelease || echo skipped)  |  device-smoke: $([ "$run_device_smoke" -eq 1 ] && echo ran || echo 'opt-in (skipped)')"
hr
