# Mobile ports — repo map

This repo (`audaro/runemobile`) is the canonical public fork of [`runelite/runelite`](https://github.com/runelite/runelite),
adding **two mobile ports** that run real RuneLite on an on-device JVM. Everything below `ios/` and `android/` is ours;
the rest of the tree (`runelite-client/`, `runelite-api/`, …) is upstream RuneLite, kept as-is so we can
`git merge upstream/master`.

The ports share one thing deliberately and split everything else:

```
runelite-ios/
├── runelite-client/  …   upstream RuneLite + our headless bridge (net.runelite.client.headless.*)
├── tools/
│   └── stage-mobile-runtime.sh   ← stages probes.jar and writes Android's runtime.tag (see below)
├── ios/              native iOS host (Objective-C/UIKit, JNI bootstrap, StikDebug per-launch JIT)
└── android/          native Android host (Kotlin/ART, JNI_CreateJavaVM, free JIT)
```

## The shared layer is the Java runtime, not the host

Both ports start from the **same two build outputs**:

| jar | what it is | built by |
| --- | --- | --- |
| `client-<version>-shaded.jar` | real RuneLite + all deps + our `net.runelite.client.headless.*` bridge | `./gradlew :client:shadowJar` |
| `probes.jar` | headless boot (`DeviceHeadlessBoot`), CACIO (folded in), W1 activation/bench probes | [`ios/app/build-probes.sh`](ios/app/build-probes.sh) |

Each port then rebuilds the shaded jar for its own GL stack, so the bundles differ and **only `probes.jar` is the
same bytes on both**. Jar SHAs never match across ports:

| port | bundles | staged by |
| --- | --- | --- |
| iOS | `client-shaded.jar` (LWJGL/rlawt replaced by the native GL facade), `client-rlhd.jar` (117HD), `probes.jar`, `routec-l1.jar` (GPU-bridge kill test) | `ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh`, `ios/dev-tools/gpu-zone/stage-ios-zone-jar.sh`, `build-probes.sh`, `ios/dev-tools/gpu-routec/l1/build-l1-probe.sh` |
| Android | `client-<version>-gpu.jar`, `client-<version>-rlhd.jar` (117HD), `probes.jar`, `runtime.tag` | `android/dev-tools/gpu/stage-android-gpu-jar.sh`, `stage-rlhd-zone-jar.sh`, `build-probes.sh`, `tools/stage-mobile-runtime.sh` |

Android does not bundle the shaded jar: its boot classpath is `[gpu : rlhd : probes]`.

The Java side — gamepack, plugins, overlays, the headless render loop, the touch/click facade — is
**the same** on iOS and Android and reused verbatim. What differs is the GL layer (each port's facade and its
117HD overlay) and the host: iOS is Mach-O + per-launch JIT attach; Android is bionic ELF + free JIT. See
[`android/README.md`](android/README.md) for the Android-specific bootstrap and [`ios/README.md`](ios/README.md)
for the iOS build pipeline.

## Staging, per port

Do **not** hand-copy jars (that's how they drift). Stage in this order, from the repository root; each step reads
the output of the one before.

```sh
# Android (or ./gradlew :app:stageBootAssets for all of it; see android/README.md)
./gradlew :client:shadowJar
( cd ios/app && ./build-probes.sh )
android/dev-tools/gpu/stage-android-gpu-jar.sh            # -> client-<ver>-gpu.jar
android/dev-tools/gpu/stage-rlhd-zone-jar.sh              # -> client-<ver>-rlhd.jar
tools/stage-mobile-runtime.sh --no-build --android-only   # stage probes.jar, write runtime.tag

# iOS (the same order the release build uses)
./gradlew :client:shadowJar
( cd ios/dev-tools/gpu-routec && ./stage-ios-gpu-jar.sh )   # -> client-shaded.jar, the facade variant
( cd ios/dev-tools/gpu-zone && ./stage-ios-zone-jar.sh )    # -> client-rlhd.jar
( cd ios/app && ./build-probes.sh )                         # -> probes.jar
( cd ios/dev-tools/gpu-routec/l1 && ./build-l1-probe.sh )   # -> routec-l1.jar

tools/stage-mobile-runtime.sh --check   # no build: probes.jar current on both ports, iOS client-shaded.jar is the GPU variant
```

Don't stage iOS with `tools/stage-mobile-runtime.sh`: it copies the **pristine** shaded jar to `client-shaded.jar`,
and [`ios/app/scripts/check-staged-jar.sh`](ios/app/scripts/check-staged-jar.sh) then fails the Xcode build until
`stage-ios-gpu-jar.sh` replaces it. That gate exists because the pristine jar builds, boots and logs in with the
GPU renderer silently dead.

`tools/stage-mobile-runtime.sh` also writes a SHA/version manifest (`tools/last-stage.manifest`, gitignored) with a
short **content-tag** over the shaded, probes, gpu and rlhd jars.

> **Stale assets can't happen:** Android unpacks its staged jars to `filesDir` once. The script writes the
> content-tag into the bundle as `jvm/runtime.tag`, and
> [`AssetInstaller.kt`](android/app/src/main/java/dev/audaro/runemobile/AssetInstaller.kt) re-unpacks whenever
> that tag changes — so a jar update invalidates old installs with no hand-typed version bump. The `BASE`
> constant there only needs bumping when the embedded JRE / native libs change (rare). iOS has no such cache —
> Xcode re-bundles `jvm-resources/` every build.

Both staged trees are gitignored (large, generated): `ios/app/jvm-resources/*.jar` and
`android/app/src/main/assets/jvm/{client-*.jar,probes.jar}`.

## Boundary convention (in lieu of a shared module)

There is intentionally **no `shared/` Gradle module and no umbrella build** — that would drag AGP/SDK
assumptions into normal RuneLite work and fight upstream mergeability for little gain. The shared edge is held
by convention, not compile-time walls. Code that crosses the JVM↔host boundary
(e.g. [`Hooks.java`](runelite-client/src/main/java/net/runelite/client/callback/Hooks.java)) falls into three
kinds — keep them distinguishable:

- **production shared** — facades both ports call (render, touch, click). Lives in `net.runelite.client.headless.*`.
- **desktop probe** — mutating spikes gated behind system properties, used for desktop A/B bring-up
  (`UnsafePokeActivation`, `DeviceHeadlessBoot`). Build front door is `ios/app/build-probes.sh`; folded into
  `probes.jar`. New probe classes should land under a `headless.probe` package, still inside `runelite-client`.
- **host-specific native** — never shared. iOS Objective-C + Android Kotlin/C++. The two `fake-xawt` builds
  stay **separate** on purpose — the platform link rules differ materially (Mach-O `libawt_xawt.dylib` vs
  bionic `libawt_xawt.so` with `DT_NEEDED libawt_headless.so`).

## Why no deeper restructure (yet)

Rationale and the rejected alternatives (shared module, umbrella `includeBuild`, hoisting the desktop harness,
unifying `fake-xawt`) were weighed in a June 2026 repo-structure review and rejected as not worth the churn.
The cheap, drift-killing move was this doc plus the staging script; revisit a module only if shared Java grows
its own independent build needs.
