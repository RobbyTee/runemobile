# Autonomous implementation backlog

> Items I can implement WITHOUT device access or operator decisions — Android-only, build-verifiable (compile/contract), low-to-medium risk, correct-by-construction. Each links its ROADMAP finding id. Verification here means **compiles + contract intact + builds**; on-device feel/perf confirmation is deferred to the ROADMAP §5 device batch. Commit + push per item/wave under `Jordan Simpson`; scrub; no contract break without a baseline bump.
>
> **Excluded from autonomous work** (need the operator/device — see ROADMAP §5): the OSS history squash / sprite redraw / repo-visibility (GATED), the Jagex-session rotation (USER), the release keystore key material (USER), the app product-name/trademark decision (USER), the device-compat matrix + thermal/battery/feel measurements (DEVICE), and anything whose *correctness* (not just feel) can't be established without a device boot (e.g. wiring staging into Gradle is implemented but must be device-boot-verified before it's trusted).

## Verify loop
- Kotlin shell: `cd android && ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileDebugKotlin --offline`
- Full APK: `:app:assembleDebug` · Native: `:app:externalNativeBuildDebug` · Shared Java: root `./gradlew :client:compileJava` + `tools/shared-contract/check-contract.sh`

## Wave 1 — slimming + dead-flag gating + leak fixes (safest, Android-only)
- [ ] **W1.1** `SLM-PKG-02/03/05` — narrow `noCompress` to `["so","dat","bin"]` so the JRE `.so` + jars DEFLATE in-APK (runtime-neutral; copied to filesDir). build.gradle. ~20 MB download.
- [ ] **W1.2** `SLM-DFLAG-01/02/03` — gate `headless.capture.*` + `gpuframedump` + `fpsprobe` behind `BuildConfig.DEBUG` in MainActivity (removes per-frame IO in release).
- [ ] **W1.3** `SLM-PROBE-01(kt)` + `SLM-MARKER-01` — gate the dev probe boot-gates (netprobe/gpuSmoke/rlhdprobe/worldmapprobe/census self-tests) behind `BuildConfig.DEBUG`.
- [ ] **W1.4** `SLM-JAR-05/03` — stop staging `gpu-smoke.jar` + `hello.jar` into release assets (dev-only).
- [ ] **W1.5** `PERF-COLD-START-01a` — AssetInstaller copy buffer 8 KB → 1 MB.
- [ ] **W1.6** `PERF-COMPOSE-UI-04` — `RuneLiteAccountRow` 2 s poll → `DisposableEffect`-bracketed (leak fix).
- [ ] **W1.7** `PERF-COMPOSE-UI-07` — `LazyColumn` keys built without per-row string concat where cheap.

## Wave 2 — UX fundamentals (Android-only, additive)
- [ ] **W2.1** `UX-XCUT-05` — OSRS Compose theme (new `Theme.kt`: `darkColorScheme` on the OSRS palette + Typography registering `runescape.ttf`); wrap `PluginPanel` in `MaterialTheme`.
- [ ] **W2.2** `UX-ONBOARD-01` — cold-start splash/progress: a branded loading View before the boot thread, dismissed on first engine frame; progress from `ensureInstalled` bytes.
- [ ] **W2.3** `UX-XCUT-01/02/03` — window-inset handling: root `setOnApplyWindowInsetsListener` for the HUD controls (devBar/hotkeys/handle), `safeInsets()` clamp for `MenuOverlayView`, `windowInsetsPadding(safeDrawing)` on the Compose sheet.
- [ ] **W2.4** `ENH-B06` / `UX-DISCRETE-01` — "Hopping…" scrim on the World Switcher (reuse `ReconnectingOverlayView`) so a relog-hop reads as a hop, not a kick.
- [ ] **W2.5** `UX-XCUT-04` — a11y semantics (additive `contentDescription`/`semantics`/`stateDescription`) on Compose controls + key Views; start with toggles/renderer/⌨/⇧/grid.

## Wave 3 — plugin/config UX + small enhancements (Android-only)
- [ ] **W3.1** `ENH-B02` — plugin search over description, not just display name.
- [ ] **W3.2** `ENH-B03` — keybind config rows show the current binding + a Clear control.
- [ ] **W3.3** `ENH-B04` — FontType config rows: REGULAR/BOLD/SMALL preset picker.
- [ ] **W3.4** `ENH-B05` — per-plugin Reset-to-defaults (with confirm).
- [ ] **W3.5** `UX-PLUGCFG-01` / `ENH-B01` — consume the `@ConfigSection` grouping in the config editor (if the bridge already emits it).
- [ ] **W3.6** `ENH-A05b` — screenshot → Android share-sheet (hold the `Uri` from `writePngToGallery`, fire `ACTION_SEND`). (The GPU-readback half is deferred — native + device.)
- [ ] **W3.7** `PROD-SECURITY-04` — move the `TEST_LOOP` intent-filter to `src/debug/AndroidManifest.xml`.

