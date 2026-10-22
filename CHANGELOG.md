# Changelog

All notable changes to Rune Mobile are recorded here, for both apps: one release
tag ships the Android APK and the iOS IPA together, and most fixes land in the
shared Java bridge both of them run. An entry that applies to one port says so.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions
follow [Semantic Versioning](https://semver.org/).

The version is set once, in `android/app/build.gradle.kts`. Android's
`versionCode` is derived from it (`major*10000 + minor*100 + patch`) and is
strictly increasing so Android accepts update-in-place; the iOS build stamps the
same version into the IPA, and the AltStore feed shows this file's section for it
as "What's new".

## [Unreleased]

## [0.2.6] - 2026-10-22

### Fixed
- 0.2.5's tap change is withdrawn. A camera drag that paused before it moved walked your character, and a
  press-and-hold did the left-click action before its menu opened. The game acts on the press itself, and
  0.2.5 sent the press while your finger was still down, before anything could tell a tap from a drag. A tap
  now presses and releases together when you lift, as it did in 0.2.4. The same change had made an
  inventory drag let go of the item about a frame after picking it up.
- After a bank or Grand Exchange search, or an "Enter amount" prompt, the prompt's text stayed in the
  chatbox for the rest of the session.
- Android, GPU renderer: a translucent triangle could sit across part of the screen for seconds, and wall
  pieces dropped out for a frame while the camera turned.
- Android, GPU renderer: taking a screenshot (the Screenshot plugin takes one on a level-up) stopped the
  game, and reopening the app showed a black game area.
- iOS, GPU renderer: the login screens froze on their first frame, so a refused login and the game's
  "Try Again" were invisible behind a Play Now button that no longer did anything.
- An account saved in Resizable - Classic logged in with no usable side panel: on Android every tab opened
  into an invisible panel, and on iOS neither the tab stones nor the panel showed. Classic and Fixed now
  get the game's own HUD.
- A position saved in the overlay editor for the chat, the minimap or the side panel fought the app's own
  placement of them every frame. The editor no longer offers those, and clears positions already saved.
- iOS: an audio route change (AirPods connecting, a car stereo) stopped the background keepalive until the
  app next went to the background, so iOS could end a session that should have stayed alive. It now
  restarts at once.
- iOS: every launch left a 172 KB file behind in the app's caches. The ones already there are removed.

### Added
- A "Switch to Resizable - Modern" button at the top of the screen when your account is in Resizable -
  Classic or Fixed. One tap changes the setting the way the game's own Settings > Display does; the
  layout is saved to your account by the game, not by the app.
- The minimap can be moved in the overlay editor, with a "Minimap" handle like the "Chat" one. It keeps
  its place relative to the top-right corner, so a notch or a rotation still places it, and "Reset to
  default" puts it back. On Android, a chat or minimap drag the system cancelled now saves where it was
  left instead of jumping back on the next launch.
- iOS keeps a small record of how each session ended (durations, counters and a memory figure) to tell
  apart what ends a session in the background. It stays on the phone; see [PRIVACY.md](PRIVACY.md).

### Changed
- The GPU renderer's vsync starts on. On Android, frames follow the display instead of a 60 fps sleep
  that drifted against it; iOS keeps its frame limiter, because its presents take no swap interval. A
  change in the plugin panel survives relaunch.
- Android, CPU renderer: frames are presented on the display's refresh instead of after a fixed 16 ms
  sleep, which ran under 60 Hz on a 90 Hz screen.
- Less work per frame: the relocated HUD is re-laid only when it moves, snapshots are republished only
  when they change, and on iOS the frame copy for the screen runs off the main thread.

## [0.2.5] - 2026-10-20

### Fixed
- The client could not log in after the 20 October game update. Jagex changes the game's content and its
  revision most weeks, and a client built against the previous one is turned away at the door with
  `js5connect_outofdate` — in practice a black screen with no useful explanation. This build is rebuilt
  against RuneLite 1.13.4-SNAPSHOT — the line following Release 1.13.3 — and the 20 October game data,
  so login works again. This will happen again after future weekly updates until a new build is
  published; it is the commonest reason to check the releases page.
- Opening the world map left the minimap orbs, the compass and the "Walk here" text painted on top of it.
  A map that takes the whole screen now owns all of it: the layers that would paint over it are held down
  while it is open and handed back exactly as they were found, so a panel the game itself had closed is
  not forced back on.
- The "Walk here" label appeared with nothing touching the screen — while rotating the camera, and after
  a tap had already finished. The client was still tracking a pointer where your finger used to be, and
  the game re-read that point every tick as the world turned underneath it.
- A tap was reported to the game as a one-millisecond hold no matter how long you actually held it, and
  the few pixels of roll a finger makes between landing and lifting were discarded. The press now goes out
  while your finger is still down and closes where it actually left the glass, so the hold the game sees
  is the hold you made. The hold itself is shared code both ports run from the same jar; the
  finger-travel half is separate Android-only plumbing that has not yet been exercised on an Android
  phone, where it falls back to a single position at contact if it does not take.
- A tap that failed mid-flight could leave the left button down, and a held button walks your character.
  The press is now retired first, so it cannot outlive the tap that opened it.
- A press-and-hold that rolled a few pixels was discarded instead of opening the menu.

### Changed
- Hover text is off. A touchscreen has no hover: nothing rests over anything, so a label naming what is
  underneath describes a cursor that does not exist. The official mobile app shows none either. Launch
  with `-Drunelite.hover.text=true` for the desktop behaviour.
- Typed text takes real time to arrive. A thirty-character paste used to land complete inside a single
  game tick, which no keyboard has ever done; it now types out over about a second.

### Removed
- **The idle keepalive — this changes when you are logged out.** The app used to post a synthetic mouse
  event every couple of seconds while you were in its own menus, so the game's idle timer never climbed
  and you were never logged out while sitting in them. That event came from nobody. It is gone end to
  end, and you will now be logged out after the usual idle period exactly as desktop RuneLite does.

## [0.2.4] - 2026-10-18
### Fixed
- Some dialogue boxes still could not be answered, even after 0.2.3 taught the client to recognise
  them. The tap stopped falling through to the world — which is what made it look fixed — but nothing
  was pressed either. The client decides which boxes it can answer itself and which the game must
  answer, and the two lists had drifted apart: quantity boxes ("how many do you wish to make?") and
  the item-reward boxes were on the first list without ever being answerable from it, so a tap on them
  was refused by both paths. Having a name for a box no longer implies the client can drive it, so
  those are now answered by pressing the button under your finger, like every other box the game draws
  for itself. The space bar and the number keys could not answer them either, for the same reason.
- Searching for an item in the Grand Exchange or a bank was unusable. The search box and its results
  are drawn in the chat area, which the mobile layout moves and covers: the prompt was hidden outright
  while the Grand Exchange window was open, and once shown, the results were drawn over bare scenery
  with no backing, leaving item names floating over the world. The search now owns the chat area while
  it is open, is drawn on its own opaque background, and a result is picked by the row you tap rather
  than by a click the window swallows.
- Taps on the game world felt sluggish and sometimes did not register. A tap was only resolved once
  the finger left the glass, and a few pixels of ordinary finger roll was enough for the client to
  decide a tap had been a drag and discard it. The tap is now resolved while the finger is still down
  and committed to where it landed rather than where it lifted, so it survives a roll; the marker ring
  is drawn the moment the finger lifts rather than when the game answers, and takes its colour once
  the answer arrives. On Android the same roll also cancelled the press-and-hold, so a sloppy hold
  never opened the menu; it does now.

### Changed
- The marker ring is drawn for every world tap as it lifts, in the walk colour, and recoloured or
  withdrawn when the game says what the tap hit. Previously no ring appeared until the game answered,
  which for a plain walk — the commonest tap there is — meant a marker arriving a quarter-second after
  the finger had gone, or not at all.

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
