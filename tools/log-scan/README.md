# Log scan

Sorts a run's warnings, errors and exceptions into known noise and new problems. A line on the known-noise list
passes; anything else turns the scan RED. It reads the logs of all four ways the client runs:

```sh
tools/log-scan/log-scan.py --sim <Documents>/jvm-stdio.log --sim <Documents>/hello-shell.log   # iOS Simulator
tools/log-scan/log-scan.py --phone ios/app/evidence/device-pull/<stamp>/jvm-stdio.log         # ios_pull_logs
tools/log-scan/log-scan.py --android pixel.txt     # adb logcat -d -v threadtime -s 'jvm-stdout:*' > pixel.txt
tools/log-scan/log-scan.py --desktop ios/dev-tools/headless/out/run-cacio.log                  # desktop CACIO
```

Any mix of sources works in one run. `--json <file>` writes the whole result, and `--suggest` prints a noise-entry
stub under each new signature. It ends with `VERDICT: LOG_SCAN_GREEN` (exit 0) or `_RED` (exit 1, or 2 when an
input is missing, empty, or not the kind of log named). Plain `python3`, no packages. `ios/dev-tools/sim-gate.sh`
runs it over every launch it makes.

A Release Android build sends only the perf-probe lines to logcat, so a Pixel capture from one has nothing much to
scan. Use a Debug build.

## What counts as an event

- a logback `WARN` or `ERROR` line (RuneLite's own log format);
- a `java.util.logging` `SEVERE:` or `WARNING:` line;
- an exception printed on its own (`java.lang.IllegalStateException: ...`, `Exception in thread ...`);
- a `[bridge-fault]` or `[json-fault]` line, a fatal VM error, a HotSpot `VM warning:`, Caciocavallo's
  `NOT YET IMPLEMENTED`;
- a native shell line that says `FAILED`, `failed`, `did not survive` or `boot-failure`.

The lines after an event belong to it: the throwable it carries, the stack frames, `Caused by:` lines and the rest
of a multi-line message. Events are grouped by signature, which is the event's first line and its exception chain
with the timestamp and thread removed and numbers and hashes blanked. The same problem on another thread or at
another time is one signature.

New signatures are listed by what logged them, in the order they first appeared, because the first new line is
usually the cause. A failing GPU shader is logged one source line per `ERROR`, so one logger can account for
hundreds of signatures; the report shows the first three and a count.

## Shader errors

The scan also counts `ERROR`-level events that mention a shader (`shaderErrors=N`, per file in the JSON). The
noise list can excuse one from the new list, but not from that count, and the simulator gate requires 0.

## The known-noise list

`known-noise.json`. Each entry has:

- `id`;
- `header`, a regex searched in the event's first line, with the timestamp and thread removed and runs of spaces
  squashed (`WARN n.r.client.Foo - message`);
- `cause` (optional), a regex searched in the throwable and each `Caused by:` line. Use it: a header alone would
  also excuse the same line with a different exception behind it;
- `sources` (optional): `sim`, `phone`, `android`, `desktop`. It defaults to all. Narrow it when a line is only
  expected on some (the phones have no Java Sound line, the simulator does);
- `reason`: what the line is and why it is expected. An entry without one is refused.

An entry that names an unknown field, repeats an id or has a bad regex stops the scan, so a typo can't quietly
excuse nothing. Keep the regexes narrow, and take an entry out once its cause is fixed. The report lists entries
that didn't match anything in the run, which is how a fixed one shows up.

Run the tests after changing the scanner:

```sh
python3 -m unittest discover -s tools/log-scan -p 'test_*.py'
```
