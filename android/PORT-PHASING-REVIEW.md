# Review brief — Android port phasing plan

> Self-contained brief for an independent reviewer. The companion artifact is
> [`PORT-PHASING.md`](PORT-PHASING.md) — a phasing roadmap to bring this repo's young Android RuneLite port
> (`android/`) to parity with the mature iOS port (`ios/`). **Your job is adversarial:** find what's wrong,
> under-scoped, or over-optimistic, and verify the plan's claims against the actual code rather than trusting
> the document. The plan was synthesized from a codebase survey, so a high-value part of the review is catching
> any claim or `file:line` citation that has drifted from reality.

## What to review
[`android/PORT-PHASING.md`](PORT-PHASING.md). Everything below is ground truth to check it against.

## Ground truth — read these, don't trust the plan's summaries
- **Shared bridge seam** (the plan's central "the Java is already done" claim rides on this):
  `runelite-client/src/main/java/net/runelite/client/headless/` — especially `JavaBridge.java`,
  `MobileUiBridge.java`, `MenuPeekBridge.java`, `MobileChromeBridge.java`, `HudDataBridge.java`,
  `HudVeneerBridge.java`, `QuestHelperBridge.java`, `WorldSwitcherBridge.java` — plus the render/input splice in
  `runelite-client/src/main/java/net/runelite/client/callback/Hooks.java`.
- **Android baseline:** `android/README.md`, `android/app/src/main/cpp/jvm_bootstrap.cpp`,
  `android/app/src/main/java/dev/audaro/helloshell/{MainActivity,JvmLauncher,AssetInstaller}.kt`,
  `android/dev-tools/fake-xawt/`.
- **iOS UX spec + host↔game contract:** `ios/app/HelloShell/JVMLauncher.h` (the bridge-method surface),
  `ios/app/HelloShell/ViewController.m` (the control scheme / chrome / menu-peek the plan ports).
- **iOS phasing history this mirrors:** `ios/REALJVM-DEV-PLAN.md`, `ios/FINDINGS.md`.
- **GPU / 117HD (Track G):** `ios/AUDIT-BRIEF-w3-gpu-renderer.md`,
  `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java`,
  `runelite-client/build.gradle.kts` (LWJGL deps + `natives-<platform>` classifiers, ~lines 80–94).
- **Staging / shared-runtime:** `tools/stage-android-jre.sh`, `tools/stage-mobile-runtime.sh`, `PORTS.md`.

## Claims to pressure-test (highest value first)
1. **"Android already equals iOS at the W1/W2 platform/runtime layer; the gap is purely the W3 native-UI layer;
   ~zero new Java is needed for the A0–A8 mainline."** Verify *every* bridge the phases depend on actually ships
   in the shaded jar Android embeds, and that the `-D` gate flags the phases turn on
   (`runelite.mobileui` / `mobilechrome` / `menupeek` / `huddata` / `hudveneer` / `hub` / `headless.autologin.*`)
   exist and behave as described. Is there any phase that will *secretly* require a Java-side change?
2. **Phase ordering & dependencies (A0–A8).** Any wrong or missing dependency? Is A3 (on-device login) genuinely
   independent of A1/A2? Is A7 (native HUD veneers + edge-to-edge) realistically scoped given it is *unfinished
   even on iOS* (the iOS edge-to-edge work was built, device-confirmed, then reverted over notch occlusion)?
3. **Track G (GPU rendering / 117HD) — attack the optimism hard.** The plan claims Android is a materially better
   GPU target than iOS because the iOS blockers B1/B2/B3 + "Claim D" largely dissolve (bionic LWJGL from the
   PojavLauncher/Amethyst lineage, native GLES 3.1+ compute + Vulkan, EGL-on-SurfaceView, free JIT). Which of
   these is weakest? Does the PojavLauncher GL-translation stack actually apply to a **headless** engine with no
   GLFW/GLFM window? Can `librlawt`/`AWTContext` truly be replaced by an offscreen/Surface context shim *without*
   modifying the unmodified plugin Java? What kills AG2 (base `gpu`) or AG3 (117HD) first?
4. **Verification discipline.** Are the per-phase md5-gated GREEN criteria actually achievable on Android via
   `adb logcat` / `adb exec-out screencap`, the way the iOS gates use `devicectl`-pulled logs?
5. **Citations.** Spot-check the plan's `file:line` references against the tree; flag any that are wrong or
   invented.

## Questions to answer (these are the decisions the plan needs back)
Answer all ten **Open questions for the reviewer** in §5 of `PORT-PHASING.md` with a concrete recommendation —
in particular the three that gate real work:
- **§4.2 / Q1** — Android UI framework: Jetpack Compose over an `AndroidView(SurfaceView)`, or classic
  Views/Fragments closer to the UIKit structure? (Affects every UI phase; settle before A2.)
- **Q8** — Is a *working* 117HD a v1 requirement (so Track G gates release), or a post-core-parity fast-follow
  (ship the playable CPU shell A0–A8 first)?
- **Q9** — AG2 render path: `glReadPixels`→`int[]` (keep the current blit) vs render-GL-directly onto the
  SurfaceView (faster, but restructures the core render seam and intersects A7)?

## Output
For each pressure-tested claim: **SUPPORTED / REFUTED / UNCERTAIN**, with the single cheapest experiment that
would settle it. Then your answers to the §5 questions, and any phase, risk, dependency, or reordering the plan
**missed**. Prefer "here is the load-bearing thing the plan got wrong" over a broad endorsement.
