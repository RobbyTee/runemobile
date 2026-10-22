# Input fidelity — the synthetic stream stops inventing things

**Status:** all four items landed and **verified on device** (iPhone 17 Pro Max, iOS 27.2, Debug build,
2026-10-20): the four checks below were walked through on the real client and all read correctly.
**Items 1 and 2 were withdrawn on 2026-10-22** — the held press made a camera drag walk. See
[Items 1 and 2 withdrawn](#items-1-and-2-withdrawn-2026-10-22).
**Commits:** `d54da5f6f9`, `7b2ef910ed` (item 1) · `455a634bf0` (keepalive) · `e32bae44c2` (item 3) ·
`04193e7d01` (item 2) · `a48430708d` (item 4) · `f5908f7fc5` (coalescing) · `c4aade89ab` (review).

## The problem this series fixed

The bridge synthesizes AWT input from touch. Several parts of what it synthesized described things the user
never did — not approximations of real input, but fabrications with no referent:

| # | What was fabricated | Now |
|---|---|---|
| 1 | Every tap reported a **1ms hold**: press and release went out together at the lift. | **Withdrawn.** Press and release go out together at the lift again. |
| 2 | A tap was **one point**. The finger's travel was dropped; the engine saw a move at contact and nothing until the release. | **Withdrawn** with item 1: there is no press during the hold to drag. |
| 3 | Every tap ended with a `MOUSE_MOVED` to **(−1,−1)** — a point no pointer can occupy. | Park is `MOUSE_EXITED` alone, carrying the point the finger left from. |
| 4 | Keys went out at `t`, `t+1`, `t+2` in one EDT drain; a 30-char paste arrived inside **one game tick**. | Each key is really held (45–85ms) and really spaced (25–55ms press-to-press). |
| — | An **idle keepalive** posted activity the user never produced, defeating the engine's idle-logout. | Deleted from all three codebases. |

## Items 1 and 2 withdrawn (2026-10-22)

Item 1 rested on a premise that turned out to be false: that the engine turns a press/release pair into a
click. OSRS acts on the **press** alone. The gamepack's `mouseReleased` only clears the button state and
stores no time. So the press that went out when the settle gate accepted, while the finger was still on the
glass, acted at once. On the Pixel 7a, a camera drag that rested for a couple of frames before it moved walked
the character to the point the finger had landed on. A long-press did the left-click action before its menu
opened. The retire could not help, because the action was already sent by the time the drag was recognised.
The retire was a release with no click, plus consuming the next `MenuOptionClicked`.

The same premise broke tap feedback. The engine's `MenuOptionClicked` for an armed tap arrived while the tap
was still `ARMED`, so it was ignored. The tap then sat in `CLICKED` until the 10-frame timeout: the
`CLICKED timeout ... (unexpected)` line in the log.

A touch is not known to be a tap until it lifts, and the engine acts on the press, so the press has to wait
for the lift. The arm keeps its latency win: the gate still resolves the point while the finger is down, and
the lift clicks at once. Press, release and click now go out together at the lift, as they did before item 1.
Item 2 goes with it, because with no press during the hold there is nothing to drag. Removed: the retire, the
phantom-click suppression and `-Drunelite.touch.suppressClickFrames`. `JavaBridge.onTapMove` stays as an
inert entry point, because both shells still call it.

What this gives back: the engine sees a ~1ms hold again. Its release handler records no timestamp, so the
hold length was only ever visible to it as "button down" between frames.

`JavaBridgePressTimingTest` now pins press-at-lift. A contact that becomes a drag or a long-press after the
gate accepted posts nothing, and the engine's answer finds the tap in `CLICKED`. Five of its seven tests fail
against the previous bridge.

## What each change cost, and why it was paid

- **Item 3 — the hover persists.** The `(−1,−1)` move existed for a real reason: the engine does not clear
  its tracked position on `MOUSE_EXITED`, so a move to a point that picks nothing was the only way to end a
  hover. Dropping it means a tapped entity stays lit and can re-light when a zoom re-projects it. That is
  what a desktop client does — a mouse stays where you let go of it — so it is a regression only against
  the fabricated version of ourselves. **If the lingering label reads badly on device, the fix is to stop
  drawing the label, not to move the cursor somewhere the finger never was.**
- **Item 4 — the dwell is a stand-in, and says so.** iOS hands us committed text, never a key down or up,
  so there is no measurement to report. What was wrong before was not the missing measurement but the
  reported one that never happened. Ranges, not constants, because a constant dwell is its own fabrication.

## Mechanics worth knowing before touching this

- **`postPointer(q, e)`** is the single seam every positioned `MouseEvent` goes through. It records
  `lastPointerX/Y` so the park's exit can report a real point. *Add new pointer posts through it*, or the
  "we never name a coordinate the finger was not at" property quietly stops being true.
- **Nothing presses before the lift.** OSRS acts on the press, so any press posted while a contact can still
  become a drag or a long-press is an action the user may not have asked for. See the withdrawal above.
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

1. **Viewport guard covered only the press point** (item 2, since withdrawn). The *stated* harm (an invented item drag) is not
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

Items 3 and 4 and the keepalive deletion stand, verified on iOS. Items 1 and 2 were withdrawn after the
first Android pass (2026-10-22). Re-check on both phones that a camera drag which rests before it moves no
longer walks, and that a long-press opens its menu without acting first.

## Open risks

- Every number in item 4 is a guess at human typing, not a measurement. If the engine or a plugin ever
  cares about the specific cadence, these are the knobs to revisit. The device pass confirms they *feel*
  right; it does not make them measurements.
- Keys overlap (holds outlast gaps), so 1–2 keys are down at once. Normal for a typist and fine for the
  gamepack's pressed-key array, but it is new behaviour for this client.
- `JavaBridge.onTapMove` is inert, but both shells still send it every move sample of an armed contact
  (Android queues them as `IN_TAPMOVE`). Removing the calls touches both ports and the shared contract, so it
  was left for a change of its own.
