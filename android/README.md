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

## Why the JRE is split (jniLibs vs assets)
Since Android 10 (API 29) an app **cannot `dlopen` a `.so` from writable storage** (filesDir). So the
embedded JRE is split:
- **Native libs** (`libjvm.so`, `libjava.so`, `libnet.so`, `libawt*.so`, …) go in
  `app/src/main/jniLibs/arm64-v8a/`. Gradle installs them into the APK's native-lib dir
  (`/data/app/.../lib/arm64/`), which **is** an allowed dlopen path. `java.library.path` points here.
- **Everything else** (the `lib/modules` image, `lib/*.jar`, `conf/`, `fontconfig`, `lib/server/` layout)
  goes in `assets/jvm/jre/` and is unpacked to `filesDir/jvm/jre` on first launch (`AssetInstaller`).
  `JAVA_HOME` points there. `lib/server/libjvm.so` must ALSO exist under JAVA_HOME for the bootstrap's
  dlopen path — install it in both places (or symlink at unpack time).

## Prerequisites already set up on this Mac
- SDK at `~/Library/Android/sdk` (platform-35, build-tools 35, platform-tools, emulator, arm64 system image).
- NDK `27.0.12077973`. AVD `rl_arm64` (Android 35, google_apis, arm64-v8a).
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for Gradle/AGP.

Create `android/local.properties` with `sdk.dir=/Users/<you>/Library/Android/sdk` (gitignored).

## The blocking input: an Android arm64 (bionic) OpenJDK 21
The iOS JRE is Mach-O — unusable here. Source a **bionic arm64 OpenJDK 21** runtime (PojavLauncher ships
exactly this as its `jre21` runtime) and split it as above. This is the one artifact that gates even the
Milestone-A smoke.

## Milestones
- **A — JVM Hello on the emulator:** `JvmLauncher.startJvm(mainClass="Hello")` boots HotSpot and runs
  `Hello.main`, printing `VERDICT = JIT ACTIVE` (logcat). De-risks `JNI_CreateJavaVM` + JIT on ART.
- **B — headless RuneLite + Surface render:** swap the classpath to the shaded client jar + CACIO, run
  `DeviceHeadlessBoot.main`, and blit `JavaBridge.getFramePixels()` (TYPE_INT_RGB) to a `SurfaceView`
  via `ANativeWindow`; feed touches back through the bridge's `onTouch/onClick` facade.
