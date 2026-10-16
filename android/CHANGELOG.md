# Changelog

All notable changes to the Android app are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The `versionName` in
`app/build.gradle.kts` is the semver below; `versionCode` is derived from it
(`major*10000 + minor*100 + patch`) and is strictly increasing so Android
accepts update-in-place.

## [Unreleased]

## [0.2.3] - 2026-10-16
### Fixed
- Dialogue boxes the game opens for itself could not be answered at all. Quest confirmations, "are you
  sure you want to destroy this", crate and reward boxes — anything the game puts in the chat area that
  isn't one of the handful of dialogue layouts the client knows by name — were not treated as dialogues,
  so the native chat veneer stayed on top of the box and a tap on Yes or No either went to the chat or
  resolved against the scene behind it, walking the character away and ending the conversation. Any box
  the game opens in the chat area is now recognised as a dialogue, covered, and answered by pressing the
  button under your finger rather than clicking through it.
- Tapping near the top of a dialogue switched a chat channel instead of answering it (Android). The
  native chat tab row is drawn across the top of the chat area, which is exactly where the dialogue
  appears, and it took the tap before the box underneath could see it. The row now steps aside for as
  long as a dialogue is open, as it already did on iOS.
- A tap meant for one dialogue could answer the next one. A tap waiting on the game thread survived a
  loading screen or a world hop, so it could land on whichever box happened to be open when the game
  came back. A waiting tap is now stamped with the frame it was made on, and dropped if it hasn't been
  applied by the next one.

### Added
- The app now says at launch that it is built for the Resizable - Modern layout, with a "don't show
  again" for anyone who has already set it. The touch controls, chat and overlays are laid out against
  that layout and no other, which was true but written down nowhere.

## [0.2.2] - 2026-10-15
### Fixed
- The renderer could ignore the setting entirely. Marker files used by development and test runs were
  read by every build, so an empty leftover file outranked the saved preference: the panel showed CPU
  selected while every launch ran 117HD, with nothing on screen able to explain it. Release builds now
  decide the renderer from the preference alone, and those files are honoured only in development
  builds. The same files could also skip the sign-in step that normally comes first.

### Changed
- The game's boot thread no longer wakes four times a second for the whole session. It was polling
  counters that only move when frame capture is on — a development-only mode — and printing a line a
  second that a release build discards. It now sleeps quietly unless there is something to record,
  which saves battery and avoids pointless disk writes during a long session.

## [0.2.1] - 2026-10-15
### Fixed
- Tapping a dialogue walked the character instead of answering it. When the engine draws its own
  dialogue the native parchment is suppressed, so the touch never reached the overlay and fell back
  to a positional click, which resolved against the scene behind the dialogue box — moving the player
  to the tile under the text and ending the conversation by walking away. Dialogue taps now drive the
  engine's own continue and option handlers, so a tap advances the conversation or selects the row it
  landed on. Option rows that miss every row are consumed rather than answered with a blind advance;
  make-X and reward boxes, which the client does not model, keep their direct click.
- Answering a dialogue no longer counts as being idle. The positional click it replaced was input the
  game's idle timer could see, so a long conversation could trigger an AFK logout mid-sentence.
- The renderer tiles showed the saved preference rather than what the next launch would actually run.
  A device marker or a TV default outranks the preference at boot, so the panel could show CPU
  selected while 117HD ran. The tiles now reflect the next launch, and say so when a pick is being
  overridden.

### Internal
- The build now fails if the staged client jar is missing the dialogue handlers. The app resolves them
  optionally so an older jar still boots, which meant a stale jar produced dialogue taps that silently
  did nothing at all.

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
