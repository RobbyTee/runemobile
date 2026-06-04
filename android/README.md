# runelite-android

Android port of the on-device-JVM RuneLite app (sibling of `ios/`). The app process is ART's, but the
game runs on a **second, in-process HotSpot VM** that the native layer brings up with `JNI_CreateJavaVM`
(dlopen of an embedded OpenJDK 21). Unlike iOS, **Android grants JIT for free** — no debugger attach, no
CoreTrust, no per-launch ceremony — so the native bootstrap drops every iOS-JIT workaround.

The Java side (the shaded `client-*-shaded.jar` + `probes.jar` + the `net.runelite.client.headless.*`
bridge) is **identical to iOS** and is reused verbatim. Only the host (Kotlin/ART UI + C++ bootstrap +
SurfaceView blit) is Android-specific.

## Layout
```
android/
  app/
    jvm-src/Hello.java              # Milestone-A smoke (compiled -> assets/jvm/hello.jar)
    src/main/
      java/dev/audaro/helloshell/   # MainActivity, JvmLauncher (JNI facade), AssetInstaller
      cpp/                          # CMake + jvm_bootstrap.cpp (JNI_CreateJavaVM)
      assets/jvm/                   # staged jars + the JRE's non-native tree (gitignored, except hello.jar)
      jniLibs/arm64-v8a/            # the JRE's .so libs (gitignored) — see "Why jniLibs" below
```

## The JRE goes in assets ONLY — do NOT mirror the .so into jniLibs
The **entire** embedded JRE (the `lib/modules` image, `conf/`, `fontconfig`, **and every `lib/*.so` +
`lib/server/libjvm.so`**) ships in `assets/jvm/jre/` and is unpacked to `filesDir/jvm/jre` on first launch
(`AssetInstaller`). `JAVA_HOME` = `filesDir/jvm/jre`; `java.library.path` = `filesDir/jvm/jre/lib`. The native
bootstrap (`libhelloshell.so`, itself loaded from the app's legit native-lib dir) `dlopen`s
`filesDir/jvm/jre/lib/server/libjvm.so` by absolute path and preloads the rest of `lib/*.so` the same way —
**this works** (verified 2026-06-04: real RuneLite boots to `LOGIN_SCREEN` on the arm64 emulator). The old
"API 29 forbids dlopen from filesDir, so split the .so into jniLibs" rule does **not** apply to a
native-initiated absolute-path dlopen of the app's own data here.

> **DO NOT also place the JRE's `.so` in `app/src/main/jniLibs/arm64-v8a/`.** Mirroring them there makes the
> linker resolve `libawt.so` **by soname** from `/data/app/.../lib/arm64/`, so `libawt`'s `JNI_OnLoad` gets a
> non-absolute `dli_fname`; its `realpath()` then fails, the toolkit-path `malloc` is handed a garbage length,
> and the VM aborts **`FATAL ERROR in native method: Could not allocate library name`** during
> `java.awt.Toolkit.<clinit>` — before CACIO can take over. Symptom looked JRE-specific but reproduced
> identically across two independent bionic jre21 builds; the cure is filesDir-only. `jniLibs/` should hold
> only the CMake-built `libhelloshell.so` (which Gradle places there automatically — not staged by hand).

## Prerequisites already set up on this Mac
- SDK at `~/Library/Android/sdk` (platform-35, build-tools 35, platform-tools, emulator, arm64 system image).
- NDK `27.0.12077973`. AVD `rl_arm64` (Android 35, google_apis, arm64-v8a).
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for Gradle/AGP.

Create `android/local.properties` with `sdk.dir=/Users/<you>/Library/Android/sdk` (gitignored).

## The blocking input: an Android arm64 (bionic) OpenJDK 21
The iOS JRE is Mach-O — unusable here. Need a **bionic arm64 OpenJDK 21** (interpreter `/system/bin/linker64`,
NEEDS the unversioned `libc.so`/`libm.so`/`libdl.so` — *not* glibc's `libc.so.6`). Verified-working source:
`AngelAuraMC/angelauramc-openjdk-build` release `download_jre21` asset **`jre21-android-arm64.tar.xz`** (same
Amethyst/PojavLauncher lineage as our iOS JRE). Stage it:
1. Unzip the whole tree → `app/src/main/assets/jvm/jre/` (assets ONLY — see the jniLibs warning above).
2. Build the missing `libawt_xawt.so`: `ANDROID_SDK_ROOT=~/Library/Android/sdk dev-tools/fake-xawt/build-libawt_xawt.sh`
   (the JRE ships `libawt_headless.so` but no headful xawt; the fake provides the `initIDs` stubs).
3. `tools/stage-mobile-runtime.sh` stages the shared `client`/`probes` jars.

This is the one artifact that gates even the Milestone-A smoke. (The QuestCraftPlusPlus fork's `JRE-21.zip`
also boots HotSpot but is otherwise interchangeable — both hit the jniLibs/`libawt` trap identically.)

## Milestones
- **A — JVM Hello on the emulator (DONE):** `JvmLauncher.startJvm(mainClass="Hello")` boots HotSpot and runs
  `Hello.main`, printing `VERDICT = JIT ACTIVE` (logcat). De-risks `JNI_CreateJavaVM` + JIT on ART.
- **B — headless RuneLite + Surface render + touch (GREEN, arm64 emulator):** swap the classpath to the
  shaded client jar + CACIO, run `DeviceHeadlessBoot.main`, and blit `JavaBridge.getFramePixels()`
  (TYPE_INT_RGB) to a `SurfaceView` via `ANativeWindow`; feed touches back through the bridge's
  `onTouch/onClick` facade. Verified: real RuneLite boots headless to `LOGIN_SCREEN`, renders the OSRS login
  to the SurfaceView with correct colors, and an on-screen tap drives the gamepack (tap on "Existing User"
  advances `loginIndex 0 -> 2`, the username/password form).
  - Android-specific gotchas this needed (all in `MainActivity`/manifest/`jvm_bootstrap.cpp`):
    `android:allowNativeHeapPointerTagging="false"` (HotSpot's VM thread trips bionic's tag-truncation
    SIGABRT); `-Djava.io.tmpdir=<filesDir>/tmp` (default `/tmp` is absent in the sandbox, poisoning
    `FontManager`); a real `libawt_xawt.so` (no-op headful `initIDs` stubs + `DT_NEEDED libawt_headless.so`,
    see `dev-tools/fake-xawt/`) since this xawt-style image ships neither the lib nor the headful initIDs;
    activate CACIO CTC (`UnsafePokeActivation`) before any AWT/bridge class loads.
