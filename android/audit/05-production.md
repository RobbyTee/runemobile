# Phase 5 — Production readiness

> Go/no-go audit of the Android `:app` for any shippable artifact: security/secret-hygiene, release-engineering & CI/CD, distribution channel, privacy/legal, crash-telemetry, QA-gating & device-compat, and versioning/update. **42 findings, every one adversarially verified** (CONFIRM / ADJUST — no OVERTURN). Corrected severity + corrected blocker-class from verification are used throughout. Stable ids are cited verbatim by the ROADMAP and sibling phases.
> Severity = verified-corrected value. **Blocker-class** = release-blocker (gates any shareable artifact) vs nice-to-have (hardening / pre-broad-distribution). Several findings were honestly de-escalated once weighed against this build's locked **personal-sideload, throwaway-account, never-distributed** posture; the few that survive that downgrade are real gates.

## 1. TL;DR — the go/no-go picture

**Is this shippable to the public today? NO.** A clean `assembleRelease` on a fresh checkout produces an **un-bootable APK** (missing the gpu/rlhd jars + `librlawt.so` because their staging lives outside Gradle), the release variant is **signed with the public AOSP debug key**, the build **bundles a live Jagex game session (`tokens.json`) into the artifact** (account takeover for anyone who gets the APK), and the GPU/HD renderers are **verified only on one Mali GPU**. Any one of those is disqualifying; together they mean there is no artifact today that is simultaneously bootable, trustworthy, and secret-free.

**P0 release-blockers: 3.** The single most important is **PROD-SECURITY-01** — a replayable live Jagex session secret packaged inside the binary. It is the one finding that survives this project's personal-sideload downgrade: even handing the APK to one other person leaks an account. Fix it (exclude `assets/tokens.json` from release packaging, rotate the bundled session) before any artifact leaves the author's own device.

**Real-world distributability caveat (read this first):** this is a **RuneLite-derived client for LIVE OSRS that is not on Jagex's approved-client list**. It runs a second in-process HotSpot JVM that JIT-executes the engine and loads Plugin-Hub bytecode at runtime, and bundles non-free OSRS assets + a prebuilt OpenJDK JRE. It **cannot ship on Google Play** (Jagex IP/ToS takedown + Play's runtime-code policy) and is **not F-Droid-eligible** (non-FOSS blobs). Using it carries an **irreducible account-ban risk** for the user. The only defensible channel is a clearly-risk-flagged **GitHub Releases sideload**, kept personal-scope. The fixes below make the artifact *safe to hold and reproducible*; they do **not** make it ToS-compliant or store-eligible.

---

## 2. RELEASE-BLOCKER LIST (P0/P1) — gates launch

These 7 must pass before any shareable build. (Corrected severity / blocker-class.)

| id | title | area | sev | requirement | effort |
|---|---|---|---|---|---|
| **PROD-SECURITY-01** | Live Jagex session secret (`tokens.json`) packaged into the release APK | security | **P0** | Zero bundled session/token material in the artifact; first launch must gate through `LoginActivity`. | S |
| **PROD-RELEASE-ENG-01** | GPU/RLHD/`librlawt` staging lives OUTSIDE Gradle → a clean `assembleRelease` is un-bootable | release-eng | **P0** | A clean build (no manual out-of-band steps) must produce a complete, bootable, reproducible APK. | L |
| **PROD-QA-02** | On-disk release APK is stale/un-bootable (no gpu/rlhd jar, no `librlawt.so`) | qa-gating | **P0** | The release artifact must be reproducibly built from the current tree and boot all three renderers. | M |
| **PROD-COMPAT-01** | Renderer coverage is Pixel 7a (Mali) + emulator only — no GPU-vendor / OS / form-factor matrix | device-compat | **P1** | A defined min device matrix must boot, reach first frame, and auto-GPU must promote-or-fall-back cleanly. *(Conditional: a true blocker only for a public GPU-diverse launch.)* | L |
| **PROD-QA-01** | No Android test harness and no Android CI/CD — zero automated gate | qa-gating | **P1** | CI must build a signed `:app:assembleRelease`, lint, run the shared-contract check, and smoke on a device pool. | L |
| **PROD-QA-03** | No defined release gate — no exit criteria binding boot/renderer/login/thermal/a11y to go/no-go | qa-gating | **P1** | An explicit, checkbox `GATE.md` with device/owner/evidence per row; a tag requires every box ticked. | M |
| **PROD-QA-09** | Thermal soak + background-battery unverified: 117HD throttles to SEVERE in ~1–2 min; engine pins CPU/GPU backgrounded | qa-gating | **P1** | A ≥10 min 117HD soak holds an fps floor (needs governor) + bounded backgrounded drain (needs visibility gate); both device-measured. | L |

> **Why PROD-COMPAT-01 is P1-not-P0:** the app boots fine on its actual target, and the **default renderer is CPU** (vendor-independent) — a fresh install on any arm64 phone boots the software renderer regardless of GPU. The accelerated path is auto-promoted **only after a real caps probe** (`probeGpuCaps()` does a live EGL ES3 context-create and returns `cpu` on any failure), so a tail GPU is never auto-promoted. It is a strong gate **for a hypothetical public launch** and a nice-to-have hardening for the real target.

---

## 3. NICE-TO-HAVES (P1–P3) — hardening / pre-broad-distribution

Real and worth doing, but not gating the author's own use of a personal sideload.

