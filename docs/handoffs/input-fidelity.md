# Input fidelity — the synthetic stream stops inventing things

**Status:** all four items landed and **verified on device** (iPhone 17 Pro Max, iOS 27.2, Debug build,
2026-10-20): the four checks below were walked through on the real client and all read correctly.
**Commits:** `d54da5f6f9`, `7b2ef910ed` (item 1) · `455a634bf0` (keepalive) · `e32bae44c2` (item 3) ·
`04193e7d01` (item 2) · `a48430708d` (item 4) · `f5908f7fc5` (coalescing) · `c4aade89ab` (review).

## The problem this series fixed

The bridge synthesizes AWT input from touch. Several parts of what it synthesized described things the user
never did — not approximations of real input, but fabrications with no referent:

| # | What was fabricated | Now |
|---|---|---|
| 1 | Every tap reported a **1ms hold**: press and release went out together at the lift. | Press at gate accept, release at the real lift. The delta is the finger's hold. |
| 2 | A tap was **one point**. The finger's travel was dropped; the engine saw a move at contact and nothing until the release. | Real samples stream as `MOUSE_DRAGGED`; the release closes at the lift point. |
| 3 | Every tap ended with a `MOUSE_MOVED` to **(−1,−1)** — a point no pointer can occupy. | Park is `MOUSE_EXITED` alone, carrying the point the finger left from. |
| 4 | Keys went out at `t`, `t+1`, `t+2` in one EDT drain; a 30-char paste arrived inside **one game tick**. | Each key is really held (45–85ms) and really spaced (25–55ms press-to-press). |
| — | An **idle keepalive** posted activity the user never produced, defeating the engine's idle-logout. | Deleted from all three codebases. |

## What each change cost, and why it was paid

- **Item 3 — the hover persists.** The `(−1,−1)` move existed for a real reason: the engine does not clear
  its tracked position on `MOUSE_EXITED`, so a move to a point that picks nothing was the only way to end a
  hover. Dropping it means a tapped entity stays lit and can re-light when a zoom re-projects it. That is
  what a desktop client does — a mouse stays where you let go of it — so it is a regression only against
  the fabricated version of ourselves. **If the lingering label reads badly on device, the fix is to stop
  drawing the label, not to move the cursor somewhere the finger never was.**
- **Item 2 — two deliberate limits.** Samples stream only *during* the press (a move during the settle
  would push the engine's cursor off the point the gate is resolving, so the gate could never accept and
  the arm would fail open — the latency arming exists to remove), and only *inside the 3D viewport* (over
  an interface a held left button is the engine's drag-to-rearrange, and 18pt of allowed roll is wider than
  an inventory slot, so replaying it would invent an item drag). `inWorldViewport` fails closed.
- **Item 4 — the dwell is a stand-in, and says so.** iOS hands us committed text, never a key down or up,
  so there is no measurement to report. What was wrong before was not the missing measurement but the
  reported one that never happened. Ranges, not constants, because a constant dwell is its own fabrication.

## Mechanics worth knowing before touching this

- **`postPointer(q, e)`** is the single seam every positioned `MouseEvent` goes through. It records
  `lastPointerX/Y` so the park's exit can report a real point. *Add new pointer posts through it*, or the
  "we never name a coordinate the finger was not at" property quietly stops being true.
- **`DRAG_STREAM_LOCK`** serialises samples (native UI thread) against press/release/retire (client
  thread). It is what guarantees no drag is posted after the release that ended it.
- **The viewport bound is a snapshot, not a live read.** `postPressOnly` reads the viewport rect on the
  client thread when the press lands; `onTapMove` bounds every sample against that snapshot. Do not call
  `inWorldViewport` from `onTapMove` — it runs on the native UI thread and must not read engine geometry
  off-thread (same reason `textInputOpen` is a snapshot).
- **`EventQueue` coalesces pending mouse-motion events.** A burst of samples posted faster than the EDT
  drains folds into one. This is intended — a desktop toolkit does the same — and nothing is lost, because
  `streamPoint` is written as each sample arrives rather than read back out of the queue.
- **Key pacing chains off EDT *delivery*, not off queueing.** `afterDelivery` books both the release and
  the next key's gap from the moment the engine actually has the press. The first cut pre-booked absolute
  slots (a cold scheduler ran three overdue tasks back to back, collapsing them into one instant); the
  second timed from queueing (an EDT behind by more than the hold delivers press and release in one drain,
  so the key is never observably down). A stalled EDT now stalls typing, which is the right direction.
- **Modifier latches are not paced, but they do share the queue.** Sticky Shift/Ctrl/Alt are a held state a
  native toggle owns — no dwell to invent, no release booked — but they go through `submitKey` so a latch
  cannot overtake keys committed before it. Cost: a toggle flipped *during* a burst waits for it to drain;
  with nothing in flight (the normal case) the pump runs it at once. With `pacing=false` the latch
  dispatches immediately too, since queueing only the latch is the same bug upside down.
- Kill switch: `-Drunelite.key.pacing=false` reverts to the back-to-back key shape.

## Review reconciliation (`c4aade89ab`)

An independent review found three gaps after the series landed. All three were real and all three are
fixed; each has a test that fails with its fix reverted.

1. **Viewport guard covered only the press point.** The *stated* harm (an invented item drag) is not
   reachable — the engine binds a drag to the widget under the press, and the press is guarded — but the
   invariant as documented was only true of the stream's first sample. Now bounded per sample.
2. **The hold was timed from queueing, not delivery.** Cosmetic in exactly the conditions it was for.
   Fixed; see the mechanics note above.
