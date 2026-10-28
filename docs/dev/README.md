# RuneMobile developer guide

Everything you need to work on RuneMobile: how it is put together, how to build and run each port, the API between
the native apps and Java, how changes are tested and debugged, and how upstream merges and releases work. Start
here, then follow the reading order for what you want to do.

RuneMobile runs the real RuneLite client, plugins and all, on an OpenJDK 21 JVM with a JIT compiler inside two
native apps: an Objective-C/UIKit app for iOS and a Kotlin/Jetpack Compose app for Android. There is no streaming
and no server; the game client runs on the phone. The engine draws off-screen through Caciocavallo, each app blits
the frame to the screen and maps touch back into the client, and a shared Java package
(`runelite-client/src/main/java/net/runelite/client/headless/`) is the bridge both apps call.

> RuneMobile is a personal, technical project. It is not on Jagex's approved-client list, so playing on it carries
> a ban risk: develop and test with a throwaway Jagex account, never your main. See the README's
> [Risk & disclaimer](../../README.md#risk--disclaimer).

## Pages

| Page | What it answers |
|---|---|
| [Setup](setup.md) | What do I install, how do I clone and stage a fresh checkout, and what is the inner dev loop? |
| [Architecture](architecture.md) | How does a frame get from the engine to the screen, and a touch back to the engine? Which thread may do what? Why is it built this way? |
| [The bridge API](bridge-api.md) | How do the native apps call Java, what is the shared contract, and how do I add or change a bridge member safely? |
| [Bridge API reference](bridge-api-reference.md) | Every bridge method, JSON key and `-D` property, with its Javadoc. Generated; never edit it by hand. |
| [iOS port](ios.md) | The UIKit app, JVM boot, JIT and StikDebug, signing, installing, the iOS Simulator, backgrounding. |
| [Android port](android.md) | The Compose app, the native GL/AWT bridge, staging, deploying, touch handling, foldables and tablets. |
| [Renderers](renderers.md) | CPU, GPU and 117 HD: probing and fallback, the GLES ports, shader rewriting, the pinned 117 HD sources. |
| [Testing and QA](testing-qa.md) | Every check from unit tests to the release gate: what each answers, when to run it, and what to run for which change. |
| [Debugging](debugging.md) | Where the logs are on each platform, the JVM and native tools, and a symptom → cause table. |
| [Upstream merges and releases](upstream-release.md) | Merging upstream RuneLite after a game update, the gate ladder, rolling and stable releases. |
| [Gotchas](gotchas.md) | The traps that cost real time, each with its symptom, cause and fix. Read it before your first change. |

## Reading order

| You want to… | Read |
|---|---|
| Get a build running | [Setup](setup.md), then your port's page ([iOS](ios.md) or [Android](android.md)) |
| Understand the codebase | [Architecture](architecture.md) → [The bridge API](bridge-api.md) → [Gotchas](gotchas.md) |
| Change shared Java (`headless/`) | [The bridge API](bridge-api.md), [Testing and QA](testing-qa.md), [Gotchas](gotchas.md) |
| Change a native screen or touch handling | Your port's page, then [Gotchas](gotchas.md) (input and HUD sections) |
| Fix a rendering bug | [Renderers](renderers.md), then [Debugging](debugging.md) |
| Chase a crash, hang or black screen | [Debugging](debugging.md) |
| Merge upstream or cut a release | [Upstream merges and releases](upstream-release.md) |

## Where things live

| Path | What |
|---|---|
| `ios/` | The iOS app (`ios/app/`), its dev tools (`ios/dev-tools/`) and docs |
| `android/` | The Android app (`android/app/`), its dev tools (`android/dev-tools/`) and docs |
| `runelite-client/src/main/java/net/runelite/client/headless/` | The shared bridge: the one first-party package inside the upstream tree |
| `tools/` | Cross-port tooling: staging, the shared contract, git hooks, linters, QA and release scripts |
| everything else | Upstream RuneLite, kept as close to upstream as possible so merges stay clean |

[`PORTS.md`](../../PORTS.md) is the full repo map: what the ports share, what each bundles, and the order each
stages its jars in. List the handful of upstream files RuneMobile edits with:

```sh
git diff --name-status upstream/master...HEAD -- runelite-client
```

## Conventions in one place

- Read versions from the source, not from a doc: the RuneLite version is `project.build.version` in
  `gradle.properties`, and docs go stale at every upstream merge.
- Develop Java on desktop OpenJDK 21 first, then the iOS Simulator, then a device. A compile or a push is not "on
  the phone": verify on the device before calling a runtime change done.
- A change to `headless/` is a change to both ports. Run the contract check and both bridge censuses (see
  [Testing and QA](testing-qa.md)).
- Keep edits to upstream files minimal, and prefer additive bridge code.
- Install the git hooks (`tools/git-hooks/install.sh`) before your first commit.
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) has the pull-request checklist and the linters.

## Keeping this guide current

The [bridge API reference](bridge-api-reference.md) is generated by `tools/shared-contract/gen-bridge-api.py`
from the bridge sources, and the pre-commit hook and the release gate fail when it is stale, so it can't drift.
The other pages are written by hand: when you change behaviour one of them describes, update it in the same
commit. Where a page and the code disagree, the code is right; fix the page.
