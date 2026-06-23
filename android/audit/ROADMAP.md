# Capstone ROADMAP — Android deep audit → ALPHA → BETA → GA

> Synthesis of the eight committed deliverables (`00-map` → `07-repo-and-release-track`). Every finding id is verbatim — they are the cross-references back to each phase. This is the single prioritized, phased plan from "mature alpha" to a defensible GitHub-Releases sideload and beyond. The single most important section for the operator is **§5 — the consolidated NEEDS-THE-DEVICE / NEEDS-YOU batch**; jump there for copy-paste-actionable device runs and gated go/no-go decisions.

## Table of contents
- [1. Executive summary](#1-executive-summary)
- [2. The prioritized roadmap (4 tiers)](#2-the-prioritized-roadmap)
  - [P0 — Release-blockers](#p0--release-blockers)
  - [P1 — Stability / perf / security (gate beta)](#p1--stability--perf--security-gate-beta)
  - [P2 — UX polish / hardening + the slimming wins](#p2--ux-polish--hardening--the-slimming-wins)
  - [P3 — Enhancements / nice-to-haves](#p3--enhancements--nice-to-haves)
- [3. The open-source cutover milestone (GATED)](#3-the-open-source-cutover-milestone-gated)
- [4. The alpha → beta → GA gate (15 exit criteria)](#4-the-alpha--beta--ga-gate)
- [5. CONSOLIDATED "NEEDS THE DEVICE / NEEDS YOU" BATCH](#5-consolidated-needs-the-device--needs-you-batch)

---

## 1. Executive summary

**What this app is.** `dev.audaro.helloshell` is a native Android app that runs **real, unmodified RuneLite headless on a second, in-process HotSpot JVM** (embedded arm64 OpenJDK 21, `JNI_CreateJavaVM`) and presents it as an OSRS-Mobile-styled touch client. A Kotlin/Compose shell hosts the live frame in a `SurfaceView`, injects touch/keys through a reverse-JNI bridge, and reads per-frame snapshots; three mutually-exclusive renderers (CPU software default, base-GPU, 117HD) are chosen once at process start. The shared engine bridges under `runelite-client/.../headless/` are class-loaded by name on **both** ports, so most shared-core edits ripple to iOS.

**Headline verdict (Phase 7): a mature, feature-rich ALPHA — NOT publicly shippable today.** The **engine is beta-complete** (full PluginManager/overlays/ConfigManager, three caps-probed renderers, Jagex login + RuneLite-account sync + Plugin-Hub install, deep native HUD/panel veneers). The **app-shell and release engineering are alpha-incomplete**: a fresh `assembleRelease` is **un-bootable**, and if it booted it would **leak a live account**.

**The single biggest blocker.** `PROD-SECURITY-01` — a live, replayable Jagex session secret (`tokens.json`, broader than a fresh on-device login) is packaged into the artifact: handing the APK to one person leaks an account. It is the one finding that survives the project's personal-sideload downgrade and gates everything else.

**The slimming opportunity (~58–70 MB).** Against a *correctly-built* release APK: **conservative ~58 MB** (drop the dead shaded.jar from Android assets `SLM-JAR-01` −29.4, narrow `noCompress` so the JRE `.so`/jars DEFLATE `SLM-PKG-02` −16 + `SLM-JAR-04` −3.4, drop `jdk.localedata` `SLM-JRE-02` −~10); **optimistic ~70 MB** if the full jlink keep-set re-link lands (`SLM-JRE-01/-04`). Installed/unpacked savings are much larger (~33–47 MB off the ~199 MB tree), which also shrinks the ~30 s first-launch unpack stall.

**The UX verdict (Phase 3): a desktop port with a native veneer — not yet OSRS-Mobile-class.** The in-game classic-View veneers (chat/minimap/tab-grid) are well-styled and inset-correct; the seam is everything *around* the scene — onboarding, the Compose config/panel layer, the long transitions (a routine world hop reads as a kick for ~8 s), and safe-area/a11y hygiene. 44 open defects (0 P0, 5 P1, 12 P2, 27 P3).

**The path.** Close P0s (safe + bootable + signed; redraw sprites if publishing) → device-compat matrix + renderer robustness + thermal/battery → CI/QA gate + app-shell polish (**beta entry**) → open-source cutover (squash to a born-clean public-Android repo) → device matrix + legal docs (**GA**).

**ALPHA-vs-BETA recommendation (explicit).** **Stay ALPHA; do not call this beta.** Beta = a person who didn't build it can install a fresh signed artifact, boot all renderers, leak no secret, and recover when it breaks. Today three open P0s + a FATAL IP blocker + zero release-engineering + a single-device surface contradict that. The hard engineering is done; productization has not started. The §4 alpha→beta gate is the work to *reach* beta.

---

## 2. The prioritized roadmap

Four tiers. Each row links to its finding id(s) in the source phase. Sequencing notes follow each table. **Shared-fix markers** call out where a slimming/perf item and a UX/enhancement item are the *same* edit.

### P0 — Release-blockers

> Must close before ANY artifact leaves the author's own device. The first three gate a *bootable, safe, trustworthy* APK; `OSS-LICENSE-ATTRIBUTION-01` is FATAL only **if publishing source/binary** (gate it with §3).

| id | item | phase-source | effort | risk | dependencies | scope |
|---|---|---|---|---|---|---|
| **PROD-SECURITY-01** (= SLM-PKG-08) | Strip `tokens.json` from release packaging; first launch gates through `LoginActivity`; **rotate the burned session** | 05 / 01 | S | low | none (do FIRST) | android |
| **PROD-RELEASE-ENG-01** | Wire gpu/rlhd/`librlawt` staging into Gradle (it lives outside Gradle today → clean build un-bootable) + fail-fast asset guard | 05 | L | high | — | android |
| **PROD-QA-02** (= SLM-JAR-06 / SLM-PKG-07 / SLM-DEP-02) | Reproducibly rebuild a bootable APK from current tree; boot all 3 renderers; delete the stale Jun-8 APK | 05 / 01 | M | low | PROD-RELEASE-ENG-01 | android |
| **PROD-RELEASE-ENG-02** (= PROD-SECURITY-02 / DISTRIBUTION-02) | Dedicated gitignored release keystore (replace AOSP debug-key signing); v2/v3; document rotation | 05 | M | low | — | android |
| **OSS-LICENSE-ATTRIBUTION-01** | FATAL OSS blocker — redraw the committed Jagex sprites, purge originals from HEAD + history (both ports) | 06 | L | med | §3 cutover; gates publish only | shared |

**Sequence within P0:** `PROD-SECURITY-01` first (a one-line packaging exclude + a session rotation; gate everything behind it). `PROD-RELEASE-ENG-01` → `PROD-QA-02` are one work-stream (the Gradle staging wiring *is* the bootable rebuild; re-baseline every size number from the resulting build — every Phase-1 MB figure depends on it). `PROD-RELEASE-ENG-02` is independent and small. `OSS-LICENSE-ATTRIBUTION-01` is only live if publishing — sequence it inside §3, after the sprite redraw and before the visibility flip.

### P1 — Stability / perf / security (gate beta)

| id | item | phase-source | effort | risk | dependencies | scope |
|---|---|---|---|---|---|---|
| **PROD-COMPAT-01** | Device-compat matrix (GPU-vendor × OS × form-factor) boots/first-frame/promote-or-fallback | 05 | L | high | bootable APK (P0) | android |
| **PERF-RENDERER-ROBUSTNESS-01** (= PROD-COMPAT-02) | EGL/GLES caps gate; fast clean CPU fallback vs 240 s silent black; honest caps (keep OpenGL33=TRUE on real ES3) | 02 / 05 | M | med | — | android |
| **PROD-QA-01** (= PROD-RELEASE-ENG-03) | Android CI: `:app:assembleRelease` + `lintRelease` + `check-contract.sh` + device-smoke | 05 | L | med | P0 staging | android |
| **PROD-QA-03** | `GATE.md` exit-criteria doc (device/owner/evidence per row) | 05 | M | med | PROD-QA-01/02, COMPAT-01 | android |
| **PROD-RELEASE-ENG-07** | Promote contract gate to a required CI step (protects iOS from shared-bridge drift) | 05 | S | low | PROD-QA-01 | shared |
| **PERF-MEMORY-THERMAL-01** (= PROD-QA-09 part) | Thermal governor — PowerManager listener → live preset/swap-interval downgrade on MODERATE+ | 02 / 05 | M | med | — | android |
| **PERF-BATTERY-NETWORK-01** (= PROD-QA-09 part) | Background/visibility gate (`g_paused` from onStop → 60→~4 Hz; stand down engine/FBO draw) | 02 / 05 | M | med | — | shared (gate android-only) |
| **PROD-SECURITY-03** (= PRIVACY-LEGAL-02) | Credential-at-rest: Keystore/`EncryptedFile`-wrap (legacy plaintext password is the sharp edge) or drop legacy from release | 05 | M | low | — | android |
| **PROD-RELEASE-ENG-06** | Make the clean-clone bootstrap explicit + ordered (`stage-android-jre.sh` → `stage-mobile-runtime.sh` → gpu/rlhd surgery) + SHA-pinned, and **mirror the single third-party JRE host** (the JRE is fetched from one external GitHub release → single point of failure) | 05 | M | med | feeds §3 build-from-clean (`OSS-BUILD-FROM-CLEAN-01`) | android |
| **UX-LOGIN-01** | Silent session-lapse / autologin-exhaustion parks on the raw desktop login with no native recovery — surface a failure-family flag through the chrome bridge (like `reconnecting`) → native "Session expired — sign in again" overlay launching `LoginActivity`, distinct from the transient reconnect scrim | 03 | M | med | — | android |
| **PERF-COLD-START-01** | 8 KB→1 MB copy buffer + stop unpacking the 3 unbooted jars (−30.6 MB filesDir, ~5–10 s) | 02 | S | low | — | android |
| **PERF-COLD-START-06** (= PROD-QA-06 part) | Incremental re-unpack (JRE-under-BASE / jars-under-tag): jar-only rebuild copies ~38 MB not 198 MB | 02 / 05 | M | low | — | android |
| **UX-ONBOARD-01** | Splash/branded progress over the ~30 s cold start (reuse `ReconnectingOverlayView` spinner) | 03 | M | low | benefits from COLD-START-01/06 | android |
| **UX-PLUGCFG-01** (= ENH-B01) | Consume the `@ConfigSection` grouping the bridge already emits (iOS-verified) | 03 / 04 | M | low | — | android |
| **UX-DISCRETE-01** (= ENH-B06; folds UX-DISCRETE-07) | "Hopping to world N…" scrim over the ~8 s relog so a hop stops reading as a kick | 03 / 04 | M | low | — | shared |
| **UX-XCUT-01** | Root `setOnApplyWindowInsetsListener` so the sole HUD controls clear the cutout/gesture bar | 03 | S | low | — | android |

**Sequence within P1.** The thermal governor + background gate (`PERF-MEMORY-THERMAL-01` + `PERF-BATTERY-NETWORK-01`) together *are* `PROD-QA-09` and must precede the soak rows in `GATE.md`. `PROD-COMPAT-01` (the matrix) and `PERF-RENDERER-ROBUSTNESS-01` (the caps gate that makes a tail GPU fall back cleanly) are paired — land the caps gate *before* running the matrix so failures self-report. `PROD-QA-01` (CI) must exist before `PROD-QA-03` (gate) and `PROD-RELEASE-ENG-07` (contract gate hangs off CI). **Shared-fix:** `PERF-COLD-START-01/06` (perf) directly feeds `UX-ONBOARD-01` (UX) and `PROD-QA-06` (update stall) — one cold-start work-stream serves all three. `UX-PLUGCFG-01`/`UX-DISCRETE-01` are the same edits as `ENH-B01`/`ENH-B06`. `UX-LOGIN-01` (native session-expired overlay) is independent of the reconnect scrim and gates beta exit-criterion #8. `PROD-RELEASE-ENG-06` (explicit ordered SHA-pinned bootstrap + mirror the third-party JRE host) is the documented-reproducible counterpart to the P0 `PROD-RELEASE-ENG-01` staging wiring — it is load-bearing for the §3 build-from-clean recipe (`OSS-BUILD-FROM-CLEAN-01`) and the Phase-5 CI design, so land it before the open-source cutover.

### P2 — UX polish / hardening + the slimming wins

| id | item | phase-source | effort | risk | dependencies | scope |
|---|---|---|---|---|---|---|
| **SLM-JAR-01** / SLM-PKG-01 / SLM-DEP-01 | Drop shaded.jar from Android assets (never a boot target) — **−29.4 MB** | 01 | S–M | low–med | PROD-QA-02 (re-baseline) | android |
| **SLM-PKG-02** + SLM-JAR-04 (SLM-PKG-03/05) | Narrow `noCompress` to `["so","dat","bin"]` → JRE `.so` + boot jars DEFLATE — **−~20 MB** download | 01 | M | med | — | android |
| **SLM-JRE-02** | Drop `jdk.localedata` (en-only safe) — **−~10 MB** download / ~29 MB installed | 01 | M | low | jmods-bearing JDK (JRE-01) or `-Duser.language` fallback | android |
| **SLM-JRE-03** | Drop debug/agent/JFR/RMI/JVMCI modules + their `.so` (incl. `libjdwp.so`) | 01 | S | low | — | android |
| **PROD-QA-04** (= SLM-DFLAG-01/02/03, PERF-FRAME-PACING-03/04/05, COLD-START-04, SECURITY-07, CRASH-TELEMETRY-02, SECURITY-12) | One DEBUG-gate change: capture/`gpuframedump`/`fpsprobe`/NetProbe/`rlhdprobe`-stdio | 05 / 01 / 02 | S | low | — | android |
| **PROD-SECURITY-04** | Move `TEST_LOOP` intent-filter to `src/debug/AndroidManifest.xml` | 05 | S | low | — | android |
| **PROD-SECURITY-10** | `doLogout` also calls `accountLogout()` (runelite.net session survives today) | 05 | S | low | — | android |
| **PERF-COLD-START-02/03/05** | AppCDS archive + `-Xms256m` + `-D`-tuned autologin warmup | 02 | M | med | needs-device feasibility (§5) | android |
| **PERF-FRAME-PACING-01** (= COMPOSE-UI-01 = MEMORY-THERMAL-03) | Short-circuit `updateChrome()` on unchanged snapshot (+ COMPOSE-UI-02 chat/dialogue) | 02 | S | low | — | android |
| **PERF-MEMORY-THERMAL-02/04** | GC selector + metaspace/codecache caps + `onTrimMemory` (non-flagship guardrail) | 02 | S–M | med | needs-device (§5) | android |
| **PERF-FRAME-PACING-06/07** | AChoreographer-paced blit + NEON-vectorize the CPU R↔B swizzle | 02 | M | med | needs-device (§5) | android |
| **UX cluster (P2)** UX-ONBOARD-02, UX-LOGIN-02, UX-PLAYINPUT-01, UX-HUD-01, UX-PLUGCFG-03, UX-DISCRETE-03, UX-DISCRETE-07, UX-XCUT-02/03/04/07 | Notif-permission rationale, login in-flight state, menu open-haptic, chat-compose affordance, IME-above-field, renderer-agnostic screenshot, hop-fail card, menu/sheet insets, a11y semantics, keepScreenOn | 03 | S–L | low–med | UX-DISCRETE-03 ⇒ ENH-A05 | android (some shared) |
| **PRIVACY-LEGAL-01/03**, **DISTRIBUTION-01**, **PROD-QA-07** | Privacy/disclaimer/THIRD-PARTY-NOTICES + data-flow table + non-Jagex-implying name/icon + release-page risk disclosure | 05 | M | med | only if publishing | android |
| **PROD-QA-08** | Keep prior signed APKs downloadable + document clear-data/CPU recovery (rollback/kill-switch) | 05 | M | med | PROD-RELEASE-ENG-02 | android |

**Sequence within P2.** The three big slimming levers (`SLM-JAR-01`, `SLM-PKG-02`+`SLM-JAR-04`, `SLM-JRE-02`/`-03`) need **no new toolchain artifact** and are android-only — do them right after the P0 re-baseline. `SLM-JRE-02`'s *download* saving is realizable as a `-Duser.language=en` fallback even before a full jlink; the full jlink (`SLM-JRE-01/-04`) is gated on sourcing a jmods-bearing bionic JDK21 (P3 below). **Shared-fix clusters:** `PROD-QA-04` is the *same* DEBUG-gate edit as `SLM-DFLAG-01/02/03` + `PERF-FRAME-PACING-03/04/05` + `PROD-SECURITY-07/12` + `CRASH-TELEMETRY-02` — do it once. **Regrade note:** `PERF-FRAME-PACING-03` is a Phase-2 **P1** (gpuframedump armed in release → 8 PNG encodes in in-world warmup) but is intentionally absorbed here at P2 — it is the *identical* one-line `BuildConfig.DEBUG` gate as the rest of the bundle, so it carries zero marginal cost and the demotion is a packaging convenience, not a deprioritization. `UX-DISCRETE-03` (screenshot fails under GPU/117HD) is the same fix as `ENH-A05` (renderer-agnostic readback + share-sheet). `UX-XCUT-02` is `ENH-B14`; `UX-XCUT-07` is `ENH-B15`. The `PERF-FRAME-PACING-01` short-circuit must fold in the `railsShown`/`mapOpen`/login-window inputs (footgun noted in Phase 2).

### P3 — Enhancements / nice-to-haves

> **Tiering convention:** Phase 4 grades enhancements on an *enhancement-priority* scale, so several findings it marks "P1" (e.g. ENH-A01/A02/A05/A08/A09b, ENH-B02) are intentionally re-tiered **below** the stability/UX P1s here — a Phase-4 "P1" means "highest-value enhancement," not a beta-gating stability defect. Only ENH-B01/ENH-B06 are promoted to the ROADMAP P1 tier (as `UX-PLUGCFG-01`/`UX-DISCRETE-01`); the rest land at P3.

| id | item | phase-source | effort | risk | dependencies | scope |
|---|---|---|---|---|---|---|
| **ENH-B01** | Sectioned config sheet (collapsible `@ConfigSection` + search) — *promoted to P1 as UX-PLUGCFG-01* | 04 | M | low | — | android |
| **ENH-B06** | Hopping scrim + per-row spinner — *promoted to P1 as UX-DISCRETE-01* | 04 | M | low | — | shared |
| **ENH-A05** | Renderer-agnostic GPU screenshot → share-sheet (substrate for A04/A06) | 04 | M | med | — | shared |
| **ENH-A08** | Typed notification channels (carry `TrayIcon.MessageType`) — substrate for A03/A06/A07/B11 | 04 | M | low | — | shared |
| **ENH-A09b** (= UX-ONBOARD-03 / PROD-QA-07 cosmetic) | Branded adaptive icon + app label + notification small-icon | 04 | S | low | — | android |
| **ENH-A02 / ENH-A01** | Time Tracking native reflow / GE item price-check Search tab (bridges done, no native UI) | 04 | M | low | — | android |
| **ENH-B02 / ENH-B09 / ENH-B13 / ENH-B15** | Plugin search over description / coherent "Settings" gear / world-id search + region / keep-screen-awake | 04 | S–M | low | B13 closes UX-DISCRETE-09/-02 | android |
| **ENH-A03/A04/A06/A07, B03/B04/B05/B07/B08/B10/B11/B12/B16/B18, A09a** | Remaining anchored enhancements (notif sub-editor, auto-screenshot, orb glow, font/keybind/reset rows, menu-swapper, overlay quick-toggle, chat drawer, launcher shortcuts) | 04 | S–L | low–med | many ride A05/A08/B01 | mixed |
| **SLM-JRE-01 / SLM-JRE-04** | Full jlink keep-set re-link (~24 modules) — **+~3–11 MB** beyond JRE-02 / ~47 MB installed | 01 | L | high | **jmods-bearing bionic JDK21** (hard blocker) | android |
| **SLM-PROBE-00** (-01..-06) | Remove 22 dormant headless probe files (KEEP WelcomePlayProbe + HudDataBridge) — **−0.259 MB** | 01 | M | low | `:client:compileJava` + `check-contract.sh`; orphans ~22 iOS harnesses | shared |
| **SLM-PROBE-01(kt) / SLM-MARKER-01 / SLM-NATIVE-01 / SLM-LATCH-01** | Gate dev probe boot-gates + marker sprawl + librlawt diagnostics behind `BuildConfig.DEBUG` / `#ifdef` | 01 | M | low–med | — | android |
| **SLM-GITIGNORE-01 / SLM-COMMENT-01 / SLM-JAR-03(hello) / SLM-JAR-05(gpu-smoke)** | Hygiene: stale gitignore line, dead comments, dev-only jars to `src/debug` | 01 | S | low | — | android |
| **PERF P3 cluster** FRAME-PACING-02/08, COMPOSE-UI-03/04/05/06/07, BATTERY-NETWORK-02/03/04/06/07, RENDERER-ROBUSTNESS-02 | JNI-read hoisting, recompose equality guards, reconnect backoff, world-list cache, swap-interval verify | 02 | S–M | low | several need-device (§5) | mixed |
| **PROD versioning/telemetry P2–P3** CRASH-TELEMETRY-01/QA-05, VERSIONING-UPDATE-04/05, QA-06 | Opt-in local crash capture, semver + monotonic versionCode + CHANGELOG, best-effort update check | 05 | S–M | low | — | android |

**Sequence within P3.** Lead with the high-leverage anchored enhancements — `ENH-B01`, `ENH-B06` (already pulled into P1), then `ENH-A05`, `ENH-A08`, `ENH-A09b` — because they unlock the most downstream items and close UX P1/P2 ids. `SLM-JRE-01/-04` is the headline installed-size win but is blocked until a jmods-bearing JDK21 is sourced — chase the JDK first, then it lands the localedata trim as a ~13 MB in-APK / ~33 MB uncompressed result. The probe removals (`SLM-PROBE-00`) edit shared `headless/` code → bump the contract, re-stage jars, knowingly orphan the iOS `run-*.sh` harnesses, and re-verify all 3 renderers on device.

---

## 3. The open-source cutover milestone (GATED)

> Publishing is optional and entirely gated behind a single explicit go-ahead. **Recommendation (Phase 7 Option B):** publish a **public-Android repo (the canonical fork)** + keep a **private-iOS repo** that consumes the published jar and retains full fork sources. The hardest scrub legs all live under `ios/` and never enter the public repo. The ordered, gated sequence:

1. **Redraw the sprites (G1 / `OSS-LICENSE-ATTRIBUTION-01`).** Redraw the 20 Android `tabassets/` PNGs as original chrome/stone/icon/chat art — removal alone breaks `ChatPillView`/`ChromeOverlayView`. (iOS's 23 sprites stay in the private history — fine only while iOS stays private.)
2. **Squash to a born-clean public history (G3 / `OSS-AISCRUB-06`).** `git reset --soft d9023f9361`; in the same staged index remove the *original* sprites + the entire `ios/` tree + the internal `*.md` notes (incl. `android/audit/*`, `OSS-REPO-DOCS-07`) + the token-mint script (`OSS-SECRETS-HISTORY-02`) + the PII evidence logs; neutralize `.gitignore:43-44`; commit as `Jordan Simpson`. This single move moots `OSS-AISCRUB-03` (3 tooling-named messages), `-04` (old gitignore blob), the PII (`OSS-AISCRUB-06`), and the mint script. **Re-encode the local exclude rules (`OSS-AISCRUB-07`):** `.git/info/exclude` is *local and is never pushed*, so the local instruction/handoff/agent-config ignore rules must be re-authored as `.gitignore` rules in the public repo (or those files simply never copied) — otherwise the public tree ships with no ignore coverage for them. Also confirm the local review-tooling dir stays untracked at publish (`OSS-AISCRUB-01`, P0-verify; the exact path is in Phase 6, the internal scrub-planning doc). (`git-filter-repo` is NOT installed; the squash is the only history op that runs today and is mandatory — the 147 interleaved commits mean no clean path-based split exists.)
3. **License + notices (`OSS-LICENSE-ATTRIBUTION-03/04/05/06`, `OSS-REPO-DOCS-06`).** Keep root `LICENSE` (BSD-2) + append the author stanza; author `THIRD-PARTY-NOTICES.md` (sections A–J: RuneLite/117HD BSD-2, Apache/MIT/BSD-3 groups, Caciocavallo + OpenJDK GPLv2+CE, fonts attributed to WolfieMario, the Jagex trademark/sprite statement). Fonts (`OSS-LICENSE-ATTRIBUTION-02`) removed-or-attributed+renamed.
4. **Repo split per Phase 7 (G2/G5/G6).** Push the **whole current fork** to the private-iOS remote (no scrub burden, builds locally). Author the public doc surface (`OSS-REPO-DOCS-01/02/03/04/05`: README honest-unofficial framing, CONTRIBUTING shared-contract rule, SECURITY, replace upstream-pointing `.github` templates). **Document the build-from-clean recipe (`OSS-BUILD-FROM-CLEAN-01` + `PROD-RELEASE-ENG-06`):** the README must enumerate the 4 staging scripts *in order* (JRE → mobile-runtime → gpu → rlhd) so a fresh clone (which lacks the gitignored JRE + 3 client jars) is buildable, with the third-party JRE host mirrored + SHA-pinned so the bootstrap is reproducible and not a single point of failure. Wire the shared-core seam: public-Android CI publishes the staged jars + `baseline.txt` SHA; private-iOS pins a jar version; `merge upstream/master` happens only in public-Android.
5. **Publish (G8).** Run the Phase-6 §5 publish-gate greps (message-history scan, tracked-tree scan, secrets/PII scans — **all must return empty**) against the squashed tree. This grep set is the enforcement for `OSS-AISCRUB-01` (verify the local review-tooling dir stays untracked) and `OSS-AISCRUB-02` (the local-review-tool phrasing scrub — repo-wide across **≥13 tracked docs**; the literal search patterns live in Phase 6; for any doc kept public, reword to "a follow-up review pass"). Then push the squashed branch to a **new** public repo and flip visibility — do **NOT** force-push `audaro/runelite-ios` over dirty history; keep it private as the iOS/working remote.

> The **binary** distribution is a separate, heavier gate (Phase 5 P0s + the actual sprite replacement in shipped assets + the GPLv2 3-year source-offer) — publish the source repo first, treat the APK as a later risk-flagged GitHub Release (`OSS-LICENSE-ATTRIBUTION-07`). The brand-term scrub patterns themselves live in Phase 6 — do not re-inline them here.

---

## 4. The alpha → beta → GA gate

The 15 exit criteria from Phase 7, mapped to the finding ids that must close at each gate.

**Alpha → Beta** *(a second person installs a fresh signed artifact, it boots all renderers, leaks no secret, breakage is observable + recoverable):*

1. **Boots & safe to hold** — `PROD-SECURITY-01`.
2. **Bootable APK** — `PROD-RELEASE-ENG-01` + `PROD-QA-02`.
3. **Signature continuity** — `PROD-RELEASE-ENG-02` / `DISTRIBUTION-02`.
4. **IP-clean to share** — `OSS-LICENSE-ATTRIBUTION-01` + `OSS-SECRETS-HISTORY-02`.
5. **Observable floor** — `PROD-CRASH-TELEMETRY-01` / `PROD-QA-05` (extend crash-cookie with failing-renderer + GPU string; *floor, not blocker*).
6. **A real gate exists and passes** — `PROD-QA-01`, `PROD-QA-03`, `PROD-RELEASE-ENG-07`.
7. **Survives a session** — `PROD-QA-09` (= `PERF-MEMORY-THERMAL-01` + `PERF-BATTERY-NETWORK-01`; ≥10-min 117HD soak holds an fps floor, bounded background drain, device-measured).
8. **App-shell stops reading as a dev build** — `UX-ONBOARD-01` (folds `PERF-COLD-START-01/06`), `UX-ONBOARD-03`, `UX-LOGIN-01`, `UX-PLUGCFG-01`, `UX-DISCRETE-01`, `UX-XCUT-01`.
9. **Identity/disclosure** — `PROD-QA-07` (non-Jagex-implying name + release-page risk disclosure; known-issues list).

*Deferred past beta entry (legitimate known-issues, not blockers):* `UX-XCUT-04` a11y (P2), most `PERF` P2/P3 levers, `PROD-COMPAT-01` (a GA gate), legacy-cred encryption `PROD-SECURITY-03` if legacy stays dev-only.

**Beta → GA** *(trustworthy on hardware you didn't test, with a maintenance story):*

10. **Device matrix (headline GA gate)** — `PROD-COMPAT-01` ({Adreno, Mali new+old, PowerVR, Xclipse} × {9/12/14/15} × {phone, cutout-landscape-right, foldable, tablet}) + `PROD-COMPAT-02/03` (caps-gate every accelerated path + in-session CPU fallback; scope/protect TV).
11. **Gate green, zero open P0/P1** — `PROD-QA-03` in full (every row green incl. reconnect-after-drop + soak).
12. **Legal/disclosure** — `PROD-PRIVACY-LEGAL-01/03` + `DISTRIBUTION-01` + `OSS-REPO-DOCS-01/04`.
13. **Secrets hardening** — `PROD-SECURITY-03` / `PRIVACY-LEGAL-02` + `PROD-SECURITY-10`.
14. **Update story** — `PROD-VERSIONING-UPDATE-04` / `QA-06` / `UPDATE-05` (semver + monotonic versionCode + CHANGELOG, incremental re-unpack `PERF-COLD-START-06`, best-effort update check) + `PROD-QA-08`.
15. **Residual UX backlog** — `UX-XCUT-02/03/04`, `UX-HUD-01`, the input-feel deltas — cleared or consciously accepted-and-documented.

**The ceiling GA cannot launder:** even a perfect GA build is an unapproved client for live OSRS — the **account-ban risk is irreducible**, and Play/F-Droid are categorically barred. "GA" here means a polished, reproducible, disclosed GitHub-Releases sideload kept personal-scope.

---

## 5. CONSOLIDATED "NEEDS THE DEVICE / NEEDS YOU" BATCH

> **This is the operator's section.** Everything here requires the physical Pixel 7a (the QA device; 117HD rows also want a Mali-G710 cross-check) or an explicit decision from you. Copy-paste-actionable. The deploy prerequisite gates every device run; the GATED actions gate the public cutover.

### 5.0 Deploy prerequisite (read this off the device first, every session)

The phone's wireless-debug port **rotates** — you must read it off the device each time before any `adb`/deploy. Pixel 7a over Tailscale `100.71.120.29`:
- On the phone: **Settings → System → Developer options → Wireless debugging** → note the current **port** (and pairing port if first pair).
- Then: `adb connect 100.71.120.29:<PORT>` (pair once with `adb pair 100.71.120.29:<PAIR_PORT>` + the 6-digit code if prompted).
- Confirm: `adb devices` shows the device `online` before running any probe below.

### 5.1 Needs-device measurements (Phase 2 — 12 items)

Run on the Pixel 7a; 117HD rows also on a Mali-G710 cross-check.

| id | measure | exact procedure |
|---|---|---|
| **PERF-COLD-START-01** | seconds off the unpack | `adb shell pm clear dev.audaro.helloshell`; nanoTime-bracket `ensureInstalled` (per-file >1 MB); baseline vs (1 MB buffer + 3-jar exclude) |
| **PERF-COLD-START-02** | CDS class-load delta | Phase-A: `run-as` → `files/jvm/jre/bin/java -XX:ArchiveClassesAtExit=…`. Phase-B: A/B `JNI_CreateJavaVM`→first LOGIN_SCREEN with/without `-XX:SharedArchiveFile=… -Xshare:auto` |
| **PERF-COLD-START-03** | young-GC count/pause at boot | `-Xlog:gc*:file=…/gc.log:time`; count GCs + pause ms `JNI_CreateJavaVM`→LOGGED_IN; A/B `-Xms256m`/`512m`; RSS via `dumpsys meminfo` |
| **PERF-COLD-START-05** | warmup-pad seconds | logcat `[headless-login] Play Now attempt N/5` timestamps: first-frame→first-attempt→LOGGED_IN; A/B `WARMUP_DRAWS=10` |
| **PERF-FRAME-PACING-06** | present-interval jitter | 90/120 Hz device: `dumpsys gfxinfo framestats` intervals + Missed Vsync/duplicates; perfetto `gfx`+`sched`; stddev before/after AChoreographer |
| **PERF-FRAME-PACING-07** | swizzle ms/frame (CPU renderer) | perfetto `jvm-render` between `ANativeWindow_lock`/`unlockAndPost`, or `clock_gettime` around the loop; fps via the **android_fps** harness optimized vs not |
| **PERF-MEMORY-THERMAL-01** | fps decay vs thermal status | **10-min 117HD soak** via `android/dev-tools/gpu/fps-measure.sh` (`-Drunelite.fpsprobe`) polling `dumpsys thermalservice` + `cmd thermalservice headroom 5`; correlate `[fpsprobe]` decay; A/B `swapinterval 2` |
| **PERF-MEMORY-THERMAL-02** | actual GC pause durations | `-Xlog:gc*:file=…/gc.log:time,uptime`, 10-min 117HD play; grep `Pause` + collector; correlate w/ framestats; A/B G1(8 ms)/Serial via the **jvm_bench** harness |
| **PERF-MEMORY-THERMAL-04** | steady PSS breakdown | `dumpsys meminfo dev.audaro.helloshell` per renderer (Java/Native/Code/Graphics/TOTAL PSS); `-XX:NativeMemoryTracking=summary` at exit; watch `lowmemorykiller` under multitask pressure |
| **PERF-RENDERER-ROBUSTNESS-01** | tail-GPU bring-up + corruption | device matrix; log `glGetString(VENDOR/RENDERER/VERSION)` + `glCheckFramebufferStatus` in createGLContext; **android_screenshot** then **image_diff** vs the Mali golden; force ES2-only to confirm silent-black |
| **PERF-BATTERY-NETWORK-01** | screen-locked drain (mA) | `dumpsys batterystats --reset`; in-world; lock 10 min; `dumpsys batterystats \| grep -A20 dev.audaro.helloshell` for CPU/est mAh; `/proc/<pid>/stat` utime delta; 117HD `RouteC_framesPresented` stall vs engine fps log |
| **PERF-BATTERY-NETWORK-07** | free-running vs frame-locked | 117HD `fps-measure.sh` (`[fpsprobe]`+`[frameprobe]`+`thermalservice`); A/B `setprop debug.routec.swapinterval 2/1/0` sustained fps after 2–3 min; **SET_HD_CONFIG** live-flip to hold scene fixed |

### 5.2 Needs-device UX feel / notch checks (Phase 3 — 19 items)

The mechanism is statically proven; the *feel/notch* magnitude must be confirmed on the Pixel 7a. **Landscape-right is the never-audited orientation — start there.**
- **Safe-area / notch (landscape-right):** `UX-XCUT-01` (HUD controls under cutout), `UX-XCUT-02` (menu box under cutout), `UX-XCUT-03` (Compose Back/search/rows under cutout), `UX-XCUT-06` (login screen under cutout). *Procedure:* rotate to landscape-right, screenshot via **android_screenshot**, confirm no primary control under the cutout/gesture inset.
- **Accessibility:** `UX-XCUT-04` (TalkBack walk of tiles/switches/sliders + the custom Canvas Views).
- **Haptics / input feel:** `UX-PLAYINPUT-02` (400 vs 500 ms long-press), `UX-PLAYINPUT-03` (arming-tap predictability), `UX-PLAYINPUT-05` (hold-gate dwell + pickup buzz), `UX-PLAYINPUT-06` (ring size on busy scenes).
- **Keyboard / IME:** `UX-PLUGCFG-03` (focused field vs IME height at the sheet detent), `UX-PANELVIEW-01` (multi-line Notes entry + scroll affordance).
- **Screen-on / lifecycle:** `UX-XCUT-07` (AFK screen-timeout duration), `UX-DISCRETE-08` (resume-after-lapse status), `UX-ACCOUNT-01` (Custom-Tab background-drop timing + reconnect surfacing).
- **Renderer-dependent / transition timing:** `UX-DISCRETE-03` (screenshot under live GPU/117HD), `UX-DISCRETE-07` (hop-failure trigger), `UX-HUD-06` (two-handed reach), `UX-HUD-07` (chat-row legibility at phone scale).

### 5.3 Needs-device production gates (Phase 5)

- **Device-compat matrix (`PROD-COMPAT-01`):** Firebase Test Lab caps-probe + Game-Loop 117HD across {Adreno, Mali new+old, PowerVR, Xclipse} × {Android 9/12/14/15} × {phone, cutout-landscape-right, foldable, tablet, TV}; treat each `:recon` VERDICT log as the per-device gating record. Manually verify cutout/foldable/leanback.
- **GPU-diversity boot tests (`PROD-COMPAT-02/03`):** confirm a non-auto GPU boot doesn't silently black-screen for 240 s; confirm the in-session CPU fallback fires; protect/scope-out the TV `LEANBACK_LAUNCHER` path.
- **Thermal/battery soak (`PROD-QA-09`):** the ≥10-min 117HD soak (5.1 `PERF-MEMORY-THERMAL-01`) holding an fps floor + the bounded backgrounded drain (5.1 `PERF-BATTERY-NETWORK-01`) are explicit `GATE.md` rows — re-run after the thermal governor + `g_paused` gate land.

### 5.4 GATED actions — need your explicit go-ahead (nothing below is run)

1. **OSS history rewrite / squash (G1–G8, Phase 6 §7 / Phase 7 migration).** The `git reset --soft d9023f9361` squash to a born-clean public history (subsumes the sprite/script/PII/internal-note removals + the 3 tooling-named commit-message scrubs). One explicit go-ahead. Run the Phase-6 §5 publish-gate greps first — all must be empty.
2. **Sprite redraw (`OSS-LICENSE-ATTRIBUTION-01`).** Decide to redraw the 20 Android `tabassets/` PNGs as original art (FATAL publish blocker; removal-without-replacement breaks the chrome).
3. **Repo-visibility change + repo split (G8 / Phase 7).** Push the squashed tree to a **new** public repo and flip to public; push the whole fork to the private-iOS remote. Do not force-push `audaro/runelite-ios` over dirty history.

### 5.5 User-only SECURITY action (do now, regardless of publishing)

**Rotate/invalidate the Jagex session if any locally-built APK was ever shared.** The debug APK (and a fresh `assembleRelease` until `PROD-SECURITY-01` lands) bundles `assets/tokens.json` — a live, replayable session. If any such APK ever left your device:
- Treat the bundled `session_id` + `otl_token` + the throwaway account as **burned**: log that account out everywhere / change its credentials, and re-mint fresh tokens via `ios/dev-tools/fetch-shield-tokens.sh`.
- Then land `PROD-SECURITY-01` (packaging exclude) so no future artifact carries it. (Use only a throwaway Jagex account, never a main.)