| id | title | area | sev | one-line action | effort |
|---|---|---|---|---|---|
| **PROD-SECURITY-03** | Credentials at rest as plaintext JSON (no Keystore) — legacy password is the sharp edge | security | P1 | Wrap `tokens.json`/`legacy_login.json` in `EncryptedFile`/Keystore; prioritize the legacy password or drop legacy creds from release. | M |
| **PROD-PRIVACY-LEGAL-01** | No privacy policy / ToS / EULA / attribution docs | privacy-legal | P2 | Author privacy + disclaimer + THIRD-PARTY-LICENSES before any public artifact. | M |
| **PROD-RELEASE-ENG-02** | Release signed with the DEBUG keystore — no managed release key | release-eng | P2 | Dedicated gitignored release keystore via env/`local.properties`; v2/v3; document rotation. | M |
| **PROD-RELEASE-ENG-03** | No Android CI/CD (CI.yml is the upstream desktop pipeline, never builds `:app`) | release-eng | P1 | Add a separate Android workflow (build/sign/contract/artifact). | L |
| **PROD-RELEASE-ENG-06** | Clean-clone build depends on gitignored bootstrap artifacts (JRE + jars) | release-eng | P1 | Make the bootstrap explicit/ordered + SHA-pinned + mirror the third-party JRE. | M |
| **PROD-RELEASE-ENG-07** | Shared-contract gate is a local pre-commit hook only (bypassable, uninstalled on fresh clones) | release-eng | P2 | Promote `check-contract.sh` to a required CI step (fail on exit 2). | S |
| **PROD-RELEASE-ENG-08** | Bare `gradle assemble` builds the unrelated `:recon` app; dev `TEST_LOOP`/probes ship in release | release-eng | P3 | Always target `:app:assembleRelease`; gate `:recon` behind a property; move `TEST_LOOP`/probes to `src/debug`. | M |
| **PROD-DISTRIBUTION-01** | Channel choice: GitHub-Releases sideload only; Play + F-Droid categorically blocked | distribution | P2 | Document the channel + risk caveat on the Release page. | S |
| **PROD-DISTRIBUTION-02** | Release signed with debug key (distribution lens — update-continuity) | distribution | P2 | Same fix as PROD-RELEASE-ENG-02 (one stable private key). | S |
| **PROD-PRIVACY-LEGAL-02** | Sensitive auth material unencrypted at rest | privacy-legal | P2 | Keystore-backed `EncryptedFile`; deprecate the legacy-password path. | M |
| **PROD-PRIVACY-LEGAL-03** | Data-handling table + deletion path must be documented (and Logout verified to forget all secrets) | privacy-legal | P2 | Ship the data-flow table; couple full closure to PROD-SECURITY-01. | M |
| **PROD-CRASH-TELEMETRY-01** | No crash reporting / telemetry of any kind | crash-telemetry | P2 | Opt-in, anonymous, self-hosted-or-none capture; never Firebase for an unapproved client. | M |
| **PROD-CRASH-TELEMETRY-02** | NetProbe sends public IP to `api.ipify.org`; screenshots write to the shared gallery | crash-telemetry | P3 | Gate NetProbe behind `BuildConfig.DEBUG`; the screenshot path is user-initiated/low-risk. | S |
| **PROD-QA-04** | Release-armed diagnostics (`gpuframedump`/`fpsprobe`/`headless.capture`) run on every public boot | crash-telemetry | P2 | Gate the three opt blocks behind `BuildConfig.DEBUG` (capture as `DEBUG \|\| census`), mirroring iOS. | S |
| **PROD-QA-05** | No crash reporting (field stability unobservable) — *duplicate of PROD-CRASH-TELEMETRY-01* | crash-telemetry | P2 | De-duped; extend the crash-cookie to log failing renderer + GPU string. | M |
| **PROD-SECURITY-07** | Game-session captures + GPU frame dumps to external storage on every GPU/117HD boot | security | P2 | Gate capture + `gpuframedump` behind `DEBUG \|\| census`; keep any needed capture on internal `filesDir`. | S |
| **PROD-COMPAT-02** | No EGL/GLES caps gate in `librlawt` — a non-auto GPU boot can silently black-screen ~240 s | device-compat | P2 | Run the caps gate on every accelerated path + add an in-session CPU fallback; tighten the watchdog. | M |
| **PROD-COMPAT-03** | TV/leanback force-boots 117HD with no caps gate and no crash-cookie on untested TV GPUs | device-compat | P2 | Scope TV out of v1 (drop `LEANBACK_LAUNCHER` for release) or caps-probe + protect the TV path. | S |
| **PROD-VERSIONING-UPDATE-05** | Sideloaded APKs have no auto-update path / no in-app update check | versioning-update | P3 | Best-effort GitHub-Releases tag check → non-blocking Custom-Tab prompt, fail-open. | M |
| **PROD-QA-06** | No versioning/update story + JRE re-unpack stalls ~30 s on every update | versioning-update | P2 | Land the incremental-unpack split (PERF-COLD-START-06) + a boot progress UI (UX-ONBOARD-01); semver. | M |
| **PROD-QA-07** | Ships as dev codename "HelloShell" with a TV banner as its icon; no app identity/legal docs | distribution | P2 | Real name (not implying Jagex endorsement) + adaptive icon + release-page risk disclosure. | M |
| **PROD-QA-08** | No rollback / kill-switch plan for a sideload that can brick on a bad GPU/update | release-eng | P2 | Keep prior signed APKs downloadable; document clear-data recovery + CPU reset; advisory feed. | M |
| **PROD-SECURITY-02** | Release signed with debug keystore (security lens) | security | P2 | Same fix as PROD-RELEASE-ENG-02. | S |
| **PROD-VERSIONING-UPDATE-04** | Placeholder version (`versionCode 1`/`versionName 0.1`), no semver/monotonic scheme | versioning-update | P3 | Semver versionName + monotonic versionCode (commit-count/CI) + CHANGELOG. | S |
| **PROD-SECURITY-04** | Exported `TEST_LOOP` Game-Loop intent-filter ships in the release manifest | security | P2 | Move the `TEST_LOOP` filter to `src/debug/AndroidManifest.xml`. | S |
| **PROD-SECURITY-06** | `id_token` transits the system clipboard during Jagex login | security | P2 | Keep `clearLink()`; flag the ClipData `EXTRA_IS_SENSITIVE` on API 33+; document the loopback constraint. | S |
| **PROD-SECURITY-08** | Session secrets passed as process env (`JX_*`/`RL_LEGACY_*`) — readable via `/proc/self/environ` | security | P2 | Accept the `JX_*` handoff; prefer not arming the legacy password in release or clear the env var post-consume. | M |
| **PROD-SECURITY-10** | Jagex logout does not clear the RuneLite-account (runelite.net) session | security | P2 | Have `doLogout` also call `accountLogout()` before restart. | S |
| **PROD-SECURITY-05** | DEBUG diagnostic receivers correctly release-stripped (verified, document) | security | P3 | No change; optionally move to a `src/debug` source set for defense-in-depth. | S |
| **PROD-SECURITY-09** | No cert pinning + spoofed launcher UA; loopback OAuth on localhost (correctly hardened) | security | P3 | Pinning optional for a sideload; loopback posture fine; the spoofed UA is a ToS/distribution concern. | M |
| **PROD-SECURITY-11** | Embedded HotSpot JIT writes+executes in-process (W^X) — informational | security | P3 | Integrity-check the unpacked JRE; document the hub trust boundary; drop `libjdwp.so`. | L |
| **PROD-SECURITY-12** | `rlhdprobe` boot path force-enables stdio→logcat unconditionally | security | P3 | Change line 2550 to `setStdioRedirect(BuildConfig.DEBUG)`; gate the probe paths behind DEBUG. | S |
| **PROD-SECURITY-13** | No explicit backup/data-extraction rules; no R8 (both acceptable now) | security | P3 | Add an explicit `dataExtractionRules`/`fullBackupContent` stub; keep R8 off (FindClass-by-name). | S |

---

## Refuted / de-duplicated (no OVERTURN; resolved id collisions)

No finding was overturned (all 42 verified CONFIRM or ADJUST). Three reconciliations of the input set are recorded so the ids stay stable:

- **PROD-SECURITY-01 — duplicate input.** Filed twice; the second instance is the same finding. Its only inaccuracy: it claims the secret is "confirmed present in `app-release.apk`." It is **absent from the stale Jun-8 release artifact** (built before `tokens.json` was added) but **would be packaged on a fresh `assembleRelease`** and **is** present in `app-debug.apk`. The vector stands.
- **PROD-SECURITY-02 — colliding id, two findings.** Batch 1's `PROD-SECURITY-02` = debug-key signing (kept here as the security-lens debug-key item). Batch 2 reused the id for the `TEST_LOOP` intent-filter — that is the **same issue as PROD-SECURITY-04**; both graded P2/nice-to-have. Counted once.
- **PROD-CRASH-TELEMETRY-01 ≡ PROD-QA-05 — same gap.** Both describe "no crash reporting." Input graded QA-05 P1/release-blocker, which contradicts CRASH-TELEMETRY-01's P2; the correct grade is **P2/nice-to-have** for both (a single-maintainer build with no field matrix to be blind to). Not double-counted as a blocker.

---

## 4. Detailed findings by area

### 4a. Security

