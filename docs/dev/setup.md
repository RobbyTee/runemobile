# Setting up a development machine

This page takes a Mac from a fresh clone to a build you can run: the tools each port needs, the git hooks, what
lives where in the tree, the staging steps a clone needs before anything boots, and the signing and login
material you keep outside the repository. It ends with how to keep the machine clean and how to move to a new one.

Read it once end to end before your first build. Most of the traps below cost an afternoon each the first time.

> Rune Mobile is a personal, technical project. A self-built client is not on Jagex's approved-client list, so
> logging into live Old School RuneScape with it risks a ban. Use a throwaway Jagex account for all development,
> never a main. The [README](../../README.md#risk--disclaimer) has the full risk statement.

## What you need

### For both ports

| Tool | Why | Notes |
|---|---|---|
| macOS on Apple silicon | The iOS port and the iOS Simulator path need it, and every tracked script is written for macOS (`stat -f`, `pbpaste`, `md5`, `xcrun`). | Android-only work may run elsewhere, but nothing is tested there. |
| git | Clone, hooks, upstream merges. | |
| A JDK 21 | Builds the client (`./gradlew :client:shadowJar`), runs the jar stagers, the desktop harness and the Android Gradle build. | Homebrew `openjdk@21` is what the scripts fall back to when `JAVA_HOME` is unset. If your JDK 21 lives elsewhere, export `JAVA_HOME` before running anything. |
| `jq` | Reads `tokens.json` in the desktop harness and a few release scripts. | `brew install jq` |
| `gh` (GitHub CLI) | Only for release work (`tools/ci/*.sh`). | Optional for everyday development. |

The root build is upstream RuneLite's Gradle build. Upstream's own CI (`.github/workflows/CI.yml`) still runs it on
JDK 11, which is fine for that build; everything this fork adds (stagers, the harness, the mobile runtimes) is JDK 21.
You only need JDK 11 for the JVM micro-benchmarks in `ios/dev-tools/jvmbench/`, and only when you deliberately compare
JVM versions.

### For the iOS port

| Tool | Why | Notes |
|---|---|---|
| Xcode, with its command-line tools | Builds the app and runs the Simulator. | Use a current Xcode. The README's [platform status](../../README.md#platform-status--supported-devices) lists the device and OS versions the app is checked on. |
| `xcodegen` | `ios/app/project.yml` is the source of truth; the `.xcodeproj` is produced from it and is not tracked. | `brew install xcodegen` |
| An Apple ID | Signing for a device build. | A free Apple ID works; see [Signing](#ios-signing). The Simulator needs no signing at all. |
| An iPhone or iPad, plus StikDebug | Running on hardware. JIT needs a debugger attach at every launch. | See [ios.md](ios.md) and the README's *Install — iOS* section. |

### For the Android port

| Tool | Version, and where it is set | Notes |
|---|---|---|
| Android SDK platform | `compileSdk`/`targetSdk` 35, `minSdk` 28 (`android/app/build.gradle.kts`) | Install with Android Studio's SDK Manager or `sdkmanager`. |
| SDK build-tools | any recent | The release gate looks for `apksigner` under `build-tools/*/` to check signing fingerprints. |
| SDK platform-tools | any recent | `adb`, for installs and logs. |
| NDK | The Android Gradle Plugin's default for its version: the build pins no `ndkVersion`. | Install the version Gradle asks for. `tools/stage-android-jre.sh` also needs an NDK to build the fake `libawt_xawt.so`, and takes the first one it finds under `$ANDROID_SDK_ROOT/ndk/`. |
| CMake | `3.22.1` (`externalNativeBuild` in `android/app/build.gradle.kts`) | Install that exact version from the SDK Manager. |
| Gradle, AGP, Kotlin | Wrapper in `android/gradle/wrapper/gradle-wrapper.properties`; plugins in `android/build.gradle.kts` | The wrapper downloads Gradle. Run it on your JDK 21; the app compiles to Java 17 bytecode. |

Tell Gradle where the SDK is with `android/local.properties` (gitignored):

```properties
sdk.dir=/path/to/Android/sdk
```

`tools/stage-android-jre.sh` reads `ANDROID_SDK_ROOT` instead, and defaults to `~/Library/Android/sdk`. Export it if
your SDK lives somewhere else.

### Python for the QA tools

The simulator gate, the screen goldens and the image-diff helpers in `tools/mcp/` need Python 3 with **Pillow** and
**numpy**. `ios/dev-tools/sim-gate.sh` runs them with `/usr/bin/python3`, the interpreter macOS ships with the Xcode
command-line tools, rather than whatever `python3` is first on your `PATH`. A Homebrew Python usually lacks both
packages, and installing into it is blocked by default, so the scripts pin the system one. Install the packages for
it once:

```sh
/usr/bin/python3 -m pip install --user pillow numpy
/usr/bin/python3 -c "import PIL, numpy; print('ok')"
```

`tools/port-drift/port-drift.py` and the log scan (`tools/log-scan/log-scan.py`) are plain standard-library Python
and run under any Python 3.9 or later. [testing-qa.md](testing-qa.md) covers each tool.

### Linters

The pre-commit hook lints the staged files of each kind when the tool is installed, and skips it quietly when it is
not. The release gate is stricter: it runs `shellcheck`, `ktlint` and `detekt` with `--require`, so a missing linter
fails the gate instead of passing it. Install all five:

```sh
brew install shellcheck ktlint detekt clang-format gitleaks
```

| Tool | What it guards | Config | Runs in |
|---|---|---|---|
| `shellcheck` | Every tracked shell script, at `--severity=warning`. | `.shellcheckrc` | pre-commit (staged), release gate (all, required) |
| `ktlint` | Kotlin style under `android/`. Auto-fix with `ktlint --format <files>`. | `.editorconfig` | pre-commit (staged), release gate (all, required) |
| `detekt` | Kotlin static analysis. Existing debt is grandfathered, so only new findings fail. | `android/config/detekt/` | release gate (required) |
| `clang-format` | Native C, C++ and Objective-C, **changed lines only**: the hand-aligned tree is not reformatted wholesale. Vendored JDK headers under `ios/app/RuneMobile/jni/` are excluded. | `.clang-format` | pre-commit (staged) |
| `gitleaks` | A second secret scan of staged content. | defaults | pre-commit (staged) |

The runners live in `tools/lint/`; each takes `--require` and explains its exit codes in its header.
[CONTRIBUTING.md](../../CONTRIBUTING.md#linting) has the policy behind each one.

## Clone, remotes and hooks

Rune Mobile is a fork of [runelite/runelite](https://github.com/runelite/runelite). Clone it, then add upstream
RuneLite as a fetch-only remote so you can merge it but never push to it by accident:

```sh
git clone https://github.com/audaro/runemobile.git
cd runemobile
git remote add upstream https://github.com/runelite/runelite.git
git remote set-url --push upstream no_push
git fetch upstream
```

Pushing to `master` on the public repository builds and publishes a release, so read
[upstream-release.md](upstream-release.md) before you push there. A fork of your own builds nothing: the release
workflow is gated to the public repository's id.

### Install the git hooks

The hooks are tracked in `tools/git-hooks/`, but git only runs them once the clone points at them. Do this once per
clone:

```sh
tools/git-hooks/install.sh
git config core.hooksPath     # expect: tools/git-hooks
```

`pre-commit` blocks a commit when it sees:

- **Secrets and keys:** a staged `tokens.json`, `legacy_login.json`, `*.jks`, `*.keystore` or `keystore.properties`,
  any private-key block in the added lines, or anything `gitleaks` flags.
- **Generated output:** `ios/app/jvm-resources/probes.jar`. Every jar is rebuilt by a staging script and never
  committed.
- **Force-added ignored files.** Anything the ignore rules cover stays local; `git add -f` does not get it in.
- **A shared-bridge contract change without its baseline.** When you stage files under `runelite-client/`,
  `ios/dev-tools/awtsmoke/` or `tools/stage-*.sh`, the hook runs `tools/shared-contract/check-contract.sh`. A changed
  bridge surface has to be committed with the refreshed `tools/shared-contract/baseline.txt` in the same commit.
  [bridge-api.md](bridge-api.md) explains the contract.
- **A login change without the auth contract.** Staging the Jagex login code on either port or
  `ios/dev-tools/fetch-shield-tokens.sh` runs `tools/shared-contract/check-auth-contract.sh`, which keeps the two
  ports and the desktop script on the same OAuth wire format.
- **Lint findings** from the tools above, on the staged files only.

`commit-msg` checks the message, and `pre-commit` the added lines, against an optional pattern list kept outside the
repository. Its path is `$RUNEMOBILE_ATTRIBUTION_DENYLIST`, or `~/.config/rune-mobile/attribution-denylist` when that
is unset. The file holds one extended regular expression per line, matched case-insensitively, with `#` comments. It
lives outside the repo because the hooks themselves are public. Contributors don't need one: without the file the
check prints a notice and passes. If the file exists but can't be read, the hook blocks the commit rather than
skipping it quietly. During a merge (`MERGE_HEAD` present) the added-line scan is skipped, so an upstream merge is
not blocked by text upstream wrote.

Override a single commit with `git commit --no-verify` only for a flag you have checked is a false positive.

**clang-format and non-ASCII punctuation.** `git clang-format` can rewrap a line differently on each run when an
em dash or other multi-byte character sits near a changed line, so the hook flags a line you just fixed. Use ASCII
hyphens in native comments. To format by hand, name the files after `--`:

```sh
git clang-format --staged HEAD -- ios/app/RuneMobile/ViewController.m
```

## A tour of the tree

Most of the repository is upstream RuneLite, and stays as upstream wrote it. The first-party code is:

| Path | What it is |
|---|---|
| `ios/` | The iOS port: the Objective-C/UIKit host in `ios/app/RuneMobile/`, the Xcode spec `ios/app/project.yml`, and `ios/dev-tools/` (stagers, harnesses, Simulator tooling). |
| `android/` | The Android port: the Kotlin/Compose host and its C++ in `android/app/`, plus `android/dev-tools/` (stagers, the release gate, the bridge census). |
| `runelite-client/src/main/java/net/runelite/client/headless/` | **The one first-party package inside upstream.** The Java bridge both ports embed: frames, input, overlays, plugin config, the mobile UI data. |
| `tools/` | Cross-port tooling: hooks, linters, the shared-contract checks, the runtime stager, release scripts (`tools/ci/`), the shader matrix, the log scan, port drift. |
| `docs/` | This guide, and `docs/handoffs/` (tracked, therefore public). |
| Root docs | `README.md`, `PORTS.md`, `CONTRIBUTING.md`, `RELEASE.md`, `CHANGELOG.md`, `PRIVACY.md`. |

Beyond those, the fork makes small edits to about fifteen upstream files: hooks in `Hooks.java`, `PluginManager`,
`ConfigManager` and `ClientUI`, the new `HeadlessClientUI`, the plugin-hub pin in `runelite.properties`, and a few
more. List the current set with:

```sh
git diff --name-status upstream/master...HEAD -- runelite-client
```

Keep those edits as small as you can, and put new behaviour in `headless/` instead. Every upstream merge has to
carry them forward, and the smaller they are, the more often the merge is clean. Upstream also edits these files,
so they are where merge conflicts show up ([upstream-release.md](upstream-release.md#conflict-hot-spots)).
[architecture.md](architecture.md) explains what each layer does, and [PORTS.md](../../PORTS.md) maps each port's
directories.

## Fresh-clone staging

A clone does not boot. The embedded JREs and every jar the apps load are gitignored, because they are large and
derived. Tracked, SHA-pinned scripts rebuild them. Run them in the order below, from the repository root.

### Why the order matters

Each later step reads what an earlier one wrote:

1. `./gradlew :client:shadowJar` builds the pristine shaded client jar. Everything else starts from it.
2. The GPU stager (`stage-ios-gpu-jar.sh` or `stage-android-gpu-jar.sh`) recompiles RuneLite's GPU plugin against a
   pure-Java GL facade and rewrites the shaded jar into the port's GPU variant, which carries that facade.
3. The 117 HD stager (`stage-ios-zone-jar.sh` or `stage-rlhd-zone-jar.sh`) compiles the pinned 117 HD source, plus
   this repo's overrides, against the same facade and a copy of the shaded jar with the real LWJGL removed. Its jar
   carries no facade of its own, so at run time it needs the GPU variant beside it.
4. `ios/app/build-probes.sh` builds `probes.jar` (the AWT activation and boot classes) against the shaded jar and
   CACIO.
5. On Android, `tools/stage-mobile-runtime.sh --android-only` runs last: it hashes the shaded, GPU, 117 HD and probe
   jars into `runtime.tag`, and refuses to run if the GPU or 117 HD jar is missing.

Run them out of order and you either get a loud failure or, worse, a build that boots with a stale jar.

### Android

```sh
tools/stage-android-jre.sh                               # SHA-pinned arm64 bionic OpenJDK 21 -> assets/jvm/jre
./gradlew :client:shadowJar                              # the shaded client jar
( cd ios/app && ./build-probes.sh )                      # probes.jar (on Android's boot classpath too)
android/dev-tools/gpu/stage-android-gpu-jar.sh           # -> client-<ver>-gpu.jar
android/dev-tools/gpu/stage-rlhd-zone-jar.sh             # -> client-<ver>-rlhd.jar (117 HD)
tools/stage-mobile-runtime.sh --no-build --android-only  # stage probes.jar, write runtime.tag
cd android && ./gradlew :app:assembleDebug
```

Never run a bare `./gradlew assemble` in `android/`: it also builds the `:recon` probe app. Use `:app:assembleDebug`
or `:app:assembleRelease`. The two native libraries are not staged; Gradle's CMake build compiles them from
`android/app/src/main/cpp/`.

The JRE goes into the APK's assets only. Never copy its `.so` files into `jniLibs/` as well: the linker then
resolves `libawt.so` by soname instead of by path, and the VM aborts with "Could not allocate library name". The
header of `tools/stage-android-jre.sh` explains it, and [android.md](android.md) covers the unpack path.

### iOS

```sh
# 0. the iOS JRE, once per machine (see below)
./gradlew :client:shadowJar
( cd ios/dev-tools/gpu-routec && ./stage-ios-gpu-jar.sh )    # -> client-shaded.jar (the GL-facade variant)
( cd ios/dev-tools/gpu-zone && ./stage-ios-zone-jar.sh )     # -> client-rlhd.jar (117 HD)
( cd ios/app && ./build-probes.sh )                          # -> probes.jar
( cd ios/dev-tools/gpu-routec/l1 && ./build-l1-probe.sh )    # -> routec-l1.jar
tools/stage-mobile-runtime.sh --check                         # verify only; builds and copies nothing
cd ios/app && xcodegen generate
```

**Do not stage iOS with `tools/stage-mobile-runtime.sh` without `--android-only`.** It copies the pristine shaded jar
over `ios/app/jvm-resources/client-shaded.jar`, which drops the GL facade (`org/lwjgl/opengl/GLALL`). The app built
from that jar boots and logs in, with the GPU renderer silently dead. `ios/app/scripts/check-staged-jar.sh` runs as an
Xcode build phase and fails the build when it sees the pristine jar, so you find out at build time, not on the phone.
`--check` is the safe way to run the stager on iOS: it builds nothing and verifies that `probes.jar` is current on
both ports and that iOS holds the GPU variant.

### What the scripts fetch for you

A clone needs no hand-placed jars:

- **CACIO.** `ios/app/build-probes.sh` downloads the two Caciocavallo jars from Maven Central into
  `ios/dev-tools/cacio-smoke/lib/` when they are absent, and checks their SHA-256 before use.
- **117 HD source.** Both 117 HD stagers fetch [117HD/RLHD](https://github.com/117HD/RLHD) at the commit pinned as
  `RLHD_REV` in each script, then lay the tracked overlays over it. `ios/dev-tools/gpu-zone/rlhd-src/PROVENANCE.md`
  records the pin. [renderers.md](renderers.md) explains the overlays.
- **Lombok.** The 117 HD stagers run Lombok as an annotation processor, using the version pinned in
  `libs.versions.toml`, which `:client:shadowJar` has already pulled into the Gradle cache. An arbitrary older 1.18.x
  crashes JDK 21's `javac` (`NoSuchFieldError JCTree$JCImport.qualid`), which is why `shadowJar` comes first. The GPU
  stagers instead recompile RuneLite's delomboked GPU plugin sources, and refresh them (`./gradlew :client:delombok`)
  on every run.
- **The Android JRE.** `tools/stage-android-jre.sh` downloads a 16 KB-page-aligned arm64 OpenJDK 21 at a pinned
  SHA-256, caches it in `android/dev-tools/.jre-cache/`, and builds the fake `libawt_xawt.so` with the NDK. `--force`
  downloads it again even when the cache has it, and `JRE_URL_MIRROR` names a fallback host for when the primary one
  is down.

### The exception: the iOS JRE

The iOS runtime is the one input no staging script produces for you. It is an arm64 Mach-O OpenJDK 21 with a binary
patch, and it lives at `ios/dev-tools/jre/` (gitignored). Build it once per machine:

1. Download the SHA-pinned zip, check its hash and unpack it, following
   [`ios/dev-tools/JRE21-INVENTORY.md`](../../ios/dev-tools/JRE21-INVENTORY.md).
2. Run `ios/dev-tools/patch-jre-txm.sh`. It verifies the original bytes, then NOPs the branches in `libjvm` that keep
   HotSpot off the debugger-mapped JIT path on current iPhones. It is idempotent. The header of the script explains
   each patched site.
3. Run `ios/dev-tools/fake-xawt/build-libawt_xawt.sh` to add `lib/libawt_xawt.dylib`, which this JRE's AWT expects
   and does not ship ([`ios/dev-tools/fake-xawt/README.md`](../../ios/dev-tools/fake-xawt/README.md) says why).

Treat the result as local state you keep, not a cache you throw away: back it up, and carry it to your next Mac (see
[Moving to a new Mac](#moving-to-a-new-mac)). The hosted release build gets its copy from a release asset rather than
rebuilding it ([upstream-release.md](upstream-release.md#hosted-build-inputs)).

### Android's Gradle self-staging, and when it lies

`./gradlew :app:assembleDebug` runs `:app:stageBootAssets` before it builds, which chains the JRE, shaded jar, probes,
GPU, 117 HD and runtime stages. Each of those tasks runs **only when its output is absent**, and the check is by
filename: if `client-<version>-gpu.jar` for the current `project.build.version` exists, the GPU stage is skipped.

That is fine on a fresh clone. It is wrong after any change that keeps the version: an upstream merge that stays on
the same `-SNAPSHOT`, a bridge edit, a change to a 117 HD overlay. The build then ships the jars from last time,
without an error. After such a change, run the Android sequence above by hand.

Running `./gradlew :app:stageBootAssets` explicitly does not help, because its subtasks keep the same guards. Nor
does deleting only the jars in `assets/jvm/`: the shaded-jar task skips when
`runelite-client/build/libs/client-<ver>-shaded.jar` exists and the probes task skips when
`ios/app/jvm-resources/probes.jar` exists, so the GPU and 117 HD jars would be rebuilt from stale inputs.

### Jar names differ by port

| | iOS (`ios/app/jvm-resources/`) | Android (`android/app/src/main/assets/jvm/`) |
|---|---|---|
| Client | `client-shaded.jar` (GL-facade variant) | `client-<ver>-gpu.jar` |
| 117 HD | `client-rlhd.jar` | `client-<ver>-rlhd.jar` |
| Probes | `probes.jar` | `probes.jar` |
| Other | `routec-l1.jar` | `runtime.tag` |

iOS names carry no version, so Xcode's project never changes. Android names carry it, and the stagers prune jars of
other versions. Android has no plain shaded jar on its boot classpath; it boots `[gpu : rlhd : probes]`. Each port
builds its own variants, so only `probes.jar` is byte-identical across the two; never compare jar SHAs across
ports. `runtime.tag` is a content tag over the staged jars: when it changes, the Android app unpacks the new jars
on the next launch, so a jar update needs no hand-edited version bump.

## First build and the inner loop

Work from the cheapest loop that can show your change, and go to a phone last.

1. **Desktop OpenJDK 21.** The phones run the same JVM family, so most Java work (the bridge, overlays, plugin
   behaviour) can be built and checked on the Mac. `ios/dev-tools/run-desktop-reference.sh` launches stock RuneLite
   from this fork as a normal desktop window, the ground truth to compare against. The headless harness in
   `ios/dev-tools/headless/` boots the same client with no window, under CACIO as the phones do, and writes frames
   to disk. [testing-qa.md](testing-qa.md) covers the harness, frame diffs and the bridge census.
2. **The iOS Simulator.** `ios/dev-tools/run-sim.sh` builds the app for the Simulator, installs and launches it. The
   Simulator needs no StikDebug: the app services HotSpot's JIT breakpoint itself there. It is the right place for
   native UI and most feature work. Add `--jars` after a Java change. It does not tell you about performance, the
   device JIT path, the phone's GPU driver, real multi-touch or backgrounding.
3. **A device.** For iOS, a Mac with the phone on its own network (USB or the same LAN) can build and install with
   `ios/dev-tools/remote-build.sh` (set `DEV` to the device id from `xcrun devicectl list devices`), or build from
   Xcode. For Android, `android/dev-tools/deploy-wireless.sh` builds and installs over Wi-Fi after a one-time pairing,
   or use `adb install -r` with a cable.

Device traps worth knowing on day one:

- `adb install -r` leaves the old process running. Run `adb shell am force-stop dev.audaro.runemobile` and relaunch,
  or you are testing the previous build.
- After a change under `android/app/src/main/cpp/`, run `./gradlew :app:externalNativeBuildDebug --rerun-tasks` before
  `:app:assembleDebug`. Otherwise the native build can report up to date and the APK carries the old `.so`.
- iOS installs have to start from the phone's own network; `lockdownd` refuses them over a VPN.
  [upstream-release.md](upstream-release.md#installing-a-dev-build-away-from-home) covers installing away from home.
- On iOS, the app waits at launch until StikDebug attaches. That wait is by design.

[ios.md](ios.md), [android.md](android.md) and [debugging.md](debugging.md) go deeper on each.

## Signing

### iOS signing

Copy the template and set your Team ID:

```sh
cp ios/dev-tools/signing.env.example ios/dev-tools/signing.env    # gitignored
# edit it: export DEVELOPMENT_TEAM=<your 10-character team id>
```

`project.yml` expands `${DEVELOPMENT_TEAM}` for automatic signing, and the build scripts
(`remote-build.sh`, `serve-ipa.sh`, `tools/ci/build-release-artifacts.sh`, `run-sim.sh`) source the file. Xcode
manages the profile (`-allowProvisioningUpdates`).

- **A free Apple ID** works. Its profiles last 7 days, so the app has to be re-signed weekly.
- **A paid Apple Developer account** gives profiles that last a year. It does not change JIT: the `get-task-allow`
  entitlement StikDebug needs comes from a *development* profile, and every launch still needs the debugger attach.
  A distribution-signed build strips that entitlement, which is why there is no TestFlight or App Store path.
- Apple registers each bundle identifier to one team. If Xcode says `dev.audaro.runemobile` is not available to
  yours, change `PRODUCT_BUNDLE_IDENTIFIER` in `ios/app/project.yml` for your local builds and don't commit it.
  Several scripts refer to the app by that identifier (`BUNDLE_ID` in `sim-gate.sh`, for one), so check the ones
  you run.

### Android signing

Without any configuration, both debug and release builds are signed with the Android debug key. That is fine for
development, with one trap: Android only updates an app in place when the signature matches, so a debug-signed build
will not install over a release from the project's Releases page. Uninstall first (and back up your login, see
[RELEASE.md](../../RELEASE.md#android)).

To sign release builds with your own key, copy `android/keystore.properties.example` to `android/keystore.properties`
(gitignored, and blocked by the pre-commit hook). It holds four keys:

```properties
storeFile=/absolute/path/outside/the/repo/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` may be relative to `android/`, but keep the keystore itself outside the repository. If the file exists
but a key is missing, the build fails rather than quietly falling back to the debug key. The template has the
`keytool` command for a new key and a warning about rotation: changing the key later breaks update-in-place for every
install signed with the old one.

Release signing for the published builds is covered in [upstream-release.md](upstream-release.md#signing).

## Logging in from the desktop

The phones log in through their own in-app Jagex login. The desktop harness can't, so it reads a session from
`ios/dev-tools/tokens.json`. Mint it with:

```sh
ios/dev-tools/fetch-shield-tokens.sh
```

The script is interactive. It prints the Jagex login URLs to open in a browser; after each login you copy the
redirect URL from the browser and press a key, and the script reads the URL from the clipboard (pasting a URL that
long into Terminal gets it cut short). It writes `tokens.json` with mode 600. The file is gitignored and the pre-commit
hook blocks it; it is a secret, so never paste it anywhere. Use a throwaway account.

What to know:

- **The desktop session and the phones' sessions are separate.** Nothing syncs between `tokens.json` and the in-app
  login on a device. The Simulator can borrow the desktop session with `ios/dev-tools/run-sim.sh --desktop-login`.
- **Don't run two clients on one account at once.** A second login kicks the first, so a harness run and a
  Simulator run on the same account interrupt each other.
- **Expired sessions** show as a 401 or a refused login. Re-run `fetch-shield-tokens.sh`; there is no refresh step
  to run by hand.
- **The launcher tokens can get a login refused.** The script also writes an access/refresh token pair from the
  launcher flow. With those set, the game server has refused the login ("Failed to login. Please try again.") while
  the same session without them logged straight in. `ios/dev-tools/headless/run-layout-switch.sh` and
  `run-sim.sh --desktop-login` leave them out for this reason. If another harness script's login is refused, try a
  copy of the file with both fields blanked, passed as `TOKENS=<path>`.

### Keep the harness out of your real RuneLite profile

RuneLite keeps its home in `~/.runelite` (it reads `user.home`). If you also play desktop RuneLite on the Mac, that
directory holds your real profiles, and possibly a RuneLite-account session that syncs them to the cloud. A harness
run that writes to it changes your profile; one run has deleted keys from a real profile. Saved overlay positions in
your profile also leak into the harness's frames and make renders differ from machine to machine.

Give each scratch run its own home, and reuse the game cache so it isn't downloaded again:

```sh
SCRATCH=$PWD/build/rlhome
mkdir -p "$SCRATCH/.runelite"
ln -s ~/.runelite/jagexcache "$SCRATCH/.runelite/jagexcache"
cp ~/.runelite/jagex_cl_oldschool_LIVE.dat ~/.runelite/random.dat "$SCRATCH/.runelite/"
export JAVA_TOOL_OPTIONS="-Duser.home=$SCRATCH"
```

The harness scripts pass their own arguments to the client, so JVM flags go through `JAVA_TOOL_OPTIONS`. The fresh
home has no RuneLite-account `session` file, so nothing in it syncs. To prove a run left your real profile alone,
fingerprint it before and after:

```sh
md5 -q ~/.runelite/profiles2/*.properties | md5
```

## Keeping the disk under control

Build output grows quickly: Gradle builds, Xcode derived data, harness frames, jar-surgery work directories. Reclaim
it with the allowlist cleaner:

```sh
tools/clean-local.sh                  # dry run: what would go, with sizes
tools/clean-local.sh --apply          # delete it
tools/clean-local.sh --only gradle    # Gradle outputs (opt-in: the rebuild takes minutes)
```

It deletes only ignored directories in known categories, never through a symlink, never a directory holding a
tracked file, never one touched in the last 7 days (`--days N` changes that), and it keeps the incremental caches the
scripts reuse.

**Never run `git clean -X` or `git clean -Xdf` in this repository.** The ignore rules also cover files you can't get
back: `tokens.json`, your signing files, and the patched iOS JRE. `-X` deletes them all.

## Moving to a new Mac

Code moves through git. Everything below is gitignored, so it doesn't, and has to be carried or rebuilt by hand:

| Item | Path | Carry or rebuild |
|---|---|---|
| The patched iOS JRE | `ios/dev-tools/jre/` | Carry it, or rebuild it as in [The exception: the iOS JRE](#the-exception-the-ios-jre). |
| iOS signing | `ios/dev-tools/signing.env` | Recreate from the `.example`; sign in to Xcode with the same Apple ID. |
| Android signing | `android/keystore.properties` and the keystore it points at | Carry both, securely. A lost release key can't be replaced. |
| SDK location | `android/local.properties` | Recreate for the new SDK path. |
| Remote-install config | `ios/dev-tools/deploy-remote.conf` | Recreate from the `.example`. |
| Desktop session | `ios/dev-tools/tokens.json` | Mint a fresh one rather than copying a secret between machines. |
| Simulator goldens | `ios/app/evidence/sim-golden/` | Rebuild: goldens depend on the Mac's simulator and Xcode. Run `ios/dev-tools/sim-screens/sim-screens.py --update` once (see [testing-qa.md](testing-qa.md)). |
| Release-build inputs | `~/.runemobile-ci/` | Maintainers only; see [upstream-release.md](upstream-release.md#hosted-build-inputs). |
| Attribution list | `~/.config/rune-mobile/attribution-denylist` | Carry it if you use one; the hooks only print a notice without it. |

Then, on the new Mac: install the tools from [What you need](#what-you-need), run `tools/git-hooks/install.sh` (hooks
are per clone), and run the staging sequence for each port. The first Gradle build downloads its dependencies and
takes a while.

`ios/dev-tools/macbook-transition.sh` is an older one-shot version of this move. It predates the 117 HD jar and the
single `master` branch, so read it as a checklist rather than running it as is.

## See also

- [README.md](README.md): the map of this guide.
- [architecture.md](architecture.md): how the JVM, the headless bridge and the native shells fit together.
- [ios.md](ios.md) and [android.md](android.md): each port in depth.
- [testing-qa.md](testing-qa.md): the harness, the Simulator gate, the censuses and the gate ladder.
- [upstream-release.md](upstream-release.md): merging upstream RuneLite and cutting releases.
- [gotchas.md](gotchas.md): the traps collected in one place.
- [PORTS.md](../../PORTS.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md).
