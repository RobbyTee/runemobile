# Phase 7 — Repo Structure & Release Track

**TL;DR**
- **Repo structure:** Split into a **public-Android repo (the canonical RuneLite fork)** + a **private-iOS repo that consumes the public-built jar AND keeps the full fork sources for local builds** — because the shared core is the *entire* RuneLite fork (not just `headless/`), so iOS cannot build from an `ios/`-only slice.
- **Alpha vs Beta:** **ALPHA** (a mature, feature-rich alpha) — because a fresh `assembleRelease` today is both **un-bootable** (`PROD-RELEASE-ENG-01`/`PROD-QA-02`) and, if it booted, would **leak a live account** (`PROD-SECURITY-01`); you cannot be in beta with an artifact that neither boots nor is safe to hold.

---

## Repo structure

### The constraint that decides everything

The split is **one Java source tree that both ports compile by name-resolution at runtime**, not "two apps sharing a folder." iOS resolves `net/runelite/client/headless/*` bridges by `FindClass` (`JVMLauncher.m`); Android does the same (`jvm_bootstrap.cpp`). Neither imports the other — both class-load the **same compiled bytecode** out of `client-*-{shaded,gpu,rlhd}.jar`. One staging script, `tools/stage-mobile-runtime.sh`, builds that jar for both ports (`--ios-only`/`--android-only` are output-destination flags, one source). The contract is pinned in `tools/shared-contract/baseline.txt`, gated by `check-contract.sh` (exit 0 unchanged / 1 additive / 2 breaking).

**Correction (load-bearing):** the shared core is **NOT** the `headless/` package. The iOS jar is produced by `:client:shadowJar`, which compiles the **entire `runelite-client` module (21 top-level packages)** plus `includeBuild` of `runelite-api`, `cache`, and `runelite-gradle-plugin`. Our W2 edits live **outside** `headless/`: 9 modified non-`headless` `runelite-client` files above merge-base (`Hooks.java` +53, `RuneLite.java`, `PluginManager.java` +32, `ClientUI.java`, `OverlayRenderer.java`, `LinkBrowser.java`, `SplashScreen.java`, `Notifier.java`) plus the net-new 293-line `HeadlessClientUI.java`. **iOS needs all of these.** `headless/` is only the *bridge subpackage* of one module — the shared core is **the whole fork + our W2 edits across `runelite-client`/`api`/`cache`**.

**The coupling is hot, not occasional:** of **845 commits** above upstream merge-base `d9023f9361`, **245 (29%) touch the shared `headless/` tree**, and **147 (17%) touch both `ios/` and `runelite-client/` in the same commit** — iOS-native, Android, and shared-engine work are interleaved at the commit level. Any split that puts a network/publish boundary in the middle of the shared core taxes a third of all future commits, and **no clean path-based history split exists** (see migration).

### The options

| Option | How shared core stays in sync | Upstream-merge impact | History-scrub fit | iOS velocity | Verdict |
|---|---|---|---|---|---|
| **A — single public repo (A1 iOS in-tree / A2 iOS submodule)** | A1: zero cost (one tree). A2: shared core must live in the public super-repo anyway; submodule holds `ios/`-native only | Unchanged (the public repo *is* the fork) | A1: scrub once. A2: scrub **both** repos — submodule still wants sprite/script removal | A1 highest; A2 medium (2-repo commit + pointer bump on ~29%) | **Reject.** A1 publishes iOS (violates premise). A2 inherits submodule footguns *and* still scrubs both halves |
| **B — public-Android + private-iOS, iOS consumes the built jar (+ keeps fork sources)** | Public-Android holds the **whole fork** + shared core + contract; private-iOS consumes the **published jar** as the ABI and keeps fork sources for local builds | **Major win:** `merge upstream/master` happens only in public-Android; iOS re-pins the post-merge jar | **Cheapest:** public-Android born clean from the squash; **all heaviest scrub legs are under `ios/`** → never enter public | Best: iOS pays nothing unless it needs a *new* bridge | **RECOMMENDED** |
| **C — monorepo with path-scoped visibility** | n/a | n/a | n/a | n/a | **Reject.** Git has no path-level ACL; every clone gets every blob. Collapses into A2/B the moment you implement it |

### Why B — and the mechanics correction

B is the only option satisfying all four constraints at once — (1) Android public, (2) iOS private, (3) shared core in sync, (4) `merge upstream/master` unchanged — **and** it makes the scrub dramatically cheaper: the hardest legs all live under `ios/` and never enter the public repo (the iOS 23 `tabassets/` sprites, `ios/dev-tools/fetch-shield-tokens.sh`, the 15 PII-bearing `ios/app/evidence/*.log`, the 7× `ios/AUDIT-BRIEF-*.md`). The Android+shared surface is all that needs cleaning.