3. **Latches bypassed the key queue.** Narrow in practice (`onText` passes no modifiers, `onKeybind` bakes
   its mask into the event) but the ordering shouldn't be unspecified. Fixed.

## Gate status

- `./gradlew :client:test` — **green** (full suite; `JavaBridge*` run 3× for flakes, clean, re-run
  after the review fixes).
- `./gradlew :client:compileJava` — green. Shared contract **576 entries, unchanged** (two additive
  entries baselined: `JavaBridge#onTapMove(int,int)`, `FLAG runelite.key.pacing`).
- Android `:app:compileDebugKotlin` + `:app:externalNativeBuildDebug` — green.
- iOS `JVMLauncher.m` / `ViewController.m` — `clang -fsyntax-only` clean (17 warnings, all pre-existing).

## Device verification (2026-10-20)

Deployed with `DEV=<uuid> ios/dev-tools/remote-build.sh --with-jars` (this Mac is tethered directly; there
is no `deploy-remote.conf`, so the remote path does not apply). Build artifacts were checked to carry the
new code rather than a stale jar — `client-shaded.jar` holds `onTapMove` / `pumpKeys` / `afterDelivery` /
`streamMinX` and no `keepAlive`; `RuneMobile.debug.dylib` holds the `postTapMoveX:` selector and the
`onTapMove` JNI literal and no `postKeepAlive`. **In a Debug build the ObjC code lives in
`RuneMobile.debug.dylib`, not in the 89KB `RuneMobile` binary** — grep the dylib when verifying a deploy.

All four checks walked through on the real client and reported good:

1. **The persistent hover (item 3)** — the judgement call. Dropping the fabricated `(−1,−1)` move leaves a
   tapped entity lit until the next contact moves it. **This reads correctly on device.** The park was
   originally added for the "stuck on the stairs" hover bug, so the risk was that removing it would bring
   that back as a visible defect; it did not. Do not reinstate the fabricated move. If a lingering label
   ever does read badly, stop drawing the label.
2. **Taps land where the finger landed (item 2)**, inventory included — no item dragged itself to a
   neighbouring slot.
3. **Typing (item 4)** — paced keys read as instant; nothing dropped or reordered.
4. **Idle logout** — behaves like desktop RuneLite now the keepalive is gone.

## The predicted cost came due — and was paid as prescribed (2026-10-20)

Item 3 accepted a lingering hover label, and both docs said in advance: *if it ever reads badly, stop
drawing the label, don't move the cursor somewhere the finger never was.* It read badly, in the way
`parkPointer`'s javadoc had already named — **"a model that can light up again when a zoom re-projects it
under the last point."** Camera rotation is that case: it writes yaw/pitch targets directly and posts no
mouse events at all, so the tracked point stays exactly where the finger left it and the *world turns
underneath it*. The engine re-resolves that point every tick, so the label re-names itself while nothing is
touching the glass.

Paid as prescribed, not reverted: the engine's hover-option text is now off client-wide via
`Client.setMouseoverTextEnabled(false)`, asserted every frame from `HudVeneerBridge.preRender` (the flag is
the engine's — a relog or world hop can hand it back). The official mobile app shows no hover text either,
for the same reason: there is no hover on a touchscreen. Kill switch `-Drunelite.hover.text=true`.

**The fabricated `(−1,−1)` park stays gone.** That is the whole point: the label was the cost, and the cost
had a remedy that doesn't require lying about where the finger was.

## Next step

The series is complete. Everything is verified **on iOS**; Android verification was deliberately deferred
(2026-10-20) and is the only thing outstanding — see the first open risk below. Nothing depends on it.

## Open risks

- Every number in item 4 is a guess at human typing, not a measurement. If the engine or a plugin ever
  cares about the specific cadence, these are the knobs to revisit. The device pass confirms they *feel*
  right; it does not make them measurements.
- Keys overlap (holds outlast gaps), so 1–2 keys are down at once. Normal for a typist and fine for the
  gamepack's pressed-key array, but it is new behaviour for this client.
- **Item 2's Android path has never executed — deferred by choice, not an oversight.** Items 1, 3, 4 and
  the keepalive deletion are pure shared Java, so the iOS pass covers them; Android runs the same jar. But
  item 2's plumbing is Android-specific and structurally different: samples are queued as `IN_TAPMOVE` and
  drained on the **render thread**, coalesced to the latest per frame, where iOS passes every sample
  straight through on the main thread. So the two ports feed different sample densities, and only one of
  them has been seen working.

  Statically verified in lieu of a device (2026-10-20): the jar exports
  `public static void onTapMove(int, int)` and the C++ binds `"onTapMove", "(II)V"` — worth re-checking if
  either side is ever renamed, because `midOpt` returns null **silently** on a mismatch, so a typo would
  mean the Android stream simply never happens with nothing logged anywhere. `IN_TAPMOVE = 20` is distinct
  (the keepalive's old slot 11 stays vacant), coalescing folds only against its own kind, and both
  `:app:compileDebugKotlin` and `:app:externalNativeBuildDebug` are green.

  Degradation if something is still wrong: Android falls back to the pre-item-2 behaviour — one move at
  contact, no drag stream. A silent no-op, not a crash, and not a regression against what Android did
  before this work. When the Pixel is next out, the 5-minute pass is: tap in the inventory (the viewport
  guard is what stops a roll inventing an item drag), and tap-and-roll in the 3D scene. Disable dozing
  first (`adb shell dumpsys deviceidle disable`, `svc power stayon true`) or the adb socket drops.
