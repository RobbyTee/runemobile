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
- **Wave 1 (partial) DONE + compile-verified:** W1.1 (noCompress narrowed → jar+so DEFLATE), W1.2 (gpuframedump/fpsprobe DEBUG-gated), W1.3 (headless.capture DEBUG-gated), W1.5 (AssetInstaller 1 MB buffer). Deferred: W1.4 (gpu-smoke/hello jars — ~30 KB P3, file-move risk), W1.6 (RuneLiteAccountRow — LaunchedEffect already auto-scoped; real fix needs host-visibility + device), W1.7 (LazyColumn keys — minor). All four shipped changes are runtime-neutral or DEBUG-only; **device boot-verify still recommended** (in ROADMAP §5).
