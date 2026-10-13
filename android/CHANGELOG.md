# Changelog

All notable changes to the Android app are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The `versionName` in
`app/build.gradle.kts` is the semver below; `versionCode` is derived from it
(`major*10000 + minor*100 + patch`) and is strictly increasing so Android
accepts update-in-place.

## [Unreleased]
### Changed
- Rebased onto upstream RuneLite `1.13.2-SNAPSHOT` (tag `runelite-parent-1.13.1`), bringing
  `rev241` (2026-10-07) game data: new GameVals/Legacy ID classes, item variations, and the
  Sailing skill-calculator data.
- Grand Exchange offer price/spent widened `int` -> `long` upstream; the native GE panel now
  parses the wider values (`optLong`), so a total past `Int.MAX_VALUE` no longer truncates.
- Upstream removed its Grand Exchange crowdsourcing upload outright, so the mobile privacy gate
  that suppressed it is gone as redundant. No GE offer data leaves the device, as before.

## [0.1.0]
Initial pre-release of the native Android client: real RuneLite headless on an
embedded arm64 OpenJDK 21, CPU/base-GPU/117HD renderers (caps-probed), Jagex
OAuth + RuneLite-account sync, native HUD/chat/tab-grid veneers, plugin panel.

### Notes
- Unofficial, sideload-only — not affiliated with or endorsed by Jagex/RuneLite;
  use at your own risk including account ban. See README.
- Release signing: a dedicated key via `keystore.properties` when present, else
  the debug key (see `keystore.properties.example`).

[Unreleased]: https://github.com/audaro/runemobile/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/audaro/runemobile/releases/tag/v0.1.0
