# rlhd java-overlay

Tracked Java source overrides layered over the **gitignored, pristine** `rlhd-src/` tree at compile time by
`stage-rlhd-zone-jar.sh` (mirrors how `shader-overlay/` overrides GLSL resources). Each file here replaces the
same-path file under `rlhd-src/src/main/java/` for the Zone-jar build, so our fixes are committed and
reproducible while `rlhd-src` stays untouched and re-fetchable.

Contents:
- `rs117/hd/HdPlugin.java` — four `awtContext == null` guards (`updateTiledLightingFbo`, `updateSceneFbo`,
  `initializeShadowMapFbo`, `processPendingConfigChanges`). A config storm from a profile switch can tear the GL
  context down before a deferred config change runs; without the guards the GL re-init NPEs into the catch-all
  `stopPlugin()` while holding `loadingLock`, which can deadlock the map loader (a black scene until restart).
  iOS carries the same file (`ios/dev-tools/gpu-zone/java-overrides/`).

Removed with the move to RLHD v1.6.0.1, which fixed the same bugs upstream: `StaticAlphaSortingJob` (the
camera-angle table index; upstream now reads `getFixedYaw()`/`getFixedPitch()` into `SINE14`/`COSINE14`), `AsyncCachedModel`
(transparency is now cached), `SceneManager` (`discardPendingScene()` replaces the "Double zone load!" throw) and
`EnvironmentManager` (`shutDown` now leaves an empty array, never null).
