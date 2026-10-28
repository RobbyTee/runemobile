# harness MCP server

A small stdio [MCP](https://modelcontextprotocol.io) server that exposes the
project's dev-tools harnesses as callable tools, so the desktop render / QA loop
can be driven directly instead of by hand. Zero third-party dependencies — pure
Python stdlib for the protocol, plus PIL + numpy (only for `image_diff`,
`ios_sim_screenshot` and `ios_sim_screens`).

## Why `/usr/bin/python3`

The server is pinned to the macOS system Python because that is the interpreter
that ships PIL (11.x) + numpy on this machine — Homebrew's `python3` does not have
them. `image_diff` and `ios_sim_screenshot` import both, and `ios_sim_screens` runs `sim-screens.py` under the
server's own interpreter, which imports them too; everything else is stdlib. If you move machines,
point `.mcp.json` at whatever `python3` has `PIL` + `numpy`
(`python3 -c "import PIL, numpy"` must succeed) and fix the absolute script path.

## Registration

Register it for the project by creating `.mcp.json` at the repo root (gitignored — it holds a
machine-specific absolute path, so each clone writes its own):

```json
{
  "mcpServers": {
    "harness": {
      "command": "/usr/bin/python3",
      "args": ["/path/to/repo/tools/mcp/harness_server.py"]
    }
  }
}
```

The MCP client prompts once to trust a project-scoped server. It is picked up at
session start, so after first adding it, start a fresh session (or re-trust) for
the tools to appear.

## Tools

| tool | needs a device? | what it does |
|------|-----------------|--------------|
| `headless_render` | no (desktop JVM) | run the headless capture (`run-headless*.sh`); returns PNG paths in draw order + markers read from the JVM log (`nullGraphics`, `gameState`, `gameStateProgression`, `exceptionLines`, `errorLines`, `framesByState`). `lastFrame` is the newest `LOGGED_IN` frame when the run got in-game. Files an early-exiting script left from an older run are ignored. `mode=in_game` needs `tokens.json`. Slow (~1-3 min). |
| `image_diff` | no | per-channel PNG diff: `maxChannelDelta`, `meanChannelDelta`, RMSE, `pctPixelsDiffering`, + an inlined red heatmap. The previously-unimplemented T12.1 golden compare. |
| `jvm_bench` | no | compile (`--release 8`) + run `jvmbench/Bench.java` on OpenJDK 21; parsed ops/s per run (`floatbits.A`, `raster.B`, ...). The default JDK must match the major version of the staged port JREs or the tool refuses; pass `jdk=` for a deliberate cross-version run (flagged `runtimeMatch: false`). |
| `glsl_es_validate` | no (needs `glslangValidator`) | run `glsl_es_repro.py`; per-mode GLES 3.00 PASS/FAIL with each mode's glslang errors. `extra_casts` passes `--extra-cast from==to`. |
| `list_evidence` | no | index `evidence/` + headless `out*/` PNG catalogs into JSON, newest-first. |
| `android_screenshot` | yes | `adb exec-out screencap` → PNG. |
| `android_set_hd_config` | yes | live 117HD preset flip (`SET_HD_CONFIG` broadcast) — the FIX-13 A/B knob. |
| `android_fps` | yes | run `fps-measure.sh` for N seconds; LOGGED_IN fps, frame-time, capture pacing percentiles and jitter (`pacingMs`, from `[framebins]`), GC, present cadence, the `session` line (since the first frame), cold start (`coldstartMs`), GPU-time stats, per-second thermal (`thermal_every`), and `abClean: false` when ThermalGovernor moved the 117HD scale mid-capture. |
| `run_verdict` | no | run any in-repo `*-verdict.sh` against a log; returns GREEN/RED/INCONCLUSIVE/NO_VERDICT/LOG_MISSING/UNKNOWN from the script's final `VERDICT:` line, cross-checked against its exit code (`consistent`). `log2` is the second launch for `mobileui-verdict.sh`. |
| `ios_pull_logs` | yes (iPhone, unlocked) | `devicectl` copy of `Documents/jvm-stdio.log` (Debug) and/or `Documents/perf-probe.log` (Release with the `perfprobe` marker) into `ios/app/evidence/device-pull/<stamp>/` (gitignored), each with `fps-measure.sh`'s summary under `perf` (pacing, GC, present cadence, memwatch `mem`, `coldstartMs`); `client_log=true` adds RuneLite's `client.log`, which can name the account. |
| `ios_sim_run` | no (iOS Simulator) | `ios/dev-tools/run-sim.sh` (build, install, launch, press Play) then waits until the launch draws a frame or reaches a game state (`until=LOGIN_SCREEN`); returns the pid, the renderer, the time to `until` and the log paths. `jars`, `build=false`, `from_phone`, `renderer` map to the script's options. ~10 s without a build. |
| `ios_sim_wait` | no (iOS Simulator) | waits on the running launch's `jvm-stdio.log` for a frame or a game state; fails at once when the app has exited or logged a `FATAL`. `since` rejects a log from an earlier launch. |
| `ios_sim_screenshot` | no (iOS Simulator) | `simctl io screenshot` turned upright from Simulator.app's recorded orientation, the Dynamic Island painted out with what the app draws beside it (phone screenshots don't have the island), optionally resized to a phone screenshot's size (`size_like`) for `image_diff`. Saves to `ios/app/evidence/sim/` (gitignored); inlines a preview. |
| `ios_sim_census` | no (iOS Simulator) | the iOS bridge census (`ios/dev-tools/bridge-census/run-census-sim.sh`): every class, method and field the app's native code looks up through JNI, resolved in the app's own VM. Returns the verdict (`IOS_BRIDGE_CENSUS_GREEN`/`_RED`), the counts and each missing lookup with the JVM's error. ~20 s when the build is current, ~10 s with `build=false`. |
| `ios_sim_screens` | no (iOS Simulator) | golden screenshots of the native screens (`ios/dev-tools/sim-screens/sim-screens.py`): launches the app logged out with the game picture covered, opens each screen in `screens.json` through its button's own method, and compares it with its golden in `ios/app/evidence/sim-golden/<model>/<facing>/` (`LandscapeLeft` or `LandscapeRight`, the way the Simulator window is turned). Returns the verdict (`SIM_SCREENS_GREEN`/`_RED`) and, per screen, the share of pixels that differ, the shot and a diff heatmap on a FAIL. `screens` picks a subset, `update=true` writes the goldens. ~40 s with an incremental build. |

