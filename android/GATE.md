# Release GATE — alpha → beta → GA exit criteria

> The single checklist a build must pass before it leaves the author's device as a beta, and again before GA.
> Rows are the 15 exit criteria from `audit/ROADMAP.md` §4, each mapped to its finding id(s). **Status reflects a
> reconciliation against the current tree** — much of the engineering is already done; what remains is wiring,
> device verification, and two user actions (release keystore material + session rotation). This is a gate, not a
> backlog: a row is only **PASS** when its evidence exists and is reproducible.
>
> Status legend: **PASS** (done, evidence reproducible) · **CODE-DONE** (implemented, on-device verification
> pending) · **OPEN** (code/doc work remains) · **BLOCKED-USER** (needs key material / a decision) ·
> **BLOCKED-DEVICE** (needs the Pixel 7a or the compat matrix). Owner: `dev` (code/doc) · `you` (operator).

## How to use
1. Build the artifact via the ordered recipe in `README.md` (clean `:app:assembleRelease`).
2. Walk every Alpha→Beta row; record evidence (logcat line, screenshot, CI run URL) in the Evidence column.
3. A build is **beta-eligible** only when rows 1–9 are PASS. GA adds rows 10–15.
4. The ceiling no build can launder: this is an **unapproved client for live OSRS — account-ban risk is
   irreducible**, and Play/F-Droid are categorically barred. "GA" = a polished, disclosed, personal-scope
   GitHub-Releases sideload.

## Alpha → Beta (a second person installs a fresh signed artifact, boots all renderers, leaks no secret, breakage is observable + recoverable)

| # | Criterion | Finding id(s) | Owner | Status | Evidence / what's left |
|---|-----------|---------------|-------|--------|------------------------|
| 1 | Boots & safe to hold (no bundled session) | PROD-SECURITY-01 | dev + you | CODE-DONE | `tokens.json` is `src/debug`-only; release guard `verifyNoReleaseSecret` (build.gradle.kts); login-first boot gates to `LoginActivity`. **Left:** rotate the burned session (you, §5.5); confirm login-first on a clean *release* install. |
| 2 | Bootable APK from a clean tree | PROD-RELEASE-ENG-01, PROD-QA-02 | dev + you | OPEN | Fail-fast `verifyReleaseBootAssets` guard now aborts `assembleRelease` with a recipe pointer if the staged JRE / gpu / rlhd jars are missing from release assets (build.gradle.kts) — no more silently-un-bootable artifact. **Left:** wire the staging *scripts* into the Gradle graph so a clean tree self-stages (today still the manual ordered recipe); then a reproducible build booting CPU/GPU/117HD on device; delete the stale Jun-8 APK. |
| 3 | Signature continuity (real release key) | PROD-RELEASE-ENG-02 / DISTRIBUTION-02 | dev + you | BLOCKED-USER | `.gitignore` covers signing material; release still signs with the debug key. **Left:** add a release `signingConfig` reading a gitignored `keystore.properties`; **you** generate the keystore; document rotation. |
| 4 | IP-clean to share | OSS-LICENSE-ATTRIBUTION-01, OSS-SECRETS-HISTORY-02 | dev + you | CODE-DONE (HEAD) | Both ports render tab/stone/chat sprites from the live cache; 0 committed sprite PNGs. **Left (gated):** history purge of the old sprite blobs + token-mint script via the §3 squash (only if publishing). |
| 5 | Observable floor (crash telemetry) | PROD-CRASH-TELEMETRY-01 / PROD-QA-05 | dev | OPEN | Crash-cookie exists for renderer fallback; extend with failing-renderer + GPU string. *Floor, not a hard blocker.* |
| 6 | A real gate exists and passes | PROD-QA-01, PROD-QA-03, PROD-RELEASE-ENG-07 | dev | PARTIAL | **This file** = PROD-QA-03 ✅. PROD-QA-01 (Android CI) + PROD-RELEASE-ENG-07 (contract gate in CI) still OPEN; local pre-commit contract hook works. |
| 7 | Survives a session (thermal + battery) | PROD-QA-09 (= PERF-MEMORY-THERMAL-01 + PERF-BATTERY-NETWORK-01) | dev + you | OPEN | No thermal governor / background frame-rate gate yet. **Left:** add both, then a ≥10-min 117HD soak holding an fps floor + bounded background drain (device, §5.1). |
| 8 | App-shell stops reading as a dev build | UX-ONBOARD-01, UX-ONBOARD-03, UX-LOGIN-01, UX-PLUGCFG-01, UX-DISCRETE-01, UX-XCUT-01 | dev + you | MOSTLY CODE-DONE | UX-PLUGCFG-01 + UX-XCUT-01 PASS; UX-ONBOARD-01 (splash) + UX-DISCRETE-01 (hop scrim) code-done, device-verify pending. **Left (code):** UX-LOGIN-01 — native "session expired" overlay launching `LoginActivity`. |
| 9 | Identity / disclosure | PROD-QA-07 | dev | PARTIAL | README carries the honest unofficial/ban-risk framing + THIRD-PARTY-NOTICES. **Left:** non-Jagex-implying app name/icon decision (you) + a release-page known-issues list. |

