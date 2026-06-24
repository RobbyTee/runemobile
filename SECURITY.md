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

## Handling credentials in reports

Never paste a real `session_id`, `id_token`, access/refresh token, or password
into an issue, PR, or log attachment. Redact account display names. Treat any
logcat/console capture as potentially containing identifiers.
