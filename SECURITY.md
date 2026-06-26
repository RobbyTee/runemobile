# Security Policy

This is a single-maintainer, best-effort project. Only the latest published
release is supported.

## Reporting a vulnerability

Please report privately — **do not** open a public issue for a security problem.

- Preferred: open a private [GitHub Security Advisory](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
  on this repository.
- Or email the maintainer: `sayhijordy@gmail.com`.

Expect a best-effort response. Please give enough detail to reproduce.

## In scope

- The native mobile shell (Kotlin/Compose, Swift/UIKit) and the C++ native layer.
- On-device login and token handling (Jagex OAuth and the optional RuneLite-account sync).
- The embedded-JVM / plugin trust boundary.

## Out of scope (accepted risks, not vulnerabilities)

- **Account ban risk.** This client is not on Jagex's approved-client list. Using
  it can get an account banned. This is irreducible — use a throwaway account.
- **In-process hub plugins.** Plugins run inside the embedded JVM with full JVM
  privileges, by design (this is how RuneLite works). Auditing third-party hub
  plugins is the user's responsibility.
- **Tokens at rest.** OAuth tokens are kept in app-private storage; no live
  `tokens.json` is shipped in any build.

## Build & supply chain

The build pins or sources its heaviest inputs deliberately:

- **Embedded JDK / AWT runtime** (OpenJDK 21 + Caciocavallo) — fetched at build time from a pinned
  upstream and verified by **SHA-256** before use (`tools/stage-android-jre.sh`); a mismatch aborts the
  build. These runtimes are not committed to the repo.
- **Native libraries** (`libhelloshell`, `librlawt`) — built from **tracked C++ source** by Gradle/CMake,
  not downloaded as binaries.
- **Gradle dependencies** — resolved from declared repositories (Maven Central, Google, the Gradle Plugin
  Portal, and RuneLite's Maven repo) at pinned versions. Full per-artifact checksum verification
  (Gradle `verification-metadata.xml`) is **not** enabled: on a fork that tracks upstream RuneLite
  closely it would break every build on each dependency bump for limited assurance beyond the SHA-pinned
  JDK fetch and the source-built native layer. The build stays reproducible from the tracked, SHA-pinned
  staging scripts.
- **No build-time telemetry**, and R8/minification is intentionally off, so the shipped bytecode
  corresponds directly to the tracked source.

## Handling credentials in reports

Never paste a real `session_id`, `id_token`, access/refresh token, or password
into an issue, PR, or log attachment. Redact account display names. Treat any
logcat/console capture as potentially containing identifiers.
