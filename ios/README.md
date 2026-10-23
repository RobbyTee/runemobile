# RuneLite on iOS — the iOS port

This directory is the **iOS half** of the mobile project: an **Objective-C / UIKit**
shell that hosts **real RuneLite** on an embedded, on-device **OpenJDK 21 with JIT**
(per-launch StikDebug attach), rendering live OSRS with the full plugin ecosystem.

- **What the app is, features, risk, privacy:** see the [root `README.md`](../README.md).
- **Installing a released build on a phone** (SideStore/AltStore + StikDebug):
  root `README.md` → **Install — iOS**. The JIT script the guide references is
  tracked at [`app/stikdebug/runelite-jit.js`](app/stikdebug/runelite-jit.js).

## Layout

```
ios/
  app/                     the native app (xcodegen project.yml → RuneMobile.xcodeproj)
    RuneMobile/            Objective-C shell (ViewController, JVMLauncher, panels, login)
    jvm-resources/         staged jars (generated/gitignored: probes.jar, client-shaded.jar, client-rlhd.jar)
    build-probes.sh        builds probes.jar (headless boot entry + CACIO folded in)
    stikdebug/             the per-launch JIT attach script (runelite-jit.js)
  dev-tools/               build/dev tooling (see its subdirectory READMEs)
    JRE21-INVENTORY.md     provenance of the patched arm64 iOS JRE (what jre/ must contain)
    jre/                   the embedded iOS JRE (gitignored; fetched/placed per machine)
    serve-ipa.sh           builds + serves RuneLite.ipa for on-phone sideload install
    DEPLOY-REMOTE.md       remote build-Mac → phone deploy loop
```

## Build from source (macOS)

Prerequisites: **Xcode** (with an Apple ID for signing — a free one works),
**xcodegen** (`brew install xcodegen`), **OpenJDK 21** (`brew install openjdk@21`),
and an arm64 iPhone on **iOS 17.4+**.

1. **Place the iOS JRE.** The embedded runtime is a patched arm64 **Mach-O**
   OpenJDK 21 (iOS needs the `MirrorMappedCodeCache` JIT patch; Android's bionic
   JRE will not work). It is gitignored; put it at `ios/dev-tools/jre/` and check
   it against [`dev-tools/JRE21-INVENTORY.md`](dev-tools/JRE21-INVENTORY.md).
2. **Build + stage the four jars** into `app/jvm-resources/`, from the repository
   root and in this order (the release build uses the same one):
   ```sh
   ./gradlew :client:shadowJar                                  # the shaded client jar
   ( cd ios/dev-tools/gpu-routec && ./stage-ios-gpu-jar.sh )    # → client-shaded.jar (GL-facade variant)
   ( cd ios/dev-tools/gpu-zone && ./stage-ios-zone-jar.sh )     # → client-rlhd.jar (117HD)
   ( cd ios/app && ./build-probes.sh )                          # → probes.jar
   ( cd ios/dev-tools/gpu-routec/l1 && ./build-l1-probe.sh )    # → routec-l1.jar
   ```
   Don't use `tools/stage-mobile-runtime.sh` for iOS: it stages the pristine
   shaded jar, and `app/scripts/check-staged-jar.sh` fails the Xcode build until
   `stage-ios-gpu-jar.sh` replaces it. [`PORTS.md`](../PORTS.md) explains why the
   two ports' jars differ.
3. **Generate the Xcode project and build:**
   ```sh
   cd ios/app && xcodegen generate                   # project.yml is the source of truth
   ```
   Open `RuneMobile.xcodeproj`, set your own signing team, and build to the
   device — or build an installable `RuneLite.ipa` with
   `ios/dev-tools/serve-ipa.sh` and install it through SideStore/AltStore.
4. **Enable JIT each launch.** Attach **StikDebug** with
   `app/stikdebug/runelite-jit.js` assigned (root README → *Install — iOS* →
   *Each time you play*). Launching without the attach just waits — that is the
   expected iOS constraint, not a bug.

## Run in the iOS Simulator (no phone)

For UI and feature work, the same app runs in the iOS Simulator on an Apple
silicon Mac with the full JIT and no StikDebug. After steps 1–2 above:

```sh
ios/dev-tools/run-sim.sh                  # build, install, launch and press Play (CPU renderer)
RENDERER=gpu ios/dev-tools/run-sim.sh     # cpu | gpu | 117hd
ios/dev-tools/run-sim.sh --jars           # restage the jars first, after a Java change
ios/dev-tools/run-sim.sh --from-phone     # start from a paired phone's settings
ios/dev-tools/bridge-census/run-census-sim.sh --jars   # check every JNI lookup against the jars
```

`--from-phone` copies the app's settings from a paired iPhone (cable or the
same network) before launch: the native UI settings, the active RuneLite
profile and the game's preferences, so the simulator starts the way the phone
does. It never copies the login; the simulator stays logged out. The renderer
follows the phone's unless `RENDERER` is set.

`run-census-sim.sh` is the iOS bridge census: it lists every class, method and
field the native code looks up through JNI and resolves each one in the app's
own VM ([`dev-tools/bridge-census/`](dev-tools/bridge-census/README.md)). Run it
after a change to the shared bridge; a lookup that no longer resolves fails it.

The simulator build retags the bundled JRE as a simulator binary
(`app/scripts/resign-jre.sh`), and the app services HotSpot's code-cache
breakpoint in-process instead of waiting for the debugger (`JVMLauncher.m`,
`TARGET_OS_SIMULATOR` only). Device builds are unchanged.

It is a UI and logic check, not a phone. It does not show performance, the
StikDebug JIT path, the phone's GPU driver (the simulator draws GPU modes with
Apple's software renderer), real multi-touch, or background/PiP behaviour.
Check those on the device.

## Keeping current with upstream RuneLite

```sh
git fetch upstream && git merge upstream/master
```

After an OSRS weekly update the client revision goes stale
(`js5connect_outofdate` / black screen at boot) until this merge + a rebuild.
The fork keeps its additions additive — the shared bridge lives in
`runelite-client/.../headless/`, and the handful of upstream-file seams are
deliberately one-liners — so the merge is normally clean.

## Note

Personal / technical project. A self-built iOS client is **not on Jagex's
approved-client list** for live OSRS — use a **throwaway account**. Full risk,
privacy, and license detail: root `README.md`, `PRIVACY.md`, `SECURITY.md`,
`THIRD-PARTY-NOTICES.md`.
