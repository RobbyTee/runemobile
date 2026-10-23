# Simulator screen goldens

Screenshots of the iOS app's native screens, taken in the iOS Simulator and compared with a golden copy of each.
A change that moves, recolours or rewords anything on one of these screens turns the run RED and points at the
pixels it changed.

```sh
ios/dev-tools/sim-screens/sim-screens.py                # build, install, check the screens in screens.json
ios/dev-tools/sim-screens/sim-screens.py settings hub   # only these
ios/dev-tools/sim-screens/sim-screens.py --update       # write this run's shots as the goldens
ios/dev-tools/sim-screens/sim-screens.py --no-build     # reuse the last simulator build
```

Run it with `/usr/bin/python3` (it needs PIL and numpy, like `tools/mcp`). It ends with
`VERDICT: SIM_SCREENS_GREEN` (exit 0) or `_RED` (exit 1). The default set takes about 40 s with an incremental
build. A new screen, or a new Mac, has no golden yet; the run says `NO_GOLDEN` until `--update` writes one.

## How it works

1. `../run-sim.sh --no-launch` builds and installs the simulator app (CPU renderer).
2. The script launches it with `-RMSkipLogin YES -RMBlankGame YES -RMScreenDriver YES` and waits for the login
   screen.
   - `-RMSkipLogin` gives the launch an empty tokens file of its own, so it starts logged out even when the
     simulator holds a session (`run-sim.sh --desktop-login`), and the real one is left alone.
   - `-RMBlankGame` covers the game picture with the screen's background colour, so the login screen's animation
     behind a screen doesn't show in its shot. The native chrome on top of the game stays visible.
   - `-RMScreenDriver` has the display tick read `Documents/screen-cmd`.
3. For each screen the script writes `<seq> <screen>` to that file. The app closes whatever it opened last, opens
   the screen through the same method its button calls (`ViewController.m`, `simOpenScreen:`), waits for the
   presentation to finish, and logs `[screen] ready <seq> <screen>` (or `failed`, with the reason) to
   `hello-shell.log`. The script then waits the screen's `settle` time and takes the screenshot.
4. Each shot is compared with `ios/app/evidence/sim-golden/<simulator model>/<facing>/<screen>.png`, where
   `<facing>` is `LandscapeLeft` or `LandscapeRight`, the way Simulator.app has the window turned. A screen passes when
   at most `maxPctDiffering` % of its pixels differ by more than `threshold` in any channel. The defaults are 2
   and 0: the compositor's antialiasing on rounded corners can come out one level off between launches, while a
   changed word differs by about 200. A failing screen gets a `.diff.png` heatmap next to its shot.

Shots, heatmaps and `summary.json` land in `ios/app/evidence/sim-screens/<stamp>/`.

All of the driver code is compiled only into simulator builds (`TARGET_OS_SIMULATOR`).

## Screens

`screens.json` lists them in order: `settings`, `about`, `plugins`, `edit-controls`, `overlays`, `profiles`,
`infoboxes`, and the plugin side panels `panel:Info`, `panel:Notes`, `panel:Hiscore` and `panel:Grand Exchange`
(`panel:<rail tooltip>` opens any panel on the rail). `hub` shows the live Plugin Hub list, which changes whenever
the hub publishes, so it is marked `network` and runs only when named.

Panels that only exist in game (`World Hopper`, `Loot Tracker` and the like) are left out, because the run is
logged out.

## Goldens are local

Goldens live under `ios/app/evidence/`, which is not tracked. They depend on the simulator model and the Xcode
simulator runtime, and on the simulator's own RuneLite config: which plugins are on, which config profiles exist,
and what the Notes panel holds. Change any of those (or update Xcode) and re-run with `--update`, after checking
the shots show what they should.

The device's window in Simulator.app has to be in landscape. `simctl` captures in the device's own portrait frame,
and the shots are turned by the rotation Simulator.app records for each window, so a device still in portrait
stops the run before the first shot. Turn it once (Device > Rotate Left). The Dynamic Island shows on the side the
top of the device points to, so the goldens are kept per way the window faces: turn it the other way and every
screen reports `NO_GOLDEN` until you turn it back or take a set for that side with `--update`.

Check them by eye the first time. A golden is only as good as the shot it was taken from.

## Plugin sweep

`plugin-sweep.py` drives the same app the same way, but has no goldens: it opens every plugin's settings screen and
every side panel on the rail, and checks each one for what is wrong with it rather than for what changed.

```sh
ios/dev-tools/sim-screens/plugin-sweep.py                                  # build, install, sweep everything
ios/dev-tools/sim-screens/plugin-sweep.py --no-build --only panels         # only the side panels (or: config)
ios/dev-tools/sim-screens/plugin-sweep.py --no-build "config:Agility" "panel:Notes"   # only these
```

1. `inventory` (a driver command that opens nothing) has the app write `Documents/screen-inventory.json`: the
   plugin list exactly as the plugin panel reads it, and the rail's panels. The script reads it until two reads in
   a row agree, since the rail fills in after boot.
2. `prepare` has the app turn on what `sweep.json` names, so the sweep also reaches panels the simulator's config
   hides: plugins that are switched off (Loot Tracker, Party) and settings that hide a panel's button (Kourend
   Library shows its button only inside the library). The app first saves what it changes to
   `Documents/screen-restore.json`; `restore` at the end puts it back, and the script checks that the plugin list and
   the rail match what they were. Both answer only once the plugins read back as started or stopped, the settings
   writes have run and a config flush behind them has written the profile. `restore` deletes the file only then, so if
   a sweep dies in between, the app's next simulator launch restores it. `prepare` refuses a setting that has no
   stored value, since a restore could only write one back. `--only config` skips both.
3. For every plugin with settings it sends `config:<plugin>`: the app opens the plugin list and then that row's
   settings screen the way the gear button does (a tap on the row for the config-only RuneLite and Chat Color
   rows). For every panel on the rail it sends `panel:<tooltip>`. It closes each screen (`-`) before the next.
4. Before each `ready` the app logs `[screen] info <seq> <json>`: which screen is up, how many texts, cells and
   images it shows, the area its content takes, and what the screen itself knows (a settings screen's item count,
   a rendered panel's frame size and how much of it isn't background).

A screen passes when the app reports it ready; when no `[bridge-fault]` or `[json-fault]` line reaches
`jvm-stdio.log` or `hello-shell.log` between opening and closing it; and when it isn't blank: a settings screen lists
at least one setting, a rendered panel has drawn a frame that isn't one flat colour, and the content area in the
screenshot shows more than its background. A settings screen also fails when another plugin in the list reports the
same config group: the screen is opened by group, so one of the two shows the other's settings. Other log lines that aren't known noise are listed with the screen but
don't fail it; `sim-gate.sh`'s log scan judges those.

A panel that `sweep.json` turned on is marked so in the results. A new plugin with a panel that is off by default needs
a line there, or the sweep won't see it. Shots (full size for a screen that failed, half size otherwise), `inventory.json`,
`summary.json` and a `results.md` table land in `ios/app/evidence/plugin-sweep/<stamp>/`. The last line is
`VERDICT: PLUGIN_SWEEP_GREEN` (exit 0) or `_RED`. `sim-gate.sh` runs it after the goldens.

## What it does not check

- The game picture itself. Use `ios_sim_screenshot` and `image_diff` (`tools/mcp`) for that, against a phone
  screenshot or a desktop render.
- Touch handling and gestures. The driver calls the same methods the buttons do, but it doesn't tap.
- Phone-only behaviour: the device's GPU driver, performance, the StikDebug JIT path.
