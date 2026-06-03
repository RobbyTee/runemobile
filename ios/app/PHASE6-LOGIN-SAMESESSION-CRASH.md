# Phase 6 — same-session login → JVM crash (diagnosis + fix)

## Symptom
Opening the Jagex login `WKWebView` (`JagexLoginViewController`) and then launching the JVM **in the same
app session** crashes the JVM natively, right after RuneLite init. The proven workaround: after login,
**force-quit + relaunch**, then Start JVM — the session persists in `tokens.json`, so the relaunch reaches
the live game world cleanly. Goal: make login → play one seamless session (no force-quit).

Only bites on a **fresh login / account-switch / re-login after expiry**. Once `tokens.json` exists, every
later session launches with no web view and is unaffected — which is exactly why the force-quit works.

## What is already defended (so the obvious causes are NOT it)
Before `JNI_CreateJavaVM` (`JVMLauncher.m -runProbesWithLog:`):
1. `resetSignalHandlers()` sets SIGSEGV/SIGBUS/SIGILL/SIGFPE/SIGPIPE/SIGTRAP → `SIG_DFL`, so any **POSIX
   sigaction** handler WebKit's JavaScriptCore installed is wiped before HotSpot installs its own.
2. The §6.2 fix clears the **task-level** `EXC_MASK_BAD_ACCESS` Mach exception port, so HotSpot's
   null-check / safepoint faults fall through to its own sigaction handler instead of the debugger.

Both halves of the "two JITs fight over fault handling" story are therefore already covered on a clean
launch. The same-session crash is something subtler. Note: on iOS, WKWebView's JS runs **out-of-process**
(WebContent), so JSC's JIT is NOT in our address space — an in-process JIT-page conflict is ruled out.

## Leading hypotheses
- **H1 jetsam (leading).** WebKit's helper processes (WebContent / GPU / Networking) hold memory; when
  HotSpot then commits its heap (`-Xmx256m`) + 64 MB code cache during RuneLite init (peak footprint), the
  app crosses its jetsam limit and the OS kills it. Death would be **silent — no SIGSEGV**, headroom near 0.
- **H2 exception-port re-arm.** JSC installed a **thread-level** bad-access port (the task-level clear
  wouldn't undo it) or re-armed the task port asynchronously after our clear → HotSpot's fault is stolen →
  the §6.2 SEGV_ACCERR-in-a-fresh-nmethod returns. Death would be a **SIGSEGV** with HotSpot's hs_err.
- **H3 surviving POSIX handler.** A `custom` SIGSEGV/SIGBUS handler survives `resetSignalHandlers()` into
  the post-login launch (e.g. re-installed lazily). Death would be a SIGSEGV handled by the wrong handler.

## Iteration 1 — instrument + cheapest plausible fix (current build)
All diagnostics write to the **crash-safe** fd (`Documents/jvm-stdio.log`, dup2'd UNBUFFERED), so they
survive a silent jetsam kill. `STRIP AFTER DIAGNOSIS` marks every temporary block.

- `dumpFaultState("pre-clear" | "post-clear" | "post-createvm")` in `JVMLauncher.m`: task + this-thread
  `EXC_BAD_ACCESS` ports, live SIGSEGV/SIGBUS/SIGILL/SIGTRAP handlers, and `os_proc_available_memory()` +
  `phys_footprint`.
- `memWatchdog`: samples avail-memory + footprint every 500 ms for 30 s across RuneLite init.
- **Fix attempt A (H2):** also clear the **thread-level** `EXC_MASK_BAD_ACCESS` port on jvm-main.
- **Fix attempt B (H1):** `JagexLoginViewController -teardownWeb` releases the web view (→ WebKit helpers)
  on character-pick / close / dealloc — the in-app equivalent of the force-quit, run before Start JVM.

If iteration 1 just works, the dumps still tell us *which* lever mattered (so we keep that, strip the rest).

## How to run the A/B (you drive device + StikDebug)
Build + install, then capture `Documents/jvm-stdio.log` after each:
- **Run A (clean baseline):** fresh launch → attach StikDebug → **Start JVM**. (No login this session.)
- **Run B (crash case):** fresh launch → **Log in with Jagex** → pick character → attach StikDebug → **Start
  JVM**.

Pull the log:
```sh
xcrun devicectl device copy from --device AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B \
  --domain-type appDataContainer --domain-identifier dev.audaro.helloshell \
  --source Documents/jvm-stdio.log --destination /tmp/jvm-stdio-B.log
```
Also grab the iOS crash/jetsam report (Settings → Privacy → Analytics & Improvements → Analytics Data →
`HelloShell-*` or `JetsamEvent-*`) for run B.

## Reading the result → which hypothesis
| Observation in run B vs run A | Means | Iteration 2 |
|---|---|---|
| `memwatch` avail collapses to ~0, then death, **no** `SIGSEGV` in log | **H1 jetsam** | shrink footprint: confirm teardown reclaimed memory (compare `post-clear` footprint A vs B); if not, force WebContent exit / delay; raise `-Xmx` only with headroom; trim login peak |
| `post-clear` shows a **non-null** task/thread BAD_ACCESS port in B | **H2 ports** | the thread clear may already fix it; if a task re-arm, re-clear immediately pre-CreateVM or install our own catch-all handler |
| a **`custom`** SIGSEGV/SIGBUS handler present at `post-clear`/`post-createvm` in B | **H3 handler** | reset that signal again post-WebKit; identify the installer |
| run B now reaches the game (fix worked) | teardown and/or thread-clear sufficient | keep the lever that changed, strip diagnostics |

## Results log
_(fill per device run)_

| Date | Build | Run | Reached game? | avail@post-clear | footprint@post-clear | BAD_ACCESS port | SIGSEGV handler | Death signal | Notes |
|---|---|---|---|---|---|---|---|---|---|
| | iter1 | A clean | | | | | | | |
| | iter1 | B login | | | | | | | |