**The mechanics from the first draft were wrong and are corrected here:**
- **iOS cannot be an `ios/+headless/` slice.** `git filter-repo --path ios/ --path .../headless/` produces a private-iOS repo that **silently fails to build on first clean clone** — the shaded jar's source (the rest of the fork + the non-`headless` W2 edits) was filtered out. Private-iOS must contain the **whole fork** (to build locally) **and/or** consume the **published jar** as the runtime ABI.
- **B "published jar" is the *primary* seam, not a graduate-to.** Because iOS already treats the bridges as an opaque ABI it resolves by name, the architecturally honest seam is: public-Android CI publishes the staged `client-*-{shaded,gpu,rlhd}.jar` + embedded `baseline.txt` SHA as versioned release assets; private-iOS's `stage-ios-zone-jar.sh` consumes the pinned version. A subtree of `headless/` **alone cannot build iOS** and is rejected; if iOS keeps sources for IDE/debug it keeps the **whole fork**, not a `headless/` prefix.
- **`git-filter-repo` is NOT installed** (no PATH binary, no python module) — every filter-repo leg is blocked until install. The **squash** (G3) is the only history op that runs on this machine today, and it is **mandatory, not a convenience** (the 147 interleaved commits mean no path-based history split is clean).

### Migration steps (creating public-Android; honoring the FATAL sprite blocker + scrub)

> All gated; run the Phase-6 §5 publish-gate greps (must return empty) before the visibility flip. **Redraw the sprites first** — `tabassets/` removal without replacement leaves Android un-skinned.

1. **Redraw the 20 Android sprites** under `android/app/src/main/assets/tabassets/` (original chrome/stone/icon/chat art). Clears the public-surface leg of FATAL `OSS-LICENSE-ATTRIBUTION-01`. (iOS's 23 sprites stay in the private repo's history — fine **only** while iOS stays private; standing constraint: if iOS ever publishes, its history is still dirty.)
2. **Squash to a born-clean public history (G3 — load-bearing).** On a fresh clone: `git reset --soft d9023f9361`, then in the same staged index remove everything that must not ship publicly (the *original* Android `tabassets/`, the entire `ios/` tree, the internal `*.md` notes incl. `android/audit/*`), neutralize `.gitignore:43-44`, then `git commit --author='Jordan Simpson <sayhijordy@gmail.com>'` with a neutral message. This collapses the 845-commit narrative into **one** authored commit over preserved upstream history and in one move moots `OSS-AISCRUB-03` (the 3 tooling-named commit messages `79aeb0ed25`/`38e8f2f223`/`e9dfcd8751`, all Android/shared-named so they *do* land in the public blast radius — the squash removes them), `-04` (old `.gitignore` blob), `OSS-AISCRUB-06` (PII), and `OSS-SECRETS-HISTORY-02` (the mint script). The shared `headless/` tree is grep-clean at HEAD (verified empty), so the public jar carries no tooling traces.
3. **Split out private-iOS.** Push the **whole current fork** (not an `ios/+headless/` slice) to the private remote so iOS builds. It keeps its full, interleaved, never-published history — no scrub burden. (Blocked on `git-filter-repo` install only if you attempt a path-filtered slice; the un-sliced push needs no filter-repo.)
4. **Run the Phase-6 §5 publish-gate greps** against the squashed public tree (the commit-message-history scan, the tracked-tree scan, and the secrets/PII scans — all must return empty; the literal search patterns live in Phase 6, the designated internal scrub-planning doc). Step 2's `android/audit/*` removal clears most doc hits; reword any kept Android/shared doc's review-tooling phrasing to "a follow-up review pass."
5. **Author the public doc surface (`OSS-REPO-DOCS`):** `LICENSE` (BSD-2 + author stanza), `THIRD-PARTY-NOTICES.md`, `README.md` (honest unofficial/sideload/ban-risk framing, Android-only), `CONTRIBUTING.md` (the shared-contract rule + `check-contract.sh`; note `headless/` is the cross-port ABI), `SECURITY.md`, `.github/` templates (replace the upstream-pointing ones).
6. **Wire the shared-core seam (primary: published jar).** Public-Android CI publishes the staged jars + embedded `baseline.txt` SHA; private-iOS pins a jar version. `merge upstream/master` happens **only** in public-Android; the contract check becomes a CI **release gate** (`PROD-RELEASE-ENG-07`) rather than a bypassable local hook.
7. **Push to a new public remote and flip visibility (G8)** — final gated action, only after step 4's greps are clean. Do **not** force-push `origin` (`audaro/runelite-ios`) over the dirty history; push the squashed tree to a **new** public repo and keep `audaro/runelite-ios` private as the iOS/working remote.

