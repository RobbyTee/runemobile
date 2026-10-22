# Residency: record why each iOS session ended

**Status: on `master`, waiting on ordinary use.** Built for the iPhone; not yet run on it from
`master`. The writer and the pull path first ran on the phone from the
`jit/no-localdevvpn-no-stikdebug` branch (2026-10-16/17), where they were written. They are on
`master` now because none of it depends on that branch's JIT work.

## Why it exists

CS_DEBUGGED is per-process and sticky, so a process that never dies never needs the StikDebug
attach again. Process death, not the attach, is what costs the user the whole JIT routine. The app
already works hard to stay resident (the `audio` background mode, a silent `AVAudioEngine`, the
PiP pump, a background task), but nothing recorded whether that works or what ends a session
when it doesn't. The plausible causes call for opposite fixes, so the first job is to find out
which one actually happens.

## What is on master

- **`RMResidency`** (`ios/app/RuneMobile/RMResidency.{h,m}`) rewrites a small record
  (`Documents/residency.plist`) every 15 s: timestamp, foreground/background, jetsam headroom and
  its low-water mark, memory warnings, whether JIT was attached, whether the idle gate released the
  keepalive, and each keepalive restart with its reason. At the next launch it turns that record
  into one `[residency]` log line and appends one JSON line to `Documents/residency-history.log`
  (capped at 200 sessions). The history is written in Debug and Release alike.
- **`ios/dev-tools/residency.py`** pulls the history over `devicectl`, merges it into
  `build/residency/history.jsonl` (deduplicated by session start, so the archive outlives the cap)
  and prints the proportions. `--self-test` checks the parser, arithmetic and merge with no phone;
  `--file` reads a copy already on disk. If the history is missing, it asks the phone whether the
  live record exists, which says whether the installed build predates the record or simply hasn't
  reported a session yet.
- **Keepalive recovery** in `ViewController.m`, which came over with the record because the record
  counts its restarts:
  - an audio **route change** (AirPods disconnecting, a car stereo dropping) restarts the engine;
    before, only the next trip to the background did;
  - a **media services reset** rebuilds the engine and its observers from scratch, as Apple
    documents; before, the keepalive was gone for the rest of the process;
  - a **15 s watchdog** restarts an engine found stopped for any other reason;
  - an **interruption** now restarts through the same path, so it no longer fights the idle gate,
    touches the log off the main thread, or reports a restart that failed.

  Every one of these checks the KEEPALIVE-1 idle gate first: once the gate has stopped the
  keepalive on purpose (logged out for 10 minutes in the background), nothing restarts it. The
  watchdog stops when the JVM thread returns, so a dead session isn't kept resident.

## How to collect

1. Install a current build (`ios/dev-tools/remote-build.sh`) and play normally for a few days.
   Nothing needs to be turned on. The first history line lands at the launch after a session
   ends: two launches on a fresh install.
2. `python3 ios/dev-tools/residency.py` pulls, archives and prints. `-v` adds each session's
   sentence.

## What the data decides

| dominant class | the lever |
| --- | --- |
| `jetsam-suspected` | the `-Xmx1024m` heap in `ios/app/RuneMobile/JVMLauncher.m` |
| `background-unknown` | the keepalive is losing the process: the restart paths in `ViewController.m` |
| `idle-gate` | `kBgIdleStopSeconds` in `ViewController.m`: by design; longer trades battery for residency |
| any, with `rearms > 0` | the keepalive stopped and was restarted; the reason tag (`interruption`, `route-change`, `media-reset`, `watchdog`) says which event, and they don't share a fix |
| `foreground-unexpected` | not residency at all: read the crash reports |

Don't tune any of these before the data says which one dominates.

## Gate status

- `python3 ios/dev-tools/residency.py --self-test`: 28 checks pass.
- The iOS app builds for the device with the record and the keepalive changes.

## Open risks

- **The classes are inferences over a 15 s sample, not kernel reports.** A memory warning does not
  prove jetsam, and jetsam can land between samples with no warning. Ground truth is the
  `JetsamEvent-*.ips` reports under Settings > Privacy & Security > Analytics & Improvements >
  Analytics Data, plus `Documents/jvm-stdio.log`, which is written unbuffered so it survives a
  silent kill. Confirm there before tuning.
- **Durations are floors except for a clean exit.** They are measured to the last sample, and a
  suspended process fires no timer, so a session that slept for hours reports only its awake time.
- The keepalive changes have not run on the phone from `master`. Worth watching in the device log:
  `[pip] keepalive was stopped by ... - restarted` lines, which should be rare.
