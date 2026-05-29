# RuneLite for iOS

A private effort to run **real RuneLite** as a **self-contained native iOS app** — on an **on-device JIT
OpenJDK**, with the **plugin ecosystem unmodified**, on **live OSRS**, and a UI styled like the official
**Jagex OSRS Mobile** app. No streaming, no host machine.

This repository is a **private fork of [`runelite/runelite`](https://github.com/runelite/runelite)** with
`upstream` wired so we ride RuneLite's updates. Everything we add lives under **`ios/`** so it never collides
with upstream — the rest of the tree (`runelite-client/`, `runelite-api/`, …) is RuneLite as-is.

## Start here
- **`ios/REALJVM-DEV-PLAN.md`** — the phased build plan (the thing to follow during development).
- **`ios/REALJVM-FEASIBILITY-SCOPE.md`** — why this approach, the de-risking ladder, the platform facts.
- **`ios/FINDINGS.md`** — the distilled hard-won lessons (what's a dead end and why); read before re-treading anything.
- **`ios/dev-tools/jvmbench/`** — the JVM throughput benchmark (proved a real JVM is 45–184× faster than the old miniJVM).

## Layout
```
ios/
  REALJVM-DEV-PLAN.md            build plan (phases, gates, risks)
  REALJVM-FEASIBILITY-SCOPE.md   scope / feasibility
  FINDINGS.md                    distilled lessons
  dev-tools/jvmbench/            JVM benchmark (Bench.java, RESULTS.md)
  app/                           the native iOS app (built in Phases 1/3/4/6)
runelite-client/, runelite-api/, …   upstream RuneLite (don't fork unnecessarily)
```

## Architecture (three workstreams)
- **W1 — Platform:** OpenJDK 21 with JIT embedded on iOS (TrollStore), + an AWT backend (Caciocavallo + fake-xawt) so the engine's canvas works.
- **W2 — RuneLite headless core:** modify `runelite-client` to bypass the desktop Swing UI, keep the PluginManager + overlays, render into a buffer we own. Developed on desktop OpenJDK first.
- **W3 — Native mobile UI:** a Swift/UIKit shell styled like Jagex OSRS Mobile — touch controls, native plugin config, overlays composited.

## Keeping current with RuneLite
```sh
git fetch upstream
git merge upstream/master        # or rebase our ios/ + W2 changes onto it
```
`origin` = this private repo; `upstream` = RuneLite (push disabled). Real RuneLite also self-updates the OSRS
gamepack at runtime, so we don't track gamepack revisions by hand.

## Note
Personal / technical project. A self-built iOS client is **not on Jagex's approved-client list** for live OSRS;
use a throwaway account, and don't distribute. Build/run conventions and platform detail are in the docs above.