**Net effect:** Android goes public with a born-clean single-commit-over-upstream history; iOS stays fully private with zero scrub obligation; the shared core has exactly one home (public-Android) so it can't drift, with the contract check as its release gate; and `git merge upstream/master` works unchanged because the public repo *is* the fork.

---

## Alpha vs Beta

### The two bars

| | ALPHA | BETA |
|---|---|---|
| **Feature state** | Feature-incomplete/unstable; proves the concept | Feature-complete (headline flows intended-final in shape) |
| **Stability** | Breakage expected & acceptable | No known criticals; maintained known-issues list |
| **Release-engineering** | None required | Reproducible signed builds, crash/field signal, a defined QA gate |
| **Testers** | Small internal/known set | Wider, pre-GA; usable by someone who didn't build it |
| **Dividing question** | — | **Can a person who didn't build it install a fresh artifact, trust it, and recover when it breaks?** |

### Evidence-based assessment

- **Feature-completeness — split axis (the honest framing).** The **engine is beta-complete**: real unmodified RuneLite headless on in-process OpenJDK 21 with full PluginManager/overlay/ConfigManager (`00-map §1/§3.1`), three caps-probed renderers with CPU default + auto-promote (`PROD-COMPAT-01`), Jagex login + RuneLite-account sync + Plugin-Hub install (`UX-ACCOUNT-02`), deep native HUD/panel veneers (`00-map §3.2`). But the **app-shell is alpha-incomplete**: `UX-ONBOARD-01` (~30 s black cold start), `UX-ONBOARD-03` ("HelloShell"/TV-banner icon), `UX-LOGIN-01` (session-expired recovery exists but is **buried** at `MainActivity.kt:521` — a status-visibility fix, not "no recovery"), `UX-PLUGCFG-01` (drops `@ConfigSection`), `UX-XCUT-01/02/03` (zero window-insets on the sole HUD controls/long-press menu/Compose sheet), `UX-XCUT-04` (zero a11y).
- **Stability — squarely ALPHA.** Phase 5's 7-row release-blocker list: **3 P0** — `PROD-SECURITY-01` (a live replayable Jagex secret, broader than a fresh login, packaged into `assembleRelease`), `PROD-RELEASE-ENG-01` + `PROD-QA-02` (gpu/rlhd jars + `librlawt.so` staged by hand-run scripts wired into nothing in Gradle → `MainActivity.kt:2603-2610` hard-aborts before `JNI_CreateJavaVM`; the on-disk Jun-8 APK is already this failure). **P1:** `PROD-RELEASE-ENG-02`/`DISTRIBUTION-02` (debug-key signing), `PROD-QA-01`/`-03` (no CI, no defined gate), `PROD-QA-09` (`PERF-MEMORY-THERMAL-01` SEVERE throttle in ~1–2 min; `PERF-BATTERY-NETWORK-01` no background-visibility gate). Phase 6 adds **FATAL** `OSS-LICENSE-ATTRIBUTION-01` (proprietary sprites across history on both ports).
- **Testers/QA — ALPHA.** One device (Pixel 7a / Mali-G710) + a SwiftShader emulator (`PROD-COMPAT-01`); no device matrix, no CI smoke (`PROD-QA-01`), no crash signal for the two-VM model (`PROD-CRASH-TELEMETRY-01`).

**Grading guards (kept faithful to the source severities):** `PROD-COMPAT-01` is **P1/conditional**, deferred to **GA** — the default renderer is CPU (vendor-independent) and GPU is auto-promoted only after a live `probeGpuCaps()` EGL probe, so multi-GPU coverage does **not** block alpha→beta. `PROD-CRASH-TELEMETRY-01`/`PROD-QA-05` is **P2-not-P1** (Phase 5 de-dup) — a beta-entry *floor* (extend the crash-cookie), **not** a release-blocker.

### DECISION: **ALPHA** — unambiguously

The single strong axis (engine feature-completeness) cannot outweigh three open P0s, a fatal IP blocker, zero release-engineering, zero crash signal, a single-device surface, and missing app-shell fundamentals. Calling this beta is a category error: a fresh `assembleRelease` is **un-bootable** and, if it booted, would **leak a live account**. This is a **mature, feature-rich alpha** — the hard engineering is done; productization and release-engineering have not started. The project's locked posture (personal-sideload, throwaway account, never distributed) moots the label for the author's own device — but alpha/beta *is* the question of broadening past one person, and the moment you broaden, `PROD-SECURITY-01` + `PROD-RELEASE-ENG-01` + the sprite blocker are live.

### EXIT CRITERIA — Alpha → Beta

*Beta = a second person installs a fresh signed artifact, it boots all renderers, leaks no secret, and breakage is observable + recoverable.*

