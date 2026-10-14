# Changelog

All notable changes to the Android app are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The `versionName` in
`app/build.gradle.kts` is the semver below; `versionCode` is derived from it
(`major*10000 + minor*100 + patch`) and is strictly increasing so Android
accepts update-in-place.

## [Unreleased]

## [0.2.0] - 2026-10-13
### Fixed
- 117HD could not load at all and so never appeared in the plugin panel. Upstream began requiring a
  `@PluginDependency` target to expose its services; `EntityHiderPlugin` never did, so instantiating
  `rs117.hd.HdPlugin` failed outright. Entity Hider now shares itself and its config with dependents
  (`rs117.hd.scene.LightManager` injects both), so the renderer loads and the panel row appears.
- RuneLite-account login failed with a JSON parse error. The API base was pinned to a retired
  `api.runelite.net` version; it tracks the build version again, which also restores Grand Exchange
  price lookups.
- A failed API call now reports its status code and URL instead of surfacing as
  "Expected BEGIN_OBJECT but was STRING", which named neither.

### Changed
- Rebased onto upstream RuneLite `1.13.2-SNAPSHOT` (tag `runelite-parent-1.13.1`), bringing
  `rev241` (2026-10-07) game data: new GameVals/Legacy ID classes, item variations, and the
  Sailing skill-calculator data.
- Grand Exchange offer price/spent widened `int` -> `long` upstream; the native GE panel now
  parses the wider values (`optLong`), so a total past `Int.MAX_VALUE` no longer truncates.
- Upstream removed its Grand Exchange crowdsourcing upload outright, so the mobile privacy gate
  that suppressed it is gone as redundant. No GE offer data leaves the device, as before.
- The in-app Discord button and the embedded client's invite pointed at RuneLite's server. They now
  point at this project's own Discord: RuneLite does not support this unofficial fork, so anyone who
  followed the old link was turned away.

## [0.1.0]
Initial pre-release of the native Android client: real RuneLite headless on an
embedded arm64 OpenJDK 21, CPU/base-GPU/117HD renderers (caps-probed), Jagex
OAuth + RuneLite-account sync, native HUD/chat/tab-grid veneers, plugin panel.

### Notes
- Unofficial, sideload-only — not affiliated with or endorsed by Jagex/RuneLite;
  use at your own risk including account ban. See README.
- Release signing: a dedicated key via `keystore.properties` when present, else
  the debug key (see `keystore.properties.example`).

[Unreleased]: https://github.com/audaro/runemobile/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/audaro/runemobile/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/audaro/runemobile/releases/tag/v0.1.0
