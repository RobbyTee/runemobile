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
4. Each shot is compared with `ios/app/evidence/sim-golden/<simulator model>/<screen>.png`. A screen passes when
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

Check them by eye the first time. A golden is only as good as the shot it was taken from.

## What it does not check

- The game picture itself. Use `ios_sim_screenshot` and `image_diff` (`tools/mcp`) for that, against a phone
  screenshot or a desktop render.
- Touch handling and gestures. The driver calls the same methods the buttons do, but it doesn't tap.
- Phone-only behaviour: the device's GPU driver, performance, the StikDebug JIT path.