## Beta → GA (trustworthy on hardware you didn't test, with a maintenance story)

| # | Criterion | Finding id(s) | Owner | Status | Evidence / what's left |
|---|-----------|---------------|-------|--------|------------------------|
| 10 | Device matrix (headline GA gate) | PROD-COMPAT-01, PROD-COMPAT-02/03 | you | BLOCKED-DEVICE | Caps-gate every accelerated path + in-session CPU fallback are implemented (PERF-RENDERER-ROBUSTNESS-01 PASS). **Left:** Firebase matrix {Adreno, Mali new+old, PowerVR, Xclipse} × {9/12/14/15} × {phone, cutout-landscape, foldable, tablet, TV}. |
| 11 | Gate green, zero open P0/P1 | PROD-QA-03 (full) | dev + you | OPEN | Every row here green incl. reconnect-after-drop + soak. |
| 12 | Legal / disclosure | PRIVACY-LEGAL-01/03, DISTRIBUTION-01, OSS-REPO-DOCS-01/04 | dev | PARTIAL | README/SECURITY/THIRD-PARTY-NOTICES done. **Left:** privacy/data-flow table + release-page risk disclosure. |
| 13 | Secrets hardening | PROD-SECURITY-03 / PRIVACY-LEGAL-02, PROD-SECURITY-10 | dev | OPEN | Legacy creds stored plaintext; `doLogout` doesn't call `accountLogout()`. **Left:** encrypt-at-rest (EncryptedFile/MasterKey) or drop legacy from release; add runelite.net logout. |
| 14 | Update story | PROD-VERSIONING-UPDATE-04 / QA-06 / UPDATE-05, PERF-COLD-START-06, PROD-QA-08 | dev | OPEN | **Left:** semver + monotonic versionCode + CHANGELOG; incremental re-unpack (COLD-START-06); best-effort update check; keep prior APKs downloadable + recovery doc. |
| 15 | Residual UX backlog | UX-XCUT-02/03/04, UX-HUD-01, input-feel deltas | dev + you | OPEN | Cleared or consciously accepted-and-documented; several need device feel/notch confirmation (§5.2). |

## Operator batch (needs the device or a decision)
See `audit/ROADMAP.md` §5 for the copy-paste-actionable device runs. The gating user actions:
- **Rotate the Jagex session** if any locally-built APK was ever shared (§5.5) — do regardless of publishing.
- **Generate the release keystore** (criterion 3) — key material only you hold.
- **Decide the app name/icon** (criterion 9) so it does not imply a Jagex-official client.
- **Run the device-compat matrix** (criterion 10) — the headline GA gate.
