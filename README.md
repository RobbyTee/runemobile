# RuneLite on mobile (unofficial)

> ⚠️ **Unofficial fan project. Not affiliated with, endorsed by, or sponsored by
> Jagex or the RuneLite project.** This is a sideload-only, personal/technical
> build. It is **not** on Jagex's approved-client list, and it is **not** on the
> App Store, Google Play, or F-Droid. Logging into live Old School RuneScape with
> an unapproved client carries a **real risk of an account ban** — use a
> **throwaway account you can afford to lose, never your main**. Provided as-is,
> no warranty, at your own risk.

A native **iOS and Android** app that runs **real RuneLite** — including the
**full plugin ecosystem** — on an **in-process, on-device
OpenJDK 21**. No streaming, no companion PC, no host machine: the actual RuneLite
client executes on your phone inside a second JVM, rendering **live OSRS**, and a
thin native shell wraps it with touch controls and mobile chrome styled after the
official Jagex OSRS Mobile app.

This is not a re-implementation or a skin. It is the genuine RuneLite engine and
its plugins, with a native mobile UI layered on top.

---

## Contents

- [How it works](#how-it-works)
- [Features](#features)
- [Status & supported devices](#status--supported-devices)
- [Install — Android](#install--android)
- [Install — iOS](#install--ios)
- [Logging in](#logging-in)
- [Updating, rollback & recovery](#updating-rollback--recovery)
- [Build from source](#build-from-source)
- [Architecture](#architecture)
- [Privacy & security](#privacy--security)
- [License & attribution](#license--attribution)
- [Risk & disclaimer](#risk--disclaimer)

---

## How it works

The mobile process (Android ART / iOS) hosts a **second HotSpot JVM**: native
C++ `dlopen`s the embedded `libjvm.so`/`libjvm.dylib` and calls
`JNI_CreateJavaVM`, then boots **RuneLite headless**. A
[Caciocavallo](https://github.com/caciocavallosilano/caciocavallo) AWT backend drives
the engine's `Canvas`/`BufferProvider` off-screen, and the rendered frame is
blitted to a native surface. Touch input is mapped back into AWT events.

A shared Java bridge under `runelite-client/.../headless/` exposes the engine to
the native shell, and is reused **verbatim** between the Android (Kotlin / Jetpack
Compose) and iOS (Objective-C / UIKit) ports — only the host shell is
platform-specific.

- **Android grants JIT for free** — no debugger attach, no per-launch ceremony.
- **iOS** requires a per-launch JIT debugger attach (StikDebug) because Apple does
  not permit runtime code generation otherwise. This is the main extra step in the
  iOS setup, and it's an OS constraint, not a bug.

---

## Features

Everything below runs against the **real** RuneLite plugin models — these are
native UIs over the genuine engine, not mock-ups.

- **Precise engine-resolved touch targeting** — a literal-point tap routes to the
  exact pixel you touch; the engine's own model clickbox resolves the hit and a
  settle gate confirms the real outcome (red ring = interact, yellow = walk, item
  border = inventory). Long-press opens the real left-click menu.
- **Full RuneLite plugin ecosystem + native config panel** — the unmodified plugin
  set, with a searchable, touch-friendly bottom-sheet: per-plugin toggles, pins,
  config screens, and a **Plugin Hub** browser to install hub plugins on-device.
- **Three renderers, selectable in-app** — **CPU** (software), **GPU** (the
  RuneLite GPU plugin), and **117HD** (desktop-grade HD lighting, shadows, water,
  draw distance). Accelerated renderers are auto-promoted only after a live GPU
  capability probe, with automatic fallback to CPU on failure. Live quality levers
  (MSAA / scene-scale / anisotropic filtering).
- **World map** — finger-pan, pinch-zoom, precise tap-to-walk, edge-to-edge with
  chrome auto-hide.
- **Native HUD veneers** — minimap, HP/prayer/run/spec orbs as enlarged one-tap
  touch targets, and a top-relocated chat with channel pills (All/Game/Public/
  Private/Channel/Clan/Trade) and a real soft keyboard.
- **Native tab grid** — open any interface tab (Inventory/Prayer/Combat/Spellbook/
  Clan/…) through the genuine menu action.
- **Native info screens** — XP tracker, infoboxes, GE, loot tracker, time
  tracking, Quest Helper, reading the real plugin models.
- **RuneLite-account sync** — sign in to runelite.net (optional) and your tuned
  desktop profiles, settings, and hub plugins load automatically.
- **World hopping** via a fast relog-based hop.
- **PiP + background keepalive** (iOS) keeps a session connected while backgrounded.

> Some panels exist on one port slightly ahead of the other as parity is closed;
> both ports share the same Java data layer, so features land on both over time.

---

## Status & supported devices

This is an early **`0.1.0` pre-release**. It works and is playable, but it is a
single-maintainer, best-effort build and may break after an OSRS weekly update
until a new build is published.

**Android**
- arm64-v8a only · `minSdk 28` (Android 9) / `target 35` (Android 15).
- Verified on a Pixel 7a (Mali-G710) and the arm64 emulator, including a 16 KB-page
  kernel. 117HD validated across Mali-G715/G78/G52 and Adreno 740/750 driver
  families.
- JIT is granted by the OS — no extra tooling needed.

**iOS**
- Requires **iOS 17.4 or newer** with a passcode set; arm64 device.
- Verified on iPhone 17 Pro Max (iOS 26.5). Runs on-device via a per-launch JIT
  debugger attach (StikDebug).
- Free Apple ID signing means the app expires every **7 days** and is re-refreshed
  in one tap (no charge, no jailbreak).

> On iOS the installed app icon is labelled **HelloShell** — that's the name to
> look for on your Home Screen and in StikDebug's app list.

### Expected behaviors (by design)

A few things this build does that you'd otherwise only discover by surprise:

- **Reconnect is a re-login.** After a disconnect the app automatically clicks
  back through the login flow (bounded attempts) rather than resuming the old
  session — you'll see the login screen flash by. If it can't get back in, a
  "session expired" card asks you to sign in again.
- **Backgrounding on Android can drop the session.** While the app is in the
  background it idles at a slow tick to save battery, and Android/Jagex may cut
  the connection; foregrounding it triggers the automatic re-login above. (iOS
  keeps the session alive in the background via its picture-in-picture mode.)
- **117HD may reduce resolution when the phone runs hot (iOS).** Under sustained
  thermal pressure the HD renderer temporarily lowers its internal resolution
  scale and restores it once the device cools — this is deliberate, not a bug.

---

## Install — Android

Android is the simpler install: **download the APK, install it, done.** No
companion app, no JIT step.

1. **Download** the latest `RuneMobile-<version>.apk` from the
   [Releases page](../../releases).
2. **Allow install from your browser/file manager.** When you open the APK,
   Android will prompt you to allow installing unknown apps from that source —
   accept it (Settings → Apps → Special access → Install unknown apps).
3. **Install and open.** Launch the app and continue to [Logging in](#logging-in).

**Verify the build is genuine (optional but recommended).** Every official APK is
signed with the project's release key:
```sh
apksigner verify --print-certs RuneMobile-<version>.apk
# Expected SHA-256:
# 45:E3:05:84:4D:53:A6:26:5D:C8:6E:B3:99:BE:99:7A:B3:FA:27:76:F9:0F:18:B1:6F:D4:EC:90:F4:3D:3C:9E
```
A build that does not show this fingerprint is not a genuine release.

**Install via adb** (alternative):
```sh
adb install RuneMobile-<version>.apk
```

---

## Install — iOS

iOS has no store distribution and Apple does not allow runtime code generation by
default, so setup takes ~15 minutes once, plus a one-tap JIT step each time you
play. No jailbreak required.

### What you need

- An iPhone on **iOS 17.4+** with a **passcode set**.
- A **computer** (Windows / Mac / Linux) **once**, plus a USB cable.
- An **Apple ID** (a throwaway one is fine — free works).
- A **throwaway OSRS account**.
- From the [Releases page](../../releases): the app **`RuneLite.ipa`** and the
  JIT script **`runelite-jit.js`**.

### One-time setup

1. **Make a pairing file** (computer, ~5 min). Plug your iPhone in via USB, run
   [`idevice_pair`](https://github.com/jkcoxson/idevice_pair) (or Jitterbug), and
   it saves a small `.mobiledevicepairing` file. AirDrop/email it to your phone.
   ([Full walkthrough](https://docs.sidestore.io/docs/advanced/pairing-file).)
2. **Install [SideStore](https://sidestore.io)** (or AltStore) — the installer
   that signs apps with *your own* Apple ID. Enable **Developer Mode**
   (Settings → Privacy & Security; it'll prompt a restart), import the pairing
   file, install the **WireGuard VPN** it asks for, and sign in with your Apple ID.
3. **Install the app.** In SideStore, tap **＋** → choose **`RuneLite.ipa`**.
   It signs and installs it; the icon (labelled **HelloShell**) lands on your Home
   Screen.
4. **Install [StikDebug](https://github.com/StephenDev0/StikDebug)** (the JIT
   enabler) through SideStore the same way, plus **LocalDevVPN** (the loopback VPN
   it uses). Open StikDebug once, enter your passcode, and import the **same**
   pairing file.
5. **Assign the JIT script.** Get `runelite-jit.js` onto the phone (AirDrop, or
   Files → On My iPhone → StikDebug → `scripts/`). In StikDebug, select
   **HelloShell** and assign `runelite-jit.js` as its script. (One time — StikDebug
   remembers it.) This app needs the custom script; the default "just connect" will
   not boot it.

### Each time you play

1. Turn on the **LocalDevVPN** toggle.
2. Open **StikDebug**, pick **HelloShell** (with `runelite-jit.js` assigned), and
   let it **attach** (the log shows `[rl-jit]` lines as JIT switches on).
3. The app boots into the game.

> Opening HelloShell *without* attaching StikDebug first will just hang/wait —
> that's expected. Attach and it continues.

### iOS upkeep

- **Every 7 days** (free Apple ID): open SideStore and **Refresh** HelloShell (it
  can also auto-refresh over the VPN). If it won't open, it's usually just expired.
- **Every launch:** re-enable JIT via StikDebug (the step above).
- **Only one VPN runs at a time:** use SideStore's VPN to install/refresh,
  LocalDevVPN to enable JIT — toggle whichever the task needs.

---

## Logging in

On first launch, tap **Log in with Jagex**, sign in to your **throwaway account**
in the window that appears, and pick your character. **No credentials or tokens
ship in the app** — you authenticate on-device, and it stays logged in between
launches.

Optionally, sign in to your **RuneLite account** (runelite.net — this is *not* your
Jagex login) from the plugins panel to sync your desktop profiles, settings, and
hub plugins.

---

## Updating, rollback & recovery

Updating **never wipes your data** — an in-place reinstall with the same signing
identity keeps your token, cache, config, and renderer choice.

- **There is no in-app update check.** Check the [Releases page](../../releases)
  manually, especially after an OSRS weekly update — a stale client shows a black
  screen / `js5connect_outofdate` until a build against the new revision is
  published.
- **Android update:** `adb install -r RuneMobile-<version>.apk` (or just open the
  newer APK). **Rollback:** `adb install -r -d RuneMobile-<older>.apk` (`-d` allows
  the version downgrade and keeps data).
- **iOS update/rollback:** reinstall the desired build through SideStore/AltStore
  with the same identity.

Full recovery steps (broken state, token backup, clearing app data, GPU-crash
recovery) are in [`RELEASE.md`](RELEASE.md).

---

## Build from source

A fresh clone does **not** contain the embedded JRE or the client jars — they are
gitignored and regenerated by tracked, SHA-pinned staging scripts. The app's native
libraries are built automatically by Gradle/CMake/Xcode from tracked C++ source.

> The embedded JDK is fetched from a single third-party host at a pinned SHA —
> mirror it if you depend on reproducible builds.

### Android

From the repository root, in order (order is load-bearing — see
[`android/README.md`](android/README.md)):

```sh
tools/stage-android-jre.sh                            # SHA-pinned arm64 OpenJDK 21 -> assets/jvm/jre
./gradlew :client:shadowJar                           # shaded client jar (gpu/rlhd/probes build from it)
( cd ios/app && ./build-probes.sh )                   # probes.jar (on the Android boot classpath)
android/dev-tools/gpu/stage-android-gpu-jar.sh        # -> client-<ver>-gpu.jar
android/dev-tools/gpu/stage-rlhd-zone-jar.sh          # -> client-<ver>-rlhd.jar  (117HD)
tools/stage-mobile-runtime.sh --no-build --android-only  # fold SHAs into runtime.tag
cd android && ./gradlew :app:assembleRelease          # never bare `assemble`
```

The GPU and 117HD jars are required — omitting them produces an un-bootable APK.

### iOS

See [`ios/README.md`](ios/README.md) for the full pipeline. In short: the iOS JRE
is Mach-O (separate from Android's bionic JRE), the Xcode project is generated
with `xcodegen` from `ios/app/project.yml`, and the jars come from the same
staging scripts as Android.

---

## Architecture

```
 ART / iOS process
   └─ native shell (Kotlin+Compose / ObjC+UIKit) ── SurfaceView / CAEAGL host
        └─ C++ bootstrap: dlopen libjvm → JNI_CreateJavaVM
             └─ embedded OpenJDK 21 (HotSpot, JIT)
                  └─ stock RuneLite (headless) + Caciocavallo AWT
                       └─ headless/* bridge ──► shared verbatim across both ports
```

The native UI thread is **never** attached to the embedded JVM. R8/minification is
intentionally off. The mobile project lives under `ios/`, `android/`, and the
shared `runelite-client/.../headless/` bridge package; the rest of the tree
(`runelite-client/`, `runelite-api/`, …) is upstream RuneLite, updated by merging
`runelite/runelite` and carried with a deliberately small set of additive fork
seams (the headless boot/UI override, bridge hooks in about a dozen upstream
files, and the mobile privacy gates described in `PRIVACY.md`).

---

## Privacy & security

- **No telemetry, no analytics, no crash reporting, no maintainer backend.** Your
  account and gameplay data go only to **Jagex** (to play) and, *only if you opt
  in* — by signing into RuneLite-account sync or enabling the (default-off) Loot
  Tracker / Chat Commands plugins — to the **RuneLite project**. The client also
  makes a few read-only fetches (world list, item prices, runtime config) from
  RuneLite's CDN/API that carry no account data. See [`PRIVACY.md`](PRIVACY.md).
- Report security issues via the private channel in [`SECURITY.md`](SECURITY.md).

---

## License & attribution

This project is **BSD 2-Clause** (see [`LICENSE`](LICENSE)). Bundled and build-time
third-party components — including the GPLv2-with-Classpath-Exception OpenJDK and
Caciocavallo runtimes (fetched at build, not in-tree) and the chat fonts — are
listed in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

RuneScape and Old School RuneScape, and the in-game art the client renders, are the
property of **Jagex Ltd** and are **not** covered by this license. RuneLite streams
that art live from the official game cache; it is not committed here. The native
mobile chrome (tab stones, skill icons, chat-tab buttons) is likewise **not**
committed — exactly like the desktop client, it renders those interface sprites from
your own live game cache at runtime, so no Jagex art ships in this repository.

---

## Risk & disclaimer

A self-built mobile client is an **unapproved third-party client** on live OSRS
(Jagex's approved list is desktop-only). Using it:

- **Risks account bans** (temporary to permanent). **Use a throwaway account,
  never your main.**
- Is **provided as-is**, best-effort, single-maintainer, **with no warranty**, and
  may break after any OSRS update until a new build is published.
- Is **sideload-only** — not on the App Store, Google Play, or F-Droid, and never
  will be. You install it yourself.

This is a personal / technical-achievement project. Use it at your own risk.
