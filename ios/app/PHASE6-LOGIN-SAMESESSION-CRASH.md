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

## CONFIRMED CAUSE (iter-1, 2026-06-03) — NOT jetsam, NOT BAD_ACCESS
Crash report `HelloShell-2026-06-03-131148.ips` + on-device `jvm-stdio.log`:
- Triggered thread `jvm-main`: `EXC_BREAKPOINT` / `SIGTRAP` at `brk #0x69` inside `BreakGetJITMapping` →
  `os::pd_reserve_memory` → `ReservedCodeSpace` → `CodeCache::initialize()` → `Threads::create_vm` →
  `JNI_CreateJavaVM`. The JIT-mirror breakpoint StikDebug services on a clean launch went **unserviced** →
  fatal SIGTRAP. `jvm-stdio.log` ends at exactly `[JIT26] Requesting 64 MB for JIT mapping`.
- **Not jetsam:** `avail=6113.6MB footprint=30.4MB` at post-clear AND memwatch t=0. 6 GB headroom.
- **Not BAD_ACCESS:** pre-clear task port = `0x18003` (StikDebug, beh=MACH_EXCEPTION_CODES, flav=5);
  post-clear = null. Our task+thread clears worked but are irrelevant — this is the BREAKPOINT mask, which
  §6.2 deliberately leaves with the debugger so the brk can be serviced. The iter-1 thread-level BAD_ACCESS
  clear + web-view teardown did NOT help (expected, given the real mask).
- **WebKit still fully resident:** the crash caught the main thread in
  `WebKit::WebProcessPool::backlightLevelDidChangeCallback` (a CFNotification observer) — `WebProcessPool`
  is alive despite nil'ing the `WKWebView`. WebKit can't be unloaded in-process, and its residency is what
  stops StikDebug from servicing HotSpot's JIT-mirror brk.

→ Force-quit works because a fresh process never loaded WebKit. **Robust fix = process separation**: after
on-phone login, relaunch into a clean (WebKit-free) process before Start JVM (productize the force-quit into
a one-tap reopen; only needed on fresh login/account-switch since `tokens.json` persists). A fully-seamless
in-process fix would need to defeat WebKit's interference with the debugger brk path — uncertain; the next
datum would be the StikDebug-side `[rl-jit]` console log, to see whether the script even receives the brk
(if not → exception-routing, likely unfixable in-process; if yes but `_M`/forward → script-side, fixable).

## Iteration log
| Date | Build | Run | Reached game? | Death | avail | Cause |
|---|---|---|---|---|---|---|
| 2026-06-03 | iter1 | B: logout→login→Start JVM | NO | SIGTRAP @ brk#0x69 (CodeCache init) | 6113 MB | WebKit resident → JIT-mirror brk unserviced |
| 2026-06-03 | iter2 | StikDebug script UPDATE 18 (scan all threads for brk#0x69) | NO | identical: SIGTRAP @ brk#0x69, same 16 log lines | 6116 MB | NOT a wrong-thread mishandle — brk isn't reaching the script at all |
| 2026-06-03 | iter3 | app-side restore BREAKPOINT port to debugger | NO | identical SIGTRAP @ brk#0x69, 20 lines | 6113 MB | NOT port theft — StikDebug owns ALL masks; the debugger gets the brk but doesn't service it |

### iter3 finding — the port-theft theory is DEAD (and a Mach gotcha)
pre-clear: `BAD_ACCESS=0x1d803`, `BREAKPOINT=0x1d80f`, `BAD_INSTRUCTION=0x1d81b`, all `beh=0x80000001`
(MACH_EXCEPTION_CODES) `flav=5`. These are all the **StikDebug debugger** (it claims EXC_MASK_ALL on one
handler). The differing port *names* per mask, and the fact that they change every read (post-clear
`BREAKPOINT=0x1d833`), are because `task_get_exception_ports` allocates a **fresh send-right name** each
call — so comparing names across masks/calls is meaningless (the iter3 restore was a no-op chasing a ghost).
Conclusion: WebKit does NOT steal any exception port. The brk #0x69 IS routed to StikDebug; it simply is
not serviced when WebKit is resident — a debugserver/script-level wall, not an app-side port-routing bug.
Three targeted fixes (thread-level BAD_ACCESS clear + web-view teardown; script all-thread scan; BREAKPOINT
restore) all failed to move it. Further progress would require seeing StikDebug's `[rl-jit]` console
(UI-only, not capturable here) and likely changing StikDebug/debugserver itself.

## VERDICT: in-process seamless is blocked; ship the relaunch
The reliable fix is **process separation** — relaunch into a fresh (WebKit-free) process after login, before
Start JVM. It is the force-quit the user already confirmed works, productized into one reopen tap; only on
fresh login/account-switch (tokens.json persists). Build entirely app-side, no device debugging.
Speculative future seamless idea (not attempted): **JVM-first** — boot the VM to RuneLite's own login screen
BEFORE any WebKit, then open the login web view (the one brk #0x69 is already serviced; the debugger has
detached), and inject the minted session into the running client via a JavaBridge call instead of
tokens.json-at-startup. Needs re-clearing EXC_BAD_ACCESS after the web view loads + a runtime login-injection
path in the Java layer — a larger W2 change, uncertain.

### iter2 finding → iter3 hypothesis (the BREAKPOINT port)
- StikDebug **is** attached: pre-clear `task BAD_ACCESS port=0x1da13 beh=0x80000001 flav=5` (the debugger's
  handler). Updated script confirmed on device (UPDATE-18 marker present). Yet the scan changed nothing →
  the brk #0x69 never produces a stop for the script.
- The diagnostics only ever dumped **BAD_ACCESS**, never **BREAKPOINT** — the mask the brk actually uses.
  New hypothesis: WebKit displaced the debugger on the task **EXC_BREAKPOINT** port specifically (it left
  BAD_ACCESS with StikDebug), so the brk is delivered to WebKit, not debugserver → never serviced.
- **iter3 fix (app-side, JVMLauncher.m):** dump BREAKPOINT + BAD_INSTRUCTION owners, and
  `restoreBreakpointPortToDebugger()` re-points the task BREAKPOINT port at the handler StikDebug still
  holds for BAD_ACCESS, right before the §6.2 BAD_ACCESS clear. No-op on a clean launch. If the brk then
  services, `jvm-stdio.log` sails past `[JIT26] Requesting 64 MB`. If BREAKPOINT already == the debugger's
  port, the hypothesis is wrong and the brk-routing wall is real → pivot to relaunch.
