# iOS bridge census

Checks that every class, method and field the iOS app's native code looks up through JNI resolves in the jars the
app ships. On a phone, a lookup that stops resolving (a bridge method renamed, or its signature changed) does not
fail loudly: most bridge lookups are optional, so the call quietly becomes a no-op. This census turns that into a
RED.

```sh
ios/dev-tools/bridge-census/run-census-sim.sh          # build, install, run, adjudicate (~20 s when the build is current)
ios/dev-tools/bridge-census/run-census-sim.sh --jars   # restage the jars first: use this after a Java change
```

It ends with `VERDICT: IOS_BRIDGE_CENSUS_GREEN` (exit 0) or `_RED` (exit 1). Output lands in
`build/bridge-census/`.

## How it works

1. `extract-lookups.py` reads every C-family source the app target compiles (from `ios/app/project.yml`) and lists
   each `FindClass`, `GetStaticMethodID`, `GetMethodID`, `GetStaticFieldID` and `GetFieldID` with its class, name
   and signature. When the class or the name is a parameter (`callMain`, `accountInvokeVoid`), the values come from
   the call sites. A lookup it can't resolve stops it with an error, so nothing is left off the list unnoticed.
2. `run-census-sim.sh` builds the simulator app (`../run-sim.sh --no-launch`), copies the list into the app's
   Documents and launches it with `-RMBridgeCensus bridge-census.tsv`.
3. In the app (`JVMLauncher.m`, `simBridgeCensusRun`, simulator builds only), a thread waits for the client's first
   frame, then resolves every entry in the running VM, the same way the app's own lookups do, and writes
   `Documents/bridge-census-result.txt`: `OK` or `MISSING` (with the JVM's error) per entry, then a `DONE` line.
4. `bridge-census-verdict.sh <result> [list]` adjudicates. GREEN needs every entry resolved and the result to cover
   exactly the lookups the source makes now; a result from an older build is RED. Without a list it extracts one from
   the current tree.

One run covers every renderer: CPU, GPU and 117HD share one classpath. The L1 test probe's own classpath is left
out.

## What it does not check

- `RegisterNatives` tables (the GL bridge's native methods). GPU and 117HD launches log their registration result
  (`RouteC_registerGLNatives rc=`).
- Whether a method behaves the same, only that it resolves.
- The Android port. That is `android/dev-tools/bridge-census/`, which checks a fixed contract against the jar on a
  desktop JDK.