1. **Boots & safe to hold:** `PROD-SECURITY-01` (strip `tokens.json`, gate first launch through `LoginActivity`, rotate the burned session; CI `unzip -l | grep -E 'tokens.json|legacy_login.json'` empty).
2. **Bootable APK:** `PROD-RELEASE-ENG-01` + `PROD-QA-02` (wire gpu/rlhd/`librlawt` staging into Gradle with a fail-fast asset guard; delete the stale APK).
3. **Signature continuity:** `PROD-RELEASE-ENG-02`/`DISTRIBUTION-02` (one managed, gitignored release keystore).
4. **IP-clean to share:** `OSS-LICENSE-ATTRIBUTION-01` (redraw/remove sprites across HEAD + history both ports) + `OSS-SECRETS-HISTORY-02` (remove the mint script).
5. **Observable floor:** `PROD-CRASH-TELEMETRY-01`/`PROD-QA-05` (extend the crash-cookie with failing-renderer + GPU string; opt-in local capture) — a beta *floor*, not a blocker.
6. **A real gate exists and passes:** `PROD-QA-01` (CI: `:app:assembleRelease` + `lintRelease` + `check-contract.sh` fail-on-breaking + device-smoke to first frame), `PROD-QA-03` (`GATE.md`), `PROD-RELEASE-ENG-07` (contract gate required in CI — protects iOS from silent shared-bridge drift).
7. **Survives a session:** `PROD-QA-09` (PowerManager thermal governor + `g_paused` background gate; ≥10-min 117HD soak holds an fps floor, bounded background drain — device-measured).
8. **App-shell stops reading as a dev build:** `UX-ONBOARD-01` (splash/progress; folds in `PERF-COLD-START-01/06`), `UX-ONBOARD-03` (real name + adaptive icon), `UX-LOGIN-01` (surface a native session-expired overlay), `UX-PLUGCFG-01` (consume `@ConfigSection`), `UX-DISCRETE-01` ("Hopping…" scrim), `UX-XCUT-01` (inset the sole HUD controls off the cutout).
9. **Identity/disclosure:** `PROD-QA-07` (non-Jagex-implying name + release-page risk disclosure; publish a known-issues list).

*Deferred past beta entry (legitimate known-issues, not blockers):* `UX-XCUT-04` a11y (P2), most `PERF` P2/P3 levers, `PROD-COMPAT-01` multi-GPU (a **GA** gate), legacy-cred encryption `PROD-SECURITY-03` (if legacy stays dev-only).

### EXIT CRITERIA — Beta → GA

*GA = trustworthy on hardware you didn't test, with a maintenance story.*

10. **Device matrix (headline GA gate):** `PROD-COMPAT-01` ({Adreno, Mali new+old, PowerVR, Xclipse} × {Android 9/12/14/15} × {phone, cutout-landscape-right, foldable, tablet} each boots/first-frame/promotes-or-falls-back; Firebase Test Lab + the `:recon` caps probe per-device); `PROD-COMPAT-02/03` (caps-gate every accelerated path with in-session CPU fallback; scope/protect TV out).
11. **Gate green, zero open P0/P1:** `PROD-QA-03` in full (every row green with device/owner/evidence incl. reconnect-after-drop + soak).
12. **Legal/disclosure:** `PROD-PRIVACY-LEGAL-01/03` + `DISTRIBUTION-01` + `OSS-REPO-DOCS-01/04` (privacy policy, disclaimer/ToS, third-party notices, data-flow table, README/SECURITY, unapproved-client + ban-risk disclosure).
13. **Secrets hardening:** `PROD-SECURITY-03`/`PRIVACY-LEGAL-02` (Keystore-wrap or drop the legacy plaintext password); `PROD-SECURITY-10` (`doLogout` also calls `accountLogout()`).
14. **Update story:** `PROD-VERSIONING-UPDATE-04`/`QA-06`/`UPDATE-05` (semver + monotonic versionCode + CHANGELOG, incremental re-unpack `PERF-COLD-START-06`, best-effort update check); `PROD-QA-08` (keep prior signed APKs + documented recovery).
15. **Residual UX backlog:** `UX-XCUT-02/03/04`, `UX-HUD-01`, the input-feel deltas — cleared or consciously accepted-and-documented.

**The ceiling GA cannot launder:** even a perfect GA build is an unapproved-client for live OSRS — the **account-ban risk is irreducible** (`05-production §1/§5`) and Play/F-Droid are categorically barred (`PROD-DISTRIBUTION-01`: Play's runtime-code policy vs the embedded JVM + Plugin-Hub `URLClassLoader`; F-Droid's FOSS-reproducible bar vs the prebuilt bionic JRE + non-free assets). "GA" here means a polished, reproducible, disclosed GitHub-Releases sideload kept personal-scope — a property of the product, not a gap to close.