### Typical loops

- **Desktop-vs-port render A/B** — `headless_render` (ground truth) → `image_diff`
  against a device/emulator screenshot (crop to a common region if dims differ).
- **117HD perf A/B** (FIX-13) — `android_fps` baseline → `android_set_hd_config`
  (e.g. scale 85, aniso 8) → `android_fps` again → compare.
- **Render-regression gate** — keep a golden PNG, `image_diff` new captures against
  it with a `threshold`, fail on `maxChannelDelta` over budget.
- **iOS UI change without the phone** — `ios_sim_run` (`until=LOGIN_SCREEN`) →
  `ios_sim_screenshot` with `size_like` set to a phone screenshot → `image_diff`
  against it.
- **Shared-bridge change** — `ios_sim_census` (`jars=true`) next to the Android census.
- **iOS native-UI change** — `ios_sim_screens`; on a FAIL, look at the shot and its `.diff.png`, and when the
  change is intended, re-run with `update=true` for those screens.

## Smoke test

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | /usr/bin/python3 tools/mcp/harness_server.py
```

Adding a tool is a single entry in the `TOOLS` registry (description, JSON Schema,
handler) at the bottom of `harness_server.py`.

## Parser tests

The log parsers are pure `parse_*` functions. Their tests feed them the line shapes the
real scripts print:

```bash
/usr/bin/python3 -m unittest discover -s tools/mcp -p 'test_*.py'
```

A changed log format should break a test here before it turns into a silently empty field.
`testdata/framestats.log` is real `headless.FrameStats` output over a synthetic frame sequence. The pacing test
checks that `fps-measure.sh` decodes its `[framebins]` buckets to the same percentiles as the Java `[framehist]`
line, so the bucket formula can't drift between the two.