**PROD-SECURITY-01 (P0, release-blocker) — live Jagex session in the APK.** `android/app/src/main/assets/tokens.json` (1091 B, mode `-rw-------`) parses to a **live** session: `session_id` (22 chars), 3 accounts, `chosen_account_id` (9), `chosen_display_name` (12), and a non-empty `otl_token` (53 chars). AGP packs all of `src/main/assets` with no exclusion (`build.gradle.kts` has no `packaging{}` excludes). `JagexTokens.readSource` (JagexTokens.kt:50-51) reads the bundled asset as the lowest-priority live source; `loadAndArm` (JagexTokens.kt:119-123) maps `session_id`→`JX_SESSION_ID` and autologins. The `otl_token` is non-empty even though `JagexLogin.writeTokens` (JagexLogin.kt:120) always writes `otl_token=""` — confirming it came from the `fetch-shield-tokens.sh` dev mint (lines 322/332), so the bundled secret is **broader than a fresh on-device login**.
- **Requirement:** no live credential/session in any distributable artifact; first launch gates through `LoginActivity`.
- **Recommendation:** `packaging { resources { excludes += "assets/tokens.json" } }` in `app/build.gradle.kts` **or** move the dev session to `src/debug/assets/`. Add a CI/`assembleRelease` guard: `unzip -l app-release.apk | grep -E 'tokens.json|legacy_login.json'` must be empty. **Rotate/invalidate the bundled `session_id` + `otl_token` + the throwaway account now** (assume burned). *Distribution caveat: the on-device login still uses a spoofed launcher UA + clipboard `id_token` relay against a non-approved client — this fix makes the artifact safe to hand out, not ToS-compliant.*
- **Effort S · risk low · scope android-only.**

**PROD-SECURITY-03 (P1, nice-to-have) — plaintext creds at rest.** `JagexLogin.writeTokens` writes `File(filesDir,"tokens.json")` cleartext (session_id + account list); `LegacyCreds` stores `legacy_login.json {username,password}` cleartext (LegacyCreds.kt:37-40) and arms `RL_LEGACY_PASS`. Neither is Keystore-wrapped. Controls bound the blast radius: `filesDir` is app-private (rooted/forensic only), `allowBackup=false` (manifest:22), the bundled-asset path never carries legacy creds, and the throwaway-account rule caps it. The **legacy plaintext password is the sharp edge** (a reusable main-grade secret). **Rec:** `EncryptedFile`/Keystore AES-GCM, prioritize the legacy password or drop legacy creds from release. **M · low · android-only.**

**PROD-SECURITY-04 (P2, nice-to-have) — exported `TEST_LOOP` in release.** `AndroidManifest.xml:45-49` adds `com.google.intent.action.TEST_LOOP` + DEFAULT + mime `application/javascript` on the exported `MainActivity` (:33), in the always-built single manifest. Worst case a caller forces the 117HD boot path — no data/secret exposure. **Rec:** move the filter to `src/debug/AndroidManifest.xml`; keep MAIN/LAUNCHER/LEANBACK_LAUNCHER. **S · low.**

**PROD-SECURITY-05 (P3, nice-to-have) — DEBUG receivers correctly stripped.** `SET_HD_CONFIG`/`SET_RECONNECT_OVERLAY` are inside `if (BuildConfig.DEBUG)` (MainActivity.kt:323-369), runtime-registered (never manifest-declared) → const-folded out of release. **No change required;** optional `src/debug` source-set move + a release smoke check.

**PROD-SECURITY-06 (P2, nice-to-have) — `id_token` via clipboard.** The consent client only accepts `redirect_uri=http://localhost`, forcing Chrome to land on `localhost/#id_token=…` which the user copies; `LoginActivity.maybeAutoImport` reads it back (LoginActivity.kt:147-156). Mitigations confirmed: shape-gated import (:150) + `clearLink()`/`clearPrimaryClip()` after capture (:174-178). Residual: a foregrounded clipboard manager/Gboard history. **Rec:** mark the ClipData `EXTRA_IS_SENSITIVE` on API 33+; document the loopback constraint. **S · low.**

**PROD-SECURITY-07 (P2, nice-to-have) — PII frames to external storage.** `extraOpts` arm headless capture unconditionally every boot (MainActivity.kt:2667-2670), with `captureBase` on **external** storage in 117HD mode (`getExternalFilesDir`, :2626), and `gpuframedump=true`+`.dir=getExternalFilesDir` on every gpu/rlhd boot (:2758-2774) writing PNGs of the logged-in game (display name/chat = PII). The consumer is inert without the dir (HeadlessCapture.java:83-84). `getExternalFilesDir` is app-private external (reachable only with legacy storage perm on the same device; scoped storage restricts further). **Rec:** gate capture + `gpuframedump` behind `BuildConfig.DEBUG || censusMode`; keep any necessary capture on internal `filesDir`. **S · low · shared/iOS-impacting** (HeadlessCapture consumer; fix is Android-only).

**PROD-SECURITY-08 (P2, nice-to-have) — secrets in process env.** `JagexTokens.loadAndArm` sets `JX_SESSION_ID/JX_CHARACTER_ID` (+ access/refresh) via `JvmLauncher.setEnv` (JagexTokens.kt:119-123); `LegacyCreds` sets `RL_LEGACY_USER/RL_LEGACY_PASS` (LegacyCreds.kt:54-55) — all `setenv` before `JNI_CreateJavaVM` (jvm_bootstrap.cpp:1436-1440). Design is sound (HotSpot snapshots env at start; env over `-D` keeps secrets off arg/log surfaces). Env is private to app/root, persists in `/proc/self/environ`; app is **not** `android:debuggable` in release (confirmed) so ptrace/env access needs root. **Rec:** accept the `JX_*` handoff; prefer not arming the legacy password in release or clear it post-`HeadlessLogin`. **M · low.**

**PROD-SECURITY-09 (P3, nice-to-have) — no pinning, loopback hardened.** Jagex REST uses `HttpURLConnection` over HTTPS (platform-verified TLS) with a hardcoded spoofed launcher UA (JagexLogin.kt:40-42); no cert pinning. The RuneLite-account loopback is correctly hardened: `HttpServer.create(InetSocketAddress("localhost",0),1)` (SessionManager.java:221) — localhost bind, random port, backlog 1, stop after one request. No `network-security-config`/`usesCleartextTraffic` → default P+ cleartext-disallowed; only INTERNET + ACCESS_NETWORK_STATE. **Rec:** pinning optional for a sideload; loopback fine; the **spoofed UA is a ToS/anti-bot distribution concern, not a transport defect.** **M · low.**

**PROD-SECURITY-10 (P2, nice-to-have) — logout leaves the runelite.net session.** `doLogout` (MainActivity.kt:1075-1098) drops `tokens.json`+`legacy_login.json`, purges WebView cookies, restarts — but never calls `accountLogout()`/`RuneLiteAccountBridge.logout()`/`SessionManager.logout()`, so the runelite.net account session survives a full Jagex logout. Both methods exist (`JvmLauncher.kt:435`, `RuneLiteAccountBridge.java:80`). UX/mental-model gap, not remote-exploitable. **Rec:** have `doLogout` also invoke `accountLogout()` on the bridge thread before restart. **S · low.**