## Wave 4 — shared-code hygiene (careful: shared/iOS; contract must stay green)
- [ ] **W4.1** `SLM-PROBE-05` — remove the 14-file CACIO de-risk probe cluster (file + `HeadlessSeams.isEnabled()` block + javadoc refs); `:client:compileJava` + contract check; iOS arms none of these.
- [ ] **W4.2** `SLM-PROBE-01` — remove the GPU-diag cluster (GpuSceneDiag + GpuHandshakeProbe + TracingDrawCallbacks) as one unit (P1 footgun).
- [ ] **W4.3** `SLM-PROBE-03/04` — remove MenuPeek (probe) + WorldMap cluster (downgrade live-bridge javadoc links; delete the Kotlin marker arm). KEEP `WelcomePlayProbe` (iOS-live) + `MobileUiSmokeRunner` (iOS comment-glue).

## Deferred-but-implementable (heavier; implement, mark device-verify-required)
- [ ] **D.1** `PERF-FRAME-PACING-01` — short-circuit `updateChrome()` on unchanged snapshot (hot path; careful).
- [ ] **D.2** `SLM-JAR-01` — drop `shaded.jar` from Android asset staging (gpu.jar is the boot closure; device-verify boot).
- [ ] **D.3** `PROD-SECURITY-01` — release-exclude bundled `tokens.json` (relocate to `src/debug/assets`; device-verify autologin/login gate).
- [ ] **D.4** `PERF-COLD-START-03` — add `-Xms` to the JVM args.

## Status log
- **Wave 1 DONE + compile-verified + APK-validated:** W1.1 (noCompress narrowed → jar+so DEFLATE), W1.2 (gpuframedump/fpsprobe DEBUG-gated), W1.3 (headless.capture DEBUG-gated), W1.5 (AssetInstaller 1 MB buffer). **`assembleDebug` validated: APK 176 MB → 136 MB (−40 MB) from W1.1 alone.** Deferred: W1.4 (gpu-smoke/hello — ~30 KB P3), W1.6 (RuneLiteAccountRow — LaunchedEffect already auto-scoped), W1.7 (LazyColumn keys — minor).
- **Wave 2 DONE + compile-verified:** W2.1 (OSRS Compose theme + RuneScape font, graceful fallback), W2.5 (a11y semantics on panel/config controls), W2.2 (cold-start splash w/ Unpacking-N% + first-frame dismiss + 60 s failsafe), W2.4 (Hopping scrim on world hop, 12 s failsafe), W2.3 (window insets: HUD controls + long-press menu + Compose sheet, additive/no double-apply).
- **Wave 3 DONE + compile-verified:** W3.1 (sectioned config — collapsible @ConfigSection groups, bridge-order, orphan-safe + warn-log; Android-only — bridge already emits section meta), W3.2 (keybind Clear + humanized binding); W3.3 font-picker + W3.4 per-plugin reset were ALREADY present. W3.5 = W3.1. W3.6 (screenshot share-sheet, ENH-A05b) DONE. W3.7 (TEST_LOOP → debug-only manifest, verified absent from packaged release manifest) DONE.
- **Commits:** 68dfac051d, 3bb7fb1636, 232da3ec05, 95fdb8eb1a, 66daa8700d, 6c45415cbe, 13b58815c1 (all pushed). Each additive/DEBUG-only/runtime-neutral + compile-gated; **on-device boot/feel verification batched in ROADMAP §5** (esp. splash dismiss timing, hopping-scrim duration, landscape-right inset magnitude, TalkBack walk, theme font render).
- **Wave 4 DONE (shared, gated):** removed the dead GPU-diag (incl. the P1 `GpuHandshakeProbe` footgun) + CACIO de-risk + MenuPeek/ShiftFeedSpike/WorldMap probe clusters — **20 files / ~8.2k LOC** + the Android `worldmapprobe.mode` marker-arm. Every deletion passed 3 hard gates: `git grep ios/` clean, `:client:compileJava` EXIT 0, contract **UNCHANGED (261)**. `ChatRelocateProbe` (iOS comment ref) + `WelcomePlayProbe` (iOS-live) correctly KEPT. Commits `3d68ffa8d2`, `6a76aab170`. NB: jars are gitignored/pre-staged — the `.class`-byte saving lands on the next `:client:shadowJar`→stage; the source is now clean.
- **STOP — safe-autonomous boundary reached.** Everything still open requires the device or carries boot/iOS risk I can't verify here, so it is intentionally NOT done autonomously: **D.2 shaded.jar drop (~29 MB)** — the one change where a wrong audit call = un-bootable APK; needs a device boot of all 3 renderers. **D.3 tokens.json release-exclude (P0)** — must device-verify autologin/login-gate after relocating to `src/debug/assets`. **D.1 updateChrome short-circuit** — per-frame hot path; device-verify no chrome stall. **D.4 -Xms** — memory-commit risk on low-end devices; needs the device matrix. **PROD-RELEASE-ENG-01 (wire staging into Gradle)** — implementable but must device-boot-verify the resulting clean-build APK. The remaining 2 probes + the orphaned `ios/dev-tools/.../run-*.sh` harnesses need iOS coordination. All are tracked in ROADMAP §2 (P1) + §5 (device batch).
