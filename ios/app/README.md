# ios/app — the native iOS app

RuneMobile's Objective-C/UIKit host. It loads the embedded OpenJDK 21 (`jre/`) in its own process with
`JNI_CreateJavaVM`, boots headless RuneLite through `DeviceHeadlessBoot`, and draws the engine's frames on a UIKit
view with native touch, settings and plugin screens. JIT on a phone needs a StikDebug attach on every launch; the
iOS Simulator needs none.

The full story (boot sequence, JIT, build routes, native UI, backgrounding, logs) is in
[`docs/dev/ios.md`](../../docs/dev/ios.md). This page is the map of the directory.

## Layout
| Path | Role |
|---|---|
| `project.yml` | xcodegen project (source of truth; the `.xcodeproj` is generated, gitignored) |
| `RuneMobile/ViewController.m` | the game screen: Tap to Play cover, JIT wait, frame display, touch, HUD, settings, keepalive |
| `RuneMobile/JVMLauncher.{h,m}` | loads `libjvm`, builds the JVM options, creates the VM, runs `DeviceHeadlessBoot`, wraps the bridge calls |
| `RuneMobile/Plugin*ViewController.m` | the plugin rail and side panels, plugin config screens, Plugin Hub |
| `RuneMobile/JagexLoginViewController.m` | on-device Jagex login (`WKWebView`), saved to `tokens.json` |
| `RuneMobile/RuneMobile.entitlements` | `increased-memory-limit` only; `get-task-allow` comes from the development profile |
| `scripts/check-staged-jar.sh` | pre-build phase: refuse a bundle whose `client-shaded.jar` is not the staged GL-facade variant |
| `scripts/resign-jre.sh` | post-build phase: sign every Mach-O in the embedded `jre/` (retagged for the Simulator first) |
| `build-probes.sh` | builds `jvm-resources/probes.jar`: `DeviceHeadlessBoot`, Caciocavallo and the AWT activation probes |
| `jvm-resources/` | the four bundled jars (`client-shaded`, `client-rlhd`, `probes`, `routec-l1`), all gitignored |
| `jvm-resources/src/Hello.java` | the first milestone's test payload; no longer built |
| `stikdebug/` | `runelite-jit.js`, the per-launch JIT script ([README](stikdebug/README.md)) |
| `../dev-tools/jre/` | the patched iOS `jre21` (gitignored; folder-referenced into the `.app` as `jre/`) |

Three sources are compiled into the app from `../dev-tools/`: `gpu-routec/app/RouteCBridge.m`,
`gpu-routec/jni/routec_gl_jni.m` (the GPU renderer's GL bridge) and `gpu-zone/jni/routec_zone_jni.m` (the extra
natives 117 HD needs).

## Build and run
1. Place the JRE and stage the jars in the order [`../README.md`](../README.md) gives, then
   `cd ios/app && xcodegen generate`.
2. Build to a phone from Xcode with your own signing team, or use `../dev-tools/remote-build.sh` or
   `../dev-tools/serve-ipa.sh`. For the Simulator, `../dev-tools/run-sim.sh` builds, installs and presses Play.
3. On a phone, launch RuneMobile. The cover reads "Attach StikDebug to play…" until StikDebug attaches with
   `runelite-jit.js` assigned, then "Ready to play". Tap **Tap to Play**. The renderer is the one picked in the
   plugins panel (CPU, GPU or 117 HD; a first run probes the GPU and picks one).
4. A Debug build writes `Documents/hello-shell.log` (the app) and `Documents/jvm-stdio.log` (the JVM's stdout and
   stderr), visible in the Files app. A triple tap on the cover opens the debug panel.

Reference patterns (GPLv3, read only, never vendored): `AngelAuraMC/Amethyst-iOS` `Natives/JavaLauncher.m` and
`Natives/utils.m`.