**PROD-SECURITY-11 (P3, nice-to-have, informational) — in-process JIT W^X.** The app dlopens embedded OpenJDK 21 `libjvm.so` and runs C2 JIT in-process; Android grants JIT freely (no iOS `brk #0x69` mirror), so the VM necessarily maps W^X-toggled exec code-cache — inherent to the product. `extractNativeLibs=true` (manifest:28), `allowNativeHeapPointerTagging=false` (:23). **Do not import the iOS increased-memory entitlement framing — Android is just `-Xmx512m`.** Live sub-item: `libjdwp.so` ships in the JRE (no `-agentlib:jdwp` wired) — a takeable footprint cut (SLM-JRE-03). **Rec:** integrity-check the unpacked JRE post-copy; document the hub trust boundary (hub plugins run with full JVM privileges in-process). **L · med.**

**PROD-SECURITY-12 (P3, nice-to-have) — `rlhdprobe` forces stdio→logcat.** Normal (:2480) and gpu/117HD (:2784) boots correctly gate `setStdioRedirect(BuildConfig.DEBUG)`, so release does not pipe VM stdio. The `rlhdprobe` path calls `setStdioRedirect(true)` **unconditionally** (:2550); native default `g_stdioRedirect{true}` (jvm_bootstrap.cpp:327) relies on the Kotlin flip. Reached only via the `rlhdprobe` intent extra / marker file (dev/CI-only). **Rec:** change :2550 to `setStdioRedirect(BuildConfig.DEBUG)`; gate the probe paths behind DEBUG; optionally flip the native default to false. **S · low.**

**PROD-SECURITY-13 (P3, nice-to-have) — backup rules + R8.** `allowBackup=false` (manifest:22) — right call — but no explicit `android:dataExtractionRules`/`fullBackupContent`. R8 intentionally **off** (build.gradle.kts:57-62) — sound (engine + 51 bridges resolve via JNI FindClass-by-name). Both acceptable for a sideload. **Rec:** add an explicit data-extraction stub documenting the all-off intent; keep R8 off.

### 4b. Release-engineering / CI-CD

**PROD-RELEASE-ENG-01 (P0, release-blocker) — un-bootable clean build.** Boot classpath = `[client-*-gpu.jar : client-*-rlhd.jar : probes.jar]` with `useGpuJar=true` unconditional (MainActivity.kt:2584,2618); a null gpu jar hits `booted.set(false); return` (2605-2609). Those jars + `librlawt.so` are produced by `stage-android-gpu-jar.sh` / `stage-rlhd-zone-jar.sh`, which `grep` confirms are **NOT wired into** `app/build.gradle.kts` or `android/build.gradle.kts` (no `exec`/`dependsOn`/`tasks.register`/`.sh` reference — empty) and are hand-invoked only. `deploy-wireless.sh:133` runs `./gradlew :app:assembleRelease` bare. So a **clean checkout (or any machine that never hand-ran staging) builds an un-bootable APK** — exactly the "no manual out-of-band steps / reproducible" requirement that is violated.
- **Rec:** register Gradle staging tasks (`stageMobileRuntime`/`stageGpuJar`/`stageRlhdJar`/`stageJre`) as `preBuild`/`mergeReleaseAssets` deps with up-to-date checks keyed off source-jar SHAs + `runtime.tag`; ensure `librlawt.so` lands in `lib/arm64-v8a` via CMake; **fail the build** if `client-*-gpu.jar`/`-rlhd.jar`/`librlawt.so` are absent from merged assets/jniLibs; delete the stale on-disk `app-release.apk`. **L · high · android-only.**

**PROD-RELEASE-ENG-02 (P2, nice-to-have) — debug-key signing.** `app/build.gradle.kts:66` `signingConfig = signingConfigs.getByName("debug")` in `release{}`; `apksigner verify` on `app-release.apk` prints `CN=Android Debug`. `grep` finds no `signingConfigs.create`/`.jks`/`keyAlias`/`storePassword` anywhere. Two real harms — anyone can re-sign/tamper a redistributed APK, and a future real-key build is a signature mismatch (forced uninstall) — bite only under distribution, which this build forbids. **Rec:** dedicated release keystore (RSA-2048+, 25yr) read from `local.properties`/env (gitignored); v2/v3; document rotation/recovery. **Play App Signing is moot** for a non-approved OSRS client. **M · low.**

**PROD-RELEASE-ENG-03 (P1, nice-to-have) — no Android CI.** `.github/workflows/CI.yml` is the lone workflow: ubuntu-24.04, `setup-java@v4` java 11, `ci/build.sh` → `./gradlew :buildAll` (stock upstream RuneLite). It never `cd android`, never invokes `android/gradlew`, never stages JRE/jars; AGP needs JDK 17+. A correct **local** build path can still produce one signed bootable APK (the hard gates are ENG-01 + ENG-02), so CI is a reproducibility multiplier, not a launch gate. **Rec:** separate Android workflow → setup-java 21 + SDK/NDK/CMake → stage shared jars + JRE (cache the ~28 MB tarball by SHA) → stage gpu/rlhd → `:app:assembleRelease` → sign from masked secrets → `check-contract.sh` gate → upload signed APK to a **private GitHub Release**. **Never expose `tokens.json` to CI. No Play/TestFlight.** **L · med.**

**PROD-RELEASE-ENG-06 (P1, nice-to-have) — clean-clone bootstrap.** `android/.gitignore` ignores `jniLibs/`, `assets/jvm/jre/`, `assets/jvm/client-*.jar`, `probes.jar`, `runtime.tag`, `tokens.json`; `git ls-files assets/jvm/` returns only `gpu-smoke.jar`+`hello.jar`. The JRE is fetched by `stage-android-jre.sh` from a **third-party** GitHub release (`AngelAuraMC … jre21-android-arm64.tar.xz`), SHA-pinned (`JRE_SHA256=8d41ec40…`, lines 31-67) — good hygiene but a single external host. The hard un-bootable mechanism is already counted under ENG-01; this is the documented-reproducible-bootstrap requirement. **Rec:** explicit ordered bootstrap (`stage-android-jre.sh` → `stage-mobile-runtime.sh` → gpu/rlhd surgery), **mirror the JRE**, cache heavy artifacts in CI. **M · med.**

**PROD-RELEASE-ENG-07 (P2, nice-to-have) — contract gate is local-only.** `tools/shared-contract/check-contract.sh` diffs the live `net.runelite.client.headless.*` bridge/`-D` contract vs `baseline.txt` (exit 0/1/2) but is invoked **only** by `tools/git-hooks/pre-commit` (per-clone install, bypassable via `--no-verify`); no CI runs it. Cross-port (iOS) drift can land silently. **Rec:** promote to a required CI step (fail on exit 2, warn on 1); run the `bridge-census` as the "doesn't break the other port" proof; keep the hook as a fast local pre-flight. **S · low · shared/iOS-impacting.**

**PROD-RELEASE-ENG-08 (P3, nice-to-have) — `:recon` + dev surfaces in release.** `settings.gradle.kts:18-19` includes `:app` + `:recon` (`dev.audaro.glesrecon`, own MAIN/LAUNCHER) with no dependency edge — a bare `./gradlew assemble` builds both. Probe boot-gates (`netprobe.mode`, `gpuSmoke`, `rlhdprobe`) are not DEBUG-gated and ship in release (each needs adb/file/intent access — low real risk). `SET_HD_CONFIG`/`SET_RECONNECT_OVERLAY` **are** correctly DEBUG-gated (no change). `gpu-smoke.jar` is git-tracked + on disk so it ships. **Rec:** always target `:app:assembleRelease`; gate `:recon` behind a property; move `TEST_LOOP`/probes to `src/debug` or DEBUG-gate; strip `gpu-smoke.jar` from release. **M · low.**

**PROD-QA-08 (P2, nice-to-have) — no rollback/kill-switch.** Only in-app self-heal is the per-device auto-GPU crash-cookie (renderer-only, next-launch: `auto-gpu-armed` at MainActivity.kt:908, watchdog reverting at 1019-1032) + the `runtime.tag` wipe-on-change (~30 s re-unpack). No remote kill-switch, no staged rollout (sideload). Static `versionCode=1` lets a same-keystore in-place reinstall of a prior APK serve as manual rollback — **only if** prior signed APKs are kept and the signing identity is stable (ENG-02). **Rec:** keep prior signed APKs downloadable; document clear-app-data recovery + CPU-renderer reset; optional "recommended version" advisory feed; be explicit the **account-ban risk is unmitigable**. **M · med.**

### 4c. Distribution

**PROD-DISTRIBUTION-01 (P2, nice-to-have) — channel choice.** App = unapproved third-party OSRS client: root `LICENSE` RuneLite BSD-2; embedded OpenJDK GPLv2+CPE (`libjvm.so` + 33 `.so` + a jimage `modules`, ~198 MB under `assets/jvm`); non-free OSRS assets (`tabassets/runescape.ttf`, stone/icon/chatbtn PNGs, `rlhd.jar`); runtime Plugin-Hub `URLClassLoader` (MobileUiSmokeRunner.java:421/438, PluginHubScreen.kt:120). **Ship via GitHub Releases signed APK sideload.** Reject Play (Jagex IP/ToS takedown + dev-account risk; Play's **runtime-code policy forbids loading/executing downloaded bytecode** — the embedded JVM + Plugin Hub are direct violations). Reject F-Droid (requires 100% FOSS reproducible build — the prebuilt bionic JRE + non-free assets disqualify). Strategy/doc decision, gates nothing buildable. **Rec:** state on the Release page "unofficial, not affiliated with Jagex, use at your own ban risk, sideload only." **S · med.**

**PROD-DISTRIBUTION-02 (P2, nice-to-have) — debug-key distribution lens.** Same mechanism as RELEASE-ENG-02; the distribution angle is **update-continuity** (sideload signature continuity needs one stable private key). For a strictly-personal single-installer flow the debug key still yields an installable v2/v3-signed APK; the moment a second person expects signed updates it is wrong. **Rec:** dedicated gitignored release keystore; note this does **not** make it Play-eligible. **S · low.**

**PROD-QA-07 (P2, release-blocker for branding/disclosure) — app identity.** `AndroidManifest.xml:24-26` `android:label="HelloShell"` with both `icon` and `banner` = `@drawable/tv_banner` (a 320×180 TV tile); no `mipmap-anydpi-v26` adaptive icon, no `values/strings.xml`; applicationId placeholder `dev.audaro.helloshell`. The **load-bearing, release-blocking** sub-items are the branding/legal ones: the name must **not** imply official Jagex/OSRS endorsement, and the release page **must** state the unapproved-client + account-ban risk + a privacy note for the two session secrets — those gate any shared link. The adaptive-icon/strings part is cosmetic. **Rec:** distinct app name + adaptive icon (keep the TV banner only on `android:banner`) + release-page risk disclosure. **M · high.**

### 4d. Privacy / legal

**PROD-PRIVACY-LEGAL-01 (P2, nice-to-have) — no privacy/ToS/EULA/attribution.** None exist in the android tree (only the embedded JRE `legal/` + vendored RuneLite/117HD LICENSE). Data surfaces verified: Jagex `id_token` via Custom Tab+clipboard, `session_id` plaintext `tokens.json`, legacy user/pass plaintext, runelite.net session synced off-device. **No public distribution event** for COPPA/GDPR/CCPA to attach to (no PII to any first-party server; no analytics/ads/SDKs — only INTERNET + the user's own OAuth). The **attribution** sub-point is the most legitimate (BSD-2 + GPLv2+CPE require notice preservation if redistributed) but is largely satisfied (BSD-2 `LICENSE` at root + `jre/legal/` bundled). **Rec (before any public artifact):** privacy policy + disclaimer + THIRD-PARTY-LICENSES. **M · med.**

**PROD-PRIVACY-LEGAL-02 (P2, nice-to-have) — auth at rest unencrypted.** `tokens.json` + `legacy_login.json` plaintext in `filesDir`; `JX_SESSION_ID` in process env; `allowBackup=false` is the load-bearing mitigation (blocks adb-backup), leaving a rooted-device-only threat. No `EncryptedFile`/`EncryptedSharedPreferences`/Keystore in app code. The **plaintext password is the worst case**. **Rec:** Keystore-backed `EncryptedFile`; prefer the revocable OAuth path over the reusable legacy password; keep `allowBackup=false`. **M · low.**

**PROD-PRIVACY-LEGAL-03 (P2, nice-to-have) — data-handling table + deletion path.** Inventory verified end-to-end (id_token clipboard, session_id at rest, legacy user/pass, JX_* env, runelite.net sync, screenshots to gallery). Logout = `JagexTokens.deleteAll` (deletes internal+external tokens + legacy + writes `logged_out.marker`); the **bundled asset `tokens.json` can only be suppressed by the marker, not deleted** — the "suppress-not-delete" gap, closed fully by PROD-SECURITY-01. **Rec:** ship the data-flow table (§6); document deletion = in-app Logout + Account Logout + uninstall; verify no asset-resurrection once SECURITY-01 lands. **M · low.**

### 4e. Crash-telemetry

**PROD-CRASH-TELEMETRY-01 / PROD-QA-05 (P2, nice-to-have — same gap).** Zero crash/analytics SDKs (build.gradle.kts:78-90 = AndroidX/Compose/coroutines only), no `google-services.json`, no `Thread.setDefaultUncaughtExceptionHandler` in app code. The only crash-awareness is the auto-GPU crash-cookie+watchdog (MainActivity.kt:846-861, 1019-1034) — covers only the renderer-promote path, reports nothing; no native-crash capture for the two-VM (HotSpot + `librlawt`) model. A single-maintainer, never-distributed build has no field matrix to be blind to. **Rec:** opt-in, anonymous, self-hosted-or-none capture (Thread handler + a JNI fatal hook → local report in `filesDir`, shared only on explicit consent); capture stack/version/API/device/SoC/GPU-renderer/selected-renderer/crash-cookie state; **never** session_id/JX_*/displayName/IP; default OFF; **avoid Firebase Crashlytics** (Google project + ToS exposure for an unapproved client). Cheap honest minimum: extend the crash-cookie to log the failing renderer + GPU string so a black-screen is self-reportable. **M · low/med.**

**PROD-CRASH-TELEMETRY-02 (P3, nice-to-have) — IP egress + gallery writes.** `NetProbe.kt:26` GETs `api.ipify.org` (logs egress IP); `:33` hits `google.com/generate_204`. Dev-marker-gated (`netprobe.mode`, MainActivity.kt:807-811 early-return) so it does **not** run on a normal release boot, but the code ships. It logs only `session_id` length (NetProbe.kt:37), and the Bearer goes to Jagex's own `auth.runescape.com` — no secret leak. Screenshots are user-initiated, scoped `MediaStore Pictures/RuneLite` (MainActivity.kt:1138-1156, stays on-device). **Rec:** gate NetProbe behind `BuildConfig.DEBUG` (bundle with PROD-QA-04). Production hosts confirmed: only `auth.runescape.com`, `account.jagex.com`, `runelite.net` are always-on. **S · low.**

**PROD-QA-04 (P2, nice-to-have) — release-armed diagnostics.** `MainActivity.kt:2667-2670` arms `headless.capture.dir/.interval=10/.preLoginMax=40` in the **unconditional** base opts (every boot, real PNGs to `filesDir/rl-capture`); :2761-2762 arms `gpuframedump` on every GPU boot; :2773-2777 arms `gpuframedump`+`fpsprobe` on every 117HD boot — none DEBUG-gated, unlike the adjacent `setStdioRedirect(BuildConfig.DEBUG)` (:2784). **iOS DOES gate these** (`JVMLauncher.m:1054-1056,1105-1109` `#if DEBUG`; never arms `gpuframedump` in shipping). Writes to **app-private** storage + minor warmup spikes — wasteful, not un-bootable. **Rec:** gate the three opt blocks behind `BuildConfig.DEBUG` (capture as `DEBUG || census`), mirroring iOS; consumers go inert. **S · low.**

### 4f. QA-gating / device-compat

**PROD-QA-01 (P1, release-blocker) — no test harness / no Android CI.** No `app/src/test` or `app/src/androidTest`; build.gradle.kts:78-90 declares zero junit/espresso/test deps; the only workflow is upstream desktop CI (never builds `:app`/`:recon`). The contract gate is a manual script only. For any trustworthy release of a build that boots a second JVM + native GL, **zero** automated build/lint/contract/smoke gate is a real blocker. Infra exists: `dev-tools/firebase/fb-gate.sh`, the `:recon` GLES caps probe, the `TEST_LOOP` Game Loop. **Rec:** `:app:assembleRelease` (release keystore secret) → `lintRelease` fail-on-error → `check-contract.sh` PASS → Firebase Test Lab Robo across a device pool + the `:recon` probe → smoke instrumentation (launches, reaches first frame on CPU). Sideload artifact, not a Play upload. **L · med.**

**PROD-QA-02 (P0, release-blocker) — stale/un-bootable APK.** Direct `unzip`: the on-disk `app-release.apk` (116 MB, Jun 8) contains only `libhelloshell.so` + `shaded.jar` + `hello.jar` + `probes.jar` — **no** gpu/rlhd jar, **no** `librlawt.so`. The debug APK (Jun 22) has all of them. Boot is fatal: MainActivity.kt:2584 sets `useGpuJar=true`/`jarSuffix='-gpu.jar'` unconditionally; :2603-2610 hard-aborts (`booted.set(false); return`) before `JNI_CreateJavaVM` when no gpu jar is found **or copyable from assets** — and the release APK has none in assets. So it is un-bootable on a clean install on **every** renderer (CPU included). **Drop** the recommendation clause "stop checking built APKs into git" — `git ls-files` shows no `.apk` tracked and `build/` is gitignored (SLM-DEP-02 already refuted-because-done). **Rec:** wire the staging into the release pipeline (= ENG-01) + an `assembleRelease` assertion that fails if `assets/jvm` lacks `client-*-gpu.jar` or `lib/` lacks `librlawt.so`; clean-install first-frame check on CPU/GPU/117HD. **M · low.**

**PROD-QA-03 (P1, release-blocker) — no defined release gate.** No `GATE.md`/release-criteria doc exists. Open cross-referenced P1s confirmed in the sibling audits: UX-ONBOARD-01 (black ~30 s cold start), UX-LOGIN-01 (silent session-lapse), UX-PLUGCFG-01 (config sections dropped), UX-DISCRETE-01 (world hop reads as a kick), UX-XCUT-01 (HUD controls under the cutout); plus the perf needs-device batch (PERF-MEMORY-THERMAL-01, PERF-BATTERY-NETWORK-01). QA is ad-hoc manual device runs. **Rec:** write a one-page checkbox `GATE.md` (device/owner/evidence per row): (1) clean-install first frame on the matrix; (2) all three renderers + auto-GPU promote AND CPU fallback per GPU vendor; (3) Jagex login + RuneLite-account sync + reconnect-after-drop; (4) ≥10 min 117HD soak holding an fps floor; (5) a11y smoke (**list it, but UX-XCUT-04 is P2 — a documented row, not a stop-ship**); (6) needs-device batches triaged; (7) zero open P0/P1. Order it after QA-02 (repro build) + COMPAT-01 (matrix). **M · med.**

**PROD-QA-09 (P1, release-blocker) — thermal/battery unverified.** PERF-MEMORY-THERMAL-01 (device-confirmed): zero Thermal/PowerManager integration (grep-clean; only a comment at MainActivity.kt:303-304); 117HD throttles the Pixel 7a to SEVERE in ~1–2 min. PERF-BATTERY-NETWORK-01: the never-exiting HotSpot thread (jvm_bootstrap.cpp:1378-1379 `while(true) pause()`) + 60 Hz loop never throttle backgrounded; `onStop` only flushes config (:2436-2444) — pinned core + Mali GPU screen-off. PERF-BATTERY-NETWORK-02 (P2, subordinate): no `WAKE_LOCK`/`FOREGROUND_SERVICE`/`keepScreenOn` — AFK survival is incidental; the "no foreground service = Play red flag" is **moot for a sideload**. These gate the canonical long-session/AFK use cases. *Honest nuance: both plugins already call `setSwapInterval(0)` (GpuPlugin.java:528-548, HdPlugin.java:1921-1951) and the 20 ms timer caps ~50 fps — the defect is the **missing closed-loop governor**, not a static default.* **Rec:** land a PowerManager thermal listener → live preset/swap-interval downgrade on MODERATE+, and a `g_paused` visibility gate from `onStop`; then measure on the matrix as explicit gate rows. **L · med.**

**PROD-COMPAT-01 (P1, release-blocker — conditional on a public launch).** `app/build.gradle.kts:13-14` minSdk 28 → targetSdk 35 (6 OS majors); :18-22 `arm64-v8a` only. The vendor-sensitive native EGL paths (`librlawt.cpp` `rcEglCreateContextCurrent`, reverse-Z `EXT_clip_control` self-test, ES-tier downport, FBO build) have only run on **Mali-G710 + SwiftShader emulator** (02-performance.md:241, 03-ux-qa.md:313). Landscape-right cutout never audited (UX-XCUT-01). `isTv` force-boots 117HD untested (MainActivity.kt:882-887). **Default renderer is CPU** (vendor-independent) so a fresh install boots regardless of GPU, and accelerated is auto-promoted only after `probeGpuCaps()` — hence P1-not-P0, and the framing "for a public launch" does not match this build's reality. **Rec:** Firebase Test Lab caps-probe + Game-Loop 117HD across {Adreno, Mali new+old, PowerVR, Xclipse} × {9,12,14,15} × {phone, cutout-landscape-right, foldable, tablet, TV}; treat the `:recon` VERDICT log as the per-device gating record; manually verify cutout/foldable/leanback; document x86 ChromeOS/Android-on-PC as unsupported. **L · high.**

**PROD-COMPAT-02 (P2, nice-to-have) — no caps gate in `librlawt`, ~240 s black-screen.** `librlawt.cpp:799` `rc_AWT_createGLContext` silently `LOGE`+returns on EGL bring-up failure with **no** Kotlin/native signal and **no** caps gate; no `gpuInitFailed` flag; no in-session restart-into-CPU. The only recovery is the next-launch crash-cookie, armed **only** for `prefRenderer ∈ {gpu,117hd}` (:907-911) — marker/Game-Loop/TV boots get zero recovery, and the protected path still shows black up to the 240 s watchdog. The **auto-promote path IS caps-gated** (`probeGpuCaps()` :952-1009 does a real EGL ES3 context-create + `MAX_SAMPLES`/`MAX_COMPUTE`, returns `cpu` on failure), and default is CPU — so the unprotected boots are deliberate/niche. *Honest nuance: `probeGpuCaps` does not validate the `librlawt` FBO/MSAA build at scene size, so the in-session fallback is the real safety net.* **Rec:** run the caps gate on **every** accelerated path + add an in-session native-flag CPU fallback (reuse `restartIntoGpu`/`finishAffinity`+`exit(0)`); tighten the 240 s watchdog past the known Zone-compile time; arm the crash-cookie for TV. **M · med.**

**PROD-COMPAT-03 (P2, nice-to-have) — TV force-boots 117HD unprotected.** `AndroidManifest.xml:18-19` leanback/touchscreen `required=false`, :41 `LEANBACK_LAUNCHER`; MainActivity.kt:882-887 `isTv` → `rlhdMode` includes `(isTv && !cpuForced)` with no caps re-run and no crash-cookie (:907 arms only `{gpu,117hd}`). Never run on TV hardware; 117HD is the heaviest renderer. **Rec:** for a phone-first v1, scope TV **out** (drop `LEANBACK_LAUNCHER` for release, or default the TV path to the caps-probed renderer + crash-cookie); document the decision. **S · low.**

### 4g. Versioning / update

**PROD-VERSIONING-UPDATE-04 (P3, nice-to-have) — placeholder version.** `app/build.gradle.kts:15-16` `versionCode=1`/`versionName="0.1"`; no CHANGELOG; no derived versionCode logic. Embeds engine `1.12.29-SNAPSHOT`. A static versionCode does **not** block the first release; it only blocks an install-over for a second build with the same code, and `adb install -r` tolerates equal/lower codes — so it doesn't even bite the actual redeploy workflow. **Rec:** semver versionName + monotonic versionCode (commit-count / date-int / CI build number) tied to a git tag; add `CHANGELOG.md` (also surfaces which upstream merge a build carries). **S · low.**

**PROD-VERSIONING-UPDATE-05 (P3, nice-to-have) — no auto-update / in-app check.** No `github-releases`/`api.github`/`checkForUpdate`/`AppUpdate`/`play.core`/`REQUEST_INSTALL_PACKAGES` code (all `VERSION_*` hits are `Build.VERSION.SDK_INT` SDK guards). Distribution is sideload via `deploy-wireless.sh`, so Play auto-update does not apply. The "old build has no signal" harm presupposes other users, which this build excludes; protocol drift is solved by the operator rebuilding from upstream. **Rec (if distribution ever broadens):** best-effort GitHub-Releases tag check → non-blocking Custom-Tab prompt, fail-open; **no** in-app installer (avoids `REQUEST_INSTALL_PACKAGES`). **M · low.**

**PROD-QA-06 (P2, nice-to-have) — versioning + re-unpack stall.** Frozen `versionCode 1`/`0.1` (= UPDATE-04); no in-app/Play update; the substantive part: `AssetInstaller.kt` uses a single marker `.installed-tag` keyed to `"$BASE-${runtime.tag}"` and on any tag change does `root.deleteRecursively()` then a full `copyAssetDir("jvm")` — **not** incremental. Sizes on disk: `assets/jvm` = 198 MB total, JRE = 132 MB (byte-identical, recopied needlessly), gpu+rlhd jars ≈ 38 MB, release APK = 116 MB. The "~30 s frozen black screen on every update" is well-founded — the same defect as PERF-COLD-START-06 (P2) + UX-ONBOARD-01 (no progress UI). For a personal sideload a ~30 s update freeze is an annoyance, not a launch blocker. **Rec:** land the incremental split (JRE-under-BASE / jars-under-tag) so an update recopies ~38 MB not 198 MB + a boot progress UI; auto-bump versionCode in CI. The un-splittable `assets/jvm` makes every update a ~116 MB full download (no Play asset delivery). **M · med.**

---

## 5. Distribution recommendation

**Recommendation: ship via GitHub Releases signed-APK sideload, kept personal-scope.** This matches the project's locked posture and is the only channel whose policies the app can satisfy.

| Channel | Verdict | Reasoning |
|---|---|---|
| **GitHub Releases (signed APK sideload)** | **RECOMMENDED** | No content-policy review; the maintainer holds the signing key + update continuity; risk caveat can be stated plainly. Matches the personal/sideload-by-design posture. |
| **Google Play** | **REJECTED** | (a) Jagex IP/ToS + not on the approved-client list → DMCA takedown + dev-account termination; (b) **Play's runtime-code policy forbids loading/executing downloaded interpreted bytecode** — the embedded HotSpot JVM + the Plugin-Hub `URLClassLoader` are direct violations; (c) impersonation/family-policy exposure (RuneScape trademark). No data-safety form, no R8 — but the policy bars are categorical regardless. |
| **F-Droid** | **REJECTED** | Requires a **100% FOSS, reproducible build with no proprietary blobs**. The SHA-pinned prebuilt bionic OpenJDK JRE (`libjvm.so` + 33 `.so` + jimage) and the non-free OSRS assets (`runescape.ttf`, sprites, `rlhd.jar`) are disqualifying. |

**Release-page must state:** "Unofficial; not affiliated with or endorsed by Jagex. RuneScape / Old School RuneScape are Jagex trademarks. Sideload only — use at your own risk, including account ban." Use a **distinct app name** (never "Old School RuneScape"). Even GitHub hosting carries a DMCA-takedown + per-user ban tail; **keep it personal-scope and do not promote.**

---

## 6. Required legal/privacy docs checklist + data-handling table

**Checklist (before any public artifact — all currently MISSING except the two ✓):**

- [ ] **PRIVACY POLICY** — the data-flow table below; "no analytics/ads/third-party SDKs"; deletion path.
- [ ] **TERMS / DISCLAIMER** — unofficial, not affiliated/endorsed by Jagex; trademark notice; account-ban risk.
- [x] **RuneLite BSD-2** — already shipped at repo-root `LICENSE`.
- [x] **OpenJDK GPLv2+Classpath-Exception** — `jre/legal/` bundled (jlink regenerates the minimal set).
- [ ] **THIRD-PARTY-LICENSES / NOTICE aggregate** — bundle RuneLite BSD-2 + OpenJDK GPLv2+CPE + 117HD/rs117 + OSRS-asset attribution into one in-app/release-page notice.
- [ ] **RELEASE / LANDING PAGE** — what the app is, the unapproved-client + ban risk, the sideload install steps.

**Data-handling table (verified against code):**

| Data | Where handled | At rest / transit | Leaves device? | Deletion |
|---|---|---|---|---|
| Jagex `id_token` | Custom Tab + clipboard (LoginActivity.kt:147-156) | transient; cleared from clipboard after import (`clearLink`/`clearPrimaryClip` :174-178) | No (handled by Jagex in-browser) | automatic on import |
| `session_id` / account-id / display-name | `tokens.json` in `filesDir` | **plaintext** (P1: encrypt) | No (app-private) | in-app Logout (`JagexTokens.deleteAll`) / uninstall |
| Legacy username + **password** | `legacy_login.json` in `filesDir` | **plaintext** (sharp edge — encrypt or drop) | No (app-private) | in-app Logout / uninstall |
| `JX_*` / `RL_LEGACY_*` | process env (`/proc/self/environ`) | in-memory; root/forensic only | No | process exit |
| RuneLite-account session (UUID) | `SessionManager` on disk | persisted | **Yes — synced to runelite.net (3rd party; link their policy)** | Account Logout (currently missed by `doLogout` — SECURITY-10) |
| Screenshots | `MediaStore Pictures/RuneLite` | shared gallery | No (on-device gallery) | user-managed |
| Analytics / ads / crash telemetry | — | none | **No (none present)** | n/a |

State "not directed at children under 13" and recommend account holders meet Jagex's age terms.

---

## 7. CI/CD pipeline design — Android workflow to add

A **separate** workflow from the upstream `CI.yml` (which stays as-is for the desktop client). Stages:

1. **checkout** (full, for commit-count versionCode).
2. **setup** — `setup-java@v4` java 21 + Android SDK/NDK + CMake; restore Gradle + artifact caches.
3. **bootstrap (cached)** — `stage-android-jre.sh` (cache the ~28 MB JRE tarball keyed by its pinned SHA; **mirror the third-party host** per RELEASE-ENG-06) → `stage-mobile-runtime.sh` → gpu/rlhd surgery scripts. *(Or fetch pinned prebuilt jars.)* These become the Gradle staging tasks from RELEASE-ENG-01 so the build is self-bootstrapping.
4. **assemble** — `./gradlew :app:assembleRelease` (explicit module; **never** bare `assemble` — excludes `:recon`).
5. **sign** — release keystore from masked secrets (base64 keystore + passwords as env); v2/v3.
6. **secret guard** — fail if `unzip -l app-release.apk | grep -E 'tokens.json|legacy_login.json'` is non-empty (SECURITY-01).
7. **asset guard** — fail if merged assets lack `client-*-gpu.jar`/`client-*-rlhd.jar` or `lib/arm64-v8a/librlawt.so` (RELEASE-ENG-01/QA-02).
8. **lint** — `./gradlew :app:lintRelease` fail-on-error.
9. **contract gate** — `tools/shared-contract/check-contract.sh` (fail on exit 2 = breaking, warn on 1 = additive) + the `bridge-census` (RELEASE-ENG-07).
10. **device smoke** — submit the Game-Loop APK + the `:recon` caps probe to Firebase Test Lab Robo across the COMPAT-01 pool; record each `:recon` VERDICT.
11. **publish** — upload the signed APK to a **private GitHub Release**. **No** Play/TestFlight publish. **Never** expose `tokens.json` to CI.

GitHub Actions is disabled by project policy, so this is the design to re-enable when a reproducible artifact is wanted.

---

## 8. Device-compatibility matrix + QA/release gating + launch checklist

**Device-compatibility matrix (target for a hypothetical GPU-diverse launch; per COMPAT-01).** Each cell must boot, reach first frame, and auto-GPU must promote-or-fall-back cleanly.

| GPU vendor | Android 9 | 12 | 14 | 15 | Form-factor notes |
|---|---|---|---|---|---|
| Adreno (Qualcomm) | ☐ | ☐ | ☐ | ☐ | unverified |
| Mali — new (G7xx) | — | — | ✅ G710 | — | **only verified cell** (Pixel 7a) |
| Mali — old (G5x/T-series) | ☐ | ☐ | ☐ | ☐ | unverified |
| PowerVR (imagination) | ☐ | ☐ | ☐ | ☐ | unverified |
| Xclipse (Samsung) | — | ☐ | ☐ | ☐ | unverified |

Form-factors to verify independently: **phone**, **large-phone notch landscape-right** (UX-XCUT-01, unaudited), **foldable** fold/unfold config-change, **tablet**, **Android TV/leanback** (COMPAT-03 — scope out for v1). **arm64-v8a only** correctly excludes x86 phones; document x86 ChromeOS/Android-on-PC as unsupported.

**QA / release gating criteria (the `GATE.md` rows — QA-03):**
1. Clean-install boot to first frame on the device matrix.
2. All three renderers + auto-GPU promote **and** its CPU fallback, per GPU vendor.
3. Jagex login + RuneLite-account sync + reconnect-after-network-drop.
4. ≥10 min 117HD thermal soak holds a sustainable fps floor (needs the thermal governor — QA-09).
5. Backgrounded/screen-off drain bounded (needs the visibility gate — QA-09).
6. a11y smoke (TalkBack labels core controls) — *documented row, not a hard stop-ship (UX-XCUT-04 is P2).*
7. needs-device UX/perf batches triaged.
8. **Zero open P0/P1.**

**Launch checklist (ordered):**
1. **PROD-SECURITY-01** — strip `tokens.json` from packaging + rotate the bundled session. *(P0, gate everything else behind this.)*
2. **PROD-RELEASE-ENG-01 / PROD-QA-02** — wire GPU/rlhd/`librlawt` staging into Gradle + a fail-fast asset guard → a reproducible bootable APK; delete the stale Jun-8 APK.
3. **PROD-RELEASE-ENG-02 / DISTRIBUTION-02** — dedicated release keystore (one stable private key).
4. **PROD-QA-04 / SECURITY-07 / SECURITY-12 / CRASH-TELEMETRY-02** — one DEBUG-gating change for capture/`gpuframedump`/`fpsprobe`/`rlhdprobe`-stdio/NetProbe; **SECURITY-04** move `TEST_LOOP` to `src/debug`.
5. **PROD-QA-09** — land the thermal governor + background visibility gate; device-measure.
6. **PROD-COMPAT-02 / COMPAT-03** — caps-gate every accelerated path + in-session CPU fallback; scope TV out for v1.
7. **PROD-QA-03** — write `GATE.md`; **PROD-QA-01 / RELEASE-ENG-03** — stand up the Android CI workflow (§7); **RELEASE-ENG-07** — promote the contract gate.
8. **PROD-COMPAT-01** — run the device matrix on Firebase Test Lab.
9. **PROD-QA-07** — real app name (no Jagex implication) + adaptive icon; **PRIVACY-LEGAL-01/03** — privacy/disclaimer/attribution + data-flow table; **DISTRIBUTION-01** — release-page risk disclosure.
10. **PROD-SECURITY-03 / PRIVACY-LEGAL-02** — encrypt the legacy password at rest (or drop legacy creds); **SECURITY-10** — `accountLogout` in `doLogout`.
11. **PROD-VERSIONING-UPDATE-04 / QA-06 / VERSIONING-UPDATE-05** — semver + monotonic versionCode + CHANGELOG; incremental re-unpack + boot progress UI; best-effort update check.
12. **PROD-QA-08** — keep prior signed APKs downloadable + document recovery; accept the unmitigable ban risk.

> Steps 1–3 produce a *safe, reproducible, bootable* artifact (the minimum to hand the APK to even one other person). Steps 4–8 make a release *trustworthy on diverse hardware*. Steps 9–12 are the public-facing wrapper — needed only if distribution ever broadens beyond the author's own device, which the project's takedown/ban realities advise against.
